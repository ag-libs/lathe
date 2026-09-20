# lathe.nvim

**A Java language server that works from your Maven build — no project import, no classpath setup.**

The Neovim client for [Lathe](https://github.com/ag-libs/lathe). This repository is a **generated,
one-way mirror** of the client that lives in the Lathe monorepo
(`lathe-maven-plugin/src/main/neovim`). File issues and open pull requests
[against the monorepo](https://github.com/ag-libs/lathe), not here.

## Requirements

- Neovim 0.12+
- The Java Treesitter parser: `:TSInstall java`
- A Maven project whose build enables the Lathe Maven extension, synced at least once with
  `mvn process-test-classes` — this installs the language server and creates the `.lathe/` workspace.
  See the [installation guide](https://github.com/ag-libs/lathe/blob/main/docs/guide/installation.md).

The client is editor-side only; the **server is resolved and installed by your Maven build**, so
installing this plugin is not enough on its own — the build must run too.

## Install

### Built-in package manager (Neovim 0.12+)

```lua
vim.pack.add({ 'https://github.com/ag-libs/lathe.nvim' })
require('lathe').setup()
```

### lazy.nvim

```lua
{
  'ag-libs/lathe.nvim',
  ft = 'java',
  config = function()
    require('lathe').setup()
  end,
}
```

`require('lathe').setup()` is required. Without it, only indentation loads and the language server is
never registered.

## Configuration & keymaps

Lathe adds no key mappings of its own — it provides LSP endpoints, `:Lathe*` commands, and a few Lua
entry points that you bind yourself. For `setup()` options, the full feature reference, and a suggested
keymap set, see the
[Neovim guide](https://github.com/ag-libs/lathe/blob/main/docs/guide/editors/neovim.md).

## License

Apache-2.0. See [LICENSE](./LICENSE).
