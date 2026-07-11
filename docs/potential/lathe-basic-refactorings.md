# Lathe — Basic Refactorings (Rename, Move, Extract)

## Problem / motivation

Lathe offers navigation, completion, and quick-fix code actions, but no structural refactoring.
Normal Java editing in Neovim needs at least the basics: **rename**, **move**, and **extract**
(method, variable, field).
Rename is already on the M2 roadmap ("prepare-rename and exact reactor rename edits") but has no
focused design; move and extract are undocumented.
This request captures the whole basic set and how each maps onto LSP.

## Sketch

Each refactoring is a different LSP surface:

- **Rename** — `textDocument/prepareRename` (validate the position, return the identifier range) plus
  `textDocument/rename` returning a cross-file `WorkspaceEdit`. Reuses the `ReferenceTarget` identity
  and candidate pipeline that Find References already uses to collect every edit site. (Already
  M2-scoped; listed here for completeness.)
- **Move** — no dedicated LSP request; expose as a `CodeActionKind.RefactorMove` (`refactor.move`)
  code action returning a `WorkspaceEdit`. Moving a top-level type uses `WorkspaceEdit` resource
  operations (`RenameFile`/`CreateFile`) to relocate the `.java` file, rewrites its `package`
  declaration, and updates imports/references across the reactor; moving a member cuts it from the
  source type and updates references.
- **Extract** — range-based `CodeActionKind.RefactorExtract` (`refactor.extract`) code actions over
  the selection the client sends in the `codeAction` request `range`:
  - *Extract variable* — selected expression → new local, replace occurrence(s).
  - *Extract field* — selected expression/constant → new field plus initialization.
  - *Extract method* — selected statements → new method; the captured locals become parameters,
    modified locals become the return value / out-params, and thrown checked exceptions propagate.

All edits are computed from the attributed javac AST (and, for extract-method, flow analysis to find
the region's inputs/outputs) — never text manipulation — and returned as a `WorkspaceEdit`. Scope is
reactor-owned sources; dependency/JDK sources are read-only.

## Open questions

- **Extract-method analysis is the hard part.** Determining parameters, return value(s), captured
  vs. modified locals, `final`-ity, and thrown exceptions for an arbitrary selected region needs
  javac data/control-flow analysis, not text parsing. This is the heaviest piece and likely gates
  the milestone; extract-variable/field are much lighter.
- **Selection semantics.** Extract needs a meaningful selection range; relates to the range-format /
  selection work (EG-029). Define how a partial or syntactically-incomplete selection is rejected.
- **WorkspaceEdit resource operations.** Move needs `documentChanges` with `RenameFile`/`CreateFile`;
  confirm the client capability (`workspace.workspaceEdit.resourceOperations`) and Neovim support.
- **Naming and collisions.** Generated method/variable/field names, and conflict detection (name
  clashes, visibility changes on move, shadowing).
- **Formatting of inserted code.** Route generated members/locals through google-java-format so
  output matches surrounding style.
- **Reactor-wide correctness and freshness.** Cross-file edits share the reference machinery and its
  staleness surface — closed files not indexed until sync (see WS-1); a rename/move that misses
  closed-file references would be a correctness bug, not just a stale hint.
- **Preview/apply UX in Neovim.** Rename has built-in client UI; move/extract arrive through the code
  action menu — confirm the flow and any parameter prompts (e.g. new name) are acceptable.

## Milestone candidate

Rename is already M2. Extract-variable and extract-field are moderate and could join M2; move and
especially extract-method are heavier (resource operations, flow analysis) and are likely M2-stretch
or post-M3. Untriaged as a set pending focused per-operation designs.
