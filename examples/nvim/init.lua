-- Sample Neovim config used to record Lathe's demo.
--
-- A single-file, self-contained setup showing how Lathe fits into a small Java IDE config:
-- completion, cross-module go-to-definition, and running tests. It is a DEMONSTRATION you can read
-- and borrow from -- not a config meant to replace your own ~/.config/nvim wholesale.
--
-- Requires Neovim 0.12+ and the Java Treesitter parser (:TSInstall java). Lathe must be installed
-- (its Neovim client is unpacked to ~/.cache/lathe/current/neovim by `lathe:sync`); the config
-- skips the Lathe/neotest specs cleanly if it isn't there yet.

vim.g.mapleader = "\\"
vim.g.maplocalleader = "\\"

-- Rounded border for every floating window that doesn't set its own (hover, signature help, the
-- completion menu, lazy's own windows). Set before lazy bootstraps below.
vim.o.winborder = "rounded"

-- Bootstrap lazy.nvim.
local lazypath = vim.fn.stdpath("data") .. "/lazy/lazy.nvim"
if not (vim.uv or vim.loop).fs_stat(lazypath) then
  vim.fn.system({
    "git",
    "clone",
    "--filter=blob:none",
    "--branch=stable",
    "https://github.com/folke/lazy.nvim.git",
    lazypath,
  })
end
vim.opt.rtp:prepend(lazypath)

-- Lathe is a locally installed language server, so it loads from a local directory rather than a
-- GitHub URL. The client is unpacked here by `lathe:sync`; guard on its presence so opening a Java
-- file doesn't error on a machine where Lathe hasn't been built.
local lathe_dir = vim.fn.expand("~/.cache/lathe/current/neovim")
local lathe_available = (vim.uv or vim.loop).fs_stat(lathe_dir) ~= nil

