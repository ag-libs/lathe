# Lathe — Unify Test Debug with the Run Path

## Status

Planned — M2. Design approved; the three decisions are **resolved** (see Resolved decisions). Sliced for
implementation (see Work slices); no code yet.
Resolves gaps [NV-3](../gaps/gaps.md) and [NV-4](../gaps/gaps.md), and creates the shared surface that
makes [NV-2](../gaps/gaps.md) an implement-once change.

Builds directly on the already-implemented streaming and debug machinery:

- [Run, Test, and Debug](../done/lathe-run-test-debug.md) — capture/replay, the launch core, the
  server-side command surface.
- [Debug Support](../done/lathe-debug-support.md) — the in-process DAP host and the nvim-dap adapter.
- [neotest Streaming](../done/lathe-neotest-streaming.md) and
  [Structured Test Results](../done/lathe-structured-test-results.md) — `lathe/testEvent` under a run
  token and the per-test result shape.
- [Test Output Streaming](../done/lathe-test-output-streaming.md) — `lathe/testOutput` and the shared
  docked console.
- [neotest Experience](../done/lathe-neotest-experience.md) — the run path's build_spec/results
  reconciliation this design routes debug through.

## Goal

Make **debugging a test behave exactly like running it**: gutters and the summary tree update live and
reconcile at the end, the shared docked output console opens and stays, and a run-level pass/fail
outcome is surfaced — by routing test-debug **through neotest's `dap` strategy** instead of the parallel
`dap.run()` path that bypasses neotest today.

Today, `:LatheDebug` resolves the runnable under the cursor and calls `dap.run(...)` directly
(`lua/lathe/dap.lua`, `M.debug`).
That path never goes through the neotest adapter's `build_spec`/`results()`, so neotest is never told a
run started or finished: gutters and the summary stay stale (**NV-3**), and the docked console is never
opened or kept, so it flashes and no pass/fail is shown (**NV-4**).

## Key finding — most of the machinery already runs during debug

A server-side trace establishes that the debug launch is **not** a separate island.
`lathe.debug.test` goes through the same `Launcher.launch()` / `LaunchSession` core as `lathe.run.test`
(`WorkspaceSession.debugTest` vs `WorkspaceSession.runTestFuture`).
During a debug session the server **already**:

- sets `lathe.results.sink` so the in-JVM `ResultsListener` writes NDJSON,
- tails it and streams each result as **`lathe/testEvent` under the run token** (`resultConsumer`),
- drains stdout/stderr and streams **`lathe/testOutput` under that token** (`streamConsumer`, wrapped by
  `jdwpReadyConsumer` only to detect the JDWP banner).

So the live per-test signal that paints gutters, and the transcript that fills the console, are *already
on the wire during debug*.
Two things stop them from reaching the UI:

1. **Server:** the debug command returns `DebugStartResult(dapPort, jdwpPort)` immediately, and at
   session end `WorkspaceSession.attachDebugHost` runs
   `session.onExit().whenComplete((outcome, error) -> worker.execute(() -> endDebugSession(token)))` —
   the final `LaunchOutcome` (with `testResults` + `exitCode`) is **computed and then discarded**.
   The run path returns that outcome as the command response; the debug path drops it.
2. **Client:** `dap.lua` calls `dap.run()` directly, so no neotest event-queue is registered for the
   token and the shared console is never opened or kept — nothing on the neotest side is listening,
   even though the notifications are streaming.

The work is therefore small: surface the outcome the server already builds, and route the client through
the run path it already has.

## Design overview — Shape 1 (chosen): a neotest `dap` strategy

neotest's `run.run({ strategy = "dap" })` funnels through the adapter's `build_spec` and, instead of
spawning `spec.command` as a process, launches `spec.strategy` as an nvim-dap config; it still calls
`results()` when the DAP session terminates.
We reuse that: the adapter builds a `strategy` config that drives the existing Lathe debug adapter, and
`results()` reconciles exactly as it does for a run.

This *deletes* the parallel path rather than patching around it, and reuses the run path's console,
results reconciliation, and stack-frame decoration for free.

### Entry points — the summary tree's `d` comes for free

