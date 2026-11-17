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

-- A Java buffer formats when an attached server formats it (Lathe, or another formatter beside it);
-- otherwise the user is told why, in Lathe's words when it gives them. The get_clients stub honours
-- the id, name and method filters, so a check that looked at the wrong client would fail here.
local attached = {}
local real_get_clients = vim.lsp.get_clients
local provider_of = {
  ["textDocument/formatting"] = "documentFormattingProvider",
  ["textDocument/rangeFormatting"] = "documentRangeFormattingProvider",
}
vim.lsp.get_clients = function(filter)
  return vim.tbl_filter(function(client)
    if filter.id and client.id ~= filter.id then
      return false
    end

    if filter.name and client.name ~= filter.name then
      return false
    end

    return not filter.method or client.server_capabilities[provider_of[filter.method]] ~= nil
  end, attached)
end
local notes = {}
vim.notify = function(msg)
  table.insert(notes, msg)
end
local function lathe_client(capabilities, initialized)
  return { name = "lathe", initialized = initialized ~= false, server_capabilities = capabilities }
end

pom_called = nil
attached = { lathe_client({ documentFormattingProvider = true }) }
local java_buf = vim.api.nvim_create_buf(false, true)
vim.api.nvim_buf_set_name(java_buf, "/tmp/proj/Foo.java")
lathe.format(java_buf)
spec.check("format(): .java routes to the fold path", fold_called, java_buf)
spec.check("format(): .java skips the pom path", pom_called, nil)
spec.check("format(): a formatting server is not explained", #notes, 0)

-- Another formatter beside a Lathe that formats nothing still formats.
fold_called = nil
attached = {
  lathe_client({}),
  { name = "null-ls", initialized = true, server_capabilities = { documentFormattingProvider = true } },
}
lathe.format(java_buf)
spec.check("format(): another formatting server still formats", fold_called, java_buf)
spec.check("format(): another formatting server is not explained", #notes, 0)

-- A format asked of Lathe alone is not approved by the other formatter that vim.lsp.buf.format
-- would then leave out.
fold_called = nil
lathe.format(java_buf, { name = "lathe" })
spec.check("format(): a name filter leaves the other formatter out", fold_called, nil)
lathe.format(java_buf, {
  filter = function(client)
    return client.name == "lathe"
  end,
})
spec.check("format(): a filter leaves the other formatter out", fold_called, nil)
spec.check("format(): a filtered-out formatter is explained", #notes, 2)

-- A selection needs a server that formats ranges: a whole-file formatter says so.
notes = {}
attached = { lathe_client({ documentFormattingProvider = true }) }
local selection = { start = { 1, 0 }, ["end"] = { 1, 0 } }
lathe.format(java_buf, { range = selection })
spec.check("format(): a range with a whole-file formatter skips the fold path", fold_called, nil)
spec.check(
  "format(): a range with a whole-file formatter says it formats whole files",
  notes[1] ~= nil and notes[1]:find("whole files only", 1, true) ~= nil,
  true
)

notes = {}
attached = {
  lathe_client({ documentFormattingProvider = true, documentRangeFormattingProvider = {} }),
}
lathe.format(java_buf, { range = selection })
spec.check("format(): a range with a range formatter formats", fold_called, java_buf)
spec.check("format(): a range with a range formatter is not explained", #notes, 0)

notes = {}
fold_called = nil
attached = {
  lathe_client({
    experimental = { latheFormatterUnavailable = "The project's formatter is not resolved." },
  }),
}
lathe.format(java_buf)
spec.check("format(): no formatter skips the fold path", fold_called, nil)
spec.check(
  "format(): no formatter shows the server's reason",
  notes[1],
  "Lathe: The project's formatter is not resolved."
)

notes = {}
attached = { lathe_client({}) }
lathe.format(java_buf)
spec.check("format(): an older server's missing reason skips the fold path", fold_called, nil)
spec.check(
  "format(): an older server's missing reason says the server does not format",
  notes[1],
  "Lathe: the server does not format this workspace."
)

notes = {}
attached = { lathe_client({}, false) }
lathe.format(java_buf)
spec.check("format(): a starting server skips the fold path", fold_called, nil)
spec.check(
  "format(): a starting server says so, not that there is no formatter",
  notes[1] ~= nil and notes[1]:find("still starting", 1, true) ~= nil,
  true
)

notes = {}
attached = {}
lathe.format(java_buf)
spec.check("format(): no server skips the fold path", fold_called, nil)
spec.check(
  "format(): no server says it is not attached",
  notes[1] ~= nil and notes[1]:find("not attached", 1, true) ~= nil,
  true
)
vim.lsp.get_clients = real_get_clients
vim.notify = notify

-- A re-attach (:lsp restart) replaces the buffer's format-on-save hooks rather than adding more,
-- so a save formats once.
do
  require("lathe.format").setup(true)
  local real_get_client_by_id = vim.lsp.get_client_by_id
  vim.lsp.get_client_by_id = function(id)
    return { id = id, name = "lathe", server_capabilities = { documentFormattingProvider = true } }
  end
  local buf = vim.api.nvim_create_buf(true, false)
  for client_id = 1, 2 do
    vim.api.nvim_exec_autocmds("LspAttach", { buffer = buf, data = { client_id = client_id } })
  end
  vim.lsp.get_client_by_id = real_get_client_by_id

  spec.check(
    "re-attach: one format hook per buffer",
    #vim.api.nvim_get_autocmds({ group = "LatheFormatOnSave", event = "BufWritePre", buffer = buf }),
    1
  )
end

spec.finish()
