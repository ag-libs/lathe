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

    cb(result)
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

-- ── flow: fill the missing pieces of a destination, then create ──────────────

local proceed, pick_module, pick_package, finish, prompt_module_name

-- package-info sends the fixed stem as name (ignored by the server); module-info sends the JPMS module
-- name and always the main root.
local function submit(client, bufnr, kind, dest, scope, name)
  execute(client, bufnr, "lathe.createType", {
    moduleRel = dest.module,
    kind = scope,
    pkg = dest.pkg or "",
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

    vim.ui.select(modules, { prompt = "Module:" }, function(module)
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
    local items = {}
    for _, entry in ipairs(packages) do
      if entry.pkg ~= "" then
        items[#items + 1] = { label = ("%s (%s)"):format(entry.pkg, entry.scope), pkg = entry.pkg, scope = entry.scope }
      end
    end
    items[#items + 1] = { label = "＋ New package…", new = true }

    vim.ui.select(items, {
      prompt = "Package:",
      format_item = function(item)
        return item.label
      end,
    }, function(choice)
      if not choice then
        return
      end

      if choice.new then
        local base = M._base_package(packages)
        vim.ui.input({ prompt = "New package: ", default = base ~= "" and base .. "." or "" }, function(pkg)
          if pkg and vim.trim(pkg) ~= "" then
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
    prompt_module_name(client, bufnr, dest)
    return
  end

  vim.ui.input({
    prompt = name_label(kind, dest, scope) .. ": ",
    default = kind == "test" and M._test_seed(bufnr) or "",
  }, function(name)
    if name and vim.trim(name) ~= "" then
      submit(client, bufnr, kind, dest, scope, vim.trim(name))
    end
  end)
end

prompt_module_name = function(client, bufnr, dest)
  execute(client, bufnr, "lathe.packages", { moduleRel = dest.module }, function(packages)
    vim.ui.input({ prompt = "Module name: ", default = M._base_package(packages) }, function(name)
      if name and vim.trim(name) ~= "" then
        submit(client, bufnr, "module-info", dest, "main", vim.trim(name))
      end
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

-- Bare :LatheNew (or a no-context fast invocation): the guided picker, kind first when not given.
local function guided(client, bufnr, kind)
  if kind then
    return proceed(client, bufnr, kind, {})
  end

  vim.ui.select(KINDS, {
    prompt = "What's new:",
    format_item = function(item)
      return item.label
    end,
  }, function(item)
    if item then
      proceed(client, bufnr, item.type, {})
    end
  end)
end

-- kind_arg / location_arg come from the command line and skip the corresponding step when present.
function M.create(kind_arg, location_arg)
  local bufnr = vim.api.nvim_get_current_buf()
  local client = vim.lsp.get_clients({ name = "lathe", bufnr = bufnr })[1]
  if not client then
    warn("server not attached — creation needs the language server")
    return
  end

  if not kind_arg or kind_arg == "" then
    return guided(client, bufnr, nil)
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
  local client = vim.lsp.get_clients({ name = "lathe", bufnr = bufnr })[1]
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
