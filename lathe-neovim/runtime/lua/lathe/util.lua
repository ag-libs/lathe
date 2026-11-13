-- Helpers the client's modules share, so each says one thing one way.
local M = {}

--- Notify the user, every message prefixed and titled alike.
function M.notify(message, level)
  vim.notify("Lathe: " .. message, level, { title = "Lathe" })
end

--- The Lathe client attached to `bufnr`, or nil. With no buffer: the current buffer's client, so a
--- workspace-wide command asks that workspace's server when several are open; else the first Lathe
--- client, as a run's console or a stack trace is no Java buffer yet has a server to ask.
function M.client(bufnr)
  if bufnr then
    return vim.lsp.get_clients({ name = "lathe", bufnr = bufnr })[1]
  end

  return vim.lsp.get_clients({ name = "lathe", bufnr = vim.api.nvim_get_current_buf() })[1]
    or vim.lsp.get_clients({ name = "lathe" })[1]
end

--- Run a server command (a name from lathe.commands); `on_result`, when given, gets its result. An
--- error the server answers is notified (at `opts.level`, ERROR by default), dropped with
--- `opts.silent`, or handed to `opts.on_error(err)` instead.
function M.exec(client, bufnr, command, arguments, on_result, opts)
  opts = opts or {}
  client:request("workspace/executeCommand", {
    command = command,
    arguments = arguments,
  }, function(err, result)
    if err then
      if opts.on_error then
        opts.on_error(err)
      elseif not opts.silent then
        M.notify(err.message, opts.level or vim.log.levels.ERROR)
      end
      return
    end

    if not on_result then
      return
    end

    -- A picker opened here runs inside this LSP response callback (a vim.schedule task), so a
    -- Ctrl-C at a vim.ui prompt raises inputlist's keyboard interrupt here instead of cancelling a
    -- command -- otherwise an unhandled "vim.schedule callback: Keyboard interrupt". Swallow only
    -- that interrupt; a genuine error still surfaces.
    local ok, failure = pcall(on_result, result)
    if not ok and not tostring(failure):match("[Ii]nterrupt") then
      error(failure, 0) -- rethrown as raised, not as raised here
    end
  end, bufnr)
end

--- Run a server command from a nio task (neotest's async context): returns `err, result`, as nio's
--- client request does.
function M.exec_nio(client, command, arguments, bufnr)
  return client.request.workspace_executeCommand({
    command = command,
    arguments = arguments,
  }, bufnr)
end

--- Let the user pick one of `entries` (each with a `display`, and an `ordinal` to match): in
--- Telescope when it is installed, else lathe.pick. opts: `title`, `on_choice(entry)`,
--- `default_text` (Telescope's prefilled prompt) and `preview_locations` (Telescope previews each
--- entry's filename and lnum).
function M.pick(entries, opts)
  if not pcall(require, "telescope.pickers") then
    require("lathe.pick").pick({
      items = entries,
      format = function(entry)
        return entry.display
      end,
      title = opts.title,
      on_choice = function(entry)
        if entry then
          opts.on_choice(entry)
        end
      end,
    })
    return
  end

  local conf = require("telescope.config").values
  local actions = require("telescope.actions")
  local action_state = require("telescope.actions.state")
  require("telescope.pickers")
    .new({}, {
      prompt_title = opts.title,
      default_text = opts.default_text,
      finder = require("telescope.finders").new_table({
        results = entries,
        entry_maker = function(entry)
          entry.value = entry
          return entry
        end,
      }),
      sorter = conf.generic_sorter({}),
      previewer = opts.preview_locations and conf.qflist_previewer({}) or nil,
      -- Only <CR> is replaced: Telescope's split and tab selections keep working.
      attach_mappings = function(prompt_bufnr)
        actions.select_default:replace(function()
          local selected = action_state.get_selected_entry()
          actions.close(prompt_bufnr)
          if selected then
            opts.on_choice(selected)
          end
        end)
        return true
      end,
    })
    :find()
end

return M
