# Lathe features

Lathe uses your build’s compiler settings for navigation, completion, refactoring, and diagnostics.
The editor guides show how to invoke these actions.
[Choose your editor](../../README.md#editors)

## Code intelligence

| To find… | Use |
| --- | --- |
| The method or constructor a call resolves to | **Go to Definition** opens the declaration of the selected overload or constructor. It can open dependency and JDK sources when available. |
| The contract a method implements | **Go to Declaration** opens the overridden interface or abstract method. |
| The type of a variable or a method’s return value | **Go to Type Definition** |
| Implementations of a method or subtypes of a class | **Go to Implementation** |
| Every use of a declaration | **Find References** searches across modules. **Document Highlight** marks reads and writes in the open file. |
| The methods that call a method, or the ones it calls | **Call Hierarchy** |
| Supertypes and subtypes | **Type Hierarchy** |
| A declaration by name | **Document Symbols** lists declarations in the file. **Workspace Symbols** searches types across the project. |

**Hover** shows the declaration and its Javadoc.
**Signature Help** shows the parameters of methods and constructors as you type.
Completion suggests types, methods, and variables and can insert their imports.
It also suggests static members already imported by other files in the same scope (main or test).
In a Lombok project, navigating to a generated accessor takes you back to its field.
[Lombok support](how-it-works.md#lombok)

## Diagnostics & formatting

- **Read compiler errors.** Syntax and type errors appear as you edit.
  Saving also runs the module’s compiler plugins and annotation processors.
  [Live and save diagnostics](how-it-works.md#diagnostics-live-vs-on-save)
- **Fix or refactor code.** Open **Code Actions** to add an import, handle an exception, implement a missing method, or extract a variable, constant, field, or method.
  Lathe offers the actions that apply to your selection.
- **Rename a symbol.** Use **Rename** on a declaration or reference.
  Lathe follows uses and overrides across modules and reports a conflict if the new name would change what a use refers to.
- **Format a file or selection.** Lathe uses the project’s formatter.
  On Maven, sync reads the supported Java formatter from Spotless, or spring-javaformat's plugin.
  On Gradle, sync detects spring-javaformat applied in the root project.
  Otherwise, declare it in `lathe-style.json`.
  External formatter commands support whole files.
  [Formatter setup](installation.md#choosing-overriding-or-opting-out-of-the-formatter)

## Run, test & debug

In Neovim, use `:LatheRun` inside a `main` method to run the compiled class.
With neotest, you can rerun one JUnit method and jump from a failure to its source.
With nvim-dap, you can stop at a breakpoint and inspect variables.
[Test setup](editors/neovim.md#tests-neotest) · [Debugger setup](editors/neovim.md#debug-nvim-dap)

Lathe uses the launch settings from your build.
To add program arguments, environment variables, or a named launch target, see [Run configuration](run-configuration.md).

## Scaffolding

Use `:LatheNew` in Neovim to create a class, interface, record, enum, annotation, or JUnit test in the module and package you choose.
Use `:LatheGoToTest` to switch between a class and its test.
[Create a new type](editors/neovim.md#create-a-new-type)

For the implementation status of individual features, including semantic highlighting and folding, see the [capability matrix](../status.md#lsp-capability-matrix).

<details>
<summary>Custom LSP commands for editor integrations</summary>

An editor can invoke these commands through `workspace/executeCommand`.
Pass the argument object as the sole entry in `arguments` for commands that take one.

| User surface | `executeCommand` | Argument object |
|---|---|---|
| `:LatheTypeHierarchy` | `lathe.types.hierarchy` | position params |
| `:LatheInstances` | `lathe.types.instantiations` | position params |
| `:LatheGoToTest` | `lathe.tests.counterparts` | `{ uri }` |
| `:LatheMissingImports` (and the "Add missing imports…" code action) | `lathe.imports.missing` | `{ uri }` |
| `:LatheMissingImports` — the edits for the chosen imports, placed by the server | `lathe.imports.edits` | `{ uri, imports }` (the chosen candidates) |
| `:LatheRun` / neotest / `:LatheDebug` — runnables of a file | `lathe.runnables.file` | `{ uri }` |
| neotest — runnables of a directory (package run) | `lathe.runnables.dir` | `{ uri }` |
| neotest — a module's tests (JUnit Platform discovery) | `lathe.tests.discover` | `{ moduleRel }` |
| neotest — source files of test classes | `lathe.tests.sources` | `{ moduleRel, classNames }` |
| `:LatheRun` | `lathe.run.main` | `{ moduleRel, mainClass, token }` |
| neotest run | `lathe.run.test` | `{ moduleRel, selections, token }` |
| `:LatheRun {name}` / `:LatheDebug {name}` | `lathe.run.named` / `lathe.debug.named` | `{ name, token }` |
| `:LatheRunStop` / neotest stop | `lathe.run.cancel` | `{ token }` |
| `:LatheDebug` (a `main`) | `lathe.debug.main` | `{ moduleRel, mainClass, token }` |
| `:LatheDebug` (a test) | `lathe.debug.test` | `{ moduleRel, selections, token }` |
| run-config completion and picker | `lathe.runConfigs.list` | none |
| `:LatheRunSave[!]` | `lathe.runConfigs.save` | `{ name?, moduleRel, kind, mainClass?, selectors?, overwrite? }` |
| `:LatheNew` — module list / current context | `lathe.workspace.modules` / `lathe.workspace.context` | none / `{ uri }` |
| `:LatheNew` — where a new type may go, ranked for the buffer | `lathe.types.destinations` | `{ uri?, kind, pkg? }` |
| `:LatheNew` — write, compile and open the file | `lathe.types.create` | `{ moduleRel, kind, pkg, type, name }` (a module-info's `name` may be empty: the server names it) |
| run output — the stack frames among its lines, resolved to their sources | `lathe.stack.resolve` | `{ lines }` |
| `:LatheResourceFind` — list / open a dependency entry | `lathe.resources.list` / `lathe.resources.open` | none / `{ jar, entry }` |
| non-Java save inside a workspace | `lathe.resources.refresh` | `{ uri }` |

Position parameters identify the document URI and the line and character of the symbol.
`moduleRel` is a module path relative to the build root.
For a run or debug request, `token` is the client’s identifier for cancelling that run.

</details>
