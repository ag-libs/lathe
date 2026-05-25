#!/usr/bin/env python3
"""
Probe what completion returns when the user presses Ctrl+Space with no prefix
(blank trigger) vs. a short prefix, in both class-body and method-body positions.

Usage:
    python3 dev/probe_blank_completion.py
"""
import sys
import time
sys.path.insert(0, str(__import__("pathlib").Path(__file__).parent))
from lsp import LatheClient
from pathlib import Path

H = "/home/ag-libs/git/helidon"
TARGET = f"{H}/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClient.java"


def inject(lines: list[str], line_idx: int, indent: int, prefix: str) -> tuple[list[str], int, int]:
    snippet = " " * indent + prefix
    lines.insert(line_idx, snippet)
    return lines, line_idx, indent + len(prefix)


POSITIONS = [
    # (label, line_idx to insert, indent, prefix)
    ("class body  / no prefix",       38, 4,  ""),
    ("class body  / prefix 'Mon'",     38, 4,  "Mon"),
    ("method body / no prefix",        99, 8,  ""),
    ("method body / prefix 'Mon'",     99, 8,  "Mon"),
    ("method body / prefix 'Mongo'",   99, 8,  "Mongo"),
]


def main():
    target = Path(TARGET)
    if not target.exists():
        print(f"[skip] not found: {TARGET}", file=sys.stderr)
        sys.exit(1)

    original = target.read_text()
    print(f"file: {target.name}\n")

    with LatheClient.start(H, debug=True) as c:
        print("opening file and waiting for diagnostics...", flush=True)
        diags = c.open(TARGET, timeout=60)
        print(f"  diagnostics: {len(diags)}\n")

        version = 2
        for label, line_idx, indent, prefix in POSITIONS:
            lines = original.splitlines()
            lines, line, col = inject(lines, line_idx, indent, prefix)
            content = "\n".join(lines)

            c.change(TARGET, content, version=version)
            version += 1
            time.sleep(0.05)

            t0 = time.perf_counter()
            items = c.completion(TARGET, line, col)
            elapsed = (time.perf_counter() - t0) * 1000

            print(f"  [{label}]  items={len(items)}  {elapsed:.0f}ms")
            for it in items[:5]:
                print(f"    {it['label']}  —  {it.get('detail', '')}")
            if len(items) > 5:
                print(f"    ... ({len(items) - 5} more)")

        log_lines = c.stderr_lines

    print("\n--- server log (relevant lines) ---")
    for line in log_lines:
        if any(kw in line for kw in ["sentinelCtx", "inject prefix", "type-index", "WARN", "ERROR"]):
            print(" ", line)


if __name__ == "__main__":
    main()
