#!/usr/bin/env python3
"""Aggregate results/ into a paired per-task, per-arm table with medians across repeats.

Reads results/<task>/<arm>/<repeat>/{metrics.json, score.json}. With R>1 repeats it reports the
median per cell; the headline deltas (treatment - baseline) are shown for tokens, cost, turns, and
missed-sites. At R=1 these are single observations — directional only, no CI (see README scope note).

Usage: report.py [results_dir]
"""

import json
import statistics
import sys
from collections import defaultdict
from pathlib import Path

BENCH = Path(__file__).resolve().parent
ARMS = ["baseline", "treatment-natural", "treatment-primed"]
COLS = [("resolved", "resolved"), ("missed", "missed_sites"),
        ("out_tok", "output_tokens"), ("cost$", "total_cost_usd"),
        ("turns", "num_turns"), ("lathe", "lathe_tool_calls"),
        ("grep/rd", "grep_read_bash_calls"), ("wall_s", "wall_s")]


def cell(run_dir: Path) -> dict:
    m = json.loads((run_dir / "metrics.json").read_text()) if (run_dir / "metrics.json").exists() else {}
    s = json.loads((run_dir / "score.json").read_text()) if (run_dir / "score.json").exists() else {}
    m["resolved"] = 1 if s.get("resolved") else 0
    m["missed_sites"] = len(s.get("missed_sites", [])) if "missed_sites" in s else None
    return m


def median(vals):
    vals = [v for v in vals if isinstance(v, (int, float))]
    return round(statistics.median(vals), 2) if vals else None


def report_cell_group(task: str, model: str, model_dir: Path):
    agg = defaultdict(lambda: defaultdict(list))
    for arm in ARMS:
        adir = model_dir / arm
        if not adir.exists():
            continue
        for rep in sorted(adir.iterdir()):
            if not rep.is_dir():
                continue
            c = cell(rep)
            for _, key in COLS:
                agg[arm][key].append(c.get(key))
    if not agg:
        return
    print("\n== %s  [model: %s] ==" % (task, model))
    print("  %-18s" % "arm" + "".join("%9s" % h for h, _ in COLS) + "   R")
    base = {key: median(agg["baseline"][key]) for _, key in COLS}
    for arm in ARMS:
        if arm not in agg:
            continue
        row = {key: median(agg[arm][key]) for _, key in COLS}
        r = max(len(v) for v in agg[arm].values())
        print("  %-18s" % arm + "".join("%9s" % ("-" if row[key] is None else row[key])
                                         for _, key in COLS) + "%4d" % r)
    if "treatment-primed" in agg and base.get("total_cost_usd"):
        tp = {key: median(agg["treatment-primed"][key]) for _, key in COLS}
        for label, key in [("cost", "total_cost_usd"), ("turns", "num_turns"),
                           ("grep/read", "grep_read_bash_calls"), ("missed", "missed_sites")]:
            b, t = base.get(key), tp.get(key)
            if b and t:
                print("   Δ %-9s primed/baseline = %.2fx" % (label, t / b))


def main(argv):
    results = Path(argv[1]) if len(argv) > 1 else BENCH / "results"
    for task in sorted(p.name for p in results.iterdir() if p.is_dir()):
        for model_dir in sorted((results / task).iterdir()):
            if model_dir.is_dir() and not model_dir.name.startswith("_"):
                report_cell_group(task, model_dir.name, model_dir)


if __name__ == "__main__":
    main(sys.argv)
