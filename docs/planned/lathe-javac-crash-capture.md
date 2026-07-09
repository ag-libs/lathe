# Lathe — Javac Crash Capture

## Status

Planned.
This design records how Lathe should capture reproducible artifacts when javac throws an unhandled exception or
compiler-origin assertion.

The first implementation slice should capture the repro bundle only.
It should not change fatal-vs-recoverable process policy unless a later design explicitly does so.

## Problem

Lathe invokes javac against editor text, generated temporary source files, captured Maven compiler settings,
and sometimes sentinel/recovery-modified source.
When javac throws an unexpected exception, the stack trace alone is usually insufficient for reporting a useful
upstream compiler bug.

The missing artifact is the exact input combination javac saw:
source text, compiler options, file-manager locations, compile mode, diagnostics emitted before the crash,
and the JDK identity.

Normal server logs are not the right place for raw source.
Logs are copied into issues and support chats, and the project logging policy forbids raw source content at any level.
Lathe needs a local crash bundle written under workspace-owned state, with logs pointing to that bundle path.

## Goals

- Capture every compiler bug that escapes javac invocation code:
  `RuntimeException`, direct `AssertionError`, and wrapped compiler-origin failures.
- Capture the exact source files passed to javac, not a reconstructed approximation.
- Capture enough javac environment to reproduce:
  options, file-manager locations, compile mode, JDK identity, diagnostics, and stack trace.
- Keep ordinary JUL logs source-free.
- Keep the initial implementation independent of fatal-process policy.
- Keep bundle writing best-effort:
  a failure to write the bundle must not mask the original compiler failure.
- Make the capture path work for single-file interactive compiles, full save compiles, external-source compiles,
  and transient batch analysis.

## Non-goals

- Automatically filing upstream javac issues.
- Redacting or transforming captured source.
- Uploading crash bundles.
- Changing whether a specific `Error` terminates the Lathe process.
- Guaranteeing a perfect one-command javac repro for every JPMS/project configuration.
- Capturing ordinary javac diagnostics when javac completed normally.

## Capture Trigger

Capture at the javac invocation boundary in `JavacRunner`.
That class is the narrow point where Lathe still has the exact `JavaFileObject` inputs and the exact `options`
passed into `JavaCompiler.getTask(...)`.

The capture wrapper should cover:

- `JavaCompiler.getTask(...)`
- `JavacTask.parse()`
- `JavacTask.analyze()`
- `JavacTask.generate()`
- `Trees.instance(task)`
- `task.getElements()`
- `task.getTypes()`

`JavaSourceCompiler.analyzeSafely(...)` currently catches `RuntimeException` from `task.analyze()` and logs a javac
bug before continuing.
That catch must also capture a bundle, or the method should be reshaped so `JavacRunner` owns all compiler-crash
capture for `analyze()`.

The trigger should include both:

- direct compiler exceptions thrown at the invocation site;
- wrapped failures whose cause chain includes a javac-origin `AssertionError` or other unexpected javac exception.

The first slice can conservatively capture every `RuntimeException` or `Error` thrown while inside `JavacRunner`.
Fatal classification still happens after capture through the existing worker boundary.

## Bundle Location

Write bundles under the workspace `.lathe/` directory:

```text
.lathe/javac-crashes/<utc-timestamp>-<short-hash>/
```

The short hash should be derived from:

- source file paths and source bytes;
- options;
- compile mode;
- exception class and top stack frame.

This makes repeated crashes recognizable without requiring global state.

External-source compiles and projects without a usable workspace directory should fall back to a user-cache location:

```text
~/.cache/lathe/javac-crashes/<utc-timestamp>-<short-hash>/
```

If neither location can be resolved or written, log the capture failure as a secondary warning and preserve the
original compiler failure.

## Bundle Contents

Every bundle should contain:

```text
README.md
stacktrace.txt
java.txt
lathe.txt
mode.txt
options.txt
locations.txt
diagnostics.txt
sources/
```

`README.md` explains what was captured, warns that source code may be private, and includes a best-effort local repro
command.

`stacktrace.txt` contains the full original throwable stack trace.

`java.txt` contains:

- `java.version`
- `java.vendor`
- `java.vendor.version`, when present
- `java.home`
- `os.name`
- `os.version`
- `os.arch`

