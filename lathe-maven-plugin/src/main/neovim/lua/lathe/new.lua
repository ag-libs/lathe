-- :LatheNew -- scaffold a new class/interface/record/enum/test through the Lathe server. The server
-- owns every Java/Maven decision (which module and source root, the package, the skeleton, the
-- caret); this client only drives the two prompts and writes the file the server returns. Creation
-- is a Java operation, so it requires the Lathe server to be attached.
--
-- Flow (at most two questions): pick the kind -> type the name. The name field takes an optional
-- location prefix -- "[module:]package.Name" -- seeded from the buffer's context and backed by
-- completion over the existing packages, so the common case is a couple of keystrokes. Scope
-- (main/test) is inferred from the target package rather than asked. Arguments skip the prompts:
-- `:LatheNew class` skips the kind pick, `:LatheNew class core:com.x.Foo` skips both. `<Tab>` after
-- `:LatheNew ` completes the kind, and after a kind it completes the "module:package" targets.
--
-- `:LatheNew test` matches the buffer: it derives <Name>Test from the current file and drops a
-- JUnit 5 test class into the test root of the same module/package (scope forced to test).

local M = {}

local KINDS = {
  { label = "Class", type = "class" },
  { label = "Interface", type = "interface" },
  { label = "Record", type = "record" },
  { label = "Enum", type = "enum" },
  { label = "Test", type = "test" },
}

local KIND_TOKENS = vim.tbl_map(function(kind)
  return kind.type
end, KINDS)

-- Best-effort snapshot of the workspace's "module:package" targets, used for completion. Refreshed
-- on every run and lazily from command-line completion; command completion must answer
-- synchronously, so it reads this cache rather than issuing an LSP request per keystroke.
local cache = { targets = {} }

local function notify(message, level)
  vim.notify("Lathe: " .. message, level, { title = "Lathe" })
end

local function warn(message)
  notify(message, vim.log.levels.WARN)
end

-- Dispatch a workspace/executeCommand to the Lathe client; cb receives the decoded result. Shared by
-- every server step so the request/error plumbing lives in one place.
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
-- line -> Neovim 1-based row). Never overwrites: if the target already exists, just open it (the
-- name you asked for is where you want to go) and leave the caret to the editor -- the server's caret
-- is for the fresh skeleton, not this file's contents.
function M._open(result)
  local edit = function()
    vim.cmd.edit(vim.fn.fnameescape(result.path))
  end

  if vim.fn.filereadable(result.path) == 1 then
    notify(vim.fn.fnamemodify(result.path, ":t") .. " already exists — opening it", vim.log.levels.INFO)
    edit()
    return
  end

  vim.fn.mkdir(vim.fn.fnamemodify(result.path, ":h"), "p")
  vim.fn.writefile(M._lines(result.content), result.path)
  edit()
  pcall(vim.api.nvim_win_set_cursor, 0, { result.caret.line + 1, result.caret.character })
end

-- ── target grammar ───────────────────────────────────────────────────────────

-- Split a name reply into module/pkg/name. Grammar: "[module:]package.Name", package optional. A
-- missing "module:" prefix falls back to the buffer's module, else the sole module, else nil (the
-- caller then asks). name is "" when the reply is blank/location-only, which the caller rejects.
function M._parse_target(input, ctx, modules)
  input = vim.trim(input or "")
  local module, rest = input:match("^([^:]+):(.*)$")
  if not module then
    rest = input
    if ctx then
      module = ctx.moduleRel
    elseif modules and #modules == 1 then
      module = modules[1]
    end
  end

  rest = vim.trim(rest)
  local pkg, name = rest:match("^(.*)%.([^.]+)$")
  if not name then
    pkg, name = "", rest
  end
  return { module = module, pkg = pkg, name = name }
end

-- A test class name for the type in `stem`: <stem>Test, but leave an already-Test-suffixed stem
-- alone so a stray invocation on FooTest.java does not produce FooTestTest.
function M._test_name(stem)
  return stem:match("Test$") and stem or (stem .. "Test")
end

