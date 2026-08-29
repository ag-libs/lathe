-- Verifies the indentation profiles from the formatting-and-indentation design:
-- the `google` fixed widths, the `editor_config` 4-space baseline, the
-- shiftwidth=0 -> tabstop fallback, and the `continuation_indent` override.
--
-- The `editor_config` profile consumes the buffer-local options that native
-- EditorConfig (or the ftplugin baseline) resolves, so these tests set those
-- options directly rather than parsing a `.editorconfig` file.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/indent_profiles_spec.lua

local indent = require("lathe.indent")
local spec = require("spec_helper").new()

local function compute(lines, target)
  vim.api.nvim_buf_set_lines(0, 0, -1, false, lines)
  return indent.compute(target, vim.api.nvim_get_current_buf())
end

-- google profile: fixed 2-space block, 4-space continuation.
indent.setup({ indent_style = "google" })
indent.apply_buffer_options(0)
spec.check("google: expandtab", vim.bo.expandtab, true)
spec.check("google: shiftwidth", vim.bo.shiftwidth, 2)
spec.check("google: softtabstop", vim.bo.softtabstop, 2)
spec.check("google: tabstop", vim.bo.tabstop, 2)
spec.check("google: block indent", compute({ "void m() {", "body();" }, 2), 2)
spec.check("google: continuation indent", compute({ "var x =", "y;" }, 2), 4)

-- editor_config baseline (no matching .editorconfig): 4-space block, 8-space continuation.
indent.setup({ indent_style = "editor_config" })
indent.apply_buffer_options(0)
spec.check("editor_config: expandtab baseline", vim.bo.expandtab, true)
spec.check("editor_config: shiftwidth baseline", vim.bo.shiftwidth, 4)
spec.check("editor_config: softtabstop baseline", vim.bo.softtabstop, 4)
spec.check("editor_config: tabstop baseline", vim.bo.tabstop, 4)
spec.check("editor_config: block indent", compute({ "void m() {", "body();" }, 2), 4)
spec.check("editor_config: continuation indent", compute({ "var x =", "y;" }, 2), 8)

-- Tab indentation (EditorConfig indent_style=tab sets expandtab off and shiftwidth 0): block width
-- falls back to tabstop per Vim's rule.
indent.setup({ indent_style = "editor_config" })
vim.bo.expandtab = false
vim.bo.shiftwidth = 0
vim.bo.softtabstop = 0
vim.bo.tabstop = 4
spec.check("shiftwidth=0 falls back to tabstop", compute({ "void m() {", "body();" }, 2), 4)

-- continuation_indent pins the continuation width regardless of profile/block width.
indent.setup({ indent_style = "google", continuation_indent = 3 })
indent.apply_buffer_options(0)
spec.check("continuation_indent override", compute({ "var x =", "y;" }, 2), 3)

-- An unknown indent_style falls back to the editor_config 4-space baseline.
indent.setup({ indent_style = "bogus" })
indent.apply_buffer_options(0)
spec.check("unknown indent_style falls back to 4-space baseline", vim.bo.shiftwidth, 4)

-- Native EditorConfig runs after ftplugins and overrides the editor_config baseline. Exercise the
-- real runtime resolver against an on-disk `.editorconfig` to confirm the precedence the design
-- relies on: baseline 4, then the project's indent_size=2 wins.
local dir = vim.fn.tempname()
vim.fn.mkdir(dir, "p")
local ec = assert(io.open(dir .. "/.editorconfig", "w"))
ec:write("root = true\n[*.java]\nindent_style = space\nindent_size = 2\n")
ec:close()
local src = assert(io.open(dir .. "/Foo.java", "w"))
src:write("class Foo {}\n")
src:close()

indent.setup({ indent_style = "editor_config" })
vim.cmd.edit(dir .. "/Foo.java")
local ec_buf = vim.api.nvim_get_current_buf()
indent.apply_buffer_options(ec_buf) -- ftplugin baseline (4), applied at FileType time
require("editorconfig").config(ec_buf) -- native EditorConfig, which runs afterward and wins
spec.check("editorconfig overrides baseline: shiftwidth", vim.bo[ec_buf].shiftwidth, 2)
spec.check("editorconfig applied props recorded", vim.b[ec_buf].editorconfig.indent_size, "2")
spec.check("editorconfig block indent", compute({ "void m() {", "body();" }, 2), 2)
vim.fn.delete(dir, "rf")

spec.finish()
