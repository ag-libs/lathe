-- Verifies the client-side pom.xml support in lathe.pom: the bundled schema is discoverable on the
-- runtimepath, xmllint stderr parses into diagnostics on the right lines (ignoring the source/caret
-- and summary lines), and setup() wires the validate/format autocmds per the options.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/pom_spec.lua

local spec = require("spec_helper").new()

local pom = require("lathe.pom")

-- The bundled Maven POM XSD is shipped under src/main/neovim/schema and found on the runtimepath
-- (run-specs.sh adds src/main/neovim), for both the cache bundle and the standalone plugin.
local schema = pom.schema_path()
spec.check("schema discovered on runtimepath", type(schema) == "string", true)
spec.check(
  "schema is maven-4.0.0.xsd",
  type(schema) == "string" and schema:match("schema/maven%-4%.0%.0%.xsd$") ~= nil,
  true
)

-- A real xmllint --schema error triple maps to exactly one diagnostic on the 0-based line; the
-- source line, the caret line, and the trailing "fails to validate" summary are ignored.
local file = "/tmp/proj/pom.xml"
local stderr = {
  file .. ":14: element dependencyy: Schemas validity error : Element '{...}dependencyy': This element is not expected.",
  "    <dependencyy>",
  "     ^",
  file .. " fails to validate",
}
local diags = pom.parse_diagnostics(stderr, file)
spec.check("one diagnostic parsed from the triple", #diags, 1)
spec.check("diagnostic line is 0-based (14 -> 13)", diags[1] and diags[1].lnum, 13)
spec.check("diagnostic severity is ERROR", diags[1] and diags[1].severity, vim.diagnostic.severity.ERROR)
spec.check("diagnostic source is xmllint", diags[1] and diags[1].source, "xmllint")
spec.check(
  "diagnostic message preserved",
  diags[1] and diags[1].message:match("^element dependencyy:") ~= nil,
  true
)

-- Clean validation (no stderr) yields no diagnostics.
spec.check("no diagnostics when valid", #pom.parse_diagnostics({}, file), 0)

-- A stray line for a different file (not the validated pom) is not attributed to this buffer.
spec.check(
  "unrelated file line ignored",
  #pom.parse_diagnostics({ "/other/pom.xml:3: something" }, file),
  0
)

-- setup() wiring: validate on by default registers the open/save autocmds; format off by default.
local function count(event)
  return #vim.api.nvim_get_autocmds({ group = "LathePom", event = event })
end

pom.setup({})
spec.check("default: validate wired on BufWritePost", count("BufWritePost"), 1)
spec.check("default: validate wired on BufReadPost", count("BufReadPost"), 1)

-- validate=false, format=false: the augroup is cleared, no autocmds.
pom.setup({ validate = false, format = false })
spec.check("disabled: no LathePom autocmds", #vim.api.nvim_get_autocmds({ group = "LathePom" }), 0)

-- format=true (validate off): only the BufReadPost formatprg autocmd.
pom.setup({ validate = false, format = true })
spec.check("format-only: BufReadPost wired", count("BufReadPost"), 1)
spec.check("format-only: no BufWritePost validate", count("BufWritePost"), 0)

-- format_buffer reindents a valid pom in place; and (the whole point) leaves an INVALID pom
-- untouched rather than blanking it the way a naive `:%!xmllint` filter would.
if vim.fn.executable("xmllint") == 1 then
  local ok_buf = vim.api.nvim_create_buf(false, true)
  vim.api.nvim_buf_set_lines(ok_buf, 0, -1, false, {
    '<?xml version="1.0"?>',
    '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion></project>',
  })
  pom.format_buffer(ok_buf)
  spec.check("format_buffer expands a valid pom", #vim.api.nvim_buf_get_lines(ok_buf, 0, -1, false) > 2, true)

  local bad_buf = vim.api.nvim_create_buf(false, true)
  local bad = { "<project><modelVersion>4.0.0</modelVersion>" } -- unclosed <project>
  vim.api.nvim_buf_set_lines(bad_buf, 0, -1, false, bad)
  pom.format_buffer(bad_buf)
  spec.check(
    "format_buffer leaves an invalid pom untouched",
    vim.api.nvim_buf_get_lines(bad_buf, 0, -1, false)[1],
    bad[1]
  )
else
  spec.pending("format_buffer end-to-end", "xmllint not installed")
end

spec.finish()
