-- Lathe LSP plugin for Neovim.
--
-- Installation: copy this file (or symlink it) into your Neovim config and call:
--   require('lathe').setup()
--
-- The launcher is read from ~/.cache/lathe/current/lathe-launcher.sh, which is
-- written by `mvn process-test-classes` when the Lathe Maven plugin is present.
-- Override the cache location by setting LATHE_CACHE in your environment.

local M = {}

local function cache_root()
  return vim.fs.normalize(vim.env.LATHE_CACHE or (vim.fn.expand('~') .. '/.cache/lathe'))
end

local function open_lathe_source(args)
  local uri = args.match
  local path = uri:sub(#'lathe-source://' + 1)
  local buf = args.buf

  vim.bo[buf].swapfile = false
  vim.bo[buf].buftype = 'nofile'
  vim.bo[buf].modifiable = true
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, vim.fn.readfile(path))
  vim.bo[buf].modifiable = false
  vim.bo[buf].filetype = 'java'

  for _, client in ipairs(vim.lsp.get_clients({ name = 'lathe' })) do
    if not vim.lsp.buf_is_attached(buf, client.id) then
      vim.lsp.buf_attach_client(buf, client.id)
    end
    break
  end
end

local function start_lathe()
  local launcher = cache_root() .. '/current/lathe-launcher.sh'
  if vim.fn.executable(launcher) ~= 1 then
    return
  end
  local root = vim.fs.root(0, '.lathe')
  if not root and vim.startswith(vim.api.nvim_buf_get_name(0), cache_root()) then
    for _, buf in ipairs(vim.api.nvim_list_bufs()) do
      local bname = vim.api.nvim_buf_get_name(buf)
      if bname ~= '' then
        local r = vim.fs.root(bname, '.lathe')
        if r then
          root = r
          break
        end
      end
    end
  end
  if not root then
    return
  end
  vim.lsp.start({
    name = 'lathe',
    cmd = { launcher },
    root_dir = root,
    capabilities = vim.lsp.protocol.make_client_capabilities(),
  })
end

function M.setup()
  vim.api.nvim_create_autocmd('FileType', { pattern = 'java', callback = start_lathe })
  vim.api.nvim_create_autocmd('BufReadCmd', {
    pattern = 'lathe-source://*',
    callback = open_lathe_source,
  })
  if vim.bo.filetype == 'java' then
    start_lathe()
  end
end

return M
