-- Verifies :LatheStart (lathe.start) and the unexpected-exit notification
-- (lathe.on_server_exit). Self-contained: builds its own project + fake
-- launcher and stubs vim.lsp.start / vim.notify, so no real server is spawned.
--
-- Run via run-specs.sh, or headless:
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-neovim/runtime" \
--     --cmd "set rtp+=lathe-neovim/test" \
--     -l lathe-neovim/test/lathestart_spec.lua

local spec = require("spec_helper").new()

local work = vim.fn.tempname()
local project = work .. "/project"
local nomarker = work .. "/nomarker"
local cache = work .. "/cache"
vim.fn.mkdir(project .. "/.lathe", "p")
vim.fn.mkdir(nomarker, "p")
vim.fn.mkdir(cache, "p")

local function write_file(path, contents)
  local f = assert(io.open(path, "w"))
  f:write(contents)
  f:close()
end

-- The launcher is the per-workspace script `lathe:sync` writes at <root>/.lathe/, pinned to the
-- server version this project uses. Here an executable stub stands in for it.
local launcher = project .. "/.lathe/lathe-launcher.sh"
write_file(launcher, "#!/bin/sh\n")
vim.fn.setfperm(launcher, "rwxr-xr-x")

vim.env.LATHE_CACHE = cache
local lathe = require("lathe")
lathe.setup({})

-- `cmd` is a function (spawns with cwd = root); stub rpc.start to capture its launcher path and cwd.
local rpc_args
vim.lsp.rpc.start = function(cmd, _dispatchers, params)
  rpc_args = { cmd = cmd, params = params }
  return { request = function() end, notify = function() end, is_closing = function() end }
end

local function spawn_launcher(root)
  vim.lsp.config["lathe"].cmd({}, { root_dir = root })
  return rpc_args
end

-- Default: the launcher resolves under the workspace's own .lathe/, spawned with cwd = root.
spec.check(
  "launcher: default resolves under the workspace .lathe dir",
  spawn_launcher(project).cmd[1],
  vim.fs.normalize(project) .. "/.lathe/lathe-launcher.sh"
)
spec.check("launcher: server cwd is the workspace root", rpc_args.params.cwd, project)

-- LATHE_SERVER_DIR overrides the launcher location for local server development,
-- without rewriting the workspace launcher.
local override = work .. "/override"
vim.fn.mkdir(override, "p")
local override_launcher = override .. "/lathe-launcher.sh"
write_file(override_launcher, "#!/bin/sh\n")
vim.fn.setfperm(override_launcher, "rwxr-xr-x")
vim.env.LATHE_SERVER_DIR = override
lathe.setup({})
spec.check(
  "launcher: LATHE_SERVER_DIR overrides the workspace .lathe dir",
  spawn_launcher(project).cmd[1],
  vim.fs.normalize(override) .. "/lathe-launcher.sh"
)
vim.env.LATHE_SERVER_DIR = nil
lathe.setup({})

-- Stub the two side-effects: vim.lsp.start would spawn a JVM, vim.notify a UI.
local started, notes = {}, {}
vim.lsp.start = function(config)
  table.insert(started, config)
  return 1
end
vim.notify = function(msg)
  table.insert(notes, msg)
end

-- start() from a marked project resolves the working directory and starts there.
vim.fn.chdir(project)
lathe.start(vim.api.nvim_get_current_buf())
-- getcwd() is the physical path, so compare against the resolved project (macOS: /var -> /private/var).
spec.check(
  "start: root_dir resolved to the project",
  started[1] and started[1].root_dir,
  vim.fs.normalize(vim.uv.fs_realpath(project))
)

-- start() with no .lathe workspace refuses, without spawning a client.
vim.fn.chdir(nomarker)
started = {}
lathe.start(vim.api.nvim_create_buf(false, true))
spec.check("start: no client without a workspace", #started, 0)

-- Neovim calls on_exit in a fast event, where the real vim.notify raises E5560. Run the callback
-- from a libuv timer the same way, and let the stub fail a notification made there.
local fast_notes = 0
vim.notify = function(msg)
  if vim.in_fast_event() then
    fast_notes = fast_notes + 1
    return
  end

  table.insert(notes, msg)
end

local function server_exit(code, signal)
  notes = {}
  local done = false
  local timer = vim.uv.new_timer()
  timer:start(0, 0, function()
    timer:close()
    lathe.on_server_exit(code, signal)
    vim.schedule(function()
      done = true
    end)
  end)
  vim.wait(1000, function()
    return done
  end)
end

-- on_server_exit notifies on an unexpected exit and stays silent on a clean one.
server_exit(0)
spec.check("on_server_exit: clean exit is silent", #notes, 0)
server_exit(0, 0)
spec.check("on_server_exit: clean exit with no signal is silent", #notes, 0)
server_exit(134)
spec.check("on_server_exit: crash notifies", #notes, 1)
spec.check(
  "on_server_exit: crash names the code, with no restart hint",
  notes[1] ~= nil and notes[1]:find("code 134", 1, true) ~= nil and notes[1]:find(
    "LatheStart",
    1,
    true
  ) == nil,
  true
)

-- A SIGKILL (the OOM killer) ends the process with code 0: the signal tells it from a clean exit.
server_exit(0, 9)
spec.check("on_server_exit: killed by a signal notifies", #notes, 1)
spec.check(
  "on_server_exit: kill names the signal and :LatheStart",
  notes[1] ~= nil
    and notes[1]:find("killed (signal 9)", 1, true) ~= nil
    and notes[1]:find(":LatheStart", 1, true) ~= nil,
  true
)

-- SIGTERM is how Neovim itself stops a client (:lsp restart, a timed-out shutdown).
server_exit(0, 15)
spec.check("on_server_exit: SIGTERM from Neovim is silent", #notes, 0)

-- The JVM handles SIGTERM with its shutdown hooks and exits with 128 + 15, no signal.
server_exit(143, 0)
spec.check("on_server_exit: JVM exit after SIGTERM is silent", #notes, 0)

-- A code and a signal together: the kill is what the user needs to hear.
server_exit(137, 9)
spec.check("on_server_exit: code with a signal notifies once", #notes, 1)
spec.check(
  "on_server_exit: code with a signal names the signal",
  notes[1] ~= nil and notes[1]:find("signal 9", 1, true) ~= nil,
  true
)

-- A launcher that does not exec the JVM reports its SIGKILL as 128 + 9, with no signal.
server_exit(137, 0)
spec.check("on_server_exit: kill reported as a code notifies once", #notes, 1)
spec.check(
  "on_server_exit: kill reported as a code names the signal and :LatheStart",
  notes[1] ~= nil
    and notes[1]:find("killed (signal 9)", 1, true) ~= nil
    and notes[1]:find(":LatheStart", 1, true) ~= nil,
  true
)
spec.check("on_server_exit: no notification in a fast event", fast_notes, 0)

vim.fn.delete(work, "rf")
spec.finish()
