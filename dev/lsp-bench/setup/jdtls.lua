-- jdtls through nvim-jdtls, with the launcher script jdtls ships (bin/jdtls).
-- needs: a JDK 21 or newer to run jdtls, and python3 for its launcher script
-- download: https://download.eclipse.org/jdtls/milestones/1.63.0/jdt-language-server-1.63.0-202610191322.tar.gz
-- run: mkdir -p ~/.local/share/jdtls && tar xzf jdt-language-server-*.tar.gz -C ~/.local/share/jdtls
-- run: ln -s ~/.local/share/jdtls/bin/jdtls ~/.local/bin/jdtls
vim.pack.add({ "https://github.com/mfussenegger/nvim-jdtls" })
vim.api.nvim_create_autocmd("FileType", {
  pattern = "java",
  callback = function()
    local root = vim.fs.root(0, { "mvnw", "gradlew", ".git" })
    -- One workspace data directory per project: jdtls keeps its project model there.
    local data = vim.fn.stdpath("cache") .. "/jdtls/" .. vim.fn.fnamemodify(root, ":t")
    require("jdtls").start_or_attach({
      cmd = { "jdtls", "--jvm-arg=-Xmx2G", "-data", data },
      root_dir = root,
    })
  end,
})
