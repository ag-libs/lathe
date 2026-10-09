# Lathe Format — a JDK-resilient Java formatter

A fork of [google-java-format](https://github.com/google/google-java-format) (GJF)
whose **primary purpose is to make formatting a first-class part of `lathe-server`**:
re-hosted off `com.sun.tools.javac` internals onto the public Compiler Tree API,
so it needs no `--add-exports` and does not break on JDK updates.
Lives in the `lathe-format` module, bundled by `lathe-server`;
a standalone library and CLI are a secondary by-product.

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
- **Phase 3 — public parse and positions: DONE.**
  Every parse goes through a public `JavacTask`; positions come from `SourcePositions`;
  `Trees`, `Formatter`, `StringWrapper`, `DimensionHelpers`, and the visitor import no internals.
  See [Phase 3 result](#phase-3-result).
- **Phase 4 — caller-supplied unused imports: DROPPED.**
  Measured too small to pay for itself; see [Primary goal](#primary-goal-formatting-inside-lathe).
- **Phase 5 — own lexer: DONE.**
  `JavaLexer` replaces javac's scanner; `JavacTokens` survives only as a test-scope oracle.
  See [Phase 5 result](#phase-5-result).
- **Phase 6 — `RemoveUnusedImports`: DONE.**
  No main code imports javac internals any more. See [Phase 6 result](#phase-6-result).
- **Phase 7 — sever: DONE.**
  Main code compiles with `--release 21` and no `--add-exports`, and runs without them.
  See [Phase 7 result](#phase-7-result).
- **Phase 8 — server integration: NOT STARTED.** See [Plan](#plan).

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

**Goal.**
Primarily: formatting inside `lathe-server` that never breaks on a JDK update —
output as close to GJF as possible, on public APIs only, no `--add-exports`,
soft-degrades on new syntax.
Secondarily: ship the same engine as a standalone library and CLI.

## Primary goal: formatting inside Lathe

### Where formatting time goes

GJF `1.35.0` on Helidon files (JDK 26, median of 30 runs after warmup),
each stage of `formatSourceAndFixImports` — the call `lathe-server` makes — timed on its own:

| File | Lines | Reorder imports | Unused imports | Format | Wrap long strings | Total |
|---|---|---|---|---|---|---|
| `Scheduling` | 312 | 0.7 ms | 1.7 ms | 5.0 ms | 0.0 ms | 5.6 ms |
| `StaticContentHandler` | 1,031 | 0.1 ms | 2.8 ms | 15.9 ms | 15.9 ms | 25.1 ms |
| `ServiceDescriptorCodegen` | 3,127 | 0.6 ms | 3.6 ms | 40.9 ms | 47.0 ms | 89.2 ms |
| `OpenApiDocument` | 4,851 | 0.1 ms | 4.6 ms | 42.5 ms | 52.5 ms | 99.0 ms |
| `HuffmanTables` | 4,927 | 0.1 ms | 6.7 ms | 107.6 ms | 0.2 ms | 115.2 ms |

A bare public `JavacTask.parse()` of the same files takes 3–6 ms.
The cost is layout (break computation, growing with the file) and, only for files with string
literals past the column limit, `StringWrapper.wrap`, which re-parses, runs a second partial
format, and parses twice more for its safety check.
Parsing and unused-import detection are small, flat costs.

### Decision: keep it simple — no reuse of Lathe's attributed tree

Both ways of feeding Lathe's attributed tree into formatting were evaluated and rejected because
the saving is a few milliseconds (the parse, or unused-import detection) against substantial
complexity:

- **Reusing the tree in place of the formatter's parse** (~3–6 ms):
  the formatter would have to survive what attribution adds to the tree,
  the server would have to compile with `-XDallowStringFolding=false`,
  format requests would have to move onto the module's compilation thread,
  and the import pipeline would have to be reordered because the tree only matches the original
  text.
  Probe findings are kept in [Rejected: formatting from the attributed tree](#rejected-formatting-from-the-attributed-tree).
- **Supplying unused imports from the tree** (Phase 4, ~2–7 ms):
  an earlier reading of the timings wrongly attributed `StringWrapper`'s cost to import fixing.
  Semantic detection would also have to walk the whole tree and parse every javadoc comment,
  shrinking the saving further.
  It was started and discarded before any tests were written.

**Future candidates**, not decided, aimed at the real costs and not needing the tree:
background pre-formatting after each debounced compile (a format-on-write request for unchanged
text becomes a lookup), a cheaper long-string wrapping pass, and formatting only the members that
changed.

### Rejected: formatting from the attributed tree

Recorded so the idea is not re-derived from scratch.
Probing `JavacTask.parse()` against the same tree after `analyze()` (JDK 26) showed that
attribution adds default constructors and implicit `super()` calls (end position `NOPOS`)
and a synthesized type for `var` locals and implicit lambda parameters (end `NOPOS`);
record accessors are not added as trees.
javac's parser also folds `"a" + "b"` into one literal unless `-XDallowStringFolding=false` is set,
so the tree would not match the formatter's tokens.
The server keeps per-URI `CachedFileAnalysis(content, version, analysis)` on each module's
single compilation thread, while formatting runs on the event-loop thread.

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

## Phase 3 result

- `Trees.parse` is a public `JavacTask.parse()`
  (`-proc:none`, `--enable-preview -source <running JDK>`, `-XDallowStringFolding=<flag>`,
  a `DiagnosticListener` with GJF's error filter) returning a `ParsedUnit`
  (source text, `CompilationUnitTree`, `DocTrees`) that answers start/end positions, lengths,
  and source slices.
  The reflective end-position handle, `Context`, `JavacFileManager`, `ParserFactory`, and `Options`
  are gone from `Trees`.
- The visitor gets the `ParsedUnit` in its constructor and resolves positions through private
  methods named like the old static helpers, so its call sites are unchanged;
  annotation/modifier ordering moved from `AnnotationOrModifier.compareTo` into the visitor,
  which owns the positions.
- `operatorName`/`precedence` are `Tree.Kind` tables copied from javac's `Pretty.operatorName`
  and `TreeInfo.opPrec`.
- `StringWrapper` is fully public: its AST-equality safety check uses the public tree's `toString()`.
- `RemoveUnusedImports` uses the public parse but still casts to `JCCompilationUnit`/`JavacTrees`
  for its import and javadoc internals (Phase 6).
- No flag could be dropped yet: `api` is still used by `RemoveUnusedImports`, `file` by the lexer.
- Performance is unchanged within noise (Helidon files, same method as
  [Where formatting time goes](#where-formatting-time-goes));
  the per-call public task setup may cost 1–3 ms on small files.
- All 898 tests green on the first run, including the synthesized-node predicates now fed by
  `SourcePositions` (`NOPOS`) instead of the internal handle (empty span).

## Plan

De-internalization order (easy → hard),
each gated by the full suite (golden + Phase 1b unit suites) staying green,
each dropping its own `--add-exports`:

1. **Phase 1b — widen the green bar (DONE).**
   Vendor the upstream unit suites listed under [Test coverage gap](#test-coverage-gap);
   all green against the intact fork.
2. **Phase 2 — Seam ⑤ (flags) + visitor internals + `DimensionHelpers` (DONE).**
   Establishes the per-seam rhythm.
3. **Phase 3 — public parse and positions (DONE)** (seams ②, ④, and the parse half of ③, merged:
   public `SourcePositions` only exist on a `JavacTask`, so positions cannot move before parsing).
   `Trees.parse` becomes a public `JavacTask.parse()` returning the unit with its `DocTrees`;
   positions come from its `SourcePositions`;
   `operatorName`/`precedence` become `Tree.Kind` tables.
   GJF's internal end-position handle reports an empty span (end == start) for synthesized nodes
   where `SourcePositions` reports `NOPOS`; the Phase 2 predicates accept both.
4. **Phase 4 — caller-supplied unused imports (DROPPED).**
   See [the decision](#decision-keep-it-simple--no-reuse-of-lathes-attributed-tree).
5. **Phase 5 — Seam ① tokenizer (DONE)** (`JavacTokens` + `JavaInput`, plus `ModifierOrderer` and
   `ImportOrderer`, which consume its token kinds) — the real rewrite:
   a hand-written Java lexer producing the same flat token stream,
   validated against `JavacTokens` as a standalone token-level oracle
   **before** it can perturb formatting.
6. **Phase 6 — `RemoveUnusedImports` (DONE)**: its `JCImport`/`JCFieldAccess`/`DCTree`/`JavacTrees`
   internals onto `ImportTree`/`MemberSelectTree`/`DocTrees`
   (`StringWrapper` already went public in Phase 3).
7. **Phase 7 — sever (DONE)**: remove the last internal imports, delete all `--add-exports`,
   confirm green.
8. **Phase 8 — server integration**: the formatter is then fully on the public tree API.
   See [Integration into lathe-server](#integration-into-lathe-server).

The differential gate runs before the server swap; distribution comes last.

### Phase 5 result

- **`JavaLexer`** (our code, `io.github.aglibs.lathe.format`, not exported) returns the contiguous
  `LexToken(start, end)` ranges of the source — whitespace runs, comments, literals and text blocks,
  identifiers, numbers, operators, and separators — **cut exactly where javac's scanner cuts them**,
  up to the first lex error (`lex` returns the tokens before it and whether the text was complete).
  It reports **no token kinds**: `JavaInput.buildToks` already classified every range by its text.
  Operators are cut like javac (longest match over its operator set, e.g. `>>>=`, `->`, `::`),
  although `buildToks` splits them into characters: GJF advances its column counter by the whole
  operator's length for each piece, and those columns drive layout heuristics such as tabular
  argument lists.
  Unicode escapes are decoded first (JLS 3.3, including the even-backslash rule) with a map back to
  raw offsets, so escaped comments and identifiers lex correctly and ranges stay raw.
- **Kinds replaced by text.**
  `ImportOrderer` stops at the words `class`/`interface`/`enum` and `ModifierOrderer` switches on
  modifier keywords — reserved words, so text is exact (`Foo.class` included, as before).
  `JavaInput.Tok` lost its kind; a string literal's text is its Unicode-decoded source, because
  javac's decoded `stringVal` is what classified a literal with escaped quotes as a string.
  A lex error after the first stop word is ignored, as javac's scanner never reached it; any other
  lex error makes `buildToks` return the lone EOF token as before, and the parse reports it.
- **Oracle.** `JavacTokens` moved to test scope; `JavacLexOracle` reduces javac's scanner output to
  the same ranges, and
  `TokenOracleTest` requires identical ranges on every golden input and output and a set of tricky
  snippets. With `-Dlathe.format.corpus=<dir>` it also checks a source tree:
  **all 7,160 Helidon files and 6,578 JDK sources (`java.base`, `jdk.compiler`, `java.desktop`)
  match**.
- **One deliberate difference.** Since JDK 23 (JEP 467) javac merges consecutive `///` lines into
  one Markdown doc-comment token; `JavaLexer` keeps one comment per line, like javac 21 and 22.
  The formatted output is the same (GJF re-indents each line of a `//` comment, and the affected
  fixtures `B38241237` and `I1153` pass either way), and it keeps the token stream independent of
  the JDK the formatter runs on. The oracle splits javac's merged comments back into lines.
- **Flags.** Main code now needs only `api`, `tree`, and `util` (all for `RemoveUnusedImports`);
  test compilation keeps `file`/`parser` for the oracle.
- All 1,126 tests green (898 before plus the lexer and oracle tests); performance unchanged within
  noise.
- **Verified on JDK 21, 26, and 27**: `clean verify` green on each (1,122 on 21, where the JDK 25+
  fixtures are version-gated), and the corpus oracle matches on all Helidon and JDK sources on each
  — javac 21's scanner (before `///` merging) and javac 26/27's agree with `JavaLexer`.

### Phase 6 result

- Imports use the public `ImportTree`/`MemberSelectTree`/`CompilationUnitTree`
  (the reflective `getQualifiedIdentifier` work-around is gone; `isModule()` stays reflective
  because it does not exist on JDK 21), and javadoc comes from `DocTrees`.
- **Javadoc references.** The public `ReferenceTree` exposes only `getSignature()`, so the names a
  reference uses are taken from that text with `JavaLexer`: like javac's own walk over its internal
  reference tree, each identifier that starts a (possibly qualified) name in the qualifier and the
  parameter types — not the `module/` prefix or the member name.
  The unused source ranges upstream collected for them are dropped.
- **Parity.** `RemoveUnusedImportsParityTest` compares the fork's `removeUnusedImports` with live
  GJF's byte for byte (or both failing) on every golden input and output and javadoc-reference
  snippets; with `-Dlathe.format.corpus` also on whole trees — **all 7,160 Helidon files and 6,578
  JDK sources match, on JDK 21, 26, and 27**. The corpus listing moved to `CorpusProvider`, shared
  with `TokenOracleTest`.
- Only the test-scope oracle (`JavacTokens`, `JavacLexOracle`) still uses javac internals.

### Test hardening (after Phase 6)

Until here the lexer and import removal were compared with javac/GJF on real code, but the whole
formatter only on the golden fixtures. Added, all comparing the fork with live GJF `1.35.0`
through the server's call (`formatSourceAndFixImports`, Google and AOSP style) and requiring the
same output or the same failure and message:

- `FormatterDifferentialTest` — every golden input, the golden inputs truncated at three offsets
  (the half-typed files an editor formats), named snippets, and with `-Dlathe.format.corpus` whole
  source trees (each file whole and cut in half).
- Shared, named snippets in `CorpusProvider` (operators and compound assignments, unary forms,
  record and enum variants, interleaved annotations and modifiers, unnamed variables, string
  concatenation, javadoc references, and — on JDK 25+ — flexible constructor bodies, module
  imports, implicit classes, primitive patterns). Each runs through all three checks (lexer oracle,
  import parity, formatter differential), and must format successfully in GJF so it cannot pass by
  failing alike. Composed annotations (`@GoldenFixtures`, `@Snippets`, `@CorpusFiles`) keep the
  parameter sources in one place.

**It found three lexer bugs** that the fixtures and the earlier oracles missed, all fixed:

1. **Operator boundaries affect layout.** `buildToks` splits operators into characters but advances
   GJF's column counter by the whole javac operator's length per piece; those columns drive the
   tabular-arguments heuristic. Splitting operators in the lexer changed the layout of `Map.of(...)`
   argument lists in 2 Helidon files. `JavaLexer` now cuts operators like javac, and the oracle
   compares javac's unsplit tokens.
2. **Lex errors after the stop word.** javac's scanner stops at `class`/`interface`/`enum` during
   import reordering, so a later error never counted; the lexer failed on it, skipped the reorder,
   and reported the parse error one line off in 1,067 truncated Helidon files. Lexing now stops
   lazily.
3. **String literals with Unicode-escaped quotes** were classified as operator characters; they are
   now classified by their decoded text, as javac's decoded `stringVal` did.

After the fixes: **all 7,160 Helidon files format identically to GJF** (both styles, whole and
truncated); the lexer oracle and import parity match on all Helidon and JDK sources on JDK 21, 26,
and 27; `clean verify` is green on all three (1,807 tests; 1,788 on 21, where JDK 25+ inputs are
gated).

A private production codebase (2,485 files, kept Spotless-clean with google-java-format) was also
run through the fork with Spotless's pipeline (`formatSource`, remove unused imports, reorder
imports; Google style): **no file changed**, the fork matched GJF `1.35.0` on every file, and the
run used no `--add-exports`.

### Phase 7 result

- Main compilation: the parent's `--release 21`, no `--add-exports`; class files are Java 21.
  A probe importing `com.sun.tools.javac.util.Context` into main code fails to compile.
- Test compilation and runtime keep only what the scanner oracle and the GJF reference need
  (see [Key technical decisions](#key-technical-decisions)).
- `clean verify` green on JDK 21, 26, and 27 (1,788 / 1,807 / 1,807 tests).
- The formatter runs with no access flags at all: the private-codebase run (see
  [Test hardening](#test-hardening-after-phase-6)) used a plain `java -cp`.

## Degradation on new syntax

The fork must not turn "the host JDK knows syntax GJF doesn't" into a broken save.

- **Parsing** comes from the host JDK's own `JavacTask.parse()`, so new syntax always parses
  when the project's JDK supports it.
- **The lexer** is ours. Unknown operator characters already lex as single characters, so only a
  new literal or comment form could break it (rare — the last one was text blocks); a lex error
  leaves the token list empty and the file unformatted.
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

Today `GoogleFormatEngine` calls
`new Formatter(JavaFormatterOptions.builder().style(style).build()).formatSourceAndFixImports(source)`
with `Style.GOOGLE` and `Style.AOSP`.
The fork keeps that API unchanged, so the server side is an engine swap;
it gets its own design approval at Phase 8.

Rollout, in two steps:

1. **Opt-in.** A new `FormatterSpec` engine value (constant in `LatheFlags`, next to
   `FORMATTER_GOOGLE`/`FORMATTER_AOSP`) selects the fork, so it can be dogfooded on the lathe repo
   alongside GJF.
   The `FormatEngine` permit list grows by one; nothing else in `JavaFormatter` changes.
2. **Swap.** Once Phase 7 is done and the differential gate is clean,
   `GoogleFormatEngine` is retargeted to the fork's package,
   the opt-in value is removed again,
   and GJF is dropped from `lathe-server` (it stays a test-scope dependency of `lathe-format` only).
   User-facing configuration (`google`/`aosp`) does not change.
   The fork needs no access flags by then, so the module-qualified ones are deleted from both
   places:
   - `lathe-server/pom.xml` — the `=com.google.googlejavaformat` lines in the Surefire `argLine`;
   - `ServerInstaller.renderLauncherScript` — the two `javacAccessLines(..., "com.google.googlejavaformat", ...)` calls.

   The `ALL-UNNAMED` flags stay (Error Prone).

The first step is a public-API change in `lathe-server` and is gated by its own design approval;
it can be skipped if dogfooding via `LATHE_SERVER_DIR` against a branch build is enough.

## Distribution (secondary)

Not needed for the primary goal; done after server integration.

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

- **Main code: `--release 21`, no `--add-exports`.**
  The release flag also rejects any `jdk.compiler` internal, so the build itself keeps main code
  on the public API (a javac-internal import fails with "package … does not exist").
- **Test code: `source`/`target 21` plus the oracle's exports.**
  The release flag forbids internal exports, which the test-only scanner oracle
  (`JavacTokens`, `JavacLexOracle`) needs, so test compilation uses the compiler plugin's
  `testRelease`/`testSource`/`testTarget` and exports `file`, `parser`, and `util` to the module.
  At test runtime the module gets the same three; the live GJF reference (an unnamed classpath
  dependency) keeps its `ALL-UNNAMED` exports and opens.
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

- **Tokenizer alignment — retired.**
  Our lexer's boundaries must match what javac's scanner produced.
  The oracle shows they do on every fixture, all Helidon sources, and three JDK modules
  (see [Phase 5 result](#phase-5-result)); the only difference is the deliberate per-line `///`.
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

The primary goal is met when:

- `lathe-server` formats with the fork, on any JDK the project uses, with no GJF-targeted access
  flags;
- formatting time stays within noise of the GJF baseline in
  [Where formatting time goes](#where-formatting-time-goes).

The whole project is done when, in addition:

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

- **Make formatting faster later?**
  See the [future candidates](#decision-keep-it-simple--no-reuse-of-lathes-attributed-tree);
  none is planned.

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
