-- Verifies lathe.new (:LatheNewClass/Interface/Record/Enum): the pure placement/skeleton/caret
-- helpers, and create_kind() end to end against a real temp workspace with vim.ui.input /
-- vim.lsp.buf.format stubbed. lathe.new uses only core Neovim APIs, so this loads headlessly.
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
  local root = vim.fn.tempname()
  vim.fn.mkdir(root .. "/.lathe", "p") -- workspace root marker, above the module
  local dir = root .. "/mod/src/main/java/com/example"
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

-- ── create_kind() end to end ─────────────────────────────────────────────────

do -- latheNew_fromJavaFile_createsSiblingInSamePackage (name as argument, zero prompts)
  local dir, seed = tmp_workspace()
  vim.cmd.edit(vim.fn.fnameescape(seed))
  new._format_on_save = false

  new.create_kind("class", "Bar")

  local created = dir .. "/Bar.java"
  spec.check("sibling created", vim.fn.filereadable(created), 1)
  spec.check(
    "sibling has same package + skeleton",
    table.concat(vim.fn.readfile(created), "\n"),
    "package com.example;\n\npublic class Bar {\n\n}"
  )
  spec.check("created buffer is opened", vim.api.nvim_buf_get_name(0):match("Bar%.java$") ~= nil, true)
end

do -- latheNew_noArgument_promptsForName (the single prompt, showing the package)
  local dir, seed = tmp_workspace()
  vim.cmd.edit(vim.fn.fnameescape(seed))
  new._format_on_save = false
  local prompt
  vim.ui.input = function(opts, cb)
    prompt = opts.prompt
    cb("Prompted")
  end

  new.create_kind("class")

  spec.check("prompt shows the workspace-relative dir", prompt, "class name in mod/src/main/java/com/example: ")
  spec.check("prompted name creates the file", vim.fn.filereadable(dir .. "/Prompted.java"), 1)
end

do -- latheNew_recordKind_writesRecordSkeleton
  local dir, seed = tmp_workspace()
  vim.cmd.edit(vim.fn.fnameescape(seed))
  new._format_on_save = false

  new.create_kind("record", "Point")

  spec.check(
    "record sibling skeleton",
    table.concat(vim.fn.readfile(dir .. "/Point.java"), "\n"),
    "package com.example;\n\npublic record Point() {\n}"
  )
end

do -- latheNew_googleFormatterEnabled_normalisesViaOnSaveFormatter
  local _, seed = tmp_workspace()
  vim.cmd.edit(vim.fn.fnameescape(seed))
  -- pretend a Lathe client is attached so the attach-wait guard passes
  vim.lsp.get_clients = function(_)
    return { { name = "lathe" } }
  end
  local formatted = false
  vim.lsp.buf.format = function(_)
    formatted = true
  end
  new._format_on_save = true

  new.create_kind("class", "Fmt")

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

  new.create_kind("class", "NoFmt")

  spec.check("formatter not invoked when disabled", formatted, false)
end

do -- latheNew_noJavaContext_errorsCleanly
  vim.cmd("enew")
  local warned = false
  vim.notify = function(_, _)
    warned = true
  end

  new.create_kind("class", "Anything")

  spec.check("no java context warns", warned, true)
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

  new.create_kind("class", "Dup")

  spec.check("existing file refused", warned, true)
  spec.check(
    "existing file not overwritten",
    table.concat(vim.fn.readfile(dup), "\n"),
    "package com.example;\npublic class Dup {}"
  )
end

-- ── v2 dotted-name pure helpers ──────────────────────────────────────────────

do
  local p, n = new._split_qualified("com.example.Foo")
  spec.check("split qualified package", p, "com.example")
  spec.check("split qualified name", n, "Foo")
  local bare_p, bare_n = new._split_qualified("Foo")
  spec.check("split bare package (nil)", bare_p, nil)
  spec.check("split bare name", bare_n, "Foo")
end

do
  spec.check("source root (main)", new._source_root("/ws/mod/src/main/java/com/example"), "/ws/mod/src/main/java")
  spec.check("source root (test)", new._source_root("/ws/mod/src/test/java/com/example"), "/ws/mod/src/test/java")
  spec.check("source root (at root)", new._source_root("/ws/mod/src/main/java"), "/ws/mod/src/main/java")
  spec.check("source root (none)", new._source_root("/ws/mod/loose/dir"), nil)
