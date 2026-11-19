-- Lathe with running and debugging: lathe.nvim wires nvim-dap itself when it is installed.
-- needs: the JDK the project builds with (17 or newer)
-- run: :LatheInit (once per project: the build that writes .lathe/)
vim.pack.add({ "https://github.com/ag-libs/lathe.nvim", "https://github.com/mfussenegger/nvim-dap" })
require("lathe").setup()
