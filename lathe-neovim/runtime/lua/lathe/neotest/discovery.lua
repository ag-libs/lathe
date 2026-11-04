-- How the adapter finds tests: the position tree neotest shows for a file, from the server's
-- runnables for an open file or its module discovery for one nobody opened, and which directories
-- neotest skips.
local common = require("lathe.neotest.common")
local readiness = require("lathe.neotest.readiness")

local M = {}

local POSITION_TYPE = { [common.TEST_METHOD_KIND] = "test", [common.TEST_CLASS_KIND] = "namespace" }
local SELECTOR_KIND = { [common.TEST_METHOD_KIND] = "METHOD", [common.TEST_CLASS_KIND] = "CLASS" }
local DISCOVERY_AUGROUP = vim.api.nvim_create_augroup("LatheNeotestDiscovery", { clear = true })

-- Buffers whose discovery already returned tests: neotest has their positions, so they need no
-- nudge. The nudge re-fires BufAdd, which also makes neotest rescan the file's whole directory, so
-- a rename that opens many test files would otherwise rescan each directory once per file.
local discovered = {}
-- Neotest rescans a file's whole directory whenever a buffer in it is added, so opening many test
-- files at once (a rename) asks for each file dozens of times. Repeats are answered from the last
-- discovery while the buffer is unchanged, and concurrent requests for a file share one.
local discovery_cache = {}
local discovery_inflight = {}

--- Records that `bufnr`'s discovery returned tests. A test seam: discover_positions needs nio and a
--- live client.
function M._record_discovered(bufnr)
  discovered[bufnr] = true
end

--- Nudges neotest to (re)discover `buf`'s file. neotest re-discovers a file on BufAdd, but does not
--- fire that discovery on the real open when its initial scan happened before the server was ready;
--- re-emitting BufAdd for the buffer runs neotest's own _update_positions -> discover_positions
--- with the server now able to answer. BufAdd (not BufWritePost) so there are no write/format
--- semantics, and it is scoped to the single buffer.
local function nudge_discovery(buf)
  if not vim.api.nvim_buf_is_valid(buf) then
    return
  end

  vim.api.nvim_exec_autocmds("BufAdd", { buffer = buf })
end

-- The server publishes diagnostics only after its open-mode compile, so the first DiagnosticChanged
-- for a Lathe-attached buffer means the doc is open on the server: mark it (a racing
-- discover_positions then pulls warm), and nudge neotest to discover the file now that the server
-- can answer, unless its discovery already returned tests.
vim.api.nvim_create_autocmd("DiagnosticChanged", {
  group = DISCOVERY_AUGROUP,
  callback = function(ev)
    if readiness.is_open_confirmed(ev.buf) then
      return
    end

    if #vim.lsp.get_clients({ name = "lathe", bufnr = ev.buf }) == 0 then
      return
    end

    readiness.confirm_open(ev.buf)
    local name = vim.api.nvim_buf_get_name(ev.buf)
    if name ~= "" and common.is_test_file(name) and not discovered[ev.buf] then
      common.debug_log("[discover] " .. name .. " open on server -> nudging neotest re-discovery")
      -- Defer out of the DiagnosticChanged callback before firing another autocmd.
      vim.schedule(function()
        nudge_discovery(ev.buf)
      end)
    end
  end,
})

vim.api.nvim_create_autocmd({ "LspDetach", "BufDelete" }, {
  group = DISCOVERY_AUGROUP,
  callback = function(ev)
    discovered[ev.buf] = nil
    discovery_cache[vim.api.nvim_buf_get_name(ev.buf)] = nil
  end,
})

--- The targets the buffer named `file_path` last discovered, or nil.
function M.cached_targets(file_path)
  local cached = discovery_cache[file_path]
  return cached and cached.targets
end

function M.root(dir)
  return vim.fs.root(dir, ".lathe")
end

-- Each module's top-level build directory, root-relative, from workspace.json's output map: the
-- front-end lists what its build writes, so `app/target/classes` (module "app") prunes `app/target`
-- and a Gradle `lib/build/classes/java/main` prunes `lib/build`. Read once per root.
local build_dirs_cache = {}

