-- Handles the server's `lathe/sync` notification (and the `:LatheSync` command) by running Maven for
-- the workspace so the `.lathe/` mirror and captured launch templates refresh. The server never runs
-- Maven itself; it only asks the client to. `captureTests` selects `mvn test` (which re-captures
-- test-launch.json for neotest) over the lighter `mvn process-test-classes`.
--
-- Presentation: a healthy sync stays quiet — a "running" notification and a one-line "synced"
-- summary. Only a *failed* build surfaces its full Maven output, in a throwaway split, so the user
-- can see why without the successful-build log ever cluttering the session.

local M = {}

-- Guard against overlapping syncs for the same root (a second prompt/command while one is running).
local running = {}

-- The most recent failure-output buffer, wiped before showing a new one so repeated failures do not
-- accumulate scratch buffers.
local output_buf

--- Opens the full captured Maven output in a throwaway bottom split. Called only on failure.
--- The header prints the exact command and working directory so the run is reproducible by hand.
local function show_failure(cmd_str, root, code, output)
  if output_buf and vim.api.nvim_buf_is_valid(output_buf) then
    vim.api.nvim_buf_delete(output_buf, { force = true })
  end

  local lines = {
    ('Lathe: sync failed (exit %d)'):format(code),
    ('$ cd %s && %s'):format(root, cmd_str),
    '',
  }
  vim.list_extend(lines, vim.split(output, '\n', { plain = true }))

  output_buf = vim.api.nvim_create_buf(false, true)
  vim.api.nvim_buf_set_lines(output_buf, 0, -1, false, lines)
  vim.bo[output_buf].buftype = 'nofile'
  vim.bo[output_buf].bufhidden = 'wipe'
  vim.bo[output_buf].modifiable = false
  pcall(vim.api.nvim_buf_set_name, output_buf, 'Lathe Sync Output')
  vim.cmd('botright split')
  vim.api.nvim_win_set_buf(0, output_buf)
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
  -- --no-transfer-progress drops the "Downloading/Downloaded" chatter so the failure output (the only
  -- output ever shown) stays readable; nothing is parsed.
  local cmd = { 'mvn', '--no-transfer-progress', goal }
  local cmd_str = table.concat(cmd, ' ')
  running[root] = true
  local started = vim.loop.hrtime()
  vim.notify('Lathe: running `' .. cmd_str .. '` …', vim.log.levels.INFO)

  vim.system(cmd, { cwd = root, text = true }, function(res)
    running[root] = nil
    local secs = (vim.loop.hrtime() - started) / 1e9
    vim.schedule(function()
      if res.code == 0 then
        vim.notify(('Lathe: synced (`%s`, %.1fs)'):format(cmd_str, secs), vim.log.levels.INFO)
      else
        vim.notify(
          ('Lathe: sync failed (exit %d) — see output'):format(res.code),
          vim.log.levels.ERROR
        )
        show_failure(cmd_str, root, res.code, (res.stdout or '') .. (res.stderr or ''))
      end
    end)
  end)
end

--- Registers the `lathe/sync` handler and the `:LatheSync` command (`:LatheSync!` also captures tests).
function M.setup()
  vim.lsp.handlers['lathe/sync'] = function(_err, result)
    if result then
      M.run_maven(result.workspaceRoot, result.captureTests)
    end
  end

  vim.api.nvim_create_user_command('LatheSync', function(cmd)
    local root = require('lathe').get_root(vim.api.nvim_get_current_buf())
    M.run_maven(root, cmd.bang)
  end, { bang = true, desc = 'Lathe: run Maven to refresh the workspace (! also captures tests)' })
end

return M
