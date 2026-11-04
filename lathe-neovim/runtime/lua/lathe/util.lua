-- Helpers the client's modules share, so each says one thing one way.
local M = {}

--- Notify the user, every message prefixed and titled alike.
function M.notify(message, level)
  vim.notify("Lathe: " .. message, level, { title = "Lathe" })
end

--- The Lathe client attached to `bufnr`, or with no buffer the first Lathe client: a run's console
--- or a stack trace is no Java buffer, yet still has the one workspace's server to ask.
function M.client(bufnr)
  return vim.lsp.get_clients({ name = "lathe", bufnr = bufnr })[1]
end

--- Run a server command; `on_result` gets its result, unless the server answers an error, which is
--- notified (at `opts.level`, ERROR by default) instead.
function M.exec(client, bufnr, command, arguments, on_result, opts)
  local level = (opts and opts.level) or vim.log.levels.ERROR
  client:request("workspace/executeCommand", {
    command = command,
    arguments = arguments,
  }, function(err, result)
    if err then
      M.notify(err.message, level)
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
