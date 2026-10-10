# Lathe — Rename Transactions

## Status

**Potential — on hold.** No code yet. The open issues at the end must be resolved before this moves to planned.
Rename first: it is the largest edit the server produces (a type rename touched 108 files and 1,059 occurrences in equalsverifier) and the hardest for the server to absorb.
Other multi-file edits the server produces (code actions, extract refactorings) can join later through the same mechanism.

## Problem

When the server answers `textDocument/rename`, it knows the whole change: every file the edit touches and each file's exact new content.
It forgets this the moment it replies.
The editor then applies the edit and the user saves (`grn` + `:wa` in Neovim), and the server handles the resulting stream of events one file at a time, as if they were unrelated:

| Event per touched file | What the server does today |
|---|---|
| open (the editor loads the file to edit it) | `OPEN` compile, publish |
| edit (the rename's text change) | `publishEmpty`, then a debounced `FAST` compile, publish |
| save (`:wa`, format-on-save first) | `FULL` compile (writes the mirror), publish, request a dependents refresh |
| every publish | `workspace/semanticTokens/refresh`: the editor re-requests and repaints the tokens of every visible window |

Measured on equalsverifier (rename `EqualsVerifier`, 108 files, `:wa` in Neovim with in-process formatting):

- **Work:** about 86 s of compiler time — 108 save compiles (median ~460 ms), hundreds of live compiles, and dependents refreshes.
- **Flicker:** each of the hundreds of publishes clears or replaces a file's diagnostics and triggers a workspace-wide semantic-token repaint.
- **Cache thrash:** with ~109 buffers open against an analysis cache of 100, editor requests cycling over the buffers (tokens, folding, neotest discovery) evict each analysis just before it is needed again, so each request recompiles.

Several workarounds were tried and measured: a debounce and then a quiet period on the dependents refresh, and a runnables cache.
Each reduced one symptom; none removed the fan-out, because they all guess from the event stream what the server already knows.
They are not adopted.
Two structural fixes are kept, as they hold for any burst: the dependents refresh waits for in-flight save compiles to drain, and reconcile skips a file whose save compile is in flight.

## Goal

Absorb a rename as **one transaction**: one analysis when the edit is applied, one compile per module when it is saved, one dependents refresh and one semantic-token refresh at the end — independent of how many files it touched and of how fast the editor saves them.

## Non-goals

- Bursts the server did not produce (a hand-edited `:wa`, `git checkout`, another tool writing files): the drain and reconcile already cover them.
- Making the editor save. The transaction expects saves but never depends on them.
- Edits other than rename, for now.

## Design

### The transaction

When `renameFuture` builds the `WorkspaceEdit`, the session opens a `RenameTransaction` holding:

- the **members**: every file the edit touches, by URI *after* the edit (for a public type, the moved file is a member under its new URI);
- the **expected content** of each member: its current content (open document, else disk) with the edit applied;
- a **deadline**, extended by each member event.

Only one rename transaction is open at a time; a new rename ends the current one (see Ending).

### Applying the edit: one analysis

As the editor applies the edit, members arrive as `didOpen` and/or `didChange`.
For a member whose content now **equals its expected content**:

- no `publishEmpty` and no per-file `FAST` compile is scheduled for it;
- once every member that the editor has open reached its expected content (or after a short settle, since some editors apply edits to unopened files on disk), **one in-memory batch analysis per module** compiles all members together and publishes each member's diagnostics once.

This needs one new compiler call: `diagnoseInBatch` returns diagnostics for a single target today; a variant returns them for every source in the batch.
The mirror is not written here: the edit is not on disk yet.

A member whose content diverges from the expected content (the user typed into it) leaves the transaction and is handled as an ordinary document from then on.

### Format-on-save

An editor with format-on-save asks the server to format a member just before writing it, then applies the returned edits as another `didChange`.
If formatting changes anything — for example re-wrapping a line the rename made longer — the member's content no longer equals the expected content, and by the rule above it would leave the transaction at its own save.

The server produced that formatted text itself, so a formatting response for a member (whole-file or range) **becomes the member's expected content**.
The change that follows still matches, and the member stays in.
Formatting needs no compile (it works on the text), so it adds no work to the transaction.

### Saving: one compile per module

A `didSave` of a member is **recorded, not compiled**.
Any saved content counts — format-on-save may legitimately reformat a line the rename made longer; membership, not content, decides.

When the **last member is saved**, the session runs, per module and upstream-first, one save-time batch compile (`compileBatch`, as reconcile uses) over the members' saved contents: it writes the mirror and records the compile stamps.
Then it requests **one** dependents refresh for the union of the members' modules (excluding the members, which were just compiled) and sends **one** `workspace/semanticTokens/refresh`.

While the transaction is open, publishes for members do not send their own semantic-token refresh.

### Ending

The transaction ends, and anything unfinished falls back to the ordinary path:

- **Completed** — the last member was saved (above).
- **Deadline passed** with members unsaved — the saved members are compiled as a batch as above; the unsaved ones remain open documents with their (already published) analysis and will compile on their own save.
- **Another rename**, a workspace reload, or server shutdown — saved members are compiled as a batch; the rest fall back.
- **A member is closed unsaved** (the user reverted the rename) — it leaves the transaction.

Falling back never loses work: every member is either compiled by the transaction or handled by the existing per-file path.

### Interaction with existing mechanisms

- **Reconcile** treats members as in flight while the transaction is open (as it already does for save compiles), so it never compiles them separately from disk; a member written to disk without a `didSave` is picked up by the transaction (see Editors).
- **Dependents drain** stays: it covers bursts outside a transaction.
- **Analysis cache:** the batch analysis keeps member analyses in one pass; it does not change the cache cap (raising it would not scale to large projects).

## Editors

The transaction is server-side and keyed on protocol events, so it must hold for every way a client applies a `WorkspaceEdit` and saves:

| Client | Applying a rename | Saving | What the transaction relies on |
|---|---|---|---|
| **Neovim** | loads each file as a buffer (`didOpen`), applies edits (`didChange`); a file move renames the buffer (`didClose` old, `didOpen` new) | `:wa`, sequential, format-on-save per buffer | content match on change; saves counted by membership |
| **VS Code** | opens each file as a dirty in-memory document (`didOpen` + `didChange`); a file move is applied on disk | Save All, near-simultaneous | same; fast saves end the transaction quickly |
| **Emacs/Eglot** | visits each file and edits the buffer (optionally after confirming) | `save-some-buffers`, sequential | same as Neovim |
| **Zed** | edits buffers, shows them in a multibuffer | save all | same |
| **A client that edits unopened files on disk** | no `didOpen`/`didChange` for those files | the edit itself writes them | the stale scan sees the member on disk with its expected content and counts it as saved |
| **MCP `rename_symbol`** | writes every file to disk, no document events | — | the engine ends the transaction itself after writing, compiling all members as one batch |

Design rules that follow:

- Never require `didOpen` for a member, nor a `didSave`, nor a particular order.
- Match edits by **final content**, not by the shape of the change events (full vs incremental sync).
- Treat a formatting response the server gave for a member as the member's new expected content, so format-on-save never pushes a member out.
- Key members by their final URI, so a moved file is the same member before and after the move.
- Never wait on the editor: the deadline bounds every transaction.

## Tests

- **Session tests (unit):** a transaction completes on the last member save with one batch compile per module and one refresh; a member diverging by hand leaves it, while one changed by a server formatting response stays; the deadline compiles the saved members and releases the rest; a new rename ends the previous one; a member written to disk without `didSave` counts as saved.
- **Service tests (burst counting, as the existing dependents tests do):** applying a rename to N open files then saving them all — as back-to-back saves (VS Code) and as paced saves (Neovim) — produces at most one compile per member for the edit, one batch compile per module for the saves, one dependents refresh, and exactly one `workspace/semanticTokens/refresh` after the transaction.
- **Invoker:** `LspSmokeTest`'s rename scenario saves every touched file and asserts the mirror is current afterwards.
- **Measurement:** the equalsverifier rename (`EqualsVerifier` ⇄ `EqualsVerifierXXX`) with Neovim's LSP log at `debug`, comparing compile counts, publishes, semantic-token refreshes, and time-to-quiet against the numbers in Problem.

## Open questions

1. **Deadline length.** It must cover the gap between the rename and the first save (user think time) and between saves (sequential `:wa` with format-on-save, ~0.5 s per file measured). A deadline re-armed by every member event — for example 10 s of member inactivity — bounds stragglers without cutting a slow `:wa` short.
2. **Diagnostics before the batch analysis lands.** For a member opened only to apply the edit, publishing nothing until the batch completes (a second or two) is preferable to the current clear-then-republish cycle; confirm with the measurement.
3. **Later scope.** Which other server-produced edits should open a transaction once rename is proven.

## Open issues (design review)

Found reviewing this design against the code; each must be resolved in the design before implementation:

1. **The deadline cuts off think time.** A transaction that ends after ~10 s of member inactivity falls back to the per-file path if the user reviews the rename before saving. Proposal: no deadline while members are unsaved and unmodified by hand; the inactivity deadline starts at the first save.
2. **Moved files.** A public-type rename moves `Foo.java` to `Bar.java`; the transaction must delete the old file's classes from the mirror, drop its compile stamp, and update the reactor type index (old type out, new type in).
3. **Bulk threshold.** Reconcile defers more than 50 externally changed files to a sync; members written to disk by a client (rather than through `didSave`) must be exempt, or a large rename hits it.
4. **The batch-analysis trigger is a timer.** "After a short settle" reintroduces timing; the trigger should be "every member accounted for" (opened and matching, or seen on disk with its expected content), with a timer only bounding members that never appear.
5. **Partial application.** A client may apply only part of the edit (VS Code's refactor preview lets the user untick files); a member still unchanged when the others are all saved should leave the set.
6. **Main and test trees.** Batches must run per source configuration (`classes`, `test-classes`), main before test, upstream-first — not per module.
7. **Stale mirror window.** Until the last save, other modules compile against the old classes; mostly invisible (only members reference the renamed symbol), but to be stated as accepted.
8. **Semantic tokens.** Holding back the refresh leaves member buffers with pre-rename tokens until the transaction ends; confirm the effect with the measurement.
9. **Memory.** Expected content is held per member; cap the member count and fall back above it for very large renames.
