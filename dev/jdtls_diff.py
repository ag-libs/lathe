#!/usr/bin/env python3
"""
Differential tester: drive Lathe and jdtls over identical probe points on the same project and
report where they disagree, as triage candidates for Lathe's gap logs.

Advisory dev/ tooling, not a build/CI gate (see docs/planned/lathe-jdtls-differential-testing.md).
Reuses `lsp.LatheClient` and `jdtls.JdtlsClient` -- both LspClient presets -- so one comparison loop
drives both servers over the identical probe set.

It exercises the full comparable LSP surface:
  navigation   hover, definition, declaration, implementation, references, documentHighlight
  structure    documentSymbol, foldingRange, workspaceSymbol
  hierarchy    typeHierarchy (super+sub), callHierarchy (in+out)
  editing      completion (member/dot), signatureHelp, prepareRename, rename, codeAction
  analysis     diagnostics, semanticTokens
Run/test/debug and lathe.* commands are Lathe-only (no jdtls oracle) and are out of scope here.

Probe points are derived automatically from the source + Lathe's documentSymbol: every symbol's
identifier position drives the position methods; member-access sites drive completion; call sites
drive signatureHelp; file methods run once per file.

Prereq: the target project disables Lathe for jdtls's embedded Maven -- JdtlsClient passes
`-Dlathe.disabled=true` as a jdtls-only JVM arg, so no project change is required.

Usage:
    python3 dev/jdtls_diff.py <file.java> [<file.java> ...]
    python3 dev/jdtls_diff.py --methods hover,definition,references <file.java>
    python3 dev/jdtls_diff.py --out dev/jdtls-diff-report.md <dir-or-files>
"""

import argparse
import re
import sys
import traceback
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from lsp import LatheClient, find_workspace_root  # noqa: E402
from jdtls import JdtlsClient  # noqa: E402

# Expected, deliberate Lathe divergences -- not gaps.
KNOWN_DIFFERENCES = [
    "Lathe suppresses java.lang.Object members from completion by design.",
    "Lathe prefers reactor-origin symbols and ranks them first in completion.",
    "documentSymbol roots differ: jdtls emits a package symbol; Lathe starts at the type.",
    "diagnostics: Lathe adds unused private member/local warnings jdtls does not emit.",
    "formatting: different engines (Lathe google-java-format vs jdtls eclipse) -- never byte-equal.",
    "hover/typeHierarchy format: jdtls prints fully-qualified names; Lathe prints simple names.",
]


# ── Canonical forms ─────────────────────────────────────────────────────────

def _basename(entry: dict) -> str:
    return Path(entry.get("targetUri") or entry.get("uri") or "").name

def _line(entry: dict) -> int:
    rng = entry.get("targetSelectionRange") or entry.get("targetRange") or entry.get("range") or {}
    return rng.get("start", {}).get("line", -1)

def canon_locations(result) -> set:
    return {(_basename(e), _line(e)) for e in (result or [])}

def _normalize_signature(sig: str) -> str:
    """Strip formatting-only differences so real signature gaps stand out: drop package qualifiers
    and the declaring-type qualifier before a method name. What survives is substantive: varargs vs
    array, dropped type params, <init>, throws."""
    sig = re.sub(r"\b(?:[a-z][\w]*\.)+", "", sig)        # com.example. / java.lang.
    sig = re.sub(r"\b[A-Z]\w*\.(?=\w+\s*\()", "", sig)    # Owner.method( -> method(
    return sig.strip()

def canon_hover(result) -> str:
    if not result:
        return ""
    contents = result.get("contents")
    text = ""
    if isinstance(contents, dict):
        text = contents.get("value", "")
    elif isinstance(contents, list):
        for part in contents:
            if isinstance(part, dict) and part.get("language") == "java":
                text = part.get("value", "")
                break
        else:
            text = next((p.get("value", "") if isinstance(p, dict) else str(p)
                         for p in contents), "")
    elif isinstance(contents, str):
        text = contents
    for ln in text.splitlines():
        s = ln.strip().strip("`")
        if s and s != "java":
            return _normalize_signature(" ".join(s.split()))
    return ""

