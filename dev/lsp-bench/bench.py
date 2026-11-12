#!/usr/bin/env python3
"""Runs the pre-registered queries (README.md) against Lathe and jdtls on one project and records
readiness, build or import time, memory, CPU, latency and correctness. Run ./setup.sh first.

    python3 dev/lsp-bench/bench.py spring-petclinic                  # 3 cold + 3 warm runs per server, -Xmx2G
    python3 dev/lsp-bench/bench.py spring-petclinic --runs 1 --servers lathe
    python3 dev/lsp-bench/bench.py spring-petclinic --heap 4G        # both at another heap: a labelled variant
    python3 dev/lsp-bench/bench.py spring-petclinic --prime          # downloads, before measuring

Results go to dev/lsp-bench/results/<time>-<project>/: a JSON file and the server's log per run,
environment.json, and report.md.
"""
import argparse
import datetime
import importlib
import json
import os
import platform
import queue
import re
import statistics
import subprocess
import sys
import urllib.parse
import threading
import time
from pathlib import Path

BENCH = Path(__file__).resolve().parent
sys.path.insert(0, str(BENCH))
sys.path.insert(0, str(BENCH.parent))

import measure as M  # noqa: E402
import projects as P  # noqa: E402
import queries as Q  # noqa: E402
import servers as S  # noqa: E402
from lsp import LspClient  # noqa: E402

RESULTS = BENCH / "results"
LOAD_ATTEMPTS = 3

# The same client capabilities for every server.
CLIENT_CAPABILITIES = {
    "workspace": {"configuration": True, "workspaceFolders": True, "symbol": {}, "applyEdit": True,
                  "workspaceEdit": {"documentChanges": True, "resourceOperations": ["create", "rename", "delete"]}},
    "window": {"workDoneProgress": True},
    "textDocument": {
        "synchronization": {"didSave": True},
        "definition": {"linkSupport": True},
        "typeDefinition": {"linkSupport": True},
        "implementation": {"linkSupport": True},
        "hover": {"contentFormat": ["markdown", "plaintext"]},
        "references": {},
        "documentHighlight": {},
        "documentSymbol": {"hierarchicalDocumentSymbolSupport": True},
        "signatureHelp": {"signatureInformation": {"parameterInformation": {"labelOffsetSupport": True}}},
        "typeHierarchy": {},
        "callHierarchy": {},
        "publishDiagnostics": {},
        # As Neovim (0.12) declares them, so both servers send what Neovim gets.
        "rename": {"prepareSupport": True},
        "codeAction": {"dataSupport": True, "isPreferredSupport": True,
                       "resolveSupport": {"properties": ["edit", "command"]},
                       "codeActionLiteralSupport": {"codeActionKind": {"valueSet": [
                           "", "quickfix", "refactor", "refactor.extract", "refactor.inline", "refactor.rewrite",
                           "source", "source.organizeImports"]}}},
        "completion": {"completionItem": {"snippetSupport": True,
                                          "resolveSupport": {"properties": ["documentation", "detail",
                                                                            "additionalTextEdits"]}}},
    },
}


class StampedQueue(queue.Queue):
    """A notification queue that keeps when each notification arrived."""

    def __init__(self):
        super().__init__()
        self.times = []

    def put(self, item, block=True, timeout=None):
        self.times.append((time.monotonic(), item))
        super().put(item, block, timeout)


