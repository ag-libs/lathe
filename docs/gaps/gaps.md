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
| `CQ-NNNN` | completion | Completion quality; checked against the completion [expectations](../done/lathe-completion-expectations.md) contract |
| `WS-N` | workspace lifecycle | Workspace freshness and lifecycle: reactor mirror / type-index staleness, source watching, sync prompting, and reload |
| `TE-N` | test execution | Maven test-fork capture, replay launch fidelity, and test-classpath isolation |
| `DB-N` | debug & evaluation | In-process DAP adapter and expression-evaluator scope, fidelity, and coverage |
| `NV-N` | neovim client | The shipped Neovim plugin (`lua/lathe/…`) and its recommended configuration |
| `MC-N` | MCP / agent facade | The in-process `LatheEngine` facade and `lathe-mcp-server` tool surface that exposes Lathe to AI agents |

## Finding the work for a release

The slice for a release is derived, not hand-maintained: every gap with `Status: accepted` and the
matching `Target` (see [gap-process.md](gap-process.md)). Post-beta, releases are demand-driven — work
accrues under `Target: next` and is version-named when cut, so `next` is the live slice.

```bash
grep -nE '^(Status|Target):|^\*\*Status' docs/gaps/gaps.md     # scan active entries
grep -n 'Target: next' docs/gaps/gaps.md                       # what the next cut is trending toward
```

Entries follow, grouped by area: exploration (EG) below, then Find References (FR), Code Actions
(CA), Completion (CQ), Workspace Lifecycle (WS), Test Execution (TE), Debug & Evaluation (DB),
Neovim Client (NV), and MCP / Agent Facade (MC).

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

**Status: deferred — Target: backlog**

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

<!-- EG-049..EG-055 were discovered by differential testing against jdtls (dev/jdtls_diff.py; see
docs/planned/lathe-jdtls-differential-testing.md) on a real multi-module reactor. Each is a behaviour
where jdtls returns a result Lathe does not, or the two disagree substantively. -->

## EG-050 — Javadoc `{@link}` / `{@code}` regions not resolved for references, highlight, completion

**Status: deferred — Target: backlog (references/highlight shipped; completion deferred).**

### Delivered — references, highlight, and rename over Javadoc references

`textDocument/references`, `textDocument/documentHighlight`, and rename now cover a symbol's Javadoc
reference-tag mentions (`{@link #of(Class)}`, `{@linkplain}`, `@see`, `@throws`), for both members and
types; and go-to-definition / hover resolve a reference under the cursor (hover is the archived
EG-003). Implemented via javac's structured `DocTrees` — the comment is read as a parsed
`DocCommentTree`, never by scanning its text: a shared `JavadocReferences.forEachReference` delivers
each reference resolved to an element + source span, consumed by `JavadocReferenceResolver` (cursor →
element) and `ReferenceLocator.scanJavadocReferences` (match + emit).

Regression targets:
`ReferenceLocatorTest.javadocLinkMentions_includedForMemberAndType`,
`HoverTest.hover_javadocLinkTags_resolveReferencedTypeAndMember`.

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

**Status: deferred — Target: backlog (multi-line Javadoc folding shipped; non-Javadoc comments deferred).**

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

## CA-7 — Pasting code with unresolved types requires per-symbol quick fixes; no whole-file "add missing imports"

**Status: deferred — Target: backlog (add-missing slice shipped; automatic invocation deferred).**

Signal: user feedback — pasting a snippet with several unimported types was recurring friction: the
only path was moving the cursor onto each unresolved name and invoking the single-name import quick fix
in turn.

### Delivered — whole-file "add missing imports" (slice 1)

