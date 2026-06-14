# Structural Navigation (Document Symbols & Folding)

## 1. Vision & Scope

The goal is to implement two LSP features that power essential editor navigation and UI feedback:
1. **Document Symbols (`textDocument/documentSymbol`)** *(beta scope)*: Powers the "Outline" view
   and breadcrumb navigation by providing a hierarchical tree of classes, methods, and fields in
   the current file.
2. **Folding Ranges (`textDocument/foldingRange`)** *(deferred to post-beta)*: Powers code folding
   for classes, methods, and import statements. Most Neovim users already get equivalent folding
   from treesitter (`foldmethod=expr`), so this is low-impact for the beta audience.

Both features rely purely on the syntactic structure of the document. They do not require type
attribution, cross-file resolution, or external index queries.

## 2. Approach

Both features are fulfilled by using `SourceParser.parseContent(...)` to obtain a
`CompilationUnitTree`, followed by a `TreePathScanner` to collect the necessary ranges and symbols.
They are routed through `CompilationWorker` → `SourceAnalysisSession` in the same way as hover and
signature help, reusing the `SourceParser` already owned by each `SourceAnalysisSession`.

### 2.1 LSP Endpoint Routing
- Add `documentSymbol` (and, post-beta, `foldingRange`) endpoint to `LatheTextDocumentService`.
- Dispatch via `WorkspaceSession` → `routeFeature` → `CompilationWorker` → `SourceAnalysisSession`,
  following the same pattern as hover.

### 2.2 Document Symbol Scanner
Implement `DocumentSymbolScanner` extending `TreePathScanner<Void, Void>`.
- **`visitClass`**: Emit `SymbolKind.Class`, `Interface`, `Enum`, or `Record`.
- **`visitMethod`**: Emit `SymbolKind.Method` or `SymbolKind.Constructor`.
- **`visitVariable`**: Emit `SymbolKind.Field` only when the enclosing node in the current path is
  a `ClassTree` (skip local variables).

**Range Calculation**:
Use `SourcePositions` (from `Trees.getSourcePositions()`) for node start/end offsets and
`CompilationUnitTree.getLineMap()` for line/column conversion.
- `range`: full span of the declaration node.
- `selectionRange`: identifier name only, located by searching forward in the source text from the
  node's start position.

**Hierarchy**:
Maintain a `Deque<DocumentSymbol>` stack. Push a new symbol on class/method entry; pop and append
to the parent's children list on exit. Top-level symbols are collected in the result list.

### 2.3 Folding Range Scanner *(post-beta)*
Implement `FoldingRangeScanner` extending `TreePathScanner<Void, Void>`.
- **Classes & Methods**: Emit `FoldingRangeKind.Region` folds using the full node span.
- **Imports**: If there are ≥2 imports, emit a single `FoldingRangeKind.Imports` fold from the
  first to the last import line.
