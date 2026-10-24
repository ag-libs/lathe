#!/usr/bin/env python3
"""MCP staleness probe: change a real workspace from OUTSIDE the MCP server (as an agent's own
file tools, git, or another process would) and check every answer reflects the disk at once.

    python3 -I dev/mcp_staleness.py <workspace> <dropwizard|equalsverifier|helidon> [scenario ...]

Restores every file it touched. One PASS/FAIL line per check; exit 1 on any FAIL. A check that
fails is retried after 3 s and 8 s and tagged [LAG] or [NEVER]. The branch scenario needs
LATHE_PROBE_OLD_REF (a git ref: its Java sources are checked out, the build files are kept).
Uses the launcher dev/mcp.py picks (LATHE_MCP_LAUNCHER, else the newest cached server).
"""
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mcp  # noqa: E402

CONFIGS = {
    "equalsverifier": dict(
        up_module="equalsverifier-core", up_pkg="nl.jqno.equalsverifier",
        up_src="equalsverifier-core/src/main/java",
        down_file="equalsverifier-test/src/test/java/nl/jqno/equalsverifier/integration/basic_contract/HashCodeTest.java",
        doc=("equalsverifier-core/src/main/java/nl/jqno/equalsverifier/Warning.java", "ALL_FIELDS_SHOULD_BE_USED",
             "equalsverifier-test/src/test/java/nl/jqno/equalsverifier/integration/basic_contract/TransitivityTest.java",
             "ALL_FIELDS_SHOULD_BE_USED"),
        iface=("equalsverifier-core/src/main/java/nl/jqno/equalsverifier/internal/checkers/Checker.java", "Checker {",
               "nl.jqno.equalsverifier.internal.checkers.Checker"),
        impl_dir=("equalsverifier-core/src/main/java/nl/jqno/equalsverifier", "nl.jqno.equalsverifier"),
        broken="equalsverifier-core/src/main/java/nl/jqno/equalsverifier/Warning.java",
        test=("equalsverifier-test/src/test/java/nl/jqno/equalsverifier/integration/basic_contract/HashCodeTest.java",
              "fail_whenHashCodesAreInconsistent"),
        pom="equalsverifier-core/pom.xml"),
    "dropwizard": dict(
        up_module="dropwizard-util", up_pkg="io.dropwizard.util",
        up_src="dropwizard-util/src/main/java",
        down_file="dropwizard-core/src/main/java/io/dropwizard/core/server/AbstractServerFactory.java",
        doc=("dropwizard-util/src/main/java/io/dropwizard/util/Duration.java", "toMilliseconds",
             "dropwizard-core/src/main/java/io/dropwizard/core/server/AbstractServerFactory.java", "toMilliseconds"),
        iface=("dropwizard-lifecycle/src/main/java/io/dropwizard/lifecycle/Managed.java", "Managed {",
               "io.dropwizard.lifecycle.Managed"),
        impl_dir=("dropwizard-core/src/main/java/io/dropwizard/core/server", "io.dropwizard.core.server"),
        broken="dropwizard-util/src/main/java/io/dropwizard/util/Duration.java",
        test=("dropwizard-util/src/test/java/io/dropwizard/util/DurationTest.java", "convertsDays"),
        pom="dropwizard-util/pom.xml"),
    "helidon": dict(
        up_module="common/media-type", up_pkg="io.helidon.common.media.type",
        up_src="common/media-type/src/main/java",
        down_file="http/http/src/main/java/io/helidon/http/HttpMediaTypes.java",
        doc=("common/media-type/src/main/java/io/helidon/common/media/type/MediaTypes.java",
             "public static MediaType create(String type, String subtype)",
             "http/http/src/main/java/io/helidon/http/AcceptQuery.java", "MediaTypes.create("),
        iface=("common/media-type/src/main/java/io/helidon/common/media/type/MediaType.java", "MediaType {",
               "io.helidon.common.media.type.MediaType"),
        impl_dir=("http/http/src/main/java/io/helidon/http", "io.helidon.http"),
        broken="common/media-type/src/main/java/io/helidon/common/media/type/MediaTypes.java",
        test=("common/media-type/src/test/java/io/helidon/common/media/type/MediaTypesTest.java", "testCustom"),
        pom="common/media-type/pom.xml",
        annot="@io.helidon.common.Api.Stable\n",
        gen=("common/configurable/src/main/java/io/helidon/common/configurable/AllowListConfigBlueprint.java",
             "common/configurable", "io/helidon/common/configurable/AllowListConfig.java",
             "webserver/webserver/src/test/java/io/helidon/webserver/TrustedProxyMatcherTest.java",
             "io.helidon.common.configurable.AllowListConfig")),
}

