#!/usr/bin/env python3
"""Editor (LSP) and agent (MCP) servers on one EqualsVerifier workspace at the same time.

    python3 -I dev/mixed_staleness.py <equalsverifier-workspace>

Behaves like a user with Neovim open while an agent works: the editor reloads a buffer whose file
the agent rewrote (didChange with the disk content), and saves its own edits (didChange, write,
didSave). Restores every file it touched; exit 1 on any FAIL.
"""
import glob
import re
import subprocess
import sys
import time
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent))
import mcp  # noqa: E402
from lsp import LatheClient  # noqa: E402

ws = Path(sys.argv[1]).resolve()
WARNING = ws / "equalsverifier-core/src/main/java/nl/jqno/equalsverifier/Warning.java"
OPEN = ws / "equalsverifier-test/src/test/java/nl/jqno/equalsverifier/integration/basic_contract/TransitivityTest.java"
PROBE = ws / "equalsverifier-test/src/test/java/nl/jqno/equalsverifier/integration/basic_contract/LatheMixedProbe.java"
failures = 0
created = []


def record(ok, label, detail=""):
    global failures
    failures += 0 if ok else 1
    print("%s %-64s %s" % ("PASS" if ok else "FAIL", label, detail), flush=True)


def git(*args):
    return subprocess.run(["git", "-C", str(ws), *args], capture_output=True, text=True).stdout


def text_of(result):
    return "\n".join(c.get("text", "") for c in (result or {}).get("content", []))


def errors(diagnostics):
    return [d["message"].splitlines()[0] for d in diagnostics or [] if d.get("severity") == 1]


class Editor:
    """An editor session: open buffers, reload on disk change, save edits."""

    def __init__(self):
        self.c = LatheClient.start(ws, debug=True)
        self.c._NULL_RESULT_REQUESTS = {*LatheClient._NULL_RESULT_REQUESTS, "window/showMessageRequest"}
        self.versions = {}
        self.buffers = {}
        self.latest = {}

    def open(self, file):
        self.latest[file.as_uri()] = self.c.open(file, timeout=60)
        self.versions[file] = 1
        self.buffers[file] = file.read_text()

    def reload_changed(self):
        """What autoread does: a buffer whose file changed on disk gets the new content."""
        for file in list(self.buffers):
            disk = file.read_text()
            if disk != self.buffers[file]:
                self.versions[file] += 1
                self.c.change(file, disk, self.versions[file])
                self.buffers[file] = disk

    def save(self, file, content):
        self.versions[file] += 1
        self.c.change(file, content, self.versions[file])
        file.write_text(content)
        self.buffers[file] = content
        try:
            self.c.save(file, timeout=60)
        except TimeoutError:
            pass

    def diags(self, file):
        q = self.c._notification_queue("textDocument/publishDiagnostics")
        while not q.empty():
            m = q.get_nowait()
            self.latest[m["params"]["uri"]] = m["params"]["diagnostics"]
        return self.latest.get(file.as_uri())


def poll(label, probe, ok, deadline=20):
    t0 = time.monotonic()
    last = None
    while time.monotonic() - t0 < deadline:
        last = probe()
        if ok(last):
            record(True, label, "after %.1fs" % (time.monotonic() - t0))
            return last
        time.sleep(0.5)
    record(False, label, "[NEVER within %ds] %s" % (deadline, str(last)[:200]))
    return last


def locate(path, token):
    for i, line in enumerate(path.read_text().splitlines()):
        c = line.find(token)
        if c >= 0 and not line.strip().startswith(("*", "//")):
            return i + 1, c + 1
    raise SystemExit("token %r not in %s" % (token, path))


dirty = [x for x in git("status", "--porcelain").splitlines() if ".mvn/" not in x]
if dirty:
    raise SystemExit("workspace not clean: %s" % dirty[:5])

