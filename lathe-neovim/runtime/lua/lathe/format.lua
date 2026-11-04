-- Formatting a Java buffer without reopening its imports fold: on demand (:LatheFormat) and, when
-- asked for, on every save.
local M = {}

-- On-demand formatting that keeps a closed imports fold from springing open (NV-3). Map a format
-- key to this instead of raw vim.lsp.buf.format, which reopens the fold on the buffer rewrite.
-- A pom.xml has no server formatter, so it routes client-side through xmllint; every other buffer
-- goes through the fold-preserving google-java-format path.
function M.format(bufnr, opts)
  bufnr = bufnr or vim.api.nvim_get_current_buf()
  if vim.fs.basename(vim.api.nvim_buf_get_name(bufnr)) == "pom.xml" then
    require("lathe.pom").format_buffer(bufnr)
    return
  end

  require("lathe.fold").format(bufnr, opts)
end

--- Registers :LatheFormat and, with `format_on_save`, formatting on every save.
function M.setup(format_on_save)
  local augroup = vim.api.nvim_create_augroup("LatheFormatOnSave", { clear = true })
  -- Format-on-save follows the server's advertised formatting capability (enabled by a workspace
  -- style file or the client option), checked per attach, rather than a static setup() flag -- so a
  -- project whose formatter comes from .lathe/style.json gets it without a global formatter option.
  if format_on_save == true then
    local fold = require("lathe.fold")
    vim.api.nvim_create_autocmd("LspAttach", {
      group = augroup,
      callback = function(args)
        local client = vim.lsp.get_client_by_id(args.data.client_id)
        if not (client and client.name == "lathe") then
          return
        end

        if not client.server_capabilities.documentFormattingProvider then
          return
        end

        -- Format-on-save rewrites the buffer, which reopens ufo's imports fold on every save
        -- (NV-3). Snapshot the fold's state before the format, then restore it after ufo settles.
        local imports_were_closed = false
        vim.api.nvim_create_autocmd("BufWritePre", {
          group = augroup,
          buffer = args.buf,
          callback = function()
            imports_were_closed = fold.imports_closed(args.buf)
            vim.lsp.buf.format({ bufnr = args.buf, id = args.data.client_id, async = false })
          end,
        })
        vim.api.nvim_create_autocmd("BufWritePost", {
          group = augroup,
          buffer = args.buf,
          callback = function()
            fold.reclose_imports(args.buf, imports_were_closed)
          end,
        })
      end,
    })
  end

  -- Manual formatting is a no-op unless the server advertises it, so :LatheFormat is always
  -- registered; it routes through M.format so the imports fold survives the whole-document rewrite.
  vim.api.nvim_create_user_command("LatheFormat", function()
    M.format(vim.api.nvim_get_current_buf())
  end, { desc = "Lathe: format the current buffer (preserving the imports fold)" })
end

return M
