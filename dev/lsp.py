#!/usr/bin/env python3
"""
Lathe LSP exploratory client — drives the server directly via JSON-RPC.

Usage:
    python3 dev/lsp.py <file> [<file> ...]            # diagnostics for each file
    python3 dev/lsp.py <file>:<line>:<col> [...]      # diagnostics + hover (1-based line/col)

    Or import LatheClient for ad-hoc feature testing:

        import sys; sys.path.insert(0, "/home/ag-libs/design/lathe/dev")
        from lsp import LatheClient, find_workspace_root
        from pathlib import Path

        file = Path("/home/ag-libs/git/helidon/...")
        with LatheClient.start(find_workspace_root(file)) as c:
            c.open(file)
            print(c.hover(file, line=10, col=5))
            print(c.definition(file, line=10, col=5))
            print(c.completion(file, line=10, col=5))

Set LATHE_DEBUG=1 for verbose server logs (default: on in CLI mode).
Set LATHE_TIMEOUT=<seconds> to change the per-request wait (default: 15).
"""

import json
import os
import queue
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any, Callable

# ── Config ────────────────────────────────────────────────────────────────────

LATHE_LAUNCHER = os.environ.get(
    "LATHE_LAUNCHER", str(Path.home() / ".cache/lathe/current/lathe-launcher.sh")
)
DEFAULT_TIMEOUT = int(os.environ.get("LATHE_TIMEOUT", "15"))


class RequestCancelledError(RuntimeError):
    """Raised when the server returns LSP RequestCancelled."""


class RequestHandle:
    """One in-flight JSON-RPC request that can be waited on or cancelled."""

    def __init__(self, client: "LatheClient", request_id: int, method: str,
                 responses: queue.SimpleQueue):
        self._client = client
        self.id = request_id
        self.method = method
        self.responses = responses

    def wait(self, timeout: int = DEFAULT_TIMEOUT) -> Any:
        return self._client._wait_response(self, timeout)

    def cancel(self) -> None:
        self._client.notify("$/cancelRequest", {"id": self.id})


# ── LatheClient ───────────────────────────────────────────────────────────────

