# Structural Navigation (Document Symbols & Folding)

## 1. Vision & Scope

The goal is to implement two high-value, read-only LSP features that power essential editor navigation and UI feedback:
1. **Document Symbols (`textDocument/documentSymbol`)**: Powers the "Outline" view and breadcrumb navigation by providing a hierarchical tree of classes, methods, and fields in the current file.
2. **Folding Ranges (`textDocument/foldingRange`)**: Powers code folding for classes, methods, Javadoc blocks, and import statements.

Both features rely purely on the syntactic structure of the document. They do not require type attribution, cross-file resolution, or external index queries. Because they are so lightweight, they can be fulfilled instantaneously using Lathe's `SourceParser` without routing through the background compilation queue.

## 2. Approach

Both features will be fulfilled by using `SourceParser.parseContent(...)` to obtain a `CompilationUnitTree`, followed by a `TreeScanner` to collect the necessary ranges and symbols.

### 2.1 LSP Endpoint Routing
- Add `documentSymbol` and `foldingRange` endpoints to `LatheTextDocumentService`.
- Dispatch these to `WorkspaceSession`.
- `WorkspaceSession` will route them to the appropriate `CompilationWorker`.
- Introduce two new methods on `CompilationWorker`: `documentSymbol(uri, content)` and `foldingRange(uri, content)`.

### 2.2 Document Symbol Scanner
Implement `DocumentSymbolScanner` extending `TreePathScanner<Void, Void>`.
- **Visit `ClassTree`**: Emit `SymbolKind.Class`, `SymbolKind.Interface`, or `SymbolKind.Enum`.
- **Visit `MethodTree`**: Emit `SymbolKind.Method` or `SymbolKind.Constructor`. 
- **Visit `VariableTree`**: Emit `SymbolKind.Field` when the parent is a `ClassTree`.

**Range Calculation**: 
Use `SourcePositions` from `JavacTrees` to get the start and end positions of each tree node. The LSP `DocumentSymbol` requires two ranges:
- `range`: The full range of the symbol (including body and Javadoc).
- `selectionRange`: The range of the identifier name itself. We can derive the identifier range using the tree's start position offset by annotations/modifiers.

**Hierarchy**:
Maintain a `Deque<DocumentSymbol>` stack to construct the parent-child hierarchy as the scanner traverses the AST.

### 2.3 Folding Range Scanner
Implement `FoldingRangeScanner` extending `TreePathScanner<Void, Void>`.
- **Classes & Methods**: Emit folding ranges based on the `SourcePositions` of `ClassTree` and `MethodTree`. Set `kind` to `FoldingRangeKind.Region`.
- **Imports**: Visit the compilation unit's imports. If there are multiple imports, emit a single folding range covering the first import to the last import. Set `kind` to `FoldingRangeKind.Imports`.

## 3. Performance & Concurrency

Since these features are pure parse passes:
- They skip the `lathe.lock` wait step.
- They do not need to build a fully attributed `JavacTask` with a `CustomFileManager`.
- They execute in `<5ms` per file.

Because they are fast and do not rely on mutable `javac` compiler caches, they can be safely served via the `CompilationWorker` using the existing `SourceParser.parseContent()` method.