end

do
  local d, p, n = new._target("/ws/mod/src/main/java/com/example", "com.example", "Foo")
  spec.check("target bare keeps context dir", d, "/ws/mod/src/main/java/com/example")
  spec.check("target bare keeps context package", p, "com.example")
  spec.check("target bare name", n, "Foo")

  local dd, dp, dn = new._target("/ws/mod/src/main/java/com/example", "com.example", "a.b.C")
  spec.check("target dotted dir under source root", dd, "/ws/mod/src/main/java/a/b")
  spec.check("target dotted package", dp, "a.b")
  spec.check("target dotted simple name", dn, "C")

  spec.check("target dotted with no source root -> nil", new._target("/ws/mod/loose", "", "a.b.C"), nil)
end

do
  spec.check(
    "relativize under root",
    new._relativize("/ws/mod/src/main/java/com/x", "/ws"),
    "mod/src/main/java/com/x"
  )
  spec.check("relativize with no root -> absolute", new._relativize("/ws/mod/x", nil), "/ws/mod/x")
end

-- ── v2 dotted-name create() end to end ───────────────────────────────────────

do -- latheNew_dottedName_createsUnderSourceRootMakingDirs
  local dir, seed = tmp_workspace()
  local root = dir:gsub("/com/example$", "")
  vim.cmd.edit(vim.fn.fnameescape(seed))
  new._format_on_save = false

  new.create_kind("class", "a.b.C")

  local created = root .. "/a/b/C.java"
  spec.check("dotted-name file created under source root", vim.fn.filereadable(created), 1)
  spec.check(
    "dotted-name package + skeleton",
    table.concat(vim.fn.readfile(created), "\n"),
    "package a.b;\n\npublic class C {\n\n}"
  )
end

do -- latheNew_dottedName_fromTestFile_usesTestRoot
  local tdir = vim.fn.tempname() .. "/mod/src/test/java/com/example"
  vim.fn.mkdir(tdir, "p")
  local seed = tdir .. "/SeedTest.java"
  vim.fn.writefile({ "package com.example;", "", "public class SeedTest {", "}" }, seed)
  local root = tdir:gsub("/com/example$", "")
  vim.cmd.edit(vim.fn.fnameescape(seed))
  new._format_on_save = false

  new.create_kind("class", "x.y.Z")

  spec.check("dotted-name from a test file uses the test root", vim.fn.filereadable(root .. "/x/y/Z.java"), 1)
end

do -- latheNew_dottedName_noSourceRoot_warnsAndBails
  local ldir = vim.fn.tempname() .. "/loose"
  vim.fn.mkdir(ldir, "p")
  local loose = ldir .. "/Loose.java"
  vim.fn.writefile({ "public class Loose {}" }, loose)
  vim.cmd.edit(vim.fn.fnameescape(loose))
  local warned = false
  vim.notify = function(_, _)
    warned = true
  end

  new.create_kind("class", "a.b.C")

  spec.check("dotted name with no source root warns", warned, true)
  spec.check("dotted name with no source root creates nothing", vim.fn.filereadable(ldir .. "/a/b/C.java"), 0)
end

do -- resolveContext_relativeBufferName_returnsAbsoluteDir (never create in the cwd)
  local base = vim.fn.tempname()
  vim.fn.mkdir(base, "p")
  local saved = vim.fn.getcwd()
  vim.cmd("cd " .. vim.fn.fnameescape(base))
  local cwd = vim.fn.getcwd()
  local dir = select(1, new._resolve_context(vim.api.nvim_get_current_buf(), "sub/pkg/Foo.java"))
  vim.cmd("cd " .. vim.fn.fnameescape(saved))
  spec.check("relative buffer name resolves to an absolute dir", dir, cwd .. "/sub/pkg")
end

do -- setup registers the four kind commands and records the formatter flag
  new.setup({ format_on_save = true })
  spec.check("setup records the formatter flag", new._format_on_save, true)
  spec.check("LatheNewClass registered", vim.fn.exists(":LatheNewClass"), 2)
  spec.check("LatheNewInterface registered", vim.fn.exists(":LatheNewInterface"), 2)
  spec.check("LatheNewRecord registered", vim.fn.exists(":LatheNewRecord"), 2)
  spec.check("LatheNewEnum registered", vim.fn.exists(":LatheNewEnum"), 2)
end

spec.finish("new_spec")
