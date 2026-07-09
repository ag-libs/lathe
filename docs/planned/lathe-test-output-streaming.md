# Lathe — Live Test Output Streaming (neotest)

Design for streaming captured replay output to the Neovim neotest adapter in real time, instead of
revealing the full transcript only once a run completes (current behavior, already implemented).
Builds on `lathe-run-test-debug.md` (§4.3 results, §10.5 server classes) and scopes the item that
document lists as deferred (§12.10: "NDJSON streaming test events") for the neotest integration
specifically, not the general NDJSON results-sink design.

---

## 1. Problem

`ReplaySession` already captures a replayed test's merged stdout/stderr in full, and `neotest.lua`'s
`results()` already surfaces that transcript once the run finishes.
What is missing is *live* output: watching the transcript scroll while the test is still running,
the way `mvn test` looks in a terminal.

**neotest's own output surfaces cannot do this**, verified directly against the user's real,
current-HEAD installed neotest (`~/.local/share/nvim/lazy/neotest`, commit from 2026-07-03):

- `consumers/output_panel/init.lua`: `client.listeners.results = function(adapter_id, results,
  partial) if partial then return end ...` — partial/streaming result events are explicitly
  discarded. The panel only renders once a run is fully finished.
- `consumers/output.lua`: `neotest.output.open()` is a one-shot read of `Result.output` at the
  moment it's invoked; nothing re-renders it as content changes.
- `RunSpec.stream` (the one genuinely incremental hook neotest exposes) feeds `results_callback`
  with partial `neotest.Result` tables — i.e. per-position pass/fail *status* updates for a batch
  process, not raw output text. It does not reach either output consumer above.

The one live-capable built-in primitive is `attach` (`a` in the summary panel /
`neotest.run.attach()`), which opens a real pty on neotest's own *spawned* process. Lathe's adapter
spawns a dummy `{"true"}` as `RunSpec.command` (the actual replay happens over a separate,
synchronous LSP round-trip inside `build_spec`), so there is nothing live to attach to today.

Also relevant: `build_spec` currently blocks (via a yielding `nio.lsp` call) until the *entire* test
finishes before returning a `RunSpec` at all — meaning even if neotest's own process-spawn
mechanisms were reused, they would not start until after the real work is already done. Any design
that reuses neotest's own live process tracking would need `build_spec` to return before the replay
finishes, which is a further complication this design avoids (§3).

### Prior art checked

- **quicktest.nvim** (github.com/quolpr/quicktest.nvim, ~105 stars — small/niche, not the wide
  precedent neotest itself is) implements live output via `plenary.job`'s `on_stdout` callback
  appending into a scratch buffer incrementally. This validates the *UI* pattern (buffer +
  incremental append via callback) but its data source is a locally spawned job — not applicable
  as-is, since Lathe's replay JVM is spawned server-side, not by the Neovim client.
- **neotest-golang** advertises "streaming results" but its docs don't specify the mechanism, and
  its `build_spec` delegates to sub-modules not inspected here. Given the confirmed
  `output_panel`/`output.lua` behavior above, it almost certainly means `RunSpec.stream`
  status-per-subtest updates (natural fit for Go's own JSON-per-subtest protocol), not raw live
  text — and even if it were, it wouldn't route through neotest's UI live either.

Conclusion: no existing plugin solves "server-driven process → live text in neotest's UI." The UI
pattern (buffer + incremental append) is proven; the data-delivery mechanism has to be custom.

---

## 2. Design

A custom LSP notification, `lathe/testOutput`, pushes each captured output line from the server to
the client as `ReplaySession` reads it — independent of, and in addition to, the existing full
buffered transcript used for the post-completion static output. The client renders incoming lines
into a dedicated scratch buffer.

This was chosen over reusing neotest's own process/stream machinery (e.g. having `build_spec` return
immediately with a command that tails a server-written temp file, giving neotest's own `attach` and
process tracking something live to watch) because:

- neither `output_panel` nor `output.lua` render live regardless of what the spawned process does
  (§1), so that approach would only benefit the little-used `attach` keybinding;
- it would require restructuring `build_spec` to return before the replay finishes, plus a
  temp-file/done-marker lifecycle to signal the wrapper process when to exit;
- the notification approach needs no change to `build_spec`'s current synchronous shape, no new
  process, and no temp-file lifecycle — strictly additive.

### 2.1 Server → client wire shape

```
notification "lathe/testOutput"
{
  "moduleRel": "app",
  "selectorValue": "com.example.app.FooTest#bar_condition_result()",
  "line": "  Caused by: java.lang.AssertionError: ..."
}
```

Correlates by the same `(moduleRel, selectorValue)` key a test is already identified by elsewhere —
no new run-id scheme introduced.
One notification per captured line, matching `ReplaySession.drainOutput()`'s existing per-line read
loop; batching is a possible later refinement, not needed for correctness.

### 2.2 Why the LSP bootstrap must change

