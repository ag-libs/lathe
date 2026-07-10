-- Verifies lathe.neotest's position-forest builder: the pure, neotest/nio-independent
-- transformation from lathe.runnables.list's flat, parentId-linked RunTarget list into
-- the nested-list shape neotest.types.Tree.from_list expects. Exercises exactly the bug
-- class found during manual validation: a class's real parent (its package) is
-- deliberately not a tracked node, and naively treating "grouped under some parentId key"
-- as "has a real parent" silently dropped every top-level class from the tree.
--
-- Self-contained: does not require neotest or nio to be installed. lathe.neotest itself
-- requires them lazily (only inside functions that actually talk to a live client), so
-- require('lathe.neotest') and _build_position_forest work without either on the
-- runtimepath -- this spec would otherwise break the build on any machine that doesn't
-- have the optional neotest plugin installed, since run-specs.sh runs every *_spec.lua
-- file unconditionally.
--
-- Run headless from the repo root (or via run-specs.sh, which runs every `*_spec.lua`
-- file in this directory the same way):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/neotest_spec.lua

local spec = require("spec_helper").new()
local adapter = require("lathe.neotest")

local FILE = "/workspace/demo/src/test/java/demo/FooTest.java"

--- Flattens the nested-list forest (Tree.from_list's expected shape: a leaf position
--- table, or `{position, child_subtree, ...}`) into a plain id -> position map, so
--- assertions can check reachability and fields without hand-walking the nesting.
local function flatten(forest)
  local by_id = {}
  local function visit(entry)
    if entry.id then
      by_id[entry.id] = entry
      return
    end
    by_id[entry[1].id] = entry[1]
    for i = 2, #entry do
      visit(entry[i])
    end
  end
  visit(forest)
  return by_id
end

