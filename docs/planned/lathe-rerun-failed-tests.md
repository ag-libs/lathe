# Lathe — Re-run Failed Tests from the neotest Client

## Status

Option 1 shipped — [NV-5](../gaps/gaps.md) resolved. Option 2 deferred (revive on demand).

- **M2 — Option 1: re-run the *first* failing test. Implemented.**
  `require("lathe.neotest").run_first_failed()`, bound `<leader>tF`. A self-shrinking ordered set of
  runnable failures is captured in `results()`; each call re-runs its head via `neotest.run.run`, so
  repeated calls walk the failures. No `results()` *reconciliation* change — only the small set capture.
- **Follow-up — Option 2: re-run *all* failed tests in one replay JVM** (repeat the previous run with
  the selection narrowed to the failures). Heavier; deferred past M2.

The [Debug/neotest Unification](lathe-debug-neotest-unification.md) has landed (`eba2c0e`), so
`run_spec` already accepts an arbitrary `selections` list and a `strategy`, and `results()` carries the
name-match container rollup. Option 2 builds on that unified `results()`; Option 1 needs none of it.

Builds on the already-implemented machinery:

- [neotest Experience](../done/lathe-neotest-experience.md) — the `build_spec`/`results()`
  reconciliation and `run_spec`'s token/stream plumbing.
- [neotest Streaming](../done/lathe-neotest-streaming.md) and
  [Structured Test Results](../done/lathe-structured-test-results.md) — the per-test `positionId` +
  status that identify a failure.
- [Run, Test, and Debug](../done/lathe-run-test-debug.md) — `lathe.run.test`, which already runs a
  **list of selections** in one replay JVM.

## Goal

Two client actions, delivered in order:

1. **`require("lathe.neotest").run_first_failed()`, suggested `<leader>tF` — re-run the first failing
   test** (M2). The everyday red-green loop: knock failures down one at a time, verify each.
2. **`require("lathe.neotest").run_failed()` — re-run every failing test of the last run in one JVM**
   (follow-up), for "re-run the whole red set and see what's still broken."

Both are Lua entry points bound by the user, matching the adapter's existing `stop()` / `open_output()`
surface (not `:Lathe*` commands). `<leader>tl` is intentionally **not** used — it is reserved for
neotest's native `run_last` (see the [neotest experience](../done/lathe-neotest-experience.md)); `tF`
("test **F**ailed") parallels `<leader>tf` (run file) and `<leader>tS` (stop).

## Why two options — the tradeoff that splits them

Re-running a **single** test is just running an existing position: its subtree is itself, so
`results()` reconciles it with the path that already works — no new mode, no clobber, one VM.

Re-running **all** failures in **one** VM is fundamentally harder. To reuse the previous run's single
JVM you must re-fire the **broad** position (the dir/file). But `neotest.run.run(broad_position)` marks
that position's whole subtree **"running"** up front, and `results()` only clears the ids it returns —
so it must return an entry for *every* node, including the passing tests it did not re-run. Today those
non-rerun nodes fall back to the run aggregate = the rerun's exit code; if the rerun **still has any
failure**, that is `failed`, and every previously-green file is repainted **red** — the exact bug fixed
in `73175d3`, reappearing on rerun. Avoiding it requires *retaining* the non-rerun tests' prior status.

So for "re-run all", you can have any two of {one VM, correct summary, minimal client logic}:

| Approach | VMs | Summary correct? | Client logic |
|---|---|---|---|
| Per-method (`run.run` each failure) | **N** | ✅ | minimal (zero `results()` change) |
| Broad position + `run_set` retain | **1** | ✅ | not minimal (retain mode in `results()`) |
| Broad position, no retain | 1 | ❌ clobbers green → red | minimal |
| One VM, bypass neotest | 1 | ❌ no gutter updates | minimal-ish |

No row is 1-VM **and** correct **and** minimal. Option 1 sidesteps the whole table (a single position
is never "broad"); Option 2 must pick a row — this design picks **broad position + `run_set` retain**,
kept correct and decoupled from neotest internals via an adapter-side shadow (below).

## Option 1 — Re-run the first failing test (M2)

Minimal by construction: no `build_spec` change, no `results()` change, one VM, no clobber.

**Target selection.** Prefer neotest's own failed-navigation if available — jump to the first failed
leaf and run nearest — so no failed-set bookkeeping is needed:

```lua
-- verify the installed neotest exposes a status-filtered jump; if so:
require("neotest").jump.first({ status = "failed" })   -- or jump.next from the top
require("neotest").run.run()                           -- run the position now under the cursor
```

If neotest has no status-filtered jump, fall back to a tiny capture: record the last run's failed
`positionId`s in execution order (from `outcome.testResults`, which the sink writes in run order) and
`neotest.run.run(first_id)`. Either way the executed run is an ordinary single-position run.

