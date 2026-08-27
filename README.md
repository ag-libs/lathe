# Lathe

Lathe is a Java language server for Maven projects.
It uses Maven itself as the source of truth:
the compiler shim records the exact `javac` parameters Maven used,
and the Maven plugin refreshes workspace metadata and dependency sources.

Setup is a single `.mvn/extensions.xml` registration.
The Lathe Maven extension injects the compiler shim, the `init`/`sync` goals, and the test-capture
dependency into the effective build in memory, so no `pom.xml` edits are needed.

Project documentation:

- [Current status](docs/status.md)
- [Roadmap](docs/roadmap.md)
- [Design index](docs/design-index.md)
- [Architecture](docs/lathe-design.md)

## Demo

<!-- TODO: replace with an inline demo clip (upload the MP4 to a GitHub issue/release and paste
     the user-attachments URL here so it renders as an inline player). Keep it ~40s: diagnostics,
     run a `main`, set a breakpoint, step, inspect a variable. Record against a public or
     `com.example` project only -- never a private codebase. -->

_Demo video coming soon — a short run-and-debug session._


## Building from Source (Internal Preview)

Lathe is currently at the M1 Internal Preview stage and must be built from source.

```bash
git clone https://github.com/ag-libs/lathe.git
cd lathe
mvn install -DskipTests
```

## Installation

Register the Lathe Maven extension once, in `.mvn/extensions.xml` at the reactor root (the directory
you run `mvn` from):

```xml
<extensions>
    <extension>
        <groupId>io.github.ag-libs</groupId>
        <artifactId>lathe-maven-extension</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </extension>
</extensions>
```

That is the only edit — no `pom.xml` changes anywhere. Before the build runs, the extension injects
into the effective model, in memory:

- the `lathe-compiler` shim on `maven-compiler-plugin` (selected via `maven.compiler.compilerId=lathe`),
  for every module — including modules with their own compiler configuration or a separate parent POM;
- the `lathe-maven-plugin` `init` (bound to `initialize`) and `sync` (bound to `process-test-classes`)
  goals, once at the reactor root;
- the `lathe-junit` test-scope dependency that enables run/test capture, for every module.

All injected artifacts use the extension's own version, so they stay in lockstep with the version you
register. Because the extension operates on each resolved project directly, split reactor-root/parent
layouts and per-module compiler blocks need no special handling.

Initialize Lathe with:

```bash
mvn clean process-test-classes
```

`clean` is required on the first run so that Maven recompiles all classes through the Lathe
compiler shim rather than skipping compilation due to existing output.
Subsequent refreshes can omit `clean`:

```bash
mvn process-test-classes
```

A refresh also happens automatically during any normal build that reaches `process-test-classes`,
such as `mvn test`, `mvn package`, or `mvn install`.

Add `.lathe/` to `.gitignore`.

## What The Build Writes

`lathe:init` creates `.lathe/` at the workspace root on the first build.
It runs automatically at the `initialize` phase — no manual invocation needed.

The compiler shim writes compilation parameter files under `.lathe/` as each module compiles.
Those files contain the classpath, module path, source roots, generated-source locations,
annotation processor settings, and other `javac` inputs that the language server needs.

`lathe:sync` resolves dependency source JARs through Maven and writes `workspace.json`.
The source JARs are downloaded into the normal local Maven repository.
They are resolved on demand by Maven instead of being guessed from POM text.
The write is skipped when the content is unchanged, so a no-op build does not trigger
a server reload.

## Test Capture

