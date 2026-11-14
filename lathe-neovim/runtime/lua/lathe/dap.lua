-- Debug the test or main class under the cursor by attaching nvim-dap to a Lathe replay launched
-- under a suspended JDWP agent. Mirrors lathe.run's discovery (`lathe.runnables.file`), but instead
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

-- Run tokens of in-flight Lathe debug sessions, so :LatheRunStop can cancel their replay JVMs.
local active_tokens = {}

local util = require("lathe.util")
local commands = require("lathe.commands")
local runnables = require("lathe.runnables")
local lathe_client = util.client

local notify = util.notify

--- True when neotest is installed and set up (its run API is present), so :LatheDebug can route a test
--- through neotest's dap strategy -- gutters/summary, the shared console, and a pass/fail outcome.
--- False means fall back to a direct dap.run (a debug session with none of those).
local function neotest_available()
  local ok, neotest = pcall(require, "neotest")
  return ok and neotest.run ~= nil
end

--- The nvim-dap `attach` config for a module + test selections. Carries the module and selections the
--- adapter forwards to lathe.debug.test (lathe_* keys), plus the run token so the launched JVM can be
--- correlated with the session. Shared by :LatheDebug's cursor path (M._test_config_for) and
--- lathe.neotest's dap-strategy specs, so the config shape lives in one place. The DAP hostName/port
--- are injected later by enrich_config, once the server has allocated the JDWP port.
function M.attach_config(module_rel, selections, label, token)
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
  return M.attach_config(target.moduleRel, {
    { selectorKind = runnables.SELECTOR_KIND[target.kind], selectorValue = target.id },
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

--- The attach config for a saved config selected by name: the server resolves its module and target,
--- so the client forwards only the name (lathe_config_name routes the adapter to lathe.debug.named).
function M._named_config_for(name)
  return {
    type = "lathe",
    request = "attach",
    name = "Lathe: debug " .. name,
    lathe_config_name = name,
    lathe_token = output.next_token(),
  }
end

--- nvim-dap adapter: launches the suspended debuggee via lathe.debug.test / lathe.debug.main and
--- resolves to a `server` adapter on the returned DAP port. enrich_config runs on this resolved
--- adapter (after this callback), so the JDWP port lands in the attach request without a port
--- ordering race. A main config (lathe_main_class set) routes to lathe.debug.main; otherwise the
--- test selections route to lathe.debug.test.
local function start_adapter(callback, config)
  local client = config.lathe_client_id and vim.lsp.get_client_by_id(config.lathe_client_id)
    or lathe_client()
  if not client then
    notify("server not attached; cannot start debug session", vim.log.levels.ERROR)
    return
  end

  -- Track the run token, with the server running it, so :LatheRunStop can cancel this debug replay
  -- there (M.stop), rather than relying on the attach adapter's terminate semantics. Cleared when the
  -- session ends, or when it fails to start.
  active_tokens[config.lathe_token] = client.id

  local command, argument
  if config.lathe_config_name then
    command = commands.DEBUG_NAMED
    argument = { name = config.lathe_config_name, token = config.lathe_token }
  elseif config.lathe_main_class then
    command = commands.DEBUG_MAIN
    argument = {
      moduleRel = config.lathe_module_rel,
      mainClass = config.lathe_main_class,
      token = config.lathe_token,
    }
  else
    command = commands.DEBUG_TEST
    argument = {
      moduleRel = config.lathe_module_rel,
      selections = config.lathe_selections,
      token = config.lathe_token,
    }
  end

  local function failed(err)
    active_tokens[config.lathe_token] = nil
    vim.schedule(function()
      notify("debug start error: " .. vim.inspect(err), vim.log.levels.ERROR)
    end)
  end

  util.exec(client, nil, command, { argument }, function(result)
    if not result then
      failed(nil)
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
  end, { on_error = failed })
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

  local client = lathe_client(bufnr)
  if not client then
    notify("server not attached to this buffer", vim.log.levels.WARN)
    return
  end

  runnables.at_cursor(client, bufnr, function(targets, cursor_line)
    local test = runnables.test_target_for(targets, cursor_line)
    local main = not test and runnables.main_target_for(targets, cursor_line) or nil
    local route = M._debug_route(test ~= nil, main ~= nil, neotest_available())
    if route == "neotest" then
      require("neotest").run.run({ strategy = "dap" })
    elseif route == "dap_test" then
      dap.run(vim.tbl_extend("force", M._test_config_for(test), { lathe_client_id = client.id }))
    elseif route == "dap_main" then
      dap.run(vim.tbl_extend("force", M._main_config_for(main), { lathe_client_id = client.id }))
    else
      notify("no test or main here to debug", vim.log.levels.WARN)
    end
  end)
end

--- Debugs a saved config selected by name (cursor-independent), via nvim-dap's attach flow. The
--- server resolves the config's target and launch mode, so run and debug share one saved entry.
function M.debug_named(name)
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

  dap.run(vim.tbl_extend("force", M._named_config_for(name), { lathe_client_id = client.id }))
end

--- Stops any in-flight Lathe debug session by cancelling its replay JVM directly (lathe.run.cancel) --
--- the same reliable server path a normal run uses, rather than depending on the attach adapter's
--- terminate semantics. Killing the JVM makes DAP emit exited/terminated (tearing down the UI); a
--- dap.terminate() follows as belt-and-suspenders cleanup. Returns true if it stopped anything, so
--- :LatheRunStop can fall through to the normal-run stop when there was no debug session.
function M.stop()
  local runs = active_tokens
  if next(runs) == nil then
    return false
  end

  active_tokens = {}
  for token, client_id in pairs(runs) do
    local client = vim.lsp.get_client_by_id(client_id)
    if client then
      util.exec(client, nil, commands.RUN_CANCEL, { { token = token } }, nil, { silent = true })
    end
  end

  local ok, dap = pcall(require, "dap")
  if ok then
    pcall(function()
      dap.terminate({}, { terminateDebuggee = true })
    end)
  end

  return true
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
    if session.config.lathe_token then
      active_tokens[session.config.lathe_token] = nil
    end

    local client_id = session.config.lathe_client_id
    stackdecorate.decorate_live_output(client_id and vim.lsp.get_client_by_id(client_id))
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
  -- :LatheDebug attaches nvim-dap to the test or main class under the cursor, replayed under a
  -- suspended JDWP agent (server-side lathe.debug.test / lathe.debug.main). Wired only when
  -- nvim-dap is present, so a runtime without it loads unaffected.
  vim.api.nvim_create_user_command("LatheDebug", function(cmd)
    if cmd.args ~= "" then
      M.debug_named(cmd.args)
    else
      M.debug(vim.api.nvim_get_current_buf())
    end
  end, {
    nargs = "?",
    complete = function(arglead)
      return require("lathe.run").complete_config(arglead)
    end,
    desc = "Lathe: debug the cursor target, or a saved config by name",
  })
  return true
end

return M
