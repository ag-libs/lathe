-- :LatheInstances -- list the instantiation sites (`new XXX(...)`) of the type under the cursor in the
-- quickfix, via the server's `lathe.instantiations` command. The server returns only new-instance
-- locations, so there is nothing to filter or render specially -- a plain location list into the
-- quickfix, picker-agnostic.

local M = {}

local function notify(message, level)
  vim.notify("Lathe: " .. message, level, { title = "Lathe" })
end

--- Request the instantiation sites of the type at the cursor and open them in the quickfix.
function M.find(bufnr)
  bufnr = bufnr or vim.api.nvim_get_current_buf()
  local client = vim.lsp.get_clients({ name = "lathe", bufnr = bufnr })[1]
  if not client then
    notify("server not attached", vim.log.levels.WARN)
    return
  end

  local params = vim.lsp.util.make_position_params(0, client.offset_encoding)
  client:request("workspace/executeCommand", {
    command = "lathe.instantiations",
    arguments = { params },
  }, function(err, locations)
    if err then
      notify(err.message, vim.log.levels.ERROR)
      return
    end
    if not locations or vim.tbl_isempty(locations) then
      notify("no instantiation sites found", vim.log.levels.INFO)
      return
    end
    vim.fn.setqflist({}, " ", {
      title = "Lathe: instantiation sites",
      items = vim.lsp.util.locations_to_items(locations, client.offset_encoding),
    })
    vim.cmd("botright copen")
  end, bufnr)
end

function M.setup()
  vim.api.nvim_create_user_command("LatheInstances", function()
    M.find(vim.api.nvim_get_current_buf())
  end, { desc = "Lathe: list instantiation sites of the type under the cursor" })
end

return M
