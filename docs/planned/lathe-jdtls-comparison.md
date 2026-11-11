# Lathe — Measured Comparison with jdtls

## Status

**In progress.** Stage 0 done (the pre-registration, #134); stage 1 done (the harness and all six projects measured, #135, 2026-11-08); stage 1b (the editing loop and outside changes, pre-registered 2026-11-11), 2 and 3 next.
This document is the plan for answering "why Lathe and not jdtls?" with evidence instead of assertion.
The measured results stay out of the repository until stage 4, as pre-registered; what they found that is Lathe's own is in the gap log, listed under stage 3 below.

## The rule

Lathe claims nothing over jdtls that a measurement anyone can rerun does not show.
Where a measurement shows Lathe worse, the fix comes before any comparison is published.
Rows Lathe still loses after the fixes are published as losses.

Architecture differences are facts and may be stated as such:
Lathe takes its project model from the build itself, and its diagnostics come from javac with the build's flags;
jdtls builds its own Eclipse model of the project (m2e, Buildship) and checks code with ECJ, the Eclipse compiler.
Whether those differences matter to a user is exactly what this plan measures.

## Leads so far (not claims)

One machine, one run each, 2026-11-05; recorded to choose the work, not to publish.

| | Lathe | jdtls 1.63 |
|---|---|---|
| spring-petclinic: ready, warm | 1.8 s | 3.7 s |
| spring-petclinic: peak memory | 975 MB | 1,842 MB |
| spring-petclinic: CPU during a warm run | 42 s | 111 s |
| spring-petclinic: definition, hover, hierarchy queries | 2–8 ms | 3–40 ms |
| spring-petclinic: references to `getId` / `getPet` | 128 / 106 ms | 751 / 76 ms |
| spring-petclinic: answers (17 queries) | 17/17, matching jdtls location by location | 17/17 |
| Hibernate ORM: fresh workspace to first answer | about 2 s after a sync | 99 s import, then no answer within 10 min |
| Hibernate ORM: `StringHelper.isEmpty` references, first / repeated | 9.6 s / 4.8 s | 20.9 s / 4.8 s |

Known weaker spots: Lathe needs a build before it answers; a repeated query costs it the full search where jdtls answers from its index; it lost one petclinic query (`getPet` references); idle memory stays near 1 GB; and it lacks features jdtls has (EG-091…095).

## Stage 0 — Fix the method before running anything

A pre-registration committed before any result, in the style of `dev/bench/README.md`, so the queries and metrics cannot be chosen after seeing the numbers.

**Projects**
- spring-petclinic: small, single module; anchors the numbers to java-index's published benchmark.
- Dropwizard and Helidon: large Maven reactors; Helidon is also modular (JPMS) and runs annotation processors (its builders).
- Hibernate ORM: a large Gradle build.
- record-companion (`ag-libs/record-companion`): small, modular (JPMS), with two annotation processors (`BuilderProcessor`, `ValidCheckProcessor`) whose output its tests module compiles against; the sharp case for stage 2.
  It is the Lathe author's own project, so it never carries a published result alone: Helidon covers the same combination at scale, from a third party.
- A public project built on Lombok and a public project using MapStruct or Immutables: to be chosen (open question 1).

**Servers**
- Lathe: the latest release, and the working tree for before-and-after runs of a fix.
- jdtls: a pinned version, with VS Code's default settings, as a VS Code user runs it.
- java-index: optional, on spring-petclinic only, to compare with its own published numbers.

**Queries per project, fixed in advance**
- The 17 kinds java-index's benchmark asks: definition (project, inherited, library, JDK), type definition, hover, references (common and overloaded), document highlight, implementations, type hierarchy both ways, call hierarchy both ways, signature help, document symbols, workspace symbols.
- The hard cases found while profiling: a common method name, a static utility method, an interface method with many implementations, a field read through a getter, an enum constant in a `switch`.

**Metrics**
- Ready time, cold and warm; Lathe's build or sync and jdtls's import each reported as their own row, never hidden in the other.
- Peak and idle resident memory (RSS), and CPU time.
- Per query: the first run, then median and 95th percentile over a fixed number of repeats.
- Correctness: every set-valued answer compared location by location between the servers; a disagreement is resolved by compiling with the build.

**Fairness**
- Each server runs with the defaults its users get; one extra run gives both the same heap limit.
- The same JDK where both support it; the machine, load, JDK and every version recorded with the results.
- A fixed warm-up; medians over several runs; Gradle daemons stopped after each run.

## Stage 1 — The benchmark harness

- Lives in `dev/` next to `dev/jdtls_diff.py`, for example `dev/lsp-bench/` (`dev/bench/` is the MCP-value benchmark).
- Reuses the `LatheClient` and `JdtlsClient` presets (`dev/lsp.py`, `dev/jdtls.py`), and the timing, memory and correctness logic prototyped against java-index's harness.
- One query file per project with pinned positions; JSON results and a markdown report per run.
- Run by hand before a release, not in CI: shared CI runners are too noisy for timing.
- Done when every project has a full report. Reports are not published until stage 4.

**Done (2026-11-08):** `dev/lsp-bench/` (#135) measured all six projects, 3 cold and 3 warm runs per server and one run of Lathe at jdtls's 2 GB heap, against Lathe `0.3.99-c15d3a55` (`main` at `c15d3a55`).
What running jdtls as its users get it took, each an amendment in the pre-registration:
- jdtls cannot import Helidon from a fresh checkout: its Maven integration resolves the compiler plugin's dependencies (Helidon's own annotation processors) only from a repository, so its runs install Helidon first, timed as its build.
- jdtls does not import Apache Fineract at all: Buildship's Gradle model creates every task, and a Fineract plugin fails on Gradle 9.7.1 when one is created, with jdtls's annotation processing on or off.
- vscode-java starts jdtls with a Lombok agent, so the harness does too on the Lombok projects (Fineract, OpenMetadata).

What the harness itself had to learn: a server that stops reading its input while it imports deadlocks a client that writes on the reading thread; a Gradle build leaves worker processes running that must not count as the server's; a run the machine suspends during is discarded; and a readiness probe must be a query whose answer no server disputes.

## Stage 1b — What a Neovim user does all day

Stage 1 measures navigation; a jdtls user in Neovim spends the day typing, completing, reading errors and switching branches, and wants to know whether the errors match the build.
Pre-registered in the 2026-11-11 amendment of `dev/lsp-bench/README.md`, with both servers at `-Xmx2G` from then on (the default of `bench.py --heap`).
How each item is built from what exists:

| Item | Reuse | Add | Size |
|---|---|---|---|
| Lathe's build, as a user first runs it: the README's first-build command with test capture, the same build with Lathe off, and build plus ready beside jdtls's ready | `servers.lathe_build_command` and `lathe_prepare`'s timing; the build row in `report.py` | the Maven command from the README instead of `process-test-classes`; a once-per-project baseline run with `-Dlathe.disabled=true` (Gradle: the same tasks without the plugin); a "first answer" column | S |
| Completion: four positions, an expected item in the first 20, the resolve timed | the case and position mechanism and `run_cases`' timing (`bench.py`); `jdtls_diff.canon_completion`, which strips jdtls's `Label - package` | `textDocument/completion` in `queries.CAPABILITY` and the completion capability in `bench.CLIENT_CAPABILITIES`; an `edit` field on a case, applied by `didChange` before the request; a rank-within-N check, and the import edit for the unimported type | S–M |
| Diagnostics while typing | `lsp.change`/`save`; `lsp_staleness.Probe.diags` and its error checks; the metric the editor smoke already defines (`driver.lua`: insert, error, undo, clean) | a monotonic timestamp on every `publishDiagnostics`, as `StampedQueue` does for `language/status`; a case kind timed from the change to the publish for that version | M |
| An error in another module | `lsp_staleness.Probe.poll` and `restore` | a case naming the upstream edit and the open downstream file; both servers saved to disk, as jdtls builds on save | M |
| Diagnostics against the build | `jdtls_diff.canon_diagnostics` (Lathe against jdtls only, today) | `stage2.py`: the build with warnings shown, javac's `/path:[line,col]` lines parsed per file, each server's publishes on those files compared both ways; public fixtures under `dev/lsp-bench/fixtures/` (the invoker fixtures test Lathe only) | L |
| Rename and imports | `lsp.prepare_rename`/`rename`, `code_action`/`resolve_code_action`; `jdtls_diff.rename_edit_count` | the edit applied to a `git worktree` copy and compiled with the build; `BenchClient` answering `workspace/applyEdit`, through which jdtls may apply an action, and declaring `workspaceEdit.documentChanges` | M |
| A branch switch | `mcp_staleness.s_branch_switch` (MCP only): checkout of an older ref and back, files added or deleted since | an LSP port acting as Neovim on the benchmark's machine: open buffers reloaded; on Linux no `didChangeWatchedFiles`, as Neovim 0.12 offers it only on macOS and Windows (there, sent for `git diff --name-status` where the server registered); `wait_ready`'s polling for "until right" | M |
| A build-file change | `lsp_staleness.s_pom` and `mcp_staleness.s_pom` (they check only that Lathe asks for a sync) | a dependency added from the local repository and a class of it used; `BenchClient` answering each server's prompt as a user clicking yes: Lathe's sync, jdtls's update (`java.configuration.updateBuildConfiguration` asks by default; to be confirmed in its source) | M |
| Running one test (a class and one method), Lathe's replay of the captured launch against nvim-jdtls's java-test and java-debug path | Lathe: `lathe.run.test` as `lathe.nvim`'s test adapter calls it, and the editor smoke's neotest run (`driver.lua`); `mcp_battery`'s `run_test` checks | jdtls: the java-test and java-debug bundles fetched at pinned versions and passed as `bundles`; the java-test command that resolves a test's launch, then a launch through java-debug's debug adapter, with the results read from its output; the build's own run of the test as the expected outcome | L |
| Disk and the project tree | `servers.jdtls_prepare`'s workspace and configuration directories | a `du` after the warm run: jdtls's `-data` and configuration, `.lathe/`; the shared installs apart; `git status --ignored` for the files each server writes into the tree; a report column | S |
| Setup in Neovim | the editor guides | a short prose table | S |

Projects, all Java and well known: spring-petclinic, Dropwizard, Helidon (the annotation-processing project: its processors generate the builders its code calls), Hibernate ORM and OpenMetadata.
Projects mixing in Kotlin or Groovy were left out, as neither server analyses those: Micronaut Core (916 Kotlin, 3,101 Groovy files) and Spring Framework (396 Kotlin), which besides does little annotation processing.
OpenJDK is reported as a project Lathe serves and jdtls does not: on a plain checkout jdtls reports `java.base`'s own packages as conflicting and navigates nowhere.
Apache Fineract leaves the comparison: jdtls's failure to import it comes half from a Fineract plugin that breaks on Gradle 9 when its task is created, which a user can work around.

Time: about 18 hours of an idle machine in all, estimated from the stage 1 runs and the release battery:
the queries at the one heap about 5 h, the build row with test capture and its baseline about 1 h, the editing loop in every run about 4 h, the outside changes in sessions of their own (three per server and project) about 7–8 h, diagnostics against the build about 3 h.
So they are gathered in sessions, one project and one group at a time, as the amendment sets out; building the tooling first is the larger cost, about a week in several pull requests.

Risks to settle while building it:
- jdtls may publish nothing for a clean file (`jdtls.JdtlsClient.open` gives up after 8 s), so "until clean" waits on the publish for the edited version or a quiet period, not on any publish.
- Each server debounces edits its own way; the times are reported as they are, with the time until the error is gone as well as until it appears, as jdtls can publish an intermediate list.
- How jdtls finds outside changes without a client's file watcher (Neovim on Linux): it is expected to fall back on Eclipse's own workspace refresh, to be confirmed in its source; Lathe scans on its own 2 s tick either way and ignores `didChangeWatchedFiles`.
- jdtls's own lint set (unused imports, raw types) has no javac counterpart, so warnings are compared only in the categories the build enables; Error Prone and `-Werror` have none in ECJ.
- The other dev scripts do not run the servers at one heap: `dev/jdtls.py` starts jdtls through its launcher's default, so `jdtls_diff.py` and `lsp_staleness.py` need `-Xmx2G` passed too before their numbers sit beside the benchmark's.

Order: Lathe's build row and completion and diagnostics while typing first (they extend the harness the least and are what a user feels most), then the error in another module, rename and imports, disk; the branch switch, the build-file change and running a test next; diagnostics against the build (stage 2) alongside, as the largest.

## Stage 2 — Where each server disagrees with the build

- Ground truth: compile each project with Maven or Gradle and collect javac's errors and warnings per file.
- Compare each server's diagnostics against them, both ways (errors the build does not report, errors the build reports and the server misses), extending `dev/jdtls_diff.py`.
- Targeted public fixtures for where the two compilers or models can differ: annotation processors (MapStruct, Immutables, AutoValue, Helidon builders), Lombok, JPMS (`opens`, `--patch-module`), `--release` below the JDK with newer APIs, compiler arguments (`-parameters`, `-Werror`, Error Prone), and generic-inference cases where ECJ and javac are known to differ.
- Gradle source sets beyond `main` and `test`, where the two models differ by design (Lathe compiles each against its own classpath; jdtls, through Buildship's Eclipse model, folds them into two visibility levels per project), each a case the build rejects:
  an `integrationTest` source set using a class of `test`, which its classpath lacks;
  `main` code using a `jmh` class, or a library only `jmh` depends on;
  a project consuming `testFixtures(project(":x"))` and using one of `x`'s ordinary test classes;
  two source sets built against different versions of one library, each using an API only its version has.
  By the design ([Gradle Source Sets](lathe-gradle-source-sets.md)), Lathe reports what Gradle reports and jdtls none; stated only once run here.
- Output: a catalogue of disagreements with reproduction steps.
  Lathe's own become gaps to fix; jdtls's become evidence.

Found by stage 1, for stage 2 to settle against the build:
- **EG-096:** on `new X(...)`, Lathe's definition answers the class, jdtls's the constructor.
- References where Lathe answers more than jdtls on Hibernate ORM: `import static` lines of the method, and calls through a subclass of the declaring class.
- jdtls's answers missing or empty on Helidon (implementations of a class; test call sites) and OpenMetadata (references to an overload, implementations of an interface method, a type definition); jdtls on OpenMetadata also starts about 40 processes and 13.5 GB on its first queries.
- References to a field read through a getter: jdtls includes the getter's callers (vscode-java's `java.references.includeAccessors`), so it answers more and slower; a difference of contract, not of speed.

Found so far, before stage 2 started, by the stage 0 setup checks (all Lathe's, all fixed):
- **WS-31:** on OpenMetadata, 98 false errors in a file that compiles against a sibling module's shaded jar, and a sync that could not compile it; fixed in #116 (a shaded jar stays on the classpath, and `lathe.sync.phase` lets the project's sync reach `package`).
- **WS-32:** on Apache Fineract, Lathe's Gradle sync failed: `compileTestJava` writes into the generated-sources directory the main capture reads, and Gradle refuses the undeclared order; fixed in #119.
- **TE-16:** a capture-only sync ran every test class in dry run (in Hibernate ORM's `hibernate-core`, 94 s and 4,953 classes, saturating 16 cores) to capture one launch; fixed in #124.
- **WS-33 and TE-12:** Gradle source sets beyond `main` and `test` had no editor features and their tests no launch; fixed in #127 and #131.

## Stage 3 — Fix wherever Lathe loses

Every loss from stages 1 and 2 becomes a gap, is fixed, and is measured again with the stage 1 harness.

Found by stage 1, in order of priority:
1. **FR-031:** on OpenMetadata, references to and incoming calls of `Entity.getEntityRepository` missed every use outside its own file (22 of about 1,086): a type-use annotation on a parameter (`@NonNull`) made the declaration's descriptor differ from its callers' on JDK 21 and older. Fixed in #136.
2. **FR-029:** references to a method whose name many files spell take 21–28 s (Hibernate ORM's `getId`, Fineract's `Money.of`).
3. **FR-032:** implementations of an interface method and incoming calls take 0.5–4.4 s on large projects (13.5 s with FR-031's method).
4. **WS-17:** with no heap cap Lathe reaches 4–9 GB on the large projects; at 2 GB every answer stays the same and most queries are at most a third slower, Fineract's slowest 76%.
5. Cold start: Lathe needs the project built first (58–152 s on the large projects); the first run without a build is the `:LatheInit` candidate below.

Candidates already known:
- Repeated queries: cache an answer until the workspace changes (FR-029, third fix direction).
- Implementations: compile a module's candidate files in batches instead of one javac task per file.
- Memory: a default heap limit in the launcher, checked on Helidon first.
- Visible missing features: inlay hints (EG-091), member symbol search (EG-092).
- First run without a build (`:LatheInit`, [potential](../potential/lathe-init.md)).

Done when the stage 1 report has no loss left that the team chose to fix, and the rest are written down as losses.

## Stage 4 — The comparison page

Written last, from the final reports, as `docs/guide/` page linked from the README's "Why Lathe".
- The architecture differences, stated as facts.
- The measured table: every row links to its raw results and the command that reproduces it.
- The catalogue of disagreements with the build.
- Where jdtls is better.

The reports hold about 150 values per server and project, some 1,700 in all, too many for a reader.
The page shows two levels, the headline set fixed here before any stage 1b result exists, so it cannot be chosen to flatter either server.

**Headline: ten values per project, both servers side by side**
1. Time to a first answer on a fresh checkout: Lathe's build with test capture plus its ready time, against jdtls's import plus its ready time.
2. Reopening the project: the warm ready time.
3. Memory at the same heap: peak and idle.
4. Navigation: the median latency over the queries, and how many were right.
5. Completion: the median latency, and how many positions offered the expected item.
6. Typing an error: the time until it shows.
7. An error in another module: the time until it shows.
8. Errors against the build: the errors each server misses, and those it reports that the build does not.
9. A branch switch: the time until the answers are right again, or never.
10. Running one test: the time to its result.

Each headline value is the median the reports give; a server that cannot do it on a project shows why, not a blank.

**Below it:** "where jdtls is better", every loss in its own words; the Neovim setup of each, in prose; and per project the full report with every value, its raw results and the command that reproduces it.

## Order

Stage 0 first; stage 1 (spring-petclinic, then the large projects) and stage 2 can run in parallel; stage 3 follows from what they find; stage 4 last.
Each stage is its own pull request.

## Open questions

1. ~~Which public projects represent Lombok and annotation-processor users.~~ Settled 2026-11-11: OpenMetadata for Lombok, Helidon for annotation processing and JPMS; candidates that mix in Kotlin or Groovy (Micronaut Core, Spring Framework) were left out, and a MapStruct application (kafbat/kafka-ui) was set aside, as it needs Node to build and is less known.
2. Whether java-index stays in the comparison beyond spring-petclinic, given it answers only navigation.
3. Which jdtls version to pin, and how often to move the pin.
