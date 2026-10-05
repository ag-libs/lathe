-- :LatheRename -- rename the symbol under the cursor like the built-in `grn`, but persist the result.
--
-- Neovim applies a rename WorkspaceEdit to in-memory buffers and writes only its resource operations
-- (the file rename) to disk, so the edited reference files stay unsaved -- the user must `:wa`. Worse,
-- when the renamed declaration is the open buffer, Neovim leaves the old-named buffer behind; a later
-- `:w`/`:wa` then rewrites the old path and resurrects a duplicate declaration. This wrapper applies
-- the edit, writes every touched buffer (without firing format-on-save across all of them), and
-- retires the renamed file's stale buffer and path -- a one-step, saved rename.

local M = {}

local function notify(message, level)
  vim.notify("Lathe: " .. message, level, { title = "Lathe" })
end

-- The URIs a WorkspaceEdit touches with text edits, and its file-rename operations.
local function dissect(edit)
  local uris, renames = {}, {}
  if edit.documentChanges then
    for _, change in ipairs(edit.documentChanges) do
      if change.kind == "rename" then
        table.insert(renames, { old = change.oldUri, new = change.newUri })
      elseif change.textDocument then
        uris[change.textDocument.uri] = true
      end
    end
  elseif edit.changes then
    for uri in pairs(edit.changes) do
      uris[uri] = true
    end
  end
  return uris, renames
end

-- An already-loaded buffer for this URI, or nil -- never creates one (unlike vim.uri_to_bufnr).
local function loaded_buffer(uri)
  local path = vim.uri_to_fname(uri)
  for _, buf in ipairs(vim.api.nvim_list_bufs()) do
    if vim.api.nvim_buf_is_loaded(buf) and vim.api.nvim_buf_get_name(buf) == path then
      return buf
    end
  end
  return nil
end

-- Write a loaded, modified buffer to disk (its own path, or `target` to redirect a renamed file).
-- `noautocmd` so these writes do not re-trigger format-on-save on every touched file -- the edit is
-- already the intended content; `keepalt` leaves the alternate-file untouched. No-op for nil or an
-- unmodified buffer, so call sites need no guards of their own.
local function save_buffer(buf, target)
  if not buf or not vim.bo[buf].modified then
    return
  end

  vim.api.nvim_buf_call(buf, function()
    local cmd = "silent noautocmd keepalt write"
    if target then
      cmd = cmd .. "! " .. vim.fn.fnameescape(target)
    end
    vim.cmd(cmd)
  end)
end

-- Persist the applied rename: write every edited buffer, and for a renamed file make the new path
-- authoritative (from the still-open old buffer if Neovim left it behind), then retire the old buffer
-- and old file so nothing can rewrite the old path.
local function persist(edit)
  local uris, renames = dissect(edit)

  for _, r in ipairs(renames) do
    local old_path, new_path = vim.uri_to_fname(r.old), vim.uri_to_fname(r.new)
    local old_buf = loaded_buffer(r.old)
    if old_buf then
      save_buffer(old_buf, new_path)
      vim.bo[old_buf].modified = false
      pcall(vim.api.nvim_buf_delete, old_buf, { force = true })
    end

    if old_path ~= new_path and vim.uv.fs_stat(new_path) and vim.uv.fs_stat(old_path) then
      vim.fn.delete(old_path)
    end

    uris[r.new] = true
  end

  for uri in pairs(uris) do
    save_buffer(loaded_buffer(uri))
  end
end

local function send_rename(client, bufnr, params, new_name)
  params.newName = new_name
  client:request("textDocument/rename", params, function(err, result)
    if err then
      notify(err.message, vim.log.levels.ERROR)
      return
    end
    if not result then
      notify("nothing to rename here", vim.log.levels.WARN)
      return
    end

    vim.lsp.util.apply_workspace_edit(result, client.offset_encoding)
    persist(result)
  end, bufnr)
end

--- Rename the symbol under the cursor; prompts for the new name when one is not supplied.
function M.rename(new_name, bufnr)
  bufnr = bufnr or vim.api.nvim_get_current_buf()
  local client = vim.lsp.get_clients({ name = "lathe", bufnr = bufnr })[1]
  if not client then
    notify("server not attached", vim.log.levels.WARN)
    return
  end

  -- Captured before the async prompt so a cursor move while typing cannot shift the target.
  local params = vim.lsp.util.make_position_params(0, client.offset_encoding)
  if new_name and new_name ~= "" then
    send_rename(client, bufnr, params, new_name)
    return
  end

  local current = vim.fn.expand("<cword>")
  vim.ui.input({ prompt = "Lathe rename: ", default = current }, function(input)
    if input and input ~= "" and input ~= current then
      send_rename(client, bufnr, params, input)
    end
  end)
end

function M.setup()
  vim.api.nvim_create_user_command("LatheRename", function(cmd)
    M.rename(cmd.args)
  end, { nargs = "?", desc = "Lathe: rename the symbol under the cursor and save every edited file" })
end

return M
