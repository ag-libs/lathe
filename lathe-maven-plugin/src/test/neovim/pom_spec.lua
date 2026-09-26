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

-- Stdin validation labels errors with a "-:<line>:" prefix (no file on disk), which is the default
-- validate() path, so it must parse against the "-" filename.
spec.check("stdin '-' prefix parses to a diagnostic", #pom.parse_diagnostics({ "-:7: bad element" }, "-"), 1)

-- setup() wiring: validate on by default registers open (BufReadPost) + live (TextChanged/TextChangedI)
-- autocmds, and no longer the save-only BufWritePost; format off by default.
local function count(event)
  return #vim.api.nvim_get_autocmds({ group = "LathePom", event = event })
end

pom.setup({})
spec.check("default: validate wired on BufReadPost", count("BufReadPost"), 1)
spec.check("default: live validate wired on TextChanged", count("TextChanged"), 1)
spec.check("default: live validate wired on TextChangedI", count("TextChangedI"), 1)
spec.check("default: no save-only BufWritePost validate", count("BufWritePost"), 0)

-- validate=false, format=false: the augroup is cleared, no autocmds.
pom.setup({ validate = false, format = false })
spec.check("disabled: no LathePom autocmds", #vim.api.nvim_get_autocmds({ group = "LathePom" }), 0)

-- format=true (validate off): only the BufReadPost formatprg autocmd, no live-validate events.
pom.setup({ validate = false, format = true })
spec.check("format-only: BufReadPost wired", count("BufReadPost"), 1)
spec.check("format-only: no live TextChanged validate", count("TextChanged"), 0)

-- End-to-end against the real xmllint binary (schema already asserted present above): format_buffer
-- reindents/refuses, and validate() exercises the stdin path (buffer contents, no file on disk).
if vim.fn.executable("xmllint") == 1 then
  local function make_buf(lines)
    local buf = vim.api.nvim_create_buf(false, true)
    vim.api.nvim_buf_set_lines(buf, 0, -1, false, lines)
    return buf
  end

  -- format_buffer reindents a valid pom in place; and (the whole point) leaves an INVALID pom
  -- untouched rather than blanking it the way a naive `:%!xmllint` filter would.
  local ok_buf = make_buf({
    '<?xml version="1.0"?>',
    '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion></project>',
  })
  pom.format_buffer(ok_buf)
  spec.check("format_buffer expands a valid pom", #vim.api.nvim_buf_get_lines(ok_buf, 0, -1, false) > 2, true)

  local bad = { "<project><modelVersion>4.0.0</modelVersion>" } -- unclosed <project>
  local bad_buf = make_buf(bad)
  pom.format_buffer(bad_buf)
  spec.check(
    "format_buffer leaves an invalid pom untouched",
    vim.api.nvim_buf_get_lines(bad_buf, 0, -1, false)[1],
    bad[1]
  )

  -- validate() feeds the buffer contents to xmllint via stdin, so an invalid (unsaved) pom yields a
  -- diagnostic and a valid one yields none -- the live, unsaved-aware path.
  pom.setup({}) -- re-enable validate (a previous case left it off)
  local function diags_after_validate(buf_lines, expect_any)
    local buf = make_buf(buf_lines)
    pom.validate(buf)
    vim.wait(4000, function()
      return (#vim.diagnostic.get(buf) > 0) == expect_any
    end, 50)
    return vim.diagnostic.get(buf)
  end

  local invalid = diags_after_validate({
    '<?xml version="1.0"?>',
    '<project xmlns="http://maven.apache.org/POM/4.0.0">',
    "  <modelVersion>4.0.0</modelVersion>",
    "  <bogusElement>nope</bogusElement>",
    "</project>",
  }, true)
  spec.check("validate flags an invalid pom from buffer contents", #invalid >= 1, true)

  local valid = diags_after_validate({
    '<?xml version="1.0"?>',
    '<project xmlns="http://maven.apache.org/POM/4.0.0">',
    "  <modelVersion>4.0.0</modelVersion>",
    "  <groupId>com.example</groupId>",
    "  <artifactId>app</artifactId>",
    "  <version>1.0.0</version>",
    "</project>",
  }, false)
  spec.check("validate passes a valid pom from buffer contents", #valid, 0)
else
  spec.pending("xmllint end-to-end (format + validate)", "xmllint not installed")
end

spec.finish()