neotest's summary consumer already ships a built-in **debug** mapping (`d`, and `D` for debug-marked)
that calls `neotest.run.run({ strategy = "dap" })` on the position under the cursor.
That is the same call Shape 1 hinges on, so **the summary `d`/`D` keys light up with no extra wiring**
once `build_spec` handles the dap strategy (Change 2) — they are a more idiomatic entry point than a
custom command.

Today `d` does *not* attach a debugger: because `build_spec` ignores `args.strategy`, it returns a
normal run spec (which fires `lathe.run.test`, a non-debug replay) and neotest's dap strategy has no
`spec.strategy` to launch — so `d` just runs the test with no debugger.
After Change 2 it drives a real Lathe debug session.

Entry points after this work, all funnelling through the same `build_spec`/`results()` path:

- neotest summary `d` / `D` (built-in) — debug the position / marked positions.
- `neotest.run.run({ strategy = "dap" })` from any user keymap.
- `:LatheDebug` (Change 4) — cursor-based, and the sole path for a `main`.

### Rejected alternative — Shape 2 (client-side bridge)

Keep `dap.run()`, but register a consumer on the debug token, accumulate the streamed `testEvent`s, open
and keep the console, and on the DAP `terminated`/`exited` event feed neotest's result state and
synthesize the outcome from the exit code.
Rejected: it avoids the (tiny) server change but **duplicates the aggregation `results()` already
performs** and loses the authoritative sink re-read (`LaunchSession.readTestResults()` at process exit),
reintroducing the two-source correctness risk the run path already solved.
Shape 2 wins only if a zero-server-change constraint is imposed; absent that, Shape 1 is strictly
cleaner.

## Changes

### 1. Server — publish the debug outcome at session end (the public-API surface)

In `WorkspaceSession.attachDebugHost`, the outcome that arrives at `session.onExit()` is currently
discarded.
Publish it under the token before cleanup:

- **New notification `lathe/testFinished`** with params `TestFinishedParams(String token, LaunchOutcome
  outcome)`.
  - New record `TestFinishedParams`, mirroring `TestOutputParams` / `TestEventParams`.
  - New `@JsonNotification` method `testFinished(TestFinishedParams)` on `LatheLanguageClient`.
  - `LaunchOutcome` is reused verbatim — it already serializes as the `lathe.run.test` command response,
    so no new payload shape is introduced.
- Fires for **both** `debugTest` and `debugMain` (both pass through `attachDebugHost`).
  A main debug carries no `testResults`; that is harmless, because no client result-future is registered
  for a main token, so its handler is a no-op.
- The error / `null`-outcome path (the launch died before exit) publishes a blocked/failed outcome, so
  `results()` always completes and never leaves a position stuck "running".

This is the only change to public API and the reason implementation waits on explicit approval.

### 2. Client — thread the dap strategy through `run_spec`

Every `pos.type` path in `build_spec` (test / namespace, `dir`, `file`) already funnels into a single
`run_spec(position_id, module_rel, selections, client, label)` call.
So the debug case is **one strategy-aware change inside `run_spec`**, not a parallel branch — this
covers method, class, file (multi-class), and package uniformly and keeps the selection-resolution DRY.
`build_spec` passes `args.strategy` down; `run_spec` branches on it:

