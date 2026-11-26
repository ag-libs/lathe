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
local notes = {}
vim.notify = function(msg)
  table.insert(notes, msg)
end

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

-- The stopped run ends with the signal's exit code: it reads as stopped, not as a failed exit.
pending[2](nil, { launched = true, exitCode = 143 })
vim.wait(50)
spec.check(
  "a stopped run: says stopped",
  notes[#notes]:find("stopped", 1, true) ~= nil and notes[#notes]:find("exited", 1, true) == nil,
  true
)

-- A run that ends with 143 on its own, no stop asked for, still reports its exit code.
run.run_named("third")
pending[3](nil, { launched = true, exitCode = 143 })
vim.wait(50)
spec.check(
  "a run killed from outside: reports its exit",
  notes[#notes]:find("exited 143", 1, true) ~= nil,
  true
)

-- A stop that came too late: the run ended on its own with a failure, which is reported as one.
run.run_named("fourth")
run.stop()
pending[4](nil, { launched = true, exitCode = 1 })
vim.wait(50)
spec.check(
  "a stop that came too late: reports the failure",
  notes[#notes]:find("exited 1", 1, true) ~= nil,
  true
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
