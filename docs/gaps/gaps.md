# Lathe — Gaps

This is the single active gap registry for Lathe.
Every open gap, across all areas, lives here, follows the shared [gap lifecycle](gap-process.md)
(a `Status` and a `Target`), and is discovered and triaged through the [gap workflow](gap-workflow.md).
Resolved (`done` / `non-goal`) gaps move to [gaps-archive.md](gaps-archive.md).

## Areas

Each gap keeps its area prefix; the area is the discovery family, not a strict feature taxonomy.

| Prefix | Area | Notes |
|---|---|---|
| `EG-NNN` | exploration | Live-probing of nav, hover, search, completion, code actions, hierarchies, against Helidon, Dropwizard, and the `@Builder`-heavy sample-workspace workspace |
| `FR-NNN` | references | `textDocument/references` scope, failure propagation, coverage |
| `CA-N` | code-action | `textDocument/codeAction` providers |
| `CQ-NNNN` | completion | Completion quality; checked against the completion [expectations](../planned/lathe-completion-expectations.md) contract |
| `WS-N` | workspace lifecycle | Workspace freshness and lifecycle: reactor mirror / type-index staleness, source watching, sync prompting, and reload |

## Finding the work for a release

The slice for a release is derived, not hand-maintained: every gap with `Status: accepted` and the
matching `Target` (see [gap-process.md](gap-process.md)).

```bash
grep -nE '^(Status|Target):|^\*\*Status' docs/gaps/gaps.md     # scan active entries
grep -n 'Target: M1' docs/gaps/gaps.md                         # the M1 slice
```

Entries follow, grouped by area: exploration (EG) below, then Find References (FR), Code Actions
(CA), Completion (CQ), and Workspace Lifecycle (WS).

EG-003 is deferred until after M2 because it requires `DocTrees` attribution of Javadoc comment
positions,
which is a non-trivial hover extension.

---

## EG-003 — Hover returns null on positions inside Javadoc type-reference tags

**Status: accepted — Target: M3**

### Observed behaviour

Pressing `K` (hover) on a type name inside a Javadoc `{@link …}` or `{@see …}` reference tag
returns no result.

```java
/**
 * ... {@link Scheduling} ...     ← hover on 'Scheduling' → null
 * @see TaskManager               ← hover on 'TaskManager' → null
 */
```

Type names at the same or nearby positions in source code resolve correctly.

### Root cause

The Javadoc region is not attributed for reference resolution.
The cursor position falls inside a `DocCommentTree` or raw comment block that javac does not
include in the attributed element table.
`HoverLocator` (or equivalent) receives a position whose `TreePath` resolves to a Javadoc
comment node, finds no attributed element, and returns null.

### Proposed fix

Two-phase lookup for positions inside Javadoc:

1. Detect that the cursor falls inside a `DocCommentTree` (by checking `DocTrees.getDocComment`
   and comparing character offsets).
2. Extract the referenced type name from the `{@link}`, `{@see}`, or `@throws` tag using
   `DocTrees.getElement(DocTreePath)`.
3. Delegate to the normal hover path with that resolved element.

This is a bounded change: only `HoverLocator` and possibly a helper on `SourceAnalysisSession`
need modification.

### Probe commands

```bash
printf 'hover "Scheduling"\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/helidon/scheduling/src/main/java/io/helidon/scheduling/Scheduling.java
```

### Regression targets

`HoverTest.hover_javadocLinkTag_resolvesReferencedType`
`HoverTest.hover_javadocSeeTag_resolvesReferencedType`

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

## EG-017 — `textDocument/documentHighlight` not implemented

**Status: accepted — Target: M3**

### Observed behaviour

The server does not advertise `documentHighlightProvider`, and no handler exists.
Cursor-occurrence highlighting — the read/write highlight an editor draws for every occurrence of
the symbol under the cursor as the cursor rests — is therefore unavailable.

### Root cause

