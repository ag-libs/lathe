#!/usr/bin/env python3
"""End-to-end debug probe for Lathe -- the automatable Phase 1 GO/NO-GO.

Drives the whole debug flow with no editor and no human: it asks the live Lathe server to
`lathe.debug.test` a captured test (launching it suspended under a JDWP agent and opening an
in-process DAP host), then speaks raw DAP to that host exactly as nvim-dap would --
initialize -> attach -> setBreakpoints -> configurationDone -> stopped -> stackTrace/scopes/
variables -> continue -> terminated. It asserts the breakpoint stops on the expected line and
prints the inspected frame, then exits 0 (green) or 1 (red).

Only the Microsoft java-debug adapter speaks DAP/JDWP; this probe is a thin client. It reuses
`lsp.LatheClient` to open the file (so the module worker attributes it -- the source-lookup
provider reads that cache) and to issue `lathe.debug.test`.

Usage:
    python3 dev/debug_probe.py --workspace <root> <TestFile.java> --line <N> [--method <name>]

Example (public multi-module invoker fixture -- see dev/debug-e2e.sh):
    python3 dev/debug_probe.py \\
        --workspace lathe-maven-plugin/target/it/multi-module \\
        lathe-maven-plugin/target/it/multi-module/jpms/src/test/java/com/example/jpms/HelloTest.java \\
        --line 16 --method greet_returnsExpectedMessage
"""

from __future__ import annotations

import argparse
import json
import socket
import sys
import threading
import time
import queue
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from lsp import LatheClient  # noqa: E402


# ── DAP wire client ──────────────────────────────────────────────────────────

class DapClient:
    """Minimal DAP client over a TCP socket: Content-Length framing, requests correlated by seq,
    events drained from a queue. Mirrors the LSP transport in lsp.py, one protocol down."""

    def __init__(self, host: str, port: int, timeout: float = 30.0):
        self._sock = socket.create_connection((host, port), timeout=timeout)
        self._sock.settimeout(timeout)
        self._buf = b""
        self._seq = 0
        self._responses: dict[int, dict] = {}
        self._resp_cv = threading.Condition()
        self._events: "queue.Queue[dict]" = queue.Queue()
        self._alive = True
        self._reader = threading.Thread(target=self._read_loop, daemon=True)
        self._reader.start()

    def request(self, command: str, arguments: dict | None = None, timeout: float = 30.0) -> dict:
        self._seq += 1
        seq = self._seq
        self._send({"seq": seq, "type": "request", "command": command,
                    **({"arguments": arguments} if arguments is not None else {})})
        with self._resp_cv:
            if not self._resp_cv.wait_for(lambda: seq in self._responses, timeout=timeout):
                raise TimeoutError(f"no DAP response to {command}")
            resp = self._responses.pop(seq)
        if not resp.get("success", False):
            raise RuntimeError(f"DAP {command} failed: {resp.get('message')}")
        return resp

    def wait_event(self, name: str, timeout: float = 30.0) -> dict:
        """Drain events until one named `name` arrives (returns it); raise on `terminated`/`exited`
        seen first when they are not the awaited event."""
        deadline = timeout
        while True:
            try:
                evt = self._events.get(timeout=deadline)
            except queue.Empty:
                raise TimeoutError(f"no DAP '{name}' event")
            if evt.get("event") == name:
                return evt
            if evt.get("event") in ("terminated", "exited") and name not in ("terminated", "exited"):
                raise RuntimeError(f"debuggee ended before '{name}' (saw '{evt.get('event')}')")

    def close(self):
        self._alive = False
        try:
            self._sock.close()
        except OSError:
            pass

    def _send(self, msg: dict):
        body = json.dumps(msg).encode()
        self._sock.sendall(f"Content-Length: {len(body)}\r\n\r\n".encode() + body)

    def _read_loop(self):
        while self._alive:
            try:
                msg = self._read_message()
            except Exception:
                break
            if msg is None:
                break
            kind = msg.get("type")
            if kind == "response":
                with self._resp_cv:
                    self._responses[msg["request_seq"]] = msg
                    self._resp_cv.notify_all()
            elif kind == "event":
                self._events.put(msg)

    def _read_message(self) -> dict | None:
        while b"\r\n\r\n" not in self._buf:
            chunk = self._sock.recv(4096)
            if not chunk:
                return None
            self._buf += chunk
        head, self._buf = self._buf.split(b"\r\n\r\n", 1)
        length = 0
        for line in head.decode().split("\r\n"):
            if line.lower().startswith("content-length:"):
                length = int(line.split(":", 1)[1].strip())
        while len(self._buf) < length:
            chunk = self._sock.recv(4096)
            if not chunk:
                return None
            self._buf += chunk
        body, self._buf = self._buf[:length], self._buf[length:]
        return json.loads(body)


