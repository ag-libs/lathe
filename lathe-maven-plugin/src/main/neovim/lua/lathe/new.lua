-- :LatheNew -- scaffold a new class / interface / record / enum next to the current file (same
-- package) and open it, so a new type lands in the right place without hand-editing the package line
-- or creating directories. Style is deferred to the on-save formatter: when Lathe's Google formatter
-- is enabled the scaffold is normalised through the same `vim.lsp.buf.format` path a save uses (so it
-- is byte-identical to a save, and we never hardcode indentation), otherwise a minimal built-in
-- skeleton is left as written.
--
-- The placement/skeleton/caret logic is pure (`_package_*`, `_skeleton`, `_caret`) and unit-tested;
-- `create()` is the thin buffer/file-IO orchestrator that also drives the kind/name prompts.

local M = {}

local KINDS = { "class", "interface", "record", "enum" }

-- Set from setup(); mirrors the same `formatter == 'google' and format_on_save` gate lathe.lua uses
-- to wire the BufWritePre formatter, so the scaffold matches what a save would produce.
M._format_on_save = false

--- The package declared by a file, from its first `package x.y.z;` line, else nil.
function M._package_from_lines(lines)
  for _, line in ipairs(lines) do
    local pkg = line:match("^%s*package%s+([%w_.]+)%s*;")
    if pkg then
      return pkg
    end
  end
  return nil
end

--- The package for a directory, derived by splitting the path on the Maven source-root marker
--- (`/src/main/java/` or `/src/test/java/`) and dot-joining the trailing segments. Returns "" for a
--- directory that is exactly the source root (default package), or nil when the path is not under a
--- recognised source root.
function M._package_from_dir(dir)
  local rel = dir:match("/src/main/java/(.+)$") or dir:match("/src/test/java/(.+)$")
  if rel then
    return (rel:gsub("/+$", ""):gsub("/", "."))
  end
  if dir:match("/src/main/java/?$") or dir:match("/src/test/java/?$") then
    return ""
  end
  return nil
end

--- Split a possibly package-qualified name: `com.example.Foo` -> `com.example`, `Foo`; a bare `Foo`
--- -> nil, `Foo`.
function M._split_qualified(input)
  local package, name = input:match("^(.+)%.([%w_]+)$")
  if package then
    return package, name
  end
  return nil, input
end

--- The module source root containing `dir` -- the path up to and including the `/src/main/java` or
--- `/src/test/java` marker (the same marker `_package_from_dir` keys on), else nil.
function M._source_root(dir)
  return dir:match("^(.-/src/main/java)") or dir:match("^(.-/src/test/java)")
end

--- Resolve the final `(dir, package, name)` for the entered `name` given the current context. A bare
--- name stays in the context package (v1); a dotted name is placed under the module source root at its
--- package path (v2, directories made by the writer). Returns nil when a qualified name cannot be
--- placed -- the current context is not under a `src/main|test/java` root.
function M._target(context_dir, context_package, name)
  local package, simple = M._split_qualified(name)
  if not package then
    return context_dir, context_package, name
  end

  local root = M._source_root(context_dir)
  if not root then
    return nil
  end

  return root .. "/" .. package:gsub("%.", "/"), package, simple
end

--- The scaffold as a list of lines. Minimal visibility (`public`, no `final`/`sealed`); the on-save
--- formatter fixes indentation/spacing. class/interface/enum get a blank body line (caret target);
--- record gets an empty `()` component list (caret target). The `package` line is omitted for the
--- default package.
function M._skeleton(kind, name, package)
  local lines = {}
  if package and package ~= "" then
    table.insert(lines, "package " .. package .. ";")
    table.insert(lines, "")
  end
  if kind == "record" then
    table.insert(lines, "public record " .. name .. "() {")
    table.insert(lines, "}")
  else
    table.insert(lines, "public " .. kind .. " " .. name .. " {")
    table.insert(lines, "")
    table.insert(lines, "}")
  end
  return lines
end