`LatheLanguageServer.initialize` registers no `documentHighlightProvider`, and there is no
`documentHighlight` request handler in the server.

Spec: [LSP 3.17 — Document Highlights Request](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_documentHighlight).
Params are `TextDocumentPositionParams` (same cursor target as references); the response is
`DocumentHighlight[]`, each a `range` plus optional `DocumentHighlightKind` (`Text=1`, `Read=2`,
`Write=3`, default `Text`). Eclipse JDT LS implements this via `DocumentHighlightHandler`/
`computeOccurrences` and returns the same LSP4J types Lathe uses, so this is parity work.

### Proposed fix

Server side: implement `textDocument/documentHighlight` as a file-scoped specialisation of the
existing exact same-file reference matching.
Reuse the `ReferenceTarget` identity already used by Find References, restrict the scan to the
current document (run against the already-attributed open-file analysis — never recompile — and make
it cancellable, since it fires per cursor-rest), and map each occurrence to a `DocumentHighlight`
with `Read`/`Write` kind (`ReferenceRole.READ/WRITE → Read/Write`, otherwise `Text`) based on whether
the occurrence is an assignment target. The range-dedup added for FR-008 already prevents a record
component's header from being highlighted once per synthetic member.

The same-file matching machinery already exists, so the server work is small and the feature is
exercised continuously during normal editing.

### Client integration (Neovim) — not server-only

Advertising the capability is not sufficient for a visible effect in the shipped plugin:

- `lua/lathe.lua` sends full `make_client_capabilities()` (so the capability negotiates), but wires
  only a `format_on_save` `LspAttach` autocmd — there is **no** `document_highlight` autocmd, so
  nothing highlights automatically.
- Stock Neovim users need a buffer-local `LspAttach` block (mirroring the `format_on_save` one,
  capability-guarded on `client:supports_method('textDocument/documentHighlight')`) that calls
  `vim.lsp.buf.document_highlight()` on `CursorHold`/`CursorHoldI` and `vim.lsp.buf.clear_references()`
  on `CursorMoved`/`CursorMovedI`, plus guidance to lower `updatetime` (~250ms; default 4000ms is too
  slow) and ensure `LspReferenceText/Read/Write` highlight groups are visible.
- `vim-illuminate` users get it for free: its default provider order is `{'lsp','treesitter','regex'}`,
  so advertising the capability silently upgrades them to semantic LSP highlighting.

This gap therefore ships as **server handler + a small `lathe.lua` autocmd** (behind an opt-out flag
like `format_on_save`); a server-only change would leave the feature half-wired for stock users.

### Probe commands

Not probeable through `explore.py` (no `documentHighlight` command); confirmed by the absent
capability and the absent handler in `LatheLanguageServer`.

### Regression targets

- `DocumentHighlightTest.documentHighlight_localVariable_highlightsReadAndWriteOccurrences`
- `DocumentHighlightTest.documentHighlight_methodName_highlightsSameFileCalls`

---

## EG-018 — `textDocument/selectionRange` not implemented

**Status: accepted — Target: M3**

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

## EG-039 — Unused-hint mislabels exception and lambda parameters as "local variable"

**Status: documented — Target: pending triage**

### Observed behaviour

The unused-declaration hint reports every unused variable it flags as a *local variable*, including
kinds that are not local variables:

```java
try { risky(); } catch (final IllegalStateException e) { recover(); }   // "Unused local variable 'e'"
map.forEach((k, v) -> System.out.println(k));                           // "Unused local variable 'v'"
```

Both are genuinely unused, so the *detection* is correct — this is not a false positive. The defect
is the **kind label**: per JLS §4.12.3 ("Kinds of Variables"), an *exception parameter* (kind 7) and
a *lambda parameter* (kind 6) are distinct variable kinds, neither of which is a *local variable*
(kind 8). Calling `e` and `v` "local variable" is inaccurate.

Verified against `UnusedDeclarationScanner` (open-mode compile):

