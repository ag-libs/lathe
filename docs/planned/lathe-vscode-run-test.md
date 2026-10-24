# Lathe — Run and Test in VS Code

**Status:** approved (2026-10-23), not built: lazy discovery first, debugging deferred.
Spikes (§9) come before any production code.

## 1. Goal

The VS Code extension runs tests and `main()` classes the way the Neovim client does:
replayed from the captured `.lathe/` bytecode, no Maven, results live in the editor.

Fast test runs without Maven are one of Lathe's main selling points, and the VS Code extension is also Lathe's Windows client (Cursor).
Today it is a pure language-client passthrough (`lathe-vscode/src/extension.ts`), so none of this reaches VS Code users.

## 2. Scope

- **In:** the Test Explorer (discover, run at method / class / package / module level, live results, failure messages, output, cancel);
  running a `main()` from a CodeLens or a command; running a named run config.
- **Deferred:** debugging (§10), coverage, continuous run, workspace-wide test discovery (§5.2).
- **Client only.** No server, Neovim-client or Maven-plugin changes
  (the one exception is the CI check in the Neovim spec runner, §8.2).
  Every server command and notification used here already exists and is editor-neutral
  ([Run, Test, and Debug](../done/lathe-run-test-debug.md), [Named run configs](../done/lathe-named-run-configs.md),
  [Structured test results](../done/lathe-structured-test-results.md), [Test output streaming](../done/lathe-test-output-streaming.md)).

## 3. The server contract this builds on

The server spawns the replay JVM, so the client never starts a Java process or builds a command line.

| Purpose | Server API (`workspace/executeCommand` unless noted) |
|---|---|
| Runnables in one open file | `lathe.runnables.list {uri}` → `RunTarget[]` `{id, parentId, kind, label, moduleRel, uri, range}` |
| What a directory runs as | `lathe.runnables.dir {uri}` → `{moduleRel, selections}` |
| Run tests | `lathe.run.test {moduleRel, selections, token}` → `LaunchOutcome` |
| Run a main | `lathe.run.main {moduleRel, mainClass, token}` → `LaunchOutcome` |
| Run a saved config | `lathe.run.named {name, token}`; list with `lathe.runconfigs.list {}`; save with `lathe.runconfig.save` |
| Cancel | `lathe.run.cancel {token}` |
| Per-test result (notification) | `lathe/testEvent {token, result}`, where `result` = `{className, methodName, methodParameterTypes, status, failureMessage, failureLine, positionId}` and `status` ∈ `passed`/`failed`/`skipped` |
| Output line (notification) | `lathe/testOutput {token, line: {stream, text}}`, where `stream` is the `TranscriptLine.Stream` ordinal (below) |

`LaunchOutcome` is `{launched, blockedReasons, exitCode, output, testResults}`.
Every `lathe/testEvent` and `lathe/testOutput` of a run arrives before the `lathe.run.*` response, because they share one ordered connection.
The neotest adapter relies on this ordering too.
The events are a best-effort live feed; the response's `testResults` is authoritative (`TestEventParams`).

Enums are serialized as ordinals, not names:

- `RunTarget.kind` (`RunnableKind`): `MAIN` 0, `TEST_METHOD` 1, `TEST_CLASS` 2, `TEST_PACKAGE` 3, `MAIN_CLASS` 4.
- `TranscriptLine.stream`: `STDOUT` 0, `STDERR` 1, `COMMAND` 2 (as `lathe.output` reads them).

The client mirrors these numbers in one place (`runnables.ts`).

## 4. Shape of the client

Three new files beside `extension.ts`, each one feature:

| File | Holds |
|---|---|
| `runnables.ts` | Pure functions over `RunTarget[]`: kind constants, the test-tree shape, selector building, cursor → main target. No `vscode` import, so they unit-test in plain Node like `launch.test.ts`. |
| `tests.ts` | The `TestController`, discovery, the run handler, result and output routing. |
| `run.ts` | The `main()` CodeLens, the run-main and run-config commands, the "Lathe Run" output channel. |

One `TestController` per workspace folder, labeled `Lathe` (`Lathe — <folder>` in a multi-root window).
Each folder already has its own `LanguageClient`, so a controller only ever talks to one server and token routing stays per client.

Server commands are sent with `client.sendRequest(ExecuteCommandRequest.type, …)`, not `vscode.commands.executeCommand`, so the request goes to that folder's server.

**Scope each client to its folder.**
Today's `documentSelector` (`extension.ts`) is `{scheme: 'file', language: 'java'}` with no path pattern, so in a multi-root window every folder's server receives `didOpen` and publishes diagnostics for every Java file.
Discovery (§5.2) would then query the wrong server.
The selector gains `pattern: <folder>/**`, as in Microsoft's multi-server sample, so one file belongs to exactly one client.

