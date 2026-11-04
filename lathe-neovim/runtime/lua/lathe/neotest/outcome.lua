-- One test result from the server, as neotest shows it: its status, its failure message with a diff
-- of the expected and actual values, and the diagnostic on its failing line. Pure.
local M = {}

local TEST_STATUS = { passed = "passed", failed = "failed", skipped = "skipped" }

-- A huge value (a whole JSON document) keeps the failure text readable; open_diff shows all of it.
local DIFF_MAX_LINES = 40

--- A unified diff of a failure's expected and actual values, line by line, without the hunk headers
--- (a "…" marks a gap between hunks) and capped at DIFF_MAX_LINES; nil when the two read the same.
function M._value_diff(expected, actual)
  local unified = vim.text.diff(expected .. "\n", actual .. "\n", { ctxlen = 3 })
  if unified == "" then
    return nil
  end

  local lines = {}
  for _, line in ipairs(vim.split(unified, "\n", { trimempty = true })) do
    if not line:match("^@@") then
      lines[#lines + 1] = line
    elseif #lines > 0 then
      lines[#lines + 1] = "…"
    end
  end

  if #lines > DIFF_MAX_LINES then
    local more = #lines - DIFF_MAX_LINES
    lines = vim.list_slice(lines, 1, DIFF_MAX_LINES)
    lines[#lines + 1] = ("… %d more lines: require('lathe.neotest').open_diff() shows both values"):format(
      more
    )
  end

  return table.concat(lines, "\n")
end

--- One structured per-test result mapped to a neotest.Result. `output` is the run's shared
--- transcript path, or nil while streaming live (the transcript file is written only once the
--- run finishes, in results()).
function M.test_result(tr, output)
  local res = { status = TEST_STATUS[tr.status] or "failed", output = output }
  if tr.failureMessage and tr.failureMessage ~= "" then
    local diff = tr.expected and tr.actual and M._value_diff(tr.expected, tr.actual)
    res.short = diff and (tr.failureMessage .. "\n" .. diff) or tr.failureMessage
    -- Feed neotest's built-in diagnostic consumer (F1): a failed test becomes a vim.diagnostic on
    -- its failing line. failureLine is a 1-based Java source line, -1 when unresolved; neotest's
    -- error.line is 0-based, and omitting it lets neotest fall back to the position's start line.
    local entry = { message = tr.failureMessage }
    if tr.failureLine and tr.failureLine > 0 then
      entry.line = tr.failureLine - 1
    end
    res.errors = { entry }
  end
  return res
end

return M
