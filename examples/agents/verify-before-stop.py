#!/usr/bin/env python3
"""Claude Code Stop hook: before the agent finishes, make sure it ran Lathe's verify_change after
its last Java edit.

A hook cannot call an MCP tool, so it asks the agent to: when the session's transcript shows a Java
edit (Edit, Write, MultiEdit, or a Lathe rename or import fix) after the last verify_change, it
blocks the stop with that instruction. It asks once per stop, so a failing check cannot loop.

Register it in .claude/settings.json:

    {"hooks": {"Stop": [{"hooks": [{"type": "command",
        "command": "python3 examples/agents/verify-before-stop.py"}]}]}}
"""

import json
import sys

EDIT_TOOLS = {"Edit", "Write", "MultiEdit"}
LATHE_EDITS = ("rename_symbol", "fix_imports")
REASON = (
    "Java files changed since the last Lathe verify_change: run the verify_change tool "
    "(no arguments) and fix what it reports before finishing."
)


def tool_uses(transcript_path):
    with open(transcript_path, encoding="utf-8") as transcript:
        for line in transcript:
            try:
                content = (json.loads(line).get("message") or {}).get("content")
            except ValueError:
                continue
            if isinstance(content, list):
                for item in content:
                    if isinstance(item, dict) and item.get("type") == "tool_use":
                        yield item.get("name", ""), json.dumps(item.get("input", {}))


def edits_java(name, arguments):
    return (name in EDIT_TOOLS and ".java" in arguments) or name.endswith(LATHE_EDITS)


def main():
    event = json.load(sys.stdin)
    if event.get("stop_hook_active"):
        return
    last_edit = last_verify = -1
    for index, (name, arguments) in enumerate(tool_uses(event["transcript_path"])):
        if name.endswith("verify_change"):
            last_verify = index
        elif edits_java(name, arguments):
            last_edit = index
    if last_edit > last_verify:
        print(json.dumps({"decision": "block", "reason": REASON}))


if __name__ == "__main__":
    main()