| Construct | Emitted message | JLS §4.12.3 kind | Correct? |
|---|---|---|---|
| unused `catch (T e)` | `Unused local variable 'e'` | exception parameter | ✗ |
| unused lambda param `v` | `Unused local variable 'v'` | lambda parameter | ✗ |
| unused `for (T s : xs)` | `Unused local variable 's'` | local variable | ✓ |
| unused pattern binding | `Unused local variable '…'` | local variable (§14.30.1) | ✓ |
| unused method parameter | *(not flagged)* | method parameter | — |

The enhanced-for variable and switch/`instanceof` pattern bindings are *local variables* per the JLS
(§14.14.2, §14.30.1), so their "local variable" label is correct and out of scope here.

### Root cause

`UnusedDeclarationScanner.visitVariable` (`UnusedDeclarationScanner.java:135-137`) assigns
`Kind.LOCAL_VARIABLE` to any `VariableTree` whose parent is neither a `ClassTree` nor a `MethodTree`.
A catch parameter (parent `CatchTree`) and a lambda parameter (parent `LambdaExpressionTree`) both
fall into that catch-all branch, so both are described with the sole non-field, non-method label the
scanner has — `local variable`. Method and constructor parameters are correctly excluded because
their parent is a `MethodTree`.

### Proposed fix

Give the scanner the two missing kinds and classify by parent node in `visitVariable`:

- parent `CatchTree` → `Kind.EXCEPTION_PARAMETER` ("exception parameter")
- parent `LambdaExpressionTree` → `Kind.PARAMETER` ("parameter")
- otherwise unchanged (`LOCAL_VARIABLE`)

This is a bounded, single-class change (a new `Kind` label plus two `instanceof` guards). It touches
only the message wording, not which declarations are flagged. Whether these genuinely-unused-but-not-
freely-removable bindings should also drop the `Unnecessary` (strike-through) tag — since, pre-`_`
(JEP 456, Java 22+), the name cannot simply be deleted — is a separate presentation question; this
gap is scoped to the JLS-inaccurate kind label.

### Probe commands

```bash
printf 'diagnostics\n' | python3 dev/explore.py /path/to/workspace/.../SomeFile.java
# Any file with an unused catch parameter or unused lambda parameter reproduces it.
```

### Regression targets

- `UnusedDeclarationScannerTest.compile_unusedCatchParameter_labelsExceptionParameter`
- `UnusedDeclarationScannerTest.compile_unusedLambdaParameter_labelsParameter`
- `UnusedDeclarationScannerTest.compile_unusedEnhancedForVariable_labelsLocalVariable` (boundary: still
  "local variable")

---

## EG-041 — JDK/library source files get live diagnostics and code actions with no "read-only source" affordance

**Status: documented — Target: pending triage**

### Observed behaviour

Opening a JDK source file (e.g. `String.java`, typically reached via "go to definition" from user
code into the cached JDK sources under `~/.cache/lathe/jdks/<jdk-id>/...`) is treated identically to a
workspace file. `didOpen` routes it through `WorkspaceSession.routeCompiler`, which resolves it to
`CompilerRoute.External` (found via `WorkspaceManifest.externalSourceRootForFile`, since it isn't
under any workspace module's source roots). Every feature dispatch — diagnostics, hover,
definition, and `codeAction` — treats `External` the same as `Module`: `ExternalCompiler` compiles
the file with real `javac` (adding `--patch-module` for the owning JDK module) and the server
publishes real diagnostics and offers real code actions for it, exactly as for the user's own code.

From a user's perspective this can be confusing: nothing in the editor signals that the buffer is
a read-only, externally-sourced file rather than an editable part of the workspace. A user could
see (harmless but unexpected) compiler diagnostics on `String.java`, or invoke a code action
there, and wonder why the file isn't inert the way most editors/IDEs treat library sources opened
via "go to definition".

### Root cause

There is no concept of "read-only source" in the server or in the LSP responses it sends. The only
distinction tracked is `CompilerRoute` (`Module` / `External` / `Missing`), which controls how a
file is *compiled*, not how it's *presented* to the user. LSP itself has no standard "this document
is read-only" signal, so closing this gap needs either editor-side affordance (a marker the
Neovim runtime can render) or deliberately suppressing some subset of features for `External`
routes — neither of which exists today.

