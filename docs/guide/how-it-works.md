# How Lathe works

Lathe derives everything from your build rather than reconstructing a project model of its own.
This document covers the mechanism.
For installation see [installation.md](installation.md);
for exactly what each build writes, see
[what and where Lathe writes](installation.md#what-and-where-lathe-writes).

## Built on the JDK compiler

The language server is the JDK's own Java compiler.
It runs `javac` in-process and reads the compiler's abstract syntax trees through the Compiler Tree
API (the `com.sun.source` packages exported by the JDK's `jdk.compiler` module), so every feature —
diagnostics, go-to-definition, hover, completion, the type and call hierarchies — is computed from the
compiler's own attributed syntax tree rather than a re-implementation of Java's rules.
The [MCP server](ai-agents.md) for AI agents runs the same engine in-process, so an agent's answers
match the editor's.
There is no second Java front-end to drift out of step: what Lathe reports is what `javac` sees,
resolved against the exact classpath and options your build captured — which is what the rest of this
document describes.

## Build capture

Lathe hooks into your build, through the [Maven extension](maven.md) or the [Gradle plugin](gradle.md):
as each module compiles, it records the exact `javac` parameters and classpath into `.lathe/`, and it
writes the workspace manifest (`workspace.json`) describing the modules. The language server reads these files, so diagnostics, completion, and
navigation reflect the same inputs your build compiled with. Every build refreshes them, so the model
tracks your project as it changes.

The overhead above normal `javac` is small — a directory walk, a parameter-file write, and a bulk copy
of the module's compiled classes (typically tens to a couple hundred milliseconds of I/O for a small
module) — and it is safe under parallel builds (`mvnd -T N`, Gradle's parallel execution), since each
module writes to its own `.lathe/<module>/` directory.

For an OpenJDK checkout, the [OpenJDK front-end](openjdk.md) reads the `make` build's own per-module
`javac` invocations instead, and writes the same `.lathe/` files, so the server works the same on every build.
The server never runs the build itself; it only reads what a front-end wrote.

## Dependency & JDK sources

The build resolves your dependencies' `-sources` JARs through its own repositories and extracts them, along
with the JDK's own sources, under `~/.cache/lathe/`. That is what lets go-to-definition step into library and JDK
code. A dependency with no published `-sources` JAR is skipped — navigation to it is unavailable, with
no error.

## Test capture and replay

Lathe captures the exact JVM launch of your build's tests (Maven's Surefire fork, or each Gradle test
task's JVM) from inside that JVM, by live introspection, then replays a fresh JVM from that template against `.lathe/`, with no recompilation.
Requirements, and the limits of what can be captured, are in [test-capture.md](test-capture.md).

## Running and debugging

Runs and debug sessions replay from the captured `.lathe/` bytecode — no per-run recompilation —
launching a fresh JVM (for debugging, suspended under a JDWP agent with Microsoft's `java-debug` hosted
in-process) that reproduces the exact launch the build captured. The same model serves any LSP client.

Runs use generated defaults. You can customize JVM flags, program args, environment, working directory,
or extra class-/module-path entries with an optional overlay (`lathe-run.json` / `.lathe/run.json`),
applied by the server and unable to change launch-correctness fields. Schema and selection rules are in
[run-configuration.md](run-configuration.md).

## Diagnostics: live vs. on save

While you edit, Lathe runs a fast `javac` pass that reports parser and type-checker diagnostics for the
file you are in — immediately, on every change.
Annotation processors and classpath javac plugins — Error Prone and its `-Xep` checks, and anything
that generates sources — are **not** run on this fast pass; they run only when you **save**, as part of
the full compile that mirrors your build.
So a file can look clean when you open it and surface more diagnostics after the first save — including
Error Prone findings and, where the build sets `-Werror`, warnings promoted to errors.
This is intentional: processor runs are too expensive to repeat on every keystroke, so Lathe keeps live
editing responsive and reserves the full, build-faithful pass for save.
Lombok is the exception; see [Lombok](#lombok).
The findings of that save stay on the file until you edit it again.

The live pass also sees your **unsaved** edits in other open files of the same module.
Change a method's parameters in one buffer and use the new signature in another, and neither shows a false error before you save.
A buffer that does not parse at the moment (an unclosed brace mid-edit) is left out, so it cannot cause errors elsewhere; its saved version is used instead.

Plugins such as Error Prone reach into `javac` internals, so the JVM running them needs extra
`--add-exports`/`--add-opens` grants.
Your build already declares these: on Maven as `-J` flags for a forked compiler, in `.mvn/jvm.config`, or in
`MAVEN_OPTS`; on Gradle in `org.gradle.jvmargs`.
The build copies them into `.lathe/jvm.args`, and the server starts with them; after you change them, re-sync and restart the server.

## Lombok

When a module has Lombok on its processor path (and the build does not turn annotation processing off), the live pass runs Lombok too, and no other processor.
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
- A changed `lombok.config` applies to an open file at its next edit, and Lombok rereads a config at most every 2 seconds.
- Lathe recognizes generated members by the `@lombok.Generated` mark Lombok adds; with
  `lombok.addLombokGeneratedAnnotation = false` in `lombok.config`, navigation treats them as written code.
- Accessors named through `lombok.config` (`lombok.accessors.prefix`) lead to the class, not the field.
- A file that does not parse yet shows no generated members until it parses again.
- On a JDK the project's Lombok does not support, opening a Lombok file stops the server, as the build itself would fail; upgrade Lombok.

## Workspace freshness

Files you have open are analysed live as you edit and save them.
When sources or resources change **outside** the editor — a branch switch, a `git pull`, or an AI agent editing files — Lathe detects it and recompiles the changed files itself, however many, with a progress indicator; changed resources are copied into `.lathe/` without a build.
Saves, outside changes, and renames all go through the same write: changed files are compiled module by module in dependency order, and the open files they affect are re-checked once afterwards.
A file that does not compile keeps its last good classes and is retried when it or a file it depends on changes.
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
behind. A partial build (Maven's `-pl`, Gradle's `latheSync --module`) never removes anything, since it sees
only part of the build.
