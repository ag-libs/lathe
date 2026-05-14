-- Adds Lathe LSP on top of the user's existing Neovim config.
-- Loaded via: dev/nvim.sh [file]
-- No other customisation — highlights, tree-sitter and keymaps come from user config.

local lathe_src = vim.env.LATHE_SRC
local java_home = vim.env.JAVA_HOME

if not lathe_src or not java_home then
  vim.notify('LATHE_SRC and JAVA_HOME must be set', vim.log.levels.ERROR)
  return
end

local module_path = lathe_src .. '/lathe-server/target/classes'
  .. ':' .. lathe_src .. '/lathe-server/target/dependency'

-- Classpath javac plugins and Google Java Format use javac internals; Lathe server code does not.
local javac_packages = {
  'api', 'code', 'comp', 'file', 'main', 'model', 'parser', 'processing', 'tree', 'util',
}
local cmd = { java_home .. '/bin/java' }
for _, pkg in ipairs(javac_packages) do
  table.insert(cmd, '--add-exports')
  table.insert(cmd, 'jdk.compiler/com.sun.tools.javac.' .. pkg .. '=ALL-UNNAMED')
  table.insert(cmd, '--add-opens')
  table.insert(cmd, 'jdk.compiler/com.sun.tools.javac.' .. pkg .. '=ALL-UNNAMED')
  table.insert(cmd, '--add-exports')
  table.insert(cmd, 'jdk.compiler/com.sun.tools.javac.' .. pkg .. '=com.google.googlejavaformat')
  table.insert(cmd, '--add-opens')
  table.insert(cmd, 'jdk.compiler/com.sun.tools.javac.' .. pkg .. '=com.google.googlejavaformat')
end
table.insert(cmd, '--add-modules')
table.insert(cmd, 'java.net.http')
table.insert(cmd, '--module-path')
table.insert(cmd, module_path)
table.insert(cmd, '--module')
table.insert(cmd, 'io.github.aglibs.lathe.server/io.github.aglibs.lathe.server.LatheServer')

local debug_port = vim.env.LATHE_DEBUG_PORT
if debug_port and debug_port ~= '' then
  table.insert(cmd, 2, '-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:' .. debug_port)
end

local function lathe_cache_root()
  return vim.fs.normalize(vim.fn.expand('~/.cache/lathe'))
end

local function is_external_source(bufnr)
  local name = vim.fs.normalize(vim.api.nvim_buf_get_name(bufnr))
  local cache = lathe_cache_root()
  return vim.startswith(name, cache .. '/deps/') or vim.startswith(name, cache .. '/jdks/')
end

local function attach_existing_lathe(bufnr)
  for _, client in ipairs(vim.lsp.get_clients({ name = 'lathe' })) do
    if not vim.lsp.buf_is_attached(bufnr, client.id) then
      vim.lsp.buf_attach_client(bufnr, client.id)
      if vim.env.LATHE_DEBUG == '1' then
        vim.notify('Lathe: attached external source to existing client', vim.log.levels.INFO)
      end
    end
    return true
  end
  if vim.env.LATHE_DEBUG == '1' then
    vim.notify('Lathe: no existing client for external source', vim.log.levels.WARN)
  end
  return false
end

local function attach_external_source(bufnr)
  if is_external_source(bufnr) then
    attach_existing_lathe(bufnr)
    return true
  end
  return false
end

local function start_lathe()
  local bufnr = vim.api.nvim_get_current_buf()
  if attach_external_source(bufnr) then
    return
  end

  local root = vim.fs.root(0, '.lathe')
  if not root then
    return
  end
  vim.lsp.start({
    name = 'lathe',
    cmd = cmd,
    root_dir = root,
    capabilities = vim.lsp.protocol.make_client_capabilities(),
    on_attach = function(_, bufnr)
      if not is_external_source(bufnr) then
        vim.notify('Lathe: attached to ' .. vim.api.nvim_buf_get_name(bufnr), vim.log.levels.INFO)
      end
    end,
  })
end

local function mark_external_source_readonly(args)
  if is_external_source(args.buf) then
    vim.bo[args.buf].readonly = true
    vim.bo[args.buf].modifiable = false
    vim.bo[args.buf].bufhidden = 'hide'
    attach_external_source(args.buf)
  end
end

vim.api.nvim_create_autocmd('FileType', { pattern = 'java', callback = start_lathe })
vim.api.nvim_create_autocmd({ 'BufReadPost', 'BufNewFile' }, {
  pattern = '*.java',
  callback = mark_external_source_readonly,
})

vim.api.nvim_set_hl(0, '@lsp.type.typeParameter.java', { link = '@lsp.type.interface' })
vim.diagnostic.config({ virtual_text = true })

-- Handle any Java file already open when this script is sourced via -c
if vim.bo.filetype == 'java' then
  start_lathe()
end
