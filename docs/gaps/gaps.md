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

## Finding the work for a release

The slice for a release is derived, not hand-maintained: every gap with `Status: accepted` and the
matching `Target` (see [gap-process.md](gap-process.md)).

```bash
grep -nE '^(Status|Target):|^\*\*Status' docs/gaps/gaps.md     # scan active entries
grep -n 'Target: M1' docs/gaps/gaps.md                         # the M1 slice
```

Entries follow, grouped by area: exploration (EG) below, then Find References (FR), Code Actions
(CA), and Completion (CQ).

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

## EG-028 — `textDocument/onTypeFormatting` is a stub and is not registered

**Status: accepted — Target: M3**

### Observed behaviour

Typing in Google-Java-Format code with complex wrapped structure (assignment continuations at +8,
wrapped call arguments at +12, multi-line record headers at +4) leaves the cursor at the wrong
indentation when a new line is started.
The server provides no type-time indentation assistance.

### Root cause

`LatheTextDocumentService.onTypeFormatting` (around line 312) is a TODO stub that returns
`List.of()` for every request, and `LatheLanguageServer.initialize` does not register a
`documentOnTypeFormattingProvider` (no `firstTriggerCharacter` / `moreTriggerCharacter`), so most
clients never send the request at all.

### Constraint (why this cannot fully close the indentation gap)

Lathe's only formatting engine is Google Java Format, which parses the **entire compilation unit**
before emitting anything and throws `FormatterException` on unparseable input (`JavaFormatter`
catches it and returns no edits).
Its range API parses the whole file too — ranges only limit which edits are emitted.
The most useful `onTypeFormatting` trigger, newline (`\n`) inside a wrapped expression or record
header, fires precisely when the buffer is **not parseable**, so a GJF-backed handler can return
nothing there.
The CLAUDE.md "no ad hoc Java parsing" rule forbids a hand-rolled indentation model as the
alternative.

### Realistic scope

`onTypeFormatting` is at best a **partial** improvement, scoped to triggers that tend to *complete*
a parseable file (`}`, `;`): when the file parses again, run GJF (range-scoped to the touched lines)
and return conservative edits for brace/statement layout.
It will not fix the live-newline cursor case.
Conservative behaviour depends on EG-029 (real range formatting) landing first.
Because the editor already formats on save (full-document GJF), the saved result is GJF-correct
regardless, so this is a live-typing nicety, not a correctness requirement — which is why it is
parked at `backlog`.
The lever that actually fixes the newline/record-component cursor is error-tolerant client-side
indentation (e.g. tree-sitter), not the server.

### Probe commands

Not probeable through `explore.py`; confirmed by the stub handler and the absent capability
registration in `LatheLanguageServer`.

### Regression targets

- `OnTypeFormattingTest.onTypeFormatting_closingBraceCompletesFile_returnsConservativeEdits`
- `OnTypeFormattingTest.onTypeFormatting_unparseableNewline_returnsNoEdits`

---

## EG-029 — `rangeFormat` ignores its range and formats the whole document

**Status: accepted — Target: M3**

### Observed behaviour

`textDocument/rangeFormatting` reformats the entire document instead of only the requested range,
so a range-format request can move and reflow code far outside the selection.

### Root cause

Both the `formatting` and `rangeFormatting` endpoints delegate to the same
`WorkspaceSession.format(tag, uri)` (`LatheTextDocumentService` lines ~302 and ~309), which calls
`JavaFormatter.format(content)` — a whole-document `Formatter().formatSourceAndFixImports(content)`.
The selection range carried by the request is never read.

### Proposed fix

Add a range-aware path in `JavaFormatter` using GJF's `formatSource(text, ranges)` with the
character range derived from the request's LSP range, and emit only the resulting in-range edits.
Keep the whole-document path for `formatting`.
This is a prerequisite for a conservative EG-028 (`}`/`;` on-type formatting that touches only the
edited region).

### Probe commands

Not probeable through `explore.py`; confirmed by both endpoints sharing the whole-document
`JavaFormatter.format` path in `WorkspaceSession`.

### Regression targets

- `RangeFormattingTest.rangeFormat_selectionInsideMethod_editsOnlySelectedLines`
- `RangeFormattingTest.rangeFormat_unchangedSelection_returnsNoEdits`

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

