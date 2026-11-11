"""The construct probe: dev/battery/LatheProbe.java.tmpl and LatheProbeImports.java.tmpl, copied into
one workspace module, and what every tool must answer on them.

A check marked `known` describes a wrong answer already in the gap log: it reports KNOWN while the
answer stays wrong and NOW PASSES once it is right, so the mark can be dropped. Used by
dev/mcp_battery.py, which passes its Battery (call/raw/check) in as `b`.
"""
import time
from pathlib import Path

import mcp_consistency as mc
from mcp_consistency import first

TEMPLATES = Path(__file__).resolve().parent / "battery"
NOT_ON_NAME = "the position must be on a name"


def line_of(text, token):
    """1-based line of the only line holding token."""
    hits = [i + 1 for i, line in enumerate(text.splitlines()) if token in line]
    if len(hits) != 1:
        raise LookupError("probe token %r matches %d lines" % (token, len(hits)))
    return hits[0]


# The probe classes are package-private: a public class needs an API stability annotation in Helidon.
class Probe:
    def __init__(self, ws: Path, src_dir: str, package: str):
        self.dir = ws / src_dir
        self.package = package
        self.file = self.dir / "LatheProbe.java"
        self.imports_file = self.dir / "LatheProbeImports.java"
        self.caller_file = self.dir / "LatheProbeCaller.java"

    def write(self, name, crlf=False):
        text = (TEMPLATES / ("%s.java.tmpl" % name)).read_text(encoding="utf-8").replace("@PACKAGE@", self.package)
        if crlf:
            text = text.replace("\n", "\r\n")
        target = self.dir / ("%s.java" % name)
        target.write_bytes(text.encode("utf-8"))
        return target

    def remove(self):
        for f in (self.file, self.imports_file, self.caller_file):
            f.unlink(missing_ok=True)

    def text(self):
        return self.file.read_text(encoding="utf-8")

    def pos(self, token, name, offset=0):
        """The symbol `name` on the line holding token, or `offset` lines from it."""
        return {"file": str(self.file), "line": line_of(self.text(), token) + offset, "symbol": name}

    def line(self, token):
        return line_of(self.text(), token)

    def at(self, token, word):
        """The column of `word` on the line holding token: for a keyword such as new or var, which
        the tools cannot find by name."""
        line = line_of(self.text(), token)
        return {"file": str(self.file), "line": line, "column": self.text().splitlines()[line - 1].index(word) + 1}


def wait_indexed(b, name, deadline_s=30):
    """A new file reaches the type index after a moment; later checks need it there."""
    end = time.monotonic() + deadline_s
    while time.monotonic() < end:
        if mc.count(b.raw("search_symbols", {"query": name})[0]) >= 1:
            return True
        time.sleep(1)
    return False


def has_line(text, line):
    return any(found == line for _, found, _ in mc.locations(text))


def run(b, ws: Path, probe: Probe):
    probe.write("LatheProbe")
    probe.write("LatheProbeCaller")
    b.call("probe: compiles cleanly", "get_diagnostics", {"file": str(probe.file)},
           ok=lambda t, r: not r.get("isError") and " Error " not in t and "not checked" not in t)
    b.check("probe: reaches the type index", wait_indexed(b, "LatheProbe"), "search_symbols finds LatheProbe")
    b.section("probe outline", outline_checks, b, probe)
    b.section("probe implementations", implementation_checks, b, probe)
    b.section("probe navigation", navigation_checks, b, probe)
    b.section("probe consistency", consistency_checks, b, probe)
    b.section("probe renames", rename_checks, b, probe)
    b.section("probe stale answer", stale_answer_check, b, probe)
    b.section("probe recent fixes", recent_fix_checks, b, probe)
    b.section("probe imports", imports_checks, b, probe)


def consistency_checks(b, probe):
    mc.outline_resolves(b, probe.file)
    mc.override_family_present(b, probe.pos("return side * side", "area", offset=-1),
                               "probe: override family of Square.area",
                               known="analyze_change reports no override family for a method that overrides one")
    mc.diagnostics_agree_with_notes(b, probe.file, "probe: diagnostics agree with notes")


