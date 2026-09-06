-- :LatheNew -- scaffold a new class/interface/record/enum through the Lathe server. The server owns
-- every Java/Maven decision (which module and source root, the package, the skeleton, the caret);
-- this client only drives the pickers and writes the file the server returns. Creation is a Java
-- operation, so it requires the Lathe server to be attached.
--
-- Flow: pick the kind -> resolve the buffer's context (for preselect) -> pick the module (skipped
-- when there is one) -> pick the package or "New package..." -> name it -> create. Works with no
-- file open (the pickers stand in for a buffer anchor).

local M = {}

local KINDS = {
  { label = "Class", type = "class" },
  { label = "Interface", type = "interface" },
  { label = "Record", type = "record" },
  { label = "Enum", type = "enum" },
}

-- Sentinel picker entry that starts a new package rather than choosing an existing one.
local NEW_PACKAGE = { new = true }

local function warn(message)
  vim.notify("Lathe: " .. message, vim.log.levels.WARN, { title = "Lathe" })
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
-- line -> Neovim 1-based row). Refuses to overwrite an existing file.
function M._open(result)
  if vim.fn.filereadable(result.path) == 1 then
    warn(vim.fn.fnamemodify(result.path, ":t") .. " already exists")
    return
  end

  vim.fn.mkdir(vim.fn.fnamemodify(result.path, ":h"), "p")
  vim.fn.writefile(M._lines(result.content), result.path)
  vim.cmd.edit(vim.fn.fnameescape(result.path))
  pcall(vim.api.nvim_win_set_cursor, 0, { result.caret.line + 1, result.caret.character })
end

-- vim.ui.select has no native preselect, so move the match to the front where the picker highlights
-- it by default. No-op when nothing matches.
function M._prefer(list, match)
  for i, item in ipairs(list) do
    if match(item) then
      table.insert(list, 1, table.remove(list, i))
      return
    end
  end
end

function M._format_package(item)
  if item.new then
    return "＋ New package…"
  end

  local label = item.pkg ~= "" and item.pkg or "(default package)"
  return item.scope == "test" and (label .. " (test)") or label
end

local function create_type(client, bufnr, type, module, scope, pkg, name)
  execute(client, bufnr, "lathe.createType", {
    moduleRel = module,
    kind = scope,
    pkg = pkg,
    type = type,
    name = name,
  }, M._open)
end

local function prompt_name(client, bufnr, type, module, scope, pkg)
  local where = pkg ~= "" and pkg or "the default package"
  vim.ui.input({ prompt = type .. " name in " .. where .. ": " }, function(name)
    if name and name ~= "" then
      create_type(client, bufnr, type, module, scope, pkg, name)
    end
  end)
end

-- The main/test scope for a brand-new package: the buffer's scope when creating in its own module,
-- else main -- unless the module carries both roots and there is no anchor to infer from, when we
-- ask. Async (may prompt), so it takes a continuation.
local function resolve_scope(context, module, entries, cb)
  if context and context.moduleRel == module then
    cb(context.scope)
    return
  end

  local has_main, has_test = false, false
  for _, entry in ipairs(entries) do
    has_main = has_main or entry.scope == "main"
    has_test = has_test or entry.scope == "test"
  end
  if has_main and has_test then
    vim.ui.select({ "main", "test" }, { prompt = "Source scope:" }, function(scope)
      if scope then
        cb(scope)
      end
    end)
    return
  end

  cb(has_test and "test" or "main")
end

local function new_package(client, bufnr, type, context, module, entries)
  local seed = (context and context.moduleRel == module) and context.pkg or ""
  vim.ui.input({ prompt = "New package: ", default = seed }, function(pkg)
    if pkg then
      resolve_scope(context, module, entries, function(scope)
        prompt_name(client, bufnr, type, module, scope, pkg)
      end)
    end
  end)
end

local function pick_package(client, bufnr, type, context, module)
  execute(client, bufnr, "lathe.packages", { moduleRel = module }, function(entries)
    local items = { NEW_PACKAGE }
    vim.list_extend(items, entries)
    if context and context.moduleRel == module then
      M._prefer(items, function(item)
        return not item.new and item.pkg == context.pkg and item.scope == context.scope
      end)
    end

    vim.ui.select(items, {
      prompt = "Package in " .. module .. ":",
      format_item = M._format_package,
    }, function(choice)
      if not choice then
        return
      end

      if choice.new then
        new_package(client, bufnr, type, context, module, entries)
      else
        prompt_name(client, bufnr, type, module, choice.scope, choice.pkg)
      end
    end)
  end)
end

local function pick_module(client, bufnr, type, context)
  execute(client, bufnr, "lathe.modules", {}, function(modules)
    if #modules == 0 then
      warn("no modules found — run a build first")
      return
    end
    if #modules == 1 then
      pick_package(client, bufnr, type, context, modules[1])
      return
    end

    if context then
      M._prefer(modules, function(module)
        return module == context.moduleRel
      end)
    end

    vim.ui.select(modules, { prompt = "Module:" }, function(module)
      if module then
        pick_package(client, bufnr, type, context, module)
      end
    end)
  end)
end

--- Pick the kind, resolve the buffer context (for preselect), then narrow module -> package, prompt
--- for the name, and create via the server.
function M.create()
  local bufnr = vim.api.nvim_get_current_buf()
  local client = vim.lsp.get_clients({ name = "lathe", bufnr = bufnr })[1]
  if not client then
    warn("server not attached — creation needs the language server")
    return
  end

  vim.ui.select(KINDS, {
    prompt = "New Lathe type:",
    format_item = function(kind)
      return kind.label
    end,
  }, function(kind)
    if kind then
      execute(client, bufnr, "lathe.resolveContext", { uri = vim.uri_from_bufnr(bufnr) }, function(context)
        pick_module(client, bufnr, kind.type, context)
      end)
    end
  end)
end

function M.setup()
  vim.api.nvim_create_user_command("LatheNew", M.create, {
    desc = "Lathe: create a new class/interface/record/enum",
  })
end

return M
