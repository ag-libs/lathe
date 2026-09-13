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
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/new_spec.lua

local spec = require("spec_helper").new()
local new = require("lathe.new")
local pick = require("lathe.pick")

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

-- Stub every pick from a plan: kind (New:), the destination (Where: -- by pkg + optional scope, or
-- "new" for the ＋ entry), module (Module:), scope (Scope:), and the vim.ui.input prompts (new
-- package, module name, or the type name). Absent plan fields fall through to the seeded default.
local function stub_ui(plan)
	pick.pick = function(opts)
		local items = opts.items
		if opts.title == "New:" then
			opts.on_choice(item_by(items, function(kind)
				return kind.label == plan.kind
			end))
		elseif opts.title == "Module:" then
			opts.on_choice(plan.module)
		elseif opts.title == "Scope:" then
			opts.on_choice(plan.scope)
		elseif opts.title == "Where:" then
			opts.on_choice(item_by(items, function(item)
				if plan.package == "new" then
					return item.new == true
				end
				return not item.new
					and item.pkg == plan.package
					and (not plan.pick_scope or item.scope == plan.pick_scope)
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

	local pkgs = {
		{ pkg = "com.example.a", scope = "main" },
		{ pkg = "com.example.b", scope = "main" },
		{ pkg = "com.other", scope = "test" },
	}
	spec.check("base package is the common main prefix", new._base_package(pkgs), "com.example")
	spec.check("base package empty with no main packages", new._base_package({ { pkg = "com.t", scope = "test" } }), "")

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

	-- ordering: context package leads, then its module, then the kind's natural scope
	local dests = {
		{ module = "core", scope = "main", pkg = "com.b" },
		{ module = "app", scope = "main", pkg = "com.a" },
		{ module = "app", scope = "test", pkg = "com.a" },
	}
	new._order_destinations(dests, { moduleRel = "app", pkg = "com.a" }, "class")
	spec.check(
		"order: context package + module leads",
		dests[1].module .. "/" .. dests[1].scope .. "/" .. dests[1].pkg,
		"app/main/com.a"
	)
	spec.check("order: same package's other scope follows", dests[2].scope, "test")
	spec.check("order: other module trails", dests[3].module, "core")

	-- typed resolution: prefer the buffer's module, then the kind's scope
	local pkgpick = {
		{ module = "core", scope = "main", pkg = "com.x" },
		{ module = "app", scope = "main", pkg = "com.x" },
		{ module = "app", scope = "test", pkg = "com.x" },
	}
	spec.check(
		"resolve typed prefers the context module",
		new._resolve_typed(pkgpick, "com.x", { moduleRel = "app" }, "class").module,
		"app"
	)
	spec.check(
		"resolve typed for a test kind prefers the test scope",
		new._resolve_typed(pkgpick, "com.x", { moduleRel = "app" }, "test").scope,
		"test"
	)
	spec.check(
		"resolve typed nil when the package is unknown",
		new._resolve_typed(pkgpick, "com.none", nil, "class") == nil,
		true
	)

	spec.check(
		"cmd completes kinds by prefix",
		table.concat(new._cmd_complete("pa", "LatheNew pa", 11), ","),
		"package-info"
	)
	spec.check("cmd completes every kind on a fresh arg", #new._cmd_complete("", "LatheNew ", 9), 8)

	-- prime the flat package cache, then assert fuzzy package completion at the second argument
	stub_server({
		["lathe.modules"] = { "core", "app" },
		["lathe.packages"] = {
			{ pkg = "com.example.app.batch", scope = "main" },
			{ pkg = "com.example.core.util", scope = "main" },
		},
	})
	new._refresh_async()
	spec.check("second-arg completion lists packages", #new._cmd_complete("", "LatheNew class ", 15), 2)
	spec.check(
		"second-arg completion is fuzzy",
		vim.tbl_contains(new._cmd_complete("appbatch", "LatheNew class appbatch", 23), "com.example.app.batch"),
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
		["lathe.resolveContext"] = { moduleRel = "core", scope = "main", pkg = "com.example.core" },
		["lathe.createType"] = result(path),
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

	local args = request_for(requests, "lathe.createType") or {}
	spec.check("anchored: type", args.type, "class")
	spec.check("anchored: moduleRel", args.moduleRel, "core")
	spec.check("anchored: scope", args.kind, "main")
	spec.check("anchored: pkg", args.pkg, "com.example.core")
	spec.check("anchored: name from the prompt", args.name, "Foo")
	spec.check("anchored: no destination picker", where_opened, false)
	spec.check("anchored: file created", vim.fn.filereadable(path), 1)
end

do -- typed: a fuzzy-completed package resolves to a destination (context module, kind scope); no picker
	local requests = stub_server({
		["lathe.resolveContext"] = { moduleRel = "core", scope = "main", pkg = "com.example.core" },
		["lathe.modules"] = { "core", "app" },
		["lathe.packages"] = { { pkg = "com.example.foo", scope = "main" } },
		["lathe.createType"] = result(vim.fn.tempname() .. "/Bar.java"),
	})
	local where_opened = false
	pick.pick = function(opts)
		if opts.title == "Where:" then
			where_opened = true
		end
	end
	vim.ui.input = function(_, cb)
		cb("Bar")
	end

	new.create("class", "com.example.foo")

	local args = request_for(requests, "lathe.createType") or {}
	spec.check("typed: moduleRel prefers the context module", args.moduleRel, "core")
	spec.check("typed: package", args.pkg, "com.example.foo")
	spec.check("typed: scope", args.kind, "main")
	spec.check("typed: name from the prompt", args.name, "Bar")
	spec.check("typed: no destination picker when resolved", where_opened, false)
end

do -- typed but unknown package: falls back to the destination picker rather than a source-root default
	local requests = stub_server({
		["lathe.resolveContext"] = nil,
		["lathe.modules"] = { "only" },
		["lathe.packages"] = { { pkg = "com.only", scope = "main" } },
		["lathe.createType"] = result(vim.fn.tempname() .. "/U.java"),
	})
	stub_ui({ package = "com.only", name = "U" })

	new.create("class", "com.unknown.pkg")

	local args = request_for(requests, "lathe.createType") or {}
	spec.check("typed-miss: routed to the picker", args.pkg, "com.only")
	spec.check("typed-miss: name", args.name, "U")
end

do -- guided: bare :LatheNew -> kind -> destination picker (context floated) -> name
	local requests = stub_server({
		["lathe.resolveContext"] = { moduleRel = "app", scope = "main", pkg = "com.app.batch" },
		["lathe.modules"] = { "core", "app" },
		["lathe.packages"] = {
			{ pkg = "com.app", scope = "main" },
			{ pkg = "com.app.batch", scope = "main" },
			{ pkg = "com.app.util", scope = "test" },
		},
		["lathe.createType"] = result(vim.fn.tempname() .. "/G.java"),
	})
	stub_ui({ kind = "Class", package = "com.app.batch", name = "G" })

	local order
	local base = pick.pick
	pick.pick = function(opts)
		if opts.title == "Where:" then
			order = vim.tbl_map(function(item)
				return item.new and "＋ New package…" or ("%s (%s · %s)"):format(item.pkg, item.module, item.scope)
			end, opts.items)
		end
		base(opts)
	end

	new.create()

	spec.check("guided: context package is item 1", order[1], "com.app.batch (app · main)")
	spec.check("guided: ＋ New package… is item 2", order[2], "＋ New package…")
	local args = request_for(requests, "lathe.createType") or {}
	spec.check("guided: package from the pick", args.pkg, "com.app.batch")
	spec.check("guided: scope from the picked row", args.kind, "main")
	spec.check("guided: name", args.name, "G")
end

do -- class in the test root: pick a `· test` destination row -> the class lands in test
	local requests = stub_server({
		["lathe.resolveContext"] = { moduleRel = "app", scope = "main", pkg = "com.app" },
		["lathe.modules"] = { "app" },
		["lathe.packages"] = {
			{ pkg = "com.app", scope = "main" },
			{ pkg = "com.app.support", scope = "test" },
		},
		["lathe.createType"] = result(vim.fn.tempname() .. "/Support.java"),
	})
	stub_ui({ kind = "Class", package = "com.app.support", pick_scope = "test", name = "Support" })

	new.create()

	local args = request_for(requests, "lathe.createType") or {}
	spec.check("class-in-test: scope from the test row", args.kind, "test")
	spec.check("class-in-test: package", args.pkg, "com.app.support")
end

do -- new package (single root): no scope prompt, lands in main
	local requests = stub_server({
		["lathe.modules"] = { "only" },
		["lathe.packages"] = { { pkg = "com.only", scope = "main" } },
		["lathe.createType"] = result(vim.fn.tempname() .. "/D.java"),
	})
	stub_ui({ kind = "Class", package = "new", new_package = "com.only.jobs", name = "D" })

	new.create()

	local args = request_for(requests, "lathe.createType") or {}
	spec.check("new package: package", args.pkg, "com.only.jobs")
	spec.check("new package: main scope with a single root", args.kind, "main")
end

do -- new package (both roots): the scope is asked, and test is reachable -- fixes the v4 gap
	local requests = stub_server({
		["lathe.modules"] = { "only" },
		["lathe.packages"] = {
			{ pkg = "com.only", scope = "main" },
			{ pkg = "com.only.it", scope = "test" },
		},
		["lathe.createType"] = result(vim.fn.tempname() .. "/E.java"),
	})
	stub_ui({ kind = "Class", package = "new", new_package = "com.only.newpkg", scope = "test", name = "E" })

	new.create()

	local args = request_for(requests, "lathe.createType") or {}
	spec.check("new test package: scope from the prompt", args.kind, "test")
	spec.check("new test package: package", args.pkg, "com.only.newpkg")
end

do -- package-info: no name prompt, the fixed stem is sent, package from context
	local requests = stub_server({
		["lathe.resolveContext"] = { moduleRel = "core", scope = "main", pkg = "com.example.core" },
		["lathe.createType"] = result(vim.fn.tempname() .. "/package-info.java"),
	})
	local name_prompted = false
	pick.pick = function(_) end
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

do -- module-info: only the module -- name auto-derived from the base package, main root, no prompt
	local requests = stub_server({
		["lathe.resolveContext"] = { moduleRel = "core", scope = "main", pkg = "com.example.core" },
		["lathe.packages"] = {
			{ pkg = "com.example.core", scope = "main" },
			{ pkg = "com.example.util", scope = "main" },
		},
		["lathe.createType"] = result(vim.fn.tempname() .. "/module-info.java"),
	})
	local prompted = false
	pick.pick = function(_) end
	vim.ui.input = function(_, cb)
		prompted = true
		cb("")
	end

	new.create("module-info")

	local args = request_for(requests, "lathe.createType") or {}
	spec.check("module-info: type", args.type, "module-info")
	spec.check("module-info: scope forced to main", args.kind, "main")
	spec.check("module-info: no package in the request", args.pkg, "")
	spec.check("module-info: name auto-derived from the base package", args.name, "com.example")
	spec.check("module-info: no prompt when a base package exists", prompted, false)
end

do -- module-info: falls back to a name prompt only when nothing can be derived
	local requests = stub_server({
		["lathe.resolveContext"] = { moduleRel = "core", scope = "main", pkg = "" },
		["lathe.packages"] = { { pkg = "", scope = "main" } },
		["lathe.createType"] = result(vim.fn.tempname() .. "/module-info.java"),
	})
	pick.pick = function(_) end
	vim.ui.input = function(_, cb)
		cb("com.manual")
	end

	new.create("module-info")

	local args = request_for(requests, "lathe.createType") or {}
	spec.check("module-info fallback: prompted name is used", args.name, "com.manual")
end

do -- kind passed as an argument skips the kind picker
	local requests = stub_server({
		["lathe.resolveContext"] = { moduleRel = "core", scope = "main", pkg = "com.example" },
		["lathe.createType"] = result(vim.fn.tempname() .. "/Rec.java"),
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

	local args = request_for(requests, "lathe.createType") or {}
	spec.check("argument sets the kind", args.type, "record")
	spec.check("argument skips the kind picker", kind_picked, false)
end

do -- the name prompt re-asks on an invalid identifier, then accepts a valid one
	local requests = stub_server({
		["lathe.resolveContext"] = { moduleRel = "core", scope = "main", pkg = "com.example.core" },
		["lathe.createType"] = result(vim.fn.tempname() .. "/Valid.java"),
	})
	pick.pick = function(_) end
	local warned, calls = false, 0
	local real_notify = vim.notify
	vim.notify = function(message, _, _)
		if type(message) == "string" and message:match("identifier") then
			warned = true
		end
	end
	vim.ui.input = function(_, cb)
		calls = calls + 1
		cb(calls == 1 and "1Bad" or "Valid")
	end

	new.create("class")

	vim.notify = real_notify
	local args = request_for(requests, "lathe.createType") or {}
	spec.check("invalid name warns", warned, true)
	spec.check("invalid name re-prompts", calls, 2)
	spec.check("valid name is used", args.name, "Valid")
end

do -- server not attached -> warns, no picker
	vim.lsp.get_clients = function(_)
		return {}
	end
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
	stub_server({ ["lathe.resolveContext"] = nil })
	pick.pick = function(_)
		error("Keyboard interrupt")
	end

	spec.check("picker interrupt is swallowed", pcall(new.create), true)

	pick.pick = function(_)
		error("boom")
	end
	spec.check("a real picker error still propagates", pcall(new.create), false)
end

do -- setup registers :LatheNew
	new.setup()
	spec.check("LatheNew registered", vim.fn.exists(":LatheNew"), 2)
end

spec.finish("new_spec")
