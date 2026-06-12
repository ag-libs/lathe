# Lathe — Standard `file://` URIs for External Sources

## Problem

The custom `lathe-source://` URI scheme was originally implemented to prevent swap-file warnings (`E325` in Neovim) and signal to client integrations that dependency/JDK sources are read-only.

However, custom URI schemes break major parts of the editor ecosystems:
1. **Broken Ecosystem Tooling**: Plugins like Telescope, Trouble, Gitsigns, and Lspsaga fail to preview, search, or highlight files using `lathe-source://` because they expect standard files. Telescope's internal `utils.is_uri()` detects the custom scheme and bypasses all `path_display` formatting, showing the raw URI verbatim. Telescope's previewer cannot read the file from disk since `plenary.path` doesn't understand the scheme.
2. **Broken Formatters and Linters**: External command-line tools (e.g. `spotless`, `google-java-format`) crash when passed virtual buffer paths.
3. **Broken Debugger Mappings**: Editor debuggers cannot map source location stack traces back to virtual buffers.
4. **AI Assistant Blindness**: Coding assistants (like GitHub Copilot) ignore non-file schemes, losing all code context when browsing library files.

## Bugs in Current Implementation

Two bugs exist in the current `lathe-source://` implementation that this migration fixes:

1. **`WorkspaceSession.publishDiagnosticsForOpenBuffers()`** (~line 539) converts URIs using `Path.of(URI.create(uri))` directly instead of routing through `LatheUri.toPath()`. If the editor sends back a `lathe-source://` URI, `Path.of()` fails because it doesn't understand that scheme. Switching to `file://` fixes this automatically.

2. **`root_dir` fallback in `lathe.lua`** (lines 63–74) checks `vim.startswith(fname, root)` where `root = ~/.cache/lathe`. With `lathe-source://` buffers, `fname` is `lathe-source:///home/...` which never matches the filesystem path prefix. The cache-file fallback for auto-attaching the LSP client to external source buffers is silently broken. With `file://` URIs, the buffer name becomes a standard path that matches correctly, and the `root_dir` function becomes the primary auto-attach mechanism for external sources, replacing the manual `buf_attach_client` call in `open_lathe_source()`.

## Solution

Revert all external/JDK source URIs back to standard **`file://`** URIs pointing directly to the local cache directory (`~/.cache/lathe/`).

To preserve the read-only behavior and prevent swap files, use a combination of **OS-level file permissions** and **editor-level path-based autocommands**:
1. **OS Read-Only Permissions**: All extracted source files inside `~/.cache/lathe/` will be marked as read-only at the filesystem level (`r--r--r--` / `444`).
2. **Deletion of read-only files**: `FileUtil.deleteDir()` will make files writable before deleting, so cache re-extraction works correctly.
3. **Editor Autocommands**: Editors will listen to path patterns matching the cache (`~/.cache/lathe/**`) to disable swap files and mark buffers as read-only.

---

## Code Inventory

### URI Producers (server → editor)

| File | Method | What it does |
|------|--------|-------------|
| `SourceAnalysisSession.java` ~L202 | `resolveExternalLocation()` | Constructs `Location` with `LatheUri.fromPath(file)` for go-to-definition on external types |
| `ExternalCompiler.java` ~L53 | `definition()` | Round-trips: `LatheUri.fromPath()` → `LatheUri.toPath()` → read file → return `Location` |

### URI Consumers (editor → server)

| File | Method | Callers |
|------|--------|---------|
| `WorkspaceSession.java` ~L796 | `toPath()` wrapper | ~10 LSP handlers (didOpen, didClose, definition, references, completion, etc.) |
| `ReferenceCandidatePlanner.java` ~L95 | `toPath()` wrapper | `planCandidates()` for package filtering |

### File Extraction (where to add read-only permissions)

| File | Method | Write Operation |
|------|--------|----------------|
| `FileUtil.java` L108 | `extractZipEntry()` | `Files.copy(in, target, REPLACE_EXISTING)` — the single write point for all extracted source files |
| `ZipCache.java` L17–22 | `extract()` | Orchestrates: unzip to temp → hook (marker) → delete old → atomic move |
| `FileUtil.java` L65–72 | `deleteDir()` | Deletes old cache dir before replacing — must handle read-only files |

### Neovim Plugin

