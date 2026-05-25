#!/usr/bin/env python3
"""
Probe type-reference completion in ServiceDescriptorCodegen.java.

Injects a partial type name at a method-parameter position inside a method
body and checks whether the type index is consulted and returns results.

Usage:
    python3 dev/probe_service_codegen.py
"""
import sys
import time
sys.path.insert(0, str(__import__("pathlib").Path(__file__).parent))
from lsp import LatheClient
from pathlib import Path

H = "/home/ag-libs/git/helidon"
WORKSPACE = H
TARGET = f"{H}/service/codegen/src/main/java/io/helidon/service/codegen/ServiceDescriptorCodegen.java"

# Prefixes to probe — types likely in the helidon dependency index.
PREFIXES = ["TypeNam", "ClassMod", "Annot", "CodegenE"]


def inject_type_ref(content: str, prefix: str) -> tuple[str, int, int]:
    """
    Find the first method body opening brace and inject a local variable whose
    type is the prefix. Returns (new_content, line, col).
    """
    lines = content.splitlines()
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.endswith("{") and not stripped.startswith("//") and i + 1 < len(lines):
            indent = len(line) - len(line.lstrip())
            injected = " " * (indent + 4) + prefix + " _probe;"
            lines.insert(i + 1, injected)
            col = indent + 4 + len(prefix)
            return "\n".join(lines), i + 1, col
    raise RuntimeError("no method body found")


def main():
    target = Path(TARGET)
    if not target.exists():
        print(f"[skip] not found: {TARGET}", file=sys.stderr)
        sys.exit(1)

    original = target.read_text()
    print(f"workspace: {WORKSPACE}")
    print(f"file:      {target.name}")

    with LatheClient.start(WORKSPACE, debug=True) as c:
        print("opening file and waiting for diagnostics...", flush=True)
        diags = c.open(TARGET, timeout=60)
        print(f"  diagnostics: {len(diags)}")

        for prefix in PREFIXES:
            content, line, col = inject_type_ref(original, prefix)
            c.change(TARGET, content, version=2)
            time.sleep(0.1)

            t0 = time.perf_counter()
            items = c.completion(TARGET, line, col)
            elapsed = (time.perf_counter() - t0) * 1000

            labels = [it["label"] for it in items[:10]]
            details = [it.get("detail", "") for it in items[:10]]
            print(f"\n  prefix={prefix!r}  items={len(items)}  {elapsed:.0f}ms")
            for label, detail in zip(labels, details):
                print(f"    {label}  —  {detail}")
            if len(items) > 10:
                print(f"    ... ({len(items) - 10} more)")

        log_lines = c.stderr_lines

    print("\n--- server log ---")
    for line in log_lines:
        if any(kw in line for kw in ["type-index", "typeRef", "TYPE_REFERENCE",
                                      "sentinelCtx", "inject", "WARN", "ERROR"]):
            print(" ", line)


if __name__ == "__main__":
    main()
