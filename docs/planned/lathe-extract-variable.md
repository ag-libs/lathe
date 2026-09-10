# Lathe — Extract Variable (`refactor.extract`)

## Status

Base slice and the replace-all-occurrences follow-up are implemented; only the `var` variant remains
(see Follow-up slice and gap CA-6). Implemented as `ExtractVariableProvider`, dispatched from
`SourceAnalysisSession.codeAction` alongside `ReplaceVarProvider`; shared helpers (`lineIndent`,
`nearestEnclosingStatement`, `isDenotable`) live in `CodeActionSupport`. Replace-all discovers
occurrences by structural + element-identity equivalence over the enclosing method, anchors the
declaration at the innermost common block, and is gated by a read-set value-stability scan.

A selection-driven `textDocument/codeAction` that introduces a local variable for the selected
expression and replaces the expression with a reference to it.
It is the first extraction refactoring and deliberately builds the shared scaffolding that a later
Extract Method slice reuses (see Reuse map).

Delivered in two slices: the base single-occurrence extract, then a replace-all-occurrences follow-up
(see Follow-up slice).
Adjusting the generated name is done through the planned [Rename](lathe-rename.md) feature rather than
snippet tab-stops (see Client affordances), so a good default name is a first-class requirement, not a
nicety.

## Goal

Select an expression, invoke code actions, and get one atomic edit that:

1. inserts `<Type> <name> = <expression>;` on its own line immediately before the enclosing statement, and
2. replaces the selected expression with `<name>`.

Correctness-gated in the same spirit as the other providers: the action is offered only when the edit
is unambiguous and safe, otherwise it is not offered at all.
A refactor that silently produces broken or surprising code is worse than no refactor.

## Pure LSP — no new command

The action carries its edit inline: the server returns a `CodeAction` whose `edit` is a
`WorkspaceEdit.changes` map, exactly as `ReplaceVarProvider` already does.
The client applies it through the standard code-action flow (`vim.lsp.buf.code_action()` in Neovim).

There is **no** `workspace/executeCommand`, no custom `lathe.*` command, and no client-plugin change.
`:LatheNew` needed a command because it *creates a file*; an edit-only refactor does not.
This keeps the feature editor-agnostic and confined to `lathe-server`.

## Prior art in the codebase

The mechanics already exist and are reused rather than reinvented:

- `ReplaceVarProvider.provide(uri, Range, analysis)` — the range-driven (non-diagnostic) refactor
  pattern, invoked once from the code-action range in `SourceAnalysisSession.codeAction`.
  Extract Variable is a sibling of this class.
- `TypeDisplayFormatter.format(TypeMirror)` — renders a denotable type for the declaration.
- `CodeActionSupport.importEditFor` / `typeFqn` / `typeSimpleName` — the import edit for the declared type.
- `SourceLocator.pathAt` / `toOffset` / `offsetToPosition` and `SourcePositions` — position ↔ offset ↔ node.
- `TryCatchWrapProvider.lineIndent` — indentation of an inserted line (extracted to `CodeActionSupport`; see DRY).

Because every occurrence range comes from javac-attributed analysis (not text scanning), lifting the
expression's exact source span and moving it satisfies the "no ad-hoc Java parsing" rule — the same
verbatim-source approach the wrap-in-try/catch provider already uses.

## Design

### Entry point

A new package-private `ExtractVariableProvider` with:

```java
List<Either<Command, CodeAction>> provide(String uri, Range range, AttributedFileAnalysis analysis);
```

invoked in `SourceAnalysisSession.codeAction` alongside the existing range-driven provider:

```java
addUnique(actions, seen, new ExtractVariableProvider().provide(uri, range, analysis));
```

No interface — one implementation, matching the `ReplaceVarProvider` convention (the coding guide
forbids single-implementation interfaces).

### Algorithm

1. **Resolve the selected expression.**
   Convert `range` start/end to offsets.
   From `SourceLocator.pathAt(startOffset)`, climb to the first `ExpressionTree` whose source span
   (`SourcePositions`) covers `[startOffset, endOffset]` — the smallest expression containing the
   selection.
   An empty selection (caret) yields the innermost enclosing expression.

