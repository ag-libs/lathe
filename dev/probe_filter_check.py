#!/usr/bin/env python3
"""
Verify that completion results actually match the typed prefix.
Injects prefixes in class body and method body; prints any item
whose label does NOT start with the prefix (case-insensitive).

Usage:
    python3 dev/probe_filter_check.py
"""
import sys
import time
sys.path.insert(0, str(__import__("pathlib").Path(__file__).parent))
from lsp import LatheClient
from pathlib import Path

H = "/home/ag-libs/git/helidon"
TARGET = f"{H}/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClient.java"

CASES = [
    # (label, line_idx, indent, prefix)
    ("class body  'Mon'",  38, 4,  "Mon"),
    ("class body  'Map'",  38, 4,  "Map"),
    ("class body  'M'",    38, 4,  "M"),
    ("method body 'Mon'",  99, 8,  "Mon"),
    ("method body 'M'",    99, 8,  "M"),
    ("method body 'mon'",  99, 8,  "mon"),
]


def inject(lines, line_idx, indent, prefix):
    lines.insert(line_idx, " " * indent + prefix)
    return lines, line_idx, indent + len(prefix)


def main():
    target = Path(TARGET)
    if not target.exists():
        print(f"[skip] {TARGET}", file=sys.stderr); sys.exit(1)

    original = target.read_text()

    with LatheClient.start(H, debug=True) as c:
        print("opening file...", flush=True)
        diags = c.open(TARGET, timeout=60)
        print(f"  diagnostics: {len(diags)}\n")

        version = 2
        for label, line_idx, indent, prefix in CASES:
            lines = original.splitlines()
            lines, line, col = inject(lines, line_idx, indent, prefix)
            c.change(TARGET, "\n".join(lines), version=version)
            version += 1
            time.sleep(0.05)

            items = c.completion(TARGET, line, col)
            bad = [it["label"] for it in items
                   if not it["label"].lower().startswith(prefix.lower())]
            ok = len(items) - len(bad)

            status = "OK" if not bad else "MISMATCH"
            print(f"  [{label}]  total={len(items)}  ok={ok}  mismatch={len(bad)}  [{status}]")
            if bad:
                for b in bad[:10]:
                    print(f"    BAD: {b!r}")
            elif items:
                for it in items[:3]:
                    print(f"    {it['label']}  —  {it.get('detail','')}")
            print()

        log = c.stderr_lines

    print("--- server log ---")
    for line in log:
        if any(kw in line for kw in ["sentinelCtx", "type-index", "WARN", "ERROR"]):
            print(" ", line)


if __name__ == "__main__":
    main()
