-- Shared live-output surface for Lathe replay runs. One docked console buffer and one
-- global `lathe/testOutput` stream handler, reused by both run paths: tests (lathe.neotest)
-- and `main` classes (lathe.run). The handler is token-agnostic -- it appends whatever the
-- server streams -- so a single registration here serves every run; two feature modules each
-- registering their own `vim.lsp.handlers["lathe/testOutput"]` would clobber one another.
-- Uses only core Neovim APIs (no neotest/nio), so it loads headlessly and from either caller.

local M = {}

local LIVE_OUTPUT_NS = vim.api.nvim_create_namespace("lathe_test_output")
local STDERR_HL = "LatheTestStderr"
vim.api.nvim_set_hl(0, STDERR_HL, { link = "DiagnosticError", default = true })
local COMMAND_HL = "LatheTestCommand"
vim.api.nvim_set_hl(0, COMMAND_HL, { link = "Comment", default = true })
local MAX_LIVE_HEIGHT = 20
local STDERR_STREAM = 1 -- TranscriptLine.Stream.STDERR ordinal
local COMMAND_STREAM = 2 -- TranscriptLine.Stream.COMMAND ordinal (the replay launch command)

local live_bufnr
local live_win
local run_counter = 0

--- A per-run correlation token the server echoes on each streamed line. Unique within a
--- session; the server tags notifications with it and a blank one disables streaming.
function M.next_token()
  run_counter = run_counter + 1
  return "run-" .. run_counter
end

local function live_buffer()
  if live_bufnr and vim.api.nvim_buf_is_valid(live_bufnr) then
    return live_bufnr
  end

  live_bufnr = vim.api.nvim_create_buf(false, true)
  vim.bo[live_bufnr].bufhidden = "hide"
  vim.bo[live_bufnr].filetype = "lathe-test-output"
  vim.bo[live_bufnr].modifiable = false
  return live_bufnr
end

--- The current live buffer if it exists and is valid, else nil. Unlike live_buffer() this
--- never creates one -- callers that only read or decorate an existing run's output (e.g.
--- stack-frame decoration) must not spawn an empty buffer.
function M.current_bufnr()
  if live_bufnr and vim.api.nvim_buf_is_valid(live_bufnr) then
    return live_bufnr
  end

  return nil
end

local function live_is_open()
  return live_win ~= nil and vim.api.nvim_win_is_valid(live_win)
end

--- Empties the live buffer at the start of a run so it always shows the current one.
function M.reset()
  local buf = live_buffer()
  vim.bo[buf].modifiable = true
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, {})
  vim.bo[buf].modifiable = false
  vim.api.nvim_buf_clear_namespace(buf, LIVE_OUTPUT_NS, 0, -1)
end

--- Appends one streamed line, coloring stderr, and follows the tail while the window is
--- open but not focused (so scrolling up to read stays sticky).
local function live_append(stream, text)
  local buf = live_buffer()
  local count = vim.api.nvim_buf_line_count(buf)
  local first_empty = count == 1 and vim.api.nvim_buf_get_lines(buf, 0, 1, false)[1] == ""
  local row = first_empty and 0 or count
  vim.bo[buf].modifiable = true
  vim.api.nvim_buf_set_lines(buf, row, first_empty and 1 or row, false, { text })
  vim.bo[buf].modifiable = false
  local hl = (stream == STDERR_STREAM and STDERR_HL) or (stream == COMMAND_STREAM and COMMAND_HL)
  if hl then
    pcall(vim.api.nvim_buf_set_extmark, buf, LIVE_OUTPUT_NS, row, 0, {
      end_row = row + 1,
      hl_group = hl,
      hl_eol = true,
    })
  end

  if live_is_open() and vim.api.nvim_get_current_win() ~= live_win then
    pcall(vim.api.nvim_win_set_cursor, live_win, { vim.api.nvim_buf_line_count(buf), 0 })
  end
end

--- Opens the docked live-output window, or toggles it closed / focuses it if already open.
--- This is the single output surface; neotest's floating output is not used.
function M.open()
  if live_is_open() then
    if vim.api.nvim_get_current_win() == live_win then
      vim.api.nvim_win_close(live_win, true)
      live_win = nil
    else
      vim.api.nvim_set_current_win(live_win)
    end

    return
  end

  local buf = live_buffer()
  local prev = vim.api.nvim_get_current_win()
  vim.cmd("botright split")
  vim.api.nvim_win_set_height(0, MAX_LIVE_HEIGHT)
  live_win = vim.api.nvim_get_current_win()
  vim.api.nvim_win_set_buf(live_win, buf)
  -- No wrap: the first line is the full replay command (a very long line); wrapping it would spill
  -- across the whole window. Unwrapped it stays one row, scroll-right to read, still copy-pasteable.
  vim.wo[live_win].wrap = false
  vim.api.nvim_set_current_win(prev)
end

--- Ensures the output window is visible, without toggling or stealing focus -- used when a run
--- starts (M.open() is the user-facing toggle, which would instead close it if it were already
--- open and focused). A no-op when already open.
function M.ensure_open()
  if live_is_open() then
    return
  end

  M.open()
end

--- Test hook: the live output buffer's current lines, or nil if it has none yet.
function M.lines()
  if not (live_bufnr and vim.api.nvim_buf_is_valid(live_bufnr)) then
    return nil
  end

  return vim.api.nvim_buf_get_lines(live_bufnr, 0, -1, false)
end

--- 0-based rows in the live buffer carrying an extmark of the given highlight group. stderr and the
--- command line share LIVE_OUTPUT_NS, so filter by hl_group to tell them apart.
local function live_output_rows(hl_group)
  if not (live_bufnr and vim.api.nvim_buf_is_valid(live_bufnr)) then
    return {}
  end

  local rows = {}
  local marks = vim.api.nvim_buf_get_extmarks(live_bufnr, LIVE_OUTPUT_NS, 0, -1, { details = true })
  for _, mark in ipairs(marks) do
    if mark[4] and mark[4].hl_group == hl_group then
      rows[#rows + 1] = mark[2]
    end
  end
  return rows
end

--- Test hook: the 0-based rows in the live buffer currently marked as stderr.
function M.stderr_rows()
  return live_output_rows(STDERR_HL)
end

--- Test hook: the 0-based rows in the live buffer currently marked as the launch command.
function M.command_rows()
  return live_output_rows(COMMAND_HL)
end

-- Streamed lines arrive on any run; append them on the main loop (buffer edits must not
-- run inside the LSP callback's fast context).
vim.lsp.handlers["lathe/testOutput"] = function(_err, result)
  if not result or not result.line then
    return
  end

  vim.schedule(function()
    live_append(result.line.stream, result.line.text)
  end)
end

return M