2. **Guards — offer the action only when the edit is safe and useful.**
   Return no action when any holds:
   - the leaf is not an `ExpressionTree`;
   - the expression's type (`trees.getTypeMirror(exprPath)`) is not denotable, or is `void`/`null`
     (nothing meaningful to declare) — reusing the denotability policy from `ReplaceVarProvider`;
   - there is no nearest enclosing `StatementTree`, or its parent is not a `BlockTree`
     (a braceless `if`/`for`/`while` body cannot receive a second statement);
   - the expression is the entire expression of its enclosing `ExpressionStatement`
     (extracting it is pointless and would collide the insert and replace edits at one offset).

3. **Type and import.**
   `typeText = new TypeDisplayFormatter(analysis.types()).format(type)`;
   `importEdit = CodeActionSupport.importEditFor(analysis, CodeActionSupport.typeFqn(type))`
   (null when no import is needed).

4. **Name.**
   Derive a base from the expression: a method name with a `get`/`is` prefix stripped
   (`getFoo()` → `foo`), a `new Foo(...)` → `foo`, otherwise the type simple name lowercased,
   otherwise `value`.
   Enforce a legal Java identifier that is not a keyword, then uniquify against the local variable and
   parameter names of the enclosing method (a small `VariableTree` visitor) with a numeric suffix on
   collision.
   Broader cross-scope shadowing is out of scope (see Non-goals).
   The name is applied as fixed text — Neovim code actions cannot carry snippet tab-stops (see Client
   affordances) — so heuristic quality matters and the user retunes it afterward via rename.

5. **Edits (one `WorkspaceEdit.changes` for this file).**
   - **Insert** at the enclosing statement's start offset (a zero-length range):
     `"<typeText> <name> = <expressionSource>;\n<indent>"`, where `<expressionSource>` is the exact
     source substring of the expression span and `<indent>` is `CodeActionSupport.lineIndent` at the
     statement.
   - **Replace** the expression span with `<name>`.
   - **Import** edit when non-null.
   The guards guarantee the insert (at the statement start) and the replace (strictly inside the
   statement) never share an offset, so the `TextEdit`s do not overlap.

6. **Action.**
   `title = "Extract variable '<name>'"`, `kind = CodeActionKind.RefactorExtract`, `edit = WorkspaceEdit`.

### Data flow

The provider is a single read-only pass over one attributed compilation unit — no compile, no
cross-file I/O, no state mutation — that folds the request and the AST into one file's edits.
Every guard is a filter: a failure short-circuits the whole pipeline to `[]` (no action offered), and
every text value (`exprSource`, ranges) is derived from javac source positions, never ad-hoc parsing.

Request path:

```
Editor ─ CodeActionParams { uri, range, context } ─▶ LatheTextDocumentService.codeAction
      ─▶ WorkspaceSession.codeActionFuture ─▶ SourceAnalysisSession.codeAction
      ─ ensureAttributedAnalysis ─▶ AttributedFileAnalysis { cu, Trees, Types, Elements, source }
      ─▶ ExtractVariableProvider.provide(uri, range, analysis)
      ─▶ List<Either<Command, CodeAction>>  (0 or 1) ─▶ addUnique ─▶ client applies WorkspaceEdit
```

Core transform (`⟂` = guard → `[]`):

```
range ──toOffset──▶ (startOffset, endOffset)
(startOffset,endOffset)+cu+Trees ──pathAt + climb via SourcePositions──▶ exprPath : TreePath
    ⟂ leaf not an ExpressionTree
exprPath ──getTypeMirror──▶ type : TypeMirror         ⟂ not denotable / void / null
exprPath ──nearestEnclosingStatement──▶ stmtPath      ⟂ none / parent ≠ BlockTree
                                                      ⟂ expr == whole ExpressionStatement expr
type ──TypeDisplayFormatter──▶ typeText ; ──typeFqn──▶ importEditFor ──▶ importEdit?
expr span + source ──substring──▶ exprSource
expr + enclosingMethod names ──base heuristic + legalize + uniquify──▶ name
stmtPath.start + source ──lineIndent──▶ indent
```