require("lazy").setup({
  -- Colorscheme. High priority so its highlights exist before lualine themes off them.
  {
    "rebelot/kanagawa.nvim",
    priority = 1000,
    config = function()
      require("kanagawa").setup()
      vim.cmd.colorscheme("kanagawa")
    end,
  },

  -- Statusline.
  {
    "nvim-lualine/lualine.nvim",
    dependencies = { "nvim-tree/nvim-web-devicons" },
    config = function()
      require("lualine").setup({ options = { theme = "auto" } })
    end,
  },

  -- Syntax highlighting via Treesitter (ASTs instead of regex).
  {
    "nvim-treesitter/nvim-treesitter",
    branch = "main",
    build = ":TSUpdate",
    lazy = false,
    config = function()
      require("nvim-treesitter").install({ "java", "lua", "vimdoc", "bash", "json", "xml" })
      vim.api.nvim_create_autocmd("FileType", {
        callback = function(args)
          pcall(vim.treesitter.start, args.buf)
        end,
      })
    end,
  },

  -- Completion (blink.cmp): Tab selects/accepts, Enter accepts -- the IntelliJ convention.
  {
    "Saghen/blink.cmp",
    version = "*",
    config = function()
      require("blink.cmp").setup({
        keymap = {
          preset = "none",
          ["<Tab>"] = { "select_next", "snippet_forward", "fallback" },
          ["<S-Tab>"] = { "select_prev", "snippet_backward", "fallback" },
          ["<CR>"] = { "accept", "fallback" },
          ["<C-space>"] = { "show", "show_documentation", "hide_documentation" },
          ["<C-k>"] = { "show_signature", "hide_signature", "fallback" },
        },
        signature = { enabled = true },
      })
      -- Advertise blink's completion capabilities to every LSP server, Lathe included.
      vim.lsp.config("*", {
        capabilities = require("blink.cmp").get_lsp_capabilities(nil, true),
      })
    end,
  },

  -- Fuzzy finder. Used below to back go-to-definition / references with a previewable picker.
  {
    "nvim-telescope/telescope.nvim",
    dependencies = { "nvim-lua/plenary.nvim" },
    cmd = "Telescope",
    keys = {
      { "<leader>ff", "<cmd>Telescope find_files<cr>", desc = "Find Files" },
      { "<leader>fg", "<cmd>Telescope live_grep<cr>", desc = "Live Grep" },
      { "<leader>fb", "<cmd>Telescope buffers<cr>", desc = "Buffers" },
    },
  },

  -- Lathe: the Java language server (code intelligence, formatting, AST-aware indentation).
  {
    dir = lathe_dir,
    ft = "java",
    cmd = "LatheStart",
    cond = lathe_available,
    config = function()
      require("lathe").setup({
        indent_style = "google",
        formatter = "google",
        format_on_save = true,
      })
      -- Run the `main` under the cursor from .lathe/ bytecode; output streams into Lathe's docked
      -- split (reopen with <leader>to). Its own <leader>r group keeps it clear of tests (<leader>t).
      vim.keymap.set("n", "<leader>rr", "<cmd>LatheRun<cr>", { desc = "Run main under cursor" })
    end,
  },

  -- Test runner (neotest via Lathe's adapter): gutter signs, run-under-cursor, pass/fail status.
  -- Discovery and execution both go through the running Lathe server -- no separate Maven run.
  {
    "nvim-neotest/neotest",
    dependencies = { "nvim-neotest/nvim-nio", "nvim-lua/plenary.nvim" },
    ft = "java",
    cond = lathe_available,
    config = function()
      local neotest = require("neotest")
      local lathe_adapter = require("lathe.neotest")
      neotest.setup({ adapters = { lathe_adapter } })

      vim.keymap.set("n", "<leader>tt", neotest.run.run, { desc = "Run Nearest Test" })
      vim.keymap.set("n", "<leader>tf", function()
        neotest.run.run(vim.fn.expand("%"))
      end, { desc = "Run File Tests" })
      vim.keymap.set("n", "<leader>to", lathe_adapter.open_output, { desc = "Test Output (docked)" })
      vim.keymap.set("n", "<leader>ts", neotest.summary.toggle, { desc = "Test Summary" })
    end,
  },
})

-- ---------------------------------------------------------
-- Options
-- ---------------------------------------------------------
vim.opt.number = true
vim.opt.relativenumber = true
vim.opt.signcolumn = "yes"
-- Shorten CursorHold (default 4000ms) so cursor-driven UI (diagnostics) refreshes promptly.
vim.opt.updatetime = 250
-- Use the OS clipboard for all yank/delete/put. Needs wl-clipboard (Wayland) or xclip/xsel (X11).
vim.opt.clipboard = "unnamedplus"

-- Only show diagnostic virtual text for the current line, to cut clutter.
vim.diagnostic.config({
  virtual_text = { current_line = true },
  signs = true,
  underline = true,
})

-- ---------------------------------------------------------
-- LSP keymaps (buffer-local, set when Lathe attaches)
-- ---------------------------------------------------------
-- gd/grr/gri route through Telescope for a fuzzy, previewable list; K (hover) and grn (rename) stay
-- on Neovim's built-in defaults.
vim.api.nvim_create_autocmd("LspAttach", {
  callback = function(ev)
    local tel = require("telescope.builtin")
    vim.keymap.set("n", "gd", tel.lsp_definitions, { buffer = ev.buf, desc = "Go to Definition" })
    vim.keymap.set("n", "grr", tel.lsp_references, { buffer = ev.buf, desc = "References" })
    vim.keymap.set("n", "gri", tel.lsp_implementations, { buffer = ev.buf, desc = "Go to Implementation" })
    vim.keymap.set("n", "gO", tel.lsp_document_symbols, { buffer = ev.buf, desc = "Document Symbols" })
    -- Reactor-wide fuzzy symbol search. The default ranks by the whole path, so an exact name can
    -- lose to longer ones; a custom entry_maker/tiebreak can rank by name and prefer reactor sources.
    vim.keymap.set("n", "<leader>ws", tel.lsp_dynamic_workspace_symbols, { buffer = ev.buf, desc = "Workspace Symbols" })
    vim.keymap.set("n", "<C-k>", vim.lsp.buf.signature_help, { buffer = ev.buf, desc = "Signature Help" })
    vim.keymap.set("n", "<leader>ca", vim.lsp.buf.code_action, { buffer = ev.buf, desc = "Code Action" })
  end,
})