ERRORS = re.compile(r"\b(\d+) diagnostic\(s\)|cannot find symbol|error:", re.I)


def text_of(result):
    return "\n".join(c.get("text", "") for c in (result or {}).get("content", []))


def git(ws, *args):
    return subprocess.run(["git", "-C", str(ws), *args], capture_output=True, text=True).stdout


def locate(path: Path, token: str, skip_comments=True):
    for i, line in enumerate(path.read_text().splitlines()):
        c = line.find(token)
        if c < 0:
            continue
        if skip_comments and line.strip().startswith(("*", "//", "/*", "import ", "package ")):
            continue
        return i + 1, c + 1
    raise SystemExit("token %r not in %s" % (token, path))


def found(name):
    return lambda t: name in t and "No symbols" not in t


def gone(name):
    return lambda t: "No symbols" in t or name not in t


def annotated(cfg: dict, content: str) -> str:
    """Helidon's own processors run on in-process writes and reject a public type with no @Api.*."""
    return content.replace("\npublic ", "\n%spublic " % cfg.get("annot", ""))


def append_member(path: Path, member: str):
    src = path.read_text()
    cut = src.rindex("}")
    path.write_text(src[:cut] + member + "\n" + src[cut:])


class Probe:
    def __init__(self, ws: Path, cfg: dict):
        self.ws, self.cfg = ws, cfg
        self.client = None
        self.failures = 0
        self.created: list[Path] = []
        self.log: list[str] = []

    def start(self):
        self.client = mcp.McpClient(mcp._default_launcher(), self.ws)
        self.client.initialize()

    def stop(self):
        if self.client:
            self.client.close()
            self.client.proc.wait(timeout=30)
            self.client = None

    def p(self, rel):
        return self.ws / rel

    def record(self, passed, label, detail=""):
        self.failures += 0 if passed else 1
        line = "%s %-58s %s" % ("PASS" if passed else "FAIL", label, detail)
        print(line, flush=True)
        self.log.append(line)

    def call(self, tool, args, client=None):
        t0 = time.monotonic()
        try:
            r = (client or self.client).call_tool(tool, args)
        except Exception as e:  # noqa: BLE001
            r = {"isError": True, "content": [{"type": "text", "text": "EXC " + str(e)[:300]}]}
        return text_of(r), int((time.monotonic() - t0) * 1000)

    def check(self, label, tool, args, ok, client=None, show=False, settle=False):
        t, ms = self.call(tool, args, client)
        passed = bool(ok(t))
        head = " | ".join(x.strip() for x in t.strip().splitlines()[:2])[:150]
        note = ""
        if not passed and settle:
            waited = 0
            for wait in (3, 5):
                time.sleep(wait)
                waited += wait
                if ok(self.call(tool, args, client)[0]):
                    note = "  [LAG: correct after %ds]" % waited
                    break
            else:
                note = "  [NEVER: still wrong after %ds]" % waited
        self.record(passed, label, "%6dms  %s%s" % (ms, head, note))
        if show or not passed:
            print("      ---\n" + "\n".join("      " + x for x in t.splitlines()[:25]) + "\n      ---", flush=True)
        return t

    def new_file(self, rel, content):
        path = self.p(rel)
        path.write_text(annotated(self.cfg, content))
        if path not in self.created:
            self.created.append(path)
        return path

    def mirror_class(self, module, pkg, name, tree="classes"):
        return self.ws / ".lathe" / module / tree / pkg.replace(".", "/") / (name + ".class")

    def restore(self):
        for path in self.created:
            if path.exists():
                path.unlink()
        touched = [x for x in git(self.ws, "diff", "--name-only").split()
                   if not x.startswith(".mvn/")]
        if touched:
            git(self.ws, "checkout", "--", *touched)
        return touched