LSP4J's `LanguageClient` interface has no `testOutput` method, and the server currently launches via
`LSPLauncher.createServerLauncher(...)`, which hardcodes `LanguageClient` as the remote interface.
Sending a custom notification requires an extended client interface and swapping in the lower-level
`LSPLauncher.Builder<T>` (verified against the installed `org.eclipse.lsp4j` 1.0.0 jar via `javap`):

```java
new LSPLauncher.Builder<LatheLanguageClient>()
    .setLocalService(server)
    .setRemoteInterface(LatheLanguageClient.class)
    .setInput(in)
    .setOutput(out)
    .setExecutorService(rpcExecutor)
    .wrapMessages(consumer -> consumer)
    .create();
```

`LSPLauncher.Builder` (not the bare `org.eclipse.lsp4j.jsonrpc.Launcher.Builder`) matters: it is
LSP4J's own subclass that installs LSP4J's Gson type adapters (`Either<A,B>` and friends). Using the
generic builder would silently break JSON (de)serialization for every other existing LSP feature
(hover, completion, diagnostics, ...), not just the new notification.

---

## 3. Server-side changes

| Class | Change |
|---|---|
| `server.LatheLanguageClient` *(new)* | `extends LanguageClient`; adds `@JsonNotification("lathe/testOutput") void testOutput(TestOutputParams params)`. Public — referenced from `server.run`. |
| `server.run.TestOutputParams` *(new record)* | `{moduleRel, selectorValue, line}`. Compact ctor: `notBlank(moduleRel)`, `notBlank(selectorValue)`, `notNull(line)` (a blank captured line is valid content, so `notBlank` would be wrong here). |
| `server.LatheServer` | `run(...)` swaps to the `LSPLauncher.Builder<LatheLanguageClient>` construction in §2.2. |
| `server.LatheLanguageServer` | `connect(LanguageClient client)` (interface-mandated signature, cannot change) casts once: `textDocumentService.connect((LatheLanguageClient) client)`. Safe because the launcher above is built with `setRemoteInterface(LatheLanguageClient.class)`, so the proxy handed to `connect` always implements it. |
| `server.LatheTextDocumentService` | `connect(...)` parameter and the `session` construction retyped to `LatheLanguageClient`. |
| `server.WorkspaceSession` | `client` field retyped to `LatheLanguageClient`; threads it into `launchReplay(...)`. |
| `server.run.ReplayLauncher` | `launch(...)` gains `client` and `moduleRel` parameters, passed to the new `ReplaySession` constructor. |
| `server.run.ReplaySession` | Constructor gains `(LatheLanguageClient client, String moduleRel, String selectorValue)`. `drainOutput()` calls `client.testOutput(new TestOutputParams(moduleRel, selectorValue, line))` per line read, **in addition to** the existing in-memory buffering (unchanged — still backs `ReplayOutcome.output`, the static post-completion transcript). |

No change to `ReplayOutcome`, `CompletenessGate`, `LaunchTemplateReader`, or the blocked-run paths —
a blocked run (no jar, no template, gate failure) never reaches `ReplaySession` and has nothing to
stream, same as today.

---

## 4. Client-side changes (`neotest.lua`)

- A module-level scratch buffer ("Lathe Test Output"), created lazily (`nofile` buftype), opened via
  a new `<leader>tL` keymap (split, mirroring the existing `<leader>tO` output-panel keymap). Added
  to the user's dotfiles alongside the other test keymaps.
