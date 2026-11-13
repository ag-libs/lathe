-- Verifies lathe.util.client: with two workspaces open, a command asks the server attached to its
-- buffer (the current one by default), and a buffer no server is attached to (a run's console)
-- still gets a Lathe client. Stubs vim.lsp.get_clients, so no server is started.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-neovim/runtime" \
--     --cmd "set rtp+=lathe-neovim/test" \
--     -l lathe-neovim/test/util_spec.lua

local spec = require("spec_helper").new()
local util = require("lathe.util")

local first = { name = "lathe", id = 1 }
local second = { name = "lathe", id = 2 }
local in_first = vim.api.nvim_create_buf(true, false)
local in_second = vim.api.nvim_create_buf(true, false)
local console = vim.api.nvim_create_buf(false, true)
local attached = { [in_first] = first, [in_second] = second }

vim.lsp.get_clients = function(filter)
  if filter.bufnr == nil then
    return { first, second }
  end

  return { attached[filter.bufnr] }
end

spec.check("given buffer -> its server", util.client(in_second), second)

vim.api.nvim_set_current_buf(in_second)
spec.check("no buffer -> the current buffer's server", util.client(), second)

spec.check("unattached buffer -> the first server", util.client(console), first)

spec.finish("util_spec")
