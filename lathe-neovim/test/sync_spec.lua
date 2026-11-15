-- Verifies lathe.sync: run executes the workspace's .lathe/lathe-sync.sh with --tests and module
-- arguments, at the workspace root; a missing script (a first build that stopped before its sync,
-- or a workspace synced by an older Lathe) is reported instead of run; the toast names the script invocation the client ran; a
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
vim.schedule = function(fn)
  fn()
end

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

-- A first build that stopped before its sync left no workspace.json and no script: run
-- :LatheInit again.
missing["/ws/old/.lathe/lathe-sync.sh"] = true
local before_old = #calls
sync.run("/ws/old", false)
spec.check("missing script runs nothing", #calls, before_old)
spec.check(
  "missing script after an unfinished first build names :LatheInit",
  notes[#notes]:find("stopped before it set Lathe up", 1, true) ~= nil,
  true
)

-- A workspace synced by an older Lathe has workspace.json but no script: say so.
local legacy = vim.fn.tempname()
vim.fn.mkdir(legacy .. "/.lathe", "p")
local manifest = assert(io.open(legacy .. "/.lathe/workspace.json", "w"))
manifest:write("{}")
manifest:close()
missing[legacy .. "/.lathe/lathe-sync.sh"] = true
sync.run(legacy, false)
spec.check("missing script runs nothing for an older sync", #calls, before_old)
spec.check(
  "missing script names the older Lathe",
  notes[#notes]:find("older Lathe", 1, true) ~= nil,
  true
)
vim.fn.delete(legacy, "rf")

-- :LatheInit's runs: the toasts name the action and the label; the console shows the command
-- itself, quoted, and the failure split opens at the build's verdict, not the warnings below it.
sync.run("/ws/f", false, nil, {
  cmd = { "/opt/bin/mvn", "-B", "-Dx=a b" },
  label = "mvn clean test",
  action = "setup",
})
spec.check(
  "a setup run sets up the files under its root",
  sync.setting_up("/ws/f/src/A.java"),
  true
)
spec.check("and no others", sync.setting_up("/ws/fx/src/A.java"), false)
spec.check(
  "a setup run's toast says it is setting up",
  notes[#notes]:find("Lathe: setting up… 0s — `mvn clean test`", 1, true) ~= nil,
  true
)
pending_cb({
  code = 1,
  stdout = "[INFO] BUILD FAILURE\n[ERROR] boom\nWARNING: dumb terminal\n",
  stderr = "",
})
spec.check(
  "a failed setup run's toast names the action",
  notes[#notes]:find("Lathe: setup failed (`mvn clean test`, exit 1)", 1, true) ~= nil,
  true
)
local console = vim.fn.bufnr("Lathe Sync Output")
local console_lines = vim.api.nvim_buf_get_lines(console, 0, -1, false)
spec.check(
  "the console shows the command itself, quoted",
  console_lines[2],
  "$ cd /ws/f && /opt/bin/mvn -B '-Dx=a b'"
)
spec.check(
  "the failure split opens at the build's verdict",
  console_lines[vim.api.nvim_win_get_cursor(vim.fn.bufwinid(console))[1]],
  "[ERROR] boom"
)

spec.check("a finished setup run sets up nothing", sync.setting_up("/ws/f/src/A.java"), false)

-- Running-guard: a second sync for the same root while one is in flight is a no-op.
sync.run("/ws/b", false)
spec.check("a plain sync is not a setup", sync.setting_up("/ws/b/src/A.java"), false)
local before = #calls
sync.run("/ws/b", false)
spec.check("concurrent sync for the same root is a no-op", #calls, before)

-- An empty/nil root does nothing.
local n = #calls
sync.run(nil, false)
sync.run("", false)
spec.check("nil/empty root does nothing", #calls, n)

-- The lathe/sync notification handler dispatches to run, forwarding capture.
sync.setup()
vim.lsp.handlers["lathe/sync"](nil, { workspaceRoot = "/ws/c", captureTests = true })
spec.check("lathe/sync handler runs at the given root", calls[#calls].opts.cwd, "/ws/c")
spec.check("lathe/sync handler forwards capture", table.concat(last_args(), " "), "--tests")
spec.check("registers :LatheSync", vim.fn.exists(":LatheSync"), 2)
spec.check("registers :LatheSyncCaptureTest", vim.fn.exists(":LatheSyncCaptureTest"), 2)
spec.check("registers :LatheSyncOutput", vim.fn.exists(":LatheSyncOutput"), 2)

-- :LatheSync from a file outside any workspace says so, and runs nothing.
local before_outside = #calls
vim.cmd.edit(vim.fn.tempname() .. "/Outside.java")
vim.cmd("LatheSync")
spec.check("outside a workspace: runs nothing", #calls, before_outside)
spec.check(
  "outside a workspace: says there is no workspace",
  notes[#notes] and notes[#notes]:find("no .lathe/", 1, true) ~= nil,
  true
)

spec.finish("lathe.sync")
