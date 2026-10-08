-- Verifies lathe.run._save_target_for: the pure resolution of which runnable :LatheRunSave turns
-- into a config save request for a given cursor line, over the flat lathe.runnables.list list.
-- Exercises the main, test-method, test-class, class-gutter, single-main-fallback, and nothing cases.
--
-- Self-contained like run_spec.lua; run-specs.sh runs every *_spec.lua unconditionally.

local spec = require("spec_helper").new()
local run = require("lathe.run")

local MAIN = 0
local TEST_METHOD = 1
local TEST_CLASS = 2
local MAIN_CLASS = 4

local function target(kind, fqcn, start_line, end_line)
  local id = fqcn .. "#main"
  if kind == MAIN_CLASS or kind == TEST_CLASS then
    id = fqcn
  elseif kind == TEST_METHOD then
    id = fqcn .. "#test()"
  end
  return {
    id = id,
    parentId = fqcn,
    kind = kind,
    moduleRel = "app",
    range = {
      start = { line = start_line, character = 2 },
      ["end"] = { line = end_line, character = 3 },
    },
  }
end

-- Case 1: cursor in a main -> a MAIN save pinning the class.
do
  local req = run._save_target_for({ target(MAIN, "com.example.App", 10, 14) }, 12)
  spec.check("main -> kind", req and req.kind, "MAIN")
  spec.check("main -> mainClass", req and req.mainClass, "com.example.App")
end

-- Case 2: cursor in a test method -> a TEST save with a METHOD selector.
do
  local targets = {
    target(TEST_CLASS, "com.example.AppTest", 5, 20),
    target(TEST_METHOD, "com.example.AppTest", 8, 12),
  }
  local req = run._save_target_for(targets, 9)
  spec.check("test method -> kind", req and req.kind, "TEST")
  spec.check("test method -> selectorKind", req and req.selectors[1].selectorKind, "METHOD")
  spec.check("test method -> selectorValue", req and req.selectors[1].selectorValue, "com.example.AppTest#test()")
end

-- Case 3: cursor on a test class (not a method) -> a TEST save with a CLASS selector.
do
  local targets = {
    target(TEST_CLASS, "com.example.AppTest", 5, 20),
    target(TEST_METHOD, "com.example.AppTest", 8, 12),
  }
  local req = run._save_target_for(targets, 6)
  spec.check("test class -> selectorKind", req and req.selectors[1].selectorKind, "CLASS")
  spec.check("test class -> selectorValue", req and req.selectors[1].selectorValue, "com.example.AppTest")
end

-- Case 4: cursor on the main class declaration resolves to that class's main.
do
  local targets = {
    target(MAIN, "com.example.App", 12, 14),
    target(MAIN_CLASS, "com.example.App", 10, 20),
  }
  local req = run._save_target_for(targets, 10)
  spec.check("class gutter -> mainClass", req and req.mainClass, "com.example.App")
end

-- Case 5: single main, cursor outside its range -> still saveable (the file's only main).
do
  local req = run._save_target_for({ target(MAIN, "com.example.App", 10, 14) }, 0)
  spec.check("single main fallback -> mainClass", req and req.mainClass, "com.example.App")
end

-- Case 6: nothing runnable under the cursor and no single main -> nil (refuse, write nothing).
do
  local targets = {
    target(MAIN, "com.example.A", 10, 14),
    target(MAIN, "com.example.B", 20, 24),
  }
  spec.check("ambiguous, cursor on none -> nil", run._save_target_for(targets, 0), nil)
end

spec.finish("run_save_spec")
