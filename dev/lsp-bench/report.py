#!/usr/bin/env python3
"""Writes report.md for one results directory of bench.py: medians per server, latency and
correctness per query, and every set-valued answer compared between the servers.

    python3 dev/lsp-bench/report.py dev/lsp-bench/results/<run>
"""
import json
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import queries as Q  # noqa: E402

SET_METHODS = ("textDocument/references", "textDocument/implementation", "textDocument/documentHighlight",
               "typeHierarchy/supertypes", "typeHierarchy/subtypes", "callHierarchy/incomingCalls",
               "callHierarchy/outgoingCalls")
EXAMPLES = 10


def load(out_dir):
    runs = {}
    for path in sorted(out_dir.glob("*.json")):
        if path.name in ("environment.json", "baseline.json"):
            continue
        result = json.loads(path.read_text())
        runs.setdefault(result["server"], []).append(result)
    return runs


def median(values):
    present = [v for v in values if v is not None]
    return statistics.median(present) if present else None


def fmt(value, unit="", scale=1.0, digits=0):
    if value is None:
        return "-"
    return "%.*f%s" % (digits, value / scale, unit)


def first_answer_ms(result):
    """What a user waits for a first answer: the run's build (Lathe's; jdtls's install where it needs
    one) and the server's ready time, which holds jdtls's import."""
    if result.get("ready_ms") is None:
        return None
    return (result.get("build_ms") or 0) + result["ready_ms"]


def disk_lines(runs):
    """Each server's install, and what it wrote into the project's tree, from its last run."""
    lines = []
    for server, results in runs.items():
        disk = next((r["disk"] for r in reversed(results) if r.get("disk")), None)
        if disk:
            tree = disk["tree"]
            lines.append("- %s: install %s; in the project tree: %s" % (
                server, fmt(disk["install_mb"], " MB"),
                ", ".join("`%s`" % p for p in tree[:8]) + (" and %d more" % (len(tree) - 8) if len(tree) > 8 else "")
                if tree else "nothing"))
    return ["", "Disk is what a server keeps for the project after a run (jdtls's workspace data and "
                "configuration, Lathe's `.lathe/`). Beside it:", ""] + lines if lines else []


def build_change_lines(runs):
    """A build-file change's times, per run, the user's step in them."""
    def took(ms):
        return "never" if ms is None else fmt(ms, " s", 1000, 1)

    rows, change = [], None
    for server, results in runs.items():
        for r in results:
            c = r.get("build_change")
            if c:
                change = c
                step = ("Sync on the prompt, script %s" % took(c.get("step_ms")) if server == "lathe"
                        else ":JdtUpdateConfig (no prompt shown)")
                if c.get("step_exit"):
                    step += ", exit %d" % c["step_exit"]
                rows.append("| %s | %s | %s | %s | %s |" % (server, r["mode"], took(c.get("prompt_ms")) if server == "lathe" else "-",
                                                           step, took(c.get("fixed_ms"))))
    if not rows:
        return []
    return ["", "## Build-file change (from the change until the file using the new dependency has no error)", "",
            "A dependency added to `%s` and one of its classes used in `%s`." % (change["build_file"], change["file"]),
            "", "| Server | Mode | Prompt | User's step | No error |", "|---|---|---|---|---|"] + rows


def branch_lines(runs):
    """A branch switch's times, per run: until each answer matches a fresh start at that commit."""
    def took(ms):
        return "never" if ms is None else fmt(ms, " s", 1000, 1)

    rows = []
    for server, results in runs.items():
        for r in results:
            s = r.get("branch_switch")
            if s:
                cells = [took(s[k]) if s.get("changed", [True] * 4)[i] else "unchanged"
                         for i, k in enumerate(("added_type_ms", "deleted_type_ms", "references_ms", "diagnostics_ms"))]
                cells.append(took(s.get("back_ms")))
                rows.append("| %s | %s | %s |" % (server, r["mode"], " | ".join(cells)))
    if not rows:
        return []
    s = next(r["branch_switch"] for results in runs.values() for r in results if r.get("branch_switch"))
    return ["", "## Branch switch to %s (from the checkout until each answer matches a fresh start there)" % s["ref"],
            "", "Added type `%s`, deleted type `%s`, references to `%s`, errors in `%s`." % (
                s["checks"]["added"], s["checks"]["deleted"], s["references_at"][2], s["checks"]["watched"]), "",
            "A check marked unchanged answered the same before the switch as a fresh start does after it, so it "
            "tells nothing. Back is from the checkout of the first commit until every answer is again what it "
            "was before the switch; the server stops after it, so its saved state is the warm run's start.", "",
            "| Server | Mode | Added type gone | Deleted type found | References | Changed file's errors | Back |",
            "|---|---|---|---|---|---|---|"] + rows


