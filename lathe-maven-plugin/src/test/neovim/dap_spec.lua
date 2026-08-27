-- Verifies lathe.dap:
--   * _test_target_for: the pure selection of which TEST runnable a :LatheDebug should attach to
--     for a given cursor line, over the flat lathe.runnables.list target list -- method-in-range,
--     class fallback, package fallback, innermost precedence, and the no-test case.
--   * _decorate_on_session_end: the DAP session-end guard that hyperlinks the streamed stack frames
--     only for a Lathe session (dap.listeners are global, so other adapters must not trigger it).
--
-- Self-contained: lathe.dap requires only lathe.output and lathe.stackdecorate, both core Neovim
-- APIs (no nvim-dap/nio), so this loads on any machine -- run-specs.sh runs every *_spec.lua
-- unconditionally. The session-end cases stub lathe.stackdecorate with a call spy so the guard is
-- asserted synchronously; the real decoration is covered by stackdecorate_spec. The stub is
-- injected before requiring lathe.dap (which binds the module at load) and is isolated because each
-- spec runs in its own nvim process.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/dap_spec.lua

local spec = require("spec_helper").new()

local decorate_calls = 0
package.loaded["lathe.stackdecorate"] = {
  decorate_live_output = function()
    decorate_calls = decorate_calls + 1
  end,
}

local dap = require("lathe.dap")

local TEST_METHOD = 1
local TEST_CLASS = 2
local TEST_PACKAGE = 3

--- Builds a runnable target with a range, mirroring the RunTarget wire shape (0-based LSP range on
--- start/end objects). The id doubles as the selectorValue lathe.debug.test receives.
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

-- Case 6: the resolved test selection maps the kind ordinal to the server's TestSelectionKind name.
do
  local target_class = target(TEST_CLASS, "com.example.AppTest", 8, 20)
  local config = dap._test_config_for(target_class)
  spec.check("test config selectorKind", config.lathe_selections[1].selectorKind, "CLASS")
  spec.check("test config selectorValue", config.lathe_selections[1].selectorValue, "com.example.AppTest")
  spec.check("test config moduleRel", config.lathe_module_rel, "app")
  spec.check("test config request", config.request, "attach")
end

-- Case 7: a MAIN target (from lathe.run's discovery) builds a main config carrying the class, not a
-- selection -- lathe_main_class is what routes the adapter to lathe.debug.main.
do
  local main = { parentId = "com.example.App", moduleRel = "app", kind = 0 }
  local config = dap._main_config_for(main)
  spec.check("main config mainClass", config.lathe_main_class, "com.example.App")
  spec.check("main config moduleRel", config.lathe_module_rel, "app")
  spec.check("main config no selections", config.lathe_selections, nil)
end

-- Case 8: a Lathe debug session ending triggers decoration of the shared output buffer -- the debug
-- twin of the run/test completion hooks.
do
  decorate_calls = 0
  dap._decorate_on_session_end({ config = { type = "lathe" } })
  spec.check("lathe session end -> decorate", decorate_calls, 1)
end

-- Case 9: a non-Lathe session must not decorate, since dap.listeners are registered globally.
do
  decorate_calls = 0
  dap._decorate_on_session_end({ config = { type = "python" } })
  spec.check("non-lathe session end -> no decorate", decorate_calls, 0)
end

-- Case 10: a malformed session event (no config) is ignored rather than erroring.
do
  decorate_calls = 0
  dap._decorate_on_session_end({})
  spec.check("session without config -> no decorate", decorate_calls, 0)
end

spec.finish("dap_spec")
