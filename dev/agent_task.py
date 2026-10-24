#!/usr/bin/env python3
"""A realistic agent task on Dropwizard through MCP only, printing every answer in full for review.

    python3 -I dev/agent_task.py <dropwizard-workspace>
"""
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mcp  # noqa: E402

ws = Path(sys.argv[1]).resolve()
DURATION = ws / "dropwizard-util/src/main/java/io/dropwizard/util/Duration.java"
CALLER = ws / "dropwizard-core/src/main/java/io/dropwizard/core/server/AbstractServerFactory.java"
TEST = ws / "dropwizard-util/src/test/java/io/dropwizard/util/DurationTest.java"


def locate(path, token):
    for i, line in enumerate(path.read_text().splitlines()):
        c = line.find(token)
        if c >= 0 and not line.strip().startswith(("*", "//")):
            return i + 1, c + 1
    raise SystemExit("no %r in %s" % (token, path))


def ask(client, step, tool, args, lines=14):
    t0 = time.monotonic()
    result = client.call_tool(tool, args)
    text = "\n".join(c.get("text", "") for c in result.get("content", []))
    shown = text.splitlines()
    print("\n### %s  —  %s  (%.1fs)%s" % (step, tool, time.monotonic() - t0, "  [isError]" if result.get("isError") else ""))
    print("\n".join("    " + x for x in shown[:lines]) + ("\n    … (%d more lines)" % (len(shown) - lines) if len(shown) > lines else ""))
    return text


client = mcp.McpClient(mcp._default_launcher(), ws)
originals = {p: p.read_text() for p in (DURATION, CALLER, TEST)}
try:
    client.initialize()
    ask(client, "0 the reactor at a glance", "workspace_summary", {}, 8)
    ask(client, "1 find the type", "search_symbols", {"query": "Duration"}, 6)
    ask(client, "1b its structure", "outline", {"file": str(DURATION)}, 12)
    line, col = locate(DURATION, "public long toMilliseconds")
    col += len("public long ")
    pos = {"file": str(DURATION), "line": line, "column": col}
    ask(client, "2 understand the method", "describe_symbol", pos, 10)
    ask(client, "3 who uses it", "find_references", dict(pos, maxResults=5), 12)
    ask(client, "4 impact before changing", "analyze_change", pos, 16)
    ask(client, "5 rename across modules", "rename_symbol", dict(pos, newName="toMillis"), 8)
    ask(client, "6 verify the rename", "verify_change", {}, 8)

    # 7: the agent adds a method with its own edit tool, and uses it from another module.
    src = DURATION.read_text()
    cut = src.rindex("}")
    DURATION.write_text(src[:cut] + "\n    public long toMillisPlus(long extra) {\n        return toMillis() + extra;\n    }\n" + src[cut:])
    caller = CALLER.read_text()
    cut = caller.rindex("}")
    CALLER.write_text(caller[:cut] + "\n    static long latheTaskUse(io.dropwizard.util.Duration d) {\n        return d.toMillisPlus(1);\n    }\n" + caller[cut:])
    ask(client, "7 diagnostics of the edited caller", "get_diagnostics", {"file": str(CALLER)}, 8)
    ask(client, "7b format the edited caller", "format_file", {"file": str(CALLER)}, 4)
    # 7c: the agent uses types without importing them, as agents do: one with a single match, one
    # ambiguous (java.util.Date, java.sql.Date). Put back afterwards, so the later steps stay clean.
    before = CALLER.read_text()
    cut = before.rindex("}")
    CALLER.write_text(before[:cut] + "\n    static Object latheTaskImports() {\n        return new ConcurrentHashMap<Date, String>();\n    }\n" + before[cut:])
    ask(client, "7c add the missing imports", "fix_imports", {"file": str(CALLER)}, 6)
    CALLER.write_text(before)

    # 8: a deliberate mistake, as agents make them: wrong argument type.
    CALLER.write_text(CALLER.read_text().replace("d.toMillisPlus(1)", 'd.toMillisPlus("1")'))
    ask(client, "8 verify after a mistake", "verify_change", {}, 12)
    CALLER.write_text(CALLER.read_text().replace('d.toMillisPlus("1")', "d.toMillisPlus(1)"))
    ask(client, "9 verify after the fix", "verify_change", {}, 8)
    ask(client, "10 run the affected test class", "run_test", {"file": str(TEST)}, 8)

    # 11: undo everything the way an agent would: rename back, remove the additions.
    line, col = locate(DURATION, "public long toMillis()")
    col += len("public long ")
    ask(client, "11 rename back", "rename_symbol", {"file": str(DURATION), "line": line, "column": col,
                                                    "newName": "toMilliseconds"}, 4)
    DURATION.write_text(originals[DURATION])
    CALLER.write_text(originals[CALLER])
    ask(client, "12 verify after undoing everything", "verify_change", {}, 8)
finally:
    client.close()
    for path, text in originals.items():
        path.write_text(text)
    left = subprocess.run(["git", "-C", str(ws), "status", "--short"], capture_output=True, text=True).stdout
    print("\ngit status:", left.strip())
