# Lathe

Lathe is a Java language server for Maven projects.
It uses Maven itself as the source of truth:
the compiler shim records the exact `javac` parameters Maven used,
and the Maven plugin refreshes workspace metadata and dependency sources.

Lathe setup has two parts:

- `lathe-compiler` is configured as the `maven-compiler-plugin` compiler.
- `lathe-maven-plugin` declares two goals in the reactor root:
  `init` (auto-bound to `initialize`) and `sync` (auto-bound to `process-test-classes`).

Project documentation:

- [Current status](docs/status.md)
- [Roadmap](docs/roadmap.md)
- [Design index](docs/design-index.md)
- [Architecture](docs/lathe-design.md)


## Building from Source (Internal Preview)

Lathe is currently at the M1 Internal Preview stage and must be built from source.

```bash
git clone https://github.com/ag-libs/lathe.git
cd lathe
mvn install -DskipTests
```

## Installation

Choose the Lathe version once in the POM where you manage plugin versions:

```xml
<properties>
    <lathe.version>0.1.0-SNAPSHOT</lathe.version>
</properties>
```

Configure the compiler shim wherever the effective `maven-compiler-plugin` configuration
for Java modules is defined.
For a simple project, this is usually the parent POM:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <dependencies>
        <dependency>
            <groupId>io.github.ag-libs</groupId>
            <artifactId>lathe-compiler</artifactId>
            <version>${lathe.version}</version>
        </dependency>
    </dependencies>
    <configuration>
        <compilerId>lathe</compilerId>
    </configuration>
</plugin>
```

Keep any existing compiler configuration.
Only add the `lathe-compiler` dependency and `<compilerId>lathe</compilerId>`.

Bind both goals in the reactor root POM:

```xml
<plugin>
    <groupId>io.github.ag-libs</groupId>
    <artifactId>lathe-maven-plugin</artifactId>
    <version>${lathe.version}</version>
    <inherited>false</inherited>
    <executions>
        <execution>
            <id>lathe-init</id>
            <goals>
                <goal>init</goal>
            </goals>
        </execution>
        <execution>
            <id>lathe-sync</id>
            <goals>
                <goal>sync</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

Do not add explicit `<phase>` elements.
`init` defaults to `initialize` (the first Maven phase, before `compile`).
`sync` defaults to `process-test-classes` (after main and test compilation).

The `<inherited>false</inherited>` entry is important in multi-module reactors.
Both goals are reactor-level steps and should run once from the root,
not once for every child module.

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
from inside the test fork. That capture is done by `lathe-junit`, a small published artifact you add
as a `test`-scoped dependency where every test module inherits it (usually the parent POM):

```xml
<dependencies>
    <dependency>
        <groupId>io.github.ag-libs</groupId>
        <artifactId>lathe-junit</artifactId>
        <version>${lathe.version}</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

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
`<leader>to` (`require("lathe.neotest").open_output()`). stdout and stderr are distinguished,
and pressing `<CR>` or `gF` on a stack-trace frame in that window jumps straight to the failing
source line.

Use this docked window rather than neotest's built-in floating output
(`require("neotest").output.open(...)` / `:Neotest output`): the float shows raw text only,
without Lathe's source-link navigation.

Pass/fail status also shows as gutter signs and in the summary tree (`<leader>ts`), and a failing
test places a diagnostic on its failing assertion line (jump between them with `]d` / `[d`), so you
can reach a failure without opening the output window. Discovery is automatic -- opening a test file
shows its runnable tests, and adding, renaming, or removing a `@Test` method updates them on save,
with no manual refresh.

### Debugging

For debugging, you can enable verbose logging by setting `LATHE_DEBUG=1` before starting Neovim,
or configuring it in your environment:

```bash
export LATHE_DEBUG=1
```

Neovim logs LSP traffic to its normal LSP log. For example:

```bash
tail -f ~/.local/state/nvim/lsp.log
```

*(Note: The plugin automatically configures format-on-save.)*


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

## Non-Trivial Maven Projects

Some projects have a reactor root that is not the same POM as the parent used by Java modules.
In that layout, install the two Lathe pieces in different places:

- Put `lathe-maven-plugin` with `<inherited>false</inherited>` in the reactor root POM,
  the POM from which users run `mvn clean process-test-classes`.
- Put the `maven-compiler-plugin` Lathe configuration in every parent or standalone POM
  that controls Java compilation for modules in the reactor.

Do not assume the top-level reactor POM is inherited by every module.
Check each module's effective compiler configuration.
If a module has its own `maven-compiler-plugin` block,
add the Lathe compiler dependency and `<compilerId>lathe</compilerId>` there too.

## Agent Installation Prompt

Use this prompt when asking an agent to configure Lathe in an existing Maven project:

```text
Configure Lathe in this Maven project.

Use version 0.1.0-SNAPSHOT unless the repository already defines a Lathe version property.

Install three pieces:

1. Configure maven-compiler-plugin so Java modules use the Lathe compiler shim.
   Add dependency io.github.ag-libs:lathe-compiler:${lathe.version}
   to the effective maven-compiler-plugin configuration and set <compilerId>lathe</compilerId>.
   Preserve all existing compiler configuration, annotation processor paths,
   release/source/target settings, compiler args, plugin management, and executions.

2. Bind io.github.ag-libs:lathe-maven-plugin:${lathe.version} in the reactor root POM.
   Add two executions: id lathe-init with goal init, and id lathe-sync with goal sync.
   Do not specify phases; init defaults to initialize and sync defaults to process-test-classes.
   Set <inherited>false</inherited> so both goals run once at the reactor root.

3. Add io.github.ag-libs:lathe-junit:${lathe.version} as a <scope>test</scope> dependency
   where every test module inherits it (usually the parent POM). This enables the editor test
   runner by capturing each module's real test-fork launch into .lathe/<module>/test-launch.json.
   It needs no maven-surefire-plugin configuration (it self-registers via the JUnit Platform SPI),
   but requires a JPMS-capable Surefire (e.g. 3.5.5+); if the build inherits an older Surefire,
   pin maven-surefire-plugin to a recent version. Skip this piece if the project only needs LSP
   code intelligence and not the test runner.

Do not assume the reactor root POM is the parent POM.
Inspect the Maven structure first.
If Java modules inherit compiler configuration from a separate parent POM,
put the compiler shim there.
If standalone modules or example projects define their own compiler plugin configuration,
add the shim there as well.
The lathe-junit test dependency belongs wherever test modules inherit their common dependencies,
which may again be a different POM from the reactor root.

After editing, run:

mvn clean process-test-classes

If mvnd is used, stop the daemon after installing updated Lathe JARs so it picks up the new version:

mvnd --stop
```

## Troubleshooting

### Neovim Info: `.lathe` directory not found

This means `lathe:init` has not run, or the POM is misconfigured.
Run `mvn lathe:init` (or `mvn clean process-test-classes` if the POM is configured)
at the reactor root to initialize Lathe. If `.lathe/` is still missing, verify the plugin configuration in your POM.

### Missing params file (`Run mvn process-test-classes to activate module`)

The LS cannot find the compiler shim parameters for the module you are editing.
Re-run `mvn process-test-classes` to force the compiler shim to generate them.

### LSP Server Crashing or Not Attaching

Check the Neovim LSP logs (`tail -f ~/.local/state/nvim/lsp.log`) for errors.
Set `export LATHE_DEBUG=1` before launching Neovim
to get verbose compiler logging from the server.