# ---------------------------------------------------------------- scenarios

def s_add_use_delete(pr: Probe):
    """New upstream type + nested + secondary type, used downstream; edit; rename; delete."""
    c = pr.cfg
    pkg, mod = c["up_pkg"], c["up_module"]
    rel = "%s/%s/LatheProbeType.java" % (c["up_src"], pkg.replace(".", "/"))
    src = (
        "package %s;\n\n"
        "/** Lathe probe type, first version. */\n"
        "public class LatheProbeType {\n"
        "  public static int latheProbeValue() { return 1; }\n\n"
        "  public static class Inner {\n    public static int innerValue() { return 2; }\n  }\n"
        "}\n\n"
        "class LatheProbeAux {\n  static int aux() { return 3; }\n}\n" % pkg)
    up = pr.new_file(rel, src)
    pr.check("A1 search_symbols finds an externally added type (immediately)", "search_symbols",
             {"query": "LatheProbeType"}, found("LatheProbeType"), settle=True)
    pr.record(pr.mirror_class(mod, pkg, "LatheProbeType").exists(), "A1b mirror has LatheProbeType.class")
    pr.record(pr.mirror_class(mod, pkg, "LatheProbeType$Inner").exists(), "A1c mirror has LatheProbeType$Inner.class")

    down = pr.p(c["down_file"])
    append_member(down, "\n  static int latheProbeUse() { return %s.LatheProbeType.latheProbeValue(); }\n"
                        "  static int latheProbeInner() { return %s.LatheProbeType.Inner.innerValue(); }\n"
                  % (pkg, pkg))
    pr.check("A2 downstream file using the new type compiles", "get_diagnostics", {"file": str(down)},
             lambda t: "No diagnostics" in t or ("error" not in t.lower() and "cannot find" not in t))
    ul, uc = locate(down, "latheProbeValue()")
    pr.check("A3 get_definition from downstream lands in the new file", "get_definition",
             {"file": str(down), "line": ul, "column": uc}, lambda t: "LatheProbeType.java" in t)
    dl, dc = locate(up, "latheProbeValue")
    pr.check("A4 find_references on new method finds the downstream use", "find_references",
             {"file": str(up), "line": dl, "column": dc}, lambda t: down.name in t)
    pr.check("A5 call_hierarchy incoming finds the downstream caller", "call_hierarchy",
             {"file": str(up), "line": dl, "column": dc, "direction": "incoming"}, lambda t: "latheProbeUse" in t)

    # external upstream rename of the method (file closed for the agent's purposes)
    up.write_text(up.read_text().replace("latheProbeValue()", "latheProbeValue2()"))
    pr.check("A6 downstream sees the upstream method rename as an error", "get_diagnostics", {"file": str(down)},
             lambda t: "cannot find symbol" in t)
    pr.check("A7 verify_change{} reports the broken downstream (MC-7)", "verify_change", {},
             lambda t: "cannot find symbol" in t or "error" in t.lower(), show=True)
    down.write_text(down.read_text().replace("latheProbeValue()", "latheProbeValue2()"))
    pr.check("A8 downstream fixed externally compiles", "get_diagnostics", {"file": str(down)},
             lambda t: "cannot find symbol" not in t)
    dl, dc = locate(up, "latheProbeValue2")
    pr.check("A8b find_references on renamed method finds downstream", "find_references",
             {"file": str(up), "line": dl, "column": dc}, lambda t: down.name in t)

    # drop the nested type and the secondary top-level type, keep the file
    up.write_text(up.read_text()
                  .replace("  public static class Inner {\n    public static int innerValue() { return 2; }\n  }\n", "")
                  .replace("class LatheProbeAux {\n  static int aux() { return 3; }\n}\n", ""))
    pr.check("A9 downstream use of a removed NESTED type is an error", "get_diagnostics", {"file": str(down)},
             lambda t: "cannot find symbol" in t or "Inner" in t, show=True)
    pr.record(not pr.mirror_class(mod, pkg, "LatheProbeType$Inner").exists(),
              "A9b mirror dropped LatheProbeType$Inner.class")
    pr.record(not pr.mirror_class(mod, pkg, "LatheProbeAux").exists(),
              "A9c mirror dropped secondary top-level LatheProbeAux.class")

    # rename the file + type (git mv style), downstream still on the old name
    moved = up.with_name("LatheProbeMoved.java")
    up.rename(moved)
    moved.write_text(moved.read_text().replace("class LatheProbeType", "class LatheProbeMoved"))
    pr.created.append(moved)
    pr.check("A10 search_symbols no longer finds the renamed-away type", "search_symbols",
             {"query": "LatheProbeType"}, gone("LatheProbeType"), settle=True)
    pr.check("A10b search_symbols finds the new type name", "search_symbols",
             {"query": "LatheProbeMoved"}, found("LatheProbeMoved"), settle=True)
    pr.check("A11 downstream still on the old type name gets an error", "get_diagnostics", {"file": str(down)},
             lambda t: "cannot find symbol" in t or "LatheProbeType" in t)
    pr.record(not pr.mirror_class(mod, pkg, "LatheProbeType").exists(),
              "A11b mirror dropped LatheProbeType.class after the file rename")

    moved.unlink()
    pr.check("A12 search_symbols after delete finds nothing", "search_symbols",
             {"query": "LatheProbeMoved"}, gone("LatheProbeMoved"), settle=True)
    pr.record(not pr.mirror_class(mod, pkg, "LatheProbeMoved").exists(), "A12b mirror dropped deleted class")

    git(pr.ws, "checkout", "--", c["down_file"])
    pr.check("A13 restored downstream compiles cleanly", "get_diagnostics", {"file": str(down)},
             lambda t: "Error [" not in t and "Not compiled" not in t)
    pr.check("A14 verify_change{} after full revert: no stale advice (MC-13)", "verify_change", {},
             lambda t: "lathe-sync" not in t and "cannot find" not in t, show=True)


