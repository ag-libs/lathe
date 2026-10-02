-- Verifies per-workspace indentation: a project's .lathe/style.json (or committed lathe-style.json)
-- `indent` section overrides the global lathe.setup() fallback for buffers under that root. Workspace
-- discovery is unchanged (the existing get_root / .lathe marker); this only reads the style file.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/indent_workspace_spec.lua

local indent = require("lathe.indent")
local style = require("lathe.style")
local spec = require("spec_helper").new()

local function compute(target)
  return indent.compute(target, vim.api.nvim_get_current_buf())
end

local function project(style_json)
  local dir = vim.fn.tempname()
  vim.fn.mkdir(dir .. "/.lathe", "p")
  vim.fn.mkdir(dir .. "/src/main/java", "p")
  local f = assert(io.open(dir .. "/.lathe/style.json", "w"))
  f:write(style_json)
  f:close()
  local src = assert(io.open(dir .. "/src/main/java/Foo.java", "w"))
  src:write("var x =\ny;\n")
  src:close()
  return dir
end

-- Global fallback is google (2-space); the workspace file pins editorconfig 4-space / 8 continuation.
indent.setup({ indent_style = "google" })
local dir = project('{"schemaVersion":"1","indent":{"profile":"editorconfig","block":4,"continuation":8}}')
vim.cmd.edit(dir .. "/src/main/java/Foo.java")
indent.apply_buffer_options(vim.api.nvim_get_current_buf())
spec.check("workspace file overrides global block", vim.bo.shiftwidth, 4)
spec.check("workspace file overrides global continuation", compute(2), 8)
vim.fn.delete(dir, "rf")

-- With no style file, buffers fall back to the global setup() profile (google, 2-space).
local plain = vim.fn.tempname()
vim.fn.mkdir(plain .. "/.lathe", "p")
vim.fn.mkdir(plain .. "/src/main/java", "p")
local src = assert(io.open(plain .. "/src/main/java/Bar.java", "w"))
src:write("var x =\ny;\n")
src:close()
indent.setup({ indent_style = "google" })
vim.cmd.edit(plain .. "/src/main/java/Bar.java")
indent.apply_buffer_options(vim.api.nvim_get_current_buf())
spec.check("no style file falls back to global block", vim.bo.shiftwidth, 2)
spec.check("no style file falls back to global continuation", compute(2), 4)
vim.fn.delete(plain, "rf")

-- style.read: committed lathe-style.json wins over generated .lathe/style.json.
local both = vim.fn.tempname()
vim.fn.mkdir(both .. "/.lathe", "p")
local gen = assert(io.open(both .. "/.lathe/style.json", "w"))
gen:write('{"schemaVersion":"1","formatter":{"engine":"google"}}')
gen:close()
local committed = assert(io.open(both .. "/lathe-style.json", "w"))
committed:write('{"schemaVersion":"1","formatter":{"engine":"none"}}')
committed:close()
spec.check("committed style wins over generated", style.read(both).formatter.engine, "none")
spec.check("missing root reads as nil", style.read(nil), nil)
vim.fn.delete(both, "rf")

spec.finish()
