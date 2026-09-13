-- :LatheNew -- create a class / interface / record / enum / annotation / test / package-info /
-- module-info through the Lathe server, which owns every Java/Maven decision (module, source root,
-- package, skeleton, caret). The client only resolves *where* and *what to call it*.
--
-- v5 UX: the destination is a single fuzzy pick, not a colon-grammar string.
--
--   :LatheNew                 guided -- kind -> destination pick -> name
--   :LatheNew <kind>          anchored -- current buffer's package fills the destination; prompt name
--                             (falls back to the destination pick when there is no buffer context)
--   :LatheNew <kind> <pkg>    typed -- fuzzy-completed package (native cmdline completion); prompt name
--
-- The destination pick is Lathe's own built-in fuzzy picker (lathe.pick) over every
-- `module · scope · package` in the reactor -- self-contained `matchfuzzy`, so it does NOT depend on
-- `vim.ui.select` / Telescope and is identically fuzzy for every user. Each row carries its scope, so
-- picking a `· test` row puts a plain class in the test root. `＋ New package…` creates a package
-- (scope asked only when the module has both roots). The type NAME is always a final, validated
-- prompt; the package never defaults by omission.

local M = {}

local pick = require("lathe.pick")

local KINDS = {
	{ label = "Class", type = "class" },
	{ label = "Interface", type = "interface" },
	{ label = "Record", type = "record" },
	{ label = "Enum", type = "enum" },
	{ label = "Annotation", type = "annotation" },
	{ label = "Test", type = "test" },
	{ label = "package-info", type = "package-info" },
	{ label = "module-info", type = "module-info" },
}

local KIND_TOKENS = vim.tbl_map(function(kind)
	return kind.type
end, KINDS)

-- Flat package names across all modules, primed lazily for command-line completion (which must answer
-- synchronously, so it reads this rather than issuing an LSP request per keystroke).
local cache = { packages = {} }

local function notify(message, level)
	vim.notify("Lathe: " .. message, level, { title = "Lathe" })
end

local function warn(message)
	notify(message, vim.log.levels.WARN)
end

-- The Lathe LSP client attached to a buffer, or nil.
local function lathe_client(bufnr)
	return vim.lsp.get_clients({ name = "lathe", bufnr = bufnr })[1]
end

-- Dispatch a workspace/executeCommand to the Lathe client; cb receives the decoded result.
local function execute(client, bufnr, command, argument, cb)
	client:request("workspace/executeCommand", {
		command = command,
		arguments = { argument },
	}, function(err, result)
		if err then
			warn(err.message)
			return
		end

		-- The pickers run inside this LSP response callback (a vim.schedule task), so a Ctrl-C at a
		-- vim.ui prompt raises inputlist's keyboard interrupt here instead of cancelling a command --
		-- otherwise an unhandled "vim.schedule callback: Keyboard interrupt". Swallow only that
		-- interrupt; a genuine error in the flow still surfaces.
		local ok, failure = pcall(cb, result)
		if not ok and not tostring(failure):match("[Ii]nterrupt") then
			error(failure)
		end
	end, bufnr)
end

-- ── file IO ──────────────────────────────────────────────────────────────────

