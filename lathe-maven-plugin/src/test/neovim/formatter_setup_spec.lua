-- Verifies lathe.setup()'s formatter wiring from the formatting-and-indentation
-- design: the server `formatter` init option and the format-on-save autocmd. The
-- autocmd is installed whenever format_on_save == true; the actual formatting is
-- gated at runtime on the server's advertised capability (checked per attach),
-- which itself is covered in LatheLanguageServerTest.
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

-- Default: no style init option, no format-on-save autocmd.
lathe.setup({})
spec.check("default: no style init option", vim.lsp.config["lathe"].init_options.lathe.style, nil)
spec.check("default: no format-on-save autocmd", lspattach_count(), 1)

-- style.formatter is forwarded to the server as the global-default init option.
lathe.setup({ style = { formatter = { engine = "google" } }, format_on_save = true })
spec.check(
  "google: formatter engine init option",
  vim.lsp.config["lathe"].init_options.lathe.style.formatter.engine,
  "google"
)
spec.check("format_on_save: autocmd installed", lspattach_count(), 2)

-- format_on_save == false: no save autocmd regardless of the formatter.
lathe.setup({ style = { formatter = { engine = "google" } }, format_on_save = false })
spec.check("no save: no format-on-save autocmd", lspattach_count(), 1)

-- format_on_save wires the autocmd even without a global formatter option: the formatter can come
-- from a workspace style file, so the capability is checked at runtime (per attach), not here.
lathe.setup({ format_on_save = true })
spec.check("save without global formatter: autocmd wired (runtime-gated)", lspattach_count(), 2)

-- The style.indent fallback propagates from lathe.setup into the lathe.indent module.
lathe.setup({ style = { indent = { profile = "google", continuation = 3 } } })
local indent = require("lathe.indent")
spec.check("indent profile propagates to lathe.indent", indent.config.profile, "google")
spec.check("indent continuation propagates to lathe.indent", indent.config.continuation, 3)

-- M.format dispatches by filename: pom.xml -> lathe.pom.format_buffer (xmllint), everything else ->
-- the fold-preserving google-java-format path. Stub both targets so the test records the route only.
local pom_called, fold_called = nil, nil
require("lathe.pom").format_buffer = function(b)
  pom_called = b
end
require("lathe.fold").format = function(b)
  fold_called = b
end

local pom_buf = vim.api.nvim_create_buf(false, true)
vim.api.nvim_buf_set_name(pom_buf, "/tmp/proj/pom.xml")
lathe.format(pom_buf)
spec.check("format(): pom.xml routes to pom.format_buffer", pom_called, pom_buf)
spec.check("format(): pom.xml skips the fold path", fold_called, nil)

pom_called = nil
local java_buf = vim.api.nvim_create_buf(false, true)
vim.api.nvim_buf_set_name(java_buf, "/tmp/proj/Foo.java")
lathe.format(java_buf)
spec.check("format(): .java routes to the fold path", fold_called, java_buf)
spec.check("format(): .java skips the pom path", pom_called, nil)

spec.finish()
