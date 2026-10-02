# Lathe — Delegated Formatting via Maven (Spotless)

**Status: potential / exploratory.**
Preferred direction: move formatting **out of the Lathe server process** and delegate it to the
project's own `spotless-maven-plugin`, invoked as `mvn spotless:apply` (expecting
[mvnd](https://github.com/apache/maven-mvnd) for latency). This would eventually remove Lathe's
in-process google-java-format engine, so Lathe depends on no bundled formatter and always matches the
project's exact formatter contract. Builds on the shipped
[workspace-scoped style](../done/lathe-workspace-style.md).

## Goal

Format a Java file through the formatter the project's build already defines, with no formatter
bundled in the server. One source of truth — the project's Spotless config — so Lathe's output can
never diverge from what the project's own `spotless:check` enforces, for **any** Spotless formatter
(google-java-format, eclipse-jdt, palantir, …), not just google/aosp.

## Motivation

Today the server formats in-process with google-java-format, and the shipped style design maps
anything else to `none` (Lathe must never emit a diff `spotless:check` would reject). That leaves
eclipse/palantir projects with no Lathe formatting and keeps a formatter dependency (and its
`jdk.compiler` add-exports) inside the server. Delegating to Maven:

- **Removes the bundled formatter** — no google-java-format on the server module path, no version skew
  with the project's formatter, no "google-only" limitation.
- **Exact project fidelity** — the build and the editor run byte-identical formatting.
- **Covers every Spotless formatter** uniformly.

## Why now plausible: mvnd

A full `mvn` per format is normally too slow (JVM boot + eclipse-jdt init every call, ~2–5s). With a
warm **mvnd** daemon the JVM and formatter stay resident. Measured on equalsverifier (eclipse Spotless):

| Invocation (mvnd, warm) | Wall time |
|---|---|
| `mvn -pl <module> spotless:apply -DspotlessFiles=…/Foo.java`, file already clean | ~0.19s |
| same, forcing a real reformat | ~0.12s |

~0.1–0.2s warm is acceptable even for format-on-save. The design therefore **assumes mvnd**; see
Tradeoffs for the non-daemon fallback.

## Proposed approach

Add a delegated `FormatEngine` that runs the project's Spotless on a single file:

1. Map the file to its module and run, from the workspace root,
   `mvn -pl <moduleRel> spotless:apply -DspotlessFiles=<regex for the file>` (mvnd on `PATH`).
2. Spotless rewrites the file **in place**; read it back and return one whole-document `TextEdit`
   (the existing `JavaFormatter`/fold-preservation path is unchanged — it only consumes edits).

`lathe:sync` already detects Spotless; `style.json`'s `formatter.engine` would become `spotless`
(delegated) whenever the reactor configures a Spotless java formatter — replacing today's
google/aosp/none mapping. No Spotless → `none` (unchanged).

## The two hard problems

### 1. Not a stdin/stdout filter

The current `ExternalCommandFormatEngine` is a stdin→stdout filter. `spotless:apply` reads nothing from
stdin and writes the result **in place by path** (stdout is Maven log noise). So this needs a **new
"in-place file" engine mode**: materialize the buffer at the file path, run Spotless, read it back.

### 2. Dirty buffers vs on-disk files

LSP `textDocument/formatting` operates on the (possibly unsaved) **buffer**; Spotless formats **files
on disk** matched by its `<includes>` (a temp file outside `src/**` won't match). So delegated
formatting has to run against the real path:

- **Format-on-save** fits naturally — the content is being written anyway.
- **On-demand format of a dirty buffer** would require writing the buffer to the real file first (a
  disk side effect before the user saved). Options: restrict delegated formatting to save, or write a
  shadow copy at a matching relative path, or accept the pre-write. This is the main open problem.

## Tradeoffs

- **Depends on mvnd for usability.** Cold daemon (first call / after idle) and plain `mvn` pay full
  startup (seconds) — poor for format-on-save. Needs a clear capability/degradation story when mvnd is
  absent (fall back to `none`? to the in-process engine during a transition?).
- **A subprocess per format** vs a warm in-process call — even at 0.12s it is ~100× the in-process
  google path, and serialized behind the daemon.
- **Couples Lathe to Spotless** specifically (goal name, `-DspotlessFiles`, includes semantics) and to
  Maven; Gradle/other builds get nothing until a parallel path exists.
- **Spotless incremental cache / ratchet** interactions, `spotlessFiles` regex escaping, and
  multi-module `-pl` targeting all need care.
- **Removing the in-process engine** is a net simplification (no bundled formatter, fewer add-exports)
  but trades guaranteed sub-ms formatting for daemon-dependent latency.

## Open questions

1. Delegated formatting for **on-demand dirty buffers** — disable, pre-write, or shadow-copy?
2. **No-mvnd degradation** — fall back to `none`, or keep the in-process google engine as an optional
   fast path during migration?
3. Fully **remove** the in-process google engine, or keep it selectable (`engine: "google"`) alongside
   `engine: "spotless"`?
4. How to surface a **failed/slow** Spotless run (timeout, non-zero exit) without corrupting the buffer
   — reuse the current "leave unchanged on failure" guarantee.
5. **Capability timing** — `documentFormattingProvider` is advertised at `initialize`; mvnd/Spotless
   availability may not be known then. Advertise optimistically and no-op, or probe once?

## Non-goals

- Reimplementing any formatter in-process (the opposite of this direction).
- Non-Maven builds (Gradle) in the first cut.
- A `spotless:print`-to-stdout mode — Spotless has none; in-place is the only contract.
