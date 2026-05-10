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

local function start_lathe()
  local root = vim.fs.root(0, '.lathe')
  if not root then return end
  vim.lsp.start({
    name = 'lathe',
    cmd = cmd,
    root_dir = root,
    capabilities = vim.lsp.protocol.make_client_capabilities(),
    on_attach = function(_, bufnr)
      vim.notify('Lathe: attached to ' .. vim.api.nvim_buf_get_name(bufnr), vim.log.levels.INFO)
    end,
  })
end

vim.api.nvim_create_autocmd('FileType', { pattern = 'java', callback = start_lathe })

vim.api.nvim_set_hl(0, '@lsp.type.typeParameter.java', { link = '@lsp.type.interface' })
vim.diagnostic.config({ virtual_text = true })

-- Handle any Java file already open when this script is sourced via -c
if vim.bo.filetype == 'java' then
  start_lathe()
end
