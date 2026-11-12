-- Apply the resolved indentation profile's baseline widths. For the editor_config profile these are
-- a 4-space fallback that Neovim's built-in EditorConfig overrides afterward (it runs after
-- ftplugins); widths the project's style pins are restored below, once it has.
require("lathe.indent").apply_buffer_options(vim.api.nvim_get_current_buf())

vim.bo.autoindent = true
vim.bo.smartindent = false
vim.bo.cindent = false

pcall(vim.treesitter.start, 0, "java")

local buf = vim.api.nvim_get_current_buf()
-- The buffer may be gone by then: a picker previewing files opens and wipes them quickly.
vim.schedule(function()
  if not vim.api.nvim_buf_is_valid(buf) then
    return
  end

  require("lathe.indent").apply_project_indent(buf)
  vim.bo[buf].indentexpr = "v:lua.require'lathe.indent'.indentexpr()"
end)

-- Scheduled so a same-tick setup() (on the lazy ft=java load path, config runs
-- before this ftplugin is sourced) has set _configured first. ftplugin is the only
-- client code that loads even when setup() was never called, so it's the one place
-- that can surface "installed but not configured".
vim.schedule(function()
  if vim.api.nvim_buf_is_valid(buf) then
    require("lathe").warn_if_not_ready(buf)
  end
end)