def _flatten_symbols(symbols) -> set:
    names = set()
    for s in symbols or []:
        names.add(s.get("name", "").split("(")[0].strip())
        names |= _flatten_symbols(s.get("children", []))
    return names

def canon_symbols(result) -> set:
    return _flatten_symbols(result)

def canon_folding(result) -> set:
    return {(r.get("startLine"), r.get("endLine")) for r in (result or [])}

def canon_semantic(result) -> int:
    data = (result or {}).get("data") or []
    return len(data) // 5  # SemanticTokens: 5 ints per token

def canon_diagnostics(result) -> set:
    return {(d.get("severity"), d.get("range", {}).get("start", {}).get("line"))
            for d in (result or [])}

def canon_completion(result) -> set:
    # jdtls appends an origin to labels ("Foo - com.pkg"); compare on the bare label.
    return {i.get("label", "").split(" - ")[0].strip() for i in (result or [])}

def canon_signature(result) -> str:
    if not result:
        return ""
    sigs = result.get("signatures") or []
    if not sigs:
        return ""
    active = result.get("activeSignature", 0) or 0
    sig = sigs[min(active, len(sigs) - 1)]
    label = sig.get("label", "")
    label = re.sub(r"\b(?:[a-z][\w]*\.)+", "", label)
    return " ".join(label.split())

def canon_prepare_rename(result) -> bool:
    return bool(result)


# ── Composite probes (multi-call sequences) ─────────────────────────────────

def probe_type_hierarchy(client, file, line, col) -> set:
    names = set()
    for it in client.prepare_type_hierarchy(file, line, col) or []:
        for s in _safe(client.type_hierarchy_supertypes, it):
            names.add(s.get("name"))
        for s in _safe(client.type_hierarchy_subtypes, it):
            names.add(s.get("name"))
    return names

def _chname(entry: dict) -> str:
    # jdtls decorates names with signatures ("foo() : void"); reduce to the bare identifier.
    return (entry or {}).get("name", "").split("(")[0].strip()

def probe_call_hierarchy(client, file, line, col) -> set:
    names = set()
    for it in client.prepare_call_hierarchy(file, line, col) or []:
        for c in _safe(client.call_hierarchy_incoming, it):
            names.add(_chname(c.get("from", {})))
        for c in _safe(client.call_hierarchy_outgoing, it):
            names.add(_chname(c.get("to", {})))
    return {n for n in names if n}

def rename_edit_count(client, file, line, col) -> int:
    edit = _safe1(client.rename, file, line, col, "latheProbeRenamed")
    if not edit:
        return 0
    n = sum(len(v) for v in (edit.get("changes") or {}).values())
    for dc in (edit.get("documentChanges") or []):
        n += len(dc.get("edits", []))
    return n

def probe_rename(client, file, line, col) -> bool:
    """Classify rename on whether it is offered at all. Exact edit counts aren't directly comparable
    (servers group WorkspaceEdits differently), so a boolean 'can rename here' is the solid signal --
    it cleanly surfaces positions where Lathe refuses a rename jdtls performs (types, constructors)."""
    return rename_edit_count(client, file, line, col) > 0

def _safe(fn, *a):
    try:
        return fn(*a) or []
    except Exception:
        return []

def _safe1(fn, *a):
    try:
        return fn(*a)
    except Exception:
        return None


# ── Method registry ─────────────────────────────────────────────────────────
# kind: file | position | dotcompletion | callsite | query
# gap:  True  -> divergences are gap candidates; False -> informational (expected-divergent)
# cap:  max probe points per file for this method (controls runtime on expensive methods)