def outline_checks(b, probe):
    items = mc.outline_items(b.raw("outline", {"file": str(probe.file)})[0])
    kinds = {name: kind.lower() for _, kind, name, _, _ in items}
    b.check("probe outline: lists every type", all(n in kinds for n in ("LatheProbe", "Shape", "Node", "Leaf", "Color", "Square")),
            "%d item(s)" % len(items))
    b.check("probe outline: record kind", kinds.get("Leaf") not in (None, "struct"), "Leaf is %s" % kinds.get("Leaf"),
            known="records show as struct")
    b.check("probe outline: enum constant kind", kinds.get("RED") not in (None, "field"), "RED is %s" % kinds.get("RED"),
            known="enum constants show as field")
    labels = sum(1 for _, _, name, _, _ in items if name.startswith("label"))
    b.check("probe outline: enum constant body methods", labels >= 2, "%d label() item(s)" % labels,
            known="methods in an enum constant body are omitted")


def implementation_checks(b, probe):
    shape = b.raw("find_implementations", probe.pos("interface Shape {", "Shape"))[0]
    b.check("probe impls(Shape): named class", has_line(shape, probe.line("static final class Square")), first(shape))
    b.check("probe impls(Shape): anonymous class", has_line(shape, probe.line("return new Shape() {")), first(shape))
    b.check("probe impls(Shape): lambda", has_line(shape, probe.line("return () -> Math.PI")), first(shape))
    area = b.raw("find_implementations", probe.pos("double area();", "area"))[0]
    b.check("probe impls(area): lambda", has_line(area, probe.line("return () -> Math.PI")), first(area))
    node = b.raw("find_implementations", probe.pos("sealed interface Node", "Node"))[0]
    b.check("probe impls(Node): records", has_line(node, probe.line("record Leaf(")) and has_line(node, probe.line("record Pair(")),
            first(node))
    label = b.raw("find_implementations", probe.pos("return name();", "label", offset=-1))[0]
    b.check("probe impls(Color.label): enum constant body", has_line(label, probe.line('return "red";') - 1), first(label))


def refused_as_no_name(text):
    """MCP's position tools take a name: a keyword such as var or the new of Type::new is refused
    by design, with a reason, though the editor navigates from both."""
    return NOT_ON_NAME in text and not mc.locations(text) and mc.clean_refusal(text)


def navigation_checks(b, probe):
    ctor = b.raw("get_definition", probe.at("Square::new", "new"))[0]
    b.check("probe definition: the new of Type::new is refused as no name", refused_as_no_name(ctor), first(ctor))
    var = b.raw("get_definition", probe.at("var list = ", "var"))[0]
    b.check("probe definition: var is refused as no name", refused_as_no_name(var), first(var))
    calls = b.raw("call_hierarchy", dict(probe.pos("static List<Shape> shapes()", "shapes"), direction="outgoing"))[0]
    b.check("probe outgoing: method reference", "unit" in calls, first(calls))
    b.check("probe outgoing: constructor reference", "Square" in calls, first(calls))


def reference_counts(b, probe, watched):
    counts = {}
    for token, name in watched:
        counts[name] = mc.count(b.raw("find_references", dict(probe.pos(token, name), maxResults=200))[0])
    return counts


# Each rename: the line and name renamed (a word for a column where the name is too short to find
# by name), the new name, and the declarations whose reference counts must not move.
RENAMES = [
    ("parameter to a field's name", ("public int compute(int x)", "x"), "value",
     [("private int value;", "value"), ("void setValue(int v)", "setValue")]),
    ("method to an overload's name", ("long twice(long n)", "twice"), "setValue",
     [("void setValue(int v)", "setValue"), ("public int compute(", "compute")]),
    ("lambda parameter to a field's name", ("IntUnaryOperator op = n ->", "n ->"), "limit",
     [("private int limit;", "limit")]),
    ("local to a field's name", ("int count = 1;", "count"), "total",
     [("private int total;", "total")]),
    ("field to a local's name", ("private int width;", "width"), "size",
     [("int size = 2;", "size")]),
    ("inherited method to an enclosing method's name", ("void pong() {}", "pong"), "ping",
     [("public void ping() {}", "ping")]),
    ("method to a subclass method's name", ("void speak() {}", "speak"), "bark",
     [("public void bark() {}", "bark")]),
    ("type to a wildcard-imported name", ("static final class Dice {}", "Dice"), "Random",
     [("Random rng()", "Random")]),
    ("method capturing another file's call", ("static int gauge(int v)", "gauge"), "measure",
     [("static long measure(long v)", "measure")]),
]


