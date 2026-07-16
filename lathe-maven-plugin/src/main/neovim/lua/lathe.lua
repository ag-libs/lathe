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

--- Marker file identifying a workspace root, exported so other plugins that
--- need to detect a Lathe workspace (e.g. project pickers) can reference the
--- same name instead of hard-coding it separately.
M.ROOT_MARKER = '.lathe'

local function cache_root()
  return vim.fs.normalize(vim.env.LATHE_CACHE or (vim.fn.expand('~') .. '/.cache/lathe'))
end

--- Resolve the workspace root for a buffer, for any code (this plugin's own
--- root_dir included) that needs to know which project a buffer belongs to.
---
--- Walks up from the buffer's path looking for `M.ROOT_MARKER`. If the buffer
--- lives inside the Lathe cache instead (decompiled/dependency sources have no
--- marker of their own), falls back to the last resolved root, then to
--- scanning other open buffers for one that does resolve. Memoizes the result
--- in `M.last_root` so repeated lookups from cache-only buffers stay stable.
---@param bufnr integer? defaults to the current buffer
---@return string? root
function M.get_root(bufnr)
  local fname = vim.api.nvim_buf_get_name(bufnr or 0)
  local root = vim.fs.root(fname, M.ROOT_MARKER)
  if root then
    M.last_root = root
    return root
  end

  if not vim.startswith(fname, cache_root()) then
    return nil
  end

  if M.last_root then
    return M.last_root
  end

  for _, buf in ipairs(vim.api.nvim_list_bufs()) do
    local bname = vim.api.nvim_buf_get_name(buf)
    if bname ~= '' then
      root = vim.fs.root(bname, M.ROOT_MARKER)
      if root then
        M.last_root = root
        return root
      end
    end
  end

  return nil
end

-- Exported so other Neovim config code (e.g. a Telescope keymap scoping a
-- picker to the project, or a config that lazy-loads this plugin's own `dir`)
-- can resolve the same cache location instead of hard-coding it separately.
M.cache_root = cache_root

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
      local r = M.get_root(bufnr)
      if r then
        on_dir(r)
      else
        vim.lsp.log.info('lathe: no ' .. M.ROOT_MARKER .. ' root found for ' .. vim.api.nvim_buf_get_name(bufnr))
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
