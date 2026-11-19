# Lathe

[![Maven Central](https://img.shields.io/maven-central/v/io.github.ag-libs/lathe-maven-extension?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.ag-libs/lathe-maven-extension)
[![CI](https://github.com/ag-libs/lathe/actions/workflows/ci.yml/badge.svg)](https://github.com/ag-libs/lathe/actions/workflows/ci.yml)

Lathe is a Java language server that learns your project from its build.
It runs `javac` as you edit using compiler settings from Maven, Gradle, or the OpenJDK `make` build.
When one module generates a class and another imports it, you can follow that import straight to the generated Java source.

[First build](#first-build) · [Editors](#editors) · [AI agents](#ai-agents)

![Java completion, diagnostics, running, debugging and testing with Lathe in Neovim](docs/demo-038eec79.gif)

<!-- dev/demo/record.sh regenerates the GIF and updates its content hash. -->

*In Neovim, you can run a main method, rerun a single test, or stop at a breakpoint using the classes your build already compiled.*

## First build

Choose the build for your project and run its commands from the project root.
This installs the language server and creates the `.lathe/` workspace that your editor or coding agent uses.
Lathe writes a `.gitignore` inside it, so git ignores the directory with no change to your project.
The Maven and Gradle commands below also capture supported JUnit test launches without running the tests.

<details>
<summary>Maven</summary>

Use Maven 3.6.3 or later with JDK 17 or newer.
Create `.mvn/extensions.xml` at the project root with the following content.
If the file already exists, add the `<extension>` entry inside its existing `<extensions>` element.

```xml
<extensions>
  <extension>
    <groupId>io.github.ag-libs</groupId>
    <artifactId>lathe-maven-extension</artifactId>
    <version>0.3.1</version>
  </extension>
</extensions>
```

```sh
mvn clean test -Dlathe.capture.only=true
```

In Windows PowerShell, quote the property: `mvn clean test '-Dlathe.capture.only=true'`.

[Maven guide](docs/guide/maven.md)

</details>

<details>
<summary>Gradle</summary>

Use Gradle 8.8 or later with JDK 17 or newer.
Add the plugin to the `plugins` block in your root settings file (`settings.gradle.kts` or `settings.gradle`).

```kotlin
plugins {
    id("io.github.ag-libs.lathe") version "0.3.1"
}
```

```sh
./gradlew latheSync -Plathe.capture.only=true --continue
```

For Groovy settings files, Windows commands, or a personal init script, see the [Gradle guide](docs/guide/gradle.md#setup).

</details>

<details>
<summary>OpenJDK (make)</summary>

If you work on the JDK itself, configure the OpenJDK checkout and build it with `make jdk`.
Then use Maven 3.6.3 or later running on JDK 17 or newer to sync Lathe with the JDK you built.

```sh
mvn io.github.ag-libs:lathe-openjdk-maven-plugin:sync
```

[OpenJDK setup and demo](docs/guide/openjdk.md#setup)

</details>

## Editors

[Neovim](#neovim) · [VS Code and Cursor](#vs-code-and-cursor) · [Emacs](docs/guide/editors/emacs.md) · [Zed](docs/guide/editors/zed.md)

On Windows, use VS Code, Cursor, or Zed; the Neovim and Emacs clients run on Linux and macOS.

### Neovim

On Linux or macOS with Neovim 0.12+, install the editor plugin once, then set up each project through its build.
If you already run `nvim-jdtls` and want to keep both servers, follow the [jdtls setup notes](docs/guide/installation.md#coexisting-with-another-java-language-server-jdtls).

#### Install the plugin

Choose the snippet for your plugin manager.

**lazy.nvim** — If your config imports `lua/plugins/`, save this as `~/.config/nvim/lua/plugins/lathe.lua`.
Restart Neovim and run `:Lazy install`.

```lua
return {
  "ag-libs/lathe.nvim",
  ft = "java",
  cmd = "LatheStart",
  main = "lathe",
  opts = {},
}
```

**Built-in package manager** — Add these lines to `~/.config/nvim/init.lua`, restart Neovim, and accept the install prompt.

```lua
vim.pack.add({ "https://github.com/ag-libs/lathe.nvim" })
require("lathe").setup()
```

After the [first build](#first-build), open a Java source file in the project and Lathe attaches automatically.
Once the workspace has loaded, use **Go to Definition** on a method call to open its source, even in another module.
For completion, type part of a name and invoke your usual completion command.
To run a `main` method, put the cursor inside it and type `:LatheRun`.

Lathe picks up Java source edits automatically.
After changing dependencies, compiler settings, or modules, run `:LatheSync` in Neovim or rerun the Lathe-enabled build.

<details>
<summary>Set up Java indentation, automatic completion, tests, and debugging</summary>

- **Indentation.** Install the Java Tree-sitter parser.
  With nvim-treesitter, run `:TSInstall java`.
- **Completion menu and keymaps.** The demo uses blink.cmp for suggestions as you type and Telescope for navigation.
  You can copy those pieces into your existing config.
  [Example init.lua](examples/nvim/init.lua) · [Try the example separately](examples/nvim/README.md#try-it)
- **JUnit tests.** Install neotest and add `require("lathe.neotest")` to its `adapters` list.
  The Maven and Gradle steps above capture the test launch for supported JUnit Platform projects.
  [Neotest setup](docs/guide/editors/neovim.md#tests-neotest) · [Test capture requirements](docs/guide/test-capture.md#requirements-and-current-limits)
- **Breakpoints.** Load nvim-dap before Lathe so the plugin can register `:LatheDebug`.
  With lazy.nvim, add `dependencies = { "mfussenegger/nvim-dap" },` to the Lathe spec above.
  [Debugger setup and keymaps](docs/guide/editors/neovim.md#debug-nvim-dap)

</details>

### VS Code and Cursor

The **Lathe for Java** preview provides completion, compiler diagnostics, navigation, rename, and refactoring.
Use **Rename Symbol** on an interface method and Lathe updates its implementations and callers across modules.
Lathe's run, test, and debug controls are currently available in Neovim.

1. Complete the [first build](#first-build) to create the `.lathe/` directory that the extension looks for.
2. Download the `.vsix` release asset and choose **Extensions → ⋯ → Install from VSIX…** in VS Code or Cursor.
   [Lathe releases](https://github.com/ag-libs/lathe/releases)
3. Open the project folder.
   If **Language Support for Java by Red Hat** is installed, disable it for this workspace so only one server handles your Java files.
4. Open a Java file and choose **Go to Definition** on a method call.
   Try a library method to jump into the dependency's source when its sources are available.

[VS Code and Cursor guide](docs/guide/editors/vscode.md)

## AI agents

An MCP server gives coding agents access to the same compiler and project information as your editor.
After changing a method signature, ask your agent “Which callers in other modules still need updating?”
Lathe can compile the affected callers and report the file and line of each error.
The initial build installs the MCP launcher.
[Connect your agent](docs/guide/ai-agents.md)

## Documentation

[Features](docs/guide/features.md) · [How Lathe works](docs/guide/how-it-works.md) · [Installation](docs/guide/installation.md) · [Troubleshooting](docs/guide/troubleshooting.md) · [Project status](docs/status.md)

## Contributing

We welcome questions, bug reports, and feedback through [GitHub issues](https://github.com/ag-libs/lathe/issues).
Code contributions are currently closed.
[Contributing policy](CONTRIBUTING.md)

Lathe is developed with AI coding tools, mainly Claude Code.
A maintainer reviews every change and takes responsibility for it.
AI-assisted commits carry a `Co-Authored-By` trailer, following the [Apache Software Foundation's guidance](https://www.apache.org/legal/generative-tooling.html).

## License

[Apache License 2.0](LICENSE)
