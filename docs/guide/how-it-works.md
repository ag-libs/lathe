# How Lathe works

Lathe derives everything from your Maven build rather than reconstructing a project model of its own.
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
There is no second Java front-end to drift out of step: what Lathe reports is what `javac` sees,
resolved against the exact classpath and options your build captured — which is what the rest of this
document describes.

## Build capture

During a Maven compile, Lathe's compiler integration records the exact `javac` parameters and classpath
for each module into `.lathe/`, and the `sync` goal writes the workspace manifest (`workspace.json`)
describing the reactor. The language server reads these files, so diagnostics, completion, and
navigation reflect the same inputs your build compiled with. Every build refreshes them, so the model
tracks your project as it changes.

The overhead above normal `javac` is small — a directory walk, a parameter-file write, and a bulk copy
of the module's compiled classes (typically tens to a couple hundred milliseconds of I/O for a small
module) — and it is safe under parallel builds (`mvnd -T N`), since each module writes to its own
`.lathe/<module>/` directory.

## Dependency & JDK sources

`lathe:sync` resolves your dependencies' `-sources` JARs through Maven and extracts them, along with the
JDK's own sources, under `~/.cache/lathe/`. That is what lets go-to-definition step into library and JDK
code. A dependency with no published `-sources` JAR is skipped — navigation to it is unavailable, with
no error.

## Test capture and replay

Lathe captures the exact JVM launch of your Surefire test fork — from inside the fork, by live
introspection — then replays a fresh JVM from that template against `.lathe/`, with no recompilation.
Requirements, and the limits of what can be captured, are in [test-capture.md](test-capture.md).

## Running and debugging

Runs and debug sessions replay from the captured `.lathe/` bytecode — no per-run recompilation —
launching a fresh JVM (for debugging, suspended under a JDWP agent with Microsoft's `java-debug` hosted
in-process) that reproduces the exact launch Maven captured. The same model serves any LSP client.

Runs use generated defaults. You can customize JVM flags, program args, environment, working directory,
or extra class-/module-path entries with an optional overlay (`lathe-run.json` / `.lathe/run.json`),
applied by the server and unable to change launch-correctness fields. Schema and selection rules are in
[run-configuration.md](run-configuration.md).

## Workspace freshness

Files you have open are analysed live as you edit and save them. When sources or resources change
**outside** the editor — a branch switch, a `git pull`, or an AI agent editing files — Lathe detects it
and offers to refresh; changed resources are copied into `.lathe/` without a build. Until you accept the
refresh, cross-file features (workspace symbol search, missing-import suggestions, and navigation into
those files) can still reflect the previous state.

## Partial builds

When Maven is invoked with `-pl`, `lathe:sync` skips writing `workspace.json` to avoid overwriting the
full workspace manifest with a partial view. Module parameter files are still written by Lathe's
compiler integration for the modules that compiled. To force a workspace manifest write from a partial
build, pass `-Dlathe.sync.force=true`.
