#!/usr/bin/env python3
"""
Probe type-reference completion in MongoDbClient.java at three positions
a Java developer would realistically trigger it:

  1. new class field       — class body, after existing fields (line 39)
  2. method return type    — method declaration (after line 96)
  3. local variable type   — inside a method body (line 100)

Usage:
    python3 dev/probe_mongo_positions.py
"""
import sys
import time
sys.path.insert(0, str(__import__("pathlib").Path(__file__).parent))
from lsp import LatheClient
from pathlib import Path

H = "/home/ag-libs/git/helidon"
TARGET = f"{H}/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClient.java"
PREFIX = "MongoCli"


def inject_field(lines: list[str]) -> tuple[list[str], int, int]:
    """After `private final MongoDatabase db;` (line 38, 0-indexed 37)."""
    idx = 38  # insert at line index 38 (between the two existing field lines and the constructor)
    indent = 4
    lines.insert(idx, " " * indent + PREFIX + " newField;")
    col = indent + len(PREFIX)
    return lines, idx, col


def inject_method_return(lines: list[str]) -> tuple[list[str], int, int]:
    """After the closing brace of the second constructor (line 96, 0-indexed 95)."""
    idx = 96  # insert right after the `}` at index 95
    indent = 4
    lines.insert(idx, "")
    lines.insert(idx + 1, " " * indent + PREFIX + " newMethod() { return null; }")
    line = idx + 1
    col = indent + len(PREFIX)
    return lines, line, col


def inject_local_var(lines: list[str]) -> tuple[list[str], int, int]:
    """Inside execute(), before the return statement (line 100, 0-indexed 99)."""
    idx = 99  # insert before `return new MongoDbExecute(...)`
    indent = 8
    lines.insert(idx, " " * indent + PREFIX + " local;")
    col = indent + len(PREFIX)
    return lines, idx, col


POSITIONS = [
    ("new field      (class body)",    inject_field),
    ("method return  (declaration)",   inject_method_return),
    ("local variable (method body)",   inject_local_var),
]


def main():
    target = Path(TARGET)
    if not target.exists():
        print(f"[skip] not found: {TARGET}", file=sys.stderr)
        sys.exit(1)

    original = target.read_text()
    print(f"file:   {target.name}")
    print(f"prefix: {PREFIX!r}\n")

    with LatheClient.start(H, debug=True) as c:
        print("opening file and waiting for diagnostics...", flush=True)
        diags = c.open(TARGET, timeout=60)
        print(f"  diagnostics: {len(diags)}\n")

        version = 2
        for label, inject_fn in POSITIONS:
            lines = original.splitlines()
            lines, line, col = inject_fn(lines)
            content = "\n".join(lines)

            c.change(TARGET, content, version=version)
            version += 1
            time.sleep(0.1)

            t0 = time.perf_counter()
            items = c.completion(TARGET, line, col)
            elapsed = (time.perf_counter() - t0) * 1000

            print(f"  [{label}]  items={len(items)}  {elapsed:.0f}ms")
            for it in items[:6]:
                print(f"    {it['label']}  —  {it.get('detail', '')}")
            if len(items) > 6:
                print(f"    ... ({len(items) - 6} more)")

        log_lines = c.stderr_lines

    print("\n--- server log (relevant lines) ---")
    for line in log_lines:
        if any(kw in line for kw in ["type-index", "sentinelCtx", "TYPE_REFERENCE",
                                      "inject prefix", "WARN", "ERROR"]):
            print(" ", line)


if __name__ == "__main__":
    main()
