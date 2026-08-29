-- Apply the resolved indentation profile's baseline widths. For the editor_config profile these are
-- a 4-space fallback that Neovim's built-in EditorConfig overrides afterward (it runs after
-- ftplugins); for the google profile they are the fixed 2-space widths.
require("lathe.indent").apply_buffer_options(vim.api.nvim_get_current_buf())

vim.bo.autoindent = true
vim.bo.smartindent = false
vim.bo.cindent = false

pcall(vim.treesitter.start, 0, "java")

local buf = vim.api.nvim_get_current_buf()
vim.schedule(function()
  vim.bo[buf].indentexpr = "v:lua.require'lathe.indent'.indentexpr()"
end)