**Ergonomics — landing on the failure.** Jumping first also moves the cursor onto the failing test, so
the user is positioned to fix it (the IntelliJ / pytest `--stepwise` feel). This turns the discovery
limit into a feature — see below.

**Discovery caveat.** `neotest.run.run(id)` only resolves a **discovered** position. After a class/file
run every failure is discovered, so Option 1 covers that (the common) case directly. After a *package*
run, failures in never-opened files have no position node; M2 targets the first failing **discovered**
test and notes the rest. Opening the failing file first (needs an `FQCN → path` resolution, a small
server round-trip) is a follow-up that would extend coverage to undiscovered failures.

**Optional stepwise enhancement (not required for M2).** Keep the ordered failing list + a cursor:
re-run the first failing test; when it passes, advance to the next. Each step is still a single-position
run, so it stays within Option 1's zero-reconciliation-cost envelope.

## Option 2 — Re-run all failed in the previous run's VM (follow-up)

Repeat the previous run with the selection narrowed to the failures — one replay JVM, only the failed
cases. This is the "broad position + `run_set` retain" row, made correct without coupling to neotest
internals:

1. **Capture.** Remember the last run's `(position_id, module_rel)` and, in `results()` on a normal
   run, shadow its full reconciled result map (`M._last_results`, positionId → result) plus the failed
   `METHOD` selections.
2. **Execute.** `run_failed()` re-fires the last position through neotest with the failed selections,
   producing one `run_spec` → one `lathe.run.test` → one VM.
3. **Reconcile with `run_set`.** Thread the launched `positionId`s into `ctx`. `results()` returns
   `shadow ∪ fresh` — fresh (rerun) results win for `run_set` ids; every other node keeps its shadowed
   prior status (so nothing spins and nothing is clobbered); containers roll up from the merged map.
   When `run_set` is absent, `results()` is unchanged.

**Routing without hidden state.** To re-fire through neotest so `results()` runs, pass the failed
selections **explicitly** via `neotest.run.run`'s args into `build_spec.args` — *if* the installed
neotest forwards custom fields there. If it does not, scope the value to the position and clear it on
consume (never a bare module-global), so a subsequent normal run can't accidentally inherit it.

Why it waits: `run_set`/shadow is a second reconciliation mode on `results()` (alongside the unified
debug-completion path), justified only by large failure sets. M2's Option 1 delivers the common loop
without it.

## Behavior and edges

- **No previous run / no failures** → info toast, nothing launched.
- **Renamed / moved / deleted test** → its `positionId` selects nothing on replay (benign); dropped on
  the next reconcile.
- **Class-level `@BeforeAll` / init failure** → reported at the class (container) level, and the runner
  records only method-level results (`ResultsListener` skips containers), so there is no `METHOD` to
  target. Both options leave that class red from the original run; narrowing it (a `CLASS` selection for
  a container failure) needs container-result capture — a follow-up tied to the `lathe/testFinished`
  outcome.
- **Stop** → the existing `M.stop()` cancels the in-flight token, unchanged.

## Files in scope

- **Option 1 (M2):** `lua/lathe/neotest.lua` — `run_first_failed()` (+ optional ordered-failed
  capture); `docs/guide/editors/neovim.md` + the plugin keymap example.
- **Option 2 (follow-up):** additionally the `M._last_results` shadow + `ctx.run_set` reconciliation in
  `results()`, the `run_failed()` entry, and the `build_spec` selection-override plumbing.

## Testing

- **Option 1:** with a captured/failed set, `run_first_failed` runs exactly the first failing
  discovered position (and nothing when there are no failures); a stepwise step advances to the next on
  pass.
- **Option 2:** `results()` with `ctx.run_set` returns `shadow ∪ fresh`, updates only the launched
  tests, retains the rest, and clears every node's "running" — a repeat that still fails does **not**
  repaint a previously-green sibling file red.

## Non-goals

- Persisting the failed set across nvim restarts.
- Re-running failures from several past runs at once, or across modules in one action.
- Narrowing a class-level (container) failure — needs container-result capture.
- Per-test output attribution (the separate parked spike).

## Open decisions

1. **Option 1 target source** — neotest's status-filtered jump (if it exists; verify) vs an adapter-side
   ordered failed list.
2. **Stepwise** — ship the one-shot "first failing" for M2, or the stepwise advance-on-pass loop too.
3. **Option 2 routing** — explicit `neotest.run.run` args into `build_spec.args` vs a
   scoped-and-cleared pending value (depends on what the installed neotest forwards).

## Implementation order

- **M2:** verify neotest failed-jump → `run_first_failed()` (+ capture fallback) → `<leader>tF` keymap
  example → editor-guide docs.
- **Follow-up (Option 2):** `M._last_results` shadow + `ctx.run_set` reconciliation + tests →
  `build_spec` selection override → `run_failed()` → docs.
