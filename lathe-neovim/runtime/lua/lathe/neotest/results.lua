-- What a finished run reports: each test's status reconciled over the whole position tree, the
-- output files, the completion summary, and the failing tests run_first_failed walks.
local common = require("lathe.neotest.common")
local util = require("lathe.util")
local outcome = require("lathe.neotest.outcome")
local runs = require("lathe.neotest.spec")

local M = {}

--- neotest.Result.output must be a path to a file containing the output, not raw text --
--- writes it to a fresh temp file every time, matching the convention other adapters use
--- (neotest is the reader; it owns no cleanup contract with adapters).
local function write_output_file(text)
  local path = vim.fn.tempname()
  local f = assert(io.open(path, "w"))
  f:write(text)
  f:close()
  return path
end

--- Flattens a replay transcript -- a list of {stream, text} tagged lines -- into
--- one string in arrival order. The stdout/stderr tag is preserved on the wire
--- for later per-stream coloring; this plain flatten is what backs the current
--- output file.
local function transcript_text(lines)
  local parts = {}
  for _, line in ipairs(lines or {}) do
    parts[#parts + 1] = line.text
  end
  return table.concat(parts, "\n")
end

--- An output file per test, holding the transcript lines the server tagged with that test's
--- positionId; a test with none of its own gets no entry (it shows the run's whole transcript).
local function per_test_output(lines)
  local by_id = {}
  for _, line in ipairs(lines or {}) do
    if line.positionId then
      by_id[line.positionId] = by_id[line.positionId] or {}
      table.insert(by_id[line.positionId], line)
    end
  end

  local files = {}
  for id, test_lines in pairs(by_id) do
    files[id] = write_output_file(transcript_text(test_lines))
  end
  return files
end

-- Ranks per-test statuses so a container takes the most significant one among the tests it encloses:
-- any failure dominates, then a pass (a class with passed+skipped tests is passed), then skipped.
local STATUS_RANK = { skipped = 1, passed = 2, failed = 3 }

--- True when `class` (a JUnit binary class name) belongs to `qualifier`: the same class or a nested class
--- of it when `qualifier` is an FQCN, or any class in the package (or a subpackage) when it is a package.
--- The trailing "." / "$" boundary stops `com.x.ser` from matching `com.x.service.Foo`.
local function class_encloses(qualifier, class, is_package)
  if is_package then
    return class:sub(1, #qualifier + 1) == qualifier .. "."
  end

  return class == qualifier or class:sub(1, #qualifier + 1) == qualifier .. "$"
end

--- A method's status when its class failed itself (its @BeforeAll broke, so the method never ran):
--- failed, naming the class's error. The class is the id's own or any class it is nested in. nil for
--- a non-method id, or when no enclosing class failed.
---
--- neotest places diagnostics only for test positions, never for a class, so the class's diagnostic
--- (on its broken setup line) rides on the first of its methods resolved here; `diagnosed` records
--- the classes already carried, so the line gets one diagnostic, not one per method.
local function class_failure(id, real, output, diagnosed)
  local class = common.class_of(id)
  while class do
    local failure = real[class]
    if failure and failure.status == "failed" then
      local res =
        { status = "failed", short = "class failed: " .. (failure.short or ""), output = output }
      if not diagnosed[class] then
        diagnosed[class] = true
        res.errors = failure.errors
      end

      return res
    end

    class = common.enclosing_class(class)
  end

  return nil
end

--- Status a container node inherits from the per-test results whose class it encloses, matched by NAME
--- rather than by discovered child nodes. neotest discovers positions only for open files, so a package
--- run's tree is childless for every file the user never opened -- yet the run still produced a real
--- result for each of those tests. Matching results to the file/package by name lets a passing but
--- never-opened file show passed instead of inheriting the run-wide aggregate failure. nil when nothing
--- matches (or the node isn't a container), so the caller falls back to the aggregate and never leaves
--- it stuck running.
local function container_status(node, real)
  local data = node:data()
  local qualifier, is_package
  if data.type == "namespace" then
    qualifier = data.id
  elseif data.type == "file" then
    qualifier = common.path_to_qualified(data.id)
  elseif data.type == "dir" then
    qualifier, is_package = common.path_to_qualified(data.id), true
  end

  if not qualifier then
    return nil
  end

  local best
  for position_id, res in pairs(real) do
    -- class name of the per-test id ("com.x.FooTest#bar(int)" -> "com.x.FooTest"); nil for a
    -- container id, which carries no '#'.
    local class = position_id:match("^(.-)#")
    if
      class
      and class_encloses(qualifier, class, is_package)
      and (not best or STATUS_RANK[res.status] > STATUS_RANK[best])
    then
      best = res.status
    end
  end
  return best
end

--- Counts distinct per-test outcomes by status. The `real` map is already method-level (parameterized
--- invocations rolled up worst-status-wins), so these counts match the summary panel rather than the
--- raw invocation count.
local function count_by_status(real)
  local passed, failed, skipped = 0, 0, 0
  for _, r in pairs(real) do
    if r.status == "failed" then
      failed = failed + 1
    elseif r.status == "skipped" then
      skipped = skipped + 1
    else
      passed = passed + 1
    end
  end
  return passed, failed, skipped
end

--- The one-line run-completion toast (NV-2): scope label, passed/failed/skipped counts, and elapsed
--- seconds, at WARN when anything failed/blocked/errored (so a failure is visible without reading the
--- text) or INFO when clean. Pure over the run context and the per-test `real` map -- results() passes
--- the elapsed ms (nil when untimed, e.g. a no-tests skip spec) -- so it is unit-testable and fires for
--- both run and debug (both flow through results()).
function M._run_summary(ctx, real, elapsed_ms)
  local label = ctx.label or "tests"
  local suffix = elapsed_ms and (" (%.1fs)"):format(elapsed_ms / 1000) or ""

  if ctx.err then
    return { warn = true, text = ("%s — errored%s"):format(label, suffix) }
  end

  if ctx.skip_reason then
    return { warn = true, text = ("%s — %s"):format(label, ctx.skip_reason) }
  end

  local outcome = ctx.outcome
  if outcome and not outcome.launched then
    return {
      warn = true,
      text = ("%s — blocked: %s"):format(label, table.concat(outcome.blockedReasons or {}, "; ")),
    }
  end

  local passed, failed, skipped = count_by_status(real)
  local warn = failed > 0 or (outcome ~= nil and outcome.exitCode ~= 0)
  return {
    warn = warn,
    text = ("%s — %d passed, %d failed, %d skipped%s"):format(
      label,
      passed,
      failed,
      skipped,
      suffix
    ),
  }
end

-- Ordered, self-shrinking set of individually-runnable failing tests (NV-5). Every run's reconciled
-- statuses update it: a test that failed AND is a discovered neotest position (so run.run can target
-- it) joins the list; one that now passes, is skipped, or isn't discovered leaves it. run_first_failed
-- re-runs the head, so calling it repeatedly walks the failures, each dropping out as it goes green.
local failing = {}
M._failing = failing
M.failing = failing

-- The most recent run's full executed-test set (set in results()): { label, module_rel, results },
-- where results is the raw testResults list -- every method that ran, including files never opened
-- into the neotest tree. open_results turns it into a quickfix. nil until the first run.
local last_run = nil

--- The last run's executed tests, or nil before the first run.
function M.last_run()
  return last_run
end

local function failing_index(id)
  for i, existing in ipairs(failing) do
    if existing == id then
      return i
    end
  end
  return nil
end

--- Updates `failing` from one run's reconciled results. Iterates testResults in execution order so a
--- newly-seen failure appends in a stable position, but reads the deduped `real` status (worst-wins) so
--- a parameterized test with any failing invocation counts as failed regardless of invocation order.
--- Only a failure that is a discovered position (tree:get_key finds it) is kept -- failures in
--- never-opened files can't be targeted individually and are covered by re-running their file.
local function update_failing(test_results, real, tree)
  local seen = {}
  for _, tr in ipairs(test_results or {}) do
    local id = tr.positionId
    if not seen[id] then
      seen[id] = true
      local status = real[id] and real[id].status
      local runnable = status == "failed" and tree ~= nil and tree:get_key(id) ~= nil
      local at = failing_index(id)
      if runnable and not at then
        failing[#failing + 1] = id
      elseif not runnable and at then
        table.remove(failing, at)
      end
    end
  end
end

--- neotest.Client:run_tree marks every id in the run's whole subtree as
--- "running" up front (client/init.lua's update_running, built from
--- tree:iter()) but only clears whichever ids results() returns -- a class
--- or package result naming just its own id leaves every descendant
--- method/class stuck showing "running" forever. Real per-test statuses from
--- outcome.testResults are mapped first, so exactly the methods that failed
--- are marked failed; every container node (file/class/dir) then takes the
--- status of the results whose class it encloses -- matched by name, since a
--- package run's tree is childless for files the user never opened -- so a
--- passing file shows passed even when a sibling in the same package fails.
--- A node that matches no result (an unmatched method, or an older outcome
--- with no testResults) falls back to the aggregate, so nothing is left stuck
--- running. Scoped to just the run position's own
--- subtree via tree:get_key(ctx.position_id), not the whole tree parameter --
--- build_spec's file-run fan-out (one spec per class) passes the same outer
--- file tree to every class's results() call, so resolving "everything in
--- tree" would incorrectly stamp sibling classes' methods with the wrong
--- class's result.
function M.results(spec, result, tree)
  local ctx = spec.context

  -- The per-test statuses were already applied live via spec.stream; this pass provides the
  -- authoritative reconciliation, the run-position aggregate, and the output file.
  runs.await_outcome(ctx, result)

  local result
  if ctx.skip_reason then
    result = { status = "skipped", short = ctx.skip_reason }
  elseif ctx.err then
    local text = "lathe.run.test error: " .. vim.inspect(ctx.err)
    result = { status = "failed", short = text, output = write_output_file(text) }
  else
    local outcome = ctx.outcome
    if not outcome.launched then
      local text = "BLOCKED: " .. table.concat(outcome.blockedReasons or {}, "; ")
      result = { status = "failed", short = text, output = write_output_file(text) }
    else
      result = {
        status = outcome.exitCode == 0 and "passed" or "failed",
        short = "exit=" .. tostring(outcome.exitCode),
        output = write_output_file(transcript_text(outcome.output)),
      }
    end
  end

  -- Real per-test statuses first, so they win over the aggregate on any node
  -- they cover (including the run's own position_id for a single-method run).
  local results = {}
  if ctx.outcome and ctx.outcome.testResults then
    local outputs = per_test_output(ctx.outcome.output)
    for _, tr in ipairs(ctx.outcome.testResults) do
      local id = tr.positionId
      -- A @ParameterizedTest/@RepeatedTest emits one record per invocation,
      -- all collapsing onto the method's single position id (Lathe discovers
      -- one position per method from compile-time analysis; it can't know the
      -- runtime invocation count). Roll them up worst-status-wins so a method
      -- with any failing invocation shows failed, not whichever invocation
      -- happened to be written last.
      local existing = results[id]
      if not existing or existing.status ~= "failed" then
        results[id] = outcome.test_result(tr, outputs[id] or result.output)
      end
    end
  end

  -- Snapshot the true per-test outcomes (keyed by positionId) before adding container/aggregate entries
  -- below, so name matching sees only real per-test results -- never a container entry we just set.
  local real = {}
  for id, r in pairs(results) do
    real[id] = r
  end

  local diagnosed = {}
  results[ctx.position_id] = results[ctx.position_id]
    or class_failure(ctx.position_id, real, result.output, diagnosed)
    or result

  -- run_tree marks the whole subtree "running" up front and clears only the ids we return, so every
  -- descendant needs an entry: a container takes the status of the results whose class it encloses (by
  -- name -- unopened files were never discovered into the tree), and anything unmatched falls back to
  -- the aggregate.
  local subtree = tree and tree:get_key(ctx.position_id)
  if subtree then
    for _, node in subtree:iter_nodes() do
      local id = node:data().id
      if not results[id] then
        local status = container_status(node, real)
        results[id] = class_failure(id, real, result.output, diagnosed)
          or (status and { status = status, output = result.output })
          or result
      end
    end
  end

  -- Track which tests are failing so run_first_failed can re-run them one at a time (NV-5); covers both
  -- run and debug (both reach results() with a reconciled outcome).
  if ctx.outcome and ctx.outcome.testResults then
    update_failing(ctx.outcome.testResults, real, tree)
    -- Remember the run's full executed-test set (every method that actually ran, incl. never-opened
    -- files -- see open_results) so <leader>tq can list it. The summary tree only shows opened files.
    last_run = { label = ctx.label, module_rel = ctx.module_rel, results = ctx.outcome.testResults }
  end

  -- One completion toast per run (NV-2), fired here so it covers both run and debug (both reach
  -- results()). ctx.started is unset on the no-tests skip spec, so the duration is simply omitted.
  local elapsed_ms = ctx.started and (vim.uv.hrtime() - ctx.started) / 1e6 or nil
  local summary = M._run_summary(ctx, real, elapsed_ms)
  util.notify(summary.text, summary.warn and vim.log.levels.WARN or vim.log.levels.INFO)

  require("lathe.stackdecorate").decorate_live_output()
  return results
end

return M
