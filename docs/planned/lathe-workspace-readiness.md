# Lathe — Workspace Readiness Signal and Neotest Discovery Logging

Fixes the long-standing startup race where features fire before the server is ready — reproduced
concretely as neotest showing no tests when a test file is opened as the first buffer
(experience-spec **D1**). It is written as the *proud, long-standing* fix, not an adapter-local
workaround: a **server-wide readiness signal** plus **client gating**, the same mechanism mature
language servers use. It also closes a logging blind spot that made the race invisible.

Related: completes/uses [lathe-lsp-progress.md](lathe-lsp-progress.md) (the `$/progress` mechanism)
and resolves D1 in [lathe-neotest-experience.md](lathe-neotest-experience.md).

---

## 1. Problem (reproduced)

Driving the real project headlessly:

```
IMMEDIATE (before attach) discover => nil
ATTACHED                            => true
AFTER-ATTACH discover               => tree with 5 positions
```

Discovery works perfectly *once the client is attached and the workspace is loaded*. But:

- The neotest config eagerly calls `neotest.run.get_tree_from_args(...)` on plugin load, and neotest
  does its own on-open discovery — both fire seconds **before** the Lathe JVM has attached.
- `discover_positions` gives up immediately when the client isn't present
  (`if not lathe_client() then return nil end`), with no wait and no retry. neotest caches "no tests"
  and never re-discovers → empty summary/gutter.

The only readiness cue the server emits is a `showMessage("Lathe: workspace ready.")` **toast** —
human-facing, not a structured signal a client can gate on. And the client side is silent
(`neotest.lua` logs nothing), so the miss leaves no trace.

## 2. How mature servers solve this (precedent)

The same class of bug — "a client request fired before the server finished loading" — is solved the
same way across the ecosystem: **the server emits an explicit readiness signal; clients gate work on
it.** No polling, no racing.

- **jdtls** sends a custom `language/status` notification; clients wait for `result.type ==
  "ServiceReady"` before sending project-dependent requests (position ops, discovery). It also
  carries `workDone/totalWork` for a progress bar before the terminal `ServiceReady`.
- **rust-analyzer** sends `experimental/serverStatus` (`{health, quiescent, message}`) — richer than
  plain `$/progress` because it signals *readiness* (`quiescent`) and health, not just a percentage;
  the client advertises a `serverStatusNotification` capability and gates on it. `$/progress` remains
  the visible init bar. (Lesson also learned there: scope `$/progress` to load/reload — do **not**
  emit it per keystroke.)

Lathe already has the two ingredients: a `ProgressReporter` (used today for reference-search
work-done progress) and a custom-notification channel (`LatheLanguageClient`, added for
`lathe/testOutput`/`lathe/testEvent`). This design uses both.

## 3. Design

### 3.1 Server — a structured readiness signal

- **Discrete gate-able event.** Add `lathe/status` to `LatheLanguageClient`:
  `{ state: "loading" | "ready" }` (room to add `health`/`reason` later, rust-analyzer style). Emit
  `loading` when a workspace load/reload begins and `ready` at the point that today sends the
  `workspace ready` toast (`WorkspaceSession`). This is the jdtls `ServiceReady` analog and the
  signal clients await. It is **server-wide**, not neotest-specific.
- **Visible progress (already planned).** Emit `$/progress` work-done (`begin` → `end`) for
  workspace init/reload via the existing `ProgressReporter`, so it renders in `vim.lsp.status()`.
  This is the scope of [lathe-lsp-progress.md](lathe-lsp-progress.md); `lathe/status: ready` and the
  progress `end` mark the same moment. Scoped to load/reload only.
- The existing `showMessage` toast can stay (or become a thin client-side reaction to
  `state="ready"`) — a decision, not a blocker.

### 3.2 Client — gate discovery on readiness, event-driven

- Register a `lathe/status` handler that resolves a per-client **`nio` "ready" event** on
  `state="ready"` (and re-arms it on `state="loading"` for reload).
- `discover_positions`: if the workspace isn't ready yet, **`await` the ready event** (bounded by a
  timeout) before calling `runnables.list`. This is suspending on an event — the normal async
  pattern — not an `nio.sleep` poll loop. Per the repro, once ready, `runnables.list` compiles the
  file on demand and returns positions, so readiness is a sufficient gate.
