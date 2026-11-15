#!/usr/bin/env python3
"""One coverage report for the whole reactor: every module's unit tests and the invoker fixtures,
which run the build side and the servers end to end. Run after a coverage build:

    mvn verify -Pcoverage -pl '!lathe-neovim,!lathe-vscode,!lathe-gradle-plugin'
    python3 -I dev/coverage.py

Merges every module's target/jacoco*.exec and reports them against all modules' classes, as a
fixture's run covers classes of other modules than its own. Writes target/coverage/html/index.html
and target/coverage/coverage.csv, and prints the line and branch coverage per module.
"""
import csv
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "target" / "coverage"
# A multi-release jar's newer-JDK copy of a class has its name; JaCoCo refuses two of one name.
VERSIONS = Path("META-INF") / "versions"


def jacoco_version():
    return re.search(r"<jacoco.version>([^<]+)</jacoco.version>", (ROOT / "pom.xml").read_text()).group(1)


def cli_jar():
    version = jacoco_version()
    jar = OUT / f"org.jacoco.cli-{version}-nodeps.jar"
    if not jar.exists():
        subprocess.run(
            ["mvn", "-q", "dependency:copy", f"-Dartifact=org.jacoco:org.jacoco.cli:{version}:jar:nodeps",
             f"-DoutputDirectory={OUT}"],
            cwd=ROOT, check=True)
    return jar


# Each module's classes, without a multi-release jar's versioned copies.
def class_dirs():
    dirs = {}
    for classes in sorted(ROOT.glob("*/target/classes")):
        module = classes.parent.parent.name
        if (classes / VERSIONS).is_dir():
            copy = OUT / "classes" / module
            shutil.rmtree(copy, ignore_errors=True)
            shutil.copytree(classes, copy, ignore=lambda d, _: ["versions"] if Path(d).name == "META-INF" else [])
            classes = copy
        dirs[module] = classes
    return dirs


# The module whose classes are in the package itself: a package dir may also be a parent of another
# module's (lathe.maven holds lathe.maven.extension).
def module_of(package, dirs):
    path = package.replace(".", "/")
    return next((m for m, d in dirs.items() if any((d / path).glob("*.class"))), "?")


def main():
    execs = sorted(str(p) for p in ROOT.glob("*/target/jacoco*.exec"))
    if not execs:
        sys.exit("no coverage data: run `mvn verify -Pcoverage` first")

    OUT.mkdir(parents=True, exist_ok=True)
    jar = cli_jar()
    merged = OUT / "all.exec"
    subprocess.run(["java", "-jar", str(jar), "merge", *execs, "--destfile", str(merged), "--quiet"],
                   check=True)
    dirs = class_dirs()
    args = ["java", "-jar", str(jar), "report", str(merged), "--name", "Lathe", "--quiet",
            "--html", str(OUT / "html"), "--csv", str(OUT / "coverage.csv")]
    for module, classes in dirs.items():
        args += ["--classfiles", str(classes)]
        for sources in (ROOT / module / "src" / "main").glob("java*"):
            args += ["--sourcefiles", str(sources)]
    subprocess.run(args, check=True)

    totals = {}
    for row in csv.DictReader(open(OUT / "coverage.csv")):
        counts = totals.setdefault(module_of(row["PACKAGE"], dirs), [0, 0, 0, 0])
        for i, key in enumerate(("LINE_MISSED", "LINE_COVERED", "BRANCH_MISSED", "BRANCH_COVERED")):
            counts[i] += int(row[key])
    totals["TOTAL"] = [sum(c[i] for c in totals.values()) for i in range(4)]
    print(f"{len(execs)} data files merged; report: {OUT / 'html' / 'index.html'}")
    print(f"{'module':28} {'lines':>7} {'branches':>9} {'missed lines':>13}")
    for module, (lm, lc, bm, bc) in totals.items():
        print(f"{module:28} {100 * lc / max(1, lm + lc):6.1f}% {100 * bc / max(1, bm + bc):8.1f}% {lm:13d}")


if __name__ == "__main__":
    main()