def s_javadoc(pr: Probe):
    c = pr.cfg
    decl_rel, decl_tok, site_rel, site_tok = c["doc"]
    decl, site = pr.p(decl_rel), pr.p(site_rel)
    sl, sc = locate(site, site_tok)
    if site_tok.startswith("MediaTypes."):
        sc += len("MediaTypes.")
    pr.check("B0 describe_symbol at downstream call site (baseline)", "describe_symbol",
             {"file": str(site), "line": sl, "column": sc}, lambda t: len(t) > 20)
    dl, _ = locate(decl, decl_tok)
    lines = decl.read_text().splitlines(keepends=True)
    lines.insert(dl - 1, "    /** LATHEPROBEDOC external javadoc. */\n")
    # Drop the previous javadoc block, if any, so ours is the one attached.
    j = dl - 2
    if lines[j].strip().endswith("*/"):
        k = j
        while not lines[k].strip().startswith("/**"):
            k -= 1
        del lines[k:j + 1]
    decl.write_text("".join(lines))
    pr.check("B1 describe_symbol shows externally edited javadoc", "describe_symbol",
             {"file": str(site), "line": sl, "column": sc}, lambda t: "LATHEPROBEDOC" in t)
    git(pr.ws, "checkout", "--", decl_rel)
    pr.check("B2 describe_symbol drops it after revert", "describe_symbol",
             {"file": str(site), "line": sl, "column": sc}, lambda t: "LATHEPROBEDOC" not in t)


def s_implementer(pr: Probe):
    c = pr.cfg
    iface_rel, iface_tok, iface_fqn = c["iface"]
    impl_dir, impl_pkg = c["impl_dir"]
    iface = pr.p(iface_rel)
    il, ic = locate(iface, iface_tok)
    impl = pr.new_file("%s/LatheProbeImpl.java" % impl_dir,
                       "package %s;\n\npublic abstract class LatheProbeImpl implements %s {\n}\n"
                       % (impl_pkg, iface_fqn))
    pr.check("C1 find_implementations includes an externally added implementer", "find_implementations",
             {"file": str(iface), "line": il, "column": ic, "maxResults": 500}, lambda t: "LatheProbeImpl" in t, settle=True)
    impl.unlink()
    pr.check("C2 find_implementations drops it after external delete", "find_implementations",
             {"file": str(iface), "line": il, "column": ic, "maxResults": 500}, lambda t: "LatheProbeImpl" not in t)