-- The server content always ends with "\n"; drop the trailing empty split element so writefile
-- reproduces it exactly rather than appending a second newline.
function M._lines(content)
	local lines = vim.split(content, "\n")
	if lines[#lines] == "" then
		table.remove(lines)
	end
	return lines
end

-- Write the server-rendered file, open it, and drop the caret where the server asked (LSP 0-based
-- line -> Neovim 1-based row). Never overwrites: if the target already exists, just open it.
function M._open(result)
	if vim.fn.filereadable(result.path) == 1 then
		notify(vim.fn.fnamemodify(result.path, ":t") .. " already exists — opening it", vim.log.levels.INFO)
		vim.cmd.edit(vim.fn.fnameescape(result.path))
		return
	end

	vim.fn.mkdir(vim.fn.fnamemodify(result.path, ":h"), "p")
	vim.fn.writefile(M._lines(result.content), result.path)
	vim.cmd.edit(vim.fn.fnameescape(result.path))
	pcall(vim.api.nvim_win_set_cursor, 0, { result.caret.line + 1, result.caret.character })
	M._compile_on_attach(vim.api.nvim_get_current_buf())
end

-- Opening a file only analyzes it; the .class is produced by a FULL compile on save. So save the
-- freshly-created buffer once the Lathe server attaches -- otherwise the new type has no bytecode in
-- .lathe/ and reads as an unbuilt "stale" source, triggering a spurious sync prompt.
function M._compile_on_attach(bufnr)
	local function save()
		if not vim.api.nvim_buf_is_valid(bufnr) then
			return
		end

		pcall(function()
			vim.api.nvim_buf_call(bufnr, function()
				vim.cmd("silent keepalt write")
			end)
		end)
	end

	-- Defer onto the main loop: the save fires format_on_save (a blocking format request) and didSave,
	-- neither of which is safe to run nested inside the LspAttach callback we may be in.
	if lathe_client(bufnr) then
		return vim.schedule(save)
	end

	vim.api.nvim_create_autocmd("LspAttach", {
		buffer = bufnr,
		callback = function(args)
			local client = vim.lsp.get_client_by_id(args.data.client_id)
			if client and client.name == "lathe" then
				vim.schedule(save)
				return true
			end
		end,
	})
end

-- ── naming helpers ───────────────────────────────────────────────────────────

-- The longest package prefix common to a and b, on dot boundaries (com.x.a & com.x.b -> com.x).
local function common_prefix(a, b)
	local sa, sb = vim.split(a, ".", { plain = true }), vim.split(b, ".", { plain = true })
	local out = {}
	for i = 1, math.min(#sa, #sb) do
		if sa[i] ~= sb[i] then
			break
		end

		out[i] = sa[i]
	end
	return table.concat(out, ".")
end

-- The module's base package: the longest common prefix of its main packages, used to seed a
-- module-info name (JPMS convention: module name = root package) and a new-package input.
function M._base_package(packages)
	local mains = {}
	for _, entry in ipairs(packages or {}) do
		if entry.scope == "main" and entry.pkg ~= "" then
			mains[#mains + 1] = entry.pkg
		end
	end

	if #mains == 0 then
		return ""
	end

	local prefix = mains[1]
	for i = 2, #mains do
		prefix = common_prefix(prefix, mains[i])
	end
	return prefix
end

-- <Stem>Test derived from the buffer's file name, leaving an already-Test-suffixed stem alone.
function M._test_seed(bufnr)
	local name = vim.api.nvim_buf_get_name(bufnr)
	local stem = name ~= "" and vim.fn.fnamemodify(name, ":t:r") or ""
	if stem == "" then
		return ""
	end

	return stem:match("Test$") and stem or (stem .. "Test")
end

-- Reorder `modules` so the buffer's own module leads; returned unchanged when there is no context
-- module or it is not among them.
function M._float_module(modules, contextModule)
	if not contextModule or not vim.tbl_contains(modules, contextModule) then
		return modules
	end

	local ordered = { contextModule }
	for _, module in ipairs(modules) do
		if module ~= contextModule then
			ordered[#ordered + 1] = module
		end
	end
	return ordered
end

local function label_of(item)
	return item.label
end

-- The scope a kind naturally targets: a test lands in the test root, everything else in main.
local function pref_scope(kind)
	return kind == "test" and "test" or "main"
end

-- The destination-row label: `pkg (module · scope)`, all three fuzzy-matchable in one line. The
-- create-package row is the pinned sentinel.
local function dest_label(item)
	if item.new then
		return "＋ New package…"
	end

	return ("%s (%s · %s)"):format(item.pkg, item.module, item.scope)
end

local function name_label(kind, dest, scope)
	local where = ("%s / %s / %s"):format(dest.module or "?", scope, dest.pkg ~= "" and dest.pkg or "<default>")
	local noun = kind == "test" and "Test class" or (kind:sub(1, 1):upper() .. kind:sub(2))
	return ("%s in %s"):format(noun, where)
end

-- ── prompts ──────────────────────────────────────────────────────────────────

-- vim.ui.input that fires cb only with a trimmed, non-empty reply; empty or cancelled is a no-op.
local function input_nonempty(opts, cb)
	vim.ui.input(opts, function(value)
		if value and vim.trim(value) ~= "" then
			cb(vim.trim(value))
		end
	end)
end

-- The type-name prompt: a valid Java identifier, re-asked on an invalid entry so the mistake is
-- caught here instead of as a raw server error after the round-trip.
local function input_name(label, default, cb)
	vim.ui.input({ prompt = label .. ": ", default = default }, function(value)
		if not value or vim.trim(value) == "" then
			return
		end

		value = vim.trim(value)
		if not value:match("^[%a_$][%w_$]*$") then
			warn("'" .. value .. "' is not a valid Java identifier")
			return input_name(label, value, cb)
		end

		cb(value)
	end)
end

-- ── destination gathering + ordering ─────────────────────────────────────────

-- Every `{ module, scope, pkg }` in the reactor (each package once per scope it exists in, so a
-- package in both roots offers a main row and a test row) plus the module list. Silent: callers
-- decide what an empty reactor means (a prompt warns; completion priming ignores it).
local function collect_destinations(client, bufnr, cb)
	execute(client, bufnr, "lathe.modules", {}, function(modules)
		modules = modules or {}
		local dests = {}
		local pending = #modules
		if pending == 0 then
			return cb(dests, modules)
		end

		for _, module in ipairs(modules) do
			execute(client, bufnr, "lathe.packages", { moduleRel = module }, function(packages)
				for _, entry in ipairs(packages or {}) do
					if entry.pkg ~= "" then
						dests[#dests + 1] = { module = module, scope = entry.scope, pkg = entry.pkg }
					end
				end

				pending = pending - 1
				if pending == 0 then
					cb(dests, modules)
				end
			end)
		end
	end)
end

-- Rank so the buffer's package leads, then its module, then the kind's natural scope; a stable
-- module/pkg/scope tie-break keeps the list deterministic.
function M._order_destinations(dests, ctx, kind)
	local prefScope = pref_scope(kind)
	local ctxPkg = ctx and ctx.pkg
	local ctxMod = ctx and ctx.moduleRel
	local function rank(d)
		if ctxPkg and ctxPkg ~= "" and d.pkg == ctxPkg and d.module == ctxMod then
			return 0
		end
		if ctxMod and d.module == ctxMod then
			return 1
		end
		if d.scope == prefScope then
			return 2
		end
		return 3
	end

	table.sort(dests, function(a, b)
		local ra, rb = rank(a), rank(b)
		if ra ~= rb then
			return ra < rb
		end
		if a.module ~= b.module then
			return a.module < b.module
		end
		if a.pkg ~= b.pkg then
			return a.pkg < b.pkg
		end
		return a.scope < b.scope
	end)
	return dests
end

-- The best destination for a typed package word: prefer the buffer's module, then the kind's scope.
function M._resolve_typed(dests, pkg, ctx, kind)
	local prefScope = pref_scope(kind)
	local ctxMod = ctx and ctx.moduleRel
	local function score(d)
		return (ctxMod and d.module == ctxMod and 2 or 0) + (d.scope == prefScope and 1 or 0)
	end

	local best
	for _, d in ipairs(dests) do
		if d.pkg == pkg and (not best or score(d) > score(best)) then
			best = d
		end
	end
	return best
end

-- ── flow ─────────────────────────────────────────────────────────────────────

-- Resolve a module: the only one, else a picker with the buffer's module floated to the top.
local function choose_module(modules, ctx, cb)
	if #modules == 1 then
		return cb(modules[1])
	end

	pick.pick({
		title = "Module:",
		items = M._float_module(modules, ctx and ctx.moduleRel),
		on_choice = function(module)
			if module then
				cb(module)
			end
		end,
	})
end

local submit, name_step, open_where, new_package, create_module_info, module_info_flow

-- package-info sends the fixed stem as name (ignored by the server); module-info sends the JPMS module
-- name and always the main root.
submit = function(client, bufnr, kind, dest, scope, name)
	execute(client, bufnr, "lathe.createType", {
		moduleRel = dest.module,
		kind = scope,
		pkg = kind == "module-info" and "" or (dest.pkg or ""),
		type = kind,
		name = name,
	}, M._open)
end

-- test forces the test scope; a plain kind takes the scope of the picked destination (so a class can
-- land in the test root). package-info skips the name prompt; the type name is otherwise validated.
name_step = function(client, bufnr, kind, dest)
	local scope = kind == "test" and "test" or (dest.scope or "main")

	if kind == "package-info" then
		submit(client, bufnr, kind, dest, scope, "package-info")
		return
	end

	input_name(name_label(kind, dest, scope), kind == "test" and M._test_seed(bufnr) or "", function(name)
		submit(client, bufnr, kind, dest, scope, name)
	end)
end

open_where = function(client, bufnr, kind, ctx, dests, modules)
	M._order_destinations(dests, ctx, kind)

	local items = {}
	for _, d in ipairs(dests) do
		items[#items + 1] = d
	end

	-- ＋ New package… sits right behind the context row (item 1) so both the "add a sibling" and the
	-- "somewhere new" picks are within immediate reach.
	local contextFirst = ctx and ctx.pkg and items[1] and items[1].pkg == ctx.pkg and items[1].module == ctx.moduleRel
	table.insert(items, contextFirst and 2 or 1, { new = true })

	pick.pick({
		title = "Where:",
		items = items,
		format = dest_label,
		on_choice = function(choice)
			if not choice then
				return
			end

			if choice.new then
				return new_package(client, bufnr, kind, ctx, modules)
			end

			name_step(client, bufnr, kind, { module = choice.module, scope = choice.scope, pkg = choice.pkg })
		end,
	})
end

-- A brand-new package: choose its module (context floated, skip-when-one), type the package (seeded
-- from the module's base package), and choose the scope only when the module has both roots.
new_package = function(client, bufnr, kind, ctx, modules)
	local function with_module(module)
		execute(client, bufnr, "lathe.packages", { moduleRel = module }, function(packages)
			local roots = {}
			for _, entry in ipairs(packages or {}) do
				roots[entry.scope] = true
			end

			local base = M._base_package(packages)
			-- A deliberate New-package entry may be empty — an explicit choice of the default package (the
			-- invariant only forbids defaulting by omission). Only a cancel (nil) aborts.
			vim.ui.input({ prompt = "New package: ", default = base ~= "" and base .. "." or "" }, function(pkg)
				if not pkg then
					return
				end

				local function go(scope)
					name_step(client, bufnr, kind, { module = module, scope = scope, pkg = vim.trim(pkg) })
				end

				if roots.main and roots.test then
					pick.pick({
						title = "Scope:",
						items = { "main", "test" },
						on_choice = function(scope)
							if scope then
								go(scope)
							end
						end,
					})
				else
					go(roots.test and not roots.main and "test" or pref_scope(kind))
				end
			end)
		end)
	end

	choose_module(modules, ctx, with_module)
end

-- module-info takes no package, scope, or type name — only the module. Its JPMS name is derived from
-- the module's base package and it always lands at the main source root, so there is no prompt. The
-- rare case where nothing can be derived (a module with no packages yet) falls back to asking.
create_module_info = function(client, bufnr, module)
	execute(client, bufnr, "lathe.packages", { moduleRel = module }, function(packages)
		local dest = { module = module, pkg = "" }
		local name = M._base_package(packages)
		if name ~= "" then
			submit(client, bufnr, "module-info", dest, "main", name)
			return
		end

		input_nonempty({ prompt = "Module name: " }, function(entered)
			submit(client, bufnr, "module-info", dest, "main", entered)
		end)
	end)
end

module_info_flow = function(client, bufnr, ctx, guided)
	if not guided and ctx and ctx.moduleRel then
		return create_module_info(client, bufnr, ctx.moduleRel)
	end

	execute(client, bufnr, "lathe.modules", {}, function(modules)
		modules = modules or {}
		if #modules == 0 then
			warn("no modules found — run a build first")
			return
		end

		choose_module(modules, ctx, function(module)
			create_module_info(client, bufnr, module)
		end)
	end)
end

-- Resolve the destination for `kind`, then create. `guided` (bare :LatheNew) always opens the
-- destination picker; an anchored `:LatheNew <kind>` skips straight to the name when the buffer
-- context yields a package.
local function dispatch(client, bufnr, kind, ctx, pkg_arg, guided)
	if kind == "module-info" then
		return module_info_flow(client, bufnr, ctx, guided)
	end

	if not guided and (not pkg_arg or pkg_arg == "") and ctx and ctx.pkg and ctx.pkg ~= "" then
		return name_step(client, bufnr, kind, { module = ctx.moduleRel, scope = ctx.scope, pkg = ctx.pkg })
	end

	collect_destinations(client, bufnr, function(dests, modules)
		if #modules == 0 then
			warn("no modules found — run a build first")
			return
		end

		if pkg_arg and pkg_arg ~= "" then
			local resolved = M._resolve_typed(dests, pkg_arg, ctx, kind)
			if resolved then
				return name_step(client, bufnr, kind, resolved)
			end
		end

		open_where(client, bufnr, kind, ctx, dests, modules)
	end)
end

-- kind_arg / pkg_arg come from the command line and skip the corresponding step when present.
function M.create(kind_arg, pkg_arg)
	local bufnr = vim.api.nvim_get_current_buf()
	local client = lathe_client(bufnr)
	if not client then
		warn("server not attached — creation needs the language server")
		return
	end

	if kind_arg and kind_arg ~= "" and not vim.tbl_contains(KIND_TOKENS, kind_arg) then
		warn("unknown kind: " .. kind_arg)
		return
	end

	execute(client, bufnr, "lathe.resolveContext", { uri = vim.uri_from_bufnr(bufnr) }, function(ctx)
		if kind_arg and kind_arg ~= "" then
			dispatch(client, bufnr, kind_arg, ctx, pkg_arg, false)
			return
		end

		pick.pick({
			title = "New:",
			items = KINDS,
			format = label_of,
			on_choice = function(item)
				if item then
					dispatch(client, bufnr, item.type, ctx, nil, true)
				end
			end,
		})
	end)
end

-- ── command-line completion ──────────────────────────────────────────────────

local function prefix_matches(list, arglead)
	local out = {}
	for _, item in ipairs(list) do
		if item:find(arglead, 1, true) == 1 then
			out[#out + 1] = item
		end
	end
	return out
end

-- Prime the flat package cache off any active client, for command-line completion (which cannot block
-- on an LSP round-trip). Silent when the server is not attached.
function M._refresh_async()
	local bufnr = vim.api.nvim_get_current_buf()
	local client = lathe_client(bufnr)
	if not client then
		return
	end

	collect_destinations(client, bufnr, function(dests)
		local seen, list = {}, {}
		for _, dest in ipairs(dests) do
			if not seen[dest.pkg] then
				seen[dest.pkg] = true
				list[#list + 1] = dest.pkg
			end
		end
		cache.packages = list
	end)
end

-- Kind at the first argument (prefix), a fuzzy-matched package at the second (builtin matchfuzzy, so
-- it is a real subsequence match regardless of the user's picker or wildmode).
function M._cmd_complete(arglead, cmdline, _)
	local parts = vim.split(cmdline, "%s+", { trimempty = true })
	local argpos = cmdline:match("%s$") and #parts or (#parts - 1)
	if argpos <= 1 then
		return prefix_matches(KIND_TOKENS, arglead or "")
	end

	if #cache.packages == 0 then
		M._refresh_async()
	end

	if not arglead or arglead == "" then
		return cache.packages
	end

	return vim.fn.matchfuzzy(cache.packages, arglead)
end

function M.setup()
	vim.api.nvim_create_user_command("LatheNew", function(opts)
		M.create(opts.fargs[1], opts.fargs[2])
	end, {
		nargs = "*",
		complete = M._cmd_complete,
		desc = "Lathe: create a new class/interface/record/enum/annotation/test/package-info/module-info",
	})
end

return M
