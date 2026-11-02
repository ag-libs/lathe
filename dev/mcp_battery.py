#!/usr/bin/env python3
"""MCP release battery on a real workspace: every tool, checks that tools agree with each other, a
probe file of less common constructs, renames that must keep what names mean, bad requests, and the
server's own health. Restores the files it touched; exits 1 if a check fails, 2 if it refuses to run.

    python3 -I dev/mcp_battery.py <workspace> <dropwizard|equalsverifier|helidon> --expect-version 0.2.2

Each check prints one line: PASS, FAIL, SKIP (a prerequisite is missing), KNOWN (a wrong answer the
gap log already describes) or NOW PASSES (a KNOWN one that is right now; drop its mark). A JSON
report goes to dev/reports/battery/, and checks more than twice as slow as the previous version's
report are listed as SLOWER.
"""
import argparse
import json
import re
import subprocess
import sys
import tempfile
import time
from datetime import date
from pathlib import Path

DEV = Path(__file__).resolve().parent
sys.path.insert(0, str(DEV))
import mcp  # noqa: E402
import mcp_consistency as mc  # noqa: E402
import mcp_probe  # noqa: E402

# Per workspace: the commit the expectations were written against; a symbol with cross-module uses
# (renamed and back); an interface and one of its methods; a dependency or JDK type used in the
# file; a reactor type and a CamelHumps query for it; a test method; where the probe file goes.
CONFIGS = {
    "dropwizard": dict(
        pin="46bb4ed819",
        file="dropwizard-util/src/main/java/io/dropwizard/util/Duration.java",
        symbol="toMilliseconds", newname="toMillisLatheSmoke", is_method=True,
        iface=("dropwizard-lifecycle/src/main/java/io/dropwizard/lifecycle/Managed.java", "Managed {", "Managed"),
        method=("dropwizard-lifecycle/src/main/java/io/dropwizard/lifecycle/Managed.java", "void stop()", "stop"),
        dep="TimeUnit", search="Jackson", humps=("DiSuRe", "DiscoverableSubtypeResolver"),
        test=("dropwizard-util/src/test/java/io/dropwizard/util/DurationTest.java", "convertsDays", "DurationTest"),
        probe=("dropwizard-util/src/main/java/io/dropwizard/util", "io.dropwizard.util"),
        # JettyManaged gets its stop() from Jetty's AbstractLifeCycle, so a rename of Managed.stop
        # must refuse rather than leave JettyManaged not implementing Managed.
        inherited_rename=("dropwizard-lifecycle/src/main/java/io/dropwizard/lifecycle/Managed.java", "void stop()",
                          "stop", "stopLatheSmoke")),
    "equalsverifier": dict(
        pin="ab54bfce",
        file="equalsverifier-core/src/main/java/nl/jqno/equalsverifier/Warning.java",
        symbol="ALL_FIELDS_SHOULD_BE_USED", newname="ALL_FIELDS_LATHE_SMOKE", is_method=False,
        iface=("equalsverifier-core/src/main/java/nl/jqno/equalsverifier/internal/checkers/Checker.java",
               "Checker {", "Checker"),
        method=("equalsverifier-core/src/main/java/nl/jqno/equalsverifier/internal/checkers/Checker.java",
                "void check()", "check"),
        dep=None, search="EqualsVerifier", humps=("EqVeRe", "EqualsVerifierReport"),
        test=("equalsverifier-test/src/test/java/nl/jqno/equalsverifier/integration/basic_contract/HashCodeTest.java",
              "fail_whenHashCodesAreInconsistent", "HashCodeTest"),
        probe=("equalsverifier-core/src/main/java/nl/jqno/equalsverifier", "nl.jqno.equalsverifier")),
    "helidon": dict(
        pin="2f6df16425",
        file="common/media-type/src/main/java/io/helidon/common/media/type/MediaType.java",
        symbol="text()", newname="textLatheSmoke", is_method=True,
        iface=("common/media-type/src/main/java/io/helidon/common/media/type/MediaType.java", "MediaType {", "MediaType"),
        method=("common/media-type/src/main/java/io/helidon/common/media/type/MediaType.java", "String text()", "text"),
        dep="String", search="MediaTypes", humps=("MeTyEn", "MediaTypeEnum"),
        test=("common/media-type/src/test/java/io/helidon/common/media/type/MediaTypesTest.java", "testCustom",
              "MediaTypesTest"),
        probe=("common/media-type/src/main/java/io/helidon/common/media/type", "io.helidon.common.media.type"),
        module_info="common/media-type/src/main/java/module-info.java"),
}

