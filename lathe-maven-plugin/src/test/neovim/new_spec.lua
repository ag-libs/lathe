-- Verifies lathe.new (:LatheNew) v4: a typed command whose type NAME is always a final prompt, with
-- the buffer context filling the location only when nothing is typed, an explicit
-- `[module:][scope:]package` location otherwise, and a guided vim.ui.select fallback when the location
-- (specifically the package) cannot resolve. Covers the two special kinds (package-info: no name;
-- module-info: seeded module name at the source root). The LSP client and pickers are stubbed, so the
-- client carries no Java logic -- the server owns placement/skeleton/caret -- and the tests assert
-- flow, parsing and IO.
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

-- A minimal lathe.createType stub result. Content/caret are irrelevant to these tests -- they assert
-- the request arguments and IO -- so this only has to be something _open can write and open.
local function result(path)
  return { path = path, content = "x\n", caret = { line = 0, character = 0 } }
end

-- Stub every prompt from a plan: kind (What's new:), module (Module:), package (Package: -- by pkg,
-- or "new" for the ＋ entry), and the vim.ui.input prompts (new package, module name, or the type
-- name). Absent plan fields fall through to the prompt's seeded default.
local function stub_ui(plan)
  vim.ui.select = function(items, opts, cb)
    if opts.prompt == "What's new:" then
      cb(item_by(items, function(kind)
        return kind.label == plan.kind
      end))
    elseif opts.prompt == "Module:" then
      cb(plan.module)
    elseif opts.prompt == "Package:" then
      cb(item_by(items, function(item)
        return plan.package == "new" and item.new or item.pkg == plan.package
      end))
    end
  end
  vim.ui.input = function(opts, cb)
    if opts.prompt == "New package: " then
      cb(plan.new_package ~= nil and plan.new_package or opts.default)
    elseif opts.prompt == "Module name: " then
      cb(plan.module_name ~= nil and plan.module_name or opts.default)
    else
      cb(plan.name ~= nil and plan.name or opts.default)
    end
  end
end

-- ── pure helpers ─────────────────────────────────────────────────────────────

