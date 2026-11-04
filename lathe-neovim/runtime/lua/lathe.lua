-- Lathe LSP plugin for Neovim (requires Neovim 0.12+).
--
-- Installation: copy this file (or symlink it) into your Neovim config and call:
--   require('lathe').setup()
--
-- The launcher is read from <root>/.lathe/lathe-launcher.sh, which the build's Lathe sync writes
-- for the server version this workspace pins.
--
-- For local server development, set LATHE_SERVER_DIR to a built server version directory (e.g.
-- ~/.cache/lathe/servers/0.1.0-SNAPSHOT) to run that launcher instead of the workspace-pinned one,
-- without rebuilding. Mirrors LATHE_NVIM_DIR for the Lua client.
--
-- Options (all optional):
--   capabilities        LSP capabilities table; defaults to vim.lsp.protocol.make_client_capabilities()
--   style               table; the global-default style, overridden per project by a workspace
--                       lathe-style.json / .lathe/style.json. Same shape as that file:
--                         style.formatter { engine = "google"|"aosp"|"none"|"command", command = {..} }
--                                         server-side save-formatter (default: none).
--                         style.indent    { profile = "google"|"editorconfig", block, continuation }
--                                         editorconfig follows Neovim's built-in EditorConfig
--                                         (4-space fallback); google uses 2/4-space widths. block and
--                                         continuation (when > 0) override the profile widths.
--   format_on_save      boolean; format on write when the server advertises formatting (default: false).
--   pom                 table; client-side pom.xml support via `xmllint` (no server involvement):
--                       { validate = true, format = false }. validate publishes XSD diagnostics on
--                       open and live (debounced) as you type (default on); format points `formatprg`
--                       at `xmllint --format` for `gq` (default off). Pass { validate = false } to
--                       disable. Needs `xmllint` (libxml2) on PATH; degrades to a one-time notice.
--
-- Set LATHE_DEBUG=1 in the environment to enable debug logging in the server process.
-- Requires the Java Treesitter parser for indentation (:TSInstall java).

local M = {}

local workspace = require("lathe.workspace")

-- The workspace API other Neovim config code can use (a project picker, a keymap scoping a
-- picker to the project): the root marker, a buffer's workspace root, and the source cache;
-- see lathe.workspace.
M.ROOT_MARKER = workspace.ROOT_MARKER
M.get_root = workspace.get_root
M.cache_root = workspace.cache_root

-- On-demand formatting that keeps a closed imports fold shut; see lathe.format.
M.format = require("lathe.format").format

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
    ("Lathe: language server exited unexpectedly (code %d); see :LspLog."):format(code),
    vim.log.levels.ERROR,
    { title = "Lathe" }
  )
end

--- Start (or reuse) the Lathe client for the current directory and attach it to
--- `bufnr`, so workspace navigation (e.g. `workspace/symbol`) works without a
--- Java file open. Resolves the root from the buffer first, then the working
--- directory. Backs the `:LatheStart` command.
---@param bufnr integer? defaults to the current buffer
function M.start(bufnr)
  bufnr = bufnr or vim.api.nvim_get_current_buf()
  local root = M.get_root(bufnr) or vim.fs.root(vim.fn.getcwd(), M.ROOT_MARKER)
  if not root then
    vim.notify(
      "Lathe: no " .. M.ROOT_MARKER .. " workspace found from the current directory.",
      vim.log.levels.WARN,
      { title = "Lathe" }
    )
    return
  end

  local launcher = workspace.launcher_path(root)
  if vim.fn.executable(launcher) ~= 1 then
    vim.notify(
      "Lathe: launcher not found at "
        .. launcher
        .. "; sync the workspace: "
        .. require("lathe.sync").SETUP_HINT,
      vim.log.levels.ERROR,
      { title = "Lathe" }
    )
    return
  end

  local config = vim.tbl_extend("force", vim.lsp.config["lathe"] or {}, { root_dir = root })
  vim.lsp.start(config, { bufnr = bufnr })
end

