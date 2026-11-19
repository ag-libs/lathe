"""Errors against the build (measurement 4 of the 2026-11-11 amendment, item 3 of the 2026-11-19
headline): the build's own javac errors and warnings per file, from a clean build and from one with the
same type error injected into each file a query of the project points at, against each server's
diagnostics on those files, both ways.

The build runs in a worktree of the clone at the pinned commit, offline and with no Lathe loaded
(`build_truth`); bench.py's `--group errors` then opens the same files in each server
(`server_errors`). Maven only: the headline's three projects build with Maven.

The injected error is one line, `static final int LATHE_BENCH = "x";`, after the opening line of the
file's first top-level class, interface or record, where every one of those accepts a static field; a
file whose first top-level type is an enum or an annotation is left out, as a field there would be a
different error. Builds go on past a failed module (`-fae`), but Maven skips the modules that depend on
one, so the injected build runs in passes: each pass drops the injections the last one reported, until
every file has its answer.
"""
import os
import re
import shutil
import subprocess
from pathlib import Path

import queries as Q
import servers as S

INJECTED = '    static final int LATHE_BENCH = "x";'
# Modifiers and annotations in any order before the kind, as `@Deprecated public class A {` has them.
TOP_TYPE = re.compile(r"^(?:(?:public|abstract|final|sealed|non-sealed|strictfp)\s+|@[\w.]+(?:\([^)]*\))?\s+)*"
                      r"(class|interface|record|enum|@interface)\s+\w+")
# Maven's compiler plugin: `[ERROR] /abs/path/File.java:[line,column] message`.
MESSAGE = re.compile(r"^\[(ERROR|WARNING)\] (/\S+?\.java):\[(\d+),(\d+)\] (.*)$")
MAX_PASSES = 6


def targets(cases):
    """The files a query of the project points at: its navigation queries' positions and files."""
    return sorted({c["at"][0] for c in cases if "at" in c} | {c["file"] for c in cases if "file" in c})


def injection(text):
    """(the text with the error injected, its 1-based line), or None for a file whose first top-level
    type takes no static field the same way."""
    lines = text.split("\n")
    start = next((i for i, line in enumerate(lines) if TOP_TYPE.match(line)), None)
    if start is None:
        return None
    match = TOP_TYPE.match(lines[start])
    if match.group(1) in ("enum", "@interface"):
        return None
    # The body's brace comes after the type's name: an annotation before it may hold braces of its own.
    braces = [(i, lines[i].find("{", match.end() if i == start else 0)) for i in range(start, len(lines))]
    opening, column = next(((i, c) for i, c in braces if c >= 0), (None, -1))
    # A body that closes on its opening line (`class Empty {}`) has no line inside it.
    if opening is None or "}" in lines[opening][column:]:
        return None
    return "\n".join(lines[:opening + 1] + [INJECTED] + lines[opening + 1:]), opening + 2


def parse(output, root):
    """{path: {"errors": [line], "warnings": [line]}} of a build's output, paths relative to root;
    each message once (Maven repeats the errors in its failure summary)."""
    found, seen = {}, set()
    # Resolved, so a work directory reached through a symlink still matches what Maven prints.
    prefix = os.path.realpath(root).rstrip("/") + "/"
    for raw in output.splitlines():
        match = MESSAGE.match(raw.strip())
        if not match or match.groups() in seen:
            continue
        absolute = os.path.realpath(match.group(2))
        if not absolute.startswith(prefix):
            continue
        seen.add(match.groups())
        kind, path, line = match.group(1), absolute[len(prefix):], int(match.group(3))
        entry = found.setdefault(path, {"errors": [], "warnings": []})
        entry["errors" if kind == "ERROR" else "warnings"].append(line)
    return {path: {k: sorted(v) for k, v in entry.items()} for path, entry in found.items()}


def module_of(root, path):
    """The Maven module directory holding a file: the nearest directory above it with a pom.xml."""
    for parent in Path(path).parents:
        if (root / parent / "pom.xml").exists():
            return parent.as_posix()
    return "."


def build_command(root, project, files):
    """The build of the modules holding the files and what they need, offline, with no Lathe and the
    project's lint and format checks off, going on past a failed module; not quiet, so warnings show.
    A project whose compiler plugin takes its own reactor's processors builds the whole reactor, as
    `-am` follows module dependencies only."""
    modules = sorted({module_of(root, f) for f in files})
    whole = modules == ["."] or project.get("errors_whole_reactor")
    selected = [] if whole else ["-pl", ",".join(modules), "-am"]
    maven = "./mvnw" if (root / "mvnw").exists() else S.MAVEN
    return [maven, "-B", "-o", "-fae", "test-compile"] + selected + S.MAVEN_CHECKS_OFF


def compile_at(root, project, files, log, must_pass=False):
    """The build's javac messages per file; with must_pass, a build that fails is no answer at all
    (offline resolution, a plugin, the JDK), not a build with no errors."""
    command = build_command(root, project, files)
    build = subprocess.run(command, cwd=root, env=S.build_env(project), capture_output=True, text=True)
    with open(log, "a", encoding="utf-8") as out:
        out.write("$ %s\n%s%s\n" % (" ".join(command), build.stdout, build.stderr))
    if must_pass and build.returncode != 0:
        raise RuntimeError("the clean build failed (exit %d), so it gives no truth; see %s" % (build.returncode, log))
    return parse(build.stdout + build.stderr, root)


