# Structural Navigation (Document Symbols & Workspace Symbols)

## 1. Vision & Scope

Three LSP features that power essential editor navigation:

1. **Document Symbols (`textDocument/documentSymbol`)** *(beta scope)*: Powers the "Outline" view
   and breadcrumb navigation by providing a hierarchical tree of classes, methods, and fields in
   the current file.
2. **Workspace Symbols (`workspace/symbol`)** *(beta scope)*: Powers "go to type by name" across
   the entire project. The client sends a partial name query; the server returns matching types from
   the type index. Essential for Neovim Telescope's `lsp_workspace_symbols` picker.
3. **Folding Ranges (`textDocument/foldingRange`)** *(deferred to post-beta)*: Powers code folding.
   Most Neovim users already get equivalent folding from treesitter (`foldmethod=expr`).

Both beta features rely on existing infrastructure (`SourceParser`, `WorkspaceTypeIndex`) and
require no new compilation passes.

## 2. Document Symbols

### 2.1 Approach

Fulfilled by calling `SourceParser.parseContent(uri, content)` to get a `CompilationUnitTree`,
then walking it with a `TreePathScanner`. No type attribution needed — parse only.

Routed through `CompilationWorker` → `SourceAnalysisSession`, reusing the `SourceParser` already
owned by each session, following the same pattern as hover and signature help.

### 2.2 LSP Routing

- Add `documentSymbol` endpoint to `LatheTextDocumentService`.
- Dispatch via `WorkspaceSession.documentSymbolFuture(uri)` → `routeFeature` →
  `CompilationWorker.documentSymbol(request)` → `SourceAnalysisSession.documentSymbol(request)`.
- `SourceAnalysisSession.documentSymbol` calls `parser.parseContent(uri, content)` directly and
  does **not** call `resolve()` — no compilation needed.

### 2.3 DocumentSymbolScanner

`DocumentSymbolScanner extends TreePathScanner<Void, Void>`:

- **`visitClass`**: push a `DocumentSymbol` onto a `Deque` stack, call `super.visitClass` to recurse
  into members and inner classes, pop on exit, attach to parent or to the root list.
  Kind: `Class`, `Interface`, `Enum`, or `Record` based on `ClassTree` flags.
- **`visitMethod`**: same push/super/pop pattern.
  Kind: `Constructor` when `getName()` returns `<init>`, `Method` otherwise.
- **`visitVariable`**: only emit when the enclosing node in the current path is a `ClassTree`
  (skips local variables, parameters, for-loop variables).
  Kind: `Field`. Attach directly to the enclosing class symbol without pushing to the stack
  (fields have no children).

**Range calculation**:
- Use `Trees.getSourcePositions().getStartPosition/EndPosition(cu, node)` for byte offsets.
- Convert to LSP `Range` via `cu.getLineMap()`: `getLineNumber(offset) - 1` (0-based),
  `getColumnNumber(offset) - 1` (0-based).
- `range`: full span of the declaration node.
- `selectionRange`: locate the name identifier by `source.indexOf(name, startOffset)` — the name
  appears after modifiers and keywords, so the first hit from the node's start is correct.

**Result**: `List<Either<SymbolInformation, DocumentSymbol>>` — all items are `forRight(symbol)`.

### 2.4 Capability

Set `capabilities.setDocumentSymbolProvider(true)` in server capability initialization.

## 3. Workspace Symbols

### 3.1 Approach

Fulfilled by querying the existing `WorkspaceTypeIndex` directly on the workspace session thread.
No module worker or per-file analysis needed.

`WorkspaceTypeIndex.search(query, limit)` already does case-insensitive prefix matching against
all indexed types (JDK, dependency shards, and reactor output shards).

### 3.2 LSP Routing

- Add a `symbol(WorkspaceSymbolParams)` override to `LatheWorkspaceService`.
- `LatheWorkspaceService` receives a reference to `WorkspaceSession` (or to
  `LatheTextDocumentService`, which already holds the worker and session).
- Dispatch: `worker.submit(() -> session.workspaceSymbol(query))` to run on the server worker thread
  so the index snapshot is accessed safely.
- `WorkspaceSession.workspaceSymbol(query)` calls `typeIndex.search(query, 100)` and maps each
  `TypeIndexEntry` to a `SymbolInformation`.

### 3.3 Location Resolution

`TypeIndexEntry` does not store a source path. Derive it at query time:

1. Convert `qualifiedName` to a relative path by replacing `.` with `/` and appending `.java`.
   For top-level classes this is exact; for inner classes (name contains a capital after a `.`)
   strip the last component to get the outer class file.
2. Probe each directory in `allSourceRoots() + manifest.externalSourceDirs()` for the relative path.
3. If found: use the file's `file://` URI; range is `{0,0}–{0,0}` (start of file). Editors open
   the file and the user navigates from there.
4. If not found: omit the result (do not emit a `SymbolInformation` with a garbage URI).

### 3.4 Kind Mapping

```
TypeKind.CLASS       → SymbolKind.Class
TypeKind.INTERFACE   → SymbolKind.Interface
TypeKind.ENUM        → SymbolKind.Enum
TypeKind.RECORD      → SymbolKind.Class   (LSP has no Record kind)
TypeKind.ANNOTATION  → SymbolKind.Interface
TypeKind.UNKNOWN     → SymbolKind.Class
```

### 3.5 Capability

Set `capabilities.setWorkspaceSymbolProvider(true)` in server capability initialization.

