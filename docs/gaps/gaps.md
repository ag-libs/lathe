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

**Status: documented (symbols, semantic tokens and module-body folding shipped; package-Javadoc folding remains).**

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

## EG-067 — The first build floods the log with warnings, and a disabled Lathe says nothing

**Status: documented**

**Value: medium — Size: S**

Found by the pre-release probes (2026-10-27) on EqualsVerifier.

### Observed behaviour

The README quick start (`mvn clean test -Dlathe.capture.only=true`) prints 199 `[WARNING]` lines on
EqualsVerifier, a small reactor:

- 164 are Surefire's `Tests run: N, Failures: 0, Errors: 0, Skipped: N`, one per test class, because
  capture-only excludes every test and Surefire reports skipped classes as warnings.
- 9 are blocks of `The following dependencies could not be resolved at this point of the build but seem to
  be part of the reactor`, printed before `lathe:sync` on the parent.
  `lathe:sync` is an aggregator with `requiresDependencyResolution = TEST`, bound to the parent's
  `process-test-classes`, which runs before any child module is built.
  The sync itself is complete.

A first-time user sees a wall of warnings from a command the README told them to run; a Helidon-size
reactor prints thousands.

The other way round, `CI=true` or `-Dlathe.disabled=true` switches Lathe off with no line at the default
log level, so a developer whose environment sets `CI` gets no hint why `.lathe/` stops updating.

### Fix direction

Find a capture-only filter that Surefire does not count as skipped, or say in the README and test-capture
guide that the warnings are expected.
Resolve the sync's dependencies without the reactor warnings, or bind the sync to a later project.
Log one `INFO` line when Lathe is disabled, naming the switch.

### Regression targets

None yet.

---

## EG-069 — Deeply nested code that javac compiles ends the server with a stack overflow

**Status: deferred.**

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

## EG-074 — Log noise: a per-module INFO line, and a JDK warning on the MCP server's stderr

**Status: deferred** (triaged 2026-11-03: low severity for the editor)

**Value: low — Size: S**

**Affects: the editor and MCP.**

Found by the pre-release test rounds (2026-10-29/30) on EqualsVerifier and Dropwizard.

### Observed behaviour

- `[compile] dropped 10 forked-javac -J option(s) …` is logged at INFO for every module compiler created,
  four times in one second on EqualsVerifier.
- On JDK 25, Guava's `sun.misc.Unsafe` deprecation warning reaches the MCP server's stderr during in-process compiles.

### Fix direction

Log the dropped options once per workspace, or at FINE.
Keep compile-time library warnings out of the server's stderr.

### Regression targets

None yet.

---

## EG-077 — No "go to test": jump from a class to its test, or create one, and back

**Status: accepted (server and Neovim shipped; VS Code pending).**

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

## EG-078 — No formatter detection on a Gradle build

**Status: documented**

**Value: medium — Size: M**

**Affects: formatting in the editor and `format_file` on Gradle builds.**

Found while documenting the Gradle plugin (2026-10-30), from its design notes.

### Observed behaviour

On Maven the sync reads the project's Spotless configuration and runs the exact formatter release it pins.
The Gradle plugin reads no formatting plugin, so a Gradle project formats only through a committed
`lathe-style.json` with a command engine.

### Fix direction

Detect the Spotless Gradle plugin's Java step (its name is readable; its version and options are not, per the
design spike), or document the `lathe-style.json` recipe as the supported path.

### Regression targets

None yet.

---

## EG-083 — The outline of `module-info.java` shows only the module line

**Status: deferred** (triaged 2026-11-03: low severity for the editor)

**Value: low — Size: S**

**Affects: MCP `outline` and the editor's document symbols.**

Found by an agent's review of the MCP tools on Helidon (2026-11-01).

### Observed behaviour

`outline` on a `module-info.java` lists the `module` declaration only, not its `requires`, `exports`,
`opens`, `uses` and `provides` directives.

### Fix direction

Emit each directive as a child of the module symbol, from the module declaration's tree.

### Regression targets

None yet.

---

## EG-084 — Diagnostics skip the build's javac plugins, and contradict the notes that run them

**Status: documented**

**Value: medium — Size: M**

**Affects: MCP `get_diagnostics` (and the editor's diagnostics, which compile the same way).**

Found by an agent's review of the MCP tools on Dropwizard (2026-11-01). Related: CA-18.

### Observed behaviour

`get_diagnostics` answers "No errors or warnings in LatheProbe.java" and, in the same reply,
"Not compiled: … [NullAway] …" (also seen with `[ReturnValueIgnored]`). Plain javac errors are reported,
and `--release 17` is honoured: the open compile runs without the build's `-Xplugin` (Error Prone,
NullAway), while the check behind the notes runs with them.

### Fix direction

Run the build's javac plugins in the open compile when the module's compile args have them,
or at least report the notes' plugin errors as diagnostics instead of contradicting them.

### Regression targets

None yet.

---

## EG-091 — No inlay hints

**Status: documented**

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

**Status: documented**

**Affects: the editor's hover and MCP `describe_symbol`.**

