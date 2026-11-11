#!/usr/bin/env python3
"""verify_change on a core type with hundreds of callers: each answer right, and its time, the files
it checked and the server's memory.

    python3 -I dev/verify_scale.py <helidon-workspace> [--expect-version 0.2.2]

One PASS/FAIL line per answer and a TIMING line per step; exit 1 if any check fails, 2 if it
refuses to run (edited sources, another run on the workspace, another server version).
"""
import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mcp  # noqa: E402

TYPE = "common/common/src/main/java/io/helidon/common/GenericType.java"
VERIFY_CLEAN = "No errors — "
# A renamed GenericType.create broke 115 files at Helidon's pinned commit; far fewer means callers
# went unchecked.
MIN_BROKEN_FILES = 100
failures = 0


def text_of(result):
    return "\n".join(c.get("text", "") for c in (result or {}).get("content", []))


def git(ws, *args):
    return subprocess.run(["git", "-C", str(ws), *args], capture_output=True, text=True).stdout


def memory(pid):
    status = Path("/proc/%d/status" % pid).read_text(encoding="utf-8")
    fields = dict(re.findall(r"^(VmRSS|VmHWM):\s+(\d+) kB", status, re.M))
    return "RSS %d MB (peak %d MB)" % (int(fields["VmRSS"]) // 1024, int(fields["VmHWM"]) // 1024)


def check(label, passed, detail):
    global failures
    failures += 0 if passed else 1
    print("%-6s %-40s %s" % ("PASS" if passed else "FAIL", label, detail), flush=True)


def verify(client, label):
    t0 = time.monotonic()
    out = text_of(client.call_tool("verify_change", {}))
    head = out.splitlines()[0] if out else ""
    print("TIMING %-40s %6.1fs  %s  | %s" % (label, time.monotonic() - t0, memory(client.proc.pid), head[:90]),
          flush=True)
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("workspace")
    parser.add_argument("--expect-version", help="refuse to run against another server version")
    opts = parser.parse_args()
    ws = Path(opts.workspace).resolve()
    mcp.lock_workspace(ws)
    dirty = [line for line in git(ws, "status", "--porcelain", "--untracked-files=no").splitlines()
             if line.rstrip().endswith(".java")]
    if dirty:
        print("REFUSED: edited .java file(s) in %s: %s" % (ws, dirty[:5]))
        sys.exit(2)
    target = ws / TYPE
    original = target.read_text(encoding="utf-8")
    client = mcp.McpClient(mcp.launcher_for(ws), ws)
    try:
        version = (client.initialize().get("serverInfo") or {}).get("version")
        print("SERVER %s; workspace %s at %s" % (version, ws.name, git(ws, "rev-parse", "--short=10", "HEAD").strip()))
        print(mcp.load_line(), flush=True)
        if opts.expect_version and version != opts.expect_version:
            print("REFUSED: the server reports %s, not %s" % (version, opts.expect_version))
            sys.exit(2)
        t0 = time.monotonic()
        client.call_tool("get_diagnostics", {"file": str(target)})
        print("TIMING %-40s %6.1fs  %s" % ("warm-up (first query)", time.monotonic() - t0, memory(client.proc.pid)),
              flush=True)
        out = verify(client, "verify_change, nothing changed")
        check("nothing changed: clean", out.startswith(VERIFY_CLEAN), out.splitlines()[0][:90] if out else "(empty)")

        target.write_text(original.replace("    public static final GenericType<String> STRING",
                                           "    public static int latheProbe() { return 1; }\n\n"
                                           "    public static final GenericType<String> STRING", 1),
                          encoding="utf-8")
        out = verify(client, "harmless edit (method added)")
        check("harmless edit: clean", out.startswith(VERIFY_CLEAN), out.splitlines()[0][:90] if out else "(empty)")

        target.write_text(re.sub(r"\bcreate\(", "createLatheProbe(", original), encoding="utf-8")
        out = verify(client, "breaking edit (create renamed)")
        files = set(re.findall(r"^(\S+\.java):\d+:\d+: Error:", out, re.M))
        check("breaking edit: callers reported broken", len(files) >= MIN_BROKEN_FILES,
              "%d file(s) with errors, want at least %d" % (len(files), MIN_BROKEN_FILES))

        target.write_text(original, encoding="utf-8")
        out = verify(client, "reverted")
        check("reverted: clean", out.startswith(VERIFY_CLEAN), out.splitlines()[0][:90] if out else "(empty)")
    finally:
        client.close()
        target.write_text(original, encoding="utf-8")

    # This run's own server log: the newest log may belong to another session.
    log = client.session_log()
    lines = log.read_text(encoding="utf-8", errors="replace").splitlines() if log and log.is_file() else []
    verify_lines = [line[11:] for line in lines if "[verify]" in line]
    if verify_lines:
        print("[verify] log lines:\n" + "\n".join("   " + line for line in verify_lines))
    left = [line for line in git(ws, "status", "--short", "--untracked-files=no").splitlines()
            if line.rstrip().endswith(".java")]
    check("workspace restored", not left, "; ".join(left)[:120] or "clean")
    print(mcp.load_line())
    print("SUMMARY fail=%d" % failures)
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