class BenchClient(LspClient):
    """The dev client's transport, answering what an editor answers to the server's own requests."""

    def __init__(self, proc, settings):
        # Every write goes through one writer thread, so the reader never waits on a write: a server
        # that stops reading its input while it imports would otherwise fill the pipe, block a write
        # that holds the send lock, stop the reader answering the server's own requests, and the
        # server would block writing its output.
        self.outbox = queue.Queue()
        threading.Thread(target=self._write_loop, args=(proc,), daemon=True).start()
        super().__init__(proc, [])
        self.settings = settings
        self.applied_edits = []
        # The type an import case removed: what a user picks among ambiguous candidates.
        self.wanted_import = None
        self.status = StampedQueue()
        self.diagnostics = StampedQueue()
        with self._notif_lock:
            self._notifications["language/status"] = self.status
            self._notifications["textDocument/publishDiagnostics"] = self.diagnostics

    def _send(self, msg):
        # Content-Length counts bytes: an opened file with non-ASCII text must not break the framing.
        body = json.dumps(msg).encode("utf-8")
        self.outbox.put(b"Content-Length: %d\r\n\r\n" % len(body) + body)

    def _write_loop(self, proc):
        while True:
            data = self.outbox.get()
            try:
                proc.stdin.write(data)
                proc.stdin.flush()
            except (BrokenPipeError, ValueError, OSError):
                return

    def _handle_server_request(self, msg):
        self.server_request_counts[msg["method"]] += 1
        result = None
        if msg["method"] == "workspace/configuration":
            result = [self.setting(item.get("section")) for item in msg.get("params", {}).get("items", [])]
        elif msg["method"] == "workspace/executeClientCommand":
            result = self.client_command(msg.get("params", {}))
        elif msg["method"] == "workspace/applyEdit":
            # Applied, as an editor answers; the case reads the edit from the action itself.
            self.applied_edits.append(msg.get("params", {}).get("edit"))
            result = {"applied": True}
        self._send({"jsonrpc": "2.0", "id": msg["id"], "result": result})

    def client_command(self, params):
        """jdtls's commands that nvim-jdtls carries out: choosing among a name's import candidates picks
        the type the user wants (the case's), else the first, as nvim-jdtls takes a lone candidate."""
        if params.get("command") == "java.action.organizeImports.chooseImports":
            return [next((c for c in selection["candidates"] if c.get("fullyQualifiedName") == self.wanted_import),
                         selection["candidates"][0]) for selection in params["arguments"][1]]
        return None

    def setting(self, section):
        node = self.settings
        for part in section.split(".") if section else []:
            if not isinstance(node, dict) or part not in node:
                return None
            node = node[part]
        return node

    def started_at(self):
        """When jdtls reported its import done (language/status Started), if it has."""
        return next((t for t, msg in self.status.times
                     if msg.get("params", {}).get("type") in ("Started", "ServiceReady")), None)


def timed(client, repo, case, timeout):
    start = time.monotonic()
    try:
        result, error = Q.ask(client, repo, case, timeout), None
    except Exception as e:  # a server error or a timeout is a result to record, not a crash
        result, error = None, str(e)
    return (time.monotonic() - start) * 1000, result, error


# How long the diagnostics must stay unchanged to count as settled, and the most a case waits for them.
SETTLED_S = 2.0
DIAGNOSTICS_TIMEOUT_S = 60


def until_published(client, edit, target, ok, settled=False, versioned=True):
    """Ms from an edit to the first publish for the target (for the edited version, when the target is
    the edited file and the server sends versions) whose diagnostics satisfy ok; with settled, to the
    last publish before the diagnostics stay unchanged for SETTLED_S, which must satisfy ok. None past
    the timeout."""
    start = time.monotonic()
    mark = len(client.diagnostics.times)
    version = edit()
    last = None
    while time.monotonic() - start < DIAGNOSTICS_TIMEOUT_S:
        for at, msg in client.diagnostics.times[mark:]:
            params = msg.get("params", {})
            if params.get("uri") == target and (not versioned or params.get("version") in (None, version)):
                if not settled and ok(params.get("diagnostics", [])):
                    return (at - start) * 1000
                last = (at, params.get("diagnostics", []))
        mark = len(client.diagnostics.times)
        if settled and last and time.monotonic() - last[0] >= SETTLED_S:
            return (last[0] - start) * 1000 if ok(last[1]) else None
        time.sleep(0.01)
    return None


def typed_error(client, repo, case):
    """An edit that breaks the code, then its undo: ms until the error shows on the watched line, and
    ms until the diagnostics there settle without it. A typed error edits the file it watches; an error
    in another module edits the upstream file, saved, and watches the open caller."""
    if "upstream" in case:
        path, old, new = case["upstream"]
        original = (repo / path).read_text(encoding="utf-8")
        edited, save = original.replace(old, new, 1), True
        watched, call = case["downstream"]
        line = next(i for i, text in enumerate((repo / watched).read_text(encoding="utf-8").split("\n")) if call in text)
    else:
        path = watched = case["edit"][0]
        original = (repo / path).read_text(encoding="utf-8")
        edited, pos = Q.edited_text(repo, case)
        line, save = pos["line"], case.get("save", False)
    target, versioned = Q.uri(repo, watched), watched == path

    def on_line(diagnostics):
        return any(d.get("severity") == 1 and d["range"]["start"]["line"] == line for d in diagnostics)

    try:
        shown = until_published(client, lambda: Q.change(client, repo, path, edited, save), target, on_line,
                                versioned=versioned)
    finally:
        gone = until_published(client, lambda: Q.change(client, repo, path, original, save), target,
                               lambda diagnostics: not on_line(diagnostics), settled=True, versioned=versioned)
    return shown, gone


