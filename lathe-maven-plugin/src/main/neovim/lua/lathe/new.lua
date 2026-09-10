-- :LatheNew -- create a class / interface / record / enum / test / package-info / module-info through
-- the Lathe server, which owns every Java/Maven decision (module, source root, package, skeleton,
-- caret). Two entry points split by the 80/20 of real use, plus one rule that removes ambiguity: the
-- type NAME is always a final prompt, never part of the argument.
--
--   :LatheNew <kind>              anchored -- the buffer context fills module/scope/package; prompt name
--   :LatheNew <kind> <location>   explicit `[module:][scope:]package`; resolves on its own; prompt name
--   :LatheNew                     guided -- kind -> module -> package/new -> name
--
-- Context fills the location ONLY when no location is typed; a typed location never partial-fills from
-- context. The package never defaults by omission -- if it cannot resolve, the guided picker takes
-- over; a file is never created at the source root. Creation needs the Lathe server attached.

local M = {}

local KINDS = {
  { label = "Class", type = "class" },
  { label = "Interface", type = "interface" },
  { label = "Record", type = "record" },
  { label = "Enum", type = "enum" },
  { label = "Test", type = "test" },
  { label = "package-info", type = "package-info" },
  { label = "module-info", type = "module-info" },
}

local KIND_TOKENS = vim.tbl_map(function(kind)
  return kind.type
end, KINDS)

-- module:package targets discovered lazily for command-line completion (which must answer
-- synchronously, so it reads this rather than issuing an LSP request per keystroke).
local cache = { modules = {}, by_module = {} }

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

    -- The create pickers run inside this LSP response callback (a vim.schedule task), so a Ctrl-C at a
    -- vim.ui prompt raises inputlist's keyboard interrupt here instead of cancelling a command --
    -- otherwise an unhandled "vim.schedule callback: Keyboard interrupt". Swallow only that interrupt;
    -- a genuine error in the flow still surfaces.
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

-- ── grammar helpers ──────────────────────────────────────────────────────────

-- Parse a typed location `[module:][scope:]package` into { module, scope, pkg }. `main`/`test` are the
-- scope, recognised only as a leading colon-segment; a leading non-scope segment is the module (a
-- reactor module literally named main/test is the rare collision, resolved in favour of the scope).
-- The type name is NOT part of this grammar -- it is always prompted.
function M._parse_location(input)
  local parts = vim.split(vim.trim(input or ""), ":", { plain = true })
  local pkg = table.remove(parts)
  local module, scope
  for _, seg in ipairs(parts) do
    if seg == "main" or seg == "test" then
      scope = seg
    else
      module = seg
    end
  end
  return { module = module, scope = scope, pkg = vim.trim(pkg or "") }
end

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

local function name_label(kind, dest, scope)
  local where = ("%s / %s / %s"):format(dest.module or "?", scope, dest.pkg ~= "" and dest.pkg or "<default>")
  local noun = kind == "test" and "Test class" or (kind:sub(1, 1):upper() .. kind:sub(2))
  return ("%s in %s"):format(noun, where)
end

-- vim.ui.input that fires cb only with a trimmed, non-empty reply; an empty or cancelled reply is a
-- no-op. Centralises the "prompt → validate → trim" the create prompts all repeat.
local function input_nonempty(opts, cb)
  vim.ui.input(opts, function(value)
    if value and vim.trim(value) ~= "" then
      cb(vim.trim(value))
    end
  end)
end

local function label_of(item)
  return item.label
end

-- Remove and return the entry whose package matches the buffer context, so pick_package can float it
-- to the top of the list; nil when there is no context package or it is not among this module's
-- packages (e.g. a different module was chosen in the guided flow).
function M._take_context(entries, contextPkg)
  if not contextPkg or contextPkg == "" then
    return nil
  end

  for i, entry in ipairs(entries) do
    if entry.pkg == contextPkg then
      return table.remove(entries, i)
    end
  end

  return nil
end

-- Reorder `modules` so the buffer's own module leads the picker; returned unchanged when there is no
-- context module or it is not among them.
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

-- ── flow: fill the missing pieces of a destination, then create ──────────────

local proceed, pick_module, pick_package, finish, create_module_info

-- package-info sends the fixed stem as name (ignored by the server); module-info sends the JPMS module
-- name and always the main root.
local function submit(client, bufnr, kind, dest, scope, name)
  execute(client, bufnr, "lathe.createType", {
    moduleRel = dest.module,
    kind = scope,
    pkg = kind == "module-info" and "" or (dest.pkg or ""),
    type = kind,
    name = name,
  }, M._open)
end

