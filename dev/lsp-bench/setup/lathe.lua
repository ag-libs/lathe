-- Lathe: the server is resolved by the project's build, not downloaded.
-- needs: the JDK the project builds with (17 or newer)
-- run: :LatheInit (once per project: the build that writes .lathe/)
vim.pack.add({ "https://github.com/ag-libs/lathe.nvim" })
require("lathe").setup()