def s_broken(pr: Probe):
    c = pr.cfg
    broken = pr.p(c["broken"])
    down = pr.p(c["doc"][2])
    append_member(broken, "    int latheProbeBroken() { return \"x\"; }\n")
    t = pr.check("D1 query elsewhere notes the externally broken file (Not compiled:)", "get_diagnostics",
                 {"file": str(down)}, lambda t: "Not compiled" in t and broken.name in t, show=True)
    pr.record("lathe-sync" not in t, "D1b broken code does not suggest a sync")
    git(pr.ws, "checkout", "--", c["broken"])
    pr.check("D2 note disappears once fixed externally", "get_diagnostics",
             {"file": str(down)}, lambda t: "Not compiled" not in t)


def s_verify_after_query(pr: Probe):
    c = pr.cfg
    broken = pr.p(c["broken"])
    down = pr.p(c["doc"][2])
    pr.check("K0 verify_change{} drains earlier changes", "verify_change", {}, lambda t: True)
    append_member(broken, "    int latheProbeBroken() { return \"x\"; }\n")
    pr.check("K1 verify_change{} right after an external break reports it", "verify_change", {},
             lambda t: "incompatible" in t or broken.name in t, show=True)
    git(pr.ws, "checkout", "--", c["broken"])
    pr.check("K2 verify_change{} drains the revert", "verify_change", {}, lambda t: True)
    append_member(broken, "    int latheProbeBroken() { return \"x\"; }\n")
    pr.check("K3 an unrelated query first (consumes the pass)", "get_diagnostics", {"file": str(down)},
             lambda t: True)
    pr.check("K4 verify_change{} still reports the external break", "verify_change", {},
             lambda t: "incompatible" in t or broken.name in t, show=True)
    git(pr.ws, "checkout", "--", c["broken"])


def s_pom(pr: Probe):
    c = pr.cfg
    pom = pr.p(c["pom"])
    down = pr.p(c["doc"][2])
    orig = pom.read_text()
    os.utime(pom)
    pr.check("F0 POM only touched: no Sync required", "get_diagnostics", {"file": str(down)},
             lambda t: "Sync required" not in t, show=True)
    pom.write_text(orig.replace("</project>", "<!-- latheprobe --></project>"))
    time.sleep(0.1)
    pr.check("F1 POM change yields Sync required", "get_diagnostics", {"file": str(down)},
             lambda t: "Sync required" in t, show=True, settle=True)
    pom.write_text(orig)
    pr.stop()
    pom.write_text(orig.replace("</project>", "<!-- latheprobe --></project>"))
    pr.start()
    pr.check("F3 POM changed while no session ran: Sync required", "get_diagnostics", {"file": str(down)},
             lambda t: "Sync required" in t, show=True, settle=True)
    pom.write_text(orig)
    pr.check("F2 POM back to the synced content: no Sync required", "get_diagnostics", {"file": str(down)},
             lambda t: "Sync required" not in t, show=True)


def s_cold_start(pr: Probe):
    """Edits made while no server runs must be caught up by the next session's first query."""
    c = pr.cfg
    pkg = c["up_pkg"]
    pr.stop()
    up = pr.new_file("%s/%s/LatheProbeCold.java" % (c["up_src"], pkg.replace(".", "/")),
                     "package %s;\n\npublic class LatheProbeCold {\n  public static int cold() { return 1; }\n}\n"
                     % pkg)
    down = pr.p(c["down_file"])
    append_member(down, "\n  static int latheProbeCold() { return %s.LatheProbeCold.cold(); }\n" % pkg)
    pr.start()
    pr.check("G1 new session: downstream use of offline-added type compiles", "get_diagnostics",
             {"file": str(down)}, lambda t: "cannot find symbol" not in t, show=True)
    pr.check("G2 new session: search_symbols finds offline-added type", "search_symbols",
             {"query": "LatheProbeCold"}, found("LatheProbeCold"), settle=True)
    pr.stop()
    up.unlink()
    pr.start()
    pr.check("G3 new session: offline delete makes downstream use an error", "get_diagnostics",
             {"file": str(down)}, lambda t: "cannot find symbol" in t, show=True)
    pr.record(not pr.mirror_class(c["up_module"], pkg, "LatheProbeCold").exists(),
              "G3b mirror dropped offline-deleted class")
    git(pr.ws, "checkout", "--", c["down_file"])


