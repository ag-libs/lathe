-- Verifies the docked output split follows the tail of a run while it is not focused, and leaves
-- the view alone while it is. With 'wrap' set, the follow also puts the last line at the bottom row:
-- headless Neovim recomputes a stale view whenever a spec reads it, so that case cannot fail here
-- and is checked in a real UI.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-neovim/runtime" \
--     --cmd "set rtp+=lathe-neovim/test" \
--     -l lathe-neovim/test/output_follow_spec.lua

local spec = require("spec_helper").new()
local output = require("lathe.output")

local function stream(text)
  vim.lsp.handlers["lathe/testOutput"](nil, { line = { stream = "stdout", text = text } })
end

local function output_win()
  for _, win in ipairs(vim.api.nvim_list_wins()) do
    if vim.api.nvim_win_get_buf(win) == output.current_bufnr() then
      return win
    end
  end
  return nil
end

-- Lines several times the window's width, enough to wrap far past its height.
local function stream_long_lines(count)
  for i = 1, count do
    stream(("line %d %s"):format(i, string.rep("x", 400)))
  end
  vim.wait(100)
end

output.reset()
output.ensure_open()
local win = output_win()
spec.check("the split opens", win ~= nil, true)

-- Unwrapped (the default): the last line is in view.
stream_long_lines(40)
local last = vim.api.nvim_buf_line_count(output.current_bufnr())
spec.check("unwrapped: the last line is in view", vim.fn.line("w$", win), last)

-- Focused, the split does not follow: scrolling up to read stays where it is.
vim.api.nvim_set_current_win(win)
vim.cmd("normal! gg")
stream_long_lines(1)
spec.check("focused: the view stays where the user put it", vim.fn.line("w0", win), 1)

spec.finish("output_follow_spec")
