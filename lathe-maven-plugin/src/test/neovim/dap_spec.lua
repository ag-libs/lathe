-- Verifies lathe.dap._test_target_for: the pure selection of which TEST runnable a :LatheDebug
-- should attach to for a given cursor line, over the flat lathe.runnables.list target list.
-- Exercises method-in-range, class fallback, package fallback, innermost precedence, and the
-- no-test case.
--
-- Self-contained: lathe.dap requires only lathe.output, which uses core Neovim APIs (no
-- nvim-dap/nio), so this loads on any machine -- run-specs.sh runs every *_spec.lua
-- unconditionally.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/dap_spec.lua

local spec = require("spec_helper").new()
local dap = require("lathe.dap")

local TEST_METHOD = 1
local TEST_CLASS = 2
local TEST_PACKAGE = 3

--- Builds a runnable target with a range, mirroring the RunTarget wire shape (0-based LSP range on
--- start/end objects). The id doubles as the selectorValue lathe.debug.start receives.
local function target(kind, id, start_line, end_line)
  return {
    id = id,
    parentId = "com.example.AppTest",
    kind = kind,
    label = id,
    moduleRel = "app",
    uri = "file:///ws/app/src/test/java/x/AppTest.java",
    range = {
      start = { line = start_line, character = 2 },
      ["end"] = { line = end_line, character = 3 },
    },
  }
end

-- Case 1: cursor inside a test method's range selects that method.
do
  local targets = { target(TEST_METHOD, "com.example.AppTest#a()", 10, 12) }
  local picked = dap._test_target_for(targets, 11)
  spec.check("cursor in method -> that method", picked and picked.id, "com.example.AppTest#a()")
end

-- Case 2: cursor on the class line (outside any method) selects the class.
do
  local targets = {
    target(TEST_METHOD, "com.example.AppTest#a()", 10, 12),
    target(TEST_CLASS, "com.example.AppTest", 8, 20),
  }
  local picked = dap._test_target_for(targets, 8)
  spec.check("cursor on class line -> the class", picked and picked.kind, TEST_CLASS)
end

-- Case 3: method precedence -- cursor on the method line, enclosed by the class range, still
-- returns the method (the innermost target wins).
do
  local targets = {
    target(TEST_METHOD, "com.example.AppTest#a()", 10, 12),
    target(TEST_CLASS, "com.example.AppTest", 8, 20),
  }
  local picked = dap._test_target_for(targets, 11)
  spec.check("method precedence over class", picked and picked.kind, TEST_METHOD)
end

-- Case 4: only a package target contains the cursor -- fall back to the package.
do
  local targets = { target(TEST_PACKAGE, "com.example", 0, 40) }
  local picked = dap._test_target_for(targets, 5)
  spec.check("only package in range -> package", picked and picked.kind, TEST_PACKAGE)
end

-- Case 5: no target contains the cursor -- select nothing rather than guess.
do
  local targets = { target(TEST_METHOD, "com.example.AppTest#a()", 10, 12) }
  spec.check("cursor outside all -> nil", dap._test_target_for(targets, 0), nil)
end

-- Case 6: the resolved selection maps the kind ordinal to the server's TestSelectionKind name.
do
  local target_class = target(TEST_CLASS, "com.example.AppTest", 8, 20)
  local config = dap._config_for(target_class)
  spec.check("config selectorKind", config.lathe_selections[1].selectorKind, "CLASS")
  spec.check("config selectorValue", config.lathe_selections[1].selectorValue, "com.example.AppTest")
  spec.check("config moduleRel", config.lathe_module_rel, "app")
  spec.check("config request", config.request, "attach")
end

spec.finish("dap_spec")
