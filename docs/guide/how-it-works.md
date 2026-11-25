# How Lathe works

When you follow a call from one module into another, Lathe asks the JDK compiler which method the call resolves to.
To answer, the compiler needs the dependencies, generated sources, and flags for the module that contains the call.
Your build supplies those inputs.

## Built on the JDK compiler

Lathe runs `javac` inside the language server.
Through the JDK's Compiler Tree API (`com.sun.source`), it can read the types and declarations the compiler assigned to the code.
Diagnostics, completion, navigation, and type and call hierarchies all use that information.
The [MCP server](ai-agents.md) runs the same analysis for coding agents.

## Build capture

As Maven or Gradle compiles a module, the Lathe extension or plugin records the compiler arguments and paths under `.lathe/`.
Sync writes the module list to `workspace.json` and installs the launcher that your editor starts.
Subsequent builds refresh the modules they compile.
[Workspace files](installation.md#what-and-where-lathe-writes)

OpenJDK records its compiler arguments in the `make` build itself.
After you build the JDK, sync reads those arguments and writes the same workspace files.
[OpenJDK setup](openjdk.md#setup)

## Dependency & JDK sources

Go to definition can open the Java source for a library method or JDK class.
The build resolves available `-sources` JARs through your configured repositories and extracts them, along with the JDK sources, under `~/.cache/lathe/`.
If a dependency has no usable sources JAR, Lathe can still show the declaration from its class file on hover.
[Troubleshooting navigation](troubleshooting.md#navigation)

## Test capture and replay

When a test JVM starts, Lathe records the JDK, classpath, module flags, and arguments it actually received.
When you run a test from the editor, Lathe launches a fresh JVM with that configuration and the compiled classes under `.lathe/`.
You can rerun one test without invoking Maven or Gradle again.
[Test capture](test-capture.md)

## Running and debugging

A main method uses the module's recorded paths.
For debugging, Lathe starts the JVM suspended with a JDWP agent and hosts Microsoft's `java-debug` adapter in the server.
Neovim connects to that adapter through nvim-dap.
[Debugger setup](editors/neovim.md#debug-nvim-dap)

To pass application arguments, change the working directory, add JVM options, or adjust the environment or paths, use `lathe-run.json` or `.lathe/run.json`.
The build still supplies the underlying launch configuration.
[Run configuration](run-configuration.md)

## Diagnostics: live vs. on save

As you type, `javac` checks syntax and types in the current file.
When you save, it also runs the module's annotation processors and compiler plugins.
A check such as Error Prone can therefore report a warning on save, and `-Werror` can turn it into an error.
Those findings stay visible until you edit the file again.
Lombok also runs during live editing so completion can see its generated members.
[Lombok](#lombok)

The live pass also sees your **unsaved** edits in other open files of the same module.
Change a method's parameters in one buffer and use the new signature in another, and neither shows a false error before you save.
A buffer that does not parse at the moment (an unclosed brace mid-edit) is left out, so it cannot cause errors elsewhere; its saved version is used instead.

If a compiler plugin needs additional JVM access, sync records the module grants from the build.
Restart the server after changing those settings.
[Compiler plugin settings](troubleshooting.md#build-checks-and-compiler-settings)

## Lombok

When annotation processing is enabled and the build supplies Lombok, generated getters, builders, and loggers are available as you type.
Go to Definition on `getName()` opens the underlying field.
On `builder()` it opens the class.
A field’s references include calls to its generated accessors.
Lathe declines to rename these members when that would leave accessor calls behind.

Lathe follows the `lombok.config` files in the source file’s directory and its parents.
Editing an existing configuration takes effect on the next edit after Lombok rereads it.
Reload the workspace to pick up a newly added configuration.

<details>
<summary>Limits on Lombok configuration and navigation</summary>

- An `import` in `lombok.config` resolves relative to Lathe’s copy of the file.
- With `lombok.addLombokGeneratedAnnotation = false`, Lathe treats generated members as written code.
- An accessor renamed through a Lombok option other than `lombok.accessors.prefix` leads to the class.
- Generated members become available once the edited file parses.
- If the server exits when you open a Lombok file, check whether Lombok supports the server JDK.
  [Lombok troubleshooting](troubleshooting.md#lombok)

</details>

## Workspace freshness

| What changed | What to do |
| --- | --- |
| You edited a Java file | Keep editing. Lathe checks open files live and compiles saved changes. |
| A branch switch, pull, or coding agent changed sources | Lathe finds changed and deleted files, recompiles in dependency order, and refreshes affected open files. |
| Dependencies, modules, or build configuration changed | Run `:LatheSync` in Neovim, or the generated sync script or build from a terminal. |

A file that fails compilation keeps its last successful classes.
Runs of its module and dependent modules are blocked until the error is fixed.
Editors and agents sharing a workspace take turns updating the same compiled files.

The sync script uses the build tool and JDK from the first build.
On Linux or macOS, `.lathe/lathe-sync.sh app core` syncs those modules and their dependencies.
`.` names the module at the workspace root, as Lathe's answers name it, and syncs the whole project.
Add `--tests` to refresh test launches too.
The Windows script is `.lathe\lathe-sync.cmd`.

## Partial builds

On Maven, a build invoked with `-pl` skips writing `workspace.json`, so a partial view does not overwrite
the full workspace manifest; the modules that compiled still write their parameter files. To force a
workspace manifest write from a partial build, pass `-Dlathe.sync.force=true`.
On Gradle, a build of some projects refreshes `workspace.json` with the other projects' last captured facts.

A full build or sync also removes from `.lathe/` what the build no longer has: a module dropped from the
build, a module's test tree once its tests are gone, and annotation-processor output a removed processor left
behind. A partial Maven build (`-pl`) never removes anything, since it sees only part of the build; on Gradle
every build does, `latheSync --module` included, since Gradle always knows every project in the settings.
