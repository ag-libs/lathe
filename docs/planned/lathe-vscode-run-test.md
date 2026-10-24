# Lathe — Run and Test in VS Code

**Status:** approved (2026-10-23); revised 2026-10-24 after studying how other VS Code extensions do this (§12).
Not built. Delivered editor-neutral first: server pieces, each adopted by Neovim, then VS Code (§11).
Spikes (§9) come before the code they cover; debugging is deferred (§10).

## 1. Goal

The VS Code extension runs tests and `main()` classes the way the Neovim client does:
replayed from the captured `.lathe/` bytecode, no Maven, results live in the editor.

Fast test runs without Maven are one of Lathe's main selling points, and the VS Code extension is also Lathe's Windows client (Cursor).
Today it is a pure language-client passthrough (`lathe-vscode/src/extension.ts`), so none of this reaches VS Code users.

## 2. Scope

- **In:** the Test Explorer (a tree that is full from the start, run at method / class / package / module level, live results,
  failure messages with expected/actual diffs and stack traces, per-test output, durations, cancel);
  running a `main()` from a CodeLens, the editor's run button or a command; running a named run config.
- **Deferred:** debugging (§10), coverage, continuous run.
- **Mostly client.** Every server command and notification used today is editor-neutral
  ([Run, Test, and Debug](../done/lathe-run-test-debug.md), [Named run configs](../done/lathe-named-run-configs.md),
  [Structured test results](../done/lathe-structured-test-results.md), [Test output streaming](../done/lathe-test-output-streaming.md)).
  Four small server additions (§3.2) are part of this design, because without them VS Code users would get the same gaps
  other Java and Rust test explorers are still criticised for (§12).
  They are additive on the wire; §3.3 lists what the Neovim client has to tolerate.

## 3. Server contract

The server spawns the replay JVM, so the client never starts a Java process or builds a command line.

### 3.1 What exists today

| Purpose | Server API (`workspace/executeCommand` unless noted) |
|---|---|
| Runnables in one open file | `lathe.runnables.list {uri}` → `RunTarget[]` `{id, parentId, kind, label, moduleRel, uri, range}` |
| What a directory runs as | `lathe.runnables.dir {uri}` → `{moduleRel, selections}` |
| Class name → source path | `lathe.testSources {moduleRel, classNames}` → `[{className, path}]` (path math, no analysis) |
| Run tests | `lathe.run.test {moduleRel, selections, token}` → `LaunchOutcome` |
| Run a main | `lathe.run.main {moduleRel, mainClass, token}` → `LaunchOutcome` |
| Run a saved config | `lathe.run.named {name, token}`; list with `lathe.runconfigs.list {}`; save with `lathe.runconfig.save` |
| Cancel | `lathe.run.cancel {token}` |
| Per-test result (notification) | `lathe/testEvent {token, result}`, where `result` = `{className, methodName, methodParameterTypes, status, failureMessage, failureLine, positionId}` and `status` ∈ `passed`/`failed`/`skipped` |
| Output line (notification) | `lathe/testOutput {token, line: {stream, text}}`, where `stream` is the `TranscriptLine.Stream` ordinal (below) |
| Debug outcome (notification) | `lathe/testFinished {token, outcome}`, sent when a debug session's JVM exits |

`LaunchOutcome` is `{launched, blockedReasons, exitCode, output, testResults}`.
Every `lathe/testEvent` and `lathe/testOutput` of a run arrives before the `lathe.run.*` response, because they share one ordered connection.
The neotest adapter relies on this ordering too.
The events are a best-effort live feed; the response's `testResults` is authoritative (`TestEventParams`).
Debug launches (`lathe.debug.*`) take the same `token` and stream the same two notifications, so one client-side result path serves both.

Every launch's output starts with **two** `COMMAND` lines: `config: <label>`, then the launch command (`Launcher`).

Test ids are `<binaryClassName>#<method>(<erasedParams>)` (`TestId`, `RunnableScanner`), e.g. `com.example.Outer$Inner#greet(java.lang.String)`.
A class's id is its binary name; a package's id is its name.
Package, class and nesting can therefore be read off any id without asking the server.
Signatures with arrays, generics or varargs don't reconstruct identically on both sides (`TestId`); such a result matches no discovered method and is attached to its class instead (§5.3).

Enums are serialized as ordinals, not names:

- `RunTarget.kind` (`RunnableKind`): `MAIN` 0, `TEST_METHOD` 1, `TEST_CLASS` 2, `TEST_PACKAGE` 3, `MAIN_CLASS` 4.
- `TranscriptLine.stream`: `STDOUT` 0, `STDERR` 1, `COMMAND` 2 (as `lathe.output` reads them).

The client mirrors these numbers in one place (`runnables.ts`).

### 3.2 Server additions

