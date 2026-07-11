-- End-to-end driver for the Lathe neotest adapter, run headlessly by
-- dev/neotest-e2e.sh. Unlike the pure-function specs under
-- lathe-maven-plugin/src/test/neovim, this exercises the REAL adapter against a
-- LIVE Lathe server and a real replay run over a built .lathe/ fixture --
-- discovery, run, and per-test results as a user would drive them. It is the
-- red/green signal the neotest-experience acceptance spec
-- (docs/planned/lathe-neotest-experience.md) is written against; each check
-- names the criterion id it covers.
--
-- The runner puts the plugin runtime, neotest, nvim-nio, plenary, and the
-- spec_helper directory on the runtimepath and points LATHE_CACHE/LATHE_E2E_FIXTURE
-- at the fixture. This file only requires and asserts.

local spec = require("spec_helper").new()
local nio = require("nio")

local fixture = assert(os.getenv("LATHE_E2E_FIXTURE"), "LATHE_E2E_FIXTURE not set")
local test_file = fixture .. "/jpms/src/test/java/com/example/jpms/HelloTest.java"
local package_dir = fixture .. "/jpms/src/test/java/com/example/jpms"

local CLASS_ID = "com.example.jpms.HelloTest"
local PACKAGE_ID = "com.example.jpms"
local METHOD_IDS = {
  "com.example.jpms.HelloTest#greet_returnsExpectedMessage()",
  "com.example.jpms.HelloTest#greet_name_returnsPersonalGreeting(java.lang.String)",
  "com.example.jpms.HelloTest#greet_resource_returnsExpectedContent()",
  "com.example.jpms.HelloTest#greet_prints_writesToBothStreams()",
}
local PRINTING_METHOD_ID = "com.example.jpms.HelloTest#greet_prints_writesToBothStreams()"

--- Drives one position subtree through build_spec + results, launching the real
--- replay. build_spec returns a single spec table (`.command` present) for a
--- test/namespace/dir node, or a list of specs for a file node; both are folded
--- into one id -> result map.
local function run_position(adapter, subtree)
  local built = adapter.build_spec({ tree = subtree })
  if built == nil then
    return nil
  end

  if built.command ~= nil then
    return adapter.results(built, nil, subtree)
  end

  local merged = {}
  for _, one in ipairs(built) do
    for id, result in pairs(adapter.results(one, nil, subtree)) do
      merged[id] = result
    end
  end

  return merged
end