# The actions that can import a missing type, in the order a user picks them: one naming the type, else
# organizing the imports, which asks which type when the name is ambiguous, else adding every missing
# import, which picks one itself (jdtls's took java.awt.print.Pageable for Spring's on spring-petclinic).
IMPORT_ACTIONS = ("organize imports", "add all missing imports")


def import_action(actions, fqn, simple):
    titles = [(a, a.get("title", "").lower()) for a in actions]
    named = next((a for a, title in titles if "import" in title and (fqn.lower() in title or simple.lower() in title)),
                 None)
    return named or next((a for wanted in IMPORT_ACTIONS for a, title in titles if title == wanted), None)


def import_fix(client, repo, case):
    """Removes an import the file uses: ms until the error shows, then ms until a code action importing
    the type is offered and resolved, and whether its edit adds the import back."""
    path, line = case["import_removed"]
    fqn = line.split()[1].rstrip(";")
    simple = fqn.rsplit(".", 1)[1]
    original = (repo / path).read_text(encoding="utf-8")
    target = Q.uri(repo, path)
    missing = []
    client.wanted_import = fqn

    def names_it(diagnostics):
        missing[:] = [d for d in diagnostics if d.get("severity") == 1 and simple in d.get("message", "")]
        return bool(missing)

    try:
        shown = until_published(client, lambda: Q.change(client, repo, path, original.replace(line + "\n", "", 1)),
                                target, names_it)
        if shown is None:
            return None, None, False
        start = time.monotonic()
        asked = {"textDocument": {"uri": target}, "range": missing[0]["range"],
                 "context": {"diagnostics": missing[:1], "triggerKind": 1}}
        actions = client.request("textDocument/codeAction", asked, timeout=DIAGNOSTICS_TIMEOUT_S) or []
        action = import_action(actions, fqn, simple)
        if action and "edit" not in action and "command" not in action and client.resolves_actions:
            action = client.request("codeAction/resolve", action, timeout=DIAGNOSTICS_TIMEOUT_S) or action
        edit_ = (action or {}).get("edit")
        command_name = ((action or {}).get("command") or {}).get("command") if isinstance(
            (action or {}).get("command"), dict) else (action or {}).get("command")
        if command_name == "java.action.organizeImports":
            # nvim-jdtls carries it out as java/organizeImports with the code action's own parameters.
            edit_ = client.request("java/organizeImports", asked, timeout=DIAGNOSTICS_TIMEOUT_S)
        elif action and not edit_ and action.get("command"):
            # A command that applies its edit through workspace/applyEdit, as jdtls's import actions do.
            command = action["command"] if isinstance(action["command"], dict) else action
            applied = len(client.applied_edits)
            client.request("workspace/executeCommand", {"command": command["command"],
                                                        "arguments": command.get("arguments", [])},
                           timeout=DIAGNOSTICS_TIMEOUT_S)
            edit_ = client.applied_edits[-1] if len(client.applied_edits) > applied else next(
                (arg for arg in command.get("arguments", []) or []
                 if isinstance(arg, dict) and ("changes" in arg or "documentChanges" in arg)), None)
        ready = (time.monotonic() - start) * 1000 if action else None
        adds = any(fqn in e.get("newText", "") for edits in Q.text_edits(edit_).values() for e in edits)
        return shown, ready, adds
    finally:
        Q.change(client, repo, path, original)


def scenario_results(client, repo, case, reps, run, suffixes):
    """One row per timing a scenario returns, as the amendment reports them; a run is right when every
    timing is there and its last value, when it is a bool, is true."""
    runs = [run(client, repo, case) for _ in range(1 + reps)]
    right = all(r[-1] for r in runs) if isinstance(runs[0][-1], bool) else True
    rows = {}
    for index, suffix in enumerate(suffixes):
        times = [r[index] for r in runs]
        done = [t for t in times if t is not None]
        rows["%s: %s" % (case["name"], suffix)] = {
            "method": case["method"], "supported": True,
            "first_ms": times[0], "median_ms": statistics.median(done[1:] or done) if done else None,
            "p95_ms": p95(done[1:] or done) if done else None,
            "error": None if len(done) == len(times) else "not within %ss" % DIAGNOSTICS_TIMEOUT_S,
            "correct": len(done) == len(times) and right, "answer": {"names": [], "locations": [], "text": ""}}
    return rows