## EG-042 — Call hierarchy does not resolve from a call site, only from the method's own declaration

**Status: documented — Target: pending triage**

### Observed behaviour

`textDocument/prepareCallHierarchy` returns items when invoked with the cursor on a method's
declaration, but returns nothing when invoked with the cursor on a call site of that same method —
confirmed symmetrically for both incoming and outgoing calls.

```java
// Declaration — works:
void handle(ServerRequest req, ServerResponse res) throws Exception;   // ← cursor here: 3 callers found

// Call site of the same method — fails:
next.handler().handle(request, response);                              // ← cursor here: "(no call hierarchy item at this position)"
```

Outgoing calls show the identical shape: `feature.setup(realBuilder)` (a call site) also returns
"(no call hierarchy item at this position)", while the declaration of the same method
(`HttpFeature.setup`) resolves correctly.

This means `<leader>ci`/`<leader>co` (`vim.lsp.buf.incoming_calls`/`outgoing_calls` in a Neovim
client) only work when the cursor happens to be sitting on a method declaration — rarely how someone
reaches for "who calls this" in practice. The IntelliJ-familiar workflow (cursor on a call you're
currently looking at, invoke call hierarchy) fails silently instead of erroring, which reads as
"broken" rather than "wrong position."

### Root cause (hypothesis)

`SourceAnalysisSession.prepareCallHierarchy` (line 546) resolves the cursor position via the shared
`resolve(request)` → `SourceLocator.pathAt(trees, tree, offset)`, then calls
`SourceLocator.elementAt(trees, path)` (`SourceLocator.java:115`) to find the enclosing method
element, keeping only `ElementKind.METHOD`/`CONSTRUCTOR` results (lines 557-560).