The neotest test runner (see [Neovim Setup](#test-runner-neotest)) replays tests from the captured
`.lathe/` bytecode with no recompilation. To know *how* to launch that replay JVM — the exact
classpath, module path, and JVM arguments Maven's Surefire fork used — Lathe captures the real launch
from inside the test fork. That capture is done by `lathe-junit`, a small `test`-scoped dependency the
extension injects into every module — nothing to add by hand.

No `maven-surefire-plugin` configuration is needed: `lathe-junit` registers a JUnit Platform
`LauncherSessionListener` through the standard service-loader SPI, and Surefire's JUnit Platform
provider auto-detects it.

On any build that actually runs tests (`mvn test`, `mvn verify`, `mvn install`), the listener fires
once per module, before test execution, and writes `.lathe/<module>/test-launch.json`. It records —
from live JVM introspection, not by parsing Surefire's command line:

- `java.home` (replay uses the same JDK),
- the fork classpath (with `lathe-junit`'s own jar removed, so replay never has to strip it),
- the module path and module directives (`--patch-module`, `--add-opens` / `--add-reads` /
  `--add-exports`, `--add-modules`),
- and the remaining JVM args from `<argLine>`.

Because these are read *after* the JVM expanded Surefire's argfile, the captured template is the
**effective, interpreted** launch — the module graph the tests actually ran under — rather than a
guess reconstructed from POM text.

Requirements and current limits:

- **A modern, JPMS-capable Surefire (e.g. 3.5.5+).** An old Surefire forks non-modularly and yields
  an empty argument list, so nothing meaningful is captured. Pin `maven-surefire-plugin` to a recent
  version if your build inherits an older one.
- **JUnit Platform (JUnit 5/6, or the JUnit 4 vintage engine).** The listener rides the JUnit
  Platform launcher; pure TestNG forks are not captured.
- **`<systemPropertyVariables>` are not captured yet** — Surefire sets them via a booter properties
  file that is invisible to JVM introspection. Known gap.
- A module whose tests are skipped or absent produces no `test-launch.json`, so its tests are not
  runnable from the editor until a build actually forks them.

## Neovim Setup

Lathe provides a distributable plugin for Neovim 0.11+ that configures native LSP,
format-on-save, and source cache autocommands out of the box.

### Supported LSP Features

Lathe implements the following LSP endpoints. In Neovim 0.11+, most are mapped automatically:

- `textDocument/publishDiagnostics`: Surfaces `javac` errors, warnings exactly as configured in Maven, plus hints for unused private members.
  - Neovim API: `vim.diagnostic.goto_next()` / `goto_prev()`
  - Neovim default: `]d` / `[d`
- `textDocument/completion`: Type, method, and variable completion. Includes automatic import insertion.
- `textDocument/definition`: Resolves to local files, unpacked dependency JAR sources, and JDK sources.
  - Neovim API: `vim.lsp.buf.definition()`
  - Neovim default: `<C-]>` (via `tagfunc`); most configs also remap `gd`
- `textDocument/declaration`: Navigates to the interface or abstract method contract being overridden.
  - Neovim API: `vim.lsp.buf.declaration()`
  - Neovim default: None; most configs remap `gD`
- `textDocument/implementation`: Finds concrete implementations of an interface method, or all subtypes of a class or interface across the workspace.
  - Neovim API: `vim.lsp.buf.implementation()`
  - Neovim default: `gri`
- `typeHierarchy`: Navigates supertypes and subtypes of the symbol under the cursor.
  - Neovim API: `vim.lsp.buf.typehierarchy('supertypes')` / `vim.lsp.buf.typehierarchy('subtypes')`
  - No Neovim default; add explicit keybinds (e.g. `<leader>ts` / `<leader>ti`)
- `callHierarchy`: Navigates incoming and outgoing calls of the method under the cursor.
  - Neovim API: `vim.lsp.buf.incoming_calls()` / `vim.lsp.buf.outgoing_calls()`
  - No Neovim default; add explicit keybinds (e.g. `<leader>ci` / `<leader>co`)
- `textDocument/hover`: Displays AST-resolved Javadoc formatted as Markdown.
  - Neovim API: `vim.lsp.buf.hover()`
  - Neovim default: `K`
- `textDocument/signatureHelp`: Shows method and constructor parameter lists.
  - Neovim API: `vim.lsp.buf.signature_help()`
  - Neovim default: `<C-S>` (insert mode)
- `textDocument/references`: Finds usages across the workspace.
  - Neovim API: `vim.lsp.buf.references()`
  - Neovim default: `grr`
- `textDocument/codeAction`: Supports four quick fixes: Import missing type, Add `throws` clause, Wrap with `try/catch`, and Declare local variable.
  - Neovim API: `vim.lsp.buf.code_action()`
  - Neovim default: `gra`
- `textDocument/formatting`: Applies `google-java-format` to the full document and removes unused imports.
  - Neovim API: `vim.lsp.buf.format()`
  - *(Lathe plugin automatically configures format-on-save)*
- `textDocument/semanticTokens`: Highlights static and deprecated members, enum constants, type parameters, and annotations beyond what tree-sitter covers. Works automatically when the server attaches.
- `textDocument/foldingRange`: Provides Java structural folding for classes, methods, blocks, and import groups. Works automatically with fold providers.
- `workspace/symbol` and `textDocument/documentSymbol`: Search for types across the workspace, or list all symbols in the current file as an outline. Beyond exact-prefix matching, workspace symbol search also matches CamelCase-hump abbreviations against your own project's types — e.g. `ASF` or `ServerFactory` both find `AbstractServerFactory`, `TaskMgr` finds `TaskManager` — so you don't need to type a type's full prefix to find it.
  - Neovim API: `vim.lsp.buf.workspace_symbol()` / `vim.lsp.buf.document_symbol()`
  - Neovim default: `gO` (document symbols)

### Installation

Load the plugin as a local directory with `lazy.nvim`, pointing `dir` at the Neovim runtime
installed by `lathe:sync`:

```lua
{
  dir = vim.fn.expand("~/.cache/lathe/current/neovim"),
  ft = "java",
  config = function()
    require("lathe").setup()
  end,
}
```

> **Note:** The `config` function is required. Without it, lazy.nvim only sources the
> `ftplugin` (indentation), but the LSP server is never registered.

### Test Runner (neotest)

Lathe includes an adapter for [neotest](https://github.com/nvim-neotest/neotest): gutter
signs for discovered test methods and classes, run-under-cursor, and pass/fail status.
Discovery and execution both go through the already-running Lathe LSP server rather than a
treesitter-query scan or a separate Maven invocation -- `lathe.runnables.list` for discovery
(real attributed-analysis, not syntax guessing) and `lathe.run.test` to run, replaying from
captured `.lathe/` bytecode with no recompilation per run.

> **Prerequisite:** running tests requires the [Test Capture](#test-capture) setup (the
> `lathe-junit` test dependency). Discovery works without it, but a run has no captured
> `test-launch.json` to replay until a build with `lathe-junit` on the test classpath has run the
> tests at least once.

Recognizes files matching Surefire's own default include patterns (`Test*.java`,
`*Test.java`, `*Tests.java`, `*TestCase.java`) as test files. These are hardcoded as a
reasonable default for now; a project that overrides Surefire's `<includes>` won't be
picked up correctly yet. Main methods aren't runnable yet either.

```lua
{
  "nvim-neotest/neotest",
  dependencies = { "nvim-neotest/nvim-nio", "nvim-lua/plenary.nvim" },
  ft = "java",
  config = function()
    require("neotest").setup({
      adapters = { require("lathe.neotest") },
    })
  end,
}
```

> **Note:** Not part of `require('lathe').setup()` -- neotest is an optional dependency, so
> the adapter is configured separately, the same way every neotest adapter is.

### Running Tests

The Lathe plugin adds no key mappings, and neotest ships none either, so bind the actions you
want. A useful starting set:

```lua
local neotest = require("neotest")
local lathe = require("lathe.neotest")

vim.keymap.set("n", "<leader>tt", neotest.run.run,                                        { desc = "Test: run nearest" })
vim.keymap.set("n", "<leader>tf", function() neotest.run.run(vim.fn.expand("%")) end,     { desc = "Test: run file" })
vim.keymap.set("n", "<leader>tp", function() neotest.run.run(vim.fn.expand("%:p:h")) end, { desc = "Test: run package (current dir)" })
vim.keymap.set("n", "<leader>tl", neotest.run.run_last,                                   { desc = "Test: run last" })
vim.keymap.set("n", "<leader>tS", lathe.stop,                                             { desc = "Test: stop / cancel run" })
vim.keymap.set("n", "<leader>ts", neotest.summary.toggle,                                 { desc = "Test: toggle summary tree" })
vim.keymap.set("n", "<leader>to", lathe.open_output,                                      { desc = "Test: output (docked, navigable)" })
```

Stop uses `require("lathe.neotest").stop()`, not neotest's `run.stop()`: the replay runs
server-side, so Lathe cancels it by asking the server to kill the replay JVM (SIGTERM, escalating to
SIGKILL for a hung test). Use this to stop a test that hangs.

Console output streams live into a docked split at the bottom of the screen; toggle it with
`<leader>to` (`require("lathe.neotest").open_output()`). The first line (dimmed) is the exact
`java` replay command that ran the tests, so you can see and copy what executed. stdout and stderr
are distinguished, and pressing `<CR>` or `gF` on a stack-trace frame in that window jumps straight
to the failing source line.

Use this docked window rather than neotest's built-in floating output
(`require("neotest").output.open(...)` / `:Neotest output`): the float shows raw text only,
without Lathe's source-link navigation.

Pass/fail status also shows as gutter signs and in the summary tree (`<leader>ts`), and a failing
test places a diagnostic on its failing assertion line (jump between them with `]d` / `[d`), so you
can reach a failure without opening the output window. Discovery is automatic -- opening a test file
shows its runnable tests, and adding, renaming, or removing a `@Test` method updates them on save,
with no manual refresh.

### Debugger (nvim-dap)

Lathe can debug a captured test or `main` class with breakpoints, stepping, call stack, and variable
inspection, using [nvim-dap](https://github.com/mfussenegger/nvim-dap) as the client.
There is no separate debug-adapter process and Lathe never runs Maven: the language server launches
the replay JVM under a JDWP agent (suspended) and hosts Microsoft's `java-debug` adapter in-process,
so debugging has the same footprint as a normal replay plus one JVM flag.

> **Prerequisite:** the same [Test Capture](#test-capture) setup as the test runner (a captured
> launch template to replay), plus `nvim-dap` installed and loaded **before**
> `require('lathe').setup()` — Lathe registers the `lathe` debug adapter during setup, so declare
> `nvim-dap` as a dependency of your Lathe spec to guarantee the load order.

```lua
{ "mfussenegger/nvim-dap" },

-- ...and make your Lathe spec depend on it so :LatheDebug registers:
{
  dir = "/path/to/lathe/runtime",  -- however you load Lathe
  ft = "java",
  dependencies = { "mfussenegger/nvim-dap" },
  config = function()
    require("lathe").setup()
  end,
}
```

> **Note:** you do **not** call any `nvim-dap` `setup()` or register an adapter yourself —
> `require('lathe').setup()` registers the `lathe` adapter. If `nvim-dap` is not installed the
> `:LatheDebug` command is simply not created, and the rest of Lathe is unaffected.

`:LatheDebug` debugs the test or `main` class under the cursor — the innermost test (method, class,
or package) that contains it, otherwise a `main` — resolved through the same `lathe.runnables.list`
discovery the run and test surfaces use. Breakpoints, stepping, and stopping are nvim-dap's — bind
the actions you want:

```lua
local dap = require("dap")
local widgets = require("dap.ui.widgets")

vim.keymap.set("n", "<leader>dd", "<cmd>LatheDebug<cr>",  { desc = "Debug: test/main under cursor" })
vim.keymap.set("n", "<leader>db", dap.toggle_breakpoint,  { desc = "Debug: toggle breakpoint" })
vim.keymap.set("n", "<leader>dc", dap.continue,           { desc = "Debug: continue / start" })
vim.keymap.set("n", "<leader>do", dap.step_over,          { desc = "Debug: step over" })
vim.keymap.set("n", "<leader>di", dap.step_into,          { desc = "Debug: step into" })
vim.keymap.set("n", "<leader>dO", dap.step_out,           { desc = "Debug: step out" })
vim.keymap.set("n", "<leader>dv", function() widgets.centered_float(widgets.scopes) end,
                                                          { desc = "Debug: variables (scopes)" })
vim.keymap.set("n", "<leader>dt", dap.terminate,          { desc = "Debug: terminate" })
```

Set a breakpoint with `<leader>db`, then `<leader>dd` to launch and attach; execution stops on the
line. Inspect locals with `<leader>dv` (the scopes float) or a UI plugin like
[nvim-dap-ui](https://github.com/rcarriga/nvim-dap-ui) /
[nvim-dap-view](https://github.com/igorlfs/nvim-dap-view), which render their variables panel from
the DAP scopes/variables requests.

### Verbose Logging

For troubleshooting, you can enable verbose logging by setting `LATHE_DEBUG=1` before starting
Neovim, or configuring it in your environment:

```bash
export LATHE_DEBUG=1
```

Neovim logs LSP traffic to its normal LSP log. For example:

```bash
tail -f ~/.local/state/nvim/lsp.log
```

*(Note: The plugin automatically configures format-on-save.)*


## Run Configuration

Lathe launches your test runs and `main`-class runs from the captured or derived launch templates
described above, with generated defaults.
To customize a launch — extra JVM flags, program arguments, environment variables, a working
directory, or additional class-/module-path entries — supply a thin **overlay**.
The overlay is applied by the language server, so it behaves the same regardless of which editor or
client triggered the run.

Overlays are read from two optional, hand-authored files that share one schema and merge into a single
effective overlay. Lathe never creates or writes them:

| File | Scope | Committed? |
|---|---|---|
| `lathe-run.json` (reactor root) | shared, travels with the repo | yes, at your discretion |
| `.lathe/run.json` | machine-local, per developer | no — it lives inside the gitignored `.lathe/` |

Put team-wide settings in the committable `lathe-run.json`; keep machine-specific paths and
secret-bearing `env` in the local `.lathe/run.json`.
When both layers set the same field the local one wins — scalars override, lists concatenate, and
`env` entries union.
When neither file exists, runs use the built-in defaults, so **no configuration is required**.

Each file is a JSON **array** of overlay entries. An entry is scoped by `kind` (`MAIN` or `TEST`, the
only required field) and an optional `module` — the module path relative to the workspace root (the
same key used under `.lathe/<module>/`). **Omit `module` to apply the entry to every module** of that
kind:

```json
[
  { "kind": "TEST", "jvmArgs": ["-Duser.timezone=UTC", "-XX:+EnableDynamicAgentLoading"] },

  {
    "kind": "MAIN",
    "module": "services/app",
    "jvmArgs": ["-Dspring.profiles.active=dev"],
    "args": ["--port", "8080"],
    "env": { "APP_ENV": "dev" },
    "cwd": "services/app",
    "classpathAppend": ["config/dev"]
  }
]
```

`kind` is the only required field; **every field below is optional**, an omitted field keeps the
generated default, and an entry that sets nothing is a no-op.

| Field | Effect |
|---|---|
| `jvmArgs` | Appended after the captured/derived JVM args — on a duplicate `-D`/`-X`, yours wins |
| `args` | Appended to the program arguments |
| `env` | Merged into the run's environment; it never replaces the inherited environment |
| `cwd` | Working directory, resolved relative to the workspace root (absolute allowed) |
| `classpathAppend` | Extra class-path entries, appended after the derived class path (workspace-root-relative; absolute allowed) |
| `modulePathAppend` | Extra module-path entries, appended after the derived module path |

The overlay is deliberately limited to these user-owned inputs.
It **cannot** change launch-correctness fields — the module path, class path, `--patch-module`, the
captured `--add-opens` / `--add-reads` / `--add-exports` / `--add-modules` directives, or dependency
placement — so an overlaid run can never diverge from how Maven would have launched it.
`classpathAppend` / `modulePathAppend` only *add* entries after the derived ones; they cannot remove
or reorder them.

### How an overlay is selected

Selection is automatic — there is no picker.
For a run of module `M`, kind `K`, Lathe applies the most specific matching entry:

1. the entry for that exact `(module, kind)`, if any;
2. otherwise the entry for that `kind` with no `module` — the **workspace-wide default**;
3. otherwise the built-in defaults, unchanged.

The most specific match wins **as a whole** — a module entry is used *instead of* the workspace one,
not merged with it. (The shared and local *layers* of the chosen entry still field-merge, as above.)
An overlay never changes *what* runs (the test or class you launched); it only overlays *how* that
launch is configured.

Named, explicitly-selected configurations — entries that add a `name` and a pinned target to the same
array — are planned but not yet selectable.

## Opt-out and CI

Lathe is active by default and skips automatically in CI environments:

| Condition | Effect |
|---|---|
| `CI` environment variable is set | both `init` and `sync` are skipped |
| `-Dlathe.skip=true` | disabled regardless of other settings |
| `-Dlathe.skip=false` | enabled, overrides `CI` |

## Partial builds

When Maven is invoked with `-pl`, `lathe:sync` skips writing `workspace.json`
to avoid overwriting the full workspace manifest with a partial view.
Module params files are still written by the compiler shim for compiled modules.
To force a workspace manifest write from a partial build, pass `-Dlathe.sync.force=true`.

## Troubleshooting

### Neovim Info: `.lathe` directory not found

This means `lathe:init` has not run, or the extension is not registered.
Run `mvn clean process-test-classes` at the reactor root to initialize Lathe.
If `.lathe/` is still missing, verify that `.mvn/extensions.xml` registers `lathe-maven-extension`
and that you are running `mvn` from the directory that contains `.mvn/`.

### Missing params file (`Run mvn process-test-classes to activate module`)

The LS cannot find the compiler shim parameters for the module you are editing.
Re-run `mvn process-test-classes` to force the compiler shim to generate them.

### LSP Server Crashing or Not Attaching

Check the Neovim LSP logs (`tail -f ~/.local/state/nvim/lsp.log`) for errors.
Set `export LATHE_DEBUG=1` before launching Neovim
to get verbose compiler logging from the server.
