# Lathe Format — a JDK-resilient Java formatter

A fork of [google-java-format](https://github.com/google/google-java-format) (GJF),
re-hosted off `com.sun.tools.javac` internals onto the public Compiler Tree API,
so it needs no `--add-exports` and does not break on JDK updates.
Lives in the `lathe-format` module, bundled by `lathe-server` and publishable standalone.

## Status

- **Phase 0 — baseline fork: DONE** (branch `feat/lathe-format`, commit `c9d0d79d`).
  The intact fork reproduces every GJF golden output byte-for-byte (420/420 tests green).
- **Phase 1 — internals audit: DONE.**
  The full `com.sun.tools.javac` surface is enumerated and categorized into five seams (below).
- **Phase 1b — widen the green bar: DONE.**
  The GJF unit suites that cover the server's actual entry point are vendored and green against the
  intact fork: 898 tests in total (420 golden/reference + 478 upstream).
  See [Test coverage gap](#test-coverage-gap).
- **Phase 2 — flags and visitor internals: DONE.**
  `JavaInputAstVisitor` and `DimensionHelpers` import no javac internals;
  the fork module no longer needs `code`, `comp`, `main`, `model`, or `processing` exported.
  See [Phase 2 result](#phase-2-result).
- **Phase 3+ — remaining seams: NOT STARTED.**
  Cut the seams one at a time onto public APIs, dropping `--add-exports` as each closes.

Work happens in a git worktree at `~/work/git/lathe-format` so `main` stays free for parallel work.
The GJF source and golden fixtures are cloned at `~/work/git/google-java-format` (tag `v1.35.0`),
outside the repo.

## Motivation

GJF parses with javac **internals** (`com.sun.tools.javac.*`), which are strongly encapsulated.
Consequences we feel today:

- **It runs on a JDK we do not choose.**
  The server's in-process javac runs on the workspace's build JDK (`LATHE_JAVA_HOME`, else
  `.lathe/java-home`), so GJF executes on whatever JDK the project uses — including EA builds such
  as the OpenJDK PoC's javac 28.
  Any internal API change in that JDK breaks formatting.
- **We cannot follow GJF's fixes.**
  GJF ships a release chasing each JDK, and its minimum runtime has climbed to JDK 21.
  Lathe pins GJF `1.35.0` to hold its Java 21 floor, so a newer JDK that breaks `1.35.0` has no
  upgrade path short of raising the floor.
- **Module-qualified access flags exist only for GJF.**
  Both `lathe-server/pom.xml` (Surefire `argLine`) and the editor launcher rendered by
  `ServerInstaller` carry `--add-exports`/`--add-opens` lines targeted at the
  `com.google.googlejavaformat` module.
- The `--add-exports` escape hatch is itself on the way out (the JEP 403 trajectory).

Not every access flag goes away.
The `ALL-UNNAMED` exports and opens stay: they serve classpath javac plugins (Error Prone replayed
via `-Xplugin:` on save), not GJF.
The MCP launcher runs everything on the classpath and only has `ALL-UNNAMED` flags, so it is
unaffected.

By contrast, Lathe's own server code (60 files) already uses **only** the public `com.sun.source.*`
Tree API — no internals.
Among Lathe's own runtime dependencies, GJF is the only one coupled to javac internals.

**Goal:** a formatter that stays as close to GJF output as possible but is immune to the
javac-internals treadmill — no `--add-exports`, no per-JDK breakage, soft-degrades on new syntax —
reusable in-process by `lathe-server` and shippable as a standalone library and CLI.

## Approach: fork and de-internalize

Three options were weighed:

1. **Port selected files** — copy GJF's layout engine + visitor, rewrite the rest.
   Large manual reconstruction.
2. **Clean-room rewrite** — re-derive GJF's rules from a harness.
   "Less code" is a myth: the rule count is fixed by Google Java Style,
   and you lose the ability to inherit upstream fixes.
3. **Fork the whole thing and de-internalize it** — **chosen.**

The fork wins because it makes this a **subtraction** problem, not an **addition** problem:
start from a 100%-correct GJF and surgically remove the internals,
rather than rebuilding fidelity from a skeleton.
It keeps GJF's 3,200-line visitor and break engine **unchanged**,
gives the cleanest upstream-merge story,
and — verified in Phase 1 — concentrates the internals in ~4 files.

GJF is Apache-2.0, so this is a legal derivative:
per-file headers are retained, modifications are documented (`lathe-format/README.md`),
and the whole repo is already Apache-2.0.

## Method: harness-first, oracle-driven, gated by a green bar

The guiding rule for the whole project:
**never change a seam without the test harness proving zero behavioral regression.**

- The harness is built and validated **before** any fork code is touched.
  Because an intact fork *is* GJF, it must pass 100% of the golden suite —
  so a red bar at Phase 0 means a bug in the harness, not the formatter.
- GJF is both the **reference implementation** (we read its code)
  and the **differential reference** (we compare output against the live dependency).
  When outputs diverge later, we open the exact GJF code path that produced the difference.
- Every seam-cut is one reviewable change:
  cut → the full suite still green → drop that seam's `--add-exports`.

## Architecture

### Module layout

`lathe-format` is a JPMS leaf module (`module io.github.aglibs.lathe.format`),
dependency-light so it publishes standalone — no dependency on `lathe-core` or `lathe-server`.

- `src/main/java/io/github/aglibs/lathe/format/gjf/**` — the vendored GJF fork
  (derivative, Apache-2.0).
  Package `com.google.googlejavaformat` was renamed to `io.github.aglibs.lathe.format.gjf`.
  A dedicated subtree keeps the derivative clearly separated from our own code
  and lets it coexist on the test classpath with the real GJF dependency (no package collision).
- The CLI front end (`Main`, `CommandLineOptions*`, `*Tool*`, `FormatFileCallable`, `UsageException`)
  and the version template were pruned —
  this module is a library; the standalone CLI will be our own.

### Dependencies

- **guava `32.1.3-jre`** — the one real runtime dependency (pervasive in GJF).
  Candidate to shed later, but kept for now.
- **jspecify**, **error_prone_annotations**, **auto-value-annotations** — compile-only (`provided`);
  the **auto-value** processor runs via `annotationProcessorPaths` (`@AutoBuilder`, `@AutoOneOf`).

### The format pipeline

The subject under test is GJF's own golden contract:
`formatSource(input)` **then** `StringWrapper.wrap(output, formatter)`.
`FormatHarness` exposes two backends through one `FormatSubject` seam:
`googleJavaFormat()` (the live dependency, the reference)
and `latheFormat()` (the fork, the subject under test).

### Test harness (DRY)

All test layers reuse one shared core, so each is a one-liner:

- `FormatSubject` — the pluggable backend seam (`String format(String)`).
- `FormatHarness` — the reference + fork subjects and the shared assertions
  (`assertFormats`, `assertIdempotent`).
- `CorpusProvider` — enumerates the vendored golden pairs,
  version-gating the JDK 21/25 fixtures exactly like GJF's `FormatterIntegrationTest`.
- `GoldenConformanceTest` — 418 parameterized cases (format-matches-expected + idempotent)
  driven by the fork.
- `GoogleFormatReferenceTest` — a smoke test keeping the live reference honest.

Later phases add a **differential layer**: fork vs. live GJF over large real corpora
(helidon, dropwizard), where there is no pre-baked `.output`.

### Test coverage gap

The golden contract is **not** the server's contract.
`GoogleFormatEngine` calls `formatSourceAndFixImports(source)` with `Style.GOOGLE` or `Style.AOSP`,
which adds two passes the golden suite never exercises:
`RemoveUnusedImports` (javadoc-aware, so it touches `DCTree`) and `ImportOrderer`.
The golden fixtures are also Google style only.

So the 420-test bar does not guard seam ① (`JavaInput` also tokenizes for `ImportOrderer`),
seam ④'s javadoc path, or AOSP indentation.
Phase 1b closes this before any seam is cut, by vendoring the upstream unit suites
(renamed like the main sources, run against the fork):

- `ImportOrdererTest`, `RemoveUnusedImportsTest`, `RemoveUnusedImportsCaseLabelsTest`
- `ModifierOrdererTest`, `ArrayDimensionTest`, `JavadocFormattingTest`
- `FormatterTest`, `PartialFormattingTest`, `DiagnosticTest`
- `StringWrapperTest`, `StringWrapperIntegrationTest`, `SnippetFormatterTest`, `ReplacementTest`
- `TypeNameClassifierTest`, `LineRangesToCharRangesTest`, `NewlinesTest`

They stay **verbatim** — JUnit 4 + Truth on the JUnit Vintage engine — so upstream merges apply to
them as cleanly as to the main sources.
About 30 of their cases (including `testFormatAosp` and the `testimports` fix-imports fixtures)
drive the formatter through GJF's `Main`, so the pruned CLI front end is restored in
**test scope only**; the published library still has no CLI.
Skipped: `MainTest` (uses javac internals directly), the CLI-parsing and tool-provider suites, and
`FormatterIntegrationTest` (superseded by `GoldenConformanceTest`).
`FormatHarness` gains a `fixImports` subject per style
so the differential layer compares the exact server entry point, not just `formatSource`.

## The seam map (Phase 1 result)

Every `com.sun.tools.javac` touch, across 10 files and 6 internal packages:

| Seam | Internal classes | Files | Replace with |
|---|---|---|---|
| **① Tokenizer** | `parser.Scanner`, `ScannerFactory`, `JavaTokenizer`, `Tokens.{TokenKind,Token,Comment,CommentStyle}` | `JavacTokens`, `JavaInput` | our own lexer |
| **② Parse invocation** | `parser.{ParserFactory,JavacParser}`, `util.{Context,Options}`, `file.JavacFileManager` | `Formatter`, `JavaInput` | public `JavacTask.parse()` |
| **③ Diagnostics** | `util.{Log, Log.DeferredDiagnosticHandler, JCDiagnostic}` | `JavaInput` | `DiagnosticCollector` |
| **④ Positions / Trees** | `api.JavacTrees`, `tree.{JCTree*, TreeInfo, Pretty, TreeScanner, DCTree}`, `util.Position` | `Trees`, `JavaInputAstVisitor`, `RemoveUnusedImports`, `StringWrapper` | `SourcePositions` / `DocTrees` |
| **⑤ Flag inspection** | `code.Flags`, `tree.TreeInfo` | `JavaInputAstVisitor` | `ModifiersTree.getFlags()` / `getKind()` |

The audit undercounted the visitor: Phase 2 found about 13 touch-points
(flags, tree tags, an internal `TreeScanner`, the any-pattern class, raw start positions),
not 5 — but every one had a public replacement, so the visitor still ported cleanly.
The hard work remains concentrated in the tokenizer.

`ModifierOrderer` and `ImportOrderer` import `Tokens.TokenKind`, but only because
`JavaInput.Tok.kind()` and `JavaInput.buildToks(…, stopTokens)` expose it;
they belong to seam ①, not ⑤, and move with it.

## Phase 2 result

javac records several declaration facts only as internal `Flags` bits.
The public tree exposes none of them, so each became a positional predicate,
probed against `JavacTask.parse()` (including annotated and zero-component forms)
and covered by existing fixtures:

| Internal fact | Public predicate | Covered by |
|---|---|---|
| `Flags.ENUM` (enum constant) | the variable's type has an empty span — javac synthesizes it from the enum name | enum golden fixtures, `partialEnum` |
| `RECORD` + `GENERATED_MEMBER` (component field) | a non-static field of a `RECORD` — the parser rejects explicit instance fields there | `Records`, `I1020`, `I1037` |
| `COMPACT_RECORD_CONSTRUCTOR` | a constructor with no `(` token between its modifiers and its body | `I574`, `Records` |
| `IMPLICIT_CLASS` | a class whose modifiers carry flags but have no source position — javac synthesizes `final` | `InstanceMain` |
| `JCTree.Tag` post-unary / `MINUS` | `Tree.Kind.POSTFIX_*` / `UNARY_MINUS` | unary golden fixtures |
| `JCAnyPattern` | `getKind().name().equals("ANY_PATTERN")` — the constant is absent on the JDK 21 floor | `Unnamed` |

The internal `TreeScanner` became the public `com.sun.source.util.TreeScanner`,
and the unused `VarArgsOrNot.fromVariable` (the only `Flags.VARARGS` use) was deleted.

## Plan

De-internalization order (easy → hard),
each gated by the full suite (golden + Phase 1b unit suites) staying green,
each dropping its own `--add-exports`:

1. **Phase 1b — widen the green bar (DONE).**
   Vendor the upstream unit suites listed under [Test coverage gap](#test-coverage-gap);
   all green against the intact fork.
2. **Phase 2 — Seam ⑤ (flags) + visitor internals + `DimensionHelpers` (DONE).**
   Establishes the per-seam rhythm.
3. **Phase 3 — Seam ④ `Trees.java`** → `SourcePositions`/`DocTrees` (11 refs, one file).
   All start positions in the visitor and `DimensionHelpers` already route through
   `Trees.getStartPosition`, so this is one file.
   Watch synthesized nodes: GJF's internal end-position handle reports an empty span (end == start)
   where `SourcePositions` reports `NOPOS`; the Phase 2 predicates accept both.
4. **Phase 4 — Seam ③ diagnostics** → `DiagnosticCollector`.
5. **Phase 5 — Seam ② parse** → `JavacTask.parse()`.
   Near-free: the public task wraps the same `JavacParser`,
   so for well-formed input it yields an identical tree and end positions.
6. **Phase 6 — Seam ① tokenizer** (`JavacTokens` + `JavaInput`, plus `ModifierOrderer` and
   `ImportOrderer`, which consume its token kinds) — the real rewrite:
   a hand-written Java lexer producing the same flat token stream,
   validated against `JavacTokens` as a standalone token-level oracle
   **before** it can perturb formatting.
7. **Phase 7 — stragglers**: `RemoveUnusedImports` (`DCTree`/javadoc)
   and `StringWrapper` (`Pretty`), case-by-case.
8. **Phase 8 — sever**: remove the last internal imports, delete all `--add-exports`, confirm green.

Then [integration](#integration-into-lathe-server), distribution, and the differential gate.

### Seam ① in detail: the lexer

The tokenizer is the only seam with no public replacement, so it gets its own design.

- **Contract.** Reproduce `JavacTokens.getTokens(source, ...)`:
  the flat list of tokens with `kind`, `pos`, `endPos`, and attached comments
  (style, position, raw text).
  `JavaInput.buildToks` consumes only that, so the lexer can be validated in isolation.
- **Token kinds.** Our own enum, not `Tokens.TokenKind`.
  `JavaInput` only needs a handful of distinctions (identifier, literal, operator, EOF, error);
  the rest is carried as text.
- **Lexer, not parser.** A token scanner is allowed here —
  this is exactly what the formatter needs, and the CLAUDE.md ban on ad hoc Java parsing targets
  LSP features, not a formatter's lexer.
  It does not try to understand structure; nesting comes from the javac tree.
- **Hard cases**, each with dedicated oracle fixtures:
  `>>`/`>>>` (lexed as one token; the visitor already splits them for generics),
  text blocks (incl. `\<newline>` and trailing-space escapes),
  numeric literals (underscores, hex floats, `L`/`f`/`d` suffixes),
  unicode escapes (`\u000a` inside comments), and unterminated comments/strings.
- **Oracle.** `TokenOracleTest` runs both lexers over every golden input and the helidon/dropwizard
  corpora, asserting identical `(kind-class, pos, endPos, comments)` streams.
  It keeps `JavacTokens` alive in **test scope only** until the oracle is retired.

## Degradation on new syntax

The fork must not turn "the host JDK knows syntax GJF doesn't" into a broken save.

- **Parsing** comes from the host JDK's own `JavacTask.parse()`, so new syntax always parses
  when the project's JDK supports it.
- **The lexer** is ours, so a new token class (rare — the last ones were text blocks and `->` in
  `case`) produces an `error` token.
  Policy: abort formatting for the file and return the input unchanged.
- **The visitor** dispatches on the tree; a new `Tree.Kind` reaches GJF's default visit path.
  Policy: fail closed, never emit a partial reformat.
  The input is returned unchanged with a `FINE`-level log line;
  the server already treats "no edit" as a valid outcome.
  A later refinement may format around the unknown node verbatim, gated by the differential
  harness.

"Soft-degrade" therefore means **unchanged file, never corrupted file**.
GJF today fails the same inputs with an exception or an `IllegalAccessError`;
the difference is that ours fails on the host JDK's newest syntax, not on any JDK update.

## Integration into lathe-server

The server-facing contract is exactly what `GoogleFormatEngine` uses today:
`new Formatter(JavaFormatterOptions.builder().style(style).build()).formatSourceAndFixImports(source)`
with `Style.GOOGLE` and `Style.AOSP`.
The fork keeps that API, so integration is an import swap.

Rollout, in two steps:

1. **Opt-in.** A new `FormatterSpec` engine value (constant in `LatheFlags`, next to
   `FORMATTER_GOOGLE`/`FORMATTER_AOSP`) selects the fork, so it can be dogfooded on the lathe repo
   alongside GJF.
   The `FormatEngine` permit list grows by one; nothing else in `JavaFormatter` changes.
2. **Swap.** Once Phase 8 is done and the differential gate is clean,
   `GoogleFormatEngine` is retargeted to the fork's package,
   the opt-in value is removed again,
   and GJF is dropped from `lathe-server` (it stays a test-scope dependency of `lathe-format` only).
   User-facing configuration (`google`/`aosp`) does not change.
   Then delete the module-qualified access flags from both places:
   - `lathe-server/pom.xml` — the `=com.google.googlejavaformat` lines in the Surefire `argLine`;
   - `ServerInstaller.renderLauncherScript` — the two `javacAccessLines(..., "com.google.googlejavaformat", ...)` calls.

   The `ALL-UNNAMED` flags stay (Error Prone).

The first step is a public-API change in `lathe-server` and is gated by its own design approval;
it can be skipped if dogfooding via `LATHE_SERVER_DIR` against a branch build is enough.

## Distribution

- **Library** — publish `lathe-format` to Maven Central with the rest of the reactor
  (`io.github.ag-libs:lathe-format`).
- **CLI** — a thin standalone command (our own, not GJF's `Main`):
  format files in place or stdin → stdout, `--aosp`, `--fix-imports` on by default,
  non-zero exit on a parse error.
  Shipped as a shaded jar; a native image is optional and only worth it if startup matters for
  pre-commit.
  Targets: Spotless `nativeCmd`, pre-commit hooks, CI, and Lathe's own `FORMATTER_COMMAND` engine.
- **Spotless.** Not a goal to ship a Spotless step; `nativeCmd` is enough.

## Differential harness

The compass for residual divergence once the lexer lands, and the long-term regression gate.

- **Corpora**: helidon and dropwizard sources (read in place, never vendored — they live outside the
  repo), plus the lathe repo itself.
- **Subjects**: `formatSource` and `formatSourceAndFixImports`, both styles,
  fork vs. live GJF `1.35.0`.
- **Output**: a byte-identical percentage plus a bucketed report of the first differing line per
  file, so divergences group by cause (comment attachment, lexer boundary, ...) rather than by file.
- **Where it runs**: a dedicated profile, not the default `mvn verify` —
  it depends on corpora outside the repo.
  CI runs it on the lathe repo alone, which is always available.
- **Gate**: the swap in [integration](#integration-into-lathe-server) requires 100% on the lathe
  repo and no unexplained bucket on the external corpora.

## Key technical decisions

- **`source`/`target 21`, not `--release`.**
  Exporting `jdk.compiler` internals is incompatible with `--release`;
  the module overrides the parent's release flag for compilation.
  Restored to `--release 21` at Phase 8, when there is nothing left to export.
- **`--add-exports` targets.**
  During the fork phases, the module name at compile time,
  and both the module name and `ALL-UNNAMED` at test runtime
  (the fork is in the named module; the live GJF reference is an unnamed classpath dep).
  Each seam-cut removes its packages from this set.
- **`module-info` now, not deferred.**
  `lathe-format` is a proper JPMS module from the start;
  `requires` guava + the compile-only annotation modules; `exports` the `gjf.java` API package.
- **Package rename to `.gjf`.**
  Deterministic (a single-token substitution),
  so future upstream merges reapply the same rename and 3-way-merge cleanly.
- **`-Werror` relaxed** for this vendored-heavy module; lint on third-party code is not actionable.
- **Vendored code keeps GJF's style, not Lathe's.**
  The CLAUDE.md coding rules (`final`, `var`, records with compact constructors, ...) apply to our
  own code (harness, lexer, CLI) only; restyling the fork would destroy the upstream-merge story.
  Spotless still formats it (it is GJF-formatted already).

## Maintenance and upstream sync

- Keep GJF as a **test-scope** dependency:
  bumping it reruns the differential harness and lists every output divergence a new GJF version
  introduces — upgrades become mechanical, not a reading exercise.
- Track only GJF's **formatting-logic** changes;
  ignore its **JDK-plumbing** releases entirely (the class of change we opted out of).
- Record the upstream baseline tag (`v1.35.0`);
  merge new GJF into the vendored subtree, reapplying the rename.
  The test-scope GJF version moves together with that baseline tag.

## Risks and residual fidelity

- **Biggest risk — tokenizer alignment.**
  Our lexer's token boundaries (generic `>>` splitting, text blocks, number literals)
  must match what `JavacTokens` produced.
  Mitigated by a standalone token-stream oracle against `JavacTokens` before formatting is involved.
- **Comment attachment.**
  The one place output can structurally diverge from GJF,
  because the fork's comment handling is reproduced on our lexer rather than javac's token stream.
  The differential harness measures it at the byte level.
- **Under-tested server path.**
  Import fixing and AOSP style are invisible to the golden suite;
  Phase 1b exists so the seam-cuts are not flying blind there.
- Realistic outcome: the full suite stays green and the differential corpus lands at 95–99%
  byte-identical — indistinguishable in practice.

## Acceptance criteria

The project is done when:

- `lathe-format` has no `com.sun.tools.javac` import in main or test-main code
  (`JavacTokens` survives only as the test-scope oracle, or is deleted with it);
- it compiles with `--release 21` and its tests run with no `--add-exports`/`--add-opens`;
- the golden suite and the Phase 1b unit suites are green;
- the differential gate passes;
- `lathe-server` no longer depends on GJF, and neither its Surefire `argLine` nor the editor launcher
  carries a `com.google.googlejavaformat`-targeted flag;
- formatting works on a JDK newer than any GJF `1.35.0` supports (validated on the OpenJDK PoC's
  javac 28 build).

## Open questions

- **Opt-in step or straight swap?**
  The opt-in engine value costs a public `LatheFlags` constant that is removed again later;
  dogfooding through `LATHE_SERVER_DIR` might be enough.
- **Shed guava?**
  It is the only runtime dependency and makes the standalone jar heavier;
  removing it touches most vendored files and hurts upstream merges.
  Leaning: keep.
- **Retire the token oracle?**
  Keeping `JavacTokens` in test scope means the test JVM still needs `--add-exports`.
  Leaning: retire it once the lexer has been stable across one GJF upstream merge.

## Non-goals

- Byte-for-byte parity with *future* GJF versions
  (we track behavioral changes deliberately, not automatically).
- A configurable formatter — this stays opinionated Google style (plus AOSP), like GJF.
- Keeping GJF's CLI — the standalone CLI will be our own thin wrapper.

## Licensing

The `gjf/**` subtree and the golden fixtures are derived from google-java-format `v1.35.0`
(Copyright Google Inc., Apache-2.0).
Per-file headers are retained; modifications are documented in `lathe-format/README.md`;
the repo is Apache-2.0.
