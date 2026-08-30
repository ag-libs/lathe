# Lathe — Cancelling a Running Test Replay

Adds the ability to stop a running test replay from the editor — the missing verb in the run flow
(experience-spec **R5**). The motivating case is a **hung** test: an infinite loop, a deadlock, or a
blocked I/O wait that never returns. Fast replay does not help here — the JVM is stuck — so the user
must be able to kill it and get the editor's "running" state cleared promptly.

The design is shaped by one hard constraint from the current architecture: **the server keeps no
handle to a running replay today**, and `WorkspaceSession` is otherwise strictly single-threaded
(worker-confined). Cancel is therefore the first piece of server state written off the worker, and
the design's whole job is to add it without breaking that discipline.

---

## 1. Problem

- `neotest.run.stop()` cannot reach our run. neotest stops a run by killing the *strategy instance's
  process*; our spec uses `command = {"true"}` (a no-op that exits instantly and is cleared from
  neotest's process table before the user could stop it), and the real replay runs asynchronously,
  server-side, tracked only by a run **token**. neotest offers no hook to reach it, and its strategy
  is chosen from *run args*, not the spec, so a custom strategy can't be defaulted cleanly.
- Server-side, `ReplaySession.cancel()` already exists (`process.destroy()`), but it is exposed to no
  LSP command, and **nothing holds a reference to the live `ReplaySession`** — `launchReplay` creates
  it as a local variable on a dedicated run thread and never stores it (see §2).

## 2. Current state model (what we build against)

**Tier 1 — worker-confined session state.** Every field on `WorkspaceSession` (`docs`, `manifest`,
`workspace`, `typeIndex`, `analysisLru`, …) is mutated *only* on the single `ServerEventLoop` worker
thread. There are no locks and no concurrent structures anywhere; `LatheTextDocumentService` funnels
every request through `worker.submit(...)` / `worker.execute(...)`.

**Tier 2 — per-run state, held nowhere.** `runTestFuture` runs on the worker but only *kicks off* a
run: it creates a `CompletableFuture<ReplayOutcome>`, spawns a daemon **run thread**
(`lathe-replay-<module>`), and returns the future immediately — the worker never blocks on a run.
`launchReplay` is static; the `ReplaySession` it creates is a **local variable**, never assigned to
any field, garbage once `onExit().whenComplete` fires. So the server currently has **zero reference
to an in-flight run**.

**`ReplaySession`** owns its own per-run state (`process`, `resultsSink`, a `synchronizedList` of
output, three completion latches) and three daemon threads (2 drains + 1 tailer). Its internals are
individually thread-safe; nothing is shared with the session. The only existing cross-thread touch is
*stateless* — run threads call `client.testOutput`/`client.testEvent` with immutable payloads that
lsp4j serializes.

## 3. Design

### 3.1 Server state — a worker-confined map

Add exactly one field to `WorkspaceSession`, treated like every other field:

```java
private final Map<String, ReplaySession> activeRuns = new HashMap<>();  // token -> session
```

A **plain `HashMap`, not `ConcurrentHashMap`** — because it is only ever *touched on the worker
thread*. The run/exit threads never touch it directly; they **marshal** the mutation onto the worker,
which is how the rest of the server already crosses the thread boundary:

- **Register:** the run thread, after `ReplayLauncher.launch(...)` returns the session, calls
  `worker.execute(() -> activeRuns.put(token, session))`.
- **Deregister:** the existing `onExit().whenComplete(...)` calls
  `worker.execute(() -> activeRuns.remove(token))`.
- **Cancel:** `lathe.run.cancel` → `worker.submit(() -> session.cancelRun(token))` →
  `activeRuns.get(token)` → `session.cancel()`.

The only inherently-concurrent thing in the whole feature is the `Process` handle, which is already
thread-safe. No concurrency primitive is added to the server's model; `activeRuns` reads exactly like
`docs`.

### 3.2 Server command — `lathe.run.cancel`

A new `executeCommand` case in `LatheWorkspaceService`, argument `{ token }`, routed through the
worker to `activeRuns.get(token)?.cancel()`. A no-op for an unknown or already-finished token.

### 3.3 `ReplaySession.cancel()` — hang-proof and non-blocking

Today it is only `process.destroy()` (SIGTERM), which a JVM wedged in a tight loop or deadlock can
ignore. Escalate to a guaranteed kill, without parking any thread for the grace period:

```java
public void cancel() {
  process.destroy();
  process.onExit()
      .orTimeout(GRACE_MS, TimeUnit.MILLISECONDS)
      .whenComplete((p, ex) -> { if (process.isAlive()) process.destroyForcibly(); });
}
```

`orTimeout`'s callback runs on a JDK background thread, never the worker, so `cancel()` returns
immediately. `destroyForcibly()` (SIGKILL) is OS-guaranteed, so `onExit()` always resolves —
`process.onExit()` completes, the drains finish, the tailer loop (`while process.isAlive()`) ends,
and the run's outcome future completes.

### 3.4 Client — `M.stop()`

The client already tracks active runs: `event_queues` maps each live token to its stream queue. So
`M.stop()` needs no new bookkeeping — it sends `lathe.run.cancel` for each active token. Because
`results()` is already blocked on `result_future.wait()`, cancelling makes `run.test` return its
(nonzero-exit) outcome, `result_future` resolves, `results()` completes, and neotest clears the
"running" glyph. The run position takes the aggregate (failed) status; unfinished methods inherit it.

Bind it in the README: `<leader>tS` → `require("lathe.neotest").stop()`.

## 4. Thread model and why it is deadlock-free

| Thread | Role in cancel |
|--------|----------------|
| Worker (single) | Handles `run.test` (kick off + return future — never blocks) and `run.cancel` (look up + `destroy` — never blocks). Free throughout a run. |
| Run thread (per run) | Marshals `put` after launch and `remove` on exit; awaits `onExit`. |
| ReplaySession threads (per run) | 2 drains + 1 tailer; stream output/results off-worker. |
| JDK background thread | Runs the `orTimeout` escalation callback (SIGTERM→SIGKILL). |

The property that makes "stop a hung test" possible: **the worker is never blocked — not by a run,
not by a cancel.** `run.test` returns a future immediately and `cancel()` is non-blocking, so the
cancel command is always dispatchable even while a run is hung. If `run.test` had blocked the single
worker, no request (cancel included) could ever run, and the hang would be unkillable.

## 5. Decisions

1. **Worker-confined `HashMap`, not `ConcurrentHashMap`.** Preserves Tier-1 discipline; adds no
   concurrency primitive. The map is mutated only on the worker (via marshaling).
2. **Explicit `lathe.run.cancel` command, not LSP `$/cancelRequest`.** The client already holds the
   active tokens, so firing a command is the simplest, most robust trigger, and it does not depend on
   `nio.lsp` exposing per-request cancellation. (`$/cancelRequest` + a per-run `AtomicReference` was
   considered — more elegant server-side, but pushes complexity to the client.)
3. **Lathe's stop verb, not `neotest.run.stop()`.** neotest gives no hook to reach an async,
   server-owned run; `<leader>tS` → `lathe.neotest.stop()` still meets R5's criteria.
4. **SIGTERM then SIGKILL escalation**, not immediate SIGKILL — lets a responsive test shut down
   cleanly, guarantees a wedged one still dies.

## 6. Rejected alternatives

- **`ConcurrentHashMap` on `WorkspaceSession`** — smuggles cross-thread mutable state into a
  single-threaded object; breaks the discipline the map is supposed to respect.
- **Extracted `ReplayRegistry`** — a dedicated concurrent component is reasonable elsewhere, but here
  it *introduces* concurrency the codebase deliberately avoids; a worker-confined map is more
  idiomatic and simpler.
- **Per-run `RunHandle` (AtomicReference + cancel-pending flag)** — closes the registration
  micro-window (§7) that does not matter for the hang case; over-engineering.

## 7. Trade-offs

- **Registration micro-window.** Because registration is marshaled, a cancel processed before the
  run thread's `put` lands (the first tens of ms of a run, during `launch`) misses the lookup.
  Irrelevant for the hang case — registration landed long before the user cancels — and for a
  just-started fast test a miss simply lets it finish normally.
- **Cancel latency is bounded by the worker's current task** (a compile, sub-second to seconds),
  never indefinite, since runs are off-worker and compiles are javac calls with no unbounded wait.

## 8. Testing

- **Server unit** — a deliberately hung/long replay fixture: call `cancel()`, assert the process is
  no longer alive and the outcome future resolves. Proves the SIGTERM→SIGKILL escalation and the
  clean `onExit` unwind.
- **e2e** — start a long-running test, invoke `M.stop()`, assert the run resolves and the running
  state clears promptly.

## 9. Non-goals / later

- Full `neotest.run.stop()` integration via a custom strategy threaded through run args (clunky
  ergonomics; deferred).
- Cancelling only part of a fan-out (per-method cancel within a file run); `M.stop()` cancels all
  active tokens.

## 10. Blast radius

`ReplaySession.cancel` (escalation), `WorkspaceSession` (the `activeRuns` field + register/deregister
marshaling + `cancelRun`), `LatheWorkspaceService` (new command + arg parse), `LatheTextDocumentService`
(cancel delegate), `neotest.lua` (`M.stop`), README (keybinding). Crosses the client↔server API (one
new command).