def p95(values):
    ordered = sorted(values)
    return ordered[max(0, int(round(0.95 * len(ordered))) - 1)]


def open_files(client, repo, cases):
    for path in Q.opened_files(cases):
        client.notify("textDocument/didOpen", {"textDocument": {
            "uri": Q.uri(repo, path), "languageId": "java", "version": 1,
            "text": (repo / path).read_text(encoding="utf-8")}})


def wait_ready(client, repo, probe, start, ready_timeout, request_timeout):
    """Polls the probe every 250 ms until its answer is right: the moment a user gets one."""
    while True:
        _ms, answer, error = timed(client, repo, probe, request_timeout)
        if answer is not None and Q.check(probe["expect"], Q.summarize(repo, probe, answer)):
            return time.monotonic()
        if client._proc.poll() is not None:
            raise RuntimeError("server exited (%s) before it was ready" % client._proc.returncode)
        if time.monotonic() - start > ready_timeout:
            raise RuntimeError("not ready after %ss; last probe: %s" % (ready_timeout, error or answer))
        time.sleep(0.25)


def thread_dumps(pids, out_dir, label):
    """jstack of each JVM among the server's processes, for a request that timed out."""
    jstack = Path(S.JDTLS_JAVA_HOME) / "bin" / "jstack"
    for pid in sorted(pids):
        dump = subprocess.run([str(jstack), str(pid)], capture_output=True, text=True)
        if dump.returncode == 0:
            (out_dir / ("%s-%d.jstack" % (label, pid))).write_text(dump.stdout, encoding="utf-8")


def run_cases(client, repo, cases, capabilities, reps, request_timeout, on_timeout, compile_check=None):
    results = {}
    for case in cases:
        if case["method"] == "textDocument/publishDiagnostics":
            results.update(scenario_results(client, repo, case, reps, typed_error, ("shows", "settles without it")))
            continue
        if "import_removed" in case:
            results.update(scenario_results(client, repo, case, reps, import_fix,
                                            ("error shows", "import action offered and resolved")))
            continue
        if not capabilities.get(Q.CAPABILITY[case["method"]]):
            results[case["name"]] = {"method": case["method"], "supported": False}
            continue
        first_ms, answer, error = timed(client, repo, case, request_timeout)
        if error and "timed out" in error:
            on_timeout()
        summary = Q.summarize(repo, case, answer)
        compiles = compile_check(answer) if compile_check and case["method"] == "textDocument/rename" and answer \
            else None
        times = [timed(client, repo, case, request_timeout)[0] for _ in range(reps)]
        results[case["name"]] = {
            "method": case["method"],
            "supported": True,
            "first_ms": first_ms,
            "median_ms": statistics.median(times),
            "p95_ms": p95(times),
            "error": error,
            "correct": error is None and Q.check(case["expect"], summary) and compiles is not False,
            "answer": summary,
            "compiles": compiles,
        }
    return results


def launch(spec, repo, name, log):
    """Starts a server as its spec says, stderr to the run's log."""
    with open(log, "ab") as err:
        proc = subprocess.Popen(spec["cmd"], cwd=repo, env=spec["env"], stdin=subprocess.PIPE,
                                stdout=subprocess.PIPE, stderr=err, start_new_session=True)
    return BenchClient(proc, spec["settings"])


def initialize(client, spec, repo, name, timeout):
    """The LSP handshake, and what the server's answer tells the cases about it."""
    init = client.request("initialize", {
        "processId": os.getpid(),
        "rootUri": repo.as_uri(),
        "rootPath": str(repo),
        "workspaceFolders": [{"uri": repo.as_uri(), "name": name}],
        "capabilities": CLIENT_CAPABILITIES,
        "initializationOptions": spec["init_options"],
    }, timeout=timeout)
    client.notify("initialized", {})
    provides = init.get("capabilities", {})
    client.resolves_completion = bool((provides.get("completionProvider") or {}).get("resolveProvider"))
    client.prepares_rename = isinstance(provides.get("renameProvider"), dict) \
        and bool(provides["renameProvider"].get("prepareProvider"))
    client.resolves_actions = isinstance(provides.get("codeActionProvider"), dict) \
        and bool(provides["codeActionProvider"].get("resolveProvider"))
    return init


# A branch switch: how long it may take to be right again, how often it is checked, and how long the
# answers must stay unchanged before the poll stops.
SWITCH_DEADLINE_S = 600
SWITCH_POLL_S = 1.0
SWITCH_STEADY_S = 60
# A fresh start's answers count once they stayed the same this long.
FRESH_STEADY_S = 15