def s_run_test(pr: Probe):
    c = pr.cfg
    test_rel, method = c["test"]
    test = pr.p(test_rel)
    l, _ = locate(test, "void %s(" % method)
    lines = test.read_text().splitlines(keepends=True)
    k = l - 1
    while "{" not in lines[k]:
        k += 1
    lines.insert(k + 1, '        if (true) { throw new AssertionError("latheprobe-fail"); }\n')
    test.write_text("".join(lines))
    pr.check("H1 run_test replays the externally edited test (fails)", "run_test",
             {"file": str(test), "scope": "method", "method": method},
             lambda t: "latheprobe-fail" in t or "1 failed" in t, show=True)
    git(pr.ws, "checkout", "--", test_rel)
    pr.check("H2 run_test after external revert passes", "run_test",
             {"file": str(test), "scope": "method", "method": method}, lambda t: "1 passed" in t)


def s_two_sessions(pr: Probe):
    """Two agent sessions on one workspace: what one writes, the other must see."""
    c = pr.cfg
    pkg = c["up_pkg"]
    other = mcp.McpClient(mcp._default_launcher(), pr.ws)
    other.initialize()
    try:
        down = pr.p(c["down_file"])
        pr.check("I0 session B warm-up", "get_diagnostics", {"file": str(down)}, lambda t: True, client=other)
        up = pr.new_file("%s/%s/LatheProbeTwo.java" % (c["up_src"], pkg.replace(".", "/")),
                         "package %s;\n\npublic class LatheProbeTwo {\n  public static int two() { return 1; }\n}\n"
                         % pkg)
        append_member(down, "\n  static int latheProbeTwo() { return %s.LatheProbeTwo.two(); }\n" % pkg)
        pr.check("I1 session A compiles the new type", "get_diagnostics", {"file": str(down)},
                 lambda t: "cannot find symbol" not in t)
        up.write_text(up.read_text().replace("two()", "twoRenamed()"))
        pr.check("I2 session A sees the rename break downstream", "get_diagnostics", {"file": str(down)},
                 lambda t: "cannot find symbol" in t)
        pr.check("I3 session B (also stale-checking) sees the break too", "get_diagnostics", {"file": str(down)},
                 lambda t: "cannot find symbol" in t, client=other)
        up.write_text(up.read_text().replace("twoRenamed()", "two()"))
        pr.check("I4 session B sees the fix", "get_diagnostics", {"file": str(down)},
                 lambda t: "cannot find symbol" not in t, client=other)
        pr.check("I5 session A sees the fix", "get_diagnostics", {"file": str(down)},
                 lambda t: "cannot find symbol" not in t)
        git(pr.ws, "checkout", "--", c["down_file"])
        up.unlink()
        pr.check("I6 session B: search after delete finds nothing", "search_symbols", {"query": "LatheProbeTwo"},
                 gone("LatheProbeTwo"), client=other, settle=True)
    finally:
        other.close()


