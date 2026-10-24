#!/usr/bin/env python3
"""LSP counterpart of mcp_staleness.py: the same outside changes, seen by an editor session.

The editor server reacts on its reconcile tick, not per query, so each check polls until a
deadline (LATHE_PROBE_DEADLINE, default 15 s) and reports how long the answer took to become
right, or that it never did. Diagnostics are the ones the server last pushed for an open file, as
an editor shows them (a didClose publishes an empty list, so reopening a file would hide them).

    python3 -I dev/lsp_staleness.py <workspace> <dropwizard|equalsverifier|helidon> [scenario ...]
"""
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from lsp import LatheClient  # noqa: E402
from mcp_staleness import CONFIGS, annotated, append_member, git, locate  # noqa: E402

DEADLINE = float(os.environ.get("LATHE_PROBE_DEADLINE", "15"))


class Probe:
    def __init__(self, ws: Path, cfg: dict):
        self.ws, self.cfg = ws, cfg
        self.c = None
        self.failures = 0
        self.created: list[Path] = []
        self.prompts: list[str] = []
        self.latest: dict = {}

    def start(self):
        self.prompts = []
        self.c = LatheClient.start(self.ws)
        self.c.on_server_request = self._on_request
        # Dismiss each prompt (a null answer), as a user closing it would: left unanswered, the
        # server rightly suppresses the next one, e.g. a startup prompt hides a later live edit's.
        self.c._NULL_RESULT_REQUESTS = {*LatheClient._NULL_RESULT_REQUESTS, "window/showMessageRequest"}

    def _on_request(self, msg):
        if msg["method"] == "window/showMessageRequest":
            self.prompts.append(msg["params"].get("message", ""))

    def stop(self):
        if self.c:
            self.c.stop()
            self.c = None

    def p(self, rel):
        return self.ws / rel

    def record(self, passed, label, detail=""):
        self.failures += 0 if passed else 1
        print("%s %-62s %s" % ("PASS" if passed else "FAIL", label, detail), flush=True)

    def poll(self, label, probe, ok, deadline=DEADLINE):
        """Re-run probe until ok(result) or the deadline; report the time it took."""
        t0 = time.monotonic()
        last = None
        while True:
            try:
                last = probe()
            except Exception as e:  # noqa: BLE001
                last = "EXC %s" % e
            if ok(last):
                self.record(True, label, "after %.1fs" % (time.monotonic() - t0))
                return last
            if time.monotonic() - t0 > deadline:
                self.record(False, label, "[NEVER within %ds] %s" % (deadline, str(last)[:160]))
                return last
            time.sleep(0.5)

    def diags(self, file: Path):
        """The latest diagnostics the server pushed for an open file, as the editor shows them."""
        q = self.c._notification_queue("textDocument/publishDiagnostics")
        while not q.empty():
            m = q.get_nowait()
            self.latest[m["params"]["uri"]] = m["params"]["diagnostics"]
        return self.latest.get(file.resolve().as_uri())

    def open(self, file: Path):
        self.latest[file.resolve().as_uri()] = self.c.open(file, timeout=60)

    def mirror(self, module, rel, tree="classes"):
        return self.ws / ".lathe" / module / tree / rel

    def new_file(self, rel, content):
        path = self.p(rel)
        path.write_text(annotated(self.cfg, content))
        self.created.append(path)
        return path

    def restore(self):
        for path in self.created:
            path.unlink(missing_ok=True)
        self.created = []
        touched = [x for x in git(self.ws, "diff", "--name-only").split() if not x.startswith(".mvn/")]
        if touched:
            git(self.ws, "checkout", "--", *touched)


def msgs(diagnostics):
    return " | ".join(d.get("message", "").splitlines()[0] for d in diagnostics or [] if d.get("severity") == 1)


def errors_with(text):
    return lambda d: isinstance(d, list) and text in msgs(d)


def no_errors(d):
    return isinstance(d, list) and not msgs(d)


def symbol_found(name):
    return lambda r: isinstance(r, list) and any(s.get("name") == name for s in r)


def symbol_gone(name):
    return lambda r: isinstance(r, list) and not any(s.get("name") == name for s in r)


# ------------------------------------------------------------------------- scenarios

def s_basics(pr: Probe):
    """External add / upstream rename / delete with the downstream file open in the editor."""
    c = pr.cfg
    pkg = c["up_pkg"]
    down = pr.p(c["down_file"])
    append_member(down, "\n  static int latheProbeUse() { return %s.LatheProbeLsp.v(); }\n" % pkg)
    pr.open(down)
    up = pr.new_file("%s/%s/LatheProbeLsp.java" % (c["up_src"], pkg.replace(".", "/")),
                     "package %s;\n\npublic class LatheProbeLsp {\n  public static int v() { return 1; }\n}\n" % pkg)
    pr.poll("P1 open downstream: external add resolves", lambda: pr.diags(down), no_errors)
    pr.poll("P2 workspace/symbol finds the external add (MC-15 analog)",
            lambda: pr.c.workspace_symbol("LatheProbeLsp"), symbol_found("LatheProbeLsp"))
    up.write_text(up.read_text().replace("int v()", "int v2()"))
    pr.poll("P3 open downstream: upstream rename becomes an error", lambda: pr.diags(down),
            errors_with("cannot find symbol"))
    up.unlink()
    pr.poll("P4 workspace/symbol drops the deleted type", lambda: pr.c.workspace_symbol("LatheProbeLsp"),
            symbol_gone("LatheProbeLsp"))
    pr.c.close(down)