def rename_keeps_meaning(b, probe, label, target, new, watched):
    files = (probe.file, probe.caller_file)
    originals = [f.read_bytes() for f in files]
    # Restored even when a check throws: every later rename expects the probe as written.
    try:
        before = reference_counts(b, probe, watched)
        token, word = target
        position = probe.at(token, word) if " " in word else probe.pos(token, word)
        text, result = b.raw("rename_symbol", dict(position, newName=new))
        if "No references" in text:
            b.check(label, False, "found nothing to rename: %s" % first(text))
        elif result.get("isError") or not text.startswith("Renamed"):
            # Only a refusal that names the rebinding keeps the meaning; any other refusal is a failure.
            if "would then" in text:
                b.check(label, True, "refused: %s" % first(text))
            else:
                b.check(label, False, "refused for another reason: %s" % first(text))
        else:
            after = reference_counts(b, probe, watched)
            moved = {n: (before[n], after[n]) for n in after if before[n] != after[n]}
            b.check(label, not moved, "reference counts moved: %s" % moved if moved else "every name keeps its uses")
    finally:
        for f, original in zip(files, originals):
            f.write_bytes(original)
        b.raw("get_diagnostics", {"file": str(probe.file)})


def rename_checks(b, probe):
    for label, target, new, watched in RENAMES:
        b.section("probe rename: %s" % label, rename_keeps_meaning, b, probe, "probe rename: %s" % label,
                  target, new, watched)


def stale_answer_check(b, probe):
    original = probe.file.read_bytes()
    probe.file.write_bytes(original.replace(b"return list;", b"return listBroken;"))
    try:
        text = b.raw("find_implementations", probe.pos("sealed interface Node", "Node"))[0]
        current = has_line(text, probe.line("record Leaf("))
        b.check("probe: broken file answers or says so", current or "Not compiled" in text, first(text))
    finally:
        probe.file.write_bytes(original)
        b.raw("get_diagnostics", {"file": str(probe.file)})


def recent_fix_checks(b, probe):
    """What this release's fixes changed, on constructs each workspace may not have."""
    area = probe.pos("return side * side", "area", offset=-1)
    callers = b.raw("call_hierarchy", dict(area, direction="incoming"))[0]
    b.check("probe incoming(Square.area): through the base method", "viaShape" in callers, first(callers))
    b.check("probe incoming(Square.area): not through a type Square is not", "viaTile" not in callers,
            first(callers))
    square = b.raw("describe_symbol", probe.pos("static final class Square", "Square"))[0]
    b.check("probe describe(Square): modifiers and supertypes", "final class Square implements Shape" in square,
            first(square))
    doc = b.raw("describe_symbol", area)[0]
    b.check("probe describe(Square.area): inherits the doc its own leaves out",
            "LATHEPROBEAREA" in doc and "side squared" in doc, first(doc))
    guarded = b.raw("find_references", dict(probe.pos("static int guarded(", "guarded"), maxResults=50))[0]
    b.check("probe references(type-use annotated parameter): finds the other file's call",
            probe.caller_file.name in guarded, first(guarded))
    wrong_arguments(b, probe)


def wrong_arguments(b, probe):
    original = probe.file.read_bytes()
    probe.file.write_bytes(original.replace(b"return pick(1);", b"return pick(1.5);"))
    try:
        text = b.raw("get_definition", probe.pos("return pick(1.5);", "pick"))[0]
        offered = has_line(text, probe.line("int pick(int i)")) and has_line(text, probe.line("int pick(String s)"))
        b.check("probe definition: a call no overload matches offers every overload", offered, first(text))
    finally:
        probe.file.write_bytes(original)
        b.raw("get_diagnostics", {"file": str(probe.file)})


def imports_checks(b, probe):
    target = probe.write("LatheProbeImports")
    text = b.raw("fix_imports", {"file": str(target)})[0]
    b.check("probe fix_imports: single candidate", "java.util.concurrent.ConcurrentHashMap" in text, first(text))
    b.check("probe fix_imports: nested type", "java.util.Map.Entry" in text, first(text))
    target = probe.write("LatheProbeImports", crlf=True)
    text = b.raw("fix_imports", {"file": str(target)})[0]
    data = target.read_bytes()
    if b"import java.util.concurrent.ConcurrentHashMap;" not in data:
        b.check("probe fix_imports: keeps CRLF", False, "no import added to the CRLF file: %s" % first(text))
    else:
        bare_lf = data.count(b"\n") - data.count(b"\r\n")
        b.check("probe fix_imports: keeps CRLF", bare_lf == 0, "%d bare LF line ending(s)" % bare_lf,
                known="MCP edits written to a CRLF file add LF line endings")
    target.unlink()