def s_branch_switch(pr: Probe):
    """Check out an older revision of the Java sources only (no build files) and back."""
    ref = os.environ.get("LATHE_PROBE_OLD_REF")
    if not ref:
        pr.record(True, "E  branch switch skipped (set LATHE_PROBE_OLD_REF)")
        return
    changed = [x for x in git(pr.ws, "diff", "--name-only", ref, "HEAD", "--", "*.java").split()]
    existing = [x for x in changed if git(pr.ws, "cat-file", "-t", "%s:%s" % (ref, x)).strip() == "blob"]
    added_since = [x for x in changed if x not in existing]
    print("      branch switch: %d java files differ (%d not in %s)" % (len(changed), len(added_since), ref))
    at_head = [x for x in changed if git(pr.ws, "cat-file", "-t", "HEAD:%s" % x).strip() == "blob"]
    git(pr.ws, "checkout", ref, "--", *existing)
    git(pr.ws, "reset", "-q", "--", *changed)
    for x in added_since:
        (pr.ws / x).unlink(missing_ok=True)
    t0 = time.monotonic()
    pr.check("E1 [info] verify_change{} after a source-only switch to %s" % ref, "verify_change", {},
             lambda t: True, show=True)
    pr.record(True, "E1 catch-up wall time", "%.1fs" % (time.monotonic() - t0))
    gone_files = [x for x in at_head if x not in existing]
    for x in gone_files[:2]:
        name = Path(x).stem
        pr.check("E2 search_symbols drops type deleted by the switch: %s" % name, "search_symbols",
                 {"query": name}, lambda t, n=name: "No symbols" in t or ("%s (" % n) not in t, settle=True)
    git(pr.ws, "checkout", "HEAD", "--", *at_head)
    for x in changed:
        if x not in at_head:
            (pr.ws / x).unlink(missing_ok=True)
    left = [x for x in git(pr.ws, "status", "--porcelain").splitlines() if ".mvn/" not in x]
    pr.record(not left, "E3 probe restored HEAD sources", str(left[:3]))
    t0 = time.monotonic()
    pr.check("E4 verify_change{} back on HEAD: no errors", "verify_change", {},
             lambda t: "Error [" not in t and "Not compiled" not in t, show=True)
    pr.record(True, "E4 catch-up wall time", "%.1fs" % (time.monotonic() - t0))
    sample = [x for x in at_head if "/src/main/" in x][:4]
    for x in sample:
        pr.check("E5 back on HEAD, clean + no notes: %s" % Path(x).name, "get_diagnostics",
                 {"file": str(pr.ws / x)}, lambda t: "Error [" not in t and "Not compiled" not in t)
    for x in gone_files[:2]:
        name = Path(x).stem
        pr.check("E6 search_symbols finds the type back on HEAD: %s" % name, "search_symbols",
                 {"query": name}, found(name), settle=True)


def s_resource(pr: Probe):
    """A test resource added and deleted outside: the replayed test must see exactly the disk."""
    c = pr.cfg
    test_rel, method = c["test"]
    test = pr.p(test_rel)
    res_dir = pr.p(test_rel.split("/src/test/java/")[0] + "/src/test/resources")
    res_dir.mkdir(exist_ok=True)
    res = pr.new_file(str((res_dir / "latheprobe.txt").relative_to(pr.ws)), "probe\n")
    l, _ = locate(test, "void %s(" % method)
    lines = test.read_text().splitlines(keepends=True)
    k = l - 1
    while "{" not in lines[k]:
        k += 1
    lines.insert(k + 1, '        if (getClass().getResource("/latheprobe.txt") == null) '
                        '{ throw new AssertionError("latheprobe-noresource"); }\n')
    test.write_text("".join(lines))
    pr.check("L1 run_test sees an externally added test resource", "run_test",
             {"file": str(test), "scope": "method", "method": method}, lambda t: "1 passed" in t, show=True,
             settle=True)
    res.unlink()
    pr.check("L2 run_test after the resource is deleted fails (no stale copy)", "run_test",
             {"file": str(test), "scope": "method", "method": method},
             lambda t: "latheprobe-noresource" in t, show=True, settle=True)