### Proposed fix

Not yet decided; options to weigh at triage:

1. Suppress `codeAction` (and/or diagnostics) responses for `CompilerRoute.External` — closest to
   how most IDEs treat decompiled/library sources, but loses a real capability users might want
   (external files are genuinely compiled against real javac).
2. Keep the current behavior but surface a clear affordance — e.g. a status-bar / virtual-text
   marker in the Neovim runtime — so users know the buffer is an external, read-only source.
3. Add a lightweight marker alongside `publishDiagnostics` (or a custom notification) so editor
   integrations can render a read-only indicator without disabling any feature.

### Probe commands

```bash
python3 dev/explore.py \
  ~/.cache/lathe/jdks/<jdk-id>/java.base/java/lang/String.java \
  diagnostics
```

### Regression targets

None yet — undecided pending triage.

---

## Implementation notes

The release slice is derived from the gap fields, not maintained as an ordered list here: the work
for a release is every gap with `Status: accepted` and the matching `Target` (see
[gap-process.md](gap-process.md)).

---

# Find References Gaps (FR)

Active `textDocument/references` gaps discovered by live probing against a large `@Builder`-heavy
reactor workspace. Resolved FR entries are in [gaps-archive.md](gaps-archive.md).

No active FR gaps remain; resolved entries are in [gaps-archive.md](gaps-archive.md).

---

# Completion Gaps (CQ)

Active completion-quality gaps. Discovered and triaged via the completion appendix of the
[gap workflow](gap-workflow.md); checked against the completion [expectations](../planned/lathe-completion-expectations.md)
contract. Resolved CQ entries are in [gaps-archive.md](gaps-archive.md).

## CQ-0002 — Method-reference completion returns no candidates

ID: CQ-0002
Status: accepted
Target: M3
Tier: assistive
Failure mode: missing-candidate
Owner component: SentinelInjector / SentinelParser

Project/file:
`/home/ag-libs/git/helidon/dbclient/tracing/src/main/java/io/helidon/dbclient/tracing/DbClientTracingProvider.java`

Probe command:
```bash
printf 'complete after "List::" expect of min 1\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/dbclient/tracing/src/main/java/io/helidon/dbclient/tracing/DbClientTracingProvider.java
```

Related project/file:
`/home/ag-libs/git/helidon/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClientBuilder.java`

Related probe:
```bash
printf 'complete after "this::" expect url username password min 1\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClientBuilder.java
```

Cursor context:
```java
config.asNodeList().orElseGet(List::of)
connConfig.get("url").asString().ifPresent(this::url)
```

IntelliJ or JDT behavior:
Expected IDE behavior is method-reference completion after `Type::` and `this::`.

Lathe behavior:
No completions are returned.
The log shows `parsed valid=false sentinelCtx=null` after `List::` and after `this::`.

Expected Lathe behavior:
Eventually,
method-reference completion should offer compatible methods for the receiver and target functional interface.

Accepted edit, if relevant:
Accepting `of` after `List::` should produce `List::of`.
Accepting `url` after `this::` should produce `this::url`.

Future design:
Method-reference completion is deferred until after M2.
The first implementation slice should be basic receiver-member listing,
not full smart compatibility filtering.
Add a `METHOD_REFERENCE` sentinel site,
detect `::`,
capture receiver text similarly to member access,
and route simple cases through member candidate generation.
`TypeName::` should offer static methods such as `List::of`;
`this::` should offer visible instance methods such as `this::url`;
ordinary expression receivers such as `service::` should offer instance methods.
Expected functional-interface filtering should be a later slice,
because robust compatibility needs the target type from contexts such as `orElseGet`,
`ifPresent`,
and `stream.map`.
Constructor references such as `TypeName::new` and array constructor references are also later slices.