class LatheClient:
    """
    Synchronous LSP client wrapper around the Lathe language server process.

    Use as a context manager or call .stop() explicitly.
    """

    def __init__(self, proc: subprocess.Popen, stderr_lines: list[str]):
        self._proc = proc
        self.stderr_lines = stderr_lines
        self._id = 0
        self._id_lock = threading.Lock()
        self._progress_id = 0
        self._send_lock = threading.Lock()
        # pending request futures: id -> queue(maxsize=1)
        self._pending: dict[int, queue.SimpleQueue] = {}
        self._pending_lock = threading.Lock()
        # notification queues keyed by method
        self._notifications: dict[str, queue.Queue] = {}
        self._notif_lock = threading.Lock()
        self._stopped = False

        self._reader = threading.Thread(target=self._read_loop, daemon=True)
        self._reader.start()

    # ── lifecycle ──────────────────────────────────────────────────────────

    @classmethod
    def start(cls, workspace_root: str | Path, debug: bool | None = None) -> "LatheClient":
        root = Path(workspace_root).resolve()
        cmd = [LATHE_LAUNCHER]
        use_debug = debug if debug is not None else bool(os.environ.get("LATHE_DEBUG"))
        env = {**os.environ, **({"LATHE_DEBUG": "1"} if use_debug else {})}
        proc = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env=env,
        )
        stderr_lines: list[str] = []
        threading.Thread(
            target=lambda: [stderr_lines.append(l.decode().rstrip()) for l in proc.stderr],
            daemon=True,
        ).start()

        client = cls(proc, stderr_lines)
        client._handshake(root)
        return client

    def _handshake(self, root: Path):
        resp = self.request("initialize", {
            "processId": os.getpid(),
            "rootUri": root.as_uri(),
            "workspaceFolders": [{"uri": root.as_uri(), "name": root.name}],
            "capabilities": {
                "textDocument": {
                    "publishDiagnostics": {"relatedInformation": False},
                    "hover": {"contentFormat": ["plaintext", "markdown"]},
                    "definition": {},
                    "references": {},
                    "implementation": {},
                    "typeHierarchy": {},
                    "completion": {"completionItem": {"snippetSupport": False}},
                    "signatureHelp": {},
                    "semanticTokens": {
                        "requests": {"full": True},
                        "tokenTypes": [],
                        "tokenModifiers": [],
                        "formats": ["relative"],
                    },
                },
                "window": {"workDoneProgress": True},
            },
        })
        self.notify("initialized", {})
        return resp

    def stop(self):
        if self._stopped:
            return
        self._stopped = True
        try:
            self.request("shutdown", None, timeout=3)
            self.notify("exit", None)
        except Exception:
            pass
        self._wait_or_terminate()

    def close_transport(self):
        """Close server stdin and require bounded process exit without LSP shutdown."""
        if self._stopped:
            return
        self._stopped = True
        self._proc.stdin.close()
        self._wait_or_terminate()

    def _wait_or_terminate(self, timeout: int = 3):
        try:
            self._proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            self._proc.terminate()
            self._proc.wait(timeout=timeout)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.stop()

    # ── transport ──────────────────────────────────────────────────────────

    def _next_id(self) -> int:
        with self._id_lock:
            self._id += 1
            return self._id

    def _next_progress_token(self) -> str:
        with self._id_lock:
            self._progress_id += 1
            return f"lathe-explorer-references-{self._progress_id}"

    def _send(self, msg: dict):
        body = json.dumps(msg)
        data = f"Content-Length: {len(body)}\r\n\r\n{body}".encode()
        with self._send_lock:
            self._proc.stdin.write(data)
            self._proc.stdin.flush()

    def _read_loop(self):
        while True:
            try:
                headers = {}
                while True:
                    line = self._proc.stdout.readline().decode()
                    if not line or line in ("\r\n", "\n"):
                        break
                    if ":" in line:
                        k, v = line.split(":", 1)
                        headers[k.strip()] = v.strip()
                length = int(headers.get("Content-Length", 0))
                if length == 0:
                    break
                msg = json.loads(self._proc.stdout.read(length))
            except Exception as exc:
                self.stderr_lines.append(f"explorer reader failed: {exc}")
                break

            if "id" in msg and "method" in msg:
                self._handle_server_request(msg)
            elif "id" in msg and "method" not in msg:
                # response to a request
                with self._pending_lock:
                    q = self._pending.pop(msg["id"], None)
                if q:
                    q.put(msg)
            elif "method" in msg and "id" not in msg:
                # notification
                method = msg["method"]
                with self._notif_lock:
                    q = self._notifications.setdefault(method, queue.Queue())
                q.put(msg)

    def _handle_server_request(self, msg: dict):
        if msg["method"] == "window/workDoneProgress/create":
            self._send({"jsonrpc": "2.0", "id": msg["id"], "result": None})
            return
        self._send({
            "jsonrpc": "2.0",
            "id": msg["id"],
            "error": {"code": -32601, "message": f"unsupported client method {msg['method']}"},
        })

    def request(self, method: str, params: Any, timeout: int = DEFAULT_TIMEOUT) -> Any:
        return self.request_async(method, params).wait(timeout)

    def request_async(self, method: str, params: Any) -> RequestHandle:
        req_id = self._next_id()
        q: queue.SimpleQueue = queue.SimpleQueue()
        with self._pending_lock:
            self._pending[req_id] = q
        msg = {"jsonrpc": "2.0", "id": req_id, "method": method}
        if params is not None:
            msg["params"] = params
        self._send(msg)
        return RequestHandle(self, req_id, method, q)

    def _wait_response(self, handle: RequestHandle, timeout: int) -> Any:
        try:
            resp = handle.responses.get(timeout=timeout)
        except queue.Empty:
            with self._pending_lock:
                self._pending.pop(handle.id, None)
            raise TimeoutError(f"{handle.method} timed out after {timeout}s")
        return self._response_result(handle.method, resp)

    @staticmethod
    def _response_result(method: str, resp: dict) -> Any:
        if "error" in resp:
            if resp["error"].get("code") == -32800:
                raise RequestCancelledError(f"{method} cancelled")
            raise RuntimeError(f"{method} error: {resp['error']}")
        return resp.get("result")

    def notify(self, method: str, params: Any):
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        self._send(msg)

    def wait_notification(
        self,
        method: str,
        predicate: Callable[[dict], bool] = lambda _: True,
        timeout: int = DEFAULT_TIMEOUT,
    ) -> dict:
        with self._notif_lock:
            if method not in self._notifications:
                self._notifications[method] = queue.Queue()
            q = self._notifications[method]

        deadline = time.time() + timeout
        while True:
            remaining = deadline - time.time()
            if remaining <= 0:
                raise TimeoutError(f"notification {method!r} timed out after {timeout}s")
            try:
                msg = q.get(timeout=remaining)
                if predicate(msg):
                    return msg
            except queue.Empty:
                raise TimeoutError(f"notification {method!r} timed out after {timeout}s")

    def _notification_queue(self, method: str) -> queue.Queue:
        with self._notif_lock:
            if method not in self._notifications:
                self._notifications[method] = queue.Queue()
            return self._notifications[method]

    # ── LSP feature helpers ────────────────────────────────────────────────

    def open(self, file: str | Path, timeout: int = DEFAULT_TIMEOUT) -> list[dict]:
        """Open file and return diagnostics."""
        p = Path(file).resolve()
        uri = p.as_uri()
        self._notification_queue("textDocument/publishDiagnostics")
        self.notify("textDocument/didOpen", {
            "textDocument": {
                "uri": uri,
                "languageId": "java",
                "version": 1,
                "text": p.read_text(),
            }
        })
        msg = self.wait_notification(
            "textDocument/publishDiagnostics",
            predicate=lambda m: m["params"]["uri"] == uri,
            timeout=timeout,
        )
        return msg["params"]["diagnostics"]

    def change(self, file: str | Path, content: str, version: int = 2):
        """Send didChange with full new content (no diagnostics wait)."""
        uri = Path(file).resolve().as_uri()
        self.notify("textDocument/didChange", {
            "textDocument": {"uri": uri, "version": version},
            "contentChanges": [{"text": content}],
        })

    def save(self, file: str | Path, timeout: int = DEFAULT_TIMEOUT) -> list[dict]:
        """Send didSave and return resulting diagnostics."""
        p = Path(file).resolve()
        uri = p.as_uri()
        self._notification_queue("textDocument/publishDiagnostics")
        self.notify("textDocument/didSave", {"textDocument": {"uri": uri}})
        msg = self.wait_notification(
            "textDocument/publishDiagnostics",
            predicate=lambda m: m["params"]["uri"] == uri,
            timeout=timeout,
        )
        return msg["params"]["diagnostics"]

    def close(self, file: str | Path):
        uri = Path(file).resolve().as_uri()
        self.notify("textDocument/didClose", {"textDocument": {"uri": uri}})

    def signature_help(self, file: str | Path, line: int, col: int) -> dict | None:
        """Signature help at 0-based line/col. Returns LSP SignatureHelp result or None."""
        return self.request("textDocument/signatureHelp", {
            "textDocument": {"uri": Path(file).resolve().as_uri()},
            "position": {"line": line, "character": col},
        })

    def hover(self, file: str | Path, line: int, col: int) -> dict | None:
        """Hover at 0-based line/col. Returns LSP Hover result or None."""
        return self.request("textDocument/hover", {
            "textDocument": {"uri": Path(file).resolve().as_uri()},
            "position": {"line": line, "character": col},
        })

    def definition(self, file: str | Path, line: int, col: int) -> list[dict]:
        """Go-to-definition at 0-based line/col. Returns list of LocationLinks."""
        result = self.request("textDocument/definition", {
            "textDocument": {"uri": Path(file).resolve().as_uri()},
            "position": {"line": line, "character": col},
        })
        if result is None:
            return []
        return result if isinstance(result, list) else [result]

    def references(self, file: str | Path, line: int, col: int,
                   include_declaration: bool = False,
                   on_progress: Callable[[dict], None] | None = None,
                   cancel_after: int | None = None,
                   cancel_mode: str = "progress") -> list[dict]:
        """Find references at 0-based line/col."""
        token = self._next_progress_token()
        progress = self._notification_queue("$/progress")
        handle = self.request_async("textDocument/references", {
            "textDocument": {"uri": Path(file).resolve().as_uri()},
            "position": {"line": line, "character": col},
            "context": {"includeDeclaration": include_declaration},
            "workDoneToken": token,
        })
        deadline = time.monotonic() + DEFAULT_TIMEOUT
        cancelled = False
        while True:
            completed = self._drain_reference_progress(progress, token, on_progress)
            if cancel_after is not None and not cancelled:
                if completed is not None and completed >= cancel_after:
                    if cancel_mode == "request":
                        handle.cancel()
                    elif cancel_mode == "shutdown":
                        self.stop()
                        raise RequestCancelledError("server stopped during references")
                    elif cancel_mode == "eof":
                        self.close_transport()
                        raise RequestCancelledError("transport closed during references")
                    else:
                        self.notify("window/workDoneProgress/cancel", {"token": token})
                    cancelled = True
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                with self._pending_lock:
                    self._pending.pop(handle.id, None)
                raise TimeoutError(
                    f"textDocument/references timed out after {DEFAULT_TIMEOUT}s"
                )
            try:
                resp = handle.responses.get(timeout=min(0.1, remaining))
                self._drain_reference_progress(progress, token, on_progress)
                try:
                    result = self._response_result(handle.method, resp)
                except RequestCancelledError:
                    self._wait_reference_end(progress, token, on_progress)
                    raise
                break
            except queue.Empty:
                continue
        return result or []

    @staticmethod
    def _drain_reference_progress(progress: queue.Queue, token: str,
                                  on_progress: Callable[[dict], None] | None) -> int | None:
        latest = None
        retained = []
        while True:
            try:
                msg = progress.get_nowait()
            except queue.Empty:
                break
            if msg.get("params", {}).get("token") == token:
                value = msg["params"]["value"]
                if on_progress is not None:
                    on_progress(value)
                message = value.get("message", "")
                try:
                    completed = int(message.split("/", 1)[0].strip())
                    latest = completed if latest is None else max(latest, completed)
                except (ValueError, IndexError):
                    pass
            else:
                retained.append(msg)
        for msg in retained:
            progress.put(msg)
        return latest

    @staticmethod
    def _wait_reference_end(progress: queue.Queue, token: str,
                            on_progress: Callable[[dict], None] | None,
                            timeout: int = 5) -> None:
        deadline = time.monotonic() + timeout
        retained = []
        try:
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return
                try:
                    msg = progress.get(timeout=remaining)
                except queue.Empty:
                    return
                if msg.get("params", {}).get("token") != token:
                    retained.append(msg)
                    continue
                value = msg["params"]["value"]
                if on_progress is not None:
                    on_progress(value)
                if value.get("kind") == "end":
                    return
        finally:
            for msg in retained:
                progress.put(msg)

    def completion(self, file: str | Path, line: int, col: int) -> list[dict]:
        """Completion at 0-based line/col. Returns list of CompletionItems."""
        result = self.request("textDocument/completion", {
            "textDocument": {"uri": Path(file).resolve().as_uri()},
            "position": {"line": line, "character": col},
        })
        if result is None:
            return []
        if isinstance(result, list):
            return result
        return result.get("items", [])  # CompletionList

    def formatting(self, file: str | Path, tab_size: int = 4) -> list[dict]:
        """Full-file formatting. Returns list of TextEdits."""
        result = self.request("textDocument/formatting", {
            "textDocument": {"uri": Path(file).resolve().as_uri()},
            "options": {"tabSize": tab_size, "insertSpaces": True},
        })
        return result or []

    def document_symbols(self, file: str | Path) -> list[dict]:
        """Document symbols (outline). Returns list of DocumentSymbols."""
        result = self.request("textDocument/documentSymbol", {
            "textDocument": {"uri": Path(file).resolve().as_uri()},
        })
        return result or []

    def folding_ranges(self, file: str | Path) -> list[dict]:
        """Folding ranges. Returns list of FoldingRange objects."""
        result = self.request("textDocument/foldingRange", {
            "textDocument": {"uri": Path(file).resolve().as_uri()},
        })
        return result or []

    def workspace_symbol(self, query: str) -> list[dict]:
        """Workspace symbol search. Returns list of SymbolInformation."""
        result = self.request("workspace/symbol", {"query": query})
        return result or []

    def implementation(self, file: str | Path, line: int, col: int) -> list[dict]:
        """Go-to-implementation at 0-based line/col. Returns list of Locations."""
        result = self.request("textDocument/implementation", {
            "textDocument": {"uri": Path(file).resolve().as_uri()},
            "position": {"line": line, "character": col},
        })
        if result is None:
            return []
        return result if isinstance(result, list) else [result]

    def prepare_type_hierarchy(self, file: str | Path, line: int, col: int) -> list[dict]:
        """Prepare type hierarchy at 0-based line/col. Returns list of TypeHierarchyItems."""
        result = self.request("textDocument/prepareTypeHierarchy", {
            "textDocument": {"uri": Path(file).resolve().as_uri()},
            "position": {"line": line, "character": col},
        })
        return result or []

    def type_hierarchy_supertypes(self, item: dict) -> list[dict]:
        """Fetch supertypes for a TypeHierarchyItem. Returns list of TypeHierarchyItems."""
        result = self.request("typeHierarchy/supertypes", {"item": item})
        return result or []

    def type_hierarchy_subtypes(self, item: dict) -> list[dict]:
        """Fetch subtypes for a TypeHierarchyItem. Returns list of TypeHierarchyItems."""
        result = self.request("typeHierarchy/subtypes", {"item": item})
        return result or []

    def prepare_call_hierarchy(self, file: str | Path, line: int, col: int) -> list[dict]:
        """Prepare call hierarchy at 0-based line/col. Returns list of CallHierarchyItems."""
        result = self.request("textDocument/prepareCallHierarchy", {
            "textDocument": {"uri": Path(file).resolve().as_uri()},
            "position": {"line": line, "character": col},
        })
        return result or []

    def call_hierarchy_incoming(self, item: dict,
                                on_progress: Callable[[dict], None] | None = None) -> list[dict]:
        """Fetch incoming calls for a CallHierarchyItem. Returns list of CallHierarchyIncomingCalls."""
        token = self._next_progress_token()
        progress = self._notification_queue("$/progress")
        handle = self.request_async("callHierarchy/incomingCalls", {
            "item": item,
            "workDoneToken": token,
        })
        deadline = time.monotonic() + DEFAULT_TIMEOUT
        while True:
            self._drain_reference_progress(progress, token, on_progress)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                with self._pending_lock:
                    self._pending.pop(handle.id, None)
                raise TimeoutError(
                    f"callHierarchy/incomingCalls timed out after {DEFAULT_TIMEOUT}s"
                )
            try:
                resp = handle.responses.get(timeout=min(0.1, remaining))
                self._drain_reference_progress(progress, token, on_progress)
                try:
                    result = self._response_result(handle.method, resp)
                except RequestCancelledError:
                    self._wait_reference_end(progress, token, on_progress)
                    raise
                break
            except queue.Empty:
                continue
        return result or []

    def call_hierarchy_outgoing(self, item: dict) -> list[dict]:
        """Fetch outgoing calls for a CallHierarchyItem. Returns list of CallHierarchyOutgoingCalls."""
        result = self.request("callHierarchy/outgoingCalls", {"item": item})
        return result or []


