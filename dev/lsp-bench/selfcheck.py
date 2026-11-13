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
print("ALL PASS" if ok else "SOME FAILED")
sys.exit(0 if ok else 1)
