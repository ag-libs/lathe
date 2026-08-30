-- Lathe LSP plugin for Neovim (requires Neovim 0.11.7+; 0.12+ recommended).
--
-- Installation: copy this file (or symlink it) into your Neovim config and call:
--   require('lathe').setup()
--
-- The launcher is read from ~/.cache/lathe/current/lathe-launcher.sh, which is
-- written by `mvn process-test-classes` when the Lathe Maven plugin is present.
-- Override the cache location by setting LATHE_CACHE in your environment.
--
-- Options (all optional):
--   capabilities        LSP capabilities table; defaults to vim.lsp.protocol.make_client_capabilities()
--   indent_style        "editor_config" | "google"; Java indentation profile (default: "editor_config").
--                       editor_config follows Neovim's built-in EditorConfig (4-space fallback);
--                       google uses fixed 2-space / 4-space Google Java Format indentation.
--   continuation_indent number; pins the wrapped-line continuation width (default: twice the block width).
--   formatter           nil | "google"; enables on-demand Google Java Format via the server (default: nil).
--   format_on_save      boolean; format on write; only wired when formatter == "google" (default: false).
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

--- Notify the user when the language server process exits unexpectedly. A clean
--- shutdown (exit code 0) or Neovim itself quitting stays silent; any other exit
--- surfaces an error pointing at `:LspLog`, since a dead server cannot report for
--- itself. Wired as the `lathe` client's on_exit, so it covers both the
--- filetype auto-start and `:LatheStart`.
---@param code integer process exit code
function M.on_server_exit(code)
  if code == 0 or vim.v.exiting ~= vim.NIL then
    return
  end

  vim.notify(
    ('Lathe: language server exited unexpectedly (code %d); see :LspLog.'):format(code),
    vim.log.levels.ERROR,
    { title = 'Lathe' }
  )
end

--- Start (or reuse) the Lathe client for the current directory and attach it to
--- `bufnr`, so workspace navigation (e.g. `workspace/symbol`) works without a
--- Java file open. Resolves the root from the buffer first, then the working
--- directory. Backs the `:LatheStart` command.
---@param bufnr integer? defaults to the current buffer
function M.start(bufnr)
  bufnr = bufnr or vim.api.nvim_get_current_buf()
  local launcher = cache_root() .. '/current/lathe-launcher.sh'
  if vim.fn.executable(launcher) ~= 1 then
    vim.notify(
      'Lathe: launcher not found at ' .. launcher .. '; run mvn process-test-classes.',
      vim.log.levels.ERROR,
      { title = 'Lathe' }
    )
    return
  end

  local root = M.get_root(bufnr) or vim.fs.root(vim.fn.getcwd(), M.ROOT_MARKER)
  if not root then
    vim.notify(
      'Lathe: no ' .. M.ROOT_MARKER .. ' workspace found from the current directory.',
      vim.log.levels.WARN,
      { title = 'Lathe' }
    )
    return
  end

  local config = vim.tbl_extend('force', vim.lsp.config['lathe'] or {}, { root_dir = root })
  vim.lsp.start(config, { bufnr = bufnr })
end

function M.setup(opts)
  opts = opts or {}
  local root = cache_root()
  local launcher = root .. '/current/lathe-launcher.sh'

  require('lathe.indent').setup({
    indent_style = opts.indent_style,
    continuation_indent = opts.continuation_indent,
  })

  local augroup = vim.api.nvim_create_augroup('LathePlugin', { clear = true })

  vim.lsp.config('lathe', {
    cmd = { launcher },
    filetypes = { 'java' },
    single_file_support = false,
    on_exit = function(code)
      M.on_server_exit(code)
    end,
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
    init_options = { lathe = { formatter = opts.formatter } },
  })
  vim.lsp.enable('lathe')

  -- Start the server for the current directory without a Java buffer open, so
  -- workspace navigation works from any buffer (e.g. a dashboard). The filetype
  -- auto-start above still covers the normal case of opening a .java file.
  vim.api.nvim_create_user_command('LatheStart', function()
    M.start(vim.api.nvim_get_current_buf())
  end, { desc = 'Lathe: start the language server for the current directory' })

  -- Format-on-save is only meaningful with the Google formatter enabled; without it the server does
  -- not advertise formatting, so wiring the autocmd would be a no-op.
  local format_on_save = opts.formatter == 'google' and opts.format_on_save == true
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

  -- Run surface: gutter signs for `main` methods plus :LatheRun to replay the buffer's main
  -- class from .lathe/ bytecode. Tests keep going through the neotest adapter; this is the
  -- main-only path (RunnableKind.MAIN, which neotest excludes). Signs refresh when the server
  -- attaches and after each save, since an edit can add or remove a main.
  local run = require('lathe.run')
  vim.api.nvim_create_autocmd('LspAttach', {
    group = augroup,
    callback = function(args)
      local client = vim.lsp.get_client_by_id(args.data.client_id)
      if client and client.name == 'lathe' then
        run.refresh_signs(args.buf)
      end
    end,
  })
  vim.api.nvim_create_autocmd('BufWritePost', {
    group = augroup,
    pattern = '*.java',
    callback = function(ev)
      if #vim.lsp.get_clients({ name = 'lathe', bufnr = ev.buf }) > 0 then
        run.refresh_signs(ev.buf)
      end
    end,
  })
  vim.api.nvim_create_user_command('LatheRun', function()
    run.run(vim.api.nvim_get_current_buf())
  end, { desc = 'Lathe: run the main class in the current buffer' })
  vim.api.nvim_create_user_command('LatheRunStop', function()
    run.stop()
  end, { desc = 'Lathe: stop the active main run' })

  -- Debug surface: :LatheDebug attaches nvim-dap to the test or main class under the cursor,
  -- replayed under a suspended JDWP agent (server-side lathe.debug.test / lathe.debug.main).
  -- Optional -- the command is only wired when nvim-dap is present, so a runtime without it loads
  -- unaffected.
  if require('lathe.dap').setup() then
    vim.api.nvim_create_user_command('LatheDebug', function()
      require('lathe.dap').debug(vim.api.nvim_get_current_buf())
    end, { desc = 'Lathe: debug the test under the cursor' })
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

  -- Resource refresh: copy a saved resource into .lathe/ so the next test replay picks it up without
  -- a rebuild. The server maps the file against the real resource roots lathe:sync captured (a save
  -- that maps to no resource root is a no-op there), so forward any non-Java save inside a Lathe
  -- workspace. Skips .java (handled by the LSP) and cache files. The editor-agnostic path
  -- (workspace/didChangeWatchedFiles) can drive the same server command later; this autocmd is
  -- Neovim's uniform, dependency-free trigger.
  vim.api.nvim_create_autocmd('BufWritePost', {
    group = augroup,
    callback = function(ev)
      local name = vim.api.nvim_buf_get_name(ev.buf)
      if name == '' or name:match('%.java$') or vim.startswith(name, root) then
        return
      end
      for _, client in ipairs(vim.lsp.get_clients({ name = 'lathe' })) do
        if client.root_dir and vim.startswith(name, client.root_dir) then
          client:request('workspace/executeCommand', {
            command = 'lathe.resource.refresh',
            arguments = { { uri = vim.uri_from_fname(name) } },
          })
          return
        end
      end
    end,
  })
end

return M
