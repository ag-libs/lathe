-- Stack-trace navigation for the shared live-output buffer
-- (docs/done/lathe-test-output-streaming.md). The server reads the frames and resolves each to its
-- class's source (lathe.stack.resolve); this links them. decorate_live_output() drives it once a run
-- finishes, so it applies identically to both run paths: tests (lathe.neotest) and `main` classes
-- (lathe.run), which share one output buffer via lathe.output.
--
-- Uses only core vim.lsp/vim.api (no neotest/nio), like lathe.output, so it loads headlessly and
-- from the main-run path, which is deliberately neotest/nio-free.

-- Safe to require at load: they use only core Neovim APIs.
local output = require("lathe.output")
local util = require("lathe.util")
local commands = require("lathe.commands")

local M = {}

local STACKTRACE_NS = vim.api.nvim_create_namespace("lathe_stacktrace")
local STACKTRACE_HL = "LatheStackFrame"
vim.api.nvim_set_hl(0, STACKTRACE_HL, { link = "Underlined", default = true })

-- clear = true so re-requiring this module (e.g. a plugin-manager reload)
-- replaces rather than duplicates these autocmds, matching lathe.lua's own
-- LathePlugin augroup.
local STACKTRACE_AUGROUP = vim.api.nvim_create_augroup("LatheStacktrace", { clear = true })

local frame_locations = {}
local jump_keymaps_set = {}

-- Tracks the most recently entered window showing a Java buffer, so a jump from the docked
-- output window lands in the editor window the source was already open in, instead of
-- replacing the output window's own buffer.
local last_java_win

local lathe_client = util.client

vim.api.nvim_create_autocmd("BufEnter", {
  group = STACKTRACE_AUGROUP,
  callback = function(ev)
    if vim.bo[ev.buf].filetype == "java" then
      last_java_win = vim.api.nvim_get_current_win()
    end
  end,
})

local function jump_to_resolved_frame(bufnr)
  local locations = frame_locations[bufnr]
  local row = vim.api.nvim_win_get_cursor(0)[1]
  local target = locations and locations[row]
  if not target then
    return
  end

  if last_java_win and vim.api.nvim_win_is_valid(last_java_win) then
    vim.api.nvim_set_current_win(last_java_win)
  end

  vim.cmd("edit " .. vim.fn.fnameescape(vim.uri_to_fname(target.uri)))
  -- Java stack-trace line numbers are 1-based, as is the cursor row; clamp defensively in case the
  -- resolved file has drifted from the line the trace named.
  local line = math.max(1, math.min(target.line, vim.api.nvim_buf_line_count(0)))
  vim.api.nvim_win_set_cursor(0, { line, 0 })
end

local function ensure_jump_keymaps(bufnr)
  if jump_keymaps_set[bufnr] then
    return
  end

  jump_keymaps_set[bufnr] = true
  local opts = { buffer = bufnr, desc = "Lathe: jump to resolved stack frame" }
  for _, key in ipairs({ "<CR>", "gF" }) do
    vim.keymap.set("n", key, function()
      jump_to_resolved_frame(bufnr)
    end, opts)
  end
end

--- Underlines a resolved frame's `File.java:line` span, the link to its source. `frame` is one
--- lathe.stack.resolve entry; its columns are UTF-16 (LSP), the extmark's are bytes.
local function highlight_frame_span(bufnr, row, text, frame)
  local start_col = vim.str_byteindex(text, "utf-16", frame.startColumn, false)
  local end_col = vim.str_byteindex(text, "utf-16", frame.endColumn, false)
  pcall(vim.api.nvim_buf_set_extmark, bufnr, STACKTRACE_NS, row - 1, start_col, {
    end_col = end_col,
    hl_group = STACKTRACE_HL,
  })
end

-- Decoration runs once a run finishes, when the live buffer already holds the streamed
-- lines, so the first read normally finds them. The bounded re-read is kept as a cheap
-- guard against being called a tick early.
local DECORATE_MAX_ATTEMPTS = 12
local DECORATE_RETRY_MS = 50

local function is_empty(lines)
  return #lines == 0 or (#lines == 1 and lines[1] == "")
end

--- Sends the output buffer's lines to the server, which answers the frames among them that it
--- resolved to a source, then underlines each and binds the jump to its line. The buffer only
--- grows while a run streams, so an answered row still holds the line that was sent; one that
--- does not is skipped.
local function decorate_stack_frames(bufnr, attempt)
  attempt = attempt or 1
  if not vim.api.nvim_buf_is_valid(bufnr) then
    return
  end

  local lines = vim.api.nvim_buf_get_lines(bufnr, 0, -1, false)
  if is_empty(lines) then
    if attempt < DECORATE_MAX_ATTEMPTS then
      vim.defer_fn(function()
        decorate_stack_frames(bufnr, attempt + 1)
      end, DECORATE_RETRY_MS)
    end

    return
  end

  local client = lathe_client()
  if not client then
    return
  end

  util.exec(client, nil, commands.STACK_RESOLVE, { { lines = lines } }, function(frames)
    vim.schedule(function()
      if not vim.api.nvim_buf_is_valid(bufnr) then
        return
      end

      local locations = {}
      frame_locations[bufnr] = locations
      for _, frame in ipairs(frames or {}) do
        local row = frame.index + 1
        local text = lines[row]
        if text and vim.api.nvim_buf_get_lines(bufnr, row - 1, row, false)[1] == text then
          highlight_frame_span(bufnr, row, text, frame)
          locations[row] = { uri = frame.uri, line = frame.line }
        end
      end

      ensure_jump_keymaps(bufnr)
    end)
  end, { silent = true })
end

local live_decorate_pending = false

--- Once a run finishes, decorate the shared live buffer's stack frames. Coalesced so a multi-spec
--- file run decorates once, and the frame namespace is cleared first so a re-run does not stack
--- duplicate marks onto the buffer that output.reset() already emptied.
function M.decorate_live_output()
  if live_decorate_pending or not output.current_bufnr() then
    return
  end

  live_decorate_pending = true
  vim.schedule(function()
    live_decorate_pending = false
    local buf = output.current_bufnr()
    if buf then
      vim.api.nvim_buf_clear_namespace(buf, STACKTRACE_NS, 0, -1)
      decorate_stack_frames(buf)
    end
  end)
end

-- The live buffer is created with bufhidden=hide, so closing its window hides rather than
-- wipes it and BufWipeout alone would leak the per-buffer decoration tables. BufHidden fires
-- the moment the buffer stops being shown, which is also when its buffer-local jump keymaps
-- become unreachable.
vim.api.nvim_create_autocmd({ "BufHidden", "BufWipeout" }, {
  group = STACKTRACE_AUGROUP,
  callback = function(ev)
    frame_locations[ev.buf] = nil
    jump_keymaps_set[ev.buf] = nil
  end,
})

return M
