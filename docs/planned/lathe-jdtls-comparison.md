# Lathe — Measured Comparison with jdtls

## Status

**In progress.** The harness is built (`dev/lsp-bench/`: the pre-registration #134, the navigation queries #135, the editing loop and outside changes 2026-11-11 to 11-13) and was trialled on every project.
On 2026-11-19 the published comparison was narrowed to five headline items, recorded as the 2026-11-19 amendment of `dev/lsp-bench/README.md`.
The harness for the five items is built (below); next, a trial round on the release candidate, then the measured round on the release.
The measured results stay out of the repository until the comparison page is written.

## The rule

Lathe claims nothing over jdtls that a measurement anyone can rerun does not show.
Where a measurement shows Lathe worse, the fix comes before any comparison is published.
Rows Lathe still loses after the fixes are published as losses.

Architecture differences are facts and may be stated as such:
Lathe takes its project model from the build itself, and its diagnostics come from javac with the build's flags;
jdtls builds its own Eclipse model of the project (m2e, Buildship) and checks code with ECJ, the Eclipse compiler.
Whether those differences matter to a user is what this plan measures.

## The headline: five items

A reader decides in a minute or two whether to try Lathe, so the page leads with five items, each a question a Java developer choosing an editor setup asks.
They were chosen where the architecture says Lathe should be stronger, and the page says so.
What makes them worth reading is the precision of each number, not their count.

| # | The question | What is measured |
|---|---|---|
| 1 | How long until it works on my project? | From a fresh checkout to the first right answer (a cold run): Lathe's first build with test capture plus its first start, against jdtls's first start on an empty workspace, which is its import (on Helidon after the `mvn install` it needs), each part shown. Reopening the project (warm ready) beside it. A project a server cannot serve shows "no answer" and how to reproduce it. |
| 2 | Will it slow my machine? | Peak and idle resident memory (RSS) of the server's processes, both at the same heap. |
| 3 | Can I trust the errors? | Against the build's own javac: errors a server reports on a clean checkout that the build does not, and injected errors the build reports that a server misses. Then the time from typing an error to seeing it, and from the undo to seeing it gone. |
| 4 | Does it stay right when the project changes under it? | A branch switch, and a dependency added to the build file and used: the time until the answers and errors are right again. A server that needs a restart or a workspace wipe to get there fails the item, with what it took. |
| 5 | Does it do what jdtls does? | One row per feature: supported or not, and on the fixed queries whether both servers give the same answer, location by location, a disagreement settled by compiling with the build. |

Item 5 covers definition (project, inherited, library, JDK), type definition, implementations, references, document highlight, type hierarchy both ways, call hierarchy both ways, hover, signature help, completion, rename (the result compiles), the import fix, formatting, document and workspace symbols, folding, and running and debugging a main method (on jdtls through the java-debug bundle, counted in its setup).
Its latency goes in the full report, not the table.
Rows Lathe lacks say so: inlay hints (EG-091, deferred), and member symbol search, which Lathe does not plan (jdtls too searches types only by default).

Not in the headline, and why:
- Running a test from the editor: its measurement (8 in the 2026-11-11 amendment) is not built, and few users of either server run tests from Neovim.
  The page states as a fact that Lathe runs a test as the build ran it.
- Completion latency, an error in another module, disk use: in the full report.
- Of items 3 and 4, the README table shows the typed error and the branch switch; the time until an undone error is gone and the build-file change are on the page.
- Navigation latency: mixed, as Lathe is slower on references to common method names (FR-029, deferred); in the full report and in "where jdtls is better".

Setup is stated in the page's introduction as counts, not as an item: the lines of Neovim configuration, the files to download and the commands to run before each server answers.

## Precision

- Lathe's release, jdtls's newest release when the round starts, the projects' commits and every JDK are pinned before the round's first session and recorded with every result.
- Both servers run at `-Xmx2G`; one user setup throughout (Neovim, jdtls on vscode-java's settings).
- What is right or wrong is checked once; only what is timed is repeated, as only timings vary between runs:

  | Measurement | Runs per server and project | Why |
  |---|---|---|
  | A cold run: the build, then the first start (jdtls: its `mvn install` on Helidon, then its first start on an empty workspace, which imports) | 2 | minutes long, with little spread |
  | A warm run: reopening the built project, with peak and idle memory | 4 | the first is also the parity run; memory comes from the other 3 |
  | A typed error until it shows, and until it is gone | 10 | seconds each |
  | A branch switch; a build-file change | 3, with a 5-minute deadline | each can take minutes |
  | Feature parity; errors against the build | 1 | the same answer every run |

  A cold run gives the time to a first answer, its build and its first start shown apart; a warm run gives the reopening time.
  Every timed value is the median with its minimum and maximum, never one run.
- A run starts only once the machine's load is under half its cores, and is repeated when the machine suspends during it.
- A latency counts only for a right answer; a wrong answer is reported as wrong, not as fast.
- Every right answer is fixed before the first measured run: the queries and their checks, the injected errors, the branch-switch commit and the dependency.
- Every published value links to its raw results and to the one command that reproduces it.

## Projects

- spring-petclinic: small, single module, known to everyone.
- Dropwizard: a large Maven reactor.
- Helidon: large, modular (JPMS), with annotation processors whose output the rest of its code calls.

Hibernate ORM (a large Gradle build) and OpenMetadata (Lombok) keep their pinned queries but are not measured in this round, to keep its machine time short.
OpenJDK appears in item 1 as a project Lathe serves and jdtls does not, with how jdtls was set up (the 2026-11-11 amendment).

## Sessions and machine time

The round runs as sessions of at most 45 minutes, each one project and one or more whole groups (start and parity; errors and typing; outside changes), so it fits around a working day instead of a night; a group longer than that runs one server per session.
A session that is interrupted is discarded and run again, never resumed.
Estimated from the stage 1 runs:

| Group | spring-petclinic | Dropwizard | Helidon |
|---|---|---|---|
| Build or import, ready, memory, parity | 15 min | 35 min | 75 min |
| Errors against the build, typing | 5 min | 20 min | 35 min |
| Branch switch, build-file change | 10 min | 20 min | 40 min |

About 4 hours in 7 sessions: spring-petclinic in one, Dropwizard in two (start and parity; the other two groups), Helidon in four (start and parity once per server; errors and typing; outside changes).
The trial round runs everything once, in about 1½ hours.

## The harness

`dev/lsp-bench/`, one session per command:

```bash
python3 -I dev/lsp-bench/bench.py <project> --prime             # downloads, not measured
python3 -I dev/lsp-bench/bench.py <project> --group start       # items 1, 2 and 5
python3 -I dev/lsp-bench/bench.py <project> --group errors      # item 3
python3 -I dev/lsp-bench/bench.py <project> --group changes     # item 4
python3 -I dev/lsp-bench/bench.py helidon --group start --servers jdtls   # a group split per server
python3 -I dev/lsp-bench/report.py --headline dev/lsp-bench/results/*     # the five items per project
python3 -I dev/lsp-bench/selfcheck.py                           # the logic, without servers, in seconds
```

- **Sessions (`bench.py --group`):** a run count per measurement, as in the table above; a cold run times the build and the first start only, a warm run answers the queries once, and the first warm run of a start session checks the rest of parity; the branch switch's fresh-start answers are taken once per session.
- **Errors against the build (`stage2.py`):** a worktree of the clone at the pinned commit, built offline with no Lathe; the injected error is `static final int LATHE_BENCH = "x";` after the opening line of each target file's first top-level class, interface or record (an enum or annotation type is left out); the injected build runs in passes, each dropping the injections the last reported, as Maven skips the modules downstream of a failed one.
  Each server then gets the same files: as opened, and with the same error typed in, unsaved, until its diagnostics settle.
- **Parity's extra rows (`extra_checks`):** formatting answers, folding gives ranges, and a main method (`debug_main` in `projects.py`) stops at a breakpoint on its first statement: on Lathe through `lathe.debug.main` and its DAP host, on jdtls through the java-debug bundle (0.60.0, plugin 0.53.2), loaded in every jdtls run as a user who debugs has it.
- **The headline (`report.py --headline`):** the five items per project, each value the median with its range, the setup counts from `setup/`, the parity table, and every value's result files and command.

Trial sessions run on a snapshot of `main` stamped with its commit (`0.3.99-<commit>`, the amendment's rule), never the local `0.1.0-SNAPSHOT`; the 2026-11-19 trial ran on `0.3.99-0e2144a5`.
Checked before that, on versions other than the round's pinned ones: the self-checks; the ground truth on spring-petclinic (three target files, each injection reported on its own line, no error on the clean build); and both debug flows on spring-petclinic, each stopping at the breakpoint (Lathe 0.3.1 with its older name for the runnables command, jdtls 1.51).
The trial round runs them on the pinned versions.
Lathe 0.3.1 advertises no formatter on spring-petclinic, whose style (spring-javaformat) it does not run, so that row would show Lathe unsupported there.

## The page

Written last, from the measured round, as a `docs/guide/` page linked from the README's "Why Lathe".
- The architecture differences, stated as facts, and the setup counts.
- The five items, both servers side by side per project.
- "Where jdtls is better": every loss in its own words.

The README's "Why Lathe" carries one table, read in half a minute, with the page one click away.
Its layout, with placeholders where the measured values go:

```markdown
## Why Lathe

Lathe reads your project from your build and checks it with javac, the compiler the build uses.
Measured against jdtls <version> on Helidon, Dropwizard and spring-petclinic, at the same 2 GB heap
([how it was measured, and every number per project](docs/guide/jdtls-comparison.md)):

|                                          | Lathe          | jdtls                    |
|------------------------------------------|----------------|--------------------------|
| Fresh checkout to first answer (Helidon) | <time>         | <time> ¹                 |
| Reopening the project                    | <time>         | <time>                   |
| Memory, peak / idle (Helidon)            | <GB> / <GB>    | <GB> / <GB>              |
| False errors on a clean checkout         | <n>            | <n>                      |
| Injected errors missed (of <n>)          | <n>            | <n>                      |
| Typed error shown after                  | <time>         | <time>                   |
| Right again after a branch switch        | <time>         | <time, or what it took>  |
| Right again after a dependency is added  | <time>         | <time, or what it took>  |
| Features with the same answers           | <n> of <n> ²   | <n> of <n>               |
| Setup in Neovim                          | <n> lines      | <n> lines, <n> downloads |

¹ includes the `mvn install` jdtls needs before it imports Helidon.
² what is missing, and where jdtls is faster, on the linked page.
Each value is a median; its range and the command that reproduces it are on the linked page.
```
- Per project, the full report with every value, its raw results and the command that reproduces it.

## Order

1. Finish the release QA (the MCP battery and the gaps).
2. Set up the round's machine (`setup.sh`, the clones and the pinned Lathe).
3. A trial round on the release candidate, about 1½ hours: it tests the harness, and a loss it shows is fixed or filed before the release; its numbers are never published.
4. The measured round on the release, about 4 hours in sessions of at most 45 minutes.
5. The page.

## Findings so far

Leads from one machine, one run each, 2026-11-05, recorded to choose the work, not to publish:

| | Lathe | jdtls 1.63 |
|---|---|---|
| spring-petclinic: ready, warm | 1.8 s | 3.7 s |
| spring-petclinic: peak memory | 975 MB | 1,842 MB |
| spring-petclinic: CPU during a warm run | 42 s | 111 s |
| spring-petclinic: definition, hover, hierarchy queries | 2–8 ms | 3–40 ms |
| spring-petclinic: references to `getId` / `getPet` | 128 / 106 ms | 751 / 76 ms |
| Hibernate ORM: fresh workspace to first answer | about 2 s after a sync | 99 s import, then no answer within 10 min |
| Hibernate ORM: `StringHelper.isEmpty` references, first / repeated | 9.6 s / 4.8 s | 20.9 s / 4.8 s |

From the stage 1 runs (2026-11-08, against `0.3.99-c15d3a55`), what jdtls needs on these projects: Helidon installed into a repository before it imports (its processors resolve only from one); a Lombok agent on the Lombok projects, as vscode-java gives it; and on OpenMetadata about 40 processes and 13.5 GB on its first queries.

Lathe's losses found so far, each a gap:
- **FR-031** (fixed, #136): references missed every use outside the declaring file when a parameter carried a type-use annotation, on JDK 21 and older.
- **FR-029** and **FR-032** (deferred): references to a method many files name, and implementations and incoming calls on large projects, take seconds.
- **WS-17** (non-goal): with no heap cap Lathe reaches 4–9 GB on the large projects; Lathe sets no default limit, and at `-Xmx2G` every answer stays the same.
- **EG-096** (fixed): definition on `new X(...)` answered the class, not the constructor.
- **WS-31, WS-32, TE-16, WS-33, TE-12** (fixed): found while setting the projects up.
