# Lathe — Run, Test, and Debug

Post-M3 design.
Builds on `lathe-design.md`.
Adopted only after the Neovim-focused Maven Central release is implemented and stable.

---

## Principle

Lathe spawns Maven for every run, test, and debug invocation triggered from the editor.
The LS does not reimplement classpath construction, JPMS module path resolution, JaCoCo agent injection, profile-driven
`argLine` composition, or any other Surefire/exec-plugin behaviour —
it shells out with the right goal and lets Maven do what Maven does.

This carries the shim philosophy of section 5 of the main design one level up:
capture-by-interposition becomes execution-by-delegation.
The user's run/test/debug experience reflects exactly the configuration they've already written into their POM, with no
Lathe-side capture step, no params file for tests, and no staleness window.

---

## `mvnd` requirement

Spawning vanilla `mvn` per request adds 3–8s of JVM startup and plugin classloader warmup before the test JVM forks —
too slow for an inner-loop "run test, edit, run again" UX.
Lathe assumes `mvnd` (Maven Daemon) is on PATH, either as the `mvnd` binary directly or as a symlink/alias for `mvn`.
With `mvnd`, per-invocation overhead drops to under 1s.

If `mvnd` is unavailable, the LS still works but surfaces a one-time warning:
"Install `mvnd` for faster run/test/debug — falling back to `mvn` (slow)."

### Output streaming

`mvnd` streams stdout in real-time when Lathe captures it via a pipe.
When no TTY is detected (which is always the case for a programmatically spawned process), `mvnd` switches
automatically to plain-text non-buffered mode — each log line is flushed immediately as the daemon sends it.
No `-B` flag or special configuration is needed.
The output format is identical to `mvn`.

---

## LS command surface

Five `workspace/executeCommand` commands and one streaming notification.
Editors translate these into native run/test/debug UI.

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

`kind` is `"main"`, `"test-class"`, or `"test-method"`.
The `id` is opaque-but-stable — editors round-trip it back to the LS without parsing.

Discovery is a one-pass AST walk on the cached `CompilationUnitTree` from section 6 of the main design:
`public static void main(String[])` for main entries;
`@Test`, `@ParameterizedTest`, `@TestFactory`, `@RepeatedTest`, JUnit 4's `@Test`, and TestNG's `@Test` for tests.

### `lathe.run`

Execute a runnable, no debug attach.
Accepts two call shapes — by runnable ID (method/class/main) or by package pattern (directory-level run):

```json
{ "runnableId": "test-method:module-a:com.example.FooTest#bar" }
```

```json
{ "module": "module-a", "testPattern": "com.example.payments.*" }
```

The LS spawns:

- **test-method**: `mvnd -pl <moduleRel> -am test -Dtest=<fqn>#<method> -DfailIfNoTests=false`
- **test-class**: `mvnd -pl <moduleRel> -am test -Dtest=<fqn> -DfailIfNoTests=false`
- **package pattern**: `mvnd -pl <module> -am test -Dtest=<testPattern> -DfailIfNoTests=false`
- **main**: `mvnd -pl <moduleRel> -am compile exec:exec -Dexec.executable=java -Dexec.args="-classpath %classpath <mainClass>"`

The exec plugin is resolved by Maven on demand — no `<plugin>` block in the user's POM is required.
`%classpath` is the exec plugin's interpolation token; it expands to the full project classpath at invocation time.

Returns `{ sessionId, kind: "run" }` immediately; output and lifecycle stream via `lathe/sessionEvent`.

### `lathe.debug`

Same as `lathe.run` but the LS also sets up JDWP.
Binds an ephemeral port, adds the JDWP agent (`-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:<port>`)
to Surefire's `-Dmaven.surefire.debug` for tests or to the exec command line for main classes, scans Maven's stdout for
`Listening for transport dt_socket at address: <port>`, then returns `{ sessionId, kind: "debug", dapHost, dapPort }`.
The editor's DAP client attaches to the DAP port exposed by the in-process `java-debug` adapter.

`suspend=y` means the JVM blocks until DAP attaches — no race window
where the program runs past breakpoints before the debugger connects.

### `lathe.session.cancel`

Kill a running session by `sessionId`.
The LS destroys the `mvnd` client process.
`mvnd` detects the client disconnect and cancels the running build, which propagates termination to any
Surefire-forked JVMs.
If the daemon crashes before propagating, Surefire forks may outlive the session; they are not tracked
separately and will be cleaned up by the next `mvnd --stop`.
Returns once the client process has exited.

