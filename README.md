# Lathe

[![Maven Central](https://img.shields.io/maven-central/v/io.github.ag-libs/lathe-maven-extension?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.ag-libs/lathe-maven-extension)
[![CI](https://github.com/ag-libs/lathe/actions/workflows/ci.yml/badge.svg)](https://github.com/ag-libs/lathe/actions/workflows/ci.yml)

Lathe is a Java language server for Maven projects — code intelligence, diagnostics, and run, test, and
debug.

Lathe's project model comes from your actual Maven build. It captures the exact configuration Maven
compiles, tests, and runs with, and works from it directly — so the setups that are hardest to get right
in an editor tend to just work. This shows most on **JPMS** projects: reactor type discovery,
exported-package visibility, and module-aware completion follow your module graph as the build defines
it. Annotation processors and plugins that add source roots or change how a module compiles are handled
the same way.

Because the editor uses the build's own configuration, it stays in step with it — diagnostics are what
the compiler reports, and runs and tests replay the real launch without recompiling.

Setup is one extension registration and a first build.

Lathe currently ships a fully supported Neovim client; a VS Code client is planned.

## Demo

<!-- TODO: replace with an inline demo clip (upload the MP4 to a GitHub issue/release and paste
     the user-attachments URL here so it renders as an inline player). Keep it ~40s: diagnostics,
     run a `main`, set a breakpoint, step, inspect a variable. Record against a public or
     `com.example` project only -- never a private codebase. -->

_Demo video coming soon — a short run-and-debug session._

## Features

Lathe implements the standard LSP feature surface, plus run, test, and debug. Every capability is
available to any LSP client; see [Editors](#editors) for the client that drives them and its key
bindings.

### Code intelligence

| Feature                      | What it does                                                                                      | LSP method                                        |
|------------------------------|---------------------------------------------------------------------------------------------------|---------------------------------------------------|
| Go to definition             | jumps to local sources, unpacked dependency JAR sources, and JDK sources                          | `textDocument/definition`                         |
| Go to declaration            | navigates to the overridden interface or abstract-method contract                                 | `textDocument/declaration`                        |
| Implementation / subtypes    | concrete implementations of a method, or all subtypes of a type across the workspace              | `textDocument/implementation`                     |
| Find references              | usages across the workspace                                                                       | `textDocument/references`                         |
| Instantiation sites          | where a type is instantiated (`new AppServer(...)`), from the type under the cursor               | `workspace/executeCommand` · `lathe.instantiations` |
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
| Diagnostics         | `javac` errors and warnings exactly as configured in Maven, plus unused private members and locals | `textDocument/publishDiagnostics` |
| Code actions        | import missing type · add `throws` clause · wrap with `try/catch` · declare local variable · replace `var` with the inferred type · stub a missing method | `textDocument/codeAction`         |
| Formatting (opt-in) | whole-document google-java-format with import cleanup — **off by default**                   | `textDocument/formatting`         |

Full-document formatting is **opt-in**: the server advertises `textDocument/formatting` only when a
client enables the `google` formatter, so Lathe never rewrites a project whose style contract isn't
Google Java Format. Live-editing indentation is a separate, always-on client concern — see the editor
guide to configure both.

### Run, test & debug

| Feature                                              | What it does                                                                                                                                                | LSP method                                                            |
|------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------|
| Run a `main`                                         | replays a `main` from captured `.lathe/` bytecode — no recompilation, live output                                                           | `workspace/executeCommand` · `lathe.run.main`                         |
| Tests                                                | discovers and runs tests (method, class, or package) from `.lathe/` bytecode, with live output and a diagnostic on the failing assertion | `workspace/executeCommand` · `lathe.runnables.list`, `lathe.run.test` |
| Debug                                                | conditional breakpoints, stepping, variable inspection, and REPL expression evaluation over DAP                                                                         | `workspace/executeCommand` · `lathe.debug.*`, then DAP                |
| [Run configuration](docs/guide/run-configuration.md) | overlay JVM args, program args, environment, working directory, and class-/module-path per module                                                           | — (`lathe-run.json` overlays)                                         |

Run, test, and debug are Lathe extensions exposed through `workspace/executeCommand` (and the Debug
Adapter Protocol for debugging), not standard LSP methods.

### Scaffolding

| Feature  | What it does                                                                                                      | Command                                                   |
|----------|------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------|
| New type | scaffolds a `class` / `interface` / `record` / `enum` — pick the kind, module, and package, then name it; the server resolves placement, writes the file, and opens it | Neovim `:LatheNew` |

`:LatheNew` walks through kind → module (skipped when there's only one) → package (existing, or a new
one) → name. The server owns every Java/Maven decision — module, source root, package, skeleton, and
caret — so the result fits your project's layout. See the
[Neovim cheatsheet](docs/guide/editors/neovim.md#create-a-new-type).

### Workspace freshness

Lathe keeps its model in step with your build. Open files are analysed live as you edit and save; when
sources or resources change **outside** the editor — a branch switch, a `git pull`, or an AI agent
editing files — Lathe detects it and offers to refresh. Changed resources are copied in without a
build.

## Editors

Lathe ships a client for Neovim; a VS Code client is planned.

| Editor  | Status    | Reference                                                               |
|---------|-----------|-------------------------------------------------------------------------|
| Neovim  | Supported | [Neovim cheatsheet](docs/guide/editors/neovim.md) — install and keymaps |
| VS Code | Planned   | —                                                                       |

## Requirements

- **Java 21+** — the same JDK your Maven build uses.
- **Maven 3.x**

Test run and debug have additional requirements (Surefire and JUnit Platform
versions); see [test-capture.md](docs/guide/test-capture.md).

## Setup

Set Lathe up once, in two steps.

**1. Register the Lathe extension** at your reactor root, as a Maven build extension — in
`.mvn/extensions.xml`, or in your root `pom.xml` under `<build><extensions>` (alongside any extensions
you already declare):

```xml
<extension>
  <groupId>io.github.ag-libs</groupId>
  <artifactId>lathe-maven-extension</artifactId>
  <version>0.1.2</version>
</extension>
```

See [installation.md](docs/guide/installation.md) for details.

**2. Generate the metadata once**, and add `.lathe/` to `.gitignore`:

```bash
mvn clean test -Dlathe.capture.only=true
```

This captures every launch template — compiler params, the workspace manifest, and each module's
run/test launch — without running your test suite. `-Dlathe.capture.only=true` forks each module to
snapshot its launch template but skips executing the tests; `clean` forces a first compile through
Lathe.

> **Tip:** the [Maven Daemon (`mvnd`)](https://github.com/apache/maven-mvnd) noticeably speeds up these
> builds — run it in place of `mvn` where you can.

> **Note:** if your build uses the Maven build cache extension, disable it for Lathe builds
> (`-Dmaven.build.cache.enabled=false`) — a cache hit skips compilation, so Lathe would not see the real
> build and its captured configuration would go stale.

After that, it keeps up on its own: every Maven build (`mvn test`, `verify`, `install`) refreshes
Lathe's configuration, and the editor watches for changes made outside it. The added build cost is
marginal — Lathe runs your real `javac` and just records its parameters — apart from resolving and
caching the dependency and JDK sources that power go-to-definition into library and JDK code. See
[what the build writes](docs/guide/installation.md#what-and-where-lathe-writes) for the details.

## How it works

Lathe has a few moving parts, each documented in depth. In brief — full mechanics in
[How Lathe works](docs/guide/how-it-works.md):

- **Build capture** — every build records the exact compiler configuration and mirrors your compiled
  classes into `.lathe/`, which the language server reads.
- **Dependency & JDK sources** — resolved and unpacked into `~/.cache/lathe/`, so go-to-definition steps
  into library and JDK code.
- **Test capture** — the test JVM is captured from inside your Surefire fork by live introspection and
  replayed against `.lathe/` with no recompilation. [Details →](docs/guide/test-capture.md)
- **Run & debug** — runs and debug sessions replay the captured launch in a fresh JVM; customize it with
  an overlay. [Details →](docs/guide/run-configuration.md)

## Files and caches

Lathe writes per-project metadata to **`.lathe/`** (add it to `.gitignore`) and machine-wide,
regenerable data — the server, dependency/JDK sources, and indexes — to **`~/.cache/lathe/`** (relocate
with `-Dlathe.cache=<dir>`, safe to delete). What each holds:
[what and where Lathe writes](docs/guide/installation.md#what-and-where-lathe-writes).

## Opt-out and CI

Lathe is active by default and skips automatically in CI:

| Condition                        | Effect                                |
|----------------------------------|---------------------------------------|
| `CI` environment variable is set | Lathe does not run                    |
| `-Dlathe.skip=true`              | disabled regardless of other settings |
| `-Dlathe.skip=false`             | enabled, overrides `CI`               |

## Documentation

- Guides (editor-agnostic): [how Lathe works](docs/guide/how-it-works.md) ·
  [installation](docs/guide/installation.md) · [run configuration](docs/guide/run-configuration.md) ·
  [test capture](docs/guide/test-capture.md)
- Editor references: [Neovim](docs/guide/editors/neovim.md)
- Project: [status](docs/status.md) · [roadmap](docs/roadmap.md) ·
  [design index](docs/design-index.md) · [architecture](docs/lathe-design.md)

## Troubleshooting

- **No Lathe features, or a "launcher not found" notice** — Lathe isn't active in the build yet. Run a
  Maven build at the reactor root — `mvn process-test-classes` is the quickest (it generates Lathe's
  metadata without running tests). If it still isn't working, confirm the Lathe extension is registered
  (see [installation.md](docs/guide/installation.md)).
- **The server won't attach, or crashes** — set `LATHE_DEBUG=1` before launching your editor and check
  its LSP log (Neovim: [cheatsheet](docs/guide/editors/neovim.md#verbose-logging)). An unexpected exit
  is also surfaced as an editor notification pointing at the log.

## Feedback & contributions

Feedback, bug reports, and questions are welcome — please [open an issue](https://github.com/ag-libs/lathe/issues).

If you would like to contribute code, please open an issue to discuss the change before opening a pull
request. Thank you for trying Lathe.

Maintainers: see [RELEASING.md](RELEASING.md) for the release process.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
