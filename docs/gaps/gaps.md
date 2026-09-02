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
| `TE-N` | test execution | Maven test-fork capture, replay launch fidelity, and test-classpath isolation |
| `DB-N` | debug & evaluation | In-process DAP adapter and expression-evaluator scope, fidelity, and coverage |
| `NV-N` | neovim client | The shipped Neovim plugin (`lua/lathe/…`) and its recommended configuration |

## Finding the work for a release

The slice for a release is derived, not hand-maintained: every gap with `Status: accepted` and the
matching `Target` (see [gap-process.md](gap-process.md)).

```bash
grep -nE '^(Status|Target):|^\*\*Status' docs/gaps/gaps.md     # scan active entries
grep -n 'Target: M1' docs/gaps/gaps.md                         # the M1 slice
```

Entries follow, grouped by area: exploration (EG) below, then Find References (FR), Code Actions
(CA), Completion (CQ), Workspace Lifecycle (WS), and Test Execution (TE).

EG-003 is deferred until after M2 because it requires `DocTrees` attribution of Javadoc comment
positions,
which is a non-trivial hover extension.

---

## EG-003 — Hover returns null on positions inside Javadoc type-reference tags

**Status: accepted — Target: backlog**

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

**Status: accepted — Target: backlog**

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

**Status: accepted — Target: backlog**

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

## EG-048 — Go-to-definition on an incomplete method call lands on the file, not the method

**Status: deferred — Target: backlog**

### Observed behaviour

With a partially-typed call (`foo(`, missing arguments), `textDocument/definition` navigates to the
file top instead of the method. When the name is overloaded it should return multiple candidate
locations so the client shows a picker; when unambiguous it should resolve to the single method.

