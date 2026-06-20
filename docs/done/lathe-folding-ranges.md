# Lathe — Folding Ranges

## 1. Goal

Implement the `textDocument/foldingRange` LSP endpoint to provide structural code folding.
A primary focus of this feature is accurately identifying and emitting the `imports` folding range, enabling editor clients (such as Neovim) to automatically fold the import block by default when opening a file.
We will also emit standard `region` folds for classes, interfaces, enums, records, and method bodies.

## 2. Approach

We will parse the file using our existing `SourceParser` and traverse the AST to locate the bounds of foldable regions. No full attribution (javac type resolution) is needed; we only need the parse tree (`CompilationUnitTree`) and its `LineMap`.

The process is:
1. Parse the document using `SourceParser.parseContent(...)`.
2. Extract the `LineMap` to convert AST byte offsets into 0-indexed line numbers.
3. Walk the AST with a new `FoldingRangeScanner`.
4. Accumulate a `List<FoldingRange>` and return it.

## 3. LSP Routing

- **Service**: Add `foldingRange(FoldingRangeParams)` to `LatheTextDocumentService`.
- **Dispatcher**: Route through `WorkspaceSession.foldingRangeFuture(...)` -> `routeFeature(...)`.
- **Worker**: `CompilationWorker` dispatches the request to `SourceAnalysisSession`.
- **Session**: `SourceAnalysisSession.foldingRange(...)` parses the file (without resolving types) and runs the `FoldingRangeScanner`.

We must also update the server initialization in `LatheWorkspaceService.java` to advertise `capabilities.setFoldingRangeProvider(true)`.

## 4. FoldingRangeScanner Design

`FoldingRangeScanner` will extend `TreePathScanner<Void, Void>` and collect ranges into a mutable list.

### 4.1 Imports (`FoldingRangeKind.Imports`)

In the `visitCompilationUnit` method, we will inspect the `getImports()` list.
If there are two or more imports:
- Find the start offset of the first `ImportTree`.
- Find the end offset of the last `ImportTree`.
- Convert these to `startLine` and `endLine` using the `LineMap`.
- If `endLine > startLine`, emit a `FoldingRange` with `kind = "imports"`.

### 4.2 Classes and Methods (`FoldingRangeKind.Region`)

- **`visitClass`**: Capture the start and end offsets of the `ClassTree`. Emit a `FoldingRange` of kind `region`. Call `super.visitClass` to recurse into its members.
- **`visitMethod`**: Capture the start and end offsets of the `MethodTree`. Emit a `FoldingRange` of kind `region`. Call `super.visitMethod` to recurse (in case there are inner classes inside the method).

### 4.3 FoldingRange Creation

The LSP `FoldingRange` object requires:
- `startLine` (number, 0-indexed)
- `endLine` (number, 0-indexed)
- `kind` (string: "imports", "region", "comment")

Offsets are mapped via `lineMap.getLineNumber(offset) - 1`.

## 5. Client Integration: Neovim Default Import Folding

Once Lathe emits the `imports` kind, Neovim users can fold imports by default.

**Using `nvim-ufo` (Recommended):**
```lua
require('ufo').setup({
    close_fold_kinds_for_ft = {
        java = {'imports'}
    }
})
```

**Using Native LSP Autocommand:**
```lua
vim.api.nvim_create_autocmd("LspAttach", {
    callback = function(args)
        local client = vim.lsp.get_client_by_id(args.data.client_id)
        if client.server_capabilities.foldingRangeProvider then
            vim.schedule(function()
                vim.cmd("normal! zR") -- open all folds
                -- Neovim doesn't natively expose an API to close folds by LSP kind yet,
                -- so `nvim-ufo` is the standard way to achieve this.
            end)
        end
    end,
})
```

## 6. Implementation Steps

1. Create `io.github.aglibs.lathe.server.analysis.FoldingRangeScanner`.
2. Add `foldingRange` to `SourceAnalysisSession`, `CompilationWorker`, and `WorkspaceSession`.
3. Add `foldingRange` to `LatheTextDocumentService`.
4. Enable the server capability.
5. Add unit tests to verify imports and classes are folded correctly.
