"""Checks that the MCP tools agree with each other, and the parsers for their text answers.

Each check needs no stored expected answer, so it works on any workspace: it asks two tools about
the same symbol and fails when their answers contradict. Used by dev/mcp_battery.py, which passes
its Battery (call/raw/check) in as `b`.
"""
import re
from pathlib import Path

LOCATION = re.compile(r"(\S+\.java):(\d+)(?: \[(\w+)\])?: ?(.*)$")
OUTLINE_ITEM = re.compile(r"^( *)(\S+) (.+?)  (\d+):(\d+): ")
SERVER_LOG_FAILURE = "see the server log"


def first(text):
    """The answer's first line, shortened for a report row."""
    lines = (text or "").strip().splitlines()
    return lines[0][:110] if lines else "(empty)"


def locations(text):
    """(path, line, snippet) of every location line, in answer order."""
    found = []
    for line in text.splitlines():
        m = LOCATION.search(line)
        if m:
            found.append((m.group(1), int(m.group(2)), m.group(4).strip()))
    return found


def count(text):
    """The N of an "N thing(s):" header; 0 for a "No ... found." answer."""
    m = re.match(r"\s*(\d+) ", text)
    return int(m.group(1)) if m else 0


def outline_items(text):
    """(depth, kind, name, line, column) of every declaration an outline answer lists."""
    items = []
    for line in text.splitlines():
        m = OUTLINE_ITEM.match(line)
        if m:
            items.append((len(m.group(1)) // 2, m.group(2), m.group(3), int(m.group(4)), int(m.group(5))))
    return items


def module_of(ws: Path, path: str):
    """The reactor module a workspace path belongs to: the deepest directory .lathe/ mirrors."""
    rel = Path(path)
    if rel.is_absolute():
        try:
            rel = rel.relative_to(ws)
        except ValueError:
            return None
    for parent in [rel.parent, *rel.parent.parents]:
        mirror = ws / ".lathe" / parent
        if parent != Path(".") and any(mirror.glob("lsp-params-*.json")):
            return str(parent)
    return None


def clean_refusal(text):
    """An error answer an agent can act on, not an internal failure."""
    return SERVER_LOG_FAILURE not in text and len(text.strip()) > 0


def outline_resolves(b, file: Path, known_names=None):
    """Every declaration outline lists resolves with describe_symbol at the position it gives."""
    known_names = known_names or {}
    text = b.raw("outline", {"file": str(file)})[0]
    unresolved = []
    for _, _, name, line, column in outline_items(text):
        answer, result = b.raw("describe_symbol", {"file": str(file), "line": line, "column": column})
        if result.get("isError") or "No symbol information" in answer:
            unresolved.append(name)
    label = "outline items resolve (%s)" % file.name
    unknown = [n for n in unresolved if n not in known_names]
    if unknown:
        b.check(label, False, "unresolved: %s" % ", ".join(unknown))
        return
    # Once a known name resolves, the check reports NOW PASSES so its mark can go.
    reasons = sorted({known_names[n] for n in (unresolved or known_names)})
    b.check(label, not unresolved, "unresolved: %s" % ", ".join(unresolved) if unresolved else
            "every declaration resolves", known="; ".join(reasons) or None)


def implementations_agree(b, type_pos, method_pos, label, known=None):
    """Every file implementing an interface's method also holds one of the interface's
    implementations (a lambda's file included)."""
    type_impls = locations(b.raw("find_implementations", type_pos)[0])
    method_impls = locations(b.raw("find_implementations", method_pos)[0])
    type_files = {p for p, _, _ in type_impls}
    missing = sorted({"%s:%d" % (p, line) for p, line, _ in method_impls if p not in type_files})
    b.check(label, not missing,
            "%d type, %d method implementation(s); %d in no implementing file: %s"
            % (len(type_impls), len(method_impls), len(missing), ", ".join(missing[:3])), known=known)


def override_family_present(b, pos, label, known=None):
    """A method that overrides or is overridden has an override family in analyze_change."""
    text = b.raw("analyze_change", pos)[0]
    family = re.search(r"override family: (.*)", text)
    b.check(label, bool(family) and family.group(1).strip() != "none",
            (family.group(0) if family else text[:120]).strip(), known=known)


def analyze_modules_cover(b, ws: Path, pos, label, known=None):
    """The modules analyze_change names include the module of every location it lists."""
    text = b.raw("analyze_change", pos)[0]
    named = re.search(r"affected modules: (.*)", text)
    named_set = {m.strip() for m in named.group(1).split(",")} if named else set()
    listed = {module_of(ws, p) for p, _, _ in locations(text)} - {None}
    missing = sorted(listed - named_set)
    b.check(label, not missing, "names %d module(s); its locations span %d; missing: %s"
            % (len(named_set), len(listed), ", ".join(missing[:5]) or "none"), known=known)


def diagnostics_agree_with_notes(b, file: Path, label, known=None):
    """A clean diagnostics answer carries no note saying the file did not compile."""
    text, result = b.raw("get_diagnostics", {"file": str(file)})
    clean = "No errors or warnings" in text
    contradicts = clean and ("Not compiled" in text or re.search(r"\berror\b", text.split("\n", 1)[-1], re.I))
    b.check(label, not result.get("isError") and not contradicts,
            text.strip().splitlines()[0][:110] if text.strip() else "(empty)", known=known)


def references_round_trip(b, ws: Path, decl_file: Path, pos, name, label, limit=10):
    """get_definition at each of the first references leads back to the declaration, or to a
    declaration that counts this reference among its own: a call through a subtype resolves to
    that subtype's override or redeclaration."""
    refs = locations(b.raw("find_references", dict(pos, maxResults=limit))[0])
    wrong = []
    for path, line, _ in refs[:limit]:
        file = Path(path) if Path(path).is_absolute() else ws / path
        targets = locations(b.raw("get_definition", {"file": str(file), "line": line, "symbol": name})[0])
        if any(Path(p).name == decl_file.name for p, _, _ in targets):
            continue
        if not targets:
            wrong.append("%s:%d (no definition)" % (path, line))
            continue
        target_path, target_line, _ = targets[0]
        target_file = Path(target_path) if Path(target_path).is_absolute() else ws / target_path
        back = locations(b.raw("find_references", {"file": str(target_file), "line": target_line, "symbol": name,
                                                   "maxResults": 500})[0])
        if not any(p == path and found == line for p, found, _ in back):
            wrong.append("%s:%d" % (path, line))
    b.check(label, bool(refs) and not wrong, "%d reference(s) checked; wrong: %s"
            % (min(len(refs), limit), ", ".join(wrong[:3]) or "none"))


def family_covers_definitions(b, ws: Path, decl_file: Path, pos, name, label, limit=500, known=None):
    """Every declaration a reference resolves to, other than the declaration itself, is in
    analyze_change's override family: an agent changing the method must hear about it."""
    family = {(Path(p).name, line) for p, line, _ in locations(b.raw("analyze_change", pos)[0])}
    refs = locations(b.raw("find_references", dict(pos, maxResults=limit))[0])
    missing = set()
    for path, line, _ in refs[:limit]:
        file = Path(path) if Path(path).is_absolute() else ws / path
        for target, target_line, _ in locations(b.raw("get_definition", {"file": str(file), "line": line,
                                                                         "symbol": name})[0]):
            if Path(target).name != decl_file.name and (Path(target).name, target_line) not in family:
                missing.add("%s:%d" % (target, target_line))
    b.check(label, not missing, "%d family member(s); left out: %s"
            % (len(family), ", ".join(sorted(missing)[:3]) or "none"), known=known)


def list_shape(b, ws: Path, text, label):
    """A location list has no duplicate rows and keeps each module's rows together."""
    rows = locations(text)
    keys = [(p, line, s) for p, line, s in rows]
    duplicates = len(keys) - len(set(keys))
    b.check("%s: no duplicate rows" % label, duplicates == 0, "%d duplicate(s) in %d row(s)" % (duplicates, len(rows)),
            known="two hits on one line show as identical rows with no column")
    order = [module_of(ws, p) for p, _, _ in rows]
    seen, scattered = set(), set()
    for i, module in enumerate(order):
        if module in seen and order[i - 1] != module:
            scattered.add(module)
        seen.add(module)
    b.check("%s: modules grouped" % label, not scattered, "%d module(s) scattered" % len(scattered),
            known="locations come back in discovery order, modules mixed")
