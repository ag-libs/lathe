# Sample Neovim config (Lathe demo)

The single-file Neovim configuration used to record Lathe's demo, kept here as a reference you can
read and borrow from. It is a **demonstration** — a small, working setup showing how Lathe fits into
a Java IDE config (completion, cross-module go-to-definition, running tests) — not a drop-in meant to
replace your own `~/.config/nvim` wholesale.

Everything lives in [`init.lua`](init.lua).

For the editor-agnostic feature reference see the [project README](../../README.md); for a
per-action keymap guide see [docs/guide/editors/neovim.md](../../docs/guide/editors/neovim.md).

## Try it

Requires **Neovim 0.12+**, the Java Treesitter parser (`:TSInstall java`), and a Maven project Lathe
has built (so its `.lathe/lathe-launcher.sh` exists). The config installs the client from the
standalone `ag-libs/lathe.nvim` repo; set `LATHE_NVIM_DIR` to a working-tree checkout to load that
instead. To try it in isolation without touching your own config:

```sh
XDG_CONFIG_HOME=/path/to/lathe/examples nvim SomeFile.java
```

On first launch Neovim bootstraps `lazy.nvim` and downloads the plugins, including `lathe.nvim`. The
server still comes from your Maven build; until a project is built, Lathe nudges you to run it.

## Keymaps

Leader is `\`. The Lathe-relevant bindings:

| Key | Action |
|---|---|
| `gd` / `grr` / `gri` | Go to definition / references / implementations (via Telescope) |
| `gO` | Document symbols |
| `K` / `<C-k>` | Hover / signature help |
| `<leader>ca` | Code action |
| `<leader>rr` | Run the `main` under the cursor |
| `<leader>tt` / `<leader>tf` | Run nearest test / file tests |
| `<leader>to` / `<leader>ts` | Test output (docked) / test summary |
| `<leader>ff` / `<leader>fg` | Find files / live grep |
