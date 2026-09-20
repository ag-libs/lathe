#!/usr/bin/env python3
"""Minimal MCP stdio driver for the Lathe MCP server — the analog of dev/lsp.py.

Spawns lathe-mcp-launcher.sh, performs the MCP initialize handshake, lists tools,
and optionally calls get_diagnostics / get_definition. Framing is newline-delimited
JSON-RPC (the SDK stdio transport), so no Content-Length headers.

Usage:
    LATHE_MCP_LAUNCHER=/path/to/lathe-mcp-launcher.sh \\
        python3 dev/mcp.py <workspace-root> [file.java [line col]]
"""

import json
import os
import subprocess
import sys
import threading
from pathlib import Path

PROTOCOL_VERSION = "2025-06-18"


class McpClient:
    def __init__(self, launcher: Path, cwd: Path):
        self.proc = subprocess.Popen(
            [str(launcher)],
            cwd=str(cwd),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        self._id = 0
        threading.Thread(target=self._drain_stderr, daemon=True).start()

    def _drain_stderr(self):
        for line in self.proc.stderr:
            sys.stderr.write("[server] " + line.decode(errors="replace"))

    def _send(self, msg: dict):
        self.proc.stdin.write((json.dumps(msg) + "\n").encode())
        self.proc.stdin.flush()

    def _read(self) -> dict:
        line = self.proc.stdout.readline()
        if not line:
            raise RuntimeError("server closed stdout without responding")
        return json.loads(line)

    def request(self, method: str, params: dict):
        self._id += 1
        rid = self._id
        self._send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        while True:
            msg = self._read()
            if msg.get("id") == rid:
                if "error" in msg:
                    raise RuntimeError("%s error: %s" % (method, msg["error"]))
                return msg.get("result")

    def notify(self, method: str, params: dict | None = None):
        self._send({"jsonrpc": "2.0", "method": method, "params": params or {}})

    def initialize(self):
        result = self.request(
            "initialize",
            {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "dev-mcp", "version": "0"},
            },
        )
        self.notify("notifications/initialized")
        return result

    def tools_list(self):
        return self.request("tools/list", {})

    def call_tool(self, name: str, arguments: dict):
        return self.request("tools/call", {"name": name, "arguments": arguments})

    def close(self):
        try:
            self.proc.stdin.close()
        except OSError:
            pass
        self.proc.terminate()


def _default_launcher() -> Path:
    cache = os.environ.get("LATHE_CACHE", str(Path.home() / ".cache" / "lathe"))
    return Path(cache) / "current" / "lathe-mcp-launcher.sh"


def _print_text(result: dict):
    print("  isError:", result.get("isError"))
    for content in result.get("content", []):
        if content.get("type") == "text":
            print("\n".join("    " + line for line in content["text"].splitlines()))


def main(argv):
    launcher = Path(os.environ.get("LATHE_MCP_LAUNCHER", str(_default_launcher())))
    workspace = Path(argv[1]).resolve() if len(argv) > 1 else Path.cwd()
    client = McpClient(launcher, workspace)
    try:
        init = client.initialize()
        print("== initialize ==")
        print("  serverInfo:", init.get("serverInfo"))
        print("  protocolVersion:", init.get("protocolVersion"))

        tools = client.tools_list()
        print("== tools/list ==")
        print("  tools:", [t["name"] for t in tools.get("tools", [])])

        if len(argv) > 2:
            file = str(Path(argv[2]).resolve())
            print("== get_diagnostics %s ==" % file)
            _print_text(client.call_tool("get_diagnostics", {"file": file}))

        if len(argv) > 4:
            print("== get_definition %s:%s ==" % (argv[3], argv[4]))
            _print_text(
                client.call_tool(
                    "get_definition",
                    {"file": file, "line": int(argv[3]), "column": int(argv[4])},
                )
            )
    finally:
        client.close()


if __name__ == "__main__":
    main(sys.argv)