| File | Lines | Action |
|------|-------|--------|
| `lathe.lua` L19 | `local SCHEME = 'lathe-source://'` | Remove |
| `lathe.lua` L25–43 | `open_lathe_source()` function | Remove |
| `lathe.lua` L97–100 | `BufReadCmd` autocommand | Replace with `BufReadPre` + `BufReadPost` |
| `lathe.lua` L21–23 | `cache_root()` | Keep (needed for root_dir, launcher, new autocmds) |
| `lathe.lua` L53–75 | `root_dir()` | Keep as-is (becomes the primary auto-attach mechanism for external sources) |
| `lathe.lua` L80–95 | format-on-save | Keep as-is (no URI dependency) |

### Tests

| File | Lines | Change |
|------|-------|--------|
| `ExternalCompilerTest.java` | L65, L108 | Change 2 assertions from `"lathe-source://"` to `file://` URIs |

---

## Implementation Plan

### Step 1: Handle read-only files in `FileUtil.deleteDir()`

Before adding read-only permissions to extracted files, `deleteDir()` must be able to
delete them during cache re-extraction.

**File:** `lathe-core/.../FileUtil.java` L65–72

```diff
   public static void deleteDir(final Path dir) throws IOException {
     try (final var walk = Files.walk(dir)) {
       walk.sorted(Comparator.reverseOrder())
-          .forEach(path -> IOUtil.unchecked(() -> Files.delete(path)));
+          .forEach(path -> IOUtil.unchecked(() -> {
+            path.toFile().setWritable(true);
+            Files.delete(path);
+          }));
     } catch (final UncheckedIOException e) {
       throw e.getCause();
     }
   }
```

### Step 2: Set read-only on extracted files in `FileUtil.extractZipEntry()`

**File:** `lathe-core/.../FileUtil.java` L95–109

```diff
   private static void extractZipEntry(
       final Path destDir, final ZipInputStream in, final ZipEntry entry) throws IOException {
     final Path target = destDir.resolve(entry.getName()).normalize();
     if (!target.startsWith(destDir)) {
       throw new IOException("zip contains unsafe path " + entry.getName());
     }

     if (entry.isDirectory()) {
       Files.createDirectories(target);
       return;
     }

     Files.createDirectories(target.getParent());
     Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
+    target.toFile().setReadOnly();
   }
```

### Step 3: Simplify `LatheUri.java`

Remove the custom scheme constant and simplify both methods.

**File:** `lathe-server/.../LatheUri.java`

```diff
-/** URI utilities for the {@code lathe-source://} custom scheme. */
+/** URI utilities for converting between LSP URIs and filesystem paths. */
 public final class LatheUri {
-
-  public static final String SCHEME = "lathe-source://";

   private LatheUri() {}

   public static String fromPath(final Path path) {
-    return SCHEME + path;
+    return path.toUri().toString();
   }

   public static Path toPath(final String uri) {
-    if (uri.startsWith(SCHEME)) {
-      return Path.of(uri.substring(SCHEME.length()));
-    }
     return Path.of(URI.create(uri));
   }
 }
```

### Step 4: Simplify `ExternalCompiler.definition()`

Remove the pointless round-trip (path → URI → path → read → URI). Work with
the `Path` directly and only construct the URI for the final `Location`.

**File:** `lathe-server/.../ExternalCompiler.java` ~L44–72

```diff
-    final var uri = LatheUri.fromPath(
-        DefinitionLocator.findSourceFile(sourceRoot, element.qualifiedName(), element.name()));
-    if (uri == null) {
+    final var file =
+        DefinitionLocator.findSourceFile(sourceRoot, element.qualifiedName(), element.name());
+    if (file == null) {
       return Optional.empty();
     }
-    final var filePath = LatheUri.toPath(uri);
-    final var lines = readLines(filePath);
+    final var lines = readLines(file);
     if (lines.isEmpty()) {
       return Optional.empty();
     }
     final var position =
         DefinitionLocator.findDefinitionPosition(lines, element.name(), element.kind());
-    return Optional.of(new Location(uri, new Range(position, position)));
+    return Optional.of(new Location(LatheUri.fromPath(file), new Range(position, position)));
```

### Step 5: Update `ExternalCompilerTest.java`

Change the 2 test assertions from expecting `lathe-source://` to expecting standard
`file://` URIs.

**File:** `lathe-server/.../ExternalCompilerTest.java` L65, L108

```diff
-    assertThat(definition.get().getUri()).isEqualTo("lathe-source://" + helperSource);
+    assertThat(definition.get().getUri()).isEqualTo(helperSource.toUri().toString());
```