local function read_build_dirs(root)
  local manifest = require("lathe.json").read(vim.fs.joinpath(root, ".lathe", "workspace.json"))
  local dirs = {}
  for _, output in ipairs(manifest and manifest.outputs or {}) do
    local prefix = (output.module or "") == "" and "" or (output.module .. "/")
    local build_dir = vim.startswith(output.path, prefix)
      and output.path:sub(#prefix + 1):match("^[^/]+")
    if build_dir then
      dirs[prefix .. build_dir] = true
    end
  end

  return dirs
end

local function build_dirs(root)
  build_dirs_cache[root] = build_dirs_cache[root] or read_build_dirs(root)
  return build_dirs_cache[root]
end

function M.filter_dir(name, rel_path, root)
  if name == ".lathe" or name == ".git" then
    return false
  end

  return not build_dirs(root)[rel_path]
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
  -- root_list is { file_root, ...positions }; length 1 means the file had only excluded targets
  -- (a main/main-class, no test or namespace). Return nil so it is not shown as an empty test node
  -- (and not stamped by a package run's fan-out) rather than a childless file tree.
  if #root_list <= 1 then
    return nil
  end
  return common.Tree().from_list(root_list, function(pos)
    return pos.id
  end)
end

-- The module a file belongs to, from the server's reactor layout; nil when it resolves to none.
function M.module_of(client, file_path)
  local err, context = client.request.workspace_executeCommand({
    command = "lathe.resolveContext",
    arguments = { { uri = vim.uri_from_fname(file_path) } },
  })
  -- A file in no module answers JSON null (vim.NIL), which cannot be indexed.
  return not err and type(context) == "table" and context.moduleRel or nil
end

-- Discovery knows no positions, so a file's tests sit at its top until the file is opened.
local TOP_OF_FILE = { start = { line = 0, character = 0 }, ["end"] = { line = 0, character = 0 } }

--- The tests discovery found in one file, as the targets lathe.runnables.list returns for an open
--- file: a test per method, and its class and every class it is nested in (read off the id), so the
--- same forest builder nests them. Pure, for the specs.
function M._targets_from_discovery(tests, file_path, module_rel)
  local targets, classes = {}, {}
  local file = vim.fs.normalize(file_path)
  for _, test in ipairs(tests or {}) do
    if test.uri ~= "" and vim.fs.normalize(vim.uri_to_fname(test.uri)) == file then
      local class = common.class_of(test.id)
      targets[#targets + 1] = {
        id = test.id,
        parentId = class,
        kind = common.TEST_METHOD_KIND,
        label = test.label,
        moduleRel = module_rel,
        range = TOP_OF_FILE,
      }
      while class and not classes[class] do
        classes[class] = true
        local outer = common.enclosing_class(class)
        targets[#targets + 1] = {
          id = class,
          parentId = outer or class:match("^(.*)%.[^.]+$") or "",
          kind = common.TEST_CLASS_KIND,
          label = class:match("([^.$]+)$"),
          moduleRel = module_rel,
          range = TOP_OF_FILE,
        }
        class = outer
      end
    end
  end
  return targets
end

-- A file nobody opened: its tests from the server's discovery of its whole module, which runs no
-- test and is cached server-side, so neotest's project sweep costs one discovery per module.
local function discovered_targets(file_path)
  local client = common.client()
  local module_rel = client and M.module_of(client, file_path)
  if not module_rel then
    return nil
  end

  local err, discovery = client.request.workspace_executeCommand({
    command = "lathe.tests.discover",
    arguments = { { moduleRel = module_rel } },
  })
  if err or not discovery then
    return nil
  end

  local targets = M._targets_from_discovery(discovery.tests, file_path, module_rel)
  return #targets > 0 and targets or nil
end

--- Asks the server for `file_path`'s runnables, or nil when there is no client or no targets; also
--- returns the buffer's changedtick as of the request (nil for an unloaded file).
local function request_targets(file_path, bufnr)
  local client = common.client()
  if not client then
    common.debug_log("[discover] " .. file_path .. " client=absent → nil")
    return nil
  end

  -- The file the user actually opened is a loaded buffer whose didOpen may still be in flight; the
  -- server answers runnables only for an open doc, so wait for its server-confirmed open before
  -- pulling -- otherwise this single discovery races didOpen and neotest caches "no tests" until an
  -- edit. An unopened file (neotest's project sweep) is not loaded here, so it skips the wait and
  -- is discovered against the server as-is (empty until it too is opened).
  if vim.api.nvim_buf_is_loaded(bufnr) then
    readiness.await_open_confirmed(bufnr)
    common.debug_log(
      "[discover] "
        .. file_path
        .. " open-confirmed="
        .. tostring(readiness.is_open_confirmed(bufnr))
    )
  end

  local tick = vim.api.nvim_buf_is_loaded(bufnr) and vim.api.nvim_buf_get_changedtick(bufnr) or nil
  local err, targets = client.request.workspace_executeCommand({
    command = "lathe.runnables.list",
    arguments = { { uri = vim.uri_from_fname(file_path) } },
  }, bufnr)
  if err or not targets or #targets == 0 then
    return nil
  end

  return targets, tick
end

function M.discover_positions(file_path)
  -- Gate on workspace readiness so an early discovery suspends until the server has loaded, rather
  -- than racing it and caching "no tests" (D1).
  readiness.await_ready()

  local bufnr = vim.fn.bufadd(file_path)
  local tick = vim.api.nvim_buf_is_loaded(bufnr) and vim.api.nvim_buf_get_changedtick(bufnr) or nil
  local cached = discovery_cache[file_path]
  if tick and cached and cached.tick == tick then
    return build_tree(file_path, cached.targets)
  end

  local pending = discovery_inflight[file_path]
  if pending then
    local targets = pending.wait()
    return targets and build_tree(file_path, targets) or nil
  end

  -- Not opened: the module's discovery; the file is not marked discovered, so opening it still
  -- re-discovers it from the open file, with real ranges and unsaved edits.
  if not vim.api.nvim_buf_is_loaded(bufnr) then
    local targets = discovered_targets(file_path)
    return targets and build_tree(file_path, targets) or nil
  end

  local future = common.nio().control.future()
  discovery_inflight[file_path] = future
  local targets, requested_tick = request_targets(file_path, bufnr)
  discovery_inflight[file_path] = nil
  future.set(targets or false)
  if not targets then
    return nil
  end

  if requested_tick then
    discovery_cache[file_path] = { tick = requested_tick, targets = targets }
  end

  M._record_discovered(bufnr)
  return build_tree(file_path, targets)
end

return M