`elementAt` does have an explicit fallback for `MethodInvocationTree` (lines 122-127: if the walked
path's leaf is a method invocation, resolve via `inv.getMethodSelect()`), so on its face it looks
designed to handle call sites. Since `resolve()`'s `pathAt` is shared with hover/definition/references
— all of which correctly resolve from call sites (confirmed: `refs` from a usage site returns real
results) — the defect is likely specific to how `pathAt`/`elementAt` behave for the exact leaf node
produced at a call site in this particular caller, not a general position-resolution problem. No
`LOG.fine(...)` call exists anywhere in the prepare path today, so it isn't traceable from server
logs alone; pinning the exact failing step needs either a debugger session or added logging.

### Probe commands

```bash
# Works — cursor on the declaration:
python3 dev/explore.py \
  ~/git/helidon/webserver/webserver/src/main/java/io/helidon/webserver/http/Handler.java \
  callers "handle(ServerRequest"

# Fails — cursor on a call site of the same method:
python3 dev/explore.py \
  ~/git/helidon/webserver/webserver/src/main/java/io/helidon/webserver/http/HttpRoutingImpl.java \
  callers "next.handler().handle"

# Fails symmetrically for outgoing calls from a call site:
python3 dev/explore.py \
  ~/git/helidon/webserver/webserver/src/main/java/io/helidon/webserver/http/HttpRoutingImpl.java \
  callees "feature.setup(realBuilder)"
```

### Regression targets

None yet — undecided pending triage.

---

## EG-043 — Type hierarchy does not resolve from a usage/reference site, only from the type's own declaration

**Status: documented — Target: pending triage**

### Observed behaviour

`textDocument/prepareTypeHierarchy` resolves correctly with the cursor on an interface's own
declaration (e.g. `public interface HttpFeature`), returning full supertypes/subtypes — but returns
nothing when the cursor is on a *usage* of that same type elsewhere in the code (a loop variable's
declared type, a parameter type, etc.).

```java
public interface HttpFeature extends Supplier<HttpFeature>, ServerLifecycle {   // ← cursor here: works
                                                                                  //   (2 supertypes, 20 subtypes)

for (HttpFeature feature : features) {                                          // ← cursor here:
                                                                                  //   "(no type hierarchy item at this position)"
```

Same shape of defect as EG-042, on the type-hierarchy side: `<leader>ts`/`<leader>ti` only work when
the cursor happens to be on the type's own declaration, not on any of its (far more common) usage
sites.

### Root cause (hypothesis)

`SourceAnalysisSession.prepareTypeHierarchy` (line 530) requires `SourceLocator.elementAt(...)` to
resolve to a `TypeElement` (line 538); if the resolved element isn't a `TypeElement`, it returns
`List.of()` immediately with no further attempt to map a usage-site reference back to the type it
refers to. Likely the same underlying class of defect as EG-042 (both share `resolve()`/
`elementAt`), but confirmed as a separate code path (`prepareTypeHierarchy` vs
`prepareCallHierarchy`), so it may need an independent fix even if the root-cause pattern is shared.

### Probe commands

```bash
# Works — cursor on the interface's own declaration:
python3 dev/explore.py \
  ~/git/helidon/webserver/webserver/src/main/java/io/helidon/webserver/http/HttpFeature.java \
  hierarchy "HttpFeature"

# Fails — cursor on a usage of the same type:
python3 dev/explore.py \
  ~/git/helidon/webserver/webserver/src/main/java/io/helidon/webserver/http/HttpRoutingImpl.java \
  hierarchy "for (HttpFeature"
```

### Regression targets

None yet — undecided pending triage.

---

## EG-044 — Call hierarchy does not aggregate calls made through a supertype/interface reference when queried from a concrete override

**Status: documented — Target: pending triage**

### Observed behaviour

Querying incoming calls from an interface method's declaration finds real callers; querying the
*same logical method* from a concrete class's `@Override` implementation returns zero callers, even
though those real call sites almost certainly reach this override at runtime through polymorphic
dispatch via an interface-typed reference.

```java
// HttpFeature.java — interface declaration:
void setup(HttpRouting.Builder routing);        // ← callers: 3 found (real call sites)

// HttpRoutingFeature.java — concrete @Override of the same method:
public void setup(HttpRouting.Builder routing) { ... }   // ← callers: 0 found, despite 61 candidates scanned
```

All 3 real callers were found via `feature.setup(...)` where `feature` is statically typed as
`HttpFeature` — none reference `HttpRoutingFeature` by its concrete type. From an IntelliJ-experience
standpoint this is a significant usability gap: users are used to invoking call hierarchy from
*either* the interface or an implementation and getting an aggregated, useful answer either way
(IntelliJ resolves polymorphic call sites into the hierarchy of whichever override you asked about).
Lathe's current behavior instead makes call hierarchy on an override look "broken" (zero results)
unless the user knows to jump to the interface declaration first.

### Root cause (hypothesis)

Not yet traced to a specific class. Likely the incoming-calls search (`CallHierarchyIncomingLocator`)
matches call sites strictly by the exact resolved method (the concrete override), rather than also
matching call sites statically bound to an interface/supertype method that this override implements.
A fix likely needs to expand the search to include call sites resolving to any method this override
transitively implements/overrides. Not yet cross-checked against how Find References handles the
equivalent override-vs-interface scenario for other symbol kinds.

### Probe commands

```bash
# Interface declaration — finds real callers:
python3 dev/explore.py \
  ~/git/helidon/webserver/webserver/src/main/java/io/helidon/webserver/http/HttpFeature.java \
  callers "void setup"

# Concrete override of the same method — finds none:
python3 dev/explore.py \
  ~/git/helidon/webserver/webserver/src/main/java/io/helidon/webserver/http/HttpRoutingFeature.java \
  callers "public void setup"
```

### Regression targets

None yet — undecided pending triage.

---

## EG-045 — Hover on a component reference inside a record's compact constructor resolves to the callee's parameter

**Status: documented — Target: pending triage**

### Observed behaviour

Hover on a bare reference to a record component **inside the compact constructor body** returns the
wrong symbol: it resolves to the formal parameter of the method the component is passed to, not to the
component itself.

```java
public record Config(String bucket) {
    public Config {
        check(bucket, "bucket");   // ← hover on `bucket` → "Object value" (check's parameter!)
    }
    static void check(Object value, String name) {}
}
```

Hover is correct everywhere else the same component appears — verified against a validation workspace:

| Hover position | Result | Correct? |
|---|---|---|
| component in the record header | the component (`T bucket`) | ✓ |
| accessor call `x.bucket()` | the accessor (`T bucket()`) | ✓ |
| ordinary method argument (a local/param elsewhere) | that argument's own type | ✓ |
| **bare component ref inside the compact constructor** | the *callee's* first parameter (`Object value`) | ✗ |

At the same failing position, `definition` correctly jumps to the component declaration, so the
symbol is resolvable — only the hover path mis-resolves it. Shares its trigger with the references
defect [FR-014](#fr-014--find-references-from-a-component-reference-inside-a-records-compact-constructor-returns-only-that-one-occurrence),
and belongs to the same resolve-from-usage-site family as EG-042 / EG-043.

### Root cause (hypothesis)

The bare reference is the implicit canonical-constructor `PARAMETER`, whose source position overlaps
the record header (the backing field and the canonical-ctor parameter share the header range). The
hover position→element path appears not to find that parameter's attributed element at the in-body
position and falls back to the enclosing `MethodInvocationTree`, resolving to the invoked method's
formal parameter (`check(Object value, …)` → `value`). `definition` uses a different resolver
(`DeclarationLocator`) and is unaffected — so this is specific to the hover resolution path, not the
shared `resolve()`/`elementAt` position mapping (ordinary arguments hover correctly).

### Probe commands

```bash
# `bucket,` lands the cursor on the compact-constructor use.
# Expected: the component's type. Bug: the callee's parameter ("Object value").
printf 'hover "bucket,"\n' | python3 dev/explore.py /path/to/workspace/.../Config.java
```

### Regression targets

- `HoverTest.hover_recordComponentInCompactConstructor_resolvesComponentNotCalleeParameter`
  (positive — the failing case)
- `HoverTest.hover_ordinaryMethodArgument_resolvesArgumentNotCalleeParameter`
  (boundary — ordinary arguments must keep resolving to the argument, not the callee's parameter)

---

## Implementation notes

The release slice is derived from the gap fields, not maintained as an ordered list here: the work
for a release is every gap with `Status: accepted` and the matching `Target` (see
[gap-process.md](gap-process.md)).

Guidance that does not fall out of the fields:

- Do **EG-007** (WARNING flood) early — it improves log signal for debugging everything else.
- Implement **EG-023 with EG-008** (shared `Object`-method suppression list).
- Implement **EG-021 with EG-006** (shared reactor-origin ranking).
- **EG-014** and **EG-015** reuse the override-resolution walk from **EG-012** (already implemented).

---

# Find References Gaps (FR)

Active `textDocument/references` gaps discovered by live probing against a large `@Builder`-heavy
reactor workspace. Resolved FR entries are in [gaps-archive.md](gaps-archive.md).

## FR-010 — Reference highlight lands on the wrong identifier in generated code

**Status: accepted — Target: M2**

### Observed behaviour

Find References on a member reports a match whose highlighted range covers a *neighbouring*
identifier (a similarly-named sibling such as `taxAmount` when searching `amount`) inside a generated
builder class, instead of the intended occurrence. Reported against generated `@Builder` sources;
not yet reproduced in a unit fixture (a broad accessor search returned correct ranges, so the defect
appears specific to generated-source positions).

### Root cause (hypothesis)

`ReferenceLocator.addMatchAtIdentifier` (`ReferenceLocator.java:257-263`) computes the identifier
start as `endPosition(node) - name.length()`, assuming the node ends exactly at the target
identifier. In generated sources javac source positions can be synthetic or approximate, so
`getEndPosition` may not land at the identifier end, painting the highlight over an adjacent token.
`addDeclarationMatch` carries a similar assumption via `findIdentifierFrom` from a possibly-inaccurate
node start. The semantic matcher is name-exact (`ReferenceTarget.matches` requires
`simpleName.equals(...)`), so this is a range-computation defect, not a matching defect — the match
count is right, the drawn range is wrong.

### Proposed fix

Derive the identifier range from a reliable name position (javac name-position API / existing
`SourceLocator` helpers) rather than end-minus-length arithmetic. Reproduce with a generated-source
fixture first, then fix and assert every returned range's text equals the searched name.

### Probe commands

```bash
# refs on a component of an @Builder record; verify each returned range's text == the searched name.
printf 'refs "<component>,"\n' \
  | python3 dev/explore.py /path/to/workspace/.../SomeRecord.java
```

### Regression targets

- `ReferenceLocatorTest.references_generatedBuilderMember_rangeCoversExactIdentifier`
- `ReferenceLocatorTest.references_adjacentSimilarNames_noCrossHighlight`

---

## FR-011 — Method call on a `var`/chained receiver is dropped when the receiver type is never spelled

**Status: accepted — Target: M2**

### Observed behaviour

Find References on a record component reports **no matches outside the declaring record**, even though
a genuine call site exists. Reproduced against a validation workspace: a record accessor is invoked
on a receiver whose type is inferred and never written in the calling file.

```java
// FeatureConfig.java — the record; component 'whitelist' has an implicit accessor whitelist()
public record FeatureConfig(boolean enabled, List<String> whitelist, ...) { ... }

// RequestContext.java — the call site, in a different module. Note: FeatureConfig is NEVER spelled
// here (no import, no explicit type); its type flows in through `var` from a getter chain.
final var config = getConfig().feature();          // config's type FeatureConfig is inferred
return config != null
    && config.enabled()
    && (msisdn == null || !config.whitelist().contains(msisdn));   // <-- missed reference
```

Clicking `whitelist` in the record returns only the declaration; `config.whitelist()` above is not
reported.

### Root cause

The defect is in **candidate planning**, not in reference matching. `ReferenceLocator`/
`ReferenceTarget.matches` would match `config.whitelist()` correctly — but the file is pruned before
javac ever compiles it.

1. The record component is normalised to its public accessor method (`ReferenceTarget.from` →
   `recordAccessorFor`), producing a `METHOD` target with `overrideFamilyBounded = true` and empty
   `overriddenDeclarers` (a record accessor overrides nothing).
2. `ReferenceCandidatePlanner.planMethodCandidates` (`ReferenceCandidatePlanner.java:116-131`) takes
   the override-family narrowing path and calls `narrowToFamily`.
3. `narrowToFamily` (`ReferenceCandidatePlanner.java:153-170`) **intersects** the files that spell the
   member simple name (`whitelist`) with the files that spell a family type's simple name
   (`FeatureConfig`). The call site spells `whitelist` but not `FeatureConfig`, so it is filtered out.

The override-family heuristic assumes a genuine call site textually spells the receiver's static type
name (the doc comment at `ReferenceCandidatePlanner.java:105-114` explicitly relies on
`baseConfig.name()` spelling the type). That assumption breaks whenever the receiver type is never
written: `var` bound from a factory/getter chain, a chained call (`getConfig().feature().whitelist()`),
a generic type variable, or a wildcard capture. The result is a **false negative** — a real reference
silently pruned.

This is not record-specific; any public-method reference has the same blind spot. Records merely make
it common, because config accessors are routinely reached through `var`/chained getters.

### Fix (approach A — drop override-family narrowing for methods)

Method targets now use the **broad simple-name candidate set** — every file that spells the method's
simple name — with no override-family narrowing. `planMethodCandidates`, `overrideFamilyBounded`, and
the `java.lang.Object` special-case are removed from the method path (`overrideFamily` /
`narrowToFamily` remain for fields, constructors, and enum constants).

Rationale: the narrowing was a candidate-pruning optimization, not a correctness mechanism — javac
still decides real matches in `ReferenceLocator`. It was the source of the false negative, and no
purely textual heuristic can tell a genuine call on an unspelled receiver (`config.whitelist()`, want
kept) from an unrelated same-named method (want dropped) without compiling. Reference search is an
explicit, batched, cancellable, progress-reported action, and the batch-compilation path is cheap
enough that compiling every file spelling the name is an acceptable trade for full correctness and a
simpler planner. A member-invocation index (former "approach B") was considered but rejected as it
adds a second index dimension and a position-aware text scan for no correctness gain over A.

Trade-off: a reference search on a very common method name (`get`, `size`, `toString`) compiles more
files than before. Mitigated by batching + cancellation; if a pathological case ever bites, a
high-threshold cap (`size > N ? narrow : broad`) can be reintroduced without an index change.

### Regression targets

- `ReferenceCandidatePlannerTest.planCandidates_methodInvokedOnUnspelledReceiver_includesCallSite`
  (positive — a call on a `var`/unspelled receiver is a candidate)
- `ReferenceCandidatePlannerTest.planCandidates_methodNameNeverSpelled_excludesFile`
  (negative — a file that never spells the method's simple name is not a candidate)

---

## FR-012 — Type references miss a same-package generated file that uses the type without importing it

**Status: accepted — Target: M2**

### Observed behaviour

Find References on a `@Builder` record **type** does not report the generated builder, even though the
builder plainly references the record. Reproduced against a validation workspace: the record's
generated builder lives in the same package, in the annotation-processor output root, and names the
record without an import.

```java
// FeatureConfig.java — src/main/java, the annotated record
package com.example.app.config;
public record FeatureConfig(boolean enabled, List<String> whitelist, ...) { ... }

// FeatureConfigBuilder.java — target/generated-sources/annotations, SAME package, NO import of the
// record; references the type by simple name only.
package com.example.app.config;
public final class FeatureConfigBuilder {
  public FeatureConfig build() { return new FeatureConfig(...); }   // <-- missed type reference
}
```

References on `FeatureConfig` return the source-tree usages but omit the generated builder.

### Root cause

Candidate-planning prune, not a matching defect. The generated builder **is** in the candidate index
(`ReferenceCandidateIndex.allSourceRoots` indexes `originalGenSourcesDir` alongside `sourceRoots`), so
it appears in `simpleCandidates` for the token `FeatureConfig`. But `planTypeCandidates`
(`ReferenceCandidatePlanner.java:64-103`) keeps a same-package file only through
`isInPackage(path, config.sourceRoots(), packageRel)` (`ReferenceCandidatePlanner.java:101, 177-182`),
which resolves `packageRel` against **`config.sourceRoots()` only** — never
`config.originalGenSourcesDir()`. The builder's parent directory sits under the generated-sources
root, matches no regular source root, and is pruned.

The import-token branch still finds generated files that use an explicit or wildcard import, so only
the **same-package, no-import** shape is lost — which is exactly how the generated builder (and
updater/companion types) reference the record they belong to.

### Proposed fix

Have the same-package filter consider the generated-sources root in addition to the regular source
roots: resolve `packageRel` against `config.sourceRoots()` ∪ `{config.originalGenSourcesDir()}` (when
non-null). Minimal and localized to `planTypeCandidates` / `isInPackage`.

### Regression targets

- `ReferenceCandidatePlannerTest.planCandidates_typeUsedInSamePackageGeneratedSource_includesGeneratedFile`
  (positive — fails before the fix)
- `ReferenceCandidatePlannerTest.planCandidates_typeInGeneratedSourceDifferentPackage_excludesFile`
  (negative — generated-root awareness must still respect package boundaries)

---

## FR-013 — Constructor references select no candidates because the target is keyed on `<init>`

**Status: accepted — Target: M2**

### Observed behaviour

Find References on a record/class **constructor** returns nothing outside the declaring file — in
particular it omits the generated builder's `new FeatureConfig(...)` call. Reproduced against a
validation workspace:

```java
// FeatureConfigBuilder.java — generated
public FeatureConfig build() {
  return new FeatureConfig(enabled, whitelist, ...);   // <-- missed constructor reference
}
```

### Root cause

Candidate-planning prune. A constructor's `ReferenceTarget.simpleName` is javac's constructor name
**`<init>`** (`ReferenceTarget.from` uses `element.getSimpleName()` for the `CONSTRUCTOR` case,
`ReferenceTarget.java:54-78`). `planCandidates` opens with
`simpleCandidates = index.candidateUris(target.simpleName())` — i.e. `candidateUris("<init>")`.
`<init>` is never an identifier token in any source file, so the set is **empty**, and the guard at
`ReferenceCandidatePlanner.java:31-33` returns `Set.of()` before the constructor branch
(`ReferenceCandidatePlanner.java:49-51`) ever runs. Every constructor reference search therefore
selects zero external candidates; the `new FeatureConfig(...)` call site is never compiled or matched.

### Proposed fix

For a `CONSTRUCTOR` target, key the initial candidate lookup on the **declaring type's simple name**
(derived from `target.qualifiedName()`), not `target.simpleName()`. The existing constructor branch
`narrowToFamily(Set.of(target.qualifiedName()), …)` then bounds the search to files spelling the type
name — the generated builder among them. Pairs with FR-012 so the same-package generated builder both
survives the simple-name lookup and passes the package filter.

### Regression targets

- `ReferenceCandidatePlannerTest.planCandidates_constructorInvokedViaNew_includesCallSite`
  (positive — fails before the fix; target keyed on `<init>`)
- `ReferenceCandidatePlannerTest.planCandidates_constructorOwnerNameNotSpelled_excludesFile`
  (negative — a file that never spells the type stays out)

---

## FR-014 — Find References from a component reference inside a record's compact constructor returns only that one occurrence

**Status: done — Target: M2**

Fixed: `ReferenceTarget.recordAccessorFor` now normalises the implicit canonical-constructor
`PARAMETER` to the component's accessor (as it already did for `RECORD_COMPONENT` and the backing
`FIELD`), so Find References is symmetric from the header and the compact-ctor use. Verified live —
the compact-ctor query now returns all sites. Regression targets below are implemented and passing.

### Observed behaviour

Find References is asymmetric for a record component. Invoked from the component in the record
header it finds every use; invoked from a bare reference to that same component **inside the compact
constructor body** it returns only that one occurrence.

```java
// Config.java — component `bucket` has an implicit accessor bucket()
public record Config(String bucket) {
    public Config {
        check(bucket, "bucket");   // ← Find References here: only this line is reported
    }
    static void check(Object value, String name) {}
    String read() { return bucket; }   // this genuine use is missed from the compact-ctor query
}
```

From the header `bucket`: all uses (backing-field reads, the compact-ctor reference, external
accessor calls). From the compact-ctor `bucket`: a single self-match, and the candidate planner only
even evaluates one candidate (the search is silently file-scoped).

`definition` from the same compact-ctor position resolves correctly to the component; only
`references` collapses. Shares its trigger with the hover defect [EG-045](#eg-045--hover-on-a-component-reference-inside-a-records-compact-constructor-resolves-to-the-callees-parameter).

### Root cause

`SourceAnalysisSession.resolveTarget` resolves the cursor via `SourceLocator.elementAt`, which inside
the compact constructor returns the **implicit canonical-constructor `PARAMETER`** (javac models each
record component as a same-named formal parameter of the canonical constructor). `ReferenceTarget.from`
then calls `recordAccessorFor` (`ReferenceTarget.java:109`), which normalises a `RECORD_COMPONENT`
element and a record's backing `FIELD` to the public accessor — **but not the canonical-constructor
`PARAMETER`**. So `from` falls through to the `default` branch and builds a `kind=PARAMETER`,
`scope=DECLARING_FILE` target that matches only same-named parameters in the one file → the single
self-hit.

The matching side already handles this member: `matchesRecordComponentMember`
(`ReferenceTarget.java:246`) explicitly counts "the canonical constructor `PARAMETER` that javac
reports for compact/canonical constructor bodies" — but that only fires when the target was built
**from the accessor**. The gap is purely entry-point normalisation, which is why the search works
from the declaration and not from the use. Every existing record test in `ReferenceLocatorTest` builds
its target from the header, so the asymmetry was never exercised.

### Proposed fix

Extend `recordAccessorFor` (threading `Types` through its single caller in `from`) to also normalise a
canonical-constructor `PARAMETER` of a record to its component's accessor, reusing the existing
`enclosingRecord`, `isCanonicalConstructorParameter`, and `componentNamed` helpers. Single-class change
in `ReferenceTarget`; no public API change, no new abstraction. Makes references (and the shared
`resolveTarget` consumers) symmetric from either end.

### Probe commands

```bash
# `bucket,` (component + comma) lands the cursor on the compact-constructor use, not the header.
# Expected: all reference sites. Bug: a single self-match.
printf 'refs "bucket,"\n' | python3 dev/explore.py /path/to/workspace/.../Config.java
```

### Regression targets

- `ReferenceLocatorTest.recordComponent_fromCompactConstructorParameterUse_findsAllReferences`
  (positive — target built from the compact-ctor use finds the same sites as from the header)
- `ReferenceLocatorTest.recordComponent_fromNonCanonicalConstructorParameter_notNormalized`
  (negative — a non-canonical constructor's same-named parameter stays file-scoped, not normalised)

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