-- Case 1: method -> class -> package. The package is a tracked node too:
-- selectPackage resolves against the real classpath at run time, not against
-- whichever single file happened to report it, so a package position built
-- from just this file's discovery still runs every class in that package
-- correctly once wired to the same "namespace" treatment a class gets.
do
  local targets = {
    {
      id = "demo.FooTest#bar_condition_result()",
      parentId = "demo.FooTest",
      kind = 1, -- TEST_METHOD
      label = "bar_condition_result",
      moduleRel = "demo",
      range = { start = { line = 5, character = 2 }, ["end"] = { line = 7, character = 3 } },
    },
    {
      id = "demo.FooTest",
      parentId = "demo",
      kind = 2, -- TEST_CLASS
      label = "FooTest",
      moduleRel = "demo",
      range = { start = { line = 2, character = 0 }, ["end"] = { line = 8, character = 1 } },
    },
    {
      id = "demo",
      parentId = "",
      kind = 3, -- TEST_PACKAGE
      label = "demo",
      moduleRel = "demo",
      range = { start = { line = 0, character = 0 }, ["end"] = { line = 8, character = 1 } },
    },
  }

  local forest = adapter._build_position_forest(FILE, targets)
  local by_id = flatten(forest)

  spec.check("root is the file", forest[1].type, "file")
  spec.check("forest has file root + one top-level entry", #forest, 2)

  local package_pos = by_id["demo"]
  spec.check("package reachable from forest", package_pos ~= nil, true)
  spec.check("package type", package_pos and package_pos.type, "namespace")
  spec.check("package selector kind", package_pos and package_pos.lathe_selector_kind, "PACKAGE")

  local class_pos = by_id["demo.FooTest"]
  spec.check("class reachable from forest", class_pos ~= nil, true)
  spec.check("class type", class_pos and class_pos.type, "namespace")
  spec.check("class selector kind", class_pos and class_pos.lathe_selector_kind, "CLASS")

  local method_pos = by_id["demo.FooTest#bar_condition_result()"]
  spec.check("method reachable from forest", method_pos ~= nil, true)
  spec.check("method type", method_pos and method_pos.type, "test")
  spec.check("method selector kind", method_pos and method_pos.lathe_selector_kind, "METHOD")
end

-- Case 2: nested class. Inner's real parent is Outer -- a class that itself
-- has no test of its own, so Outer is not a tracked node either. Inner must
-- still fall through to the file root (not vanish) rather than being
-- silently dropped for lacking a tracked parent.
do
  local targets = {
    {
      id = "demo.Outer$Inner#nested_condition_result()",
      parentId = "demo.Outer$Inner",
      kind = 1,
      label = "nested_condition_result",
      moduleRel = "demo",
      range = { start = { line = 4, character = 4 }, ["end"] = { line = 6, character = 5 } },
    },
    {
      id = "demo.Outer$Inner",
      parentId = "demo.Outer", -- Outer itself is never emitted: no test of its own
      kind = 2,
      label = "Inner",
      moduleRel = "demo",
      range = { start = { line = 3, character = 2 }, ["end"] = { line = 7, character = 3 } },
    },
  }

  local forest = adapter._build_position_forest(FILE, targets)
  local by_id = flatten(forest)

  spec.check("nested case: forest has file root + one top-level entry", #forest, 2)
  spec.check("nested class reachable despite untracked enclosing class", by_id["demo.Outer$Inner"] ~= nil, true)
end

-- is_test_file mirrors Surefire's own default include patterns
-- (Test*.java, *Test.java, *Tests.java, *TestCase.java).
do
  spec.check("Test*.java matches", adapter.is_test_file("/a/TestFoo.java"), true)
  spec.check("*Test.java matches", adapter.is_test_file("/a/FooTest.java"), true)
  spec.check("*Tests.java matches", adapter.is_test_file("/a/FooTests.java"), true)
  spec.check("*TestCase.java matches", adapter.is_test_file("/a/FooTestCase.java"), true)
  spec.check("plain class does not match", adapter.is_test_file("/a/Foo.java"), false)
  spec.check("non-.java file does not match", adapter.is_test_file("/a/FooTest.txt"), false)
end

local function read_file(path)
  local f = assert(io.open(path, "r"))
  local content = f:read("*a")
  f:close()
  return content
end

-- results() must always write neotest.Result.output as a path to a file
-- containing the real content, never raw text in the field itself.
do
  local POS_ID = "demo.FooTest#bar()"
  local function result_for(context)
    return adapter.results({ context = context })[POS_ID]
  end

  local passed = result_for({
    position_id = POS_ID,
    outcome = { launched = true, exitCode = 0, output = { "line one", "line two" } },
  })
  spec.check("passing result status", passed.status, "passed")
  spec.check("passing result output file content", read_file(passed.output), "line one\nline two")

  local failed = result_for({
    position_id = POS_ID,
    outcome = { launched = true, exitCode = 1, output = { "boom" } },
  })
  spec.check("failing result status", failed.status, "failed")
  spec.check("failing result output file content", read_file(failed.output), "boom")

  local blocked = result_for({
    position_id = POS_ID,
    outcome = { launched = false, blockedReasons = { "no runner jar" } },
  })
  spec.check("blocked result status", blocked.status, "failed")
  spec.check("blocked result output file content", read_file(blocked.output), "BLOCKED: no runner jar")

  local errored = result_for({ position_id = POS_ID, err = { message = "timeout" } })
  spec.check("errored result status", errored.status, "failed")
end

-- results() must resolve every descendant of the position actually run, not
-- just that position's own id. Reproduces the "stuck running forever" bug:
-- neotest.Client:run_tree marks every id in the run's whole subtree as
-- running up front (client/init.lua's update_running(adapter_id, root.id,
-- pos_ids), built from tree:iter()), but only clears whichever ids
-- results() returns -- so a class or package result that reports just its
-- own id leaves every sibling method/class stuck showing "running"
-- indefinitely. A minimal fake tree (get_key/iter_nodes only) reproduces
-- this without requiring the real neotest.types.Tree, keeping this spec
-- installable-neotest-independent like the rest of the file. Also proves
-- results() scopes correctly to just the run position's own subtree (via
-- tree:get_key), not the whole tree it's handed -- build_spec's file-run
-- fan-out passes the same outer file tree to every per-class results()
-- call, so naively resolving "everything in tree" would incorrectly stamp
-- sibling classes' methods with the wrong class's result.
do
  local function fake_tree(nodes_by_id)
    local function node_for(id)
      return {
        data = function()
          return nodes_by_id[id]
        end,
        iter_nodes = function()
          local ids = {}
          local function collect(i)
            table.insert(ids, i)
            for _, child_id in ipairs(nodes_by_id[i].children or {}) do
              collect(child_id)
            end
          end
          collect(id)
          local idx = 0
          return function()
            idx = idx + 1
            if ids[idx] then
              return idx, node_for(ids[idx])
            end
          end
        end,
      }
    end
    return {
      get_key = function(_, id)
        return nodes_by_id[id] and node_for(id) or nil
      end,
    }
  end

  local nodes = {
    ["demo.FooTest"] = { id = "demo.FooTest", children = { "demo.FooTest#a()", "demo.FooTest#b()" } },
    ["demo.FooTest#a()"] = { id = "demo.FooTest#a()", children = {} },
    ["demo.FooTest#b()"] = { id = "demo.FooTest#b()", children = {} },
    ["demo.OtherTest"] = { id = "demo.OtherTest", children = { "demo.OtherTest#c()" } },
    ["demo.OtherTest#c()"] = { id = "demo.OtherTest#c()", children = {} },
  }
  local tree = fake_tree(nodes)

  local results = adapter.results({
    context = {
      position_id = "demo.FooTest",
      outcome = { launched = true, exitCode = 0, output = { "ok" } },
    },
  }, nil, tree)

  local method_a = results["demo.FooTest#a()"]
  spec.check("class result present", results["demo.FooTest"] ~= nil, true)
  spec.check("sibling method a resolved, not stuck running", method_a ~= nil, true)
  spec.check("sibling method b resolved, not stuck running", results["demo.FooTest#b()"] ~= nil, true)
  spec.check("method a inherits class status", method_a and method_a.status, "passed")
  spec.check("unrelated class in the same outer tree left untouched", results["demo.OtherTest"], nil)
  spec.check("unrelated class's method left untouched", results["demo.OtherTest#c()"], nil)
