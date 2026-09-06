-- :LatheNew -- scaffold a new class/interface/record/enum through the Lathe server. The server owns
-- every Java/Maven decision (which module and source root, the package, the skeleton, the caret);
-- this client only drives the pickers and writes the file the server returns. Creation is a Java
-- operation, so it requires the Lathe server to be attached.

local M = {}

-- Picker label -> the wire token lathe.createType expects.
local KINDS = {
  { label = "Class", type = "class" },
  { label = "Interface", type = "interface" },
  { label = "Record", type = "record" },
  { label = "Enum", type = "enum" },
}

local function warn(message)
  vim.notify("Lathe: " .. message, vim.log.levels.WARN, { title = "Lathe" })
end

-- Dispatch a workspace/executeCommand to the Lathe client; cb receives the decoded result. Shared by
-- the resolveContext and createType steps so the request/error plumbing lives in one place.
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

local function create_in(client, bufnr, type, context, name)
  execute(client, bufnr, "lathe.createType", {
    moduleRel = context.moduleRel,
    kind = context.scope,
    pkg = context.pkg,
    type = type,
    name = name,
  }, M._open)
end

-- Resolve the buffer's module/scope/package on the server, then prompt for the name and create there.
local function resolve_and_create(client, bufnr, type)
  execute(client, bufnr, "lathe.resolveContext", { uri = vim.uri_from_bufnr(bufnr) }, function(context)
    if not context then
      warn("open a file inside a source package first")
      return
    end

    local where = context.pkg ~= "" and context.pkg or "the default package"
    vim.ui.input({ prompt = type .. " name in " .. where .. ": " }, function(name)
      if name and name ~= "" then
        create_in(client, bufnr, type, context, name)
      end
    end)
  end)
end

--- Pick the kind, then resolve context, prompt for the name, and create via the server.
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
      resolve_and_create(client, bufnr, kind.type)
    end
  end)
end

function M.setup()
  vim.api.nvim_create_user_command("LatheNew", M.create, {
    desc = "Lathe: create a new class/interface/record/enum",
  })
end

return M