**Command-id collision.**
`vscode-languageclient` registers every command the server advertises (`lathe.run.main`, …) as a VS Code command under the same id.
The extension's own user-facing commands therefore use a separate prefix, `lathe.editor.*`, e.g. `lathe.editor.runMain`.
The same registration collides between clients: a second folder's client registers the same ids again and gets "command already exists".
Spike S5 checks both cases; if the second one fails, the client stops `vscode-languageclient` from registering the server's commands (it never invokes them through `vscode.commands` anyway).

## 5. Test Explorer

### 5.1 Tree

`module → package → class → method`, built from the `TEST_*` targets of each discovered file:

- A module node per `moduleRel` (label = the module directory; `""` is the reactor root).
- Packages, classes and methods come from the targets' `parentId` chain, so nested classes nest naturally.
- `TestItem.id` is the server's target `id`, and its `uri`/`range` come from the target.
  A method's `id` equals the `positionId` its `lathe/testEvent` carries; neotest depends on the same equality.
- Parameterized and repeated invocations are a single method item.
  The status is worst-wins across invocations, matching neotest.
  VS Code keeps whichever state was set last, so the run handler records each item's worst status so far
  and never moves a `failed` item back to `passed` or `skipped`.

### 5.2 Discovery

The server answers `runnables.list` only for a document it has open, so discovery is **lazy**: a file's tests appear once the file is opened.

- Discover a file after its **first `publishDiagnostics`**, through `LanguageClientOptions.middleware.handleDiagnostics`.
  The server publishes only after its open-mode compile, so that is the reliable "the server has this file open" signal.
  Neovim hit the same race between `didOpen` and the first discovery request, and fixed it the same way.
- Re-discover on save, and on later diagnostics after an edit (debounced), so new or renamed tests show up.
  A rediscovery replaces that file's items.
- Items stay in the tree after the file is closed; the `refreshHandler` re-queries every file still in the tree.
- A module or package node stays runnable without opening its files, because `runnables.dir` resolves it from the reactor layout.

**Accepted limitation:** a fresh window shows an empty Test Explorer until a test file is opened.
Workspace-wide discovery needs a new server command (e.g. test classes from the type index, methods resolved when a class is expanded).
That is a separate design, to do once testers report how much the sparse tree bothers them.

### 5.3 Run handler

One `Run` profile (`TestRunProfileKind.Run`, default).

1. Collect `request.include`; when it is undefined, run every module node.
   With lazy discovery (§5.2) that means every module with at least one opened test file, not every module in the reactor.
2. Turn each included item into selectors:
   method → `METHOD`, class → `CLASS`, package → `PACKAGE`, all keyed by the item's id.
   A module node calls `runnables.dir` on the module directory (the folder path joined with `moduleRel`).
   When `request.exclude` is non-empty, expand the included nodes down to methods and drop the excluded ones, because Lathe selectors have no exclude.
3. Group selectors by `moduleRel`.
   Run one `lathe.run.test` per module, **sequentially**, so output from different modules doesn't interleave.
4. At the start, mark every method under the included items as `enqueued`, then as `started` when its module's launch begins.
   The server sends no per-test start event.
5. `lathe/testEvent` → `passed` / `failed` / `skipped` on the item with that `positionId`.
   A failure becomes a `TestMessage(failureMessage)` with a `Location` on the failure line when `failureLine` ≥ 1.
   The line convention is to be verified (spike S6) against what neotest does with it.
6. When the `lathe.run.test` response arrives:
   - `launched == false` → every enqueued item is `errored` with `blockedReasons` (e.g. "capture is stale, sync").
   - Otherwise first apply the response's `testResults` to every item that no `lathe/testEvent` reported, the same way as in step 5, because the live events are best-effort.
   - Launched with a non-zero exit and items still unreported → those items are `errored` with "no result (exit N)".
   - Otherwise unreported items are left unrun.
7. `run.end()` once every module has finished.

**Output.**
`lathe/testOutput` → `run.appendOutput(text + "\r\n")`; the Test Results terminal needs CRLF.
`STDERR` (1) lines are wrapped in red ANSI, and the `COMMAND` (2) line (the replay command, always first) is dimmed.
Output is attached to the run, not to individual tests: the server doesn't attribute lines to tests.

**Cancel.**
The run's `CancellationToken` → `lathe.run.cancel {token}`.
The run then ends normally, when its `lathe.run.test` response arrives.

**Routing.**
A per-client `Map<token, RunSink>` receives the two notifications; tokens are minted by the client and unique per launch.
The main-run path (§6) uses the same map.

## 6. Running a `main()`

