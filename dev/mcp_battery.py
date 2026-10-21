#!/usr/bin/env python3
"""MCP smoke on a real workspace: every tool once, a break/verify/repair cycle, and a cross-module
rename round-trip. Restores the files it touched; exits 1 if a check fails.

    python3 -I dev/mcp_battery.py <workspace> <dropwizard|equalsverifier|helidon>

Uses the launcher dev/mcp.py picks (LATHE_MCP_LAUNCHER, else the newest cached server).
"""
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mcp  # noqa: E402

# Per workspace: a symbol with cross-module uses (renamed and back), an interface, a dependency or
# JDK type used in the file, a reactor type and a CamelHumps query for it, and a test method.
CONFIGS = {
    "dropwizard": dict(
        file="dropwizard-util/src/main/java/io/dropwizard/util/Duration.java",
        symbol="toMilliseconds", newname="toMillisLatheSmoke",
        iface=("dropwizard-lifecycle/src/main/java/io/dropwizard/lifecycle/Managed.java", "Managed {"),
        dep="TimeUnit", search="Jackson", humps=("DiSuRe", "DiscoverableSubtypeResolver"),
        test=("dropwizard-util/src/test/java/io/dropwizard/util/DurationTest.java", "convertsDays")),
    "equalsverifier": dict(
        file="equalsverifier-core/src/main/java/nl/jqno/equalsverifier/Warning.java",
        symbol="ALL_FIELDS_SHOULD_BE_USED", newname="ALL_FIELDS_LATHE_SMOKE",
        iface=("equalsverifier-core/src/main/java/nl/jqno/equalsverifier/internal/checkers/Checker.java",
               "Checker {"),
        dep=None, search="EqualsVerifier", humps=("EqVeRe", "EqualsVerifierReport"),
        test=("equalsverifier-test/src/test/java/nl/jqno/equalsverifier/integration/basic_contract/HashCodeTest.java",
              "fail_whenHashCodesAreInconsistent")),
    "helidon": dict(
        file="common/media-type/src/main/java/io/helidon/common/media/type/MediaType.java",
        symbol="text()", newname="textLatheSmoke",
        iface=("common/media-type/src/main/java/io/helidon/common/media/type/MediaType.java", "MediaType {"),
        dep="String", search="MediaTypes", humps=("MeTyEn", "MediaTypeEnum"),
        test=("common/media-type/src/test/java/io/helidon/common/media/type/MediaTypesTest.java", "testCustom")),
}

ERROR_COUNT = re.compile(r"\b[1-9]\d* errors?\b")


def locate(path: Path, token: str):
    """1-based line and column of the first non-comment, non-import occurrence of token."""
    for i, line in enumerate(path.read_text().splitlines()):
        c = line.find(token)
        if c >= 0 and not line.strip().startswith(("*", "//", "/*", "import ", "package ")):
            return i + 1, c + 1
    raise SystemExit("token %r not in %s" % (token, path))


def text_of(result):
    return "\n".join(c.get("text", "") for c in (result or {}).get("content", []))


def git(ws, *args):
    return subprocess.run(["git", "-C", str(ws), *args], capture_output=True, text=True).stdout


class Battery:
    def __init__(self, ws: Path):
        self.client = mcp.McpClient(mcp._default_launcher(), ws)
        self.client.initialize()
        self.failures = 0

    def record(self, passed, label, detail):
        self.failures += 0 if passed else 1
        print("%s %-34s %s" % ("PASS" if passed else "FAIL", label, detail), flush=True)

    def call(self, label, tool, args, ok=lambda t, r: not r.get("isError")):
        t0 = time.monotonic()
        try:
            r, err = self.client.call_tool(tool, args), None
        except Exception as e:  # noqa: BLE001 -- report, never abort the battery
            r, err = {"isError": True}, str(e)[:200]
        t = text_of(r)
        first = (err or t).strip().splitlines()[:1]
        self.record(err is None and ok(t, r), label,
                    "%6dms  %s" % (int((time.monotonic() - t0) * 1000), first[0][:110] if first else ""))
        return t


