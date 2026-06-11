-- Lathe LSP plugin for Neovim.
--
-- Installation: copy this file (or symlink it) into your Neovim config and call:
--   require('lathe').setup()
--
-- The launcher is read from ~/.cache/lathe/current/lathe-launcher.sh, which is
-- written by `mvn process-test-classes` when the Lathe Maven plugin is present.
-- Override the cache location by setting LATHE_CACHE in your environment.

local M = {}

local MIN_SERVER_VERSION = { 0, 1, 0 }

local function cache_root()
  return vim.fs.normalize(vim.env.LATHE_CACHE or (vim.fn.expand('~') .. '/.cache/lathe'))
end

local function launcher_path()
  return cache_root() .. '/current/lathe-launcher.sh'
end

local function attach_existing_lathe(bufnr)
  for _, client in ipairs(vim.lsp.get_clients({ name = 'lathe' })) do
    if not vim.lsp.buf_is_attached(bufnr, client.id) then
      vim.lsp.buf_attach_client(bufnr, client.id)
    end
    return true
  end
  return false
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

  local cache_prefix = 'lathe-source://' .. cache_root() .. '/'
  if vim.startswith(uri, cache_prefix) then
    vim.api.nvim_buf_set_name(buf, 'lathe-source://' .. uri:sub(#cache_prefix + 1))
  end

  attach_existing_lathe(buf)
end

local function start_lathe()
  local launcher = launcher_path()
  if vim.fn.executable(launcher) ~= 1 then
    return
  end
  local root = vim.fs.root(0, '.lathe')
  if not root then
    return
  end
  vim.lsp.start({
    name = 'lathe',
    cmd = { launcher },
    root_dir = root,
    capabilities = vim.lsp.protocol.make_client_capabilities(),
    on_attach = function(client, _)
      local info = client.server_info
      if info and info.version then
        local v = vim.version.parse(info.version)
        if v and vim.version.lt(v, MIN_SERVER_VERSION) then
          vim.notify(
            ('lathe-server %s is too old (need >= %d.%d.%d) — re-run mvn process-test-classes')
              :format(info.version, unpack(MIN_SERVER_VERSION)),
            vim.log.levels.WARN
          )
        end
      end
    end,
  })
end

function M.setup()
  vim.api.nvim_create_autocmd('FileType', { pattern = 'java', callback = start_lathe })
  vim.api.nvim_create_autocmd('BufReadCmd', {
    pattern = 'lathe-source://*',
    callback = open_lathe_source,
  })
end

return M