# Every tool the server lists must have a check here, so a new tool cannot ship untested.
CHECKED_TOOLS = {"get_diagnostics", "get_definition", "find_references", "rename_symbol", "run_test",
                 "call_hierarchy", "describe_symbol", "search_symbols", "find_implementations",
                 "analyze_change", "verify_change", "format_file", "fix_imports", "outline", "workspace_summary"}
VERIFY_CLEAN = "No errors — "
EDIT_LINE = re.compile(r"^\s+(\S+\.java)(?: \[\w+\])? — \d+ edit")
REPORTS = DEV / "reports" / "battery"


def locate(path: Path, token: str):
    """1-based line and column of the first non-comment, non-import occurrence of token."""
    for i, line in enumerate(path.read_text(encoding="utf-8").splitlines()):
        c = line.find(token)
        if c >= 0 and not line.strip().startswith(("*", "//", "/*", "import ", "package ")):
            return i + 1, c + 1
    raise SystemExit("token %r not in %s" % (token, path))


def text_of(result):
    return "\n".join(c.get("text", "") for c in (result or {}).get("content", []))


def git(ws, *args):
    return subprocess.run(["git", "-C", str(ws), *args], capture_output=True, text=True).stdout


def first(text):
    lines = (text or "").strip().splitlines()
    return lines[0][:110] if lines else "(empty)"


class Battery:
    def __init__(self, ws: Path, launcher: Path):
        self.ws = ws
        self.client = mcp.McpClient(launcher, ws, echo_stderr=False)
        self.results = []
        self.last_ms = 0

    def record(self, status, label, detail, ms=None):
        ms = self.last_ms if ms is None else ms
        self.results.append(dict(status=status, label=label, detail=detail, ms=ms))
        print("%-11s %-46s %6dms  %s" % (status, label, ms, detail), flush=True)

    def check(self, label, passed, detail, known=None):
        if known is None:
            status = "PASS" if passed else "FAIL"
        else:
            status = "NOW PASSES" if passed else "KNOWN"
            detail = detail if passed else "%s — %s" % (known, detail)
        self.record(status, label, detail)

    def raw(self, tool, args):
        """(text, result) of a tool call; a timeout or a protocol error becomes an error result."""
        t0 = time.monotonic()
        try:
            result = self.client.call_tool(tool, args)
        except Exception as e:  # noqa: BLE001 -- report, never abort the battery
            result = {"isError": True, "exception": str(e)[:200],
                      "content": [{"type": "text", "text": "EXCEPTION %s" % str(e)[:200]}]}
        self.last_ms = int((time.monotonic() - t0) * 1000)
        return text_of(result), result

    def call(self, label, tool, args, ok=lambda t, r: not r.get("isError"), known=None):
        text, result = self.raw(tool, args)
        self.check(label, "exception" not in result and ok(text, result), first(text), known=known)
        return text

    def failures(self):
        return sum(1 for r in self.results if r["status"] == "FAIL")


def dirty_java(ws):
    return [line for line in git(ws, "status", "--porcelain", "--untracked-files=no").splitlines()
            if line.rstrip().endswith(".java")]


def untracked(ws):
    return set(git(ws, "ls-files", "--others", "--exclude-standard").split())