-- Nudge for the silent failure modes: a Java buffer is open but Lathe can't serve it because the
-- client was never configured, no `.lathe/` workspace exists (the Lathe Maven build isn't
-- configured, or the project hasn't been synced), or the workspace was synced by a Lathe older than
-- the per-workspace launcher link (so the server never starts). All otherwise fail silently.
--
-- Called from ftplugin/java.lua, the one place that runs even when `setup()` was
-- never called. Fires at most once per session (re-armed by setup()).
local not_ready_notified = false
function M.warn_if_not_ready(bufnr)
  if not_ready_notified then
    return
  end
  local root = M._configured and M.get_root(bufnr) or nil
  if root ~= nil and vim.fn.executable(workspace.launcher_path(root)) == 1 then
    return
  end

  not_ready_notified = true
  local msg
  if not M._configured then
    msg = 'Lathe: plugin installed but not configured -- call `require("lathe").setup()`.'
  elseif root == nil then
    msg = "Lathe: no `.lathe/` workspace -- Lathe is not set up for this project, or it is not "
      .. "synced yet: "
      .. require("lathe.sync").SETUP_HINT
      .. " Then `:LatheSync` refreshes it."
      .. " If a build already ran, its log says why: Lathe needs Maven 3.6.3+."
  else
    msg = "Lathe: `.lathe/` has no server launcher -- the workspace was synced by an older Lathe. "
      .. "Update the Lathe build plugin (Maven: lathe-maven-extension 0.1.16 or later) and sync again: "
      .. require("lathe.sync").SETUP_HINT
  end

  vim.notify(msg, vim.log.levels.WARN, { title = "Lathe" })
end

-- Warn when the client is loaded from more than one location -- e.g. two plugin-manager installs, or
-- a current one alongside a stale copy left in the cache by an older Lathe. Both ship the same
-- modules, so require('lathe') silently binds to whichever is first on runtimepath and shadows the
-- rest; an update to one copy is then invisibly overridden by the other. Keyed on the version module
-- (unique to the client). Fires at most once.
local double_load_notified = false
function M.warn_if_double_loaded()
  if double_load_notified then
    return
  end

  local found = vim.api.nvim_get_runtime_file("lua/lathe/version.lua", true)
  if #found < 2 then
    return
  end

  double_load_notified = true
  vim.notify(
    "Lathe: loaded from multiple locations; keep only one (standalone plugin OR the cache dir):\n"
      .. table.concat(found, "\n"),
    vim.log.levels.WARN,
    { title = "Lathe" }
  )
end

-- Compare the server's advertised protocol against this client's and warn on a mismatch. The
-- git-installed standalone client and the Maven-pinned server version independently, so a coarse
-- integer (server: capabilities.experimental.latheProtocol; client: lua/lathe/version.lua) flags
-- "these two can't talk" without nagging on every benign version difference. The server version is
-- pinned by the `lathe-maven-extension` in the build, so an older/absent server means that pin must
-- be bumped (re-running the sync alone reinstalls the same version); a newer server means the client
-- must be updated. Called from on_init, so it runs once per server (re)start.
function M.check_protocol(server_protocol)
  local client_protocol = require("lathe.version").PROTOCOL
  if server_protocol == client_protocol then
    return
  end

  local msg
  if type(server_protocol) ~= "number" or server_protocol < client_protocol then
    msg = "Lathe: server is older than this client -- update the Lathe build plugin in your build "
      .. "(Maven: the `lathe-maven-extension` version) and sync again."
  else
    msg =
      "Lathe: client is older than this server -- update lathe.nvim (`:Lazy update` / `vim.pack.update` / git pull)."
  end

  vim.notify(msg, vim.log.levels.WARN, { title = "Lathe" })
end

function M.setup(opts)
  opts = opts or {}
  -- Read by warn_if_not_ready (via ftplugin) to tell "installed but not configured"
  -- apart from "no .lathe workspace"; re-arm the one-shot nudge for this fresh config.
  M._configured = true
  not_ready_notified = false
  M.warn_if_double_loaded()

  require("lathe.indent").setup({ indent = opts.style and opts.style.indent })

  local augroup = vim.api.nvim_create_augroup("LathePlugin", { clear = true })

  vim.lsp.config("lathe", {
    -- A function cmd is the only hook that sees the per-buffer root_dir: resolve that workspace's
    -- launcher (<root>/.lathe/) and spawn with cwd = root so it reads `.lathe/java-home` relative to
    -- it. Covers both auto-start and M.start.
    cmd = function(dispatchers, config)
      return vim.lsp.rpc.start(
        { workspace.launcher_path(config.root_dir) },
        dispatchers,
        { cwd = config.root_dir }
      )
    end,
    filetypes = { "java" },
    single_file_support = false,
    on_exit = function(code)
      M.on_server_exit(code)
    end,
    on_init = function(client)
      M.check_protocol(vim.tbl_get(client, "server_capabilities", "experimental", "latheProtocol"))
    end,
    root_dir = function(bufnr, on_dir)
      local r = M.get_root(bufnr)
      if r and vim.fn.executable(workspace.launcher_path(r)) == 1 then
        on_dir(r)
      end
    end,
    capabilities = opts.capabilities or vim.lsp.protocol.make_client_capabilities(),
    -- The global-default style (same shape as a workspace style file); a project's file overrides it.
    -- The server reads style.formatter; indent is consumed client-side by lathe.indent.
    init_options = { lathe = { style = opts.style } },
  })
  vim.lsp.enable("lathe")

  -- Safety net for vim.lsp.enable's async root_dir gate. Native LSP attaches only when the
  -- root_dir resolver calls on_dir, and it never retries: if get_root returns nil at FileType
  -- time (a .lathe/cache buffer before last_root is seeded, or a split that opened a distinct
  -- buffer for an already-open file) the buffer stays unattached, silently. Re-check on BufEnter
  -- and attach once a root resolves. Deferred so native enable still owns the normal open; the
  -- get_clients short-circuit then skips the fs walk, and vim.lsp.start reuses the client already
  -- running for that root, so a redundant call is a no-op. buftype guard skips artificial/diff
  -- buffers, which native enable also refuses to attach.
  vim.api.nvim_create_autocmd("BufEnter", {
    group = augroup,
    pattern = "*.java",
    callback = function(ev)
      vim.schedule(function()
        if not vim.api.nvim_buf_is_valid(ev.buf) or vim.bo[ev.buf].buftype ~= "" then
          return
        end

        if #vim.lsp.get_clients({ name = "lathe", bufnr = ev.buf }) > 0 then
          return
        end

        local r = M.get_root(ev.buf)
        if not r or vim.fn.executable(workspace.launcher_path(r)) ~= 1 then
          return
        end

        M.start(ev.buf)
      end)
    end,
  })

  -- Start the server for the current directory without a Java buffer open, so
  -- workspace navigation works from any buffer (e.g. a dashboard). The filetype
  -- auto-start above still covers the normal case of opening a .java file.
  vim.api.nvim_create_user_command("LatheStart", function()
    M.start(vim.api.nvim_get_current_buf())
  end, { desc = "Lathe: start the language server for the current directory" })

  -- Formatting: :LatheFormat, and format-on-save when the server advertises formatting.
  require("lathe.format").setup(opts.format_on_save)

  -- Run surface: gutter signs for `main` methods plus :LatheRun to replay the buffer's main class
  -- from .lathe/ bytecode; tests go through the neotest adapter.
  require("lathe.run").setup()

  -- New-type surface: :LatheNew scaffolds a class/interface/record/enum through the server (which
  -- owns placement, skeleton, and caret) and opens the returned file.
  require("lathe.new").setup()

  -- Instantiations surface: :LatheInstances lists where the type under the cursor is `new`-ed up
  -- (via the server's lathe.instantiations command) in the quickfix. Suggested mapping: grN.
  require("lathe.instances").setup()

  -- Type-hierarchy surface: :LatheTypeHierarchy shows the full both-directions transitive hierarchy
  -- of the type under the cursor (via the server's lathe.typeHierarchy command) in Telescope, or the
  -- built-in fuzzy picker when Telescope is absent. Suggested mapping: grh.
  require("lathe.typehierarchy").setup()

  -- Resource finder: :LatheResourceFind [name] finds a resource by name across this workspace's
  -- reactor + dependency resources (lathe.resources), tagged by origin. A reactor hit opens its
  -- editable source; a dependency hit is extracted read-only on open. Telescope when installed,
  -- else the built-in fuzzy picker. Suggested mapping: <leader>lr.
  require("lathe.resources").setup()

  -- Missing-imports surface: :LatheMissingImports adds an import for every unresolved type in the
  -- buffer (server's lathe.missingImports command). Unambiguous names are added automatically; a name
  -- with several candidates is offered one at a time. Also surfaced as the "Add missing imports…"
  -- code action. Suggested mapping: <leader>li.
  require("lathe.imports").setup()

  -- Sync surface: the server's lathe/sync notification and :LatheSync run Maven
  -- (process-test-classes, or `mvn test` with !) to refresh the .lathe/ mirror after POM/structural
  -- changes. The server never runs Maven itself.
  require("lathe.sync").setup()

  -- pom.xml surface: client-side XSD validation (diagnostics on open/save) and optional formatting,
  -- both via `xmllint`. No language server involvement -- Lathe is never attached to pom.xml. On by
  -- default; pass `pom = { validate = false }` to disable, `pom = { format = true }` for `gq`.
  require("lathe.pom").setup(opts.pom)

  -- Debug surface: :LatheDebug through nvim-dap, wired only when nvim-dap is present.
  require("lathe.dap").setup()

  workspace.setup()
end

return M