def s_build(pr: Probe):
    """A reactor build (the sync script) running while the agent queries, and edits made during it."""
    c = pr.cfg
    pkg = c["up_pkg"]
    down = pr.p(c["down_file"])
    log = open(Path(tempfile.gettempdir()) / ("lathe-probe-build-%s.log" % pr.ws.name), "w")
    proc = subprocess.Popen([str(pr.ws / ".lathe/lathe-sync.sh"), c["up_module"]], cwd=pr.ws,
                            stdout=log, stderr=subprocess.STDOUT)
    lock = pr.ws / ".lathe/lathe.lock"
    for _ in range(600):
        if lock.exists() or proc.poll() is not None:
            break
        time.sleep(0.1)
    pr.record(lock.exists(), "M0 reactor lock appears during the sync")
    up = pr.new_file("%s/%s/LatheProbeBuild.java" % (c["up_src"], pkg.replace(".", "/")),
                     "package %s;\n\npublic class LatheProbeBuild {\n  public static int b() { return 1; }\n}\n"
                     % pkg)
    append_member(down, "\n  static int latheProbeBuild() { return %s.LatheProbeBuild.b(); }\n" % pkg)
    pr.check("M1 query during the build says a build is running", "get_diagnostics", {"file": str(down)},
             lambda t: "build" in t.lower() and "running" in t.lower(), show=True)
    rc = proc.wait(timeout=900)
    pr.record(rc == 0, "M2 sync finished", "rc=%d" % rc)
    pr.check("M3 after the build, the edit made during it is caught up", "get_diagnostics", {"file": str(down)},
             lambda t: "cannot find symbol" not in t, show=True, settle=True)
    git(pr.ws, "checkout", "--", c["down_file"])
    up.unlink()
    pr.check("M4 after the build, the delete is caught up", "search_symbols", {"query": "LatheProbeBuild"},
             gone("LatheProbeBuild"), settle=True)


def s_generated(pr: Probe):
    """An external blueprint edit must regenerate the annotation-processor output downstream sees."""
    if "gen" not in pr.cfg:
        pr.record(True, "N  generated-sources scenario not configured")
        return
    bp_rel, mod, gen_rel, down_rel, gen_fqn = pr.cfg["gen"]
    bp, down = pr.p(bp_rel), pr.p(down_rel)
    append_member(bp, "\n    /**\n     * Lathe probe option.\n     *\n     * @return probe\n     */\n"
                      '    @Option.Configured("lathe.probe")\n    @Option.Default("lathe")\n'
                      "    String latheProbeOpt();\n")
    append_member(down, "\n    static Object latheProbeGen() { return %s.builder().latheProbeOpt(\"x\"); }\n" % gen_fqn)
    pr.check("N1 downstream uses a builder method generated from an external blueprint edit", "get_diagnostics",
             {"file": str(down)}, lambda t: "cannot find symbol" not in t and "Error [" not in t, show=True,
             settle=True)
    gens = list((pr.ws / ".lathe" / mod).glob("generated-sources*/**/" + Path(gen_rel).name))
    pr.record(any("latheProbeOpt" in g.read_text() for g in gens), "N1b .lathe generated mirror has the option",
              str([str(g.relative_to(pr.ws)) for g in gens])[:150])
    git(pr.ws, "checkout", "--", bp_rel)
    pr.check("N2 removing the option externally breaks the downstream use", "get_diagnostics",
             {"file": str(down)}, lambda t: "cannot find symbol" in t, show=True, settle=True)


# In run order; build runs the workspace's sync script, and branch needs LATHE_PROBE_OLD_REF.
SCENARIOS = dict(add=s_add_use_delete, javadoc=s_javadoc, impl=s_implementer, broken=s_broken,
                 verify=s_verify_after_query, pom=s_pom, cold=s_cold_start, runtest=s_run_test,
                 resource=s_resource, two=s_two_sessions, gen=s_generated, build=s_build,
                 branch=s_branch_switch)


def main():
    ws, cfg = Path(sys.argv[1]).resolve(), CONFIGS[sys.argv[2]]
    names = sys.argv[3:] or list(SCENARIOS)
    dirty = [x for x in git(ws, "status", "--porcelain").splitlines() if ".mvn/" not in x]
    if dirty:
        raise SystemExit("workspace not clean: %s" % dirty[:5])
    pr = Probe(ws, cfg)
    pr.start()
    try:
        for n in names:
            print("== scenario %s" % n, flush=True)
            try:
                SCENARIOS[n](pr)
            except Exception as e:  # noqa: BLE001
                pr.record(False, "scenario %s crashed" % n, repr(e)[:200])
            finally:
                pr.restore()
                if pr.client is None:
                    pr.start()
    finally:
        pr.stop()
        touched = pr.restore()
        left = [x for x in git(ws, "status", "--porcelain").splitlines() if ".mvn/" not in x]
        print("RESTORED", touched, "LEFT", left)
    print("SUMMARY fail=%d" % pr.failures)
    sys.exit(1 if pr.failures else 0)


if __name__ == "__main__":
    main()
