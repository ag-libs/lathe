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

Write a stub source tree for each dependency without sources at sync time, and record it as that dependency's sources directory, so search, definition, hover and references reach it through the existing source paths.
javac prints a type from its class file as Java-shaped source (`Elements.printElements`): the package, every member's signature with generics, `throws` and annotations, and nested types, without bodies or Javadoc (Guava's `Joiner`: 115 lines in 92 ms, checked 2026-11-18).
Parameters are `arg0` unless the jar was compiled with `-parameters`.
A decompiler (an embedded Vineflower) would add bodies, at the cost of a dependency.

Each stub says in a header that it was generated from the class file of `g:a:v` and is not the source.
The debugger must not take a stub for source: its lines do not match the class file's.
The cost is a javac pass over each sourceless jar in the sync, and its disk in the cache.

Since EG-112 (2026-11-18), `search_symbols` lists such a type with its dependency and definition names the dependency; stubs would replace the search part, and let the editor's workspace symbols list these types too.

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

## EG-069 — Deeply nested code that javac compiles ends the server with a stack overflow

**Status: deferred** (2026-11-18: deferred by decision; such code is rare outside generated sources)

**Kind: bug**

**Value: medium — Size: S**

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

## EG-071 — In `module-info.java`, a module or package name highlights nothing, or only its last segment

**Status: deferred** (probed 2026-11-19: the per-segment highlights are gone; an `exports` package and the module name now find nothing, and a `requires` name highlights only its last segment; references on a package need a package reference target, a design of its own)

**Kind: bug**

**Value: low — Size: M**

**Affects: the editor.**

Found by the pre-release test rounds (2026-10-29/30) on Helidon.

### Observed behaviour

Probed 2026-11-19 on the server built from `eabe332c` (main `d2a58562` without NV-9, which does not touch this), in the `multi-module` invoker fixture's `jpms/src/main/java/module-info.java`, through `documentHighlight`, `references` and `hover` on every segment:

- `exports com.example.jpms`: no highlight and no references on any segment; hover shows the qualified name.
- The module's own name (`module com.example.jpms`): no highlight and no references.
- `requires io.github.aglibs.validcheck`: one reference, but the highlight covers only `validcheck`, not the whole name.

`CursorElements.elementAt` skips package elements, so a cursor on an `exports` segment resolves to the enclosing module, whose search finds nothing.

History: before `5c85a747` (2026-11-08), which replaced a walk that matched every member-select segment with `trees.getElement`, highlight marked each dotted segment on its own, also inside another package's `exports` (a `…type.spi` beside `…type`) and in the module's own name, and `find_references` on an `exports` segment returned every segment of the module and package names (EqualsVerifier, Helidon `exports io.helidon.webserver.spi`).

### Fix direction

Highlight a module or package name in `module-info.java` as one qualified unit, wherever the same name is used; the `requires` case and the module name need only the span.
`find_references` on an exported package needs a package reference target, which `ReferenceTarget` and the search do not have, so it can be split off; no gap tracks renaming a package.

### Regression targets

None yet.

---

## EG-072 — Malformed requests and unusual URIs get internal errors or are accepted silently

**Status: deferred** (triaged 2026-11-17: unrelated defects at the protocol edge, none of which ends the server; split it when one is taken up)

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
ignore stale versions.
Publishing diagnostics under the canonical URI is by design (every client URI goes through `LatheUri.canonical`); that item stays unless a client is seen to lose diagnostics by it.
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
`:LatheNew` → Test creates one through the full new-type dialog,
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
  The Gradle facts record only whether Spotless is applied (`ProjectFacts.spotless`), so they would first have to record its engine.
- The reason is the one at startup: after a sync resolves the formatter, `:LatheFormat` still says to run the sync until Lathe restarts (the server already says to restart).
  Keeping it current needs a way to tell the editor after `initialize` (a notification, or a command the client asks), which no existing message carries.
- VS Code reads no `latheFormatterUnavailable`: the extension has no formatting code of its own, so it still shows VS Code's own answer.

### Regression targets

`LatheLanguageServerTest.createCapabilities_noFormatter_saysWhyInExperimental`, `initialize_pinnedFormatterUnresolved_saysToSync`; `formatter_setup_spec.lua` `format(): no formatter shows the server's reason`, `format(): a range with a whole-file formatter says it formats whole files`, `format(): a name filter leaves the other formatter out`.

