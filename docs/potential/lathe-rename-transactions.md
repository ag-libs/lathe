# Lathe — Rename Transactions

## Status

**Slices 1 and 2 implemented** (see As built under Slices, which records where the code differs from the design below); slice 0 and the deferred parts remain.
Two design reviews are resolved (see Decisions).
Rename first: it is the largest edit the server produces (a type rename touched 108 files and 1,059 occurrences in equalsverifier) and the hardest for the server to absorb.
Other multi-file edits the server produces (code actions, extract refactorings) can join later through the same mechanism.
The chained batch write and the batch analysis this design introduces are the general primitives described in [Batching](lathe-batching.md), which maps them onto the other bursts.

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
- **Save pacing:** a later run with the drain in place took 62 s for `:wa` — a steady ~0.55 s per file — although each format request took 2–30 ms and the server went quiet ~1 s after the last save.
  About 520 ms of each step is spent in the editor between writing one buffer and formatting the next.
  The per-publish semantic-token refreshes are the suspected cause; this is not yet confirmed (see Slices, slice 0).

The MCP `rename_symbol` tool has the same blind spot from the other side.
It writes every file to disk and asks for one stale-scan refresh, but the written files are not open documents, so reconcile counts them as external changes:
more than 50 defer to a sync and leave the mirror stale.
It also cannot rename a public top-level type, because the engine never declares file-rename support and reads only the edit's `changes` map, which carries no `RenameFile`.

Reconcile itself has an ordering gap that a type rename hits hardest.
Its batches are *submitted* upstream-first, but each source tree has its own compilation worker, so they *run* in parallel:
a downstream batch can compile against the old upstream mirror classes while the upstream batch is still writing the renamed type.
The downstream sources then fail, stay unstamped, and wait for a later pass to retry them.

Several workarounds were tried and measured: a debounce and then a quiet period on the dependents refresh, and a runnables cache.
Each reduced one symptom; none removed the fan-out, because they all guess from the event stream what the server already knows.
They are not adopted.
Two structural fixes are kept, as they hold for any burst: the dependents refresh waits for in-flight save compiles to drain, and reconcile skips a file whose save compile is in flight.

## Goal

Absorb a rename as **one transaction**: one analysis when the edit is applied, one compile per source tree when it is saved, one dependents refresh and one semantic-token refresh at the end — independent of how many files it touched, of how fast the editor saves them, and of whether an editor or the engine applies it.

## Non-goals

- Bursts the server did not produce (a hand-edited `:wa`, `git checkout`, another tool writing files): the drain and reconcile already cover them.
- Making the editor save. The transaction expects saves but never depends on them.
- Edits other than rename, for now.

## Design

### The transaction

When `renameFuture` builds the `WorkspaceEdit`, the session opens a `RenameTransaction` holding:

- the **members**: every file the edit touches, by URI *after* the edit;
- the **moved-away** URIs: for a public type rename, the old URI of each moved file;
- the **expected content hash** of each member: a hash of its current content (open document, else disk) with the edit applied, with line endings normalized before hashing.
  Only the hash is kept, so a large rename holds no copies of file contents.

At most one transaction is open.
A rename while one is open **merges** into it: the members are unioned, and the new rename's expected hashes win for the files it touches.
They were computed from current content, which already includes the earlier rename's unsaved edits, so the merged hashes stay consistent.

All membership decisions go through the `RenameTransaction` object, one method per event — `onOpen`, `onChange`, `onFormat`, `onSave`, `onClose`, `onDiskChange` — each returning what the session should do: handle normally, suppress, drop the member, or commit.
Each document path makes a single call; the rules themselves are unit-tested on the object, without the service.
Every member that leaves the transaction is logged at `FINE` with its reason, so a measurement shows silent fallbacks.

The edit is applied in one of two ways, and both end in the same **commit**:

- **Engine-applied** (MCP `rename_symbol`): the engine writes every member to disk and commits immediately. No document events are involved.
- **Editor-applied** (Neovim, VS Code, Emacs, Zed): the session tracks members through document events and commits when the last member is saved.

### The commit

The commit is a reconcile restricted to the members, run eagerly:

1. **Deletions first** — a moved-away file is gone from disk, so its compile stamp has no surviving source.
   The existing orphan cleanup drops its classes from the mirror and prunes the stamp; the reactor index shard is rebuilt, taking the old type out.
2. **One batch compile per source tree, chained by dependency.**
   A tree's batch starts only after the batches of every upstream tree it depends on have completed (the main tree counts as upstream of its module's test tree); independent trees still compile in parallel.
   Each batch writes the mirror, records the compile stamps, and rebuilds the module's reactor index shard (the new type goes in), so each downstream batch resolves against the renamed upstream classes.