def s_impl(pr: Probe):
    c = pr.cfg
    iface_rel, iface_tok, iface_fqn = c["iface"]
    impl_dir, impl_pkg = c["impl_dir"]
    iface = pr.p(iface_rel)
    pr.c.open(iface, timeout=60)
    il, ic = locate(iface, iface_tok)
    pr.new_file("%s/LatheProbeImpl.java" % impl_dir,
                "package %s;\n\npublic abstract class LatheProbeImpl implements %s {\n}\n" % (impl_pkg, iface_fqn))
    pr.poll("C1 textDocument/implementation includes an external implementer (MC-15 analog)",
            lambda: pr.c.implementation(iface, il - 1, ic - 1),
            lambda r: isinstance(r, list) and any("LatheProbeImpl" in str(x) for x in r))
    pr.c.close(iface)


def s_pom(pr: Probe):
    """WS-13 / MC-14 analog: does the editor get a sync prompt for a POM change, live and cold?"""
    pom = pr.p(pr.cfg["pom"])
    orig = pom.read_text()
    pr.prompts.clear()
    pom.write_text(orig.replace("</project>", "<!-- latheprobe --></project>"))
    pr.poll("F1 live POM change prompts a sync", lambda: list(pr.prompts), lambda p: bool(p))
    pom.write_text(orig)
    time.sleep(3)
    pr.stop()
    pom.write_text(orig.replace("</project>", "<!-- latheprobe2 --></project>"))
    pr.start()
    pr.poll("F3 POM changed while no server ran prompts a sync (WS-13)", lambda: list(pr.prompts),
            lambda p: bool(p))
    pom.write_text(orig)


def s_resource(pr: Probe):
    """WS-14: a deleted resource must leave the mirror."""
    test_rel = pr.cfg["test"][0]
    module_rel = test_rel.split("/src/test/java/")[0]
    res_dir = pr.p(module_rel + "/src/test/resources")
    made_dir = not res_dir.exists()
    res_dir.mkdir(exist_ok=True)
    res = pr.new_file(str((res_dir / "latheprobe.txt").relative_to(pr.ws)), "probe\n")
    copy = pr.mirror(module_rel, "latheprobe.txt", "test-classes")
    pr.poll("L1 an external resource add is copied to the mirror", lambda: copy.exists(), bool)
    res.unlink()
    pr.poll("L2 an external resource delete leaves the mirror (WS-14)", lambda: not copy.exists(), bool)
    copy.unlink(missing_ok=True)
    if made_dir:
        res_dir.rmdir()


def s_generated(pr: Probe):
    """WS-15: does an external edit, or an editor save, of a blueprint regenerate its builder?"""
    if "gen" not in pr.cfg:
        pr.record(True, "N  generated-sources scenario not configured")
        return
    bp_rel, mod, gen_rel, down_rel, gen_fqn = pr.cfg["gen"]
    bp, down = pr.p(bp_rel), pr.p(down_rel)
    gen = pr.ws / ".lathe" / mod / "generated-sources" / gen_rel
    option = ('\n    /**\n     * Lathe probe option.\n     *\n     * @return probe\n     */\n'
              '    @Option.Configured("lathe.probe")\n    @Option.Default("lathe")\n    String %s();\n')
    append_member(bp, option % "latheProbeOpt")
    pr.poll("N1 external blueprint edit regenerates the builder (WS-15)",
            lambda: "latheProbeOpt" in gen.read_text(), bool)
    git(pr.ws, "checkout", "--", bp_rel)
    time.sleep(4)
    # The editor path: open, edit the buffer, write the disk, didSave (what :w does).
    pr.c.open(bp, timeout=60)
    src = bp.read_text()
    cut = src.rindex("}")
    edited = src[:cut] + option % "latheProbeSaved" + "\n" + src[cut:]
    pr.c.change(bp, edited, version=2)
    bp.write_text(edited)
    try:
        pr.c.save(bp, timeout=60)
    except TimeoutError:
        pass
    pr.poll("N2 editor save of the blueprint regenerates the builder (WS-15 / EG-052)",
            lambda: "latheProbeSaved" in gen.read_text(), bool)
    append_member(down, "\n    static Object latheProbeGen() { return %s.builder().latheProbeSaved(\"x\"); }\n"
                  % gen_fqn)
    pr.open(down)
    pr.poll("N3 a dependent using the saved option compiles", lambda: pr.diags(down), no_errors)
    pr.c.close(down)
    pr.c.close(bp)


SCENARIOS = dict(basics=s_basics, impl=s_impl, pom=s_pom, resource=s_resource, gen=s_generated)


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
                if pr.c is None:
                    pr.start()
                pr.restore()
                time.sleep(3)  # let the server catch up with the restore before the next scenario
    finally:
        pr.stop()
        pr.restore()
        left = [x for x in git(ws, "status", "--porcelain").splitlines() if ".mvn/" not in x]
        print("LEFT", left)
    print("SUMMARY fail=%d" % pr.failures)
    sys.exit(1 if pr.failures else 0)


if __name__ == "__main__":
    main()
