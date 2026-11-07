-- Verifies lathe.stackdecorate: the shared, nio-free stack-frame decoration that both run paths
-- (lathe.neotest tests and lathe.run mains) drive over the shared lathe.output buffer. The server
-- reads and resolves the frames (lathe.stack.resolve, covered by StackFramesTest); this asserts the
-- client's part:
--   * the buffer's lines naming a .java file go to the server in one request, mapped back to rows
--   * each answered frame's span is underlined, converting its UTF-16 columns to bytes (the third
--     line carries a non-ASCII prefix)
--   * lines the server did not answer are left alone
--   * the jump keymap is bound on the output buffer
--
-- Self-contained: stubs vim.lsp.get_clients with a fake client answering lathe.stack.resolve, so no
-- live LSP is needed. lathe.stackdecorate uses only core Neovim APIs (no neotest/nio).
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-neovim/runtime" \
--     --cmd "set rtp+=lathe-neovim/test" \
--     -l lathe-neovim/test/stackdecorate_spec.lua

local spec = require("spec_helper").new()

local output = require("lathe.output")
local stackdecorate = require("lathe.stackdecorate")

local LINES = {
  "\tat com.example.App.main(App.java:42)",
  "some non-frame log line",
  "»» \tat com.example.App.handle(App.java:99)",
  "\tat com.example.Unknown.run(Unknown.java:7)",
}

-- The server's answer for the lines it was sent: each App frame, by its index in the request, with
-- the UTF-16 columns of its File.java:line span; the Unknown frame resolves to nothing.
local function span(index, text)
  local file_line = text:match("(App%.java:%d+)")
  local start = text:find(file_line, 1, true) - 1
  local utf16_start = vim.str_utfindex(text, "utf-16", start, false)
  return {
    index = index,
    uri = "file:///ws/App.java",
    line = tonumber(file_line:match(":(%d+)$")),
    startColumn = utf16_start,
    endColumn = utf16_start + #file_line,
  }
end

local function answer(lines)
  local frames = {}
  for i, text in ipairs(lines) do
    if text:find("App.java", 1, true) then
      frames[#frames + 1] = span(i - 1, text)
    end
  end
  return frames
end

local requests = {}
local fake_client = {
  name = "lathe",
  request = function(_self, method, params, handler, _bufnr)
    requests[#requests + 1] = params
    if method == "workspace/executeCommand" and params.command == "lathe.stack.resolve" then
      handler(nil, answer(params.arguments[1].lines))
    else
      handler(nil, nil)
    end
  end,
}
vim.lsp.get_clients = function()
  return { fake_client }
end

output.reset()
local buf = output.current_bufnr()
local stream = vim.lsp.handlers["lathe/testOutput"]
for _, text in ipairs(LINES) do
  stream(nil, { line = { stream = 0, text = text } })
end
vim.wait(200)

-- Drive decoration and wait for the async lathe.stack.resolve round-trip + highlight pass to land.
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

-- Rows 0 and 2 are the two answered App frames (0-based); the log line and the Unknown frame,
-- which the server did not answer, are absent.
spec.check("answered frames underlined, others skipped", table.concat(rows, ","), "0,2")
spec.check("underline uses the stack-frame highlight", hl_ok, true)
spec.check(
  "underline marks only the File.java:line span",
  marks[1] and marks[1][3],
  ("\tat com.example.App.main("):len()
)
spec.check(
  "a non-ASCII prefix shifts the underline by its bytes, not its UTF-16 units",
  marks[2] and marks[2][3],
  LINES[3]:find("App.java:99", 1, true) - 1
)
spec.check("one request carries every candidate line", #requests, 1)
spec.check(
  "only lines naming a .java file are sent",
  requests[1] and #requests[1].arguments[1].lines,
  3
)

local has_jump = false
for _, m in ipairs(vim.api.nvim_buf_get_keymap(buf, "n")) do
  if m.desc == "Lathe: jump to resolved stack frame" then
    has_jump = true
  end
end
spec.check("jump keymap bound on the output buffer", has_jump, true)

spec.finish("stackdecorate_spec")