- **Run (`strategy ~= "dap"`, today's behavior):** unchanged — mint token, register
  `event_queues[token]`, fire `lathe.run.test` async, `command = { "true" }`, resolve `result_future`
  from the command response.
- **Debug (`strategy == "dap"`):**
  - Mint the run token, register `event_queues[token]` and a `result_future` **keyed by token** (a new
    small `debug_futures[token]` map) — so streamed `testEvent`s paint gutters live (already on the wire
    during debug).
  - Return a spec whose **`strategy`** field is the Lathe DAP attach config carrying that same `token`,
    with the same `stream` iterator and `context = { position_id, token, result_future }`.
  - **Do not fire `lathe.run.test`** here: the debug launch is driven by neotest's dap strategy via
    `spec.strategy` → the `lathe` adapter → `lathe.debug.test`. Firing the normal replay too would spawn
    a second, un-debugged JVM alongside the debug session.
  - Keep `command = { "true" }` (the dap strategy launches `spec.strategy`, not the command).

`output.reset()` already runs once at the top of `build_spec` for every run action, so the debug path
inherits the shared-console reset. The debug path **additionally calls `output.ensure_open()`**
(resolved decision 3: debug-only) so the console is visible for a debug session; the neotest *run* path
is left as-is (it does not auto-open — see the correction note). Because debug already streams
`lathe/testOutput`, the shared token-agnostic handler in `lathe.output` fills the docked buffer and it
no longer flashes — **that is the core of NV-4.**

**Edge — nothing to debug:** the `file`-with-no-test-classes path currently returns a no-op skip spec.
Under `strategy == "dap"` there is nothing to launch, so notify "nothing here to debug" and return `nil`
(or a skip spec `results()` clears) rather than handing neotest's dap strategy an empty `spec.strategy`.

**Correction from the final code review.** The gap notes and an earlier draft of this design said the
neotest *run* path calls `output.ensure_open()`. It does not: `neotest.lua` `build_spec` calls only
`output.reset()`; `output.ensure_open()` is called solely by the `main` run path (`run.lua`). A test
run's console appears "kept open" only because the docked buffer/window persists once the user has
opened it (`<leader>to`), and reset() reuses it. This is why debug "flashes": it does not use that
persistent buffer at all today.

### 3. Client — `results()` completion for the debug case

Register a handler:

```lua
vim.lsp.handlers["lathe/testFinished"] = function(_err, result)
  -- resolve debug_futures[result.token] with { err = nil, outcome = result.outcome }
end
```

`results()` then reconciles exactly as it does today for a run — same per-test reconciliation, same
run-position aggregate, same output file, same `stackdecorate.decorate_live_output()`.

**The one real difference from the run path — the wait must be bounded.** Today `results()` does an
*unbounded* `ctx.result_future.wait()`, which is safe only because the run path resolves that future
in-process from the `lathe.run.test` response. In the debug case the future is resolved by an
out-of-band `lathe/testFinished` notification; a lost notification (client disconnect, a server error
before publish) would hang the neotest task and leave the position stuck "running" forever.
So the debug case must use a **bounded** wait (the `nio().first({ future.wait, function() sleep(T) end })`
pattern already used by `await_ready`) and, on timeout, **fall back to synthesizing the outcome from
neotest's dap-strategy `_result`** (its session exit code) so `results()` always completes.
The normal cancel/stop path is *not* a concern: `cancelRun` → `session.cancel()` → `session.onExit()`
still fires, so `lathe/testFinished` is still published and the future resolves promptly.

Ordering between "DAP session terminated → neotest calls `results()`" and "server published the
outcome" is harmless (both are triggered by the debuggee exiting): if the notification arrives first the
future is already set; otherwise the bounded wait absorbs the gap.

### 4. Client — `:LatheDebug` rewiring (`dap.lua`)

- **Test target under the cursor** → `require("neotest").run.run({ strategy = "dap" })` (nearest
  position), instead of `dap.run(M._test_config_for(test))`.
- **Fallback:** if neotest is not installed / set up, keep today's direct `dap.run` (degraded: no gutter
  update), preserving the optional-dependency posture that `lathe.setup` already guards.
- **Main target** → unchanged direct `dap.run(M._main_config_for(main))`; a `main` has no neotest path,
  and NV-3/NV-4 are test-only.
- The nvim-dap **adapter (`start_adapter`) is reused unchanged** — it already reads `config.lathe_token`
  and drives `lathe.debug.test`.

### Shared refactor

Extract the attach-config builder so `build_spec` and `dap._test_config_for` do not duplicate it:
`dap._attach_config(module_rel, selections, label, token)` returns the
`{ type = "lathe", request = "attach", lathe_* }` table.
`build_spec` builds it from the neotest `pos` and injects the token minted there, rather than
self-minting via `output.next_token()`.

## Behavior and edges

- **Stop:** neotest-driven debug is cancelled via the existing `neotest` adapter `M.stop()`
  (token → `lathe.run.cancel`, which the debug launch already registers in `activeRuns`); the DAP
  session terminates with the JVM.
- **Breakpoint pauses:** `testEvent`s stream as each test finishes, so sitting at a breakpoint only
  delays them — gutters fill progressively and the aggregate lands at session end. No special handling.
- **Stack decoration:** the existing `_decorate_on_session_end` dap listener stays for **main** debug;
  **test** debug now decorates via `results()`. The overlap is harmless — decoration is idempotent.

