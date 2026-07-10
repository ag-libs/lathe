# Lathe — Test Output: Diagnostics and Docked-Window Freshness

Follow-up to the shipped work in `docs/done/lathe-test-output-streaming.md`. That document covers
stack-trace navigation inside neotest's own output buffer (`output.lua`, `filetype=neotest-output`)
and a docked-split convenience wrapper (`lathe.neotest.open_output()`). Verifying that work
interactively against Helidon surfaced a real, structural limitation it doesn't solve, and a look at
how `nvim-jdtls` handles the same problem suggests a different, complementary direction rather than
patching around the limitation.

This document lays out the problem and the options, without committing to one yet.

---

## 1. Problem

`lathe.neotest.open_output()` opens a docked split so the output window can stay open across
multiple test runs (§7.3 of the shipped design). But `output.lua`'s own `open()` only rebuilds its
buffer's content when it currently has **no** tracked window. If the split is left open and you run
a *different* test, calling `open()` again just refocuses the same window showing the **first**
run's stale content — it never re-renders.

Neither of neotest's two built-in output surfaces solves this by construction:

- `output.lua` (what we decorate) renders once, at the moment `.open()` is called, and never touches
  that buffer again.
- `output_panel` never goes stale — but only because it's a fundamentally different UI: a single,
  session-long, **append-only** log, driven by actively listening to `client.listeners.results` and
  appending new content as new runs complete. It never re-renders a fixed "current state"; it just
  keeps growing. It also still has no per-run hook to decorate (already noted as a non-goal in the
  shipped design, §5) and no `FileType` distinguishing it (`neotest-output-panel`, not
  `neotest-output`).

So "docked, always current, and navigable" is not a combination either surface offers — we built
navigation on the surface that render-once, and the always-fresh surface has no navigation hook.

---

## 2. Prior art: how `nvim-jdtls` actually handles this

Checked directly against the real source
(`github.com/mfussenegger/nvim-jdtls`, `lua/jdtls/junit.lua`), not assumed from memory:

- Raw test output goes to `nvim-dap`'s REPL via `require('dap.repl').append(...)` — append-only,
  same shape as `output_panel`. No navigation is attempted there at all.
- Failures are placed with `vim.diagnostic.set(ns, bufnr, failures, {})` **directly on the failing
  line of the test source file**, and `vim.api.nvim_buf_clear_namespace(bufnr, ns, 0, -1)` runs at
  the start of every new result, before the new diagnostics are set.
- Failures also populate the quickfix list via `vim.fn.setqflist({}, 'r', {...})` — `'r'` (replace)
  mode, so it's always current too, with `<CR>`-to-jump being Neovim's own built-in quickfix
  behavior, not custom code.

The reason `nvim-jdtls` never had to solve "keep a persistent window's content fresh" is that it
never built such a window. It fully separates two concerns onto two different, differently-shaped
mechanisms:

| Concern | Mechanism | Freshness | Navigation |
|---|---|---|---|
| Raw output / println | append-only log (`dap-repl`) | trivial — nothing to invalidate | none attempted |
| "Where did it fail" | `vim.diagnostic` + quickfix, both replace-on-every-run | built into the API | native (`gO`/`<CR>`/`]d`) |

**Confirmed neotest already has the second mechanism, built in and always-on:**
`consumers/diagnostic.lua` listens to `client.listeners.results` and, for any position with
`result.errors = {{line, message, severity?}}` (`neotest.Error`, the same shape neotest-go and every
other real adapter already use), calls `vim.diagnostic.set` — replacing, not appending, so it is
always current with zero window-management code. Our adapter currently never populates `errors`.

---

## 3. What's already shipped (`docs/done/lathe-test-output-streaming.md`)

- `FileType neotest-output` autocommand scans `output.lua`'s buffer for Java stack frames and
  resolves them via `workspace/symbol` (no new server-side code).
- Extmark highlight + buffer-local `<CR>`/`gF` jump to the resolved location, targeting the most
  recently entered `java`-filetype window rather than the output window itself.
