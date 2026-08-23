-- Stack-trace navigation for the shared live-output buffer
-- (docs/done/lathe-test-output-streaming.md). Resolution works from the frame text alone (§4) --
-- no server-side command and no metadata recorded when the transcript streamed. decorate_live_output()
-- drives it once a run finishes, so it applies identically to both run paths: tests (lathe.neotest)
-- and `main` classes (lathe.run), which share one output buffer via lathe.output.
--
-- Uses only core vim.lsp/vim.api (no neotest/nio), like lathe.output and lathe.stacktrace, so it
-- loads headlessly and from the main-run path, which is deliberately neotest/nio-free.

-- Safe to require at load: both use only core Neovim APIs.
local output = require("lathe.output")
local stacktrace = require("lathe.stacktrace")

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

local function lathe_client()
  return vim.lsp.get_clients({ name = "lathe" })[1]
end

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

--- Underlines the `File.java:line` span of a resolved frame so the clickable
--- target reads as a link. `entry` is one frames_in_buffer() record: `text` is
--- the buffer row and `row` its 1-based index. Marks only the file:line span,
--- not the whole frame. Returns whether an extmark was placed.
local function highlight_frame_span(bufnr, entry)
  local span = ("%s:%d"):format(entry.frame.file, entry.frame.line)
  local start = entry.text:find(span, 1, true)
  if not start then
    return false
  end

  local span_start = start - 1
  pcall(vim.api.nvim_buf_set_extmark, bufnr, STACKTRACE_NS, entry.row - 1, span_start, {
    end_col = span_start + #span,
    hl_group = STACKTRACE_HL,
  })
  return true
end

-- Decoration runs once a run finishes, when the live buffer already holds the streamed
-- lines, so the first scan normally finds them. The bounded re-scan is kept as a cheap
-- guard against being called a tick early.
local DECORATE_MAX_ATTEMPTS = 12
local DECORATE_RETRY_MS = 50

--- Collects the stack frames in the output buffer. The live buffer holds one
--- logical line per row (we append transcript lines directly -- no terminal
--- hard-wrap to undo), so each frame maps to a single row. Returns a list of
--- { frame = <parse_frame result>, row = <1-based buffer row>, text = <row text> }.
local function frames_in_buffer(bufnr)
  local raw = vim.api.nvim_buf_get_lines(bufnr, 0, -1, false)
  local frames = {}
  for i, text in ipairs(raw) do
    local frame = stacktrace.parse_frame(text)
    if frame then
      frames[#frames + 1] = { frame = frame, row = i, text = text }
    end
  end

  return frames
end

--- Identity of a frame's class for resolution caching: the simple name alone
--- would collide for same-named classes in different packages, which
--- pick_candidate distinguishes by package.
local function frame_key(frame)
  return frame.simple_name .. "#" .. frame.package
end

--- Resolves the source location of each distinct class in `frames` via workspace/symbol, then
--- invokes `done(resolved)` with a frame_key -> location (or false) map. Fires one core-async
--- request per distinct class and counts completions -- no nio, so the main-run path can drive it.
local function resolve_frames(client, bufnr, frames, done)
  local by_key = {}
  local order = {}
  for _, entry in ipairs(frames) do
    local key = frame_key(entry.frame)
    if by_key[key] == nil then
      by_key[key] = entry.frame
      order[#order + 1] = key
    end
  end

  local resolved = {}
  local pending = #order
  if pending == 0 then
    done(resolved)
    return
  end

  for _, key in ipairs(order) do
    local frame = by_key[key]
    client:request("workspace/symbol", { query = frame.simple_name }, function(_err, symbols)
      local candidate = stacktrace.pick_candidate(frame, symbols)
      resolved[key] = candidate and candidate.location or false
      pending = pending - 1
      if pending == 0 then
        done(resolved)
      end
    end, bufnr)
  end
end

--- Scans the output buffer for stack frames and resolves each via the standard
--- workspace/symbol request -- no new server-side command, no metadata recorded when the
--- transcript streamed; resolution works from the frame text alone (§4).
local function decorate_stack_frames(bufnr, attempt)
  attempt = attempt or 1
  if not vim.api.nvim_buf_is_valid(bufnr) then
    return
  end

  local frames = frames_in_buffer(bufnr)
  if #frames == 0 then
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

  resolve_frames(client, bufnr, frames, function(resolved)
    -- Re-scan after the async resolution: more lines may have streamed in while the LSP request
    -- was in flight, so highlight against a fresh read rather than the pre-resolution frames.
    vim.schedule(function()
      if not vim.api.nvim_buf_is_valid(bufnr) then
        return
      end

      local locations = {}
      frame_locations[bufnr] = locations
      for _, entry in ipairs(frames_in_buffer(bufnr)) do
        local location = resolved[frame_key(entry.frame)]
        if location then
          highlight_frame_span(bufnr, entry)
          -- Jump to the frame's own line in the resolved file. workspace/symbol only locates the
          -- class (its declaration), so location.range points at the class, not the failing line --
          -- pair the resolved uri with the frame's parsed line instead.
          locations[entry.row] = { uri = location.uri, line = entry.frame.line }
        end
      end

      ensure_jump_keymaps(bufnr)
    end)
  end)
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
