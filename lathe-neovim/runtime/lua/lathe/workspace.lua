-- Where a buffer's Lathe workspace is: its root (the directory holding `.lathe/`), the launcher
-- that starts its server, and the cache that holds dependency and JDK sources.
local M = {}

--- Marker file identifying a workspace root, exported so other plugins that
--- need to detect a Lathe workspace (e.g. project pickers) can reference the
--- same name instead of hard-coding it separately.
M.ROOT_MARKER = ".lathe"

function M.cache_root()
  local root = vim.fs.normalize(vim.env.LATHE_CACHE or (vim.fn.expand("~") .. "/.cache/lathe"))
  -- Buffer names are symlink-resolved, so prefix checks against them need the resolved cache dir.
  return vim.uv.fs_realpath(root) or root
end

--- Absolute path to the server launcher this client execs. Honors the
--- LATHE_SERVER_DIR dev override (a built server version directory, e.g. a
--- SNAPSHOT under the cache) so a working-tree server can be run without a
--- build; otherwise resolves the per-workspace launcher that `lathe:sync` links
--- at <root>/.lathe/, pinned to the server version this project uses. Mirrors
--- LATHE_NVIM_DIR for the Lua client.
---@param root string? workspace root; required unless LATHE_SERVER_DIR is set
function M.launcher_path(root)
  local override = vim.env.LATHE_SERVER_DIR
  if override then
    return vim.fs.normalize(override) .. "/lathe-launcher.sh"
  end
  return vim.fs.normalize(root) .. "/" .. M.ROOT_MARKER .. "/lathe-launcher.sh"
end

-- The last root a buffer resolved to, for a cache buffer that has no marker of its own.
local last_root = nil

--- Resolve the workspace root for a buffer, for any code (this plugin's own
--- root_dir included) that needs to know which project a buffer belongs to.
---
--- Walks up from the buffer's path looking for `M.ROOT_MARKER`. If the buffer
--- lives inside the Lathe cache instead (decompiled/dependency sources have no
--- marker of their own), falls back to the last resolved root, then to
--- scanning other open buffers for one that does resolve. Memoizes the result
--- in `last_root` so repeated lookups from cache-only buffers stay stable.
---@param bufnr integer? defaults to the current buffer
---@return string? root
function M.get_root(bufnr)
  local fname = vim.api.nvim_buf_get_name(bufnr or 0)
  local root = vim.fs.root(fname, M.ROOT_MARKER)
  if root then
    last_root = root
    return root
  end

  if not vim.startswith(fname, M.cache_root()) then
    return nil
  end

  if last_root then
    return last_root
  end

  for _, buf in ipairs(vim.api.nvim_list_bufs()) do
    local bname = vim.api.nvim_buf_get_name(buf)
    if bname ~= "" then
      root = vim.fs.root(bname, M.ROOT_MARKER)
      if root then
        last_root = root
        return root
      end
    end
  end

  return nil
end

--- A dependency or JDK source in the cache is read-only, and needs no swap file.
function M.setup()
  local augroup = vim.api.nvim_create_augroup("LatheWorkspace", { clear = true })
  local cache_pattern = M.cache_root() .. "/**"
  vim.api.nvim_create_autocmd("BufReadPre", {
    group = augroup,
    pattern = cache_pattern,
    callback = function(ev)
      vim.bo[ev.buf].swapfile = false
    end,
  })
  vim.api.nvim_create_autocmd("BufReadPost", {
    group = augroup,
    pattern = cache_pattern,
    callback = function(ev)
      vim.bo[ev.buf].readonly = true
      vim.bo[ev.buf].modifiable = false
    end,
  })
end

return M
