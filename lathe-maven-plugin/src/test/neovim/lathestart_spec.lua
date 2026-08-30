-- Verifies :LatheStart (lathe.start) and the unexpected-exit notification
-- (lathe.on_server_exit). Self-contained: builds its own project + fake
-- launcher and stubs vim.lsp.start / vim.notify, so no real server is spawned.
--
-- Run via run-specs.sh, or headless:
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/lathestart_spec.lua

local spec = require("spec_helper").new()

local work = vim.fn.tempname()
local project = work .. "/project"
local nomarker = work .. "/nomarker"
local cache = work .. "/cache"
vim.fn.mkdir(project, "p")
vim.fn.mkdir(nomarker, "p")
vim.fn.mkdir(cache .. "/current", "p")

local function write_file(path, contents)
  local f = assert(io.open(path, "w"))
  f:write(contents)
  f:close()
end

write_file(project .. "/.lathe", "")
local launcher = cache .. "/current/lathe-launcher.sh"
write_file(launcher, "#!/bin/sh\n")
vim.fn.setfperm(launcher, "rwxr-xr-x")

vim.env.LATHE_CACHE = cache
local lathe = require("lathe")
lathe.setup({})

-- Stub the two side-effects: vim.lsp.start would spawn a JVM, vim.notify a UI.
local started, notes = {}, {}
vim.lsp.start = function(config)
  table.insert(started, config)
  return 1
end
vim.notify = function()
  table.insert(notes, true)
end

-- start() from a marked project resolves the working directory and starts there.
vim.fn.chdir(project)
lathe.start(vim.api.nvim_get_current_buf())
spec.check("start: root_dir resolved to the project", started[1] and started[1].root_dir, vim.fs.normalize(project))

-- start() with no .lathe workspace refuses, without spawning a client.
vim.fn.chdir(nomarker)
started = {}
lathe.start(vim.api.nvim_create_buf(false, true))
spec.check("start: no client without a workspace", #started, 0)

-- on_server_exit notifies on an unexpected exit and stays silent on a clean one.
notes = {}
lathe.on_server_exit(0)
spec.check("on_server_exit: clean exit is silent", #notes, 0)
lathe.on_server_exit(134)
spec.check("on_server_exit: crash notifies", #notes, 1)

vim.fn.delete(work, "rf")
spec.finish()
