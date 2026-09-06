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

## CQ-0055 — `:LatheNewClass`/`Interface`/`Record`/`Enum` — scaffold a new type in the right package

ID: CQ-0055
Status: v1/v2 implemented client-side (Neovim `:LatheNewClass`/`Interface`/`Record`/`Enum`, incl. dotted
name); **v3 redesign planned and superseding** — a single `:LatheNew`, all Java/Maven semantics moved to
the LSP server, the client reduced to a thin picker shell
Target: M2
Tier: assistive
Failure mode: missing-affordance
Owner component: split — `lathe-server` (new `workspace/executeCommand` queries + a `lathe.createType`
command that owns placement, skeleton, formatting, caret) and the Neovim client plugin
(`lua/lathe/new.lua`, reduced to the `:LatheNew` command + pickers + file IO).

**Decision (v3 — server owns the Java semantics; supersedes the earlier client-scaffold framing).** The
feature *creates* a new `.java` file — in the right package/directory, with the `package` line and a
named type skeleton — and opens it. Because it is file creation, not a completion item, it sidesteps the
completion "live templates" Non-Goal
([expectations](../planned/lathe-completion-expectations.md) § Non-Goals) entirely. The v1/v2
implementation put every Java/Maven decision in the Lua client (regex-parse the `package` line, split on
`/src/main|test/java` markers, split dotted names, emit skeleton syntax); v3 **moves all of that into the
LSP**, per the project's own "no ad-hoc Java in the client" rule and the jdtls / vscode-java reference
architecture (see *Reference* below). The previously-listed completion-snippet and tree-node-targeting
options are **not** pursued; the earlier "editor-agnostic server (Option B) deferred until a second
client" framing is **reversed** — the server owns the Java facts now, because that is where Java
knowledge belongs, not because a second client arrived.

> The `UX flow` / `Placement` / `Skeletons and style` / `v2` sections below document the **implemented
> v1/v2 client-side** behaviour and are retained as history; the **v3 redesign** section is the
> authoritative plan and supersedes them.

### UX flow

The kind is the **command**, so it is never picked — the only prompt is the name:

1. **Choose the kind by command** — `:LatheNewClass` / `:LatheNewInterface` / `:LatheNewRecord` /
   `:LatheNewEnum`.
2. **Name** — pass it as an argument for zero prompts (`:LatheNewClass Foo`, or a dotted
   `:LatheNewClass com.example.sub.Foo`); with no argument the command asks once via
   `vim.ui.input('<kind> name in <dir>: ')`, where `<dir>` is the target directory relative to the
   workspace (`.lathe`) root (so it shows the module / source-root / package path). The name is both
   the file name (`<Name>.java`) and the type name.
3. **Resolve the target directory** from the current buffer (see Placement).
4. **Create + open + format** — write `<dir>/<Name>.java` with the package line and skeleton, open the
   buffer, normalise its style via the on-save formatter (see *Skeletons and style*), then drop the
   cursor in the body (or record component list). Refuse (message, no-op) if the file exists.

### Placement — how the target directory and package are figured out

Resolution order, from the current buffer (no server round-trip in v1):

1. If the current buffer is a **directory buffer** (oil.nvim `oil://…`, or netrw `b:netrw_curdir`) →
   use that directory. (Covers "create a class where I'm browsing.")
2. Else if it is a **`.java` file** → use its directory — i.e. the **same package** as the open file.
3. Else → error cleanly ("open a file or directory inside a source package first").

The **package** for that directory is taken from the open file's `package …;` line when present
(`^%s*package%s+([%w.]+)`), falling back to path-derivation: split the directory on the source-root
marker (`/src/main/java/` or `/src/test/java/`) and dot-join the trailing segments. **main vs test**
follows whichever root the current buffer sits under — no separate prompt.

### Skeletons and style

The scaffold writes a **minimal, valid** skeleton and **defers style to the on-save formatter** rather
than hardcoding indentation or brace placement (one source of style truth; avoids guessing — e.g. GJF
uses 2-space indentation, so a hardcoded 4-space body would be wrong for exactly the users who format):