- `build_spec` appends a `=== <position id> ===` header line before issuing the request for
  `test`/`namespace` positions. **Never clears the buffer** — clearing on every run start would let
  two concurrently-running tests (`running.concurrent = true` is neotest's default) stomp each
  other's in-progress output. Content simply accumulates across runs, like a log.
- A `lathe/testOutput` handler is registered once on the **raw** `vim.lsp.Client` object
  (`vim.lsp.get_clients({name="lathe"})[1]`), *not* the object `lathe_client()` currently returns.
  Verified directly against `nio/lsp.lua`: `nio.lsp.get_clients(...)` returns a freshly-built plain
  table exposing only `request`/`notify`/`supports_method`/`server_capabilities` — it has no
  `.handlers` field. Verified directly against Neovim's own `client.lua`
  (`/opt/nvim-linux-x86_64/share/nvim/runtime/lua/vim/lsp/client.lua`) that the *real* client object
  has a mutable `handlers: table<string, lsp.Handler>` field that `_get_handler` consults ahead of
  the global `vim.lsp.handlers` table — the correct, supported extension point.
- The handler appends `result.line` to the output buffer and scrolls any window currently showing it
  to the last line.

`RunSpec.command` is untouched (`{"true"}`) — the live view is entirely our own buffer fed by the
notification, not routed through neotest's process/stream machinery, so none of the tail-file/done-
marker mechanics considered and rejected in §2 are needed.

---

## 5. Testing strategy

### Server (JUnit 5 + AssertJ + Mockito, following existing patterns)

`LanguageClient` mocking already has established precedent in this codebase —
`DiagnosticPublisherTest`, `ProgressReporterTest`, `LatheWorkspaceServiceTest`, and others all
`mock(LanguageClient.class)` and assert with `verify(client, ...)`. `ReplaySessionTest` already
spawns real processes (`ProcessBuilder("sh", "-c", ...)`) rather than mocking the process itself —
only the client is a mock boundary, matching CLAUDE.md's "mock only at the boundary where a real
object would require network I/O."

Extend `ReplaySessionTest` (constructor now takes `client`/`moduleRel`/`selectorValue`):

- `drainOutput_processPrintsLines_notifiesClientPerLine` — real `sh -c 'echo one; echo two'`
  process; `verify(client, timeout(5000)).testOutput(new TestOutputParams("mod", "sel", "one"))`
  and the same for `"two"`, in order (`InOrder`).
- `drainOutput_processPrintsNothing_neverNotifies` — real `true` process;
  `verify(client, never()).testOutput(any())`.
- Existing tests (`onExit_processExitsZero_completesWithZero`, the `cancel(...)` test, etc.) gain the
  new constructor args but assert unchanged behavior — confirms the notification wiring is additive.

`TestOutputParams`'s compact constructor gets a small validation test alongside `ReplayOutcome`'s
existing one (blank `moduleRel`/`selectorValue` rejected, `null` line rejected, blank line accepted).

The `LatheServer`/`LatheLanguageServer` bootstrap change (the builder swap, the cast) is wiring, not
logic — not independently unit-testable in a meaningful way. Confidence there comes from the `javap`
verification in §2.2 (the *right* builder subclass is used) plus the fact that every existing
server-side test that talks to a mocked or real `LanguageClient` continues to pass unmodified, which
would catch a serialization regression indirectly.

### Client (headless `neotest_spec.lua`, following the existing pattern)

The pure, testable slice is small: appending a line to a buffer and scrolling. Extract that into a
small local function (e.g. `append_output_line(bufnr, line)`) callable directly from a spec without
a real LSP connection — mirroring how `_build_position_forest` was extracted specifically to stay
testable without neotest/nio installed. Cover:

- appending to a fresh buffer creates content;
- the `=== <position id> ===` header is written without clearing prior content (the concurrency-
  safety property from §4) — build two headers in sequence, assert both survive.

**Not unit-testable, by design of this codebase's existing conventions**: the real
`vim.lsp.Client.handlers` registration and an actual `lathe/testOutput` round-trip over a live LSP
connection. This session's established pattern for exactly this class of thing (confirmed working
end-to-end via `LATHE_DEBUG=1` and `~/.local/state/nvim/lsp.log`, e.g. the auto-glyph fix and the
real `[replay] ... exit=0` log lines from the user's interactive session) is real, interactive
verification against the user's actual Neovim session and actual Helidon workspace — not a headless
script. That remains the verification step for this feature: run a real test, confirm
`=== <position> ===` plus streamed lines appear in the `<leader>tL` buffer while the replay JVM is
still running (not just after), and confirm log evidence of `testOutput` notifications being sent
server-side (a `LOG.fine` per line, gated behind `LATHE_DEBUG=1` per this repo's logging
conventions, consistent with the existing `[replay] argv=%s` line in `ReplayLauncher`).

---

## 6. Known simplifications / non-goals

- **One shared buffer, not one per position.** Concurrent runs (`running.concurrent = true` is
  neotest's default) interleave into the same buffer, distinguished only by the `=== <position> ===`
  headers. Per-position buffer management is a materially bigger jump in UI complexity for a case
  that's rare in normal single-test-at-a-time usage; revisit only if it proves confusing in practice.
- **No batching.** One `lathe/testOutput` notification per line. Fine for typical test output volume;
  worth revisiting only if a pathologically chatty test (thousands of lines) is reported as sluggish.
- **Cancellation is out of scope for this document**, as separately agreed — stopping an in-flight
  replay is a distinct follow-up (`ReplaySession.cancel()` already exists but nothing calls it).
- **No change to the static post-completion output path.** `results()` and the existing
  `write_output_file`/`ReplayOutcome.output` machinery are unchanged; this is purely additive live
  output alongside them.

---

## 7. Reviewable deliverables

### 7.1 Server: notification plumbing

**Scope:** `LatheLanguageClient`, `TestOutputParams`, the `LSPLauncher.Builder` swap in
`LatheServer`, the retyping through `LatheLanguageServer` → `LatheTextDocumentService` →
`WorkspaceSession` → `ReplayLauncher` → `ReplaySession`, and `drainOutput()`'s per-line notify call.

**Verification:** extended `ReplaySessionTest` (§5); full existing server test suite passes
unmodified (serialization-regression canary, §5).

**Commit prefix:** `feat: stream replay output over a custom LSP notification`

### 7.2 Client: live output buffer

**Scope:** the `neotest.lua` buffer, handler registration on the raw client, `build_spec`'s header
append, and the `<leader>tL` dotfiles keymap.

**Verification:** extracted pure-function spec coverage (§5); real interactive confirmation against
the user's Helidon workspace (§5), the same verification standard used for the auto-glyph and output-
capture work earlier in this effort.

**Commit prefix:** `feat(neovim): show live streamed test output in neotest adapter`
