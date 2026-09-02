-- Debug the test or main class under the cursor by attaching nvim-dap to a Lathe replay launched
-- under a suspended JDWP agent. Mirrors lathe.run's discovery (`lathe.runnables.list`), but instead
-- of `lathe.run.*` it drives `lathe.debug.test` / `lathe.debug.main`: the server launches the JVM
-- suspended and opens an in-process DAP host, returning the ports nvim-dap attaches to. All
-- DAP/JDWP wire handling lives server-side (Microsoft java-debug); this module only registers the
-- adapter and builds the attach config.
--
-- Optional dependency: only :LatheDebug needs nvim-dap. lathe.setup guards M.setup with the
-- adapter's own pcall(require, "dap"), so a runtime without nvim-dap loads unaffected.

local output = require("lathe.output")
-- Safe to require at load: lathe.stackdecorate is core-only (no neotest/nio), like lathe.output.
local stackdecorate = require("lathe.stackdecorate")

local M = {}

-- RunnableKind ordinals (lsp4j serializes the enum by ordinal), mirroring lathe.run's map. The
-- TEST_* kinds map to the server's TestSelectionKind names that lathe.debug.test expects; a
-- METHOD range wins over its enclosing CLASS, which wins over the PACKAGE, so debugging from a
-- method, class, or package line all resolve to the innermost target under the cursor.
local TEST_METHOD = 1
local TEST_CLASS = 2
local TEST_PACKAGE = 3
local SELECTOR_KIND = {
  [TEST_METHOD] = "METHOD",
  [TEST_CLASS] = "CLASS",
  [TEST_PACKAGE] = "PACKAGE",
}
-- Innermost first: the first kind whose range contains the cursor is the one to debug.
local PRECEDENCE = { TEST_METHOD, TEST_CLASS, TEST_PACKAGE }

local function lathe_client()
  return vim.lsp.get_clients({ name = "lathe" })[1]
end

local function notify(msg, level)
  vim.notify(msg, level, { title = "Lathe" })
end

--- True when neotest is installed and set up (its run API is present), so :LatheDebug can route a test
--- through neotest's dap strategy -- gutters/summary, the shared console, and a pass/fail outcome.
--- False means fall back to a direct dap.run (a debug session with none of those).
local function neotest_available()
  local ok, neotest = pcall(require, "neotest")
  return ok and neotest.run ~= nil
end

local function in_range(range, line)
  return range ~= nil and line >= range.start.line and line <= range["end"].line
end

--- The TEST runnable to debug for a cursor position: the innermost target (method, then class,
--- then package) whose range contains `cursor_line` (0-based); else nil. Pure over the raw
--- lathe.runnables.list targets, so it is unit-testable without a live client.
function M._test_target_for(targets, cursor_line)
  for _, kind in ipairs(PRECEDENCE) do
    for _, t in ipairs(targets) do
      if t.kind == kind and in_range(t.range, cursor_line) then
        return t
      end
    end
  end

  return nil
end

--- The nvim-dap `attach` config for a module + test selections. Carries the module and selections the
--- adapter forwards to lathe.debug.test (lathe_* keys), plus the run token so the launched JVM can be
--- correlated with the session. Shared by :LatheDebug's cursor path (M._test_config_for) and
--- lathe.neotest's dap-strategy specs, so the config shape lives in one place. The DAP hostName/port
--- are injected later by enrich_config, once the server has allocated the JDWP port.
function M._attach_config(module_rel, selections, label, token)
  return {
    type = "lathe",
    request = "attach",
    name = "Lathe: debug " .. label,
    lathe_module_rel = module_rel,
    lathe_selections = selections,
    lathe_token = token,
  }
end

--- The attach config for a resolved TEST target under the cursor, minting its own run token.
function M._test_config_for(target)
  return M._attach_config(target.moduleRel, {
    { selectorKind = SELECTOR_KIND[target.kind], selectorValue = target.id },
  }, target.label, output.next_token())
end

