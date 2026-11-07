-- Verifies lathe.new (:LatheNew) v5: kind (arg or pick) -> a single fuzzy destination pick (Lathe's
-- own built-in `matchfuzzy` picker -- lathe.pick -- so the fuzzy experience is identical regardless of
-- the user's plugins) -> a validated type-name prompt. The buffer context still fills the destination
-- for an anchored `:LatheNew <kind>`; a typed `:LatheNew <kind> <pkg>` resolves a fuzzy-completed
-- package; bare `:LatheNew` is fully guided. Scope rides on the picked row (so a class can land in the
-- test root), and `＋ New package…` asks scope only when the module has both roots. The LSP client and
-- lathe.pick are stubbed, so the tests assert flow, ordering and IO -- the server owns
-- placement/skeleton/caret, and lathe.pick owns the picker UI (covered by pick_spec).
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-neovim/runtime" \
--     --cmd "set rtp+=lathe-neovim/test" \
--     -l lathe-neovim/test/new_spec.lua

local helper = require("spec_helper")
local spec = helper.new()
local new = require("lathe.new")
local pick = require("lathe.pick")

-- A fake Lathe client answering executeCommand from `responses` (keyed by command); a function
-- answer gets the request's argument and returns the result, or nil and an error. Returns the
-- ordered list of requests it received.
local function stub_server(responses)
  return helper.fake_client(function(_, _, params, callback, _)
    local answer = responses[params.command]
    if type(answer) == "function" then
      local value, err = answer(params.arguments and params.arguments[1])
      return callback(err, value)
    end

    callback(nil, answer)
  end)
end

-- lathe.types.destinations over `rows`, given in the server's rank order: every row, or a typed
-- package's rows.
local function destinations(rows)
  return function(args)
    if not args.pkg or args.pkg == "" then
      return rows
    end

    return vim.tbl_filter(function(row)
      return row.pkg == args.pkg
    end, rows)
  end
end

local function request_for(requests, command)
  for _, request in ipairs(requests) do
    if request.command == command then
      return request.arguments[1]
    end
  end
end

local function last_request_for(requests, command)
  local found
  for _, request in ipairs(requests) do
    if request.command == command then
      found = request.arguments[1]
    end
  end
  return found
end

local function item_by(items, predicate)
  for _, item in ipairs(items) do
    if predicate(item) then
      return item
    end
  end
end

-- A minimal lathe.types.create stub result. Content/caret are irrelevant to these tests -- they assert
-- the request arguments and IO -- so this only has to be something _open can write and open.
local function result(path)
  return { path = path, content = "x\n", caret = { line = 0, character = 0 } }
end

-- Stub every pick from a plan: kind (New:), the destination (Where: -- an existing row by `package`
-- [+ optional `pick_scope`]), module (Module:), and the vim.ui.input prompts (module-info name, or
-- the type name -- which may be dotted, `sub.pkg.Name`). Absent plan fields fall to the seeded default.
local function stub_ui(plan)
  pick.pick = function(opts)
    local items = opts.items
    if opts.title == "New:" then
      opts.on_choice(item_by(items, function(kind)
        return kind.label == plan.kind
      end))
    elseif opts.title == "Module:" then
      opts.on_choice(plan.module)
    elseif opts.title == "Where:" then
      opts.on_choice(item_by(items, function(item)
        return item.pkg == plan.package and (not plan.pick_scope or item.scope == plan.pick_scope)
      end))
    end
  end
  vim.ui.input = function(opts, cb)
    if opts.prompt == "Module name: " then
      cb(plan.module_name ~= nil and plan.module_name or opts.default)
    else
      -- the class-name prompt is seeded with the package; the user appends plan.name (a class name
      -- or sub.pkg.Class), or accepts the seed as-is when plan.name is absent.
      cb(plan.name ~= nil and ((opts.default or "") .. plan.name) or opts.default)
    end
  end
end

-- ── pure helpers ─────────────────────────────────────────────────────────────

do
  spec.check("lines drop the trailing empty", table.concat(new._lines("a\nb\n"), "|"), "a|b")
  spec.check("lines keep interior blanks", table.concat(new._lines("a\n\nb\n"), "|"), "a||b")

  spec.check(
    "float_module leads with the context module",
    table.concat(new._float_module({ "a", "b", "c" }, "c"), ","),
    "c,a,b"
  )
  spec.check(
    "float_module keeps order when context absent",
    table.concat(new._float_module({ "a", "b" }, "z"), ","),
    "a,b"
  )

  vim.cmd.edit(vim.fn.tempname() .. "/Foo.java")
  spec.check("test seed appends Test", new._test_seed(0), "FooTest")
  vim.cmd.edit(vim.fn.tempname() .. "/BarTest.java")
  spec.check("test seed keeps an existing suffix", new._test_seed(0), "BarTest")

  spec.check(
    "cmd completes kinds by prefix",
    table.concat(new._cmd_complete("pa", "LatheNew pa", 11), ","),
    "package-info"
  )
  spec.check("cmd completes every kind on a fresh arg", #new._cmd_complete("", "LatheNew ", 9), 8)

  -- prime the flat package cache, then assert fuzzy package completion at the second argument
  stub_server({
    ["lathe.types.destinations"] = destinations({
      { module = "app", scope = "main", pkg = "com.example.app.batch" },
      { module = "core", scope = "main", pkg = "com.example.core.util" },
    }),
  })
  new._refresh_async()
  spec.check(
    "second-arg completion lists packages",
    #new._cmd_complete("", "LatheNew class ", 15),
    2
  )
  spec.check(
    "second-arg completion is fuzzy",
    vim.tbl_contains(
      new._cmd_complete("appbatch", "LatheNew class appbatch", 23),
      "com.example.app.batch"
    ),
    true
  )
end

-- ── lathe.pick fuzzy filter ──────────────────────────────────────────────────

do
  local entries = {
    { item = 1, text = "com.example.app.batch" },
    { item = 2, text = "com.example.core.util" },
    { item = 3, text = "com.example.app.api" },
  }
  local all = pick._filter(entries, "")
  spec.check("filter: empty query keeps every entry", #all, 3)
  local matched = pick._filter(entries, "appbatch")
  spec.check("filter: subsequence match", matched[1].text, "com.example.app.batch")
  spec.check("filter: non-matches drop out", #pick._filter(entries, "appbatch"), 1)
  spec.check("filter: no match yields an empty list", #pick._filter(entries, "zzzzz"), 0)
end

-- ── create() end to end ──────────────────────────────────────────────────────

do -- anchored: :LatheNew <kind> in a buffer fills the destination from context; name only, no picker
  local path = vim.fn.tempname() .. "/core/src/main/java/com/example/core/Foo.java"
  local requests = stub_server({
    ["lathe.workspace.context"] = { moduleRel = "core", scope = "main", pkg = "com.example.core" },
    ["lathe.types.create"] = result(path),
  })
  stub_ui({ name = "Foo" })
  local where_opened = false
  local base = pick.pick
  pick.pick = function(opts)
    if opts.title == "Where:" then
      where_opened = true
    end
    base(opts)
  end

  new.create("class")

  local args = request_for(requests, "lathe.types.create") or {}
  spec.check("anchored: type", args.type, "class")
  spec.check("anchored: moduleRel", args.moduleRel, "core")
  spec.check("anchored: scope", args.kind, "main")
  spec.check("anchored: pkg", args.pkg, "com.example.core")
  spec.check("anchored: name from the prompt", args.name, "Foo")
  spec.check("anchored: no destination picker", where_opened, false)
  spec.check("anchored: file created", vim.fn.filereadable(path), 1)
end

do -- typed: a fuzzy-completed package goes to the server's best destination for it; no picker
  local requests = stub_server({
    ["lathe.workspace.context"] = { moduleRel = "core", scope = "main", pkg = "com.example.core" },
    ["lathe.types.destinations"] = destinations({
      { module = "core", scope = "main", pkg = "com.example.foo" },
      { module = "app", scope = "main", pkg = "com.example.foo" },
    }),
    ["lathe.types.create"] = result(vim.fn.tempname() .. "/Bar.java"),
  })
  local where_opened = false
  pick.pick = function(opts)
    if opts.title == "Where:" then
      where_opened = true
    end
  end
  vim.ui.input = function(opts, cb)
    cb((opts.default or "") .. "Bar")
  end

  new.create("class", "com.example.foo")

  local args = request_for(requests, "lathe.types.create") or {}
  spec.check("typed: the server's best destination", args.moduleRel, "core")
  spec.check(
    "typed: the package is sent to the server",
    (request_for(requests, "lathe.types.destinations") or {}).pkg,
    "com.example.foo"
  )
  spec.check("typed: package", args.pkg, "com.example.foo")
  spec.check("typed: scope", args.kind, "main")
  spec.check("typed: name from the prompt", args.name, "Bar")
  spec.check("typed: no destination picker when resolved", where_opened, false)
end

do -- typed but unknown package: falls back to the destination picker rather than a source-root default
  local requests = stub_server({
    ["lathe.workspace.context"] = nil,
    ["lathe.types.destinations"] = destinations({
      { module = "only", scope = "main", pkg = "com.only" },
    }),
    ["lathe.types.create"] = result(vim.fn.tempname() .. "/U.java"),
  })
  stub_ui({ package = "com.only", name = "U" })

  new.create("class", "com.unknown.pkg")

  local args = request_for(requests, "lathe.types.create") or {}
  spec.check("typed-miss: routed to the picker", args.pkg, "com.only")
  spec.check("typed-miss: name", args.name, "U")
end

do -- guided: bare :LatheNew -> kind -> destination picker (in the server's order) -> name
  local requests = stub_server({
    ["lathe.workspace.context"] = { moduleRel = "app", scope = "main", pkg = "com.app.batch" },
    ["lathe.types.destinations"] = destinations({
      { module = "app", scope = "main", pkg = "com.app.batch" },
      { module = "app", scope = "main", pkg = "com.app" },
      { module = "app", scope = "test", pkg = "com.app.util" },
    }),
    ["lathe.types.create"] = result(vim.fn.tempname() .. "/G.java"),
  })
  stub_ui({ kind = "Class", package = "com.app.batch", name = "G" })

  local order
  local base = pick.pick
  pick.pick = function(opts)
    if opts.title == "Where:" then
      order = vim.tbl_map(function(item)
        return ("%s (%s · %s)"):format(item.pkg, item.module, item.scope)
      end, opts.items)
    end
    base(opts)
  end

  new.create()

  spec.check("guided: the server's first row is item 1", order[1], "com.app.batch (app · main)")
  spec.check(
    "guided: destinations asked for this buffer and kind",
    (request_for(requests, "lathe.types.destinations") or {}).kind,
    "class"
  )
  local args = request_for(requests, "lathe.types.create") or {}
  spec.check("guided: package from the pick", args.pkg, "com.app.batch")
  spec.check("guided: scope from the picked row", args.kind, "main")
  spec.check("guided: name", args.name, "G")
end

do -- class in the test root: pick a `· test` destination row -> the class lands in test
  local requests = stub_server({
    ["lathe.workspace.context"] = { moduleRel = "app", scope = "main", pkg = "com.app" },
    ["lathe.types.destinations"] = destinations({
      { module = "app", scope = "main", pkg = "com.app" },
      { module = "app", scope = "test", pkg = "com.app.support" },
    }),
    ["lathe.types.create"] = result(vim.fn.tempname() .. "/Support.java"),
  })
  stub_ui({ kind = "Class", package = "com.app.support", pick_scope = "test", name = "Support" })

  new.create()

  local args = request_for(requests, "lathe.types.create") or {}
  spec.check("class-in-test: scope from the test row", args.kind, "test")
  spec.check("class-in-test: package", args.pkg, "com.app.support")
end

do -- new package via a qualified name: `jobs.Scheduler` nests the class under the picked package
  local requests = stub_server({
    ["lathe.workspace.context"] = { moduleRel = "app", scope = "main", pkg = "com.app" },
    ["lathe.types.destinations"] = destinations({
      { module = "app", scope = "main", pkg = "com.app" },
    }),
    ["lathe.types.create"] = result(vim.fn.tempname() .. "/Scheduler.java"),
  })
  stub_ui({ kind = "Class", package = "com.app", name = "jobs.batch.Scheduler" })

  new.create()

  local args = request_for(requests, "lathe.types.create") or {}
  spec.check(
    "qualified name: sub-package appended to the destination",
    args.pkg,
    "com.app.jobs.batch"
  )
  spec.check("qualified name: class name is the last segment", args.name, "Scheduler")
end

do -- a qualified name in a test-scope destination keeps the test scope for the new sub-package
  local requests = stub_server({
    ["lathe.workspace.context"] = { moduleRel = "app", scope = "main", pkg = "com.app" },
    ["lathe.types.destinations"] = destinations({
      { module = "app", scope = "test", pkg = "com.app.support" },
    }),
    ["lathe.types.create"] = result(vim.fn.tempname() .. "/Fixture.java"),
  })
  stub_ui({
    kind = "Class",
    package = "com.app.support",
    pick_scope = "test",
    name = "fakes.Fixture",
  })

  new.create()

  local args = request_for(requests, "lathe.types.create") or {}
  spec.check(
    "qualified name in test: sub-package under the test package",
    args.pkg,
    "com.app.support.fakes"
  )
  spec.check("qualified name in test: keeps the test scope", args.kind, "test")
end

do -- an FQN with an invalid package segment is re-asked, then accepted
  local requests = stub_server({
    ["lathe.workspace.context"] = { moduleRel = "app", scope = "main", pkg = "com.app" },
    ["lathe.types.create"] = result(vim.fn.tempname() .. "/Ok.java"),
  })
  local warned, calls = false, 0
  local real_notify = vim.notify
  vim.notify = function(message, _, _)
    if type(message) == "string" and message:match("valid fully%-qualified class name") then
      warned = true
    end
  end
  -- the seed is `com.app.`; the user types an FQN with an invalid segment, then a valid one (the
  -- re-prompt seeds with the previous value, so these are absolute)
  vim.ui.input = function(_, cb)
    calls = calls + 1
    cb(calls == 1 and "com.app.1bad.Ok" or "com.app.good.Ok")
  end

  new.create("class")

  vim.notify = real_notify
  local args = request_for(requests, "lathe.types.create") or {}
  spec.check("fqn: invalid segment warns", warned, true)
  spec.check("fqn: re-prompts", calls, 2)
  spec.check("fqn: accepted package", args.pkg, "com.app.good")
  spec.check("fqn: accepted class name", args.name, "Ok")
end

do -- package-info: no name prompt, the fixed stem is sent, package from context
  local requests = stub_server({
    ["lathe.workspace.context"] = { moduleRel = "core", scope = "main", pkg = "com.example.core" },
    ["lathe.types.create"] = result(vim.fn.tempname() .. "/package-info.java"),
  })
  local name_prompted = false
  pick.pick = function(_) end
  vim.ui.input = function(opts, cb)
    name_prompted = true
    cb(opts.default)
  end

  new.create("package-info")

  local args = request_for(requests, "lathe.types.create") or {}
  spec.check("package-info: type", args.type, "package-info")
  spec.check("package-info: package from context", args.pkg, "com.example.core")
  spec.check("package-info: name is the fixed stem", args.name, "package-info")
  spec.check("package-info: no name prompt", name_prompted, false)
end

do -- module-info: only the module -- sent without a name, the server names it; main root, no prompt
  local requests = stub_server({
    ["lathe.workspace.context"] = { moduleRel = "core", scope = "main", pkg = "com.example.core" },
    ["lathe.types.create"] = result(vim.fn.tempname() .. "/module-info.java"),
  })
  local prompted = false
  pick.pick = function(_) end
  vim.ui.input = function(_, cb)
    prompted = true
    cb("")
  end

  new.create("module-info")

  local args = request_for(requests, "lathe.types.create") or {}
  spec.check("module-info: type", args.type, "module-info")
  spec.check("module-info: scope forced to main", args.kind, "main")
  spec.check("module-info: no package in the request", args.pkg, "")
  spec.check("module-info: the name is left to the server", args.name, "")
  spec.check("module-info: no prompt when a base package exists", prompted, false)
end

do -- module-info: the server refuses a module with no package to name it after; the name is asked
  local path = vim.fn.tempname() .. "/module-info.java"
  local requests = stub_server({
    ["lathe.workspace.context"] = { moduleRel = "core", scope = "main", pkg = "" },
    ["lathe.types.create"] = function(args)
      if args.name == "" then
        return nil, { code = -32602, message = "core has no package to name its module after" }
      end

      return result(path)
    end,
  })
  pick.pick = function(_) end
  vim.ui.input = function(_, cb)
    cb("com.manual")
  end

  new.create("module-info")

  local args = last_request_for(requests, "lathe.types.create") or {}
  spec.check("module-info fallback: prompted name is used", args.name, "com.manual")
end

do -- kind passed as an argument skips the kind picker
  local requests = stub_server({
    ["lathe.workspace.context"] = { moduleRel = "core", scope = "main", pkg = "com.example" },
    ["lathe.types.create"] = result(vim.fn.tempname() .. "/Rec.java"),
  })
  local kind_picked = false
  pick.pick = function(opts)
    if opts.title == "New:" then
      kind_picked = true
    end
  end
  vim.ui.input = function(_, cb)
    cb("Rec")
  end

  new.create("record")

  local args = request_for(requests, "lathe.types.create") or {}
  spec.check("argument sets the kind", args.type, "record")
  spec.check("argument skips the kind picker", kind_picked, false)
end

do -- server not attached -> warns, no picker
  helper.no_client()
  local picked = false
  pick.pick = function(_)
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

do -- compile-on-attach saves the new buffer (→ FULL compile → .class) when the Lathe client is attached
  vim.lsp.get_clients = function(_)
    return { { name = "lathe" } }
  end
  local wrote = false
  local real_cmd = vim.cmd
  vim.cmd = setmetatable({}, {
    __call = function(_, command)
      if type(command) == "string" and command:match("write") then
        wrote = true
      end
    end,
  })
  local buf = vim.api.nvim_create_buf(false, true)

  new._compile_on_attach(buf)
  vim.wait(200, function()
    return wrote
  end)

  vim.cmd = real_cmd
  spec.check("compile-on-attach saves when the Lathe client is attached", wrote, true)
end

do -- a Ctrl-C at a picker cancels cleanly instead of surfacing the LSP-callback interrupt
  stub_server({ ["lathe.workspace.context"] = nil })
  pick.pick = function(_)
    error("Keyboard interrupt")
  end

  spec.check("picker interrupt is swallowed", pcall(new.create), true)

  pick.pick = function(_)
    error("boom")
  end
  spec.check("a real picker error still propagates", pcall(new.create), false)
end

do -- :LatheGoToTest: one test opens, several are picked from, none offers to create one
  -- Real path: macOS's temp dir is a symlink, and a buffer is named by the file's real path.
  local dir = vim.fn.tempname()
  vim.fn.mkdir(dir, "p")
  dir = vim.uv.fs_realpath(dir)
  local foo_test = dir .. "/FooTest.java"
  local foo_it = dir .. "/FooIT.java"
  vim.fn.writefile({ "class FooTest {}" }, foo_test)
  vim.fn.writefile({ "class FooIT {}" }, foo_it)
  local function target(path, name)
    return { uri = vim.uri_from_fname(path), qualifiedName = name }
  end

  vim.cmd.edit(dir .. "/Foo.java")
  stub_server({
    ["lathe.tests.counterparts"] = {
      fromTest = false,
      targets = { target(foo_test, "com.x.FooTest") },
    },
  })
  new.go_to_test()
  spec.check("one test opens", vim.api.nvim_buf_get_name(0), foo_test)

  vim.cmd.edit(dir .. "/Foo.java")
  stub_server({
    ["lathe.tests.counterparts"] = {
      fromTest = false,
      targets = { target(foo_test, "com.x.FooTest"), target(foo_it, "com.x.FooIT") },
    },
  })
  local shown
  pick.pick = function(opts)
    shown = { title = opts.title, labels = vim.tbl_map(opts.format, opts.items) }
    opts.on_choice(opts.items[2])
  end
  new.go_to_test()
  spec.check("several: picked under Test:", shown.title, "Test:")
  spec.check(
    "several: labelled by qualified name",
    table.concat(shown.labels, ","),
    "com.x.FooTest,com.x.FooIT"
  )
  spec.check("several: the pick opens", vim.api.nvim_buf_get_name(0), foo_it)

  vim.cmd.edit(dir .. "/Foo.java")
  local requests = stub_server({
    ["lathe.tests.counterparts"] = { fromTest = false, targets = {} },
    ["lathe.workspace.context"] = { moduleRel = "app", scope = "main", pkg = "com.x" },
    ["lathe.types.create"] = result(dir .. "/created/FooTest.java"),
  })
  local offered
  stub_ui({})
  pick.pick = function(opts)
    offered = opts.title .. " " .. opts.items[1]
    opts.on_choice(opts.items[1])
  end
  new.go_to_test()
  local created = request_for(requests, "lathe.types.create")
  spec.check("none: offers to create the test", offered, "No test found: Create test FooTest")
  spec.check(
    "none: creates it in the buffer's module and package, test scope",
    created
      and (created.moduleRel .. "/" .. created.kind .. "/" .. created.pkg .. "/" .. created.name),
    "app/test/com.x/FooTest"
  )

  vim.cmd.edit(dir .. "/BarTest.java")
  requests = stub_server({ ["lathe.tests.counterparts"] = { fromTest = true, targets = {} } })
  pick.pick = function(_)
    error("no pick expected")
  end
  spec.check("none from a test: no pick, no create", pcall(new.go_to_test), true)
  spec.check("none from a test: nothing created", request_for(requests, "lathe.types.create"), nil)
end

do -- setup registers :LatheNew and :LatheGoToTest
  new.setup()
  spec.check("LatheNew registered", vim.fn.exists(":LatheNew"), 2)
  spec.check("LatheGoToTest registered", vim.fn.exists(":LatheGoToTest"), 2)
end

spec.finish("new_spec")
