-- Headless editor smoke, run by dev/editor-smoke.sh inside the user's own Neovim config (lathe.nvim
-- from LATHE_NVIM_DIR): drives what a user does in a Java buffer of a real workspace and writes one
-- PASS/FAIL line per check to SMOKE_OUT. Edits stay in the buffer and are undone; no file is written.
--
-- env: SMOKE_OUT (report file), SMOKE_SYMBOL (a member with cross-file uses), SMOKE_DOT (text
-- ending in a member-access point), SMOKE_DEP (a JDK/dependency type used in the file), SMOKE_TEST
-- (a test file, run through the Lathe neotest adapter).
local out = assert(io.open(assert(os.getenv("SMOKE_OUT"), "SMOKE_OUT not set"), "w"))
local failures = 0

local function check(name, ok, detail)
  failures = failures + (ok and 0 or 1)
  out:write(string.format("%s %-30s %s\n", ok and "PASS" or "FAIL", name, detail or ""))
  out:flush()
end

local function now()
  return vim.uv.hrtime() / 1e6
end

-- 0-based row and column of the first occurrence of token outside comments, imports and package.
local function find(token)
  for i, line in ipairs(vim.api.nvim_buf_get_lines(0, 0, -1, false)) do
    local col = line:find(token, 1, true)
    local text = vim.trim(line)
    if col and not (text:match("^%*") or text:match("^//") or text:match("^/%*")
        or text:match("^import ") or text:match("^package ")) then
      return i - 1, col - 1
    end
  end
end

local function count(result)
  if type(result) ~= "table" then
    return result == nil and 0 or 1
  end
  if result.items then
    return #result.items
  end
  if result.data then
    return #result.data
  end
  if result.contents or result.uri or result.targetUri then
    return 1
  end
  return #result
end

-- One request and its check: pass(result) decides, the detail shows timing, size and any error.
local function probe(name, client, method, params, pass, buf)
  local t0 = now()
  local resp = client:request_sync(method, params, 30000, buf or 0)
  local ms = math.floor(now() - t0)
  local result = resp and resp.result
  local err = not resp and "timeout" or (resp.err and vim.inspect(resp.err):sub(1, 120))
  check(name, not err and pass(result), string.format("%dms n=%d %s", ms, count(result), err or ""))
  return result
end

local function nonempty(result)
  return count(result) > 0
end

local function answered()
  return true
end

local function at(client, row, col)
  vim.api.nvim_win_set_cursor(0, { row + 1, col })
  return vim.lsp.util.make_position_params(0, client.offset_encoding)
end

local function errors()
  return #vim.diagnostic.get(0, { severity = vim.diagnostic.severity.ERROR })
end

local function lathe_client(buf)
  return vim.lsp.get_clients({ name = "lathe", bufnr = buf })[1]
end

local function run_tests(test)
  vim.cmd("edit " .. vim.fn.fnameescape(test))
  local buf = vim.api.nvim_get_current_buf()
  vim.wait(120000, function() return lathe_client(buf) ~= nil end, 200)
  local adapter = require("lathe.neotest")
  local state = {}
  -- As dev/neotest-e2e does: the real adapter's discover, build and replay, inside a nio task.
  require("nio").run(function()
    -- Any error ends the task with state.error, never leaving the wait below to time out.
    local ok, err = pcall(function()
      local tree = adapter.discover_positions(test)
      state.tests = 0
      for _, position in tree:iter() do
        state.tests = state.tests + (position.type == "test" and 1 or 0)
      end
      if state.tests == 0 then
        return
      end
      local t0 = now()
      local built = adapter.build_spec({ tree = tree })
      state.counts = { passed = 0, failed = 0, skipped = 0 }
      for _, spec in ipairs(built.command and { built } or built) do
        for _, result in pairs(adapter.results(spec, nil, tree)) do
          state.counts[result.status] = (state.counts[result.status] or 0) + 1
        end
      end
      state.ms = math.floor(now() - t0)
    end)
    state.error = not ok and tostring(err) or nil
    state.done = true
  end)
  vim.wait(600000, function() return state.done end, 500)
  check("neotest discovery", (state.tests or 0) > 0,
    string.format("tests=%d %s", state.tests or 0, state.error or ""))
  if state.counts then
    local c = state.counts
    check("neotest run file", c.failed == 0 and c.passed > 0,
      string.format("%dms passed=%d failed=%d skipped=%d", state.ms, c.passed, c.failed, c.skipped))
  end
end