SPECS = {
    # navigation
    "hover":            dict(kind="position", cap=40, gap=True,
                             call=lambda c, f, l, co: c.hover(f, l, co), norm=canon_hover),
    "definition":       dict(kind="position", cap=40, gap=True,
                             call=lambda c, f, l, co: c.definition(f, l, co), norm=canon_locations),
    "declaration":      dict(kind="position", cap=40, gap=True,
                             call=lambda c, f, l, co: c.declaration(f, l, co), norm=canon_locations),
    "implementation":   dict(kind="position", cap=12, gap=True,
                             call=lambda c, f, l, co: c.implementation(f, l, co), norm=canon_locations),
    "references":       dict(kind="position", cap=12, gap=True,
                             call=lambda c, f, l, co: c.references(f, l, co), norm=canon_locations),
    "documentHighlight": dict(kind="position", cap=25, gap=True,
                              call=lambda c, f, l, co: c.document_highlight(f, l, co), norm=canon_locations),
    # structure
    "documentSymbol":   dict(kind="file", gap=True,
                             call=lambda c, f: c.document_symbols(f), norm=canon_symbols),
    "foldingRange":     dict(kind="file", gap=True,
                             call=lambda c, f: c.folding_ranges(f), norm=canon_folding),
    "workspaceSymbol":  dict(kind="query", gap=True,
                             call=lambda c, q: c.workspace_symbol(q), norm=canon_symbols),
    # hierarchy
    "typeHierarchy":    dict(kind="position", cap=10, gap=True,
                             call=probe_type_hierarchy, norm=lambda x: x, composite=True),
    "callHierarchy":    dict(kind="position", cap=10, gap=True,
                             call=probe_call_hierarchy, norm=lambda x: x, composite=True),
    # editing
    "completion":       dict(kind="dotcompletion", cap=15, gap=True,
                             call=lambda c, f, l, co: c.completion(f, l, co), norm=canon_completion),
    "signatureHelp":    dict(kind="callsite", cap=15, gap=True,
                             call=lambda c, f, l, co: c.signature_help(f, l, co), norm=canon_signature),
    "prepareRename":    dict(kind="position", cap=25, gap=True,
                             call=lambda c, f, l, co: c.prepare_rename(f, l, co), norm=canon_prepare_rename),
    "rename":           dict(kind="position", cap=10, gap=True,
                             call=probe_rename, norm=lambda x: x, composite=True),
    # analysis
    "diagnostics":      dict(kind="file", gap=True,
                             call=lambda c, f: c.open(f), norm=canon_diagnostics),
    "semanticTokens":   dict(kind="file", gap=False,
                             call=lambda c, f: c.semantic_tokens(f), norm=canon_semantic),
}

DEFAULT_METHODS = list(SPECS.keys())
EXPENSIVE = {"references", "implementation", "typeHierarchy", "callHierarchy", "rename"}


# ── Probe derivation ─────────────────────────────────────────────────────────

def _positions(symbols, acc):
    for s in symbols or []:
        rng = s.get("selectionRange") or s.get("range") or {}
        start = rng.get("start")
        if start:
            acc.append((s.get("name", "?"), start["line"], start["character"]))
        _positions(s.get("children", []), acc)
    return acc

def _dot_sites(text: str, cap: int):
    """Member-access completion points: the position right after `<ident>.`.
    Skips import/package lines, where the two servers use intentionally different package-completion
    models (Lathe offers the next simple segment; jdtls offers fully-qualified sub-packages)."""
    lines = text.splitlines()
    sites = []
    for m in re.finditer(r"[A-Za-z0-9_)\]]\.\s*([A-Za-z_]\w*)", text):
        line = text.count("\n", 0, m.start(1))
        if line < len(lines) and lines[line].lstrip().startswith(("import ", "package ")):
            continue
        col = m.start(1) - (text.rfind("\n", 0, m.start(1)) + 1)
        sites.append((f".{m.group(1)}", line, col))
    return _dedup(sites, cap)

