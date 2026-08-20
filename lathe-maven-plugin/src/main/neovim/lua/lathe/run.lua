-- Run the current buffer's `main` class from Lathe's captured `.lathe/` bytecode, and mark
-- runnable mains in the sign column. Tests keep going through lathe.neotest; this is the
-- main-only surface -- RunnableKind.MAIN, which the neotest adapter deliberately excludes
-- (a `main` is not a test). Discovery reuses `lathe.runnables.list` and execution
-- `lathe.run.main`; console output reuses lathe.output, so there is no output code here.
--
-- Uses core vim.lsp directly (no neotest/nio), so it loads headlessly and independently of
-- whether the neotest adapter is configured.

local output = require("lathe.output")

local M = {}

-- RunnableKind ordinals (lsp4j serializes the enum by ordinal): MAIN(0) is the main method,
-- MAIN_CLASS(4) is its enclosing class declaration. Both are gutter-signed and launch the same
-- class; TEST_* (1-3) are neotest's. See lathe.neotest's POSITION_TYPE for the test mapping.
local RUN_KIND_MAIN = 0
local RUN_KIND_MAIN_CLASS = 4

local SIGN_NS = vim.api.nvim_create_namespace("lathe_run_signs")
local SIGN_HL = "LatheRunnable"
vim.api.nvim_set_hl(0, SIGN_HL, { link = "DiagnosticInfo", default = true })
local SIGN_TEXT = "▶"

-- The single in-flight main run's token, so :LatheRunStop can cancel it. A main run is a
-- foreground user action (one buffer, one class), so tracking one token is sufficient; a new
-- run overwrites it, matching the shared single-buffer output surface.
local active_token

local function lathe_client()
  return vim.lsp.get_clients({ name = "lathe" })[1]
end

local function in_range(range, line)
  return range ~= nil and line >= range.start.line and line <= range["end"].line
end

--- The MAIN (method) runnable to launch for a cursor position: the main whose method range
--- contains `cursor_line` (0-based); else the main whose class range contains it (running from
--- the class gutter); else the file's only main if there is exactly one; else nil (no main, or
--- several with the cursor on none). Always returns a MAIN target -- a MAIN_CLASS hit resolves to
--- its class's method (id == the method's parentId) so the launch path is unchanged. Pure over the
--- raw lathe.runnables.list targets, so it is unit-testable without a live client.
function M._main_target_for(targets, cursor_line)
  local mains = {}
  local main_by_class = {}
  for _, t in ipairs(targets) do
    if t.kind == RUN_KIND_MAIN then
      mains[#mains + 1] = t
      main_by_class[t.parentId] = t
    end
  end

  -- Method range wins when the cursor is on the method line (the class range encloses it).
  for _, t in ipairs(mains) do
    if in_range(t.range, cursor_line) then
      return t
    end
  end

  for _, t in ipairs(targets) do
    if t.kind == RUN_KIND_MAIN_CLASS and in_range(t.range, cursor_line) then
      local method = main_by_class[t.id]
      if method then
        return method
      end
    end
  end

  if #mains == 1 then
    return mains[1]
  end

  return nil
end

local function place_signs(bufnr, targets)
  if not vim.api.nvim_buf_is_valid(bufnr) then
    return
  end

  vim.api.nvim_buf_clear_namespace(bufnr, SIGN_NS, 0, -1)
  for _, t in ipairs(targets) do
    if (t.kind == RUN_KIND_MAIN or t.kind == RUN_KIND_MAIN_CLASS) and t.range then
      pcall(vim.api.nvim_buf_set_extmark, bufnr, SIGN_NS, t.range.start.line, 0, {
        sign_text = SIGN_TEXT,
        sign_hl_group = SIGN_HL,
      })
    end
  end
end

--- Refreshes the gutter signs for a buffer from the server's runnable discovery. Best-effort:
--- a missing client or an empty/failed discovery leaves the buffer without run signs rather
--- than erroring.
function M.refresh_signs(bufnr)
  local client = lathe_client()
  if not client then
    return
  end

  client:request("workspace/executeCommand", {
    command = "lathe.runnables.list",
    arguments = { { uri = vim.uri_from_bufnr(bufnr) } },
  }, function(err, targets)
    if err or not targets then
      return
    end

    vim.schedule(function()
      place_signs(bufnr, targets)
    end)
  end, bufnr)
end

local function notify(msg, level)
  vim.notify(msg, level, { title = "Lathe" })
end

local function on_finished(target, err, outcome)
  active_token = nil
  if err then
    notify("run.main error: " .. vim.inspect(err), vim.log.levels.ERROR)
    return
  end

  if outcome and not outcome.launched then
    notify("run blocked -- " .. table.concat(outcome.blockedReasons or {}, "; "), vim.log.levels.WARN)
    return
  end

  local code = (outcome and outcome.exitCode) or -1
  notify(
    ("%s exited %d"):format(target.parentId, code),
    code == 0 and vim.log.levels.INFO or vim.log.levels.WARN
  )
end

local function launch_main(client, target)
  local token = output.next_token()
  active_token = token
  output.reset()
  output.ensure_open()
  notify("Running " .. target.parentId, vim.log.levels.INFO)
  client:request("workspace/executeCommand", {
    command = "lathe.run.main",
    arguments = { {
      moduleRel = target.moduleRel,
      mainClass = target.parentId,
      token = token,
    } },
  }, function(err, outcome)
    vim.schedule(function()
      on_finished(target, err, outcome)
    end)
  end)
end

--- Runs the `main` class in the current buffer (the one under the cursor, or the file's only
--- main). Files with no main defer to neotest for tests -- this surface is main-only.
function M.run(bufnr)
  bufnr = bufnr or vim.api.nvim_get_current_buf()
  local client = lathe_client()
  if not client then
    notify("server not attached to this buffer", vim.log.levels.WARN)
    return
  end

  local cursor_line = vim.api.nvim_win_get_cursor(0)[1] - 1
  client:request("workspace/executeCommand", {
    command = "lathe.runnables.list",
    arguments = { { uri = vim.uri_from_bufnr(bufnr) } },
  }, function(err, targets)
    local target = not err and targets and M._main_target_for(targets, cursor_line) or nil
    vim.schedule(function()
      if not target then
        -- Main-only surface: no main means the user wants tests, which stay with neotest. Delegate
        -- to it when installed (pcall -- neotest.run is nil until require('neotest').setup() ran).
        local ok = pcall(function()
          require("neotest").run.run()
        end)
        if not ok then
          notify("no main here; configure neotest to run tests", vim.log.levels.WARN)
        end

        return
      end

      launch_main(client, target)
    end)
  end, bufnr)
end

--- Cancels the in-flight main run, if any. The replay JVM is server-side, so the stop is the
--- lathe.run.cancel command keyed by the run token, not a client-side process kill.
function M.stop()
  if not active_token then
    notify("no main run to stop", vim.log.levels.WARN)
    return
  end

  local client = lathe_client()
  if not client then
    return
  end

  client:request("workspace/executeCommand", {
    command = "lathe.run.cancel",
    arguments = { { token = active_token } },
  })
end

return M
