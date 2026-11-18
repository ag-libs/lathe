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
Subsequent builds refresh the modules they compile. [Workspace files](installation.md#what-and-where-lathe-writes)

The overhead above normal `javac` is small — a directory walk, a parameter-file write, and a bulk copy
of the module's compiled classes (typically tens to a couple hundred milliseconds of I/O for a small
module) — and it is safe under parallel builds (`mvnd -T N`, Gradle's parallel execution), since each
module writes to its own `.lathe/<module>/` directory.

For an OpenJDK checkout, the [OpenJDK front-end](openjdk.md) reads the `make` build's own per-module
`javac` invocations instead, and writes the same `.lathe/` files, so the server works the same on every build.
The server never runs the build itself; it only reads what a front-end wrote.

## Dependency & JDK sources

Go to definition can open the Java source for a library method or JDK class.
The build resolves available `-sources` JARs through your configured repositories and extracts them, along with the JDK sources, under `~/.cache/lathe/`.
If a dependency has no usable sources JAR, Lathe can still show the declaration from its class file on hover. [Troubleshooting navigation](troubleshooting.md#navigation)

## Test capture and replay

When a test JVM starts, Lathe records the JDK, classpath, module flags, and arguments it actually received.
When you run a test from the editor, Lathe launches a fresh JVM with that configuration and the compiled classes under `.lathe/`.
You can rerun one test without invoking Maven or Gradle again. [Test capture](test-capture.md)

## Running and debugging

A main method uses the module's recorded paths.
For debugging, Lathe starts the JVM suspended with a JDWP agent and hosts Microsoft's `java-debug` adapter in the server.
Neovim connects to that adapter through nvim-dap. [Debugger setup](editors/neovim.md#debug-nvim-dap)

To pass application arguments, change the working directory, add JVM options, or adjust the environment or paths, use `lathe-run.json` or `.lathe/run.json`.
The build still supplies the underlying launch configuration. [Run configuration](run-configuration.md)

## Diagnostics: live vs. on save

As you type, `javac` checks syntax and types in the current file.
When you save, it also runs the module's annotation processors and compiler plugins.
A check such as Error Prone can therefore report a warning on save, and `-Werror` can turn it into an error.
Those findings stay visible until you edit the file again.
Lombok also runs during live editing so completion can see its generated members. [Lombok](#lombok)

The live pass also sees your **unsaved** edits in other open files of the same module.
Change a method's parameters in one buffer and use the new signature in another, and neither shows a false error before you save.
A buffer that does not parse at the moment (an unclosed brace mid-edit) is left out, so it cannot cause errors elsewhere; its saved version is used instead.

Plugins such as Error Prone reach into `javac` internals, so the JVM running them needs extra
`--add-exports`/`--add-opens` grants.
Your build already declares these: on Maven as `-J` flags for a forked compiler, in `.mvn/jvm.config`, or in
`MAVEN_OPTS`; on Gradle in `org.gradle.jvmargs`, or in a forked compile's `options.forkOptions.jvmArgs`.
The build copies them into `.lathe/jvm.args`, and the server starts with them; after you change them, re-sync and restart the server.

## Lombok

When a module has Lombok on its processor path, or on its class path when it has none (and the build does not turn annotation processing off), the live pass runs Lombok too, and no other processor.
So the getters, builders and loggers Lombok generates are there while you type, in the editor and for an agent.
It costs about 10 ms a keystroke, and about a second the first time a module loads Lombok.

A generated member leads back to the code it comes from:
definition on `getName()` lands on the field `name` (on the class for `builder()` or `toString()`),
and the field's references include its accessors' calls.
Renaming such a field, or a generated member, is refused with the reason, as the accessors' calls would be left behind.

Lombok reads `lombok.config` from the source's directory and each one above it, as in the build.
Lathe compiles a copy of each file elsewhere, so it lays that chain out beside the copy.

Limits:
- An `import` line in a `lombok.config` is resolved from Lathe's copy, not from the file's own directory.
- A changed `lombok.config` applies to an open file at its next edit, and the closed sources below it are compiled again; Lombok rereads a config at most every 2 seconds.
- A `lombok.config` added after the server loaded counts only from the next reload.
- Lathe recognizes generated members by the `@lombok.Generated` mark Lombok adds; with
  `lombok.addLombokGeneratedAnnotation = false` in `lombok.config`, navigation treats them as written code.
- Accessors named through `lombok.config` other than by `lombok.accessors.prefix` lead to the class, not the field.
- A file that does not parse yet shows no generated members until it parses again.
- On a JDK the project's Lombok does not support, opening a Lombok file stops the server, as the build itself would fail; upgrade Lombok.

## Workspace freshness

Files you have open are analysed live as you edit and save them.
When sources or resources change **outside** the editor — a branch switch, a `git pull`, or an AI agent editing files — Lathe detects it and recompiles the changed files itself, however many, with a progress indicator; changed resources are copied into `.lathe/` without a build.
It looks every 2 seconds while you work, and every 10 seconds once a minute has passed with no request from the editor and no change; the first change it finds brings it back to every 2 seconds.
Saves, outside changes, and renames all go through the same write: changed files are compiled module by module in dependency order, and the open files they affect are re-checked once afterwards.
A file that does not compile keeps its last good classes and is retried when it or a file it depends on changes.
Until it compiles, a run, test or debug of its module, or of a module that depends on it, is blocked with its first error rather than replaying those classes.
Several Lathe servers on one workspace (your editor's and an agent's) take turns writing `.lathe/`, so a change is compiled once, by whichever sees it first.
While one of them writes, an agent's question waits at most 2 s for it, then is answered from `.lathe/` as it is; `verify_change` asks to re-check in a moment.

Only a change to a **build file** (a `pom.xml`, a Gradle build script or `gradle.properties`, or a dependency
change) asks you to sync, as Lathe cannot reproduce dependency resolution itself.
The sync runs `.lathe/lathe-sync.sh`, a script the build writes, so the editor never needs to know your build tool.
The Neovim client runs it when you answer the prompt; in other editors, run it (or your build) yourself.
You can run it any time: `.lathe/lathe-sync.sh` refreshes everything, `.lathe/lathe-sync.sh app core` only those modules (with their upstream), and `--tests` also re-captures test launches without running the tests.
It prints the exact build command first.
While a build holds `.lathe/lathe.lock`, Lathe still compiles the files you save and catches up on outside changes when the build ends.

## Partial builds

On Maven, a build invoked with `-pl` skips writing `workspace.json`, so a partial view does not overwrite
the full workspace manifest; the modules that compiled still write their parameter files. To force a
workspace manifest write from a partial build, pass `-Dlathe.sync.force=true`.
On Gradle, a build of some projects refreshes `workspace.json` with the other projects' last captured facts.

A full build or sync also removes from `.lathe/` what the build no longer has: a module dropped from the
build, a module's test tree once its tests are gone, and annotation-processor output a removed processor left
behind. A partial Maven build (`-pl`) never removes anything, since it sees only part of the build; on Gradle
every build does, `latheSync --module` included, since Gradle always knows every project in the settings.
