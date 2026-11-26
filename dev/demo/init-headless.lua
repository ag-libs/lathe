-- Runs `:LatheInit maven` with no one at the keyboard (prepare.sh): accepts the root the prompt
-- offers, waits for the setup build to end, and exits non-zero if it did not set the project up.
vim.ui.input = function(opts, on_confirm)
  on_confirm(opts.default)
end

local root = vim.fn.getcwd()
vim.cmd("LatheInit maven")
local sync = require("lathe.sync")
vim.wait(1000, function()
  return sync.busy(root)
end, 100)
local finished = vim.wait(15 * 60 * 1000, function()
  return not sync.busy(root)
end, 500)
local launcher = vim.fs.joinpath(root, ".lathe", "lathe-launcher.sh")
if not finished or vim.fn.executable(launcher) ~= 1 then
  io.stderr:write("[demo] :LatheInit did not set " .. root .. " up\n")
  vim.cmd("cquit 1")
end

vim.cmd("qall!")