- **CodeLens** `▶ Run` on each `MAIN` target's range, computed client-side from `runnables.list` (the server has no CodeLens provider).
  The lens command is `lathe.editor.runMain {moduleRel, mainClass}` (`mainClass` = the target's `parentId`).
  Neovim also puts a run sign on the `MAIN_CLASS` line.
  VS Code doesn't add a second lens there: the `main` lens sits a few lines below and runs the same thing, and two identical lenses on one class would be noise.
- **Command** `Lathe: Run Main` (`lathe.editor.runMain` with no argument) resolves the main at the cursor:
  the main whose method range holds the cursor, else the one whose class does, else the file's only main.
  This ports `lathe.run._main_target_for` into `runnables.ts`.
- **Output** goes to a `Lathe Run` output channel, cleared and shown per run, with the replay command as its first line.
  While a run is going, a status-bar item `$(debug-stop) <class>` cancels it.
  At the end there is a notification "`<class>` exited N" (information on 0, warning otherwise), or "run blocked: …" when the run didn't launch.
- **No stdin.** The server never writes to the replay JVM's input, so an interactive `main()` can't read input in any editor.
  This is documented as a known limit rather than worked around; it is why the output goes to a channel and not a terminal, which would suggest interactivity.

**Named configs.**
`Lathe: Run Config…` is a QuickPick over `lathe.runconfigs.list` (`name · module · target`) → `lathe.run.named`.
`Lathe: Run Last Config` repeats the last one.
`Lathe: Save Run Config` saves the runnable under the cursor through `lathe.runconfig.save` (an optional name from an input box) and opens the file at the new entry.
This ports Neovim's `_save_target_for`.

## 7. Contributions (`package.json`)

- Commands: `lathe.editor.runMain`, `lathe.editor.runConfig`, `lathe.editor.runLastConfig`, `lathe.editor.saveRunConfig`, `lathe.editor.stopRun`.
- `activationEvents` don't change: Test Explorer and CodeLens are registered in `activate`, which already runs on `onLanguage:java` / `workspaceContains:**/.lathe`.
- **Other Java test extensions.**
  If `vscjava.vscode-java-test` is active, Test Explorer shows two trees for the same tests.
  The extension shows a one-time notice; it doesn't try to disable the other extension.

## 8. Tests

### 8.1 Unit (plain Node, beside `launch.test.ts`)

The `runnables.ts` functions:
the tree built from targets (nesting, parameterized collapse, the reactor-root module `""`);
selector building, with and without excludes;
cursor → main (method range, class range, single main, none, two mains with the cursor on neither);
cursor → save request;
worst-wins across invocations (a `failed` followed by a `passed` stays `failed`);
outcome → final states (blocked, a result present only in the response's `testResults`, non-zero exit with unreported items, clean);
the stream ordinal → rendering (stderr red, command dimmed).

### 8.2 End-to-end (`@vscode/test-electron`, working-tree server)

This is the first editor E2E against a real Lathe server; the Neovim specs stub the server.

**Harness.**
- `@vscode/test-electron` (exact pin) downloads a pinned VS Code, **1.84.2**, the `engines` floor, into `lathe-vscode/target/vscode-test/`.
  The tests run inside the extension host with `--disable-extensions` (the development extension still loads) and a user-data dir under `target/`.
- The workspace is the `multi-module` invoker fixture after the invoker has built it: `lathe-maven-plugin/target/it/multi-module`.
  Its `.lathe/` launcher points at the working-tree server in the invoker's own cache, so the E2E tests this checkout, not a release.
  `lathe-vscode` is already declared after `lathe-maven-plugin` in the root `pom.xml`, so a reactor build has the fixture ready.
- Bound to `integration-test` in `lathe-vscode/pom.xml` (`npm run e2e` through `frontend-maven-plugin`), skipped by `skipTests`.
  It **fails** under CI if the fixture or the display is missing, and **skips with a message** locally.
  CI can't be detected through `CI`: the workflow runs Maven as `env -u CI mvn …` so that Lathe stays enabled in the fixture builds, so `CI` is never set inside the build.
  The check uses `GITHUB_ACTIONS` instead.
  (`lathe-neovim/test/run-specs.sh` checks `CI` and so never fails in CI today; it moves to the same check.)
  On Linux it runs under `xvfb-run -a`.
- CI: it runs on every row that builds `lathe-vscode`. Today that is JDK 21 Linux, macOS (JDK 25) and Windows (JDK 25), so the Windows `.cmd` launcher path gets covered as well.
  The JDK 17, 25 and 27 Linux rows exclude the client modules.

**Observing results.**
VS Code has no stable API for reading another controller's results (`tests.testResults` is proposed API).
`activate()` therefore returns a small test-only handle: `{ controller(folder), runs: Event<RunRecord> }`.
`RunRecord` = per-item final state, failure messages with locations, output lines, exit code.
The tests **trigger** runs through the real UI commands (`testing.runAtCursor`, `testing.runAll`, the CodeLens command), so they exercise the same path a user does.

**Scenarios** (fixture classes: `jpms` `HelloTest` with three `@Test`s and a `@ParameterizedTest`, including one that writes to both streams; `app` `Main` printing `ALICE`; `jpms` `HelloTester`, a `main` in test sources):

1. Discovery: opening `HelloTest.java` yields `jpms` → `com.example.jpms` → `HelloTest` → four methods, with the parameterized test as one item.
   `Main.java` (no tests) adds nothing to the tree.
2. Run class at cursor: four `passed`.
   The output's first line is the replay command, and it contains `hello on stdout` and `warning on stderr`.
3. Run one method at cursor: only that method gets a state.
4. Edit → save → rerun: change `greet_returnsExpectedMessage`'s expected string, save, wait for diagnostics, run.
   The method is `failed`, with a message naming both strings and a location on the assertion line.
   The file is restored and saved in a `finally`.
5. `testing.runAll` after only `HelloTest.java` was opened: every `jpms` test passes, run through the module node (`runnables.dir`).
   No other module runs, because none was discovered; in particular the `verify` module's `LspSmokeTest`/`MultiModuleTest` must never start from here.
6. Main: `vscode.executeCodeLensProvider` on `Main.java` returns a `▶ Run` lens on `main`, and none on `Greeter.java`.
   Executing it gives exit code 0 and `ALICE` in the run's output.
7. Test-scope main: the lens on `HelloTester.main` runs it through the module's test launch and prints `tester n=3`.

Cancel is covered by unit tests only: the fixture has no long-running test, and adding one would slow every invoker run.

## 9. Spikes (before production code)

No showstopper has been found; these settle the unknowns that could change the design.

| # | Question | Done when |
|---|---|---|
| S1 | Does `@vscode/test-electron` with VS Code 1.84.2 run on all three CI OSes (xvfb on Ubuntu, no display on macOS/Windows), with the download cached, within a sensible time budget? | A trivial extension-host test opens `HelloTest.java` in the fixture and sees the server's diagnostics on Linux, macOS and Windows CI rows. |
| S2 | Can a test drive a custom controller through `testing.runAtCursor` / `testing.runAll` headlessly, and see the results through the exported handle? | A throwaway controller's run handler fires from both commands, and the test reads its outcome. |
| S3 | Is the first `publishDiagnostics` (middleware) a reliable "file is open on the server" signal in `vscode-languageclient` 9, so the first `runnables.list` never comes back empty? | 50 cold opens of `HelloTest.java`, each discovering all four methods on the first query. |
| S4 | Does edit → save → rerun replay the edited test, i.e. does the in-process save compile land on the replay classpath for an already-captured class? | Scenario 4 fails and passes as expected by hand, through the existing Neovim client or `dev/explore.py`. |
| S5 | Command-id collision: confirm `vscode-languageclient` auto-registers the server's advertised `lathe.*` commands, and that `lathe.editor.*` is free. Then open a two-folder window: does the second client fail with "command already exists", and does the folder-scoped `documentSelector` keep each file on one server? | A list of registered commands in a single-folder window; a two-folder window where both clients start and each file is opened on only its own server. |
| S6 | Ids and lines: does `positionId` equal the method target `id` for nested classes and parameterized tests? Is `failureLine` 1-based, with -1 meaning none? | Checked against `TestId`, `RunnableScanner` and neotest's mapping, and pinned by a unit test. |

S1 and S2 are the risky ones: they decide whether the E2E is feasible as designed.
S3 decides whether discovery needs a retry.

## 10. Debugging (deferred, not designed)

The server side exists: `lathe.debug.test/main/named` start a suspended replay and return `{dapPort, jdwpPort}` for an in-process, attach-only DAP host.
Two VS Code constraints to address when this is designed:

- VS Code resolves the debug configuration **before** it creates the adapter.
  Neovim's order (start the adapter, then enrich the attach request with the JDWP port) doesn't carry over;
  the launch probably has to happen in `resolveDebugConfigurationWithSubstitutedVariables`.
- `DebugSessionOptions.testRun` (linking a debug session to a `TestRun`) is not in the `@types/vscode` 1.84 API.
  A debug profile on the Test Explorer therefore needs either a higher engine floor or its own bookkeeping of `lathe/testFinished`.

## 11. Delivery slices

1. Spikes S1–S6.
2. Test Explorer run: lazy discovery, run at every level, live results, failure messages, output, cancel; unit tests and E2E scenarios 1–5.
3. Run `main()`: CodeLens, command, output channel, stop; E2E scenarios 6–7.
4. Named run configs: run, run last, save.
5. Update `README.md` / `docs/guide/editors/` (a VS Code cheatsheet) and `docs/status.md` (Run/test: "Implemented (Neovim, VS Code)").
