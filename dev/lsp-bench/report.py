#!/usr/bin/env python3
"""Writes report.md for one results directory of bench.py: medians per server, latency and
correctness per query, and every set-valued answer compared between the servers. With --headline,
the five headline items (the 2026-11-19 amendment) per project from every session given.

    python3 dev/lsp-bench/report.py dev/lsp-bench/results/<run>
    python3 dev/lsp-bench/report.py --headline dev/lsp-bench/results/*    # writes results/headline.md
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
        if path.name in ("environment.json", "baseline.json", "build_truth.json"):
            continue
        result = json.loads(path.read_text())
        runs.setdefault(result["server"], []).append(result)
    # Lathe first, as every table reads: files sort jdtls before lathe.
    return {server: runs[server] for server in sorted(runs, key=lambda s: (s != "lathe", s))}


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


def errors_lines(runs):
    """Errors against the build, per server, from an errors session."""
    rows = []
    for server, results in runs.items():
        for r in results:
            c = (r.get("errors_vs_build") or {}).get("compare")
            if c:
                rows.append("| %s | %d | %d | %d of %d | %d | %d / %d | %s |" % (
                    server, c["false_errors"], c["missed_clean_errors"], c["missed_injected"], c["injected"],
                    c["extra_injected_errors"], c["extra_warnings"], c["missed_warnings"],
                    ", ".join("`%s`" % u for u in c["unsettled"]) or "-"))
    if not rows:
        return []
    return ["", "## Errors against the build (the build's javac, clean and with an error injected per file)", "",
            "| Server | False errors | Build's errors missed | Injected missed | Other errors in injected files "
            "| Warnings extra / missed | No settled answer |", "|---|---|---|---|---|---|---|"] + rows


def failures(runs):
    lines = []
    for server, results in runs.items():
        for result in results:
            if result.get("error"):
                lines.append("- %s %s run: %s" % (server, result["mode"], result["error"]))
            if result.get("tree_changed"):
                lines.append("- %s %s run left the clone changed: %s" % (server, result["mode"],
                                                                      "; ".join(result["tree_changed"])))
            for name, case in result["cases"].items():
                if case.get("supported") and not case.get("correct"):
                    lines.append("- %s %s run, %s: %s" % (server, result["mode"], name,
                                                         case.get("error") or "wrong answer"))
    return ["## Failures", ""] + sorted(set(lines)) if lines else []


# -- The headline (the 2026-11-19 amendment) ---------------------------------------------------------

SETUP = Path(__file__).resolve().parent / "setup"
DEFAULT_HEAP = "2G"
SERVERS = ("lathe", "jdtls")


def spread(values, unit="", scale=1.0, digits=0):
    """The median with its minimum and maximum, as every headline value is given."""
    present = sorted(v for v in values if v is not None)
    if not present:
        return "-"
    med = fmt(statistics.median(present), unit, scale, digits)
    if len(present) == 1:
        return med
    return "%s (%s–%s)" % (med, fmt(present[0], "", scale, digits), fmt(present[-1], "", scale, digits))


def with_never(values, unit, scale, digits):
    """A spread of the runs that got there, and how many never did."""
    never = sum(1 for v in values if v is None)
    shown = spread(values, unit, scale, digits) if never < len(values) else "never"
    return shown + (" (never in %d of %d)" % (never, len(values)) if 0 < never < len(values) else "")


def setup_counts(server, debug=False):
    """Lines, downloads and commands of a setup file (setup/README.md)."""
    path = SETUP / ("%s%s.lua" % (server, "-debug" if debug else ""))
    if not path.exists():
        return None
    lines = path.read_text(encoding="utf-8").splitlines()
    return {"lines": sum(1 for line in lines if line.strip() and not line.strip().startswith("--")),
            "downloads": sum(1 for line in lines if line.startswith("-- download:")),
            "commands": sum(1 for line in lines if line.startswith("-- run:"))}


def setup_cell(server):
    plain, debug = setup_counts(server), setup_counts(server, debug=True)
    if not plain:
        return "-"
    cell = "%d lines, %d downloads, %d commands" % (plain["lines"], plain["downloads"], plain["commands"])
    if debug:
        cell += "; with debugging %d, %d, %d" % (debug["lines"], debug["downloads"], debug["commands"])
    return cell


def sessions(dirs):
    """{project: [(dir, environment, {server: [result]})]} of the results directories given."""
    found = {}
    for out_dir in dirs:
        env_file = out_dir / "environment.json"
        if not env_file.exists() or "-prime" in out_dir.name:
            continue
        env = json.loads(env_file.read_text())
        # A labelled variant (another heap, changed jdtls settings) is reported apart, never folded in.
        if env.get("heap", DEFAULT_HEAP) != DEFAULT_HEAP or env.get("jdtls_settings"):
            continue
        found.setdefault(env["project"], []).append((out_dir, env, load(out_dir)))
    return found


def results_of(project_sessions, server, plans, failed=False):
    """A server's runs of those plans; with failed, the runs that ended in an error too."""
    return [(out_dir, r) for out_dir, _env, runs in project_sessions for r in runs.get(server, [])
            if r.get("plan") in plans and (failed or not r.get("error"))]


