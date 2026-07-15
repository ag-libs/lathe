# Lathe — Workspace Readiness via Progress, and Neotest Discovery Logging

Fixes the long-standing startup race where a feature fires before the server has loaded the
workspace — reproduced concretely as neotest showing no tests when a test file is the first buffer
opened (experience-spec **D1**). The fix is the *proud, standards-based* one: the server reports
workspace load/reload as standard **`$/progress`**, and the client **gates discovery on that
progress's completion**. One mechanism gives three things — a visible loading bar in
`vim.lsp.status()`, a readiness gate, and it implements the init/reload scope of the planned
[lathe-lsp-progress.md](lathe-lsp-progress.md) (which is **not yet implemented** — verified: today
`initialize()` only sends a `showMessage` toast; `ProgressReporter` is wired only to reference/call
searches).

The logging half (§4) is already implemented (commit `fb3c5d1`); it is documented here for the full
picture.

---

## 1. Problem (reproduced)

Driving the real project headlessly:

```
IMMEDIATE (before attach) discover => nil
ATTACHED                            => true
AFTER-ATTACH discover               => tree with 5 positions
```

Discovery works perfectly *once the workspace is loaded*. But the neotest config eagerly calls
`neotest.run.get_tree_from_args(...)` on plugin load, and neotest also discovers on open — both fire
seconds before the Lathe JVM has attached and scanned the workspace. `discover_positions` then gives
up immediately (`if not lathe_client() then return nil end`), neotest caches "no tests," and never
re-discovers. The only readiness cue the server emits is a `showMessage` toast — human-facing, not a
signal a client can gate on.

## 2. How mature servers solve this (precedent)

The same class of bug is solved the same way everywhere: **the server signals readiness; the client
gates work on it.** No polling, no racing.

- **rust-analyzer** emits standard `$/progress` for its indexing/loading; editors render it and know
  the server is ready when it ends. (It adds `experimental/serverStatus` for health/quiescence on
  top — out of scope here.) Lesson also taken: scope progress to load/reload, never per keystroke.
- **jdtls** carries `workDone/totalWork` progress during project import and a terminal
  `language/status: ServiceReady`; clients wait for ready before project-dependent requests.

We follow the rust-analyzer shape: **standard `$/progress` for the workspace load, and its `end` is
the readiness edge** — no Lathe-specific notification needed.

## 3. Design

### 3.1 Server — report workspace load/reload as `$/progress`

This is the init/reload scope of `lathe-lsp-progress`, implemented here.

- Wrap the worker's workspace load (`WorkspaceSession.initialize`) and each reload in a progress
  task under a **stable, recognizable title constant** (e.g. `"Lathe: indexing workspace"`):
  `progress.begin(title)` when the scan starts → `progress.end()` when it completes (where the
  `workspace ready` toast is sent today).
- Server-initiated progress is **already supported**: `ProgressReporter.open(null, response)` mints
  its own token and sends `window/workDoneProgress/create` when the client advertises
  `window.workDoneProgress`. The `response` future (used only for cancel bookkeeping) is the load
  future or a placeholder.
- The recognizable title is the contract the client keys on to tell workspace progress apart from
  feature progress (`"Finding references…"`), which also uses `$/progress`. It lives as a constant
  the server owns.
- `ProgressReporter` already logs `[progress] …`; keep the `workspace ready` toast (or let the
  client raise it) as a separate UX nicety.

### 3.2 Client — gate discovery on the progress completion

- Register an **`LspProgress`** autocmd (first-class in Neovim ≥0.10). When an event from the lathe
  client carries the workspace title with `value.kind == "end"`, set a **persistent `ready` flag**,
  fire an `nio` event, and **re-trigger discovery for open Java buffers** so anything neotest cached
  empty before attach refills — the user never re-opens the file.
- `discover_positions`: if `ready`, proceed; else `await` the ready event, bounded by a timeout. The
  eager `get_tree_from_args` and neotest's on-open discovery both now *suspend* until ready instead
  of returning `nil`.
- **Missed-edge safety:** if the progress `end` fired before the autocmd was registered (adapter
  loaded very late) the flag stays false; on await timeout the adapter **tries `runnables.list`
  anyway** rather than giving up — the workspace is loaded by then, so it succeeds. A missed signal
  degrades to a slightly slower first discovery, never a broken one.

### 3.3 Why `loading` is not a separate signal

An earlier draft proposed a `lathe/status {loading|ready}` notification. Dropped: the only thing the
gate needs is the **ready edge**, which the progress `end` already provides; the "loading" UX is the
progress *bar* itself. One mechanism, not two.

## 4. Logging (implemented — commit `fb3c5d1`)

Existing frameworks only (server JUL / `LATHE_DEBUG`, client `vim.lsp.log`), **no duplication** —
each side logs only what it alone can see:

- **Server:** `runnablesFuture` logs the discovery outcomes it owns — `[runnables] <uri> no open
  doc → empty` and `[runnables] <uri> Xms targets=N`. (Run path already logs `[replay] … exit`.)
- **Client:** one line — `[discover] <file> client=absent → nil`, the request that is never sent so
  the server cannot see it. The readiness work adds `[status] ready → re-discovering K buffers` and
  an await-timeout warning.

## 5. Does this actually fix the race?

Yes, for the reproduced case (opening a test file first — D1), because discovery no longer *races*:
it **suspends until the workspace-load progress ends**, then proceeds (the repro shows discovery
returns real positions once loaded), and the ready edge **re-discovers** anything cached empty
earlier. The eager `get_tree_from_args` that triggers the bug is itself gated.

Honest bounds on the claim:

- Depends on the client advertising `window.workDoneProgress` — Neovim does by default; if a client
  did not, `open` degrades to a no-op task and discovery falls back to the timeout→try-anyway path
  (still works, just without the visible bar).
- A missed progress `end` (late adapter load) degrades to timeout→try-anyway, not failure.
- It fixes **open-file** discovery. It does **not** make the project-wide summary populate for files
  you have not opened — that needs closed-file/treesitter discovery (§7), a separate effort.

## 6. Decisions to confirm

1. **Await timeout** in `discover_positions` when readiness never arrives — proposed **~30s**
   (matches a cold JVM start), then **try `runnables.list` anyway** (degrade, not give up) + a
   `WARN`. OK?
2. **Workspace-progress title** — a fixed constant the client matches on (e.g. `"Lathe: indexing
   workspace"`). Confirm wording; it is user-visible in `vim.lsp.status()`.

## 7. Verification

- **Harness:** a spec that calls `discover_positions` **immediately, without waiting for attach**
  (the eager path that ships broken today) and asserts it still resolves to the real tree — the case
  the current harness never exercised, which is why the race shipped. It exercises the await-until-
  ready gate end to end.
- **Manual:** `LATHE_DEBUG=1 vi …DiscountWrapperTest.java` as the first buffer → tests appear
  without re-opening; `vim.lsp.status()` shows the indexing bar; `:LspLog` shows the flow.

## 8. Non-goals / later

- **Treesitter discovery fast-path** (project-wide / closed-file discovery, so the summary populates
  for unopened files like `neotest-java`) — separate, larger; not needed by this fix.
- `experimental/serverStatus`-style health reporting — out of scope; `$/progress` covers readiness.

## 9. Relationship to lathe-lsp-progress

This implements that doc's **workspace init/reload** scope (the `$/progress` begin→end lifecycle) and
consumes it as the readiness signal. Remaining lsp-progress ideas (per-module percentages, reload
granularity) stay in [lathe-lsp-progress.md](lathe-lsp-progress.md) as follow-ups.