def git(repo, *args):
    return subprocess.run(["git", *args], cwd=repo, capture_output=True, text=True, check=True).stdout


def errors_in(client, target):
    """The errors in a file's last published diagnostics; None before any publish."""
    published = [msg["params"] for _, msg in client.diagnostics.times if msg["params"].get("uri") == target]
    return sum(1 for d in published[-1]["diagnostics"] if d.get("severity") == 1) if published else None


def references_count(client, repo, case, timeout):
    _ms, answer, _error = timed(client, repo, case, timeout)
    return len(Q.summarize(repo, case, answer)["locations"]) if answer is not None else None


def finds_type(client, name, path, timeout):
    """Whether workspace symbol search finds the type declared in that file."""
    try:
        found = client.request("workspace/symbol", {"query": name}, timeout=timeout) or []
    except Exception:  # a refused or timed-out search finds nothing
        return False
    return any(s.get("name") == name and urllib.parse.unquote((s.get("location") or {}).get("uri", "")).endswith(path)
               for s in found)


TYPE_DECLARATION = re.compile(r"^\s*(?:public\s+|abstract\s+|final\s+|sealed\s+)*(class|interface|enum|record)\s+(\w+)")


def switch_checks(repo, project, ref, pin):
    """What must change when the tree goes from the pinned commit to the older one, picked by rule
    from the diff: a type added since the older commit (search must stop finding it), a type deleted
    since (search must find it), the type whose uses changed most by text (its reference count must
    match a fresh start), and a changed file of the main module (its errors must match the build:
    none). Main-module files first, then any."""
    main = project["main_module"] + "/"
    status = [line.split("\t") for line in git(repo, "diff", "--name-status", "--no-renames", ref, pin).splitlines()]
    java = [(s, p) for s, p in status if p.endswith(".java") and "/src/main/java/" in "/" + p]

    def first(kind):
        files = sorted((not p.startswith(main), p) for s, p in java if s == kind)
        return files[0][1] if files else None

    added, deleted = first("A"), first("D")
    modified = sorted((not p.startswith(main), p) for s, p in java if s == "M")
    watched = modified[0][1]

    def uses(name, rev):
        counts = git_ok(repo, "grep", "-c", "-w", name, rev, "--", "*.java")
        return sum(int(line.rsplit(":", 1)[1]) for line in counts.splitlines() if line)

    best = None
    for _main_first, path in modified[:40]:
        declared = next((m.group(2) for m in map(TYPE_DECLARATION.match, (repo / path).read_text().splitlines()) if m),
                        None)
        if declared:
            change = abs(uses(declared, ref) - uses(declared, pin))
            if best is None or change > best[0]:
                best = (change, path, declared)
    _change, used_path, used = best
    # Kind and name only: the rest of the declaration line may differ between the two commits.
    kind = next(m.group(1) for m in map(TYPE_DECLARATION.match, (repo / used_path).read_text().splitlines())
                if m and m.group(2) == used)
    return {"added": added, "deleted": deleted, "watched": watched,
            "references": {"name": "references: %s" % used, "method": "textDocument/references",
                           "at": (used_path, "%s %s" % (kind, used), used)}}


def git_ok(repo, *args):
    """git output, empty when it fails (git grep exits 1 when nothing matches)."""
    return subprocess.run(["git", *args], cwd=repo, capture_output=True, text=True).stdout


def switch_answers(client, repo, checks, timeout):
    """(added type found, deleted type found, reference count, watched file's errors)."""
    stem = lambda path: Path(path).stem if path else None
    return (finds_type(client, stem(checks["added"]), checks["added"], timeout) if checks["added"] else None,
            finds_type(client, stem(checks["deleted"]), checks["deleted"], timeout) if checks["deleted"] else None,
            references_count(client, repo, checks["references"], timeout),
            errors_in(client, Q.uri(repo, checks["watched"])))


def checkout(repo, rev, reapply):
    """Checks out a commit and re-applies the benchmark's own edits to it (the Lathe extension, a
    project's appended lines), as a user's setup survives a branch switch."""
    git(repo, "checkout", "-q", "-f", "--detach", rev)
    reapply()


