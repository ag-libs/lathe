-- :LatheResourceFind [name] -- find a resource by name across this workspace's reactor and
-- dependency resources, via the server's `lathe.resources` command. Each row shows the name and its
-- origin (reactor:<module> / dep:<gav>); fuzzy matching is case-insensitive and client-side, from
-- Telescope's sorter or the `lathe.pick` fallback. A reactor hit opens its editable file; a
-- dependency hit is extracted read-only on open (`lathe.resourceOpen`, one entry).

local M = {}

local function notify(message, level)
  vim.notify("Lathe: " .. message, level, { title = "Lathe" })
end

-- Server ResourceEntry list -> entries. `ordinal` is "<name>  <origin>" so a query narrows by name
-- or origin; the kind/path/jar/entry fields drive the open. Pure, so it is unit-testable.
function M._entries(result)
  local entries = {}
  for _, r in ipairs(result or {}) do
    local label = r.name .. "  " .. r.origin
    entries[#entries + 1] = {
      display = label,
      ordinal = label,
      kind = r.kind,
      path = r.path,
      jar = r.jar,
      entry = r.entry,
    }
  end
  return entries
end

-- Opens a picked resource: a reactor FILE opens its real editable source; a dependency JAR entry is
-- extracted read-only on the server, then the returned path is opened non-modifiable.
local function open_entry(entry, client)
  if entry.kind == "FILE" then
    vim.cmd.edit(vim.fn.fnameescape(entry.path))
    return
  end

  client:request("workspace/executeCommand", {
    command = "lathe.resourceOpen",
    arguments = { { jar = entry.jar, entry = entry.entry } },
  }, function(err, path)
    if err or not path then
      notify(err and err.message or "could not open resource", vim.log.levels.ERROR)
      return
    end

    vim.schedule(function()
      vim.cmd.edit(vim.fn.fnameescape(path))
      vim.bo.readonly = true
      vim.bo.modifiable = false
    end)
  end)
end

local function open_telescope(entries, query, client)
  local pickers = require("telescope.pickers")
  local finders = require("telescope.finders")
  local conf = require("telescope.config").values
  local actions = require("telescope.actions")
  local action_state = require("telescope.actions.state")

  pickers
    .new({}, {
      prompt_title = "Lathe: resources",
      default_text = query,
      finder = finders.new_table({
        results = entries,
        entry_maker = function(entry)
          entry.value = entry
          return entry
        end,
      }),
      sorter = conf.generic_sorter({}),
      attach_mappings = function(prompt_bufnr)
        actions.select_default:replace(function()
          local selected = action_state.get_selected_entry()
          actions.close(prompt_bufnr)
          if selected then
            open_entry(selected, client)
          end
        end)
        return true
      end,
    })
    :find()
end

local function open_fallback(entries, client)
  require("lathe.pick").pick({
    items = entries,
    format = function(entry)
      return entry.display
    end,
    title = "Lathe: resources",
    on_choice = function(entry)
      if entry then
        open_entry(entry, client)
      end
    end,
  })
end

--- Lists every resource in the workspace (reactor + dependencies) and opens the picked one. `query`
--- prefills the Telescope prompt (the prefill-last-query preference); the fallback starts empty.
function M.find(query)
  local client = vim.lsp.get_clients({ name = "lathe" })[1]
  if not client then
    notify("server not attached (open a Java file first)", vim.log.levels.WARN)
    return
  end

  client:request("workspace/executeCommand", {
    command = "lathe.resources",
    arguments = {},
  }, function(err, result)
    if err then
      notify(err.message, vim.log.levels.ERROR)
      return
    end
    if not result or #result == 0 then
      notify("no resources found", vim.log.levels.INFO)
      return
    end

    local entries = M._entries(result)
    if pcall(require, "telescope.pickers") then
      open_telescope(entries, query, client)
    else
      open_fallback(entries, client)
    end
  end)
end

function M.setup()
  vim.api.nvim_create_user_command("LatheResourceFind", function(opts)
    M.find(opts.args ~= "" and opts.args or nil)
  end, { nargs = "?", desc = "Lathe: find a resource by name (reactor + dependencies)" })
end

return M
