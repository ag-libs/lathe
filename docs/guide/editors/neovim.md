# Lathe in Neovim — cheatsheet

The Neovim client: how to install it and a suggested keymap for every action, so you can wire your own
config. For *what* each feature does (editor-agnostic), see the
[feature reference in the README](../../../README.md#features).
Requires **Neovim 0.11.7+** (0.12+ recommended) and the Java Treesitter parser for indentation
(`:TSInstall java`). The default `editor_config` indent profile needs Neovim's native EditorConfig
support (0.12+); on 0.11.x, select a different indent profile.

Lathe adds **no key mappings of its own** — it provides LSP endpoints, the `:Lathe*` commands, and a
few Lua entry points; you bind the ones you want. Each section below is a table of what's available:
the action, the command or API that implements it, its Neovim default (if any), and a suggested
mapping. The suggested keymaps are a coherent starting set, not defaults.

## Install

Load the plugin as a local directory with `lazy.nvim`, pointing `dir` at the Neovim runtime installed
by `lathe:sync`:

```lua
{
  dir = vim.fn.expand("~/.cache/lathe/current/neovim"),
  ft = "java",
  config = function()
    require("lathe").setup()
  end,
}
```

> **The `config` function is required.** Without it, lazy.nvim only sources the `ftplugin`
> (indentation); the LSP server is never registered.

`setup()` options:

| Option | Default | Meaning |
|---|---|---|
| `indent_style` | `"editor_config"` | live-editing indent profile: `"editor_config"` (follow `.editorconfig`, else a 4-space Java baseline) or `"google"` (2-space block, 4-space continuation) |
| `continuation_indent` | `nil` | pin the wrapped-line indent width; when `nil`, derived as 2× the block indent |
| `formatter` | `nil` | full-document formatter; set `"google"` to enable google-java-format |
| `format_on_save` | `false` | wire format-on-save (takes effect only with `formatter = "google"`) |
| `capabilities` | `make_client_capabilities()` | LSP capabilities table |

See [Formatting & indentation](#formatting--indentation) below.

## LSP actions

Lathe implements these endpoints; in Neovim 0.11+ most already have a default mapping. Bind or rebind
freely.

| Action | Neovim API | Default | Suggested |
|---|---|---|---|
| Go to definition (local, dependency, and JDK sources) | `vim.lsp.buf.definition()` | `<C-]>` (`tagfunc`) | `gd` |
| Go to declaration (overridden contract) | `vim.lsp.buf.declaration()` | — | `gD` |
| Go to implementation / subtypes | `vim.lsp.buf.implementation()` | `gri` | `gri` |
| Find references | `vim.lsp.buf.references()` | `grr` | `grr` |
| Hover (AST-resolved Javadoc) | `vim.lsp.buf.hover()` | `K` | `K` |
| Signature help | `vim.lsp.buf.signature_help()` | `<C-s>` (insert) | `<C-k>` |
| Completion (with auto-import) | `vim.lsp.completion` / omnifunc | `<C-x><C-o>` | auto |
| Code action (import type · add `throws` · wrap `try/catch` · declare local) | `vim.lsp.buf.code_action()` | `gra` | `<leader>ca` |
| Format document (opt-in — needs `formatter = "google"`) | `vim.lsp.buf.format()` | — | `<leader>f` |
| Document symbols (outline) | `vim.lsp.buf.document_symbol()` | `gO` | `gO` |
| Workspace symbols (CamelCase-hump aware) | `vim.lsp.buf.workspace_symbol()` | — | `<leader>ws` |
| Type hierarchy (super / sub) | `vim.lsp.buf.typehierarchy("supertypes"/"subtypes")` | — | `<leader>hs` / `<leader>hi` |
| Call hierarchy (incoming / outgoing) | `vim.lsp.buf.incoming_calls()` / `outgoing_calls()` | — | `<leader>ci` / `<leader>co` |
| Next / previous diagnostic | `vim.diagnostic.jump({count=1/-1})` | `]d` / `[d` | `]d` / `[d` |
| Semantic tokens (static/deprecated members, enum constants, type params, annotations) | automatic | — | — |
| Folding (classes, methods, blocks, import groups) | automatic (with a fold provider) | — | — |

Diagnostics (`javac` errors/warnings, plus unused-private-member hints) publish automatically.
Formatting is opt-in — see [Formatting & indentation](#formatting--indentation).

The server auto-starts when you open a `.java` file. To use workspace navigation (e.g. workspace
symbols) *before* opening one — from a dashboard or an empty buffer — run **`:LatheStart`**, which
starts the server for the current directory's `.lathe` workspace and attaches it to the current
buffer.

## Formatting & indentation

Live-editing **indentation** is always on; full-document **formatting** is opt-in. They are separate,
so you can get useful Java indentation without letting Lathe reformat files whose style contract isn't
Google Java Format.

**Indentation** — chosen with `indent_style`:

- `editor_config` (default) — follows the project's `.editorconfig` via Neovim's native EditorConfig
  support (Lathe never parses `.editorconfig` itself). With no matching `.editorconfig`, a 4-space Java
  baseline applies. The wrapped-line (continuation) indent defaults to 2× the block width; pin it with
  `continuation_indent`.
- `google` — 2-space block, 4-space continuation.

**Formatting** — set `formatter = "google"` to enable google-java-format (whole document, with import
cleanup). It is off by default. When enabled it runs on demand via `vim.lsp.buf.format()`; add
`format_on_save = true` to also format on write (that autocmd is wired only when `formatter =
"google"`). Range and on-type formatting are intentionally disabled, so a stray client request can't
trigger a whole-document rewrite.

To keep the previous "Google format on save" behaviour:

```lua
require("lathe").setup({
  indent_style = "google",
  formatter = "google",
  format_on_save = true,
})
```

## Run a `main`

`:LatheRun` replays the `main` under the cursor — the one whose method or class the cursor is in, or
the file's only `main` — from the captured `.lathe/` bytecode, with no recompilation.
A `▶` sign marks runnable `main`s in the gutter.
Output streams live into the docked split (see [Output surface](#output-surface--stack-frame-navigation)).

| Action | Command | Suggested |
|---|---|---|
| Run the `main` under the cursor | `:LatheRun` | `<leader>rr` |
| Stop the in-flight run | `:LatheRunStop` | `<leader>rs` |

## Tests (neotest)

Lathe ships an adapter for [neotest](https://github.com/nvim-neotest/neotest): gutter signs for
discovered tests, run-under-cursor, and pass/fail status. Discovery and execution both go through the
running Lathe server (`lathe.runnables.list` / `lathe.run.test`), replaying from `.lathe/` bytecode.
It is **optional** — configured separately from `require('lathe').setup()`, like any neotest adapter.

```lua
{
  "nvim-neotest/neotest",
  dependencies = { "nvim-neotest/nvim-nio", "nvim-lua/plenary.nvim" },
  ft = "java",
  config = function()
    require("neotest").setup({ adapters = { require("lathe.neotest") } })
  end,
}
```

| Action | Entry point | Suggested |
|---|---|---|
| Run nearest test | `require("neotest").run.run()` | `<leader>tt` |
| Run file | `require("neotest").run.run(vim.fn.expand("%"))` | `<leader>tf` |
| Toggle summary tree | `require("neotest").summary.toggle()` | `<leader>ts` |
| Open output (docked, navigable) | `require("lathe.neotest").open_output()` | `<leader>to` |
| Stop / cancel run | `require("lathe.neotest").stop()` | `<leader>tS` |

Stop uses `require("lathe.neotest").stop()`, **not** neotest's `run.stop()`: the replay runs
server-side, so Lathe cancels it by asking the server to kill the replay JVM (SIGTERM, escalating to
SIGKILL for a hung test).
Pass/fail also shows as gutter signs and in the summary tree, and a failing test places a diagnostic on
its failing assertion line (jump with `]d` / `[d`).
Discovery is automatic — opening a test file shows its runnables, and add/rename/remove of a `@Test`
updates on save.

Recognizes Surefire's default include patterns (`Test*.java`, `*Test.java`, `*Tests.java`,
`*TestCase.java`); a project that overrides Surefire `<includes>` is not picked up yet.

## Debug (nvim-dap)

`:LatheDebug` debugs the test or `main` class under the cursor — the innermost test (method, class, or
package) that contains it, otherwise a `main` — via [nvim-dap](https://github.com/mfussenegger/nvim-dap).
The server launches the replay JVM under a suspended JDWP agent and hosts Microsoft's `java-debug`
adapter in-process — so there is no separate debug adapter to install or configure.

> **Prerequisites:** the same [Test Capture](../test-capture.md) setup as the test runner, plus `nvim-dap`
> installed and loaded **before** `require('lathe').setup()` — Lathe registers the `lathe` adapter
> during setup, so declare `nvim-dap` as a dependency of your Lathe spec. You do **not** call any
> nvim-dap `setup()` or register an adapter yourself. If nvim-dap is absent, `:LatheDebug` is simply
> not created.

Only `:LatheDebug` is Lathe's; the rest are nvim-dap's own functions, bound alongside it. The `dap` /
`widgets` entries assume `local dap = require("dap")` and `local widgets = require("dap.ui.widgets")`.

| Action | Command / function | Suggested |
|---|---|---|
| Debug the test / `main` under the cursor | `:LatheDebug` | `<leader>dd` |
| Toggle breakpoint | `dap.toggle_breakpoint` | `<leader>db` |
| Continue / start | `dap.continue` | `<leader>dc` |
| Step over | `dap.step_over` | `<leader>do` |
| Step into | `dap.step_into` | `<leader>di` |
| Step out | `dap.step_out` | `<leader>dO` |
| Terminate | `dap.terminate` | `<leader>dt` |
| Toggle REPL | `dap.repl.toggle` | `<leader>dr` |
| Variables (scopes float) | `widgets.centered_float(widgets.scopes)` | `<leader>dv` |
| Toggle UI panel (nvim-dap-view) | `require("dap-view").toggle` | `<leader>du` |

Set a breakpoint with `<leader>db`, then `<leader>dd` to launch and attach; execution stops on the
line. With no breakpoint, the debuggee runs to completion.
At a stopped frame you can **evaluate expressions** (including method calls) in the DAP REPL.

**Where the output goes — important.** The debuggee's stdout/stderr does **not** appear in nvim-dap's
REPL/console. Lathe streams it server-side into the **same docked split as tests** (see below), so open
that split (`<leader>to`) to read program output and errors. The DAP panel is for debugger control
only (variables, call stack, stepping).

For an IntelliJ-style panel (variables, call stack, breakpoints, threads, REPL), add a UI plugin. The
actively-maintained choice is [nvim-dap-view](https://github.com/igorlfs/nvim-dap-view):

```lua
{
  "igorlfs/nvim-dap-view",
  dependencies = { "mfussenegger/nvim-dap" },
  config = function()
    require("dap-view").setup({ auto_toggle = true })
    vim.keymap.set("n", "<leader>du", require("dap-view").toggle, { desc = "Debug: toggle UI panel" })
  end,
}
```

`auto_toggle = true` opens the panel when a Lathe session starts and closes it when the session ends.

## Output surface & stack-frame navigation

`:LatheRun`, the tests, and `:LatheDebug` all stream into one **docked split** at the bottom of the
screen; toggle it with `<leader>to` (`require("lathe.neotest").open_output()`), or it opens
automatically when a run starts.
The first line (dimmed) is the exact `java` replay command that ran, so you can see and copy what
executed. stdout and stderr are distinguished, and pressing **`<CR>` or `gF`** on a stack-trace frame
jumps straight to the failing source line.
Use this docked window rather than neotest's floating output (`:Neotest output`), which shows raw text
without the source-link navigation.

## Verbose logging

Set `LATHE_DEBUG=1` before starting Neovim to enable verbose (`FINE`) server logging:

```bash
export LATHE_DEBUG=1
```

Neovim logs LSP traffic to its normal LSP log:

```bash
tail -f ~/.local/state/nvim/lsp.log
```

## Troubleshooting (Neovim)

If the server exits unexpectedly, Lathe notifies you (`language server exited unexpectedly`); check the
LSP log above and set `LATHE_DEBUG=1` for verbose compiler logging. If it does not attach at all,
confirm the launcher exists (`:LatheStart` reports when it is missing) and run `mvn process-test-classes`.
For workspace-level issues (`.lathe/` not found, missing params file), see the **Troubleshooting**
section of the [README](../../../README.md#troubleshooting).