def build_truth(name, project, cases, log):
    """The build's answer for every target file: its errors and warnings on the clean checkout, and
    for each file with the error injected, the lines the build reports in it."""
    if project["build"] != "maven":
        raise SystemExit("errors against the build run on Maven projects only; %s builds with %s"
                         % (name, project["build"]))
    repo = S.repo_of(name)
    copy = S.WORK / "errors-check" / name
    # What an interrupted run left: a worktree, or a directory git no longer knows as one.
    subprocess.run(["git", "worktree", "remove", "--force", str(copy)], cwd=repo, capture_output=True)
    shutil.rmtree(copy, ignore_errors=True)
    subprocess.run(["git", "worktree", "prune"], cwd=repo, capture_output=True)
    subprocess.run(["git", "worktree", "add", "--detach", "-q", str(copy), project["commit"]], cwd=repo, check=True)
    try:
        files = targets(cases)
        clean = compile_at(copy, project, files, log, must_pass=True)
        originals = {f: (copy / f).read_text(encoding="utf-8") for f in files}
        injected = {f: injection(text) for f, text in originals.items()}
        skipped = sorted(f for f, i in injected.items() if i is None)
        pending = {f for f, i in injected.items() if i is not None}
        for f in pending:
            (copy / f).write_text(injected[f][0], encoding="utf-8")
        answers = {}
        for _ in range(MAX_PASSES):
            if not pending:
                break
            built = compile_at(copy, project, sorted(pending), log)
            settled = {f for f in pending if built.get(f, {}).get("errors")}
            for f in settled:
                answers[f] = {"line": injected[f][1], "errors": built[f]["errors"]}
                (copy / f).write_text(originals[f], encoding="utf-8")
            pending -= settled
            if not settled:
                break
        return {"command": " ".join(build_command(copy, project, files)), "files": files, "skipped": skipped,
                "clean": {f: clean.get(f, {"errors": [], "warnings": []}) for f in files},
                "injected": answers, "unanswered": sorted(pending)}
    finally:
        subprocess.run(["git", "worktree", "remove", "--force", str(copy)], cwd=repo, capture_output=True)


def lines_of(diagnostics, severity):
    """The 1-based lines of a publish's diagnostics of one severity (1 error, 2 warning)."""
    return sorted({d["range"]["start"]["line"] + 1 for d in diagnostics if d.get("severity") == severity})


def server_errors(client, repo, truth, last_published, settle, wait_quiet):
    """Each target file's diagnostics in a running server: as opened (the clean checkout, read once the
    server is quiet; nothing published reads as nothing, as an editor shows it), then with the build's
    injected error typed in, unsaved, until the diagnostics settle, then undone."""
    wait_quiet()
    clean = {}
    for path in truth["files"]:
        published = last_published(client, Q.uri(repo, path)) or []
        clean[path] = {"errors": lines_of(published, 1), "warnings": lines_of(published, 2)}
    injected = {}
    for path, answer in truth["injected"].items():
        original = (repo / path).read_text(encoding="utf-8")
        text, line = injection(original)
        target = Q.uri(repo, path)
        try:
            settled_ms = settle(lambda: Q.change(client, repo, path, text), target)
            published = last_published(client, target) if settled_ms is not None else None
            injected[path] = {"line": line, "settled_ms": settled_ms,
                              "errors": lines_of(published, 1) if published is not None else None}
        finally:
            settle(lambda: Q.change(client, repo, path, original), target)
    return {"clean": clean, "injected": injected}


def compare(truth, server):
    """Both ways, per the amendment: on the clean checkout, the errors a server reports on lines the build
    reports none (false) and the build's errors it misses; with the injected errors, the injections the
    build reports and the server misses, and the server's errors on other lines of those files. Warnings
    on the clean checkout likewise, for the full report."""
    def apart(theirs, ours):
        return len(set(ours) - set(theirs)), len(set(theirs) - set(ours))

    false_errors = missed_clean = extra_warnings = missed_warnings = 0
    for path, built in truth["clean"].items():
        seen = server["clean"].get(path, {"errors": [], "warnings": []})
        extra, missed = apart(built["errors"], seen["errors"])
        false_errors, missed_clean = false_errors + extra, missed_clean + missed
        extra, missed = apart(built["warnings"], seen["warnings"])
        extra_warnings, missed_warnings = extra_warnings + extra, missed_warnings + missed
    missed_injected = extra_injected = 0
    unsettled = []
    for path, built in truth["injected"].items():
        seen = server["injected"].get(path)
        if not seen or seen["errors"] is None:
            unsettled.append(path)
            missed_injected += built["line"] in built["errors"]
            continue
        missed_injected += built["line"] in built["errors"] and built["line"] not in seen["errors"]
        extra_injected += len(set(seen["errors"]) - set(built["errors"]))
    return {"false_errors": false_errors, "missed_clean_errors": missed_clean,
            "injected": sum(1 for b in truth["injected"].values() if b["line"] in b["errors"]),
            "missed_injected": missed_injected, "extra_injected_errors": extra_injected,
            "extra_warnings": extra_warnings, "missed_warnings": missed_warnings, "unsettled": sorted(unsettled)}
