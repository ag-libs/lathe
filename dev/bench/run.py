#!/usr/bin/env python3
"""Run ONE (task x arm x repeat) of the MCP-value ablation and capture a prediction + metrics.

Single-variable ablation: baseline and treatment differ ONLY by whether the Lathe MCP server is
registered (see dev/bench/README.md). The agent never sees the oracle (test.patch); scoring is a
separate hermetic step (score.py).

Usage:
    run.py <task_dir> <baseline|treatment> --repeat N [--model opus] [--budget 5]

Writes results/<task_id>/<arm>/<repeat>/{prediction.patch, metrics.json, transcript.jsonl}.
Does NOT score — that is score.py.
"""

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

BENCH = Path(__file__).resolve().parent


def _find_repos_dir(start: Path) -> Path:
    # Walk up until we find the dir holding the target repos (robust to running from a git worktree).
    p = start
    for _ in range(10):
        if (p / "dropwizard").exists() or (p / "helidon").exists():
            return p
        p = p.parent
    raise RuntimeError("could not locate repos dir (with dropwizard/helidon) above %s" % start)


REPOS_DIR = Path(os.environ.get("BENCH_REPOS_DIR") or _find_repos_dir(BENCH))
JAVA_HOME = "/opt/amazon-corretto-25.0.0.36.2-linux-x64"
MCP_LAUNCHER = Path.home() / ".cache/lathe/servers/0.1.0-SNAPSHOT/lathe-mcp-launcher.sh"
CAPTURE_SKIPS = ["-Dspotless.check.skip=true", "-Denforcer.skip=true", "-Dpgpverify.skip=true"]


def load_task(task_dir: Path) -> dict:
    import_yaml = _load_yaml(task_dir / "task.yaml")
    return import_yaml


def _load_yaml(path: Path) -> dict:
    # Minimal dependency-free YAML: we control the manifest shape, so a tiny parser suffices for the
    # fields run.py needs (id, repo, base_ref, problem_statement).
    try:
        import yaml  # type: ignore
        return yaml.safe_load(path.read_text())
    except ModuleNotFoundError:
        return _tiny_yaml(path.read_text())


def _tiny_yaml(text: str) -> dict:
    """Handles only top-level `key: value` and `key: |` block scalars — enough for run.py."""
    out: dict = {}
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        if not line.strip() or line.lstrip().startswith("#") or line[0] in " \t":
            i += 1
            continue
        if ":" not in line:
            i += 1
            continue
        key, _, rest = line.partition(":")
        key = key.strip()
        rest = rest.strip()
        if rest == "|":
            block = []
            i += 1
            while i < len(lines) and (not lines[i].strip() or lines[i].startswith("  ")):
                block.append(lines[i][2:] if lines[i].startswith("  ") else lines[i])
                i += 1
            out[key] = "\n".join(block).strip() + "\n"
            continue
        out[key] = rest
        i += 1
    return out


def env_with_java() -> dict:
    env = dict(os.environ)
    env["JAVA_HOME"] = JAVA_HOME
    env["PATH"] = "%s/bin:%s" % (JAVA_HOME, env["PATH"])
    return env


def add_worktree(repo: Path, base_ref: str, dest: Path) -> str:
    subprocess.run(["git", "-C", str(repo), "worktree", "add", "-f", str(dest), base_ref],
                   check=True, capture_output=True)
    sha = subprocess.run(["git", "-C", str(dest), "rev-parse", "HEAD"],
                         check=True, capture_output=True, text=True).stdout.strip()
    return sha


def remove_worktree(repo: Path, dest: Path):
    subprocess.run(["git", "-C", str(repo), "worktree", "remove", "--force", str(dest)],
                   capture_output=True)


def provision_lathe(repo: Path, worktree: Path) -> None:
    """Treatment-only: wire the (untracked) extension, then full-reactor capture. See NOTES.md."""
    ext = repo / ".mvn" / "extensions.xml"
    if not ext.exists():
        raise RuntimeError("no %s — cannot provision .lathe for treatment" % ext)
    (worktree / ".mvn").mkdir(exist_ok=True)
    (worktree / ".mvn" / "extensions.xml").write_text(ext.read_text())
    # `clean` is REQUIRED: without it an incremental build skips compilation, so the lathe compiler's
    # class-copy never fires and the mirror's symbol index is empty (search_symbols returns nothing).
    subprocess.run(["mvn", "-q", "clean", "process-test-classes", *CAPTURE_SKIPS],
                   cwd=str(worktree), env=env_with_java(), check=True)
    if not (worktree / ".lathe" / "workspace.json").exists():
        raise RuntimeError("capture produced no .lathe/workspace.json")


def mcp_config_arg(worktree: Path) -> list:
    cfg = {"mcpServers": {"lathe": {"command": str(MCP_LAUNCHER)}}}
    (worktree / ".mcp-bench.json").write_text(json.dumps(cfg))
    return ["--mcp-config", str(worktree / ".mcp-bench.json")]


