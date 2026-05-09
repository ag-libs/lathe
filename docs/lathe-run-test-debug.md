# Lathe — Run, Test, and Debug

Post-v1 design. Builds on `lathe-design-v2.md`. Adopted only after the main design is implemented and stable.

---

## Principle

Lathe spawns Maven for every run, test, and debug invocation triggered from the editor. The LS does not reimplement classpath construction, JPMS module path resolution, JaCoCo agent injection, profile-driven `argLine` composition, or any other Surefire/exec-plugin behaviour — it shells out with the right goal and lets Maven do what Maven does.

This carries the shim philosophy of section 5 of the main design one level up: capture-by-interposition becomes execution-by-delegation. The user's run/test/debug experience reflects exactly the configuration they've already written into their POM, with no Lathe-side capture step, no params file for tests, and no staleness window.

---

## `mvnd` requirement

Spawning vanilla `mvn` per request adds 3–8s of JVM startup and plugin classloader warmup before the test JVM forks — too slow for an inner-loop "run test, edit, run again" UX. Lathe assumes `mvnd` (Maven Daemon) is on PATH, either as the `mvnd` binary directly or as a symlink/alias for `mvn`. With `mvnd`, per-invocation overhead drops to under 1s.

If `mvnd` is unavailable the LS still works but surfaces a one-time warning: "Install `mvnd` for faster run/test/debug — falling back to `mvn` (slow)."

---

## LS command surface

Six `workspace/executeCommand` commands and one streaming notification. Editors translate these into native run/test/debug UI.

### `lathe.runnables.list`

Given a file URI, return everything in scope that can be run or debugged.

```json
{
  "runnables": [
    {
      "id": "main:module-a:com.example.Main",
      "kind": "main",
      "label": "Main.main()",
      "moduleRel": "module-a",
      "mainClass": "com.example.Main",
      "uri": "file:///workspace/module-a/src/main/java/com/example/Main.java",
      "range": { "start": { "line": 12, "character": 22 }, "end": { "line": 12, "character": 26 } }
    }
  ]
}
```

`kind` is `"main"`, `"test-class"`, `"test-method"`, or `"test-parameterized"`. The `id` is opaque-but-stable — editors round-trip it back to the LS without parsing.

Discovery is a one-pass AST walk on the cached `CompilationUnitTree` from section 6 of the main design: `public static void main(String[])` for main entries; `@Test`, `@ParameterizedTest`, `@TestFactory`, `@RepeatedTest`, JUnit 4's `@Test`, and TestNG's `@Test` for tests.

### `lathe.runnables.listInWorkspace`

Same shape, no file argument, returns runnables across all reactor modules. Used by editors that populate a workspace-wide test tree on open. Lazy population is encouraged for large reactors: enumerate test classes from the type index (section 7 of the main design), expand methods on demand via `lathe.runnables.list`.

### `lathe.run`

Execute a runnable, no debug attach.

```json
{
  "command": "lathe.run",
  "arguments": [{
    "runnableId": "test-method:module-a:com.example.FooTest#bar",
    "args": [], "vmArgs": [], "env": {}, "cwd": null
  }]
}
```

The LS spawns `mvnd -pl <moduleRel> -am test -Dtest=<fqn>#<method> -DfailIfNoTests=false -Dsurefire.useFile=false` for tests, or `mvnd -pl <moduleRel> -am compile exec:exec -Dexec.executable=java -Dexec.args="..."` for main classes. Returns `{ sessionId, kind: "run" }` immediately; output and lifecycle stream via `lathe/sessionEvent`.

### `lathe.debug`

Same as `lathe.run` but the LS also sets up JDWP. Binds an ephemeral port, adds the JDWP agent (`-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:<port>`) to Surefire's `-Dmaven.surefire.debug` for tests or to the exec command line for main classes, scans Maven's stdout for `Listening for transport dt_socket at address: <port>`, then returns `{ sessionId, kind: "debug", jdwpHost, jdwpPort }`. The editor's DAP client attaches to that port.

`suspend=y` means the JVM blocks until DAP attaches — no race window where the program runs past breakpoints before the debugger connects.

### `lathe.session.cancel`

Kill a running session by `sessionId`. The LS signals `mvnd` (which propagates termination to the forked JVM and any test forks). Returns once the process tree is reaped.

### `lathe.session.list`

Return `{ sessionId, kind, runnableId, startedAt, status }` for all live sessions. Used by editors that show a session picker or "stop all" UI.

---

## Streaming events — `lathe/sessionEvent`

Server-to-client notification carrying output and lifecycle for a session.

```json
{ "sessionId": "run-abc123", "type": "output", "channel": "stdout", "data": "FooTest.bar -- PASSED\n" }
```

Event `type` values:

