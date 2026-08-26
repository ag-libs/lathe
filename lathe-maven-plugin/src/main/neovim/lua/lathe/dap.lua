-- Debug the test under the cursor by attaching nvim-dap to a Lathe replay launched under a
-- suspended JDWP agent. Mirrors lathe.run's discovery (`lathe.runnables.list`), but instead of
-- `lathe.run.*` it drives `lathe.debug.start`: the server launches the JVM suspended and opens an
-- in-process DAP host, returning the ports nvim-dap attaches to. All DAP/JDWP wire handling lives
-- server-side (Microsoft java-debug); this module only registers the adapter and builds the
-- attach config.
--
-- Optional dependency: only :LatheDebug needs nvim-dap. lathe.setup guards M.setup with the
-- adapter's own pcall(require, "dap"), so a runtime without nvim-dap loads unaffected.

local output = require("lathe.output")

local M = {}

-- RunnableKind ordinals (lsp4j serializes the enum by ordinal), mirroring lathe.run's map. The
-- TEST_* kinds map to the server's TestSelectionKind names that lathe.debug.start expects; a
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

--- The nvim-dap `attach` config for a resolved TEST target. Carries the module and selection the
--- adapter forwards to lathe.debug.start (lathe_* keys), plus a run token so the launched JVM can
--- be correlated with the session. The DAP hostName/port are injected later by enrich_config,
--- once the server has allocated the JDWP port.
function M._config_for(target)
  return {
    type = "lathe",
    request = "attach",
    name = "Lathe: debug " .. target.label,
    lathe_module_rel = target.moduleRel,
    lathe_selections = { {
      selectorKind = SELECTOR_KIND[target.kind],
      selectorValue = target.id,
    } },
    lathe_token = output.next_token(),
  }
end

--- nvim-dap adapter: launches the suspended debuggee via lathe.debug.start and resolves to a
--- `server` adapter on the returned DAP port. enrich_config runs on this resolved adapter (after
--- this callback), so the JDWP port lands in the attach request without a port ordering race.
local function start_adapter(callback, config)
  local client = lathe_client()
  if not client then
    notify("server not attached; cannot start debug session", vim.log.levels.ERROR)
    return
  end

  client:request("workspace/executeCommand", {
    command = "lathe.debug.start",
    arguments = { {
      moduleRel = config.lathe_module_rel,
      selections = config.lathe_selections,
      token = config.lathe_token,
    } },
  }, function(err, result)
    if err or not result then
      vim.schedule(function()
        notify("debug.start error: " .. vim.inspect(err), vim.log.levels.ERROR)
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

--- Debugs the test under the cursor in `bufnr`. Resolves the target from the server's runnable
--- discovery, then hands nvim-dap the attach config -- the adapter does the launch-and-attach.
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
    local target = not err and targets and M._test_target_for(targets, cursor_line) or nil
    vim.schedule(function()
      if not target then
        notify("no test here to debug", vim.log.levels.WARN)
        return
      end

      dap.run(M._config_for(target))
    end)
  end, bufnr)
end

--- Registers the `lathe` nvim-dap adapter. Returns false (a no-op) when nvim-dap is absent, so
--- lathe.setup can skip the :LatheDebug command on runtimes without it.
function M.setup()
  local ok, dap = pcall(require, "dap")
  if not ok then
    return false
  end

  dap.adapters.lathe = start_adapter
  return true
end

return M