---

## EG-109 — `:LatheNew` cannot create a type in a Gradle source set beyond `main` and `test`

**Status: deferred** (triaged 2026-11-17: a destination beyond `main` and `test` needs a way to name a source set that `SourceScope` does not have)

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

## EG-113 — Formatting recognizes only Spotless, not spring-javaformat or formatter-maven-plugin

**Status: deferred** (triaged 2026-11-18: a feature, and the feature freeze holds)

**Kind: feature**

**Affects: `format_file`, `:LatheFormat` and the editor's formatting on Maven builds.**

Found by the exploratory round on Spring Petclinic and Quarkus (2026-11-17).

### Observed behaviour

Petclinic formats with `spring-javaformat-maven-plugin`, Quarkus with `formatter-maven-plugin` (net.revelc) and `impsort-maven-plugin`.
On both, `format_file` answers "No formatter for this workspace: the project pins none (no Spotless Java step)": `MavenStyle` reads only `spotless-maven-plugin`.
Spring projects (spring-boot, Petclinic) use spring-javaformat throughout.

### Fix direction

Read those plugins' configuration as a Spotless step is read: spring-javaformat as its own engine, formatter-maven-plugin's Eclipse configuration through the existing Eclipse engine.

### Regression targets

None yet.

---

## EG-114 — Code generation and refactorings jdtls offers that Lathe does not

**Status: deferred** (triaged 2026-11-21: features, and the feature freeze holds; the jdtls comparison reports each as a row Lathe lacks)

**Kind: feature**

**Affects: the editor's code actions, on every build.**

Found by the jdtls comparison's probe on spring-petclinic (2026-11-21, Lathe `0.3.99-0f8e6e85`, jdtls 1.51 with the java-debug bundle and nvim-jdtls's client capabilities): the code actions each server offered at the same positions in `Owner` and `OwnerController`.
One entry for the differences no other gap covers, so the comparison's list of what Lathe does not do can cite it; it splits into a gap per feature when one is taken up.

### Observed behaviour

What jdtls offered and Lathe did not:

| Feature | jdtls's code action | How jdtls delivers it |
|---|---|---|
| Generate getters (offered on `OwnerController`, whose fields are final, so setters were not in question) | `source.generate.accessors` | an edit, after a resolve |
| Generate constructors | `source.generate.constructors` | a prompt (`java.action.generateConstructorsPrompt`) |
| Generate `toString()` | `source.generate.toString` | a prompt |
| Generate `hashCode()` and `equals()` | `source.generate.hashCodeEquals` | a prompt |
| Generate delegate methods | `source.generate.delegateMethods` | a prompt |
| Override or implement methods, chosen from a list | `source.overrideMethods` | a prompt |
| Inline a local variable | `refactor.inline` | an edit, after a resolve |
| Inline a method | `refactor.inline` | an edit, after a resolve |
| Move a type or a method | `refactor.move` | a refactoring command that applies its edit through the client |
| Change a method's signature | `refactor.change.signature` | a refactoring command |
| Introduce a parameter from an expression | `refactor.introduce.parameter` | an edit, after a resolve |
| Make a method static | `refactor.inline` ("Make Static") | an edit, after a resolve |
| Surround any statement with try/catch | `refactor` | an edit, after a resolve |

Lathe offers override completion and stubs for missing methods, not a chosen list; and try/catch only where an exception is uncaught, from that diagnostic.

