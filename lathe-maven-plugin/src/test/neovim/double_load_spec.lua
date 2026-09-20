-- Verifies double-load detection (lathe.warn_if_double_loaded): a single client copy
-- on runtimepath is silent; two or more copies (e.g. the standalone plugin AND the
-- bundled cache dir) produce exactly one warning naming the paths; and it fires at
-- most once. Stubs nvim_get_runtime_file so no real runtimepath is needed.
--
-- Run via run-specs.sh, or headless:
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/double_load_spec.lua

local spec = require("spec_helper").new()

local lathe = require("lathe")

local notes = {}
vim.notify = function(msg)
  table.insert(notes, msg)
end

local found = {}
vim.api.nvim_get_runtime_file = function()
  return found
end

-- Single copy on runtimepath: silent.
found = { "/a/lua/lathe/version.lua" }
lathe.warn_if_double_loaded()
spec.check("single copy: silent", #notes, 0)

-- Two copies: one warning naming both paths.
found = { "/a/lua/lathe/version.lua", "/b/lua/lathe/version.lua" }
lathe.warn_if_double_loaded()
spec.check("double copy: one notify", #notes, 1)
spec.check(
  "double copy: names the first path",
  notes[1] and notes[1]:find("/a/lua/lathe/version.lua", 1, true) ~= nil,
  true
)
spec.check(
  "double copy: names the second path",
  notes[1] and notes[1]:find("/b/lua/lathe/version.lua", 1, true) ~= nil,
  true
)

-- Fires at most once.
lathe.warn_if_double_loaded()
spec.check("double copy: fires once", #notes, 1)

spec.finish()