- If Lathe's Google formatter is enabled — the same gate that wires the BufWritePre hook
  (`formatter == 'google'` and `format_on_save`, `lathe.lua`) — the command runs the identical
  `vim.lsp.buf.format({ bufnr = …, name = 'lathe', async = false })` on the freshly-opened buffer, so
  the scaffold is byte-identical to what a save would produce. The Lathe client attaches to the new
  buffer *asynchronously*, so the command waits briefly for the attach before formatting (only when a
  Lathe client is running) — otherwise the format runs before attach and no-ops ("no matching language
  servers"), which an early implementation hit and a live-editor test caught.
- Otherwise it emits a built-in fallback honouring the buffer's `expandtab` / `shiftwidth`.

Name = the entered `<Name>`; minimal visibility (`public`, no `final`/`sealed`). Rough shape before
formatting (the formatter fixes indentation/spacing):

```java
public class <Name> {
}
public interface <Name> {
}
public enum <Name> {
}
public record <Name>() {
}
```

The file starts with `package <pkg>;` (omitted for the default package). The **caret** is placed
*after* formatting (which can shift lines): the empty body line for class/interface/enum, or inside the
`()` component list for record. Only Lathe's own formatter is invoked (gated on the existing config); a
buffer using a different on-save formatter gets the fallback skeleton and is normalised by its own hook
on first save.

### v2 — dotted-name creation (client-side, implemented)

Accept a **dotted name** (as the command argument or the name prompt): `com.example.sub.Foo` creates
`Foo` in package `com.example.sub`, making the package directories as needed; a bare `Foo` stays
same-package (v1). Done **entirely in the client**, reusing the existing helpers — no server
round-trip, no new LSP command, no cross-language duplication of the create path:

- Split the input on the last dot → `{ package = 'com.example.sub', name = 'Foo' }`; no dot → the bare
  same-package v1 path.
- Resolve the module **source root** from the current context directory by taking the path up to and
  including the `/src/main/java` or `/src/test/java` marker — the same marker `_package_from_dir`
  already keys on (reused, not duplicated). main vs test follows the current buffer.
- Target directory = `sourceRoot/<package-as-path>`, then hand off to the **existing**
  `_write_and_open(dir, package, kind, name)`, which already does `mkdir -p`, write, open, format, and
  caret. So v2 only computes `(dir, package, name)` differently; the file-creation path is unchanged.
- If the current context is not under a `src/main|test/java` root, a package-qualified name cannot be
  placed — warn and bail (a bare name still works same-directory).

### v3 — server-owned semantics, thin `:LatheNew` picker client (planned redesign, authoritative)

**Principle.** Every Java/Maven decision — which module, which source root, main vs test, the package,
the skeleton syntax, the formatting, the caret, name validity — is decided by the **LSP server**, never
ad-hoc in the Lua client. The v1/v2 client violated this (it regex-parsed `package` lines, split on
`/src/main|test/java` markers, split dotted names, and emitted Java skeleton strings). v3 moves those
decisions to `lathe-server` and reduces the client to a **thin shell**: one command, pickers, and file
IO. This reverses the earlier "client resolution is reliable enough / Option B deferred" call — a
weaker argument than "Java knowledge has exactly one home, the LSP."

**Why the cache detour was wrong.** An intermediate design cached a whole `lathe.workspaceModel`
client-side because command-line `<Tab>` completion must return candidates synchronously. But we chose
**`vim.ui.select` pickers**, which are launched by a discrete command and can therefore *fetch, then
show* — async is fine. That removes the synchronous constraint **and** the stale-cache problem at once:
the picker fetches fresh on open, so it always reflects the current workspace. No client cache is
required (an optional warm-start is a pure latency tweak).

**Server surface — lazy, `getChildren`-style queries + a create command.** Modelled on jdtls
(`java.project.getChildren` / `java.getPackageData` / `java.resolvePath`), returning one level at a time
rather than a flat cartesian `module × main/test × package` list — which keeps payloads small and
avoids the documented jdtls memory/latency cliff when a big module's whole package set is materialised
at once:

- `lathe.modules` → the reactor's modules (reactor-relative names).
- `lathe.packages(module)` → the packages in that module, tagged main/test, read from **current disk**
  so a package created since the last build shows up without a rebuild.
- `lathe.resolveContext(uri)` → the `{ module, kind: main|test, package }` a URI belongs to, for the
  picker's preselect (the `resolvePath` analogue).
- `lathe.createType({ module, kind: main|test, package, type: class|interface|record|enum, name })` →
  the slim `{ path, content, caret }`: the server resolves the target path, generates the correct Java
  skeleton, formats it with `JavaFormatter` (the same engine as on-save, so byte-identical), and
  computes the caret offset. **This is Option B, revived on the principle** — not a `WorkspaceEdit`
  (whose resource-operation plumbing the client does not need); the client writes `content` to `path`,
  opens it, and places the caret.

**Client — a thin shell (`:LatheNew` only).** The four `:LatheNewClass`/`Interface`/`Record`/`Enum`
commands are **removed**; a single `:LatheNew` drives the flow. The client keeps *no* Java knowledge —
`_package_from_lines` / `_package_from_dir` / `_source_root` / `_split_qualified` / `_target` /
`_skeleton` / `_caret` are all deleted. It owns only: the command, the `vim.ui.select` / `vim.ui.input`
pickers, writing the returned content, opening the buffer, applying the caret, and error notifications.

**UX flow — Kind → Destination → Name (decided), progressive narrowing, small lists.**

1. **Kind** — a fixed four-item `vim.ui.select` (Class / Interface / Record / Enum). Cannot be inferred,
   so it is always the first pick; it is instant.
2. **Destination** — never a global package list; narrow coarse→fine, each list short:
   - **Module** (`lathe.modules`) — *skipped* when there is exactly one; otherwise the current module
     (from `lathe.resolveContext`) is preselected.
   - **Package** within that module (`lathe.packages(module)`) — existing packages plus a `＋ New
     package…` entry (→ `vim.ui.input` **seeded** with the current/highlighted package + trailing dot,
     e.g. `com.acme.▮`, so a new sub-package is a short extension) and a `⇄ Other module…` escape. The
     current package is preselected when anchored.
   - **main vs test** — inferred, never a standing question: default `main`, infer `test` when anchored
     in a test file, and surface a two-item main/test pick *only* when the chosen module has both roots
     and there is no anchor.
3. **Name** — typed last (`vim.ui.input`); the final Enter is what triggers `lathe.createType` and
   creates the file.

Skip-when-one + preselect-from-context make the common anchored case fast: `:LatheNew` → pick Kind →
Enter (module skipped/preselected) → Enter (current package) → type Name — two real decisions.

**Freshness (fetch-on-open).** Each `:LatheNew` fetches the modules/packages fresh, so **packages** you
(or a `git pull`, or another editor) just created appear immediately — the server reads current disk,
no rebuild needed. **Modules** appear only after their build/sync reload (a new module needs a POM edit;
`WorkspaceWatcher` polls `workspace.json` + POM fingerprints — the existing sync-prompt path), which is
correct since the module does not exist until built. There is no background push: Lathe registers no
file watchers (`didChangeWatchedFiles` is a deliberate no-op), and the picker's fetch-on-open is the
refresh point.

**Consequence — creation now requires the server attached.** With all Java logic server-side there is no
offline fallback (that would reintroduce ad-hoc client Java); `:LatheNew` with no Lathe client attached
errors cleanly (`server not attached — creation needs the language server`). This is a behaviour change
from the client-only v1/v2, and is the intended trade: it is a Java operation, so it uses the LSP.

**Reference — this mirrors jdtls / vscode-java, and avoids the nvim-jdtls client-convention trap.**
Eclipse jdtls keeps all Java knowledge server-side behind custom `java.*` `executeCommand` extensions
(`java.project.list`, `java.project.getChildren` / `java.getPackageData` for lazy project→root→package
→type children, `java.resolvePath`, plus server-side templates); vscode-java's "New Java Class" is a
thin wizard over them. Two lessons transfer directly: **lazy children, not flat lists**, and a
**documented perf cliff** when a large module's package set is fetched whole — both argue for the
per-node queries above. Conversely, `nvim-jdtls` ships *no* new-class wizard, so the nvim community uses
`java-helpers.nvim`, which does placement **client-side in Lua** (marker-based package guessing + Lua
templates) — exactly the School-B approach our current `new.lua` falls into and this redesign leaves
behind. (The same `lathe.modules` / `lathe.packages` queries also back WS-8's `:LatheSync <module>`
completion — build them once here.)

### Scope

- **v1 + v2 (implemented, to be replaced):** the four `:LatheNew*` kind commands with **client-side**
  resolution and skeleton generation (bare same-package name; dotted absolute; directory-buffer
  targeting; on-save formatter). Shipped in `lua/lathe/new.lua`; superseded by v3.
- **Planned (v3 redesign, authoritative):** a single `:LatheNew`; all Java/Maven semantics in
  `lathe-server` via lazy `getChildren`-style queries (`lathe.modules`, `lathe.packages`,
  `lathe.resolveContext`) plus `lathe.createType` (path + skeleton + format + caret); a
  Kind → module → package → name picker with skip-when-one, context preselect, seeded new-package input,
  and inferred main/test; fetch-on-open freshness; creation requires the server. The client holds no
  Java knowledge.

### Regression targets

**Server** (`lathe-server`; JUnit, real workspace fixtures — the executeCommand handlers and the
`WorkspaceSession` methods behind them, mirroring the `lathe.instantiations` path):

- `modules_listsReactorModules` (positive) / `modules_singleModule_returnsOne` (edge)
- `packages_listsModulePackagesTaggedMainTest` (positive) /
  `packages_reflectNewlyCreatedDirWithoutRebuild` (positive — server reads current disk) /
  `packages_moduleWithoutTestRoot_returnsMainOnly` (edge)
- `resolveContext_uriUnderMain_returnsModulePackageMain` /
  `resolveContext_uriUnderTest_returnsTest` (positive) /
  `resolveContext_uriOutsideAnyRoot_returnsNull` (negative)
- `createType_generatesFormattedSkeletonWithPathAndCaret` (positive, per kind — path resolved under the
  right root, package dirs implied, content formatted by `JavaFormatter`, caret in body / record
  component list) / `createType_invalidJavaName_rejected` (negative) /
  `createType_existingFile_refuses` (negative)

**Client** (Neovim busted spec `new_spec.lua`; `vim.ui.select` / `vim.ui.input` and the
`workspace/executeCommand` requests stubbed — the client carries no Java logic to unit-test, so the
tests assert flow + IO, not placement):

- `latheNew_kindThenDestinationThenName_createsViaServer` (positive — the three steps issue
  `lathe.createType` and the returned `{path, content, caret}` is written / opened / caret-placed)
- `latheNew_anchored_preselectsCurrentModuleAndPackage` (positive — `resolveContext` result preselects)
- `latheNew_singleModule_skipsModuleStep` (positive — skip-when-one)
- `latheNew_newPackage_seedsInputFromContext` (positive — `＋ New package…` input seeded with the
  current package + dot)
- `latheNew_bothRoots_noAnchor_promptsMainTest` (positive) /
  `latheNew_anchoredTest_infersTest` (positive — no main/test prompt)
- `latheNew_serverNotAttached_errorsCleanly` (negative — creation needs the server)

Verify end-to-end in a live Neovim against the invoker workspace: `:LatheNew` from a cold start creates
a class under a chosen (or new) package and formats it via the running server.

Notes:
Pairs with CQ-0054 (keyword insertion) but is independent of it. The `lathe.modules` / `lathe.packages`
queries are shared with WS-8 (`:LatheSync <module>` completion) — build them once here.

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

Scheduled for M2 as **WS-5**. The original plan was option 2 (in-process recompile), but that is
**parked** — correct only for single-module change sets — in favour of **option 1**: detect source
staleness and reuse the shipped sync prompt, per
[lathe-external-change-detection.md](../planned/lathe-external-change-detection.md). The parked compile
design is [lathe-external-change-recompilation.md](../potential/lathe-external-change-recompilation.md).
WS-1 remains the umbrella for the wider reconciliation (option 3) and cross-module cases.

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

**Status: deferred — Target: backlog (superseded by WS-5)**

**Superseded by [WS-5](#ws-5--external-on-disk-edits-to-sourcesresources-are-not-picked-up-without-a-maven-build)
for the source case.** WS-2's proposal was a Sync/Later *prompt* for source-only changes; that is not
pursued — a prompt is intrusive and non-binding (the user can dismiss it and keep working against stale
state). Instead WS-5 (accepted, M2) makes small source-only changes a non-issue by *auto-recompiling*
the changed files (the non-intrusive resolution this gap called for), and WS-3 owns the pom/structural
prompt. This entry stays recorded as the rejected prompt-for-source approach; WS-1 remains the umbrella
for the wider reconciliation. Original analysis retained below.

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

## WS-3 — The POM-changed sync prompt re-appears every 2s, and "Sync" neither runs Maven nor is distinguished from "Later"

**Status: done — Target: M2**

### Observed behaviour

After a `git checkout` where the branches differ in POM files, the advisory prompt fires:

```
Maven project changed. Run 'mvn process-test-classes' to refresh Lathe.
1: Sync
2: Later
```

- Choosing **Later** (`2`) dismisses it, but it **re-appears ~2 seconds later**, and keeps re-appearing
  on every poll — an inescapable loop until the user actually runs `mvn process-test-classes`.
- Choosing **Sync** (`1`) behaves identically: it does **not** run Maven, does **not** reload, and the
  prompt returns 2 seconds later. The "Sync" label implies the server will sync; it does not.
- Cancelling (`q` / empty) has the same effect — the loop continues.

### Root cause

Two defects in `WorkspaceSession.checkForChanges` / the watcher baseline
(`WorkspaceSession.java:2005-2023`, `WorkspaceWatcher.java`):

1. **"Later" is not honoured across polls.** The poll runs every 2s
   (`worker.scheduleAtFixedRate(2_000L, this::checkForChanges)`, `:185`). `POM_CHANGED` is derived by
   `WorkspaceWatcher.detectPomChange()`, which compares live POM fingerprints against `pomBaseline`.
   The baseline is refreshed **only** in `reloadWorkspace()` (via `updatePomPaths`), which runs on a
   `workspace.json` change — never on a prompt response. So after the user answers, the live POMs still
   differ from the baseline and the very next poll returns `POM_CHANGED` again. The
   `pomNotificationPending` flag only de-dupes a *concurrent* prompt; it is reset as soon as the user
   responds (`thenAccept(action -> … pomNotificationPending = false)`, `:2018`), so it does nothing to
   suppress the *next* poll's prompt.

2. **The chosen action is discarded.** The `showMessageRequest` callback ignores its `action`
   argument entirely and just clears `pomNotificationPending` for both "Sync" and "Later" (and for a
   null/cancelled response). Nothing dispatches on `"Sync"`, so selecting it runs no Maven and triggers
   no reload — consistent with Lathe's rule that the LSP server never invokes Maven, but then the
   button is mislabeled and misleading.

### Proposed direction

Decided — this is now the actionable heavy-path prompt (the companion to the WS-5 light-regime
auto-recompile). Three parts:

- **Honour a dismissal (loop fix).** On "Later"/cancel, snapshot the *current* POM fingerprints as an
  "acknowledged" baseline so `detectPomChange()` stays quiet until the POMs change **again** (a further
  edit or another branch switch) — without treating the project as synced (the mirror/index are still
  stale; only the nagging stops). Distinct from `updatePomPaths`, which asserts freshness. The prompt
  then shows **once per POM change**, not every 2s.
- **Keep detection on the server poll.** POM/`workspace.json` detection stays in `WorkspaceWatcher`
  (small, bounded, server-authoritative) and is **not** folded into `workspace/didChangeWatchedFiles`
  — that mechanism is for the huge source/resource set (WS-5). Two mechanisms, clean split.
- **Actionable prompt (Alternative A — client runs Maven, server never does).** Dispatch on the
  returned `MessageActionItem` instead of discarding it; on a chosen action the server sends a custom
  `lathe/sync` notification and the **client** runs Maven as a job, after which the server picks up the
  refreshed `.lathe/` (WS-4). Offer **two** actions:
  - **"Sync"** → `mvn process-test-classes` — refresh types/mirror/main-launch + manifest (LSP + *main*
    run/debug).
  - **"Sync + capture tests"** → `mvn test` (capture flavor) — also (re)captures `test-launch.json`
    (which is *captured from a test fork*, not derived by `lathe:sync`), needed for neotest / test-run
    / test-debug on new or changed test modules.

  Both actions offered on every POM change for now; **auto-recommending** capture only when a
  new/uncaptured module is detected is deferred (Slice 2, backlog). Open question: whether a plain
  dependency change also invalidates the captured `test-launch.json` (making capture the default rather
  than the secondary action) — depends on the capture model (writer still in progress); confirm before
  wiring the default.

Relates to WS-1 (staleness/invalidation umbrella), WS-2 (the deferred *source-only* branch-switch
prompt — now revived as the chosen approach), WS-4 (post-Maven pickup), and WS-5 /
[lathe-external-change-detection.md](../planned/lathe-external-change-detection.md) (source staleness
also routes to this same prompt). WS-3 itself is the shipped-behaviour reliability defect (looping
prompt + inert "Sync").

### Probe commands

Not probeable through `explore.py`. Reproduced by checking out a branch whose POMs differ, then
answering the prompt (either option) and observing it return after ~2s.

### Regression targets

Loop fix (server):
- `WorkspaceWatcherTest.acknowledgePoms_afterPomChange_stopsReprompting`
  (positive — a changed POM reports on every poll until `acknowledgePoms()`, then goes quiet)

Actionable prompt (end-to-end):
- `LspSmokeTest.pomChange_triggersResyncPrompt`
  (a POM change prompts with the three actions `Sync` / `Sync + capture tests` / `Later`)

Client dispatch (Neovim):
- `sync_spec.lua` — the `lathe/sync` handler and `:LatheSync` run `mvn process-test-classes` (or
  `mvn test` with `captureTests`) at the workspace root; a concurrent sync for the same root is a
  no-op.

The server-side action→`lathe/sync` dispatch (`WorkspaceSession.onSyncPromptResponse`) is a small
switch exercised through the client handler above; a dedicated e2e assertion was skipped as too racy
against the shared-server prompt state.

---

## WS-4 — `workspace/symbol` misses newly added reactor types after an incremental `mvn process-test-classes` (no clean)

**Status: rejected — not an issue**

### Observed behaviour

After switching to a branch that adds new classes and running `mvn process-test-classes` **without
`clean`**, the new types do not appear in the `workspace/symbol` picker (`\ws` in the reporter's
Neovim config). The sync ran, the classes compiled, yet they stay invisible to symbol search until
the server is restarted (or a manifest-changing sync happens).

### Root cause

The reactor type index is an in-memory cache that is only rebuilt on a `workspace.json` mtime change,
and the incremental sync does not change `workspace.json`:

1. `WorkspaceSession.typeIndex` (`:135`) is built once at init from the per-module shard files
   (`WorkspaceTypeIndex.build(manifest.typeIndexShardPaths(), reactorShards.values())`, `:182`) and
   rebuilt **only** in `reloadWorkspace()` (`:2049-2051`, which does `reactorShards.clear()` →
   `scanReactorShards()` → rebuild `typeIndex`). `workspace/symbol` queries this cached snapshot.
2. `reloadWorkspace()` runs only via `reload()`, triggered by `WorkspaceWatcher` returning
   `WORKSPACE_CHANGED`, which is derived purely from the `workspace.json` **mtime**
   (`WorkspaceWatcher.detectManifestChange`).
3. `WorkspaceManifestWriter.write` **skips the write when the content is unchanged**
   (`WorkspaceManifestWriter.java:48-50` — `"[sync] workspace unchanged — skipping write"`). Adding a
   class to an **existing** module leaves the manifest identical (same modules, source roots, and
   classpath; shards are per-module, so no new shard **path**), so the file is not rewritten and its
   mtime does not move.

Net: `lathe:sync` refreshes the module's type-index **shard content** on disk, but the server never
re-reads it because its only reload trigger (manifest mtime) never fires. The reporter's "not using
`clean`" detail fits: an incremental sync is exactly the case where the manifest content is unchanged.
The existing lighter refresh (`typeIndex.withReactorEntries(...)`, `:1870`) only runs for open-file
recompiles, so it does not cover types added by an external sync.

### Proposed direction

Not yet decided; options, cheapest first:

1. **Fingerprint the shard files** in `WorkspaceWatcher` (mtime + size of `manifest.typeIndexShardPaths()`),
   not just `workspace.json`, and refresh the type index when any shard changes — ideally a
   type-index-only refresh rather than a full `reloadWorkspace()` (which also rebuilds the candidate
   index and refreshes open documents).
2. Have `lathe:sync` bump `workspace.json`'s mtime whenever it rewrites any shard, even when the
   manifest content is unchanged, so the existing `WORKSPACE_CHANGED` path fires (simpler, but reuses
   the heavy reload).

Distinct from WS-1/WS-2, which cover staleness when **no** sync is run; WS-4 is the case where the
user **did** run the sync and the refreshed shards are still ignored. WS-1 remains the umbrella for a
fuller freshness model.

### Scope — the in-editor create-and-save path is NOT affected (verified)

Creating a type in the editor and **saving** it already updates `workspace/symbol` and needs no fix.
The module save route compiles into the `.lathe/<module>/classes` mirror
(`ModuleSourceCompiler` sets `CLASS_OUTPUT` to `config.latheClassesDir()`), and `afterModuleSave` →
`refreshReactorShard(config)` re-scans that dir and rebuilds `typeIndex`. Verified live against the
`multi-module` invoker workspace: a new `WsProbeWidget` type returned *no symbol* right after
`didOpen`, then — after a save — `workspace/symbol` returned exactly one hit pointing at its source,
and `.lathe/app/classes/.../WsProbeWidget.class` appeared. WS-4 is therefore scoped strictly to the
**external** refresh path (branch switch + `mvn process-test-classes`, no editor save), where nothing
triggers `refreshReactorShard`/`reload`.

Note the coverage context: the individual pieces are unit-tested only with hand-built indices
(`WorkspaceTypeIndexTest`, `WorkspaceSymbolTest`, `ClassFileTypeScannerTest`), and **no** end-to-end
test drives "workspace change → index reflects it" — `LspSmokeTest` never issues `workspace/symbol` at
all. The regression targets below therefore both fix WS-4 and close that end-to-end gap (the
save-path target locks in behaviour that works today but is otherwise untested).

### Probe commands

WS-4 itself (external re-sync) is not probeable through `explore.py`. Reproduce by adding a class to an
existing module, running `mvn process-test-classes` (no `clean`), and issuing `workspace/symbol` for
the new name without restarting the server. The sibling save-path *is* probeable and was used to
confirm the scope above:

```bash
# against a synced workspace, in one session: query before save, save, query after
printf 'sym NewType\ndiag\nsym NewType\n' | python3 dev/explore.py <ws>/.../NewType.java
```

### Regression targets

Implemented via the **manifest mtime-touch** signal: `lathe:sync` bumps `workspace.json`'s mtime on
every run (even when content is unchanged), and the watcher compares content to distinguish a
reactor-only refresh (`REACTOR_REFRESH`, silent reactor re-scan) from a structural change
(`WORKSPACE_CHANGED`, full reload).

- `WorkspaceManifestWriterTest.write_sameContent_touchesMtimeButKeepsContent`
  (positive — a skipped rewrite still bumps the mtime; content stays byte-identical)
- `WorkspaceWatcherTest.poll_manifestTouchedSameContent_returnsMirrorRefreshOnceThenNoChange`
  (positive — mtime bump, same content → one `REACTOR_REFRESH`, then quiet)
- `WorkspaceWatcherTest.poll_contentChangeAfterMirrorTouch_returnsWorkspaceChanged`
  (negative/boundary — a real content change is still `WORKSPACE_CHANGED`)

End-to-end coverage this gap exposed (invoker `LspSmokeTest`, previously never exercised
`workspace/symbol`):

- `LspSmokeTest.workspaceSymbol_reactorType_resolvesAcrossModules`
  (positive — reactor types from different modules resolve via `workspace/symbol`)
- `LspSmokeTest.workspaceReload_manifestContentChanged_notifiesUser`
  (positive — a manifest **content** change is structural → full reload + notification, distinct from
  the silent mtime touch)

Still open (deferred to the WS-5 / broader freshness work): a `workspaceSymbol_afterStaleTypeRemoved`
negative and a fabricated-`.class` "new external type appears" positive at the integration level.

---

## WS-5 — External on-disk edits to sources/resources are not picked up without a Maven build

**Status: done — Target: M2 (detect → prompt, not in-process recompile)**

**Resolved.** Implemented as **server-side detection → the shipped sync prompt**: a source-root scan
compares each *closed* source to its compiled `.class` in `.lathe/` (stale when the `.class` is missing
or older; open files and the annotation-processor root are skipped) and, if any is stale, raises the
WS-3 Maven sync prompt. External **resource** changes auto-copy into `.lathe/` via `refreshResource`
(a copy is not compilation). Both run at startup and on the `WorkspaceWatcher` tick, suppressed while a
module is mid-build (`LatheLock.isBuilding`); Lathe never runs Maven automatically. Verified end-to-end
via the dev probe against the SNAPSHOT server — a stale source fires the prompt, a new resource is
copied into `.lathe/`.

Commits: `feat(freshness): detect externally-changed sources and prompt to sync (WS-5)` and
`feat(freshness): auto-copy externally changed resources into .lathe/ (WS-5)`; e2e coverage in
`LatheTextDocumentServiceTest`. Design:
[External-Change Detection → Sync Prompt](../planned/lathe-external-change-detection.md) (WS-1
option 1). The in-process recompile originally proposed was **not pursued** — correct only for a
single-module change set, else it re-implements Maven's ordered reactor build — and is parked in
[In-Process External-Change Recompilation](../potential/lathe-external-change-recompilation.md).

**Deferred follow-ups (not blocking):** deletion detection (an orphan `.class` for a source removed
while Lathe was down needs a source-*set* baseline, not just newest-mtime) and the "Sync + capture"
heuristic for a new test module / test source.

### Observed behaviour

A Java source or resource changed **on disk from outside the editor** — a branch switch, a `git pull`,
or an AI agent editing files directly — is invisible to Lathe until the next `mvn process-test-classes`.
Navigation, completion, `workspace/symbol`, replay resources, and dependents' diagnostics all reflect
the previous state, even though the change is fully on disk.

### Root cause

The server watches nothing at the source level. `LatheWorkspaceService.didChangeWatchedFiles` acts
**only** on `FileChangeType.Deleted`; `Created`/`Changed` events are dropped
(`LatheWorkspaceService.java:47-49`). Resource edits reach `.lathe/` only through the editor
`BufWritePost` → `lathe.resource.refresh` autocmd, so an external resource change is never copied. No
server-side compile is triggered for a non-open source file, and the reactor mirror/type index are not
invalidated by filesystem source changes (also see WS-1 root cause).

### Proposed fix

Per the design doc: extend `workspace/didChangeWatchedFiles` to `Created`/`Changed`, register
`**/*.java` and resource-root watchers, and react by reusing existing machinery —

- `.java` (not open) → `WorkspaceSession.onExternalChange(uri)`: a `FULL` compile from disk (runs
  annotation processors, writes `.lathe/<module>/classes`) then `refreshReactorShard`, i.e. the
  `onSave` path;
- resource under a tracked root → the existing `refreshResource(uri)` copy;
- `pom.xml`/structural → unchanged (heavy path → the Maven sync prompt, WS-3);

with per-module debounce and open-file precedence. A **bulk cutoff** guards the storm case: above a
threshold of changed files in a window — or when the batch also carries a `pom.xml` change — the light
regime defers to the heavy-path prompt (WS-3) rather than per-file recompiling, so WS-5 owns *small*
source/resource change sets while a bulk `git pull`/branch switch goes to the prompt (picked up by
WS-4). Cross-module dependents stay Maven-bounded (see
[Sibling Recompilation](../planned/lathe-sibling-recompilation.md)).

### Probe commands

Reproduced (and verified for the fix) against the `multi-module` invoker workspace: edit a **closed**
`.java` on disk, deliver a `didChangeWatchedFiles`, then query `sym`/`refs`; repeat for a resource.
The same `sym`/`diag`/`sym` harness used for the save path applies.

### Regression targets

**Superseded** — the targets below (and the probe above) were written for the parked in-process
recompile (`didChangeWatchedFiles`/`onExternalChange`), which no longer exists. New targets for the
server-side detection scan → sync prompt will be defined when it is implemented, per
[external-change detection](../planned/lathe-external-change-detection.md). Retained for history:

- `LatheWorkspaceServiceTest.didChangeWatchedFiles_javaCreatedOrChanged_routesToExternalChange`
- `LatheWorkspaceServiceTest.didChangeWatchedFiles_resourceChanged_routesToRefreshResource`
- `WorkspaceSessionTest.onExternalChange_closedFile_updatesMirrorAndSymbolIndex`
  (positive — incl. an `@Builder` generated type appearing via the FULL compile)
- `WorkspaceSessionTest.onExternalChange_openFile_isIgnored` (negative — editor buffer wins)
- `WorkspaceSessionTest.onExternalChange_burst_debouncesPerModule`
- `WorkspaceSessionTest.onExternalChange_bulkChangeSet_defersToHeavyPathPrompt`
  (negative — above the threshold, defer to WS-3 instead of per-file recompiling)

---

## WS-6 — Open files in dependent modules keep stale diagnostics after an upstream open file is saved

**Status: accepted — Target: backlog**

Discovered while scoping WS-5. This is the *in-session, open-file* facet of cross-module freshness —
distinct from WS-5 (external/closed-file edits) and cheaper, because it only concerns files the user
already has open.

### Observed behaviour

With a file open in module B that depends on module A, editing and saving an open file in A does not
refresh B's open buffer: B's diagnostics, semantic tokens, and quick-fixes continue to reflect A's
previous state until B is itself edited or reopened. Adding or changing a public API in A — a new
method, a changed signature, a removed type — is therefore invisible to an already-open dependent file
even though A's new bytecode was written to `.lathe/A/classes` by the save.

What *does* work today: same-module open files refresh, and A's type-index shard is rebuilt, so
cross-module `workspace/symbol` / navigation / completion for A's new types resolve. Only the
**dependent module's live per-file analysis** (diagnostics/tokens) is left stale.

### Root cause

On save, [`WorkspaceSession.afterModuleSave`](../../lathe-server/src/main/java/io/github/aglibs/lathe/server/WorkspaceSession.java)
runs `scheduleOpenFilesInModule(savedUri, savedModule)`, which reschedules open documents filtered to
the **same** `moduleDir`:

```java
.filter(uri -> workspace.moduleSourceFor(...).map(m -> m.moduleDir().equals(savedModule.moduleDir())).orElse(false))
```

Open files in *downstream* modules are never rescheduled (the method's "…for dependents…" log line
overstates what the filter does). A full reschedule of every open file happens only on a whole
workspace `reload()` (`scheduleAllOpenFiles`), i.e. after a Maven sync — not on an ordinary in-editor
save.

### Proposed direction

The graph primitive already exists: `WorkspaceModuleGraph.referenceSearchScope(config)` returns the
declaring module plus its transitive downstream dependents (`downstreamOf`). The fix is to reschedule
open files in any module within that scope (recompiled in `OPEN` mode against the now-fresh
`.lathe/A/classes`), instead of filtering to the same module. It stays bounded — only files the user
has open, no closed-file compile, no reactor rebuild — which is what separates it from WS-5 and from
[Sibling Recompilation](../planned/lathe-sibling-recompilation.md) (its closed-file, whole-module
counterpart).

**Caveat to resolve first:** module B's `CompilationWorker` holds a `StandardJavaFileManager` that may
cache A's *old* `.class`. Rescheduling B's open file is only correct if B's compiler first invalidates
its cached view of A (there is a `dropFromCache(uri)` on the worker; the right invalidation granularity
for a cross-module class change needs confirming). This caching concern is the likely reason the
current code restricts refresh to the same module.

### Probe commands

Not probeable through `explore.py`; reproduce in one session against the `multi-module` invoker
workspace: open a file in a downstream module, add a public method to an open upstream file, save the
upstream file, then request diagnostics/completion in the downstream file without editing it —
today the new method is absent.

### Regression targets

None yet — to be defined when scheduled (upstream open-file save → downstream open file re-analysed
against the new API; no spurious reschedule of open files in unrelated modules).

Relates to WS-1 (the staleness umbrella), WS-5 (the closed-file/external counterpart; its in-process
recompile is being reconsidered in favour of the WS-3 sync prompt — a docs reconciliation still
pending), and [Sibling Recompilation](../planned/lathe-sibling-recompilation.md) (the closed-file,
whole-module dependent recompilation).

---

## WS-7 — A submodule build without a root `.mvn/` can create a stray `.lathe/` inside the module

**Status: done — Target: M2**

**Resolved.** `ReactorProjects.isReactorRootBuild` now decides purely from on-disk `<modules>`
aggregation: `isReactorRoot(basedir)` walks up ancestor directories and, at each `pom.xml` (read via
Maven's own `MavenXpp3Reader`), returns false when a `<module>` resolves **exactly** to the candidate.
No `.mvn/` dependence, correct at any nesting depth (incl. pom-less intermediate dirs), and — unlike a
bare "any ancestor pom/`.lathe`" existence check — it does not false-skip a project merely nested
*under* an unrelated pom or a declared module (e.g. an IT fixture under `lathe-maven-plugin/target`).
Only `InitMojo`/`SyncMojo` consult it; the compiler's class-refresh into an existing root `.lathe/`
(`-pl`, in-submodule) is untouched. Covered by `ReactorProjectsTest` (root / deep-submodule / nested-
under-a-module / symlink) and the `multi-module` invoker.

Discovered while verifying `ac1c4f0` (don't create a submodule `.lathe` on a `-pl` build) for the
WS-8 targeted-sync work.

### Observed behaviour

Running Maven directly under a module directory (`cd <module> && mvn compile`) in a multi-module
project that has **no `.mvn/` at the reactor root** creates a stray `.lathe/` inside that submodule.
The editor's `.lathe` root marker then resolves to the submodule (a module maps to a blank
`moduleRel`) — the same failure `ac1c4f0` fixed for `-pl`: runnables discovery crashes on `RunTarget`
validation.

`-pl <module> -am` **from the reactor root** is already correct: the compiler copies classes into the
existing root `.lathe/`, and `InitMojo`/`SyncMojo` skip (no manifest rewrite). This gap is only the
*direct in-submodule invocation without a root `.mvn/`*.

### Root cause

`ReactorProjects.isMultiModuleRootBuild(session)` gates `.lathe` creation on
`topLevelProject.basedir == request.multiModuleProjectDirectory`. Maven derives
`multiModuleProjectDirectory` by searching up for a `.mvn/` directory; with none present it defaults to
the invocation directory. So `cd <module> && mvn` sets `multiModuleProjectDirectory` to the module
dir, which equals `topLevelProject.basedir` → the guard passes → `InitMojo` runs
`Files.createDirectories(topLevel/.lathe)` inside the submodule. The `multi-module` invoker fixture
that "verified" `ac1c4f0` has a root `.mvn/`, so it cannot catch this case.

The class-transfer path is unaffected: `LatheCompiler`/`LatheWorkspace.findRoot` only ever *walk up to
an existing* `.lathe/` and never create one.

### Proposed fix

Strengthen the gate with an on-disk aggregator check in `ReactorProjects`:

- `hasAggregatorAncestorOnDisk(moduleBasedir)`: walk up ancestor directories; for each `pom.xml`, read
  its `<modules>` and resolve each entry against that directory. If any resolves onto the path down to
  `moduleBasedir`, the project is a submodule → not a root.
- `isMultiModuleRootBuild` becomes
  `isSameDirectory(topLevel, multiModuleDir) && !hasAggregatorAncestorOnDisk(topLevel.basedir)`.
- Read `<modules>` via Maven's `ModelReader` component (or `MavenXpp3Reader`) — entries are literal
  directory names, so a raw model read is enough. External framework parents resolved from `~/.m2` are
  not on the ancestor path, so a genuine single-module root still gets its `.lathe/`.

`InitMojo`/`SyncMojo` need no change — both already call the gate. Known limitation to document:
profile-activated `<modules>` in an ancestor are invisible to a raw read.

### Regression targets

- `ReactorProjectsTest.isMultiModuleRootBuild_submoduleListedByAncestorPom_returnsFalse`
- `ReactorProjectsTest.isMultiModuleRootBuild_genuineSingleModuleRoot_returnsTrue`
- A new invoker fixture **without** a root `.mvn/`: build a submodule from its own directory and assert
  no `.lathe/` is created in the submodule (the `-pl`-from-root and root-build cases stay covered by
  `multi-module`).

---

## WS-8 — Targeted `mvn -pl <changed> -am` sync instead of a full-reactor build

**Status: accepted — Target: backlog**

Optimization on top of WS-5. On a large reactor a full `mvn process-test-classes` to refresh one edited
module is slow; the server already knows which modules are stale and could ask for a scoped build.

### Observed behaviour

When WS-5 detection fires (a closed source is stale), the sync prompt runs a **full-reactor**
`mvn process-test-classes`, even if a single module changed. On a 300-module reactor this is far more
work than needed to refresh the affected module's `.lathe/` mirror.

### Root cause

By design, the WS-3 prompt / `LatheSyncParams` carry only `workspaceRoot` + `captureTests`; the client
always runs a whole-reactor build. The staleness scan iterates per `ModuleSourceConfig` but discards
which modules were stale (`newestStaleMtime` returns only a max mtime).

### Proposed direction

Route a **source-only** change to a scoped build; keep full builds for structural changes.

- **Server:** have the staleness scan also collect the set of stale modules (their reactor-relative
  `moduleRel`, which is exactly what `-pl` accepts, since `workspaceRoot == reactorRoot`). Add
  `List<String> modules` to `LatheSyncParams` (empty ⇒ full). In `checkSourceStaleness`/`requestSync`:
  a POM/structure change (POM fingerprint / `pomNotificationPending`) ⇒ empty ⇒ full build (only a full
  build regenerates `workspace.json`, which `-pl` deliberately skips); source-only ⇒ the stale
  `moduleRel` set; all/most modules stale ⇒ drop `-pl`.
- **Client (`sync.lua`):** `run_maven` gains the module list; with modules present, build
  `mvn … -pl m1,m2 -am <goal>`; the `lathe/sync` handler forwards `result.modules`. Manual `:LatheSync`
  stays full-reactor.

### Correctness caveats (to bake in and document)

- `-am` builds **upstream** deps only, not dependents (`-amd`). The edited module compiles against fresh
  upstreams; a downstream module's `.lathe` mirror is not refreshed by this build. Acceptable for a
  source-edit refresh (downstream sources aren't stale) and avoids `-amd` ballooning into a near-full
  build. The open-file dependent case is WS-6.
- `-pl` does **not** rewrite `workspace.json` (verified in the `ac1c4f0`/WS-7 trace) — correct here
  precisely because the module structure did not change.
- Use `moduleRel` path form for `-pl`; fall back to `:artifactId` (stored in the manifest) if
  nested-path forms prove fragile.

Depends on WS-7 (the `-pl`/submodule `.lathe` behaviour must be sound first).

### Regression targets

- `WorkspaceSessionTest` — source-only stale ⇒ sync params carry the stale `moduleRel`s; POM change ⇒
  empty (full).
- `sync_spec` — `-pl m1,m2 -am` present with modules, absent without.
- Optional invoker/e2e — `-pl <module> -am` refreshes that module's `.lathe/classes` and leaves
  `workspace.json` untouched.

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

# Neovim Client Gaps (NV)

Gaps in the shipped Neovim client plugin (`lua/lathe/…`) and its recommended configuration, as
distinct from the server's LSP/DAP surface. Resolved NV entries move to
[gaps-archive.md](gaps-archive.md).

NV-1 and NV-2 are resolved in [gaps-archive.md](gaps-archive.md).

## NV-3 — Collapsed import fold pops back open on every save

**Status: accepted — Target: backlog.**

### Observed behaviour

Open a `.java` file with the recommended folding config: the import block auto-collapses.
Edit anywhere in the file and `:w` (save), and the previously-collapsed imports fold springs back
open. It re-collapses only on the next full buffer display (reopen / `BufReadPost`), so every save
during an editing session leaves the imports expanded and the user re-folds them by hand.

### Root cause

The reopen is **not** a server-side change — the fold geometry is byte-stable across a save. Probed
against the working-tree server (`dev/explore.py`, `folds` before and after `save` on a real file):
the `imports` range is identical both times (`3:1-14:27` → `3:1-14:27`).

The trigger is the recommended `nvim-ufo` config that Lathe ships in
[`docs/done/lathe-folding-ranges.md`](../done/lathe-folding-ranges.md):

```lua
close_fold_kinds_for_ft = { java = { "imports" } }
```

Per ufo's own docs this option closes the matching-kind folds **only "after the buffer is displayed
(opened for the first time)"** — it is a first-display action, not re-applied on later fold updates.
ufo re-requests `textDocument/foldingRange` and recomputes folds whenever the buffer text changes,
converting `foldmethod` to `manual`; the recomputed folds default to *open*, and because the
auto-close is first-display-only the imports never re-close. The save-time churn is amplified by the
server firing **two** `workspace/semanticTokens/refresh` requests per save (one from the FULL-compile
`DiagnosticPublisher.publish`, one from the scheduled AST-refresh `refreshTokensIfCurrent`) versus one
on open — confirmed by instrumenting the probe client.

Lathe ships no fold logic of its own (`lua/lathe.lua`'s save autocmds only refresh run-signs and
resources), so the fold behaviour is entirely the server's stable `foldingRange` plus the
Lathe-recommended ufo config — which is why this is an NV (recommended-configuration) gap.

### Proposed direction — what to present

Re-apply the imports auto-close after a post-save fold recompute settles, in the recommended config
(and optionally the shipped plugin), so the fold state a user last chose survives a save. Candidate
mechanisms, to be finalised against a live nvim + ufo repro:

- A `BufWritePost` autocmd (java buffers with a Lathe client attached, mirroring the existing
  run-signs autocmd in `lua/lathe.lua`) that defers briefly for ufo's fold update, then re-closes the
  imports fold — either via a ufo close-by-kind call if one is public, or by locating the `imports`
  range from the server's `foldingRange` (already stable and correct) and closing that fold.
- Alternatively, only re-close when the imports region itself was untouched, so a user who manually
  opened the imports is not overridden.

No server change is required — the fold geometry is already correct and stable. Reducing the
save-time refresh from two to one is a separate, minor server cleanup that does not fix the refold on
its own (ufo recomputes on any text change regardless).
