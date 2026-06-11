-- Lathe LSP plugin for Neovim (requires Neovim 0.11+).
--
-- Installation: copy this file (or symlink it) into your Neovim config and call:
--   require('lathe').setup()
--
-- The launcher is read from ~/.cache/lathe/current/lathe-launcher.sh, which is
-- written by `mvn process-test-classes` when the Lathe Maven plugin is present.
-- Override the cache location by setting LATHE_CACHE in your environment.
--
-- Options (all optional):
--   capabilities     LSP capabilities table; defaults to vim.lsp.protocol.make_client_capabilities()
--   format_on_save   boolean; format buffer on write via lathe (default: true)
--
-- Set LATHE_DEBUG=1 in the environment to enable debug logging in the server process.
-- Requires the Java Treesitter parser for indentation (:TSInstall java).

local M = {}

local SCHEME = 'lathe-source://'

local function cache_root()
  return vim.fs.normalize(vim.env.LATHE_CACHE or (vim.fn.expand('~') .. '/.cache/lathe'))
end

local function open_lathe_source(args)
  local uri = args.match
  local path = uri:sub(#SCHEME + 1)
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

function M.setup(opts)
  opts = opts or {}
  local root = cache_root()
  local launcher = root .. '/current/lathe-launcher.sh'

  vim.lsp.config('lathe', {
    cmd = { launcher },
    filetypes = { 'java' },
    root_dir = function(bufnr, on_dir)
      if vim.fn.executable(launcher) ~= 1 then
        return
      end
      local fname = vim.api.nvim_buf_get_name(bufnr)
      local r = vim.fs.root(fname, '.lathe')
      if r then
        on_dir(r)
        return
      end
      if vim.startswith(fname, root) then
        for _, buf in ipairs(vim.api.nvim_list_bufs()) do
          local bname = vim.api.nvim_buf_get_name(buf)
          if bname ~= '' then
            r = vim.fs.root(bname, '.lathe')
            if r then
              on_dir(r)
              return
            end
          end
        end
      end
    end,
    capabilities = opts.capabilities or vim.lsp.protocol.make_client_capabilities(),
  })
  vim.lsp.enable('lathe')

  local format_on_save = opts.format_on_save ~= false
  if format_on_save then
    vim.api.nvim_create_autocmd('LspAttach', {
      callback = function(args)
        local client = vim.lsp.get_client_by_id(args.data.client_id)
        if client and client.name == 'lathe' then
          vim.api.nvim_create_autocmd('BufWritePre', {
            buffer = args.buf,
            callback = function()
              vim.lsp.buf.format({ bufnr = args.buf, id = args.data.client_id, async = false })
            end,
          })
        end
      end,
    })
  end

  vim.api.nvim_create_autocmd('BufReadCmd', {
    pattern = SCHEME .. '*',
    callback = open_lathe_source,
  })
end

return M