# ── probe choreography ───────────────────────────────────────────────────────

MAIN_KIND = 0  # RunnableKind ordinals
TEST_METHOD_KIND = 1


def select_target(client: LatheClient, file: Path, method: str | None) -> dict:
    """Pick the TEST_METHOD runnable to debug: the one whose id contains `method`, or the only
    test method when `method` is unset. Returns {moduleRel, selectorValue}."""
    targets = [t for t in client.runnables(file) if t.get("kind") == TEST_METHOD_KIND]
    if not targets:
        raise SystemExit(f"[probe] no test methods discovered in {file}")
    if method:
        targets = [t for t in targets if method in t.get("id", "")]
        if not targets:
            raise SystemExit(f"[probe] no test method matching '{method}' in {file}")
    picked = targets[0]
    return {"moduleRel": picked["moduleRel"], "selectorValue": picked["id"]}


def resolve_main_module(client: LatheClient, file: Path, main_class: str) -> str:
    """The moduleRel of the MAIN runnable whose class is `main_class`, from the file's discovery."""
    for target in client.runnables(file):
        if target.get("kind") == MAIN_KIND and target.get("parentId") == main_class:
            return target["moduleRel"]
    raise SystemExit(f"[probe] no main class '{main_class}' discovered in {file}")


def run_probe(workspace: Path, file: Path, line: int, method: str | None,
              main_class: str | None, detach: bool = False,
              eval_expr: str | None = None, expect: str | None = None,
              condition: str | None = None, expect_stop: bool = True,
              complete: str | None = None, expect_item: str | None = None) -> int:
    with LatheClient.start(workspace) as lathe:
        lathe.open(file)  # attribute the file in the module worker (source lookup reads that cache)
        if main_class:
            module_rel = resolve_main_module(lathe, file, main_class)
            print(f"[probe] debugging main {main_class} (module {module_rel}), "
                  f"breakpoint {file.name}:{line}")
            result = lathe.debug_main(module_rel, main_class)
        else:
            sel = select_target(lathe, file, method)
            print(f"[probe] debugging {sel['selectorValue']} (module {sel['moduleRel']}), "
                  f"breakpoint {file.name}:{line}")
            result = lathe.debug_test(sel["moduleRel"], [{
                "selectorKind": "METHOD",
                "selectorValue": sel["selectorValue"],
            }])

        dap_port, jdwp_port = result["dapPort"], result["jdwpPort"]
        print(f"[probe] dapPort={dap_port} jdwpPort={jdwp_port}")

        dap = DapClient("127.0.0.1", dap_port)
        try:
            if condition is not None:
                rc = _drive_condition(dap, file, line, jdwp_port, condition, expect_stop)
            elif complete is not None:
                rc = _drive_complete(dap, file, line, jdwp_port, complete, expect_item)
            elif eval_expr is not None:
                rc = _drive_eval(dap, file, line, jdwp_port, eval_expr, expect)
            elif detach:
                rc = _drive_detach(dap, file, line, jdwp_port)
            else:
                rc = _drive(dap, file, line, jdwp_port)
        except BaseException:
            _dump_server_log(lathe)
            raise
        finally:
            dap.close()
        if rc != 0:
            _dump_server_log(lathe)
        return rc


def _dump_server_log(lathe: LatheClient):
    """On failure, surface the server-side debug logs (the DAP host + source-lookup path) so the
    cause is visible -- the server's stderr is captured into the client, not the console."""
    lines = [l for l in lathe.stderr_lines if "[debug]" in l or "SEVERE" in l]
    if lines:
        print("[probe] --- server debug log ---")
        for line in lines[-20:]:
            print("[probe]   " + line)


