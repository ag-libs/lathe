-- Verifies lathe.new (:LatheNew): the pure placement/skeleton/caret helpers, and create() end to
-- end against a real temp workspace with vim.ui.select / vim.ui.input / vim.lsp.buf.format stubbed.
-- lathe.new uses only core Neovim APIs, so this loads headlessly like the other specs.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/new_spec.lua

local spec = require("spec_helper").new()
local new = require("lathe.new")

--- A fresh temp module with a seeded `com.example.Seed` main class; returns its package dir and the
--- seed file path. Each call is a unique tempname, so blocks do not collide.
local function tmp_workspace()
  local dir = vim.fn.tempname() .. "/mod/src/main/java/com/example"
  vim.fn.mkdir(dir, "p")
  local seed = dir .. "/Seed.java"
  vim.fn.writefile({ "package com.example;", "", "public class Seed {", "}" }, seed)
  return dir, seed
end

-- ── pure helpers ────────────────────────────────────────────────────────────

do
  spec.check(
    "package from lines",
    new._package_from_lines({ "// header", "package com.example.app;", "class X {}" }),
    "com.example.app"
  )
  spec.check("package from lines (none)", new._package_from_lines({ "class X {}" }), nil)
end

do
  spec.check(
    "package from dir (main)",
    new._package_from_dir("/ws/mod/src/main/java/com/example/app"),
    "com.example.app"
  )
  spec.check(
    "package from dir (test)",
    new._package_from_dir("/ws/mod/src/test/java/com/example/verify"),
    "com.example.verify"
  )
  spec.check("package from dir (at source root)", new._package_from_dir("/ws/mod/src/main/java"), "")
  spec.check("package from dir (not under a root)", new._package_from_dir("/ws/mod/misc/dir"), nil)
end

do
  spec.check(
    "class skeleton",
    table.concat(new._skeleton("class", "Foo", "com.example"), "\n"),
    "package com.example;\n\npublic class Foo {\n\n}"
  )
  spec.check(
    "record skeleton",
    table.concat(new._skeleton("record", "Foo", "com.example"), "\n"),
    "package com.example;\n\npublic record Foo() {\n}"
  )
  spec.check(
    "default-package skeleton omits the package line",
    table.concat(new._skeleton("interface", "Foo", ""), "\n"),
    "public interface Foo {\n\n}"
  )
end

do
  local caret = new._caret("class", "Foo", { "package p;", "", "public class Foo {", "", "}" })
  spec.check("class caret row (blank body line)", caret[1], 4)
  spec.check("class caret col (blank body line)", caret[2], 0)

  local rc = new._caret("record", "Foo", { "public record Foo() {", "}" })
  spec.check("record caret row (component list)", rc[1], 1)
  spec.check("record caret col (after '(')", rc[2], 18)
end

-- ── create() end to end ──────────────────────────────────────────────────────

do -- latheNew_fromJavaFile_createsSiblingInSamePackage
  local dir, seed = tmp_workspace()
  vim.cmd.edit(vim.fn.fnameescape(seed))
  new._format_on_save = false
  vim.ui.select = function(_, _, cb)
    cb("class")
  end
  vim.ui.input = function(_, cb)
    cb("Bar")
  end

  new.create()

  local created = dir .. "/Bar.java"
  spec.check("sibling created", vim.fn.filereadable(created), 1)
  spec.check(
    "sibling has same package + skeleton",
    table.concat(vim.fn.readfile(created), "\n"),
    "package com.example;\n\npublic class Bar {\n\n}"
  )
  spec.check("created buffer is opened", vim.api.nvim_buf_get_name(0):match("Bar%.java$") ~= nil, true)
end

do -- latheNew_recordKind_writesRecordSkeleton
  local dir, seed = tmp_workspace()
  vim.cmd.edit(vim.fn.fnameescape(seed))
  new._format_on_save = false
  vim.ui.select = function(_, _, cb)
    cb("record")
  end
  vim.ui.input = function(_, cb)
    cb("Point")
  end

  new.create()

  spec.check(
    "record sibling skeleton",
    table.concat(vim.fn.readfile(dir .. "/Point.java"), "\n"),
    "package com.example;\n\npublic record Point() {\n}"
  )
end

do -- latheNew_googleFormatterEnabled_normalisesViaOnSaveFormatter
  local _, seed = tmp_workspace()
  vim.cmd.edit(vim.fn.fnameescape(seed))
  local formatted = false
  vim.lsp.buf.format = function(_)
    formatted = true
  end
  new._format_on_save = true
  vim.ui.select = function(_, _, cb)
    cb("class")
  end
  vim.ui.input = function(_, cb)
    cb("Fmt")
  end

  new.create()

  spec.check("formatter invoked when enabled", formatted, true)
end

do -- latheNew_noFormatter_usesFallbackStyle
  local _, seed = tmp_workspace()
  vim.cmd.edit(vim.fn.fnameescape(seed))
  local formatted = false
  vim.lsp.buf.format = function(_)
    formatted = true
  end
  new._format_on_save = false
  vim.ui.select = function(_, _, cb)
    cb("class")
  end
  vim.ui.input = function(_, cb)
    cb("NoFmt")
  end

  new.create()

  spec.check("formatter not invoked when disabled", formatted, false)
end

do -- latheNew_noJavaContext_errorsCleanly
  vim.cmd("enew")
  local warned = false
  vim.notify = function(_, _)
    warned = true
  end
  vim.ui.select = function(_, _, _)
    error("must not prompt without a java context")
  end

  new.create()

  spec.check("no java context warns and does not prompt", warned, true)
end

do -- latheNew_existingFile_refusesToOverwrite
  local dir, seed = tmp_workspace()
  vim.cmd.edit(vim.fn.fnameescape(seed))
  local dup = dir .. "/Dup.java"
  vim.fn.writefile({ "package com.example;", "public class Dup {}" }, dup)
  local warned = false
  vim.notify = function(_, _)
    warned = true
  end
  vim.ui.select = function(_, _, cb)
    cb("class")
  end
  vim.ui.input = function(_, cb)
    cb("Dup")
  end

  new.create()

  spec.check("existing file refused", warned, true)
  spec.check(
    "existing file not overwritten",
    table.concat(vim.fn.readfile(dup), "\n"),
    "package com.example;\npublic class Dup {}"
  )
end

do -- setup registers :LatheNew and records the formatter flag
  new.setup({ format_on_save = true })
  spec.check("setup records the formatter flag", new._format_on_save, true)
  spec.check("LatheNew command registered", vim.fn.exists(":LatheNew"), 2)
end

spec.finish("new_spec")