## Gaps addressed and synergy

- **NV-3** (debug updates gutters/summary) — closed directly.
- **NV-4** (debug keeps the console and shows pass/fail) — closed directly, for free, by going through
  `build_spec`/`results()`.
- **NV-2** (a run completion toast) — *enabled*. NV-2 specifies its toast should fire from `results()`.
  Once debug is routed through `results()`, a single NV-2 implementation there fires for **both run and
  debug**; the new `lathe/testFinished` outcome is also the natural carrier for the elapsed-ms NV-2 wants
  the server to return. Best delivered with, or immediately after, this work.
- **NV-5** (re-run only failed) — independent; not helped mechanically. The only bonus is that, once it
  exists, re-running failures is also debuggable through the unified path.
- **TE-2/3/4, DB-1…6** — untouched; those are replay working-directory, system properties, and debugger
  internals, at a different layer.

## Relationship to per-test output

This design makes debug's **output panel** work like a run's (NV-4): the whole transcript streams into
the shared docked console and pass/fail is surfaced.
It does **not** attribute or label output per test — that remains the parked
[Per-Test Output Attribution](../potential/lathe-per-test-output-attribution.md) spike (and the lighter
marker-line variant discussed alongside it).

The payoff is structural: run and debug now share the one `lathe/testOutput` handler and the one docked
console, so any future output feature — a per-test marker line, or full structured attribution — is
implemented once and appears in **both** run and debug automatically.
This work is a prerequisite surface for that, not a substitute.

## Files in scope

- `lathe-server`:
  - `WorkspaceSession.attachDebugHost` — publish the outcome at session end.
  - new `TestFinishedParams`; new `testFinished(...)` on `LatheLanguageClient`.
- Client Lua:
  - `lua/lathe/neotest.lua` — `build_spec` debug branch, `lathe/testFinished` handler, `debug_futures`.
  - `lua/lathe/dap.lua` — `M.debug` rewire, `_attach_config` extraction.

## Testing

