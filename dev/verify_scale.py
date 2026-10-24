#!/usr/bin/env python3
"""verify_change on a core type with hundreds of callers: time, files checked, server memory.

    python3 -I dev/verify_scale.py <helidon-workspace>
"""
import glob
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mcp  # noqa: E402

ws = Path(sys.argv[1]).resolve()
TYPE = ws / "common/common/src/main/java/io/helidon/common/GenericType.java"
original = TYPE.read_text()


def text_of(result):
    return "\n".join(c.get("text", "") for c in (result or {}).get("content", []))


def memory(pid):
    status = Path("/proc/%d/status" % pid).read_text()
    fields = dict(re.findall(r"^(VmRSS|VmHWM):\s+(\d+) kB", status, re.M))
    return "RSS %d MB (peak %d MB)" % (int(fields["VmRSS"]) // 1024, int(fields["VmHWM"]) // 1024)


def verify(client, label):
    t0 = time.monotonic()
    out = text_of(client.call_tool("verify_change", {}))
    head = out.splitlines()[0] if out else ""
    print("%-34s %6.1fs  %s  | %s" % (label, time.monotonic() - t0, memory(client.proc.pid), head[:90]), flush=True)
    return out


client = mcp.McpClient(mcp._default_launcher(), ws)
try:
    client.initialize()
    t0 = time.monotonic()
    client.call_tool("get_diagnostics", {"file": str(TYPE)})
    print("%-34s %6.1fs  %s" % ("warm-up (first query)", time.monotonic() - t0, memory(client.proc.pid)), flush=True)
    verify(client, "verify_change, nothing changed")

    TYPE.write_text(original.replace("    public static final GenericType<String> STRING",
                                     "    public static int latheProbe() { return 1; }\n\n"
                                     "    public static final GenericType<String> STRING", 1))
    verify(client, "harmless edit (method added)")

    TYPE.write_text(re.sub(r"\bcreate\(", "createLatheProbe(", original))
    out = verify(client, "breaking edit (create renamed)")
    modules = re.findall(r"^(\S[^:\n]*):$", out, re.M)
    print("   broken modules reported: %d; first lines:" % len(modules))
    print("\n".join("     " + line for line in out.splitlines()[:6]))

    TYPE.write_text(original)
    verify(client, "reverted")
finally:
    client.close()
    TYPE.write_text(original)

log = sorted(glob.glob(str(Path.home() / ".cache/lathe/logs/mcp-*.log")))[-1]
print("[verify] log lines:")
print("".join("   " + l[11:] for l in open(log) if "[verify]" in l))
print("git status:", subprocess.run(["git", "-C", str(ws), "status", "--short"], capture_output=True, text=True).stdout.strip())
