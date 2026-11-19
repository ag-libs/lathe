# Neovim setups the comparison counts

Each file is a complete, minimal Neovim 0.12 config that gets its server answering a first query on the headline projects (the 2026-11-19 amendment): `<server>.lua` to a first answer, `<server>-debug.lua` with running and debugging a main method added.
Each follows its own project's documented setup: Lathe's [Neovim guide](../../../docs/guide/editors/neovim.md), and nvim-jdtls's README with the `jdtls` launcher script jdtls ships.

`report.py --headline` counts them by one rule:
- **lines**: lines that are neither blank nor a comment;
- **downloads**: `-- download:` lines, each a file a user fetches by hand (a plugin `vim.pack.add` fetches is a line, not a download);
- **commands**: `-- run:` lines, each a shell or editor command a user runs once before the first answer.

A `-- needs:` line names what must already be installed (a JDK) and is not counted.
