-- Verifies the BufEnter safety net that re-attaches a Java buffer vim.lsp.enable's async
-- root_dir gate left unattached (a split opening a distinct buffer for an already-open file,
-- or a .lathe/cache buffer whose root wasn't resolvable at FileType time). Self-contained:
-- builds its own project + fake launcher and stubs vim.lsp.start / vim.lsp.get_clients, so no
-- real server is spawned and no .java FileType/ftplugin runs.
--
-- Run via run-specs.sh, or headless:
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/split_attach_spec.lua

local spec = require("spec_helper").new()

local work = vim.fn.tempname()
local project = work .. "/project"
local nomarker = work .. "/nomarker"
local cache = work .. "/cache"
vim.fn.mkdir(project .. "/src/main/java", "p")
vim.fn.mkdir(nomarker .. "/src", "p")
vim.fn.mkdir(cache .. "/current", "p")

local function write_file(path, contents)
  local f = assert(io.open(path, "w"))
  f:write(contents)
  f:close()
end

write_file(project .. "/.lathe", "")
write_file(project .. "/src/main/java/Foo.java", "class Foo {}\n")
write_file(nomarker .. "/src/Bar.java", "class Bar {}\n")
local launcher = cache .. "/current/lathe-launcher.sh"
write_file(launcher, "#!/bin/sh\n")
vim.fn.setfperm(launcher, "rwxr-xr-x")

vim.env.LATHE_CACHE = cache
local lathe = require("lathe")
lathe.setup({})

-- Stub the attach side-effect (vim.lsp.start would spawn a JVM); record which buffer each
-- start was asked to attach. Resolved per call, so stubbing after setup() is enough.
local started = {}
vim.lsp.start = function(_config, opts)
  table.insert(started, opts and opts.bufnr)
  return 1
end

-- Controllable attachment state: get_clients reports a buffer attached only once flagged here.
local attached = {}
vim.lsp.get_clients = function(filter)
  if filter and filter.bufnr and attached[filter.bufnr] then
    return { { name = "lathe" } }
  end

  return {}
end

local foo = vim.fn.bufadd(project .. "/src/main/java/Foo.java")
vim.fn.bufload(foo)

-- Pattern-triggered BufEnter with foo current: the event buffer is the current buffer, so the
-- net sees foo. A deferred vim.schedule runs the attach; vim.wait pumps it.
local function enter(buf, path)
  started = {}
  vim.api.nvim_set_current_buf(buf)
  vim.api.nvim_exec_autocmds("BufEnter", { pattern = path })
  vim.wait(50)
end

-- The unattached-split case: a project Java buffer with no client gets started on BufEnter.
enter(foo, project .. "/src/main/java/Foo.java")
spec.check("attach: unattached java buffer starts on BufEnter", started[1], foo)

-- Idempotent: once attached, re-entering does not start again.
attached[foo] = true
enter(foo, project .. "/src/main/java/Foo.java")
spec.check("attach: no re-start when already attached", #started, 0)

-- A Java buffer outside any `.lathe` workspace never attaches (get_root returns nil).
local bar = vim.fn.bufadd(nomarker .. "/src/Bar.java")
vim.fn.bufload(bar)
enter(bar, nomarker .. "/src/Bar.java")
spec.check("attach: no start outside a workspace", #started, 0)

-- An artificial buffer (non-empty buftype) is skipped, mirroring native enable's refusal.
attached[foo] = nil
vim.bo[foo].buftype = "nofile"
enter(foo, project .. "/src/main/java/Foo.java")
spec.check("attach: no start for an artificial buffer", #started, 0)

vim.fn.delete(work, "rf")
spec.finish()
