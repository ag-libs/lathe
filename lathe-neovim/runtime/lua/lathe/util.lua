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

return M
