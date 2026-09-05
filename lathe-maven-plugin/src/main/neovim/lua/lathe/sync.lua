-- Handles the server's `lathe/sync` notification (and the `:LatheSync` command) by running Maven for
-- the workspace so the `.lathe/` mirror and captured launch templates refresh. The server never runs
-- Maven itself; it only asks the client to. `captureTests` selects `mvn test` (which re-captures
-- test-launch.json for neotest) over the lighter `mvn process-test-classes`.
--
-- Presentation: while the build runs, a transient "syncing… Ns" toast (carrying the command) is
-- refreshed every couple of seconds; on completion it becomes a one-line success summary. Both
-- auto-dismiss. Only a *failed* build surfaces its full output, in a persistent bottom split that
-- stays visible until the next sync, so the user can see what went wrong.

local M = {}

-- Guard against overlapping syncs for the same root (a second prompt/command while one is running).
local running = {}

-- The last sync's parameters, so the output buffer's `r` keymap can replay it after a failure.
local last_sync

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
    vim.notify(msg, level, { title = 'Lathe', replace = state.handle, timeout = timeout })
end

-- Refreshes the transient "still syncing" toast until the run completes.
local function notify_progress(state)
  if state.done then
    return
  end

  notify(
    state,
    ('Lathe: syncing… %.0fs — `%s`'):format(elapsed_s(state.started), state.cmd_str),
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
  vim.bo[out_buf].bufhidden = 'hide'
  pcall(vim.api.nvim_buf_set_name, out_buf, 'Lathe Sync Output')
  vim.keymap.set('n', 'r', function()
    if last_sync then
      M.run_maven(last_sync.root, last_sync.capture_tests)
    end
  end, { buffer = out_buf, desc = 'Lathe: re-run the last sync' })
  vim.keymap.set(
    'n',
    'q',
    '<cmd>close<cr>',
    { buffer = out_buf, desc = 'Lathe: close the sync output' }
  )
end

local function set_output(lines)
  ensure_output_buf()
  vim.bo[out_buf].modifiable = true
  vim.api.nvim_buf_set_lines(out_buf, 0, -1, false, lines)
  vim.bo[out_buf].modifiable = false
end

--- Opens (or focuses) the sync-console buffer in a bottom split and scrolls it to the end, so Maven's
--- [ERROR]/BUILD FAILURE summary is what fills the view. Returns false when no sync has run yet.
local function open_output_window()
  if not (out_buf and vim.api.nvim_buf_is_valid(out_buf)) then
    return false
  end

  if vim.fn.bufwinid(out_buf) == -1 then
    vim.cmd('botright 15split')
    vim.api.nvim_win_set_buf(0, out_buf)
  end

  local win = vim.fn.bufwinid(out_buf)
  if win ~= -1 then
    vim.api.nvim_win_set_cursor(win, { vim.api.nvim_buf_line_count(out_buf), 0 })
  end
  return true
end

-- The reproducible command line, so the console shows exactly how to rerun the sync by hand.
local function command_header(root, cmd_str)
  return ('$ cd %s && %s'):format(root, cmd_str)
end

--- Resets the console to a syncing… header, so any open split immediately drops the previous run's
--- output. Does not open a split — a healthy sync stays quiet.
local function show_running(cmd_str, root)
  set_output({ 'Lathe: syncing…', command_header(root, cmd_str) })
end

--- Replaces the console with a one-line success summary, updating an open split in place.
local function show_success(cmd_str, secs)
  set_output({ ('Lathe: sync succeeded (%s, %.1fs)'):format(cmd_str, secs) })
end

--- Fills the console with the full captured Maven output and opens the split. The header prints the
--- exact command and working directory so the run is reproducible by hand.
local function show_failure(cmd_str, root, code, output)
  local lines = {
    ('Lathe: sync FAILED (exit %d) — press r to retry, q to close'):format(code),
    command_header(root, cmd_str),
    '',
  }
  vim.list_extend(lines, vim.split(output, '\n', { plain = true }))
  set_output(lines)
  open_output_window()
end

--- Runs `mvn <goal>` at `root` as a background job, notifying on start and completion.
function M.run_maven(root, capture_tests)
  if not root or root == '' then
    return
  end
  if running[root] then
    vim.notify('Lathe: a sync is already running for ' .. root, vim.log.levels.INFO)
    return
  end

  local goal = capture_tests and 'test' or 'process-test-classes'
  -- --no-transfer-progress drops the download chatter; the build cache is disabled so the sync always
  -- reproduces the outputs Lathe mirrors from `.lathe/`.
  local cmd = { 'mvn', '--no-transfer-progress', '-Dmaven.build.cache.enabled=false', goal }
  local cmd_str = table.concat(cmd, ' ')
  running[root] = true
  last_sync = { root = root, capture_tests = capture_tests }

  show_running(cmd_str, root) -- clear the console so an open split never shows the previous run
  local state = { started = vim.loop.hrtime(), cmd_str = cmd_str }
  notify_progress(state) -- show the command immediately, then keep the toast alive every 3s
  local timer = vim.loop.new_timer()
  timer:start(PROGRESS_INTERVAL_MS, PROGRESS_INTERVAL_MS, function()
    vim.schedule(function()
      notify_progress(state)
    end)
  end)

  vim.system(cmd, { cwd = root, text = true }, function(res)
    running[root] = nil
    state.done = true
    timer:stop()
    timer:close()
    local secs = elapsed_s(state.started)
    vim.schedule(function()
      if res.code == 0 then
        show_success(cmd_str, secs)
        notify(
          state,
          ('Lathe: sync succeeded (`%s`, %.1fs)'):format(cmd_str, secs),
          vim.log.levels.INFO,
          SUCCESS_TIMEOUT_MS
        )
      else
        notify(
          state,
          ('Lathe: sync failed (`%s`, exit %d) — see the Lathe Sync Output split'):format(
            cmd_str,
            res.code
          ),
          vim.log.levels.ERROR
        )
        show_failure(cmd_str, root, res.code, (res.stdout or '') .. (res.stderr or ''))
      end
    end)
  end)
end

local function sync_current(capture_tests)
  local root = require('lathe').get_root(vim.api.nvim_get_current_buf())
  M.run_maven(root, capture_tests)
end

--- Registers the `lathe/sync` handler and the `:LatheSync` / `:LatheSyncCaptureTest` / retry commands.
function M.setup()
  vim.lsp.handlers['lathe/sync'] = function(_err, result)
    if result then
      M.run_maven(result.workspaceRoot, result.captureTests)
    end
  end

  vim.api.nvim_create_user_command('LatheSync', function()
    sync_current(false)
  end, { desc = 'Lathe: run mvn process-test-classes to refresh the workspace' })

  vim.api.nvim_create_user_command('LatheSyncCaptureTest', function()
    sync_current(true)
  end, { desc = 'Lathe: run mvn test to refresh the workspace and re-capture test launches' })

  vim.api.nvim_create_user_command('LatheSyncOutput', function()
    if not open_output_window() then
      vim.notify('Lathe: no sync output yet', vim.log.levels.INFO)
    end
  end, { desc = 'Lathe: reopen the last sync output' })
end

return M
