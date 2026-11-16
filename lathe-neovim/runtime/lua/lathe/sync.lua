-- Handles the server's `lathe/sync` notification (and the `:LatheSync` command) by running the
-- workspace's `.lathe/lathe-sync.sh`, which the build wrote, so the `.lathe/` mirror and captured
-- launch templates refresh. Neither the server nor this client knows the build tool: the script does.
-- `captureTests` passes `--tests` (re-capture test-launch.json for neotest).
--
-- Presentation: while the build runs, a transient "syncing… Ns" toast (carrying the command;
-- "downloading…" or "setting up…" for :LatheInit's runs) is
-- refreshed every couple of seconds; on completion it becomes a one-line success summary. Both
-- auto-dismiss. Only a *failed* build surfaces its full output, in a persistent bottom split that
-- stays visible until the next sync, so the user can see what went wrong.

local M = {}

-- Before the first sync there is no script to run, so a setup or recovery message names :LatheInit
-- (the server's SETUP_REMEDIATION, which other clients and MCP agents read, names each build's
-- own first command).
M.SETUP_HINT = "run `:LatheInit` (it doesn't change your project); "
  .. "for an OpenJDK checkout, run `mvn io.github.ag-libs:lathe-openjdk-maven-plugin:sync`."
-- A first build that stopped before its sync left `.lathe/` with no launcher and no script.
M.UNFINISHED_HINT = "the first Lathe build of this project stopped before it set Lathe up: "
  .. "fix what stopped it and run `:LatheInit` again."

-- Guard against overlapping syncs for the same root (a second prompt/command while one is running):
-- each running root with its run's action.
local running = {}

-- The last sync's parameters, so the output buffer's `r` keymap can replay it after a failure.
local last_sync

-- What a run is, for its toasts and console: a sync, or :LatheInit's download and first build.
local ACTIONS = {
  sync = { running = "syncing", noun = "sync" },
  download = { running = "downloading", noun = "download" },
  setup = { running = "setting up", noun = "setup" },
}

-- How often to refresh the "still syncing" toast, and how long each notification lingers.
local PROGRESS_INTERVAL_MS = 3000
local PROGRESS_TIMEOUT_MS = 4000
local SUCCESS_TIMEOUT_MS = 4000

local function elapsed_s(started)
  return (vim.loop.hrtime() - started) / 1e9
end

-- Emits a Lathe toast, reusing state.handle so a backend that honours it (nvim-notify, snacks,
-- fidget) updates one toast in place via `replace` and drops it after `timeout`; stock vim.notify
-- ignores the opts and just records each line in :messages.
local function notify(state, msg, level, timeout)
  state.handle =
    vim.notify(msg, level, { title = "Lathe", replace = state.handle, timeout = timeout })
end

-- Refreshes the transient "still syncing" toast until the run completes.
local function notify_progress(state)
  if state.done then
    return
  end

  notify(
    state,
    ("Lathe: %s… %.0fs — `%s`"):format(
      state.action.running,
      elapsed_s(state.started),
      state.cmd_str
    ),
    vim.log.levels.INFO,
    PROGRESS_TIMEOUT_MS
  )
end

-- A single reused "sync console" buffer that always reflects the latest run: cleared to a syncing…
-- header at start, then a one-line success summary or the full failure log at the end. Created on the
-- first sync and reused, so it never accumulates scratch buffers.
local out_buf

local function ensure_output_buf()
  if out_buf and vim.api.nvim_buf_is_valid(out_buf) then
    return
  end

  out_buf = vim.api.nvim_create_buf(false, true)
  vim.bo[out_buf].bufhidden = "hide"
  pcall(vim.api.nvim_buf_set_name, out_buf, "Lathe Sync Output")
  vim.keymap.set("n", "r", function()
    if last_sync then
      M.run(last_sync.root, last_sync.capture_tests, last_sync.modules, last_sync.opts)
    end
  end, { buffer = out_buf, desc = "Lathe: re-run the last sync" })
  vim.keymap.set(
    "n",
    "q",
    "<cmd>close<cr>",
    { buffer = out_buf, desc = "Lathe: close the sync output" }
  )
end

local function set_output(lines)
  ensure_output_buf()
  vim.bo[out_buf].modifiable = true
  vim.api.nvim_buf_set_lines(out_buf, 0, -1, false, lines)
  vim.bo[out_buf].modifiable = false
end

-- The build's verdict, else Lathe's own last word (a setup that installed no server says why in a
-- `[lathe]` line, above the build's success), else the last line: mvnd ends a failed build's output
-- with terminal warnings, below Maven's [ERROR] summary.
local function verdict_line()
  local lines = vim.api.nvim_buf_get_lines(out_buf, 0, -1, false)
  local lathe_line
  for i = #lines, 1, -1 do
    if lines[i]:find("^%[ERROR%]") or lines[i]:find("BUILD FAIL", 1, true) then
      return i
    end

    lathe_line = lathe_line or (lines[i]:find("[lathe]", 1, true) and i)
  end

  return lathe_line or #lines
end

--- Opens the sync-console buffer in a bottom split and scrolls it to the build's verdict, so Maven's
--- [ERROR] summary or Gradle's BUILD FAILED is what fills the view. With `focus` the cursor moves
--- into it; without, it stays in the window the user was in, as a failure opens it unasked. Returns
--- false when no sync has run yet.
local function open_output_window(focus)
  if not (out_buf and vim.api.nvim_buf_is_valid(out_buf)) then
    return false
  end

  local from = vim.api.nvim_get_current_win()
  if vim.fn.bufwinid(out_buf) == -1 then
    vim.cmd("botright 15split")
    vim.api.nvim_win_set_buf(0, out_buf)
  end

  local win = vim.fn.bufwinid(out_buf)
  if win ~= -1 then
    vim.api.nvim_win_set_cursor(win, { verdict_line(), 0 })
  end

  vim.api.nvim_set_current_win(focus and win ~= -1 and win or from)
  return true
end

local function shell_word(word)
  return word:find("[^%w%-_./:=@,+]") and vim.fn.shellescape(word) or word
end

-- The reproducible command line, so the console shows exactly how to rerun the build by hand: the
-- command itself, which a label shortens in the toasts.
local function command_header(root, cmd)
  return ("$ cd %s && %s"):format(shell_word(root), table.concat(vim.tbl_map(shell_word, cmd), " "))
end

--- Resets the console to a running header, so any open split immediately drops the previous run's
--- output. Does not open a split — a healthy sync stays quiet.
local function show_running(action, root, cmd)
  set_output({ ("Lathe: %s…"):format(action.running), command_header(root, cmd) })
end

--- Replaces the console with a one-line success summary, updating an open split in place.
local function show_success(action, cmd_str, secs)
  set_output({ ("Lathe: %s succeeded (%s, %.1fs)"):format(action.noun, cmd_str, secs) })
end

--- Fills the console with the full captured build output under `verdict` and opens the split. The
--- header prints the exact command and working directory so the run is reproducible by hand.
local function show_output(verdict, root, cmd, output)
  local lines = {
    ("Lathe: %s — :LatheSyncOutput jumps here; r retries, q closes"):format(verdict),
    command_header(root, cmd),
    "",
  }
  vim.list_extend(lines, vim.split(output, "\n", { plain = true }))
  set_output(lines)
  open_output_window()
end

local function lock_path(root)
  return vim.fs.joinpath(root, ".lathe", "lathe.lock")
end

-- Write the reactor build lock the instant a sync starts, before Maven has booted far enough to
-- take it itself, so the server suppresses its sync prompt with no startup-window race. During the
-- build the extension heartbeats the same lock. Best-effort: on a first-ever build `.lathe/` may
-- not exist yet, and there is nothing to protect then.
local function pretouch_lock(root)
  pcall(vim.fn.writefile, {}, lock_path(root))
end

-- Remove the pre-touched lock when the build ends: the client owns the lock it wrote, so it is never
-- left behind if the build's Maven extension is too old (or absent) to release it — the version skew
-- hit while dogfooding against a published extension. With a current extension `afterSessionEnd` has
-- already removed it, so this is a no-op. vim.loop (not vim.fn) because on_exit is a fast event
-- context.
local function release_lock(root)
  pcall(vim.loop.fs_unlink, lock_path(root))
end

-- The notification names what the client ran; the script prints the build command it resolves
-- (`lathe-sync: mvnd …`) as its first stderr line, so that shows in the output console.
local SCRIPT = require("lathe.workspace").SYNC_SCRIPT

--- Runs `.lathe/lathe-sync.sh [--tests] [module ...]` at `root` as a background job, notifying on
--- start and completion. `modules` (reactor-relative paths) scope it; empty/nil syncs everything.
--- `opts.cmd` runs that command instead (`:LatheInit`'s first build, before there is a script), shown
--- as `opts.label` and named by `opts.action` ("download" or "setup"; "sync" by default);
--- `opts.failure_toast(result)` may return a failed run's toast, shown as a warning instead of the
--- default error; `opts.success_warning(result)` may return a warning for a run that succeeded but
--- did not do what it ran for, shown with the build's output as a failure is;
--- `opts.on_done(result)` runs after the build ends, on the main loop.
function M.run(root, capture_tests, modules, opts)
  opts = opts or {}
  if not root or root == "" then
    return
  end
  if running[root] then
    vim.notify("Lathe: a sync is already running for " .. root, vim.log.levels.INFO)
    return
  end

  local cmd, cmd_str
  if opts.cmd then
    cmd, cmd_str = opts.cmd, opts.label or table.concat(opts.cmd, " ")
  else
    local script = vim.fs.joinpath(root, SCRIPT)
    if vim.fn.executable(script) ~= 1 then
      vim.notify(
        require("lathe.workspace").first_build_unfinished(root) and "Lathe: " .. M.UNFINISHED_HINT
          or "Lathe: `.lathe/` has no sync script -- the workspace was synced by an older Lathe. "
            .. "Update the Lathe build plugin and sync once: "
            .. M.SETUP_HINT,
        vim.log.levels.WARN
      )
      return
    end

    local args = vim.list_extend(capture_tests and { "--tests" } or {}, modules or {})
    cmd = vim.list_extend({ script }, args)
    cmd_str = table.concat(vim.list_extend({ SCRIPT }, args), " ")
  end

  running[root] = opts.action or "sync"
  last_sync = { root = root, capture_tests = capture_tests, modules = modules, opts = opts }

  local action = ACTIONS[opts.action or "sync"]
  show_running(action, root, cmd) -- clear the console so an open split never shows the previous run
  local state = { started = vim.loop.hrtime(), cmd_str = cmd_str, action = action }
  notify_progress(state) -- show the command immediately, then keep the toast alive every 3s
  local timer = vim.loop.new_timer()
  timer:start(PROGRESS_INTERVAL_MS, PROGRESS_INTERVAL_MS, function()
    vim.schedule(function()
      notify_progress(state)
    end)
  end)

  pretouch_lock(root)
  vim.system(cmd, { cwd = root, text = true }, function(res)
    release_lock(root)
    running[root] = nil
    state.done = true
    timer:stop()
    timer:close()
    local secs = elapsed_s(state.started)
    vim.schedule(function()
      local output = (res.stdout or "") .. (res.stderr or "")
      local warning = res.code == 0 and opts.success_warning and opts.success_warning(res)
      if warning then
        notify(state, warning, vim.log.levels.WARN)
        show_output(("%s succeeded, with a warning"):format(action.noun), root, cmd, output)
      elseif res.code == 0 then
        show_success(action, cmd_str, secs)
        notify(
          state,
          ("Lathe: %s succeeded (`%s`, %.1fs)"):format(action.noun, cmd_str, secs),
          vim.log.levels.INFO,
          SUCCESS_TIMEOUT_MS
        )
      else
        local custom = opts.failure_toast and opts.failure_toast(res)
        notify(
          state,
          custom
            or ("Lathe: %s failed (`%s`, exit %d) — see the Lathe Sync Output split"):format(
              action.noun,
              cmd_str,
              res.code
            ),
          custom and vim.log.levels.WARN or vim.log.levels.ERROR
        )
        show_output(("%s FAILED (exit %d)"):format(action.noun, res.code), root, cmd, output)
      end

      if opts.on_done then
        opts.on_done(res)
      end
    end)
  end)
end

-- The same path whatever symlinks lead to it (macOS's /var is /private/var), resolved through the
-- nearest folder that exists, as a new buffer's file does not yet.
local function real(path)
  local resolved = vim.uv.fs_realpath(path)
  if resolved then
    return resolved
  end

  local dir = vim.fs.dirname(path)
  return dir ~= path and vim.fs.joinpath(real(dir), vim.fs.basename(path)) or path
end

--- Whether :LatheInit is setting up the build that holds path (its download or first build): its
--- `.lathe/` is incomplete until the run ends and starts the server.
function M.setting_up(path)
  local wanted = real(path)
  for root, action in pairs(running) do
    local at = real(root)
    if action ~= "sync" and (wanted == at or vim.startswith(wanted, at .. "/")) then
      return true
    end
  end

  return false
end

--- Whether a run (a sync, or :LatheInit's download or first build) is going at root.
function M.busy(root)
  local wanted = real(root)
  for at in pairs(running) do
    if real(at) == wanted then
      return true
    end
  end

  return false
end

local function sync_current(capture_tests)
  local root = require("lathe.workspace").get_root(vim.api.nvim_get_current_buf())
  if not root then
    vim.notify(
      "Lathe: no .lathe/ at or above this file, so there is no workspace to sync",
      vim.log.levels.WARN
    )
    return
  end

  M.run(root, capture_tests)
end

--- Registers the `lathe/sync` handler and the `:LatheSync` / `:LatheSyncCaptureTest` / retry commands.
function M.setup()
  vim.lsp.handlers["lathe/sync"] = function(_err, result)
    if result then
      M.run(result.workspaceRoot, result.captureTests)
    end
  end

  vim.api.nvim_create_user_command("LatheSync", function()
    sync_current(false)
  end, { desc = "Lathe: run .lathe/lathe-sync.sh to refresh the workspace" })

  vim.api.nvim_create_user_command("LatheSyncCaptureTest", function()
    sync_current(true)
  end, { desc = "Lathe: run .lathe/lathe-sync.sh --tests to also re-capture test launches" })

  vim.api.nvim_create_user_command("LatheSyncOutput", function()
    if not open_output_window(true) then
      vim.notify("Lathe: no sync output yet", vim.log.levels.INFO)
    end
  end, { desc = "Lathe: reopen the last sync output" })
end

return M