def branch_switch(client, repo, project, cases, args, fresh_answers, reapply):
    """git checkout of the project's older commit while the server runs; the client reloads its open
    buffers, as Neovim does, sends no watched-file events (Neovim offers none on Linux) and answers no
    prompt. Ms until each answer of switch_checks matches what a fresh start of the same server gives
    at that commit; then the tree goes back."""
    ref, pin = project["branch_ref"], git(repo, "rev-parse", "HEAD").strip()
    checks = switch_checks(repo, project, ref, pin)
    for path in (checks["watched"], checks["references"]["at"][0]):
        client.notify("textDocument/didOpen", {"textDocument": {"uri": Q.uri(repo, path), "languageId": "java",
                                                                  "version": 1, "text": (repo / path).read_text()}})
    opened = Q.opened_files(cases.CASES) + [checks["watched"], checks["references"]["at"][0]]
    # The answers just before the switch: a check whose answer does not change tells nothing.
    before = switch_answers(client, repo, checks, args.request_timeout)
    polls = []
    start = time.monotonic()
    checkout(repo, ref, reapply)
    try:
        for path in opened:
            if (repo / path).exists():
                Q.change(client, repo, path, (repo / path).read_text(encoding="utf-8"))
            else:
                client.notify("textDocument/didClose", {"textDocument": {"uri": Q.uri(repo, path)}})
        while time.monotonic() - start < SWITCH_DEADLINE_S:
            # Stamped when the round of queries ends: the moment its answers were all there.
            answers = switch_answers(client, repo, checks, args.request_timeout)
            at = time.monotonic() - start
            polls.append((at,) + answers)
            steady = [p for p in polls if p[0] >= at - SWITCH_STEADY_S]
            if at >= SWITCH_STEADY_S and len({p[1:] for p in steady}) == 1:
                break
            time.sleep(SWITCH_POLL_S)
        expected = fresh_answers(checks)
    finally:
        checkout(repo, pin, reapply)

    def settled(index):
        """Ms from the switch to the poll after which every poll matches the fresh start; None if the
        last does not."""
        last_wrong = max((i for i, p in enumerate(polls) if p[index] != expected[index - 1]), default=-1)
        return polls[last_wrong + 1][0] * 1000 if last_wrong + 1 < len(polls) else None

    return {"ref": ref, "checks": {k: v for k, v in checks.items() if k != "references"},
            "references_at": checks["references"]["at"], "before": before, "expected": expected,
            "polls": len(polls), "changed": [b != e for b, e in zip(before, expected)],
            "added_type_ms": settled(1), "deleted_type_ms": settled(2), "references_ms": settled(3),
            "diagnostics_ms": settled(4)}


def run_server(server, name, project, cases, mode, args, out_dir, label):
    repo = S.repo_of(name)
    state = S.WORK / "state" / name / server
    log = out_dir / ("%s.log" % label)
    before = set(M.processes())
    result = {"server": server, "mode": mode, "cases": {}}
    result["build_ms"] = S.SERVERS_BY_NAME[server]["prepare"](name, project, mode, state, log)
    # What the build left running (Gradle's worker processes outlive it) is not the server's.
    M.stop_leftovers(before, str(repo))
    before = set(M.processes())
    spec = S.SERVERS_BY_NAME[server]["spec"](name, project, state, args.heap, jdtls_overrides(args))
    if server == "lathe":
        result["server_version"] = S.lathe_server_version(repo)
        if result["server_version"] != S.LATHE_VERSION:
            result["error"] = "the build wrote Lathe %s, not %s" % (result["server_version"], S.LATHE_VERSION)
            return result
    probe = next(c for c in cases.CASES if c["name"] == cases.PROBE)

    start = time.monotonic()
    client = launch(spec, repo, name, log)
    sampler = M.Sampler(client._proc.pid, before).start()
    try:
        init = initialize(client, spec, repo, name, args.ready_timeout)
        result["initialize_ms"] = (time.monotonic() - start) * 1000
        result["server_info"] = init.get("serverInfo")
        open_files(client, repo, cases.CASES)
        ready_at = wait_ready(client, repo, probe, start, args.ready_timeout, args.request_timeout)
        result["ready_ms"] = (ready_at - start) * 1000
        imported_at = client.started_at()
        result["import_ms"] = (imported_at - start) * 1000 if server == "jdtls" and imported_at else None
        result["startup_peak_mb"] = sampler.peak_mb(until=ready_at)
        if args.scenario == "branch":
            def fresh_answers(checks):
                """What a fresh start of the same server answers at the switched commit, once its answers
                stop changing (its indexes warm up after it is ready)."""
                fresh = launch(spec, repo, name, log)
                try:
                    initialize(fresh, spec, repo, name, args.ready_timeout)
                    open_files(fresh, repo, [probe, checks["references"], {"file": checks["watched"]}])
                    wait_ready(fresh, repo, probe, time.monotonic(), args.ready_timeout, args.request_timeout)
                    answers, since = None, time.monotonic()
                    while time.monotonic() - since < FRESH_STEADY_S:
                        latest = switch_answers(fresh, repo, checks, args.request_timeout)
                        if latest != answers:
                            answers, since = latest, time.monotonic()
                        time.sleep(SWITCH_POLL_S)
                    return answers
                finally:
                    fresh.stop()
            result["branch_switch"] = branch_switch(client, repo, project, cases, args, fresh_answers,
                                                    lambda: S.SERVERS_BY_NAME[server]["edits"](name, project))
            return result
        result["cases"] = run_cases(client, repo, cases.CASES, init.get("capabilities", {}), args.reps,
                                    args.request_timeout,
                                    lambda: thread_dumps(sampler.pids, out_dir, label),
                                    (lambda edit: S.compiles_with(name, project, edit)) if args.compile_renames else None)
        result["query_peak_mb"] = sampler.peak_mb(since=ready_at)
        time.sleep(5)
        sampler.sample()
        result["idle_mb"] = sampler.last_mb()
    except Exception as e:  # the run is recorded as failed with its reason; the next one still runs
        result["error"] = str(e)
    finally:
        sampler.stop()
        result["cpu_s"] = sampler.cpu_s()
        result["peak_mb"] = sampler.peak_mb()
        result["processes"] = len(sampler.pids)
        client.stop()
        table = M.processes()
        M.kill(M.descendants(table, sampler.pids))
        M.stop_started(before)
        # After the server stopped: what it keeps on disk, and anything it wrote into the tree.
        result["disk"] = S.SERVERS_BY_NAME[server]["disk"](name, state)
    return result