Edit assembly:

```
insertEdit  = TextEdit( [stmtStart,stmtStart) , "‹typeText› ‹name› = ‹exprSource›;\n‹indent›" )
replaceEdit = TextEdit( [exprStart,exprEnd)   , "‹name›" )
WorkspaceEdit.changes = { uri : [ insertEdit, replaceEdit, importEdit? ] }
CodeAction { title, kind = RefactorExtract, edit }
```

That is the base slice; the replace-all follow-up adds an occurrence scan and a read-set stability
check before assembly (see Follow-up slice).

### Capability

`LatheLanguageServer.createCapabilities` currently advertises
`new CodeActionOptions(List.of(CodeActionKind.QuickFix))`.
Add `Refactor` and `RefactorExtract` so clients that request `only: ["refactor.extract"]` receive the
action.
One line; no other capability change.

## DRY

- `lineIndent(String source, int offset)` moves from `TryCatchWrapProvider` (private) to
  `CodeActionSupport`; `TryCatchWrapProvider` switches to the shared helper.
  Both inserters need identical indentation logic.
- `nearestEnclosingStatement(TreePath)` (first `StatementTree` ancestor, bounded at a method / lambda /
  anonymous-class boundary) lives in `CodeActionSupport` next to the existing walkers, so the provider
  holds no bespoke tree-walking.
- Type rendering, import synthesis, and denotability all reuse existing helpers; the provider adds no
  new type utilities.

## Client affordances (Neovim)

The client side is entirely standard LSP — `vim.lsp.buf.code_action` — with no custom command or plugin:

- **Selection → range.** Invoked in visual mode, Neovim sends the visual selection as the request
  `range` (the expression to extract); in normal mode it sends a zero-width range at the cursor, which
  the empty-selection path handles.
- **A dedicated keymap** narrows to this refactor and auto-applies a lone result:

  ```lua
  vim.keymap.set({ "n", "v" }, "<leader>rv", function()
    vim.lsp.buf.code_action({ context = { only = { "refactor.extract" } }, apply = true })
  end)
  ```

  This is why the server advertises `RefactorExtract` and honours `only` (see Capability).
- **The replace-all choice appears for free** as a second entry in the `vim.ui.select` picker (see
  Follow-up slice) — no special client support.

### Naming and the rename hand-off

Neovim's `apply_workspace_edit` applies plain text and does **not** interpret snippet placeholders
(`${1:name}` / `SnippetTextEdit` is non-standard and unimplemented in core), so the extract cannot offer
an IntelliJ-style in-place rename tab-stop.
The design accepts this deliberately: the extract lands a **good generated default name**, and the user
adjusts it afterward with `vim.lsp.buf.rename()` — which pairs with the planned
[Rename](lathe-rename.md) feature and needs no snippet machinery.
This makes the name heuristic (Design step 4) a first-class requirement rather than a nicety, and makes
Rename the natural companion to advertise and document alongside extract.

## Scope

In — covered and tested:

- Extract a sub-expression (`bar(x)` from `foo(bar(x))`), a constructor call (`new Foo(...)`), and a
  generic-typed expression that requires an import.
- Explicit declared type via `TypeDisplayFormatter`.
- Name derived from the expression with method-scope collision avoidance.

## Non-goals (explicit)

- **Replace all occurrences** — the base slice extracts only the selected occurrence; replacing every
  equivalent occurrence is a defined follow-up with its own correctness gates (see Follow-up slice).
- **A `var` variant** — explicit type only for now; a second action offering `var` is a fast follow-up.
- **Braceless single-statement bodies** — guarded out (no action), rather than synthesising a block.
- **Multi-statement or partial-expression selections** — only a single covering `ExpressionTree` is
  handled.
- **Cross-scope shadowing / full conflict analysis** — only enclosing-method locals and parameters are
  checked for name collision; every remaining collision surfaces as a compile-error diagnostic, never
  silent corruption.
- **Field / class-body extraction** — the target is a local inside a method or initializer block.