- **Server** (`WorkspaceSession`'s debug test home): a finished debug session publishes
  `lathe/testFinished` with the outcome — a passing test yields `exitCode = 0` and a passed
  `testResults` entry; a failing test yields a failed entry. Positive and failed cases.
- **Client** (`dap_spec.lua`): a **test** target routes through
  `neotest.run.run({ strategy = "dap" })` (not `dap.run`); a **main** target still uses `dap.run`; with
  neotest absent, a test target falls back to direct `dap.run`.
- **Client** (`neotest_spec.lua`): `build_spec` with `strategy = "dap"` returns a spec whose `strategy`
  carries a registered token and resets/opens the console; a `lathe/testFinished` notification resolves
  the future and `results()` reconciles the run position and its subtree.
- `mvn spotless:apply` after any Java change.

Regression-target one-liners for the gap entries: debugging a passing test marks it passed in the
summary and keeps the console open with a pass outcome; debugging a failing test marks it failed with a
WARN-level outcome.

## Non-goals

- Per-test output attribution or a test-name marker (see the section above).
- Changing main-class debug (`debugMain` / `M._main_config_for`), which has no neotest surface.
- NV-5 (re-run failed) and the TE/DB gaps.
- Any change to the DAP wire handling or the nvim-dap adapter contract — the adapter is reused as-is.

## Resolved decisions

1. **Notification name — `lathe/testFinished`.** Kept over `lathe/runFinished`; it mirrors the existing
   `lathe/testEvent` / `lathe/testOutput` naming, and a main debug firing it is a harmless client no-op.
2. **neotest-absent `:LatheDebug` on a test — silent degraded fallback to direct `dap.run`.** Preserves
   the optional-dependency posture `lathe.setup` already guards; no nag toast.
3. **Console on debug — `ensure_open()` on the debug path only.** Debug always shows its output (NV-4);
   the run path is left unchanged, so a plain test run does not start popping the split.

## Verify during implementation (not blockers)

- **neotest honors `spec.stream` under the `dap` strategy.** Live gutter updates during debug depend on
  neotest invoking the spec's `stream` iterator when `strategy == "dap"`. If a given neotest version
  wires streaming only for the integrated strategy, debug degrades gracefully — gutters reconcile at
  `results()` time instead of live — but confirm the live path against the pinned neotest version.
- **The custom `lathe_token` field survives neotest's dap-strategy hand-off to `dap.run`.** The token
  minted in `build_spec` must reach `start_adapter` via `spec.strategy.lathe_token`; confirm neotest
  passes the strategy config through to nvim-dap without stripping unknown keys.

## Work slices

Four slices, each landing on a working tree with its own tests. Order is dependency-driven: 2 needs 1
(to complete `results()`); 3 needs 2 (it delegates to the working path); 4 is closeout.

### Slice 1 — Server: publish the debug outcome (`lathe/testFinished`)

- Add record `TestFinishedParams(String token, LaunchOutcome outcome)` (mirror `TestEventParams` /
  `TestOutputParams`); add `@JsonNotification testFinished(TestFinishedParams)` to `LatheLanguageClient`.
- In `WorkspaceSession.attachDebugHost`, publish the outcome in the existing `session.onExit()`
  `whenComplete` (before `endDebugSession`), for both `debugTest` and `debugMain`; on the error /
  `null`-outcome path publish a blocked/failed outcome.
- `mvn spotless:apply`.
- **Check:** a server test drives a debug session to completion and asserts one `lathe/testFinished` is
  sent with the outcome — a passing test → `exitCode = 0` + a passed `testResults` entry; a failing test
  → a failed entry. Positive + failed case.
- **Shippable:** yes — adds a notification no client consumes yet (inert).

### Slice 2 — Client: debug path end-to-end (the main deliverable)

- Thread `args.strategy` from `build_spec` into `run_spec`; branch run vs `strategy == "dap"` inside
  `run_spec` (covers method / class / file / package uniformly — no duplicated selection logic).
- Debug branch: register `event_queues[token]` + `debug_futures[token]`; return a spec with
  `strategy = dap._attach_config(module_rel, selections, label, token)`, the shared `stream` iterator,
  and `context`; do **not** fire `lathe.run.test`; `command = { "true" }`; `output.ensure_open()`.
- Extract `dap._attach_config(...)` from `dap._test_config_for` (shared refactor).
- Register `vim.lsp.handlers["lathe/testFinished"]` to resolve `debug_futures[token]`.
- `results()`: when the run is a debug run, use a **bounded** `result_future` wait with a fallback that
  synthesizes the outcome from the dap-strategy `_result` exit code.
- `file`-with-no-tests under `strategy == "dap"`: notify "nothing here to debug", return `nil`.
- **Check:** `neotest_spec.lua` — `build_spec` with `strategy == "dap"` returns a spec whose `strategy`
  carries a registered token and does **not** fire `lathe.run.test`, and opens the console; a
  `lathe/testFinished` notification resolves the future and `results()` reconciles the run position and
  its subtree; the timeout fallback synthesizes from the exit code.
- **Shippable:** yes — after this, the neotest summary `d` / `D` and any
  `neotest.run.run({ strategy = "dap" })` keymap fully debug with live gutters, kept console, and a
  pass/fail outcome. **This closes NV-3 and NV-4.**

### Slice 3 — Client: `:LatheDebug` rewire

- Test target under the cursor → `require("neotest").run.run({ strategy = "dap" })`; neotest absent →
  silent fallback to today's direct `dap.run`; `main` target → unchanged direct `dap.run`.
- **Check:** `dap_spec.lua` — a **test** target routes through `neotest.run.run({ strategy = "dap" })`
  (not `dap.run`); a **main** target still uses `dap.run`; neotest-absent falls back to `dap.run`.
- **Shippable:** yes — `:LatheDebug` now matches the summary `d` behavior for tests.

### Slice 4 — Docs and closeout

- Update `docs/guide/editors/neovim.md` Debug section: the summary `d` / `D` now debugs via Lathe with
  live gutters + kept console; note the debug console auto-opens; keep the `<leader>to` output pointer.
- Mark **NV-3** and **NV-4** resolved in the gap registry (move to the archive per the gap lifecycle),
  each with the regression target below; add a note on the **NV-2** follow-up.
- Update `docs/status.md` if it enumerates the debug surface.
- **Regression targets:** debugging a passing test marks it passed in the summary and keeps the console
  open with a pass outcome; debugging a failing test marks it failed with a WARN-level outcome.