def preflight(ws, cfg, expected):
    """Refuses to run on a tree with edited sources or a sync from another version."""
    dirty = dirty_java(ws)
    if dirty:
        raise SystemExit("REFUSED: %d edited .java file(s) in %s; commit or stash them first:\n%s"
                         % (len(dirty), ws, "\n".join(dirty[:10])))
    head = git(ws, "rev-parse", "HEAD").strip()
    if not head.startswith(cfg["pin"]):
        print("WARN        HEAD %s is not the pinned %s; the expectations may not match" % (head[:10], cfg["pin"]))
    synced = json.loads((ws / ".lathe" / "workspace.json").read_text(encoding="utf-8")).get("serverVersion")
    if synced != expected:
        raise SystemExit("REFUSED: .lathe/ was synced by Lathe %s, not %s; set the extension to %s and run "
                         "`mvn process-test-classes` first" % (synced, expected, expected))
    return head


def workspace_checks(b, ws, cfg, f, pos, tests_captured):
    b.call("get_diagnostics (clean file)", "get_diagnostics", {"file": str(f)},
           ok=lambda t, r: not r.get("isError") and "No errors or warnings" in t)
    b.call("describe_symbol", "describe_symbol", pos,
           ok=lambda t, r: not r.get("isError") and cfg["symbol"].rstrip("()") in t)
    b.call("get_definition", "get_definition", pos, ok=lambda t, r: not r.get("isError") and f.name in t)
    if cfg["dep"]:
        dl, dc = locate(f, cfg["dep"])
        b.call("get_definition (dependency/JDK)", "get_definition", {"file": str(f), "line": dl, "column": dc},
               ok=lambda t, r: not r.get("isError") and cfg["dep"] in t)

    refs = b.raw("find_references", dict(pos, maxResults=500))[0]
    ref_modules = {mc.module_of(ws, p) for p, _, _ in mc.locations(refs)} - {None}
    b.check("find_references: uses in several modules", mc.count(refs) >= 3 and len(ref_modules) >= 2,
            "%d reference(s) in %d module(s)" % (mc.count(refs), len(ref_modules)))
    mc.list_shape(b, ws, refs, "find_references")

    incoming = b.raw("call_hierarchy", dict(pos, direction="incoming"))[0]
    if cfg["is_method"]:
        b.check("call_hierarchy incoming: has callers", mc.count(incoming) >= 1, first(incoming))
    else:
        b.check("call_hierarchy incoming: answers", not incoming.startswith("EXCEPTION"), first(incoming))
    b.call("call_hierarchy outgoing", "call_hierarchy", dict(pos, direction="outgoing"))
    b.call("analyze_change", "analyze_change", pos,
           ok=lambda t, r: not r.get("isError") and "references:" in t and "affected modules:" in t)

    iface = ws / cfg["iface"][0]
    il, _ = locate(iface, cfg["iface"][1])
    b.call("find_implementations", "find_implementations", {"file": str(iface), "line": il, "symbol": cfg["iface"][2]},
           ok=lambda t, r: not r.get("isError") and mc.count(t) >= 1)
    # "No symbols matching 'X'." repeats the query, so each check needs a count too.
    b.call("search_symbols", "search_symbols", {"query": cfg["search"]},
           ok=lambda t, r: mc.count(t) >= 1 and cfg["search"] in t)
    query, expected = cfg["humps"]
    b.call("search_symbols (CamelHumps)", "search_symbols", {"query": query}, ok=lambda t, r: expected in t)
    b.call("search_symbols: only usable types", "search_symbols", {"query": "Entry"},
           ok=lambda t, r: mc.count(t) >= 1 and "ThreadLocalMap" not in t and "Hashtable.Entry" not in t,
           known="search_symbols lists types the project cannot use, though its description says it excludes them")

    b.call("outline", "outline", {"file": str(f)},
           ok=lambda t, r: mc.count(t) >= 1 and cfg["symbol"].rstrip("()") in t)
    original = f.read_bytes()
    b.call("format_file", "format_file", {"file": str(f)},
           ok=lambda t, r: mc.clean_refusal(t) and "EXCEPTION" not in t)
    f.write_bytes(original)
    summary = b.raw("workspace_summary", {})[0]
    modules = len({p.parent for p in (ws / ".lathe").rglob("lsp-params-*.json")})
    b.check("workspace_summary: every module", mc.count(summary) >= max(1, modules), "%s; %d synced module(s)"
            % (first(summary), modules))
    if cfg.get("module_info"):
        b.call("outline module-info", "outline", {"file": str(ws / cfg["module_info"])},
               ok=lambda t, r: "requires" in t or "exports" in t,
               known="the outline of module-info.java shows only the module line")

    test = ws / cfg["test"][0]
    if not tests_captured:
        b.record("SKIP", "run_test class", "no captured test launch; run .lathe/lathe-sync.sh --tests <module>", 0)
        b.record("SKIP", "run_test method", "no captured test launch", 0)
    else:
        b.call("run_test class", "run_test", {"file": str(test)},
               ok=lambda t, r: not r.get("isError") and " 0 failed" in t)
        b.call("run_test method", "run_test", {"file": str(test), "scope": "method", "method": cfg["test"][1]},
               ok=lambda t, r: not r.get("isError") and "1 passed" in t and cfg["test"][2] in t)
    b.call("verify_change (no change)", "verify_change", {}, ok=lambda t, r: t.startswith(VERIFY_CLEAN))