def _drive(dap: DapClient, file: Path, line: int, jdwp_port: int) -> int:
    dap.request("initialize", {"adapterID": "lathe", "clientID": "lathe-probe",
                               "linesStartAt1": True, "columnsStartAt1": True,
                               "pathFormat": "path"})
    _attach_with_retry(dap, jdwp_port)
    dap.wait_event("initialized")

    bp = dap.request("setBreakpoints", {
        "source": {"path": str(file)},
        "breakpoints": [{"line": line}],
    })
    # verified=false here is expected: the debuggee is suspended before main, so the class is not
    # loaded yet. The adapter registers a ClassPrepareRequest and binds the breakpoint when the
    # class loads after configurationDone resumes it. The `stopped` event below is the real signal.
    state = bp["body"]["breakpoints"][0]
    print(f"[probe] breakpoint registered at line {state.get('line', line)} "
          f"(verified={state.get('verified')} -- binds on class load)")

    dap.request("configurationDone")

    stopped = dap.wait_event("stopped")
    reason = stopped["body"].get("reason")
    thread_id = stopped["body"]["threadId"]
    print(f"[probe] stopped: reason={reason} thread={thread_id}")

    frames = dap.request("stackTrace", {"threadId": thread_id})["body"]["stackFrames"]
    top = frames[0]
    stop_line = top.get("line")
    print(f"[probe] top frame: {top.get('name')} at {Path(top['source']['path']).name}:{stop_line}")

    _dump_variables(dap, top["id"])

    dap.request("continue", {"threadId": thread_id})
    dap.wait_event("terminated")
    print("[probe] debuggee resumed to completion")

    if reason != "breakpoint" or stop_line != line:
        print(f"[probe] FAIL: expected a breakpoint stop at line {line}, got {reason} @ {stop_line}")
        return 1

    print("[probe] PASS")
    return 0


def _drive_eval(
    dap: DapClient, file: Path, line: int, jdwp_port: int, expr: str, expect: str | None) -> int:
    """Stop at the breakpoint, then send a DAP `evaluate` for `expr` against the top frame (as a
    watch/hover does) and check the rendered result -- the read-only expression-evaluation GO/NO-GO."""
    dap.request("initialize", {"adapterID": "lathe", "clientID": "lathe-probe",
                               "linesStartAt1": True, "columnsStartAt1": True, "pathFormat": "path"})
    _attach_with_retry(dap, jdwp_port)
    dap.wait_event("initialized")
    dap.request("setBreakpoints", {"source": {"path": str(file)}, "breakpoints": [{"line": line}]})
    dap.request("configurationDone")

    stopped = dap.wait_event("stopped")
    thread_id = stopped["body"]["threadId"]
    frame_id = dap.request("stackTrace", {"threadId": thread_id})["body"]["stackFrames"][0]["id"]

    result = dap.request(
        "evaluate", {"expression": expr, "frameId": frame_id, "context": "watch"})["body"]["result"]
    print(f"[probe] evaluate({expr!r}) = {result}")

    dap.request("continue", {"threadId": thread_id})
    if expect is not None and expect != result:
        print(f"[probe] FAIL: expected {expect!r}, got {result!r}")
        return 1

    print("[probe] PASS")
    return 0


def _drive_complete(
    dap: DapClient, file: Path, line: int, jdwp_port: int, text: str, expect_item: str | None) -> int:
    """Stop at the breakpoint, then send a DAP `completions` for `text` (cursor at end) against the
    top frame -- the debug-console completion GO/NO-GO (DB-4). Asserts `expect_item` is offered.
    Cursor-at-end sidesteps the 0-vs-1-based column question: the provider clamps to the snippet
    length either way, which is the common REPL case (complete what you just typed)."""
    dap.request("initialize", {"adapterID": "lathe", "clientID": "lathe-probe",
                               "linesStartAt1": True, "columnsStartAt1": True, "pathFormat": "path"})
    _attach_with_retry(dap, jdwp_port)
    dap.wait_event("initialized")
    dap.request("setBreakpoints", {"source": {"path": str(file)}, "breakpoints": [{"line": line}]})
    dap.request("configurationDone")

    stopped = dap.wait_event("stopped")
    thread_id = stopped["body"]["threadId"]
    frame_id = dap.request("stackTrace", {"threadId": thread_id})["body"]["stackFrames"][0]["id"]

    targets = dap.request(
        "completions",
        {"frameId": frame_id, "text": text, "column": len(text) + 1})["body"]["targets"]
    labels = [t.get("label") for t in targets]
    print(f"[probe] completions({text!r}) = {len(labels)} items: {labels[:12]}")

    dap.request("continue", {"threadId": thread_id})
    if expect_item is not None and expect_item not in labels:
        print(f"[probe] FAIL: expected an item {expect_item!r}, got {labels[:20]}")
        return 1

    print("[probe] PASS")
    return 0


def _drive_condition(
    dap: DapClient, file: Path, line: int, jdwp_port: int, condition: str, expect_stop: bool) -> int:
    """Set a conditional breakpoint and verify it suspends only when the condition holds -- exercises
    evaluateForBreakpoint (the adapter evaluates the condition on each hit and inverts the result)."""
    dap.request("initialize", {"adapterID": "lathe", "clientID": "lathe-probe",
                               "linesStartAt1": True, "columnsStartAt1": True, "pathFormat": "path"})
    _attach_with_retry(dap, jdwp_port)
    dap.wait_event("initialized")
    dap.request("setBreakpoints",
                {"source": {"path": str(file)}, "breakpoints": [{"line": line, "condition": condition}]})
    dap.request("configurationDone")

    try:
        event = dap.wait_event("stopped")
        stopped = True
        dap.request("continue", {"threadId": event["body"]["threadId"]})
    except RuntimeError:
        stopped = False  # debuggee ran to termination without the condition ever holding

    print(f"[probe] condition {condition!r}: stopped={stopped} (expected {expect_stop})")
    if stopped != expect_stop:
        return 1

    print("[probe] PASS")
    return 0