local function main()
  local t0 = now()
  local client
  local attached = vim.wait(180000, function()
    client = lathe_client(0)
    return client ~= nil and client.initialized
  end, 200)
  check("attach", attached, string.format("%dms", math.floor(now() - t0)))
  if not attached then
    return
  end

  local published = false
  vim.api.nvim_create_autocmd("DiagnosticChanged", { callback = function() published = true end })
  vim.wait(120000, function() return published end, 200)
  check("diagnostics published", published, string.format("%dms", math.floor(now() - t0)))
  check("clean file has no errors", errors() == 0, string.format("errors=%d", errors()))
  vim.wait(8000) -- the reference index warms up after the first compile

  local srow, scol = find(os.getenv("SMOKE_SYMBOL"))
  local p = at(client, srow, scol + 1)
  probe("hover", client, "textDocument/hover", p, function(r) return r and r.contents end)
  probe("definition", client, "textDocument/definition", p, nonempty)
  probe("references", client, "textDocument/references",
    vim.tbl_extend("force", p, { context = { includeDeclaration = true } }), nonempty)
  probe("documentHighlight", client, "textDocument/documentHighlight", p, nonempty)
  probe("prepareRename", client, "textDocument/prepareRename", p, function(r) return r ~= nil end)
  probe("rename (computed, not applied)", client, "textDocument/rename",
    vim.tbl_extend("force", p, { newName = "latheRenamed" }),
    function(r) return r and (next(r.changes or {}) or next(r.documentChanges or {})) end)
  local calls = probe("callHierarchy prepare", client, "textDocument/prepareCallHierarchy", p, nonempty)
  if calls and calls[1] then
    probe("callHierarchy incoming", client, "callHierarchy/incomingCalls", { item = calls[1] }, answered)
  end

  local trow, tcol, keyword
  for _, k in ipairs({ "class ", "record ", "interface ", "enum " }) do
    trow, tcol = find(k)
    if trow then
      keyword = k
      break
    end
  end
  local types = probe("typeHierarchy prepare", client, "textDocument/prepareTypeHierarchy",
    at(client, trow, tcol + #keyword + 1), nonempty)
  if types and types[1] then
    probe("typeHierarchy supertypes", client, "typeHierarchy/supertypes", { item = types[1] }, answered)
  end

  local doc = { textDocument = p.textDocument }
  probe("documentSymbol", client, "textDocument/documentSymbol", doc, nonempty)
  probe("foldingRange", client, "textDocument/foldingRange", doc, nonempty)
  probe("semanticTokens", client, "textDocument/semanticTokens/full", doc, nonempty)
  probe("workspaceSymbol", client, "workspace/symbol",
    { query = vim.fn.fnamemodify(vim.api.nvim_buf_get_name(0), ":t:r") }, nonempty)
  probe("codeAction", client, "textDocument/codeAction",
    { textDocument = p.textDocument, range = { start = p.position, ["end"] = p.position },
      context = { diagnostics = {} } }, answered)

  -- Into a JDK/dependency source: the cache buffer attaches and is navigable itself.
  local drow, dcol = find(os.getenv("SMOKE_DEP"))
  local defs = probe("definition into dependency", client, "textDocument/definition",
    at(client, drow, dcol + 1), nonempty)
  local target = defs and (defs[1] or defs)
  if target then
    local origin = vim.api.nvim_get_current_buf()
    vim.lsp.util.show_document(target, client.offset_encoding, { focus = true })
    local dep_buf = vim.api.nvim_get_current_buf()
    local dep_attached = vim.wait(30000, function() return lathe_client(dep_buf) ~= nil end, 200)
    check("dependency buffer attached", dep_attached, vim.fn.fnamemodify(vim.api.nvim_buf_get_name(dep_buf), ":t"))
    if dep_attached then
      local dep_client = lathe_client(dep_buf)
      local hp = vim.lsp.util.make_position_params(0, dep_client.offset_encoding)
      probe("hover in dependency source", dep_client, "textDocument/hover", hp,
        function(r) return r ~= nil end, dep_buf)
      probe("outline of dependency source", dep_client, "textDocument/documentSymbol",
        { textDocument = hp.textDocument }, nonempty, dep_buf)
    end
    vim.api.nvim_set_current_buf(origin)
  end

  local dot = os.getenv("SMOKE_DOT")
  local crow, ccol = find(dot)
  probe("completion after dot", client, "textDocument/completion", at(client, crow, ccol + #dot), nonempty)

  -- Typing in the buffer: an error appears and clears on undo, and a typed receiver completes.
  local body = find("return ")
  vim.api.nvim_buf_set_lines(0, body, body, false, { '    int latheSmokeBroken = "oops";' })
  check("error appears while typing", vim.wait(20000, function() return errors() > 0 end, 100),
    string.format("errors=%d", errors()))
  vim.cmd("silent undo")
  check("error clears after undo", vim.wait(20000, function() return errors() == 0 end, 100),
    string.format("errors=%d", errors()))
  local typed = '    String latheS = "x"; latheS.'
  vim.api.nvim_buf_set_lines(0, body, body, false, { typed })
  -- A person's keystroke reaches the server a loop turn before the completion trigger; a script's
  -- edit and request share one tick, so give the change tracking that turn.
  vim.wait(300)
  probe("completion on typed receiver", client, "textDocument/completion",
    { textDocument = p.textDocument, position = { line = body, character = #typed } },
    function(r)
      return vim.iter((r and (r.items or r)) or {}):any(function(item) return item.label:match("^length") end)
    end)
  vim.cmd("silent undo")

  -- The client entry point <leader>cf and format-on-save run; the buffer change is undone.
  local tick = vim.api.nvim_buf_get_changedtick(0)
  local formatted_ok, format_error = pcall(require("lathe").format, 0)
  local changed = vim.wait(3000, function() return vim.api.nvim_buf_get_changedtick(0) ~= tick end, 50)
  check("lathe.format", formatted_ok, string.format("changed=%s %s", tostring(changed), format_error or ""))
  if changed then
    vim.cmd("silent undo")
  end

  vim.api.nvim_win_set_cursor(0, { trow + 1, tcol + #keyword + 1 })
  vim.fn.setqflist({})
  local instances_ok, instances_error = pcall(vim.cmd, "LatheInstances")
  vim.wait(15000, function() return #vim.fn.getqflist() > 0 end, 200)
  check("LatheInstances", instances_ok, string.format("qf=%d %s", #vim.fn.getqflist(), instances_error or ""))

  run_tests(assert(os.getenv("SMOKE_TEST"), "SMOKE_TEST not set"))
end

local ok, err = xpcall(main, debug.traceback)
if not ok then
  check("driver", false, err)
end
out:write(string.format("SUMMARY fail=%d\n", failures))
out:close()
vim.cmd(failures == 0 and "qa!" or "cquit! 1")