### Step 6: Update Neovim plugin (`lathe.lua`)

Remove custom scheme handling. Add path-based autocommands.

**Remove** lines 19, 25–43 (`SCHEME` constant and `open_lathe_source()` function):

```diff
-local SCHEME = 'lathe-source://'
-
-local function open_lathe_source(args)
-  local uri = args.match
-  local path = uri:sub(#SCHEME + 1)
-  local buf = args.buf
-
-  vim.bo[buf].swapfile = false
-  vim.bo[buf].buftype = 'nofile'
-  vim.bo[buf].modifiable = true
-  vim.api.nvim_buf_set_lines(buf, 0, -1, false, vim.fn.readfile(path))
-  vim.bo[buf].modifiable = false
-  vim.bo[buf].filetype = 'java'
-
-  for _, client in ipairs(vim.lsp.get_clients({ name = 'lathe' })) do
-    if not vim.lsp.buf_is_attached(buf, client.id) then
-      vim.lsp.buf_attach_client(buf, client.id)
-    end
-    break
-  end
-end
```

**Replace** `BufReadCmd` (lines 97–100) with standard autocommands:

```diff
-  vim.api.nvim_create_autocmd('BufReadCmd', {
-    pattern = SCHEME .. '*',
-    callback = open_lathe_source,
-  })
+  local cache_pattern = root .. '/**'
+  vim.api.nvim_create_autocmd('BufReadPre', {
+    pattern = cache_pattern,
+    callback = function(ev)
+      vim.bo[ev.buf].swapfile = false
+    end,
+  })
+  vim.api.nvim_create_autocmd('BufReadPost', {
+    pattern = cache_pattern,
+    callback = function(ev)
+      vim.bo[ev.buf].readonly = true
+      vim.bo[ev.buf].modifiable = false
+    end,
+  })
```

### Step 7 (optional): Add Telescope path shortener to `lathe.lua`

Export a `M.path_display` helper that collapses the long cache path in Telescope
search results to `[lathe]/...`:

```lua
function M.path_display(opts, path)
  local root = cache_root()
  if vim.startswith(path, root) then
    local tail = vim.fs.basename(path)
    local parent = "[lathe]/" .. vim.fs.dirname(path):sub(#root + 2)
    return string.format("%s (%s)", tail, parent)
  end
  local tail = vim.fs.basename(path)
  local cwd = vim.fn.getcwd()
  local parent = vim.startswith(path, cwd)
    and vim.fs.dirname(path):sub(#cwd + 2)
    or vim.fs.dirname(path)
  return string.format("%s (%s)", tail, parent)
end
```

Users enable it in their Telescope config:

```lua
require("telescope").setup({
  defaults = {
    path_display = require("lathe").path_display,
  }
})
```

### Step 8: Run tests

```bash
mvn test -pl lathe-core
mvn test -pl lathe-server
mvn test -pl lathe-maven-plugin
```

### Step 9: Update design documents

Mark `lathe-source-uri-scheme.md` as superseded by this document.

---

## Commit Sequence

| # | Scope | Message |
|---|-------|---------|
| 1 | `lathe-core` | `feat: mark extracted source files as read-only, handle in deleteDir` |
| 2 | `lathe-server` | `refactor: replace lathe-source:// with standard file:// URIs` |
| 3 | `neovim` | `refactor: drop custom URI scheme, use path-based autocommands` |
| 4 | `docs` | `docs: update design docs for file URI migration` |

---

## VS Code Client

- **Zero Config**: VS Code natively opens standard `file://` URIs. Auto-completion, navigation, and hover features work out of the box without any custom `TextDocumentContentProvider`.
- **Read-Only Lock**: Since the files are marked `r--r--r--` at the OS level, VS Code will automatically display a padlock icon and prevent the user from typing.

## What is eliminated

Compared to the current `lathe-source://` approach, the following client-side complexity disappears:

| Current | Replaced by |
|---|---|
| `SCHEME` constant | not needed |
| `open_lathe_source()` function (19 lines) | not needed |
| `BufReadCmd` autocmd | `BufReadPre` + `BufReadPost` (standard, 10 lines) |
| Manual `buf_attach_client` for external sources | `root_dir` fallback (already exists, now works correctly) |
| `buftype=nofile` virtual buffer workaround | standard file buffer (Neovim reads file natively) |
| VS Code `TextDocumentContentProvider` | not needed |
