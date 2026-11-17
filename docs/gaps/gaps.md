# Lathe — Gaps

This is the single active gap registry for Lathe.
Every open gap, across all areas, lives here, follows the shared [gap lifecycle](gap-process.md) (a `Status`), and is discovered and triaged through the [gap workflow](gap-workflow.md).
Resolved (`done` / `non-goal`) gaps move to [gaps-archive.md](gaps-archive.md).
The [2026-11-09 closure audit](audit-2026-11-09.md) records the evidence and corrections through merged PR #145.

## Areas

Each gap keeps its area prefix; the area is the discovery family, not a strict feature taxonomy.

| Prefix | Area | Notes |
|---|---|---|
| `EG-NNN` | exploration | Live-probing of nav, hover, search, completion, code actions, hierarchies, against Helidon, Dropwizard, and the `@Builder`-heavy sample-workspace workspace |
| `FR-NNN` | references | `textDocument/references` scope, failure propagation, coverage |
| `CA-N` | code-action | `textDocument/codeAction` providers |
| `CQ-NNNN` | completion | Completion quality; checked against the completion [expectations](../done/lathe-completion-expectations.md) contract |
| `WS-N` | workspace lifecycle | Workspace freshness and lifecycle: reactor mirror / type-index staleness, source watching, sync prompting, and reload |
| `TE-N` | test execution | Maven test-fork capture, replay launch fidelity, and test-classpath isolation |
| `DB-N` | debug & evaluation | In-process DAP adapter and expression-evaluator scope, fidelity, and coverage |
| `NV-N` | neovim client | The shipped Neovim plugin (`lua/lathe/…`) and its recommended configuration |
| `MC-N` | MCP / agent facade | The in-process `LatheEngine` facade and `lathe-mcp-server` tool surface that exposes Lathe to AI agents |

## Finding the work for a release

The slice for a release is derived, not hand-maintained: every gap with `Status: accepted` (see [gap-process.md](gap-process.md)).
Releases are demand-driven: the accepted work is version-named when it is cut.

```bash
grep -n '^\*\*Status' docs/gaps/gaps.md        # scan active entries
grep -n 'Status: accepted' docs/gaps/gaps.md    # what the next cut is trending toward
```

Entries follow, grouped by area: exploration (EG) below,
then Find References (FR), Code Actions (CA), Completion (CQ), Workspace Lifecycle (WS), Test Execution (TE), Debug & Evaluation (DB), Neovim Client (NV), and MCP / Agent Facade (MC).

---

## Timing Observations

Collected from `FINE` logs during the session.
These are reference data, not gap items.

| Operation | Helidon (332 mods) | Dropwizard (68 mods) |
|---|---|---|
| Server + workspace load | ~3.4s | ~3.6s |
| Type-index full shard load | 333–405ms | 354–447ms |
| Reactor index refresh | 149–218ms | 162–193ms |
| Member-access completion | 33–71ms | 54ms |
| Full-document formatting | 134ms | 187ms |
| Code action response | 178–293ms | 261ms |
| `compile:open` | ~280ms | ~250ms |
| `compile:full` (on save) | — | 79ms |
| References (153 results, 15+ modules) | — | ~4s |


---

## EG-018 — `textDocument/selectionRange` not implemented

**Status: deferred**

**Kind: feature**

### Observed behaviour

The server does not advertise `selectionRangeProvider`, and no handler exists.
Expand-selection and shrink-selection (a common editing keystroke) are unavailable.

The `selectionRange` occurrences in the server source are unrelated: they are the
`DocumentSymbol.selectionRange` and `CallHierarchyItem.selectionRange` fields, not the
`textDocument/selectionRange` feature.