def run_values(project_sessions, server, plans, value):
    """A value of every run of those plans, None for a run the server failed: what with_never counts.
    A run the harness never started (a busy machine, a wrong checkout) is no answer either way."""
    return [None if r.get("error") else value(r) for _d, r in results_of(project_sessions, server, plans, failed=True)
            if not r.get("not_started")]


def case_times(results, name):
    """Every timed run of a scenario row (a typed error), across the runs given."""
    return [t for _d, r in results for t in (r["cases"].get(name) or {}).get("times", [])]


def branch_right_ms(switch):
    """A branch switch's time until every answer it changed was right; None if one never was."""
    times = [switch[k] for k, changed in zip(("added_type_ms", "deleted_type_ms", "references_ms", "diagnostics_ms"),
                                             switch.get("changed", [True] * 4)) if changed]
    return None if any(t is None for t in times) else max(times, default=0)


def parity(project_sessions):
    """{row: {server: (supported, correct)}} from each server's parity run, and the set-valued rows'
    agreement between the servers."""
    rows, answers = {}, {}
    for server in SERVERS:
        for _d, r in results_of(project_sessions, server, ("parity",))[:1]:
            for name, case in r["cases"].items():
                rows.setdefault(name, {})[server] = (case.get("supported", False), bool(case.get("correct")))
                if case.get("supported") and case.get("method") in SET_METHODS and case.get("answer"):
                    answers.setdefault(name, {})[server] = set(keys(case["answer"]))
    agree = {name: a["lathe"] == a["jdtls"] for name, a in answers.items() if set(a) == set(SERVERS)}
    return rows, agree


def headline_rows(project, project_sessions):
    """The headline table of one project: each row (label, {server: cell}, sources)."""
    def per_server(fn):
        return {server: fn(server) for server in SERVERS}

    def sources(plans):
        files = sorted({"%s/%s-*.json" % (d.name, server) for server in SERVERS
                        for d, _r in results_of(project_sessions, server, plans)})
        return files

    cold = lambda server: results_of(project_sessions, server, ("ready",))
    # Memory from the warm runs that answer the queries only: the parity run also debugs a main method,
    # whose JVM is the server's child and counts in its memory.
    memory = lambda server: results_of(project_sessions, server, ("start",))
    errors = lambda server: results_of(project_sessions, server, ("errors",))
    branch = lambda server: [r["branch_switch"] for _d, r in results_of(project_sessions, server, ("branch",))
                             if r.get("branch_switch")]
    build = lambda server: [r["build_change"] for _d, r in results_of(project_sessions, server, ("build",))
                            if r.get("build_change")]

    def first_answer(server):
        tried = [r for _d, _e, runs in project_sessions for r in runs.get(server, [])
                 if r.get("plan") == "ready" and not r.get("not_started")]
        runs = [r for _d, r in cold(server)]
        if not tried:
            return "-"
        if not runs:
            return "no answer (%s)" % tried[0].get("error")
        parts = "build %s, ready %s" % (spread([r.get("build_ms") for r in runs], " s", 1000, 1),
                                         spread([r.get("ready_ms") for r in runs], " s", 1000, 1))
        if server == "jdtls":
            parts += ", import %s" % spread([r.get("import_ms") for r in runs], " s", 1000, 1)
        return "%s (%s)" % (with_never(run_values(project_sessions, server, ("ready",), first_answer_ms),
                                       " s", 1000, 1), parts)

    def compare(server, key):
        found = [(r["errors_vs_build"]["compare"]) for _d, r in errors(server) if r.get("errors_vs_build")]
        if not found:
            return "-"
        c = found[0]
        if key == "missed_injected":
            return "%d of %d" % (c[key], c["injected"])
        return str(c[key])

    def typed(server, name):
        times = case_times(errors(server), name)
        return with_never(times, " s", 1000, 2) if times else "-"

    def parity_cell(server):
        rows, _agree = parity(project_sessions)
        right = sum(1 for r in rows.values() if r.get(server, (False, False))[1])
        return "%d of %d" % (right, len(rows)) if rows else "-"

    return [
        ("Fresh checkout to first answer", per_server(first_answer), sources(("ready",))),
        ("Reopening the project", per_server(lambda s: with_never(
            run_values(project_sessions, s, ("start", "parity"), lambda r: r.get("ready_ms")), " s", 1000, 1)),
         sources(("start", "parity"))),
        ("Memory, peak / idle", per_server(lambda s: "%s / %s" % (
            spread([r.get("peak_mb") for _d, r in memory(s)], " MB"), spread([r.get("idle_mb") for _d, r in memory(s)], " MB"))),
         sources(("start",))),
        ("False errors on a clean checkout", per_server(lambda s: compare(s, "false_errors")), sources(("errors",))),
        ("Injected errors missed", per_server(lambda s: compare(s, "missed_injected")), sources(("errors",))),
        ("Typed error shown after", per_server(lambda s: typed(s, "diagnostics: typing an error: shows")),
         sources(("errors",))),
        ("Typed error gone after the undo", per_server(lambda s: typed(s, "diagnostics: typing an error: settles without it")),
         sources(("errors",))),
        ("Right again after a branch switch", per_server(lambda s: with_never([branch_right_ms(b) for b in branch(s)],
                                                                              " s", 1000, 1) if branch(s) else "-"),
         sources(("branch",))),
        ("Right again after a dependency is added", per_server(lambda s: with_never([b.get("fixed_ms") for b in build(s)],
                                                                                    " s", 1000, 1) if build(s) else "-"),
         sources(("build",))),
        ("Features with the same answers", per_server(parity_cell), sources(("parity",))),
    ]