-- The main/test scope a new type lands in, inferred rather than asked: test kinds force test; an
-- existing target package keeps its own scope; the buffer's package keeps the buffer's scope;
-- everything else (a brand-new package) defaults to main.
function M._infer_scope(kind, module, pkg, entries, ctx)
  if kind == "test" then
    return "test"
  end
  for _, entry in ipairs(entries) do
    if entry.module == module and entry.pkg == pkg then
      return entry.scope
    end
  end
  if ctx and ctx.moduleRel == module and ctx.pkg == pkg then
    return ctx.scope
  end
  return "main"
end

-- The location prefix a completion candidate / seed carries: "module:package" across a multi-module
-- workspace (so the module is explicit), or the bare package when there is only one module.
local function location_prefix(module, pkg, multi)
  return multi and (module .. ":" .. pkg) or pkg
end

-- ── completion ───────────────────────────────────────────────────────────────

local function prefix_matches(list, arglead)
  local out = {}
  for _, item in ipairs(list) do
    if item:find(arglead, 1, true) == 1 then
      out[#out + 1] = item
    end
  end
  return out
end

-- customlist source for the name input (referenced as v:lua...._complete): existing targets from
-- the cache filtered by what the user has typed.
function M._complete(arglead)
  return prefix_matches(cache.targets, arglead or "")
end

-- Command-line completion for `:LatheNew`: the kind at the first argument, then "module:package"
-- targets at the second. The targets come from the cache (primed lazily here since completion can't
-- block on an LSP round-trip); the kinds are always available.
function M._cmd_complete(arglead, cmdline, _)
  local parts = vim.split(cmdline, "%s+", { trimempty = true })
  local argpos = cmdline:match("%s$") and #parts or (#parts - 1)
  if argpos <= 1 then
    return prefix_matches(KIND_TOKENS, arglead or "")
  end

  if #cache.targets == 0 then
    M._refresh_async()
  end
  return M._complete(arglead)
end

-- ── server steps ─────────────────────────────────────────────────────────────

-- Fetch every module's packages and fold them into a flat {module, pkg, scope} list plus a deduped
-- set of "module:package" (or bare "package") completion targets, then refresh the completion cache
-- and hand the entries to cb. Fans out one lathe.packages request per module.
local function gather_targets(client, bufnr, modules, cb)
  local multi = #modules > 1
  local entries, targets, seen = {}, {}, {}
  local pending = #modules

  for _, module in ipairs(modules) do
    execute(client, bufnr, "lathe.packages", { moduleRel = module }, function(packages)
      for _, entry in ipairs(packages or {}) do
        entries[#entries + 1] = { module = module, pkg = entry.pkg, scope = entry.scope }
        local target = location_prefix(module, entry.pkg, multi)
        if target ~= "" and not seen[target] then
          seen[target] = true
          targets[#targets + 1] = target
        end
      end

      pending = pending - 1
      if pending == 0 then
        cache.targets = targets
        cb(entries)
      end
    end)
  end
end

-- Resolve the workspace's modules and their packages (priming the completion cache), then hand
-- (modules, entries) to cb -- or cb(nil) when the workspace has no modules yet. Shared by the create
-- flow and the completion cache refresh.
local function load_targets(client, bufnr, cb)
  execute(client, bufnr, "lathe.modules", {}, function(modules)
    if not modules or #modules == 0 then
      cb(nil)
      return
    end

    gather_targets(client, bufnr, modules, function(entries)
      cb(modules, entries)
    end)
  end)
end

-- Best-effort cache refresh for command-line completion, off any active run. Silent when the server
-- is not attached (completion just offers the kinds until a run populates the cache).
function M._refresh_async()
  local bufnr = vim.api.nvim_get_current_buf()
  local client = vim.lsp.get_clients({ name = "lathe", bufnr = bufnr })[1]
  if client then
    load_targets(client, bufnr, function() end)
  end
end

local function submit(client, bufnr, kind, parsed, entries, ctx)
  if not parsed.name or parsed.name == "" then
    return
  end

  local scope = M._infer_scope(kind, parsed.module, parsed.pkg, entries, ctx)
  execute(client, bufnr, "lathe.createType", {
    moduleRel = parsed.module,
    kind = scope,
    pkg = parsed.pkg,
    type = kind,
    name = parsed.name,
  }, M._open)
end

-- ── prompts ──────────────────────────────────────────────────────────────────

local function buffer_stem(bufnr)
  local name = vim.api.nvim_buf_get_name(bufnr)
  return name ~= "" and vim.fn.fnamemodify(name, ":t:r") or ""
end

-- The prefilled reply for the name prompt: the buffer's location, plus -- for a test whose buffer is
-- the class under test (main scope) -- the derived <Name>Test. From a test buffer there is no class
-- under test (the derived name would just point back at the current file), so seed only the location
-- and let the user name the new sibling test, like the other kinds. Blank when there is no anchor,
-- except a test still seeds <Name>Test from the current file name.
function M._default_target(kind, ctx, multi, stem)
  if not ctx then
    return (kind == "test" and stem ~= "") and M._test_name(stem) or ""
  end

  local prefix = location_prefix(ctx.moduleRel, ctx.pkg, multi)
  local sep = ctx.pkg ~= "" and "." or ""
  if kind == "test" and ctx.scope == "main" and stem ~= "" then
    return prefix .. sep .. M._test_name(stem)
  end
  return prefix .. sep
end

local function prompt_label(kind, multi)
  local grammar = multi and "module:pkg.Name" or "pkg.Name"
  local noun = kind == "test" and "Test class" or (kind:sub(1, 1):upper() .. kind:sub(2))
  return ("%s (%s): "):format(noun, grammar)
end

local function choose_kind(kind_arg, cb)
  if kind_arg and kind_arg ~= "" then
    if not vim.tbl_contains(KIND_TOKENS, kind_arg) then
      warn("unknown kind: " .. kind_arg)
      return
    end
    cb(kind_arg)
    return
  end

  vim.ui.select(KINDS, {
    prompt = "What's new:",
    format_item = function(kind)
      return kind.label
    end,
  }, function(kind)
    if kind then
      cb(kind.type)
    end
  end)
end

-- Resolve the reply to a full target and create. An unresolved module (no prefix, no context, more
-- than one module) is the one case that still needs a pick.
local function resolve_and_submit(client, bufnr, kind, ctx, modules, entries, input)
  if not input or vim.trim(input) == "" then
    return
  end

  local parsed = M._parse_target(input, ctx, modules)
  if parsed.module then
    submit(client, bufnr, kind, parsed, entries, ctx)
    return
  end

  vim.ui.select(modules, { prompt = "Module:" }, function(module)
    if module then
      parsed.module = module
      submit(client, bufnr, kind, parsed, entries, ctx)
    end
  end)
end

local function prompt_target(client, bufnr, kind, ctx, modules, entries, target_arg)
  if target_arg and target_arg ~= "" then
    resolve_and_submit(client, bufnr, kind, ctx, modules, entries, target_arg)
    return
  end

  local multi = #modules > 1
  vim.ui.input({
    prompt = prompt_label(kind, multi),
    default = M._default_target(kind, ctx, multi, buffer_stem(bufnr)),
    completion = "customlist,v:lua.require'lathe.new'._complete",
  }, function(input)
    resolve_and_submit(client, bufnr, kind, ctx, modules, entries, input)
  end)
end

--- Pick the kind (unless given), resolve the buffer context and the workspace's modules/packages,
--- then prompt for the name and create via the server. `kind_arg`/`target_arg` come from the
--- command line and skip the corresponding prompt when present.
function M.create(kind_arg, target_arg)
  local bufnr = vim.api.nvim_get_current_buf()
  local client = vim.lsp.get_clients({ name = "lathe", bufnr = bufnr })[1]
  if not client then
    warn("server not attached — creation needs the language server")
    return
  end

  choose_kind(kind_arg, function(kind)
    execute(client, bufnr, "lathe.resolveContext", { uri = vim.uri_from_bufnr(bufnr) }, function(ctx)
      load_targets(client, bufnr, function(modules, entries)
        if not modules then
          warn("no modules found — run a build first")
          return
        end

        prompt_target(client, bufnr, kind, ctx, modules, entries, target_arg)
      end)
    end)
  end)
end

function M.setup()
  vim.api.nvim_create_user_command("LatheNew", function(opts)
    M.create(opts.fargs[1], opts.fargs[2])
  end, {
    nargs = "*",
    complete = M._cmd_complete,
    desc = "Lathe: create a new class/interface/record/enum/test",
  })
end

return M