def _call_sites(text: str, cap: int):
    """signatureHelp points: the position just inside `methodName(`."""
    sites = []
    for m in re.finditer(r"\b([A-Za-z_]\w*)\(", text):
        pos = m.end()  # just after '('
        line = text.count("\n", 0, pos)
        col = pos - (text.rfind("\n", 0, pos) + 1)
        sites.append((f"{m.group(1)}(", line, col))
    return _dedup(sites, cap)

def _dedup(sites, cap):
    seen, out = set(), []
    for name, line, col in sites:
        if (line, col) in seen:
            continue
        seen.add((line, col))
        out.append((name, line, col))
        if len(out) >= cap:
            break
    return out


# ── Classification ──────────────────────────────────────────────────────────

def classify(lathe_val, jdtls_val) -> str:
    le, je = not lathe_val, not jdtls_val
    if le and je:
        return "both_empty"
    if le and not je:
        return "lathe_missing"   # jdtls found it, Lathe did not -> gap candidate
    if je and not le:
        return "jdtls_missing"   # Lathe found it, jdtls did not
    return "agree" if lathe_val == jdtls_val else "differ"


def _run_method(method, spec, lathe, jdt, file, text, symbols, rows):
    kind = spec["kind"]
    norm = spec["norm"]
    call = spec["call"]
    cap = spec.get("cap", 9999)

    def record(sym, line, col, lv, jv):
        rows.append({"method": method, "file": file, "line": line + 1, "col": col + 1,
                     "symbol": sym, "status": classify(lv, jv), "lathe": lv, "jdtls": jv,
                     "gap": spec["gap"]})

    if kind == "file":
        lv = norm(_safe1(call, lathe, file))
        jv = norm(_safe1(call, jdt, file))
        record("<file>", -1, -1, lv, jv)
        return

    if kind == "query":
        for q in _queries(symbols):
            lv = norm(_safe1(call, lathe, q))
            jv = norm(_safe1(call, jdt, q))
            record(f"?{q}", -1, -1, lv, jv)
        return

    if kind == "dotcompletion":
        probes = _dot_sites(text, cap)
    elif kind == "callsite":
        probes = _call_sites(text, cap)
    else:  # position
        probes = _dedup(_positions(symbols, []), cap)

    for sym, line, col in probes:
        lv = norm(_safe1(call, lathe, file, line, col))
        jv = norm(_safe1(call, jdt, file, line, col))
        record(sym, line, col, lv, jv)


def _queries(symbols):
    names = sorted(_flatten_symbols(symbols))
    picks = [n for n in names if n and n[0].isupper()][:4]        # type names
    humps = ["".join(c for c in n if c.isupper()) for n in picks]  # CamelHump queries
    return list(dict.fromkeys(picks + [h for h in humps if len(h) >= 2]))


# ── Report ─────────────────────────────────────────────────────────────────

GAP_STATUSES = ("lathe_missing", "differ")

def _fmt(val) -> str:
    if isinstance(val, (set, frozenset)):
        items = sorted(map(str, val))
        shown = ", ".join(items[:8])
        return (shown + (f" … (+{len(items)-8})" if len(items) > 8 else "")) or "∅"
    return str(val) if val or val == 0 else "∅"

