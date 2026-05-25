#!/usr/bin/env python3
"""
Unified UX probe for the Lathe completion engine.

Runs multiple scenario suites against real Java projects to surface
completion quality and presentation issues.  Re-run at will after any
engine change to track improvement.

Usage:
    python3 dev/probe.py                            # all suites, all workspaces
    python3 dev/probe.py --workspace helidon
    python3 dev/probe.py --workspace dropwizard
    python3 dev/probe.py --suite ux
    python3 dev/probe.py --suite ux,type_ref
    python3 dev/probe.py --verbose

Suites:
    ux         13 specific positions with expected labels (chains, nesting, statics)
    present    Presentation quality at reliably-resolving positions (fields, ordering)
    type_ref   Type-reference injection at class fields, method bodies
    blank      Blank and short-prefix behaviour at class/method body
    filter     Prefix filter accuracy — no stray items returned
    member        Broad scan across real files (auto-detected positions, perf data)
    member_inject Inject `receiver.` at class/method/ctor body positions, cursor after dot
    multi_tree    Open main-tree and test-tree files from the same module in one session
               (regression test for the wrong-worker-per-source-tree crash)
"""

import argparse
import re
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

sys.path.insert(0, str(Path(__file__).parent))
from lsp import LatheClient, find_workspace_root

H = "/home/ag-libs/git/helidon"
D = "/home/ag-libs/git/dropwizard"

MONGO   = f"{H}/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClient.java"
ASF     = f"{D}/dropwizard-core/src/main/java/io/dropwizard/core/server/AbstractServerFactory.java"
CVT     = f"{D}/dropwizard-jersey/src/test/java/io/dropwizard/jersey/validation/ConstraintViolationExceptionMapperTest.java"
DRC     = f"{D}/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java"
DRCTEST = f"{D}/dropwizard-jersey/src/test/java/io/dropwizard/jersey/DropwizardResourceConfigTest.java"
CRON    = f"{H}/scheduling/src/main/java/io/helidon/scheduling/CronTask.java"
CODEGEN = f"{H}/service/codegen/src/main/java/io/helidon/service/codegen/ServiceDescriptorCodegen.java"

# ─── Data model ──────────────────────────────────────────────────────────────

MakeContentFn = Callable[[str], tuple[str, int, int]]


@dataclass
class Probe:
    """A single completion scenario."""
    suite: str
    file: str
    description: str
    expected: list[str] = field(default_factory=list)
    line: int = -1
    col: int = -1
    # For injected probes: given original content, return (new_content, line, col)
    make_content: MakeContentFn | None = None
    # All returned items must start with this prefix (case-insensitive)
    prefix_filter: str | None = None
    # Fail if fewer than this many items are returned
    min_count: int = 0


@dataclass
class ProbeResult:
    suite: str
    description: str
    status: str  # pass | fail | info | error | timeout | open-failed
    count: int = 0
    ms: float = 0.0
    items: list[dict] = field(default_factory=list)
    issues: list[str] = field(default_factory=list)


# ─── Content injection helpers ────────────────────────────────────────────────

def _insert_line(line_idx: int, indent: int, prefix: str) -> MakeContentFn:
    """Insert `<indent><prefix>` at line_idx; cursor lands after prefix."""
    def fn(content: str) -> tuple[str, int, int]:
        lines = content.splitlines()
        lines.insert(line_idx, " " * indent + prefix)
        return "\n".join(lines), line_idx, indent + len(prefix)
    return fn


def _member_access(line_idx: int, indent: int, receiver: str) -> MakeContentFn:
    """Inject `<indent><receiver>.` at line_idx; cursor lands after the dot (empty member prefix)."""
    return _insert_line(line_idx, indent, receiver + ".")


def _type_ref_first_method(prefix: str) -> MakeContentFn:
    """Inject `<prefix> _probe;` at the start of the first method body found."""
    def fn(content: str) -> tuple[str, int, int]:
        lines = content.splitlines()
        for i, ln in enumerate(lines):
            s = ln.strip()
            if s.endswith("{") and not s.startswith("//") and i + 1 < len(lines):
                indent = len(ln) - len(ln.lstrip())
                lines.insert(i + 1, " " * (indent + 4) + prefix + " _probe;")
                return "\n".join(lines), i + 1, indent + 4 + len(prefix)
        raise RuntimeError("no method body found")
    return fn


