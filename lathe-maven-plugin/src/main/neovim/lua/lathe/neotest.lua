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

local M = {}
M.name = "neotest-lathe"

local output_bufnr
local registered_output_handlers = {}

-- RunnableKind ordinal -> neotest position type / TestSelectionKind. lsp4j's Gson layer
-- serializes Java enums by ordinal, matching the LSP convention that kind fields like
-- SymbolKind/DiagnosticSeverity are integers (see dev/explore.py's identical handling).
-- Ordinals match RunnableKind's declaration order in lathe-server: MAIN, TEST_METHOD,
-- TEST_CLASS, TEST_PACKAGE. MAIN(0) has no entry: main-class replay isn't implemented
-- yet. TEST_PACKAGE(3) maps to "namespace" like a class does -- selectPackage resolves
-- against the real classpath at run time, not against whatever single file happened to
-- report the package, so a package position built from just one file's discovery still
-- runs every class in that package correctly; cross-file listing is covered separately
-- by neotest's own directory-position aggregation.
local POSITION_TYPE = { [1] = "test", [2] = "namespace", [3] = "namespace" }
local SELECTOR_KIND = { [1] = "METHOD", [2] = "CLASS", [3] = "PACKAGE" }

local function lathe_client()
  local clients = nio().lsp.get_clients({ name = "lathe" })
  return clients[1]
end

local function raw_lsp_client()
  local clients = vim.lsp.get_clients({ name = "lathe" })
  return clients[1]
end

local function append_output_line(bufnr, line)
  if not bufnr or not vim.api.nvim_buf_is_valid(bufnr) then
    return
  end

  local line_count = vim.api.nvim_buf_line_count(bufnr)
  local first_line = vim.api.nvim_buf_get_lines(bufnr, 0, 1, false)[1]
  if line_count == 1 and first_line == "" then
    vim.api.nvim_buf_set_lines(bufnr, 0, 1, false, { line })
    return
  end

  vim.api.nvim_buf_set_lines(bufnr, -1, -1, false, { line })
end

local function ensure_output_buffer()
  if output_bufnr and vim.api.nvim_buf_is_valid(output_bufnr) then
    return output_bufnr
  end

  output_bufnr = vim.api.nvim_create_buf(false, true)
  vim.bo[output_bufnr].buftype = "nofile"
  vim.bo[output_bufnr].bufhidden = "hide"
  vim.bo[output_bufnr].swapfile = false
  pcall(vim.api.nvim_buf_set_name, output_bufnr, "Lathe Test Output")
  return output_bufnr
end

local function scroll_output_windows(bufnr)
  for _, win in ipairs(vim.api.nvim_list_wins()) do
    if vim.api.nvim_win_get_buf(win) == bufnr then
      vim.api.nvim_win_set_cursor(win, { vim.api.nvim_buf_line_count(bufnr), 0 })
    end
  end
end

local function append_live_output_line(line)
  local bufnr = ensure_output_buffer()
  append_output_line(bufnr, line)
  scroll_output_windows(bufnr)
end

local function append_output_header(position_id)
  append_live_output_line("=== " .. position_id .. " ===")
end

local function register_test_output_handler()
  local client = raw_lsp_client()
  if not client then
    return
  end

  local id = client.id or client.name
  if registered_output_handlers[id] then
    return
  end

  client.handlers = client.handlers or {}
  client.handlers["lathe/testOutput"] = function(_err, result, _ctx, _config)
    if result and result.line ~= nil then
      append_live_output_line(result.line)
    end
  end
  registered_output_handlers[id] = true
end

function M.open_output()
  local bufnr = ensure_output_buffer()
  vim.cmd("botright split")
  vim.api.nvim_win_set_buf(0, bufnr)
  vim.api.nvim_win_set_cursor(0, { vim.api.nvim_buf_line_count(bufnr), 0 })
end

function M._append_output_line(bufnr, line)
  append_output_line(bufnr, line)
end

function M._append_output_header(position_id)
  append_output_header(position_id)
end

function M._output_buffer()
  return ensure_output_buffer()
end

pcall(vim.api.nvim_create_user_command, "LatheTestOutput", M.open_output, {
  desc = "Open Lathe live test output",
  force = true,
})

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

local function class_spec(pos, client)
  register_test_output_handler()
  append_output_header(pos.id)

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

  if pos.type ~= "file" then
    -- No direct run for dir positions; neotest breaks the tree down
    -- and calls build_spec on each child instead.
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
    return nil
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

--- neotest.Client:run_tree marks every id in the run's whole subtree as
--- "running" up front (client/init.lua's update_running, built from
--- tree:iter()) but only clears whichever ids results() returns -- a class
--- or package result naming just its own id leaves every descendant
--- method/class stuck showing "running" forever. There's no per-test
--- breakdown available (ReplayOutcome only carries an aggregate exit code
--- and raw output, not individual test results), so every descendant gets
--- the same aggregate status rather than none at all. Scoped to just the
--- run position's own subtree via tree:get_key(ctx.position_id), not the
--- whole tree parameter -- build_spec's file-run fan-out (one spec per
--- class) passes the same outer file tree to every class's results() call,
--- so resolving "everything in tree" would incorrectly stamp sibling
--- classes' methods with the wrong class's result.
function M.results(spec, _result, tree)
  local ctx = spec.context
  local result
  if ctx.err then
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

  local results = { [ctx.position_id] = result }
  local subtree = tree and tree:get_key(ctx.position_id)
  if subtree then
    for _, node in subtree:iter_nodes() do
      results[node:data().id] = results[node:data().id] or result
    end
  end
  return results
end

return M
