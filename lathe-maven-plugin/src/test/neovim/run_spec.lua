-- Verifies lathe.run._main_target_for: the pure selection of which MAIN runnable a :LatheRun
-- should launch for a given cursor line, over the flat lathe.runnables.list target list.
-- Exercises the cursor-in-range, single-main fallback, no-main, and ambiguous-multiple cases.
--
-- Self-contained: lathe.run requires only lathe.output, which uses core Neovim APIs (no
-- neotest/nio), so this loads on any machine -- run-specs.sh runs every *_spec.lua
-- unconditionally.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/run_spec.lua

local spec = require("spec_helper").new()
local run = require("lathe.run")

local MAIN = 0
local TEST_METHOD = 1
local MAIN_CLASS = 4

--- Builds a runnable target with a range, mirroring the RunTarget wire shape (0-based LSP range on
--- start/end objects). MAIN_CLASS carries id == fqcn and ranges the class; MAIN carries fqcn#main
--- and parentId == fqcn, so a MAIN_CLASS hit resolves to its MAIN via that link.
local function target(kind, fqcn, start_line, end_line)
  local id = fqcn .. "#main"
  if kind == MAIN_CLASS then
    id = fqcn
  elseif kind == TEST_METHOD then
    id = fqcn .. "#test()"
  end
  return {
    id = id,
    parentId = fqcn,
    kind = kind,
    label = kind == MAIN and "main" or "class",
    moduleRel = "app",
    uri = "file:///ws/app/src/main/java/x/" .. fqcn .. ".java",
    range = {
      start = { line = start_line, character = 2 },
      ["end"] = { line = end_line, character = 3 },
    },
  }
end

-- Case 1: cursor inside a main's declaration range selects that main.
do
  local targets = { target(MAIN, "com.example.App", 10, 14) }
  local picked = run._main_target_for(targets, 12)
  spec.check("cursor in range -> that main", picked and picked.parentId, "com.example.App")
end

-- Case 2: a single main with the cursor outside its range still runs (the file's only main).
do
  local targets = { target(MAIN, "com.example.App", 10, 14) }
  local picked = run._main_target_for(targets, 0)
  spec.check("single main, cursor outside -> fallback", picked and picked.parentId, "com.example.App")
end

-- Case 3: no main targets (a test-only file) selects nothing -- :LatheRun defers to neotest.
do
  local targets = {
    target(TEST_METHOD, "com.example.AppTest", 5, 9),
    target(TEST_METHOD, "com.example.AppTest", 11, 15),
  }
  spec.check("no main -> nil", run._main_target_for(targets, 6), nil)
end

-- Case 4: several mains with the cursor on none is ambiguous -- select nothing rather than guess.
do
  local targets = {
    target(MAIN, "com.example.A", 10, 14),
    target(MAIN, "com.example.B", 20, 24),
  }
  spec.check("multiple mains, cursor on none -> nil", run._main_target_for(targets, 0), nil)
end

-- Case 5: several mains, cursor inside one, picks exactly that one.
do
  local targets = {
    target(MAIN, "com.example.A", 10, 14),
    target(MAIN, "com.example.B", 20, 24),
  }
  local picked = run._main_target_for(targets, 22)
  spec.check("multiple mains, cursor in B -> B", picked and picked.parentId, "com.example.B")
end

-- Case 6: cursor on the class declaration (in the class range, not the method) resolves to that
-- class's main method -- so :LatheRun works from the class gutter, not just the method.
do
  local targets = {
    target(MAIN, "com.example.App", 12, 14),
    target(MAIN_CLASS, "com.example.App", 10, 20),
  }
  local picked = run._main_target_for(targets, 10)
  spec.check("cursor on class line -> its main", picked and picked.id, "com.example.App#main")
end

-- Case 7: cursor on the method line still returns the method target (method range wins, since the
-- class range encloses it).
do
  local targets = {
    target(MAIN, "com.example.App", 12, 14),
    target(MAIN_CLASS, "com.example.App", 10, 20),
  }
  local picked = run._main_target_for(targets, 13)
  spec.check("method precedence over class", picked and picked.kind, MAIN)
end

-- Case 8: several mains, cursor on class B's declaration resolves to B's main (not ambiguous).
do
  local targets = {
    target(MAIN, "com.example.A", 12, 14),
    target(MAIN_CLASS, "com.example.A", 10, 20),
    target(MAIN, "com.example.B", 32, 34),
    target(MAIN_CLASS, "com.example.B", 30, 40),
  }
  local picked = run._main_target_for(targets, 30)
  spec.check("cursor on class B -> B's main", picked and picked.id, "com.example.B#main")
end

spec.finish("run_spec")
