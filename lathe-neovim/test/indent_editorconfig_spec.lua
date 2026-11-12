-- Verifies who decides a Java buffer's indent when a project's .editorconfig disagrees: the
-- project's style (its formatter's widths, or a committed indent) wins over .editorconfig, and
-- .editorconfig wins over an editorconfig profile and over the personal setup() fallback.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-neovim/runtime" \
--     --cmd "set rtp+=lathe-neovim/test" \
--     -l lathe-neovim/test/indent_editorconfig_spec.lua

local spec = require("spec_helper").new()

-- -u NONE loads no plugins: the ftplugin and native EditorConfig run as in an editor.
vim.cmd("filetype plugin on")
vim.cmd.runtime("plugin/editorconfig.lua")

local indent = require("lathe.indent")

-- A project whose .editorconfig asks for 4-wide tabs in Java, with the given style files.
local function project(generated, committed)
  local dir = vim.fn.tempname()
  vim.fn.mkdir(dir .. "/.lathe", "p")
  vim.fn.mkdir(dir .. "/src/main/java", "p")
  local files = {
    [".editorconfig"] = "root = true\n\n[*.java]\nindent_style = tab\nindent_size = 4\ntab_width = 4\n",
    ["src/main/java/Foo.java"] = "class Foo {}\n",
    [".lathe/style.json"] = generated,
    ["lathe-style.json"] = committed,
  }
  for name, content in pairs(files) do
    local f = assert(io.open(dir .. "/" .. name, "w"))
    f:write(content)
    f:close()
  end
  return dir
end

-- Opens the project's Java file and waits for the ftplugin's deferred step, which runs after
-- EditorConfig; returns the buffer's indent settings.
local function open(dir)
  vim.cmd.edit(dir .. "/src/main/java/Foo.java")
  local buf = vim.api.nvim_get_current_buf()
  -- Neovim's own Java indent sets an indentexpr first; Lathe's comes with its deferred step.
  vim.wait(500, function()
    return vim.bo[buf].indentexpr:find("lathe", 1, true) ~= nil
  end)
  local settings = {
    shiftwidth = vim.bo[buf].shiftwidth,
    expandtab = vim.bo[buf].expandtab,
    continuation = vim.b[buf].lathe_continuation,
  }
  vim.cmd("bwipeout!")
  vim.fn.delete(dir, "rf")
  return settings
end

indent.setup({ indent = { profile = "google" } })

local formatter = open(project('{"indent":{"profile":"google","block":2,"continuation":4}}', nil))
spec.check("formatter's indent: block over .editorconfig", formatter.shiftwidth, 2)
spec.check("formatter's indent: spaces over .editorconfig's tabs", formatter.expandtab, true)
spec.check("formatter's indent: continuation", formatter.continuation, 4)

local committed = open(
  project(
    '{"indent":{"profile":"google","block":2,"continuation":4}}',
    '{"indent":{"profile":"google","block":4,"continuation":8}}'
  )
)
spec.check("committed indent: block over .editorconfig", committed.shiftwidth, 4)
spec.check("committed indent: continuation", committed.continuation, 8)
spec.check("committed indent: spaces", committed.expandtab, true)

local widths_only = open(
  project(
    '{"indent":{"profile":"editorconfig","block":0,"continuation":0}}',
    '{"indent":{"block":4,"continuation":8}}'
  )
)
spec.check(
  "committed widths without a profile: block over .editorconfig",
  widths_only.shiftwidth,
  4
)
spec.check("committed widths without a profile: spaces", widths_only.expandtab, true)

local eclipse =
  open(project('{"indent":{"profile":"editorconfig","block":0,"continuation":0}}', nil))
spec.check("editorconfig profile: .editorconfig's tabs", eclipse.expandtab, false)
spec.check("editorconfig profile: .editorconfig's width", eclipse.shiftwidth, 4)

local fallback = open(project('{"formatter":{"engine":"none"}}', nil))
spec.check("setup() fallback: .editorconfig wins", fallback.expandtab, false)
spec.check("setup() fallback: .editorconfig's width", fallback.shiftwidth, 4)

spec.finish()
