-- Verifies lathe.new (:LatheNew): picks the kind, then narrows module -> package (or a new package)
-- via the server's lathe.modules / lathe.packages / lathe.resolveContext / lathe.createType commands,
-- and writes/opens the returned file. The LSP client and the pickers are stubbed, so this loads
-- headlessly. The client holds no Java logic -- the server owns placement/skeleton/caret -- so the
-- tests assert flow and IO.
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

-- Stub the pickers from a plan: { kind=<label>, module=<name>, package="new"|<pkg>, scope=<wire>,
-- new_pkg=<string>, name=<string> }. Selects dispatch on the prompt; inputs on whether it is the
-- new-package prompt.
local function stub_ui(plan)
  vim.ui.select = function(items, opts, cb)
    if opts.prompt == "New Lathe type:" then
      cb(item_by(items, function(k)
        return k.label == plan.kind
      end))
    elseif opts.prompt == "Module:" then
      cb(plan.module)
    elseif opts.prompt:match("^Package in") then
      cb(item_by(items, function(it)
        return plan.package == "new" and it.new or it.pkg == plan.package
      end))
    elseif opts.prompt:match("^Source scope") then
      cb(plan.scope)
    end
  end
  vim.ui.input = function(opts, cb)
    cb(opts.prompt:match("^New package") and plan.new_pkg or plan.name)
  end
end

-- ── pure helpers ─────────────────────────────────────────────────────────────

do
  spec.check("lines drop the trailing empty", table.concat(new._lines("a\nb\n"), "|"), "a|b")
  spec.check("lines keep interior blanks", table.concat(new._lines("a\n\nb\n"), "|"), "a||b")

  local list = { "a", "b", "c" }
  new._prefer(list, function(x)
    return x == "c"
  end)
  spec.check("prefer moves the match to the front", table.concat(list, ","), "c,a,b")
  new._prefer(list, function(x)
    return x == "zzz"
  end)
  spec.check("prefer no match leaves order", table.concat(list, ","), "c,a,b")

  spec.check("format new package", new._format_package({ new = true }), "＋ New package…")
  spec.check("format main package", new._format_package({ pkg = "com.a", scope = "main" }), "com.a")
  spec.check(
    "format test package tags scope",
    new._format_package({ pkg = "com.a", scope = "test" }),
    "com.a (test)"
  )
  spec.check(
    "format default package",
    new._format_package({ pkg = "", scope = "main" }),
    "(default package)"
  )
end

-- ── create() end to end ──────────────────────────────────────────────────────

do -- anchored: single module, existing package preselected from context, scope from the entry
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
  stub_ui({ kind = "Class", package = "com.example.core", name = "Foo" })

  new.create()

  local args = request_for(requests, "lathe.createType") or {}
  spec.check("createType type", args.type, "class")
  spec.check("createType moduleRel (single module)", args.moduleRel, "core")
  spec.check("createType scope from the package entry", args.kind, "main")
  spec.check("createType pkg", args.pkg, "com.example.core")
  spec.check("createType name", args.name, "Foo")
  spec.check("file created", vim.fn.filereadable(path), 1)
  spec.check("buffer opened", vim.api.nvim_buf_get_name(0):match("Foo%.java$") ~= nil, true)
end

do -- cold start: no context, pick module, then a new package, scope defaults to main (main-only)
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
  stub_ui({ kind = "Class", module = "app", package = "new", new_pkg = "com.app.sub", name = "Sub" })

  new.create()

  local args = request_for(requests, "lathe.createType") or {}
  spec.check("cold-start moduleRel from module pick", args.moduleRel, "app")
  spec.check("new package from input", args.pkg, "com.app.sub")
  spec.check("new package scope defaults to main", args.kind, "main")
  spec.check("createType name", args.name, "Sub")
end

do -- new package in a module with both roots and no anchor -> asks the scope
  local requests = stub_server({
    ["lathe.resolveContext"] = nil,
    ["lathe.modules"] = { "core" },
    ["lathe.packages"] = {
      { pkg = "com.example", scope = "main" },
      { pkg = "com.example", scope = "test" },
    },
    ["lathe.createType"] = {
      path = vim.fn.tempname() .. "/T.java",
      content = "package com.new;\n\npublic class T {\n\n}\n",
      caret = { line = 3, character = 0 },
    },
  })
  stub_ui({ kind = "Class", package = "new", new_pkg = "com.new", scope = "test", name = "T" })

  new.create()

  local args = request_for(requests, "lathe.createType") or {}
  spec.check("new package scope taken from the scope prompt", args.kind, "test")
  spec.check("new package pkg", args.pkg, "com.new")
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

do -- open() refuses to overwrite an existing file
  local path = vim.fn.tempname() .. "/Dup.java"
  vim.fn.mkdir(vim.fn.fnamemodify(path, ":h"), "p")
  vim.fn.writefile({ "existing" }, path)
  local warned = false
  vim.notify = function(_, _, _)
    warned = true
  end

  new._open({ path = path, content = "new\n", caret = { line = 0, character = 0 } })

  spec.check("existing file warns", warned, true)
  spec.check("existing file not overwritten", table.concat(vim.fn.readfile(path), "\n"), "existing")
end

do -- setup registers :LatheNew
  new.setup()
  spec.check("LatheNew registered", vim.fn.exists(":LatheNew"), 2)
end

spec.finish("new_spec")
