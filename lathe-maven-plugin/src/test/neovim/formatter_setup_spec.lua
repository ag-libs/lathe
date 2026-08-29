-- Verifies lathe.setup()'s formatter wiring from the formatting-and-indentation
-- design: the server `formatter` init option and the gated format-on-save
-- autocmd. The autocmd is installed only when formatter == "google" AND
-- format_on_save == true; the server capability itself is gated separately in
-- LatheLanguageServerTest.
--
-- The default (formatter absent) case runs first because vim.lsp.config merges
-- successive config calls, so only the first setup observes a fresh config.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/formatter_setup_spec.lua

local spec = require("spec_helper").new()

vim.env.LATHE_CACHE = vim.fn.tempname()
local lathe = require("lathe")

-- The run-signs LspAttach autocmd is always registered; the format-on-save one is added on top only
-- when gated on. The augroup is recreated (clear=true) on each setup, so the count is per-setup.
local function lspattach_count()
  return #vim.api.nvim_get_autocmds({ group = "LathePlugin", event = "LspAttach" })
end

-- Default: no formatter init option, no format-on-save autocmd.
lathe.setup({})
spec.check("default: no formatter init option", vim.lsp.config["lathe"].init_options.lathe.formatter, nil)
spec.check("default: no format-on-save autocmd", lspattach_count(), 1)

-- formatter="google" + format_on_save: init option present and save autocmd wired.
lathe.setup({ formatter = "google", format_on_save = true })
spec.check(
  "google: formatter init option",
  vim.lsp.config["lathe"].init_options.lathe.formatter,
  "google"
)
spec.check("google+save: format-on-save autocmd installed", lspattach_count(), 2)

-- formatter="google" without format_on_save: no save autocmd.
lathe.setup({ formatter = "google", format_on_save = false })
spec.check("google, no save: no format-on-save autocmd", lspattach_count(), 1)

-- format_on_save without a formatter: gated off.
lathe.setup({ format_on_save = true })
spec.check("save without formatter: gated off", lspattach_count(), 1)

-- Indent profile options propagate from lathe.setup into the lathe.indent module.
lathe.setup({ indent_style = "google", continuation_indent = 3 })
local indent = require("lathe.indent")
spec.check("indent_style propagates to lathe.indent", indent.config.indent_style, "google")
spec.check("continuation_indent propagates to lathe.indent", indent.config.continuation_indent, 3)

spec.finish()
