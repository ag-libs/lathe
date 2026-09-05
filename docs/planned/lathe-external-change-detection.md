# Lathe — External-Change Detection → Sync Prompt

Lathe compiles the open file live and otherwise trusts the `.lathe/` mirror that Maven produced at the
last `mvn process-test-classes`. Anything that changes on disk **outside the editor** — a branch
switch, a `git pull`, or an AI agent editing files — leaves the mirror and the reactor type index
silently stale until the next Maven build.

**Decision.** Lathe does **not** recompile externally changed sources in-process, and it **never runs
Maven automatically.** It **detects** the staleness and **prompts** the user, who decides when (and
whether) to sync — reusing the already-shipped sync prompt (WS-3) and the silent post-sync refresh
(WS-4). The in-process recompile idea — correct only for single-module change sets and effectively a
re-implementation of Maven's reactor for multi-module ones — is parked in
[In-Process External-Change Recompilation](../potential/lathe-external-change-recompilation.md); it may
return later as a single-module latency fast path.

**Detection is entirely server-side** — an internal scan, no client file watching. The *only* client
role anywhere in this flow is **running Maven** on the user's confirmation, which is the already-shipped
WS-3 path (the server never runs Maven). Concretely: detection = server; the prompt is raised by the
server and shown by the client; the reaction (Maven) runs on the client.

This document evaluates what Lathe detects today and specifies **what else it must detect, beyond
POM changes, for a good experience.**