# ─── Suite definitions ────────────────────────────────────────────────────────

UX_PROBES: list[Probe] = [
    # AbstractServerFactory — member access scenarios
    Probe("ux", ASF, "ASF handler.getServletContext — simple field member access",
          expected=["getServletContext"], line=577, col=16),
    Probe("ux", ASF, "ASF getServletContext().setAttribute — method-call receiver",
          expected=["setAttribute"], line=577, col=31),
    Probe("ux", ASF, "ASF addFilter()↵.setInitParameter — cross-line chained call",
          expected=["setInitParameter"], line=583, col=17),
    Probe("ux", ASF, "ASF bufferedReader↵.lines — cross-line simple-name receiver",
          expected=["lines", "readLine"], line=727, col=25),
    Probe("ux", ASF, "ASF instrumented.setServer — field-result member access",
          expected=["setServer", "setHandler"], line=623, col=21),
    Probe("ux", ASF, "ASF new LogbackAccessR... — constructor type from type index",
          expected=["LogbackAccessRequestLogFactory"], line=322, col=29),
    # ConstraintViolationExceptionMapperTest — fluent assertions
    Probe("ux", CVT, "CVT assertThat(getStatus()).isEqualTo — nested call receiver",
          expected=["isEqualTo"], line=67, col=40),
    Probe("ux", CVT, "CVT readEntity(String.class)).isEqualTo — generic-arg receiver",
          expected=["isEqualTo"], line=68, col=53),
    Probe("ux", CVT, "CVT target().request()↵.post — multi-hop cross-line chain",
          expected=["post", "get"], line=66, col=17),
    # MongoDbClient — builder chains and static access
    # line 54 (0-based) = "        ConnectionString connectionString = new ConnectionString(config.url());"
    # col 72 = start of "url" after "config."
    Probe("ux", MONGO, "MONGO config.url — member of method param type",
          expected=["url"], line=54, col=72),
    Probe("ux", MONGO, "MONGO builder()↵.applyConnectionString — cross-line builder",
          expected=["applyConnectionString"], line=57, col=17),
    # line 71 (0-based) = "        this.client = MongoClients.create(settingsBuilder.build());"
    # col 35 = start of "create" after "MongoClients."
    # col 58 = start of "build" after "settingsBuilder."
    Probe("ux", MONGO, "MONGO settingsBuilder.build — builder result in arg position",
          expected=["build"], line=71, col=58),
    Probe("ux", MONGO, "MONGO MongoClients.create — static factory method",
          expected=["create"], line=71, col=35),
]

# Presentation suite: fixed positions where resolution is known to work.
# Checks item field quality (filterText, textEdit, detail, sort order) not which items appear.
# All positions are in MongoDbClient so the file is shared with the ux suite.
#
# line 57 (0-based) = "                .applyConnectionString(connectionString);"  → col 17 = "a"
#   receiver: MongoClientSettings.Builder  (large, well-typed, many real methods)
# line 59 (0-based) = "        String dbName = connectionString.getDatabase();"  → col 41 = "g"
#   receiver: com.mongodb.ConnectionString  (library type, resolved via cached analysis)
# line 68 (0-based) = "            settingsBuilder.credential(credentials);"  → col 28 = "c"
#   receiver: MongoClientSettings.Builder  (same type, different method, tests stability)
PRESENT_PROBES: list[Probe] = [
    Probe("present", MONGO, "PRESENT builder chain — item field quality",
          line=57, col=17, min_count=5),
    Probe("present", MONGO, "PRESENT connectionString members — item field quality",
          line=59, col=41, min_count=3),
    Probe("present", MONGO, "PRESENT settingsBuilder.credential — sort order and fields",
          line=68, col=28, min_count=5),
]