def jdtls_overrides(args):
    """--jdtls-setting key=value pairs: a labelled variant of jdtls's settings."""
    overrides = {}
    for pair in args.jdtls_setting:
        key, _, value = pair.partition("=")
        overrides[key] = json.loads(value) if value in ("true", "false") or value.isdigit() else value
    return overrides


def overloaded():
    return os.getloadavg()[0] > os.cpu_count()


def machine():
    cpu = platform.processor()
    memory = None
    if platform.system() == "Linux":
        cpuinfo = Path("/proc/cpuinfo").read_text()
        cpu = next((line.split(":", 1)[1].strip() for line in cpuinfo.splitlines() if line.startswith("model name")),
                   cpu)
        meminfo = Path("/proc/meminfo").read_text()
        memory = int(next(line.split()[1] for line in meminfo.splitlines() if line.startswith("MemTotal"))) // 2**20
    return {"cpu": cpu, "cores": os.cpu_count(), "memory_gb": memory,
            "os": "%s %s" % (platform.system(), platform.release())}


def environment(name, project, args):
    commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=S.repo_of(name), capture_output=True,
                            text=True).stdout.strip()
    return {
        "date": datetime.datetime.now().isoformat(timespec="seconds"),
        "project": name,
        "project_commit": commit,
        "machine": machine(),
        "load_before": os.getloadavg(),
        "heap": args.heap,
        "jdtls_settings": jdtls_overrides(args),
        "versions": S.versions(project),
        "command": " ".join([Path(sys.argv[0]).name] + sys.argv[1:]),
    }


def slept_s():
    """Seconds the machine has spent suspended since boot: the boot clock counts them, the
    monotonic clock does not."""
    return time.clock_gettime(time.CLOCK_BOOTTIME) - time.clock_gettime(time.CLOCK_MONOTONIC)


def measured_run(server, name, project, cases, mode, args, out_dir, label):
    """One run, repeated when the machine was busy before or after it, or slept during it
    (README.md, Runs)."""
    for attempt in range(1, LOAD_ATTEMPTS + 1):
        load_before = os.getloadavg()
        slept_before = slept_s()
        result = run_server(server, name, project, cases, mode, args, out_dir, label)
        result["load"] = {"before": load_before, "after": os.getloadavg(), "attempt": attempt}
        if slept_s() - slept_before > 1:
            print("  the machine slept during the run, run discarded", flush=True)
            continue
        if not (load_before[0] > os.cpu_count() or overloaded()):
            return result
        print("  load above %d cores, run discarded" % os.cpu_count(), flush=True)
    result["error"] = "no attempt ran on an idle, awake machine"
    return result