## Follow-up slice — replace all occurrences

After the base slice ships, a second **separately-labelled** action —
`Extract variable '‹name›' (replace all N occurrences)` — replaces every equivalent occurrence in
scope.
It is surfaced as a distinct code action (not an interactive prompt, which LSP lacks); the base
single-occurrence action always remains available.

Added stages over the base data flow:

- **Occurrence discovery** — a `TreeScanner` over the enclosing method collects expressions equal to
  the selection by **structural** equality (same tree kinds / literals / operators) **and** **semantic**
  equality (each name / member-select resolves via `Trees.getElement` to the same `Element`). Semantic
  equality is what prevents merging `a.x` sites where `a` is a different local — no text matching.
- **Common anchor** — the nearest common ancestor `BlockTree` of all occurrences; insert before the
  earliest top-level statement of that block that transitively contains an occurrence. If no single
  dominating block exists, only the single-occurrence action is offered.
- **Edits** — one insert at the anchor + N replaces + import; non-overlapping by construction (the
  insert precedes every occurrence; the occurrence spans are disjoint).

Two correctness gates decide whether replace-all is offered:

1. **Semantic-equality match** (above) — occurrences must be the same symbol, not the same spelling.
2. **Value-stability** — none of the variables / fields the expression **reads** may be reassigned,
   compound-assigned, `++`/`--`-ed, or re-declared between the anchor and the last occurrence. This
   refuses the value-capture bug (collapsing `x + 1` while `x` changes between uses). Implemented as a
   read-set scan over the region; any hit ⇒ no replace-all.

**Side-effect duplication — resolved (IntelliJ-style, value-stability-gated).** Collapsing N evaluations
into one changes evaluation *count*; for a value-stable set this preserves the *value*, and the reduced
count is the refactor's intent.
Replace-all is therefore offered for any semantically-equal, value-stable set — including expressions
containing calls — as an explicit, count-labelled action the user opts into, rather than restricting to
provably pure expressions (which would exclude the common repeated-getter case and make the feature
rarely fire).
The design does not attempt to prove callee purity; the only silent-corruption mode — value capture — is
refused by gate 2.

## Reuse map (for the later Extract Method slice)

Landing this slice creates the shared scaffolding Extract Method rides:

- the range-driven provider entry point and its dispatch line;
- `CodeActionSupport.lineIndent` and `nearestEnclosingStatement`;
- type rendering + import synthesis + denotability;
- the `WorkspaceEdit.changes` / non-overlapping-`TextEdit` assembly and the `RefactorExtract` capability;
- the name-generation + collision uniquifier (the result-variable name of an extracted method reuses it);
- the verbatim-source-lift approach and the compile-inline test-fixture pattern.

The replace-all follow-up's semantic-equality comparator and read-set / reassignment scan are early
pieces of that use/def analysis, so it too is an investment toward Extract Method.

Extract Method then reduces to one net-new, self-contained addition — the data-flow (use/def) and
control-flow analysis plus signature synthesis — rather than a from-scratch feature.

## Testing

Mirror the existing analysis-provider tests: compile inline source, call `provide(...)`, assert the
resulting `WorkspaceEdit` text.

- Positive: sub-expression extraction; `new Foo()`; a generic type that adds an import; a name derived
  from `getX()` → `x`; an empty-range caret inside an expression.
- Negative / edge: a `void` call offers no action; a braceless `if` body offers no action; the whole
  `ExpressionStatement` expression offers no action; a name that collides with an in-scope local is
  suffixed.
- The invoker `LspSmokeTest` may assert that a `refactor.extract` action is returned against the
  multi-module fixture, once the capability advertises the kind.

## Open items before implementation

- **Name-heuristic breadth.** Start with the small heuristic above; widen only if real usage shows poor
  names — do not build a general natural-language namer.
- **Work tracking.** Add a `gaps.md` code-action entry when implementation starts.
- **Neovim wiring.** Neovim already surfaces code actions; confirm `refactor.extract` appears in the
  menu with no client change, and add a one-line note to the editor guide if a dedicated keymap is wanted.