- **Self-heal the early miss (push).** On `state="ready"`, re-trigger neotest discovery for the open
  Java buffers (the eager `get_tree_from_args` path), so a summary that cached empty before attach
  fills in the moment the server is ready — the user does not have to re-open the file.

### 3.3 Logging (existing frameworks only — no custom log)

An audit of the neotest paths found the run/replay side already well covered (`[replay]` warnings +
INFO exit) and the file lifecycle logged (`[open]/[change]/[close]/[save]` — which shows whether
`didOpen` reached the server, key to this race), but two genuine blind spots:

- **The discovery command path is silent.** `LatheWorkspaceService` has no LOG calls, and
  `runnablesFuture` logs neither the no-open-doc miss nor the target count.
- **The client adapter is completely silent.** `neotest.lua` has zero log lines, so nothing on the
  client side of the flow can be seen.

**Principle: no duplication — each side logs only what *it* alone can see.** The server owns the
discovery outcome and the run; the client logs only the client-side facts that never reach the
server. Close exactly the gaps, using the current frameworks — server JUL (`LATHE_DEBUG=1` →
`FINE`), client `vim.lsp.log` (`:LspLog`) directly (no wrapper module) — in the house format
`[operation] target detail Xms outcome`:

- **Server (JUL) — the discovery outcome, which the run path already covers:**
  - `[runnables] <uri> no open doc → empty` — the silent miss that explains "no tests" when the
    client *is* attached but the file isn't analyzed yet.
  - `[runnables] <uri> Xms targets=N` — the result.
  - `[status] <root> loading` / `ready Xms` — from §3.1 (lands with the readiness fix).
- **Client (`vim.lsp.log`) — only the client-only signal:**
  - `[discover] <file> client=absent → nil` — the race: the request was *never sent* because no
    client had attached, so the server cannot log it. This is the one line that pinpoints D1.
  - (With the fix, §3.2:) `[discover] … awaiting ready` and `[status] ready → re-discovering K
    buffers` — also client-only.
  - No run/results line (the server's `[replay] … exit` already covers a run), no timing (kept
    simple, per the client's minimal role).

Quiet by default, on under `LATHE_DEBUG` (matching the server); one line per distinct outcome.

## 4. Decisions to confirm

1. **Gate signal shape:** custom `lathe/status {state}` (recommended — a clean, explicit gate like
   jdtls/rust-analyzer) as the primary readiness event, with `$/progress` as the complementary
   visible bar. Alternative: gate purely on `$/progress` `end` and skip the custom notification
   (fewer moving parts, but progress-end is a fuzzier gate and couples us to nvim's progress
   aggregation).
2. **Client log sink:** ~~confirm~~ **Resolved** — `vim.lsp.log` (`:LspLog`), used directly, no
   custom log module.
3. **Await timeout** in `discover_positions` when readiness never arrives (server crash / no
   workspace): what bound, and what to log/return on timeout (`nil`, so neotest simply shows no
   tests, plus a `WARN`).

## 5. Verification

- **Harness:** a spec that calls `discover_positions` **immediately, without waiting for attach**
  (reproducing the eager path) and asserts it still resolves to the real tree once ready — locking
  D1 out. This is the case the current harness never exercised (it waits for attach first), which is
  why the race shipped.
- **Manual:** open a test file as the first buffer in the real project → tests appear without
  re-opening.

## 6. Non-goals / later

- **Treesitter discovery fast-path** (project-wide / closed-file discovery, so the summary populates
  for unopened files too, like `neotest-java`) — a separate, larger change; the readiness signal
  here does not need it.
- Fine-grained `$/progress` reporting (percentages per module) — left to
  [lathe-lsp-progress.md](lathe-lsp-progress.md).

## 7. Why this is the right fix

- It matches the established pattern (jdtls `ServiceReady`, rust-analyzer `serverStatus`) rather than
  polling or an adapter-local `nio.sleep` hack.
- The readiness signal is **server-wide and reusable**, so it addresses the whole class of
  "fired-before-ready" races — not just neotest discovery — which is the category of intermittent
  startup problems seen before.
- It advances an already-planned roadmap item (`lathe-lsp-progress`) instead of bolting on
  throwaway code, and the logging pass makes this and future startup issues diagnosable in seconds.
