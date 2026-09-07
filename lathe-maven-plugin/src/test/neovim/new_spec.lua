-- Verifies lathe.new (:LatheNew): picks the kind, then takes one "[module:]package.Name" reply
-- (seeded from the buffer's context, scope inferred) and creates via the server's lathe.modules /
-- lathe.packages / lathe.resolveContext / lathe.createType commands, writing/opening the returned
-- file. The LSP client and the pickers are stubbed, so this loads headlessly. The client holds no
-- Java logic -- the server owns placement/skeleton/caret -- so the tests assert flow, parsing and IO.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/new_spec.lua

local spec = require("spec_helper").new()
local new = require("lathe.new")

-- A fake Lathe client answering executeCommand from `responses` (keyed by command); returns the
-- ordered list of {command, argument} it received.
local function stub_server(responses)
  local requests = {}
  vim.lsp.get_clients = function(_)
    return {
      {
        name = "lathe",
        request = function(_, _, params, callback, _)
          table.insert(requests, { command = params.command, argument = params.arguments[1] })
          callback(nil, responses[params.command])
        end,
      },
    }
  end
  return requests
end

local function request_for(requests, command)
  for _, request in ipairs(requests) do
    if request.command == command then
      return request.argument
    end
  end
end

local function item_by(items, predicate)
  for _, item in ipairs(items) do
    if predicate(item) then
      return item
    end
  end
end

-- Stub the two prompts from a plan: { kind=<label>, target=<string>, module=<name> }. The kind
-- select dispatches on label; the name input returns plan.target, or the seeded default when the
-- plan omits it (so a `test` plan exercises the derived <Name>Test default). The module select is
-- the ambiguous-module fallback.
local function stub_ui(plan)
  vim.ui.select = function(items, opts, cb)
    if opts.prompt == "What's new:" then
      cb(item_by(items, function(kind)
        return kind.label == plan.kind
      end))
    elseif opts.prompt == "Module:" then
      cb(plan.module)
    end
  end
  vim.ui.input = function(opts, cb)
    cb(plan.target ~= nil and plan.target or opts.default)
  end
end

-- ── pure helpers ─────────────────────────────────────────────────────────────

do
  spec.check("lines drop the trailing empty", table.concat(new._lines("a\nb\n"), "|"), "a|b")
  spec.check("lines keep interior blanks", table.concat(new._lines("a\n\nb\n"), "|"), "a||b")

  spec.check("test name appends suffix", new._test_name("Foo"), "FooTest")
  spec.check("test name keeps existing suffix", new._test_name("FooTest"), "FooTest")

  local qualified = new._parse_target("core:com.x.Foo", nil, { "core", "app" })
  spec.check("parse module prefix", qualified.module, "core")
  spec.check("parse package", qualified.pkg, "com.x")
  spec.check("parse name", qualified.name, "Foo")

  local anchored = new._parse_target("Bar", { moduleRel = "core", pkg = "com.x" }, { "core" })
  spec.check("parse bare name uses context module", anchored.module, "core")
  spec.check("parse bare name default package", anchored.pkg, "")
  spec.check("parse bare name", anchored.name, "Bar")

  local single = new._parse_target("com.y.Baz", nil, { "only" })
  spec.check("parse falls back to sole module", single.module, "only")
  spec.check("parse package from qualified", single.pkg, "com.y")

  local ambiguous = new._parse_target("Qux", nil, { "a", "b" })
  spec.check("parse leaves module unresolved when ambiguous", ambiguous.module == nil, true)

  local entries = {
    { module = "core", pkg = "com.x", scope = "test" },
    { module = "core", pkg = "com.y", scope = "main" },
  }
  spec.check("infer forces test for the test kind", new._infer_scope("test", "core", "com.x", entries, nil), "test")
  spec.check("infer keeps a main package's scope", new._infer_scope("class", "core", "com.y", entries, nil), "main")
  spec.check("infer keeps a test package's scope", new._infer_scope("class", "core", "com.x", entries, nil), "test")
  spec.check(
    "infer takes the buffer scope for a new package",
    new._infer_scope("class", "core", "com.z", {}, { moduleRel = "core", pkg = "com.z", scope = "test" }),
    "test"
  )
  spec.check("infer defaults a brand-new package to main", new._infer_scope("class", "core", "com.new", {}, nil), "main")

  local main_ctx = { moduleRel = "core", pkg = "com.example", scope = "main" }
  local test_ctx = { moduleRel = "core", pkg = "com.example", scope = "test" }
  spec.check("seed: test on a main class derives <Name>Test", new._default_target("test", main_ctx, false, "Foo"), "com.example.Foo" .. "Test")
  spec.check("seed: test in a test buffer is location-only", new._default_target("test", test_ctx, false, "FooTest"), "com.example.")
  spec.check("seed: a plain kind is location-only", new._default_target("class", main_ctx, false, "Foo"), "com.example.")
  spec.check("seed: multi-module carries the module prefix", new._default_target("test", { moduleRel = "core", pkg = "com.x", scope = "main" }, true, "Foo"), "core:com.x.FooTest")
  spec.check("seed: no context yields the bare derived test name", new._default_target("test", nil, false, "Foo"), "FooTest")

  spec.check("cmd completes kinds by prefix", table.concat(new._cmd_complete("te", "LatheNew te", 11), ","), "test")
  spec.check("cmd completes every kind on a fresh arg", #new._cmd_complete("", "LatheNew ", 9), 5)
end

-- ── create() end to end ──────────────────────────────────────────────────────

do -- anchored: single module, existing package, scope inferred from the package entry
  local path = vim.fn.tempname() .. "/core/src/main/java/com/example/core/Foo.java"
  local requests = stub_server({
    ["lathe.resolveContext"] = { moduleRel = "core", scope = "main", pkg = "com.example.core" },
    ["lathe.modules"] = { "core" },
    ["lathe.packages"] = {
      { pkg = "com.example.core", scope = "main" },
      { pkg = "com", scope = "main" },
    },
    ["lathe.createType"] = {
      path = path,
      content = "package com.example.core;\n\npublic class Foo {\n\n}\n",
      caret = { line = 3, character = 0 },
    },
  })
  stub_ui({ kind = "Class", target = "com.example.core.Foo" })

  new.create()

  local args = request_for(requests, "lathe.createType") or {}
  spec.check("createType type", args.type, "class")
  spec.check("createType moduleRel (single module)", args.moduleRel, "core")
  spec.check("createType scope inferred from the package entry", args.kind, "main")
  spec.check("createType pkg", args.pkg, "com.example.core")
  spec.check("createType name", args.name, "Foo")
  spec.check("file created", vim.fn.filereadable(path), 1)
  spec.check("buffer opened", vim.api.nvim_buf_get_name(0):match("Foo%.java$") ~= nil, true)

  -- The run above primed the completion cache; command-line completion serves targets from it.
  spec.check(
    "cmd completes targets from the primed cache",
    table.concat(new._cmd_complete("com.e", "LatheNew class com.e", 20), ","),
    "com.example.core"
  )
end

do -- cold start, multi-module: explicit module prefix, brand-new package -> scope defaults to main
  local requests = stub_server({
    ["lathe.resolveContext"] = nil,
    ["lathe.modules"] = { "app", "core" },
    ["lathe.packages"] = { { pkg = "", scope = "main" }, { pkg = "com.app", scope = "main" } },
    ["lathe.createType"] = {
      path = vim.fn.tempname() .. "/Sub.java",
      content = "package com.app.sub;\n\npublic class Sub {\n\n}\n",
      caret = { line = 3, character = 0 },
    },
  })
  stub_ui({ kind = "Class", target = "app:com.app.sub.Sub" })

  new.create()

  local args = request_for(requests, "lathe.createType") or {}
  spec.check("cold-start moduleRel from the module prefix", args.moduleRel, "app")
  spec.check("new package from the reply", args.pkg, "com.app.sub")
  spec.check("new package scope defaults to main", args.kind, "main")
  spec.check("createType name", args.name, "Sub")
end

do -- ambiguous module (no prefix, no context, several modules) -> falls back to the module pick
  local requests = stub_server({
    ["lathe.resolveContext"] = nil,
    ["lathe.modules"] = { "app", "core" },
    ["lathe.packages"] = { { pkg = "com.app", scope = "main" } },
    ["lathe.createType"] = {
      path = vim.fn.tempname() .. "/T.java",
      content = "package com.new;\n\npublic class T {\n\n}\n",
      caret = { line = 3, character = 0 },
    },
  })
  stub_ui({ kind = "Class", target = "com.new.T", module = "core" })

  new.create()

  local args = request_for(requests, "lathe.createType") or {}
  spec.check("ambiguous module taken from the pick", args.moduleRel, "core")
  spec.check("ambiguous module keeps the typed package", args.pkg, "com.new")
end

do -- test kind: derives <Name>Test from the buffer, keeps the package, forces test scope
  local dir = vim.fn.tempname()
  local subject = dir .. "/core/src/main/java/com/example/core/Foo.java"
  vim.fn.mkdir(vim.fn.fnamemodify(subject, ":h"), "p")
  vim.fn.writefile({ "package com.example.core;", "public class Foo {}" }, subject)
  vim.cmd.edit(vim.fn.fnameescape(subject))

  local requests = stub_server({
    ["lathe.resolveContext"] = { moduleRel = "core", scope = "main", pkg = "com.example.core" },
    ["lathe.modules"] = { "core" },
    ["lathe.packages"] = { { pkg = "com.example.core", scope = "main" } },
    ["lathe.createType"] = {
      path = dir .. "/core/src/test/java/com/example/core/FooTest.java",
      content = "package com.example.core;\n\nimport org.junit.jupiter.api.Test;\n\nclass FooTest {\n\n  @Test\n  void name() {\n\n  }\n}\n",
      caret = { line = 8, character = 0 },
    },
  })
  stub_ui({ kind = "Test" }) -- no target -> accept the seeded <Name>Test default

  new.create()

  local args = request_for(requests, "lathe.createType") or {}
  spec.check("test createType type", args.type, "test")
  spec.check("test scope forced", args.kind, "test")
  spec.check("test name derived from the buffer", args.name, "FooTest")
  spec.check("test package from the context", args.pkg, "com.example.core")
end

do -- kind passed as an argument skips the kind picker
  local requests = stub_server({
    ["lathe.resolveContext"] = { moduleRel = "core", scope = "main", pkg = "com.example" },
    ["lathe.modules"] = { "core" },
    ["lathe.packages"] = { { pkg = "com.example", scope = "main" } },
    ["lathe.createType"] = {
      path = vim.fn.tempname() .. "/Rec.java",
      content = "package com.example;\n\npublic record Rec() {\n}\n",
      caret = { line = 2, character = 0 },
    },
  })
  local picked = false
  vim.ui.select = function(_, _, _)
    picked = true
  end
  vim.ui.input = function(_, cb)
    cb("com.example.Rec")
  end

  new.create("record")

  local args = request_for(requests, "lathe.createType") or {}
  spec.check("argument sets the kind", args.type, "record")
  spec.check("argument skips the kind picker", picked, false)
end

do -- server not attached -> warns, no picker
  vim.lsp.get_clients = function(_)
    return {}
  end
  local picked = false
  vim.ui.select = function(_, _, _)
    picked = true
  end
  local warned = false
  vim.notify = function(_, _, _)
    warned = true
  end

  new.create()

  spec.check("no server warns", warned, true)
  spec.check("no server never opens the kind picker", picked, false)
end

do -- open() opens an existing file rather than overwriting it
  local path = vim.fn.tempname() .. "/Dup.java"
  vim.fn.mkdir(vim.fn.fnamemodify(path, ":h"), "p")
  vim.fn.writefile({ "existing" }, path)
  local notified = false
  vim.notify = function(_, _, _)
    notified = true
  end

  new._open({ path = path, content = "new\n", caret = { line = 0, character = 0 } })

  spec.check("existing file notifies", notified, true)
  spec.check("existing file not overwritten", table.concat(vim.fn.readfile(path), "\n"), "existing")
  spec.check("existing file opened", vim.api.nvim_buf_get_name(0):match("Dup%.java$") ~= nil, true)
end

do -- setup registers :LatheNew
  new.setup()
  spec.check("LatheNew registered", vim.fn.exists(":LatheNew"), 2)
end

spec.finish("new_spec")
