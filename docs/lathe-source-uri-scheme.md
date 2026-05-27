# Lathe — `lathe-source://` URI Scheme for External Sources

## Problem

When Lathe resolves a definition that lands in a JDK or dependency source file, it currently
returns a `file://` URI pointing into `~/.cache/lathe/jdks/` or `~/.cache/lathe/deps/`.
This causes three problems in editors:

1. **Swap files** — Neovim creates a swap file for the extracted source, triggering the E325
   attention dialog on repeated visits or when another session has the file open.
2. **New server instance** — editors auto-start a new LSP instance for the file because no
   `.lathe` root marker exists in the cache directory tree.
3. **Path-based detection on the client** — the Neovim plugin must inspect file paths to
   recognise external sources and apply workarounds (swap suppression, readonly marking,
   manual client attach). This logic must be replicated in every editor integration.

## Solution

Return `lathe-source://` URIs for external source files instead of `file://` URIs.
The scheme is the only signal editors need; the path inside the URI is the verbatim absolute
disk path so editors can read the file directly without asking the server.

```
lathe-source:///home/ag-libs/.cache/lathe/jdks/Amazon.com-Inc./26/java.base/java/util/Objects.java
lathe-source:///home/ag-libs/.cache/lathe/deps/com.google.guava/guava/32.0.0/com/google/common/collect/ImmutableList.java
```

Reactor source files (files in the project being edited) continue to use `file://` URIs.

## URI format

```
lathe-source://<absolute-path-to-extracted-source-file>
```

The path is always absolute. On Linux this means the URI always starts with
`lathe-source:///` (three slashes: scheme + empty authority + absolute path).
The path portion maps directly to the disk file that `lathe:sync` extracted into
`~/.cache/lathe/`.

## Server changes

One change in `ModuleAnalysisSession.definition()`. The external-source branch already resolves
the file via `WorkspaceManifest.externalSourceRoot()` and `DefinitionLocator.findSourceFile()`.
Only the final URI construction changes:

**Before:**
```java
return new Location(file.toUri().toString(), new Range(lspPos, lspPos));
```

**After:**
```java
return new Location("lathe-source://" + file.toAbsolutePath(), new Range(lspPos, lspPos));
```

`WorkspaceManifest` already handles root resolution for both JDK sources (`jdkSourceDir`)
and dependency sources (`sourceDirToJar`). No other server changes are required.

## VS Code client

Register a `TextDocumentContentProvider` for the `lathe-source` scheme.
The provider reads the file from disk — no server request needed.

```typescript
context.subscriptions.push(
  vscode.workspace.registerTextDocumentContentProvider('lathe-source', {
    provideTextDocumentContent(uri: vscode.Uri): string {
      return fs.readFileSync(uri.fsPath, 'utf-8');
    }
  })
);
```

VS Code documents opened via a custom scheme are read-only and have no swap file by default.
No further configuration is needed.

## Neovim client

Override the `textDocument/definition` LSP handler in the Lathe plugin to intercept
`lathe-source://` locations. For each such location, read the file from disk, create a
`buftype=nofile` scratch buffer, and jump to the target position.

```lua
local orig_definition = vim.lsp.handlers['textDocument/definition']

vim.lsp.handlers['textDocument/definition'] = function(err, result, ctx, config)
  if err or not result then return end
  local locations = vim.islist(result) and result or { result }
  for _, loc in ipairs(locations) do
    local uri = loc.uri or loc.targetUri
    if vim.startswith(uri, 'lathe-source://') then
      local path = uri:sub(#'lathe-source://' + 1)
      local bufnr = vim.api.nvim_create_buf(false, true)
      vim.api.nvim_buf_set_lines(bufnr, 0, -1, false, vim.fn.readfile(path))
      vim.bo[bufnr].filetype = 'java'
      vim.bo[bufnr].buftype = 'nofile'
      vim.bo[bufnr].modifiable = false
      local range = (loc.targetSelectionRange or loc.range).start
      vim.api.nvim_win_set_buf(0, bufnr)
      vim.api.nvim_win_set_cursor(0, { range.line + 1, range.character })
      vim.lsp.buf_attach_client(bufnr, ctx.client_id)
      return
    end
  end
  orig_definition(err, result, ctx, config)
end
```

`buftype=nofile` means Neovim never creates a swap file. No `BufReadPre` autocmd,
no `is_external_source` path detection, no manual readonly marking, no separate client
attach logic.

## What is eliminated

Compared to the current `file://` approach, the following client-side complexity disappears:

| Current | Replaced by |
|---|---|
| `is_external_source(bufnr)` path predicate | `lathe-source://` prefix check in one handler |
| `lathe_cache_root()` helper | not needed |
| `BufReadPre` autocmd for `swapfile = false` | `buftype=nofile` |
| `BufReadPost` autocmd for readonly + attach | not needed |
| `attach_to_external_source()` | not needed |
| Custom `root_dir` function to suppress new server start | not needed |

## Out of scope

Hover, semantic tokens, and other LSP features on external source buffers are not covered
by this scheme. The `buftype=nofile` buffer is not identified to the server via
`textDocument/didOpen`, so the server holds no analysis for it. Adding LSP features on
external sources requires a separate design (server-side compilation of external sources
keyed by `lathe-source://` URI and triggered by `didOpen`).