editor = Editor()
agent = mcp.McpClient(mcp._default_launcher(), ws)
agent.initialize()
try:
    editor.open(OPEN)
    poll("X0 editor: open test file is clean", lambda: editor.diags(OPEN), lambda d: d is not None and not errors(d))

    # X1: the agent renames a constant the editor's open file uses; the editor reloads the buffer.
    line, col = locate(WARNING, "ALL_FIELDS_SHOULD_BE_USED")
    t0 = time.monotonic()
    out = text_of(agent.call_tool("rename_symbol", {"file": str(WARNING), "line": line, "column": col,
                                                     "newName": "ALL_FIELDS_LATHE_MIXED"}))
    record("Renamed" in out, "X1 agent: rename_symbol across modules", "%.1fs %s" % (time.monotonic() - t0, out.splitlines()[0][:80]))
    editor.reload_changed()
    poll("X1b editor: reloaded buffer is clean against the agent's rename", lambda: editor.diags(OPEN),
         lambda d: d is not None and not errors(d))

    # X2: the editor saves a new constant upstream; the agent's new file uses it at once.
    editor.open(WARNING)
    src = WARNING.read_text()
    editor.save(WARNING, src.replace("ALL_FIELDS_LATHE_MIXED,", "ALL_FIELDS_LATHE_MIXED,\n    LATHE_EDITOR_ADDED,", 1))
    PROBE.write_text("package nl.jqno.equalsverifier.integration.basic_contract;\n\n"
                     "class LatheMixedProbe {\n  Object w = nl.jqno.equalsverifier.Warning.LATHE_EDITOR_ADDED;\n}\n")
    created.append(PROBE)
    out = text_of(agent.call_tool("get_diagnostics", {"file": str(PROBE)}))
    record(out.startswith("No errors or warnings"),
           "X2 agent: sees a constant the editor just saved", out.splitlines()[0][:90])

    # X3: a mass outside edit with both servers live (a git pull, say): 30 core files touched.
    sources = sorted(glob.glob(str(ws / "equalsverifier-core/src/main/java/nl/jqno/equalsverifier/internal/**/*.java"),
                           recursive=True))[:30]
    for s in sources:
        p = Path(s)
        p.write_text(p.read_text() + "\n// lathe mixed probe\n")
    t0 = time.monotonic()
    out = text_of(agent.call_tool("verify_change", {}))
    record("Error" not in out and "cannot find" not in out, "X3 agent: verify_change after 30 outside edits",
           "%.1fs %s" % (time.monotonic() - t0, out.splitlines()[0][:80]))
    poll("X3b editor: open file still clean after the mass edit", lambda: editor.diags(OPEN),
         lambda d: d is not None and not errors(d))
    git("checkout", "--", *[str(Path(s).relative_to(ws)) for s in sources])

    # X4: undo everything from the agent side and the editor side; both end clean.
    PROBE.unlink()
    editor.save(WARNING, WARNING.read_text().replace("    LATHE_EDITOR_ADDED,\n", ""))
    line, col = locate(WARNING, "ALL_FIELDS_LATHE_MIXED")
    out = text_of(agent.call_tool("rename_symbol", {"file": str(WARNING), "line": line, "column": col,
                                                     "newName": "ALL_FIELDS_SHOULD_BE_USED"}))
    record("Renamed" in out, "X4 agent: rename back", out.splitlines()[0][:80])
    editor.reload_changed()
    poll("X4b editor: clean after the rename back", lambda: editor.diags(OPEN), lambda d: d is not None and not errors(d))
    left = [x for x in git("status", "--porcelain").splitlines() if ".mvn/" not in x]
    record(not left, "X5 tree back to HEAD", str(left[:4]))
    out = text_of(agent.call_tool("verify_change", {}))
    record("lathe-sync" not in out and "Error" not in out, "X5b agent: verify_change after all is undone", out.splitlines()[0][:80])
    print("      X5b full answer:\n" + "\n".join("        " + x for x in out.splitlines()))

    severe = [l for l in editor.c.stderr_lines if re.search(r"SEVERE|^\s+at [a-z]|Exception: ", l)]
    record(not severe, "X6 editor server log: no SEVERE or exceptions", (severe[:2] or [""])[0][:160])
finally:
    agent.close()
    editor.c.stop()
    for p in created:
        p.unlink(missing_ok=True)
    touched = [x for x in git("diff", "--name-only").split() if not x.startswith(".mvn/")]
    if touched:
        git("checkout", "--", *touched)

mcp_log = sorted(glob.glob(str(Path.home() / ".cache/lathe/logs/mcp-*.log")))[-1]
severe = [l for l in open(mcp_log, errors="replace") if re.search(r"SEVERE|^\s+at [a-z]|Exception: ", l)]
record(not severe, "X6b agent server log: no SEVERE or exceptions", (severe[:2] or [""])[0][:160])
print("SUMMARY fail=%d" % failures)
sys.exit(1 if failures else 0)