Spec: [LSP 3.17 — Selection Range Request](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_selectionRange).
Params carry `positions: Position[]` (multiple cursors); the response is a `SelectionRange[]` aligned
to those positions, each a linked chain `{ range, parent? }` where every `parent` is a strictly
larger enclosing range. This is request-driven (an explicit keystroke), not ambient, so latency is
not critical. Eclipse JDT LS implements it (`SelectionRangeHandler`, PR #1101) by chaining ranges
built from the AST, with extra handling for line/block comments — so this is parity work.

### Root cause

`LatheLanguageServer.initialize` registers no `selectionRangeProvider`, and there is no
`selectionRange` request handler.

### Proposed fix

Implement `textDocument/selectionRange` syntactically.
For each requested position, walk the enclosing `TreePath` from the leaf outward and emit a nested
chain of `SelectionRange` entries (identifier → expression → statement → block → member → type).
This needs only source positions, not type resolution, so it can run on the parsed tree without a
full attribution pass. Emit strictly-increasing ranges (dedup identical spans), and — as jdtls does
— handle a cursor inside a comment, which is not an AST node and would otherwise have no enclosing
leaf to walk from.

### Client integration (Neovim) — low marginal value here

Unlike EG-017, this needs no plugin wiring, and its payoff in Neovim is limited:

- Neovim 0.12 exposes it built-in via `vim.lsp.buf.selection_range(direction, timeout_ms)` (positive
  expands, negative shrinks) plus visual-mode `an`/`in` text objects; pre-0.12 users used the
  `nvim-lsp-selection-range` plugin. A coder just binds keys — no `lathe.lua` change required beyond
  advertising the capability.
- Crucially, Neovim's `an`/`in` uses **Treesitter as the primary provider and LSP only as a
  fallback**. Lathe's `ftplugin/java.lua` already starts the Java Treesitter parser, so users already
  have expand/shrink selection today; the LSP version would rarely be reached. This is why EG-018 is
  a weaker candidate than EG-017 (which filled a genuinely absent feature and auto-upgraded
  vim-illuminate users).

### Probe commands

Not probeable through `explore.py` (no `selectionRange` command); confirmed by the absent
capability and the absent handler in `LatheLanguageServer`.

### Regression targets

- `SelectionRangeTest.selectionRange_insideExpression_returnsNestedSyntacticRanges`
- `SelectionRangeTest.selectionRange_atMethodName_expandsToMemberThenType`

---

## EG-020 — Info-file structural features shipped, but package Javadoc folding remains missing

**Status: deferred** (2026-11-15: feature freeze)

**Kind: feature**

### Delivered

`DocumentSymbolScanner` emits module and package symbols.
`TokenScannerModuleInfoTest` and `TokenScannerPackageInfoTest` cover their namespace tokens.
`FoldingRangeScannerTest.scan_moduleInfo_returnsModuleBodyRegion` covers the module body.
The finer outline of module directives remains EG-083.

### Remaining — package Javadoc folding

Reopened by the 2026-11-08 closure audit: the earlier archived entry promised a fold for the leading package Javadoc, but cited `FoldingRangeTest.foldingRange_packageInfo_returnsJavadocRange`, a proposed test that does not exist.
`FoldingRangeScanner` adds Javadoc folds only when visiting classes, methods and variables; it never visits a package declaration for this purpose.
A probe through `SourceAnalysisSession.files().foldingRange` on the current build confirms that this source returns `[]`:

```java
/**
 * Package documentation.
 */
package com.example;
```

The module-body control returns a region fold.
The original proposal also mentioned folding leading module/package annotation groups and module-directive groups; those refinements have no dedicated handling or regression coverage and remain unverified, rather than being claimed delivered.
Package hover/definition from the original observation was never part of the verified closure.

### Regression targets

Delivered: `DocumentSymbolScannerTest.scan_moduleInfo_noDirectives_returnsModuleSymbol`,
`DocumentSymbolScannerTest.scan_packageInfo_returnsPackageSymbol`,
`FoldingRangeScannerTest.scan_moduleInfo_returnsModuleBodyRegion`,
`TokenScannerModuleInfoTest.semanticTokens_moduleInfo_requiresModuleName_isNamespace`,
`TokenScannerPackageInfoTest.semanticTokens_packageInfo_packageName_isNamespace`.
Package-Javadoc folding: no regression test yet; the probe above reproduces the remainder.

---

<!-- EG-049..EG-055 were discovered by differential testing against jdtls (dev/jdtls_diff.py; see
docs/done/lathe-jdtls-differential-testing.md) on a real multi-module reactor. Each is a behaviour
where jdtls returns a result Lathe does not, or the two disagree substantively. -->

---

## EG-050 — Javadoc `{@link}` / `{@code}` regions not resolved for references, highlight, completion

**Status: deferred (references/highlight shipped; completion deferred).**

**Kind: feature**

### Delivered — references, highlight, and rename over Javadoc references

`textDocument/references`, `textDocument/documentHighlight`, and rename now cover a symbol's Javadoc
reference-tag mentions (`{@link #of(Class)}`, `{@linkplain}`, `@see`, `@throws`), for both members and
types; and go-to-definition / hover resolve a reference under the cursor (hover is the archived
EG-003). Implemented via javac's structured `DocTrees` — the comment is read as a parsed
`DocCommentTree`, never by scanning its text: a shared `JavadocReferences.forEachReference` delivers
each reference resolved to an element + source span, consumed by `JavadocReferenceResolver` (cursor →
element) and `ReferenceLocator.scanJavadocReferences` (match + emit).

Rename can also be started from a `{@link}` to a workspace type. It only proceeds when the identifier
under the cursor names the target, so Javadoc prose, comments, and keywords are refused instead of
renaming the enclosing class.

Regression targets:
`ReferenceLocatorTest.javadocLinkMentions_includedForMemberAndType`,
`HoverTest.hover_javadocLinkTags_resolveReferencedTypeAndMember`,
`RenameProviderTest.resolveRenameTarget_cursorNotNamingTarget_isRefusedButJavadocLinkTargetsLinkedType`.

### Remaining — completion inside `{@link}` / `{@code}` (deferred)

`textDocument/completion` still returns nothing inside a `{@link …}` / `{@code …}` tag, where jdtls
offers type/member completion. This is the one part that cannot use structured `DocTrees`: an
in-progress tag in a not-yet-parseable comment needs detecting "the cursor is after `{@link `" from the
raw comment text — the comment-text parsing deliberately deferred here. A separate, larger slice.

```bash
python3 dev/jdtls_diff.py --methods completion <ws>/.../Api.java
```

---

## EG-053 — Multi-line comment / Javadoc blocks are not folded

**Status: deferred (multi-line Javadoc folding shipped; non-Javadoc comments deferred).**

**Kind: feature**

### Delivered — multi-line Javadoc blocks fold

`FoldingRangeScanner` now folds a declaration's leading multi-line Javadoc (`/** … */`) as a
`FoldingRangeKind.Comment` range spanning the `/**`…`*/` delimiters. Javadoc is reachable without a
token stream: `Trees.getDocCommentTree(path)` attaches the doc comment to its class / method / field
declaration node, and the reported content span is widened outward to the enclosing delimiters.
Import-group, class-body, and method-body folds continue to work.

Regression targets:
`FoldingRangeScannerTest.scan_multilineJavadocOnDeclarations_returnsCommentFoldsSpanningDelimiters`,
`FoldingRangeScannerTest.scan_singleLineJavadocAndNonJavadocComments_returnNoCommentFold`.

### Remaining — non-Javadoc comments (deferred)

jdtls also folds plain multi-line block comments (`/* … */` not attached to a declaration) and runs of
consecutive single-line `//` comments. These are **not** folded: a free-floating comment is not an AST
node and is not reachable through `getDocCommentTree`, so a fold pass would need javac's token/comment
stream (not regex, per the no-ad-hoc-parsing constraint). Single-line Javadoc and single-line/plain
comments correctly produce no fold today (asserted by the second test above).

```bash
python3 dev/jdtls_diff.py --methods foldingRange <ws>/.../Api.java
```

---

## EG-057 — Symbol search matches CamelHumps only in reactor types, not in JDK and dependency types

**Status: deferred** (by design since EG-005; the docs that claimed more are corrected).

**Kind: feature**

### Observed behaviour

On Dropwizard, through both `workspace/symbol` and MCP `search_symbols`:

| Query | Result |
|---|---|
| `DiSuRe` | `DiscoverableSubtypeResolver` (reactor) — humps match |
| `ConcurrentHashMap` | found (JDK), by prefix |
| `ConcHashMap` | nothing: no humps match for a JDK type |
| `CHM` | `CHMaxFragmentLengthConsumer`, `CHMaxFragmentLengthProducer` (JDK, by prefix), not `ConcurrentHashMap` |

For reactor types, all-capitals queries are humps patterns too (`ASF` finds `AbstractServerFactory`).

### Root cause

Not a bug: `WorkspaceTypeIndex.searchCamelCase` filters to `reactorBinaryNames` on purpose.
EG-005 added humps as a layer on top of the prefix search for reactor types only, so it would not
touch the prefix contract that completion and the import quick fix rely on.
JDK and dependency types match by name prefix only.

The defect was in the docs: the README, `docs/status.md`, `docs/guide/ai-agents.md` and the
`search_symbols` tool's `query` description said CamelHumps worked "across reactor, dependencies,
JDK". They now say prefix everywhere, CamelHumps for reactor types.

### If it is ever wanted

Humps over JDK and dependency types means scanning their whole index for every query, which the
reactor-only layer avoids (it runs on each keystroke of an editor's symbol picker).
Measure that cost on a large workspace first, and keep it out of `search()` so completion and the
import quick fix keep their prefix contract.
IntelliJ's Search Everywhere matches humps everywhere, so the demand would come from there.

### Regression targets

None: the behaviour is unchanged; only the docs and the tool description were corrected.

---

## EG-063 — A dependency without a sources jar has no source to navigate to

**Status: deferred** (triaged 2026-11-03: low severity for the editor)

**Kind: feature**

**Value: low — Size: L**

Found by the agent-tooling landscape review (2026-10-24).

### Gap

Definition, hover javadoc and the MCP location answers rely on extracted sources jars.
A dependency that publishes none leaves an editor and an agent with nothing to read.
IntelliJ's MCP `read_file` serves decompiled classes, LSP4J-MCP has `decompile_class`, and vscode-java
decompiles too.

### Fix direction

A decompiler fallback (for example an embedded Vineflower) writing a read-only source into the cache
for a class with no source; mark such answers as decompiled.

### Regression targets

None yet.

---

## EG-064 — No code lens: reference counts and run/debug actions above declarations

**Status: deferred** (triaged 2026-11-03: low severity for the editor)

**Kind: feature**

**Value: low — Size: L**

Found by the agent-tooling landscape review (2026-10-24).

### Gap

jdtls offers reference and implementation counts as code lenses, and vscode-java 1.56 added them for
fields. Run and Debug lenses above test methods and `main` would also give the VS Code client run/test
without client-side work; today its run/test is Neovim-only.

### Fix direction

`textDocument/codeLens` from the runnables and the reference counts, resolved lazily
(`codeLens/resolve`) so a large file does not pay for every count up front.

### Regression targets

None yet.

---

## EG-067 — The first build floods the log with warnings

**Status: deferred** (both warnings are documented as expected in the [test-capture guide](../guide/test-capture.md) and the README)

**Kind: bug**

**Value: medium — Size: S**

Found by the pre-release probes (2026-10-27) on EqualsVerifier.

### Observed behaviour

The README quick start (`mvn clean test -Dlathe.capture.only=true`) prints 56 `[WARNING]` lines on
EqualsVerifier, a small reactor (2026-11-15):

- 21 are Surefire's `Tests run: N, Failures: 0, Errors: 0, Skipped: N`, one per test class, because
  capture-only excludes every test and Surefire reports skipped classes as warnings.
  They come from the Kotlin, `-21`/`-25`/`-26`, mockito and signedjar modules;
  `equalsverifier-core` and `equalsverifier-test` report `Tests run: 0, Skipped: 0` and no warning.
- 27 lines, 9 blocks of `The following dependencies could not be resolved at this point of the build but seem to
  be part of the reactor`, printed before `lathe:sync` on the parent.
  `lathe:sync` is an aggregator with `requiresDependencyResolution = TEST`, bound to the parent's
  `process-test-classes`, which runs before any child module is built.
  The sync itself is complete.
- 3 are maven-compiler's `I/O error reading dependency state` after `clean`, not Lathe's.

A first-time user sees a wall of warnings from a command the README told them to run; a Helidon-size
reactor prints thousands.

### Fix direction

Surefire's warnings: set `surefire.printSummary=false` in capture-only runs, which should drop the per-class lines
but likely not the final results line (unverified), or exclude every test instead of JUnit's dry run, which reports
`Tests run: 0` but gives up what the dry run keeps (a `@Suite` and Develocity see the tests).
The reactor warnings: bind the sync to the reactor's last project, so everything is built when it resolves, or
resolve its dependencies through Lathe's own resolver instead of Maven's aggregator resolution.
Either needs a probe on a real reactor (EqualsVerifier) with this build's extension.

### Delivered

The other half of this entry, a disabled Lathe saying nothing: with `CI` set or `lathe.disabled=true`, the Maven extension
and the Gradle settings plugin now say once that Lathe is off and which switch did it (`LatheFlags.disabledNotice`).

### Regression targets

- `LatheFlagsTest.disabledNotice_explicitSwitchOrCi_namesIt_noneWhenOn`
- `LatheSettingsPluginFunctionalTest.apply_switches_commandLineWinsThenDotLatheThenCiAndGradleProperties`

---

## EG-069 — Deeply nested code that javac compiles ends the server with a stack overflow

**Status: deferred.**

**Kind: bug**

Decision (2026-10-30): not before the release; such code is rare outside generated sources, and 0.1.24 does the same.
**Affects: the editor and MCP.**

Found by the pre-release fuzzing (2026-10-29) on EqualsVerifier.

### Observed behaviour

Valid code nested deeply enough overflows the stack of the thread analysing it.
A `StackOverflowError` is a `VirtualMachineError`, which ends the server by design, so the server exits:

- a call chain about 700 long (`new StringBuilder().append(1).append(1)…`), verified on main (2849e785);
- by the fuzzing report, an `if`/`else if` chain about 1000 long, or about 3000 terms joined with `+`.

It fires when such a file is opened or edited (the live analysis), and on MCP `outline` and `describe_symbol`;
the MCP server exits with status 1.
The write pass and `verify_change` compile such a file without overflowing.
javac itself compiles it.

The overflowing frames are Lathe's own tree walk, `TokenScanner.visitMemberSelect` under `WrittenSourceScanner.scan`, on top of javac's attribution.
Lathe's compile workers are plain threads with the JVM's default stack (1 MB on 64-bit Linux),
while command-line javac runs on the main thread, which gets about 8 MB.

Hand-written code rarely nests this deep;
generated sources can (long builder or `append` chains, large concatenated string constants, big dispatch `if` chains).

### Fix direction

Give the compile workers a larger stack:
`CompilationWorker` creates its threads itself, and the `Thread` constructor takes a stack size (reserved address space, committed only as used).
Size it from javac's main-thread headroom and measure the deepest case above.
Keep `StackOverflowError` fatal, in line with the fail-fast policy until GA.

### Regression targets

None yet.

---

## EG-071 — Highlighting a package name in `module-info.java` highlights every segment, elsewhere too

**Status: deferred** (triaged 2026-11-03: low severity for the editor)

**Kind: bug**

**Value: low — Size: S**

**Affects: the editor.**

Found by the pre-release test rounds (2026-10-29/30) on Helidon.

### Observed behaviour

Document highlight on a package name in an `exports` clause highlights each dotted segment on its own,
also inside another package's `exports` (a `…type.spi` beside `…type`) and in the module's own name.
0.1.24 behaves the same.

### Fix direction

Highlight a qualified package name as one unit, and only where the same package is named.

Also (exploratory round, 2026-11-04): `find_references` on a package segment in `exports` returns every segment of the module and package names (EqualsVerifier, Helidon `exports io.helidon.webserver.spi`); the rename form is FR-027.

### Regression targets

None yet.

---

## EG-072 — Malformed requests and unusual URIs get internal errors or are accepted silently

**Status: deferred** (triaged 2026-11-03: low severity for the editor)

**Kind: bug**

**Value: low — Size: M**

**Affects: the editor.**

Found by the pre-release test rounds (2026-10-29/30) on EqualsVerifier (fuzzing).

### Observed behaviour

None of these ends the server, but each answers wrongly or logs a SEVERE line:

- missing or null params (hover without params, references without a context, `workspace/symbol` with a null query, a command without arguments): `NullPointerException` or `ClassCastException`, internal error, SEVERE;
- a position past a line's end or a huge line number: internal error (`bitIndex < 0`);
- range formatting with its start after its end, or on code that does not parse: SEVERE, though the reply is normal;
- a `didChange` with an older version than the document's is applied, not ignored;
- a URI with lowercase `%xx` escapes gets its diagnostics under the canonical URI, not the one the client sent;
  a raw unencoded non-ASCII URI fails with "Bad escape".

0.1.24 behaves the same.

### Fix direction

Validate params at the protocol edge and answer `InvalidParams`; clamp positions to the document;
ignore stale versions; publish diagnostics under the URI the client used for the document.
`LatheWorkspaceService.rethrowInvalidParams` already maps `IllegalArgumentException` to `InvalidParams` for some commands.

### Regression targets

None yet.

---

## EG-077 — No "go to test": jump from a class to its test, or create one, and back

**Status: deferred** (server and Neovim shipped; the VS Code command waits until VS Code client work resumes)

**Kind: feature**

**Value: high — Size: M**

**Affects: the editor (Neovim first, then VS Code).**

Requested in daily use (2026-10-31), as IntelliJ's Navigate → Test (`Ctrl+Shift+T`).

### Gap

From a main class there is no quick way to open its test.
`:LatheNew` → Test creates one through the full new-type dialog (and lists the main location first, see [NV-9](#nv-9--lathenew--test-from-a-main-source-file-lists-the-main-location-first)),
but nothing finds an existing test, and nothing goes from a test back to the class it tests.

### Wanted behaviour

One key on a main class (or anywhere in its file):

- one test found: open it;
- several (`FooTest`, `FooIT`, `FooTests`): pick from a list;
- none: offer "Create test `FooTest`", prefilled with the class's package in the module's test root, then open it
  with the caret in the new test (what `lathe.types.create` already renders for a Test);
- on a test class, the same key goes to the class it tests.

### Fix direction

Editor-neutral first: a server command (beside `lathe.tests.sources` and `lathe.types.create`) that, for a file,
returns its counterparts and the create arguments when there are none.
Candidates come from the module's real source roots, not path strings: test classes in the same package named
by the build's own conventions (Surefire's and Failsafe's default includes, or the captured `test-filter.json`
when the module has one), then, as a fallback, test classes that reference the type (the reference index).
The reverse direction strips the same suffixes and looks in the main roots.
Then a Neovim mapping and command over it, and the same in VS Code.

### Delivered — server and Neovim

`lathe.tests.counterparts { uri }` answers the counterparts of the file's top-level type, best first: from a main file
its tests, from a test file the type it tests. They are found by Surefire's and Failsafe's default test names
(`FooTest`, `FooTests`, `FooTestCase`, `FooIT`, `FooITCase`, `TestFoo`, `ITFoo`) through the type index, so a test in
a separate test module is found too, ranked same package, then same module, then name convention.
The module's `test-filter.json` is not used: its patterns say what the build runs, not what a class is named after,
and a reference-index fallback was left out until the names miss real tests.
Neovim's `:LatheGoToTest` (suggested `grT`) opens one, picks among several, and with none offers the `:LatheNew test`
step in the file's package.

### Remaining — VS Code

A VS Code command over the same server command.

### Regression targets

`TestCounterpartsTest.testCounterparts_mainAndTestFiles_rankedTestsAndTheirSubject`,
`TestCounterpartsTest.testCounterpartNames_conventions_testNamesAndTheSubjectTheyStripTo`,
`new_spec.lua` (`:LatheGoToTest`).

---

## EG-091 — No inlay hints

**Status: deferred** (2026-11-15: feature freeze)

**Kind: feature**

**Affects: the editor.**

Found by the comparison with [java-index](https://github.com/slgobinath/java-index) (2026-11-05), which offers them.

### Gap

The server does not advertise `inlayHintProvider`, and no handler exists.
java-index shows parameter names at call sites, the type of a `var` local, lambda parameter types and the types in a member chain.

### Fix direction

Answer `textDocument/inlayHint` for the requested range from the open file's attributed tree: javac knows each argument's parameter, each `var`'s type and each lambda parameter's type, so the hints are exact.

### Regression targets

None yet.

---

## EG-092 — Symbol search finds types only, not methods and fields

**Status: deferred** (triaged 2026-11-09: beyond jdtls, which by its settings searches types, and source methods only behind `java.symbols.includeSourceMethodDeclarations` — not measured; the per-keystroke cost is the open risk)

**Kind: feature**

**Affects: the editor's workspace symbols and MCP `search_symbols`.**

Found by the comparison with [java-index](https://github.com/slgobinath/java-index) (2026-11-05), whose symbol search covers classes, methods and fields across sources and libraries.

### Gap

`workspace/symbol` and `search_symbols` match type names only.
A method or field is found only by first finding its type.

### Fix direction

See [Fuzzy Method-Name Symbol Search in the Reactor](../potential/lathe-reactor-method-symbol-search.md) for the reactor;
dependencies and the JDK would need members in the type-index shards.

### Regression targets

None yet.

---

## EG-104 — Hover shows no usage count

**Status: deferred** (2026-11-15: feature freeze)

**Kind: feature**

**Affects: the editor's hover and MCP `describe_symbol`.**

Split from EG-093, whose overrides and deprecation notes shipped.
The comparison with [java-index](https://github.com/slgobinath/java-index) (2026-11-05) also showed how often a symbol is used.

### Fix direction

Add the count once a reference count is cheap to compute, which today it is not (FR-029).

### Regression targets

None yet.

---

## EG-094 — A deprecated API's use does not suggest its replacement

**Status: deferred** (2026-11-15: feature freeze)

**Kind: feature**

**Affects: the editor's diagnostics and code actions.**

Found by the comparison with [java-index](https://github.com/slgobinath/java-index) (2026-11-05), which reports uses of `@Deprecated` APIs with the replacement the javadoc names.

### Gap

Deprecated members are struck through by semantic tokens, and javac warns about them only when the build enables `-Xlint:deprecation`.
Nothing names the replacement.

### Fix direction

For a use of a deprecated element, read its javadoc's `@deprecated` block through `DocTrees`, and offer the `{@link}` it names in the diagnostic, or as a quick fix when the replacement is a drop-in.

### Regression targets

None yet.

---

## EG-095 — No Spring Boot or Quarkus configuration-key navigation

**Status: deferred** (2026-11-15: feature freeze)

**Kind: feature**

**Affects: the editor and MCP.**

Found by the comparison with [java-index](https://github.com/slgobinath/java-index) (2026-11-05), which links keys in `application*.properties` and `.yml` both ways with the Java that reads them
(`@Value`, `@ConfigurationProperties`, `@ConfigProperty`, `@ConfigMapping`, `getProperty("…")`), shows per-profile values on hover, and has an MCP `java_config` tool.

### Gap

Lathe does not read configuration files; a key and the code that binds it are connected only by grep.

### Fix direction

Design first: the resource roots are already captured; the binding annotations are attributed by javac, so the Java side is exact.
Decide the scope (which frameworks, relaxed binding, profiles) before any code.

### Regression targets

None yet.

---

## EG-107 — On a Gradle build that declares no formatter, `:LatheFormat` says only that formatting failed

**Status: deferred** (2026-11-16)

**Kind: bug**

**Value: medium — Size: S**

**Affects: Neovim's `:LatheFormat` and format-on-save on a Gradle build that applies Spotless without a `lathe-style.json`.**

Found by the pre-release JUnit editor round (2026-11-15).

### Observed behaviour

On JUnit, `:LatheFormat` in any Java buffer shows only Neovim's own "[LSP] Format request failed, no matching language servers."
The sync knew why and said so, but only in the Gradle output: Spotless formats this build, but Lathe cannot read its settings on Gradle, so the build should declare the formatter Spotless runs in `lathe-style.json`.
That warning's example also suggests `{"engine": "google"}`, though JUnit's Spotless runs Eclipse.

### Fix direction

Tell the user in the editor why there is no formatter and how to declare one, when they ask to format.
Let the sync's example name the engine Spotless runs when the build says which.

### Progress

Done (2026-11-16): the server says at `initialize` why it does not format (`capabilities.experimental.latheFormatterUnavailable`, the reason MCP's `format_file` gives), and `:LatheFormat` shows it instead of Neovim's "no matching language servers" when no attached server formats the buffer; the reason now names the Gradle case too.
The editor exploratory round (2026-11-16) hit the same message on a Maven build with no formatter at all.
Formatting a selection with a whole-file engine (`command`), which advertises no range formatting, now says the formatter formats whole files only.
Left:

- The Gradle sync's example still suggests `{"engine": "google"}` whatever Spotless runs.
- The reason is the one at startup: after a sync resolves the formatter, `:LatheFormat` still says to run the sync until Lathe restarts (the server already says to restart).
  Keeping it current needs a way to tell the editor after `initialize` (a notification, or a command the client asks), which no existing message carries.
- VS Code reads no `latheFormatterUnavailable`: the extension has no formatting code of its own, so it still shows VS Code's own answer.

### Regression targets

`LatheLanguageServerTest.createCapabilities_noFormatter_saysWhyInExperimental`, `initialize_pinnedFormatterUnresolved_saysToSync`; `formatter_setup_spec.lua` `format(): no formatter shows the server's reason`, `format(): a range with a whole-file formatter says it formats whole files`, `format(): a name filter leaves the other formatter out`.

---

## EG-109 — `:LatheNew` cannot create a type in a Gradle source set beyond `main` and `test`

**Status: documented**

**Kind: bug**

**Value: low — Size: M**

**Affects: `:LatheNew` from a file in a source set such as `testFixtures`, `jmh` or `integrationTest`.**

Found by the pre-release JUnit editor round (2026-11-15).

### Observed behaviour

From `junit-platform-engine/src/testFixtures/java/org/junit/platform/fakes/TestDescriptorStub.java`, `:LatheNew class` opens the global "Where:" picker (163 rows) instead of a name prompt in `org.junit.platform.fakes`, and none of its rows is a `testFixtures` or `jmh` destination.
`lathe.workspace.context` answers `null` for such a buffer.
From a `main` file it prompts in the file's package and creates the type.

### Cause (verified against the code, 2026-11-16)

The exclusion is deliberate: `SourceLayout.resolveContext`, `destinations()` and `packages()` drop every source-set tree (`classes-<set>`, `LatheLayout.SOURCE_SET_TREE_PREFIX`), noting that such a tree "has no place in the create-type scopes yet".
`SourceScope` has only `MAIN` and `TEST`, and `sourceRootFor` and `createType` work by scope, so neither the context nor a destination can name a source set.
`lathe-neovim/runtime/lua/lathe/new.lua` then falls back to the global picker on the `null` answer.

### Fix direction

Answer the workspace context, and list destinations, for every source-set tree, as for `main` and `test`:
a destination needs a way to name its source set, beyond the two `SourceScope` values, not only the filter removed.
The same `null` context hides a source-set file's module from neotest's discovery (TE-18).

### Regression targets

None yet.

---

## EG-110 — Definition on a parameter type in a Javadoc `@see` goes to the method, not the type

**Status: documented**

**Kind: bug**

**Affects: go to definition on a parameter type inside a Javadoc member reference (`@see #m(Type)`, `{@link #m(Type)}`).**

Found by the editor exploratory round on jdbi (2026-11-17).

### Observed behaviour

In jdbi's `Jdbi.java`, line 362 reads `@see #withHandle(HandleCallback)`.
Definition with the cursor on `HandleCallback` lands on `Jdbi.java:410`, the `withHandle` method, as it does on `withHandle` itself.
The same type in code (`withHandle(HandleCallback<R, X> callback)`) goes to `HandleCallback.java:22`.

### Fix direction

Inside a reference's parameter list, resolve the type under the cursor, as for a type in code.

### Regression targets

None yet.

---

## EG-111 — Signatures drop type-parameter bounds and `throws` clauses

**Status: documented**

**Kind: bug**

**Affects: the rendered signature of `describe_symbol` and `analyze_change` (MCP), and probably hover, which renders the same way (unverified).**

Found by the MCP exploratory round on jdbi (2026-11-17).

### Observed behaviour

jdbi's `Jdbi.withHandle` is declared `public <R, X extends Exception> R withHandle(HandleCallback<R, X> callback) throws X`.
`describe_symbol` and `analyze_change` render it as `public <R, X> R withHandle(HandleCallback<R, X> callback)`: the bound `extends Exception` and `throws X` are gone.
The JDK's `DriverManager.getConnection(String url)`, declared `throws SQLException`, renders without it too.
An agent reading the signature to write a call cannot see the checked exception it must handle.

### Fix direction

Render each type parameter's bounds and the method's thrown types, as javac's own signature does.

### Regression targets

None yet.

---

# Find References Gaps (FR)

Active references and rename gaps.
Resolved FR entries are in [gaps-archive.md](gaps-archive.md).

## FR-019 — Renaming one member of a statically imported shared name is refused rather than done

**Status: deferred** (triaged 2026-11-03: low severity for the editor)

**Kind: feature**

**Value: low — Size: M**

**Affects: the editor and MCP.**

Found by the pre-release test rounds (2026-10-29/30) on EqualsVerifier.

### Observed behaviour

Since the fix that stopped this rename from breaking files, renaming one overload of a statically imported name
(`Rethrow.rethrow(ThrowingSupplier)`) is refused: the import must stay for the other overloads, and the renamed calls in that file would have no import.
The user has to rename the overloads together or qualify the calls first.

### Fix direction

Rename it, and add `import static T.newName;` beside the kept import in each file where the renamed member is called through it.

### Regression targets

None yet.

---

## FR-020 — Renaming a Helidon blueprint option leaves the generated builder's callers broken

**Status: documented**

**Kind: bug**

**Value: medium — Size: M**

**Affects: MCP and the editor.**

Found by the pre-release test rounds (2026-10-29/30) on Helidon.

### Observed behaviour

`rename_symbol` on `AllowListConfigBlueprint.allowAll()` edits 4 places in 3 files, but not the callers of the generated `Builder.allowAll(boolean)`; the build then fails with 21 errors.
The answer does warn ("Not compiled: TrustedProxyMatcher.java:76"), and `verify_change` reports the errors.
0.1.24 behaves the same.

### Fix direction

Refuse a blueprint rename with the reason, as for Lombok members (`RenameConflicts.lombokRefusal`), or rename the generated members' callers too when the generator links them.

Verified against the code (2026-11-16): still present.
`RenameService` refuses only a symbol declared in a generated source, and nothing links a generated member to the declaration it was generated from;
`ModuleSourceConfig.isGenerated` counts only the Lathe mirror and the build's generated-sources directory, so the Caffeine case below holds too.

Also (exploratory round, 2026-11-04): renaming Dropwizard's JMH `parseDuration` leaves 10 generated callers with no note; `verify_change` says clean while `get_diagnostics` on the generated file shows the errors.

Also (pre-release Gradle round, 2026-11-15): a generator that is not an annotation processor writes into a plain source root, which Lathe takes for hand-written code.
Caffeine's `codeGen` source set compiles `build/generated/sources/local-cache`, written by its own JavaPoet task;
`rename_symbol` on `FrequencySketch.ensureCapacity` edits 16 of its `*EvictionPolicy.java` files, which the next build overwrites and `git checkout` cannot undo,
and only flags the generator's template (`AddMaximum.java:173`) as "Not renamed — review".
Taking a source root inside the Gradle build directory as generated would leave those files to the generator, as annotation-processor output is.

### Regression targets

None yet.

---

## FR-029 — References to a widely used method take 12–27 s on a very large codebase

**Status: deferred** (triaged 2026-11-16: what is left, parallel batches per module and cached answers, needs its own design)

**Kind: bug**

**Value: low — Size: M**

**Affects: the editor's references and MCP `find_references` on large workspaces.**

Found by the end-to-end Gradle check (2026-11-04): Lathe 0.3.0 as released, the plugin from a local repository standing in for the Gradle Plugin Portal, applied with the guide's personal init script and first-run command `./gradlew latheSync -Plathe.capture.only=true`.

### Observed behaviour

On Hibernate ORM, references to `StringHelper.isEmpty` (`hibernate-core/.../internal/util/StringHelper.java:487`) take 27 s on the first query and 12 s on the next in the same session, through MCP; an editor request with a 15 s timeout gave up.
The answer is right: 202 references in `hibernate-core`, `hibernate-envers`, `hibernate-jcache`, `hibernate-community-dialects` and `tooling`.
Helidon's `Config.create()` took about 13 s warm in an earlier round, so this is the size of the codebase, not Gradle.

### Profile (2026-11-05)

Lathe 0.3.0 on JDK 26, `-Xmx4g`, two warm-up queries, then JFR over five identical queries:
20.6 s for the first query, 11.4 s median warm, 201 references.
jdtls 1.63 on the same checkout: no answer within 10 minutes on a fresh workspace (still indexing), then 20.9 s first and 4.8 s median warm, the same 201 references.

- **One thread per module is the critical path.**
  Each module's batches of 8 run one after another on its worker:
  `hibernate-core`'s main sources took about 9.8 s of CPU per query, its tests 5.8 s in parallel, every other module under 1 s, while most of the machine's 16 hardware threads idled.
- **Inside a batch:** attribution about 35%, loading classes 20–30% (repeated by every batch, each a fresh javac task), `ct.sym` about 5%, `ReferenceLocator` 2.5%, temp-file writes nothing measurable.
- **The planner compiles every file that spells the method's name.**
  1,150 files mention `isEmpty`; 170 of them also mention `StringHelper`, which every reference outside `StringHelper`'s own family has to name.

On spring-petclinic the same profile showed `ct.sym` at 34% of a 60 ms query: `--release` below the server's JDK makes javac reopen it for every task, as `JDKPlatformProvider`'s cache does not outlive the task.
Reusing it is out of scope: it would widen what Lathe uses from the compiler.

### Measured by the jdtls comparison's benchmark (2026-11-08)

The stage 1 harness (`dev/lsp-bench/`, pinned Lathe `0.3.99-c15d3a55`), warm median of 10 repeats over 3 runs, uncapped heap (the last column: one warm run at `-Xmx2G`);
references to a method whose name many files spell are the slowest query Lathe answers.

| Project | Query | References | Median | At `-Xmx2G` |
|---|---|---|---|---|
| Hibernate ORM | `EntityAction.getId` (`getId` in 4,239 files) | 47 | 27.7 s | 29.9 s |
| Apache Fineract | `Money.of(CurrencyData, BigDecimal)` (`of` in 1,174 files) | 125 | 21.8 s | 38.3 s |
| Apache Fineract | `Money.of(MonetaryCurrency, BigDecimal)`, one overload | 441 | 22.3 s | 38.0 s |
| Apache Fineract | `DataValidatorBuilder.parameter` (2,609 call sites) | 2,614 | 3.1 s | 3.7 s |
| Hibernate ORM | `StringHelper.isNotEmpty` | 494 | 2.0 s | 2.3 s |

A name spelled in few files stays fast (Hibernate ORM's `registerKeyword`, 1,274 references: 0.37 s), which is the planner cost above.
The queries and positions are `dev/lsp-bench/cases/hibernate_orm.py` and `fineract.py` ("references: common name", "references: one overload").

### Progress (2026-11-10)

The design is [Search Speed on Large Codebases](../planned/lathe-search-speed.md).
Done so far: the startup regression that blocked every request for up to 280 s after a start (#157); a search batch
of 32 files per javac task instead of 8; implementations and incoming calls batched the way references are, instead
of one task per file. Parallel compiles within a module are ruled out (the file manager is not thread-safe).
Measured with each project's synced `.lathe/`, one fresh server per query, warm median of 3, the same answers before
and after (the machine was partly loaded, load average 3–15):

| Query | Before: cold / warm | After: cold / warm |
|---|---|---|
| Hibernate ORM references `EntityAction.getId` | 38.3 / 31.5 s | 24.6 / 18.3 s |
| Fineract references `Money.of(CurrencyData, …)` | 44.4 / 26.3 s | 37.3 / 22.9 s |
| Fineract references `Money.of(MonetaryCurrency, …)` | 44.8 / 26.4 s | 36.9 / 21.3 s |
| Fineract implementations `NewCommandSourceHandler.processCommand` | 9.5 / 5.2 s | 4.1 / 1.2 s |
| Fineract incoming calls `DataValidatorBuilder.parameter` | 14.9 / 6.2 s | 11.2 / 3.6 s |
| OpenMetadata incoming calls `Entity.getEntityRepository` | 21.5 / 15.3 s | 13.0 / 7.8 s |
| Helidon incoming calls `WebClientMetrics.Builder.build()` | 13.9 / 6.3 s | 9.5 / 4.7 s |

Remaining: following exposures transitively, so a common name compiles only the files that can reach the method
(lever (b) of the design), and each module caching its answers until it changes (lever (c)).

### Status of the fix directions (verified against the code, 2026-11-16)

- Direction 1 is done for reactor types: `ReferenceCandidatePlanner.planStaticMethod` searches the files naming the declaring type first and defers the rest (ebd41702, narrowed to reactor types in bdbef9ee).
  This entry's headline query, the static `StringHelper.isEmpty`, takes that path: the 2026-11-08 benchmark measured `StringHelper.isNotEmpty` at 2.0 s.
  The slow queries left are instance methods (`EntityAction.getId`) and static methods of library types, which `ReferenceCandidatePlanner` still searches in every file spelling the name.
- Lever (b), following exposures transitively, was tried and shelved on 2026-11-10 ([design](../planned/lathe-search-speed.md)); it is not "remaining".
- Directions 2 and 3 are open; the design lists (c), the answer cache, and an exposure graph read from class files as post-release candidates.

### Fix direction

1. **Narrow static methods to their family, exactly.** (Done; see above.)
   Compile first every file that names the declaring type or a subtype, or imports the method statically, whether or not it spells the method.
   A file outside that set can reach the method only through an expression of a family type, whose type comes from a non-family type's non-private declaration naming the family, so in that set:
   check those declarations (as javac's model of each type, so Lombok's and a record's generated members count), and compile the files spelling only the method's name when one exposes a family type, or when a class in the set extends a family type the type index does not know.
   On this query 370 files name `StringHelper` and none uses it as a declared type, so 370 compiles answer exactly instead of 1,150.
   Known limit: Lombok's `@Delegate` generates members from another type without the file naming it, so a type exposed only that way goes unseen.
2. **Run a module's batches in parallel**, each on its own file manager.
   A fresh file manager costs nothing measurable per task and about 6 MB once warm; a throwaway prototype on 160 `hibernate-core` files ran 2–4 times faster on 8–16 threads.
   This touches the module worker's threading contract and needs its own design.
3. **Cache answers until the workspace changes**, for repeated queries (agents repeat them); it does not help a first query.

Rejected: reusing `ct.sym` (above), and replacing temp files (no measurable cost; module-system and patch-module compiles need a source's file to place it in its module).
Body stripping is a last resort: it saved about 15% in the prototype, and implicit uses (`super()`, an enhanced `for`'s `iterator()`) need their bodies.

Also (pre-release Hibernate ORM round, 2026-11-15): instantiation sites (`lathe.types.instantiations`) pay the same cost.
On `HibernateException`, which 617 files name, they took 13.8 s, then 13.6 s warm, for 576 sites; a cache of each
module's answer would serve them as it does references.

### Regression targets

None yet.

---

## FR-032 — Implementations of an interface method and incoming calls take seconds on a large codebase

**Status: deferred** (triaged 2026-11-16: what is left is FR-029's)

**Kind: bug**

**Value: medium — Size: M**

**Affects: the editor's implementations and incoming calls, and MCP `find_implementations` and `call_hierarchy`.**

Found by the jdtls comparison's benchmark (2026-11-08), Lathe `0.3.99-c15d3a55`, warm median of 10 repeats over 3 runs.

### Observed behaviour

| Project | Query | Answer | Median |
|---|---|---|---|
| Apache Fineract | implementations of `NewCommandSourceHandler.processCommand` | 461 | 3.8 s |
| Apache Fineract | incoming calls of `DataValidatorBuilder.parameter` | 2,614 references | 4.4 s |
| OpenMetadata | implementations of `EntityMapper.createToEntity` | 81 | 1.6 s |
| Hibernate ORM | implementations of `RepeatableContainer.value` | 54 | 0.73 s |
| Hibernate ORM | incoming calls of `KeywordRegistration.registerKeyword` | 1,274 references | 0.55 s |
| Dropwizard | incoming calls of `Environment.jersey()` | 78 references | 0.63 s |

Implementations of a class, and subtypes, answer from the type index in 14–121 ms on the same projects; implementations of a *method* compile the implementing files.
Incoming calls cost about what references to the same method cost, and more: OpenMetadata's incoming calls of `Entity.getEntityRepository` took 13.5 s.

### Progress (2026-11-10)

The design is [Search Speed on Large Codebases](../planned/lathe-search-speed.md).
Done so far: the startup regression that blocked every request for up to 280 s after a start (#157); a search batch
of 32 files per javac task instead of 8; implementations and incoming calls batched the way references are, instead
of one task per file. Parallel compiles within a module are ruled out (the file manager is not thread-safe).
Measured with each project's synced `.lathe/`, one fresh server per query, warm median of 3, the same answers before
and after (the machine was partly loaded, load average 3–15):

| Query | Before: cold / warm | After: cold / warm |
|---|---|---|
| Hibernate ORM references `EntityAction.getId` | 38.3 / 31.5 s | 24.6 / 18.3 s |
| Fineract references `Money.of(CurrencyData, …)` | 44.4 / 26.3 s | 37.3 / 22.9 s |
| Fineract references `Money.of(MonetaryCurrency, …)` | 44.8 / 26.4 s | 36.9 / 21.3 s |
| Fineract implementations `NewCommandSourceHandler.processCommand` | 9.5 / 5.2 s | 4.1 / 1.2 s |
| Fineract incoming calls `DataValidatorBuilder.parameter` | 14.9 / 6.2 s | 11.2 / 3.6 s |
| OpenMetadata incoming calls `Entity.getEntityRepository` | 21.5 / 15.3 s | 13.0 / 7.8 s |
| Helidon incoming calls `WebClientMetrics.Builder.build()` | 13.9 / 6.3 s | 9.5 / 4.7 s |

Remaining: following exposures transitively, so a common name compiles only the files that can reach the method
(lever (b) of the design), and each module caching its answers until it changes (lever (c)).

### Fix direction

Batching (stage 3 of the [comparison plan](../planned/lathe-jdtls-comparison.md)) is done (#158).
Verified against the code (2026-11-16): implementations of a method still compile every subtype file and every lambda or method-reference file (`ReferenceSearch.implementationsIn`), and incoming calls the reference candidates, in batches of 32 run one after another on the module's worker; only implementations of a type answer from the type index.
What is left is FR-029's directions 2 and 3: a module's batches in parallel, and cached answers.

### Regression targets

None yet.

---

# Code Action Gaps (CA)

Active `textDocument/codeAction` provider gaps. Resolved CA entries are in
[gaps-archive.md](gaps-archive.md).

## CA-7 — Pasting code with unresolved types requires per-symbol quick fixes; no whole-file "add missing imports"

**Status: deferred (add-missing slice shipped; automatic invocation deferred).**

**Kind: feature**

Signal: user feedback — pasting a snippet with several unimported types was recurring friction: the
only path was moving the cursor onto each unresolved name and invoking the single-name import quick fix
in turn.

### Delivered — whole-file "add missing imports" (slice 1)

`:LatheMissingImports` (server command `lathe.imports.missing`, also surfaced as the "Add missing
imports…" code action when the cursor is on an unresolved type) resolves every unresolved type in the
buffer in one pass. It works off the live buffer's debounce compile — no save round-trip needed, since
a syntactically-valid paste already yields `cant.resolve` diagnostics on change. The server enumerates
those TYPE_REF names and resolves each against the type index via a shared `ImportCandidates` helper
(extracted from `ImportQuickFixProvider`), returning each name with its candidate FQNs. The Neovim
client auto-adds unambiguous names, prompts one at a time (`vim.ui.select`) for ambiguous ones,
asks the server to place the chosen imports (`lathe.imports.edits`, each at its sorted place in its group), applies those edits,
and summarises what was added and any name it could not resolve.
The candidates include statically imported names, and the MCP `fix_imports` tool shares the same path.

No `source.*` capability is claimed: `source.organizeImports` would over-promise (it implies unused
removal + sorting) and `source.addMissingImports` is non-standard, so the feature is an
`executeCommand`, consistent with `lathe.types.hierarchy` / `lathe.types.create`.

Regression targets: `QuickFixCodeActionTest.missingImports_mixedNames_partitionsCandidatesByAmbiguity`,
`QuickFixCodeActionTest.missingImports_allTypesResolved_returnsNoItems`,
`QuickFixCodeActionTest.codeAction_typeRef_offersAddMissingImportsCommand`; client `imports_spec.lua`.

### Remaining — automatic invocation (deferred, backlog)

Running the command **automatically** — on paste and/or as an on-save step alongside format-on-save —
so the common case needs no manual invocation. A Neovim-client concern layered on the shipped command;
keep it opt-in like `format_on_save`. Unused-import removal and import sorting for this command (a fuller
"organize imports") are separate later slices; formatting with a pinned google-java-format or palantir already
removes unused imports and reorders them as the project's Spotless does.

---

## CA-18 — Extract method's compile check runs no javac plugins, so it can offer a refactor that fails Error Prone

**Status: deferred.**

**Kind: bug**

Decision (2026-11-03): Error Prone is too heavy for the analysis compile, so it runs only in the full compile on save,
where the editor shows the warning; a refactor's compile check is not expected to run it.
**Affects: Neovim, VS Code, any client, on builds that run Error Prone (or another javac plugin) with `-Werror`.**

Found by the pre-release test round (2026-11-01) on EqualsVerifier.

### Observed behaviour

Extracting `var fieldTypes = new ArrayList<Class<?>>();` and the loop that fills it in
`InstantiatorFactory.findConstructorMatchingFields` offers "Extract method", whose compile check passes, and writes
`ArrayList<Class<?>> fieldTypes = extracted(type);` with `private static ArrayList<Class<?>> extracted(Class<?> type)`.
That is valid Java, but EqualsVerifier's build runs Error Prone with `-Werror`, and `[NonApiType] Prefer a java.util.List
instead` on the new return type fails the module; the code before the edit builds clean.
The check is an analysis compile, which runs no annotation processor or javac plugin (Error Prone runs only in the
full compile on save), so it cannot see the warning; the editor shows it after the next save.
The return type is the local's inferred concrete type, and the caller's `var` becomes that explicit type.

### Fix direction

Keep the caller's `var` when the selection declared the local with `var`, and type a returned local by the declared
type rather than the inferred one where they differ; a check through the build's plugins is out of reach of the
analysis compile.

### Regression targets

None yet.

---

## CA-20 — An added import's group follows the file, not the project's configured import order

**Status: documented**

**Kind: bug**

**Value: low — Size: M**

**Affects: the import quick fix, "Add missing imports" and completion's auto-import, on a project that orders imports in groups.**

Found by the pre-release JUnit editor round (2026-11-15), after the group-aware placement that fixed most of it.

### Observed behaviour

An added import goes to the group that already holds its top-level package, which the file shows; three cases the file cannot show are guessed.
A file whose imports are one block of a single top-level package cannot say whether the project groups imports: JUnit's `ConditionEvaluator.java` holds only `org.*` imports, and an added `java.*` import joins that block without the blank line JUnit's import order puts between them.
A new group goes where it sorts alphabetically, which JUnit's order (`java`, `javax`, `jdk`, `aQute`, …, `org`) does not always follow: an `aQute.*` import would land above `java`.
Within a group, imports sort alphabetically, which IntelliJ's default `javax` before `java` block does not.
The build's import-order check fails on each until the file is formatted.

### Fix direction

Record the order the project declares, Spotless `importOrder` or an Eclipse `.importorder` file, in the sync's style, and place imports by it when there is one.

Verified against the code (2026-11-16): still present.
`ImportLayout` places an import from the file's own imports only (one group takes everything; a new group sorts alphabetically; a group sorts in natural order), all three entry points use it, and nothing reads an import order (`FormatterSpec` carries none).

### Regression targets

None yet.

---

# Completion Gaps (CQ)

Active completion-quality gaps. Discovered and triaged via the completion appendix of the
[gap workflow](gap-workflow.md); checked against the completion [expectations](../done/lathe-completion-expectations.md)
contract. Resolved CQ entries are in [gaps-archive.md](gaps-archive.md).

## CQ-0041 — Module directives complete, but annotation arguments on a module descriptor do not

**Status: deferred** (2026-11-15: feature freeze)

**Kind: feature**

### Delivered

`CompletionModuleInfoTest.complete_requiresDirective_offersModuleNames` and
`CompletionModuleInfoTest.complete_exportsDirective_offersPackageSegments` cover the delivered module-directive completions.

### Remaining — module annotation arguments

Reopened by the 2026-11-08 closure audit: the archived entry listed a proposed module-annotation regression as though it existed, but it was never added.
A probe through the current `SourceAnalysisSession.complete` reproduces the remainder:

```java
@Deprecated(s§) module example.app {}
```

At `§`, the result is `[requires, exports, opens, uses, provides]`, not the annotation element `since`.
The control `@Deprecated(s§) class Test {}` returns `[since]` through the same API and compiler.
The module-descriptor completion branch is being chosen inside the annotation argument.

### Regression targets

The directive tests above cover the delivered behavior.
Module-annotation argument completion has no regression test yet; the paired probe above reproduces it.

---

## CQ-0062 — Simple-name completion's duplicate check keys a method on annotated parameter text

**Status: deferred** (triaged 2026-11-16: narrow and not reproduced)

**Kind: bug**

**Value: low — Size: S**

**Affects: simple-name completion (no qualifier) inside a nested class or with static imports, on a server running JDK 21 or older, or for a `SOURCE`-retained annotation.**

Found reviewing the FR-031 fix (2026-11-08); suspected from the code, not reproduced.
Rewritten after checking the code (2026-11-16): the override case first suspected cannot happen.

### Suspected behaviour

`SimpleNameProvider.memberKey`, which keeps the same member reached twice from being listed twice, keys a method on its parameters' type text (`asType().toString()`).
FR-031 showed that text carries type-use annotations for a type compiled from source and lacks them for the same type read from a class file on JDK 21 and older.

An override is not affected: `SimpleNameProvider` takes a class's members from `Elements.getAllMembers`, which already drops a supertype method that a member overrides, by erasure, so the two never meet in `memberKey`.
Member completion after a dot goes through `CandidateGenerator` and does not use `memberKey` at all.
What is left is the same signature reached through two `getAllMembers` calls: an enclosing class's members (`addMembersOutwards`) or a static import.
A mismatch there would list the method twice, though an inner class's method of the same name usually shadows the outer one, so the case is narrow.

### Probe

Simple-name completion inside a nested class whose outer class and a static import both reach one class-file method with a `SOURCE`-retained type-use annotation on a parameter; the method should be listed once.
The likely fix keys on `ReferenceTarget.erasedTypeName`, which names a type without its annotations; it is harmless hardening even if the probe does not reproduce.

### Regression targets

None yet.

---

## CQ-0063 — The first completion in a module not analysed yet answers empty and complete

**Status: documented**

**Kind: bug**

**Affects: completion right after opening a file in a module of a large workspace, before the module's first analysis; Neovim with blink.cmp or `vim.lsp.completion`.**

Found by the editor exploratory round on jdbi (2026-11-17).

### Observed behaviour

Open `core/src/test/java/org/jdbi/v3/core/TestJdbi.java`, type `h.create` inside `try (Handle h = db.open()) { … }`, and ask for completion about three seconds after opening.
The answer takes about 1.7 s and is `items=0 incomplete=false`; the FINE log reads `[completion] parsed valid=false sentinelCtx=null receiver=|null| class=null method=null role=ORDINARY`.
A second later the same request goes through `[completion] inject prefix=|create| receiver=|h|` and answers 9 items (`createBatch` first).
Six requests in a row right after opening all answered 0 items; the small two-module fixture never does.
Because the empty answer says it is complete, the client does not ask again: the user sees no completion on the first keystrokes.

### Fix direction

Find why the sentinel recovery is skipped before the module's first analysis (the incomplete-text path needs it); until it can run, wait for it or answer `isIncomplete=true` so the client asks again.

### Regression targets

None yet.

---

# Workspace Lifecycle Gaps (WS)

Workspace freshness and lifecycle gaps: reactor mirror / type-index staleness, source watching, sync
prompting, and reload. Resolved WS entries are in [gaps-archive.md](gaps-archive.md).

## WS-17 — The server JVM has no heap cap and grows toward a quarter of the machine's memory

**Status: deferred.**

**Kind: bug**

Decision (2026-10-24): Lathe sets no default limit until users report how much memory they want it to
use; machines with plenty of memory are expected to use it. The installation guide documents capping
it with `LATHE_JVM_OPTS` (and `-XX:G1PeriodicGCInterval` to return memory when idle), and the agent
guide's troubleshooting points there. Lathe's own dev tools cap their servers at 2 GB.
**Affects: the editor and MCP.**

Found by the MCP-quality probes (2026-10-24) on Helidon.

### Observed behaviour

An MCP session on Helidon started at 752 MB RSS; three `verify_change` calls on `GenericType`
(235–311 caller files in 36–60 modules each) took it to 9.6 GB.
The launchers pass no `-Xmx`, so on a 60 GB machine the default maximum heap is about 15 GB, and the
heap grows rather than collects.
The same workload under `LATHE_JVM_OPTS=-Xmx2g` ran at the same speed (5.5–11 s per call) and stayed
at about 2.6 GB, so most of the growth is uncollected garbage, not memory Lathe holds.
An editor server and an MCP server per agent, all day, add up.
These numbers predate releasing idle module sessions (after 5 idle minutes, or earlier under memory pressure; [design](../done/lathe-memory-pressure.md)) and need re-measuring.

### Re-measured by the jdtls comparison's benchmark (2026-11-08)

After idle module sessions are released, Lathe `0.3.99-c15d3a55`, each server's summed RSS at its peak while answering the benchmark's 17 to 23 queries (warm runs, medians), against one run per project with `LATHE_JVM_OPTS=-Xmx2G`:

| Project | Uncapped | `-Xmx2G` | Queries at 2 GB | CPU at 2 GB |
|---|---|---|---|---|
| spring-petclinic | 1.1 GB | 0.8 GB | about the same | 1.3× |
| Dropwizard | 9.3 GB | 2.6 GB | 0–36% slower | 1.3× |
| Helidon | 6.3 GB | 2.6 GB | about the same | 1.1× |
| Hibernate ORM | 6.7 GB | 2.7 GB | 0–20% slower | 1.5× |
| Apache Fineract | 7.6 GB | 3.6 GB | up to 76% slower (FR-029's slowest queries) | 2.9× |
| OpenMetadata | 3.8 GB | 2.9 GB | 0–15% slower | 1.9× |

Every answer stayed the same at 2 GB.
So the earlier conclusion holds: most of the growth is uncollected garbage, and a 2 GB cap costs CPU, not correctness; Fineract's reference searches are the case a cap would visibly slow.

### Fix direction

Give the launchers a default heap policy: a cap (absolute, or `-XX:MaxRAMPercentage`), and G1's
periodic collection (`-XX:G1PeriodicGCInterval`) so an idle server returns memory; keep
`LATHE_JVM_OPTS` as the override. Measure the cap against the largest probes first.
Related: archived [EG-058](gaps-archive.md#eg-058--module-analysis-sessions-are-never-released-a-reactor-wide-search-pins-every-touched-modules-jars-for-the-session--done),
done: module sessions are now released when idle or under memory pressure.

### Regression targets

None yet.

---

## WS-24 — An unreadable `workspace.json` is not reported to the editor and is never re-read

**Status: deferred** (triaged 2026-11-03: low severity for the editor)

**Kind: bug**

**Value: low — Size: S**

**Affects: the editor and MCP.**

Found by the pre-release test rounds (2026-10-29/30) on a fresh Maven project.

### Observed behaviour

A `workspace.json` that is not UTF-8 or not readable (chmod 000) is logged at SEVERE in `WorkspaceWatcher.readManifest`; the editor gets no message, the watcher does not start, and a fixed file is never re-read, so outside changes go unnoticed until a restart.
An empty file leaks `Cannot invoke "…schema…"`; a missing file or one with no modules is silent.

### Root cause

`WorkspaceManifest.load` already turns a read error into a load problem that `showWorkspaceStatus` would show,
but `loadWorkspace` constructs the `WorkspaceWatcher` first, and its `readManifest` throws, so the warning is never sent.

### Fix direction

Report a broken manifest to the client with the remedy (a sync), keep watching, and reload once it is readable.

### Regression targets

None yet.

---

## WS-27 — On Gradle, included builds are not covered

**Status: deferred** (2026-11-15: feature freeze)

**Kind: feature**

**Value: low — Size: L**

**Affects: the editor and MCP on Gradle composite builds.**

Found while documenting the Gradle plugin (2026-10-30), from its design notes.

### Observed behaviour

The settings plugin acts on the root build only, so the projects of an included build (`includeBuild`) get no
`.lathe/` configuration: their Java files have no code intelligence, and a project whose directory is outside the
root build is left out with a `[lathe] … left out` line.

### Fix direction

Capture included builds that hold Java projects as more modules of the same workspace.

### Regression targets

None yet.

---

## WS-34 — A build check that scans the whole project finds the class mirror in `.lathe/`

**Status: deferred** (2026-11-16: the workaround is documented; the proper fix is the External Content Directory design)

**Kind: bug**

**Value: medium — Size: M**

**Affects: Maven and Gradle builds with a check that scans the project directory itself (nohttp, license-header checks, Docker contexts).**

Found syncing spring-petclinic for the Declared Formatters probes (2026-11-11).

### Observed behaviour

From the second build on, petclinic's nohttp check fails.
On Maven it is the `maven-checkstyle-plugin` execution that runs `nohttp-checkstyle` over `${basedir}`;
on Gradle, the `io.spring.nohttp` plugin's `checkstyleNohttp`.
Both find `http://` URLs in `.lathe/classes/META-INF/sbom/application.cdx.json`,
a generated resource that Lathe's class and resource mirror copies.
The first build passes only because `.lathe/` does not exist yet.
The check runs in `validate`, before the sync, so the sync cannot change it.
Only the mirror (`classes`, `test-classes`, generated sources) holds such files; the small files in `.lathe/` hold none.

### Fix direction

The workaround is documented: exclude `.lathe/` from such checks (README, `troubleshooting.md`; #178).
Two ways to need no exclusion are open:

- **Lathe adds the exclusion itself.**
  The Maven extension appends `**/.lathe/**` to a nohttp checkstyle execution's `<excludes>` in memory,
  and the Gradle plugin adds it to the nohttp extension's `source`, when Lathe is active.
  It covers one tool, and editing an execution's configuration after the model is built needs a spike.
- **Move the mirror out of the project** into the per-user cache, leaving `.lathe/` the small files.
  No scanner or Docker context would see it, at the cost of a per-workspace cache and its cleanup.
  The mirror must stay outside `target/` and `build/`: it outlives a clean and a failed build (`lathe-gradle-support.md`).
  This is the [External Content Directory](../potential/lathe-external-content-dir.md) design, which covers every
  scanner, not only nohttp: the proper fix, taken up if more users hit this.

### Regression targets

None yet.

---

## WS-35 — Lombok in the analysis compile reads a `lombok.config` up to 2 seconds old

**Status: deferred** (documented in the [troubleshooting guide](../guide/troubleshooting.md); fix only if Lombok users ask)

**Kind: bug**

**Value: low — Size: S**

**Affects: the editor's diagnostics of Lombok classes right after a `lombok.config` edit; `verify_change` is not affected.**

Found while batching `verify_change`'s checks (2026-11-15).

### Observed behaviour

Lombok runs inside the analysis compile, which stays loaded between compiles, and caches each `lombok.config` it read:
it looks at the file again only when more than 2 seconds passed since it last did (`FileSystemSourceCache.RECHECK_FILESYSTEM`, Lombok 1.18.48).
An analysis within those 2 seconds of an edit to `lombok.config` generates members from the old settings:
after removing `lombok.accessors.prefix += f`, a class still gets `getCount()` for its field `fCount`, and a caller of it shows no error.
The write pass runs a fresh Lombok for each compile and is not affected.
`verify_change` checks a changed file alone, against the classes its write produced, so it is not affected either;
the editor's batched refresh of open files is.

### Fix direction

Release the analysis sessions of the trees below an edited `lombok.config` before the refresh of their open files:
each session loads Lombok in a class loader of its own, so the refresh runs a fresh Lombok that reads the new settings.

### Regression targets

None yet.

---

## WS-36 — A re-sync once recompiled a module's generated sources twice, holding a query for 44 s

**Status: deferred** (triaged 2026-11-16: seen once, not reproduced; revisit when it recurs)

**Kind: bug**

**Value: low — Size: unknown**

**Affects: MCP and editor answers right after a sync, on a module with many generated sources.**

Found by the pre-release Gradle round (2026-11-15) on Caffeine.

### Observed behaviour

After `.lathe/lathe-sync.sh caffeine`, the MCP server recompiled the module's 493 generated sources twice in a row
(28 s, then 22 s), and a `get_diagnostics` asked meanwhile answered after 43.8 s.
Two reduced attempts (add a file, sync, delete it, sync) did not repeat it.

### Fix direction

Reproduce first: find what made the pass write the generated sources twice (a reload landing mid-pass, or stamps the sync
rewrote after the first write).

Both are possible in the code (checked 2026-11-16): `WritePass.onReload` bumps the pass generation, but `writeLevels` checks it only between dependency levels, so a level already compiling finishes and the next pass may write it again;
the stamps guess would sit in `stableSources`/`pendingStale` and the stamp map.
No commit since the report targets either.

### Regression targets

None yet.

---

## WS-37 — Java that Groovy compiles jointly, under `src/<source set>/groovy`, is in no module

**Status: deferred** (triaged 2026-11-16: a feature, deferred under the feature freeze)

**Kind: feature**

**Value: low — Size: L**

**Affects: Gradle and GMavenPlus builds whose Groovy source sets hold Java files.**

Found by the exploratory round on micronaut-core (2026-11-15).

### Observed behaviour

Gradle's Groovy plugin compiles the Java files under `src/<source set>/groovy` together with the Groovy ones, with groovyc, not javac.
Lathe captures javac's compiles only, so those Java files belong to no module:
opened in the editor they get "not under any Lathe module", and no search, rename or check reads them.
Answers about a symbol's uses name the module, as one with Groovy sources Lathe does not search,
so an agent knows to check it with grep.

### Fix direction

Capture the Groovy compile's Java sources as a tree of their own, compiled by javac against the Groovy classes from the build;
the Groovy sources themselves stay unread.

### Regression targets

None yet.

---

## WS-38 — An empty dependency-source marker fails every later sync

**Status: documented**

**Kind: bug**

**Value: medium — Size: S**

**Affects: every sync (Maven, Gradle, OpenJDK) whose Lathe cache holds a truncated `.lathe-source.json`.**

Found while running `mvn clean install` after a machine crash (2026-11-16).

### Observed behaviour

A hard crash while the Gradle plugin's functional tests ran left `deps/com.example/withsrc/1.0/.lathe-source.json` in the fixtures' Lathe cache as a 0-byte file:
`ZipCache` writes the marker into a temporary directory and renames it into place, which a crash before the data reaches the disk does not survive.
Every later sync failed with `[lathe] workspace files not refreshed: Cannot invoke "…DependencySourceSync$SourceMarker.schema()" because "m" is null`, and wrote no `.lathe/workspace.json`.
Only deleting the cache by hand recovered it.

### Cause

Gson reads an empty file as `null`, and `DependencySourceSync.isSourceCacheCurrent` dereferences the result; its `catch (IOException)` does not catch the `NullPointerException`.
The other cache readers already tolerate it: `TypeIndexFiles.current` catches `RuntimeException`, `CompiledStamps` checks for `null`.
`WorkspaceTypeIndex.loadFile` (the server) has the same weakness: an empty shard becomes `Stream.of(null)`.

### Fix direction

Take a `null` marker as a stale cache, so the sources are extracted again and the marker rewritten; treat an empty type-index shard as missing.

### Regression targets

None yet.

---

# Test Execution Gaps (TE)

Capture/replay gaps involving the Maven test fork, the recorded launch template, and the standalone
replay JVM. Resolved TE entries are in [gaps-archive.md](gaps-archive.md).

## TE-3 — `<systemPropertyVariables>` are not carried into the replay launch

**Status: deferred**

**Kind: bug**

(Renumbered from TE-1, which is already used by the archived capture-dependency-leak gap in
[gaps-archive.md](gaps-archive.md#te-1--capture-only-dependencies-leak-into-the-recorded-replay-classpath).)

Surefire applies `<systemPropertyVariables>` inside the fork via `System.setProperty` from a program
argument that never appears in `getInputArguments()`, so the capture listener cannot see them and
replay omits them. No project validated against Lathe uses them today; the escape hatch is
`<argLine>-Dkey=value</argLine>` (captured as `jvmArgs`). The design — `lathe:sync` reads the
effective Surefire model (or a hybrid where sync supplies the key names and the fork supplies the
values) — is in [lathe-run-test-debug.md](../done/lathe-run-test-debug.md) §15.1.

### Regression targets

None yet — to be defined when the fix is scheduled.

---

## TE-11 — A test run whose Surefire tests a copy of the classes replays the last build's classes

**Status: deferred** (2026-11-04: the silent part fixed; replaying the copy from `.lathe/` deferred)

**Kind: bug**

**Value: medium — Size: M**

**Affects: MCP `run_test`, editor test runs; any module whose Surefire `classesDirectory` is not the compile output.**

Found while fixing a `lathe-server` bug through Lathe's own MCP server (2026-10-30).

### Observed behaviour

On JDK 21+, a profile in `lathe-server/pom.xml` (since 2026-11-05 in `lathe-analysis/pom.xml`, with the
multi-release classes) copies `target/classes` into `target/classes-jdk21`,
overlays the multi-release `META-INF/versions/21` classes on it, and points Surefire's `classesDirectory` there.
The captured `test-launch.json` names `target/classes-jdk21`.
After editing `SourceScope.java`, `run_test` on `WorkspaceSessionTest` compiled the edit into the mirror
(`.lathe/lathe-server/classes`, fresh) and the edited test, yet ran the old `SourceScope` from `classes-jdk21`
(left by the last Maven build): the new tests failed with the exception the edit had removed.
`get_diagnostics` on the file and a second `run_test` gave the same result; nothing said the run used stale classes.
A Maven run of the same tests passed.

### Root cause

The replay swaps only the module's compile output directories for Lathe's mirrors (`ReactorRewrite`, from the
workspace manifest).
`classes-jdk21` is a copy made by the resources plugin, not a compile output, so it passes through unchanged.
The same holds for any build that tests a copied or merged classes directory.

### Fix direction

At sync, compare Surefire's `classesDirectory` with the module's output directory; when they differ, record it,
and map it in the replay to the mirror trees it was built from (here the main mirror with the `21` tree's mirror in front).
Where Lathe cannot tell what a copied directory holds, the completeness gate should at least say the run uses classes
from the last build, as it does for other stale inputs.
For this repo alone, the build could test against the compile output plus the `versions/21` overlay without a copy.

### Partial resolution

The run no longer uses the copy silently.
The completeness gate notes each directory a launch takes from a reactor module's build directory that no
`.lathe/` mirror stands for: the editor's test output shows it, and `run_test` ends with
"Note: lathe-server/target/classes-jdk21 runs as the last build left it, not from .lathe: it may not hold your
latest edits".
Checked on this repo: `run_test` on `TestIdTest` (`lathe-server`) passes with the note, and on `BuildLockTest`
(`lathe-core`, which tests its compile output) without one.

Replaying the copy from the mirrors (the `classes` mirror with the `21` tree's in front: on the module path through
`--patch-module`) is deferred.
It is mostly this repository's own setup, Surefire tests `target/classes` by default, and the module-path patching
would touch every replayed launch; reconsider if a project asks for it.

### Regression targets

`CompletenessGateTest.verify_copyOfClassesInABuildDir_openWithNote_mirroredAndOutsideDirsNot`

---

## TE-17 — A Gradle source set beyond `test` has no package, folder or module runs

**Status: deferred** (2026-11-15: feature freeze)

**Kind: feature**

**Value: low — Size: M**

**Affects: editor test runs and MCP `run_test` on Gradle source sets such as `integrationTest`, `jmh` or a JVM test suite.**

Found by the pre-release Gradle round (2026-11-15) on Caffeine.

### Observed behaviour

In such a source set, a test class or method runs from the source set's own captured launch.
A package, folder or module run does not: a package or folder in it offers no run, and a module run takes `test`'s tests only.
Before 2026-11-15, a test file there offered a package run that ran `test`'s package of the same name
(on Caffeine's `lincheckTest`, 31 classes and 234,588 results in place of the Lincheck test).

### Fix direction

Run a package, folder or module of a source set from that source set's launch.
A module run covering several source sets needs one launch per source set.

### Regression targets

`LatheWorkspaceServiceTest.executeCommand_listRunnables_sourceSetFile_noPackageRun` (the package run is not offered).

---

## TE-18 — Tests a Gradle source set runs through another engine never show in the editor

**Status: documented**

**Kind: bug**

**Value: low — Size: M**

**Affects: editor test discovery and runs, and MCP `run_test`, for a source set like JUnit's `archUnit` suite.**

Found by the pre-release JUnit editor round (2026-11-15).

### Observed behaviour

With the `archUnit` source set's launch captured (`test-launch-classes-archUnit.json`), `ArchUnitTests.java` shows no tests in neotest: the file's runnables know `@Test`-style methods only, not ArchUnit's `@ArchTest` fields, and module discovery (`lathe.tests.discover`) reads the `test` source set only, listing 53 tests and none of `archUnit`'s.
These tests cannot be run from the editor at all.

### Cause (verified against the code, 2026-11-16)

- Module discovery (`RunCoordinator.launchDiscovery` through `SourceLayout.testConfigs()`) keeps the `test-classes` tree only.
- `RunnableScanner` knows `@Test`, `@ParameterizedTest`, `@TestFactory`, `@RepeatedTest`, suites, `@Nested` and JUnit 3 classes, not `@ArchTest` fields.
- neotest finds an unopened file's module through `lathe.workspace.context`, which answers `null` for a source-set file (EG-109), so such a file has no module to discover in.

Running is not the missing part: `checkedTestLaunch` reads `test-launch-classes-<set>.json`, which the Gradle plugin captures; the editor has no target to run.

### Fix direction

Discover each captured source set's launch, which finds the ArchUnit engine's tests, and place the file's runnables from that discovery.
Fix EG-109's context first: discovery alone leaves neotest without the file's module.

### Regression targets

None yet.

---


## TE-19 — A capture-only sync leaves some Surefire modules uncaptured, without a word

**Status: documented**

**Kind: bug**

**Affects: Maven modules whose capture-only test run reports `Tests run: 0` (seen on jdbi: `core`, `sqlobject`, `postgres`, `spring` and others); `run_test`, neotest and `:LatheRun` on their tests.**

Found by the editor exploratory round on jdbi (2026-11-17).

### Observed behaviour

`:LatheInit` on jdbi succeeds and captures 21 modules' test launches, but not `core`, `sqlobject` and other modules with tests; nothing in the output says so.
Running `TestJdbi` then answers `blocked: no captured test launch for core; run .lathe/lathe-sync.sh --tests core`, and that command again reports `Tests run: 0, Failures: 0, Errors: 0, Skipped: 0` and writes no `.lathe/core/test-launch.json`.
`lathe-junit` is on the fork's test classpath (`-X`), Surefire uses the JUnit Platform provider with `forkCount=4`, and no fork reports a test class.
`vavr`, with the same Surefire settings, captures (its dry run reports 3 skipped tests).
`-Dtest=TestJdbi -Dlathe.capture.only=true` on `core` captures the launch (8 skipped).
`CaptureLauncherSessionListener` swallows every `Throwable`, so a failed capture leaves no trace.
[EG-067](#eg-067--the-first-build-floods-the-log-with-warnings) saw `Tests run: 0, Skipped: 0` on EqualsVerifier's `equalsverifier-core` and `equalsverifier-test`; whether those were captured is not recorded.

### Fix direction

Find why the whole-module dry run starts no test class in these modules; whatever the cause, have the sync say which modules ran tests but captured no launch, and have the listener print why a capture failed instead of nothing.

### Regression targets

None yet.

---

# Debug & Evaluation Gaps (DB)

Gaps in the in-process DAP adapter and the two-stage expression evaluator (see [lathe-run-test-debug.md](../done/lathe-run-test-debug.md) §12.11).
Breakpoints, stepping, inspection, conditional breakpoints, and read/invoke expression evaluation all work;
the entries below are what remains.

## DB-1 — Expression evaluation is read/invoke only; no assignment or `setVariable`

**Status: deferred**

**Kind: feature**

### Observed behaviour

The evaluator computes values (reads, method/constructor invocation, `String` concat) but cannot
write: an assignment expression raises `EvaluationException`, and the DAP `setVariable` request is not
backed by a write path. A watch or console `x = 5`, a field write, or editing a variable in the
Variables pane therefore does nothing.

### Expected behaviour

`x = expr`, field writes, and array-element writes assign into the suspended frame and return the
assigned value; the DAP `setVariable` request mutates the named variable. This is the deferred eval
"v3" — it mutates debuggee state and warrants its own design (type coercion/boxing on the write side,
final-field policy, event suppression) before implementation.

### Probe commands

```bash
python3 dev/debug_probe.py --workspace <ws> <MainFile.java> --line <N> --main <Class> \
  --eval "x = 5"          # today: EvaluationException (assignment unsupported)
```

### Regression targets

None yet — to be defined when the fix is scheduled.

---

## DB-2 — Array-creation expressions (`new T[]{…}` / `new T[n]`) are unsupported

**Status: deferred**

**Kind: feature**

### Observed behaviour

A `NewArray` tree raises `EvaluationException: unsupported expression: NEW_ARRAY`. Reads of existing
arrays (indexing, `.length`) work; only *creating* an array in an expression fails, which also blocks
calls whose argument is an array literal (e.g. `java.util.BitSet.valueOf(new long[]{…})`).

### Expected behaviour

`new int[]{1, 2, 3}`, `new String[4]`, and `new long[]{}` allocate a JDI array (via
`ArrayType.newInstance`), fill any initialiser elements, and evaluate to the mirror. Low marginal
value in watches, hence deferred.

### Probe commands

```bash
python3 dev/debug_probe.py --workspace <ws> <MainFile.java> --line <N> --main <Class> \
  --eval "new int[]{1,2,3}[0]"   # today: EvaluationException NEW_ARRAY
```

### Regression targets

None yet — to be defined when the fix is scheduled.

---

## DB-7 — A breakpoint in a file that is not open, or was closed, never fires, and nothing says so

**Status: deferred (cross-module source lookup shipped; arming breakpoints in files without an analysis remains, needed once a client keeps breakpoints in closed files).**

**Kind: bug**

**Value: low — Size: S–M**

**Affects: no shipped client today; VS Code once it debugs, and any editor after a memory-pressure release.**

Signal: user feedback — with breakpoints across several source files, a debug run stopped at seemingly "random" places instead of the breakpoints.
Re-evaluated with a spike (2026-10-31): the misses are deterministic, not random.
Which breakpoints fire depends on which of their files the server has an analysis of when the debug session starts,
and every breakpoint is reported unverified, so the editor cannot show which ones are dead.
No stop at a line without a breakpoint was seen.

Downgraded (2026-10-31): Neovim never sends a breakpoint for a file it has not loaded.
nvim-dap keeps breakpoints as signs, and `:bd` deletes a buffer's signs, so closing a buffer removes its breakpoints;
a hidden buffer stays loaded and attached, so its breakpoints arm.
What remains is a client that keeps breakpoints independent of open files, as VS Code does (its client does not debug yet),
and an open file whose analysis a memory-pressure release dropped (read from the code, not probed).

### Observed behaviour

Debugging `com.example.app.Main` on the `multi-module` invoker workspace (fresh bytecode),
breakpoints at `Main:21` and in `StringUtils.upper` (module `core`), varying only what the editor did with `StringUtils.java` first:

| `StringUtils.java` | `Main:21` | `StringUtils` |
|---|---|---|
| opened, diagnostics received | stops | stops |
| opened, debug started without waiting for diagnostics | stops | stops |
| never opened | stops | runs past |
| opened, then closed | stops | runs past |

- Opening and debugging at once is not a race: the open-time compile and the arming query run in order on the module's worker.
- Closing a file drops its analysis, so a breakpoint left in a closed file is dead.
  Neovim cannot hit this: `:bd` removes the buffer's breakpoints along with its signs.
- Every `setBreakpoints` answer has `verified: false`, armed or not.
  `LatheSourceLookUpProvider.supportsRealtimeBreakpointVerification` returns `false`,
  so java-debug only verifies a breakpoint once its class loads, and a dead one is indistinguishable until the run ends.
  The server logs `[breakpoint] … not armed`, which the user does not see.

The other half of the original report, a stop in another module's class showing no source, is fixed:
the debug provider context now receives `workspace.allSourceRoots()` plus the manifest's dependency and per-module JDK source dirs.

### Root cause

`LatheSourceLookUpProvider.breakpointLocation` → `FileFeatures.enclosingBinaryName` (through the module worker's `session.files()`) takes the class name only from the module session's cache of attributed open documents.
That cache holds a file only between its open-time compile and its `didClose`, and loses everything when the module's session is released
(a second memory-pressure round releases sessions of modules with open files too; read from the code, not probed).
With no class name, java-debug installs nothing for the breakpoint (`SetBreakpointsRequestHandler` skips a location whose `className()` is `null`).

### Fix direction

Spiked and confirmed (uncommitted): when the cache has no analysis of the file, parse it from disk (parse only, no attribution, through the session's `SourceParser`)
and return the binary name of the top-level type whose span holds the line.
The top-level name is enough: java-debug also arms the line in the type's nested classes,
already-loaded ones through `classesByName(name)` with nested types, later ones through a `name$*` class-prepare filter.
With the spike, breakpoints in a never-opened and in a closed file stop on the top-level class, a static nested class, an anonymous class and a lambda body.

For the real change:

- Decide whether to keep the cached path at all. The disk file is what the build compiled;
  an edited, unsaved buffer's analysis can name a type the running bytecode does not have yet.
  Parsing always is simpler and has no cache dependency.
- Parse once per file per `setBreakpoints`, not once per breakpoint line.
- Report armed breakpoints as verified (`supportsRealtimeBreakpointVerification` → `true` once every armed location has a class name),
  so the editor marks a breakpoint that cannot be armed (no executable code, file outside every module) differently from one waiting for its class.

### Probe commands

`dev/debug_probe.py --bp` opens every breakpoint file before debugging, so it only covers the attributed case.
The spike's driver varies the file's state (`open`, `nowait`, `none`, `closed`):

```bash
# /tmp/lt/db7_probe.py (spike driver, outside the repo); LATHE_LAUNCHER points at a server built from the checkout
python3 -I /tmp/lt/db7_probe.py <ws> none <core/StringUtils.java>:8 <app/Main.java>:21
```

### Regression targets

- Cross-module source lookup: `LatheSourceLookUpProviderTest.getSourceFileURI_classInAnotherModule_resolvesWhenRootsSpanTheReactor`.
- Arming without an analysis: none yet — a file never opened, a file opened then closed, and lines in a nested, an anonymous and a local class.

---

# Neovim Client Gaps (NV)

Gaps in the shipped Neovim client plugin (`lua/lathe/…`) and its recommended configuration, as distinct from the server's LSP/DAP surface.

Resolved entries are in [gaps-archive.md](gaps-archive.md).

## NV-9 — `:LatheNew` → Test from a main-source file lists the main location first

**Status: deferred** (triaged 2026-11-03: low severity for the editor)

**Kind: bug**

**Value: low — Size: S**

**Affects: Neovim.**

Found in daily use (2026-10-30).

### Observed behaviour

From a file under `src/main/java`, `:LatheNew` → Test offers the buffer's package twice, and the main-source row comes first.
Accepting the top row is the natural move, yet a test belongs in the test root.

### Fix direction

`SourceLayout.destinations` now owns the ranking (moved from `lua/lathe/new.lua` in `6d40d341`).
Its unfiltered list ranks both scopes equally within the buffer's package and module,
so the alphabetical tie-break (`main` < `test`) still decides.
The typed-package path already prefers the kind's scope; that partial improvement does not fix the unfiltered list.
Within a rank, prefer the kind's natural scope (`test` for Test) before the module/package tie-break.

### Regression targets

None yet.

---

## NV-10 — Signature help highlights no parameter when the active overload is not the first

**Status: deferred** (2026-11-16: the fix is blink.cmp's, in its open PR #2497; re-check on its next release)

**Kind: bug**

**Value: medium — Size: S**

**Affects: Neovim with blink.cmp's signature window, the setup in `examples/nvim/init.lua`.**

Found in daily use (2026-10-30).

### Observed behaviour

Typing the second argument of `list.add(0, |)`, the signature window lists both `ArrayList.add` overloads
but highlights nothing.
The server answers correctly: `[signatureHelp] sig=void add(int index, E element) param=1`,
the second signature active and its second parameter (label offsets 20–29) active.
A method with one overload, or an active overload listed first, highlights as expected.

### Root cause

blink.cmp (v1.10.2, `lua/blink/cmp/signature/window.lua`) writes every signature's label into its window,
one per line, but takes the highlight from Neovim's `convert_signature_help_to_markdown_lines`,
which renders only the active signature, inside a code fence.
Its line (1, under the fence) minus one is 0, so blink paints the active parameter's columns on the first label:
`boolean add(E e)` is 16 characters, so columns 20–29 show nothing.
Introduced by blink's `b6f04e8` ("Update active parameter to use line and col range").
Neovim's own `vim.lsp.buf.signature_help` (`<C-s>` in normal mode in the example config) renders only the active
signature and highlights correctly.

### Fix direction

The fix is blink.cmp's: [PR #2497](https://github.com/saghen/blink.cmp/pull/2497) (open since 2026-04-19, for this
Java overload case) takes the line from the active signature's index in its own buffer. Not in v1.10.2, the latest
release on 2026-11-16; once a release has it, check the example config and close this gap.
The Neovim guide's troubleshooting names the workaround: `<C-s>`, Neovim's own signature help, highlights correctly.
Lathe listing the active signature first (`activeSignature` 0) was not taken: every client's overload order would
change as the arguments change, to work around one plugin.

Related, on the server: when the call does not attribute yet, `SignatureHelpResolver` now makes active the first
overload whose arity fits the arguments typed, not the first declared
([EG-088](gaps-archive.md#eg-088--signature-help-is-empty-inside-new-p-and-super-when-no-constructor-takes-zero-arguments--done)).

### Regression targets

None yet.

---

## NV-11 — The Java indenter still disagrees with google-java-format on 1% of lines

**Status: deferred** (2026-11-12: stopped on purpose, to keep the indenter simple)

**Kind: bug**

**Value: low — Size: M**

**Affects: Neovim's live indentation (`indentexpr`, `gg=G`) of code the project formats with google-java-format.**

Found measuring the indenter against formatted code (#176).

### Observed behaviour

`dev/indent-corpus.sh` asks the indenter for each line of this repository's own sources, which google-java-format formats.
It disagrees on 536 of 53,893 non-blank lines (1.0%; 3.9% before #176).
What remains is continuation shapes that would each need a rule of their own:
an argument after a multi-line argument,
the first argument of a call that roots a method chain broken onto the next lines (google-java-format indents it two continuations deep),
`&&`/`||` lines, and ternary branches.
Format-on-save rewrites them, so the cost is a line typed at the wrong column, not a wrong file.

### Fix direction

Two of these were implemented and dropped in #176:
the chain-root rule had to look ahead through the tree for a chain still being typed,
and the multi-line-argument rule regressed hand-formatted code.
Take one up only with a narrow, tree-based rule, measured with `dev/indent-corpus.sh` before and after.

### Regression targets

`lathe-neovim/test/indent_tree_spec.lua` and `indent_spec.lua`; the measure is `dev/indent-corpus.sh`.

---

# MCP / Agent Facade Gaps (MC)

Gaps in the in-process `LatheEngine` facade and the `lathe-mcp-server` tool surface that exposes
Lathe to AI agents, as distinct from the LSP/DAP server behaviour those tools sit on top of.
Resolved MC entries move to [gaps-archive.md](gaps-archive.md).

## MC-26 — `search_symbols` finds types only, not methods or fields

**Status: deferred** (2026-11-15: bug fixes only for now; a new tool or view waits)

**Kind: feature**

**Value: low — Size: M**

Found by the agent-tooling landscape review (2026-10-24).

### Gap

IntelliJ's `search_symbol` finds classes, methods and fields, and gopls's `go_search` searches all
symbols. For "where is method X declared" Lathe has nothing, so the agent greps.

Seen again by an agent's review on Helidon (2026-11-01): `search_symbols doRoute` answers nothing, so an agent falls back to grep.

### Fix direction

Needs a member-name index; reactor members first (the reactor bytecode and shards hold most of the
data), dependencies later. Design first: index size and freshness on Helidon.
Related: [EG-057](#eg-057--symbol-search-matches-camelhumps-only-in-reactor-types-not-in-jdk-and-dependency-types).

### Regression targets

None yet.

---

## MC-27 — No view of a type's API by name: members, supertypes and the first javadoc line

**Status: deferred** (2026-11-15: bug fixes only for now; a new tool or view waits)

**Kind: feature**

**Value: low — Size: M**

Found by the agent-tooling landscape review (2026-10-24).

### Gap

To learn what a dependency's `Builder` offers, an agent must locate and read its source.
gopls's `go_package_api` and Serena's `find_symbol(depth=1)` answer that in one call.
Lathe's `describe_symbol` needs a position, and MCP has no type hierarchy.

### Fix direction

A `describe_type` tool taking a (qualified) type name: its supertypes, and each member's signature with
its first javadoc line, from javac's `Elements` — so it works for a dependency without sources too.
It also covers the type hierarchy MCP lacks, which an agent asked for again on 2026-10-30
(see also [MC-55](#mc-55--no-tool-finds-the-instanceof-and-pattern-match-sites-for-a-type)).

### Regression targets

None yet.

---

## MC-29 — No tool maps a stack trace to source

**Status: deferred** (2026-11-15: bug fixes only for now; a new tool or view waits)

**Kind: feature**

**Value: low — Size: M**

Found by the agent-tooling landscape review (2026-10-24).

### Gap

A failing test or a log gives an agent a stack trace; each frame names a class and a line, not a file.
LSP4J-MCP has `resolve_stack_trace`. Lathe's editor already resolves stack frames through the type
index, including package-private dependency types.
The editor-neutral `lathe.stack.resolve` command shipped in `e4896954`; exposing a stack-trace tool through MCP remains open.

### Fix direction

A `resolve_stack_trace` tool turning each frame into a `path:line: code` line (reactor, generated,
dependency and JDK sources), and `run_test` failures could use it for their frames.

### Regression targets

None yet.

---

## MC-31 — `get_definition` cannot return the definition's body

**Status: deferred** (2026-11-15: bug fixes only for now; a new tool or view waits)

**Kind: feature**

**Value: medium — Size: M**

Found by the agent-tooling landscape review (2026-10-24);
confirmed by an agent's exploration of a private 61-module Maven service (2026-10-30).

### Gap

`get_definition` returns one line; reading a dependency or JDK method then takes a second read of a
cache path. mcp-language-server's `definition` returns the implementation, and Serena's
`find_symbol` has `include_body`.

In the 2026-10-30 session the agent's pattern was `outline`, then a whole-file read.
That read was its largest single cost: about 11k tokens for a file when it needed four methods,
although `outline` already knows each member's line range.

### Fix direction

An optional `includeBody` returning the declaration's source range (the javac tree span), capped.
Also a tool returning one member's source by name (`source("OrderCommand#store")`),
resolved the way [MC-30](gaps-archive.md#mc-30--position-tools-need-an-exact-column-which-agents-get-wrong--done) resolves a name path.

### Regression targets

None yet.

---

## MC-39 — `rename_symbol` refuses a generated symbol without naming the blueprint method to rename

**Status: deferred** (triaged 2026-11-16: waits on FR-020's generator link)

**Kind: bug**

**Value: low — Size: S**

**Affects: MCP `rename_symbol`.**

Found by the pre-release probes (2026-10-27) on Helidon.

### Observed behaviour

`rename_symbol` on a Helidon generated builder method (`allowAll`) correctly refuses it, but does not name the
blueprint method (`AllowListConfigBlueprint.java:51`) to rename instead.
The ordering half of this gap is done: capped answers list hand-written sources before generated ones (MC-70).

### Fix direction

When refusing a generated symbol, name its blueprint declaration. Waits on FR-020's generator link, which
connects a generated member to the declaration it was generated from.

Verified against the code (2026-11-16): the refusal (`RenameService`) is still the generic "rename the source it is generated from instead", and the generator link does not exist.
MC-70's resolution also mentions "MC-39" for ranking generated types after hand-written ones in `search_symbols`; that is the ordering half, not this entry's open half.

### Regression targets

`LatheEngineNavigationTest.referencesAndCallers_handWrittenBeforeGenerated_cappedToTheHandWritten` (the ordering half).

---

## MC-50 — `call_hierarchy` returns one level per call

**Status: deferred** (2026-11-15: a new capability, not a defect; one level per call is what the tool says it answers)

**Kind: feature**

**Value: medium — Size: M**

**Affects: MCP `call_hierarchy`.**

Found by an agent's exploration of a private 61-module Maven service (2026-10-30).

### Observed behaviour

Tracing a resource → service → adapter chain takes one `call_hierarchy` call per level, each needing a fresh position.

### Fix direction

A `depth` argument returning a tree, with cycles cut and a total cap.
Most useful together with [MC-48](gaps-archive.md#mc-48--outgoing-call_hierarchy-lists-where-each-callee-is-defined-not-where-it-is-called--done)
and [MC-49](gaps-archive.md#mc-49--call_hierarchy-cannot-leave-out-jdk-and-dependency-calls--done), or the tree fills with JDK calls.

### Regression targets

None yet.

---

## MC-55 — No tool finds the `instanceof` and pattern-match sites for a type

**Status: deferred** (2026-11-15: bug fixes only for now; a new tool or view waits)

**Kind: feature**

**Value: medium — Size: M**

**Affects: MCP; the editor has no such search either.**

Found by an agent's exploration of a private 61-module Maven service (2026-10-30).

### Observed behaviour

The service picks an adapter through a 180-line `instanceof` chain over its config types.
`find_implementations` cannot navigate that, and `find_references` on a type mixes the tests
with imports, declarations and every other use.

### Fix direction

A filter on `find_references` (or its own tool) keeping the uses that test a value's type:
`instanceof`, type patterns in `switch` and record patterns, and casts.
These are tree kinds javac already gives the reference search, not text matches.
Related: [MC-27](#mc-27--no-view-of-a-types-api-by-name-members-supertypes-and-the-first-javadoc-line) for the type hierarchy.

### Regression targets

None yet.

---

## MC-56 — No map of JAX-RS routes and their wiring

**Status: deferred** (2026-11-15: bug fixes only for now; a new tool or view waits)

**Kind: feature**

**Value: medium — Size: L**

**Affects: MCP.**

Found by an agent's exploration of a private 61-module Maven service (2026-10-30).

### Observed behaviour

"Which endpoint needs which role" and "in what order do the request filters run" were key questions.
The agent grepped for `@Path` and the auth annotations and still could not establish the filter order.

### Fix direction

A `routes` tool listing each HTTP method and full path (class plus method `@Path`) with its resource method
and the security annotations on it (`@RolesAllowed`, `@PermitAll` and custom ones),
plus the `@Provider` filters and interceptors with their `@Priority` and name bindings.
All of it is annotation data in the attributed trees, so it suits Lathe; registration in code
(`register(...)` calls) is the part a static view can only list, not order.
Framework-specific: JAX-RS first (Jersey, Dropwizard, Helidon MP); decide the scope before others.

### Regression targets

None yet.

---

## MC-57 — No search for code that shares a string identifier

**Status: deferred** (2026-11-15: bug fixes only for now; a new tool or view waits)

**Kind: feature**

**Value: low — Size: M**

**Affects: MCP.**

Found by an agent's exploration of a private 61-module Maven service (2026-10-30).

### Observed behaviour

Job names, queue names, topic names and config keys tie modules together through equal string literals
(`"order-export"` in a batch application and in a service that schedules it).
Lathe cannot connect them; grep did.

### Fix direction

Find the uses of a string constant's value: the literal and the constants holding it,
resolved through `static final` constants (javac's constant values), across the reactor.
Group a query's hits by value so one call links every module using the name.
Java sources only; configuration files are out of scope.

### Regression targets

None yet.

---

## MC-61 — No overview of how a type's members are used

**Status: deferred** (2026-11-15: bug fixes only for now; a new tool or view waits)

**Kind: feature**

**Value: low — Size: M**

**Affects: MCP.**

Found by a refactoring review of Lathe itself through its own MCP server (2026-10-30).

### Observed behaviour

Planning to split a large class needs to know which of its members are used from outside it, and by whom
(production or tests, which classes).
`find_references` answers for one member per call; `WorkspaceSession` has 306 methods.
The agent grepped the test file for `WorkspaceSession.<name>(` instead, which finds static calls only.

### Fix direction

A per-member summary of a type: each member with its counts of uses from inside the type,
from other production classes and from tests, and the classes involved, with the unused ones listed.
`analyze_change` already counts production and test uses for one symbol; this is that over every member.

### Regression targets

None yet.

---

## MC-64 — No safe delete: removing a symbol is find-then-edit by hand

**Status: deferred** (2026-11-15: bug fixes only for now; a new tool or view waits)

**Kind: feature**

**Value: low — Size: M**

**Affects: MCP.**

Found by the survey of other Java MCP servers (2026-11-01).

### Gap

To remove a method an agent runs `find_references`, then edits, then `verify_change`.
NetBeans' Dukehoff MCP and Serena have `safe_delete`: delete only when nothing uses the symbol,
else list the uses that block it.

### Fix direction

A `safe_delete` tool on the rename pipeline: refuse with the blocking uses (production before tests),
else remove the declaration and report the file.
Overrides and implementations count as uses.

### Regression targets

None yet.

---

## MC-65 — No structural search and replace

**Status: deferred** (2026-11-15: bug fixes only for now; a new tool or view waits)

**Kind: feature**

**Value: low — Size: L**

**Affects: MCP.**

Found by the survey of other Java MCP servers (2026-11-01).

### Gap

Migrations (JUnit 4 to 5, `javax` to `jakarta`, a deprecated API to its replacement) are many similar
edits an agent makes one file at a time.
NetBeans' Dukehoff MCP exposes NetBeans' Jackpot rules through `apply_rule`, with a dry run and rule packs,
and reports that agents used it unprompted once `CLAUDE.md` mentioned it.

### Fix direction

Design first: a pattern language over javac trees with typed placeholders (`$x.foo($y)` where `$x` is a
`java.util.List`), a dry run listing the matches, then apply.
Weigh against what a rename plus `fix_imports` already covers.

### Regression targets

None yet.

---

## MC-66 — `rename_symbol` has no preview

**Status: deferred** (2026-11-15: bug fixes only for now; a new tool or view waits)

**Kind: feature**

**Value: low — Size: S**

**Affects: MCP `rename_symbol`.**

Found by the survey of other Java MCP servers (2026-11-01).

### Gap

A rename writes to disk at once.
IntelliJ's rename takes `preview: true` and answers the files and usages it would change, and its
conflicts, writing nothing; numsu/java-lsp-mcp never writes and returns the edits instead.
An agent unsure of a rename's reach has `analyze_change`, which counts references but does not list the edits.

### Fix direction

An optional `preview` returning the same per-file list without applying it.
Low value while renames are undone by git as easily as they are made.

### Regression targets

None yet.

---

## MC-85 — `verify_change` says nothing about uses in modules the sync leaves out

**Status: deferred** (triaged 2026-11-16: waits on `verify_change` telling an API change from a body change)

**Kind: bug**

**Value: medium — Size: S**

**Affects: MCP `verify_change`.**

Found by the exploratory MCP round (2026-11-04) on Helidon.

### Observed behaviour

Renaming `ServerFeature.setup` left `webserver/tests/webserver/.../FeaturesOrderingTest.java:268` broken: that module is
behind the root `tests` profile and not synced.
`rename_symbol` now lists the Java files outside the built modules that name the symbol;
`verify_change` still checks only the synced modules and says nothing about the others.

### Fix direction

List the files outside the built modules that name a changed type only when its API changed:
for a change inside a method body the same list would come back on every call, and agents learn to skip it.
Waits on `verify_change` telling an API change from a body change.

Verified against the code (2026-11-16): still present, and the blocker stands.
`LatheEngine.verifyChange` never calls `OutsideSources` (only rename does), and `ChangeVerifier` compares content fingerprints and finds callers by token and constant-pool matches; nothing diffs signatures or members.
A `verify_change` version also has to choose which names to search for, since rename searches the one old name.

### Regression targets

`LatheEngineRenameTest.rename_methodUsedInAnotherFile_rewritesBothFilesOnDisk_listsTextAndFilesOutsideModules` (the rename half).

---

## MC-86 — Smaller answer defects from the exploratory round

**Status: deferred** (2026-11-15: the defects that gave wrong answers are fixed; what is left is below)

**Kind: bug**

**Value: low — Size: S each**

**Affects: MCP tools.**

Found by the exploratory MCP round (2026-11-04) on Dropwizard, EqualsVerifier and Helidon.

### Observed behaviour

- `verify_change` checks callers 50 to a javac task, and javac skips flow analysis once a task has an error, as a Maven build does:
  while one caller fails, another in its batch that only lacks a `return` or an exception declaration is not listed.
- `get_definition` on a `requires` module name answers "No definition found" without saying modules are not navigable.
- The rename review list lists every `"get"` literal for a short name.
- `search_symbols` labels an annotation "(Interface)".

### Fix direction

Each is small; take them one by one when touching the tool.
The rest stay as they are (decided 2026-11-15):
- The annotation label and the `requires` module answer each need a reason carried from the analysis to the answer
  (the hit's kind past the symbol resolver; why a definition is empty): more plumbing than a rare query is worth.
- A short name's literals are what the review list is for: a reflective call names a member by its bare name.

Out of scope: `workspace_summary`'s size (about 46 KB on Helidon) is its point, every module named once with its
reactor dependencies already capped at ten; a smaller answer would drop the module graph.

### Regression targets

- `LatheEngineVerifyTest.analyzeChange_interfaceMethod_reportsOverrideFamilyAndReferences` (`analyze_change` counts uses, not declarations; fixed 2026-11-15)
- `LatheEngineNavigationTest.diagnostics_moreThanAHundredErrors_reportsEveryOne` (analysis reports past javac's 100-error limit; fixed 2026-11-15)
- `LibrarySourcesTest.originLabel_reactorModuleOnTheModulePath_namesTheBuildModule` (`describe_symbol`'s `source:` names a reactor module alike from every caller; fixed 2026-11-15)
- `LatheEngineEditTest.fixImports_supertypeNestsAName_importsTheSupertypeOnly` and `fixImports_genericUse_onlyTheTypeTakingItsTypeArguments` (`fix_imports` picks the inherited nested type and the generic type; fixed 2026-11-15)
- `TextMentionsTest.inStringLiterals_literalNamingIt_notCodeProseOrALongerWord` (a rename's text mention points at the name, in a text block on its own line; fixed 2026-11-15)

---

## MC-87 — One MCP server answers for one worktree only

**Status: deferred** (2026-11-15: bug fixes only for now; a new tool or view waits)

**Kind: feature**

**Affects: MCP, with several git worktrees.**

Found by the comparison with [java-index](https://github.com/slgobinath/java-index) (2026-11-05), whose tools all take an optional `worktree` path and share one daemon per repository.

### Gap

An agent working across worktrees needs one Lathe MCP server per worktree, each with its own JVM and indexes.

### Fix direction

Design first; it overlaps [Shared Workspace Server](../potential/lathe-shared-workspace-server.md), which keeps one process per client until measurements show a need.

### Regression targets

None yet.

---

## MC-96 — `verify_change` fails whenever a dependent module has Kotlin sources in a Maven source root

**Status: documented**

**Kind: bug**

**Affects: `verify_change` on a Maven reactor where a module that depends on the change registers a Kotlin source root (jdbi's `kotlin-sqlobject`, `src/test/kotlin`).**

Found by the MCP exploratory round on jdbi (2026-11-17).

### Observed behaviour

After any edit in jdbi's `core` (a new method nobody calls, or a 54-file rename), `verify_change` answers only `the request failed; see the server log`.
The log: `IllegalArgumentException: Compilation unit is not of SOURCE kind: "/tmp/lathe-compile-…/org/jdbi/v3/sqlobject/kotlin/TestIssue2961.kt"`, from `JavacRunner.createTask` under `JavacRunner.attributeBatch` while checking the dependents.
The `.kt` file is `kotlin-sqlobject/src/test/kotlin/…/TestIssue2961.kt`.
With nothing changed, or after a deletion, `verify_change` answers normally; on resilience4j (Gradle, Kotlin module `resilience4j-kotlin`) it works.
On jdbi an agent cannot check any change to `core`.

### Fix direction

Never hand a non-`.java` file to javac when collecting a dependent module's sources; check the rest and name the Kotlin sources as not checked, as the other tools already do (`Kotlin sources in … are not searched`).

### Regression targets

None yet.

---

## MC-97 — A deferred `verify_change` tells the agent to run the sync that is already running

**Status: documented**

**Kind: bug**

**Affects: `verify_change` while the project's own `.lathe/lathe-sync.sh` (or any build Lathe sees writing `.lathe/`) runs.**

Found by the MCP exploratory round on resilience4j (2026-11-17).

### Observed behaviour

During a `latheSync`, every `verify_change` answers at once ``Verification deferred: a build is in progress. Run `.lathe/lathe-sync.sh`, then re-check.``; the first call after the build ends checks normally.
Not stalling is right, but the advice is to start a second build while the first runs; an agent following it doubles the wait.
`McpText.verifyChangeResult` names the suggested sync whenever there is one, whatever the deferral.

### Fix direction

For a build in progress, say to wait for it to finish and re-check; name the sync only when what is missing is one.

### Regression targets

None yet.

