-- Verifies lathe.stackdecorate: the shared, nio-free stack-frame decoration that both run paths
-- (lathe.neotest tests and lathe.run mains) drive over the shared lathe.output buffer. Asserts the
-- orchestration only -- frame parsing shapes and candidate selection are covered by
-- stacktrace_spec.lua and are deliberately not re-tested here.
--
-- Fixture: two frames of one resolvable class (App, at different lines), one frame of an
-- unresolvable class (Unknown), and a non-frame log line. This exercises, in one pass:
--   * every occurrence of a resolved class is underlined (App appears twice)
--   * unresolved classes and non-frame lines are left alone
--   * workspace/symbol is requested once per distinct class, not once per frame (resolve cache)
--   * the jump keymap is bound on the output buffer
--
-- Self-contained: stubs vim.lsp.get_clients with a fake client answering workspace/symbol, so no
-- live LSP is needed. lathe.stackdecorate uses only core Neovim APIs (no neotest/nio).
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-maven-plugin/src/main/neovim" \
--     --cmd "set rtp+=lathe-maven-plugin/src/test/neovim" \
--     -l lathe-maven-plugin/src/test/neovim/stackdecorate_spec.lua

local spec = require("spec_helper").new()

local output = require("lathe.output")
local stackdecorate = require("lathe.stackdecorate")

-- Fake lathe client: resolves only "App" (one candidate), returns nothing for anything else.
-- client:request is a method call, so the first parameter is the client itself. Every query is
-- recorded so the spec can assert the resolve cache issues one request per distinct class.
local queried = {}
local fake_client = {
  request = function(_self, method, params, handler, _bufnr)
    queried[#queried + 1] = params and params.query
    if method == "workspace/symbol" and params.query == "App" then
      handler(nil, {
        {
          containerName = "com.example",
          location = {
            uri = "file:///ws/App.java",
            range = { start = { line = 5, character = 0 }, ["end"] = { line = 5, character = 3 } },
          },
        },
      })
    else
      handler(nil, {})
    end
  end,
}
vim.lsp.get_clients = function()
  return { fake_client }
end

output.reset()
local buf = output.current_bufnr()
local stream = vim.lsp.handlers["lathe/testOutput"]
for _, text in ipairs({
  "\tat com.example.App.main(App.java:42)",
  "some non-frame log line",
  "\tat com.example.Unknown.run(Unknown.java:7)",
  "\tat com.example.App.handle(App.java:99)",
}) do
  stream(nil, { line = { stream = 0, text = text } })
end
vim.wait(200)

-- Drive decoration and wait for the async workspace/symbol round-trip + highlight pass to land.
stackdecorate.decorate_live_output()
local ns = vim.api.nvim_get_namespaces()["lathe_stacktrace"]
vim.wait(2000, function()
  return ns ~= nil and #vim.api.nvim_buf_get_extmarks(buf, ns, 0, -1, {}) > 0
end)

local marks = ns and vim.api.nvim_buf_get_extmarks(buf, ns, 0, -1, { details = true }) or {}
table.sort(marks, function(a, b)
  return a[2] < b[2]
end)
local rows, hl_ok = {}, #marks > 0
for _, m in ipairs(marks) do
  rows[#rows + 1] = m[2]
  if not (m[4] and m[4].hl_group == "LatheStackFrame") then
    hl_ok = false
  end
end

-- Rows 0 and 3 are the two App frames (0-based); the Unknown frame (row 2) and the log line (row 1)
-- are absent -- so this single assertion proves both "resolved class underlined at every
-- occurrence" and "unresolved / non-frame lines skipped".
spec.check("resolved class underlined at every occurrence, others skipped", table.concat(rows, ","), "0,3")
spec.check("underline uses the stack-frame highlight", hl_ok, true)
-- The mark starts past the frame's leading text: it underlines the File.java:line span only, not
-- the whole line (highlight_frame_span's substring offset).
spec.check("underline marks only the File.java:line span", marks[1] and marks[1][3], ("\tat com.example.App.main("):len())
spec.check("one workspace/symbol request per distinct class (resolve cache)", #queried, 2)

local has_jump = false
for _, m in ipairs(vim.api.nvim_buf_get_keymap(buf, "n")) do
  if m.desc == "Lathe: jump to resolved stack frame" then
    has_jump = true
  end
end
spec.check("jump keymap bound on the output buffer", has_jump, true)

spec.finish("stackdecorate_spec")
