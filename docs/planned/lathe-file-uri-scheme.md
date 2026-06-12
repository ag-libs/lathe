# Lathe — Standard `file://` URIs for External Sources

## Problem

The custom `lathe-source://` URI scheme was originally implemented to prevent swap-file warnings (`E325` in Neovim) and signal to client integrations that dependency/JDK sources are read-only. 

However, custom URI schemes break major parts of the editor ecosystems:
1. **Broken Ecosystem Tooling**: Plugins like Telescope, Trouble, Gitsigns, and Lspsaga fail to preview, search, or highlight files using `lathe-source://` because they expect standard files.
2. **Broken Formatters and Linters**: External command-line tools (e.g. `spotless`, `google-java-format`) crash when passed virtual buffer paths.
3. **Broken Debugger Mappings**: Editor debuggers cannot map source location stack traces back to virtual buffers.
4. **AI Assistant Blindness**: Coding assistants (like GitHub Copilot) ignore non-file schemes, losing all code context when browsing library files.

## Solution

We will revert all external/JDK source URIs back to standard **`file://`** URIs pointing directly to the local cache directory (`~/.cache/lathe/`). 

To preserve the read-only behavior and prevent swap files, we will use a combination of **OS-level file permissions** and **editor-level path-based autocommands**:
1. **OS Read-Only Permissions**: All extracted source files inside `~/.cache/lathe/` will be marked as read-only at the filesystem level (`r--r--r--` / `444`).
2. **Sync Override Logic**: During sync operations, the server/Maven plugin will programmatically unlock files, perform the update, and lock them again.
3. **Editor Autocommands**: Editors will listen to path patterns matching the cache (`~/.cache/lathe/**`) to disable swap files and mark buffers as read-only.

---

## Server Changes

### 1. URI Generation (`LatheUri.java`)
Revert `LatheUri` to generate standard `file://` URIs natively:

```java
package io.github.aglibs.lathe.server;

import java.net.URI;
import java.nio.file.Path;

public final class LatheUri {

  private LatheUri() {}

  public static String fromPath(final Path path) {
    return path.toUri().toString();
  }

  public static Path toPath(final String uri) {
    return Path.of(URI.create(uri));
  }
}
```

### 2. Updating Source Syncing & Overwrite Logic
When extracting library sources from JARs during sync, make the files writable temporarily before writing, and restore read-only status afterward:

```java
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public void writeCacheFile(Path path, String content) throws Exception {
    File file = path.toFile();
    
    // 1. If the file exists, temporarily make it writable so we can overwrite it
    if (file.exists()) {
        file.setWritable(true);
    }
    
    // 2. Write updated source file content
    Files.writeString(path, content);
    
    // 3. Mark the file read-only to prevent user edits
    file.setReadOnly(); // sets file permissions to r--r--r-- (444)
}
```

### 3. Updating Unit Tests
Update tests (such as `ExternalCompilerTest.java`) that assert `lathe-source://` prefixes to verify standard `file://` URIs instead.

---

## Editor Client Changes

### 1. Neovim Plugin (`neovim/lua/lathe.lua`)
Remove the custom `BufReadCmd` handler and the `lathe-source://` interceptor. Add standard `BufReadPre` and `BufReadPost` autocommands matching the cache root to disable swap files and lock buffers:

```lua
function M.setup(opts)
  -- ... standard LSP config setup ...

  -- Prevent swap file creation and lock buffer for external dependencies
  local cache_pattern = root .. '/**'
  
  vim.api.nvim_create_autocmd('BufReadPre', {
    pattern = cache_pattern,
    callback = function(ev)
      vim.bo[ev.buf].swapfile = false
    end,
  })
  
  vim.api.nvim_create_autocmd('BufReadPost', {
    pattern = cache_pattern,
    callback = function(ev)
      vim.bo[ev.buf].readonly = true
      vim.bo[ev.buf].modifiable = false
    end,
  })
end
```

### 2. Telescope Path Shortener (Optional Export)
Export a path formatter helper from the plugin that collapses the long cache path in Telescope search results to `[lathe]/...`:

```lua
function M.path_display(opts, path)
  local root = cache_root()
  if vim.startswith(path, root) then
    local tail = vim.fs.basename(path)
    local parent = "[lathe]/" .. vim.fs.dirname(path):sub(#root + 2)
    return string.format("%s (%s)", tail, parent)
  end
  
  -- Fallback to standard relative path
  local tail = vim.fs.basename(path)
  local cwd = vim.fn.getcwd()
  local parent = vim.startswith(path, cwd) and vim.fs.dirname(path):sub(#cwd + 2) or vim.fs.dirname(path)
  return string.format("%s (%s)", tail, parent)
end
```

### 3. VS Code Client
- **Zero Config**: VS Code natively opens standard `file://` URIs. Auto-completion, navigation, and hover features work out of the box without any custom providers.
- **Read-Only Lock**: Since the files are marked `r--r--r--` at the OS level, VS Code will automatically display a padlock icon and prevent the user from typing.