def consistency_checks(b, ws, cfg, f, pos):
    iface = ws / cfg["iface"][0]
    method = ws / cfg["method"][0]
    type_pos = {"file": str(iface), "line": locate(iface, cfg["iface"][1])[0], "symbol": cfg["iface"][2]}
    method_pos = {"file": str(method), "line": locate(method, cfg["method"][1])[0], "symbol": cfg["method"][2]}
    mc.implementations_agree(b, type_pos, method_pos, "impls(%s) cover impls(%s)" % (cfg["iface"][2], cfg["method"][2]),
                             known="implementations of an interface omit lambdas that implementations of its method include")
    mc.override_family_present(b, method_pos, "override family of %s" % cfg["method"][2])
    mc.analyze_modules_cover(b, ws, method_pos, "analyze_change modules cover its locations",
                             known="analyze_change names too few affected modules")
    mc.references_round_trip(b, ws, f, pos, cfg["symbol"].rstrip("()"), "references lead back to the declaration")
    mc.outline_resolves(b, f)
    mc.diagnostics_agree_with_notes(b, f, "diagnostics agree with notes")


def break_and_repair(b, f):
    original = f.read_bytes()
    cut = original.rindex(b"}")
    modifier = b"default " if re.search(rb"^public interface", original, re.M) else b""
    f.write_bytes(original[:cut] + b"    %sint latheSmokeBroken() { return \"x\"; }\n" % modifier + original[cut:])
    b.call("verify_change sees new error", "verify_change", {"files": [str(f)]}, ok=lambda t, r: "incompatible" in t)
    b.call("get_diagnostics sees new error", "get_diagnostics", {"file": str(f)}, ok=lambda t, r: "incompatible" in t)
    f.write_bytes(original)
    b.call("verify_change after repair", "verify_change", {"files": [str(f)]}, ok=lambda t, r: t.startswith(VERIFY_CLEAN))


def rename_round_trip(b, ws, cfg, f, pos):
    renamed = b.raw("rename_symbol", dict(pos, newName=cfg["newname"]))[0]
    edited = {mc.module_of(ws, m.group(1)) for m in map(EDIT_LINE.match, renamed.splitlines()) if m} - {None}
    b.check("rename_symbol: edits several modules", renamed.startswith("Renamed") and len(edited) >= 2,
            "%s; %d module(s)" % (first(renamed), len(edited)))
    b.call("verify_change after rename", "verify_change", {}, ok=lambda t, r: t.startswith(VERIFY_CLEAN))
    nl, _ = locate(f, cfg["newname"])
    b.call("rename_symbol back", "rename_symbol",
           {"file": str(f), "line": nl, "symbol": cfg["newname"], "newName": cfg["symbol"].rstrip("()")})
    left = [line for line in git(ws, "status", "--porcelain", "--untracked-files=no").splitlines()
            if line.rstrip().endswith(".java")]
    b.check("rename round-trip leaves tree clean", not left, "; ".join(left)[:300] or "clean")


