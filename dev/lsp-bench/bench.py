#!/usr/bin/env python3
"""Runs the pre-registered queries (README.md) against Lathe and jdtls on one project and records
readiness, build or import time, memory, CPU, latency and correctness. Run ./setup.sh first.

    python3 dev/lsp-bench/bench.py spring-petclinic                  # 3 cold + 3 warm runs per server
    python3 dev/lsp-bench/bench.py spring-petclinic --runs 1 --servers lathe
    python3 dev/lsp-bench/bench.py spring-petclinic --heap 2G        # the equal-heap run
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
import statistics
import subprocess
import sys
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
    "workspace": {"configuration": True, "workspaceFolders": True, "symbol": {}},
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
        self.status = StampedQueue()
        with self._notif_lock:
            self._notifications["language/status"] = self.status

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
        self._send({"jsonrpc": "2.0", "id": msg["id"], "result": result})

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


def run_cases(client, repo, cases, capabilities, reps, request_timeout):
    results = {}
    for case in cases:
        if not capabilities.get(Q.CAPABILITY[case["method"]]):
            results[case["name"]] = {"method": case["method"], "supported": False}
            continue
        first_ms, answer, error = timed(client, repo, case, request_timeout)
        summary = Q.summarize(repo, case, answer)
        times = [timed(client, repo, case, request_timeout)[0] for _ in range(reps)]
        results[case["name"]] = {
            "method": case["method"],
            "supported": True,
            "first_ms": first_ms,
            "median_ms": statistics.median(times),
            "p95_ms": p95(times),
            "error": error,
            "correct": error is None and Q.check(case["expect"], summary),
            "answer": summary,
        }
    return results


def run_server(server, name, project, cases, mode, args, out_dir, label):
    repo = S.repo_of(name)
    state = S.WORK / "state" / name / server
    log = out_dir / ("%s.log" % label)
    before = set(M.processes())
    result = {"server": server, "mode": mode, "cases": {}}
    result["build_ms"] = S.SERVERS_BY_NAME[server]["prepare"](name, project, mode, state, log)
    spec = S.SERVERS_BY_NAME[server]["spec"](name, project, state, args.heap)
    if server == "lathe":
        result["server_version"] = S.lathe_server_version(repo)
        if result["server_version"] != S.LATHE_VERSION:
            result["error"] = "the build wrote Lathe %s, not %s" % (result["server_version"], S.LATHE_VERSION)
            return result
    probe = next(c for c in cases.CASES if c["name"] == cases.PROBE)

    start = time.monotonic()
    with open(log, "ab") as err:
        proc = subprocess.Popen(spec["cmd"], cwd=repo, env=spec["env"], stdin=subprocess.PIPE,
                                stdout=subprocess.PIPE, stderr=err, start_new_session=True)
    client = BenchClient(proc, spec["settings"])
    sampler = M.Sampler(proc.pid, before).start()
    try:
        init = client.request("initialize", {
            "processId": os.getpid(),
            "rootUri": repo.as_uri(),
            "rootPath": str(repo),
            "workspaceFolders": [{"uri": repo.as_uri(), "name": name}],
            "capabilities": CLIENT_CAPABILITIES,
            "initializationOptions": spec["init_options"],
        }, timeout=args.ready_timeout)
        client.notify("initialized", {})
        result["initialize_ms"] = (time.monotonic() - start) * 1000
        result["server_info"] = init.get("serverInfo")
        open_files(client, repo, cases.CASES)
        ready_at = wait_ready(client, repo, probe, start, args.ready_timeout, args.request_timeout)
        result["ready_ms"] = (ready_at - start) * 1000
        imported_at = client.started_at()
        result["import_ms"] = (imported_at - start) * 1000 if server == "jdtls" and imported_at else None
        result["startup_peak_mb"] = sampler.peak_mb(until=ready_at)
        result["cases"] = run_cases(client, repo, cases.CASES, init.get("capabilities", {}), args.reps,
                                    args.request_timeout)
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
    return result


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
        "versions": S.versions(project),
        "command": " ".join([Path(sys.argv[0]).name] + sys.argv[1:]),
    }


def measured_run(server, name, project, cases, mode, args, out_dir, label):
    """One run, repeated when the machine was busy before or after it (README.md, Runs)."""
    for attempt in range(1, LOAD_ATTEMPTS + 1):
        load_before = os.getloadavg()
        result = run_server(server, name, project, cases, mode, args, out_dir, label)
        result["load"] = {"before": load_before, "after": os.getloadavg(), "attempt": attempt}
        if not (load_before[0] > os.cpu_count() or overloaded()):
            return result
        print("  load above %d cores, run discarded" % os.cpu_count(), flush=True)
    result["error"] = "load above the core count on every attempt"
    return result


def summary_line(result, total):
    correct = sum(1 for c in result["cases"].values() if c.get("correct"))
    supported = sum(1 for c in result["cases"].values() if c.get("supported"))
    return "build %s  ready %s  peak %4.0f MB  correct %d/%d (%d unsupported)  %s" % (
        "%6.1fs" % (result["build_ms"] / 1000) if result.get("build_ms") else "     -",
        "%6.1fs" % (result["ready_ms"] / 1000) if result.get("ready_ms") else "     -",
        result.get("peak_mb") or 0, correct, supported, total - supported if result["cases"] else 0,
        result.get("error") or "")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("project", choices=sorted(P.PROJECTS))
    parser.add_argument("--servers", default="lathe,jdtls")
    parser.add_argument("--runs", type=int, default=3, help="cold runs and warm runs per server")
    parser.add_argument("--reps", type=int, default=10, help="timed repeats of each query")
    parser.add_argument("--heap", help="the same -Xmx for every server, e.g. 2G")
    parser.add_argument("--ready-timeout", type=int, default=1800, help="seconds")
    parser.add_argument("--request-timeout", type=int, default=300, help="seconds")
    parser.add_argument("--prime", action="store_true",
                        help="one cold run per server, only to download dependencies; not a measurement")
    args = parser.parse_args()

    name = args.project
    project = P.PROJECTS[name]
    cases = importlib.import_module("cases.%s" % project["cases"])
    if not S.repo_of(name).is_dir():
        raise SystemExit("no clone of %s: run setup.sh first" % name)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    suffix = "-prime" if args.prime else "-heap" + args.heap if args.heap else ""
    out_dir = RESULTS / ("%s-%s%s" % (stamp, name, suffix))
    out_dir.mkdir(parents=True)
    (out_dir / "environment.json").write_text(json.dumps(environment(name, project, args), indent=2))
    for server in args.servers.split(","):
        for i in range(1 if args.prime else args.runs):
            for mode in ("cold",) if args.prime else ("cold", "warm"):
                label = "%s-%s-%d" % (server, mode, i + 1)
                print("%-14s" % label, end="", flush=True)
                result = measured_run(server, name, project, cases, mode, args, out_dir, label)
                (out_dir / ("%s.json" % label)).write_text(json.dumps(result, indent=2))
                print(summary_line(result, len(cases.CASES)), flush=True)
    print("results in", out_dir)
    subprocess.run([sys.executable, str(BENCH / "report.py"), str(out_dir)], check=True)


if __name__ == "__main__":
    main()