end

-- root() resolves the nearest .lathe marker walking up from a nested path,
-- the same fixture-building approach as root_spec.lua's own coverage of
-- lathe.get_root -- this is neotest's own project-root hook, a separate
-- entry point from that one, so it gets its own direct check.
do
  local work = vim.fn.tempname()
  local project = work .. "/project"
  vim.fn.mkdir(project .. "/src/test/java/demo", "p")
  local marker = io.open(project .. "/.lathe", "w")
  marker:write("")
  marker:close()

  local nested = project .. "/src/test/java/demo/FooTest.java"
  spec.check("root() finds the marked root from a nested path", adapter.root(nested), project)
  spec.check("root() returns nil with no marker above", adapter.root(work), nil)

  vim.fn.delete(work, "rf")
end

-- filter_dir() prunes build output and VCS/workspace-metadata directories
-- from neotest's workspace-wide discovery walk.
do
  spec.check("filter_dir excludes target", adapter.filter_dir("target", "app/target", "/root"), false)
  spec.check("filter_dir excludes .lathe", adapter.filter_dir(".lathe", ".lathe", "/root"), false)
  spec.check("filter_dir excludes .git", adapter.filter_dir(".git", ".git", "/root"), false)
  spec.check("filter_dir keeps src", adapter.filter_dir("src", "app/src", "/root"), true)
end

spec.finish()
