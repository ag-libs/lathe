-- :LatheMissingImports -- add an import for every unresolved type in the buffer, via the server's
-- `lathe.missingImports` command. Unambiguous names (one candidate) are added automatically; a name
-- with several candidates is presented one at a time with vim.ui.select so the user picks (or skips).
-- The chosen imports go in with the edits `lathe.importEdits` returns, then a summary is shown. Also
-- wired as the client-side handler for the "Add missing imports…" code action.

local M = {}

local function notify(message, level)
  vim.notify('Lathe: ' .. message, level, { title = 'Lathe' })
end

-- Apply the chosen imports with the edits the server places (each at its sorted spot in its group),
-- then call on_added. A server without lathe.importEdits answers nothing: warn, add nothing.
local function insert_imports(bufnr, client, fqns, on_added)
  if #fqns == 0 then
    on_added()
    return
  end
  client:request('workspace/executeCommand', {
    command = 'lathe.importEdits',
    arguments = { { uri = vim.uri_from_bufnr(bufnr), imports = fqns } },
  }, function(err, edits)
    if err or edits == nil or edits == vim.NIL then
      notify(
        err and err.message or 'this server cannot add imports -- update the Lathe build plugin and sync again',
        vim.log.levels.WARN
      )
      return
    end
    vim.lsp.util.apply_text_edits(edits, bufnr, client.offset_encoding)
    on_added()
  end, bufnr)
end

-- Partition items into auto-add (one candidate), needs-choice (several), and unresolved (none),
-- prompt for the ambiguous ones in sequence, then apply and summarise.
local function resolve(bufnr, client, result)
  local items = result and result.items or {}
  if #items == 0 then
    notify('no missing imports', vim.log.levels.INFO)
    return
  end

  local chosen, ambiguous, unresolved = {}, {}, {}
  local auto, picked = 0, 0
  for _, item in ipairs(items) do
    local candidates = item.candidates or {}
    if #candidates == 0 then
      unresolved[#unresolved + 1] = item.name
    elseif #candidates == 1 then
      chosen[#chosen + 1] = candidates[1]
      auto = auto + 1
    else
      ambiguous[#ambiguous + 1] = item
    end
  end

  local function report()
    local message
    if #chosen == 0 then
      message = 'no imports added'
    elseif picked > 0 then
      message = string.format('added %d imports (%d auto, %d chosen)', #chosen, auto, picked)
    else
      message = string.format('added %d imports', #chosen)
    end
    if #unresolved > 0 then
      message = message .. "; couldn't resolve: " .. table.concat(unresolved, ', ')
    end
    notify(message, vim.log.levels.INFO)
  end

  local function finish()
    insert_imports(bufnr, client, chosen, report)
  end

  local index = 0
  local function prompt_next()
    index = index + 1
    if index > #ambiguous then
      finish()
      return
    end
    local item = ambiguous[index]
    local choices = vim.list_extend({ unpack(item.candidates) }, { 'Skip' })
    vim.ui.select(choices, {
      prompt = string.format('Import %s  (%d/%d)', item.name, index, #ambiguous),
    }, function(choice)
      if choice and choice ~= 'Skip' then
        chosen[#chosen + 1] = choice
        picked = picked + 1
      end
      prompt_next()
    end)
  end

  prompt_next()
end

--- Add imports for every unresolved type in the buffer.
function M.run(bufnr)
  bufnr = bufnr or vim.api.nvim_get_current_buf()
  local client = vim.lsp.get_clients({ name = 'lathe', bufnr = bufnr })[1]
  if not client then
    notify('server not attached', vim.log.levels.WARN)
    return
  end

  client:request('workspace/executeCommand', {
    command = 'lathe.missingImports',
    arguments = { { uri = vim.uri_from_bufnr(bufnr) } },
  }, function(err, result)
    if err then
      notify(err.message, vim.log.levels.ERROR)
      return
    end
    resolve(bufnr, client, result)
  end, bufnr)
end

function M.setup()
  vim.api.nvim_create_user_command('LatheMissingImports', function()
    M.run(vim.api.nvim_get_current_buf())
  end, { desc = 'Lathe: add imports for every unresolved type in the buffer' })

  -- The "Add missing imports…" code action carries this command; intercept it client-side so the
  -- selection wizard runs instead of a bare server round-trip.
  vim.lsp.commands['lathe.missingImports'] = function(_, ctx)
    M.run(ctx.bufnr)
  end
end

return M