TYPE_REF_PROBES: list[Probe] = [
    # CronTask.java — type-index prefix probes via method-body injection
    *(Probe("type_ref", CRON, f"CronTask type-ref prefix '{p}'",
            expected=[p[:4]], min_count=1,
            make_content=_type_ref_first_method(p))
      for p in ["Immut", "Schedul", "Execut"]),
    # MongoDbClient — class-body field vs. local variable (cursor after type prefix)
    Probe("type_ref", MONGO, "MongoDbClient class-field 'MongoCli'",
          expected=["MongoCl"], min_count=1,
          make_content=_insert_line(38, 4, "MongoCli")),
    Probe("type_ref", MONGO, "MongoDbClient local-var 'MongoCli'",
          expected=["MongoCl"], min_count=1,
          make_content=_insert_line(99, 8, "MongoCli")),
    # ServiceDescriptorCodegen — helidon-internal types via method-body injection
    *(Probe("type_ref", CODEGEN, f"Codegen type-ref prefix '{p}'",
            expected=[p[:4]], min_count=1,
            make_content=_type_ref_first_method(p))
      for p in ["TypeNam", "ClassMod", "Annot"]),
]

BLANK_PROBES: list[Probe] = [
    # Empty prefix — server guard should return 0 items; reported as INFO (no assertion)
    Probe("blank", MONGO, "class body — empty prefix",
          make_content=_insert_line(38, 4, "")),
    Probe("blank", MONGO, "method body — empty prefix",
          make_content=_insert_line(99, 8, "")),
    # Non-empty prefix — expect at least 1 result
    Probe("blank", MONGO, "class body — prefix 'Mon'",
          make_content=_insert_line(38, 4, "Mon"), min_count=1),
    Probe("blank", MONGO, "method body — prefix 'Mon'",
          make_content=_insert_line(99, 8, "Mon"), min_count=1),
    Probe("blank", MONGO, "method body — prefix 'Mongo'",
          make_content=_insert_line(99, 8, "Mongo"), min_count=1),
]

# Member-access injection suite: insert `receiver.` at class-body and method/ctor-body
# positions and request completion at the empty-prefix member-access point.
#
# MongoDbClient (0-based line refs):
#   line 38  class body between field declarations  indent=4
#   line 60  ctor body, after connectionString is declared at line 54  indent=8
#   line 99  method body (execute()), no local vars  indent=8
#
# Unknown-receiver probes (a.) report as INFO — no assertion on count or labels,
# just verify the server does not crash and we can observe fallback behaviour.
# Known-receiver probe (connectionString.) asserts specific members and min count.
MEMBER_INJECT_PROBES: list[Probe] = [
    Probe("member_inject", MONGO, "MONGO class body 'a.' — unknown receiver, observe fallback",
          make_content=_member_access(38, 4, "a")),
    Probe("member_inject", MONGO, "MONGO method body 'a.' — unknown receiver, observe fallback",
          make_content=_member_access(99, 8, "a")),
    Probe("member_inject", MONGO, "MONGO ctor body 'connectionString.' — local var in scope",
          expected=["getDatabase", "getUsername"], min_count=3,
          make_content=_member_access(60, 8, "connectionString")),
]

# Multi-tree suite: open test-tree and main-tree files from the same Maven module
# (dropwizard-jersey) inside one server session.
# The test file is listed first so it creates the ModuleWorker; the main file is
# opened second.  Under the old code (worker keyed by moduleDir) the main-tree
# compile crashes with "no source root" because the cached worker only knows the
# test-tree source root.  With the fix (keyed by latheClassesDir) both get their
# own worker and both must return ≥1 item.
#
# Positions (0-based):
#   DRCTEST line 47 col 22 → "rc.getClasses()" — cursor on getClasses
#   DRC     line 70 col 89 → "Clock.defaultClock()" — cursor on defaultClock
MULTI_TREE_PROBES: list[Probe] = [
    Probe("multi_tree", DRCTEST,
          "jersey TEST tree — rc.getClasses (opened first, creates module worker)",
          expected=["getClasses"], line=47, col=22, min_count=1),
    Probe("multi_tree", DRC,
          "jersey MAIN tree — Clock.defaultClock (opened second, must not inherit test worker)",
          expected=["defaultClock"], line=70, col=89, min_count=1),
]

FILTER_PROBES: list[Probe] = [
    *(Probe("filter", MONGO, f"class body '{p}' — no stray items",
            make_content=_insert_line(38, 4, p), prefix_filter=p)
      for p in ["Mon", "Map", "M"]),
    *(Probe("filter", MONGO, f"method body '{p}' — no stray items",
            make_content=_insert_line(99, 8, p), prefix_filter=p)
      for p in ["Mon", "M", "mon"]),
]

