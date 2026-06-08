# Lathe — Reference-Index Guided Sibling Recompilation

## Problem

Lathe maintains low-latency didChange diagnostics by compiling only the currently open file.
Sibling files in the same module are resolved by javac through compiled `.class` files under `.lathe/<rel>/classes/`.
This creates a compilation boundary gap:
if a user modifies a public or package-private API signature (e.g., renames a public method, changes parameter types, or deletes fields) in an open file, closed callers in the same module continue to resolve against the old signature stored in the `.class` outputs.
These callers will not show compilation errors until they are explicitly opened, saved, or recompiled via a Maven build.

## Goal

Resolve the boundary gap by introducing **Reference-Index Guided Sibling Recompilation**:
* Detect when a compilation/save pass changes the public or package-private API signature of an open file.
* Query the in-memory [ReferenceCandidateIndex](file:///home/ag-libs/git/lathe/lathe-server/src/main/java/io/github/aglibs/lathe/server/ReferenceCandidateIndex.java) to find sibling caller files within the same module.
* Asynchronously recompile and publish updated diagnostics for those affected sibling files.
* Keep the recompilation debounced and asynchronous to protect the 500ms p95 editing latency goal.

## Non-Goals

* Cross-module live sibling compilation. (Recompilation of dependent modules remains bounded by Maven compilation).
* Blindly recompiling all files in the module. Only files containing references to the changed class or members should compile.
* Changing the fast-path compile model for non-API modifications (e.g., changes confined to method bodies).

## Design & Flow

### 1. Detecting API Signature Changes

To avoid unnecessary recompilations, Lathe must detect if the public-facing API changed.

* During the compilation task, extract a signature string/hash of the file's public and package-private declarations:
  - Class, interface, record, or enum declarations (including type parameters, extends/implements clauses).
  - Public, protected, and package-private method signatures (name, parameter types, return type).
  - Public, protected, and package-private field declarations (name, type).
* Save this representation (or a hash of it) inside [AttributedFileAnalysis](file:///home/ag-libs/git/lathe/lathe-server/src/main/java/io/github/aglibs/lathe/server/analysis/AttributedFileAnalysis.java).
* When a compile finishes:
  - Compare the new signature hash against the previously cached [AttributedFileAnalysis](file:///home/ag-libs/git/lathe/lathe-server/src/main/java/io/github/aglibs/lathe/server/analysis/AttributedFileAnalysis.java) for the file.
  - If they differ, trigger the sibling recompilation pipeline.

### 2. Finding Affected Siblings

If an API signature change is detected:

* Query the [ReferenceCandidateIndex](file:///home/ag-libs/git/lathe/lathe-server/src/main/java/io/github/aglibs/lathe/server/ReferenceCandidateIndex.java) using the name of the modified class or members.
* Filter the returned candidate file paths:
  - Restrict files to the current file's [ModuleSourceConfig](file:///home/ag-libs/git/lathe/lathe-server/src/main/java/io/github/aglibs/lathe/server/module/ModuleSourceConfig.java) (intra-module only).
  - Exclude the modified file itself and any other open documents (open documents compile on their own fast/full flow).

### 3. Background Recompilation Pipeline

Recompiling callers must happen in the background and not block direct editor feedback.

* **Triggering compilation:**
  - `WorkspaceSession` receives the list of dirty sibling URIs.
  - It schedules a debounced job (e.g., `1000ms` delay) on the `lathe-worker` event loop.
* **Execution:**
  - When the debounce timer fires, submit a single-file compile pass to the `CompilationWorker` for each dirty sibling file.
  - Because the edited file's new `.class` files were written to `.lathe/<rel>/classes/` during its `FULL` compile pass, sibling compiles will naturally resolve symbols against the new API signature.
* **Interruption:**
  - If the user makes a new edit (triggers a `didChange` event) while sibling recompilations are queued or running, cancel all pending sibling compiles immediately to preserve CPU cycles.

```
LSP didSave(File A)
  -> compiler.compile(File A) -> writes new A.class
  -> Compare API signature of A: API changed!
  -> Query ReferenceCandidateIndex -> finds File B (caller)
  -> Queue File B for compile (1000ms debounce)
  ... debounce fires ...
  -> compiler.compile(File B) -> resolves new A.class -> detects error!
  -> publishDiagnostics(File B)
```

## Threading & Concurrency

No synchronization primitives are introduced:
* Event scheduling, index queries, and dirty-file queue management occur entirely on the `lathe-worker` thread.
* Compilation tasks are executed by the existing thread-confined `CompilationWorker` executors.
* Diagnostics publish from the `lathe-worker` thread upon receiving compilation responses, maintaining consistency.

## Verification & Tests

### Unit Tests
* Signature hashing tests:
  - Changing a method body does not change the API hash.
  - Changing a method parameter type or renaming a public/protected method changes the API hash.
  - Adding/removing public/protected fields changes the API hash.
  - Changing private methods/fields does not change the API hash.

### Integration Tests
* Sibling recompilation test cases in `lathe-server` mock session tests:
  - Modify a public method signature in a file, verify that a sibling file containing a reference is compiled and its diagnostics updated.
  - Verify that sibling compiles are cancelled/interrupted if a new `didChange` edit occurs before they finish.
