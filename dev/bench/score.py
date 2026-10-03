#!/usr/bin/env python3
"""Hermetically score ONE prediction against a task's oracle. Lathe is NEVER in this path.

Applies the agent's prediction.patch + the hidden test.patch to a clean worktree, runs the repo's own
tests via mvn (Corretto 25), and records resolved? + FAIL_TO_PASS / PASS_TO_PASS status + a file-level
missed/extra-sites proxy vs gold.patch.

Usage:
    score.py <task_dir> <results_run_dir>      # e.g. results/<id>/treatment/1
Writes <results_run_dir>/score.json.
"""

import json
import os
import re
import subprocess
import sys
from pathlib import Path

import yaml

BENCH = Path(__file__).resolve().parent


def _find_repos_dir(start: Path) -> Path:
    p = start
    for _ in range(10):
        if (p / "dropwizard").exists() or (p / "helidon").exists():
            return p
        p = p.parent
    raise RuntimeError("could not locate repos dir (with dropwizard/helidon) above %s" % start)


REPOS_DIR = Path(os.environ.get("BENCH_REPOS_DIR") or _find_repos_dir(BENCH))
JAVA_HOME = "/opt/amazon-corretto-25.0.0.36.2-linux-x64"
SKIPS = ["-Dspotless.check.skip=true", "-Denforcer.skip=true", "-Dpgpverify.skip=true"]


def env_java() -> dict:
    import os
    env = dict(os.environ)
    env["JAVA_HOME"] = JAVA_HOME
    env["PATH"] = "%s/bin:%s" % (JAVA_HOME, env["PATH"])
    return env


def files_in_patch(patch_text: str) -> set:
    return set(re.findall(r"^\+\+\+ b/(.+)$", patch_text, re.MULTILINE))


def git(args, cwd, check=True):
    return subprocess.run(["git", "-C", str(cwd), *args], capture_output=True, text=True,
                          check=check)


def apply_patch(worktree: Path, patch: Path) -> bool:
    if not patch.exists() or not patch.read_text().strip():
        return False
    r = git(["apply", "--whitespace=nowarn", str(patch)], worktree, check=False)
    return r.returncode == 0


def surefire_class_ok(worktree: Path, fqcn: str) -> str:
    """Return 'pass' / 'fail' / 'absent' for one test class from its surefire report."""
    hits = list(worktree.glob("*/target/surefire-reports/%s.txt" % fqcn))
    if not hits:
        return "absent"
    text = hits[0].read_text()
    m = re.search(r"Failures: (\d+), Errors: (\d+)", text)
    if not m:
        return "absent"
    return "pass" if (m.group(1) == "0" and m.group(2) == "0") else "fail"


def main(argv):
    task_dir = Path(argv[1]).resolve()
    run_dir = Path(argv[2]).resolve()
    task = yaml.safe_load((task_dir / "task.yaml").read_text())
    repo = (REPOS_DIR / task["repo"]).resolve()
    oracle = task["oracle"]

    base_sha = json.loads((run_dir / "metrics.json").read_text()).get("base_sha",
                                                                       task.get("base_ref", "HEAD"))
    worktree = Path("/tmp/bench-score-%s" % run_dir.as_posix().replace("/", "_"))
    git(["worktree", "remove", "--force", str(worktree)], repo, check=False)
    git(["worktree", "add", "-f", str(worktree), base_sha], repo)

    score = {"task": task["id"], "run": str(run_dir.relative_to(BENCH))}
    try:
        pred = run_dir / "prediction.patch"
        score["prediction_files"] = sorted(files_in_patch(pred.read_text() if pred.exists() else ""))
        gold_files = sorted(files_in_patch((task_dir / "gold.patch").read_text()))
        score["gold_files"] = gold_files
        score["missed_sites"] = sorted(set(gold_files) - set(score["prediction_files"]))
        score["extra_sites"] = sorted(set(score["prediction_files"]) - set(gold_files))

        score["prediction_applies"] = apply_patch(worktree, pred)
        if not score["prediction_applies"]:
            score.update(resolved=False, reason="prediction.patch did not apply")
            return _write(run_dir, score)

        if not apply_patch(worktree, task_dir / "test.patch"):
            score.update(resolved=False, reason="test.patch (oracle) did not apply")
            return _write(run_dir, score)

        mvn = oracle["mvn_test"].split()
        r = subprocess.run(mvn + SKIPS, cwd=str(worktree), env=env_java(),
                           capture_output=True, text=True)
        score["compiles"] = "BUILD FAILURE" not in r.stdout or "Tests run" in r.stdout
        f2p = {c: surefire_class_ok(worktree, c) for c in oracle.get("fail_to_pass", [])}
        p2p = {c: surefire_class_ok(worktree, c) for c in oracle.get("pass_to_pass", [])}
        score["fail_to_pass"] = f2p
        score["pass_to_pass"] = p2p
        f2p_green = all(v == "pass" for v in f2p.values()) and len(f2p) > 0
        p2p_green = all(v == "pass" for v in p2p.values())
        score["resolved"] = bool(score["compiles"] and f2p_green and p2p_green)
        score["reason"] = "ok" if score["resolved"] else "oracle not fully green"
    finally:
        git(["worktree", "remove", "--force", str(worktree)], repo, check=False)
    return _write(run_dir, score)


def _write(run_dir: Path, score: dict):
    (run_dir / "score.json").write_text(json.dumps(score, indent=2))
    print(json.dumps({k: score.get(k) for k in
                      ("task", "resolved", "reason", "missed_sites", "extra_sites",
                       "fail_to_pass", "pass_to_pass")}, indent=2))


if __name__ == "__main__":
    main(sys.argv)
