-- Lathe LSP plugin for Neovim (requires Neovim 0.12+).
--
-- Installation: copy this file (or symlink it) into your Neovim config and call:
--   require('lathe').setup()
--
-- The launcher is read from ~/.cache/lathe/current/lathe-launcher.sh, which is
-- written by `mvn process-test-classes` when the Lathe Maven plugin is present.
-- Override the cache location by setting LATHE_CACHE in your environment.
--
-- For local server development, set LATHE_SERVER_DIR to a built server version
-- directory (e.g. ~/.cache/lathe/servers/0.1.0-SNAPSHOT) to run that launcher
-- instead of the installed `current` server, without repointing the shared
-- `current` symlink. Mirrors LATHE_NVIM_DIR for the Lua client.
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

--- Absolute path to the server launcher script the client execs. Honors the
--- LATHE_SERVER_DIR dev override (a built server version directory, e.g. a
--- SNAPSHOT under the cache) so a working-tree server can be run without
--- repointing the shared `current` symlink; falls back to the installed
--- `current` server otherwise. Mirrors LATHE_NVIM_DIR for the Lua client.
local function launcher_path()
  local dir = vim.env.LATHE_SERVER_DIR or (cache_root() .. '/current')
  return vim.fs.normalize(dir) .. '/lathe-launcher.sh'
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
  local launcher = launcher_path()
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

-- On-demand formatting that keeps a closed imports fold from springing open (NV-3). Map a format
-- key to this instead of raw vim.lsp.buf.format, which reopens the fold on the buffer rewrite.
function M.format(bufnr, opts)
  require('lathe.fold').format(bufnr or vim.api.nvim_get_current_buf(), opts)
end

-- Nudge for the standalone install's silent failure modes, which the bundled cache
-- path cannot hit: a Java buffer is open but Lathe can't serve it because the client
-- was never configured, or no `.lathe/` workspace exists (the Lathe Maven build isn't
-- configured, or the project hasn't been synced). Both otherwise fail silently.
--
-- Called from ftplugin/java.lua, the one place that runs even when `setup()` was
-- never called. Fires at most once per session (re-armed by setup()).
local not_ready_notified = false
function M.warn_if_not_ready(bufnr)
  if not_ready_notified then
    return
  end
  if M._configured and M.get_root(bufnr) ~= nil then
    return
  end

  not_ready_notified = true
  local msg
  if not M._configured then
    msg = 'Lathe: plugin installed but not configured -- call `require("lathe").setup()`.'
  else
    msg = 'Lathe: no `.lathe/` workspace -- the Lathe Maven plugin is not configured, or the project '
      .. 'is not synced. Run `mvn process-test-classes` (then `:LatheSync` refreshes it).'
  end

  vim.notify(msg, vim.log.levels.WARN, { title = 'Lathe' })
end

function M.setup(opts)
  opts = opts or {}
  -- Read by warn_if_not_ready (via ftplugin) to tell "installed but not configured"
  -- apart from "no .lathe workspace"; re-arm the one-shot nudge for this fresh config.
  M._configured = true
  not_ready_notified = false
  local root = cache_root()
  local launcher = launcher_path()

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
      local r = M.get_root(bufnr)
      if r and vim.fn.executable(launcher) == 1 then
        on_dir(r)
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
    local fold = require('lathe.fold')
    vim.api.nvim_create_autocmd('LspAttach', {
      group = augroup,
      callback = function(args)
        local client = vim.lsp.get_client_by_id(args.data.client_id)
        if not (client and client.name == 'lathe') then
          return
        end

        -- Format-on-save rewrites the buffer, which reopens ufo's imports fold on every save
        -- (NV-3). Snapshot the fold's state before the format, then restore it after ufo settles.
        local imports_were_closed = false
        vim.api.nvim_create_autocmd('BufWritePre', {
          group = augroup,
          buffer = args.buf,
          callback = function()
            imports_were_closed = fold.imports_closed(args.buf)
            vim.lsp.buf.format({ bufnr = args.buf, id = args.data.client_id, async = false })
          end,
        })
        vim.api.nvim_create_autocmd('BufWritePost', {
          group = augroup,
          buffer = args.buf,
          callback = function()
            fold.reclose_imports(args.buf, imports_were_closed)
          end,
        })
      end,
    })
  end

  -- Manual formatting is available whenever the server advertises it (formatter enabled), whether or
  -- not format-on-save is wired. :LatheFormat routes through M.format so the imports fold survives.
  if opts.formatter == 'google' then
    vim.api.nvim_create_user_command('LatheFormat', function()
      M.format(vim.api.nvim_get_current_buf())
    end, { desc = 'Lathe: format the current buffer (preserving the imports fold)' })
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

  -- New-type surface: :LatheNew scaffolds a class/interface/record/enum through the server (which
  -- owns placement, skeleton, and caret) and opens the returned file.
  require('lathe.new').setup()

  -- Instantiations surface: :LatheInstances lists where the type under the cursor is `new`-ed up
  -- (via the server's lathe.instantiations command) in the quickfix. Suggested mapping: grN.
  require('lathe.instances').setup()

  -- Type-hierarchy surface: :LatheTypeHierarchy shows the full both-directions transitive hierarchy
  -- of the type under the cursor (via the server's lathe.typeHierarchy command) in Telescope, or the
  -- built-in fuzzy picker when Telescope is absent. Suggested mapping: grh.
  require('lathe.typehierarchy').setup()

  -- Missing-imports surface: :LatheMissingImports adds an import for every unresolved type in the
  -- buffer (server's lathe.missingImports command). Unambiguous names are added automatically; a name
  -- with several candidates is offered one at a time. Also surfaced as the "Add missing imports…"
  -- code action. Suggested mapping: <leader>li.
  require('lathe.imports').setup()

  -- Sync surface: the server's lathe/sync notification and :LatheSync run Maven
  -- (process-test-classes, or `mvn test` with !) to refresh the .lathe/ mirror after POM/structural
  -- changes. The server never runs Maven itself.
  require('lathe.sync').setup()

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
