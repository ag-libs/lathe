-- Verifies a Java buffer keeps the indent character its file already uses: a tab-indented file
-- keeps tabs (new lines too), a space-indented or empty one spaces. .editorconfig's indent_style and
-- an indent the project's style pins, which its formatter writes with spaces, still decide over the
-- file.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-neovim/runtime" \
--     --cmd "set rtp+=lathe-neovim/test" \
--     -l lathe-neovim/test/indent_tabs_spec.lua

local spec = require("spec_helper").new()

-- -u NONE loads no plugins: the ftplugin and native EditorConfig run as in an editor.
vim.cmd("filetype plugin on")
vim.cmd.runtime("plugin/editorconfig.lua")

local TABS = table.concat({
  "/**",
  " * A class indented with tabs, as JUnit's are.",
  " */",
  "class Foo {",
  "\tvoid run() {",
  "\t\tint a = 1;",
  "\t}",
  "",
  "\t/**",
  "\t * One more.",
  "\t */",
  "\tvoid other() {",
  "\t}",
  "}",
  "",
}, "\n")

local SPACES = "class Foo {\n    void run() {\n        int a = 1;\n    }\n}\n"

-- A project holding Foo.java with the given content, and an .editorconfig and generated and
-- committed style files when given.
local function project(content, editorconfig, style, committed)
  local dir = vim.fn.tempname()
  vim.fn.mkdir(dir .. "/.lathe", "p")
  vim.fn.mkdir(dir .. "/src/main/java", "p")
  local files = {
    [".editorconfig"] = editorconfig or "root = true\n\n[*]\ncharset = utf-8\n",
    ["src/main/java/Foo.java"] = content,
    [".lathe/style.json"] = style,
    ["lathe-style.json"] = committed,
  }
  for name, text in pairs(files) do
    local f = assert(io.open(dir .. "/" .. name, "w"))
    f:write(text)
    f:close()
  end
  return dir
end

-- Opens the project's Java file, waits for the ftplugin's deferred step (after EditorConfig), and
-- opens a line below `int a = 1;`; returns 'expandtab' and the new line's leading whitespace.
local function open(dir)
  vim.cmd.edit(dir .. "/src/main/java/Foo.java")
  local buf = vim.api.nvim_get_current_buf()
  vim.wait(500, function()
    return vim.bo[buf].indentexpr:find("lathe", 1, true) ~= nil
  end)
  local expandtab = vim.bo[buf].expandtab
  vim.fn.cursor(vim.fn.search("int a = 1;", "n"), 1)
  vim.cmd("normal! ob = 2;")
  local typed = vim.api.nvim_get_current_line():match("^%s*")
  vim.cmd("bwipeout!")
  vim.fn.delete(dir, "rf")
  return expandtab, typed
end

local tabs, tabs_typed = open(project(TABS))
spec.check("tab-indented file: keeps tabs", tabs, false)
spec.check("tab-indented file: a new line indents with tabs", tabs_typed, "\t\t")

local spaces, spaces_typed = open(project(SPACES))
spec.check("space-indented file: spaces", spaces, true)
spec.check("space-indented file: a new line indents with spaces", spaces_typed, "        ")

local empty = open(project("class Foo {\nint a = 1;\n}\n"))
spec.check("file without indented lines: spaces", empty, true)

local blank_tabs =
  open(project("class Foo {\n\t\n\t\n\t\n\t\n    void run() {\n        int a = 1;\n    }\n}\n"))
spec.check("space-indented file with whitespace-only tab lines: spaces", blank_tabs, true)

local editorconfig = open(project(TABS, "root = true\n\n[*.java]\nindent_style = space\n"))
spec.check(".editorconfig's indent_style over the file's tabs", editorconfig, true)

local google =
  open(project(TABS, nil, '{"indent":{"profile":"google","block":2,"continuation":4}}'))
spec.check("google profile: spaces over the file's tabs", google, true)

local committed = open(project(TABS, nil, nil, '{"indent":{"block":4,"continuation":8}}'))
spec.check("committed widths: spaces over the file's tabs", committed, true)

spec.finish()
