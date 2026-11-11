-- Verifies lathe.setup()'s formatter wiring: no editor-wide formatter (a project declares its own),
-- and the format-on-save autocmd. The
-- autocmd is installed whenever format_on_save == true; the actual formatting is
-- gated at runtime on the server's advertised capability (checked per attach),
-- which itself is covered in LatheLanguageServerTest.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-neovim/runtime" \
--     --cmd "set rtp+=lathe-neovim/test" \
--     -l lathe-neovim/test/formatter_setup_spec.lua

local spec = require("spec_helper").new()

vim.env.LATHE_CACHE = vim.fn.tempname()
local lathe = require("lathe")

-- The format-on-save LspAttach autocmd, registered only when gated on. Its augroup is recreated
-- (clear=true) on each setup, so the count is per-setup.
local function lspattach_count()
  return #vim.api.nvim_get_autocmds({ group = "LatheFormatOnSave", event = "LspAttach" })
end

-- Default: no init options for the server, no format-on-save autocmd.
lathe.setup({})
spec.check("default: no init options", vim.lsp.config["lathe"].init_options, nil)
spec.check("default: no format-on-save autocmd", lspattach_count(), 0)

-- style.formatter is no longer read: the formatter is the project's. Passing it warns, once.
local warnings = {}
local notify = vim.notify
vim.notify = function(msg, level)
  if level == vim.log.levels.WARN then
    table.insert(warnings, msg)
  end
end
lathe.setup({ style = { formatter = { engine = "google" } }, format_on_save = true })
lathe.setup({ style = { formatter = { engine = "google" } }, format_on_save = false })
vim.notify = notify
spec.check("global formatter: not sent to the server", vim.lsp.config["lathe"].init_options, nil)
spec.check("global formatter: warned once", #warnings, 1)
spec.check(
  "global formatter: points at lathe-style.json",
  warnings[1] and warnings[1]:find("lathe-style.json", 1, true) ~= nil,
  true
)
spec.check("no save: no format-on-save autocmd", lspattach_count(), 0)

-- format_on_save wires the autocmd even without a global formatter option: the formatter can come
-- from a workspace style file, so the capability is checked at runtime (per attach), not here.
lathe.setup({ format_on_save = true })
spec.check("save without global formatter: autocmd wired (runtime-gated)", lspattach_count(), 1)

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