**Status: planned — Target: M2 (WS-1 option 1; supersedes WS-5's in-process recompile).**

---

## Current detection (what the code checks today)

- **`WorkspaceWatcher` 2s poll** (`WorkspaceSession.checkForChanges`) checks exactly two things:
  - `workspace.json` — mtime, then content: changed content → full `reload()` (`WORKSPACE_CHANGED`);
    unchanged content but bumped mtime → silent `REACTOR_REFRESH` (WS-4).
  - **POM fingerprints** (mtime + size) → the Maven **sync prompt** (WS-3), shown once per change.
- **`LatheWorkspaceService.didChangeWatchedFiles`** is now an empty **no-op**: client-driven watching
  is not used, Lathe registers no file watchers (`client/registerCapability`), and Neovim only emits
  the notification for globs a server registers — so it never fires. The former `Deleted`-only handler
  and its `didDeleteWatchedFile → onDeletedFile` chain have been **removed** as dead code (the method
  survives only as the empty stub `WorkspaceService` requires). Detection is server-only (see
  *Detection mechanism*).
- **Resources** are copied into `.lathe/` only when the **editor saves** them (a `BufWritePost` autocmd
  → `lathe.resource.refresh` → `refreshResource`). An external resource change is missed.

**The gap:** nothing compares **source-root or resource-root contents** against the last sync. POM and
structural changes are caught; ordinary source and resource edits made outside the editor (or while
Lathe was down) are invisible until the user happens to run Maven. This is the [WS-1](../gaps/gaps.md)
root cause.

## The "last sync" marker (free, already maintained)

WS-4 made `WorkspaceManifestWriter` **bump `workspace.json`'s mtime on every sync**, even when the
content is unchanged. So `workspace.json`'s mtime is a reliable *"time of last sync"* marker, and the
detection key is simply:

> a tracked file whose mtime is **newer than `workspace.json`** has changed since the last sync.

No new bookkeeping, no per-file state to persist — reuse the marker WS-4 already guarantees.

## What to detect, beyond POM — and the reaction for each

The reaction splits cleanly: **sources/structural → prompt** (Maven rebuilds correctly, any module
count); **resources → auto-copy** (a copy is not compilation, it is cheap and already implemented).

| # | Signal | Detection | Reaction |
|---|---|---|---|
| 1 | A tracked `.java` **modified/created** since last sync (under a source root, excluding `originalGenSourcesDir()`) | mtime > `workspace.json` mtime | **Sync prompt** (WS-3) |
| 2 | A tracked `.java` **deleted** since last sync (orphan `.class` with no source) | presence check (mtime can't see a deletion) | **Sync prompt** — lower priority; deferable |
| 3 | A **new module** (a `pom.xml` / module dir not in the manifest) | manifest membership check | **Sync + capture** (new modules usually add tests) |
| 4 | A **test source** changed / a **new test module** | as #1/#3, but under a test source root | **Sync + capture** — `test-launch.json` is captured from a real `mvn test`, not derived by `lathe:sync` |
| 5 | An **external resource** change (under a resource root, not via editor save) | mtime > destination mtime | **Auto-copy** via `refreshResource` — no Maven, no prompt |
| 6 | A **bulk change / branch switch** (many files at once) | count over a threshold | **Sync prompt** (offer capture); log the count (no silent cap) |

POM/dependency/module-structure changes (already detected) stay on the same **sync prompt**.

Rationale for the source-root exclusion in #1: Maven lists the annotation-processor output
(`target/generated-sources/annotations`) *as* a source root, and `ModuleSourceConfig` tracks it
separately as `originalGenSourcesDir()`; excluding it avoids treating regenerated output as a
user change.

## Detection mechanism — a single internal scan

One server-side mechanism covers **both** startup and in-session detection: a source-root **mtime
scan**, run once after `loadWorkspace` and then on the existing `WorkspaceWatcher` worker tick. There
is **no client-side file watching** — this is entirely internal to the server.

Each pass walks the tracked source roots (excluding `originalGenSourcesDir()`), stats each `.java`, and
raises the sync prompt if any file's mtime is newer than `workspace.json` (the "last sync" marker
above). Resources use the same walk over resource roots, reacting with a `refreshResource` copy rather
than a prompt.

**Newly added files are covered — by re-enumeration, not a baseline.** Because each pass re-lists the
source roots rather than diffing against a remembered file list, a **created** `.java` in an existing
root is caught automatically: it simply appears with a mtime newer than the marker (table item #1). No
per-file state is kept. Two cases fall outside this scan by construction and are handled by their own
signals: a **deletion** leaves nothing to stat (item #2's presence check), and a file in a **new source
root or new module** is not under any root the scan knows from the last sync (item #3's manifest-
membership check → Sync + capture, since a new root normally coincides with a POM/build change).

**It is cheap enough to run periodically — measured, not assumed.** A throwaway spike on a mid-size
private reactor (~21 modules, 44 source roots, ~2,500 `.java`) timed a full file-level scan at
**~10 ms** per pass (warm inode cache; a directory-mtime-only variant was ~5 ms). At ~10 ms even the
current 2 s tick is a ~0.5% worker duty cycle; a longer sub-interval (5–10 s) leaves more headroom and
is still responsive for "you changed files outside the editor." Cost is `O(source files)`, so a much
larger reactor scales to tens of ms — still fine on a throttled tick, with the directory-mtime pass as
a cheap coarse pre-filter if it ever matters.

This is deliberately **not** the whole-`.lathe/`-tree walk the
[Lightweight Watcher](lathe-lightweight-watcher.md) rejects: it stats only source files under the
tracked roots, and the measurement above confirms the cost. Because one internal scan suffices, the
client-side `didChangeWatchedFiles` watch — and its Neovim / inotify / gitignore machinery — is **not
pursued**; the prototyped client-capability override (`M.watch_capabilities` in `lathe.lua`) has been
reverted. The archived watch analysis remains in the parked
[recompilation doc](../potential/lathe-external-change-recompilation.md) §1 should an event-driven path
ever be revisited.

**Cleanup — done.** The client-watch-only `didChangeWatchedFiles → didDeleteWatchedFile →
WorkspaceSession.onDeletedFile` chain has been **removed** as dead code (`didChangeWatchedFiles` is now
an empty stub the interface requires; `didDeleteWatchedFile` and `onDeletedFile` are deleted). On-disk
**deletions** are covered instead by the scan's presence check (item #2 → prompt), not by an in-process
eviction.

## Reaction — reuse shipped machinery, add nothing

- **Sources / structural / bulk → `WorkspaceSession.promptForSync` (WS-3):** the actionable, once-per-
  change prompt with *Sync* / *Sync + capture tests* / *Later*. The **client** runs Maven; the server
  never does. WS-4 then refreshes the reactor index silently. Correct for 1 module or 10.
- **Resources → `refreshResource`:** the existing atomic copy into `.lathe/`.
- **No in-process compilation** — parked (see potential doc).

## Prompt UX — keep it non-intrusive

WS-2 originally rejected a source-change prompt as "intrusive and non-binding." That objection is
weaker than the alternative (a silent stale state, or a single-module-only auto-recompile that fails on
real multi-module edits), but it must still be respected:

- **Once per change.** WS-3 already dedupes (`acknowledgePoms` / `pomNotificationPending`); the source
  path must do the same — a prompt fires once when staleness is first detected, not every 2s and not
  every keystroke.
- **At startup, at most one prompt** summarising that sources changed since the last sync.
- **Actionable, not nagging.** The prompt *runs Maven for you* on a click and stays quiet until the
  next change (even while Maven runs).

## Open questions

- **Resource auto-copy vs prompt** — recommend auto-copy (cheap, correct, no Maven); confirm there is
  no case where a resource change needs a full rebuild.
- **Deleted-source detection (#2)** — worth a presence check, or defer until users hit phantom types?
- **Scan interval.** Reuse the existing 2 s `WorkspaceWatcher` tick for the source scan (~10 ms/pass,
  measured), or throttle it to a longer sub-interval (5–10 s) to cut the duty cycle further? Startup
  always scans once regardless.
- **Capture heuristic (#3/#4)** — auto-recommend "Sync + capture" when a new test module or changed
  test source is detected, vs always offering both (WS-3 currently always offers both).

## Related gaps and designs

- [WS-1](../gaps/gaps.md) — the freshness umbrella; this is its **option 1** (detect source staleness →
  advisory prompt, no invalidation).
- WS-2 — the deferred source-only re-sync prompt; **revived** here (the multi-module reality makes the
  prompt the pragmatic choice over auto-recompile).
- WS-3 — the shipped, actionable, non-looping Maven sync prompt this reuses.
- WS-4 — the shipped silent post-sync reactor-index refresh.
- WS-5 — the in-process recompile gap; its reaction is **parked** (see below) in favour of this.
- WS-6 — in-session refresh of open files in *dependent* modules (the open-file cousin; separate).
- [In-Process External-Change Recompilation](../potential/lathe-external-change-recompilation.md) —
  the parked compile-in-process design; the (now not pursued) live-watch / gitignore / Neovim analysis
  is archived there, superseded by the single internal scan above.
- [Lightweight Watcher](lathe-lightweight-watcher.md) — why continuous source polling is avoided.
