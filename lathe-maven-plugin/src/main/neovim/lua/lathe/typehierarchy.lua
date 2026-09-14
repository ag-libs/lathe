-- :LatheTypeHierarchy -- the full type hierarchy of the type under the cursor (all transitive
-- supertypes up to Object + all transitive subtypes) in one flat, fuzzy-searchable picker, via the
-- server's `lathe.typeHierarchy` command. Each row is tagged by its relation to the anchor. Uses
-- Telescope when installed, otherwise the built-in `lathe.pick` fuzzy picker.

local M = {}

local GLYPH = { supertype = "▲", ["self"] = "●", subtype = "▼" }

local function notify(message, level)
  vim.notify("Lathe: " .. message, level, { title = "Lathe" })
end

-- A server TypeHierarchyItem + its relation -> a picker entry. Fuzzy-matched on the fully-qualified
-- name (`ordinal`); the tag glyph and package are display-only. `filename`/`lnum`/`col` drive both
-- Telescope's preview + default-select and the fallback jump.
local function make_entry(item, relation)
  local pkg = item.detail or ""
  local fqn = pkg ~= "" and (pkg .. "." .. item.name) or item.name
  local entry = {
    display = string.format("%s %s  %s", GLYPH[relation], item.name, pkg),
    ordinal = fqn,
  }
  if item.uri then
    local start = item.range and item.range.start or { line = 0, character = 0 }
    entry.filename = vim.uri_to_fname(item.uri)
    entry.lnum = start.line + 1
    entry.col = start.character + 1
    entry.location = { uri = item.uri, range = item.range }
  end
  return entry
end

local function build_entries(result)
  local entries = {}
  for _, item in ipairs(result.supertypes or {}) do
    entries[#entries + 1] = make_entry(item, "supertype")
  end
  entries[#entries + 1] = make_entry(result.self, "self")
  for _, item in ipairs(result.subtypes or {}) do
    entries[#entries + 1] = make_entry(item, "subtype")
  end
  return entries
end

local function open_telescope(entries, title)
  local pickers = require("telescope.pickers")
  local finders = require("telescope.finders")
  local conf = require("telescope.config").values

  pickers
    .new({}, {
      prompt_title = title,
      finder = finders.new_table({
        results = entries,
        entry_maker = function(entry)
          entry.value = entry
          return entry
        end,
      }),
      sorter = conf.generic_sorter({}),
      previewer = conf.qflist_previewer({}),
    })
    :find()
end

local function open_fallback(entries, title, offset_encoding)
  require("lathe.pick").pick({
    items = entries,
    format = function(entry)
      return entry.display
    end,
    title = title,
    on_choice = function(entry)
      if entry and entry.location then
        vim.lsp.util.show_document(entry.location, offset_encoding, { focus = true })
      end
    end,
  })
end

--- Request the full hierarchy of the type at the cursor and open it in a picker.
function M.show(bufnr)
  bufnr = bufnr or vim.api.nvim_get_current_buf()
  local client = vim.lsp.get_clients({ name = "lathe", bufnr = bufnr })[1]
  if not client then
    notify("server not attached", vim.log.levels.WARN)
    return
  end

  local params = vim.lsp.util.make_position_params(0, client.offset_encoding)
  client:request("workspace/executeCommand", {
    command = "lathe.typeHierarchy",
    arguments = { params },
  }, function(err, result)
    if err then
      notify(err.message, vim.log.levels.ERROR)
      return
    end
    if not result or not result.self then
      notify("no type under the cursor", vim.log.levels.INFO)
      return
    end
    if result.truncated then
      notify("subtype list truncated -- too many subtypes", vim.log.levels.WARN)
    end

    local entries = build_entries(result)
    local title = "Lathe: type hierarchy -- " .. result.self.name
    if pcall(require, "telescope.pickers") then
      open_telescope(entries, title)
    else
      open_fallback(entries, title, client.offset_encoding)
    end
  end, bufnr)
end

function M.setup()
  vim.api.nvim_create_user_command("LatheTypeHierarchy", function()
    M.show(vim.api.nvim_get_current_buf())
  end, { desc = "Lathe: full type hierarchy of the type under the cursor" })
end

return M
