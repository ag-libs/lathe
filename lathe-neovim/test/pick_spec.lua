-- Verifies lathe.pick, the built-in fuzzy picker used without Telescope: a default text fills the
-- prompt and filters the list before the first keystroke (NV-15), and without one the prompt is
-- empty and every item is listed.
--
-- Run headless from the repo root (or via run-specs.sh):
--   nvim --headless --clean -u NONE \
--     --cmd "set rtp+=lathe-neovim/runtime" \
--     --cmd "set rtp+=lathe-neovim/test" \
--     -l lathe-neovim/test/pick_spec.lua

local spec = require("spec_helper").new()
local pick = require("lathe.pick")

local items = { "alpha.yml", "beta.yml", "application.yml" }

-- The picker's two windows: the prompt (focused) and the results list.
local function open(default_text)
  pick.pick({ items = items, title = "Lathe: resources", default_text = default_text })
  local prompt = vim.api.nvim_get_current_line()
  local results = {}
  for _, win in ipairs(vim.api.nvim_list_wins()) do
    local buf = vim.api.nvim_win_get_buf(win)
    if win ~= vim.api.nvim_get_current_win() and vim.api.nvim_win_get_config(win).relative ~= "" then
      results = vim.api.nvim_buf_get_lines(buf, 0, -1, false)
    end
  end
  vim.api.nvim_feedkeys(vim.api.nvim_replace_termcodes("<Esc>", true, false, true), "x", false)
  vim.cmd("stopinsert")
  -- <Esc> cancels and closes the picker; close what is left of it either way.
  for _, win in ipairs(vim.api.nvim_list_wins()) do
    pcall(function()
      if vim.api.nvim_win_get_config(win).relative ~= "" then
        vim.api.nvim_win_close(win, true)
      end
    end)
  end
  return prompt, results
end

local prompt, results = open("appl")
spec.check("a default text fills the prompt", prompt, "appl")
spec.check("and filters the list from the start", table.concat(results, ","), "application.yml")

-- A match after a multibyte character is highlighted on that character's bytes, not two early.
items = { "\u{25A3}  app  schema.graphqls" }
pick.pick({ items = items, default_text = "schema" })
local marks = {}
for _, win in ipairs(vim.api.nvim_list_wins()) do
  local buf = vim.api.nvim_win_get_buf(win)
  if win ~= vim.api.nvim_get_current_win() and vim.api.nvim_win_get_config(win).relative ~= "" then
    for _, mark in ipairs(vim.api.nvim_buf_get_extmarks(buf, -1, 0, -1, { details = true })) do
      marks[#marks + 1] = vim.api.nvim_buf_get_text(buf, mark[2], mark[3], mark[2], mark[4].end_col, {})[1]
    end
  end
end
spec.check("highlights fall on the matched characters", table.concat(marks), "schema")
for _, win in ipairs(vim.api.nvim_list_wins()) do
  pcall(function()
    if vim.api.nvim_win_get_config(win).relative ~= "" then
      vim.api.nvim_win_close(win, true)
    end
  end)
end
vim.cmd("stopinsert")
items = { "alpha.yml", "beta.yml", "application.yml" }

prompt, results = open(nil)
spec.check("no default text: an empty prompt", prompt, "")
spec.check("and every item", #results, #items)

spec.finish()
