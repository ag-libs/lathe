#!/usr/bin/env python3
"""Release check: the MCP battery on every configured workspace found, optionally the staleness
probe and the verify_change scale check, summed up in one dated report.

    python3 -I dev/release_check.py <dir-with-clones> --expect-version 0.2.2 [--staleness] [--scale]

Looks for dropwizard/, equalsverifier/ and helidon/ under the directory and skips any that is
missing. Writes dev/reports/release-<version>-<date>.md; exits 1 if any run failed.
"""
import argparse
import re
import subprocess
import sys
from datetime import date
from pathlib import Path

DEV = Path(__file__).resolve().parent
sys.path.insert(0, str(DEV))
import mcp_battery  # noqa: E402

STATUS = re.compile(r"^(PASS|FAIL|KNOWN|NOW PASSES|SKIP|SLOWER|REFUSED|WARN|RESTORED)\b")


def run(title, command):
    """Runs one probe, echoing its output; returns (title, exit code, the lines worth reporting)."""
    print("\n=== %s" % title, flush=True)
    # Streamed, not buffered: a long Helidon run shows its progress, and a hang shows where.
    proc = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    lines = []
    for line in proc.stdout:
        sys.stdout.write(line)
        sys.stdout.flush()
        if STATUS.match(line) or line.startswith(("SUMMARY", "SERVER")):
            lines.append(line.rstrip("\n"))
    return title, proc.wait(), lines


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("clones", help="directory holding the workspace clones")
    parser.add_argument("--expect-version", required=True)
    parser.add_argument("--staleness", action="store_true", help="also run dev/mcp_staleness.py")
    parser.add_argument("--scale", action="store_true", help="also run dev/verify_scale.py on Helidon")
    opts = parser.parse_args()
    clones = Path(opts.clones).expanduser().resolve()
    python = [sys.executable, "-I"]

    runs = []
    for name in sorted(mcp_battery.CONFIGS):
        ws = clones / name
        if not (ws / ".lathe").is_dir():
            print("SKIP %s: no synced clone at %s" % (name, ws))
            continue
        runs.append(run("battery %s" % name, python + [str(DEV / "mcp_battery.py"), str(ws), name,
                                                      "--expect-version", opts.expect_version]))
        if opts.staleness:
            runs.append(run("staleness %s" % name, python + [str(DEV / "mcp_staleness.py"), str(ws), name]))
    if opts.scale and (clones / "helidon" / ".lathe").is_dir():
        runs.append(run("verify_change scale helidon", python + [str(DEV / "verify_scale.py"), str(clones / "helidon")]))

    report = DEV / "reports" / ("release-%s-%s.md" % (opts.expect_version, date.today()))
    report.parent.mkdir(parents=True, exist_ok=True)
    parts = ["# Release check %s (%s)\n" % (opts.expect_version, date.today()),
             "| Run | Result |", "|---|---|"]
    parts += ["| %s | %s |" % (title, "ok" if code == 0 else "exit %d" % code) for title, code, _ in runs]
    for title, code, lines in runs:
        parts += ["", "## %s" % title, "", "```", *lines, "```"]
    report.write_text("\n".join(parts) + "\n", encoding="utf-8")
    print("\nREPORT %s" % report)
    sys.exit(1 if any(code for _, code, _ in runs) else 0)


if __name__ == "__main__":
    main()
