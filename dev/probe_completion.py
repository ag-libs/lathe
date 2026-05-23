#!/usr/bin/env python3
"""
Probe completion at 10 Helidon + 10 Dropwizard positions and report
speed, result counts, and relevant server log messages.

Usage:
    python3 dev/probe_completion.py
"""
import re
import sys
import time
sys.path.insert(0, str(__import__("pathlib").Path(__file__).parent))
from lsp import LatheClient, find_workspace_root
from pathlib import Path

H = "/home/ag-libs/git/helidon"
D = "/home/ag-libs/git/dropwizard"

FILES = [
    # Helidon
    f"{H}/scheduling/src/main/java/io/helidon/scheduling/TaskManagerImpl.java",
    f"{H}/scheduling/src/main/java/io/helidon/scheduling/FixedRateInvocation.java",
    f"{H}/scheduling/src/main/java/io/helidon/scheduling/CronTask.java",
    f"{H}/security/security/src/main/java/io/helidon/security/SecurityImpl.java",
    f"{H}/security/security/src/main/java/io/helidon/security/AuthenticationResponse.java",
    f"{H}/security/security/src/main/java/io/helidon/security/CompositeOutboundProvider.java",
    f"{H}/security/security/src/main/java/io/helidon/security/FirstProviderSelectionPolicy.java",
    f"{H}/security/abac/role/src/main/java/io/helidon/security/abac/role/RoleValidator.java",
    f"{H}/webserver/webserver/src/main/java/io/helidon/webserver/ConnectionHandler.java",
    f"{H}/webserver/webserver/src/main/java/io/helidon/webserver/ConnectionProviders.java",
    # Dropwizard
    f"{D}/dropwizard-core/src/main/java/io/dropwizard/core/Application.java",
    f"{D}/dropwizard-core/src/main/java/io/dropwizard/core/cli/Cli.java",
    f"{D}/dropwizard-core/src/main/java/io/dropwizard/core/cli/ConfiguredCommand.java",
    f"{D}/dropwizard-core/src/main/java/io/dropwizard/core/server/DefaultServerFactory.java",
    f"{D}/dropwizard-core/src/main/java/io/dropwizard/core/server/AbstractServerFactory.java",
    f"{D}/dropwizard-core/src/main/java/io/dropwizard/core/setup/Bootstrap.java",
    f"{D}/dropwizard-core/src/main/java/io/dropwizard/core/setup/Environment.java",
    f"{D}/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java",
    f"{D}/dropwizard-lifecycle/src/main/java/io/dropwizard/lifecycle/ExecutorServiceManager.java",
    f"{D}/dropwizard-testing/src/main/java/io/dropwizard/testing/DropwizardTestSupport.java",
]

# Skip lines that are imports, package decls, comments, or bare field declarations.
SKIP_RE = re.compile(r"^\s*(//|/\*|\*|import |package |@)")
# Require a member-access dot with an identifier on both sides.
DOT_RE = re.compile(r"[A-Za-z_$0-9)]+\.([A-Za-z_$][A-Za-z0-9_$]*)")


def find_probe(path: str) -> tuple[int, int, str] | None:
    """Return (0-based line, 0-based col after dot, receiver snippet) or None."""
    lines = Path(path).read_text().splitlines()
    for i, raw in enumerate(lines):
        if SKIP_RE.match(raw):
            continue
        m = DOT_RE.search(raw)
        if m:
            col = m.start(1)   # col of the first char of the identifier after "."
            snippet = raw.strip()[:60]
            return i, col, snippet
    return None


def short(path: str) -> str:
    p = Path(path)
    return f"{p.parent.name}/{p.name}"


def main():
    probes: list[tuple[str, int, int, str, str]] = []
    for path in FILES:
        if not Path(path).exists():
            print(f"[skip] not found: {path}", file=sys.stderr)
            continue
        hit = find_probe(path)
        if hit is None:
            print(f"[skip] no dot line: {path}", file=sys.stderr)
            continue
        line, col, snippet = hit
        probes.append((path, line, col, short(path), snippet))

    by_workspace: dict[str, list] = {}
    for entry in probes:
        root = str(find_workspace_root(Path(entry[0])))
        by_workspace.setdefault(root, []).append(entry)

    results: list[tuple[str, str, float | None, int | None, str]] = []
    all_log_lines: list[str] = []

    for workspace, group in by_workspace.items():
        print(f"workspace: {workspace}", flush=True)
        with LatheClient.start(workspace, debug=True) as c:
            opened: set[str] = set()
            for path, _l, _c, label, snippet in group:
                if path not in opened:
                    try:
                        c.open(path, timeout=30)
                        opened.add(path)
                        print(f"  opened {short(path)}", flush=True)
                    except Exception as e:
                        print(f"  [open-fail] {short(path)}: {e}", flush=True)

            for path, line, col, label, snippet in group:
                if path not in opened:
                    results.append((label, snippet, None, None, "open-failed"))
                    continue
                t0 = time.perf_counter()
                try:
                    items = c.completion(path, line, col)
                    elapsed = time.perf_counter() - t0
                    results.append((label, snippet, elapsed, len(items), "ok"))
                except RuntimeError as e:
                    elapsed = time.perf_counter() - t0
                    msg = str(e)
                    results.append((label, snippet, elapsed, 0,
                                    "unsupported" if "UnsupportedOperation" in msg else f"error: {msg[:60]}"))
                except TimeoutError:
                    results.append((label, snippet, None, 0, "timeout"))

            all_log_lines.extend(c.stderr_lines)

    # ── Summary table ─────────────────────────────────────────────────────────
    print("\n" + "=" * 80)
    print(f"{'File / snippet':<52} {'ms':>6}  {'#':>5}  Status")
    print("=" * 80)
    ok_times = []
    for label, snippet, elapsed, count, status in results:
        ms_s = f"{elapsed*1000:.0f}" if elapsed is not None else "   —"
        cnt_s = str(count) if count is not None else "  —"
        line1 = f"{label:<52} {ms_s:>6}  {cnt_s:>5}  {status}"
        line2 = f"    {snippet}"
        print(line1)
        print(line2)
        if status == "ok" and elapsed is not None:
            ok_times.append(elapsed * 1000)
    print("=" * 80)
    if ok_times:
        print(f"  ok: {len(ok_times)}/{len(results)}  "
              f"min={min(ok_times):.0f}ms  avg={sum(ok_times)/len(ok_times):.0f}ms  "
              f"max={max(ok_times):.0f}ms")

    # ── Relevant log lines ────────────────────────────────────────────────────
    interesting = [l for l in all_log_lines
                   if any(kw in l for kw in
                          ["sentinel", "completion", "WARN", "ERROR",
                           "invalid", "miss", "inject", "parse"])]
    print(f"\n--- server log excerpt ({len(interesting)} relevant / {len(all_log_lines)} total) ---")
    for l in interesting[:60]:
        print(" ", l)


if __name__ == "__main__":
    main()
