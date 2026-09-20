-- Verifies the first-run readiness nudge (lathe.warn_if_not_ready): before setup()
-- it reports "not configured"; once configured it stays silent in a `.lathe`
-- workspace but reports the missing-workspace cause (Maven not configured / not
-- synced) elsewhere; and it fires at most once per session. Self-contained: builds
-- its own workspace tree and stubs vim.notify.
--
-- Run via run-specs.sh, or headless:
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/readiness_spec.lua

local spec = require("spec_helper").new()

local work = vim.fn.tempname()
local cache = work .. "/cache"
local synced = work .. "/synced"
local bare = work .. "/bare"
vim.fn.mkdir(cache .. "/current", "p")
vim.fn.mkdir(synced .. "/src/main/java", "p")
vim.fn.mkdir(bare .. "/src/main/java", "p")

local function write_file(path, contents)
  local f = assert(io.open(path, "w"))
  f:write(contents)
  f:close()
end

write_file(synced .. "/.lathe", "") -- a resolvable Lathe workspace; `bare` has none

vim.env.LATHE_CACHE = cache
local lathe = require("lathe")

local notes = {}
vim.notify = function(msg)
  table.insert(notes, msg)
end

-- 1) Not configured yet: nudge to call setup(), regardless of workspace.
lathe.warn_if_not_ready(vim.fn.bufadd(bare .. "/src/main/java/A.java"))
spec.check("not configured: one notify", #notes, 1)
spec.check(
  "not configured: message says not configured",
  notes[1] and notes[1]:find("not configured", 1, true) ~= nil,
  true
)

-- Fires once per session.
lathe.warn_if_not_ready(vim.fn.bufadd(bare .. "/src/main/java/A2.java"))
spec.check("not configured: fires once per session", #notes, 1)

-- Configuring re-arms the one-shot nudge and marks the client configured.
lathe.setup({})

-- 2) Configured + a resolvable `.lathe` workspace: fully ready -> silent.
lathe.warn_if_not_ready(vim.fn.bufadd(synced .. "/src/main/java/B.java"))
spec.check("configured + workspace: silent", #notes, 1)

-- 3) Configured but no `.lathe/`: nudge naming both causes (not configured / not synced).
lathe.warn_if_not_ready(vim.fn.bufadd(bare .. "/src/main/java/C.java"))
spec.check("configured, no workspace: notify", #notes, 2)
spec.check(
  "configured, no workspace: message mentions .lathe",
  notes[2] and notes[2]:find(".lathe", 1, true) ~= nil,
  true
)
spec.check(
  "configured, no workspace: message names the not-synced cause",
  notes[2] and notes[2]:find("synced", 1, true) ~= nil,
  true
)

-- Fires once per session after configuring, too.
lathe.warn_if_not_ready(vim.fn.bufadd(bare .. "/src/main/java/D.java"))
spec.check("configured, no workspace: fires once per session", #notes, 2)

vim.fn.delete(work, "rf")
spec.finish()