`:LatheMissingImports` (server command `lathe.missingImports`, also surfaced as the "Add missing
imports…" code action when the cursor is on an unresolved type) resolves every unresolved type in the
buffer in one pass. It works off the live buffer's debounce compile — no save round-trip needed, since
a syntactically-valid paste already yields `cant.resolve` diagnostics on change. The server enumerates
those TYPE_REF names and resolves each against the type index via a shared `ImportCandidates` helper
(extracted from `ImportQuickFixProvider`), returning each name with its candidate FQNs. The Neovim
client auto-adds unambiguous names, prompts one at a time (`vim.ui.select`) for ambiguous ones, inserts
all chosen imports in one edit, and summarises what was added and any name it could not resolve.

No `source.*` capability is claimed: `source.organizeImports` would over-promise (it implies unused
removal + sorting) and `source.addMissingImports` is non-standard, so the feature is an
`executeCommand`, consistent with `lathe.typeHierarchy` / `lathe.createType`.

Regression targets: `CodeActionTest.missingImports_mixedNames_partitionsCandidatesByAmbiguity`,
`CodeActionTest.missingImports_allTypesResolved_returnsNoItems`,
`CodeActionTest.codeAction_typeRef_offersAddMissingImportsCommand`; client `imports_spec.lua`.

### Remaining — automatic invocation (deferred, backlog)

Running the command **automatically** — on paste and/or as an on-save step alongside format-on-save —
so the common case needs no manual invocation. A Neovim-client concern layered on the shipped command;
keep it opt-in like `format_on_save`. Unused-import removal and import sorting (a fuller "organize
imports") are separate later slices.

---

# Completion Gaps (CQ)

Active completion-quality gaps. Discovered and triaged via the completion appendix of the
[gap workflow](gap-workflow.md); checked against the completion [expectations](../done/lathe-completion-expectations.md)
contract. Resolved CQ entries are in [gaps-archive.md](gaps-archive.md).

---

# Workspace Lifecycle Gaps (WS)

Workspace freshness and lifecycle gaps: reactor mirror / type-index staleness, source watching, sync
prompting, and reload. Resolved WS entries are in [gaps-archive.md](gaps-archive.md).

No active WS gaps remain; resolved entries are in [gaps-archive.md](gaps-archive.md).

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

## DB-7 — Breakpoints in a class outside the launched module resolve to no source (and only arm when the file is open)

**Status: accepted — Target: next (primary fix shipped; arming breakpoints in unopened files remains).**

Signal: user feedback — setting breakpoints across several project source files and debugging suspends
at seemingly "random" places along the execution path rather than at the set breakpoints. Reproduced
and diagnosed (below); it is **not** a staleness / line-table issue.

### Observed behaviour

With breakpoints spread across more than one class, a debug run appears to stop at locations that
follow the runtime flow but do not correspond to the breakpoints. In the editor a stop in another
module's class lands on a wrong/blank location because the adapter returns a stack frame with **no
source**, so nvim-dap cannot highlight where it actually stopped — it reads as a "random" halt.

### Reproduced (multi-module invoker workspace, fresh bytecode)

`dev/debug_probe.py` (extended with `--bp path:line`, multi-file) against the `multi-module` invoker
project, launching `com.example.app.Main` with breakpoints in `Main` (module `app`) and in
`StringUtils.upper` (module `core`, called at `Main:10`):

- Breakpoints in `Main` (the launched class's own module) all hit on their exact lines.
- The `StringUtils` breakpoint, when that file is **not open**, never fires — the debuggee runs past it.
- When `StringUtils.java` **is** opened, the breakpoint fires, but the stop frame comes back as
  `<NO-SOURCE>:8 (frame=StringUtils.upper(String))` — it stopped correctly, but with no source path.

Fresh `mvn process-test-classes` before the run (no edits) — so staleness is ruled out.

### Root cause (confirmed)

Two coupled defects in the debug source-lookup path, both from scoping the debug session to the
launched module:

1. **No cross-module source resolution.** `WorkspaceSession` builds the debug provider context with
   only the launched module's source roots
   (`configsFor(moduleRel).flatMap(c -> c.sourceRoots())`, WorkspaceSession.java ~474), where every
   other feature uses `workspace.allSourceRoots()`. So `LatheSourceLookUpProvider.getSource(fqn)` →
   `TypeSourceLocator.findSourceFile(fqn, sourceRoots)` returns `null` for any class in another reactor
   module, and the DAP stack frame has no source. This is the "cross-module reverse lookup is Phase 2"
   deferral noted in `LatheSourceLookUpProvider`'s javadoc, now biting in practice.
2. **Arming needs the file open.** `LatheSourceLookUpProvider.classNameAt` → `enclosingBinaryName`
   reads only the module worker's open-file analysis cache (`cache.get(uri)`), returning empty for a
   file that is not currently attributed — so a breakpoint in an unopened file gets no class name and is
   never armed.

### Fix direction

- **Primary — shipped:** the debug provider context now receives `workspace.allSourceRoots()` plus the
  manifest's dependency and per-module JDK source dirs, so cross-module (and library) frames resolve to
  their real source files.
  Defect 1 is fixed: a stop in another module's class shows the right file:line when its file is open.
- **Follow-up — remaining:** arm breakpoints in files that are not open — attribute/compile the
  breakpoint file on demand in the owning module worker rather than requiring the open-file cache.
  Today `LatheSourceLookUpProvider` logs a `[breakpoint] … not armed` warning and the breakpoint never
  suspends.

### Probe commands

```bash
# Multi-file breakpoints across modules; every requested file:line must appear in the actual stops:
python3 dev/debug_probe.py --workspace <ws> <app/Main.java> --main com.example.app.Main \
  --bp <app/Main.java>:8 --bp <core/StringUtils.java>:8
# Today: the core stop reports <NO-SOURCE>; with the primary fix it reports StringUtils.java:8.
```

### Regression targets

To be added with the fix (positive: a breakpoint in a different reactor module than the launched class
suspends and its frame resolves to that module's source file:line; negative: a same-simple-name class
in another module is not confused for the launched one).

---

# Neovim Client Gaps (NV)

Gaps in the shipped Neovim client plugin (`lua/lathe/…`) and its recommended configuration, as
distinct from the server's LSP/DAP surface. Resolved NV entries move to
[gaps-archive.md](gaps-archive.md).

All NV entries to date are resolved in [gaps-archive.md](gaps-archive.md); none are currently open.

---

# MCP / Agent Facade Gaps (MC)

Gaps in the in-process `LatheEngine` facade and the `lathe-mcp-server` tool surface that exposes
Lathe to AI agents, as distinct from the LSP/DAP server behaviour those tools sit on top of.
Resolved MC entries move to [gaps-archive.md](gaps-archive.md).

## MC-1 — `describe_symbol` (and position tools) return empty on dependency/JDK source files: the MCP warmup skips the External compile route

**Status: accepted — Target: next (root cause confirmed by code trace; fix is small and localized).**

Signal: dogfooding `describe_symbol` while designing [EG-003](gaps-archive.md#eg-003--hover-returns-null-on-positions-inside-javadoc-type-reference-tags--done).
Pointing it at a javac `DocTrees` method to confirm an API returned nothing; the same symbol
described from a use-site in reactor code returned the full signature + javadoc.

### Observed behaviour

`describe_symbol` aimed at a symbol **in a dependency or JDK source file** (e.g.
`~/.cache/lathe/jdks/…/jdk.compiler/com/sun/source/util/DocTrees.java`) returns empty
`{"markup":""}` — on both declarations and references. The identical symbol described from a
**use-site inside a reactor file** (a `DocTrees` reference in `JavadocLocator.java`) resolves fully:

```
DocCommentTree getDocCommentTree(TreePath path)
Returns the doc comment tree, if any, for the Tree node identified by a given TreePath. …
source: jdk.compiler (JDK 26)
```

Crucially, **hovering directly inside the same JDK/dep source file works in the editor**, so this is
an MCP-facade discrepancy, not an inherent "external sources can't be attributed" limitation and not
a staleness issue.

### Root cause (confirmed by trace)

Every position-based engine tool calls `LatheEngine.compileFromDisk(file)` to register and warm the
file before issuing the LSP request. `compileFromDisk` routes through
`WorkspaceSession.diagnosticsFuture`, which switches on `routeCompiler(uri)`:

- `CompilerRoute.Module` → `docs.put(...)` + `submitCompile(...)` — the file is registered and
  attributed, so the later `hover` finds a cached analysis.
- `CompilerRoute.External` (dep/JDK sources tracked by the manifest) → short-circuits to
  `CompletableFuture.completedFuture(List.of())` — **no `docs.put`, no compile**.

So the external file is never attributed, and `service.hover(...)` returns null → empty markup. The
editor path (`WorkspaceSession.onOpen`) compiles **unconditionally** via
`workspace.externalWorker()`, which is why the same hover works in the editor. `External` genuinely
has a compiling worker (`routeCompiler` maps it to `externalWorker()`), so attribution is available —
the warmup just doesn't use it.

### Fix direction (small)

Give the `External` route the same register-and-compile the `Module` route already gets in
`diagnosticsFuture`, using `external.worker()`:

```java
case CompilerRoute.External external -> {
  final var snapshot = docs.put(uri, content, version);
  candidateIndex.update(uri, content);
  final var result = new CompletableFuture<List<Diagnostic>>();
  submitCompile(
      external, snapshot, CompileMode.OPEN, (ignored, response) -> result.complete(response.diagnostics()));
  yield result;
}
```

`submitCompile` already accepts a `CompilerRoute` (`onSave` passes an `External` route through it), so
this is localized — external files attribute and cache exactly as the editor's `onOpen` does, and
every position tool that pre-warms via `compileFromDisk` benefits (`describe_symbol`,
`get_definition`/`get_diagnostics` on external paths). The `Missing` route stays empty. Consider
evicting the warmed external document after the one-shot engine call so read-only dep/JDK files do not
accumulate in the open-document set.

### Probe commands

```bash
# Via MCP (returns empty today; expected: rendered signature + javadoc):
#   describe_symbol file=~/.cache/lathe/jdks/…/com/sun/source/util/DocTrees.java line=110 column=36
# Contrast (already works): describe_symbol at a `DocTrees` use-site in a reactor .java file.
```

### Regression targets

To be added with the fix:

- `LatheEngineTest.describe_dependencyOrJdkSourceFile_returnsSignatureAndJavadoc` (positive: a symbol
  in a manifest-tracked external source resolves to its rendered signature + javadoc).
- `LatheEngineTest.describe_fileOutsideAnyModuleAndManifest_returnsEmpty` (negative: a `Missing`-route
  file stays empty).

---

## MC-3 — MCP server logs are not observable: the client persists only startup stderr; the server has no durable log sink

**Status: accepted — Target: next (durable file sink shipped; rotation/pruning and timestamp format remain).**

Signal: attempting to read the server's per-call logs to diagnose [MC-1](#mc-1--describe_symbol-and-position-tools-return-empty-on-dependencyjdk-source-files-the-mcp-warmup-skips-the-external-compile-route)
— the logs were nowhere to be found despite the server emitting them.

### Observed behaviour

The server emits one `INFO` line per tool call to stderr by default
(`io.github.aglibs.lathe.level=INFO`), e.g. `[tool] search_symbols query=DocTrees 875ms ok` — verified
by driving the launcher standalone and reading its stderr. But **neither** Claude Code sink persists
the server's stderr past the connection handshake:

- `~/.cache/claude-cli-nodejs/<project>/mcp-logs-<server>/*.jsonl` (undocumented) — one
  `Server stderr:` snapshot at connect, then only client-side `Calling MCP tool` / `completed` lines.
- `~/.claude/debug/<session>.txt` (documented, via `claude --debug=mcp`) — the same: a single
  `[ERROR] … Server stderr:` entry at the connect instant, then only client `[DEBUG]` lines.

So every post-handshake server line — all per-call `[tool]`/`[symbol]` INFO, and any FINE under
`LATHE_DEBUG` — is unobservable through the client.

### Root cause

A client-side capture limitation, and an undocumented one. The MCP spec says stdio stderr is
"captured by the host application automatically" but specifies no lifetime or size limit; Claude Code
records only the startup burst. A request for proper per-server logs is open and marked "not planned"
(anthropics/claude-code#29035). The server therefore cannot rely on the client to surface its logs,
and today has no file sink of its own — only a `ConsoleHandler` to stderr.

### Fix direction

Give the MCP server a durable, rotating JUL `FileHandler` alongside the existing `ConsoleHandler`:

- Location (Option B): `~/.cache/lathe/logs/mcp-<workspace-slug>.log`, under the global cache dir the
  server binary already lives in — untouched by `lathe:sync` cleaning `.lathe`, and disambiguated per
  workspace with a readable slug (mirroring the client's own `-home-…-lathe` scheme). New
  `LatheLayout.CACHE_LOGS_DIR = "logs"` (no hardcoded dir names); env override `LATHE_LOG_DIR`.
- Rotation: pattern `mcp-<slug>.%g.log`, `limit≈5MB`, `count=3`, `append=true`, `%u` to stay safe if
  two servers share a workspace.
- Level `INFO` default, `FINE` under `LATHE_DEBUG`. Keep `ConsoleHandler` so the startup snapshot the
  client *does* capture still works.
- Emit the resolved log-file path in the startup line
  (`[startup] lathe-mcp-server ready — logging to <path>`) so the one stderr line the client persists
  points at the full log.
- Switch the formatter to **UTC / ISO-8601** timestamps (today JUL prints server-local time, e.g.
  `22:14` vs the client's `20:14` UTC — a needless correlation hazard).

The MCP `logging` protocol capability (`notifications/message`) is deprecated in favour of
stderr/OpenTelemetry, so a server-owned file plus stderr is the spec-aligned path — not protocol
logging.

### Delivered — per-session file sink

`LatheMcpServer.main` now attaches an appending JUL `FileHandler` via `LatheLogging.initFile`, writing
to `~/.cache/lathe/logs/mcp-<start-timestamp>-<pid>.log` (`LatheLayout.mcpSessionLog`), alongside the
existing `ConsoleHandler`. This differs from the per-workspace-slug design above: one file per server
process, so concurrent servers never share a file and no `%u` disambiguation is needed.

### Remaining

- **Retention:** nothing rotates or prunes `~/.cache/lathe/logs`; one file accrues per MCP session,
  indefinitely. Cap the per-file size and prune old session files (e.g. keep the newest N).
- **Timestamps:** `logging.properties` still formats server-local time; switch to UTC / ISO-8601.
- **Discoverability:** the startup line (`[startup] pid=… cwd=…`) does not name the log file; include
  the resolved path so the one stderr snapshot the client persists points at the full log.
- **Override:** no `LATHE_LOG_DIR` env override for the logs dir.

### Regression targets

To be added with the fix:

- `LatheMcpServerTest.logging_afterToolCall_writesPerCallLineToFile` (positive: a rotating log file is
  created under the resolved logs dir and receives the `[tool] …` line after a call).
- `LatheMcpServerTest.logging_timestamps_areUtcIso8601` (formatter emits UTC, not local time).
