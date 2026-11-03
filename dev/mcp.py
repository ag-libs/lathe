#!/usr/bin/env python3
"""Minimal MCP stdio driver for the Lathe MCP server — the analog of dev/lsp.py.

Spawns lathe-mcp-launcher.sh, performs the MCP initialize handshake, lists tools,
and optionally calls get_diagnostics / get_definition. Framing is newline-delimited
JSON-RPC (the SDK stdio transport), so no Content-Length headers.

Usage:
    python3 dev/mcp.py <workspace-root> [file.java [line col]]

The server is LATHE_MCP_LAUNCHER, else the workspace's own .lathe/lathe-mcp-launcher.sh (the version
that synced it), else the newest cached server. Each request times out after LATHE_MCP_TIMEOUT
seconds (default 120).
"""

import json
import os
import queue
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

PROTOCOL_VERSION = "2025-06-18"
# Lathe sets no heap limit; dev runs cap theirs so probes on large reactors stay light.
DEV_JVM_OPTS = "-Xmx2g"
# A hung tool must fail the probe that called it, not stall the whole run.
DEFAULT_TIMEOUT_S = float(os.environ.get("LATHE_MCP_TIMEOUT", "120"))


class McpClient:
    def __init__(self, launcher: Path, cwd: Path, echo_stderr: bool = True):
        self.launcher = launcher
        self.proc = subprocess.Popen(
            [str(launcher)],
            cwd=str(cwd),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env={**os.environ, "LATHE_JVM_OPTS": os.environ.get("LATHE_JVM_OPTS", DEV_JVM_OPTS)},
        )
        self._id = 0
        self._echo = echo_stderr
        self.stderr_lines = []
        self._messages = queue.Queue()
        threading.Thread(target=self._drain_stderr, daemon=True).start()
        threading.Thread(target=self._drain_stdout, daemon=True).start()

    def _drain_stderr(self):
        for line in self.proc.stderr:
            text = line.decode(errors="replace")
            self.stderr_lines.append(text.rstrip("\n"))
            if self._echo:
                sys.stderr.write("[server] " + text)

    # Reads on its own thread so a request can give up after a deadline.
    def _drain_stdout(self):
        for line in self.proc.stdout:
            try:
                self._messages.put(json.loads(line))
            except ValueError:
                self._messages.put({"unparseable": line.decode(errors="replace")})
        self._messages.put(None)

    def session_log(self):
        """The server's full log file, named in its startup line on stderr."""
        for line in self.stderr_lines:
            m = re.search(r"\blog=(\S+)", line)
            if m:
                return Path(m.group(1))
        return None

    def _send(self, msg: dict):
        self.proc.stdin.write((json.dumps(msg) + "\n").encode())
        self.proc.stdin.flush()

    def send_raw(self, line: str):
        self.proc.stdin.write((line + "\n").encode())
        self.proc.stdin.flush()

    def _read(self, timeout: float) -> dict:
        try:
            msg = self._messages.get(timeout=timeout)
        except queue.Empty:
            raise TimeoutError("no answer within %ds" % timeout) from None
        if msg is None:
            raise RuntimeError("server closed stdout without responding")
        return msg

    def request(self, method: str, params: dict, timeout: float = DEFAULT_TIMEOUT_S):
        self._id += 1
        rid = self._id
        self._send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        # One deadline for the request: notifications and late answers must not restart it.
        deadline = time.monotonic() + timeout
        while True:
            msg = self._read(max(0.0, deadline - time.monotonic()))
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

    def call_tool(self, name: str, arguments: dict, timeout: float = DEFAULT_TIMEOUT_S):
        return self.request("tools/call", {"name": name, "arguments": arguments}, timeout)

    def close(self, grace_s: float = 5.0) -> bool:
        """Close stdin and wait for the server to exit on its own; True if it did."""
        try:
            self.proc.stdin.close()
        except OSError:
            pass
        try:
            self.proc.wait(timeout=grace_s)
            return True
        except subprocess.TimeoutExpired:
            self.proc.terminate()
            return False


def _version_key(path: Path):
    """Orders server dirs by version number; a release sorts above its own SNAPSHOT."""
    name = path.parent.name
    base, _, qualifier = name.partition("-")
    nums = tuple(int(n) if n.isdigit() else 0 for n in base.split("."))
    return nums, qualifier == ""


def launcher_for(workspace: Path) -> Path:
    """LATHE_MCP_LAUNCHER, else the workspace's own launcher (the version that synced it), else the
    newest cached server."""
    if os.environ.get("LATHE_MCP_LAUNCHER"):
        return Path(os.environ["LATHE_MCP_LAUNCHER"])
    own = Path(workspace) / ".lathe" / "lathe-mcp-launcher.sh"
    if own.is_file():
        return own
    return _cached_launcher()


def _cached_launcher() -> Path:
    cache = os.environ.get("LATHE_CACHE", str(Path.home() / ".cache" / "lathe"))
    servers = Path(cache) / "servers"
    launchers = sorted(servers.glob("*/lathe-mcp-launcher.sh"), key=_version_key)
    if launchers:
        return launchers[-1]
    raise SystemExit("no lathe-mcp-launcher.sh under %s — install the server first" % servers)


def _print_text(result: dict):
    print("  isError:", result.get("isError"))
    for content in result.get("content", []):
        if content.get("type") == "text":
            print("\n".join("    " + line for line in content["text"].splitlines()))


def main(argv):
    workspace = Path(argv[1]).resolve() if len(argv) > 1 else Path.cwd()
    launcher = launcher_for(workspace)
    client = McpClient(launcher, workspace)
    try:
        init = client.initialize()
        print("== initialize ==")
        print("  serverInfo:", init.get("serverInfo"))
        print("  protocolVersion:", init.get("protocolVersion"))

        tools = client.tools_list()
        print("== tools/list ==")
        for tool in tools.get("tools", []):
            print("  %-22s %s" % (tool["name"], tool.get("annotations")))

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