# ── CLI ───────────────────────────────────────────────────────────────────────

def parse_arg(arg: str) -> tuple[Path, tuple[int, int] | None]:
    """Parse 'file.java' or 'file.java:line:col' (1-based line/col)."""
    parts = arg.rsplit(":", 2)
    if len(parts) == 3:
        try:
            line, col = int(parts[1]) - 1, int(parts[2]) - 1
            return Path(parts[0]).expanduser().resolve(), (line, col)
        except ValueError:
            pass
    return Path(arg).expanduser().resolve(), None


def find_workspace_root(file: Path) -> Path:
    for p in [file, *file.parents]:
        if (p / ".lathe").is_dir():
            return p
    raise RuntimeError(f"No .lathe/ directory found above {file}")


def print_diagnostics(file: Path, diags: list[dict]):
    print(f"\n{'='*60}")
    print(f"  {file.name}  ({file.parent})")
    print(f"{'='*60}")
    if not diags:
        print("  OK — no errors")
    else:
        for d in diags:
            sev = {1: "ERROR", 2: "WARN ", 3: "INFO "}.get(d["severity"], "?    ")
            line = d["range"]["start"]["line"] + 1
            col = d["range"]["start"]["character"] + 1
            msg = d["message"].split("\n")[0]
            print(f"  [{sev}] {line}:{col}  {msg}")
    print()