### `lathe.session.list`

Return `{ sessionId, kind, runnableId, startedAt, status }` for all live sessions.
Used by editors that show a session picker or "stop all" UI.

---

## Streaming events — `lathe/sessionEvent`

Server-to-client notification carrying output and lifecycle for a session.

```json
{ "sessionId": "run-abc123", "type": "output", "channel": "stdout", "data": "FooTest.bar -- PASSED\n" }
```

Event `type` values:

- `"started"` — `{ pid, jdwpPort?
  }`.
  Emitted once the process is up and (for debug) JDWP is listening.
- `"output"` — `{ channel: "stdout"|"stderr"|"maven", data }`.
  Forwarded from the process.
  The `"maven"` channel carries Maven's own log lines (filtered — see below);
  `"stdout"`/`"stderr"` are the test/program's own output.
  For test runs, Surefire captures test stdout/stderr into `surefire-reports/*.txt` — these are included in
  the XML and surfaced via `testResult` events rather than `output` events.
  For main-class runs (`exec:exec`), the spawned program's stdout flows directly through the captured process
  stream without a `[INFO]`/`[WARNING]`/`[ERROR]` prefix; Lathe routes non-prefixed lines to `"stdout"` and
  Maven's own prefixed lines to `"maven"`.
- `"testResult"` — `{ testId, status: "passed"|"failed"|"skipped", durationMs, message?, stackTrace?
  }`.
  Surefire writes one `TEST-*.xml` file per test class into `target/surefire-reports/` as each class finishes.
  Lathe registers a `WatchService` on that directory **before spawning `mvnd`** to avoid missing XMLs from
  fast-completing test classes, then parses each XML immediately when it appears.
  Each `<testcase>` element in the XML becomes a `testResult` event; a `<failure>` or `<error>` child
  element supplies the `message` and `stackTrace`.
  This approach requires no extra configuration, no bundled listener JARs, and works for JUnit 4, JUnit 5,
  and TestNG.
  On `"exit"`, Lathe sweeps any XMLs not yet processed to cover edge cases where the watch event was delayed.
- `"exit"` — `{ exitCode, elapsedMs }`.
  Final event for the session.

---

## Output filtering

Maven is verbose.
By default the LS drops `[INFO] Scanning for projects`, dependency resolution chatter, plugin descriptor banners,
and other lifecycle noise from the `"maven"` channel.
Surefire output, exec-plugin output, and the program's own stdout/stderr are forwarded verbatim.
Set `LATHE_RUN_VERBOSE=1` to disable filtering.

---

## JDWP debug — bundled `java-debug` adapter

Lathe bundles Microsoft's `java-debug` (open source, JDI-based) as the DAP adapter.
`java-debug` runs **in-process inside the Lathe JVM** — no separate launcher process or subprocess is needed.
This mirrors how jdtls hosts `java-debug` as an in-process plugin.

On the first `lathe.debug` call, Lathe loads the bundled `java-debug` JARs, binds a DAP listener socket on a
free port, and has `java-debug` attach to the Surefire-forked test JVM via JDI/JDWP over localhost.
The editor's DAP client (`nvim-dap`) connects to Lathe's DAP socket directly.

```
nvim-dap ◄──DAP/socket──► [Lathe JVM: java-debug in-process]
                                        │
                                  JDI / JDWP / localhost
                                        │
                            [Test JVM forked by Surefire]
```

Lathe does not implement its own DAP↔JDI translation.
Attach-only is sufficient because Maven launches the JVM —
`java-debug` only needs to translate DAP requests into JDI operations on an already-running target.
This keeps Lathe out of the JVM-launching business entirely.

---

## JDWP handshake

1. Editor calls `lathe.debug` with a `runnableId`.
2. LS allocates two free ports: one for JDWP (test JVM), one for DAP (nvim-dap).
3. LS spawns `mvnd` with the JDWP agent set to `suspend=y` on the JDWP port.
4. LS scans Maven's stdout line-by-line for `Listening for transport dt_socket at address: <port>`.
5. On match, LS starts the in-process `java-debug` DAP server on the DAP port; `java-debug` attaches
   to the test JVM via JDI on the JDWP port.
