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
      error(failure)
    end
  end, bufnr)
end

return M
