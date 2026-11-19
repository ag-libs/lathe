"""Server-free checks of the harness's scenario logic, against a fake client, in seconds: run it
after changing bench.py, before a run that takes hours.

    python3 -I dev/lsp-bench/selfcheck.py
"""
import pathlib
import sys
import shutil
import tempfile
import threading
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import bench  # noqa: E402
import json  # noqa: E402
import report  # noqa: E402
import stage2  # noqa: E402

class Fake:
    def __init__(self):
        self.diagnostics, self.status, self.progress = bench.StampedQueue(), bench.StampedQueue(), bench.StampedQueue()
        self.sent = []
    def notify(self, method, params):
        self.sent.append((method, params))

def publish(c, uri, errors):
    c.diagnostics.put({"params": {"uri": uri, "diagnostics": [{"severity": 1}] * errors}})

ok = True
def check(name, cond):
    global ok
    ok &= bool(cond)
    print("PASS" if cond else "FAIL", name)

# until_errors: a momentary empty publish must not count when a hold is asked for.
c = Fake(); publish(c, "u", 1)
def flap():
    time.sleep(0.3); publish(c, "u", 0)   # transient clear
    time.sleep(0.3); publish(c, "u", 1)   # error back
    time.sleep(0.5); publish(c, "u", 0)   # really fixed
threading.Thread(target=flap).start()
origin = time.monotonic()
ms = bench.until_errors(c, "u", lambda n: n == 0, 10, hold_s=1.0, origin=origin)
check("until_errors ignores a transient clear (%.0f ms)" % ms, ms is not None and ms >= 1000)
check("until_errors: no hold returns at once", bench.until_errors(c, "u", lambda n: n == 0, 1) is not None)
check("until_errors: None at the deadline", bench.until_errors(c, "u", lambda n: n == 5, 0.5) is None)
check("errors_in: nothing published is None", bench.errors_in(c, "other") is None)

# wait_quiet: an open progress token keeps it waiting; its end lets it go after QUIET_S.
bench.QUIET_S = 0.5
c = Fake()
c.progress.put({"params": {"token": "t", "value": {"kind": "begin"}}})
threading.Thread(target=lambda: (time.sleep(1.0), c.progress.put({"params": {"token": "t", "value": {"kind": "end"}}}))).start()
ms = bench.wait_quiet(c, 5)
check("wait_quiet waits for the open token and QUIET_S (%.0f ms)" % ms, 1400 <= ms < 3000)
c = Fake()
check("wait_quiet: nothing ever sent is quiet at once", bench.wait_quiet(c, 5) < 600)

# reload: only changed, gone or returned files are sent.
repo = pathlib.Path(tempfile.mkdtemp())
(repo / "A.java").write_text("a"); (repo / "B.java").write_text("b"); (repo / "C.java").write_text("c")
texts = {"A.java": "a", "B.java": "b", "C.java": None}
(repo / "B.java").write_text("b2"); (repo / "A.java").unlink()
c = Fake(); bench.reload(c, repo, texts)
methods = sorted(m for m, _ in c.sent)
check("reload: close gone, change edited, open returned, skip unchanged (%s)" % methods,
      methods == ["textDocument/didChange", "textDocument/didClose", "textDocument/didOpen"])
check("reload updates texts", texts == {"A.java": None, "B.java": "b2", "C.java": "c"})
c = Fake(); bench.reload(c, repo, texts)
check("reload: a second reload with nothing changed sends nothing", c.sent == [])
shutil.rmtree(repo)

# stage2.injection: after a class's opening line; an enum or an annotation type is left out.
text, line = stage2.injection("package p;\n\n@Deprecated\npublic final class A\n    extends B {\n  int x;\n}\n")
check("injection: after the opening line of a two-line declaration (line %d)" % line,
      line == 6 and text.split("\n")[5] == stage2.INJECTED)
check("injection: a record takes it", stage2.injection("record R(int a) {\n}\n")[1] == 2)
check("injection: an interface takes it", stage2.injection("public interface I {\n}\n")[1] == 2)
check("injection: an enum is left out", stage2.injection("public enum E {\n  A, B\n}\n") is None)
check("injection: an annotation type is left out", stage2.injection("public @interface X {\n}\n") is None)
check("injection: annotations and modifiers on the declaration's line",
      stage2.injection('@SuppressWarnings("x") public @Ann final class Foo {\n}\n')[1] == 2)
check("injection: a body closed on its opening line is left out", stage2.injection("class Empty {}\n") is None)
check("injection: braces in an annotation before the type are not its body",
      stage2.injection('@SuppressWarnings({"a", "b"}) public class X {\n}\n')[1] == 2)
check("injection: a nested class is not the top-level type",
      stage2.injection("enum E {\n  A;\n  class In {\n  }\n}\n") is None)

# stage2.parse: Maven's lines, relative paths, each message once, other files' lines ignored.
output = """[INFO] Compiling 3 source files
[ERROR] /w/copy/m/src/main/java/p/A.java:[6,26] incompatible types: String cannot be converted to int
[WARNING] /w/copy/m/src/main/java/p/A.java:[9,5] [deprecation] f() in B has been deprecated
[ERROR] /w/copy/m/src/main/java/p/A.java:[6,26] incompatible types: String cannot be converted to int
[ERROR] /elsewhere/X.java:[1,1] not ours
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin"""
parsed = stage2.parse(output, "/w/copy")
check("parse: errors and warnings by relative path, once (%s)" % parsed,
      parsed == {"m/src/main/java/p/A.java": {"errors": [6], "warnings": [9]}})