def print_hover(line: int, col: int, result: dict | None):
    print(f"  hover at {line + 1}:{col + 1}:")
    if result is None:
        print("    (no result)")
        return
    contents = result.get("contents", {})
    value = contents.get("value", "") if isinstance(contents, dict) else str(contents)
    for text_line in value.strip().splitlines():
        print(f"    {text_line}")
    print()


def main():
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help"):
        print(__doc__)
        sys.exit(0)

    targets = [parse_arg(arg) for arg in sys.argv[1:]]
    for file, _ in targets:
        if not file.exists():
            print(f"error: file not found: {file}", file=sys.stderr)
            sys.exit(1)

    workspace_root = find_workspace_root(targets[0][0])
    print(f"workspace: {workspace_root}", file=sys.stderr)
    print(f"server:    {LATHE_LAUNCHER}", file=sys.stderr)

    with LatheClient.start(workspace_root, debug=True) as client:
        for file, pos in targets:
            try:
                diags = client.open(file)
                print_diagnostics(file, diags)
                if pos is not None:
                    result = client.hover(file, pos[0], pos[1])
                    print_hover(pos[0], pos[1], result)
            except TimeoutError as e:
                print(f"\n  TIMEOUT: {e}\n")

    if os.environ.get("LATHE_DEBUG"):
        print("=== server log ===")
        for line in client.stderr_lines:
            print(line)


if __name__ == "__main__":
    main()
