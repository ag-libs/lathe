# Lathe — Measured Comparison with jdtls

## Status

**Planned (2026-11-06).** No stage has started.
This document is the plan for answering "why Lathe and not jdtls?" with evidence instead of assertion.

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

## Stage 2 — Where each server disagrees with the build

- Ground truth: compile each project with Maven or Gradle and collect javac's errors and warnings per file.
- Compare each server's diagnostics against them, both ways (errors the build does not report, errors the build reports and the server misses), extending `dev/jdtls_diff.py`.
- Targeted public fixtures for where the two compilers or models can differ: annotation processors (MapStruct, Immutables, AutoValue, Helidon builders), Lombok, JPMS (`opens`, `--patch-module`), `--release` below the JDK with newer APIs, compiler arguments (`-parameters`, `-Werror`, Error Prone), and generic-inference cases where ECJ and javac are known to differ.
- Output: a catalogue of disagreements with reproduction steps.
  Lathe's own become gaps to fix; jdtls's become evidence.

## Stage 3 — Fix wherever Lathe loses

Every loss from stages 1 and 2 becomes a gap, is fixed, and is measured again with the stage 1 harness.
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

## Order

Stage 0 first; stage 1 (spring-petclinic, then the large projects) and stage 2 can run in parallel; stage 3 follows from what they find; stage 4 last.
Each stage is its own pull request.

## Open questions

1. Which public projects represent Lombok and MapStruct (or Immutables) users: an established open-source application rather than a sample, so the numbers mean something.
   Also whether a third-party project beats record-companion and Helidon for JPMS with annotation processing.
2. Whether java-index stays in the comparison beyond spring-petclinic, given it answers only navigation.
3. Which jdtls version to pin, and how often to move the pin.
