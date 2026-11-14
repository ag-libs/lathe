-- Verifies :LatheRunStop's bookkeeping: an earlier run ending does not forget the run that replaced
-- it, and a debug session that failed to start leaves nothing to stop, so the stop falls through to
-- a normal run. The fake client holds each run's reply until the spec releases it.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-neovim/runtime" \
--     --cmd "set rtp+=lathe-neovim/test" \
--     -l lathe-neovim/test/run_stop_spec.lua

local helper = require("spec_helper")
local spec = helper.new()
local commands = require("lathe.commands")

local pending = {}
local requests = helper.fake_client(function(_, _, params, callback)
  if params.command == commands.RUN_NAMED then
    pending[#pending + 1] = callback
  elseif params.command == commands.DEBUG_NAMED then
    callback({ message = "no debug host" }, nil)
  end
end)

-- Two runs, the first ending after the second started: the stop still reaches the second.
local run = require("lathe.run")
run.run_named("first")
run.run_named("second")
pending[1](nil, { launched = true, exitCode = 0 })
vim.wait(50)
run.stop()
local cancel = requests[#requests]
spec.check("stop after an earlier run ended: a cancel", cancel.command, commands.RUN_CANCEL)
spec.check(
  "stop after an earlier run ended: the newer run's token",
  cancel.arguments[1].token,
  requests[2].arguments[1].token
)

-- A debug start the server refuses leaves no session for the stop to take.
local adapters = {}
package.loaded["dap"] = {
  adapters = adapters,
  listeners = { after = { event_terminated = {}, event_exited = {} } },
}
local dap = require("lathe.dap")
dap.setup()
adapters.lathe(function() end, dap._named_config_for("saved"))
spec.check("failed debug start: nothing left to stop", dap.stop(), false)
package.loaded["dap"] = nil

spec.finish("run_stop_spec")
