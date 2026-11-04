-- What the user does after a run: re-run the first failing test, compare a failure's expected and
-- actual values, and list the last run's tests in the quickfix.
local common = require("lathe.neotest.common")
local util = require("lathe.util")
local discovery = require("lathe.neotest.discovery")
local run_results = require("lathe.neotest.results")

local M = {}

--- Indirection over neotest's runner so run_first_failed's selection logic is unit-testable without
--- neotest installed (the spec env has neither neotest nor nio).
function M._run_position(id)
  require("neotest").run.run(id)
end

--- Re-runs the first individually-runnable failing test from recent runs (NV-5). The failing list is
--- self-shrinking -- a re-run that passes drops its test -- so invoking this repeatedly walks the
--- failures one at a time (the red-green loop). Only tests discovered as neotest positions can be
--- targeted individually; failures in never-opened files (e.g. a package run) are not listed and are
--- covered by re-running that file. Bind e.g. <leader>tF.
function M.run_first_failed()
  if #run_results.failing == 0 then
    util.notify("No failing tests to re-run", vim.log.levels.WARN)
    return
  end

  M._run_position(run_results.failing[1])
end

-- Quickfix `type` per status: a failure is an error row, a skip a warning, a pass unadorned.
local QF_TYPE = { failed = "E", skipped = "W", passed = "" }

--- Quickfix items for a run's executed tests: failures first (each pointing at its failure line),
--- then the rest in run order. `paths` maps className -> source file path; an unresolved class
--- (empty path) becomes a text-only row (still listed, just not jumpable). No LSP round-trip, so it
--- is unit-testable.
function M._results_qf_items(results, paths)
  local failures, others = {}, {}
  for _, tr in ipairs(results or {}) do
    -- A class's own failure (its @BeforeAll broke) has no method: named by the class alone.
    local id = tr.methodName == "" and tr.className or (tr.className .. "#" .. tr.methodName)
    local failed = tr.status == "failed"
    local message = failed and tr.failureMessage and tr.failureMessage ~= "" and tr.failureMessage
      or nil
    local text = message and (id .. " — " .. message:gsub("%s+", " "))
      or (id .. " — " .. tr.status)
    if tr.durationMs and tr.durationMs >= 0 then
      text = ("%s (%d ms)"):format(text, tr.durationMs)
    end
    local item = { text = text, type = QF_TYPE[tr.status] or "" }
    local path = paths[tr.className]
    if path and path ~= "" then
      item.filename = path
      item.lnum = (failed and tr.failureLine and tr.failureLine > 0) and tr.failureLine or 1
      item.col = 1
    end

    local bucket = failed and failures or others
    bucket[#bucket + 1] = item
  end

  return vim.list_extend(failures, others)
end

--- The failure open_diff compares, from a run's results: the failure with values whose id is
--- `cursor_id` (the test under the cursor), else the only failure with values. Returns nil and the
--- candidates when there are several (the caller asks), or none. Pure, so the specs reach it.
function M._diff_target(results, cursor_id)
  local candidates = {}
  for _, tr in ipairs(results or {}) do
    if tr.status == "failed" and tr.expected ~= nil and tr.actual ~= nil then
      if tr.positionId == cursor_id then
        return tr, { tr }
      end

      candidates[#candidates + 1] = tr
    end
  end

  if #candidates == 1 then
    return candidates[1], candidates
  end

  return nil, candidates
end

-- The test method under the cursor, from the buffer's last discovery; nil anywhere else.
local function cursor_test_id()
  local targets = discovery.cached_targets(vim.api.nvim_buf_get_name(0))
  local line = vim.api.nvim_win_get_cursor(0)[1] - 1
  for _, t in ipairs(targets or {}) do
    if
      t.kind == common.TEST_METHOD_KIND
      and t.range
      and t.range.start.line <= line
      and line <= t.range["end"].line
    then
      return t.id
    end
  end

  return nil
end

local function diff_buffer(name, text)
  local buf = vim.api.nvim_get_current_buf()
  vim.bo[buf].buftype = "nofile"
  vim.bo[buf].bufhidden = "wipe"
  vim.bo[buf].swapfile = false
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, vim.split(text, "\n"))
  vim.bo[buf].modifiable = false
  pcall(vim.api.nvim_buf_set_name, buf, name)
  vim.cmd.diffthis()
end

--- Opens a failure's expected and actual values side by side in a new tab, in diff mode, so Neovim
--- marks the changed lines and the changed text within them.
function M._show_diff(tr)
  vim.cmd.tabnew()
  diff_buffer(("expected: %s"):format(tr.positionId), tr.expected)
  vim.cmd("rightbelow vnew")
  diff_buffer(("actual: %s"):format(tr.positionId), tr.actual)
end

--- Compares a failure's expected and actual values from the last run: the test under the cursor,
--- else the only such failure, else the one picked from a list. Bind <leader>td.
function M.open_diff()
  local last_run = run_results.last_run()
  local target, candidates = M._diff_target(last_run and last_run.results, cursor_test_id())
  if target then
    M._show_diff(target)
    return
  end

  if #candidates == 0 then
    util.notify("No failure with expected and actual values in the last run", vim.log.levels.WARN)
    return
  end

  vim.ui.select(candidates, {
    prompt = "Compare",
    format_item = function(tr)
      return tr.displayName ~= "" and ("%s %s"):format(tr.positionId, tr.displayName)
        or tr.positionId
    end,
  }, function(choice)
    if choice then
      M._show_diff(choice)
    end
  end)
end

--- Lists the last run's executed tests in the quickfix window, failures first and jumpable, with each
--- test's time. className -> source path is resolved server-side (lathe.testSources,
--- pure path math, no attribution). Reflects the last run; the next run replaces it. Bind <leader>tq.
function M.open_results()
  local run = run_results.last_run()
  if not run or #run.results == 0 then
    util.notify("No test run to show yet", vim.log.levels.WARN)
    return
  end

  common.nio().run(function()
    local paths = {}
    local client = common.client()
    if client and run.module_rel then
      -- The server dedups classNames (its testSources returns one entry per class), so collect them
      -- as-is rather than deduping here too.
      local names = {}
      for _, tr in ipairs(run.results) do
        names[#names + 1] = tr.className
      end
      local err, sources = client.request.workspace_executeCommand({
        command = "lathe.testSources",
        arguments = { { moduleRel = run.module_rel, classNames = names } },
      })
      if not err and sources then
        for _, source in ipairs(sources) do
          paths[source.className] = source.path
        end
      end
    end

    local items = M._results_qf_items(run.results, paths)
    vim.schedule(function()
      vim.fn.setqflist({}, " ", { title = "Lathe tests: " .. (run.label or ""), items = items })
      vim.cmd("copen")
    end)
  end)
end

return M