- `"started"` — `{ pid, jdwpPort? }`. Emitted once the process is up and (for debug) JDWP is listening.
- `"output"` — `{ channel: "stdout"|"stderr"|"maven", data }`. Forwarded from the process. The `"maven"` channel carries Maven's own log lines (filtered — see below); `"stdout"`/`"stderr"` are the test/program's own output.
- `"testResult"` — `{ testId, status: "passed"|"failed"|"skipped", durationMs, message?, stackTrace? }`. Emitted by the JUnit Platform `TestExecutionListener` Lathe bundles on Surefire's classpath via a small `<dependencies>` block wired in by `lathe:sync`. JUnit 4 and TestNG fall back to parsing `target/surefire-reports/TEST-*.xml` after `"exit"`.
- `"exit"` — `{ exitCode, elapsedMs }`. Final event for the session.

---

## Output filtering

Maven is verbose. By default the LS drops `[INFO] Scanning for projects`, dependency resolution chatter, plugin descriptor banners, and other lifecycle noise from the `"maven"` channel. Surefire output, exec-plugin output, and the program's own stdout/stderr are forwarded verbatim. Set `LATHE_RUN_VERBOSE=1` to disable filtering.

---

## JDWP debug — bundled `java-debug` adapter

Lathe bundles Microsoft's `java-debug` (open source, JDI-based) as the DAP adapter. It runs in attach-only mode against the JDWP port the LS allocated.

`lathe:sync` resolves `com.microsoft.java:com.microsoft.java.debug.plugin` from Central, installs to `~/.cache/lathe/bin/<version>/lib/java-debug-adapter.jar`, and writes `lathe-debug-launcher.sh` next to `lathe-launcher.sh` (section 9 of the main design). Editors point their DAP client at the launcher; the launcher hosts `java-debug` on stdio.

Lathe does not implement its own DAP↔JDI translation. Attach-only is sufficient because Maven launches the JVM — `java-debug` only needs to translate DAP requests into JDI operations on an already-running target. This keeps Lathe out of the JVM-launching business entirely.

---

## JDWP handshake

1. Editor calls `lathe.debug` with a `runnableId`.
2. LS allocates a free port by binding/closing an ephemeral socket.
3. LS spawns `mvnd` with the JDWP agent set to `suspend=y` on the chosen port.
4. LS scans Maven's stdout line-by-line for `Listening for transport dt_socket at address: <port>`.
5. On match, LS responds to `lathe.debug` with `{ jdwpPort }`.
6. Editor connects its DAP client to `127.0.0.1:<port>` via the bundled launcher.
7. `java-debug` issues JDI attach. JVM resumes from suspend.
8. Debug session proceeds normally.

A 30-second timeout on step 4 covers the case where Maven fails to start; on timeout the LS emits `"exit"` with the captured stderr.

---

## Session lifecycle and cleanup

Each `lathe.run` / `lathe.debug` invocation creates a new session. Sessions are not reused; cancelling one does not affect others. `mvnd`'s persistent daemon handles warmup — the per-session `mvnd` invocation is a fast client connection to a long-lived daemon.

The LS tracks `Process` handles for all live sessions. On LS shutdown (LSP `exit` notification or process signal), all sessions are cancelled and reaped. Orphaned JVMs from prior LS crashes are not detected — they belong to the user's process tree and are cleaned up by the next `mvnd --stop` if needed.

---

## Editor integration

The LS exposes data and execution. Editors own UI translation:

- **Neovim** — a `neotest-lathe` adapter (~250 lines Lua) drives `neotest` for test discovery and run; `nvim-dap` configuration (~30 lines Lua) consumes `lathe.debug`'s response and attaches via the bundled launcher. Optional companion plugin places gutter run buttons from `lathe.runnables.list`.
- **VS Code** — the `vscode-lathe` extension (section 9 of the main design) gains a `TestController` for the Testing API, a `CodeLens` provider for inline Run/Debug links, and a `DebugAdapterDescriptorFactory` for the `lathe` debug type. ~600 lines TypeScript total.

Both editors call the same six LS commands and consume the same `lathe/sessionEvent` notification. The protocol is editor-agnostic; the translation layers are not.

---

## Not in scope

- **Hot code replace.** `java-debug` supports it; Lathe does not actively integrate it. Future work.
- **Test sharding / parallel execution control from the editor.** Whatever Surefire is configured to do is what runs. The editor cannot force `forkCount=1` for a single test invocation.
- **Coverage UI.** If the user has JaCoCo wired into their POM, runs produce `.exec` files; rendering coverage in the editor is out of scope.
- **Launch configuration UI.** Editing `<argLine>`, profile selection, etc. happens in `pom.xml`. Lathe does not synthesize launch configurations the user must maintain in JSON.
