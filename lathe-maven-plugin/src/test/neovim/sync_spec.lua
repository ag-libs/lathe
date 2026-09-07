-- Verifies lathe.sync: run_maven picks the right goal/cwd, a concurrent sync for the same root is a
-- no-op, an empty root does nothing, and the lathe/sync handler dispatches to run_maven. Stubs
-- vim.system so no Maven is spawned.
--
-- Run via run-specs.sh, or headless:
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/sync_spec.lua

local spec = require("spec_helper").new()

-- Capture vim.system calls instead of spawning Maven; retain the completion callback so the
-- running-guard can be released on demand.
local calls = {}
local pending_cb
vim.system = function(cmd, opts, on_exit)
  table.insert(calls, { cmd = cmd, opts = opts })
  pending_cb = on_exit
  return { pid = 1 }
end
vim.notify = function() end
vim.schedule = function(fn) fn() end

local sync = require("lathe.sync")

sync.run_maven("/ws/a", false)
spec.check("invokes mvn", calls[1].cmd[1], "mvn")
spec.check("passes --no-transfer-progress", calls[1].cmd[2], "--no-transfer-progress")
spec.check("disables the build cache", calls[1].cmd[3], "-Dmaven.build.cache.enabled=false")
spec.check("default goal is process-test-classes", calls[1].cmd[4], "process-test-classes")
spec.check("runs at the workspace root", calls[1].opts.cwd, "/ws/a")

pending_cb({ code = 0 }) -- finish the first job so the guard for /ws/a clears

sync.run_maven("/ws/a", true)
spec.check("capture selects the test goal", calls[2].cmd[4], "test")

pending_cb({ code = 0 })

-- Targeted sync: a module list narrows the build to `-pl <modules> -am` before the goal.
sync.run_maven("/ws/e", false, { "app", "core" })
local targeted = calls[#calls].cmd
spec.check("targeted sync inserts -pl", targeted[4], "-pl")
spec.check("targeted sync joins modules with comma", targeted[5], "app,core")
spec.check("targeted sync adds -am", targeted[6], "-am")
spec.check("targeted goal follows -pl -am", targeted[7], "process-test-classes")

pending_cb({ code = 0 })

-- Running-guard: a second sync for the same root while one is in flight is a no-op.
sync.run_maven("/ws/b", false)
local before = #calls
sync.run_maven("/ws/b", false)
spec.check("concurrent sync for the same root is a no-op", #calls, before)

-- An empty/nil root does nothing.
local n = #calls
sync.run_maven(nil, false)
sync.run_maven("", false)
spec.check("nil/empty root does nothing", #calls, n)

-- The lathe/sync notification handler dispatches to run_maven.
sync.setup()
vim.lsp.handlers["lathe/sync"](nil, { workspaceRoot = "/ws/c", captureTests = false })
spec.check("lathe/sync handler runs Maven at the given root", calls[#calls].opts.cwd, "/ws/c")
spec.check("registers :LatheSync", vim.fn.exists(":LatheSync"), 2)
spec.check("registers :LatheSyncCaptureTest", vim.fn.exists(":LatheSyncCaptureTest"), 2)
spec.check("registers :LatheSyncOutput", vim.fn.exists(":LatheSyncOutput"), 2)

spec.finish("lathe.sync")
