-- neotest adapter for Lathe (requires https://github.com/nvim-neotest/neotest).
--
-- Installation: alongside require('lathe').setup(), configure neotest with this adapter:
--   require('neotest').setup({ adapters = { require('lathe.neotest') } })
--
-- Discovery and execution both go through the already-running Lathe LSP server rather
-- than spawning Maven or a treesitter-query scan: discover_positions calls
-- lathe.runnables.list (real attributed-analysis discovery, not syntax guessing), and
-- build_spec calls lathe.run.test synchronously via nio.lsp, replaying from captured
-- .lathe/ bytecode -- no Maven invocation, no recompilation. build_spec is not declared
-- async but neotest always invokes it from inside TestRunner:run_tree's async chain, so
-- a yielding nio.lsp call inside it is valid.

-- Required lazily (inside functions, not here at module load) so that
-- `require('lathe.neotest')` itself never fails when neotest/nio aren't on
-- the runtimepath -- this file must stay loadable, including by tests that
-- exercise its pure logic, on any machine that doesn't have the optional
-- neotest plugin installed.
local function nio()
  return require("nio")
end

local function Tree()
  return require("neotest.types").Tree
end

local function stacktrace()
  return require("lathe.stacktrace")
end

local M = {}
M.name = "neotest-lathe"

-- Troubleshooting log for the adapter. Writes to Neovim's LSP log (:LspLog) -- the same place the
-- Lathe server's own stderr lands -- gated on LATHE_DEBUG so it matches the server's debug switch
-- (LATHE_DEBUG=1) and stays silent otherwise. Format mirrors the server's "[operation] target
-- detail Xms outcome" convention.
local function debug_log(msg)
  if vim.env.LATHE_DEBUG then
    vim.lsp.log.info("[lathe.neotest] " .. msg)
  end
end

-- Workspace readiness gate. The Lathe server reports workspace load/reload as a $/progress task
-- under this title (mirrors WorkspaceSession.WORKSPACE_PROGRESS_TITLE); discovery is gated on that
-- progress ending, so a discovery that fires before the server is ready suspends instead of caching
-- "no tests" (the D1 startup race). Neovim backfills the begin title onto the end event, so the
-- title alone identifies the workspace-load completion.
local WORKSPACE_PROGRESS_TITLE = "Lathe: indexing workspace"
local READY_TIMEOUT_MS = 30000
local READY_AUGROUP = vim.api.nvim_create_augroup("LatheNeotestReady", { clear = true })
local workspace_ready = false
local ready_event

local function signal_ready()
  if workspace_ready then
    return
  end

  workspace_ready = true
  if ready_event then
    ready_event.set()
  end
end

--- Re-runs neotest discovery for open Java test buffers once the workspace is ready, so a summary
--- that cached "no tests" before the server loaded fills in without the user re-opening the file.
local function rediscover_open_buffers()
  debug_log("[status] workspace ready → re-discovering open buffers")
  nio().run(function()
    for _, buf in ipairs(vim.api.nvim_list_bufs()) do
      if vim.api.nvim_buf_is_loaded(buf) and vim.bo[buf].filetype == "java" then
        local name = vim.api.nvim_buf_get_name(buf)
        if name ~= "" and M.is_test_file(name) then
          -- Wrap the whole access: neotest.run is nil until neotest.setup() has run (e.g. the
          -- direct-drive harness never sets it up), and indexing it must not error the autocmd.
          pcall(function()
            require("neotest").run.get_tree_from_args(name)
          end)
        end
      end
    end
  end)
end

vim.api.nvim_create_autocmd("LspProgress", {
  group = READY_AUGROUP,
  pattern = "end",
  callback = function(ev)
    local client = vim.lsp.get_client_by_id(ev.data.client_id)
    if not client or client.name ~= "lathe" then
      return
    end

    local value = ev.data.params and ev.data.params.value
    if value and value.title == WORKSPACE_PROGRESS_TITLE then
      signal_ready()
      rediscover_open_buffers()
    end
  end,
})

--- Suspends until the workspace has loaded (the $/progress end above), bounded by a timeout after
--- which discovery is attempted anyway -- so a missed progress edge degrades to a slower first
--- discovery, never a broken one. Event-driven (no polling); called from neotest's async discovery.
local function await_ready()
  if workspace_ready then
    return
  end

  ready_event = ready_event or nio().control.event()
  nio().first({
    ready_event.wait,
    function()
      nio().sleep(READY_TIMEOUT_MS)
    end,
  })
end

-- RunnableKind ordinal -> neotest position type / TestSelectionKind. lsp4j's Gson layer
-- serializes Java enums by ordinal, matching the LSP convention that kind fields like
-- SymbolKind/DiagnosticSeverity are integers (see dev/explore.py's identical handling).
-- Ordinals match RunnableKind's declaration order in lathe-server: MAIN, TEST_METHOD,
-- TEST_CLASS, TEST_PACKAGE. MAIN(0) has no entry: main-class replay isn't implemented
-- yet. TEST_PACKAGE(3) has no entry either: a package spans multiple files, so nesting
-- it as a child of whichever single file's discovery happened to report it puts it at
-- the wrong level in the tree (file contains package, not the other way around).
-- Package-level running is instead bound to the directory node neotest's own tree walk
-- already creates at the right level -- see build_spec's "dir" handling below.
local POSITION_TYPE = { [1] = "test", [2] = "namespace" }
local SELECTOR_KIND = { [1] = "METHOD", [2] = "CLASS" }

local function lathe_client()
  local clients = nio().lsp.get_clients({ name = "lathe" })
  return clients[1]
end

-- ===== Live test-output surface (streamed via the lathe/testOutput notification) =====
-- One docked scratch buffer shows a run's console output as it streams, with stderr
-- distinguished from stdout. It replaces neotest's render-once float: the buffer is
-- appended to live (so it is always current by construction, no reopen dance), and once a
-- run finishes it is decorated with the same stack-frame navigation used before. build_spec
-- runs a file's per-class specs sequentially inline, so a run is single-threaded here: one
-- token, one buffer, reset once at the start.

local LIVE_OUTPUT_NS = vim.api.nvim_create_namespace("lathe_test_output")
local STDERR_HL = "LatheTestStderr"
vim.api.nvim_set_hl(0, STDERR_HL, { link = "DiagnosticError", default = true })
local MAX_LIVE_HEIGHT = 20
local STDERR_STREAM = 1 -- TranscriptLine.Stream.STDERR ordinal

local live_bufnr
local live_win
local run_counter = 0

--- A per-run correlation token the server echoes on each streamed line. Unique within a
--- session; the server tags notifications with it and a blank one disables streaming.
local function next_token()
  run_counter = run_counter + 1
  return "run-" .. run_counter
end

local function live_buffer()
  if live_bufnr and vim.api.nvim_buf_is_valid(live_bufnr) then
    return live_bufnr
  end

  live_bufnr = vim.api.nvim_create_buf(false, true)
  vim.bo[live_bufnr].bufhidden = "hide"
  vim.bo[live_bufnr].filetype = "lathe-test-output"
  vim.bo[live_bufnr].modifiable = false
  return live_bufnr
end

local function live_is_open()
  return live_win ~= nil and vim.api.nvim_win_is_valid(live_win)
end

--- Empties the live buffer at the start of a run so it always shows the current one.
local function live_reset()
  local buf = live_buffer()
  vim.bo[buf].modifiable = true
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, {})
  vim.bo[buf].modifiable = false
  vim.api.nvim_buf_clear_namespace(buf, LIVE_OUTPUT_NS, 0, -1)
end

--- Appends one streamed line, coloring stderr, and follows the tail while the window is
--- open but not focused (so scrolling up to read stays sticky).
local function live_append(stream, text)
  local buf = live_buffer()
  local count = vim.api.nvim_buf_line_count(buf)
  local first_empty = count == 1 and vim.api.nvim_buf_get_lines(buf, 0, 1, false)[1] == ""
  local row = first_empty and 0 or count
  vim.bo[buf].modifiable = true
  vim.api.nvim_buf_set_lines(buf, row, first_empty and 1 or row, false, { text })
  vim.bo[buf].modifiable = false
  if stream == STDERR_STREAM then
    pcall(vim.api.nvim_buf_set_extmark, buf, LIVE_OUTPUT_NS, row, 0, {
      end_row = row + 1,
      hl_group = STDERR_HL,
      hl_eol = true,
    })
  end

  if live_is_open() and vim.api.nvim_get_current_win() ~= live_win then
    pcall(vim.api.nvim_win_set_cursor, live_win, { vim.api.nvim_buf_line_count(buf), 0 })
  end
end

--- Opens the docked live-output window, or toggles it closed / focuses it if already open.
--- This is the single output surface; neotest's floating output is not used.
function M.open_output()
  if live_is_open() then
    if vim.api.nvim_get_current_win() == live_win then
      vim.api.nvim_win_close(live_win, true)
      live_win = nil
    else
      vim.api.nvim_set_current_win(live_win)
    end

    return
  end

  local buf = live_buffer()
  local prev = vim.api.nvim_get_current_win()
  vim.cmd("botright split")
  vim.api.nvim_win_set_height(0, MAX_LIVE_HEIGHT)
  live_win = vim.api.nvim_get_current_win()
  vim.api.nvim_win_set_buf(live_win, buf)
  vim.api.nvim_set_current_win(prev)
end

--- Test hook: the live output buffer's current lines, or nil if it has none yet.
function M._live_output_lines()
  if not (live_bufnr and vim.api.nvim_buf_is_valid(live_bufnr)) then
    return nil
  end

  return vim.api.nvim_buf_get_lines(live_bufnr, 0, -1, false)
end

--- Test hook: the 0-based rows in the live buffer currently marked as stderr.
function M._live_output_stderr_rows()
  if not (live_bufnr and vim.api.nvim_buf_is_valid(live_bufnr)) then
    return {}
  end

  local rows = {}
  for _, mark in ipairs(vim.api.nvim_buf_get_extmarks(live_bufnr, LIVE_OUTPUT_NS, 0, -1, {})) do
    rows[#rows + 1] = mark[2]
  end
  return rows
end

-- Streamed lines arrive on any run; append them on the main loop (buffer edits must not
-- run inside the LSP callback's fast context).
vim.lsp.handlers["lathe/testOutput"] = function(_err, result)
  if not result or not result.line then
    return
  end

  vim.schedule(function()
    live_append(result.line.stream, result.line.text)
  end)
end

local TEST_STATUS = { passed = "passed", failed = "failed", skipped = "skipped" }

--- One structured per-test result mapped to a neotest.Result. `output` is the run's shared
--- transcript path, or nil while streaming live (the transcript file is written only once the
--- run finishes, in results()).
local function test_result(tr, output)
  local res = { status = TEST_STATUS[tr.status] or "failed", output = output }
  if tr.failureMessage and tr.failureMessage ~= "" then
    res.short = tr.failureMessage
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

-- Per-run event queues: the lathe/testEvent handler pushes each result onto the queue for its
-- run token; the spec's stream iterator (build_spec below) drains it and hands neotest the
-- position result, so each method's gutter/summary status flips the moment it finishes rather
-- than all at once when the run ends. STREAM_DONE is a sentinel the run puts when it completes
-- (a queue cannot carry nil).
local event_queues = {}
local STREAM_DONE = {}

vim.lsp.handlers["lathe/testEvent"] = function(_err, result)
  if not (result and result.result and result.token) then
    return
  end

  local queue = event_queues[result.token]
  if queue then
    queue.put_nowait({ position_id = result.result.positionId, result = test_result(result.result, nil) })
  end
end

function M.root(dir)
  return vim.fs.root(dir, ".lathe")
end

function M.filter_dir(name, _rel_path, _root)
  return name ~= "target" and name ~= ".lathe" and name ~= ".git"
end

-- Surefire's own default test-file include patterns (what Maven uses whenever a project
-- doesn't override <includes> in its maven-surefire-plugin config): Test*.java,
-- *Test.java, *Tests.java, *TestCase.java. Hardcoded here as a reasonable default rather
-- than guessed at -- could be improved later by reading each module's real, possibly
-- project-overridden <includes> at lathe:sync time (the same way runnerClasspath already
-- reads real reactor state instead of assuming one) and recording it in workspace.json for
-- this adapter to read, instead of assuming every project uses Surefire's defaults.
function M.is_test_file(file_path)
  if not file_path:match("%.java$") then
    return false
  end
  local name = vim.fn.fnamemodify(file_path, ":t:r")
  return name:match("^Test") ~= nil
    or name:match("Test$") ~= nil
    or name:match("Tests$") ~= nil
    or name:match("TestCase$") ~= nil
end

--- Converts an LSP Range (0-based line/character, on start/end objects) into neotest's
--- flat 4-element range shape. Both are 0-based, so no offset conversion is needed.
local function to_range(lsp_range)
  return {
    lsp_range.start.line,
    lsp_range.start.character,
    lsp_range["end"].line,
    lsp_range["end"].character,
  }
end

--- Builds the nested-list forest shape Tree.from_list expects (head = node data,
--- following elements = child subtrees) from lathe.runnables.list's flat,
--- parentId-linked result. Pure data transformation, no neotest/nio dependency --
--- kept separate from build_tree specifically so it's testable without either
--- being installed. Any position whose parent isn't itself a node in this file (an
--- intermediate class with no tests of its own) attaches directly under the file root.
function M._build_position_forest(file_path, targets)
  local positions = {}
  local children_of = {}
  for _, t in ipairs(targets) do
    local ptype = POSITION_TYPE[t.kind]
    if ptype then
      local pos = {
        id = t.id,
        parent_id = t.parentId,
        type = ptype,
        name = t.label,
        path = file_path,
        range = to_range(t.range),
        lathe_module_rel = t.moduleRel,
        lathe_selector_kind = SELECTOR_KIND[t.kind],
      }
      positions[t.id] = pos
      children_of[t.parentId] = children_of[t.parentId] or {}
      table.insert(children_of[t.parentId], pos)
    end
  end

  local function to_list(pos)
    local kids = children_of[pos.id]
    if not kids then
      return pos
    end
    local list = { pos }
    for _, kid in ipairs(kids) do
      table.insert(list, to_list(kid))
    end
    return list
  end

  local root = {
    id = file_path,
    type = "file",
    name = vim.fn.fnamemodify(file_path, ":t"),
    path = file_path,
  }
  local root_list = { root }
  for _, pos in pairs(positions) do
    -- A position is reached via recursion from its real parent only if that
    -- parent is itself a tracked node; otherwise (an intermediate class with
    -- no tests of its own) it attaches directly to the file root.
    if not positions[pos.parent_id] then
      table.insert(root_list, to_list(pos))
    end
  end

  return root_list
end

local function build_tree(file_path, targets)
  local root_list = M._build_position_forest(file_path, targets)
  return Tree().from_list(root_list, function(pos)
    return pos.id
  end)
end

function M.discover_positions(file_path)
  -- Gate on workspace readiness so an early discovery suspends until the server has loaded, rather
  -- than racing it and caching "no tests" (D1).
  await_ready()

  local client = lathe_client()
  if not client then
    debug_log("[discover] " .. file_path .. " client=absent → nil")
    return nil
  end

  local bufnr = vim.fn.bufadd(file_path)
  local uri = vim.uri_from_fname(file_path)
  local err, targets = client.request.workspace_executeCommand({
    command = "lathe.runnables.list",
    arguments = { { uri = uri } },
  }, bufnr)
  if err or not targets or #targets == 0 then
    return nil
  end

  return build_tree(file_path, targets)
end

--- Derives {moduleRel, package} from a directory path, mirroring how Maven's
--- own layout (and RunnableScanner.packageName() server-side) resolve package
--- identity from source layout: <module>/src/(test|main)/java/<package/as/dirs>.
--- Pure and workspace_root-parameterized (no M.root() call inside) so it's
--- directly unit-testable. Returns nil for anything that doesn't match that
--- shape -- the module root itself, a path above src/, a non-Maven-standard
--- layout, or the default/unnamed package (RunnableScanner.emitPackageOnce
--- skips that one too) -- so build_spec can safely fall back to neotest's own
--- decomposition instead of guessing at a selector that might run the wrong
--- (or nothing at all) thing.
function M._package_for_dir(dir_path, workspace_root)
  local module_abs, package_path = dir_path:match("^(.-)/src/[^/]+/java/(.*)$")
  if not module_abs or package_path == "" then
    return nil
  end

  if not vim.startswith(module_abs, workspace_root) then
    return nil
  end
  local module_rel = module_abs:sub(#workspace_root + 2)
  local package_name = package_path:gsub("/", ".")
  return module_rel, package_name
end

--- Builds a spec that runs one or more selectors (a method, class, a package for a directory, or
--- every class in a file) in a single replay JVM, without blocking: the run is fired asynchronously
--- and its per-test results stream in via lathe/testEvent, so neotest can mark positions live.
--- build_spec must return fast (neotest polls spec.stream only after build_spec returns), so the run
--- cannot happen inline here. `command = {"true"}` is a no-op process neotest still needs; the real
--- work rides the stream and the run token. `position_id` is the run position's own id, so its
--- aggregate result and output attach to it.
local function run_spec(position_id, module_rel, selections, client)
  local token = next_token()
  local queue = nio().control.queue()
  event_queues[token] = queue
  local result_future = nio().control.future()

  nio().run(function()
    local err, outcome = client.request.workspace_executeCommand({
      command = "lathe.run.test",
      arguments = { {
        moduleRel = module_rel,
        selections = selections,
        token = token,
      } },
    }, 0)
    -- Every testEvent for this run has already been handled (they precede the run.test response
    -- on the wire), so the queue holds them all before this close sentinel.
    queue.put_nowait(STREAM_DONE)
    result_future.set({ err = err, outcome = outcome })
  end)

  return {
    command = { "true" },
    context = { position_id = position_id, token = token, result_future = result_future },
    stream = function()
      return function()
        local item = queue.get()
        if item == STREAM_DONE then
          return nil
        end
        return { [item.position_id] = item.result }
      end
    end,
  }
end

function M.build_spec(args)
  local pos = args.tree:data()
  local client = lathe_client()
  if not client then
    return nil
  end

  -- Reset the shared console buffer once per user run action; each class_spec below mints its own
  -- run token, so a file's classes (run concurrently by neotest) stay routed to distinct streams.
  live_reset()

  if pos.type == "test" or pos.type == "namespace" then
    return run_spec(pos.id, pos.lathe_module_rel, {
      { selectorKind = pos.lathe_selector_kind, selectorValue = pos.id },
    }, client)
  end

  if pos.type == "dir" then
    -- A directory is a Java package 1:1 in standard Maven layout -- bind
    -- running it to a single PACKAGE-selector run (selectPackage resolves
    -- against the real classpath and already includes subpackages
    -- recursively, so this covers everything under the directory in one
    -- JVM launch) instead of letting neotest fall through to running every
    -- file underneath individually. Falls back to normal decomposition
    -- (return nil) for anything that doesn't look like a package directory.
    local workspace_root = M.root(pos.path)
    if not workspace_root then
      return nil
    end
    local module_rel, package_name = M._package_for_dir(pos.path, workspace_root)
    if not module_rel then
      return nil
    end
    return run_spec(package_name, module_rel, {
      { selectorKind = "PACKAGE", selectorValue = package_name },
    }, client)
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
  if #selections == 0 then
    -- Returning nil here routes into neotest's own fallback (_run_broken_down_tree), which finds
    -- zero runnable leaf nodes and returns without ever calling results_callback -- the "running"
    -- status set at the start of run_tree for this position never gets cleared, so its glyph spins
    -- forever. Return a real no-op spec instead so results() always fires and clears it, with a
    -- message explaining why nothing ran.
    local reason = vim.fn.bufloaded(pos.path) == 1 and "no tests found in this file"
      or ("open " .. vim.fn.fnamemodify(pos.path, ":t") .. " to discover its tests before running")
    return { command = { "true" }, context = { position_id = pos.id, skip_reason = reason } }
  end
  return run_spec(pos.id, module_rel, selections, client)
end

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

--- neotest.Client:run_tree marks every id in the run's whole subtree as
--- "running" up front (client/init.lua's update_running, built from
--- tree:iter()) but only clears whichever ids results() returns -- a class
--- or package result naming just its own id leaves every descendant
--- method/class stuck showing "running" forever. Real per-test statuses from
--- outcome.testResults are mapped first, so exactly the methods that failed
--- are marked failed; any descendant not covered by a structured result (an
--- id-mapping miss, the class namespace node itself, or an older outcome with
--- no testResults) still inherits the aggregate status via the fan-out, so
--- nothing is left stuck running. Scoped to just the run position's own
--- subtree via tree:get_key(ctx.position_id), not the whole tree parameter --
--- build_spec's file-run fan-out (one spec per class) passes the same outer
--- file tree to every class's results() call, so resolving "everything in
--- tree" would incorrectly stamp sibling classes' methods with the wrong
--- class's result.
function M.results(spec, _result, tree)
  local ctx = spec.context

  -- neotest calls results() as soon as the no-op "true" process exits, which is well before the
  -- async replay finishes -- so wait for the run here, then read its outcome. The per-test
  -- statuses were already applied live via spec.stream; this pass provides the authoritative
  -- reconciliation, the run-position aggregate, and the output file. Runs in neotest's async
  -- context, so the wait yields. Pure-function specs set ctx.outcome directly (no result_future).
  if ctx.result_future then
    local finished = ctx.result_future.wait()
    ctx.err = finished.err
    ctx.outcome = finished.outcome
    event_queues[ctx.token] = nil
  end

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
        results[id] = test_result(tr, result.output)
      end
    end
  end

  results[ctx.position_id] = results[ctx.position_id] or result

  local subtree = tree and tree:get_key(ctx.position_id)
  if subtree then
    for _, node in subtree:iter_nodes() do
      results[node:data().id] = results[node:data().id] or result
    end
  end

  M._decorate_live_output()
  return results
end

-- Stack-trace navigation for the live output buffer
-- (docs/planned/lathe-neotest-streaming.md). Resolution works from the frame text alone
-- (§4 of docs/done/lathe-test-output-streaming.md) -- no server-side command and no metadata
-- recorded when the transcript streamed. _decorate_live_output() drives it once a run
-- finishes; the functions below take an explicit bufnr so they apply to the live buffer the
-- same way they once applied to neotest's output buffer.

local STACKTRACE_NS = vim.api.nvim_create_namespace("lathe_stacktrace")
local STACKTRACE_HL = "LatheStackFrame"
vim.api.nvim_set_hl(0, STACKTRACE_HL, { link = "Underlined", default = true })

-- clear = true so re-requiring this module (e.g. a plugin-manager reload)
-- replaces rather than duplicates these autocmds, matching lathe.lua's own
-- LathePlugin augroup.
local STACKTRACE_AUGROUP = vim.api.nvim_create_augroup("LatheStacktrace", { clear = true })

local frame_locations = {}
local jump_keymaps_set = {}

-- Tracks the most recently entered window showing a Java buffer, so a jump from the docked
-- output window lands in the editor window the source was already open in, instead of
-- replacing the output window's own buffer.
local last_java_win

vim.api.nvim_create_autocmd("BufEnter", {
  group = STACKTRACE_AUGROUP,
  callback = function(ev)
    if vim.bo[ev.buf].filetype == "java" then
      last_java_win = vim.api.nvim_get_current_win()
    end
  end,
})

local function jump_to_resolved_frame(bufnr)
  local locations = frame_locations[bufnr]
  local row = vim.api.nvim_win_get_cursor(0)[1]
  local target = locations and locations[row]
  if not target then
    return
  end

  if last_java_win and vim.api.nvim_win_is_valid(last_java_win) then
    vim.api.nvim_set_current_win(last_java_win)
  end

  vim.cmd("edit " .. vim.fn.fnameescape(vim.uri_to_fname(target.uri)))
  -- Java stack-trace line numbers are 1-based, as is the cursor row; clamp defensively in case the
  -- resolved file has drifted from the line the trace named.
  local line = math.max(1, math.min(target.line, vim.api.nvim_buf_line_count(0)))
  vim.api.nvim_win_set_cursor(0, { line, 0 })
end

local function ensure_jump_keymaps(bufnr)
  if jump_keymaps_set[bufnr] then
    return
  end

  jump_keymaps_set[bufnr] = true
  local opts = { buffer = bufnr, desc = "Lathe: jump to resolved stack frame" }
  for _, key in ipairs({ "<CR>", "gF" }) do
    vim.keymap.set("n", key, function()
      jump_to_resolved_frame(bufnr)
    end, opts)
  end
end

--- Underlines the `File.java:line` span of a resolved frame so the clickable
--- target reads as a link. `entry` is one frames_in_buffer() record: `text` is
--- the buffer row and `row` its 1-based index. Marks only the file:line span,
--- not the whole frame. Returns whether an extmark was placed.
local function highlight_frame_span(bufnr, entry)
  local span = ("%s:%d"):format(entry.frame.file, entry.frame.line)
  local start = entry.text:find(span, 1, true)
  if not start then
    return false
  end

  local span_start = start - 1
  pcall(vim.api.nvim_buf_set_extmark, bufnr, STACKTRACE_NS, entry.row - 1, span_start, {
    end_col = span_start + #span,
    hl_group = STACKTRACE_HL,
  })
  return true
end

-- Decoration runs once a run finishes, when the live buffer already holds the streamed
-- lines, so the first scan normally finds them. The bounded re-scan is kept as a cheap
-- guard against being called a tick early.
local DECORATE_MAX_ATTEMPTS = 12
local DECORATE_RETRY_MS = 50

--- Collects the stack frames in the output buffer. The live buffer holds one
--- logical line per row (we append transcript lines directly -- no terminal
--- hard-wrap to undo), so each frame maps to a single row. Returns a list of
--- { frame = <parse_frame result>, row = <1-based buffer row>, text = <row text> }.
local function frames_in_buffer(bufnr)
  local raw = vim.api.nvim_buf_get_lines(bufnr, 0, -1, false)
  local frames = {}
  for i, text in ipairs(raw) do
    local frame = stacktrace().parse_frame(text)
    if frame then
      frames[#frames + 1] = { frame = frame, row = i, text = text }
    end
  end

  return frames
end

--- Identity of a frame's class for resolution caching: the simple name alone
--- would collide for same-named classes in different packages, which
--- pick_candidate distinguishes by package.
local function frame_key(frame)
  return frame.simple_name .. "#" .. frame.package
end

--- Resolves the source location of each distinct class in `frames` via
--- workspace/symbol. Returns frame_key -> location (or false). Runs in an nio
--- context; this is the only async step, so it is kept separate from the (fast,
--- synchronous) highlight pass that follows a fresh re-scan.
local function resolve_frames(client, bufnr, frames)
  local resolved = {}
  for _, entry in ipairs(frames) do
    local frame = entry.frame
    if resolved[frame_key(frame)] == nil then
      local _err, symbols = client.request.workspace_symbol({ query = frame.simple_name }, bufnr)
      local candidate = stacktrace().pick_candidate(frame, symbols)
      resolved[frame_key(frame)] = candidate and candidate.location or false
    end
  end

  return resolved
end

--- Scans the output buffer for stack frames and resolves each via the standard
--- workspace/symbol request -- no new server-side command, no metadata recorded when the
--- transcript streamed; resolution works from the frame text alone (§4).
local function decorate_stack_frames(bufnr, attempt)
  attempt = attempt or 1
  if not vim.api.nvim_buf_is_valid(bufnr) then
    return
  end

  local frames = frames_in_buffer(bufnr)
  if #frames == 0 then
    if attempt < DECORATE_MAX_ATTEMPTS then
      vim.defer_fn(function()
        decorate_stack_frames(bufnr, attempt + 1)
      end, DECORATE_RETRY_MS)
    end

    return
  end

  local client = lathe_client()
  if not client then
    return
  end

  nio().run(function()
    local resolved = resolve_frames(client, bufnr, frames)

    -- Re-scan after the async resolution: more lines may have streamed in while the LSP request
    -- was in flight, so highlight against a fresh read rather than the pre-resolution frames.
    if not vim.api.nvim_buf_is_valid(bufnr) then
      return
    end

    local locations = {}
    frame_locations[bufnr] = locations
    for _, entry in ipairs(frames_in_buffer(bufnr)) do
      local location = resolved[frame_key(entry.frame)]
      if location then
        highlight_frame_span(bufnr, entry)
        -- Jump to the frame's own line in the resolved file. workspace/symbol only locates the
        -- class (its declaration), so location.range points at the class, not the failing line --
        -- pair the resolved uri with the frame's parsed line instead.
        locations[entry.row] = { uri = location.uri, line = entry.frame.line }
      end
    end

    ensure_jump_keymaps(bufnr)
  end)
end

local live_decorate_pending = false

--- Once a run finishes (from results()), decorate the live buffer's stack frames the same
--- way the neotest output surface used to be. Coalesced so a multi-spec file run decorates
--- once, and the frame namespace is cleared first so a re-run does not stack duplicate marks
--- onto the buffer that live_reset() already emptied.
function M._decorate_live_output()
  if live_decorate_pending or not (live_bufnr and vim.api.nvim_buf_is_valid(live_bufnr)) then
    return
  end

  live_decorate_pending = true
  vim.schedule(function()
    live_decorate_pending = false
    if live_bufnr and vim.api.nvim_buf_is_valid(live_bufnr) then
      vim.api.nvim_buf_clear_namespace(live_bufnr, STACKTRACE_NS, 0, -1)
      decorate_stack_frames(live_bufnr)
    end
  end)
end

-- The live buffer is created with bufhidden=hide, so closing its window hides rather than
-- wipes it and BufWipeout alone would leak the per-buffer decoration tables. BufHidden fires
-- the moment the buffer stops being shown, which is also when its buffer-local jump keymaps
-- become unreachable.
vim.api.nvim_create_autocmd({ "BufHidden", "BufWipeout" }, {
  group = STACKTRACE_AUGROUP,
  callback = function(ev)
    frame_locations[ev.buf] = nil
    jump_keymaps_set[ev.buf] = nil
  end,
})

return M
