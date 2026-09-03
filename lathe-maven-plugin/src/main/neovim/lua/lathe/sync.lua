-- Handles the server's `lathe/sync` notification (and the `:LatheSync` command) by running Maven for
-- the workspace so the `.lathe/` mirror and captured launch templates refresh. The server never runs
-- Maven itself; it only asks the client to. `captureTests` selects `mvn test` (which re-captures
-- test-launch.json for neotest) over the lighter `mvn process-test-classes`.

local M = {}

-- Guard against overlapping syncs for the same root (a second prompt/command while one is running).
local running = {}

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
  running[root] = true
  vim.notify('Lathe: running mvn ' .. goal .. ' …', vim.log.levels.INFO)

  vim.system({ 'mvn', goal }, { cwd = root, text = true }, function(res)
    running[root] = nil
    vim.schedule(function()
      if res.code == 0 then
        vim.notify('Lathe: sync complete (mvn ' .. goal .. ')', vim.log.levels.INFO)
      else
        vim.notify(
          'Lathe: sync failed (mvn ' .. goal .. ', exit ' .. tostring(res.code) .. ')',
          vim.log.levels.ERROR
        )
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