def parity_lines(project_sessions):
    rows, agree = parity(project_sessions)
    if not rows:
        return []
    mark = lambda cell: "-" if cell is None else ("✓" if cell[1] else ("✗ unsupported" if not cell[0] else "✗"))
    lines = ["", "| Feature | Lathe | jdtls | Same locations |", "|---|---|---|---|"]
    for name in sorted(rows):
        lines.append("| %s | %s | %s | %s |" % (name, mark(rows[name].get("lathe")), mark(rows[name].get("jdtls")),
                                               {True: "yes", False: "no"}.get(agree.get(name), "-")))
    return lines


def headline(dirs):
    found = sessions(dirs)
    lines = ["# Headline", "",
             "Every value is the median, with its minimum and maximum, of the runs the sessions below hold "
             "(the 2026-11-19 amendment); a server that never got there says so.", "",
             "Setup in Neovim (setup/): Lathe %s; jdtls %s." % (setup_cell("lathe"), setup_cell("jdtls"))]
    for project in sorted(found):
        project_sessions = found[project]
        envs = [env for _d, env, _r in project_sessions]
        versions = sorted({"Lathe %s, jdtls %s" % (e["versions"]["lathe"], e["versions"]["jdtls"]) for e in envs})
        lines += ["", "## %s" % project, "",
                  "Commit `%s`; %s." % (envs[0]["project_commit"][:10], "; ".join(versions)), "",
                  "| | Lathe | jdtls |", "|---|---|---|"]
        rows = headline_rows(project, project_sessions)
        for label, cells, _sources in rows:
            lines.append("| %s | %s | %s |" % (label, cells["lathe"], cells["jdtls"]))
        lines += parity_lines(project_sessions)
        lines += ["", "Sources:", ""]
        for label, _cells, files in rows:
            lines.append("- %s: %s" % (label, ", ".join("`%s`" % f for f in files) or "none"))
        lines += ["", "Commands:", ""] + sorted({"- `%s` (%s)" % (env["command"], d.name)
                                                 for d, env, _r in project_sessions})
    return lines


def main():
    if sys.argv[1] == "--headline":
        dirs = [Path(a) for a in sys.argv[2:] if Path(a).is_dir()]
        out = (dirs[0].parent if dirs else Path(".")) / "headline.md"
        out.write_text("\n".join(headline(dirs)) + "\n", encoding="utf-8")
        print("headline:", out)
        return
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
    ] + query_table(runs) + errors_lines(runs) + [""] + diff_section(runs) + [""] + failures(runs)
    (out_dir / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("report:", out_dir / "report.md")


if __name__ == "__main__":
    main()
