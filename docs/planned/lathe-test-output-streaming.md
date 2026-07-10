# Lathe — Neotest Output Navigation

Design for making Lathe test failures feel natural in Neovim while keeping the standard neotest
output workflow.

Live output streaming is deliberately deferred.
The first goal is a copy/paste-clean output buffer with stack-trace navigation layered on top.

---

## 1. Direction

Lathe keeps the existing replay model:

- the server launches the captured replay JVM;
- `ReplaySession` captures the merged stdout/stderr transcript;
- `ReplayOutcome.output` returns the full transcript after the run completes;
- `neotest.lua` writes that transcript to the `neotest.Result.output` file.

The Neovim adapter does not create a Lathe-specific output command or custom output buffer.
Users open output through neotest's normal UI.

The adapter may decorate that output buffer after neotest opens it.
Decorations must not change the buffer text.

---

## 2. Prior Art

VS Code's Java test runner, built around JDT LS, follows the same separation:

- raw output is appended to the test run output;
- stack traces are parsed separately;
- resolved source locations are attached to test messages/UI metadata.

The important lesson is that navigation is metadata, not rewritten stack-trace text.
Lathe should follow that model in Neovim.

---

## 3. Neovim Design

Neither of neotest's own output surfaces backs its buffer with the transcript file itself — both
were re-read directly from the installed plugin (`~/.local/share/nvim/lazy/neotest`) while designing
this section, not assumed from memory:

- `consumers/output.lua`'s `open_output()` (the one-shot floating window, `neotest.output.open()`)
  creates a fresh **anonymous** scratch buffer (`nvim_create_buf(false, true)`), pipes `Result.output`
  through `lib.ui.open_term()` (`nvim_chan_send` into a terminal channel), and — reliably, at the end
  of every call — sets `filetype = "neotest-output"` on it. The buffer is never named or otherwise
  linked back to the transcript file path, so file-identity correlation is not possible. The `FileType
  neotest-output` autocommand is the one dependable, generically-scoped hook: it fires exactly when a
  fresh buffer holding Lathe's transcript text is about to be shown.
- `consumers/output_panel/init.lua`'s buffer is a single **persistent** terminal buffer, shared across
  the whole session and every adapter, appended to incrementally with no per-run separator and no
  `FileType` set. There is no "buffer opened for this file" event to hook here at all; reliably
  mapping newly-appended terminal lines back to source content would need to watch content deltas
  (`nvim_buf_attach` with `on_lines`) against an inherently async terminal channel. Left out of the
  first iteration (§5) as a materially different mechanism, not a smaller version of the same one.

Because there is no real per-transcript buffer to key metadata off of, the design drops file-identity
correlation entirely and resolves purely from the stack-frame text already sitting in the buffer:

1. On `FileType neotest-output`, scan the buffer's lines for Java stack-frame lines (§4 pattern).
2. For each candidate frame, query the already-running Lathe LSP server with the **standard**
   `workspace/symbol` request (`LatheWorkspaceService.symbol`, backed by `WorkspaceSymbolResolver` /
   `WorkspaceTypeIndex` — the same index the editor's own symbol search already uses), using the
   frame's simple class name as the query string.
3. Resolve among the returned `SymbolInformation` results per §4, and skip the frame on ambiguity or
   a miss.
4. For each resolved frame, add an extmark/highlight over the `(File.java:line)` span and a
   buffer-local `<CR>` / `gF` mapping that jumps to the resolved `Location`.

This needs **no new server-side command, no new notification type, and no metadata registered at
output-write time** — `neotest.lua`'s existing `results()` / `write_output_file` path is completely
untouched. The only new pieces are a Neovim-side `FileType neotest-output` autocommand and an LSP
`workspace/symbol` request, made the same way `discover_positions` already makes `lathe.runnables.list`
requests against `lathe_client()`.

Copying the stack trace still copies exactly the original test output.
No `file:///...` prefixes, resolved absolute paths, OSC-8 hyperlinks, or synthetic helper lines are
inserted into the transcript — extmarks and buffer-local keymaps are display/interaction-only and
never mutate buffer text.

---

## 4. Resolution Strategy

The first implementation should stay conservative:

1. Parse only standard Java stack-frame lines matching `at <fqcn>.<method>(<SimpleFile>.java:<line>)`.
2. Query `workspace/symbol` with the frame's simple class name — the last `.`-delimited segment of
   the FQCN, with any `$Nested` suffix stripped.
3. Among the results, prefer a `SymbolInformation` whose container/package matches the frame's FQCN
   prefix. If exactly one candidate comes back, accept it even without a package match — a class name
   being unique workspace-wide is the common case.
4. Do nothing for ambiguous (multiple candidates, none matching the package) or empty results.

Framework-frame filtering — skipping `java.base` / `org.junit.platform` / etc. frames before even
attempting a lookup, as a cheap pre-filter — can be added later.
The raw output should remain complete even when navigation metadata is incomplete.

---

## 5. Non-goals

- No live output buffer.
- No custom `lathe/testOutput` LSP notification.
- No custom `:LatheTestOutput` command.
- No visible file links injected into stack traces.
- No attempt to replace neotest's output panel.
- No `output_panel` support in the first iteration — its shared, persistent terminal buffer has no
  per-run hook to attach to (§3); revisit only as an explicit follow-up if it proves needed in
  practice, not as a silent gap in this one.

Streaming can be revisited after stack-trace navigation works well.
If it returns, it should reuse the same parsing/navigation layer instead of introducing a separate
Lathe-only output experience.

---

## 6. Reviewable Deliverables

### 6.1 Remove abandoned streaming prototype

**Scope:** delete the custom notification/client plumbing and Neovim scratch-buffer command.
Keep the existing final-output file path through `ReplayOutcome.output` and `neotest.Result.output`.

**Verification:** server replay tests and Neovim adapter specs pass.

**Commit prefix:** `refactor: drop custom test output streaming prototype`

### 6.2 Add stack-trace navigation for neotest output

**Scope:** a `FileType neotest-output` autocommand that scans the buffer for Java stack-frame lines,
resolves each via a `workspace/symbol` request, and adds extmark/highlight decoration plus
buffer-local `<CR>` / `gF` jump mappings for resolved frames. No changes to `results()`,
`write_output_file`, or any server-side code — `workspace/symbol` already exists.

**Verification:** focused Neovim specs for stack-frame parsing (pure function, no neotest/nio
dependency) and the `workspace/symbol` query-construction logic; manual verification against Helidon
for real stack traces, confirming `<CR>` jumps to the right file/line and the transcript text is
unchanged after decoration.

**Commit prefix:** `feat(neovim): navigate stack traces in test output`
