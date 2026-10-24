# Lathe — Run and Test in VS Code

**Status:** approved (2026-10-23); revised 2026-10-24 after studying how other VS Code extensions do this (§13).
Not built. Delivered editor-neutral first: server pieces, each adopted by Neovim, then VS Code (§11).
All seven spikes ran on 2026-10-24 (§9); their outcomes are folded in below. Debugging is deferred (§10).

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
  Small server additions (§3.2) are part of this design, because without them VS Code users would get the same gaps
  other Java and Rust test explorers are still criticised for (§13).
  The spikes and the research also found four bugs in today's run path that Neovim users hit as well (§3.2 E–H).
  The additions are additive on the wire; §3.3 lists what the Neovim client has to tolerate.

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
Spike S6 checked the two sides against each other: plain, overloaded, generic (erased to `java.util.List`), repeated, factory, `@Nested` and doubly nested methods all match.
Array and varargs parameters don't: JUnit reports them in JVM form (`arrayParam([I)`, `varargsParam([Ljava.lang.String;)`) while javac's id says `int[]` and `java.lang.String[]`.
Their results land on no method today, in Neovim too; §3.2 E fixes that.
Any result that still matches no method is attached to its class (§5.3).

`failureLine` is 1-based and `-1` when no line is known (S6: the assertion on line 74 reported 74).
A parameterized test produces one result per invocation, all with the same `positionId`.

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
The ids come from the same `MethodSource` → `TestId.positionId` mapping as run results, so discovered items and results agree.
One exception: a `@Nested` class declared in a parent and inherited by two outer classes is two JUnit tests but one Lathe id (S7 found one in Dropwizard).
The tree shows it once, and both runs' results land on that one item, worst-wins.
Parameterized, repeated and factory methods are listed once, as their template, which is how §5.1 shows them.
`uri` is resolved like `lathe.testSources` (the outer class's source path).
There is no `range`: positions need javac, and an opened file supplies them (§5.2).

The selection is the module's, as a module run selects it: one package selector per test package (`WorkspaceSession.moduleRun`), filtered by the build's own test-class filter (F).
Discovery loads test classes without initializing them, so no user code runs: Spring, Weld and Helidon test extensions, `@MethodSource` and `@EnabledIf` act only at execution.

The server caches one answer per module and drops it on the event that sends `lathe/testsChanged`.
A module with no capture answers with `blockedReasons` (the same messages `lathe.run.test` gives), so the tree can say why it is empty.

**Cost (spike S7).** One JVM per module, paid only when someone expands that module:

| Module | Test classes | Test methods | JVM + discovery, cold / warm |
|---|---|---|---|
| lathe-server (JPMS, `--patch-module`) | 111 | 1222 | 1.15–1.25 s / 0.95–1.1 s |
| Helidon `config/config` | 75 | 648 | 0.73–1.15 s / 0.52–0.63 s |
| Helidon `common/reactive` | 73 | 557 | 0.74–1.0 s / 0.57–0.63 s |
| Dropwizard `dropwizard-jersey` | 55 | 372 | 1.37–1.89 s / 1.08–1.36 s |
| Dropwizard `dropwizard-testing` | 85 | 179 | 1.4–1.83 s / 1.0–1.13 s |
| Dropwizard `dropwizard-util` | 7 | 67 | 0.76–1.04 s / 0.7–0.77 s |

Two thirds of the time is JUnit loading classes; walking the plan takes under 30 ms.
Discover mode starts its JVM with `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC` ahead of the launch's own JVM arguments (so the build can still override them), which cut `dropwizard-jersey` by about 30%.
A second at most per module needs no per-package split; the client shows the module as busy while it waits (§5.2).

Against what Surefire ran, the discovered set matched exactly on four of the six modules, including disabled tests (Surefire reports them as skipped), inherited tests and parameterized templates.
The other two listed classes Surefire's include patterns leave out; F makes discovery and runs follow those patterns.
Not covered: TestNG run through Surefire's own TestNG provider rather than the JUnit Platform.
The JUnit Platform runner finds nothing there, so discovery lists no tests for such a module; what its runs do today was not checked.

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
The runner never parses `expected: <a> but was: <b>` out of the message: Microsoft's Java test extension does, and it breaks on multi-line strings (§13).
The message itself is unchanged.

**C. Container failures.**
`ResultsListener` today ignores every container, so a failing `@BeforeAll`, a class initializer or a broken `@MethodSource` never reaches the client.
The run then looks like "no result (exit 1)" and the actual error is lost.
Spike S6 confirmed it: a class whose `@BeforeAll` throws ran with exit 1, no `lathe/testEvent` and an empty `testResults`; the error appeared only as plain text in the output.
A failed or aborted container now produces a record with `methodName` empty and `positionId` = the class id.
(`TestResult` relaxes its `methodName` check for that case.)

**D. A started event and a closed stdin.**

- `lathe/testStarted {token, positionId}` (notification), from the runner's `executionStarted` for a method.
  The runner already flushes its sink per record, so the existing tailer streams it.
  It is a separate notification, not a fourth `status`: the neotest adapter maps an unknown status to `failed` (`neotest.lua:268`).
- The `Launcher` closes the replay process's stdin right after it starts.
  A `main()` reading `System.in` gets end-of-input at once; today it hangs on an open pipe until it is cancelled.

**E. Array and varargs ids.**
`TestId.positionId` turns JUnit's JVM-form parameter names into javac's source form before building the id:
`[I` → `int[]`, `[[J` → `long[][]`, `[Ljava.lang.String;` → `java.lang.String[]` (a varargs parameter is an array).
This reads a JVM type descriptor, a fixed format, not Java source.
[Structured test results](../done/lathe-structured-test-results.md) flagged array and varargs shapes as the open risk of this mapping, and `TestId`'s own comment records the mismatch; S6 confirmed it.
Results for such methods then land on their method, in Neovim as in VS Code.

**F. Follow the build's test-class filter.**
A module or package run today executes every test class JUnit finds, while `mvn test` runs only the classes Surefire's `includes`/`excludes` select (by default `**/Test*.java`, `**/*Test.java`, `**/*Tests.java`, `**/*TestCase.java`) and the tags its `groups`/`excludedGroups` select.
[Run, Test, and Debug](../done/lathe-run-test-debug.md) lists this as a known limitation (§9 there); S7 showed it matters on real projects, so F lifts it, and that document's limitation and `docs/status.md` change when F lands.
Spike S7 found the difference on real projects: Helidon's `config/config` includes only `**/*Test.java`, so three classes `mvn test` never runs are run by Lathe;
Dropwizard's `dropwizard-testing` has inner `*SubTest` fixtures that fail on purpose and are meant to be driven only by another test, and a Lathe module run reports them as failures.
The capture records the filter Surefire applied (its include/exclude patterns and tag expressions, as Surefire resolved them), and module runs, package runs and discovery apply it as a JUnit `ClassNameFilter` and `TagFilter`.
Running a class or a method explicitly ignores the filter, as IntelliJ does: the user asked for that class.
How the capture records it is settled when F is built: the in-fork capture listener sees the discovery request Surefire built (one class selector per included class), and the patterns themselves are in the plugin's configuration.

**G. Running a class runs its static nested classes.**
JUnit's class selector includes `@Nested` inner classes but not `static` nested ones, so running `Outer` skips `Outer$StaticNested`'s tests (spike S6), although `runnables.list` shows that class under `Outer`, in Neovim as in VS Code.
`lathe.run.test` adds a class selector for every member class of a selected class, from the module's captured test classes (`Outer$Name.class`; anonymous and local classes, whose names continue with a digit, are skipped).
JUnit ignores classes without tests and runs each test once even when a `@Nested` class is selected twice (S6: 17 results, no duplicates).

**H. Cancel stops the whole process tree.**
`LaunchSession.cancel` stops only the replay JVM (`destroy`, then `destroyForcibly` after a grace period).
Processes the tests or the `main()` started — an embedded server, a forked tool — outlive the cancel and keep their ports and files.
On Windows this is the common case for a `.cmd`-started tool, and VS Code/Cursor is Lathe's Windows client.
Cancel first stops the JVM's descendants (`ProcessHandle.descendants()`), then the JVM, as `FormatProcess` already does for a formatter.

### 3.3 Impact on the Neovim client

- New fields in `TestResult`: ignored.
- `lathe/testStarted` and `lathe/testsChanged`: unknown notifications, ignored.
- A container-failure record: neotest sees an unknown method id and falls back to the class's aggregate status, which is better than today's "no result".
  A spec pins that it doesn't crash on an empty `methodName`.
- Closed stdin: applies to Neovim runs too, by design.
- E, F, G and H need no adapter change and fix Neovim runs directly:
  array and varargs tests get their results, a module run stops running classes `mvn test` excludes, running a class runs its static nested classes,
  and cancelling a run also stops the processes it started.

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
7. **A check on open-file discovery.** The adapter waits for a buffer's first `DiagnosticChanged` before asking `runnables.list`, because it can't see when Neovim's LSP client has sent `didOpen`.
   Spike S3 showed the server side needs no more than that `didOpen` was sent: the server handles it before any later request and queues that request behind the open compile,
   so a query sent right after `didOpen` was complete 30 times out of 30.
   It also showed the wait can be fooled: on `didClose` the server publishes empty diagnostics, which can arrive just after a quick reopen and look like the first diagnostics of the new open.
   Worth checking whether the adapter can key on the `didOpen` itself (Neovim's client tracks which buffers it has opened) instead.

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
With two folders one client's registration throws, its `start()` rejects with ``command 'lathe.run.test' already exists``, and it stops (Starting → Running → Stopped):
a two-folder window loses one of its servers today, a random one (spike S5, on 1.84.2 and on stable).
The surviving server then receives the other folder's files as well, and reports them as "not under any Lathe module source root".
The client therefore adds a `StaticFeature` whose `preInitialize` deletes `executeCommandProvider` from the server capabilities it is given, before the command feature sees them.
With that feature and the folder-scoped selector, S5 saw both clients start (19 commands stripped), and each file opened on its own server only.
The extension's own user-facing commands use a separate prefix, `lathe.editor.*`, e.g. `lathe.editor.runMain`.

**"Add missing imports…" needs a handler of its own.**
The one server command VS Code itself invokes is `lathe.missingImports`, returned by the "Add missing imports…" code action.
It answers with candidates, not an edit:
`{insertionRange, items: [{name: "List", candidates: ["java.awt.List", "java.util.List"]}]}`.
The command `vscode-languageclient` registers today sends the request and drops that answer, so the action does nothing visible in VS Code, even with one folder.
The extension registers `lathe.missingImports` once, sends it to the client that owns `args[0].uri`, asks with a QuickPick for every name with more than one candidate, and applies the import lines as a `WorkspaceEdit` at `insertionRange`.
S5 checked the routing in both folders of a two-folder window.

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
  Child items per invocation were considered and rejected: they are the most persistent source of bugs in Microsoft's Java test extension (§13).

### 5.2 Discovery

Two sources fill one tree, and both speak the same ids.

**Workspace, lazily** (`lathe.tests.*`, §3.2 A):

- `resolveHandler(undefined)` (the Testing view opens) adds one module node per `lathe.tests.modules` entry, with `canResolveChildren`.
- `resolveHandler(module)` calls `lathe.tests.discover` and adds the module's packages, classes and methods.
  The module item is `busy` meanwhile; S7 measured up to about 1.9 s cold.
  `blockedReasons` becomes the module item's `error`, e.g. "no test capture, sync with `--tests`".
- `lathe/testsChanged` re-queries a module the user has already expanded; a module never expanded stays unexpanded.

**Open files, live** (`lathe.runnables.list`):

- The server answers `runnables.list` only for a document it has open.
  The client asks **right after sending `didOpen`**: in `LanguageClientOptions.middleware.didOpen`, after `await next(document)`.
  The server handles `didOpen` before any later request and queues that request behind the file's open compile, so the first answer is complete.
  Spike S3: 50/50 complete on 1.84.2 and 50/50 on stable when asking after the first diagnostics, and 30/30 on 1.84.2 when asking straight after `didOpen`, with a reopened document answered in a median of about 45 ms and a cold server in about 1.3 s.
- The first `publishDiagnostics` is **not** used as the open signal, although the design first proposed it (Neovim's adapter uses it, §3.4 item 7):
  on `didClose` the server publishes empty diagnostics, which S3 saw arrive just before or just after a quick reopen's `didOpen`, so they can pass for the new open's first diagnostics.
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
   The replay runs saved bytecode: an unsaved edit is not what runs (spike S4 ran an unsaved failing edit and it passed).
   A run sent right after the save does replay the new bytecode, because `didSave`'s compile and the run are queued on the same server worker in arrival order
   (S4: 10 of 10 alternating fail/pass edits, each run sent straight after `didSave` without waiting for diagnostics, about 0.5 s per method run).
   So the handler awaits the saves, then runs, with no extra wait (Metals reported tests green that never ran when a run beat the compile, §13).
2. Collect `request.include`; when it is undefined, run every module node.
3. Turn each included item into selectors:
   method → `METHOD`, class → `CLASS`, package → `PACKAGE`, all keyed by the item's id.
   A class's static nested classes come along server-side (§3.2 G), so the client sends one selector per included item.
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
     Its `location` is the `failureLine` (1-based, `-1` = none, as S6 confirmed) in the item's file.
     The `stackTrace` follows the message as Markdown, each reactor frame a link to its file and line
     (class → file through `lathe.testSources`); `TestMessageStackFrame` needs a newer VS Code than 1.84.
   - A container failure (empty `methodName`) marks the class `errored` with the message,
     and its methods that got no result `errored` with "class failed: …".
   - A result for an id the tree doesn't have — a test in a file nobody opened while the module was never expanded,
     or a signature `TestId` still can't match after §3.2 E — gets its items created on the spot from the id (package and class from the class name, `uri` through `lathe.testSources`).
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
rust-analyzer's lens and explorer runs went through different code and behaved differently (§13).

## 6. Running a `main()`

**Starting one.**

- **CodeLens** `▶ Run` on each `MAIN` target's range, computed client-side from `runnables.list` (the server has no CodeLens provider).
  The lens command is `lathe.editor.runMain {moduleRel, mainClass}` (`mainClass` = the target's `parentId`).
  The provider fires `onDidChangeCodeLenses` when a file's `runnables.list` answer arrives (asked right after `didOpen`, §5.2) and on save.
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
- Run-path fixes, pinned with the id shapes spike S6 used (kept as a fixture class):
  `TestId` maps `[I`, `[[J` and `[Ljava.lang.String;` to `int[]`, `long[][]` and `java.lang.String[]`, so array and varargs results match their `RunTarget` ids (E);
  a module run skips a class the captured Surefire patterns exclude and a test the captured tag expression excludes, while running that class explicitly still runs it (F);
  running `Outer` runs `Outer$StaticNested` once and `@Nested` classes once, and skips `Outer$1` (G);
  cancelling a run whose test started a child process (a fixture helper started on the test JDK's own `java`, per the portability rules) leaves neither process alive, on Linux and Windows (H).
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
This is the layer Microsoft's Java extension and Metals rely on (§13).

### 8.4 End-to-end (`@vscode/test-electron`, working-tree server)

This is the first editor E2E against a real Lathe server; the Neovim specs stub the server.

**Harness.**
- `@vscode/test-electron` (exact pin) downloads VS Code into `lathe-vscode/target/vscode-test/`, user data under `target/`, `--disable-extensions` (the development extension still loads).
  It runs twice: on **1.84.2**, the `engines` floor, and on current **stable**, which catches Testing API drift the floor can't (rust-analyzer does the same).
- Pins, from spike S1: `@vscode/test-electron` 3.1.0 and mocha **10.8.2**. Mocha 11 needs Node 18.18, and VS Code 1.84's extension host runs Node 18.15.
  `stable` is resolved with a network lookup on every run, so CI resolves it once and keys the download cache on the resolved version.
  Stable logs a stream of `[AgentHost]`/`[ChatModelSelection]` lines; the runner filters them out of the test output.
- Cost on Linux (S1): 1.84.2 is a 129 MB download (370 MB unpacked), stable 335 MB (990 MB); a warm run takes about 4–7 s, a cold one 12–32 s.
- **Display.** Linux runs under `xvfb-run -a` (on GitHub's Ubuntu runners it is installed).
  VS Code launches get `--ozone-platform=x11`, and `WAYLAND_DISPLAY` is removed from their environment:
  current VS Code otherwise prefers Wayland and opens its windows on the developer's own screen, outside Xvfb.
  Locally without root, `apt-get download xvfb` and `dpkg -x` into a temporary directory give a working `Xvfb` and `xvfb-run` (the spikes ran that way).
- **Forcing a cold open in a test.** Closing an editor doesn't make VS Code send `didClose` (S3 waited 30 s; the document stays alive).
  `languages.setTextDocumentLanguage(doc, 'plaintext')` and back to `'java'` makes the client send a real `didClose` and `didOpen`, without restarting the server.
- The workspace is the `multi-module` invoker fixture after the invoker has built it: `lathe-maven-plugin/target/it/multi-module`.
  Its `.lathe/` launcher points at the working-tree server in the invoker's own cache, so the E2E tests this checkout, not a release.
  `lathe-vscode` is already declared after `lathe-maven-plugin` in the root `pom.xml`, so a reactor build has the fixture ready.
- Bound to `integration-test` in `lathe-vscode/pom.xml` (`npm run e2e` through `frontend-maven-plugin`), skipped by `skipTests`.
  It **fails** under CI if the fixture or the display is missing, and **skips with a message** locally.
  CI can't be detected through `CI`: the workflow runs Maven as `env -u CI mvn …` so that Lathe stays enabled in the fixture builds, so `CI` is never set inside the build.
  The check uses `GITHUB_ACTIONS` instead.
  (`lathe-neovim/test/run-specs.sh` checks `CI` and so never fails in CI today; it moves to the same check.)
- On failure it prints the server's `lsp.log`.
- CI: it runs on every row that builds `lathe-vscode`. Today that is JDK 21 Linux, macOS (JDK 25) and Windows (JDK 25), so the Windows `.cmd` launcher path gets covered as well.
  The JDK 17, 25 and 27 Linux rows exclude the client modules.

**Observing results.**
VS Code has no stable API for reading another controller's results (`tests.testResults` is proposed API).
`activate()` therefore returns a small test-only handle: `{ controller(folder), run(items), runs: Event<RunRecord> }`.
`RunRecord` = per-item final state, durations, failure messages with locations and diffs, output lines with the item they were attributed to, exit code.
The tests trigger runs through the real UI commands (`testing.runAtCursor`, the CodeLens command) where one exists, so they exercise the same path a user does;
`run(items)` drives the same run handler for a chosen node, for which VS Code has no command.
S2 confirmed this on 1.84.2 and stable: `testing.runAtCursor` calls the controller's run handler with the method under the cursor (or the class, on its declaration line) within about 0.1 s, also with the Testing panel focused.
With the cursor outside every item's range it does nothing and raises no error, so every such test has its own timeout.

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

## 9. Spikes

All seven ran on 2026-10-24, on Linux, against a copy of the `multi-module` fixture (plus test classes for the id shapes and a failing `@BeforeAll`) and against Helidon and Dropwizard.
No repository code was changed; the throwaway harnesses live outside the repository.
None found a showstopper; each outcome is folded into the section it affects.

| # | Question | Outcome |
|---|---|---|
| S1 | Does `@vscode/test-electron` run VS Code 1.84.2 and stable headlessly, cached, in a sensible time? | **Pass on Linux**, both versions; numbers and pins in §8.4. macOS and Windows need a CI run, which is still open. |
| S2 | Can a test drive a custom controller through `testing.runAtCursor` / `testing.runAll` and read the outcome through an exported handle? | **Pass** on 1.84.2 and stable (§8.4). `runAll` passes `include` undefined. |
| S3 | Is the first `publishDiagnostics` a reliable "server has this file open" signal? | **The wrong signal, but the right result.** 50/50 complete answers on each version, and also 30/30 when asking straight after `didOpen`, which is the better trigger: empty diagnostics from a `didClose` can pass for a reopen's first ones (§5.2). |
| S4 | Does a run sent right after `didSave` replay the edited test? | **Pass**, 10/10; the save's compile and the run share the server worker's queue. An unsaved edit is not replayed, so the client saves first (§5.3). |
| S5 | Does a two-folder window fail today, and do the `preInitialize` feature and the folder-scoped selector fix it? | **Fails today** (a random client stops on "command already exists", the other receives both folders' files); **the fix works** on both versions. "Add missing imports…" turned out to do nothing in VS Code even with one folder (§4). |
| S6 | Do result ids equal `RunTarget` ids; is `failureLine` 1-based? | **Mostly.** All shapes match except array and varargs parameters (§3.2 E). `failureLine` is 1-based, `-1` = none. Also found: a class run skips static nested classes (§3.2 G), and a failing `@BeforeAll` produces no result at all (§3.2 C). |
| S7 | How long does discover mode take on large modules, and does it list what a run executes? | **About 0.5–1.9 s per module** (§3.2 A); no per-package split needed. Matches Surefire except for Surefire's class include/exclude patterns, which Lathe's module runs ignore today (§3.2 F). |

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
- Run stays on `lathe.run.*`, not a `noDebug` debug session: Metals moved its plain Run off DAP (§13), and our run path already streams per-test results that a DAP session only gives at the end.

## 11. Delivery

Editor-neutral pieces come first, and each one lands in Neovim before VS Code is touched,
so Neovim users gain from every phase and the server work is proven by an existing client.

**Phase 0: spikes.** Done (§9). The one open item is S1 on macOS and Windows, which needs a CI run and matters only for phase 3.

**Phase 1: run-path fixes and richer results.**

1. Server: the small run-path fixes, independent of each other, each a direct Neovim improvement with no adapter change:
   array and varargs ids (§3.2 E), static nested classes (§3.2 G), cancel stopping the process tree (§3.2 H), and the closed stdin (§3.2 D).
2. Server: container failures, `expected`/`actual`, `stackTrace`, `durationMs`, `displayName` and `lathe/testStarted` (§3.2 B, C and D); runner and server tests (§8.1).
3. Neovim: the spec for the new data (§3.3), then the adapter work of §3.4 items 2–6: per-test output, container failures, the inline and side-by-side diffs, structured stack frames, durations.
4. Capture and server: follow Surefire's test-class filter in module and package runs (§3.2 F).
   This touches the capture side (Maven), so it gets its own small design check first.

**Phase 2: whole-module discovery.**

5. Server: discover mode, `lathe.tests.modules`/`discover`, `lathe/testsChanged` (§3.2 A), applying the filter from step 4.
6. Neovim: the whole-module summary (§3.4 item 1), and the open-file discovery check (§3.4 item 7).

**Phase 3: VS Code.**

7. Groundwork: folder-scoped selector, the command-registration feature, and the `lathe.missingImports` handler that builds the import edit (§4). This fixes multi-root and "Add missing imports…" on their own.
8. Test Explorer: lazy and open-file discovery, run at every level, live results, diffs, stack traces, per-test output, cancel; unit and canned-event tests, E2E scenarios 1–5.
9. Run `main()`: CodeLens, run button and menu, run terminals, stop; E2E scenarios 6–7.
10. Named run configs: run, run last, save.

**Docs** with each phase: `README.md`, `docs/guide/` (`test-capture.md`, the Neovim cheatsheet, a new VS Code cheatsheet) and `docs/status.md`.

**Approval per step.**
Steps that span several classes or change the wire contract — at least 2, 4, 5, 7, 8 and 9 — each start with a short implementation summary (classes, records, tests) approved before any code, as the project's working rules require.
The design here is what those summaries implement; it doesn't replace them.

## 12. Open items

What this design leaves open, on purpose:

| Item | Status | Needed before |
|---|---|---|
| How the capture records Surefire's filter (§3.2 F) | Two candidate sources, the in-fork view of Surefire's discovery request or the plugin's configuration; settled in a short design check | Phase 1 step 4 |
| S1 on macOS and Windows | Linux verified; the other two need a CI run on a pushed branch | Phase 3 |
| Debugging (§10) | Deferred; the wiring is recorded, the design is not written | After phase 3 |
| TestNG through Surefire's own TestNG provider | Out of scope; what today's runs do on such a module was not checked | — |
| Neovim open-file discovery trigger (§3.4 item 7) | A check, not a decided change | Phase 2 step 6 |

Accepted limits, not open items: one id for a `@Nested` class inherited by two outer classes (§3.2 A);
a placeholder position for tests in unopened files in neotest (§3.4 item 1); no stdin for a `main()` (§6).

## 13. Prior art

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
