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

local function neotest_api()
  return require("neotest")
end

local M = {}
M.name = "neotest-lathe"

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
  local client = lathe_client()
  if not client then
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

local function class_spec(pos, client)
  local err, outcome = client.request.workspace_executeCommand({
    command = "lathe.run.test",
    arguments = { {
      moduleRel = pos.lathe_module_rel,
      selectorKind = pos.lathe_selector_kind,
      selectorValue = pos.id,
    } },
  }, 0)

  return {
    command = { "true" },
    context = { position_id = pos.id, err = err, outcome = outcome },
  }
end

function M.build_spec(args)
  local pos = args.tree:data()
  local client = lathe_client()
  if not client then
    return nil
  end

  if pos.type == "test" or pos.type == "namespace" then
    return class_spec(pos, client)
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
    return class_spec({
      id = package_name,
      lathe_module_rel = module_rel,
      lathe_selector_kind = "PACKAGE",
    }, client)
  end

  if pos.type ~= "file" then
    return nil
  end

  -- Bind file-run to class-run: one lathe.run.test call per CLASS in this
  -- file (never PACKAGE -- that would run every other class in the same
  -- package too -- and never per-method, which is what neotest's own
  -- fallback decomposition does if build_spec returns nil here: it skips
  -- straight to every leaf "test" node in the subtree via run_pos_types
  -- ("test"), spawning one replay JVM per method concurrently instead of
  -- one per class.
  local specs = {}
  for _, node in args.tree:iter_nodes() do
    local child = node:data()
    if child.type == "namespace" and child.lathe_selector_kind == "CLASS" then
      table.insert(specs, class_spec(child, client))
    end
  end
  if #specs == 0 then
    -- Returning nil here routes into neotest's own fallback
    -- (_run_broken_down_tree), which finds zero runnable leaf nodes and
    -- returns without ever calling results_callback -- the "running" status
    -- set at the start of run_tree for this position never gets cleared, so
    -- its glyph spins forever. Return a real no-op spec instead so results()
    -- always fires and clears it, with a message explaining why nothing ran.
    local reason = vim.fn.bufloaded(pos.path) == 1 and "no tests found in this file"
      or ("open " .. vim.fn.fnamemodify(pos.path, ":t") .. " to discover its tests before running")
    return { command = { "true" }, context = { position_id = pos.id, skip_reason = reason } }
  end
  return specs
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

local TEST_STATUS = { passed = "passed", failed = "failed", skipped = "skipped" }

--- Rebuilds a method's RunTarget position id from a structured TestResult's
--- JUnit MethodSource fields, matching RunnableScanner.methodTarget's format
--- exactly: "<binaryClassName>#<methodName>(<erasedParams>)". JUnit joins
--- parameter types with ", " whereas javac's erasure joins with "," and no
--- space, so whitespace is stripped; the dominant zero-arg case is
--- "<class>#<method>()" identically on both sides. A signature shape that
--- doesn't reconstruct identically (arrays, @ParameterizedTest dynamic ids)
--- simply won't match a tree id and falls through to the aggregate fan-out.
local function test_result_position_id(tr)
  local params = (tr.methodParameterTypes or ""):gsub("%s", "")
  return string.format("%s#%s(%s)", tr.className, tr.methodName, params)
end

--- One structured per-test result. Status only for now -- result.errors /
--- vim.diagnostic from failureLine is deferred to the diagnostics design
--- (docs/planned/lathe-test-diagnostics-and-refresh.md). Reuses the run's
--- shared transcript as output (a class/package run is one JVM, so there is
--- only the one transcript) and surfaces the failure message as `short`.
local function test_result(tr, output)
  local res = { status = TEST_STATUS[tr.status] or "failed", output = output }
  if tr.failureMessage and tr.failureMessage ~= "" then
    res.short = tr.failureMessage
  end
  return res
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
        output = write_output_file(table.concat(outcome.output or {}, "\n")),
      }
    end
  end

  -- Real per-test statuses first, so they win over the aggregate on any node
  -- they cover (including the run's own position_id for a single-method run).
  local results = {}
  if ctx.outcome and ctx.outcome.testResults then
    for _, tr in ipairs(ctx.outcome.testResults) do
      local id = test_result_position_id(tr)
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

  M._refresh_docked_output()
  return results
end

-- Stack-trace navigation for neotest's own output UI
-- (docs/done/lathe-test-output-streaming.md). Neither of neotest's output
-- surfaces (consumers/output.lua, consumers/output_panel/init.lua) backs its
-- buffer with the transcript file itself -- both render through
-- lib.ui.open_term(), a terminal-channel buffer. output.lua's floating
-- window is the one that reliably sets filetype=neotest-output on a fresh
-- buffer, so that FileType event -- not any correlation to the transcript
-- file's path -- is the hook. output_panel's persistent, shared buffer has
-- no equivalent per-run hook and is out of scope for now.

local STACKTRACE_NS = vim.api.nvim_create_namespace("lathe_stacktrace")
local STACKTRACE_HL = "LatheStackFrame"
vim.api.nvim_set_hl(0, STACKTRACE_HL, { link = "Underlined", default = true })

-- clear = true so re-requiring this module (e.g. a plugin-manager reload)
-- replaces rather than duplicates these autocmds, matching lathe.lua's own
-- LathePlugin augroup.
local STACKTRACE_AUGROUP = vim.api.nvim_create_augroup("LatheStacktrace", { clear = true })

local frame_locations = {}
local jump_keymaps_set = {}

-- Tracks the most recently entered window showing a Java buffer, so a jump
-- from the output window (float or split, both keyed off the same
-- FileType neotest-output hook) lands in the editor window the source was
-- already open in, instead of replacing the output window's own buffer --
-- there is no other way to recover "the window before this one" here: by
-- the time FileType neotest-output fires, output.lua has already finished
-- its own enter/restore focus handling (see its open_output()), so the
-- current window at that point is always the output window itself, not
-- whatever was focused beforehand.
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
  local location = locations and locations[row]
  if not location then
    return
  end

  if last_java_win and vim.api.nvim_win_is_valid(last_java_win) then
    vim.api.nvim_set_current_win(last_java_win)
  end

  vim.cmd("edit " .. vim.fn.fnameescape(vim.uri_to_fname(location.uri)))
  vim.api.nvim_win_set_cursor(0, { location.range.start.line + 1, location.range.start.character })
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

--- Underlines the `File.java:line` span of a resolved frame. `frame_line` is
--- one unwrap() entry: `text` is the rejoined logical line and `rows` are the
--- physical grid rows it spans. The span is located in the joined text, then
--- projected back onto the physical rows -- clamped to each row -- so it is
--- underlined even when a terminal wrap splits it across a row boundary, while
--- still marking only the file:line, not the whole frame. Returns whether any
--- extmark was placed.
local function highlight_frame_span(bufnr, frame_line, frame)
  local span = ("%s:%d"):format(frame.file, frame.line)
  local start = frame_line.text:find(span, 1, true)
  if not start then
    return false
  end

  local span_start = start - 1
  local span_end = span_start + #span
  local consumed = 0
  local placed = false
  for index, row in ipairs(frame_line.rows) do
    -- Segment against the row text captured at unwrap time, not a fresh
    -- nvim_buf_get_lines: the terminal buffer can reflow between the scan and
    -- here, and a re-read would no longer line up with frame_line.text.
    local row_len = #frame_line.texts[index]
    local seg_start = math.max(span_start, consumed)
    local seg_end = math.min(span_end, consumed + row_len)
    if seg_start < seg_end then
      pcall(vim.api.nvim_buf_set_extmark, bufnr, STACKTRACE_NS, row - 1, seg_start - consumed, {
        end_col = seg_end - consumed,
        hl_group = STACKTRACE_HL,
      })
      placed = true
    end

    consumed = consumed + row_len
  end

  return placed
end

-- The neotest-output terminal buffer renders asynchronously, so the FileType
-- hook can fire before the transcript is in the grid. Re-scan a few times until
-- a frame appears (or the buffer really has none) rather than decorating once
-- against empty content.
local DECORATE_MAX_ATTEMPTS = 12
local DECORATE_RETRY_MS = 50

--- Collects the frames in a neotest-output buffer, rejoining terminal-wrapped
--- rows first. Returns a list of { frame = <parse_frame result>, line = <unwrap
--- entry> }. The wrap width is the longest line in the buffer: the terminal
--- renders at whatever window was current when output was sent (a narrow split,
--- say), so it is NOT reliably vim.o.columns, but every wrapped row is exactly
--- that width, so the longest line reveals it empirically.
local function frames_in_buffer(bufnr)
  local raw = vim.api.nvim_buf_get_lines(bufnr, 0, -1, false)
  local width = 0
  for _, line in ipairs(raw) do
    width = math.max(width, #line)
  end

  local frames = {}
  for _, line in ipairs(stacktrace().unwrap(raw, width)) do
    local frame = stacktrace().parse_frame(line.text)
    if frame then
      frames[#frames + 1] = { frame = frame, line = line }
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

--- Scans one already-rendered neotest-output buffer for stack frames and
--- resolves each via the standard workspace/symbol request -- no new
--- server-side command, no metadata recorded when the transcript was
--- written; resolution works from the frame text alone (§4).
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

    -- Re-scan after the async resolution: a terminal buffer can reflow while
    -- the LSP request is in flight, so highlight against a fresh, self-
    -- consistent read rather than the frames captured before resolution.
    if not vim.api.nvim_buf_is_valid(bufnr) then
      return
    end

    local locations = {}
    frame_locations[bufnr] = locations
    for _, entry in ipairs(frames_in_buffer(bufnr)) do
      local location = resolved[frame_key(entry.frame)]
      if location then
        highlight_frame_span(bufnr, entry.line, entry.frame)
        for _, row in ipairs(entry.line.rows) do
          locations[row] = location
        end
      end
    end

    ensure_jump_keymaps(bufnr)
  end)
end

local MAX_OUTPUT_SPLIT_HEIGHT = 20
local output_split_win

local function output_split_is_open()
  return output_split_win ~= nil and vim.api.nvim_win_is_valid(output_split_win)
end

local function output_split_is_focused()
  return output_split_is_open() and vim.api.nvim_get_current_win() == output_split_win
end

--- Opens the last run's output in a docked split rather than output.lua's
--- default floating window -- same buffer, same terminal rendering, same
--- FileType-triggered stack-trace decoration above, only the window shape
--- differs. output.lua's own open() already supports this via opts.open_win;
--- this is a thin convenience wrapper, not a new output mechanism. Toggles:
--- a second call while that split is the current window closes it, matching
--- the toggle behavior of neotest's own output_panel.toggle().
function M.open_output(opts)
  if output_split_is_focused() then
    vim.api.nvim_win_close(output_split_win, true)
    output_split_win = nil
    return
  end

  local open_opts = vim.tbl_extend("force", { enter = true, last_run = true }, opts or {}, {
    open_win = function(win_opts)
      vim.cmd("botright split")
      vim.api.nvim_win_set_height(0, math.min(win_opts.height, MAX_OUTPUT_SPLIT_HEIGHT))
      output_split_win = vim.api.nvim_get_current_win()
      return output_split_win
    end,
  })

  neotest_api().output.open(open_opts)
end

local refresh_pending = false

--- Keeps the docked output split current across runs. output.lua only rebuilds
--- its buffer when it has no tracked window, so a split left open shows the
--- first run's output forever. When a run finishes (called from results()),
--- close the split and reopen it -- deferred one tick so neotest's last-run
--- bookkeeping has settled, coalesced so a multi-spec run refreshes once, and
--- with enter=false so focus stays in the editor. No-op when the split is
--- closed, so users who don't dock output are unaffected.
function M._refresh_docked_output()
  if refresh_pending or not output_split_is_open() then
    return
  end

  refresh_pending = true
  vim.schedule(function()
    refresh_pending = false
    if output_split_is_open() then
      pcall(vim.api.nvim_win_close, output_split_win, true)
      output_split_win = nil
      M.open_output({ enter = false })
    end
  end)
end

-- output.lua's scratch buffers are created via nvim_create_buf(false, true),
-- which defaults to bufhidden=hide, not wipe -- so closing the output window
-- hides the buffer but almost never actually wipes it, and BufWipeout alone
-- would leave these tables growing for the rest of the session. BufHidden
-- fires reliably the moment a buffer stops being shown in any window, which
-- is also the point these entries become unreachable (the jump keymaps are
-- buffer-local, so nothing can use them once hidden).
vim.api.nvim_create_autocmd({ "BufHidden", "BufWipeout" }, {
  group = STACKTRACE_AUGROUP,
  callback = function(ev)
    frame_locations[ev.buf] = nil
    jump_keymaps_set[ev.buf] = nil
  end,
})

vim.api.nvim_create_autocmd("FileType", {
  group = STACKTRACE_AUGROUP,
  pattern = "neotest-output",
  callback = function(ev)
    decorate_stack_frames(ev.buf)
  end,
})

return M