def baseline_lines(out_dir, runs):
    """The build without Lathe, and Lathe's share of its cold build beside it."""
    path = out_dir / "baseline.json"
    if not path.exists():
        return []
    baseline = json.loads(path.read_text())
    lathe = median([r.get("build_ms") for r in runs.get("lathe", []) if r["mode"] == "cold" and not r.get("error")])
    share = "; Lathe's cold build %s, so its share %s" % (
        fmt(lathe, " s", 1000, 1), fmt(lathe - baseline["build_ms"], " s", 1000, 1)) if lathe else ""
    return ["", "Build without Lathe (`%s`, fresh checkout): %s%s." % (
        baseline["command"], fmt(baseline["build_ms"], " s", 1000, 1), share)]


def server_table(runs):
    rows = ["| Server | Mode | Runs | Build | Import | Ready | First answer | Startup peak | Query peak | Idle | CPU | Disk | Failed |",
            "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for server, results in runs.items():
        for mode in ("cold", "warm"):
            done = [r for r in results if r["mode"] == mode]
            ok = [r for r in done if not r.get("error")]
            rows.append("| %s | %s | %d | %s | %s | %s | %s | %s | %s | %s | %s | %s | %d |" % (
                server, mode, len(done),
                fmt(median([r.get("build_ms") for r in ok]), " s", 1000, 1),
                fmt(median([r.get("import_ms") for r in ok]), " s", 1000, 1),
                fmt(median([r.get("ready_ms") for r in ok]), " s", 1000, 1),
                fmt(median([first_answer_ms(r) for r in ok]), " s", 1000, 1),
                fmt(median([r.get("startup_peak_mb") for r in ok]), " MB"),
                fmt(median([r.get("query_peak_mb") for r in ok]), " MB"),
                fmt(median([r.get("idle_mb") for r in ok]), " MB"),
                fmt(median([r.get("cpu_s") for r in ok]), " s", 1, 1),
                fmt(median([(r.get("disk") or {}).get("state_mb") for r in ok]), " MB"),
                len(done) - len(ok)))
    return rows


def case_names(runs):
    names = []
    for results in runs.values():
        for result in results:
            names += [n for n in result["cases"] if n not in names]
    return names


def query_table(runs):
    servers = list(runs)
    header = "| Query | " + " | ".join("%s first / median / p95 | %s correct" % (s, s) for s in servers) + " |"
    rows = [header, "|---|" + "---|---|" * len(servers)]
    for name in case_names(runs):
        cells = []
        for server in servers:
            warm = [r["cases"].get(name) for r in runs[server] if r["mode"] == "warm" and not r.get("error")]
            warm = [c for c in warm if c]
            if warm and not any(c.get("supported") for c in warm):
                cells += ["unsupported", "-"]
                continue
            supported = [c for c in warm if c.get("supported")]
            cells.append("%s / %s / %s" % (fmt(median([c["first_ms"] for c in supported]), " ms"),
                                           fmt(median([c["median_ms"] for c in supported]), " ms"),
                                           fmt(median([c["p95_ms"] for c in supported]), " ms")))
            cells.append("%d/%d" % (sum(1 for c in supported if c["correct"]), len(supported)))
        rows.append("| %s | %s |" % (name, " | ".join(cells)))
    return rows


def answer_of(results, name):
    """The first warm run's answer to a query, else the first run's that has one."""
    ordered = sorted(results, key=lambda r: r["mode"] != "warm")
    for result in ordered:
        case = result["cases"].get(name)
        if case and case.get("supported") and case.get("answer") is not None:
            return case["answer"]
    return None


def method_of(runs, name):
    return next((r["cases"][name].get("method") for results in runs.values() for r in results
                 if name in r["cases"]), None)


def keys(answer):
    locations = Q.location_keys(answer)
    # jdtls labels a call hierarchy item with its signature, Lathe with its name only; jdtls names a
    # nested type Outer$Inner, Lathe Inner.
    return locations if locations else sorted({n.split("(")[0].strip().rsplit("$", 1)[-1] for n in answer["names"]})


def diff_section(runs):
    if not {"lathe", "jdtls"} <= set(runs):
        return []
    lines = ["## Set-valued answers, Lathe against jdtls", ""]
    for name in case_names(runs):
        if method_of(runs, name) not in SET_METHODS:
            continue
        lathe, jdtls = answer_of(runs["lathe"], name), answer_of(runs["jdtls"], name)
        if lathe is None or jdtls is None:
            continue
        a, b = set(keys(lathe)), set(keys(jdtls))
        only_lathe, only_jdtls = sorted(a - b), sorted(b - a)
        verdict = "same %d" % len(a) if not (only_lathe or only_jdtls) else \
            "%d shared, %d only Lathe, %d only jdtls" % (len(a & b), len(only_lathe), len(only_jdtls))
        lines.append("- **%s:** %s" % (name, verdict))
        for label, items in (("only Lathe", only_lathe), ("only jdtls", only_jdtls)):
            if items:
                shown = ", ".join("`%s`" % i for i in items[:EXAMPLES])
                more = " and %d more" % (len(items) - EXAMPLES) if len(items) > EXAMPLES else ""
                lines.append("  - %s: %s%s" % (label, shown, more))
    lines += ["", "A difference is settled by the build: what javac compiles is the truth (README.md, Metrics)."]
    return lines


def failures(runs):
    lines = []
    for server, results in runs.items():
        for result in results:
            if result.get("error"):
                lines.append("- %s %s run: %s" % (server, result["mode"], result["error"]))
            for name, case in result["cases"].items():
                if case.get("supported") and not case.get("correct"):
                    lines.append("- %s %s run, %s: %s" % (server, result["mode"], name,
                                                         case.get("error") or "wrong answer"))
    return ["## Failures", ""] + sorted(set(lines)) if lines else []


def main():
    out_dir = Path(sys.argv[1])
    env = json.loads((out_dir / "environment.json").read_text())
    runs = load(out_dir)
    m = env["machine"]
    lines = [
        "# %s, %s" % (env["project"], env["date"]),
        "",
        "Project commit `%s`; Lathe %s; jdtls %s; build JDK %s; jdtls JDK %s%s." % (
            env["project_commit"][:10], env["versions"]["lathe"], env["versions"]["jdtls"],
            env["versions"]["build_jdk"], env["versions"]["jdtls_jdk"],
            "; every server at -Xmx%s" % env["heap"] if env.get("heap") else ""),
        "**Variant:** jdtls with %s changed from vscode-java's defaults." % ", ".join(
            "`%s=%s`" % kv for kv in env["jdtls_settings"].items()) if env.get("jdtls_settings") else "",
        "%s, %d cores, %s GB, %s. Command: `%s`." % (m["cpu"], m["cores"], m["memory_gb"], m["os"], env["command"]),
        "",
        "## Servers (medians; memory is the summed RSS of the server's processes)",
        "",
    ] + server_table(runs) + baseline_lines(out_dir, runs) + disk_lines(runs) + branch_lines(runs) + build_change_lines(runs) + [
        "",
        "Build is what a cold run builds before the server starts: Lathe's first build as the README has a "
        "user run it, tests captured but not run, and for jdtls only where it needs the project installed "
        "first. Import is jdtls's own project import, inside its ready time. Ready runs from the server's "
        "start to the first right answer to the probe query. First answer is build plus ready: what a user "
        "waits for on a fresh checkout.",
        "",
        "## Queries (warm runs, medians)",
        "",
    ] + query_table(runs) + [""] + diff_section(runs) + [""] + failures(runs)
    (out_dir / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("report:", out_dir / "report.md")


if __name__ == "__main__":
    main()
