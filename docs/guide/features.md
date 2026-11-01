# Lathe features

Most capabilities are standard LSP, so any LSP client can use them.
The rest (run, test, debug, and a few navigation and import commands) are Lathe commands over `workspace/executeCommand`, with DAP for debugging; the [Neovim client](editors/neovim.md) drives them all.
See the [editor guides](../../README.md#editors) for each client.

## Code intelligence

| Feature                      | What it does                                                                                      | LSP method                                        |
|------------------------------|---------------------------------------------------------------------------------------------------|---------------------------------------------------|
| Go to definition             | jumps to local sources, unpacked dependency JAR sources, and JDK sources                          | `textDocument/definition`                         |
| Go to declaration            | navigates to the overridden interface or abstract-method contract                                 | `textDocument/declaration`                        |
| Implementation / subtypes    | concrete implementations of a method, or all subtypes of a type across the workspace; on a functional interface's method, also the lambdas and method references that implement it | `textDocument/implementation`                     |
| Find references              | usages across the workspace                                                                       | `textDocument/references`                         |
| Highlight uses               | read/write uses of the symbol under the cursor, within the current file                          | `textDocument/documentHighlight`                  |
| Rename                       | renames a symbol and every reference **across every module of the build** — as one atomic edit (locals, fields, methods incl. the override family, record components, constructors) | `textDocument/rename` · `textDocument/prepareRename` |
| Instantiation sites          | where a type is instantiated (`new AppServer(...)`), from the type under the cursor               | `workspace/executeCommand` · `lathe.instantiations` |
| Hover                        | AST-resolved Javadoc, rendered as Markdown                                                        | `textDocument/hover`                              |
| Signature help               | parameter lists for methods and constructors                                                      | `textDocument/signatureHelp`                      |
| Completion                   | types, methods, and variables, with automatic import insertion; static members the project already imports statically elsewhere in the same scope (test or main), with their `import static` | `textDocument/completion`                         |
| Document / workspace symbols | file outline; workspace search by name prefix, simple or qualified (`java.util.Map.Entry`), with CamelCase-hump matching for reactor types (`ASF` finds `AbstractServerFactory`) | `textDocument/documentSymbol`, `workspace/symbol` |
| Type hierarchy               | supertypes and subtypes of the symbol under the cursor, one level at a time                        | `textDocument/prepareTypeHierarchy`               |
| Full type hierarchy          | all transitive supertypes and subtypes of the type under the cursor at once, tagged by relation    | `workspace/executeCommand` · `lathe.typeHierarchy` |
| Add missing imports          | resolve every unimported type in the file in one pass, and every unresolved call the scope statically imports elsewhere — unambiguous names are added automatically, ambiguous ones prompt | `workspace/executeCommand` · `lathe.missingImports`, `lathe.importEdits` |
| Call hierarchy               | incoming and outgoing calls of a method                                                           | `textDocument/prepareCallHierarchy`               |
| Resource finder              | find a resource by name across reactor resource roots and dependency JARs; dependency entries open read-only | `workspace/executeCommand` · `lathe.resources`, `lathe.resourceOpen` |
| Semantic tokens              | highlights static/deprecated members, enum constants, type parameters, annotations                | `textDocument/semanticTokens/full`                |
| Folding                      | the imports (one range), module, classes, methods, and Javadoc comments                           | `textDocument/foldingRange`                       |

In a Lombok project, what Lombok generates is part of the analysis and leads back to its field; see [Lombok](how-it-works.md#lombok).

## Diagnostics & formatting

| Feature             | What it does                                                                                 | LSP method                        |
|---------------------|----------------------------------------------------------------------------------------------|-----------------------------------|
| Diagnostics         | `javac` errors and warnings exactly as your build configures them, plus unused private members and locals | `textDocument/publishDiagnostics` |
| Code actions        | quick fixes and refactors: missing imports (including `import static` for members the scope imports elsewhere), add `throws`, wrap in `try/catch`, declare local, replace `var` with the inferred type, extract variable / constant / field / method (an expression or statements; one value used afterwards or a `return` on every path becomes the call's value, and copies elsewhere in the class can be replaced too), add a `final` field as a constructor parameter, stub a missing method, surround with try-with-resources | `textDocument/codeAction`         |
| Formatting          | the project's own formatter, detected from its Spotless setup — google-java-format, palantir-java-format, or Eclipse at the exact version it pins — run in-process in milliseconds, selections included | `textDocument/formatting`, `rangeFormatting`, `rangesFormatting` |

Lathe applies the formatter step only, not other Spotless steps such as import order or license headers.
A committed `lathe-style.json` picks another formatter, an external command, or none; see
[choosing the formatter](installation.md#choosing-overriding-or-opting-out-of-the-formatter).

## Run, test & debug

| Feature                                              | What it does                                                                                                                                                | LSP method                                                            |
|------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------|
| Run a `main`                                         | replays a `main` from captured `.lathe/` bytecode — no rebuild, live output                                                                 | `workspace/executeCommand` · `lathe.run.main`                         |
| Tests                                                | discovers and runs tests (method, class, or package) from `.lathe/` bytecode, with live output (each test's own output attributed to it), a diagnostic on the failing assertion, expected/actual diffs, and class-level failures | `workspace/executeCommand` · `lathe.runnables.list`, `lathe.tests.discover`, `lathe.run.test` |
| Debug                                                | conditional breakpoints, stepping, variable inspection, and REPL expression evaluation over DAP                                                                         | `workspace/executeCommand` · `lathe.debug.*`, then DAP                |
| [Run configuration](run-configuration.md) | auto-applied overlays (JVM/program args, env, cwd, class-/module-path) plus named, selectable run configs (`:LatheRun {name}`, saved from the cursor)         | `lathe.run.named` · `lathe.runconfigs.list` · `lathe.runconfig.save`  |

Run, test, and debug are Lathe extensions exposed through `workspace/executeCommand` (and the Debug
Adapter Protocol for debugging), not standard LSP methods. The Neovim commands (`:LatheRun`,
`:LatheDebug`, save/last/stop, the config picker) are in the
[Neovim cheatsheet](editors/neovim.md) and [run configuration](run-configuration.md).

## Scaffolding

| Feature  | What it does                                                                                                      | Command                                                   |
|----------|------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------|
| New type | scaffolds a `class` / `interface` / `record` / `enum` / `annotation` / `test`, plus `package-info` and `module-info` — pick the kind, fuzzy-pick the destination package, and name it; the server resolves placement, writes the file, and opens it | Neovim `:LatheNew` |

`:LatheNew <kind>` adds a type in the current package; bare `:LatheNew` runs a guided module/package
picker. The server owns placement — module, source root, package, and skeleton.
[Walkthrough →](editors/neovim.md#create-a-new-type).