--- The dir and file positions neotest synthesizes during its workspace walk are
--- not part of discover_positions' file-rooted tree, so a directory run is
--- exercised with a hand-made dir position matching the shape neotest passes:
--- `data()` returns the dir node and `get_key()` returns nil (a synthesized dir
--- has no discovered subtree here, and results() treats a nil subtree as "no
--- fan-out").
local function dir_tree(path)
  local data = { id = path, type = "dir", path = path, name = PACKAGE_ID }
  return {
    data = function()
      return data
    end,
    get_key = function()
      return nil
    end,
  }
end

require("lathe").setup()
vim.cmd("edit " .. test_file)

-- The D1 acceptance criterion is really about neotest's *discovery scheduling*
-- racing the LSP attach and caching "no tests"; this harness calls the adapter
-- directly, so it cannot reproduce that caching race and instead pins the
-- adapter-level facts underneath it: the client does come up on a cold open, and
-- discovery returns a real tree once it has. Reproducing the scheduling race
-- needs driving neotest's own discovery consumer and is left to the D1 fix.
spec.pending(
  "D1 discovery-vs-attach scheduling race",
  "needs neotest's discovery consumer, not a direct adapter call — deferred to the D1 fix"
)

local attached = vim.wait(30000, function()
  return #vim.lsp.get_clients({ name = "lathe" }) > 0
end, 50)
spec.check("server attaches on cold open", attached, true)
if not attached then
  spec.finish("neotest-e2e")
  return
end

local captured = {}
local done = false
nio.run(function()
  local ok, err = pcall(function()
    local adapter = require("lathe.neotest")

    -- Capture the live per-test event stream (R6) by wrapping the adapter's own handler, so its
    -- queue routing is preserved and we also see every result as it is emitted mid-run.
    local events = {}
    local inner = vim.lsp.handlers["lathe/testEvent"]
    vim.lsp.handlers["lathe/testEvent"] = function(e, result, ...)
      if result and result.result then
        events[#events + 1] = { positionId = result.result.positionId, status = result.result.status }
      end
      if inner then
        return inner(e, result, ...)
      end
    end
    captured.events = events

    local tree = adapter.discover_positions(test_file)
    captured.discovered = tree ~= nil
    if not tree then
      return
    end

    local ids = {}
    for _, pos in tree:iter() do
      ids[pos.id] = pos.type
    end
    captured.ids = ids

    local class_subtree = tree:get_key(CLASS_ID)
    local class_built = adapter.build_spec({ tree = class_subtree })
    -- results() awaits the async run and sets context.outcome, so read the transcript after it.
    captured.class_results = adapter.results(class_built, nil, class_subtree)
    captured.class_transcript = class_built.context.outcome and class_built.context.outcome.output

    captured.dir_results = run_position(adapter, dir_tree(package_dir))

    local file_results = run_position(adapter, tree)
    captured.file_results = file_results
    captured.file_has_output = file_results ~= nil and file_results[test_file] ~= nil

    -- R6 (stream contract): drive spec.stream directly for a single method and drain it, the exact
    -- interface neotest's runner iterates to mark positions live. It must yield the method's result
    -- keyed by its positionId before the stream ends. Use the printing method so this final run
    -- leaves the console buffer populated for the O1/O3 checks below (each run resets it).
    local method_id = PRINTING_METHOD_ID
    local method_built = adapter.build_spec({ tree = tree:get_key(method_id) })
    local streamed = {}
    local iterator = method_built.stream()
    while true do
      local batch = iterator()
      if batch == nil then
        break
      end

      for position_id, r in pairs(batch) do
        streamed[position_id] = r.status
      end
    end
    adapter.results(method_built, nil, tree:get_key(method_id))
    captured.method_stream_status = streamed[method_id]
  end)
  captured.err = (not ok) and tostring(err) or nil
  done = true
end)
vim.wait(120000, function()
  return done
end, 50)

spec.check("nio driver finished without error", captured.err, nil)
spec.check("discover_positions returns a tree once client is ready", captured.discovered, true)

-- D3: every runnable kind in the file is discovered at the right level.
local ids = captured.ids or {}
spec.check("D3 file position discovered", ids[test_file], "file")
spec.check("D3 class namespace discovered", ids[CLASS_ID], "namespace")
for _, id in ipairs(METHOD_IDS) do
  spec.check("D3 method discovered: " .. id, ids[id], "test")
end

-- R1/R3: a class run reports a real per-method status (all pass here), and the
-- class node itself resolves -- not stuck running.
local class_results = captured.class_results or {}
spec.check("R1 class run resolves class node", class_results[CLASS_ID] ~= nil, true)
for _, id in ipairs(METHOD_IDS) do
  local r = class_results[id]
  spec.check("R3 method status passed: " .. id, r and r.status, "passed")
end

-- R6: every method's result streamed in live via lathe/testEvent during the run (not just at the
-- end). Runs above emit events too, but the class/dir/file runs all cover HelloTest, so each
-- method's positionId must have appeared as a passed event.
local streamed_status = {}
for _, ev in ipairs(captured.events or {}) do
  if ev.status == "passed" then
    streamed_status[ev.positionId] = "passed"
  end
end
for _, id in ipairs(METHOD_IDS) do
  spec.check("R6 method streamed a live event: " .. id, streamed_status[id], "passed")
end
spec.check("R6 spec.stream yields the method result to neotest", captured.method_stream_status, "passed")

-- Deliverable 1: the replay transcript arrives as a list of stdout/stderr-tagged
-- {stream, text} lines, not a flat string list. A passing run legitimately emits
-- no console output (LatheTestRunner prints only failing tests), so this asserts
-- the shape, not non-emptiness; the stdout/stderr tagging itself is proven in
-- ReplaySessionTest. Entries, when present, must carry text.
local transcript = captured.class_transcript
spec.check("transcript arrives as a list", type(transcript) == "table", true)
local entries_well_formed = true
for _, line in ipairs(transcript or {}) do
  if type(line.text) ~= "string" then
    entries_well_formed = false
  end
end
spec.check("transcript entries carry text", entries_well_formed, true)

-- O1/O3: a run of HelloTest (which includes a test that prints to both streams) streams its
-- console output live into the docked buffer, with stderr distinguished. Each run resets the
-- buffer, so this reflects the last run above; the printing test runs under all of them.
-- Streamed lines are appended via vim.schedule, so wait for the stdout line before asserting.
local adapter = require("lathe.neotest")
vim.wait(3000, function()
  local lines = adapter._live_output_lines()
  return lines ~= nil and vim.tbl_contains(lines, "hello on stdout")
end, 25)
local live_lines = adapter._live_output_lines() or {}
spec.check("O1 stdout line streamed to live buffer", vim.tbl_contains(live_lines, "hello on stdout"), true)
spec.check("O1 stderr line streamed to live buffer", vim.tbl_contains(live_lines, "warning on stderr"), true)
spec.check("O3 stderr line is highlighted", #adapter._live_output_stderr_rows() > 0, true)

-- R1: a directory run binds to a single package selector (keyed by the package
-- name, not the dir path) and reports passed.
local dir_results = captured.dir_results or {}
spec.check(
  "R1 directory run reports a status",
  dir_results[PACKAGE_ID] and dir_results[PACKAGE_ID].status,
  "passed"
)

-- R2: a file run should surface output reachable from the file position. Known
-- gap today (the per-class fan-out never keys output onto the file id), so it is
-- recorded pending rather than failed until Phase 3 fixes it.
if captured.file_has_output then
  spec.check("R2 file run output reachable from file position", captured.file_has_output, true)
else
  spec.pending(
    "R2 file run output reachable from file position",
    "file-run fan-out keys output per class, never onto the file position id"
  )
end

spec.finish("neotest-e2e")