**A. Workspace test discovery** (backs the lazy tree, §5.2).

| API | Answer |
|---|---|
| `lathe.tests.modules {}` | `[{moduleRel, label}]`: every module with a test source tree. Cheap: read from the reactor layout. |
| `lathe.tests.discover {moduleRel}` | `{moduleRel, blockedReasons, classes: [{id, label, uri, methods: [{id, label}]}]}`; nested classes are separate entries whose `id` carries the `$`. |
| `lathe/testsChanged {moduleRel}` (notification) | That module's captured test classes changed (a sync, or an in-process save compile); answers from `tests.discover` for it are stale. |

`tests.discover` runs the module's captured test launch with the runner in a new **discover mode**:
`LauncherFactory.create().discover(request)` with a module-wide selector, and no test executed.
JUnit itself decides what a test is, so inherited tests, `@Nested` classes, meta-annotations and every engine on the test classpath come out right.
The ids come from the same `MethodSource` → `TestId.positionId` mapping as run results, so discovered items and results can't disagree.
Parameterized, repeated and factory methods are listed once, as their template, which is how §5.1 shows them.
`uri` is resolved like `lathe.testSources` (the outer class's source path).
There is no `range`: positions need javac, and an opened file supplies them (§5.2).

The server caches one answer per module and drops it on the event that sends `lathe/testsChanged`.
The cost is one short JVM start per module, paid only when someone expands that module.
A module with no capture answers with `blockedReasons` (the same messages `lathe.run.test` gives), so the tree can say why it is empty.

**B. Richer results** (backs §5.3). `TestResult` gains fields; the runner's NDJSON record carries them:

| Field | Meaning |
|---|---|
| `durationMs` | Wall time of the test, from the runner's start and finish callbacks; `-1` when unknown. |
| `displayName` | JUnit's display name, e.g. `[2] name=Bob` for one parameterized invocation, so a collapsed item's message can say which invocation failed. |
| `expected`, `actual` | The two compared values as strings, for a diff view; present only when the failure carries both (below). |
| `stackTrace` | `[{className, methodName, fileName, line}]`, cut at the first frame of JUnit, the runner or reflection. |

Where `expected`/`actual` come from — the same sources IntelliJ's "Click to see difference" uses:

| Failure | Thrown by | Read as |
|---|---|---|
| `org.opentest4j.AssertionFailedError` | JUnit 5 `assertEquals`, AssertJ `isEqualTo` (opentest4j is always on the JUnit Platform's classpath), Truth | `isExpectedDefined()` and `isActualDefined()`, then `getExpected().getStringRepresentation()` / `getActual()…` |
| `org.junit.ComparisonFailure` | JUnit 4 `assertEquals(String, String)` through the vintage engine | `getExpected()` / `getActual()`, by reflection: JUnit 4 may not be on the classpath |
| Anything else | Hamcrest `assertThat`, `assertTrue`, custom assertions | No values, so no diff: only the message |

The string representation is the value's `toString()`, so two records or POJOs compare by their `toString()` output, as in IntelliJ.
The runner never parses `expected: <a> but was: <b>` out of the message: Microsoft's Java test extension does, and it breaks on multi-line strings (§12).
The message itself is unchanged.

**C. Container failures.**
`ResultsListener` today ignores every container, so a failing `@BeforeAll`, a class initializer or a broken `@MethodSource` never reaches the client.
The run then looks like "no result (exit 1)" and the actual error is lost.
A failed or aborted container now produces a record with `methodName` empty and `positionId` = the class id.
(`TestResult` relaxes its `methodName` check for that case.)

**D. A started event and a closed stdin.**

- `lathe/testStarted {token, positionId}` (notification), from the runner's `executionStarted` for a method.
  The runner already flushes its sink per record, so the existing tailer streams it.
  It is a separate notification, not a fourth `status`: the neotest adapter maps an unknown status to `failed` (`neotest.lua:268`).
- The `Launcher` closes the replay process's stdin right after it starts.
  A `main()` reading `System.in` gets end-of-input at once; today it hangs on an open pipe until it is cancelled.

### 3.3 Impact on the Neovim client

- New fields in `TestResult`: ignored.
- `lathe/testStarted` and `lathe/testsChanged`: unknown notifications, ignored.
- A container-failure record: neotest sees an unknown method id and falls back to the class's aggregate status, which is better than today's "no result".
  A spec pins that it doesn't crash on an empty `methodName`.
- Closed stdin: applies to Neovim runs too, by design.

### 3.4 Neovim adoption

The additions are editor-neutral, and the neotest adapter can use them once they land.
Each item is adapter work (Lua), delivered right after the server piece it uses (§11).

1. **Whole-module summary.** neotest builds its tree by calling `discover_positions(path)` per test file, and the adapter can answer only for buffers the server has open, so the summary shows only opened files.
   With `lathe.tests.discover`, cached per module, it can answer for every file: the summary shows the whole module at once, runs from it work for unopened files, and a module run's results land on their positions instead of only in the quickfix list.
   An opened file switches to `runnables.list` as today, with real ranges and unsaved edits.
   `lathe/testsChanged` tells the adapter when to drop a module's cache.
   The limit: neotest needs a range for every position and discovery has none, so an unopened file's positions get a placeholder (the top of the file) until it is opened.
   Approximate method lines from the classes' line-number tables would close that gap; that is a later server addition.
2. **Per-test output.** `lathe/testStarted` lets the adapter write each test's output to its own file, instead of every test opening the run's whole transcript.
3. **Container failures.** A failing `@BeforeAll` or class initializer shows on the class with its real error, instead of every method reading "no result (exit 1)".
4. **Expected/actual as a diff.** neotest has no diff view, so the adapter offers two, both from `expected`/`actual`:
   - **Inline**, always visible: the failure's short text gets a unified diff of the two values (Neovim's built-in diff function), highlighted as a diff.
     Multi-line values are diffed line by line; the inline diff is capped (a few dozen lines) and says how to see the rest.
     ```
     greet_returnsExpectedMessage  FAILED
     - Hello, Alice!
     + Hello, alice!
     ```
   - **Side by side**, for long or multi-line values: `:LatheTestDiff` on a failed test opens a tab with two read-only scratch buffers, `expected` and `actual`, in diff mode (`:diffthis`),
     so Neovim highlights the changed lines and the changed text within them, like an IDE's comparison window.
   A failure without the two values shows only its message, as today.
5. **Structured stack frames.** `stackTrace` can replace the text parsing in `stacktrace.lua`, and put failure diagnostics on lines in helper classes, not only in the test class.
6. **Durations** in the run summary, e.g. the slowest tests.

## 4. Shape of the client

Three new files beside `extension.ts`, each one feature:

| File | Holds |
|---|---|
| `runnables.ts` | Pure functions: wire constants, the tree shape from discovery and from `RunTarget[]`, id parsing, selector building, result → state, cursor → main target. No `vscode` import, so they unit-test in plain Node like `launch.test.ts`. |
| `tests.ts` | The `TestController`, discovery, the run handler, result and output routing. |
| `run.ts` | The `main()` CodeLens, the run-main and run-config commands, the run terminals. |

One `TestController` per workspace folder, labeled `Lathe` (`Lathe — <folder>` in a multi-root window).
Each folder already has its own `LanguageClient`, so a controller only ever talks to one server and token routing stays per client.

Server commands are sent with `client.sendRequest(ExecuteCommandRequest.type, …)`, not `vscode.commands.executeCommand`, so the request goes to that folder's server.

**Scope each client to its folder.**
Today's `documentSelector` (`extension.ts`) is `{scheme: 'file', language: 'java'}` with no path pattern, so in a multi-root window every folder's server receives `didOpen` and publishes diagnostics for every Java file.
The selector gains `pattern: new RelativePattern(folder, '**/*')`.
A `RelativePattern`, not a `${fsPath}/**` string: VS Code matches a string against the raw path, so backslashes on Windows and glob characters in folder names break it.
(`vscode-languageclient` 9 passes the pattern through untouched, though its type says `string`.)
A folder nested inside another Lathe folder gets no client of its own, as in Microsoft's multi-server sample.

**Don't let the language client register the server's commands.**
`vscode-languageclient` registers every command the server advertises (`lathe.run.main`, …) as a VS Code command.
With two folders the second client's registration throws "command already exists" inside `initialize`, and that client stops: a two-folder window loses its second server today.
The client therefore adds a `StaticFeature` whose `preInitialize` deletes `executeCommandProvider` from the server capabilities it is given, before the command feature sees them.
The one server command VS Code itself invokes is `lathe.missingImports`, returned by the "Add missing imports" code action.
The extension registers it once and sends it to the client that owns `args[0].uri`.
The extension's own user-facing commands use a separate prefix, `lathe.editor.*`, e.g. `lathe.editor.runMain`.

## 5. Test Explorer

### 5.1 Tree

`module → package → class → method`:

- A module node per `moduleRel` (label = the module directory; `""` is the reactor root).
- Packages are flat (`com.example.jpms`), not split into segments.
- Nested classes nest under their outer class.
- `TestItem.id` is the server's id (§3.1); `uri` and `range` come from the target when the file is open, `uri` only from discovery otherwise.
  A method's `range` starts at its modifiers, so the gutter icon sits on the `@Test` line.
- `sortText` is the declaration line when the range is known, so methods list in source order.
- Parameterized and repeated invocations are a single method item.
  The status is worst-wins across invocations, matching neotest.
  VS Code keeps whichever state was set last, so the run handler records each item's worst status so far
  and never moves a `failed` item back to `passed` or `skipped`.
  A failure's message names its invocation (`displayName`).
  Child items per invocation were considered and rejected: they are the most persistent source of bugs in Microsoft's Java test extension (§12).

### 5.2 Discovery

Two sources fill one tree, and both speak the same ids.

**Workspace, lazily** (`lathe.tests.*`, §3.2 A):

- `resolveHandler(undefined)` (the Testing view opens) adds one module node per `lathe.tests.modules` entry, with `canResolveChildren`.
- `resolveHandler(module)` calls `lathe.tests.discover` and adds the module's packages, classes and methods.
  `blockedReasons` becomes the module item's `error`, e.g. "no test capture, sync with `--tests`".
- `lathe/testsChanged` re-queries a module the user has already expanded; a module never expanded stays unexpanded.

**Open files, live** (`lathe.runnables.list`):

- The server answers `runnables.list` only for a document it has open, and only after its open-mode compile.
  The client asks after a file's **first `publishDiagnostics`** (`LanguageClientOptions.middleware.handleDiagnostics`), which is the reliable "the server has this file open" signal.
  Neovim hit the same race between `didOpen` and the first request and fixed it the same way.
- It asks again on save, and on later diagnostics after an edit, debounced.
- An open file is the better source for its own classes: it reflects unsaved edits and supplies ranges.
  Its answer replaces what discovery said about the classes declared in that file.

**Merging, never replacing.**
Every update is a sync by id: existing items keep their identity and get a new `range`, `label` or `sortText`; new ids are added; ids no longer reported are removed.
Replacing items instead would throw away their last results and gutter state on every keystroke (a bug both Microsoft's Java extension and rust-analyzer had to fix).
When a file changes, the client calls `invalidateTestResults` on its items, so their old results show as outdated.
Out-of-order answers are dropped by a per-file version counter.
`workspace.onDidDeleteFiles` / `onDidRenameFiles` remove a file's classes and any package left empty.

The `refreshHandler` re-queries every expanded module and every open file.
It doesn't re-ask closed files through `runnables.list`, which would come back empty.

### 5.3 Run handler

One `Run` profile (`TestRunProfileKind.Run`, default); a `Debug` profile comes with §10.

1. Save the folder's dirty Java files first.
   The replay runs saved bytecode; running before the save compile lands reports a stale result
   (Metals' "green without running", §12; spike S4 checks the ordering).
2. Collect `request.include`; when it is undefined, run every module node.
3. Turn each included item into selectors:
   method → `METHOD`, class → `CLASS`, package → `PACKAGE`, all keyed by the item's id.
   A module node calls `runnables.dir` on the module directory (the folder path joined with `moduleRel`).
   When `request.exclude` is non-empty, expand the included nodes down to methods and drop the excluded ones, because Lathe selectors have no exclude.
4. Group selectors by `moduleRel`.
   Run one `lathe.run.test` per module, **sequentially**, so output from different modules doesn't interleave.
   Each module launch has its own token; a second run request starts its own launches rather than being dropped or blocking.
5. At the start, mark every *method* under the included items `enqueued`.
   Containers never get a state of their own (VS Code derives it), except on a container failure (step 7).
6. `lathe/testStarted` → that method `started`; it is now the run's *current test* for output (below).
7. `lathe/testEvent` → `passed` / `failed` / `skipped` with `durationMs`, on the item with that `positionId`, worst-wins (§5.1).
   - A failure becomes a `TestMessage`.
     With `expected`/`actual` it is `TestMessage.diff(message, expected, actual)`: the Test Results peek then shows VS Code's own diff editor (side by side or inline, changed characters highlighted), with nothing else to build.
     Without them it is a plain `TestMessage(message)`.
     Its `location` is the `failureLine` (1-based, `-1` = none; spike S6 pins this) in the item's file.
     The `stackTrace` follows the message as Markdown, each reactor frame a link to its file and line
     (class → file through `lathe.testSources`); `TestMessageStackFrame` needs a newer VS Code than 1.84.
   - A container failure (empty `methodName`) marks the class `errored` with the message,
     and its methods that got no result `errored` with "class failed: …".
   - A result for an id the tree doesn't have — a test in a file nobody opened while the module was never expanded,
     or a signature `TestId` can't match — gets its items created on the spot from the id (package and class from the class name, `uri` through `lathe.testSources`).
     A run never drops a result.
8. When the `lathe.run.test` response arrives:
   - `launched == false` → every enqueued item is `errored` with `blockedReasons` (e.g. "capture is stale, sync"),
     and a notification shows the reasons once per run.
   - Otherwise first apply the response's `testResults` to every item that no `lathe/testEvent` reported, the same way as in step 7, because the live events are best-effort.
   - Launched with a non-zero exit and items still unreported → those items are `errored` with "no result (exit N)".
   - Launched and items still unreported with exit 0 → they stay unrun, and the run output says "N selected tests reported no result".
9. `run.end()` once every module has finished.

**Output.**
`lathe/testOutput` → `run.appendOutput(text + "\r\n", undefined, currentTest)`; the Test Results terminal needs CRLF.
`currentTest` is the method last `started` and not yet finished, or none.
JUnit runs a class's methods one at a time unless the project turns on parallel execution, so this attributes output correctly in the common case;
with parallel execution enabled, output stays attached to the run only.
`STDERR` (1) lines are wrapped in red ANSI, and the two `COMMAND` (2) lines are dimmed.

**Cancel.**
The run's `CancellationToken` → `lathe.run.cancel {token}` for the module running now; modules not yet started are skipped.
The run then ends normally, when its `lathe.run.test` response arrives.

**Routing.**
A per-client `Map<token, RunSink>` receives the three notifications; tokens are minted by the client and unique per launch.
The main-run path (§6) and later the debug path (§10) use the same map.

**One execution path.**
The Test Explorer, `testing.runAtCursor` and any future lens all end in `lathe.run.test`.
rust-analyzer's lens and explorer runs went through different code and behaved differently (§12).

## 6. Running a `main()`

**Starting one.**

- **CodeLens** `▶ Run` on each `MAIN` target's range, computed client-side from `runnables.list` (the server has no CodeLens provider).
  The lens command is `lathe.editor.runMain {moduleRel, mainClass}` (`mainClass` = the target's `parentId`).
  The provider fires `onDidChangeCodeLenses` after a file's first diagnostics and on save, because `runnables.list` is empty until the server has the file open.
  `lathe.codeLens.run` (default on) turns the lens off, e.g. when Microsoft's Java debugger extension already shows its own `Run | Debug` lens (it is on Open VSX, so Cursor users may have it).
  No lens on the `MAIN_CLASS` line, unlike Neovim's sign: the `main` lens sits a few lines below and runs the same thing.
- **Editor run button and context menu** (`editor/title/run`, `editor/context`): `Lathe: Run Main` (`lathe.editor.runMain` with no argument).
  It resolves the main at the cursor: the main whose method range holds the cursor, else the one whose class does, else the file's only main.
  This ports `lathe.run._main_target_for` into `runnables.ts`.
  With several mains and the cursor on none, a QuickPick lists them, most recently run first.
  With no main in the file, it reruns the last main.

**Where the output goes: a terminal.**
Each run opens a terminal named `Run: <class>` backed by a `Pseudoterminal` that relays `lathe/testOutput`:

- Standard output as is, `STDERR` in red, the two `COMMAND` lines dimmed.
- At the end it prints `[exited N]` and stays open for reading.
  A notification "`<class>` exited N" appears only for a non-zero exit, or "run blocked: …" when the run didn't launch.
- Closing the terminal while the program runs cancels it (`lathe.run.cancel`); so does `Lathe: Stop Run` (`lathe.editor.stopRun`), which keeps the output.
- A rerun of the same class reuses its terminal, cleared.

Java users already get a `Run: <class>` integrated terminal from Microsoft's Java debugger extension.
A terminal also renders colours, and the `[exited N]` line adds what that extension doesn't show; an output channel would give neither.

**No stdin.**
The server never writes to the replay JVM's input and closes it at launch (§3.2 D), so a `main()` reading `System.in` gets end-of-input.
Typing into the terminal shows a one-line notice "input is not supported" the first time.
The terminal is chosen for display, not for interactivity.

**Named configs.**
`Lathe: Run Config…` is a QuickPick over `lathe.runconfigs.list` (`name · module · target`) → `lathe.run.named`, output in a `Run: <name>` terminal.
`Lathe: Run Last Config` repeats the last one.
`Lathe: Save Run Config` saves the runnable under the cursor through `lathe.runconfig.save` (an optional name from an input box) and opens the file at the new entry.
This ports Neovim's `_save_target_for`.

## 7. Contributions (`package.json`)

- Commands: `lathe.editor.runMain`, `lathe.editor.runConfig`, `lathe.editor.runLastConfig`, `lathe.editor.saveRunConfig`, `lathe.editor.stopRun`, and the internal `lathe.missingImports` (§4).
- Menus: `lathe.editor.runMain` in `editor/title/run` and `editor/context`, when the file is Java.
- Settings: `lathe.codeLens.run` (default `true`).
- `activationEvents` don't change: Test Explorer and CodeLens are registered in `activate`, which already runs on `onLanguage:java` / `workspaceContains:**/.lathe`.
- **Other Java test extensions.**
  If `vscjava.vscode-java-test` is active, Test Explorer shows two trees for the same tests.
  The extension shows a one-time notice; it doesn't try to disable the other extension.

## 8. Tests

### 8.1 Server

- Runner: discover mode lists a fixture's tests (nested, inherited, parameterized as one entry) without running them;
  records carry `durationMs`, `displayName` and a cut `stackTrace`;
  `expected`/`actual` are present for a JUnit 5 `assertEquals`, an AssertJ `isEqualTo` and a JUnit 4 `ComparisonFailure` (vintage),
  multi-line values arrive intact, and an `assertTrue` failure or a message that merely looks like `expected: <a> but was: <b>` carries none;
  a failing `@BeforeAll` and a failing static initializer each produce a container record;
  a started record precedes each method's result.
- Server: `tests.modules` and `tests.discover` against the existing test workspaces, including a module without capture (`blockedReasons`);
  `tests.discover` answers from cache until the module's test classes change, then sends `lathe/testsChanged` once;
  a launched `main()` reading `System.in` exits instead of hanging.
- Neovim: a spec feeds a container-failure result and the two new notifications to the neotest adapter (§3.3).

### 8.2 Client unit (plain Node, beside `launch.test.ts`)

The `runnables.ts` functions:
the tree built from discovery and from targets (nesting, parameterized collapse, the reactor-root module `""`);
the merge of the two sources by id (update in place, add, remove);
items created from an unknown result id;
selector building, with and without excludes;
cursor → main (method range, class range, single main, none, two mains with the cursor on neither);
cursor → save request;
worst-wins across invocations (a `failed` followed by a `passed` stays `failed`);
outcome → final states (blocked, a result present only in the response's `testResults`, non-zero exit with unreported items, a container failure, clean);
the stream ordinal → rendering (stderr red, both command lines dimmed).

### 8.3 Extension host, canned events (`@vscode/test-electron`)

A real `TestController` and `TestRun`, with spies on `started`/`passed`/`failed`/`appendOutput`, fed scripted `lathe/testStarted`/`testEvent`/`testOutput` sequences and responses through the `RunSink`.
No server: this pins the result routing (diffs, per-test output, container failures, cancel) quickly, and covers what the fixture can't produce, such as a long-running test to cancel.
This is the layer Microsoft's Java extension and Metals rely on (§12).

### 8.4 End-to-end (`@vscode/test-electron`, working-tree server)

This is the first editor E2E against a real Lathe server; the Neovim specs stub the server.

**Harness.**
- `@vscode/test-electron` (exact pin) downloads VS Code into `lathe-vscode/target/vscode-test/`, user data under `target/`, `--disable-extensions` (the development extension still loads).
  It runs twice: on **1.84.2**, the `engines` floor, and on current **stable**, which catches Testing API drift the floor can't (rust-analyzer does the same).
- The workspace is the `multi-module` invoker fixture after the invoker has built it: `lathe-maven-plugin/target/it/multi-module`.
  Its `.lathe/` launcher points at the working-tree server in the invoker's own cache, so the E2E tests this checkout, not a release.
  `lathe-vscode` is already declared after `lathe-maven-plugin` in the root `pom.xml`, so a reactor build has the fixture ready.
- Bound to `integration-test` in `lathe-vscode/pom.xml` (`npm run e2e` through `frontend-maven-plugin`), skipped by `skipTests`.
  It **fails** under CI if the fixture or the display is missing, and **skips with a message** locally.
  CI can't be detected through `CI`: the workflow runs Maven as `env -u CI mvn …` so that Lathe stays enabled in the fixture builds, so `CI` is never set inside the build.
  The check uses `GITHUB_ACTIONS` instead.
  (`lathe-neovim/test/run-specs.sh` checks `CI` and so never fails in CI today; it moves to the same check.)
  On Linux it runs under `xvfb-run -a`.
- On failure it prints the server's `lsp.log`.
- CI: it runs on every row that builds `lathe-vscode`. Today that is JDK 21 Linux, macOS (JDK 25) and Windows (JDK 25), so the Windows `.cmd` launcher path gets covered as well.
  The JDK 17, 25 and 27 Linux rows exclude the client modules.

**Observing results.**
VS Code has no stable API for reading another controller's results (`tests.testResults` is proposed API).
`activate()` therefore returns a small test-only handle: `{ controller(folder), run(items), runs: Event<RunRecord> }`.
`RunRecord` = per-item final state, durations, failure messages with locations and diffs, output lines with the item they were attributed to, exit code.
The tests trigger runs through the real UI commands (`testing.runAtCursor`, the CodeLens command) where one exists, so they exercise the same path a user does;
`run(items)` drives the same run handler for a chosen node, for which VS Code has no command.

**Scenarios** (fixture classes: `jpms` `HelloTest` with three `@Test`s and a `@ParameterizedTest`, including one that writes to both streams; `app` `Main` printing `ALICE`; `jpms` `HelloTester`, a `main` in test sources):

1. Discovery without opening anything: expanding `jpms` yields `com.example.jpms` → `HelloTest` → four methods, the parameterized test as one item, `uri` set and no `range`.
   Opening `HelloTest.java` then gives the same four items ranges without re-creating them.
   `Main.java` (no tests) adds nothing.
2. Run class at cursor: four `passed`, each with a duration.
   The output starts with the two `COMMAND` lines; `hello on stdout` and `warning on stderr` are attributed to `greet_prints_writesToBothStreams`.
3. Run one method at cursor: only that method gets a state.
4. Edit → save → rerun: change `greet_returnsExpectedMessage`'s expected string, run *without* saving first (the handler saves), wait for the result.
   The method is `failed`, with a diff message naming both strings, a location on the assertion line and a stack trace.
   The file is restored and saved in a `finally`.
5. Run the `jpms` module node (`run(items)`): every `jpms` test passes, through `runnables.dir`.
   Not `testing.runAll`: with the whole tree discovered it would also replay the `verify` module's `LspSmokeTest`/`MultiModuleTest`, which start servers of their own.
6. Main: `vscode.executeCodeLensProvider` on `Main.java` returns a `▶ Run` lens on `main`, and none on `Greeter.java`.
   Executing it opens `Run: com.example.app.Main` with `ALICE` and `[exited 0]`.
7. Test-scope main: the lens on `HelloTester.main` runs it through the module's test launch and prints `tester n=3`.

Cancel and container failures are covered by §8.3: the fixture has no long-running or broken test, and adding one would slow every invoker run.

## 9. Spikes (before production code)

No showstopper has been found; these settle the unknowns that could change the design.

| # | Question | Done when |
|---|---|---|
| S1 | Does `@vscode/test-electron` with VS Code 1.84.2 and stable run on all three CI OSes (xvfb on Ubuntu, no display on macOS/Windows), with the download cached, within a sensible time budget? | A trivial extension-host test opens `HelloTest.java` in the fixture and sees the server's diagnostics on Linux, macOS and Windows CI rows. |
| S2 | Can a test drive a custom controller through `testing.runAtCursor` headlessly, and see the results through the exported handle? | A throwaway controller's run handler fires from the command, and the test reads its outcome. |
| S3 | Is the first `publishDiagnostics` (middleware) a reliable "file is open on the server" signal in `vscode-languageclient` 9, so the first `runnables.list` never comes back empty? | 50 cold opens of `HelloTest.java`, each discovering all four methods on the first query. |
| S4 | Does save → run replay the edited test: does the save's in-process compile land on the replay classpath, and is a `lathe.run.test` sent right after `didSave` ordered behind that compile? | Scenario 4 fails as expected by hand, through the existing Neovim client or `dev/explore.py`, with the run sent immediately after the save. |
| S5 | Does the `preInitialize` feature (§4) stop the command registration, so a two-folder window starts both clients, each file opened on only its own server, and "Add missing imports" still works in both? | A two-folder window where both clients start; before the fix, the second one fails. |
| S6 | Ids and lines: does `positionId` equal the method target `id` for nested classes and parameterized tests? Is `failureLine` 1-based, with -1 meaning none? | Checked against `TestId`, `RunnableScanner` and neotest's mapping, and pinned by a unit test. |
| S7 | Discover mode's cost and fidelity: how long does `tests.discover` take on a large module (a Helidon or Dropwizard module with hundreds of tests), and does it list exactly what a run executes? | Timings for a few large modules, and a test plan diffed against the ids of a full run's results. |

S1 and S2 decide whether the E2E is feasible as designed.
S3 decides whether open-file discovery needs a retry.
S7 decides whether the module expansion needs a progress indicator or a per-package split.

## 10. Debugging (deferred, not designed)

The server side exists: `lathe.debug.test/main/named` start a suspended replay and return `{dapPort, jdwpPort}` for an in-process, attach-only DAP host,
and they stream the run's notifications with the client's token (§3.1).
What the research settled for when this is designed:

- VS Code resolves the debug configuration **before** it creates the adapter, while `lathe.debug.*` starts the JVM first.
  The launch therefore happens in `resolveDebugConfigurationWithSubstitutedVariables`:
  it calls `lathe.debug.*`, fills the attach arguments with the JDWP port, and keeps the DAP port for the descriptor factory, which returns `new DebugAdapterServer(dapPort)`.
  If the session never starts, it cancels the suspended JVM.
  Metals wires its server-hosted adapter the same way.
- `DebugSessionOptions.testRun` isn't in the 1.84 API.
  A Debug profile instead tags its configuration with the run token and follows the session through `onDidStartDebugSession` / `onDidTerminateDebugSession`, as Microsoft's Java test extension does.
  Results arrive through the same `RunSink` as a run (§5.3), and `lathe/testFinished` closes the run.
- Named configs appear as dynamic debug configurations (`provideDebugConfigurations`), never written into `launch.json`.
- Run stays on `lathe.run.*`, not a `noDebug` debug session: Metals moved its plain Run off DAP (§12), and our run path already streams per-test results that a DAP session only gives at the end.

## 11. Delivery

Editor-neutral pieces come first, and each one lands in Neovim before VS Code is touched,
so Neovim users gain from every phase and the server work is proven by an existing client.

**Phase 0: spikes.**
S4, S6 and S7 settle the editor-neutral phases; S1–S3 and S5 are VS Code-only and can wait until phase 3.

**Phase 1: richer results.**

1. Server: container failures, `expected`/`actual`, `stackTrace`, `durationMs`, `displayName`, `lathe/testStarted`, the closed stdin (§3.2 B–D); runner and server tests (§8.1).
2. Neovim: the spec for the new data (§3.3), then the adapter work of §3.4 items 2–6: per-test output, container failures, the inline and side-by-side diffs, structured stack frames, durations.

**Phase 2: whole-module discovery.**

3. Server: discover mode, `lathe.tests.modules`/`discover`, `lathe/testsChanged` (§3.2 A).
4. Neovim: the whole-module summary (§3.4 item 1).

**Phase 3: VS Code.**

5. Groundwork: folder-scoped selector, the command-registration feature and `lathe.missingImports` (§4). This fixes multi-root on its own.
6. Test Explorer: lazy and open-file discovery, run at every level, live results, diffs, stack traces, per-test output, cancel; unit and canned-event tests, E2E scenarios 1–5.
7. Run `main()`: CodeLens, run button and menu, run terminals, stop; E2E scenarios 6–7.
8. Named run configs: run, run last, save.

**Docs** with each phase: `README.md`, `docs/guide/` (`test-capture.md`, the Neovim cheatsheet, a new VS Code cheatsheet) and `docs/status.md`.

## 12. Prior art

Studied in October 2026 from source: Microsoft's Test Runner for Java and Debugger for Java (on jdtls), Red Hat's Java extension,
Metals (Scala) and rust-analyzer, plus `vscode-languageclient` 9.0.1.

| Decision here | Why |
|---|---|
| Lazy tree: modules, then classes and methods per module (§5.2) | Microsoft's Java and Metals both load suites workspace-wide and methods lazily; eager discovery is their main performance complaint ("Searching tests…" for minutes, Metals 2's 7-minute confirm step on spring-boot). rust-analyzer expands whole crates eagerly and carries a `FIXME` for it. |
| JUnit's own discovery, not a Lathe heuristic (§3.2 A) | Microsoft's Java extension finds tests with jdtls' own search; Metals asks the build server. Each one lets the component that knows the test model decide. |
| Merge by id, never replace (§5.2) | Both Microsoft's Java extension and rust-analyzer moved to in-place updates after replacing items lost results and gutter icons. |
| Parameterized invocations as one item (§5.1) | Microsoft's Java extension creates child items per invocation at run time; missing and misordered invocations are still open issues there. |
| Results for unknown items create items (§5.3) | Metals attaches such results to the suite; dropping them hides failures from a module run. |
| Container failures (§3.2 C) | Microsoft's Java extension reports failures on class items (vscode-java-test #1888); without them a `@BeforeAll` error has nowhere to go. |
| Diffs and stack traces (§3.2 B, §5.3) | IntelliJ's comparison window reads the same two exception types. Microsoft's Java extension falls back to parsing the message with a regex, which breaks on multi-line strings (vscode-java-test #1626); structured values avoid that. |
| Per-test output (§3.2 D, §5.3) | Output attributed to the run only is the most-requested open issue in both Microsoft's Java extension and rust-analyzer. |
| Save before running (§5.3) | Metals reported tests green that never ran when the run beat the compile. |
| Never drop a second run (§5.3) | Metals' `isRunning` guard silently dropped runs and got stuck after errors. |
| One execution path (§5.3) | rust-analyzer's lens and explorer runs diverged. |
| No test CodeLens | Microsoft's Java extension dropped its test lenses for VS Code's gutter icons; rust-analyzer shows both and has an open issue about the duplication. |
| Run output in a terminal (§6) | Microsoft's Java debugger runs `main()` in a `Run: <class>` terminal by default; its Debug Console alternative is documented as having no input either, the same limit Lathe has. |
| Disable command registration (§4) | `vscode-languageclient` throws on a duplicate id and stops the client; upstream treats it as by design (issues #333, #607). Red Hat's Java extension avoids it with one server for all folders. |
| Run via commands, debug via DAP (§10) | Metals moved plain Run off DAP because of the debug-session overhead and end-of-session-only results. |