MEMBER_FILES = [
    f"{H}/scheduling/src/main/java/io/helidon/scheduling/TaskManagerImpl.java",
    f"{H}/scheduling/src/main/java/io/helidon/scheduling/FixedRateInvocation.java",
    f"{H}/scheduling/src/main/java/io/helidon/scheduling/CronTask.java",
    f"{H}/security/security/src/main/java/io/helidon/security/SecurityImpl.java",
    f"{H}/security/security/src/main/java/io/helidon/security/AuthenticationResponse.java",
    f"{H}/webserver/webserver/src/main/java/io/helidon/webserver/ConnectionHandler.java",
    f"{D}/dropwizard-core/src/main/java/io/dropwizard/core/Application.java",
    f"{D}/dropwizard-core/src/main/java/io/dropwizard/core/cli/Cli.java",
    f"{D}/dropwizard-core/src/main/java/io/dropwizard/core/server/DefaultServerFactory.java",
    f"{D}/dropwizard-core/src/main/java/io/dropwizard/core/server/AbstractServerFactory.java",
    f"{D}/dropwizard-core/src/main/java/io/dropwizard/core/setup/Bootstrap.java",
    f"{D}/dropwizard-core/src/main/java/io/dropwizard/core/setup/Environment.java",
    f"{D}/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java",
    f"{D}/dropwizard-testing/src/main/java/io/dropwizard/testing/DropwizardTestSupport.java",
]

