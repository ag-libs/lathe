-- The server's runnables on the client: the kinds a lathe.runnables.file target carries, which one
-- sits under the cursor, and fetching a file's targets. Run, debug and the neotest adapter share
-- it, so they agree on what is where.
local util = require("lathe.util")
local commands = require("lathe.commands")

local M = {}

-- RunnableKind ordinals (lsp4j serializes the enum by ordinal): MAIN is the main method and
-- MAIN_CLASS its enclosing class declaration, both launching the same class; TEST_* are tests.
M.KIND = {
  MAIN = 0,
  TEST_METHOD = 1,
  TEST_CLASS = 2,
  TEST_PACKAGE = 3,
  MAIN_CLASS = 4,
}

-- The server's TestSelectionKind name for each test kind, as lathe.run.test and lathe.debug.test
-- take it.
M.SELECTOR_KIND = {
  [M.KIND.TEST_METHOD] = "METHOD",
  [M.KIND.TEST_CLASS] = "CLASS",
  [M.KIND.TEST_PACKAGE] = "PACKAGE",
}

-- Innermost first: a method range wins over its class, which wins over the package.
local TEST_PRECEDENCE = { M.KIND.TEST_METHOD, M.KIND.TEST_CLASS, M.KIND.TEST_PACKAGE }

--- True when `line` (0-based) lies within an LSP `range`.
function M.in_range(range, line)
  return range ~= nil and line >= range.start.line and line <= range["end"].line
end

--- The MAIN (method) target to launch for a cursor position: the main whose method range contains
--- `cursor_line` (0-based); else the main whose class range contains it (running from the class
--- gutter); else the file's only main if there is exactly one; else nil (no main, or several with
--- the cursor on none). A MAIN_CLASS hit resolves to its class's method (id == the method's
--- parentId), so the launch path is the same.
function M.main_target_for(targets, cursor_line)
  local mains = {}
  local main_by_class = {}
  for _, t in ipairs(targets) do
    if t.kind == M.KIND.MAIN then
      mains[#mains + 1] = t
      main_by_class[t.parentId] = t
    end
  end

  -- Method range wins when the cursor is on the method line (the class range encloses it).
  for _, t in ipairs(mains) do
    if M.in_range(t.range, cursor_line) then
      return t
    end
  end

  for _, t in ipairs(targets) do
    if t.kind == M.KIND.MAIN_CLASS and M.in_range(t.range, cursor_line) then
      local method = main_by_class[t.id]
      if method then
        return method
      end
    end
  end

  if #mains == 1 then
    return mains[1]
  end

  return nil
end

--- The TEST target for a cursor position: the innermost one (method, then class, then package)
--- whose range contains `cursor_line` (0-based); else nil.
function M.test_target_for(targets, cursor_line)
  for _, kind in ipairs(TEST_PRECEDENCE) do
    for _, t in ipairs(targets) do
      if t.kind == kind and M.in_range(t.range, cursor_line) then
        return t
      end
    end
  end

  return nil
end

--- Fetches the buffer's runnables and calls `on_targets(targets, cursor_line)` on the main loop,
--- with the cursor's 0-based line read now; a failed request counts as no targets.
function M.at_cursor(client, bufnr, on_targets)
  local cursor_line = vim.api.nvim_win_get_cursor(0)[1] - 1
  util.exec(
    client,
    bufnr,
    commands.RUNNABLES_FILE,
    { { uri = vim.uri_from_bufnr(bufnr) } },
    function(targets)
      on_targets(targets or {}, cursor_line)
    end,
    {
      on_error = function()
        on_targets({}, cursor_line)
      end,
    }
  )
end

return M