do
  spec.check("lines drop the trailing empty", table.concat(new._lines("a\nb\n"), "|"), "a|b")
  spec.check("lines keep interior blanks", table.concat(new._lines("a\n\nb\n"), "|"), "a||b")

  local full = new._parse_location("core:test:com.x")
  spec.check("parse module", full.module, "core")
  spec.check("parse scope", full.scope, "test")
  spec.check("parse package", full.pkg, "com.x")

  local bare = new._parse_location("com.y")
  spec.check("parse bare package leaves module unset", bare.module == nil, true)
  spec.check("parse bare package leaves scope unset", bare.scope == nil, true)
  spec.check("parse bare package keeps the package", bare.pkg, "com.y")

  local scoped = new._parse_location("test:com.z")
  spec.check("parse scope-only prefix sets scope", scoped.scope, "test")
  spec.check("parse scope-only prefix leaves module unset", scoped.module == nil, true)

  local moduleOnly = new._parse_location("core:")
  spec.check("parse module-only keeps the module", moduleOnly.module, "core")
  spec.check("parse module-only yields an empty package", moduleOnly.pkg, "")

  local pkgs = {
    { pkg = "com.example.a", scope = "main" },
    { pkg = "com.example.b", scope = "main" },
    { pkg = "com.other", scope = "test" },
  }
  spec.check("base package is the common main prefix", new._base_package(pkgs), "com.example")
  spec.check("base package empty with no main packages", new._base_package({ { pkg = "com.t", scope = "test" } }), "")

  vim.cmd.edit(vim.fn.tempname() .. "/Foo.java")
  spec.check("test seed appends Test", new._test_seed(0), "FooTest")
  vim.cmd.edit(vim.fn.tempname() .. "/BarTest.java")
  spec.check("test seed keeps an existing suffix", new._test_seed(0), "BarTest")

  spec.check("cmd completes kinds by prefix", table.concat(new._cmd_complete("pa", "LatheNew pa", 11), ","), "package-info")
  spec.check("cmd completes every kind on a fresh arg", #new._cmd_complete("", "LatheNew ", 9), 7)

  -- Prime the completion cache, then assert position-aware location completion.
  stub_server({
    ["lathe.modules"] = { "core", "app" },
    ["lathe.packages"] = { { pkg = "com.core", scope = "main" } },
  })
  new._refresh_async()
  spec.check("complete head offers modules", vim.tbl_contains(new._complete_location("co"), "core:"), true)
  spec.check("complete head offers scope keywords", vim.tbl_contains(new._complete_location("te"), "test:"), true)
  spec.check(
    "complete after a module offers its packages",
    table.concat(new._complete_location("core:com"), ","),
    "core:com.core"
  )
end

-- ── create() end to end ──────────────────────────────────────────────────────

do -- anchored: no location typed, the buffer context fills module/scope/package; name is prompted
  local path = vim.fn.tempname() .. "/core/src/main/java/com/example/core/Foo.java"
  local requests = stub_server({
    ["lathe.resolveContext"] = { moduleRel = "core", scope = "main", pkg = "com.example.core" },
    ["lathe.createType"] = result(path),
  })
  stub_ui({ name = "Foo" })

  new.create("class")

  local args = request_for(requests, "lathe.createType") or {}
  spec.check("context: type", args.type, "class")
  spec.check("context: moduleRel", args.moduleRel, "core")
  spec.check("context: scope", args.kind, "main")
  spec.check("context: pkg", args.pkg, "com.example.core")
  spec.check("context: name from the prompt", args.name, "Foo")
  spec.check("context: file created", vim.fn.filereadable(path), 1)
end

do -- typed: an explicit module:scope:package location resolves on its own; name is still prompted
  local requests = stub_server({
    ["lathe.modules"] = { "core", "app" },
    ["lathe.createType"] = result(vim.fn.tempname() .. "/Bar.java"),
  })
  stub_ui({ name = "Bar" })

  new.create("class", "core:test:com.example.foo")

  local args = request_for(requests, "lathe.createType") or {}
  spec.check("typed: moduleRel from the prefix", args.moduleRel, "core")
  spec.check("typed: scope from the keyword", args.kind, "test")
  spec.check("typed: package", args.pkg, "com.example.foo")
  spec.check("typed: name from the prompt", args.name, "Bar")
end

do -- typed, sole module, package only: module resolves to the only one, scope defaults to main
  local requests = stub_server({
    ["lathe.modules"] = { "only" },
    ["lathe.createType"] = result(vim.fn.tempname() .. "/Baz.java"),
  })
  stub_ui({ name = "Baz" })

  new.create("class", "com.example.baz")

  local args = request_for(requests, "lathe.createType") or {}
  spec.check("typed sole-module: moduleRel", args.moduleRel, "only")
  spec.check("typed sole-module: package", args.pkg, "com.example.baz")
  spec.check("typed sole-module: scope defaults to main", args.kind, "main")
end

do -- guided: bare :LatheNew picks kind, module, then an existing package (scope from its entry)
  local requests = stub_server({
    ["lathe.modules"] = { "core", "app" },
    ["lathe.packages"] = { { pkg = "com.app", scope = "main" }, { pkg = "com.app.util", scope = "test" } },
    ["lathe.createType"] = result(vim.fn.tempname() .. "/G.java"),
  })
  stub_ui({ kind = "Class", module = "app", package = "com.app", name = "G" })

  new.create()

  local args = request_for(requests, "lathe.createType") or {}
  spec.check("guided: moduleRel from the pick", args.moduleRel, "app")
  spec.check("guided: package from the pick", args.pkg, "com.app")
  spec.check("guided: scope from the picked package entry", args.kind, "main")
  spec.check("guided: name from the prompt", args.name, "G")
end

do -- no context + no location: routes to the guided picker rather than a source-root default package
  local requests = stub_server({
    ["lathe.resolveContext"] = nil,
    ["lathe.modules"] = { "only" },
    ["lathe.packages"] = { { pkg = "com.only", scope = "main" } },
    ["lathe.createType"] = result(vim.fn.tempname() .. "/H.java"),
  })
  stub_ui({ package = "com.only", name = "H" })

  new.create("class")

  local args = request_for(requests, "lathe.createType") or {}
  spec.check("no-context: routed to the guided package pick", args.pkg, "com.only")
  spec.check("no-context: package never empty by omission", args.pkg ~= "", true)
  spec.check("no-context: name", args.name, "H")
end

do -- package-info: no name prompt, the fixed stem is sent, package from context
  local requests = stub_server({
    ["lathe.resolveContext"] = { moduleRel = "core", scope = "main", pkg = "com.example.core" },
    ["lathe.createType"] = result(vim.fn.tempname() .. "/package-info.java"),
  })
  local name_prompted = false
  vim.ui.select = function(_, _, _) end
  vim.ui.input = function(opts, cb)
    name_prompted = true
    cb(opts.default)
  end

  new.create("package-info")

  local args = request_for(requests, "lathe.createType") or {}
  spec.check("package-info: type", args.type, "package-info")
  spec.check("package-info: package from context", args.pkg, "com.example.core")
  spec.check("package-info: name is the fixed stem", args.name, "package-info")
  spec.check("package-info: no name prompt", name_prompted, false)
end

do -- module-info: prompts a name seeded with the module's base package, forces the main root
  local requests = stub_server({
    ["lathe.resolveContext"] = { moduleRel = "core", scope = "main", pkg = "com.example.core" },
    ["lathe.packages"] = {
      { pkg = "com.example.core", scope = "main" },
      { pkg = "com.example.util", scope = "main" },
    },
    ["lathe.createType"] = result(vim.fn.tempname() .. "/module-info.java"),
  })
  local seed
  vim.ui.select = function(_, _, _) end
  vim.ui.input = function(opts, cb)
    seed = opts.default
    cb(opts.default)
  end

  new.create("module-info")

  local args = request_for(requests, "lathe.createType") or {}
  spec.check("module-info: type", args.type, "module-info")
  spec.check("module-info: scope forced to main", args.kind, "main")
  spec.check("module-info: name seeded from the base package", args.name, "com.example")
  spec.check("module-info: the seed shown was the base package", seed, "com.example")
end

do -- kind passed as an argument skips the kind picker
  local requests = stub_server({
    ["lathe.resolveContext"] = { moduleRel = "core", scope = "main", pkg = "com.example" },
    ["lathe.createType"] = result(vim.fn.tempname() .. "/Rec.java"),
  })
  local picked = false
  vim.ui.select = function(_, _, _)
    picked = true
  end
  vim.ui.input = function(_, cb)
    cb("Rec")
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

do -- _open opens an existing file rather than overwriting it
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