# stage2.compare: both ways, clean and injected.
truth = {"clean": {"A.java": {"errors": [], "warnings": [9]}, "B.java": {"errors": [], "warnings": []}},
         "injected": {"A.java": {"line": 6, "errors": [6]}, "B.java": {"line": 3, "errors": [3]}}}
seen = {"clean": {"A.java": {"errors": [12], "warnings": []}, "B.java": {"errors": [], "warnings": [4]}},
        "injected": {"A.java": {"line": 6, "errors": [6, 12]}, "B.java": {"line": 3, "errors": None}}}
c = stage2.compare(truth, seen)
check("compare: a false error, a missed injection that never settled, an extra error (%s)" % c,
      c["false_errors"] == 1 and c["missed_injected"] == 1 and c["injected"] == 2
      and c["extra_injected_errors"] == 1 and c["unsettled"] == ["B.java"]
      and c["extra_warnings"] == 1 and c["missed_warnings"] == 1)

# plan_cases: what each kind of run asks.
cases = [{"name": "def", "method": "textDocument/definition"}, {"name": "c", "method": "textDocument/completion"},
         {"name": "t", "method": "textDocument/publishDiagnostics"}, {"name": "i", "method": "textDocument/codeAction",
                                                                     "import_removed": ("f", "import x;")}]
names = lambda plan: [c["name"] for c in bench.plan_cases(cases, plan)]
check("plan_cases: ready none, start navigation, parity adds editing, errors the typing",
      names("ready") == [] and names("start") == ["def"] and names("parity") == ["def", "c", "i"]
      and names("errors") == ["t"])

# session_runs: the runs of each group.
class A:
    prime, group, cold, warm, change_reps = False, "start", 2, 3, 3
runs = bench.session_runs("lathe", A, ["branch", "build"])
check("session_runs: start is 2 cold, then 3 warm with the first the parity run",
      [(m, p) for m, p, _ in runs] == [("cold", "ready")] * 2 + [("warm", "parity"), ("warm", "start"), ("warm", "start")])
A.group = "changes"
check("session_runs: changes is 3 of each outside change",
      [p for _m, p, _ in bench.session_runs("jdtls", A, ["branch", "build"])] == ["branch"] * 3 + ["build"] * 3)
A.prime = True
check("session_runs: a priming run is one cold start", bench.session_runs("lathe", A, []) == [("cold", "ready", "lathe-prime")])

# step_failed: only a step that exited non-zero.
check("step_failed: a sync that exits 1", bench.step_failed({"step_exit": 1}))
check("step_failed: a sync that passed, and jdtls's notification",
      not bench.step_failed({"step_exit": 0}) and not bench.step_failed({"step_ms": 0}))

# report: spreads, setup counts and a headline from fake sessions.
check("spread: median and range", report.spread([3000, 1000, 2000], " s", 1000, 1) == "2.0 s (1.0–3.0)")
check("spread: one value alone", report.spread([1500], " s", 1000, 1) == "1.5 s")
check("with_never: some runs never got there",
      report.with_never([1000, None, 3000], " s", 1000, 1) == "2.0 s (1.0–3.0) (never in 1 of 3)")
check("with_never: none got there", report.with_never([None, None], " s", 1000, 1) == "never")
fake = [(pathlib.Path("d"), {}, {"jdtls": [{"plan": "ready", "error": "no attempt ran", "not_started": True},
                                            {"plan": "ready", "ready_ms": 5}]})]
check("run_values: a run the harness never started is not the server's never",
      report.run_values(fake, "jdtls", ("ready",), lambda r: r["ready_ms"]) == [5])
counts = report.setup_counts("jdtls")
check("setup_counts: jdtls's minimal setup (%s)" % counts, counts and counts["downloads"] == 1 and counts["commands"] == 2)
results = pathlib.Path(tempfile.mkdtemp())
session = results / "20261120-000000-demo-start"
session.mkdir()
env = {"project": "demo", "project_commit": "0" * 40, "command": "bench.py demo --group start",
       "versions": {"lathe": "L", "jdtls": "J"}}
(session / "environment.json").write_text(json.dumps(env))
answer = {"names": [], "locations": [{"project": True, "path": "A.java", "line": 3}], "text": ""}
for server, ready in (("lathe", 2000), ("jdtls", 9000)):
    (session / ("%s-cold-1.json" % server)).write_text(json.dumps(
        {"server": server, "mode": "cold", "plan": "ready", "cases": {}, "build_ms": 60000, "ready_ms": ready}))
    (session / ("%s-warm-1.json" % server)).write_text(json.dumps(
        {"server": server, "mode": "warm", "plan": "parity", "ready_ms": ready / 2, "peak_mb": 900, "idle_mb": 700,
         "cases": {"references: x": {"method": "textDocument/references", "supported": True, "correct": True,
                                     "answer": answer},
                   "inlay": {"method": "x", "supported": server == "jdtls", "correct": server == "jdtls"}}}))
variant = results / "20261120-000001-demo-start-heap4G"
variant.mkdir()
(variant / "environment.json").write_text(json.dumps(dict(env, heap="4G")))
check("sessions: a labelled variant is left out of the headline", "demo" not in report.sessions([variant]))
check("load: Lathe first, though its files sort after jdtls's", list(report.load(session)) == ["lathe", "jdtls"])
lines = report.headline([session])
table = "\n".join(lines)
check("headline: first answer is build plus ready", "| Fresh checkout to first answer | 62.0 s" in table)
check("headline: parity counts the right rows", "| Features with the same answers | 1 of 2 | 2 of 2 |" in table)
check("headline: set-valued rows agree", "| references: x | ✓ | ✓ | yes |" in table)
shutil.rmtree(results)
print("ALL PASS" if ok else "SOME FAILED")
sys.exit(0 if ok else 1)
