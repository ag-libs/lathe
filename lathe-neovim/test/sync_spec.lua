-- Verifies lathe.sync: run executes the workspace's .lathe/lathe-sync.sh with --tests and module
-- arguments, at the workspace root; a missing script (a workspace synced by an older Lathe) is
-- reported instead of run; the toast names the script invocation the client ran; a
-- concurrent sync for the same root is a no-op; an empty root does nothing; and the lathe/sync
-- handler dispatches to run. Stubs vim.system so no build is spawned.
--
-- Run via run-specs.sh, or headless:
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-neovim/runtime" \
--     --cmd "set rtp+=lathe-neovim/test" \
--     -l lathe-neovim/test/sync_spec.lua

local spec = require("spec_helper").new()

-- Capture vim.system calls instead of spawning the script; retain the completion callback so the
-- running-guard can be released on demand.
local calls = {}
local pending_cb
vim.system = function(cmd, opts, on_exit)
  table.insert(calls, { cmd = cmd, opts = opts })
  pending_cb = on_exit
  return { pid = 1 }
end
local notes = {}
vim.notify = function(msg)
  table.insert(notes, msg)
end
vim.schedule = function(fn) fn() end

-- Capture the reactor-lock pre-touch and release instead of touching a real file, so the path and
-- lifecycle can be asserted.
local writes = {}
vim.fn.writefile = function(_lines, path)
  table.insert(writes, path)
  return 0
end
local unlinks = {}
vim.loop.fs_unlink = function(path)
  table.insert(unlinks, path)
  return true
end

-- Every root has a sync script unless listed here (a workspace synced by an older Lathe).
local missing = {}
vim.fn.executable = function(path)
  return missing[path] and 0 or 1
end

local function last_args()
  return vim.list_slice(calls[#calls].cmd, 2)
end

local sync = require("lathe.sync")

sync.run("/ws/a", false)
spec.check("runs the workspace sync script", calls[1].cmd[1], "/ws/a/.lathe/lathe-sync.sh")
spec.check("full sync passes no arguments", #last_args(), 0)
spec.check("runs at the workspace root", calls[1].opts.cwd, "/ws/a")
spec.check("pre-touches the reactor lock before spawning", writes[1], "/ws/a/.lathe/lathe.lock")

-- The toast names what the client ran, not the build command the script resolves.
pending_cb({ code = 0, stdout = "", stderr = "lathe-sync: mvnd process-test-classes\n" })
spec.check("releases the reactor lock when the build ends", unlinks[1], "/ws/a/.lathe/lathe.lock")
spec.check(
  "success toast names the script invocation",
  notes[#notes]:find("`.lathe/lathe-sync.sh`", 1, true) ~= nil
    and notes[#notes]:find("mvnd", 1, true) == nil,
  true
)

sync.run("/ws/a", true)
spec.check("capture passes --tests", table.concat(last_args(), " "), "--tests")
pending_cb({ code = 0 })

sync.run("/ws/e", false, { "app", "core" })
spec.check("scoped sync passes the modules", table.concat(last_args(), " "), "app core")
pending_cb({ code = 0 })

sync.run("/ws/e", true, { "app" })
spec.check("capture and scope combine", table.concat(last_args(), " "), "--tests app")
pending_cb({ code = 1, stdout = "out\n", stderr = "lathe-sync: mvnd -pl app test\nboom\n" })
spec.check(
  "failure toast names the script invocation",
  notes[#notes]:find(".lathe/lathe-sync.sh --tests app", 1, true) ~= nil,
  true
)

-- A workspace synced by an older Lathe has no script: say so and run nothing.
missing["/ws/old/.lathe/lathe-sync.sh"] = true
local before_old = #calls
sync.run("/ws/old", false)
spec.check("missing script runs nothing", #calls, before_old)
spec.check(
  "missing script names the older Lathe",
  notes[#notes]:find("older Lathe", 1, true) ~= nil,
  true
)

-- Running-guard: a second sync for the same root while one is in flight is a no-op.
sync.run("/ws/b", false)
local before = #calls
sync.run("/ws/b", false)
spec.check("concurrent sync for the same root is a no-op", #calls, before)

-- An empty/nil root does nothing.
local n = #calls
sync.run(nil, false)
sync.run("", false)
spec.check("nil/empty root does nothing", #calls, n)

-- The lathe/sync notification handler dispatches to run, forwarding capture and modules.
sync.setup()
vim.lsp.handlers["lathe/sync"](nil, { workspaceRoot = "/ws/c", captureTests = true, modules = { "m" } })
spec.check("lathe/sync handler runs at the given root", calls[#calls].opts.cwd, "/ws/c")
spec.check("lathe/sync handler forwards capture and modules", table.concat(last_args(), " "), "--tests m")
spec.check("registers :LatheSync", vim.fn.exists(":LatheSync"), 2)
spec.check("registers :LatheSyncCaptureTest", vim.fn.exists(":LatheSyncCaptureTest"), 2)
spec.check("registers :LatheSyncOutput", vim.fn.exists(":LatheSyncOutput"), 2)

spec.finish("lathe.sync")
