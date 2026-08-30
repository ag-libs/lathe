# Lathe

Lathe is a Java language server for Maven projects. It provides code intelligence, diagnostics, and
run/test/debug.

Lathe takes its project model from your Maven build rather than reconstructing one. Each time you
build, Lathe records the exact `javac` parameters and classpath Maven used and refreshes its workspace
state, so diagnostics and completion always match your latest build. Analysis runs on javac's own front
end — the JDK Compiler Tree API from the `jdk.compiler` module — so Lathe reports what `javac` reports.
Runs and debug sessions replay from captured bytecode without recompiling, reproducing the launch Maven
would use.

Setup is a single extension registration; no per-piece `pom.xml` edits are needed. The extension
injects its compiler integration, the `init`/`sync` goals, and the test-capture dependency into the
build in memory.

Lathe currently ships a Neovim client; a VS Code client is in progress.

## Demo

<!-- TODO: replace with an inline demo clip (upload the MP4 to a GitHub issue/release and paste
     the user-attachments URL here so it renders as an inline player). Keep it ~40s: diagnostics,
     run a `main`, set a breakpoint, step, inspect a variable. Record against a public or
     `com.example` project only -- never a private codebase. -->

_Demo video coming soon — a short run-and-debug session._

## Features

Lathe is a language server; every capability below is available to any LSP client.
See [Editors](#editors) for the client that drives them and its key bindings.

### Code intelligence

| Feature                      | What it does                                                                                      | LSP method                                        |
|------------------------------|---------------------------------------------------------------------------------------------------|---------------------------------------------------|
| Go to definition             | jumps to local sources, unpacked dependency JAR sources, and JDK sources                          | `textDocument/definition`                         |
| Go to declaration            | navigates to the overridden interface or abstract-method contract                                 | `textDocument/declaration`                        |
| Implementation / subtypes    | concrete implementations of a method, or all subtypes of a type across the workspace              | `textDocument/implementation`                     |
| Find references              | usages across the workspace                                                                       | `textDocument/references`                         |
| Hover                        | AST-resolved Javadoc, rendered as Markdown                                                        | `textDocument/hover`                              |
| Signature help               | parameter lists for methods and constructors                                                      | `textDocument/signatureHelp`                      |
| Completion                   | types, methods, and variables, with automatic import insertion                                    | `textDocument/completion`                         |
| Document / workspace symbols | file outline; workspace search with CamelCase-hump matching (`ASF` finds `AbstractServerFactory`) | `textDocument/documentSymbol`, `workspace/symbol` |
| Type hierarchy               | supertypes and subtypes of the symbol under the cursor                                            | `textDocument/prepareTypeHierarchy`               |
| Call hierarchy               | incoming and outgoing calls of a method                                                           | `textDocument/prepareCallHierarchy`               |
| Semantic tokens              | highlights static/deprecated members, enum constants, type parameters, annotations                | `textDocument/semanticTokens/full`                |
| Folding                      | classes, methods, blocks, and import groups                                                       | `textDocument/foldingRange`                       |

### Diagnostics & formatting

| Feature             | What it does                                                                                 | LSP method                        |
|---------------------|----------------------------------------------------------------------------------------------|-----------------------------------|
| Diagnostics         | `javac` errors and warnings exactly as configured in Maven, plus unused-private-member hints | `textDocument/publishDiagnostics` |
| Code actions        | import missing type · add `throws` clause · wrap with `try/catch` · declare local variable   | `textDocument/codeAction`         |
| Formatting (opt-in) | whole-document google-java-format with import cleanup — **off by default**                   | `textDocument/formatting`         |

Full-document formatting is **opt-in**: the server advertises `textDocument/formatting` only when a
client enables the `google` formatter, so Lathe never rewrites a project whose style contract isn't
Google Java Format. Live-editing indentation is a separate, always-on client concern — see the editor
guide to configure both.

### Run, test & debug

| Feature                                              | What it does                                                                                                                                                | LSP method                                                            |
|------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------|
| Run a `main`                                         | replays a `main` from captured `.lathe/` bytecode with no recompilation; output streams back live                                                           | `workspace/executeCommand` · `lathe.run.main`                         |
| Tests                                                | discovers and runs tests (method, class, or package) from `.lathe/` bytecode, with live output, pass/fail status, and a diagnostic on the failing assertion | `workspace/executeCommand` · `lathe.runnables.list`, `lathe.run.test` |
| Debug                                                | breakpoints, stepping, variable inspection, and REPL expression evaluation over DAP                                                                         | `workspace/executeCommand` · `lathe.debug.*`, then DAP                |
| [Run configuration](docs/guide/run-configuration.md) | overlay JVM args, program args, environment, working directory, and class-/module-path per module                                                           | — (`lathe-run.json` overlays)                                         |

Run, test, and debug are Lathe extensions exposed through `workspace/executeCommand` (and the Debug
Adapter Protocol for debugging), not standard LSP methods.

## Editors

Lathe ships a client for Neovim; VS Code is coming.

| Editor  | Status      | Reference                                                               |
|---------|-------------|-------------------------------------------------------------------------|
| Neovim  | Supported   | [Neovim cheatsheet](docs/guide/editors/neovim.md) — install and keymaps |
| VS Code | Coming soon | —                                                                       |

## Requirements

- **Java 21+** — the same JDK your Maven build uses.
- **Maven 3.x**

Test run and debug have additional requirements (Surefire and JUnit Platform
versions); see [test-capture.md](docs/guide/test-capture.md).

## Setup

> Publishing to a Maven repository is pending — for now, [build from source](docs/guide/installation.md) first.

Register the extension once, in `.mvn/extensions.xml` at the reactor root (the directory you run `mvn`
from):

```xml
<extensions>
  <extension>
    <groupId>io.github.ag-libs</groupId>
    <artifactId>lathe-maven-extension</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </extension>
</extensions>
```

Then generate the Lathe metadata and add `.lathe/` to `.gitignore`:

```bash
mvn clean test -Dlathe.capture.only=true
```

This captures every launch template — compiler params, the workspace manifest, and each module's
run/test launch — without running your test suite. `-Dlathe.capture.only=true` forks each module to
snapshot its launch template but skips executing the tests; `clean` forces a first compile through
Lathe.

> **Tip:** the [Maven Daemon (`mvnd`)](https://github.com/apache/maven-mvnd) noticeably speeds up these
> builds — run it in place of `mvn` where you can.

You rarely run this again. Every normal test build (`mvn test`, `verify`, `install`) refreshes the
templates automatically. For a lighter refresh of just LSP intelligence and `main`-class runs (no test
capture), use `mvn process-test-classes`.

For **manual POM configuration** (and when to prefer it over the extension), plus what the build
writes, see [installation.md](docs/guide/installation.md).

## How it works

### Build capture

During a Maven compile, Lathe's compiler integration records the exact `javac` parameters and classpath
for each module into `.lathe/`, and the `sync` goal writes the workspace manifest (`workspace.json`)
describing the reactor. The language server reads these files, so diagnostics, completion, and
navigation reflect the same inputs your build compiled with. Every build refreshes them, so the model
tracks your project as it changes.

### Dependency & JDK sources

`lathe:sync` resolves your dependencies' `-sources` JARs through Maven and extracts them, along with the
JDK's own sources, under `~/.cache/lathe/`. That is what lets go-to-definition step into library and JDK
code. A dependency with no published `-sources` JAR is skipped — navigation to it is unavailable, with
no error.

### Test capture

Lathe captures the exact JVM launch of your Surefire test fork — from inside the fork, by live
introspection — then replays a fresh JVM from that template against `.lathe/`, with no recompilation.
Details, requirements, and limits: [test-capture.md](docs/guide/test-capture.md).

### Running and debugging

Runs and debug sessions replay from the captured `.lathe/` bytecode — no per-run recompilation —
launching a fresh JVM (for debugging, suspended under a JDWP agent with Microsoft's `java-debug`
hosted in-process) that reproduces the exact launch Maven captured. The same model serves any LSP
client.

### Run configuration

Runs use generated defaults. Customize JVM flags, program args, environment, working directory, or
extra class-/module-path entries with an optional **overlay** (`lathe-run.json` /
`.lathe/run.json`) — applied by the server, and unable to change launch-correctness fields.
Schema and selection rules: [run-configuration.md](docs/guide/run-configuration.md).

## Files and caches

Lathe writes two kinds of data:

- **`.lathe/`** in your project — per-build metadata (compiler params, the workspace manifest,
  run/test launch templates). Add it to `.gitignore`. Details:
  [installation.md](docs/guide/installation.md#what-and-where-lathe-writes).
- **`~/.cache/lathe/`** on your machine — the unpacked language server and editor client, the
  dependency and JDK **source** trees `lathe:sync` extracts, and the symbol index. Regenerable and
  safe to delete; relocate with `-Dlathe.cache=<dir>`.

## Opt-out and CI

Lathe is active by default and skips automatically in CI:

| Condition                        | Effect                                |
|----------------------------------|---------------------------------------|
| `CI` environment variable is set | both `init` and `sync` are skipped    |
| `-Dlathe.skip=true`              | disabled regardless of other settings |
| `-Dlathe.skip=false`             | enabled, overrides `CI`               |

## Partial builds

When Maven is invoked with `-pl`, `lathe:sync` skips writing `workspace.json` to avoid overwriting the
full workspace manifest with a partial view. Module params files are still written by Lathe's compiler
integration for compiled modules. To force a workspace manifest write from a partial build, pass
`-Dlathe.sync.force=true`.

## Documentation

- Guides (editor-agnostic): [installation](docs/guide/installation.md) ·
  [run configuration](docs/guide/run-configuration.md) ·
  [test capture](docs/guide/test-capture.md)
- Editor references: [Neovim](docs/guide/editors/neovim.md)
- Project: [status](docs/status.md) · [roadmap](docs/roadmap.md) ·
  [design index](docs/design-index.md) · [architecture](docs/lathe-design.md)

## Troubleshooting

### `.lathe` directory not found

`lathe:init` has not run, or Lathe is not registered in the build.
Run `mvn process-test-classes` at the reactor root.
If `.lathe/` is still missing, verify Lathe is registered — a core extension in `.mvn/extensions.xml`,
a build extension in the reactor-root `pom.xml`, or the manual wiring (see
[installation.md](docs/guide/installation.md)). With the `.mvn/extensions.xml` route, also confirm you
are running `mvn` from the directory that contains `.mvn/`.

### Missing params for a module (`Run mvn process-test-classes to activate module`)

The server cannot find Lathe's compiler parameters for the module you are editing.
Re-run `mvn process-test-classes` to regenerate them.

### Server not attaching or crashing

Set `LATHE_DEBUG=1` before launching your editor for verbose server logging, then consult your editor's
LSP log (for Neovim, see the [cheatsheet](docs/guide/editors/neovim.md#verbose-logging)).

## Feedback & contributions

Feedback, bug reports, and questions are welcome — please [open an issue](https://github.com/ag-libs/lathe/issues).

If you would like to contribute code, please open an issue to discuss the change before opening a pull
request. Thank you for trying Lathe.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