def _drive_detach(dap: DapClient, file: Path, line: int, jdwp_port: int) -> int:
    """Stop at the breakpoint, then disconnect (as nvim-dap does when you stop debugging) and verify
    the debuggee is torn down -- its JDWP socket closes -- rather than left as an orphaned JVM."""
    dap.request("initialize", {"adapterID": "lathe", "clientID": "lathe-probe",
                               "linesStartAt1": True, "columnsStartAt1": True, "pathFormat": "path"})
    _attach_with_retry(dap, jdwp_port)
    dap.wait_event("initialized")
    dap.request("setBreakpoints", {"source": {"path": str(file)}, "breakpoints": [{"line": line}]})
    dap.request("configurationDone")
    dap.wait_event("stopped")
    print("[probe] stopped at breakpoint; sending disconnect(terminateDebuggee=true)")

    dap.request("disconnect", {"terminateDebuggee": True})
    if _await_port_closed(jdwp_port, timeout=10.0):
        print("[probe] PASS: debuggee torn down on disconnect (jdwp port closed, no orphan)")
        return 0

    print("[probe] FAIL: debuggee still alive after disconnect (jdwp port open -- orphaned JVM)")
    return 1


def _await_port_closed(port: int, timeout: float) -> bool:
    """True once a connection to the loopback `port` is refused (the debuggee JVM has exited)."""
    remaining = timeout
    step = 0.25
    while remaining > 0:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                pass
        except OSError:
            return True
        time.sleep(step)
        remaining -= step
    return False


def _attach_with_retry(dap: DapClient, jdwp_port: int, attempts: int = 40, delay: float = 0.5):
    """Attach, retrying while the debuggee's JDWP agent is still coming up. Launcher spawns the JVM
    and returns before the -agentlib:jdwp socket is listening, so the first attach can race the
    agent (Connection refused); suspend=y guarantees it appears shortly and then parks."""
    for attempt in range(attempts):
        try:
            dap.request("attach", {"hostName": "127.0.0.1", "port": jdwp_port})
            return
        except RuntimeError as exc:
            if "refused" not in str(exc).lower() or attempt == attempts - 1:
                raise
            time.sleep(delay)


def _dump_variables(dap: DapClient, frame_id: int):
    scopes = dap.request("scopes", {"frameId": frame_id})["body"]["scopes"]
    for scope in scopes:
        ref = scope.get("variablesReference")
        if not ref:
            continue
        variables = dap.request("variables", {"variablesReference": ref})["body"]["variables"]
        shown = ", ".join(f"{v['name']}={v.get('value')}" for v in variables[:8])
        print(f"[probe] scope {scope['name']}: {shown or '(none)'}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Lathe debug e2e probe")
    parser.add_argument("--workspace", required=True, type=Path, help="Lathe workspace root")
    parser.add_argument("file", type=Path, help="source file to breakpoint")
    parser.add_argument("--line", required=True, type=int, help="1-based breakpoint line")
    parser.add_argument("--method", help="substring of the test method id to debug")
    parser.add_argument("--main", dest="main_class",
                        help="fully-qualified main class to debug (instead of a test)")
    parser.add_argument("--detach", action="store_true",
                        help="stop at the breakpoint, then disconnect and verify no orphaned JVM")
    parser.add_argument("--eval", dest="eval_expr",
                        help="evaluate this expression at the breakpoint (via DAP evaluate)")
    parser.add_argument("--expect", help="assert the evaluated result equals this string")
    parser.add_argument("--condition", help="set a conditional breakpoint with this expression")
    parser.add_argument("--expect-nostop", dest="expect_nostop", action="store_true",
                        help="with --condition, expect the breakpoint NOT to suspend")
    parser.add_argument("--complete",
                        help="request debug-console completions for this text (cursor at end)")
    parser.add_argument("--expect-item", dest="expect_item",
                        help="assert this label is among the completions")
    args = parser.parse_args()

    workspace = args.workspace.resolve()
    file = args.file.resolve()
    return run_probe(workspace, file, args.line, args.method, args.main_class, args.detach,
                     args.eval_expr, args.expect, args.condition, not args.expect_nostop,
                     args.complete, args.expect_item)


if __name__ == "__main__":
    raise SystemExit(main())