3. **One dependents refresh** for the union of the members' modules, **excluding the members** (they were just compiled).
   The existing drain merges the per-batch refresh requests into one while the batches are in flight; reconcile batches today exclude nothing, so the commit passes the members as the exclusion.
4. **One `workspace/semanticTokens/refresh`.**

Members are **exempt from the bulk threshold**: the server produced the change, so a 108-file rename is compiled in-process rather than deferred to a sync.
A member that fails to compile is handled as reconcile does today: it keeps its old mirror classes, stays unstamped, and is retried by a later pass.

Chaining is not specific to the transaction: ordinary reconcile batches adopt it too, which closes the ordering gap described in Problem.

### Engine-applied: the MCP process

The MCP server is a separate process from the editor's language server, and both watch the same workspace.
Without care, the editor's server would see the engine's writes before the engine's commit stamps them — a spurious bulk-change prompt, or a reconcile racing the commit for the mirror.

The engine therefore holds the reactor lock (`.lathe/lathe.lock`) from its first write until its commit completes.
Reconcile already stands down while a reactor build holds that lock.
Once it is released, the members' stamps match their files, so the editor's server sees nothing stale and only refreshes its open dependents against the new mirror.

### Editor-applied: applying the edit

As the editor applies the edit, members arrive as `didOpen` and/or `didChange`.
For a member whose content hash now **equals its expected hash**:

- no `publishEmpty` and no per-file `FAST` compile is scheduled for it;
- once **every member is accounted for** — open with its expected content, or seen on disk with its expected content (some clients apply edits to unopened files on disk) — **one in-memory analysis per source tree** compiles that tree's members and publishes each member's diagnostics once.
  A timer only bounds members that never appear; it does not decide when the analysis runs.

Before the commit, the mirror still holds the old upstream classes, so a downstream tree's analysis cannot resolve the renamed type from it.
Each tree's analysis therefore takes, besides its own members as targets, the members of its upstream trees as **additional source inputs** — compiled for resolution, not reported.
This needs one compiler extension: `diagnoseInBatch` returns diagnostics for a single target over sibling sources of one module today; the variant reports every target and accepts sources from other modules.
The mirror is not written here: the edit is not on disk yet.

A member whose content diverges from the expected content (the user typed into it) leaves the transaction and is handled as an ordinary document from then on.

### Editor-applied: format-on-save

An editor with format-on-save asks the server to format a member just before writing it, then applies the returned edits as another `didChange`.
If formatting changes anything — for example re-wrapping a line the rename made longer — the member's hash would no longer match, and by the rule above it would leave the transaction at its own save.

The server produced that formatted text itself, so a formatting response for a member (whole-file or range) **replaces the member's expected hash**: the server applies its own returned edits to the member's content and hashes the result.
The change that follows still matches, and the member stays in.
Formatting needs no compile (it works on the text), so it adds no work to the transaction.

### Editor-applied: saving

A `didSave` of a member is **recorded, not compiled**.
Any saved content counts — membership, not content, decides once the member is saved.
A member written to disk with its expected content but no `didSave` (a client that writes directly) counts as saved when the stale scan sees it.
A member whose file changes on disk to **anything else** without a `didSave` (a `git checkout` over an unsaved rename) leaves the transaction, so reconcile handles it as an ordinary external change.

When the **last member is saved**, the session commits.
While the transaction is open, publishes for members do not send their own semantic-token refresh, and reconcile treats members as in flight, so it never compiles them separately from disk.

### Ending

The transaction ends, and anything unfinished falls back to the ordinary path:

- **Completed** — the last member was saved (editor), or the engine wrote them all.
- **Inactivity after the first save** — once any member is saved, 10 s with no member event commits the saved members; the unsaved ones remain open documents with their (already published) analysis and compile on their own save.
  Before the first save there is no deadline: the user may review the rename for as long as they like, and the open members cost nothing beyond their published analysis.
  An external disk change to an unsaved member makes it leave (Saving), so no member can be skipped by reconcile indefinitely.