Split from EG-093, whose overrides and deprecation notes shipped.
The comparison with [java-index](https://github.com/slgobinath/java-index) (2026-11-05) also showed how often a symbol is used.

### Fix direction

Add the count once a reference count is cheap to compute, which today it is not (FR-029).

### Regression targets

None yet.

---

## EG-094 — A deprecated API's use does not suggest its replacement

**Status: documented**

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

**Status: documented**

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

# Find References Gaps (FR)

Active references and rename gaps.
Resolved FR entries are in [gaps-archive.md](gaps-archive.md).

## FR-019 — Renaming one member of a statically imported shared name is refused rather than done

**Status: deferred** (triaged 2026-11-03: low severity for the editor)

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

**Value: medium — Size: M**

**Affects: MCP and the editor.**

Found by the pre-release test rounds (2026-10-29/30) on Helidon.

### Observed behaviour

`rename_symbol` on `AllowListConfigBlueprint.allowAll()` edits 4 places in 3 files, but not the callers of the generated `Builder.allowAll(boolean)`; the build then fails with 21 errors.
The answer does warn ("Not compiled: TrustedProxyMatcher.java:76"), and `verify_change` reports the errors.
0.1.24 behaves the same.

### Fix direction

Refuse a blueprint rename with the reason, as for Lombok members, or rename the generated members' callers too when the generator links them.

Also (exploratory round, 2026-11-04): renaming Dropwizard's JMH `parseDuration` leaves 10 generated callers with no note; `verify_change` says clean while `get_diagnostics` on the generated file shows the errors.

### Regression targets

None yet.

---

## FR-029 — References to a widely used method take 12–27 s on a very large codebase

**Status: documented**

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

### Fix direction

1. **Narrow static methods to their family, exactly.**
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

### Regression targets

None yet.

---

## FR-032 — Implementations of an interface method and incoming calls take seconds on a large codebase

**Status: documented**

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

Stage 3 of the [comparison plan](../planned/lathe-jdtls-comparison.md) lists compiling a module's candidate files in batches instead of one javac task per file; FR-029's per-module parallelism applies here too.

### Regression targets

None yet.

---

# Code Action Gaps (CA)

Active `textDocument/codeAction` provider gaps. Resolved CA entries are in
[gaps-archive.md](gaps-archive.md).

## CA-7 — Pasting code with unresolved types requires per-symbol quick fixes; no whole-file "add missing imports"

**Status: deferred (add-missing slice shipped; automatic invocation deferred).**

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

# Completion Gaps (CQ)

Active completion-quality gaps. Discovered and triaged via the completion appendix of the
[gap workflow](gap-workflow.md); checked against the completion [expectations](../done/lathe-completion-expectations.md)
contract. Resolved CQ entries are in [gaps-archive.md](gaps-archive.md).

## CQ-0041 — Module directives complete, but annotation arguments on a module descriptor do not

**Status: documented (module-directive completion shipped; module-annotation arguments remain).**

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

## CQ-0062 — A member overriding one with a type-use-annotated parameter may be listed twice

**Status: documented**

**Affects: member completion, on a server running JDK 21 or older, or for a `SOURCE`-retained annotation.**

Found reviewing the FR-031 fix (2026-11-08); suspected from the code, not yet reproduced.

### Suspected behaviour

`SimpleNameProvider.memberKey`, which keeps a member and an inherited one of the same signature from both being listed, keys a method on its parameters' type text (`asType().toString()`).
FR-031 showed that text carries type-use annotations for a type compiled from source and lacks them for the same type read from a class file on JDK 21 and older.
A source override of a library method declared `void put(@NonNull String key)` (or the reverse) would then key differently from the inherited method and be listed twice.

### Probe

A class compiled from source overriding a method of a class-file supertype whose parameter carries a `SOURCE`-retained type-use annotation; member completion on an instance should list the method once.
The likely fix keys on `ReferenceTarget.erasedTypeName`, which names a type without its annotations.

### Regression targets

None yet.

---

# Workspace Lifecycle Gaps (WS)

Workspace freshness and lifecycle gaps: reactor mirror / type-index staleness, source watching, sync
prompting, and reload. Resolved WS entries are in [gaps-archive.md](gaps-archive.md).

## WS-17 — The server JVM has no heap cap and grows toward a quarter of the machine's memory

**Status: deferred.**

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

## WS-21 — A rename racing a build can leave the tree broken

**Status: documented**

**Value: medium — Size: M–L**

**Affects: MCP.**

Found by the pre-release test rounds (2026-10-29/30) on EqualsVerifier.

### Observed behaviour

With a Maven sync running, an agent's `rename_symbol` succeeded on disk (31 edits in 16 files), but the next `verify_change` reported 27 "cannot find symbol" errors for the new name: the server's compiled class was the pre-rename one.
Renaming back then changed only the declaration, leaving 30 uses of the dropped name and 29 errors.
2 of 16 rounds with a sync running, none without.
Likely cause: the sync stamps a source from the file on disk when it mirrors, after javac compiled an older content, so a stale class looks current.
0.1.24 shares the stamping code.

### Fix direction

Stamp what javac compiled (its read content or fingerprint), not the disk at mirror time.
`rename_symbol` could also defer, or warn, while a build is running, as `verify_change` does.

### Regression targets

None yet.

---

## WS-24 — An unreadable `workspace.json` is not reported to the editor and is never re-read

**Status: deferred** (triaged 2026-11-03: low severity for the editor)

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

## WS-26 — A second agent session can answer from stale state while the first one writes

**Status: documented**

**Value: low — Size: S–M**

**Affects: MCP, two sessions on one workspace.**

Found by the pre-release check (2026-11-04) on Helidon, with `dev/mcp_staleness.py` (scenario I4): 1 failure in 13 runs.

### Observed behaviour

Session A fixes an upstream file on disk; session B, asked about the downstream file straight after, still reports
the `cannot find symbol` the fix removed, while A reports it clean.
B's two queries (I3, I4) each took 2.05 s instead of the usual 0.3–0.5 s: B waited for the workspace write lock, held
by A's write pass, then stopped waiting and answered from its own state, as a query does after one lock wait.
Only A and B ran on the workspace then.
It did not reproduce in 9 runs of the scenario alone and 3 runs of the whole Helidon probe, all with `LATHE_DEBUG=1`.

The pre-release check of 2026-11-10 (main at `3403329e`) shows the wait without the stale answer:
on Dropwizard, B's I3 took 1.5–1.7 s in all 4 runs and I5 0.2–1.5 s, against 0.2 s on 2026-11-06; every answer was right.
With `LATHE_DEBUG=1`, B logged nothing for about 1.2 s, then scanned and compiled in about 0.2 s:
a pass that finds the write lock held retries on the next scan tick (`WritePass.runLocked`, 2 s), not when the lock is released.

### Fix direction

Retry the lock as soon as the peer releases it, or at a shorter interval than the scan tick, so a query waits only as long as the peer's pass.
Find what held the lock for about 4 s: A's write of the fix, perhaps followed by the immediate retry of the downstream
file that failed before (the WS-20 change). Then either let a query wait out a short pass by its peer, or have the
answer say it may be stale while another server writes (as "A build is running" does for a build).

### Regression targets

None yet.

---

## WS-27 — On Gradle, included builds are not covered

**Status: documented**

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

## WS-30 — The JDK type index lists the build's JDK, not the project's

**Status: documented**

**Value: low — Size: S**

**Affects: completion and type search of JDK types, when a build compiles with a different JDK than it runs on (a toolchain).**

Found while reviewing the JDK cache lock (2026-11-05).

### Observed behaviour

`JdkTypeIndexSync` records the index as the project's JDK (`source.home()`, vendor and version, which also key its cache entry), but `scanJdk` reads the `jrt:/` file system of the JVM the sync runs in: the build's own JDK.
When the two differ (Maven or Gradle on JDK 25 compiling with a JDK 17 toolchain), the index lists the build JDK's types: completion and type search offer types the project's JDK lacks, and miss any it has that the build JDK does not.
The cache entry is keyed by the project's JDK, so the first build to index it decides its content for every later build.

### Fix direction

Scan the project's JDK: open its `jrt:/` through `source.home()` (`FileSystems.newFileSystem(URI.create("jrt:/"), Map.of("java.home", ...))`, public API since JDK 9), and keep the build JDK's only when the two are the same.

### Regression targets

None yet.

---

# Test Execution Gaps (TE)

Capture/replay gaps involving the Maven test fork, the recorded launch template, and the standalone
replay JVM. Resolved TE entries are in [gaps-archive.md](gaps-archive.md).

## TE-3 — `<systemPropertyVariables>` are not carried into the replay launch

**Status: deferred**

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

## TE-10 — `run_test` on a method name shared by two top-level classes runs only the first

**Status: documented**

**Value: low — Size: S**

**Affects: MCP.**

Found by the pre-release test rounds (2026-10-29/30) on a fresh Maven project.

### Observed behaviour

With two top-level test classes in one file both declaring `method`, `run_test` with `scope=method` runs only the first class's; its `Ran:` line names that class, and the second goes unmentioned.
Class scope runs every top-level class (archived [TE-9](gaps-archive.md#te-9--running-a-file-with-two-top-level-test-classes-runs-only-the-first--done)).

The same happens with a `@Nested` class: a method name in both the outer class and a nested one runs only the outer one, and the ambiguity is not reported (agent review on Dropwizard, 2026-11-01).

### Fix direction

Run every match, as the class scope does, or ask which class is meant.

### Regression targets

None yet.

---

## TE-11 — A test run whose Surefire tests a copy of the classes replays the last build's classes

**Status: deferred** (2026-11-04: the silent part fixed; replaying the copy from `.lathe/` deferred)

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


# Debug & Evaluation Gaps (DB)

Gaps in the in-process DAP adapter and the two-stage expression evaluator (see [lathe-run-test-debug.md](../done/lathe-run-test-debug.md) §12.11).
Breakpoints, stepping, inspection, conditional breakpoints, and read/invoke expression evaluation all work;
the entries below are what remains.

## DB-1 — Expression evaluation is read/invoke only; no assignment or `setVariable`

**Status: deferred**

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

`LatheSourceLookUpProvider.breakpointLocation` → `SourceAnalysisSession.enclosingBinaryName` takes the class name only from the module session's cache of attributed open documents.
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

**Status: documented**

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

Report it to blink.cmp: the line must be the active signature's index in its own buffer, not Neovim's.
Until it is fixed, Lathe could list the active signature first (`activeSignature` 0) in `SignatureHelpResolver`,
which every client renders correctly, at the cost of the overload order changing as the arguments change.

Related, on the server: when the call does not attribute yet, `SignatureHelpResolver` now makes active the first
overload whose arity fits the arguments typed, not the first declared
([EG-088](gaps-archive.md#eg-088--signature-help-is-empty-inside-new-p-and-super-when-no-constructor-takes-zero-arguments--done)).

### Regression targets

None yet.

---

# MCP / Agent Facade Gaps (MC)

Gaps in the in-process `LatheEngine` facade and the `lathe-mcp-server` tool surface that exposes
Lathe to AI agents, as distinct from the LSP/DAP server behaviour those tools sit on top of.
Resolved MC entries move to [gaps-archive.md](gaps-archive.md).

## MC-26 — `search_symbols` finds types only, not methods or fields

**Status: documented**

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

**Status: documented**

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

**Status: documented**

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

**Status: documented**

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

## MC-39 — `find_references` lists generated code before the user's call sites

**Status: documented**

**Value: low — Size: S**

**Affects: MCP `find_references`.**

Found by the pre-release probes (2026-10-27) on Helidon.

### Observed behaviour

On a Helidon builder method (`allowAll`), all of the first `maxResults=4` results are `[GENERATED]` builder
internals, and none is a user call site.
`rename_symbol` there correctly refuses generated code, but does not name the blueprint method to rename
instead.

### Fix direction

Order hand-written sources before generated ones (and production before tests, as `analyze_change`
counts them), so a capped answer shows what an agent edits.
When refusing a generated symbol, name its blueprint declaration when the generator links one.

Also (exploratory round, 2026-11-04): `search_symbols` lists the generated `ListenerConfig` and `ListenerConfigImpl` before the hand-written `ListenerConfigBlueprint`.

### Regression targets

None yet.

---

## MC-41 — MCP edits written to a CRLF file add LF line endings

**Status: deferred.**

Decision (2026-10-27): a real but narrow problem; a fix was written and reverted (see below).
**Affects: MCP `fix_imports`.**

Found by the pre-release probes (2026-10-27) on EqualsVerifier.

### Observed behaviour

Server edits break lines with `\n`, which is right for an editor: it applies them in its buffer, in the
document's own line separator.
`DiskEdits.applyFileEdits` writes them to disk verbatim, so in a CRLF file (Git for Windows'
default `core.autocrlf=true` checkout) `fix_imports` leaves mixed line endings:

```
package nl.jqno.equalsverifier;\r\n
\n
import java.util.Objects;\n
\r\n
public class LatheCrlf {\r\n
```

Only `fix_imports` inserts line breaks today: `rename_symbol` replaces identifiers, and `format_file`
writes the formatter's output, which keeps one separator.
The effect is small: `git` with `autocrlf=true` normalizes the lines on commit; a local Spotless
`check` (line endings from git attributes, so CRLF on such a machine) likely reports the file until it is
formatted; Vim and Emacs show `^M` on the original lines; VS Code picks one separator. Placement is
unaffected, since javac's line map counts `\r\n` as one break.
The tests do not cover it: their sources are text blocks, so LF even on the Windows CI row.

### Fix direction

Convert at the disk-write boundary only, never in the edits an editor applies (a `\r` inside Neovim
buffer lines): in `DiskEdits.applyFileEdits`, write each edit's line breaks in the file's separator,
normalizing a `\r\n` already in an edit first so a CRLF formatter result does not become `\r\r\n`.
Keep the check private to that method; the reverted attempt shared a `lineSeparatorOf` helper with
`EclipseFormatEngine` through `SourceLocator`, which was the wrong home.
Matching the separator of the line an edit lands next to, instead of a whole-file guess, would also
handle files that are already mixed.
Tests must build CRLF content explicitly (`"…\r\n"`): `fix_imports` and `format_file` on a CRLF file,
asserting no bare `\n` and no `\r\r`.

### Regression targets

None yet.

---

## MC-45 — Read tools open `.java` files outside the workspace, through symlinks too

**Status: documented**

**Value: low — Size: S**

**Affects: MCP.**

Found by the pre-release test rounds (2026-10-29/30) on EqualsVerifier (fuzzing).

### Observed behaviour

The read tools accept a `.java`-named path outside the workspace, including a symlink to `/etc/passwd`, whose third line was echoed back.
Writes outside the reactor are refused.

### Fix direction

Resolve the real path and answer only for files under the workspace, its dependency sources and the JDK's.

### Regression targets

None yet.

---

## MC-50 — `call_hierarchy` returns one level per call

**Status: documented**

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

## MC-52 — External locations still repeat full URIs, with no dependency/JDK path shorthand

**Status: documented (workspace-relative result paths shipped; external-path shorthand remains).**

**Value: medium — Size: S**

**Affects: MCP tools that return locations.**

Found by an agent's exploration of a private 61-module Maven service (2026-10-30).

### Delivered — workspace-relative result paths

MC-18 already changed reactor and generated locations to workspace-relative paths.
`LatheEngine.displayPath` and the shared `McpText.position` renderer apply this to symbols, references, definitions and call hierarchies.
The original blanket claim that every result repeats a URI is obsolete.

### Remaining — external paths and round-trip shorthand

The original report was:

Each line of `search_symbols` and `call_hierarchy` carries `file:///home/<user>/<workspace>/…`,
the same long prefix repeated, about 30–40% of the tokens of those answers by the agent's estimate.

### Fix direction

Keep the delivered workspace-relative results, and add short prefixes for files outside it (`~deps/…`, `~jdk/…`),
with the expansion stated once in the tool description or `workspace_summary`.
The tools that take a `file` must accept the same short forms back.

### Regression targets

Delivered path rendering: `McpTextTest.locationLine_reactorAndExternal_oneGrepStyleLineTaggedOnlyOutsideTheReactor`.
External shorthand: no regression test yet.

---

## MC-53 — `find_references` does not tell reads from writes

**Status: documented**

**Value: medium — Size: S**

**Affects: MCP `find_references`.**

Found by an agent's exploration of a private 61-module Maven service (2026-10-30).

### Observed behaviour

"Where does the order's status change" needs the writes of a field or enum constant
(assignments, setter and builder calls) apart from its reads (comparisons, getters).
`find_references` lists them all alike, so the agent reads every site.

### Fix direction

The editor's `documentHighlight` already maps each reference's role to read or write.
Label each `find_references` line with it, and add an `access` filter (`read`, `write`).
Builder and setter calls count as writes only by convention; decide whether to include them.

### Regression targets

None yet.

---

## MC-54 — A dependency result does not say which version it came from

**Status: documented**

**Value: low — Size: S**

**Affects: MCP tools that return dependency locations.**

Found by an agent's exploration of a private 61-module Maven service (2026-10-30).

### Observed behaviour

The source cache held three versions of one internal library, and the agent had to work out which one
Lathe had resolved for the module it asked about.

### Fix direction

Name the artifact's `groupId:artifactId:version` on a dependency result,
in whatever short form [MC-52](#mc-52--every-result-repeats-a-full-file-uri) gives dependency paths.

### Regression targets

None yet.

---

## MC-55 — No tool finds the `instanceof` and pattern-match sites for a type

**Status: documented**

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

**Status: documented**

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

**Status: documented**

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

## MC-59 — `outline` overflows on a large file

**Status: documented**

**Value: medium — Size: S**

**Affects: MCP `outline`.**

Found by a refactoring review of Lathe itself through its own MCP server (2026-10-30).

### Observed behaviour

`outline` on `WorkspaceSession.java` (5,324 lines, 428 declarations) answered 57,670 characters, over
Claude Code's result limit; the client saved it to a file and the agent had to slice it with `jq`.
The largest file is where an outline saves the most reading, and the one where the tool fails.
Part of the size was the JSON form, gone since answers are text only ([MC-58](gaps-archive.md#mc-58--claude-code-shows-the-structured-content-so-an-agent-never-sees-the-notes--done)),
but 428 lines of text would still be about 25k characters.

### Fix direction

An optional `depth` (1 = the top-level types and their members, without nested types' members)
and a `kinds` filter (types only, methods only).
Above a size cap, answer with the types and their member counts and say how to ask for one type's members,
instead of an answer the client cuts.

### Regression targets

None yet.

---

## MC-61 — No overview of how a type's members are used

**Status: documented**

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

## MC-63 — `find_references` cannot group its answer by the calling method

**Status: documented**

**Value: medium — Size: S**

**Affects: MCP `find_references`.**

Found by the survey of other Java MCP servers (2026-11-01).

### Gap

A method with forty call sites in ten methods answers forty lines; the agent's question is usually
"which methods call it", and it reads the list to work that out.
NetBeans' Dukehoff MCP takes `group_by: "caller"` and names each containing method by signature.

### Fix direction

An optional `groupBy` (`file`, `caller`): one heading per containing method (or file), its call lines
below it. The enclosing method is already known to the reference search's tree path.

### Regression targets

None yet.

---

## MC-64 — No safe delete: removing a symbol is find-then-edit by hand

**Status: documented**

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

**Status: documented**

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

**Status: documented**

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

## MC-67 — `analyze_change` reports no override family for a method that overrides one

**Status: documented**

**Value: medium — Size: S**

**Affects: MCP `analyze_change`.**

Found by an agent's review of the MCP tools on Helidon (2026-11-01).

### Observed behaviour

For `HttpRouteImpl.beforeStart`, marked `@Override` and overriding `ServerLifecycle.beforeStart`,
`analyze_change` answers "override family: none" while counting 109 references (49 production, 60 test),
a count that only adds up because it also searched the parent method's callers.
The family lists only the methods that override this one, never the ones it overrides,
so the two halves of the answer contradict each other.

### Fix direction

Part of the implementation-search set described under
[EG-085](gaps-archive.md#eg-085--implementations-miss-anonymous-classes-and-enum-constant-bodies--done).

Why: `LatheEngine.analyzeChange` takes the family from the implementation search (`implementationsAt`), which looks
only down the hierarchy; the methods the target overrides never appear.

The fix: add the overridden methods, walking the owner's supertypes and keeping each method the target overrides
(`Elements.overrides`), cheap and without the index; label the two directions ("overrides", "overridden by").
EG-085's change completes the downward half for free, since the family reuses the same search.

Test: an override's `analyze_change` lists its parent method under "overrides".

### Regression targets

None yet.

---

## MC-68 — `analyze_change` has no caller-selected output limit or exact capped counts

**Status: documented (a fixed internal cap exists; caller-selected limiting remains).**

**Value: low — Size: S**

**Affects: MCP `analyze_change`.**

Found by an agent's review of the MCP tools on Helidon (2026-11-01).

### Observed behaviour

On `Handler.handle` it printed all 225 implementations in its override family,
while the other tools take `maxResults`.

### Current behavior

`LatheEngine.IMPACT_MAX_RESULTS` already caps references and implementations at 1,000 each.
`McpText.analyzeResult` marks truncated references, but the tool has no `maxResults` argument, the override-family truncation is not exposed, and the displayed counts are derived from capped references.
The original claim of no limit at all is inaccurate; the user-controlled limit and exact counts remain open.

### Fix direction

Take `maxResults` like the other tools, cap the listed locations, and keep the counts exact with a
"(N more)" line.

### Regression targets

None yet.

---

## MC-69 — `analyze_change` names too few affected modules

**Status: documented**

**Value: medium — Size: S**

**Affects: MCP `analyze_change`.**

Found by an agent's review of the MCP tools on Helidon (2026-11-01).

### Observed behaviour

On `Handler.handle` it names 5 affected modules, while the 225 implementations it found span about 15
(openapi and webclient/http2 among them); changing the method's signature would break all of them.

### Fix direction

Part of the implementation-search set described under
[EG-085](gaps-archive.md#eg-085--implementations-miss-anonymous-classes-and-enum-constant-bodies--done).

Why: `LatheEngine.analyzeChange` counts the modules of reference sites only, never of the override family's members.

The fix: classify the family's locations as the references are (`classifyPaths`) and add their modules, since a
signature change breaks every implementation; EG-085's change brings in the anonymous and enum constant bodies.

Test: a method implemented in a module that never calls it counts that module as affected.

### Regression targets

`dev/mcp_battery.py`'s check "analyze_change modules cover the implementations" on Helidon's `Handler.handle`, known until fixed (on 2026-11-07: 5 modules named, implementations in 14).

---

## MC-70 — Tool results come back unsorted, with modules mixed together

**Status: documented**

**Value: low — Size: S**

**Affects: MCP tools that list locations (`find_implementations`, `analyze_change`, and the like).**

Found by an agent's review of the MCP tools on Helidon (2026-11-01).

### Observed behaviour

Locations come in discovery order, so one module's results are scattered among another's,
and a long answer is hard to scan.

### Fix direction

Sort listed locations by module, then file, then line, in one shared place the tools format through.

### Regression targets

`dev/mcp_battery.py`'s check "find_implementations: modules grouped" on Helidon's `Handler.handle`, known until fixed (on 2026-11-07: 2 modules scattered in 50 rows).

---

## MC-71 — `outline` gives nothing for a file outside the reactor

**Status: documented**

**Value: low — Size: M**

**Affects: MCP `outline` (and the editor's document symbols) on files no module compiles.**

Found by an agent's review of the MCP tools on Helidon (2026-11-01).

### Observed behaviour

Helidon's `tests/`, `examples/` and `webserver/tests/*` are not reactor modules, so they are not indexed.
For such a file, `outline` answers "No declarations in BookService.java", then adds the "Not checked" note,
although a file's structure needs no compile.

### Fix direction

Outline a file no module compiles from a parse alone (no attribution), and keep the note saying it is
not checked.

### Regression targets

None yet.

---

## MC-72 — MCP recompiles an unchanged file on every tool call; parallel calls compile it N times

**Status: documented**

**Value: medium — Size: M**

**Affects: MCP tools, on large files and with agents that call tools in parallel.**

Found watching an MCP session log on Dropwizard (2026-11-01).

### Observed behaviour

Every tool call reads the file from disk and passes it to the analysis as a new document
(`LatheEngine.fileWork`, formerly `compileFromDisk`), so the session compiles it again (`[compile:open]`) even when nothing changed.
Four parallel calls (`find_references` ×3, `describe_symbol`) compiled the same file four times in 50 ms;
three refused renames compiled it three times. It costs 15–40 ms per compile there (186 ms under load),
more on very large files.

### Fix direction

Reuse the last analysis when the file's content equals what was last compiled for it and nothing it depends
on changed since (the compile stamps and the stale scan know); otherwise compile as today.
Coalesce concurrent requests for one file onto one compile.

### Regression targets

None yet.

---

## MC-74 — `search_symbols` lists types the project cannot use, though its description says it excludes them

**Status: documented**

**Value: low — Size: S**

**Affects: MCP `search_symbols`.**

Found by an agent's review of the MCP tools on Dropwizard (2026-11-01). Follow-up to MC-38, which ranks JDK internals last by design.

### Observed behaviour

Results include `ThreadLocal.ThreadLocalMap.Entry`, `Hashtable.Entry` (package-private nested types)
and `com.sun.tools.javac.util.StringUtils` (a JDK internal, ranked last per MC-38),
while the tool's description says such types are excluded.

### Fix direction

Drop nested types that are not public (or not accessible from any reactor package), and make the
description match MC-38's behaviour (internals listed last, not excluded).

### Regression targets

None yet.

---

## MC-75 — `outline` mislabels records and enum constants, and omits methods in enum constant bodies

**Status: documented**

**Value: low — Size: S**

**Affects: MCP `outline` (and the editor's document symbols, which share it).**

Found by an agent's review of the MCP tools on Dropwizard (2026-11-01).

### Observed behaviour

Records show as `struct`, enum constants as `field`, and an enum constant body's methods are missing.

### Fix direction

Label records `record` and enum constants `enum constant` in the MCP text (LSP has no record kind, so its
mapping can stay), and list a constant body's members under the constant.

Also (exploratory round, 2026-11-04): `outline` shows a record as "struct" while `search_symbols` calls it "Class" (Dropwizard).

### Regression targets

None yet.

---

## MC-76 — Two hits on one line show as duplicate rows without columns

**Status: documented**

**Value: low — Size: S**

**Affects: MCP tools that list locations.**

Found by an agent's review of the MCP tools on Dropwizard (2026-11-01).

### Observed behaviour

When a line holds two results, the answer shows the same `file:line: snippet` row twice, with no column
to tell them apart.

### Fix direction

Print the column (`file:line:column`), or merge the hits of one line into one row with both columns.

Also (exploratory round, 2026-11-04): Dropwizard's `KIBIBYTES` gives 46 rows for 34 lines; Helidon's generated `ObserveServices__ServiceDescriptor.java` lines 64 and 66 twice.

### Regression targets

None yet.

---

## MC-77 — `workspace_summary` gives no test status for some modules

**Status: documented**

**Value: low — Size: S**

**Affects: MCP `workspace_summary`.**

Found by an agent's review of the MCP tools on Dropwizard (2026-11-01).

### Observed behaviour

Some modules show no test status at all, while others say `tests: not captured`.

### Fix direction

Show a status for every module: captured, not captured (with the command), or no tests.

Also (exploratory round, 2026-11-04): no test status for Dropwizard's docs/source/examples `conscrypt`, `getting-started` and `views`.

### Regression targets

None yet.

---

## MC-78 — `analyze_change`'s override family leaves out abstract redeclarations

**Status: documented**

**Value: medium — Size: S**

**Affects: MCP `analyze_change`. Go to implementation and `find_implementations` leave them out by design.**

Found by the MCP release battery's reference round trip on Helidon (2026-11-02).

### Observed behaviour

For Helidon's `MediaType.text()`, `analyze_change` reports an override family of 3: `HttpMediaTypeImpl.text()`,
the `MediaTypeImpl` record component, and `MediaTypeEnum.text()`.
It leaves out `HttpMediaType.text()`, the `String text();` that the subinterface `HttpMediaType extends MediaType`
redeclares (`http/http/src/main/java/io/helidon/http/HttpMediaType.java:191`).
That redeclaration matters to a change: `get_definition` at calls such as `HttpMediaTypes.JSON.text()` and
`HttpMediaType::text` lands on it, `find_references` there lists 77 uses, and `rename_symbol` renames it with the rest.
An agent asking what changing `MediaType.text()` affects is not told that a subinterface redeclares it.

### Fix direction

Why: `LatheEngine.analyzeChange` takes the family from the implementation search (`implementationsAt`), and
`MethodImplementationLocator.visitClass` keeps only methods without the `abstract` modifier.
That is right for an implementation, but an interface method without a body is abstract, so a subinterface's
redeclaration (and an abstract class's) is dropped.
The subinterface already reaches the search: the type index's transitive subtypes include interfaces, so only this
filter removes it.

The fix: the locator keeps abstract overriders too and marks each result as an implementation or a redeclaration;
go to implementation and `find_implementations` keep the implementations only; `analyze_change` keeps both and
labels the redeclarations ("redeclared by").
It belongs to the one-search set under
[EG-085](gaps-archive.md#eg-085--implementations-miss-anonymous-classes-and-enum-constant-bodies--done) and
[MC-67](#mc-67--analyze_change-reports-no-override-family-for-a-method-that-overrides-one): MC-67 adds the methods
the target overrides, this completes the ones that override it.
A second search only for `analyze_change` would walk the same candidates twice.

Test: `analyze_change` on an interface method that a subinterface redeclares lists the redeclaration;
`find_implementations` on it does not.

### Regression targets

None yet. The battery's reference round trip no longer catches this: since it accepts a definition that
`find_references` agrees with, it passes on Helidon either way.

---

## MC-80 — `verify_change` skips callers that reach a changed type without naming it

**Status: documented**

**Value: high — Size: S–M**

**Affects: MCP `verify_change`.**

Found by the exploratory MCP round (2026-11-04) on Dropwizard.

### Observed behaviour

With the same `toSeconds(int)` change, `verify_change` misses 5 errors in three `dropwizard-health` test files
(`ScheduleTest:24`, `HealthCheckConfigurationTest:25/26`, `DefaultHealthFactoryTest:60/61`). They call
`getSchedule().getDowntimeInterval().toSeconds()` and never name `Duration`; `find_references` lists all 11 uses and
`get_diagnostics` on `ScheduleTest` shows the error.
The callers checked are the files that mention the changed file's type.

### Fix direction

Take the callers from the changed members' references (the reference index already answers them), or add the files that
mention a changed member's name in the modules that can see it.

### Regression targets

None yet.

---

## MC-81 — `verify_change` after a `module-info.java` edit checks only `module-info.java`

**Status: documented**

**Value: high — Size: S**

**Affects: MCP `verify_change`.**

Found by the exploratory MCP round (2026-11-04) on EqualsVerifier.

### Observed behaviour

Removing `requires static java.sql;` (or `requires org.objenesis;`) from `equalsverifier-core`'s `module-info.java`:
`verify_change` says "No errors", and the log shows `[verify] 1 file(s)`. `get_diagnostics` on `JavaSqlValueSupplier.java`
reports "package java.sql is not visible".

### Fix direction

A changed `module-info.java` affects every source of its module (and the modules reading it): check the module's sources.

### Regression targets

None yet.

---

## MC-82 — `verify_change` says clean when the change fails only the build's Error Prone and `-Werror`

**Status: documented**

**Value: high — Size: M**

**Affects: MCP `verify_change`, `run_test` and the notes.**

Found by the exploratory MCP round (2026-11-04) on EqualsVerifier.

### Observed behaviour

Renaming `Formatter.of` to `ofX` (`Formatter.java:37`) leaves a `{@link}` at line 20 dangling, an Error Prone `InvalidLink`
warning that `-Werror` fails. `verify_change` reports no errors: the log shows `[compile:batch] failed=1` then
`[verify] 37 files diags=0`, the callers checked against the old classes, with no "Not compiled" note.
With the link fixed too, it reports 103 errors. An unused new parameter, and an unused local in a test, give the same.
The other tools' note reads only `Formatter.java:1: warnings found and -Werror specified`: line 1, the warning itself
hidden; `run_test` refuses with the same text.
Related to EG-084 (the open compile skips the build's plugins) and MC-73 (a file that does not compile answers from stale
classes); this is their consequence for `verify_change`.

### Fix direction

When a changed file's write fails, report that failure in `verify_change` (its real diagnostics, not the `-Werror` summary)
and say the callers were checked against the previous classes.

### Regression targets

None yet.

---

## MC-83 — Kotlin callers are invisible, yet references and rename call themselves complete

**Status: documented**

**Value: high — Size: M (a note) / L (support)**

**Affects: MCP `find_references`, `rename_symbol`, `analyze_change`, `verify_change`, `workspace_summary`.**

Found by the exploratory MCP round (2026-11-04) on EqualsVerifier.

### Observed behaviour

`find_references` on `withPrefabValuesForField` (`SingleTypeEqualsVerifierApi.java:244`) misses `KotlinWithoutReflectTest.kt:110`
and `:159` and says "no need to re-verify with grep". Renaming it, or `Warning.ALL_FIELDS_SHOULD_BE_USED`, leaves the Kotlin
uses broken (`KotlinDelegationTest.kt:169`), the review list silent on `.kt` files, and `verify_change` clean.
`analyze_change` counts no Kotlin callers (`forClass` has 77); `workspace_summary` reports 8 modules and leaves out the two
Kotlin test modules that `workspace.json` lists.

### Fix direction

At least: when a module has Kotlin sources, say so in these answers (and stop claiming completeness), and list the `.kt`
files that mention the name for review. Reading Kotlin is a separate decision.

### Regression targets

None yet.

---

## MC-85 — Rename and `verify_change` say nothing about uses in modules the sync leaves out

**Status: documented**

**Value: medium — Size: S**

**Affects: MCP `rename_symbol` and `verify_change`.**

Found by the exploratory MCP round (2026-11-04) on Helidon.

### Observed behaviour

Renaming `ServerFeature.setup` left `webserver/tests/webserver/.../FeaturesOrderingTest.java:268` broken: that module is
behind the root `tests` profile and not synced. Neither the rename nor `verify_change` mentions it, though
`get_diagnostics` on the file says it is outside the compiled modules.

### Fix direction

When the reactor has profile-gated modules not synced, say in a rename's and `verify_change`'s answer that they were not
checked, and list their files that mention the name.

### Regression targets

None yet.

---

## MC-86 — Smaller answer defects from the exploratory round

**Status: documented**

**Value: low — Size: S each**

**Affects: MCP tools.**

Found by the exploratory MCP round (2026-11-04) on Dropwizard, EqualsVerifier and Helidon.

### Observed behaviour

- `fix_imports` imports `javax.xml.transform.Result` where the inherited `HealthCheck.Result` was meant (Dropwizard
  `JdbiHealthCheck` with every import removed), and offers `java.awt.List` for `List<String>` (not generic).
- `verify_change` lists at most javac's 100 errors per compile without saying so: `seconds(long)` to `seconds(long, int)`
  reports "156 error(s)" of about 221.
- `analyze_change` counts declarations and overriding declarations as references (`toSeconds`: "5 production" for 4 calls;
  an unused constructor: "1 production").
- `describe_symbol`'s `source:` label is the JPMS module name from one caller and the Maven module from another, and the
  signature drops `final`, `<T>` and `default`.
- `get_definition` on a `requires` module name answers "No definition found" without saying modules are not navigable.
- The rename review list cites the line a text block opens on, not the line with the name (`PrefabValuesApi.java:106`
  for line 108), and lists every `"get"` literal for a short name.
- `search_symbols` labels an annotation "(Interface)"; `workspace_summary` has no size limit (about 46 KB on Helidon).

### Fix direction

Each is small; take them one by one when touching the tool.

### Regression targets

None yet.

---

## MC-87 — One MCP server answers for one worktree only

**Status: documented**

**Affects: MCP, with several git worktrees.**

Found by the comparison with [java-index](https://github.com/slgobinath/java-index) (2026-11-05), whose tools all take an optional `worktree` path and share one daemon per repository.

### Gap

An agent working across worktrees needs one Lathe MCP server per worktree, each with its own JVM and indexes.

### Fix direction

Design first; it overlaps [Shared Workspace Server](../potential/lathe-shared-workspace-server.md), which keeps one process per client until measurements show a need.

### Regression targets

None yet.