def write_report(rows, out: Path, files):
    by_method = {}
    for r in rows:
        by_method.setdefault(r["method"], Counter())[r["status"]] += 1

    L = ["# Lathe vs jdtls — differential report", ""]
    L.append(f"Probed **{len(files)}** file(s) across **{len(SPECS)}** capabilities; "
             f"**{len(rows)}** comparisons.")
    L.append("")
    L.append("- `lathe_missing` — jdtls returned a result Lathe did not → **gap candidate**")
    L.append("- `differ` — both returned results that disagree → inspect")
    L.append("- `jdtls_missing` — Lathe returned more than jdtls (often Lathe being richer)")
    L.append("")
    L.append("## Agreement by capability")
    L.append("")
    L.append("| capability | agree | both_empty | lathe_missing | differ | jdtls_missing | kind |")
    L.append("|---|---|---|---|---|---|---|")
    for method in SPECS:
        c = by_method.get(method)
        if not c:
            continue
        tag = "gap" if SPECS[method]["gap"] else "info"
        L.append(f"| {method} | {c['agree']} | {c['both_empty']} | **{c['lathe_missing']}** "
                 f"| {c['differ']} | {c['jdtls_missing']} | {tag} |")
    L.append("")

    gaps = [r for r in rows if r["gap"] and r["status"] in GAP_STATUSES]
    L.append(f"## Gap candidates ({len(gaps)})")
    L.append("")
    if not gaps:
        L.append("_None._")
    for method in SPECS:
        mgaps = [r for r in gaps if r["method"] == method]
        if not mgaps:
            continue
        L.append(f"### {method} ({len(mgaps)})")
        L.append("")
        for r in mgaps[:30]:
            loc = f"{Path(r['file']).name}:{r['line']}:{r['col']}" if r["line"] > 0 else Path(r['file']).name
            L.append(f"- **{r['symbol']}** @ {loc} ({r['status']})")
            L.append(f"  - lathe: `{_fmt(r['lathe'])}`")
            L.append(f"  - jdtls: `{_fmt(r['jdtls'])}`")
        if len(mgaps) > 30:
            L.append(f"  - … and {len(mgaps)-30} more")
        L.append("")

    L.append("## Known deliberate differences (not gaps)")
    L.append("")
    L += [f"- {kd}" for kd in KNOWN_DIFFERENCES]
    L.append("")
    out.write_text("\n".join(L))
    return gaps


# ── CLI ──────────────────────────────────────────────────────────────────────

def _expand(paths):
    files = []
    for p in paths:
        pp = Path(p).resolve()
        if pp.is_dir():
            files.extend(sorted(pp.rglob("*.java")))
        else:
            files.append(pp)
    return [f for f in files if f.name != "module-info.java"]

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("paths", nargs="+", help="Java file(s) or dir(s) to probe.")
    ap.add_argument("--methods", default=",".join(DEFAULT_METHODS))
    ap.add_argument("--out", default="dev/jdtls-diff-report.md")
    ap.add_argument("--max-files", type=int, default=0, help="cap number of files (0 = all)")
    args = ap.parse_args()

    files = _expand(args.paths)
    if args.max_files:
        files = files[:args.max_files]
    methods = [m.strip() for m in args.methods.split(",") if m.strip() in SPECS]
    root = find_workspace_root(files[0])
    print(f"workspace: {root}\nmethods:   {methods}\nfiles:     {len(files)}", file=sys.stderr)

    rows = []
    with LatheClient.start(root) as lathe, JdtlsClient.start(root) as jdt:
        print("both servers ready; warming Lathe index ...", file=sys.stderr)
        _warm(lathe, jdt, files[0])
        for i, f in enumerate(files, 1):
            print(f"[{i}/{len(files)}] {f.name}", file=sys.stderr)
            try:
                lathe.open(f)
                jdt.open(f)
                symbols = lathe.document_symbols(f)
                text = f.read_text()
                for m in methods:
                    _run_method(m, SPECS[m], lathe, jdt, f, text, symbols, rows)
            except Exception:
                print(f"  ! {f.name} failed:\n{traceback.format_exc()}", file=sys.stderr)

    gaps = write_report(rows, Path(args.out), files)
    print(f"\nwrote {args.out}  ({len(gaps)} gap candidates / {len(rows)} comparisons)", file=sys.stderr)


def _warm(lathe, jdt, file):
    """First references call blocks until Lathe's reference index is built (~seconds); without this
    warmup early reference/implementation probes would read as false 'lathe_missing'."""
    try:
        lathe.open(file)
        syms = _positions(lathe.document_symbols(file), [])
        if syms:
            _, line, col = syms[0]
            lathe.references(file, line, col)
    except Exception:
        pass


if __name__ == "__main__":
    main()