def inherited_rename(b, ws, cfg):
    path, token, name, new = cfg["inherited_rename"]
    file = ws / path
    text, result = b.raw("rename_symbol", {"file": str(file), "line": locate(file, token)[0], "symbol": name, "newName": new})
    refused = result.get("isError") or not text.startswith("Renamed")
    verified = "" if refused else b.raw("verify_change", {})[0]
    b.check("rename refuses a dependency-implemented method", refused or verified.startswith(VERIFY_CLEAN),
            first(text) if refused else first(verified),
            known="renaming an interface method misses implementations inherited from a dependency")
    restore_java(ws)


def negative_checks(b, ws, f):
    b.call("bad request: missing file", "get_diagnostics", {"file": str(ws / "lathe-probe-missing" / "Missing.java")},
           ok=lambda t, r: r.get("isError") and mc.clean_refusal(t))
    blank = next(i + 1 for i, line in enumerate(f.read_text(encoding="utf-8").splitlines()) if not line.strip())
    b.call("bad request: position on whitespace", "get_definition", {"file": str(f), "line": blank, "column": 1},
           ok=lambda t, r: mc.clean_refusal(t))
    b.call("bad request: missing line", "get_definition", {"file": str(f)},
           ok=lambda t, r: r.get("isError") and "line" in t)
    with tempfile.TemporaryDirectory(prefix="lathe-battery-outside-") as outside:
        target = Path(outside) / "Outside.java"
        target.write_text("class Outside {}\n", encoding="utf-8")
        known = "read tools open .java files outside the workspace, through symlinks too"
        b.call("bad request: file outside the workspace", "outline", {"file": str(target)},
               ok=lambda t, r: r.get("isError") and mc.clean_refusal(t), known=known)
        link = ws / "LatheProbeLink.java"
        link.symlink_to(target)
        try:
            b.call("bad request: symlink out of the workspace", "outline", {"file": str(link)},
                   ok=lambda t, r: r.get("isError") and mc.clean_refusal(t), known=known)
        finally:
            link.unlink()
    # A JSON-RPC error and an error result are both fine; only a hang or silence is not.
    text, result = b.raw("no_such_tool", {})
    b.check("bad request: unknown tool", result.get("isError") and "no answer within" not in text, first(text))


def lifecycle_checks(b, launcher):
    """On a session of its own: a server deafened by the garbage line must not spoil the main
    session's exit and log checks."""
    client = mcp.McpClient(launcher, b.ws, echo_stderr=False)
    b.last_ms = 0
    try:
        client.initialize()
        client.send_raw("this is not json {")
        answered = "tools" in client.request("tools/list", {}, timeout=15)
    except Exception:  # noqa: BLE001
        answered = False
    finally:
        exited = client.close()
    b.check("server answers after a garbage line", answered, "tools/list after an unparseable line",
            known="an unparseable line on stdin leaves the MCP server running but deaf")
    b.check("deafened server still exits on stdin close", exited, "exited" if exited else "still running after 5 s",
            known="an unparseable line on stdin leaves the MCP server running but deaf")


def health_checks(b, exited):
    b.last_ms = 0
    b.check("server exits when stdin closes", exited, "exited within 5 s" if exited else "still running after 5 s")
    log = b.client.session_log()
    lines = list(b.client.stderr_lines)
    if log and log.is_file():
        lines += log.read_text(encoding="utf-8", errors="replace").splitlines()
    severe = [line for line in lines if " SEVERE " in line]
    traces = [line for line in lines if re.match(r"\s+at [\w.$]+\(", line)]
    b.check("server log: no SEVERE or stack trace", not severe and not traces,
            "%d SEVERE, %d stack frame(s); first: %s" % (len(severe), len(traces), (severe or traces or ["none"])[0][:90]))
    jdk = [line for line in b.client.stderr_lines if line.startswith("WARNING:")]
    b.check("server stderr: no JDK warnings", not jdk, first("\n".join(jdk)) if jdk else "none",
            known="a JDK warning on the MCP server's stderr")