def main():
    ws, cfg = Path(sys.argv[1]).resolve(), CONFIGS[sys.argv[2]]
    dirty_before = git(ws, "status", "--porcelain", "--untracked-files=no")
    b = Battery(ws)
    f = ws / cfg["file"]
    line, col = locate(f, cfg["symbol"])
    pos = {"file": str(f), "line": line, "column": col}
    try:
        b.call("get_diagnostics (clean file)", "get_diagnostics", {"file": str(f)},
               ok=lambda t, r: not r.get("isError") and "No diagnostics" in t)
        b.call("describe_symbol", "describe_symbol", pos, ok=lambda t, r: cfg["symbol"].rstrip("()") in t)
        b.call("get_definition", "get_definition", pos, ok=lambda t, r: f.name in t)
        if cfg["dep"]:
            dl, dc = locate(f, cfg["dep"])
            b.call("get_definition (dependency/JDK)", "get_definition", {"file": str(f), "line": dl, "column": dc},
                   ok=lambda t, r: not r.get("isError") and cfg["dep"] in t)
        b.call("find_references", "find_references", dict(pos, maxResults=500))
        b.call("call_hierarchy incoming", "call_hierarchy", dict(pos, direction="incoming"))
        b.call("call_hierarchy outgoing", "call_hierarchy", dict(pos, direction="outgoing"))
        b.call("analyze_change", "analyze_change", pos, ok=lambda t, r: not r.get("isError") and len(t) > 50)
        iface = ws / cfg["iface"][0]
        il, ic = locate(iface, cfg["iface"][1])
        b.call("find_implementations", "find_implementations", {"file": str(iface), "line": il, "column": ic},
               ok=lambda t, r: not r.get("isError") and ".java" in t)
        b.call("search_symbols", "search_symbols", {"query": cfg["search"]}, ok=lambda t, r: cfg["search"] in t)
        query, expected = cfg["humps"]
        b.call("search_symbols (CamelHumps)", "search_symbols", {"query": query}, ok=lambda t, r: expected in t)
        test = ws / cfg["test"][0]
        b.call("run_test class", "run_test", {"file": str(test)},
               ok=lambda t, r: not r.get("isError") and " 0 failed" in t)
        b.call("run_test method", "run_test", {"file": str(test), "scope": "method", "method": cfg["test"][1]},
               ok=lambda t, r: not r.get("isError") and "1 passed" in t)
        b.call("verify_change (no change)", "verify_change", {}, ok=lambda t, r: "No new diagnostics" in t)

        # break -> verify reports it -> repair -> verify clean
        orig = f.read_text()
        cut = orig.rindex("}")
        modifier = "default " if re.search(r"^public interface", orig, re.M) else ""
        f.write_text(orig[:cut] + "    %sint latheSmokeBroken() { return \"x\"; }\n" % modifier + orig[cut:])
        b.call("verify_change sees new error", "verify_change", {"files": [str(f)]},
               ok=lambda t, r: "incompatible" in t)
        b.call("get_diagnostics sees new error", "get_diagnostics", {"file": str(f)},
               ok=lambda t, r: "incompatible" in t)
        f.write_text(orig)
        b.call("verify_change after repair", "verify_change", {"files": [str(f)]},
               ok=lambda t, r: "No new diagnostics" in t)

        # cross-module rename round-trip: the rename back must find every renamed use
        b.call("rename_symbol", "rename_symbol", dict(pos, newName=cfg["newname"]))
        b.call("verify_change after rename", "verify_change", {}, ok=lambda t, r: not ERROR_COUNT.search(t))
        nl, nc = locate(f, cfg["newname"])
        b.call("rename_symbol back", "rename_symbol",
               {"file": str(f), "line": nl, "column": nc, "newName": cfg["symbol"].rstrip("()")})
        left = git(ws, "status", "--porcelain", "--untracked-files=no")
        b.record(left == dirty_before, "rename round-trip leaves tree clean", left.strip()[:300])
    finally:
        b.client.close()
        before = {entry[3:] for entry in dirty_before.splitlines()}
        touched = [p for p in git(ws, "diff", "--name-only").split() if p not in before]
        if touched:
            print("RESTORING %d touched file(s)" % len(touched))
            git(ws, "checkout", "--", *touched)
    print("SUMMARY fail=%d" % b.failures)
    sys.exit(1 if b.failures else 0)


if __name__ == "__main__":
    main()
