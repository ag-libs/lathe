#!/usr/bin/env python3
"""Release check: the MCP battery on every configured workspace found, optionally the staleness
probe, the verify_change scale check, a battery run on an older JDK and the editor smoke, summed up
in one dated report.

    python3 -I dev/release_check.py <dir-with-clones> --expect-version 0.2.2
        [--staleness] [--scale] [--old-jdk JAVA_HOME] [--editor]

Looks for dropwizard/, equalsverifier/ and helidon/ under the directory and skips any that is
missing. Writes dev/reports/release-<version>-<date>.md; exits 1 if any run failed.
"""
import argparse
import os
import re
import subprocess
import sys
import threading
import time
from datetime import date
from pathlib import Path

DEV = Path(__file__).resolve().parent
sys.path.insert(0, str(DEV))
import mcp_battery  # noqa: E402

# TIMING: verify_scale.py's per-step time and memory, the numbers its run is for.
STATUS = re.compile(r"^(PASS|FAIL|KNOWN|NOW PASSES|SKIP|SLOWER|REFUSED|WARN|RESTORED|TIMING|LOAD)\b")
COUNTED = ("PASS", "FAIL", "KNOWN", "NOW PASSES", "SKIP", "WARN", "SLOWER")
# The marks a reader acts on: a NOW PASSES drops a known= mark, a KNOWN is in the gap log, a SKIP
# did not run.
LISTED = ("NOW PASSES", "KNOWN", "SKIP")


class LoadSampler:
    """The 1-minute load average every 5 s while a run lasts: its peak and mean go in the report."""

    def __init__(self):
        self.samples = []
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._sample, daemon=True)

    def _sample(self):
        while True:
            self.samples.append(os.getloadavg()[0])
            if self._stop.wait(5):
                return

    def __enter__(self):
        self._thread.start()
        return self

    def __exit__(self, *_):
        self._stop.set()
        self._thread.join()

    def summary(self):
        if not self.samples:
            return "-"
        return "%.1f / %.1f" % (max(self.samples), sum(self.samples) / len(self.samples))


def run(title, command):
    """Runs one probe, echoing its output; returns (title, exit code, the lines worth reporting,
    wall seconds, load)."""
    print("\n=== %s" % title, flush=True)
    started = time.monotonic()
    lines = []
    with LoadSampler() as load:
        # Streamed, not buffered: a long Helidon run shows its progress, and a hang shows where.
        proc = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in proc.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            if STATUS.match(line) or line.startswith(("SUMMARY", "SERVER", "===")):
                lines.append(line.rstrip("\n"))
        code = proc.wait()
    return title, code, lines, time.monotonic() - started, load.summary()


def counts(lines):
    found = {s: 0 for s in COUNTED}
    for line in lines:
        status = next((s for s in sorted(COUNTED, key=len, reverse=True) if line.startswith(s)), None)
        if status:
            found[status] += 1
    return found


def check_label(line, status):
    """A status line's label, without its timing and detail: the same check on every workspace."""
    rest = line[len(status):].strip()
    return re.split(r"\s{2,}|\s+\d+ms\b", rest, maxsplit=1)[0].strip()


def header(clones, opts):
    repo = Path(__file__).resolve().parent.parent
    head = subprocess.run(["git", "-C", str(repo), "rev-parse", "--short=10", "HEAD"], capture_output=True,
                          text=True).stdout.strip()
    dirty = subprocess.run(["git", "-C", str(repo), "status", "--porcelain", "--untracked-files=no"],
                           capture_output=True, text=True).stdout.strip()
    parts = ["# Release check %s (%s)\n" % (opts.expect_version, date.today()),
             "Lathe checkout %s%s; %d CPUs. Load is the 1-minute average, peak / mean over each run: above %d "
             "the timings say more about the machine than the server.\n"
             % (head, " (uncommitted changes)" if dirty else "", os.cpu_count() or 1, (os.cpu_count() or 2) // 2)]
    for name in sorted(mcp_battery.CONFIGS):
        java_home = clones / name / ".lathe" / "java-home"
        if java_home.is_file():
            home = Path(java_home.read_text(encoding="utf-8").strip())
            parts.append("- %s: JDK %s (%s)" % (name, mcp_battery.java_version(home), home))
    return parts + [""]


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("clones", help="directory holding the workspace clones")
    parser.add_argument("--expect-version", required=True)
    parser.add_argument("--staleness", action="store_true", help="also run dev/mcp_staleness.py")
    parser.add_argument("--scale", action="store_true", help="also run dev/verify_scale.py on Helidon")
    parser.add_argument("--old-jdk", metavar="JAVA_HOME",
                        help="also run the Dropwizard battery with the server on this JDK (17 or 21): javac 21 and "
                             "older keep type-use annotations in erasure, which newer ones hide")
    parser.add_argument("--editor", action="store_true",
                        help="also run dev/editor-smoke.sh (headless, in your own Neovim config)")
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
            runs.append(run("staleness %s" % name, python + [str(DEV / "mcp_staleness.py"), str(ws), name,
                                                             "--expect-version", opts.expect_version]))
    if opts.old_jdk and (clones / "dropwizard" / ".lathe").is_dir():
        runs.append(run("battery dropwizard (server on %s)" % Path(opts.old_jdk).name,
                        python + [str(DEV / "mcp_battery.py"), str(clones / "dropwizard"), "dropwizard",
                                  "--expect-version", opts.expect_version, "--server-jdk", opts.old_jdk]))
    if opts.scale and (clones / "helidon" / ".lathe").is_dir():
        runs.append(run("verify_change scale helidon", python + [str(DEV / "verify_scale.py"), str(clones / "helidon"),
                                                                "--expect-version", opts.expect_version]))
    if opts.editor:
        runs.append(run("editor smoke", [str(DEV / "editor-smoke.sh"), str(clones)]))

    report = DEV / "reports" / ("release-%s-%s.md" % (opts.expect_version, date.today()))
    report.parent.mkdir(parents=True, exist_ok=True)
    parts = header(clones, opts)
    parts += ["| Run | Result | %s | Wall | Load |" % " | ".join(s.lower() for s in COUNTED),
              "|---|---|%s---|---|" % ("---|" * len(COUNTED))]
    for title, code, lines, seconds, load in runs:
        found = counts(lines)
        parts.append("| %s | %s | %s | %ds | %s |" % (title, "ok" if code == 0 else "exit %d" % code,
                                                     " | ".join(str(found[s]) for s in COUNTED), seconds, load))
    for status in LISTED:
        labels = {}
        for title, _, lines, _, _ in runs:
            for line in lines:
                if line.startswith(status + " ") or line.startswith(status + "\t"):
                    labels.setdefault(check_label(line, status), []).append(title)
        if labels:
            parts += ["", "## Every %s" % status, ""]
            parts += ["- %s (%s)" % (label, ", ".join(titles)) for label, titles in sorted(labels.items())]
    for title, code, lines, _, _ in runs:
        parts += ["", "## %s" % title, "", "```", *lines, "```"]
    report.write_text("\n".join(parts) + "\n", encoding="utf-8")
    print("\nREPORT %s" % report)
    sys.exit(1 if any(code for _, code, _, _, _ in runs) else 0)


if __name__ == "__main__":
    main()