```bash
python3 dev/explore.py <ws>/.../Foo.java def <line>:<col>   # cursor in `foo(<incomplete>`
# today: Foo.java 0:0 ; expected: the method declaration, or a location list for overloads
```

### Root cause

The invocation is erroneous, so javac cannot attribute the method element; `SourceLocator.elementAt`
yields null and definition takes the file-top fallback. The pipeline also returns at most one
location — `WorkspaceSession.definitionFuture` maps `Optional<Location>` to a 0-or-1 list (`:1250`) —
so overloads cannot be offered even though the LSP result type (`Either<List<Location>, …>`) allows
many.

### Proposed direction

Recover the intended target with the existing sentinel/recovery pipeline
(`SentinelInjector` / `SentinelParser`) already used for completion on incomplete input: obtain the
receiver, method name, and argument index from `ParsedSentinel`. Enumerate the accessible
`ExecutableElement`s of that name on the receiver type and return **all** declaration locations
(single → jump, many → picker); extend `definitionFuture` to build a `List<Location>`. No ad-hoc
parsing — sentinel pipeline + javac elements only (AGENTS.md).

Deferred: the recovery integration, overload enumeration, and multi-location plumbing are the largest
of the three and are not in the current release slice.

### Regression targets

None yet — re-triaged from backlog when scheduled.

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

# Code Action Gaps (CA)

Active `textDocument/codeAction` provider gaps. Resolved CA entries are in
[gaps-archive.md](gaps-archive.md).

No active CA gaps remain; resolved entries are in [gaps-archive.md](gaps-archive.md).

---

# Completion Gaps (CQ)

Active completion-quality gaps. Discovered and triaged via the completion appendix of the
[gap workflow](gap-workflow.md); checked against the completion [expectations](../planned/lathe-completion-expectations.md)
contract. Resolved CQ entries are in [gaps-archive.md](gaps-archive.md).

## CQ-0002 — Method-reference completion returns no candidates

ID: CQ-0002
Status: accepted
Target: backlog
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

**Status: accepted — Target: backlog**

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

---

## WS-2 — No re-sync prompt after a source-only branch switch

**Status: deferred — Target: backlog**

Deferred from M2. The proposed fix was a Sync/Later prompt, but a prompt is both intrusive and
non-binding: the user can dismiss it and keep working against stale state, so it adds friction without a
reliable payoff — and a developer who deliberately switches branches already knows Lathe needs a manual
re-sync. The behaviour is documented instead (README → "How it works → Build capture"): files you have
open are analysed live, and changes to files you don't have open are picked up at the next
`mvn process-test-classes`. Revisit only if beta users report silent-staleness confusion (e.g. after a
`git pull` or merge they did not initiate), and then with a non-intrusive signal rather than a prompt.
WS-1 remains the backlog umbrella for actual invalidation/reconciliation.

### Observed behaviour

After a `git checkout` that changes only Java sources, `WorkspaceWatcher.poll()` reports `NO_CHANGE` and
the user is never told to re-sync, so completion, missing-import, `workspace/symbol`, and navigation
silently reflect the *previous* branch until the next `mvn process-test-classes`. For a public user
switching branches daily, silent staleness reads as "Lathe is wrong."

### Root cause

Staleness detection is keyed only to Lathe's own artifacts: `WorkspaceWatcher.poll()` checks
`workspace.json` mtime and POM fingerprints, never source-root contents (see WS-1 root cause).

### Proposed direction

WS-1 option 1 (cheapest): detect that a tracked source root's newest mtime is ahead of the last recorded
sync and raise the same advisory "run `mvn process-test-classes`" prompt already used for `POM_CHANGED` —
detection and an honest nudge, no invalidation. Full auto-invalidation / no-Maven freshness (WS-1
options 2–3) stays backlog.

### Regression targets

None yet — to be defined when scheduled (source newer than last sync → advisory prompt; no prompt when
in sync).

---

# Test Execution Gaps (TE)

Capture/replay gaps involving the Maven test fork, the recorded launch template, and the standalone
replay JVM. Resolved TE entries are in [gaps-archive.md](gaps-archive.md).

## TE-3 — `<systemPropertyVariables>` are not carried into the replay launch

**Status: deferred — Target: backlog**

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

## TE-2 — No named run-configuration selection (`:LatheRun {name}`)

**Status: deferred — Target: backlog**

The run-config overlay data model (a checkable `lathe-run.json` plus a gitignored `.lathe/run.json`,
field-merged per `(module, kind)`) is implemented, but only the built-in default and `(module, kind)`
overlays resolve; there is no command to select a *named* config. `:LatheRun {name}` (with
server-provided completion; a picker in a future VS Code client) is the planned surface. Gutter and
neotest runs work without it — a named config is only needed to customize a run. Design:
[lathe-run-test-debug.md](../done/lathe-run-test-debug.md) §8.2, §12.10.

### Regression targets

None yet — to be defined when the fix is scheduled.

---

## TE-4 — Replay working directory does not match Maven's (module basedir)

**Status: resolved**

### Observed behaviour

Tests and `main` runs that pass under `mvn` fail under Lathe's replay because they resolve
relative paths against a different current directory. Example, a test reading a fixture by a
project-relative source path:

```
java.lang.AssertionError: src/test/resources/fixtures/logo.png
    at com.example.app.AppResourceTest.getPutDelete(AppResourceTest.java:455)
```
(Genericised; the real class and path are from a private workspace.)

### Root cause

Maven runs the Surefire fork (and `exec`/`main`) with the JVM working directory set to the
module's `${project.basedir}`, so relative paths like `src/test/resources/…`,
`new File("target/…")`, or `Paths.get("config/…")` resolve against the module. Lathe replays a
fresh JVM whose current directory is not set the same way (it inherits the server/launcher cwd,
e.g. the reactor root), so those relative paths point elsewhere and the run fails.

### Proposed direction

Set the replay JVM's working directory to the module basedir (the same directory Surefire uses),
for both test and `main` replay, so relative-path resolution matches Maven. A run-configuration
overlay may later override it (see [run-configuration.md](../guide/run-configuration.md)), but the
default must match Maven. Confirm the basedir is captured (or derivable from the manifest) per
module.

### Resolution

Fixed in `fix(run): set the replay working directory to the module basedir (TE-4)`. A required
`workingDir` (module-relative to the workspace root) was added to `TestLaunchData` and
`MainLaunchData`; the capture side sets it to `moduleRel` (`LaunchCapture` /
`MainLaunchWriter.deriveLaunch`), and `RunOverlay.resolveCwd` resolves the replay cwd from it —
`workspaceRoot.resolve(workingDir)` — unless a run-configuration overlay supplies an explicit
`cwd`. An empty `workingDir` (the reactor-root module) resolves to the workspace root, matching
Maven's Surefire/exec fork.

### Regression targets

`RunOverlayTest` — asserts the resolved cwd equals the module basedir for the default case and
that an explicit overlay `cwd` wins.

---

# Debug & Evaluation Gaps (DB)

Gaps in the in-process DAP adapter and the two-stage expression evaluator (see
[lathe-run-test-debug.md](../done/lathe-run-test-debug.md) §12.11). The debug surface is complete
for the current milestone — breakpoints, stepping, inspection, conditional breakpoints, and
read/invoke expression evaluation all work — so the entries below are the deliberately deferred
remainder, re-triaged in a future round.

## DB-1 — Expression evaluation is read/invoke only; no assignment or `setVariable`

**Status: deferred — Target: backlog**

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

**Status: deferred — Target: backlog**

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

## DB-3 — Object-scoped `evaluate` overload — done

**Status: done — Target: M2**

Invoked only by java-debug's `JavaLogicalStructure` to render collections/maps in the Variables view
(it evaluates member expressions such as `size()` / `toArray()` with `this` = the object). Now
implemented: `LatheEvaluationProvider.evaluate(expression, object, thread)` attributes the expression
against an *accessible* type of the object (the runtime type when public, else the nearest public
supertype — since types like `java.util.ImmutableCollections$ListN` can't be named in source) via a
synthetic typed receiver (`SourceAnalysisSession.attributeReceiverExpression`), then interprets it
with that receiver bound to the object (a `JdiInterpreter` seed binding). Reuses the Stage-2
interpreter; any failure falls back to the raw field view. Collection/map logical views now populate.

### Regression targets

`dev/debug-e2e.sh` object-scoped case: at a test-scope `main`, expanding an `ArrayList` local yields
its logical elements (`0="alpha"`, …) rather than raw fields, via the `debug_probe.py --expand` mode.

### Observed behaviour

`IEvaluationProvider.evaluate(expression, ObjectReference, thread)` returns a failed future
("object-scoped evaluation is not supported yet"). Only the thread/frame-scoped overload and
`evaluateForBreakpoint` are implemented. The adapter uses the frame-scoped path for watches, hover,
console, and conditional breakpoints, so this is not user-visible today; it would matter for evaluate
requests that supply a receiver object instead of a frame.

### Expected behaviour

Evaluate an expression with a supplied `ObjectReference` as the implicit receiver/`this`, reusing the
Stage-2 interpreter against that object rather than a stack frame.

### Probe commands

Not reproducible through `debug_probe.py` today (the probe only issues frame-scoped `evaluate`);
exercised by a DAP client that sends `evaluate` with an object scope.

### Regression targets

None yet — to be defined when the fix is scheduled.

---

## DB-4 — Debug-console / REPL code completion returns nothing

**Status: done — Target: M2**

### Observed behaviour (resolved)

The DAP `completions` request was fully plumbed but unanswered: java-debug advertises
`supportsCompletionsRequest = true` and Lathe registered a `LatheCompletionsProvider`, but the
provider was a stub returning an empty list, so the `nvim-dap` REPL offered nothing.

### Resolution

`LatheCompletionsProvider` now answers `codeComplete(frame, snippet, line, column)` by running
Lathe's ordinary completion engine at the frame's source line — reusing only javac/engine outputs,
with no ad-hoc Java parsing:

- Frame → source file → module worker via the shared `FrameSources` helper (extracted from
  `LatheEvaluationProvider`).
- The snippet is spliced as the initializer of a throwaway local (`var __LATHE_COMPLETE__ =` on the
  frame's line, the snippet on the next line) so it sits in an **expression** position — a bare
  statement only surfaces keywords. This is source-text construction (like `ExpressionSplice`), not
  parsing.
- Completion runs through a new `SourceAnalysisSession.completeTransient` /
  `CompilationWorker.completeTransient` that (a) never touches the open-document cache, and (b)
  attributes the **unmodified** file (FAST, uncached) as the completion's baseline analysis — which
  the semantic completer requires to resolve the frame's locals/params/members (without it only
  keywords and type-index candidates survive).
- `CompletionOutcome` items map to `Types.CompletionItem` using the engine's own javac-derived
  replacement range as the DAP `start`/`number`; the request's LSP trigger context is ignored by the
  engine (it derives context from the content), so no snippet inspection is needed.

The client is free: `nvim-dap` wires the REPL `omnifunc` to the `completions` request automatically,
and a completion plugin (`cmp-dap`, or a blink.cmp source) can surface it as-you-type.

Verified: locals (`arg`→`args`), static imports (`asser`→`assertEquals`), static members
(`System.`), members on `this`/objects/`String`, and types all complete. A frame with no debuggee
interaction — pure read-only source analysis, no JDI, no invocation lock.

**Resolved (separate gap, not DB-4):** member completion on an **array-typed** receiver (`args.` where
`args` is `String[]`) now offers `length`, `clone()`, and the inherited `Object` members — a shared
completion-engine fix (the editor path shares it), tracked as
[CQ-0053](gaps-archive.md) (resolved).

### Probe commands

```bash
python3 dev/debug_probe.py --workspace <ws> <MainFile.java> --line <N> --main <Class> \
  --complete "arg" --expect-item "args"          # local param
python3 dev/debug_probe.py --workspace <ws> <TestFile.java> --line <N> --method <m> \
  --complete "asser" --expect-item "assertEquals" # static import
```

### Regression targets

- `LatheCompletionsMappingTest` (LSP→DAP item mapping, replace-range and kind).
- `dev/debug-e2e.sh` debug-console completion cases (local, static member, static import, `this.`).

---

## DB-5 — Local inspection fails because on-save compiles strip the local-variable table

**Status: resolved**

### Observed behaviour

At a breakpoint, both the Variables/scopes pane and local evaluation come up empty for a
genuine local in the suspended method — the pane silently shows no locals, and `dap> <name>`
fails with `EvaluationException: no local-variable table for this frame`. Breakpoints still
bind (the line stops), so only locals are affected. Reproduced on an ordinary `@Test` method
that a healthy `-g` build would debug fine.

### Root cause

Not the project's build flags — Lathe's own on-save compile.

- On every save, `didSave` → `WorkspaceSession.onSave` runs a **FULL** compile
  (`WorkspaceSession.java`, `submitCompile(..., CompileMode.FULL, ...)`).
- A FULL compile calls `task.generate()` (`JavacRunner.compileFull`), **writing `.class` files**
  to `config.latheClassesDir()` = `.lathe/<module>/classes` (or `test-classes`) — the **same**
  directory the Maven compiler shim mirrors into.
- `ModuleSourceCompiler.buildOptions` added `--release`, `-encoding`, `-parameters`,
  `--enable-preview`, and the project's captured `compilerArgs`, but **never `-g`**. javac's
  default without `-g` is `-g:source,lines` → `LineNumberTable` (breakpoints bind) but **no
  `LocalVariableTable`**.

So on save, the server overwrote the Maven-mirrored bytecode (which had the LVT, from
`maven-compiler-plugin`'s `<debug>true>` default) with its own `-g`-less version, and the debug
session — replaying `.lathe/` — loaded the class with no locals. Files not yet re-saved kept the
Maven mirror and debugged fine, which is why the failure looked build-specific.

### Resolution

`ModuleSourceCompiler.buildOptions` now adds `-g` for `CompileMode.FULL`, so the classes the
server writes into `.lathe/` carry a `LocalVariableTable` and the replayed bytecode always
exposes locals. FAST/OPEN only `analyze()` (no bytecode written), so they are unchanged.

The `no local-variable table` case is now rare — a class Lathe did not compile (a precompiled
dependency) or a synthetic lambda/bridge frame — so `JdiInterpreter.readLocal` surfaces a single
concise message (`local '<name>' is unavailable: this frame has no local-variable table`)
instead of the earlier build-advice wording, which the fix made misleading.

### Probe commands

Reproduce the *old* behaviour by compiling with `-g:lines,source` (line numbers, no vars) and
debugging a local; with the fix, a normal on-save workflow exposes locals in every module.

### Regression targets

None yet — to be defined when scheduled.

---

## DB-6 — Variable inspection throws `InvalidStackFrameException` after an invocation-resume

**Status: resolved**

### Observed behaviour

During a suspended debug session, requesting the Variables/scopes view fails:

```
Failed to get variables. Reason: com.sun.jdi.InvalidStackFrameException: Thread has been resumed
```

Evaluation of individual locals may still work, but expanding the scope (the Variables pane /
`variables` request) errors intermittently.

### Root cause

A JDI `StackFrame` is valid only while its thread stays suspended. Lathe's **evaluate** path is
built around this: `JdiInterpreter.frame()` (`JdiInterpreter.java:106`) never caches the frame,
re-fetching it after any invocation, because "a method/constructor call, or a cold-class
force-load … resumes the thread and permanently invalidates every prior `StackFrame`" (see also
`LatheEvaluationProvider.java:49`).

But the **variables/scopes** request is handled by Microsoft java-debug, which caches frames by
id in its own frame manager. When a Lathe evaluation invokes a method (or force-loads a cold
class), it momentarily resumes the thread and invalidates *all* frames — including the ones
java-debug cached for the Variables view. The next `variables` request then reads a stale frame
and throws `InvalidStackFrameException`. So Lathe's own guard does not extend to the DAP
variables path it hosts.

### Proposed direction

Ensure the hosted java-debug frame cache is refreshed (or invalidated) whenever a Lathe
evaluation resumes the thread — e.g. drop/rebuild java-debug's cached frames after an
invocation, or surface a retry — so an evaluate that invokes a method does not break the
subsequent Variables view. Confirm the exact trigger (invocation-based evaluate vs. any
step/resume) when scheduled.

### Resolution

Fixed in `fix(debug): reload java-debug's frame cache after an in-eval invocation (DB-6)`.
`LatheEvaluationProvider.initialize` captures the session's shared `IStackFrameManager` (the
same cache every java-debug handler mutates), and `invokeGuarded` refreshes it in a `finally`
via `reloadStackFrames(thread)` — the same primitive `StackTraceRequestHandler` uses — right
after each invocation. That is the one moment core cannot cover on its own: Lathe's
resume/suspend is hidden from java-debug because `isInEvaluation` suppresses those events, so no
`stackTrace` gets re-driven to rebuild the cache. The reload runs inside the existing per-thread
lock and is best-effort (a resumed thread yields an empty cache rather than an error), so no new
lock or shared state is introduced.

### Probe commands

Not reproducible through `debug_probe.py` (frame-scoped evaluate only). Reproduced in an
interactive DAP session: at a breakpoint, run an evaluation that invokes a method, then expand
the Variables view.

### Regression targets

`LatheEvaluationProviderTest` — asserts an invocation triggers `reloadStackFrames`, that a
missing frame manager is a no-op, and that a reload failure is swallowed rather than surfaced.

---

# Neovim Client Gaps (NV)

Gaps in the shipped Neovim client plugin (`lua/lathe/…`) and its recommended configuration, as
distinct from the server's LSP/DAP surface. Resolved NV entries move to
[gaps-archive.md](gaps-archive.md).

(NV-1 is resolved in [gaps-archive.md](gaps-archive.md); active entries continue at NV-2.)

## NV-2 — A neotest test run gives no completion notification, only a "Running" start toast

**Status: accepted — Target: M2**

### Observed behaviour

Starting a neotest run shows a single `vim.notify` toast — `Running <label>`
(`lua/lathe/neotest.lua`, `run_spec`) — and then nothing when the run finishes.
The user gets no at-a-glance summary of how the run went; they must open the summary panel or the
docked output to learn the outcome.

A `main` run, by contrast, notifies on **both** ends: `Running <class>` at launch and
`<class> exited <code>` on completion (`lua/lathe/run.lua`, `on_finished`), the latter at `WARN`
level on a non-zero exit. The server also logs a one-line INFO result per run
(mode, target, elapsed ms, counts) that never surfaces in the editor.

### Root cause

The neotest path emits only the start toast in `run_spec`; `results()` reconciles per-test statuses
into the summary tree and gutters but does not raise a run-level completion notification, and neither
does the `lathe/testEvent` stream handler. There is no completion-summary surface equivalent to
`run.lua`'s `on_finished`.

### Proposed direction — what to present

A single concise completion toast per run, mirroring the server's `[operation] target detail Xms
outcome` log convention and the summary already computed in `results()`:

- **Scope** — the run label (method / class / package) already passed to `run_spec`.
- **Counts** — passed / failed / skipped, plus the total, derived from the same
  `outcome.testResults` and fan-out `results()` already builds.
- **Duration** — elapsed wall-clock ms for the replay (the server can return it on the run outcome;
  the client otherwise times from the start toast).
- **Level** — `INFO` when nothing failed, `WARN` when any test failed or the run was blocked/errored,
  so a failure is visible without reading the text.

Example: `tests io.example.portal — 12 passed, 1 failed, 2 skipped (1.8s)` at `WARN`.
Keep it to one line (no per-test spam — the summary panel already itemises), fire it once from
`results()` (or the run-future completion), and suppress it for a still-streaming partial pass.
Reuse `run.lua`'s `notify` helper shape (`{ title = "Lathe" }`) for consistency.

### Regression targets

None yet — to be defined when scheduled (all-pass run → one INFO toast with counts; a run with a
failure → one WARN toast; counts match `results()`).

---

## NV-3 — Debugging a test does not update neotest gutters or the summary panel

**Status: accepted — Target: M2**

### Observed behaviour

Running a test via neotest paints the gutter signs and summary tree with pass/fail status.
Debugging the same test via `:LatheDebug` leaves both **unchanged** — the debug session runs, but
neotest never learns the result, so gutters and the summary panel stay at their previous state (or
blank).

### Root cause

`:LatheDebug` (`lua/lathe/dap.lua`, `M.debug`) resolves the runnable under the cursor and calls
`dap.run(M._test_config_for(test))` directly, launching the `lathe` nvim-dap adapter
(`lathe.debug.test`). This path is entirely parallel to neotest: it never goes through the adapter's
`build_spec`/`results()`, and neotest is not told a run started or finished, so it has no result to
apply to the gutter/summary consumers.

### Proposed direction

Route test-debugging through neotest so the same `results()` reconciliation updates the UI, rather
than bypassing it. Two candidate shapes to weigh when scheduled:

1. A neotest **`dap` strategy** for the adapter: `neotest.run.run({ strategy = "dap" })` builds the
   spec, launches the Lathe debug adapter (reusing `lathe.debug.test`'s DAP-port handshake), and
   reports the captured per-test results back to neotest on session end — the neotest-idiomatic path.
2. Failing that, have the debug session's terminal outcome feed the captured `testResults` into
   neotest's result state for the debugged position(s), so gutters/summary update even though the run
   was DAP-driven.

Ties into NV-4 (both stem from the debug path not sharing the run/neotest output+results surface).

### Regression targets

None yet — to be defined when scheduled (debugging a passing test marks it passed in the summary; a
failing one marks it failed).

---

## NV-4 — Debugging a test flashes the output window and never shows pass/fail

**Status: accepted — Target: M2**

### Observed behaviour

When running a test, the docked output console opens and **stays** open, streaming the replay
transcript, and the run resolves to a visible pass/fail. When **debugging** a test, the output window
"opens and closes quickly," and there is no indication whether the test passed or failed — the user
wants the debug path to behave like the run path here.

### Root cause

The run and neotest paths call `output.reset()` / `output.ensure_open()` and keep the shared docked
console (`lua/lathe/output.lua`) for the life of the run, then surface an outcome
(`run.lua on_finished`, or neotest's `results()`). The debug path (`lua/lathe/dap.lua`) does neither:
it hands off to nvim-dap (its own REPL/console) and only calls `stackdecorate.decorate_live_output()
` on `event_terminated`/`event_exited`. Any transient appearance of the Lathe console during launch
is not kept open, and there is no results()/exit-code surface, so pass/fail is never shown.

### Proposed direction

Give the debug path the same output+outcome surface as a run: open and keep the shared docked
console for the debug session, stream the debuggee's transcript into it, and on session end surface
the outcome (pass/fail from the captured test results or exit code) the same way `on_finished` /
`results()` do. Best delivered with NV-3 (a neotest `dap` strategy naturally reuses the run path's
output and results surfaces).

### Regression targets

None yet — to be defined when scheduled (a debug session keeps the docked console open and reports a
terminal pass/fail).

---

## NV-5 — No way to re-run only the failed tests from the last neotest run

**Status: accepted — Target: M2**

### Observed behaviour

After a neotest run leaves some tests red, there is no command or keymap to re-run **just** those
failed tests. The user must re-run the whole class/package (paying for the passing tests again) or
navigate to each failure and run it individually.

### Root cause

The shipped neotest keymaps (`plugins/lathe.lua`: `<leader>tt` nearest, `<leader>tf` file,
`<leader>ts` summary, `<leader>tS` stop) expose run-nearest/file/stop but no "run failed" surface,
and neotest core has no built-in status-filtered run — the failed set has to be gathered from
neotest's result state and re-run explicitly.

### Proposed direction

Add a client helper + keymap (e.g. `<leader>tl` / a `:LatheTestFailed`-style command) that collects
the failed leaf positions from the last run — from `neotest` result state, or from a small
last-run failed-set the adapter already has the data to track (`results()` sees every status) — and
re-runs exactly those positions (one spec per class, reusing `build_spec`, so it stays a single
replay per class rather than one JVM per method). Decide when scheduled whether to source the failed
set from neotest state or track it in the adapter.

### Regression targets

None yet — to be defined when scheduled (after a run with N failures, the re-run targets exactly
those N positions and no passing ones).