--- The caret position `{row (1-based), col (0-based)}` for `nvim_win_set_cursor`, resolved over the
--- (possibly formatter-reflowed) buffer lines: inside the record component list, else on the blank
--- body line when present, else immediately after the opening brace.
function M._caret(kind, name, lines)
  for i = 1, #lines do
    local line = lines[i]
    if kind == "record" then
      local at = line:find("record " .. name, 1, true)
      local paren = at and line:find("(", at, true)
      if paren then
        return { i, paren }
      end
    else
      local at = line:find(kind .. " " .. name, 1, true)
      local open = at and line:find("{", at, true)
      if open then
        local body = lines[i + 1]
        local closing = lines[i + 2]
        if body and body:match("^%s*$") and closing and closing:find("}", 1, true) then
          return { i + 1, #body }
        end
        return { i, open }
      end
    end
  end
  return { 1, 0 }
end

--- The directory of an oil/netrw directory buffer, else nil.
local function directory_buffer_path(buf, bufname)
  if bufname ~= "" and vim.fn.isdirectory(bufname) == 1 then
    return bufname
  end

  local ok_oil, oil = pcall(require, "oil")
  if ok_oil and bufname:match("^oil://") then
    return oil.get_current_dir()
  end

  local ok, curdir = pcall(vim.api.nvim_buf_get_var, buf, "netrw_curdir")
  if ok and type(curdir) == "string" and curdir ~= "" then
    return curdir
  end

  return nil
end

--- Resolve the target directory and package from the current buffer: a directory buffer targets that
--- directory; a `.java` file targets its own directory (same package). Returns nil when there is no
--- usable file/directory context.
function M._resolve_context(buf, bufname)
  local dir = directory_buffer_path(buf, bufname)
  if dir then
    return dir, M._package_from_dir(dir) or ""
  end

  if bufname:match("%.java$") then
    local file_dir = vim.fs.dirname(bufname)
    local lines = vim.api.nvim_buf_get_lines(buf, 0, 40, false)
    local package = M._package_from_lines(lines) or M._package_from_dir(file_dir) or ""
    return file_dir, package
  end

  return nil
end

local function warn(message)
  vim.notify("[lathe] :LatheNew — " .. message, vim.log.levels.WARN)
end

function M._write_and_open(dir, package, kind, name)
  local path = dir .. "/" .. name .. ".java"
  if vim.fn.filereadable(path) == 1 then
    warn(name .. ".java already exists")
    return
  end

  vim.fn.mkdir(dir, "p")
  vim.fn.writefile(M._skeleton(kind, name, package), path)
  vim.cmd.edit(vim.fn.fnameescape(path))

  local buf = vim.api.nvim_get_current_buf()
  M._format(buf)

  local lines = vim.api.nvim_buf_get_lines(buf, 0, -1, false)
  pcall(vim.api.nvim_win_set_cursor, 0, M._caret(kind, name, lines))
end

--- Normalise the freshly-opened scaffold through Lathe's formatter, matching a save. The Lathe client
--- attaches to the new buffer asynchronously, so wait briefly for it before formatting -- otherwise
--- `vim.lsp.buf.format` runs before attach and no-ops ("no matching language servers"). Only waits
--- when a Lathe client is actually running, so no-server setups fall through to the built-in skeleton
--- without a delay.
function M._format(buf)
  if not M._format_on_save or #vim.lsp.get_clients({ name = "lathe" }) == 0 then
    return
  end

  vim.wait(2000, function()
    return #vim.lsp.get_clients({ name = "lathe", bufnr = buf }) > 0
  end, 25)
  pcall(vim.lsp.buf.format, { bufnr = buf, name = "lathe", async = false })
end

function M.create()
  local buf = vim.api.nvim_get_current_buf()
  local dir, package = M._resolve_context(buf, vim.api.nvim_buf_get_name(buf))
  if not dir then
    warn("open a file or directory inside a source package first")
    return
  end

  vim.ui.select(KINDS, { prompt = "New Java type" }, function(kind)
    if not kind then
      return
    end
    vim.ui.input({ prompt = kind .. " name: " }, function(name)
      if not name or name == "" then
        return
      end
      local target_dir, target_package, target_name = M._target(dir, package, name)
      if not target_dir then
        warn("cannot place a package-qualified name here — no src/main|test/java root")
        return
      end
      M._write_and_open(target_dir, target_package, kind, target_name)
    end)
  end)
end

function M.setup(opts)
  opts = opts or {}
  M._format_on_save = opts.format_on_save == true
  vim.api.nvim_create_user_command("LatheNew", function()
    M.create()
  end, { desc = "Lathe: create a new class/interface/record/enum in the current package" })
end

return M
