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

-- Case 1: method -> class -> package. The package is deliberately not a
-- tracked node (it spans multiple files), so the class must fall through to
-- the file root directly, not get silently dropped because it's "grouped"
-- under the package's id in the parent-linking pass.
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
      kind = 3, -- TEST_PACKAGE, excluded from the forest entirely
      label = "demo",
      moduleRel = "demo",
      range = { start = { line = 0, character = 0 }, ["end"] = { line = 8, character = 1 } },
    },
  }

  local forest = adapter._build_position_forest(FILE, targets)
  local by_id = flatten(forest)

  spec.check("root is the file", forest[1].type, "file")
  spec.check("forest has file root + one top-level entry", #forest, 2)
  spec.check("package excluded from forest", by_id["demo"], nil)

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
-- still fall through to the file root (not vanish), the same fallback that
-- handles the package case above.
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
  spec.check(
    "nested class reachable despite untracked enclosing class",
    by_id["demo.Outer$Inner"] ~= nil,
    true
  )
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

spec.finish()
