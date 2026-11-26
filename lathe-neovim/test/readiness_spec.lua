-- Verifies the first-run readiness nudge (lathe.warn_if_not_ready): before setup()
-- it reports "not configured"; once configured it stays silent in a `.lathe`
-- workspace with a launcher, names :LatheInit for a build not set up yet, says a
-- folder without a build cannot be set up, names :LatheInit again after a first
-- build that stopped before its sync, and names an older-Lathe sync when a synced
-- `.lathe/` has no launcher; and it fires at most once per session. Self-contained:
-- builds its own workspace tree and stubs vim.notify.
--
-- Run via run-specs.sh, or headless:
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-neovim/runtime" \
--     --cmd "set rtp+=lathe-neovim/test" \
--     -l lathe-neovim/test/readiness_spec.lua

local spec = require("spec_helper").new()

local work = vim.fn.tempname()
local cache = work .. "/cache"
local synced = work .. "/synced"
local bare = work .. "/bare"
local legacy = work .. "/legacy"
local unfinished = work .. "/unfinished"
local plain = work .. "/plain"
vim.fn.mkdir(cache, "p")
vim.fn.mkdir(synced .. "/.lathe", "p") -- a resolvable Lathe workspace; `bare` has none
vim.fn.mkdir(synced .. "/src/main/java", "p")
vim.fn.mkdir(bare .. "/src/main/java", "p")
-- Synced by a Lathe older than the per-workspace launcher link: `.lathe/` but no launcher.
vim.fn.mkdir(legacy .. "/.lathe", "p")
vim.fn.mkdir(legacy .. "/src/main/java", "p")
-- A first build that stopped before its sync: `.lathe/` without workspace.json.
vim.fn.mkdir(unfinished .. "/.lathe", "p")
vim.fn.mkdir(unfinished .. "/src/main/java", "p")
vim.fn.mkdir(plain, "p")

local function write_file(path, contents)
  local f = assert(io.open(path, "w"))
  f:write(contents)
  f:close()
end

write_file(synced .. "/.lathe/lathe-launcher.sh", "#!/bin/sh\n")
vim.fn.setfperm(synced .. "/.lathe/lathe-launcher.sh", "rwxr-xr-x")
write_file(legacy .. "/.lathe/workspace.json", "{}")
for _, dir in ipairs({ bare, legacy, unfinished }) do
  write_file(dir .. "/pom.xml", "<project/>")
end

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

-- 3) Configured but no `.lathe/`: nudge naming :LatheInit.
lathe.warn_if_not_ready(vim.fn.bufadd(bare .. "/src/main/java/C.java"))
spec.check("configured, no workspace: notify", #notes, 2)
spec.check(
  "configured, no workspace: message mentions .lathe",
  notes[2] and notes[2]:find(".lathe", 1, true) ~= nil,
  true
)
spec.check(
  "configured, no workspace: message names :LatheInit, which sets up OpenJDK too",
  notes[2]
    and notes[2]:find(":LatheInit", 1, true) ~= nil
    and notes[2]:find("lathe-openjdk-maven-plugin:sync", 1, true) == nil,
  true
)

-- Fires once per session after configuring, too.
lathe.warn_if_not_ready(vim.fn.bufadd(bare .. "/src/main/java/D.java"))
spec.check("configured, no workspace: fires once per session", #notes, 2)

-- 4) Configured, `.lathe/` present but no launcher: the server would never start, so name the
-- older-Lathe sync instead of staying silent.
lathe.setup({})
lathe.warn_if_not_ready(vim.fn.bufadd(legacy .. "/src/main/java/E.java"))
spec.check("legacy sync: notify", #notes, 3)
spec.check(
  "legacy sync: message names the older Lathe and the fix",
  notes[3]
    and notes[3]:find("older Lathe", 1, true) ~= nil
    and notes[3]:find("0.1.16", 1, true) ~= nil,
  true
)

-- 5) Configured, a first build still running: it attaches when it ends, so nothing to say yet.
lathe.setup({})
local sync = require("lathe.sync")
local setting_up = sync.setting_up
sync.setting_up = function(path)
  return vim.startswith(vim.uv.fs_realpath(path) or path, vim.uv.fs_realpath(unfinished) .. "/")
end
lathe.warn_if_not_ready(vim.fn.bufadd(unfinished .. "/src/main/java/Running.java"))
sync.setting_up = setting_up
spec.check("first build running: silent", #notes, 3)

-- 6) Configured, a first build that stopped before its sync: run :LatheInit again, not "older".
lathe.warn_if_not_ready(vim.fn.bufadd(unfinished .. "/src/main/java/F.java"))
spec.check("unfinished first build: notify", #notes, 4)
spec.check(
  "unfinished first build: message says it stopped and names :LatheInit",
  notes[4]:find("stopped before it set Lathe up", 1, true) ~= nil
    and notes[4]:find(":LatheInit", 1, true) ~= nil
    and notes[4]:find("older Lathe", 1, true) == nil,
  true
)

-- 7) Configured, a file in no build: nothing to set up, so no :LatheInit.
lathe.setup({})
lathe.warn_if_not_ready(vim.fn.bufadd(plain .. "/G.java"))
spec.check("no build: notify", #notes, 5)
spec.check(
  "no build: message says so and does not name :LatheInit",
  notes[5]:find("no Maven, Gradle or OpenJDK build", 1, true) ~= nil
    and notes[5]:find(":LatheInit", 1, true) == nil,
  true
)

vim.fn.delete(work, "rf")
spec.finish()