Quick fixes on a diagnostic, found by the editor exploratory round (2026-11-22, `c0d49de`, Dropwizard's `Duration.java`), which jdtls offers and Lathe does not:

| Diagnostic | jdtls's quick fix | Lathe |
|---|---|---|
| a misspelled member (`long probe = toMillisecond();`, "cannot find symbol") | change to the similar name (`toMilliseconds`) | no code action |
| a call to a method that does not exist (`this.notThereYet();`) | create the method | only "Extract method", unrelated |
Extract interface was not offered by jdtls 1.51 at a class name; the comparison's pinned jdtls settles whether it has it.

What both offered at the same positions: extract a variable, a constant, a field and a method (jdtls through a refactoring command, Lathe as an edit or, for a method, after a resolve), and conversion to `var`.

Covered by gaps of their own, and so not here: selection range (EG-018), code lens (EG-064), inlay hints (EG-091), member symbol search (EG-092), formatting with spring-javaformat or formatter-maven-plugin (EG-113).

### Fix direction

Each feature is its own piece of work; the likely first are generating accessors and constructors, which a plain edit delivers, and inline variable, the reverse of extract variable.
A prompt-driven one (`toString`, `equals` and `hashCode`, delegates) needs a client-side choice, which `lathe.nvim` and the VS Code extension would each implement.

### Regression targets

None yet.

---

## EG-118 — Type hierarchy with the cursor on a member answers nothing

**Status: accepted** (triaged 2026-11-22: found by the jdtls differential run; fixed before the comparison goes on)

**Kind: bug**

**Value: medium — Size: S**

**Affects: type hierarchy in the editor, when the command runs with the cursor anywhere but on a type name.**

Found by the differential run against jdtls on spring-petclinic (2026-11-22, Lathe `0.1.0-SNAPSHOT` at `c0d49de`, jdtls 1.63.0, `dev/jdtls_diff.py` over all 50 files), and reproduced by hand.

### Observed behaviour

With the cursor on a method or a field name, such as `getId` in `BaseEntity` or `getName` in `NamedEntity`, `textDocument/prepareTypeHierarchy` answers nothing; on the class name it answers.
jdtls answers with the hierarchy of the enclosing class (`NamedEntity`, `Person`, `Visit` for `BaseEntity`).
28 of the run's 30 type-hierarchy misses were such positions, so an editor's type-hierarchy command finds nothing unless the cursor is on a type name.

### Fix direction

Prepare the hierarchy of the innermost type enclosing the position when the position is not on a type name, as jdtls does.

### Regression targets

None yet.

---

## EG-119 — Workspace symbol initials do not match a nested type

**Status: accepted** (triaged 2026-11-22: found by the jdtls differential run; fixed before the comparison goes on)

**Kind: bug**

**Value: low — Size: S**

**Affects: workspace symbol search in the editor and through MCP.**

Found by the differential run against jdtls on spring-petclinic (2026-11-22, Lathe `0.1.0-SNAPSHOT` at `c0d49de`, jdtls 1.63.0, `dev/jdtls_diff.py` over all 50 files), and reproduced by hand.

### Observed behaviour

`PCFHE` and `ProcCreFormHasErr` find nothing, while `ProcessCreationForm` finds `ProcessCreationFormHasErrors`, a `@Nested` class in `PetControllerTests`.
Top-level types match by initials and humps (`OCT` finds `OwnerControllerTests`, `PTF` `PetTypeFormatter`, `VC` the controllers), as EG-005 made them; jdtls matches the nested type too.

### Fix direction

Run EG-005's CamelCase matching over nested types as well as top-level ones.

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

## FR-034 — Renaming a member a processor derives another from leaves the derived member's callers broken

**Status: deferred** (triaged 2026-11-17: the break is reported: the rename lists the generated uses it left and `verify_change` lists the errors; the fix needs the generator link)

**Kind: bug**

**Value: medium — Size: M**

**Affects: MCP and the editor.**

Split from FR-020 (2026-11-17), whose build-task half is done.
Found by the pre-release test rounds (2026-10-29/30) on Helidon.

### Observed behaviour

`rename_symbol` on `AllowListConfigBlueprint.allowAll()` edits 4 places in 3 files, but not the callers of the generated `Builder.allowAll(boolean)`; the build then fails with 21 errors.
The answer does warn ("Not compiled: TrustedProxyMatcher.java:76"), and `verify_change` reports the errors.
The annotation processor regenerates the builder from the renamed blueprint, so its member gets the new name, and the hand-written callers of the old one break.
A JPA metamodel attribute (`Foo_.name`) used by hand-written queries behaves the same.

Renaming Dropwizard's JMH `parseDuration`, whose generated callers `verify_change` called clean, was split out as MC-98 (done).
Seen again on Helidon (2026-11-19, `8c099a5`): renaming `ListenerConfigBlueprint.port()` edits 3 places in 2 files, lists 5 places "Left to the generator", and breaks 34 hand-written calls of the generated `Builder.port(int)`, which `verify_change` reports.
Before the rename, `analyze_change` on the option reports `references: 5 production, 0 test` and `relevant tests: none`: the derived setter's callers are not part of the blast radius it previews.

### Fix direction

Processor output stays unedited, and renames only it refers to (Dagger factories, MapStruct implementations) go ahead.
Refuse when a generated file that refers to the symbol also declares a member of the old name that hand-written code uses (`Builder.allowAll(boolean)`, `Foo_.name`), naming that member and one caller.
Find those members with javac trees through the existing file queries, and run the check only when processor output refers to the target.
Generated types named after a renamed type (`Foo_Factory`) are out of scope.

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

### Fix direction

Batching (a fix the [comparison plan](../planned/lathe-jdtls-comparison.md)'s first runs called for) is done (#158).
Verified against the code (2026-11-16): implementations of a method still compile every subtype file and every lambda or method-reference file (`ReferenceSearch.implementationsIn`), and incoming calls the reference candidates, in batches of 32 run one after another on the module's worker; only implementations of a type answer from the type index.
What is left is FR-029's directions 2 and 3: a module's batches in parallel, and cached answers.

### Regression targets

None yet.

---

## FR-036 — Renaming a getter leaves its property name in templates and message files unreported

**Status: deferred** (triaged 2026-11-18: still on main `cdf57f1`; the review list is advisory, and listing property names needs a rule for which accessors count)

**Kind: bug**

**Affects: MCP `rename_symbol` and `verify_change`, and the editor's rename.**

Found by the exploratory round on Spring Petclinic (2026-11-17).

### Observed behaviour

`rename_symbol` on `Person.getFirstName` → `getGivenName` edits 4 places in 3 files and lists no place to review.
Petclinic's Thymeleaf templates use the JavaBean property, not the method: `${owner.firstName}` in `ownersList.html`, `*{firstName + ' ' + lastName}` in `ownerDetails.html`, and two more; the message bundles key `firstName` too.
"Not renamed — review" lists only the old method name as text, so none of them is named, and `verify_change` says clean.
The application then fails at runtime, on the first page that renders an owner.
The same holds for a property Jackson serializes or a JPQL query names: any framework that reads accessors as properties.

### Fix direction

When the renamed symbol is a JavaBean accessor (`get`/`is`/`set` + capitalised name), list the text mentions of its property name (`firstName`) in resources the way the old method name is listed, as places to review, never edited.
A field renamed with its accessors is the same case.

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

**Status: deferred** (triaged 2026-11-19: keeping the caller's `var` does not remove the warning, which is on the extracted method's return type; no simple fix picks the type a plugin would accept)

**Kind: bug**

**Value: low — Size: S**

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

Keeping the caller's `var` when the selection declared the local with `var` tidies the call site only.
`[NonApiType]` flags the extracted method's return type, which is the local's inferred type (`ExtractMethodProvider` takes it from `asType()`), so the method still returns `ArrayList<Class<?>>`.
Avoiding the warning needs a return type the plugin accepts (`List`, `Collection`, …), and nothing in the analysis compile says which one.
A check through the build's plugins is out of reach of the analysis compile.

### Regression targets

None yet.

---

## CA-20 — An added import's group follows the file, not the project's configured import order

**Status: deferred** (triaged 2026-11-17: formatting the file fixes the order; the fix needs the sync to record the project's import order)

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

# Workspace Lifecycle Gaps (WS)

Workspace freshness and lifecycle gaps: reactor mirror / type-index staleness, source watching, sync
prompting, and reload. Resolved WS entries are in [gaps-archive.md](gaps-archive.md).

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

## WS-44 — `:LatheInit` builds a Maven project to `test`, though `lathe.sync.phase` asks for a later phase

**Status: deferred** (triaged 2026-11-18: it needs a build that cannot reach `test` without packaging, as Quarkus, and `lathe.sync.phase` set; the fix is clear when such builds come up)

**Kind: bug**

**Affects: Maven setup with `:LatheInit` on a build that cannot reach `test` without packaging some of its modules (Quarkus, a shading build); the Neovim client.**

Found testing the WS-41 fix on Quarkus (2026-11-18), with that fix applied (`main` `2c9804d` plus #381).

### Observed behaviour

`:LatheInit`'s first Maven build is fixed: `clean test`, capture only (`MAVEN_FIRST_BUILD` in `lathe-neovim/runtime/lua/lathe/latheinit.lua`).
On Quarkus' whole reactor, that build passes WS-41's module and stops at the third: `maven-dependency-plugin:3.8.1:copy-dependencies (copy-porting-pkg) on project arc-cdi-tck-runner: Artifact 'io.quarkus.arc:arc-cdi-tck-porting-pkg:jar:999-SNAPSHOT:compile' has not been packaged`.
The module copies another reactor module's jar, which only `package` makes; plain `mvn clean test` fails the same way.
The build stops before `lathe:sync`, so no `.lathe/workspace.json` or sync script is written.

`lathe.sync.phase` is the setting for such a build: in `.mvn/maven.config` (`-Dlathe.sync.phase=package`) or as a root POM property, it makes the sync script build to that phase, skipping the tests on the way.
`:LatheInit` does not read it, so a project that sets it still gets a `test` build, which fails before any sync script exists to use it.
The build's warning to set it covers only shaded modules (`SyncCoordinator`), so a build like Quarkus' is not told about it.

The same first build run to `package` instead (`clean package -Dlathe.sync.phase=package`, capture only, `-DskipITs` as the sync script adds past `test`) gets past `arc-cdi-tck-runner`: stopped by hand after 58 of the reactor's 1,435 modules (it was still fetching dependencies), it had met no failure and captured 62 test launches.
Whether the whole setup then completes and writes the workspace files was not checked.

### Fix direction

Have `:LatheInit` build a Maven project to the phase `lathe.sync.phase` names, read from `.mvn/maven.config` or the root POM as `MavenSyncScript.phase` reads it, with the capture-only flags the sync script adds past `test` (`-DskipITs`), and `test` when it names none.
Then consider pointing at the setting when a build fails on an artifact that "has not been packaged", as the shading warning does for shading.

### Regression targets

None yet.

---

## WS-47 — One module's javac crash fails a whole cross-module search

**Status: deferred** (2026-11-21: split from WS-46; rare now, and failing the request is the safe answer, which a rename needs)

**Kind: bug**

**Affects: references, implementations, callers and `analyze_change` across modules, when javac crashes on one module's files.**

Split from WS-46 (2026-11-21), which fixed the server's JDK being older than a module needs; this crash is a separate failure.

### Observed behaviour

Before #422, with the server on JDK 21, javac crashed in Enter on Dropwizard's docs examples (`--system` naming JDK 25), and the crash failed the whole cross-module request, not just that module: `find_implementations` on `Managed.stop`, its override family and `analyze_change` answered "the request failed; see the server log".
`JavaSourceCompiler.analyzeSafely` keeps the crashed batch's partial analysis, the locator's first call into `Elements` then throws ("Cannot use Elements.getTypeElement before the TaskEvent.Kind.ENTER finished event"), and the module's failed future fails the request (`ReferenceSearch.searchFiles`).
`find_references` fails the same way whenever a crashed batch holds one of its candidate files.

What can still cause it after #422: a `--system` JDK javac cannot read whose release file is missing or unparseable, another javac crash in Enter, or a Lathe locator bug on one file.
Deferred: it is rare now, and failing the request is the safe answer, which a rename needs (one that skipped a file would leave its uses unrenamed).
Answering queries from the other modules would need the skipped files carried up with the hits (`SearchHits`) so a rename can still refuse, and the answer can say it is partial.
The same note would cover a javac crash in Attr, which throws nothing and can leave a batch's hits missing without a trace.

### Regression targets

None yet.

---

# Test Execution Gaps (TE)

Capture/replay gaps involving the Maven test fork, the recorded launch template, and the standalone
replay JVM. Resolved TE entries are in [gaps-archive.md](gaps-archive.md).

## TE-3 — `<systemPropertyVariables>` are not carried into the replay launch

**Status: deferred** (triaged 2026-11-17: no project validated against Lathe uses them, and `<argLine>` is the escape hatch)

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
Since the copy moved to `lathe-analysis` (2026-11-05), `lathe-server` tests its compile output and runs without the note; the repro on this repo is a `lathe-analysis` test.

Replaying the copy from the mirrors (the `classes` mirror with the `21` tree's in front: on the module path through
`--patch-module`) is deferred.
It is mostly this repository's own setup, Surefire tests `target/classes` by default, and the module-path patching
would touch every replayed launch; reconsider if a project asks for it.

### Regression targets

`CompletenessGateTest.verify_copyOfClassesInABuildDir_openWithNote_mirroredAndOutsideDirsNot`

---

## TE-18 — Tests a Gradle source set runs through another engine never show in the editor

**Status: deferred** (triaged 2026-11-17: waits on EG-109's workspace context)

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

## TE-20 — A Gradle source set beyond `test` has no package, folder or module runs

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

## TE-24 — A test method marked by a custom annotation built on JUnit's `@Testable` gets no run entry

**Status: deferred** (triaged 2026-11-18: custom test annotations built on `@Testable` are mostly in large projects like Kafka; the fix is small and clear when it is picked)

**Kind: bug**

**Affects: the editor's runnables of a test file (run and debug entries, neotest) and MCP `run_test` on a file, for tests a project marks with its own annotation: Apache Kafka's `@ClusterTest`, `@ClusterTests` and `@ClusterTemplate` (about 150 files).**

Found evaluating Lathe on Apache Kafka (2026-11-18), from the code; not run live, as Kafka's `@ClusterTest` files are in `clients-integration-tests`, which needs the Scala `core` built.

### Observed behaviour

Kafka's `@ClusterTest` is annotated `@TestTemplate`, `@Tag("integration")` and `@Timeout(60)`; JUnit runs each such method as a test template.
`RunnableScanner.isTestAnnotation` takes a method as a test only by its annotation's simple name (`Test`, `ParameterizedTest`, `TestFactory`, `RepeatedTest`), so these methods get no run entry, and a file holding only them has no test to run.
A module run, through JUnit's own discovery of the captured launch, should still run them; not checked.

### Fix direction

Take a method as a test when one of its annotations is, or is itself annotated with, JUnit Platform's `org.junit.platform.commons.annotation.Testable`, which `@Test`, `@TestTemplate` and `@TestFactory` carry, keeping the names for JUnit 4's `org.junit.Test`, which does not.
That covers any custom test annotation built on JUnit's own; ArchUnit's `@ArchTest` (TE-18), on fields, likely carries `@Testable` too (to check).

### Regression targets

None yet.

---

## TE-25 — A module that publishes a shaded jar at its jar's path runs its tests on the last build's classes

**Status: deferred** (triaged 2026-11-18: a module that replaces its jar with a shaded one is rare, the answer already says what runs, and the fix needs a design)

**Kind: bug**

**Affects: MCP `run_test` and editor test runs of a Gradle module whose jar task is replaced by a Shadow jar at the jar's path (Apache Kafka's `clients`), after an edit to the module's main code.**

Found evaluating Lathe on Apache Kafka (2026-11-18), with the WS-43 branch: the first module checked end to end.

### Observed behaviour

Kafka's `clients` disables its `jar` task and publishes `shadowJar` at the jar's path, relocating `protobuf`, `opentelemetry-proto` and `commons-net`.
Its captured test launch holds no `build/classes/java/main`: its own classes reach its tests only in `kafka-clients-4.5.0-SNAPSHOT.jar`, which Lathe records as `shaded: true`, and replay keeps.
`run_test` on `BytesTest` passes (9 tests) with the note `clients/build/libs/kafka-clients-4.5.0-SNAPSHOT.jar runs as the last build left it, not from .lathe: it may not hold your latest edits`.
An edit to `clients`' main code reaches its tests only at the next Gradle build.

### Cause

By design (WS-31, and `LatheSyncTaskFunctionalTest.sync_jarTaskDisabled_marksTheJarAtItsPathShaded`): the mirror lacks the relocated classes, and its own classes name the original packages, which the shaded build leaves off the runtime classpath, so the mirror cannot stand in for the jar.

### Fix direction

Needs a design: the mirror ahead of the shaded jar would make the module's own classes fresh while the jar still supplies the relocated ones, but a fresh class that uses a relocated package by its original name would then fail to load.
Until then, the note says what runs.

### Regression targets

None yet.

---

## TE-27 — Tests a JUnit 4 runner selects by name (Lucene's `testXxx`) get no run entry

**Status: deferred** (triaged 2026-11-18: it affects projects built on randomizedtesting (Lucene, Solr, Elasticsearch, OpenSearch), where a class with one `@Test` method still runs all its tests; the fix direction is clear when it is picked)

**Kind: bug**

**Affects: editor test runs and MCP `run_test` on projects whose tests run under randomizedtesting's `RandomizedRunner` (Apache Lucene, Solr, Elasticsearch, OpenSearch), or another JUnit 4 runner that picks its own methods.**

Found checking the TE-23 fix on Apache Lucene (2026-11-18, main `7e748f89` plus #398).

### Observed behaviour

`run_test` on `lucene/core/src/test/org/apache/lucene/search/TestTermQuery.java` answers "no test in TestTermQuery.java", and the editor shows no run entry on its methods.
Lucene's tests are `public void testXxx()` methods without `@Test`, in classes extending `LuceneTestCase`, which declares `@RunWith(RandomizedRunner.class)` and `@TestMethodProviders({LuceneJUnit3MethodProvider.class, JUnit4MethodProvider.class})`: the runner takes a method as a test by its name, as JUnit 3 did, or by `@Test`.
It is not JUnit 3 (`LuceneTestCase` extends `Assert`, not `junit.framework.TestCase`), and not the build tool: Gradle, as Surefire would, hands the class to the vintage engine, which runs the class's runner, and the runner picks the methods.
Lathe's source scan (`RunnableScanner`) takes a method as a test by `@Test`/`@Testable`, or by its `test` prefix only in a `TestCase` subclass, so it finds none here.
A class with at least one `@Test` method is found, and its replay then runs every test the runner picks (`TestDocValuesQueries`: 41 passed), so the run itself works.

### Fix direction

Ask the runner, rather than guess from the source: Lathe already has a discover-mode launch, a JVM on the module's captured test launch that asks the JUnit Platform for the tests a run would execute (`RunCoordinator.discoverTestsFuture`, `lathe.tests.discover`), where the vintage engine asks the runner for its description, which runs the method providers.
Only the client's test tree uses it today; `run_test` and the editor's run entries use the source scan alone.
Use a discovery's tests for a file the scan finds none in (and for the methods a class with some `@Test` methods also has), so whatever any runner selects becomes runnable, at the cost of one discovery JVM per module, run one at a time and kept until the module's test classes change.
A source-only alternative, `test*` methods in a class whose hierarchy carries `@TestMethodProviders` with a JUnit 3-style provider, covers randomizedtesting alone.
Related: TE-24, the same gap for custom annotations built on `@Testable`, which discovery would close too.

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

**Status: deferred** (triaged 2026-11-16: waits on FR-034's generator link)

**Kind: bug**

**Value: low — Size: S**

**Affects: MCP `rename_symbol`.**

Found by the pre-release probes (2026-10-27) on Helidon.

### Observed behaviour

`rename_symbol` on a Helidon generated builder method (`allowAll`) correctly refuses it, but does not name the
blueprint method (`AllowListConfigBlueprint.java:51`) to rename instead.
The ordering half of this gap is done: capped answers list hand-written sources before generated ones (MC-70).

### Fix direction

When refusing a generated symbol, name its blueprint declaration. Waits on FR-034's generator link, which
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

Re-checked against the code (2026-11-17): only the first item is open work.
The `requires` answer applies to JDK and dependency modules only: a reactor module's `requires` navigates (`DefinitionLocator.locateModule`).
The other two are decided below.

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

## MC-100 — A capped list shows sibling modules' tests before the declaring module's own results

**Status: deferred** (triaged 2026-11-18: low; the count is right, only the first lines are)

**Kind: bug**

**Affects: MCP `find_implementations` and the other capped lists.**

Found by the exploratory round on javaparser (2026-11-18, main `cdf57f1`).

### Observed behaviour

`find_implementations` of `GenericVisitor` with `maxResults: 5` answers "33 implementations (showing first 5)", all anonymous classes in tests of `javaparser-core-testing` and `javaparser-core-testing-bdd`; `GenericVisitorAdapter` itself is not among them.
The list is in path order, and `javaparser-core-testing/…` sorts before `javaparser-core/…` because `-` sorts before `/`.

### Fix direction

Order a capped list by module and source tree (main before test, the declaring module first), then by path.

### Regression targets

None yet.
