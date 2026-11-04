-- :LatheTypeHierarchy -- the full type hierarchy of the type under the cursor (all transitive
-- supertypes up to Object + all transitive subtypes) in one flat, fuzzy-searchable picker, via the
-- server's `lathe.typeHierarchy` command. Each row is tagged by its relation to the anchor. Uses
-- Telescope when installed, otherwise the built-in `lathe.pick` fuzzy picker.

local M = {}

local GLYPH = { supertype = "▲", ["self"] = "●", subtype = "▼" }

local util = require("lathe.util")
local notify = util.notify

-- A server TypeHierarchyItem + its relation -> a picker entry. Fuzzy-matched on the fully-qualified
-- name (`ordinal`); the tag glyph and package are display-only. `filename`/`lnum`/`col` drive
-- Telescope's preview, `location` the jump on a pick.
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

--- Request the full hierarchy of the type at the cursor and open it in a picker.
function M.show(bufnr)
  bufnr = bufnr or vim.api.nvim_get_current_buf()
  local client = util.client(bufnr)
  if not client then
    notify("server not attached", vim.log.levels.WARN)
    return
  end

  local params = vim.lsp.util.make_position_params(0, client.offset_encoding)
  util.exec(client, bufnr, "lathe.typeHierarchy", { params }, function(result)
    if not result or not result.self then
      notify("no type under the cursor", vim.log.levels.INFO)
      return
    end
    if result.truncated then
      notify("subtype list truncated -- too many subtypes", vim.log.levels.WARN)
    end

    local entries = build_entries(result)
    util.pick(entries, {
      title = "Lathe: type hierarchy -- " .. result.self.name,
      preview_locations = true,
      on_choice = function(entry)
        if entry.location then
          vim.lsp.util.show_document(entry.location, client.offset_encoding, { focus = true })
        end
      end,
    })
  end)
end

function M.setup()
  vim.api.nvim_create_user_command("LatheTypeHierarchy", function()
    M.show(vim.api.nvim_get_current_buf())
  end, { desc = "Lathe: full type hierarchy of the type under the cursor" })
end

return M