_SKIP_RE = re.compile(r"^\s*(//|/\*|\*|import |package |@)")
_MEMBER_RE = re.compile(r"[A-Za-z_$0-9)]+\.([A-Za-z_$][A-Za-z0-9_$]*)")
_ARG_RE = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*\(([A-Za-z_$][A-Za-z0-9_$]+)")


def _auto_member_probes() -> list[Probe]:
    probes: list[Probe] = []
    for path in MEMBER_FILES:
        p = Path(path)
        if not p.exists():
            continue
        lines = p.read_text().splitlines()
        tag = f"{p.parent.name}/{p.name}"
        seen: set[str] = set()
        for i, raw in enumerate(lines):
            if _SKIP_RE.match(raw):
                continue
            if "member" not in seen:
                m = _MEMBER_RE.search(raw)
                if m:
                    probes.append(Probe("member", path,
                                        f"{tag} member [{raw.strip()[:45]}]",
                                        line=i, col=m.start(1), min_count=1))
                    seen.add("member")
            if "arg" not in seen:
                m = _ARG_RE.search(raw)
                if m:
                    probes.append(Probe("member", path,
                                        f"{tag} arg [{raw.strip()[:45]}]",
                                        line=i, col=m.start(1), min_count=1))
                    seen.add("arg")
            if len(seen) == 2:
                break
    return probes


# ─── Runner ──────────────────────────────────────────────────────────────────

def _check_expected(items: list[dict], expected: list[str]) -> list[str]:
    labels = [it.get("label", "") for it in items]
    return [e for e in expected if not any(lbl.startswith(e) for lbl in labels)]


def _check_filter(items: list[dict], prefix: str) -> list[str]:
    return [it.get("label", "") for it in items
            if not it.get("label", "").lower().startswith(prefix.lower())]


_OBJ_UTILITY = frozenset(["toString", "equals", "hashCode", "getClass", "clone"])
_OBJ_BOILER  = frozenset(["wait", "notify", "notifyAll", "finalize"])

def _detect_presentation(desc: str, items: list[dict]) -> list[str]:
    """
    Check LSP field quality of returned completion items.  Reports one summary
    line per category rather than one line per item to keep output scannable.
    """
    if not items:
        return []

    missing_filter: list[str] = []   # method item, no filterText
    wrong_filter:   list[str] = []   # filterText includes "("
    sig_insert:     list[str] = []   # method-with-params inserts full "name(Type)" label
    no_textedit:    int       = 0    # items without textEdit
    missing_detail: list[str] = []   # method/field without detail
    wrong_kind:     list[str] = []   # method/field missing kind

    for it in items[:15]:
        label  = it.get("label", "")
        ft     = it.get("filterText") or ""
        insert = it.get("insertText") or ""
        te     = it.get("textEdit")
        kind   = it.get("kind")       # 2=Method, 5=Field, 6=Variable
        detail = it.get("detail") or ""

        is_method  = "(" in label
        bare       = label.split("(")[0] if is_method else label
        has_params = is_method and not label.endswith("()")

        # filterText must be the bare symbol name for method items
        if is_method:
            if not ft:
                missing_filter.append(bare)
            elif "(" in ft:
                wrong_filter.append(f"{bare}→{ft!r}")

        # methods with params: effective insertText must not be the full signature label
        if has_params:
            te_text  = te.get("newText", "") if isinstance(te, dict) else ""
            effective = te_text or insert
            if effective and effective == label:
                sig_insert.append(bare)

        # textEdit absence — prefix replacement relies on client heuristics
        if not te:
            no_textedit += 1

        # detail (return type / field type) expected for methods and fields
        if kind in (2, 5) and not detail:
            missing_detail.append(bare)

        # kind should be set on all items
        if kind is None:
            wrong_kind.append(bare)

    issues: list[str] = []
    sample = items[:15]
    n = len(sample)

    if missing_filter:
        issues.append(f"filterText missing on {len(missing_filter)}/{n} method items: "
                      f"{missing_filter[:4]}")
    if wrong_filter:
        issues.append(f"filterText contains signature: {wrong_filter[:3]}")
    if sig_insert:
        issues.append(f"method-with-params inserts full signature label: {sig_insert[:3]}")
    if no_textedit:
        issues.append(f"textEdit absent on {no_textedit}/{n} items — prefix replacement "
                      f"relies on client heuristics")
    if missing_detail:
        issues.append(f"detail (return/field type) missing on {len(missing_detail)} items: "
                      f"{missing_detail[:4]}")
    if wrong_kind:
        issues.append(f"kind missing on items: {wrong_kind[:4]}")

    # Sort-order check: Object utility / boilerplate must not dominate the top 5
    top5 = [it.get("label", "").split("(")[0] for it in items[:5]]
    obj_top5 = [n for n in top5 if n in _OBJ_UTILITY | _OBJ_BOILER]
    if obj_top5:
        issues.append(f"Object utility/boilerplate in top 5 items: {obj_top5}")

    # Overload consistency: same bare name → same filterText value
    ft_by_name: dict[str, set[str]] = {}
    for it in items[:15]:
        label = it.get("label", "")
        if "(" not in label:
            continue
        bare = label.split("(")[0]
        ft = it.get("filterText") or ""
        ft_by_name.setdefault(bare, set()).add(ft)
    inconsistent = [name for name, fts in ft_by_name.items() if len(fts) > 1]
    if inconsistent:
        issues.append(f"overloads have inconsistent filterText: {inconsistent[:3]}")

    return issues


def _reset_file(c: LatheClient, probe: Probe,
                originals: dict[str, str], versions: dict[str, int]) -> None:
    if probe.make_content is not None:
        versions[probe.file] += 1
        c.change(probe.file, originals[probe.file], versions[probe.file])


def _run_one(c: LatheClient, probe: Probe,
             originals: dict[str, str], versions: dict[str, int]) -> ProbeResult:
    path = probe.file

    if probe.make_content is not None:
        try:
            content, line, col = probe.make_content(originals[path])
        except Exception as e:
            return ProbeResult(probe.suite, probe.description, "error", issues=[str(e)])
        versions[path] += 1
        c.change(path, content, versions[path])
        time.sleep(0.05)
    else:
        line, col = probe.line, probe.col

    t0 = time.perf_counter()
    try:
        items = c.completion(path, line, col)
        ms = (time.perf_counter() - t0) * 1000
    except TimeoutError:
        _reset_file(c, probe, originals, versions)
        return ProbeResult(probe.suite, probe.description, "timeout")
    except RuntimeError as e:
        _reset_file(c, probe, originals, versions)
        return ProbeResult(probe.suite, probe.description, "error", issues=[str(e)[:100]])

    _reset_file(c, probe, originals, versions)

    issues = _detect_presentation(probe.description, items)
    status = "pass"

    if probe.prefix_filter is not None:
        bad = _check_filter(items, probe.prefix_filter)
        if bad:
            issues.append(f"filter mismatch {len(bad)} items: {bad[:5]}")
            status = "fail"
    elif probe.expected:
        missing = _check_expected(items, probe.expected)
        if missing:
            issues.append(f"missing expected: {missing}")
            status = "fail"

    if probe.min_count > 0 and len(items) < probe.min_count:
        issues.append(f"too few items: got {len(items)}, want ≥{probe.min_count}")
        status = "fail"

    # Downgrade to INFO when there are no assertions at all
    if (status == "pass"
            and not probe.expected
            and probe.prefix_filter is None
            and probe.min_count == 0):
        status = "info"

    return ProbeResult(probe.suite, probe.description, status,
                       count=len(items), ms=ms, items=items, issues=issues)


LOG_FILE = Path("/tmp/lathe_probe.log")


def run_probes(probes: list[Probe], verbose: bool = False,
               show_logs: bool = False) -> list[ProbeResult]:
    alive = [p for p in probes if Path(p.file).exists()]
    if not alive:
        return []

    groups: dict[str, list[Probe]] = {}
    for probe in alive:
        try:
            root = str(find_workspace_root(Path(probe.file)))
        except RuntimeError:
            root = str(Path(probe.file).parent)
        groups.setdefault(root, []).append(probe)

    results: list[ProbeResult] = []
    all_log_lines: list[str] = []

    for workspace, group in groups.items():
        print(f"\nworkspace: {workspace}", flush=True)
        unique_files = list(dict.fromkeys(p.file for p in group))

        with LatheClient.start(workspace, debug=True) as c:
            opened: set[str] = set()
            originals: dict[str, str] = {}
            versions: dict[str, int] = {}

            for path in unique_files:
                try:
                    print(f"  opening {Path(path).name} ...", flush=True, end=" ")
                    c.open(path, timeout=40)
                    opened.add(path)
                    originals[path] = Path(path).read_text()
                    versions[path] = 1
                    print("ok", flush=True)
                except Exception as e:
                    print(f"FAILED: {e}", flush=True)

            for probe in group:
                if probe.file not in opened:
                    results.append(
                        ProbeResult(probe.suite, probe.description, "open-failed"))
                    continue
                r = _run_one(c, probe, originals, versions)
                results.append(r)
                _print_line(r, verbose)

        all_log_lines.extend(c.stderr_lines)
        if show_logs:
            _print_logs(c.stderr_lines, workspace)

    LOG_FILE.write_text("\n".join(all_log_lines))
    print(f"\n(full server log → {LOG_FILE})", flush=True)

    return results


_LOG_KW = ("inject prefix", "sentinelCtx", "resolve receiver", "type-index",
           "typeRef", "proposals", "WARN", "ERROR")


def _print_logs(lines: list[str], workspace: str) -> None:
    relevant = [l for l in lines if any(kw in l for kw in _LOG_KW)]
    print(f"\n--- server log: {Path(workspace).name}"
          f" ({len(relevant)} relevant / {len(lines)} total) ---")
    for l in relevant:
        print(" ", l)


# ─── Display ─────────────────────────────────────────────────────────────────

_ICON = {"pass": "PASS", "fail": "FAIL", "info": "INFO",
         "error": "ERR!", "timeout": "TIME", "open-failed": "SKIP"}


def _print_line(r: ProbeResult, verbose: bool) -> None:
    icon = _ICON.get(r.status, r.status[:4].upper())
    ms_s = f" {r.ms:.0f}ms" if r.ms else ""
    print(f"  [{icon}] {r.description}  ({r.count}{ms_s})", flush=True)
    for iss in r.issues:
        print(f"         ! {iss}", flush=True)
    if verbose and r.items:
        for it in r.items[:6]:
            label  = it.get("label", "?")
            detail = it.get("detail") or ""
            insert = it.get("insertText") or ""
            print(f"           {label!r:<40s} detail={detail!r}  insert={insert!r}")
        if len(r.items) > 6:
            print(f"           … {len(r.items) - 6} more")


def print_summary(results: list[ProbeResult]) -> None:
    by_suite: dict[str, list[ProbeResult]] = {}
    for r in results:
        by_suite.setdefault(r.suite, []).append(r)

    print("\n" + "=" * 72)
    print("SUMMARY")
    print("=" * 72)

    total_pass = total_fail = total_err = 0
    all_issues: list[str] = []

    for suite, rs in by_suite.items():
        n_pass = sum(1 for r in rs if r.status == "pass")
        n_fail = sum(1 for r in rs if r.status == "fail")
        n_err  = sum(1 for r in rs if r.status in ("error", "timeout", "open-failed"))
        n_info = sum(1 for r in rs if r.status == "info")
        ok_ms  = [r.ms for r in rs if r.status in ("pass", "info") and r.ms > 0]
        avg_s  = f"  avg={sum(ok_ms)/len(ok_ms):.0f}ms" if ok_ms else ""
        info_s = f"  {n_info} info" if n_info else ""
        print(f"  {suite:<12}  {n_pass:>3} pass  {n_fail:>3} fail  {n_err:>3} error{info_s}{avg_s}")
        total_pass += n_pass
        total_fail += n_fail
        total_err  += n_err
        for r in rs:
            all_issues.extend(r.issues)

    print("-" * 72)
    print(f"  {'Total':<12}  {total_pass:>3} pass  {total_fail:>3} fail  {total_err:>3} error")

    seen_issues: set[str] = set()
    deduped: list[str] = []
    for iss in all_issues:
        if iss not in seen_issues:
            seen_issues.add(iss)
            deduped.append(iss)

    if deduped:
        print(f"\nIssues ({len(deduped)}):")
        for iss in deduped[:25]:
            print(f"  - {iss}")
        if len(deduped) > 25:
            print(f"  … and {len(deduped) - 25} more")
    else:
        print("\nNo issues detected.")


# ─── Main ────────────────────────────────────────────────────────────────────

ALL_SUITES = ("ux", "present", "type_ref", "blank", "filter", "member", "member_inject", "multi_tree")


def build_probes(suites: set[str]) -> list[Probe]:
    result: list[Probe] = []
    if "ux"       in suites: result.extend(UX_PROBES)
    if "present"  in suites: result.extend(PRESENT_PROBES)
    if "type_ref" in suites: result.extend(TYPE_REF_PROBES)
    if "blank"    in suites: result.extend(BLANK_PROBES)
    if "filter"   in suites: result.extend(FILTER_PROBES)
    if "member"        in suites: result.extend(_auto_member_probes())
    if "member_inject" in suites: result.extend(MEMBER_INJECT_PROBES)
    if "multi_tree"    in suites: result.extend(MULTI_TREE_PROBES)
    return result


def filter_workspace(probes: list[Probe], workspace: str | None) -> list[Probe]:
    if workspace is None:
        return probes
    root = H if workspace == "helidon" else D
    return [p for p in probes if p.file.startswith(root)]


def main() -> None:
    parser = argparse.ArgumentParser(description="Lathe completion UX probe")
    parser.add_argument("--workspace", choices=["helidon", "dropwizard"],
                        help="Limit to one workspace (default: both)")
    parser.add_argument("--suite", default=",".join(ALL_SUITES),
                        help=f"Comma-separated suites to run (default: all)")
    parser.add_argument("--verbose", "-v", action="store_true",
                        help="Show top items for each probe")
    parser.add_argument("--logs", "-l", action="store_true",
                        help="Print relevant server log lines after each workspace")
    args = parser.parse_args()

    suites = {s.strip() for s in args.suite.split(",") if s.strip()}
    unknown = suites - set(ALL_SUITES)
    if unknown:
        print(f"Unknown suites: {unknown}. Valid: {', '.join(ALL_SUITES)}", file=sys.stderr)
        sys.exit(1)

    probes = build_probes(suites)
    probes = filter_workspace(probes, args.workspace)

    if not probes:
        print("No probes to run.", file=sys.stderr)
        sys.exit(1)

    print(f"Running {len(probes)} probes  suites={', '.join(sorted(suites))}"
          + (f"  workspace={args.workspace}" if args.workspace else ""))
    results = run_probes(probes, verbose=args.verbose, show_logs=args.logs)
    print_summary(results)


if __name__ == "__main__":
    main()
