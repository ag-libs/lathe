vim.bo.expandtab = true
vim.bo.shiftwidth = 2
vim.bo.softtabstop = 2
vim.bo.tabstop = 2

vim.bo.autoindent = true
vim.bo.smartindent = false
vim.bo.cindent = false

pcall(vim.treesitter.start, 0, "java")

local buf = vim.api.nvim_get_current_buf()
vim.schedule(function()
  vim.bo[buf].indentexpr = "v:lua.require'lathe.indent'.indentexpr()"
end)
