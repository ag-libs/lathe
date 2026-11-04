-- How the adapter runs tests: build_spec turns a neotest position into a replay run (or a debug
-- session) on the server, streams each test's result back as it finishes, and stops runs.
local common = require("lathe.neotest.common")
local util = require("lathe.util")
local discovery = require("lathe.neotest.discovery")
local outcome = require("lathe.neotest.outcome")
-- Safe to require at load: lathe.output uses only core Neovim APIs (no neotest/nio), and owns the
-- shared console buffer + `lathe/testOutput` handler this adapter and lathe.run both stream into.
local output = require("lathe.output")

local M = {}

local next_token = output.next_token

-- Per-run event queues: the lathe/testEvent handler pushes each result onto the queue for its
-- run token; the spec's stream iterator (build_spec below) drains it and hands neotest the
-- position result, so each method's gutter/summary status flips the moment it finishes rather
-- than all at once when the run ends. STREAM_DONE is a sentinel the run puts when it completes
-- (a queue cannot carry nil).
local event_queues = {}
local STREAM_DONE = {}
-- Debug runs resolve their result_future from the lathe/testFinished notification (the debug launch
-- returns its DAP ports immediately, so the aggregate outcome only arrives out-of-band at session
-- end) rather than from a lathe.run.test response. Keyed by token; set in run_spec's dap branch,
-- cleared in results().
local debug_futures = {}

vim.lsp.handlers["lathe/testEvent"] = function(_err, result)
  if not (result and result.result and result.token) then
    return
  end

  local queue = event_queues[result.token]
  if queue then
    queue.put_nowait({
      position_id = result.result.positionId,
      result = outcome.test_result(result.result, nil),
    })
  end
end

-- A debug run's final outcome arrives here at session end. Put the stream's close sentinel so the
-- live iterator terminates, then resolve the run's future so results() can reconcile. The final
-- outcome.testResults are authoritative, so any straggling live testEvent cut off by the early
-- STREAM_DONE is still covered by the reconciliation pass in results().
vim.lsp.handlers["lathe/testFinished"] = function(_err, result)
  if not (result and result.token and result.outcome) then
    return
  end

  local queue = event_queues[result.token]
  if queue then
    queue.put_nowait(STREAM_DONE)
  end
  local future = debug_futures[result.token]
  if future then
    future.set({ err = nil, outcome = result.outcome })
  end
end