def seconds(ms):
    return "never" if ms is None else "%.1fs" % (ms / 1000)


def summary_line(result):
    switch = result.get("branch_switch")
    if switch:
        return "branch switch: added type gone %s, deleted type found %s, references %s, errors %s  %s" % (
            seconds(switch["added_type_ms"]), seconds(switch["deleted_type_ms"]), seconds(switch["references_ms"]),
            seconds(switch["diagnostics_ms"]), result.get("error") or "")
    correct = sum(1 for c in result["cases"].values() if c.get("correct"))
    supported = sum(1 for c in result["cases"].values() if c.get("supported"))
    # Rows, not cases: a typed-error case reports two.
    total = len(result["cases"])
    return "build %s  ready %s  peak %4.0f MB  correct %d/%d (%d unsupported)  %s" % (
        "%6.1fs" % (result["build_ms"] / 1000) if result.get("build_ms") else "     -",
        "%6.1fs" % (result["ready_ms"] / 1000) if result.get("ready_ms") else "     -",
        result.get("peak_mb") or 0, correct, supported, total - supported if result["cases"] else 0,
        result.get("error") or "")


# Both servers at the heap vscode-java gives jdtls: the 2026-11-11 amendment.
DEFAULT_HEAP = "2G"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("project", choices=sorted(P.PROJECTS))
    parser.add_argument("--servers", default="lathe,jdtls")
    parser.add_argument("--runs", type=int, default=3, help="cold runs and warm runs per server")
    parser.add_argument("--reps", type=int, default=10, help="timed repeats of each query")
    parser.add_argument("--heap", default=DEFAULT_HEAP,
                        help="the same -Xmx for every server (default %s, vscode-java's for jdtls)" % DEFAULT_HEAP)
    parser.add_argument("--ready-timeout", type=int, default=1800, help="seconds")
    parser.add_argument("--request-timeout", type=int, default=300, help="seconds")
    parser.add_argument("--jdtls-setting", action="append", default=[], metavar="KEY=VALUE",
                        help="a jdtls setting changed from vscode-java's defaults: a labelled variant")
    parser.add_argument("--scenario", choices=["branch"],
                        help="run an outside-change scenario after the server is ready, instead of the queries")
    parser.add_argument("--compile-renames", action="store_true",
                        help="apply each rename case's edit to a copy of the project and compile it there")
    parser.add_argument("--baseline", action="store_true",
                        help="first time the build the project costs without Lathe (once per project)")
    parser.add_argument("--prime", action="store_true",
                        help="one cold run per server, only to download dependencies; not a measurement")
    args = parser.parse_args()

    name = args.project
    project = P.PROJECTS[name]
    cases = importlib.import_module("cases.%s" % project["cases"])
    if not S.repo_of(name).is_dir():
        raise SystemExit("no clone of %s: run setup.sh first" % name)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    suffix = "-prime" if args.prime else "-heap" + args.heap if args.heap != DEFAULT_HEAP else ""
    if args.jdtls_setting:
        suffix += "-variant"
    out_dir = RESULTS / ("%s-%s%s" % (stamp, name, suffix))
    out_dir.mkdir(parents=True)
    (out_dir / "environment.json").write_text(json.dumps(environment(name, project, args), indent=2))
    if args.baseline:
        print("%-14s" % "baseline", end="", flush=True)
        before = set(M.processes())
        build_ms = S.baseline_build(name, project, out_dir / "baseline.log")
        M.stop_leftovers(before, str(S.repo_of(name)))
        (out_dir / "baseline.json").write_text(json.dumps(
            {"build_ms": build_ms, "command": " ".join(S.baseline_command(project, S.repo_of(name)))}, indent=2))
        print("build %6.1fs" % (build_ms / 1000), flush=True)
    for server in args.servers.split(","):
        for i in range(1 if args.prime else args.runs):
            for mode in ("cold",) if args.prime else ("cold", "warm"):
                label = "%s-%s-%d" % (server, mode, i + 1)
                print("%-14s" % label, end="", flush=True)
                result = measured_run(server, name, project, cases, mode, args, out_dir, label)
                (out_dir / ("%s.json" % label)).write_text(json.dumps(result, indent=2))
                print(summary_line(result), flush=True)
    print("results in", out_dir)
    subprocess.run([sys.executable, str(BENCH / "report.py"), str(out_dir)], check=True)


if __name__ == "__main__":
    main()