-- dest = { module, scope, pkg } with any field nil. Fill the next missing piece for `kind`, then
-- create. module-info needs only a module; the other kinds also need a package (never defaulted by
-- omission -- an empty package routes to the picker, not the source root).
proceed = function(client, bufnr, kind, dest)
  if not dest.module then
    return pick_module(client, bufnr, kind, dest)
  end

  if kind == "module-info" then
    return finish(client, bufnr, kind, dest)
  end

  if not dest.pkg or dest.pkg == "" then
    return pick_package(client, bufnr, kind, dest)
  end

  return finish(client, bufnr, kind, dest)
end

pick_module = function(client, bufnr, kind, dest)
  execute(client, bufnr, "lathe.modules", {}, function(modules)
    modules = modules or {}
    if #modules == 0 then
      warn("no modules found — run a build first")
      return
    end

    if #modules == 1 then
      dest.module = modules[1]
      return proceed(client, bufnr, kind, dest)
    end

    vim.ui.select(M._float_module(modules, dest.contextModule), { prompt = "Module:" }, function(module)
      if module then
        dest.module = module
        proceed(client, bufnr, kind, dest)
      end
    end)
  end)
end

pick_package = function(client, bufnr, kind, dest)
  execute(client, bufnr, "lathe.packages", { moduleRel = dest.module }, function(packages)
    packages = packages or {}
    local entries = {}
    for _, entry in ipairs(packages) do
      if entry.pkg ~= "" then
        entries[#entries + 1] =
          { label = ("%s (%s)"):format(entry.pkg, entry.scope), pkg = entry.pkg, scope = entry.scope }
      end
    end

    -- The buffer's own package on top, ＋ New package… right behind it, then the long tail: the common
    -- "add a sibling from the guided flow" pick is item 1, and creating a package stays within reach
    -- instead of buried at the bottom of a large module's list.
    local items = {}
    local context = M._take_context(entries, dest.contextPkg)
    if context then
      items[#items + 1] = context
    end

    items[#items + 1] = { label = "＋ New package…", new = true }
    for _, entry in ipairs(entries) do
      items[#items + 1] = entry
    end

    vim.ui.select(items, { prompt = "Package:", format_item = label_of }, function(choice)
      if not choice then
        return
      end

      if choice.new then
        local base = M._base_package(packages)
        -- A deliberate New-package entry may be any package, including empty — that is an explicit
        -- choice of the default package (the invariant only forbids defaulting by omission). Only a
        -- cancel (nil) aborts.
        vim.ui.input({ prompt = "New package: ", default = base ~= "" and base .. "." or "" }, function(pkg)
          if pkg then
            dest.pkg = vim.trim(pkg)
            finish(client, bufnr, kind, dest)
          end
        end)
        return
      end

      dest.pkg = choice.pkg
      dest.scope = dest.scope or choice.scope
      finish(client, bufnr, kind, dest)
    end)
  end)
end

-- test forces the test scope; package-info skips the name prompt, module-info prompts a seeded module
-- name, the rest prompt the type name.
finish = function(client, bufnr, kind, dest)
  local scope = kind == "test" and "test" or (dest.scope or "main")

  if kind == "package-info" then
    submit(client, bufnr, kind, dest, scope, "package-info")
    return
  end

  if kind == "module-info" then
    create_module_info(client, bufnr, dest)
    return
  end

  input_nonempty({
    prompt = name_label(kind, dest, scope) .. ": ",
    default = kind == "test" and M._test_seed(bufnr) or "",
  }, function(name)
    submit(client, bufnr, kind, dest, scope, name)
  end)
end

-- module-info takes no package, scope, or type name — only the module. Its JPMS name is derived from
-- the module's base package and it always lands at the main source root, so there is no prompt. The
-- rare case where nothing can be derived (a module with no packages yet) falls back to asking.
create_module_info = function(client, bufnr, dest)
  execute(client, bufnr, "lathe.packages", { moduleRel = dest.module }, function(packages)
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

-- ── entry points ─────────────────────────────────────────────────────────────

-- No location typed: fill the destination from the buffer context (the 80% "add a sibling" case).
local function from_context(client, bufnr, kind, ctx)
  proceed(client, bufnr, kind, { module = ctx.moduleRel, scope = ctx.scope, pkg = ctx.pkg })
end

-- A typed location resolves on its own -- context never partial-fills it. A missing module falls back
-- to the sole module (else the picker); a missing package routes to the picker (proceed).
local function from_location(client, bufnr, kind, location, modules)
  local parsed = M._parse_location(location)
  if not parsed.module and #modules == 1 then
    parsed.module = modules[1]
  end

  proceed(client, bufnr, kind, { module = parsed.module, scope = parsed.scope, pkg = parsed.pkg })
end

-- Bare :LatheNew (or a no-context fast invocation): the guided picker, kind first when not given. A
-- resolved context (may be nil) never skips a step here -- guided means "let me pick" -- but its
-- module and package seed the pickers to float the buffer's own module and package to the top.
local function guided(client, bufnr, kind, ctx)
  local dest = { contextModule = ctx and ctx.moduleRel, contextPkg = ctx and ctx.pkg }
  if kind then
    return proceed(client, bufnr, kind, dest)
  end

  vim.ui.select(KINDS, { prompt = "What's new:", format_item = label_of }, function(item)
    if item then
      proceed(client, bufnr, item.type, dest)
    end
  end)
end

-- kind_arg / location_arg come from the command line and skip the corresponding step when present.
function M.create(kind_arg, location_arg)
  local bufnr = vim.api.nvim_get_current_buf()
  local client = lathe_client(bufnr)
  if not client then
    warn("server not attached — creation needs the language server")
    return
  end

  if not kind_arg or kind_arg == "" then
    execute(client, bufnr, "lathe.resolveContext", { uri = vim.uri_from_bufnr(bufnr) }, function(ctx)
      guided(client, bufnr, nil, ctx)
    end)
    return
  end

  if not vim.tbl_contains(KIND_TOKENS, kind_arg) then
    warn("unknown kind: " .. kind_arg)
    return
  end

  if location_arg and location_arg ~= "" then
    execute(client, bufnr, "lathe.modules", {}, function(modules)
      from_location(client, bufnr, kind_arg, location_arg, modules or {})
    end)
    return
  end

  execute(client, bufnr, "lathe.resolveContext", { uri = vim.uri_from_bufnr(bufnr) }, function(ctx)
    if ctx then
      from_context(client, bufnr, kind_arg, ctx)
    else
      guided(client, bufnr, kind_arg)
    end
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

-- Prime the module/package cache off any active run, for command-line completion (which cannot block
-- on an LSP round-trip). Silent when the server is not attached.
function M._refresh_async()
  local bufnr = vim.api.nvim_get_current_buf()
  local client = lathe_client(bufnr)
  if not client then
    return
  end

  execute(client, bufnr, "lathe.modules", {}, function(modules)
    cache.modules = modules or {}
    for _, module in ipairs(cache.modules) do
      execute(client, bufnr, "lathe.packages", { moduleRel = module }, function(packages)
        local list = {}
        for _, entry in ipairs(packages or {}) do
          if entry.pkg ~= "" then
            list[#list + 1] = entry.pkg
          end
        end
        cache.by_module[module] = list
      end)
    end
  end)
end

-- Position-aware completion for the location argument: at the head, module (`module:`) and scope
-- (`main:`/`test:`) prefixes; once a module segment is present, that module's packages.
function M._complete_location(arglead)
  local prefix = arglead:match("^(.*:)") or ""
  local tail = arglead:sub(#prefix + 1)
  local segments = prefix == "" and {} or vim.split(prefix:sub(1, #prefix - 1), ":", { plain = true })

  local module
  for _, seg in ipairs(segments) do
    if seg ~= "main" and seg ~= "test" then
      module = seg
    end
  end

  local candidates = {}
  if module and cache.by_module[module] then
    candidates = cache.by_module[module]
  elseif #segments == 0 then
    for _, name in ipairs(cache.modules) do
      candidates[#candidates + 1] = name .. ":"
    end
    for _, keyword in ipairs({ "main:", "test:" }) do
      candidates[#candidates + 1] = keyword
    end
  end

  return vim.tbl_map(function(candidate)
    return prefix .. candidate
  end, prefix_matches(candidates, tail))
end

-- Command-line completion for `:LatheNew`: the kind at the first argument, the location at the second.
function M._cmd_complete(arglead, cmdline, _)
  local parts = vim.split(cmdline, "%s+", { trimempty = true })
  local argpos = cmdline:match("%s$") and #parts or (#parts - 1)
  if argpos <= 1 then
    return prefix_matches(KIND_TOKENS, arglead or "")
  end

  if #cache.modules == 0 then
    M._refresh_async()
  end
  return M._complete_location(arglead or "")
end

function M.setup()
  vim.api.nvim_create_user_command("LatheNew", function(opts)
    M.create(opts.fargs[1], opts.fargs[2])
  end, {
    nargs = "*",
    complete = M._cmd_complete,
    desc = "Lathe: create a new class/interface/record/enum/test/package-info/module-info",
  })
end

return M