PRIME = (
    "A Lathe MCP server is available, providing javac-accurate code intelligence over this Maven "
    "reactor: find_references, find_implementations, get_definition, call_hierarchy, search_symbols, "
    "describe_symbol, rename_symbol, get_diagnostics. Prefer these MCP tools over grep/text search "
    "for locating symbols, references, implementations and overload-correct call sites, and for "
    "renames — they are overload- and type-aware where text search is not."
)


def is_treatment(arm: str) -> bool:
    return arm.startswith("treatment")


def run_agent(worktree: Path, prompt: str, arm: str, model: str, budget: float) -> list:
    # acceptEdits + an explicit allow-list: the user's settings disable bypassPermissions mode, so we
    # whitelist tools instead. The allow-list is IDENTICAL across arms; treatment arms only add the
    # MCP server (and, for -primed, a system-prompt nudge), preserving the single-variable ablation.
    allowed = ["Bash", "Edit", "Write", "Read", "Grep", "Glob"]
    if is_treatment(arm):
        allowed.append("mcp__lathe")
    cmd = ["claude", "-p", prompt,
           "--output-format", "stream-json", "--verbose",
           "--permission-mode", "acceptEdits",
           "--model", model, "--strict-mcp-config",
           "--max-budget-usd", str(budget),
           "--allowedTools", *allowed]
    if arm == "treatment-primed":
        cmd += ["--append-system-prompt", PRIME]
    if is_treatment(arm):
        cmd += mcp_config_arg(worktree)
    proc = subprocess.run(cmd, cwd=str(worktree), env=env_with_java(),
                          capture_output=True, text=True)
    events = []
    for line in proc.stdout.splitlines():
        line = line.strip()
        if line:
            try:
                events.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    if not events:
        sys.stderr.write(proc.stderr[-2000:])
    return events


def summarize(events: list) -> dict:
    tool_calls: dict = {}
    result = {}
    for ev in events:
        if ev.get("type") == "assistant":
            for block in ev.get("message", {}).get("content", []):
                if block.get("type") == "tool_use":
                    name = block.get("name", "?")
                    tool_calls[name] = tool_calls.get(name, 0) + 1
        elif ev.get("type") == "result":
            result = ev
    usage = result.get("usage", {})
    lathe = sum(v for k, v in tool_calls.items() if k.startswith("mcp__lathe"))
    grep_read = sum(tool_calls.get(k, 0) for k in ("Grep", "Read", "Glob", "Bash"))
    return {
        "input_tokens": usage.get("input_tokens", 0),
        "output_tokens": usage.get("output_tokens", 0),
        "cache_read_input_tokens": usage.get("cache_read_input_tokens", 0),
        "cache_creation_input_tokens": usage.get("cache_creation_input_tokens", 0),
        "total_cost_usd": result.get("total_cost_usd"),
        "num_turns": result.get("num_turns"),
        "duration_ms": result.get("duration_ms"),
        "is_error": result.get("is_error"),
        "tool_calls": tool_calls,
        "lathe_tool_calls": lathe,
        "grep_read_bash_calls": grep_read,
    }


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("task_dir")
    ap.add_argument("arm", choices=["baseline", "treatment-natural", "treatment-primed"])
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--model", default="opus")
    ap.add_argument("--budget", type=float, default=5.0)
    args = ap.parse_args(argv[1:])

    task_dir = Path(args.task_dir).resolve()
    task = load_task(task_dir)
    repo = (REPOS_DIR / task["repo"]).resolve()
    out = BENCH / "results" / task["id"] / args.arm / str(args.repeat)
    out.mkdir(parents=True, exist_ok=True)
    worktree = Path("/tmp/bench-wt-%s-%s-%d" % (task["id"], args.arm, args.repeat))
    remove_worktree(repo, worktree)

    meta = {"task": task["id"], "arm": args.arm, "repeat": args.repeat, "model": args.model}
    try:
        meta["base_sha"] = add_worktree(repo, task.get("base_ref", "HEAD"), worktree)
        if is_treatment(args.arm):
            provision_lathe(repo, worktree)
        t0 = time.monotonic()
        events = run_agent(worktree, task["problem_statement"], args.arm, args.model, args.budget)
        meta["wall_s"] = round(time.monotonic() - t0, 1)
        meta.update(summarize(events))
        diff = subprocess.run(["git", "-C", str(worktree), "diff"],
                              capture_output=True, text=True).stdout
        (out / "prediction.patch").write_text(diff)
        (out / "transcript.jsonl").write_text("\n".join(json.dumps(e) for e in events))
    finally:
        remove_worktree(repo, worktree)
    (out / "metrics.json").write_text(json.dumps(meta, indent=2))
    print(json.dumps({k: meta.get(k) for k in
                      ("task", "arm", "repeat", "wall_s", "output_tokens",
                       "total_cost_usd", "num_turns", "lathe_tool_calls",
                       "grep_read_bash_calls", "is_error")}, indent=2))


if __name__ == "__main__":
    main(sys.argv)