`lathe.txt` contains:

- Lathe server version, if available from the workspace manifest;
- operation type: single compile, full compile, external compile, or batch analysis;
- original URI or source-file count;
- source SHA-256 values.

`mode.txt` contains `FAST`, `OPEN`, or `FULL`, plus the javac phase where the failure was observed:
`getTask`, `parse`, `analyze`, `generate`, `trees`, `elements`, or `types`.

`options.txt` contains one compiler option per line, exactly as passed to `getTask`.

`locations.txt` records `StandardJavaFileManager` locations that materially affect javac behavior:

- `CLASS_PATH`
- `MODULE_PATH`
- `ANNOTATION_PROCESSOR_PATH`
- `CLASS_OUTPUT`
- `SOURCE_OUTPUT`
- `SYSTEM_MODULES`, if available
- any other non-empty standard location exposed by the file manager

Each location should be recorded one path per line under a clear heading.

`diagnostics.txt` contains diagnostics collected before the crash.
Use the diagnostic kind, code, source URI, start/end positions, and message.

`sources/` contains copies of every source file passed to javac.
For temp-file-backed module compiles, this is the temporary source file Lathe wrote, preserving the package-relative
path underneath `sources/`.
For in-memory parser paths, write the in-memory content to a synthetic path matching the URI.

## Source Identity

The source captured must be the exact source javac saw.
This matters because Lathe may compile:

- current unsaved editor text;
- sentinel/recovery-modified text;
- generated temporary files under a module-specific temp directory;
- external JDK or dependency sources copied to a temporary patch-module tree;
- multi-file transient batches.

The capture code should copy from the `JavaFileObject` content or URI used in the actual task,
not from the original workspace file on disk.

If both transformed and original editor text are available in a future slice, capture both:

```text
sources/javac/...
sources/original/...
```

The initial slice only needs the javac-visible source.

## Logging

The normal log entry should be source-free and point to the bundle:

```text
LOG.log(
    Level.SEVERE,
    failure,
    () -> "[javacCrash] %s phase=%s mode=%s bundle=%s".formatted(uriOrCount, phase, mode, bundle));
```

If bundle capture fails:

```text
LOG.log(
    Level.WARNING,
    captureFailure,
    () -> "[javacCrash] %s phase=%s capture failed".formatted(uriOrCount, phase));
```

Do not log source text, AST nodes, or snippets.

## Error Policy Interaction

Capture is not classification.

After capture, the original failure should continue through the existing path:

- `OutOfMemoryError` and other fatal VM/resource failures still reach the fatal worker boundary.
- javac `AssertionError` still follows the current policy until a separate design changes it.
- ordinary `RuntimeException` behavior remains whatever the current compile path defines.

This separation lets Lathe start producing useful javac bug artifacts without first deciding whether javac assertions
should keep the server alive.

## API Shape

Add a small package-private helper in `lathe-server`:

```java
record JavacCrashInput(
    String operation,
    CompileMode mode,
    String phase,
    List<JavaFileObject> sources,
    List<String> options,
    DiagnosticCollector<JavaFileObject> diagnostics,
    StandardJavaFileManager fileManager) {}
```

```java
final class JavacCrashCapture {
  Optional<Path> capture(JavacCrashInput input, Throwable failure) { ... }
}
```

`JavacRunner` constructs `JavacCrashInput` at each task boundary and calls the capturer from a catch block.
The helper should be package-private and concrete; no interface is needed unless tests require injecting a capture
root.

Tests can use a package-private constructor on `JavacCrashCapture` that accepts a crash root path.

## Testing

Unit tests should cover:

- direct `RuntimeException` thrown by a fake javac phase writes a bundle and rethrows the original failure;
- direct `AssertionError` writes a bundle and rethrows the original failure;
- diagnostics collected before failure are written;
- multiple source files in a batch are all copied;
- source content is not written to log messages;
- capture write failure does not mask the compiler failure.

Existing fatal-worker tests should remain valid.
If they assert direct `AssertionError` causes process termination, they should still pass in the first slice because
capture does not change fatal classification.

## Follow-up Decisions

After crash bundles exist, decide separately whether javac-origin `AssertionError` should become recoverable.
That later design should distinguish javac bugs from Lathe assertions and VM/resource failures.
