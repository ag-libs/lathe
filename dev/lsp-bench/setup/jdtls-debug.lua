-- jdtls with running and debugging: the java-debug bundle loaded into jdtls, and nvim-dap.
-- needs: a JDK 21 or newer to run jdtls, and python3 for its launcher script
-- download: https://download.eclipse.org/jdtls/milestones/1.63.0/jdt-language-server-1.63.0-202610191322.tar.gz
-- download: https://open-vsx.org/api/vscjava/vscode-java-debug/0.60.0/file/vscjava.vscode-java-debug-0.60.0.vsix
-- run: mkdir -p ~/.local/share/jdtls && tar xzf jdt-language-server-*.tar.gz -C ~/.local/share/jdtls
-- run: ln -s ~/.local/share/jdtls/bin/jdtls ~/.local/bin/jdtls
-- run: unzip -j vscjava.vscode-java-debug-0.60.0.vsix 'extension/server/*.jar' -d ~/.local/share/java-debug
vim.pack.add({ "https://github.com/mfussenegger/nvim-jdtls", "https://github.com/mfussenegger/nvim-dap" })
vim.api.nvim_create_autocmd("FileType", {
  pattern = "java",
  callback = function()
    local root = vim.fs.root(0, { "mvnw", "gradlew", ".git" })
    -- One workspace data directory per project: jdtls keeps its project model there.
    local data = vim.fn.stdpath("cache") .. "/jdtls/" .. vim.fn.fnamemodify(root, ":t")
    local bundles = vim.fn.glob(vim.fn.expand("~/.local/share/java-debug") .. "/com.microsoft.java.debug.plugin-*.jar", true, true)
    require("jdtls").start_or_attach({
      cmd = { "jdtls", "--jvm-arg=-Xmx2G", "-data", data },
      root_dir = root,
      init_options = { bundles = bundles },
      -- The main classes as nvim-dap configurations, so :DapContinue can launch one.
      on_attach = function()
        require("jdtls.dap").setup_dap_main_class_configs()
      end,
    })
  end,
})