def restore_java(ws, created=()):
    touched = [p for p in git(ws, "diff", "--name-only").split() if p.endswith(".java")]
    if touched:
        git(ws, "checkout", "--", *touched)
    for p in created:
        (ws / p).unlink(missing_ok=True)
    return len(touched) + len(created)


def write_report(cfg_name, version, head, results):
    REPORTS.mkdir(parents=True, exist_ok=True)
    report = REPORTS / ("%s-%s.json" % (cfg_name, version))
    previous = sorted((p for p in REPORTS.glob("%s-*.json" % cfg_name) if p != report),
                      key=lambda p: p.stat().st_mtime)
    report.write_text(json.dumps(dict(workspace=cfg_name, version=version, head=head, date=str(date.today()),
                                      checks=results), indent=1), encoding="utf-8")
    if not previous:
        return
    before = json.loads(previous[-1].read_text(encoding="utf-8"))
    times = {c["label"]: c["ms"] for c in before["checks"]}
    for check in results:
        was = times.get(check["label"])
        if was is not None and check["ms"] > 2 * was and check["ms"] - was > 500:
            print("SLOWER      %-46s %6dms  was %dms in %s" % (check["label"], check["ms"], was, before["version"]))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("workspace")
    parser.add_argument("config", choices=sorted(CONFIGS))
    parser.add_argument("--expect-version", required=True, help="the Lathe version under test, e.g. 0.2.2")
    opts = parser.parse_args()
    ws, cfg = Path(opts.workspace).resolve(), CONFIGS[opts.config]
    head = preflight(ws, cfg, opts.expect_version)
    launcher = mcp.launcher_for(ws)
    untracked_before = untracked(ws)
    b = Battery(ws, launcher)
    version = (b.client.initialize().get("serverInfo") or {}).get("version")
    print("SERVER      %s from %s; workspace %s at %s" % (version, launcher, ws.name, head[:10]))
    if version != opts.expect_version:
        b.client.close()
        raise SystemExit("REFUSED: the server reports %s, not %s" % (version, opts.expect_version))

    f = ws / cfg["file"]
    line, _ = locate(f, cfg["symbol"])
    pos = {"file": str(f), "line": line, "symbol": cfg["symbol"].rstrip("()")}
    test_module = mc.module_of(ws, cfg["test"][0])
    tests_captured = bool(test_module) and (ws / ".lathe" / test_module / "test-launch.json").is_file()
    probe = mcp_probe.Probe(ws, *cfg["probe"])
    exited = False
    try:
        listed = {t["name"] for t in b.client.tools_list().get("tools", [])}
        b.check("every listed tool has a check", listed <= CHECKED_TOOLS,
                "untested: %s" % ", ".join(sorted(listed - CHECKED_TOOLS)) if listed - CHECKED_TOOLS
                else "%d tool(s)" % len(listed))
        workspace_checks(b, ws, cfg, f, pos, tests_captured)
        consistency_checks(b, ws, cfg, f, pos)
        break_and_repair(b, f)
        rename_round_trip(b, ws, cfg, f, pos)
        if cfg.get("inherited_rename"):
            inherited_rename(b, ws, cfg)
        try:
            mcp_probe.run(b, ws, probe)
        finally:
            probe.remove()
        negative_checks(b, ws, f)
    finally:
        exited = b.client.close()
        created = sorted(untracked(ws) - untracked_before)
        restored = restore_java(ws, [p for p in created if p.endswith(".java")])
        if restored:
            print("RESTORED    %d file(s)" % restored)
    health_checks(b, exited)
    lifecycle_checks(b, launcher)
    write_report(opts.config, version, head, b.results)
    counts = {s: sum(1 for r in b.results if r["status"] == s) for s in ("PASS", "FAIL", "KNOWN", "NOW PASSES", "SKIP")}
    print("SUMMARY     " + " ".join("%s=%d" % (s.lower().replace(" ", "_"), n) for s, n in counts.items()))
    sys.exit(1 if b.failures() else 0)


if __name__ == "__main__":
    main()
