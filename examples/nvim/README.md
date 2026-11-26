# Sample Neovim config (Lathe demo)

The [example init.lua](init.lua) sets up completion with blink.cmp, navigation with Telescope, JUnit test results with neotest, debugging with nvim-dap and dap-view, and toasts with snacks.nvim.
Copy the pieces you want into your config, or try the whole example in an isolated Neovim session.
For a minimal installation, use the [Neovim quick start](../../README.md#neovim).

## Try it

Use Neovim 0.12 or later on Linux or macOS.
Install Git, the `tree-sitter` CLI, and a C compiler so nvim-treesitter can build its parsers.

From the Maven or Gradle project you want to open, replace `/path/to/lathe` with your Lathe checkout and `SomeFile.java` with a source file in the project.
The following command keeps the example's plugins and settings separate from your usual ones.

```sh
XDG_CONFIG_HOME=/path/to/lathe/examples \
XDG_DATA_HOME=/tmp/lathe-demo/data \
XDG_STATE_HOME=/tmp/lathe-demo/state \
  nvim SomeFile.java
```

The first launch downloads lazy.nvim and the plugins listed in `init.lua`.
Run `:LatheInit` to set the project up: one build, and the project's build files are not changed.
A project whose build already registers Lathe needs no setup: Lathe starts the server that build installed.
If you are developing the client itself, set `LATHE_NVIM_DIR` to your checkout's `lathe-neovim/runtime` directory.

## Keymaps

Leader is Space. The Lathe-relevant bindings:

| Key | Action |
|---|---|
| `gd` / `grr` / `gri` | Go to definition / references / implementations (via Telescope) |
| `grN` / `grh` | Instantiation sites of the type / full type hierarchy |
| `grT` | Go to the class's test, or from a test to its class |
| `gO` | Document symbols |
| `<leader>ws` | Workspace symbols (reactor-wide, server-ranked) |
| `K` / `<C-s>` | Hover / signature help (`<C-k>` in insert mode) |
| `gra` / `grn` | Code action / rename (Neovim's built-in keys) |
| `<leader>li` | Add missing imports |
| `<leader>rr` | Run the `main` under the cursor |
| `<leader>tt` / `<leader>tf` | Run nearest test / file tests |
| `<leader>to` / `<leader>ts` | Test output (docked) / test summary |
| `<leader>td` / `<leader>tq` | Failed test's expected vs actual / results with times |
| `<leader>db` / `<leader>dd` | Toggle breakpoint / debug the test or `main` under the cursor |
| `<leader>dc` / `<leader>dt` | Continue / terminate the debug session |
| `<leader>ff` / `<leader>fg` | Find files / live grep |
