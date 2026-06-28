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

local function cache_root()
  return vim.fs.normalize(vim.env.LATHE_CACHE or (vim.fn.expand('~') .. '/.cache/lathe'))
end

function M.setup(opts)
  opts = opts or {}
  local root = cache_root()
  local launcher = root .. '/current/lathe-launcher.sh'
  
  local augroup = vim.api.nvim_create_augroup('LathePlugin', { clear = true })

  vim.lsp.config('lathe', {
    cmd = { launcher },
    filetypes = { 'java' },
    single_file_support = false,
    root_dir = function(bufnr, on_dir)
      if vim.fn.executable(launcher) ~= 1 then
        return
      end
      local fname = vim.api.nvim_buf_get_name(bufnr)
      local r = vim.fs.root(fname, '.lathe')

      if not r and vim.startswith(fname, root) then
        r = M.last_root
        if not r then
          for _, buf in ipairs(vim.api.nvim_list_bufs()) do
            local bname = vim.api.nvim_buf_get_name(buf)
            if bname ~= '' then
              r = vim.fs.root(bname, '.lathe')
              if r then break end
            end
          end
        end
      end

      if r then
        M.last_root = r
        on_dir(r)
      else
        vim.lsp.log.info('lathe: no .lathe root found for ' .. fname)
      end
    end,
    capabilities = opts.capabilities or vim.lsp.protocol.make_client_capabilities(),
  })
  vim.lsp.enable('lathe')

  local format_on_save = opts.format_on_save ~= false
  if format_on_save then
    vim.api.nvim_create_autocmd('LspAttach', {
      group = augroup,
      callback = function(args)
        local client = vim.lsp.get_client_by_id(args.data.client_id)
        if client and client.name == 'lathe' then
          vim.api.nvim_create_autocmd('BufWritePre', {
            group = augroup,
            buffer = args.buf,
            callback = function()
              vim.lsp.buf.format({ bufnr = args.buf, id = args.data.client_id, async = false })
            end,
          })
        end
      end,
    })
  end

  local cache_pattern = root .. '/**'
  vim.api.nvim_create_autocmd('BufReadPre', {
    group = augroup,
    pattern = cache_pattern,
    callback = function(ev)
      vim.bo[ev.buf].swapfile = false
    end,
  })
  vim.api.nvim_create_autocmd('BufReadPost', {
    group = augroup,
    pattern = cache_pattern,
    callback = function(ev)
      vim.bo[ev.buf].readonly = true
      vim.bo[ev.buf].modifiable = false
    end,
  })
end

return M