- `M.open_output()` — opens the same decorated buffer in a docked split via `output.lua`'s own
  `opts.open_win` extension point, with toggle-close-when-focused behavior.
- None of this touches `output_panel`.

This is the code the staleness problem (§1) lives in.

---

## 4. Relationship to `docs/planned/lathe-structured-test-results.md`

That document (already planned, independent of this one) would give `ReplayOutcome` real per-test
results instead of one aggregate status/output blob per class or package run. It matters here
because it directly bears on how far an `errors`-based diagnostics approach (§2) can reach:

- **Without it**: a class/package run's output is one merged transcript covering however many
  methods ran, with no per-test breakdown. Attributing one stack frame's line to *every* descendant
  method (the way status is fanned out today) would misattribute failures to methods that didn't
  cause them. So `errors` population is only safe for single-method runs (`selectorKind == "METHOD"`)
  — which also happens to be neotest's own default interactive workflow (run nearest test).
- **With it**: each `TestResult` in `ctx.outcome.testResults` would carry its own status and failure
  detail already correctly attributed per method, and the same `errors`-population approach could
  extend to class/package runs too, removing the method-only restriction entirely.

These are sequenced, complementary designs, not competing ones: diagnostics for method-level runs
can ship first and independently; structured results later removes its scope restriction rather than
requiring rework.

---

## 5. Options

### A. Leave the docked split as-is (do nothing)

Staleness remains; refreshing requires the user to notice and re-press the keymap (which does work —
`output_split_is_focused()`'s toggle-close, then a second press reopens fresh). Zero new code.
Confusing default behavior (silently stale) is the cost.

### B. Auto-refresh the docked split from `M.results()`

`M.results()` already runs on every completed run with the fresh result in hand. It could, if the
docked split is currently open, close and reopen it (`position_id = ctx.position_id`, `enter =
false`, deferred one tick via `vim.schedule` so neotest's own results cache is updated first).
Keeps the "docked and navigable" want intact. Costs: real implementation complexity (a refactor of
`M.open_output` to share its split-creation logic), and a soft timing dependency on neotest's
internal bookkeeping being updated by the next event-loop tick (very likely reliable in practice,
not a hard guarantee).

### C. Revert `<leader>tO` to plain, unmodified `output_panel.toggle()`

Embraces the jdtls-style separation directly: the docked, always-open surface goes back to being a
pure append-only log — never stale, by construction, zero code of ours involved. Cost: no in-buffer
stack-trace navigation on that surface at all (not even the extmark/jump behavior already built for
`output.lua`). Whether that's an acceptable loss depends entirely on whether §6 (diagnostics) already
covers "jump to the failure" well enough that in-buffer navigation stops mattering for the common
case.

### D. Add `result.errors` (method-level diagnostics)

As described in §2/§4. Standalone and additive regardless of which of A/B/C is chosen for the docked
split — solves "jump to the exact failing line, always fresh" for single-method runs without any
output window being open at all, matching neotest's own standard adapter convention exactly.

### E. D, plus C for the docked-split question

Ship D first — it's the highest-value, lowest-risk piece, and directly mirrors the pattern real
adapters and jdtls itself use. Then, given D now covers "jump to failure" for the common single-
method-run case without any window at all, reconsider whether the docked split's navigation is still
worth the staleness fight (B) or whether reverting it to a plain log (C) is the more honest,
jdtls-aligned answer — informed by actually using D for a while rather than deciding both at once.

---

## 6. Open questions

1. Does D (diagnostics) end up covering "jump to failure" well enough in practice that the docked
   split's own navigation (built in the shipped work) stops being needed at all — i.e., is B worth
   building, or should C win once D ships?
2. If C is chosen, should the `output.lua`-based decoration/jump work from the shipped design be kept
   around for class/package runs specifically (where D can't reach until structured results ship), or
   removed entirely as unused once `<leader>tO` no longer routes through it?
3. Should `docs/planned/lathe-structured-test-results.md` be re-prioritized given it would remove D's
   method-only restriction, or left as independently-scheduled work?
