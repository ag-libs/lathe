"""Resource sampling of a server's process tree: summed resident set and CPU time, from `ps`.

A server's processes are its own descendants plus any process started during the run whose command
matches a pattern: a build daemon (Gradle's, Kotlin's) is detached from the process that starts it.
"""
import os
import re
import signal
import subprocess
import threading
import time

DAEMONS = re.compile(r"GradleDaemon|KotlinCompileDaemon|GradleWorkerMain")


def processes():
    """pid -> (ppid, rss_kb, cpu_seconds, command) for every process."""
    out = subprocess.run(["ps", "-axo", "pid=,ppid=,rss=,time=,command="],
                         capture_output=True, text=True).stdout
    table = {}
    for line in out.splitlines():
        parts = line.split(None, 4)
        if len(parts) == 5:
            pid, ppid, rss, cpu, cmd = parts
            table[int(pid)] = (int(ppid), int(rss), cpu_seconds(cpu), cmd)
    return table


def cpu_seconds(text):
    """ps's [[dd-]hh:]mm:ss[.cc] as seconds."""
    days, _, clock = text.rpartition("-")
    seconds = 0.0
    for part in clock.split(":"):
        seconds = seconds * 60 + float(part)
    return int(days or 0) * 86400 + seconds


def descendants(table, roots):
    found = {r for r in roots if r in table}
    grew = True
    while grew:
        grew = False
        for pid, (ppid, *_rest) in table.items():
            if ppid in found and pid not in found:
                found.add(pid)
                grew = True
    return found


def started_daemons(table, before):
    return {pid for pid, (_p, _r, _c, cmd) in table.items() if pid not in before and DAEMONS.search(cmd)}


def kill(pids):
    for pid in pids:
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass


def stop_started(before):
    """Stop every daemon started since `before` (a set of pids), and its children; never one that
    was already running."""
    table = processes()
    kill(descendants(table, started_daemons(table, before)))


class Sampler:
    """Samples a server's processes every `interval` seconds on a thread of its own."""

    def __init__(self, root, before, interval=0.25):
        self.root = root
        self.before = before
        self.interval = interval
        self.samples = []  # (monotonic time, rss_kb)
        self.cpu_by_pid = {}
        self.pids = set()
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self):
        self._thread.start()
        return self

    def sample(self):
        table = processes()
        pids = descendants(table, {self.root} | started_daemons(table, self.before))
        self.pids |= pids
        for pid in pids:
            self.cpu_by_pid[pid] = table[pid][2]
        self.samples.append((time.monotonic(), sum(table[p][1] for p in pids)))

    def _run(self):
        while not self._stop.is_set():
            self.sample()
            self._stop.wait(self.interval)

    def stop(self):
        self._stop.set()
        self._thread.join()

    def peak_mb(self, since=None, until=None):
        values = [rss for t, rss in self.samples
                  if (since is None or t >= since) and (until is None or t <= until)]
        return max(values) / 1024 if values else None

    def last_mb(self):
        return self.samples[-1][1] / 1024 if self.samples else None

    def cpu_s(self):
        """User and system time of every process seen, as ps last reported it."""
        return sum(self.cpu_by_pid.values())