--- Stops all in-flight runs. event_queues holds exactly the live run tokens (set in run_spec, cleared
--- in results()), so a cancel is sent for each: the server destroys the replay JVM, which lets the
--- awaited run.test return, unblocks results(), and clears neotest's running state. This is Lathe's
--- own stop verb -- neotest's run.stop() targets a process, and our real replay runs server-side, so
--- its stop cannot reach it. Bind it (e.g. <leader>tS).
function M.stop()
  local tokens = {}
  for token in pairs(event_queues) do
    tokens[#tokens + 1] = token
  end
  if #tokens == 0 then
    util.notify("No test runs to stop", vim.log.levels.WARN)
    return
  end

  util.notify(
    "Stopping " .. (#tokens == 1 and "1 test run" or (#tokens .. " test runs")),
    vim.log.levels.INFO
  )
  common.nio().run(function()
    local client = common.client()
    if not client then
      return
    end

    for _, token in ipairs(tokens) do
      client.request.workspace_executeCommand({
        command = "lathe.run.cancel",
        arguments = { { token = token } },
      })
    end
  end)
end

--- Builds a spec that runs one or more selectors (a method, class, a package for a directory, or
--- every class in a file) in a single replay JVM, without blocking: the run is fired asynchronously
--- and its per-test results stream in via lathe/testEvent, so neotest can mark positions live.
--- build_spec must return fast (neotest polls spec.stream only after build_spec returns), so the run
--- cannot happen inline here. `command = {"true"}` is a no-op process neotest still needs; the real
--- work rides the stream and the run token. `position_id` is the run position's own id, so its
--- aggregate result and output attach to it.
--- The live-stream iterator factory shared by run and debug specs: drains the run's event queue,
--- handing neotest each per-test result as it arrives, until the STREAM_DONE sentinel ends it.
local function stream_fn(queue)
  return function()
    return function()
      local item = queue.get()
      if item == STREAM_DONE then
        return nil
      end
      return { [item.position_id] = item.result }
    end
  end
end

local function run_spec(position_id, module_rel, selections, client, label, strategy)
  local token = next_token()
  local queue = common.nio().control.queue()
  event_queues[token] = queue
  local result_future = common.nio().control.future()

  local spec = {
    command = { "true" },
    context = {
      position_id = position_id,
      token = token,
      result_future = result_future,
      label = label,
      module_rel = module_rel,
      started = vim.uv.hrtime(),
    },
    stream = stream_fn(queue),
  }

  if strategy == "dap" then
    -- Debug path: neotest's dap strategy launches spec.strategy (the lathe adapter ->
    -- lathe.debug.test), so we must NOT also fire lathe.run.test -- that would spawn a second,
    -- un-debugged JVM alongside the debug session. The aggregate outcome arrives via
    -- lathe/testFinished, which resolves this future (see debug_futures).
    util.notify("Debugging " .. label, vim.log.levels.INFO)
    debug_futures[token] = result_future
    spec.context.debug = true
    spec.strategy = require("lathe.dap").attach_config(module_rel, selections, label, token)
    return spec
  end

  util.notify("Running " .. label, vim.log.levels.INFO)
  common.nio().run(function()
    local err, outcome = client.request.workspace_executeCommand({
      command = "lathe.run.test",
      arguments = {
        {
          moduleRel = module_rel,
          selections = selections,
          token = token,
        },
      },
    }, 0)
    -- Every testEvent for this run has already been handled (they precede the run.test response
    -- on the wire), so the queue holds them all before this close sentinel.
    queue.put_nowait(STREAM_DONE)
    result_future.set({ err = err, outcome = outcome })
  end)

  return spec
end

function M.build_spec(args)
  local pos = args.tree:data()
  local client = common.client()
  if not client then
    return nil
  end

  -- neotest passes strategy == "dap" for its summary `d`/`D` debug mappings and any
  -- run.run({ strategy = "dap" }); run_spec turns that into a debug spec instead of a replay run.
  local strategy = args.strategy

  -- Reset the shared console buffer once per user run action; each class_spec below mints its own
  -- run token, so a file's classes (run concurrently by neotest) stay routed to distinct streams.
  output.reset()
  -- A debug run reuses the run path's console surface (NV-4): open and keep the docked console so the
  -- streamed transcript is visible. The neotest run path deliberately does not auto-open (only
  -- lathe.run's main path does), so this is debug-only.
  if strategy == "dap" then
    output.ensure_open()
  end

  if pos.type == "test" or pos.type == "namespace" then
    return run_spec(pos.id, pos.lathe_module_rel, {
      { selectorKind = pos.lathe_selector_kind, selectorValue = pos.id },
    }, client, pos.name, strategy)
  end

  if pos.type == "dir" then
    -- What to run for a directory (a package, a whole module in one launch, or nothing for the
    -- reactor root) is resolved server-side from the real reactor layout: the client parses no
    -- paths and needs no attribution, so it works even for a never-opened module. Blocking
    -- round-trip in neotest's async run context, like discover_positions' runnables.list call.
    local err, run = client.request.workspace_executeCommand({
      command = "lathe.runnables.dir",
      arguments = { { uri = vim.uri_from_fname(pos.path) } },
    })
    if not err and run and run.selections and #run.selections > 0 then
      -- Key on the dir node's id (not a package name, which matches no node) so results()' fan-out
      -- lands the aggregate here and clears every descendant; per-test statuses still win.
      return run_spec(pos.id, run.moduleRel, run.selections, client, pos.name, strategy)
    end

    if strategy == "dap" then
      util.notify("Nothing here to debug", vim.log.levels.WARN)
      return nil
    end

    -- Not a single run target (reactor root, or a dir with no tests). A no-op skip fires results()
    -- and clears the glyph honestly, instead of neotest's per-file decomposition -- which runs only
    -- already-opened files and reports a misleading green for every test it never ran.
    return {
      command = { "true" },
      context = {
        position_id = pos.id,
        skip_reason = "no runnable tests here — run a module, package, or file",
        label = pos.name,
      },
    }
  end

  if pos.type ~= "file" then
    return nil
  end

  -- Bind file-run to one CLASS-selector run over every class in the file (never PACKAGE -- that
  -- would run every other class in the same package too -- and never per-method, which is what
  -- neotest's own fallback decomposition does if build_spec returns nil: it spawns one replay JVM
  -- per method). A single run means one JVM, one transcript, and one result attached to the file
  -- position, so opening its output works.
  local selections = {}
  local module_rel
  for _, node in args.tree:iter_nodes() do
    local child = node:data()
    if child.type == "namespace" and child.lathe_selector_kind == "CLASS" then
      selections[#selections + 1] = { selectorKind = "CLASS", selectorValue = child.id }
      module_rel = module_rel or child.lathe_module_rel
    end
  end
  -- Discovery may not have answered yet (the file was just opened): run the file's class, whose name
  -- the path gives, rather than skipping the run.
  local class = #selections == 0 and common.path_to_qualified(pos.path)
  module_rel = module_rel or (class and discovery.module_of(client, pos.path))
  if class and module_rel then
    selections = { { selectorKind = "CLASS", selectorValue = class } }
  end

  if #selections == 0 then
    -- Nothing to debug: there is no selector to launch a suspended JVM for. Notify and bail rather
    -- than hand neotest's dap strategy an empty spec.strategy.
    if strategy == "dap" then
      util.notify("Nothing here to debug", vim.log.levels.WARN)
      return nil
    end

    -- Returning nil here routes into neotest's own fallback (_run_broken_down_tree), which finds
    -- zero runnable leaf nodes and returns without ever calling results_callback -- the "running"
    -- status set at the start of run_tree for this position never gets cleared, so its glyph spins
    -- forever. Return a real no-op spec instead so results() always fires and clears it, with a
    -- message explaining why nothing ran.
    return {
      command = { "true" },
      context = {
        position_id = pos.id,
        skip_reason = "no tests found in this file",
        label = pos.name,
      },
    }
  end
  return run_spec(pos.id, module_rel, selections, client, pos.name, strategy)
end

-- A debug run's lathe/testFinished should land at session end, right when neotest calls results();
-- this bounds how long results() waits for it before falling back, so a lost notification degrades
-- to an exit-code-only outcome instead of a position stuck "running".
local DEBUG_FINISH_TIMEOUT_MS = 10000

--- Waits for `future` up to `timeout_ms`, returning its value or nil on timeout. Races the wait
--- against a sleep with nio.first (event-driven, no polling), mirroring await_ready.
local function wait_bounded(future, timeout_ms)
  local value
  common.nio().first({
    function()
      value = future.wait()
    end,
    function()
      common.nio().sleep(timeout_ms)
    end,
  })
  return value
end

--- A minimal outcome-shaped fallback when a debug run's lathe/testFinished never arrives: launched,
--- with neotest's dap-strategy exit code and no per-test results, so results() reports pass/fail from
--- the exit code rather than hanging on the future forever.
local function synthesized_outcome(result)
  return {
    launched = true,
    blockedReasons = {},
    exitCode = (result and result.code) or -1,
    output = {},
    testResults = {},
  }
end
M._synthesized_outcome = synthesized_outcome

--- Waits for the run behind `ctx` and records its outcome in ctx.err and ctx.outcome. neotest calls
--- results() as soon as the no-op "true" process exits, well before the async replay finishes; this
--- runs in neotest's async context, so the wait yields. A spec built by the specs themselves sets
--- ctx.outcome directly and has no result_future.
function M.await_outcome(ctx, result)
  if ctx.result_future then
    local finished
    if ctx.debug then
      -- A debug run's outcome arrives out-of-band via lathe/testFinished, not from an in-process
      -- run.test call, so the wait must be bounded: a lost notification would otherwise hang the
      -- position in "running" forever. On timeout, synthesize from neotest's dap-strategy result
      -- (its session exit code) so results() always completes.
      finished = wait_bounded(ctx.result_future, DEBUG_FINISH_TIMEOUT_MS)
        or { err = nil, outcome = synthesized_outcome(result) }
      debug_futures[ctx.token] = nil
    else
      finished = ctx.result_future.wait()
    end
    ctx.err = finished.err
    ctx.outcome = finished.outcome
    event_queues[ctx.token] = nil
  end
end

return M