6. LS responds to `lathe.debug` with `{ sessionId, kind: "debug", dapHost, dapPort }`.
7. Editor connects `nvim-dap` to `127.0.0.1:<dapPort>`.
   `java-debug` issues JDI attach; JVM resumes from suspend.
8. Debug session proceeds normally.

A 30-second timeout on step 4 covers the case where Maven fails to start;
on timeout the LS emits `"exit"` with the captured stderr.

---

## Session lifecycle and cleanup

Each `lathe.run` / `lathe.debug` invocation creates a new session.
Sessions are not reused; cancelling one does not affect others.
`mvnd`'s persistent daemon handles warmup — the per-session `mvnd` invocation is a fast client connection to a
long-lived daemon.

The LS tracks `Process` handles for all live sessions.
On LS shutdown (LSP `exit` notification or process signal), all sessions are cancelled and reaped.
Orphaned JVMs from prior LS crashes are not detected —
they belong to the user's process tree and are cleaned up by the next `mvnd --stop` if needed.

---

## Editor integration

The LS exposes data and execution.
Editors own UI translation.

All five commands and `lathe/sessionEvent` are standard LSP (`workspace/executeCommand` and server-to-client
notification).
No editor-specific assumptions live in the protocol; the translation layers are editor-specific, not the LS.

### Neovim (primary target)

A `neotest-lathe` adapter (~250 lines Lua) drives `neotest` for test discovery and run.
`nvim-dap` configuration (~30 lines Lua) consumes `lathe.debug`'s `dapPort` response and connects directly to
Lathe's in-process DAP server.
An optional companion plugin places gutter run buttons populated from `lathe.runnables.list`.

**What the adapter implements (not Lathe's responsibility):**

- `root(dir)` — walks up to the Maven project root (`.git` + `pom.xml`/`mvnw`)
- `filter_dir(name, rel_path, root)` — prunes `target/`, `build/`, `src/main/`, `resources/`;
  keeps only paths under `src/test/java/`
- `is_test_file(path)` — `.java` file whose name matches test class patterns
- `discover_positions(file_path)` — calls `lathe.runnables.list`; maps results to a neotest position tree
- `build_spec` for method/class nodes — calls `lathe.run` with `runnableId`
- `build_spec` for directory nodes — derives `{ module, testPattern }` from the filesystem path
  (`src/test/java/com/example/payments/` → module from nearest `pom.xml`, pattern `com.example.payments.*`);
  calls `lathe.run` with `{ module, testPattern }` — one `mvnd` invocation for the whole package
- `results(spec, result, tree)` — consumes `testResult` events from `lathe/sessionEvent`; marks each
  neotest position passed/failed/skipped as events arrive, giving per-test feedback at class-completion
  granularity without waiting for the full run to finish

**Panel appearance (`:Neotest summary`):**

```
▼ module-payments
  ▼ src/test/java
    ▼ com/example
      ▼ payments          ← select → one mvnd invocation for com.example.payments.*
          ▼ ✗ PaymentServiceTest
              ✓ create_valid_succeeds
              ✗ create_nullAmount_throws
          ▼ ✓ PaymentValidatorTest
              ✓ validate_valid_passes
              ✓ validate_expired_fails
▼ module-orders
  ▼ src/test/java
    ▼ com/example
      ▼ orders
          ▼ ○ OrderServiceTest
              ○ create_persists
```

The `src/test/java` prefix appears in the tree but is not an interactive run target.
`filter_dir` prevents neotest from ever entering `src/main/java/` or `target/`.
The effective interactive levels are: module → package directory → test class → test method.

### VS Code (deferred, post-Neovim)

A VS Code extension calls the same five commands and consumes `lathe/sessionEvent`.
It maps results into VS Code's `TestItem` / `TestRun` API.
Real-time per-test pass/fail comes from `testResult` events — the same stream neotest consumes.
No protocol changes are needed to support VS Code alongside Neovim.

---

## Not in scope

- **Hot code replace.** `java-debug` supports it; Lathe does not actively integrate it.
  Future work.
- **Test sharding / parallel execution control from the editor.** Whatever Surefire is configured to do is what runs.
  The editor cannot force `forkCount=1` for a single test invocation.
- **Coverage UI.** If the user has JaCoCo wired into their POM, runs produce `.exec` files;
  rendering coverage in the editor is out of scope.
- **Launch configuration UI.** Editing `<argLine>`, profile selection, etc. happens in `pom.xml`.
  Lathe does not synthesize launch configurations the user must maintain in JSON.