- **Partial application** — at commit, a member whose content never changed (the client applied only part of the edit, e.g. VS Code's refactor preview with files unticked) leaves the set.
- **A member is closed unsaved** (the user reverted the rename) — it leaves the transaction.
  The `didClose` of a **moved-away** URI is expected (Neovim renames the buffer as close-old, open-new) and changes nothing.
- **A workspace reload or server shutdown** — saved members are committed; the rest fall back.
- **A server restart** loses the transaction; every member falls back to the per-file path — correct, only slower.

Falling back never loses work: every member is either compiled by the commit or handled by the existing per-file path.

### Accepted trade-offs

- **Stale mirror until the commit.** Until the last save, other modules compile against the old classes.
  This is mostly invisible — only members reference the renamed symbol — and is accepted.
- **Held semantic tokens.** Member buffers keep pre-rename tokens until the commit's single refresh.
  Slice 0 confirms whether this is noticeable.

## Editors

The transaction is server-side and keyed on protocol events, so it must hold for every way a client applies a `WorkspaceEdit` and saves:

| Client | Applying a rename | Saving | What the transaction relies on |
|---|---|---|---|
| **Neovim** | loads each file as a buffer (`didOpen`), applies edits (`didChange`); a file move renames the buffer (`didClose` old, `didOpen` new) | `:wa`, sequential, format-on-save per buffer | content match on change; the moved-away close is expected; saves counted by membership |
| **VS Code** | opens each file as a dirty in-memory document (`didOpen` + `didChange`); a file move is applied on disk; the refactor preview may apply a subset | Save All, near-simultaneous | same; untouched members leave at commit |
| **Emacs/Eglot** | visits each file and edits the buffer (optionally after confirming) | `save-some-buffers`, sequential | same as Neovim |
| **Zed** | edits buffers, shows them in a multibuffer | save all | same |
| **A client that edits unopened files on disk** | no `didOpen`/`didChange` for those files | the edit itself writes them | the stale scan sees the member on disk with its expected content and counts it as saved |
| **MCP `rename_symbol`** | the engine writes every file to disk under the reactor lock, no document events | — | engine-applied: commits right after writing |

Design rules that follow:

- Never require `didOpen` for a member, nor a `didSave`, nor a particular order.
- Match edits by **final content** (its normalized hash), not by the shape of the change events (full vs incremental sync).
- Treat a formatting response the server gave for a member as the member's new expected content, so format-on-save never pushes a member out.
- Key members by their final URI, and record moved-away URIs, so a moved file is the same member before and after the move.
- Never wait on the editor once saving has started: the inactivity deadline bounds it.

## Slices

0. **Measure the save pacing.**
   One equalsverifier rename with Neovim's LSP log at `debug`, to confirm where the ~520 ms between saves goes.
   If the per-publish semantic-token refreshes are the cause, a stopgap that sends one refresh when the compile queue drains (instead of one per publish) helps every burst, not only renames, and can ship before slice 2.
   If the cause is elsewhere (an autocmd, neotest, the editor's write path), slice 2 still removes the flicker and the compile waste, but the `:wa` duration needs its own fix.
1. **Commit core, engine-applied.**
   The `RenameTransaction` object, the commit (deletions, chained batches, member exclusion, bulk exemption, one token refresh), chained batches for ordinary reconcile, and MCP `rename_symbol` on top of it.
   The engine declares file-rename support, applies `documentChanges` (text edits and `RenameFile`), holds the reactor lock while writing, and commits instead of requesting a stale-scan refresh.
   This fixes both MCP gaps and reconcile's ordering gap, and proves the commit without any editor-event tracking.
2. **Editor-applied tracking.**
   Expected hashes, suppression of per-member live compiles and token refreshes, the cross-module analysis on apply, format-on-save handling, recorded saves, merging, and the ending rules — all committing through slice 1.

### As built

Slices 1 and 2 are implemented with these differences from the design above:

- **No analysis on apply.** Members publish nothing between the edit and the commit; the commit's dependents refresh then compiles and publishes each open member once, against the committed mirror.
  This drops the cross-module `diagnoseInBatch` extension. The apply-time analysis stays deferred until a member's missing diagnostics during review prove to matter.
- **The commit's refresh includes the members**, since it is what publishes them. While the transaction is open, every dependents refresh **skips** members instead: a moved file's orphan cleanup triggers one at apply time, which would otherwise compile every member against the half-renamed mirror and publish errors.
- **The LSP and the engine take separate entry points**: the service's `rename` opens the transaction before the response goes out (ahead of the edit's document events); the engine calls `renameEdit`, writes the files itself, and commits with `commitChangeSet`, so an engine rename never leaves an editor transaction open.
- **A rename the editor never applies** (no member seen with its edited content and none saved within 30 s, as when a refactor preview is cancelled) is dropped, so it cannot hold its files back; an applied one still has no deadline before its first save.
- **A rename undone everywhere before saving** (Neovim's `u` in every touched buffer: undo is per buffer, and the file move stays on disk) is dropped once the last member is back to its original text, so the files return to the ordinary path. Undoing only some members leaves them unsaved; the idle commit then commits the saved ones and the undone ones compile as ordinary documents.
- **Not yet built:** holding the per-publish semantic-token refresh (the commit's refresh still sends one per published member), dropping a member on an unexpected disk change, counting a member written to disk without `didSave` as saved, and merging tasks for a workspace reload.

Measured with `dev/burst.py` on equalsverifier (rename `EqualsVerifier`, 108 files):

| | Neovim pacing, before | after | Save All, before | after |
|---|---|---|---|---|
| Compiles | 1,834 | 116 | 651 | 116 |
| `publishDiagnostics` | 1,792 | 109 | 589 | 109 |
| Token refreshes | 1,759 | 108 | 567 | 108 |
| Analysis cache evictions | 2,044 | 17 | 801 | 17 |
| Edit to quiet | 66.7 s | 72.7 s | 51.9 s | 14.4 s |

With Neovim's pacing the harness cannot show a shorter `:wa` (its pacing is fixed); the commit's chained batches and the members' refresh run after the last save, about 9 s.

## Tests

- **Transaction unit tests** (on `RenameTransaction` alone): a member matching its expected hash is suppressed, one diverging by hand leaves, one changed by a server formatting response stays; a CRLF file matches after normalization; a second rename merges, with the new hashes winning; the moved-away `didClose` keeps membership while a member's unsaved close drops it; a disk change to an unsaved member that differs from its hash drops it, while a match counts as saved; the inactivity deadline starts only at the first save; an unchanged member leaves at commit.
- **Session tests:** the commit runs a downstream tree's batch only after its upstream batches complete, independent trees in parallel; it excludes members from the dependents refresh and is exempt from the bulk threshold; a moved-away file's classes and stamp are removed and the index swaps the type; ordinary reconcile chains its batches the same way.
- **Engine tests:** an MCP rename of more than 50 files leaves the mirror current with no sync; an MCP rename of a public top-level type in a module with dependents moves the file, the mirror holds only the new class, and the dependents compile against it on the first pass; the reactor lock is held across the writes and released after the commit.
- **Service tests (burst counting, as the existing dependents tests do):** applying a rename to N open files then saving them all — as back-to-back saves (VS Code) and as paced saves (Neovim) — produces at most one analysis per member for the edit, with no unresolved-type diagnostics on downstream members; one batch compile per source tree for the saves; one dependents refresh; and exactly one `workspace/semanticTokens/refresh` after the transaction.
- **Invoker:** `LspSmokeTest`'s rename scenario saves every touched file and asserts the mirror is current afterwards.
- **Measurement:** the equalsverifier rename (`EqualsVerifier` ⇄ `EqualsVerifierXXX`) with Neovim's LSP log at `debug`, comparing compile counts, publishes, semantic-token refreshes, member-leave reasons, `:wa` duration, and time-to-quiet against the numbers in Problem.

## Decisions

The first design review raised these issues; each is resolved above:

1. **The deadline cut off think time** → no deadline before the first save; a 10 s inactivity deadline starts there (Ending).
2. **Moved files** → the commit runs the existing orphan cleanup first, then the batches rebuild the index shards (The commit).
3. **Bulk threshold** → members are exempt (The commit).
4. **The batch-analysis trigger was a timer** → it runs when every member is accounted for; a timer only bounds members that never appear (Applying the edit).
5. **Partial application** → unchanged members leave at commit (Ending).
6. **Main and test trees** → batches run per source tree, chained by dependency, main before test (The commit).
7. **Stale mirror window** → accepted (Accepted trade-offs).
8. **Semantic tokens** → held until the commit, confirmed by the measurement (Accepted trade-offs).
9. **Memory** → only a hash per member is kept (The transaction).

The second review found:

10. **Commit ordering was assumed** — reconcile submits upstream-first but runs trees in parallel → batches are chained by dependency, for the commit and for ordinary reconcile (The commit).
11. **The apply-time analysis could not resolve the renamed type downstream** → upstream members join each tree's analysis as extra source inputs (Applying the edit).
12. **Ending on a new rename re-created the storm** → renames merge (The transaction).
13. **The MCP and editor servers race on the same workspace** → the engine holds the reactor lock across write and commit (The MCP process).
14. **Members could be skipped by reconcile forever** → an unexpected disk change drops the member (Saving).
15. **Neovim's file move looked like an unsaved close** → moved-away URIs are recorded and their close is expected (Ending).
16. **The `:wa` speed-up rested on an unconfirmed cause** → slice 0 measures it first (Slices).
17. **Hash matching fails silently at the edges** → line endings are normalized and every member leave is logged with its reason (The transaction).
18. **The member checks spread across many paths** → one `RenameTransaction` object decides; each path makes one call (The transaction).

## Open questions

1. **Diagnostics before the apply-time analysis lands.** For a member opened only to apply the edit, publishing nothing until the analysis completes (a second or two) is preferable to the current clear-then-republish cycle; confirm with the measurement.
2. **Later scope.** Which other server-produced edits should open a transaction once rename is proven.
