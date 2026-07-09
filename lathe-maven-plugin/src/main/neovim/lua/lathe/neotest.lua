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

-- RunnableKind ordinal -> neotest position type / TestSelectionKind. lsp4j's Gson layer
-- serializes Java enums by ordinal, matching the LSP convention that kind fields like
-- SymbolKind/DiagnosticSeverity are integers (see dev/explore.py's identical handling).
-- Ordinals match RunnableKind's declaration order in lathe-server: MAIN, TEST_METHOD,
-- TEST_CLASS, TEST_PACKAGE. MAIN(0) and TEST_PACKAGE(3) have no entry: a package spans
-- multiple files so it has no place in a single file's position tree, and main-class
-- replay isn't implemented yet.
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

function M.is_test_file(file_path)
  if not file_path:match("%.java$") then
    return false
  end
  local name = vim.fn.fnamemodify(file_path, ":t:r")
  return name:match("Test$") ~= nil or name:match("^Test") ~= nil or name:match("Tests$") ~= nil
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
--- being installed. Any position whose parent isn't itself a node in this file (a
--- package, or an intermediate class with no tests of its own) attaches directly
--- under the file root -- packages deliberately have no node here since they span
--- multiple files.
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
    -- parent is itself a tracked node; otherwise (parent is a package, which
    -- spans multiple files and is deliberately untracked, or an intermediate
    -- class with no tests of its own) it attaches directly to the file root.
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

function M.build_spec(args)
  local pos = args.tree:data()
  if pos.type ~= "test" and pos.type ~= "namespace" then
    -- No direct run for file/dir positions; neotest breaks the tree down
    -- and calls build_spec on each child instead.
    return nil
  end

  local client = lathe_client()
  if not client then
    return nil
  end

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

function M.results(spec, _result, _tree)
  local ctx = spec.context
  if ctx.err then
    return {
      [ctx.position_id] = {
        status = "failed",
        short = "lathe.run.test error: " .. vim.inspect(ctx.err),
      },
    }
  end

  local outcome = ctx.outcome
  if not outcome.launched then
    return {
      [ctx.position_id] = {
        status = "failed",
        short = "BLOCKED: " .. table.concat(outcome.blockedReasons or {}, "; "),
      },
    }
  end

  return {
    [ctx.position_id] = {
      status = outcome.exitCode == 0 and "passed" or "failed",
      short = "exit=" .. tostring(outcome.exitCode),
    },
  }
end

return M