Regression target:
Future method-reference completion test class or `CompletionEngineTest` method-reference section.

Notes:
This matches the existing deferred method-reference gap in the historical completion docs.

---

# Workspace Lifecycle Gaps (WS)

Workspace freshness and lifecycle gaps: reactor mirror / type-index staleness, source watching, sync
prompting, and reload. Resolved WS entries are in [gaps-archive.md](gaps-archive.md).

## WS-1 — Reactor mirror and type index go silently stale after a source change or branch switch

**Status: accepted — Target: Post-M3**

Discovered by workflow analysis (not live probing) while reconciling CA-4; recorded here because it
is the general problem of which CA-4's closed-file residual is one facet.

### Observed behaviour

Switching git branches — or otherwise changing source files outside the editor — leaves Lathe
describing the **previous** state of the workspace, usually with no prompt:

- After `git checkout <branch>` where only Java sources differ (the common case), the watcher
  reports `NO_CHANGE` and the user is never told to re-sync. The `.lathe/` mirrored bytecode and the
  reactor type-index shards continue to reflect the old branch until the next
  `mvn process-test-classes`.
- Types **added** on the new branch are missing from completion, missing-import actions, and
  `workspace/symbol` (CA-4's open-file enrichment only softens this for a file the user actually
  opens).
- Types **removed** on the new branch linger as phantom entries: completion offers them and
  missing-import actions insert an `import` for a class that no longer exists.
- `definition`, `references`, and `typeHierarchy` into non-open reactor sources resolve against the
  stale mirror and can point at old-branch files or positions.

If the branch differs in POM files, `WorkspaceWatcher` fingerprints the POMs and does raise the
advisory "run `mvn process-test-classes`" prompt — but that path only fires on POM changes, not on
source changes, and `reload()` merely re-reads the still-stale `.lathe/` from disk (it does not
re-run Maven).

### Root cause

Staleness detection is intentionally coarse and keyed only to Lathe's own artifacts:

- `WorkspaceWatcher.poll()` checks exactly two things — `workspace.json` mtime (→ full `reload()`)
  and POM fingerprints (mtime + size → advisory sync prompt). It never inspects source-root
  contents.
- `LatheWorkspaceService.didChangeWatchedFiles` acts **only** on `FileChangeType.Deleted` events;
  `Created` and `Changed` events on non-open source files are dropped.
- The reactor type index and the `.lathe/` mirror are produced only by `lathe:sync` and are never
  invalidated by filesystem source changes.

The `lathe-lightweight-watcher.md` design's Non-Goals claim source watching "is already handled via
LSP `workspace/didChangeWatchedFiles`" — the deletion-only implementation makes that claim
inaccurate today.

### Proposed fix

Not yet decided; options to weigh when scheduled, cheapest first:

1. Detect that a tracked source root's newest mtime is ahead of the last recorded sync and raise the
   same advisory sync prompt already used for `POM_CHANGED` — no invalidation, just an honest nudge.
2. Act on `Created`/`Changed` watched-file events (not only `Deleted`) to invalidate or refresh the
   affected reactor type-index entries between syncs.
3. A fuller freshness model that reconciles the reactor index and mirror with on-disk sources
   without a Maven round trip; overlaps with [Sibling Recompilation](../planned/lathe-sibling-recompilation.md)
   and the [Reactor Type Index](../planned/lathe-reactor-type-index.md) freshness follow-ups.

This subsumes CA-4's remaining closed-file case (new/renamed types in files the user has not opened),
which is only discoverable today after a manual sync.

### Probe commands

Not probeable through `explore.py`; reproduced by checking out a branch that adds and removes a
reactor type, then requesting completion / missing-import actions without running
`mvn process-test-classes`.

### Regression targets

None yet — to be defined when the fix is scheduled.