--- The nvim-dap `attach` config for a resolved MAIN target (from lathe.run's discovery). Carries
--- the module and main class the adapter forwards to lathe.debug.main; the presence of
--- lathe_main_class (rather than lathe_selections) is what routes the adapter to the main command.
function M._main_config_for(target)
  return {
    type = "lathe",
    request = "attach",
    name = "Lathe: debug " .. target.parentId,
    lathe_module_rel = target.moduleRel,
    lathe_main_class = target.parentId,
    lathe_token = output.next_token(),
  }
end

--- nvim-dap adapter: launches the suspended debuggee via lathe.debug.test / lathe.debug.main and
--- resolves to a `server` adapter on the returned DAP port. enrich_config runs on this resolved
--- adapter (after this callback), so the JDWP port lands in the attach request without a port
--- ordering race. A main config (lathe_main_class set) routes to lathe.debug.main; otherwise the
--- test selections route to lathe.debug.test.
local function start_adapter(callback, config)
  local client = lathe_client()
  if not client then
    notify("server not attached; cannot start debug session", vim.log.levels.ERROR)
    return
  end

  local command, argument
  if config.lathe_main_class then
    command = "lathe.debug.main"
    argument = {
      moduleRel = config.lathe_module_rel,
      mainClass = config.lathe_main_class,
      token = config.lathe_token,
    }
  else
    command = "lathe.debug.test"
    argument = {
      moduleRel = config.lathe_module_rel,
      selections = config.lathe_selections,
      token = config.lathe_token,
    }
  end

  client:request("workspace/executeCommand", {
    command = command,
    arguments = { argument },
  }, function(err, result)
    if err or not result then
      vim.schedule(function()
        notify("debug start error: " .. vim.inspect(err), vim.log.levels.ERROR)
      end)
      return
    end

    vim.schedule(function()
      callback({
        type = "server",
        host = "127.0.0.1",
        port = result.dapPort,
        enrich_config = function(cfg, on_config)
          on_config(vim.tbl_extend("force", cfg, {
            hostName = "127.0.0.1",
            port = result.jdwpPort,
          }))
        end,
      })
    end)
  end)
end

--- The debug route for a resolved cursor target. Pure so the branch choice is unit-testable without a
--- live client, nvim-dap, or neotest: a test routes through neotest's dap strategy when neotest is
--- available (gutters/console/outcome), else falls back to a direct dap.run; a main always uses a
--- direct dap.run; nothing resolved is "none".
function M._debug_route(has_test, has_main, neotest)
  if has_test then
    return neotest and "neotest" or "dap_test"
  end

  if has_main then
    return "dap_main"
  end

  return "none"
end

--- Debugs the test or main class under the cursor in `bufnr`. Resolves the target from the server's
--- runnable discovery -- the innermost test under the cursor, else a main -- then either routes a test
--- through neotest's dap strategy (so gutters/summary/console/pass-fail match a run) or hands nvim-dap
--- the attach config directly (a main, or the neotest-absent fallback).
function M.debug(bufnr)
  bufnr = bufnr or vim.api.nvim_get_current_buf()
  local ok, dap = pcall(require, "dap")
  if not ok then
    notify("nvim-dap not installed", vim.log.levels.ERROR)
    return
  end

  local client = lathe_client()
  if not client then
    notify("server not attached to this buffer", vim.log.levels.WARN)
    return
  end

  local cursor_line = vim.api.nvim_win_get_cursor(0)[1] - 1
  client:request("workspace/executeCommand", {
    command = "lathe.runnables.list",
    arguments = { { uri = vim.uri_from_bufnr(bufnr) } },
  }, function(err, targets)
    targets = (not err and targets) or {}
    local test = M._test_target_for(targets, cursor_line)
    local main = not test and require("lathe.run")._main_target_for(targets, cursor_line) or nil
    vim.schedule(function()
      local route = M._debug_route(test ~= nil, main ~= nil, neotest_available())
      if route == "neotest" then
        require("neotest").run.run({ strategy = "dap" })
      elseif route == "dap_test" then
        dap.run(M._test_config_for(test))
      elseif route == "dap_main" then
        dap.run(M._main_config_for(main))
      else
        notify("no test or main here to debug", vim.log.levels.WARN)
      end
    end)
  end, bufnr)
end

-- Once a Lathe debug session ends, hyperlink the stack frames the debuggee streamed into the
-- shared output buffer -- the debug twin of lathe.run's on_finished / lathe.neotest's results(),
-- which both call this after their run completes. The debug launch request returns at attach
-- (ports allocated), long before the debuggee exits, so the DAP `terminated`/`exited` events are
-- the only client-side "run finished" signal. Scoped to type == "lathe" because dap.listeners are
-- global (other adapters must not trigger it); decorate_live_output is idempotent -- it clears its
-- namespace and re-scans -- so firing on both events is safe. Exposed as M._ so the guard is unit
-- testable without a live nvim-dap session.
function M._decorate_on_session_end(session)
  if session and session.config and session.config.type == "lathe" then
    stackdecorate.decorate_live_output()
  end
end

--- Registers the `lathe` nvim-dap adapter. Returns false (a no-op) when nvim-dap is absent, so
--- lathe.setup can skip the :LatheDebug command on runtimes without it.
function M.setup()
  local ok, dap = pcall(require, "dap")
  if not ok then
    return false
  end

  dap.adapters.lathe = start_adapter
  dap.listeners.after.event_terminated.lathe_decorate = M._decorate_on_session_end
  dap.listeners.after.event_exited.lathe_decorate = M._decorate_on_session_end
  return true
end

return M
