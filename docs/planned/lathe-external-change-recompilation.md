# Lathe — External-Change Detection and Recompilation

This document describes how Lathe keeps its `.lathe/` mirror and in-memory indices fresh when Java
sources or resources change **on disk from outside the editor** — a branch switch, a `git pull`, or,
increasingly, an AI agent editing files directly — without a Maven round trip.

It is the concrete plan for the cheapest slice of the [WS-1](../gaps/gaps.md) freshness umbrella and
builds on the existing save-time compile pipeline
([lathe-reactor-type-index.md](lathe-reactor-type-index.md)) and the resource-copy path already in the
server.

**Status: planned — Target: M2 (owned by WS-5).**

---

## Problem

Lathe compiles the currently open file live and otherwise trusts the `.lathe/` mirror that Maven
produced at the last `mvn process-test-classes`.
Any change that lands on disk without going through the editor's save path is invisible until the next
Maven build:

- A file an agent (or a `git` operation) creates or edits is **not** reflected in navigation,
  completion, `workspace/symbol`, or dependents' diagnostics.
- The server already accepts `workspace/didChangeWatchedFiles`, but
  [`LatheWorkspaceService.didChangeWatchedFiles`](../../lathe-server/src/main/java/io/github/aglibs/lathe/server/LatheWorkspaceService.java)
  acts **only** on `Deleted` events; `Created` and `Changed` are dropped.
- Resource edits are only carried into `.lathe/` when the editor saves them (a `BufWritePost` autocmd
  forwards non-Java saves to the `lathe.resource.refresh` command); an external resource change is
  missed.

The expectation, stated plainly: if a tool edits a `.java` file or a resource in the workspace, Lathe
should notice and recompile/copy just that file, no Maven, no prompt.

## Goal

Detect external changes to tracked Java sources and resources and react by reusing the machinery Lathe
already has:

- **D1 — Detection.** Extend `workspace/didChangeWatchedFiles` to `Created`/`Changed` and register the
  server to watch `**/*.java` and resource roots, so the client's file watcher pushes disk events.
- **R1 — Java reaction.** Treat an externally changed `.java` file like a save: a `FULL` compile from
  disk into the mirror (annotation processors run), then a reactor-shard refresh.
- **Resources.** Route an externally changed resource through the existing `refreshResource` copy.

The light regime (per-file source/resource changes) is handled without Maven; the heavy regime (POM,
dependency, or module-structure changes) continues to route to the Maven sync prompt.

## Non-Goals

- Live **cross-module** recompilation. A change in module A that breaks module B is not live-compiled
  here; that stays bounded by Maven, consistent with
  [lathe-sibling-recompilation.md](lathe-sibling-recompilation.md).
- Blindly recompiling a whole module or the whole reactor on every change.
- Replacing Maven as the source of truth for classpaths, JPMS, module structure, or dependency
  resolution — POM/structural changes remain the heavy path (see WS-3/WS-5 prompt).
- A fully event-driven server-side `WatchService`. Detection is client-driven via LSP file watching
  (see Alternatives).

## Design

### 1. Detection (D1)

The server registers dynamic file watchers via `client/registerCapability` for
`workspace/didChangeWatchedFiles` with two glob groups:

- `**/*.java` under the tracked source roots.
- The tracked **resource roots** (from the same `ResourceRootData` `lathe:sync` already captures and
  that `ResourceRootIndex` maps).

`LatheWorkspaceService.didChangeWatchedFiles` stops filtering to `Deleted` only and dispatches every
event to the session by `(uri, FileChangeType)`:

| `FileChangeType` | Java source | Resource |
|---|---|---|
| `Created` / `Changed` | `onExternalChange(uri)` — FULL compile (R1) | `refreshResource(uri)` — copy |
| `Deleted` | existing deletion path (remove `.class`, refresh shard) | existing no-op / cleanup |

`pom.xml` changes continue to be handled by the existing `WorkspaceWatcher` POM path and surface as
the Maven sync prompt; they are **not** auto-recompiled here.

### 2. Java reaction (R1)

`WorkspaceSession.onExternalChange(uri)`:

1. **Skip open files.** If the URI is an open document, ignore the disk event — the editor buffer is
   authoritative, and its own save path already keeps the mirror fresh. This avoids compiling a
   half-written external copy over the user's unsaved edits.
2. **Route.** Resolve the module via `routeCompiler`. Only a `Module` route recompiles; `External` and
   `Missing` routes are ignored.
3. **Compile from disk.** Read the file content and submit a `FULL` compile — the same path `onSave`
   uses. `CompileMode.FULL` runs annotation processors (no `-proc:none`) and writes `.class` into
   `.lathe/<module>/classes`, so `@Builder`-style generated types are reproduced. The existing
   `afterModuleSave` follow-up (`refreshReactorShard`, dependent open-file rescheduling) then runs
   unchanged.
4. **Failure is inert.** A broken mid-edit file (an agent wrote a partial change) fails the compile;
   the previous mirror is kept and no diagnostics are published for the non-open file.

### 3. Resources

`refreshResource(uri)` already maps a file to its `.lathe/` destination through `ResourceRootIndex` /
`manifest.resourceDestination` and copies it (no-op if the file is under no resource root). D1 simply
drives this on `Created`/`Changed` events instead of relying on the editor `BufWritePost` autocmd. The
autocmd — whose own comment notes "`workspace/didChangeWatchedFiles` can drive the same server command
later" — becomes redundant for watched paths and is removed to avoid a double fire; the
`lathe.resource.refresh` command stays for manual/other-client use.

### 4. Debounce, coalescing, and the bulk cutoff

Agents write many files in quick bursts. Events are coalesced per module over a short debounce window
before compiling, protecting the editing-latency budget and collapsing a multi-file write into one
compile pass per affected file. Reuse the existing worker scheduling; no new executor.

**Bulk cutoff → defer to the heavy path.** A `git pull` or branch switch can change hundreds of files
at once; recompiling each one in-process would storm the compiler and thrash the mirror. So above a
threshold of changed files in a debounce window — or when the same batch includes a `pom.xml` change —
the light regime **stops** and defers to the heavy-path Maven sync prompt (WS-3) instead of per-file
recompiling. The light regime therefore owns *small* source/resource change sets (the agent-edit and
few-file-pull cases); large or structural change sets are the prompt's job, with WS-4 picking up the
result. The exact threshold is tunable and should be logged when it trips (no silent cap).

## Correctness and boundaries

- **Annotation processing is covered** because the reaction reuses the `FULL`/save compile, which runs
  processors and writes generated sources to the module's `generated-sources` output — the same as a
  save. Multi-file AP aggregation (a processor that reads several sources at once) is the one case a
  single-file compile can diverge from Maven; those changes still converge at the next `mvn`.
- **Cross-module staleness** remains: a changed public API in module A does not live-recompile module
  B. In-module dependents can later be wired via [Sibling Recompilation](lathe-sibling-recompilation.md);
  cross-module stays Maven-bounded.
- **Write contention.** The server and Maven both write `.lathe/<module>/classes`. Writes are atomic
  and a subsequent `mvn` overwrites; a server compile racing a live Maven build is a known, tolerated
  edge (documented, not locked in this slice).
- **Heavy path unchanged.** POM/dependency/module-structure changes route to the Maven sync prompt
  (WS-3/WS-5), never to `onExternalChange`.

## Heavy-path companion (WS-3)

Structural changes — `pom.xml`, dependencies, module add/remove — are **not** handled here; they route
to the Maven sync prompt, which is a separate mechanism by design:

- **Detection stays on the server poll.** `pom.xml` and `workspace.json` are a small, bounded,
  server-authoritative set, kept in `WorkspaceWatcher` (the [lightweight-watcher](lathe-lightweight-watcher.md)
  target of `O(modules)`). They are deliberately **not** folded into `workspace/didChangeWatchedFiles`,
  which exists for the huge source/resource set. Two mechanisms, clean split.
- **Actionable prompt, client runs Maven.** On a change, the server shows the prompt and, on a chosen
  action, sends a custom `lathe/sync` notification; the **client** runs Maven as a job (the server
  never runs Maven), and the refreshed `.lathe/` is picked up via WS-4. The prompt shows once per
  change (WS-3 loop fix).
- **Two actions.** "Sync" → `mvn process-test-classes` (types/mirror/main-launch + manifest); "Sync +
  capture tests" → `mvn test` (also re-captures `test-launch.json`, which is *captured from a test
  fork*, not derived by `lathe:sync`, so a new/changed test module needs a real test run). Both offered
  on every change for now; auto-recommending capture on new-module detection is deferred (Slice 2).

Full detail lives on WS-3 in the [gap registry](../gaps/gaps.md).

## Alternatives considered

- **Server-side `WatchService`** (detection): client-independent, but non-recursive on Linux, prone to
  event loss, and reintroduces the large-tree cost [lathe-lightweight-watcher.md](lathe-lightweight-watcher.md)
  exists to avoid. Rejected in favour of client LSP watching.
- **Poll source mtimes** (detection): simple but O(N) walks that do not scale to large reactors.
  Rejected as a primary; usable only as a degraded fallback.
- **Invalidate + lazy recompile** (reaction): recompile on first feature that touches the file.
  Deferred — R1's eager compile is simpler and reuses the save path; laziness can be a later
  optimization if agent write-storms prove costly despite debouncing.

## Implementation sketch

- `LatheLanguageServer` — register `**/*.java` and resource-root watchers on `initialized`.
- `LatheWorkspaceService.didChangeWatchedFiles` — dispatch `Created`/`Changed`/`Deleted` by file type.
- `WorkspaceSession` — `onExternalChange(uri)` (open-file skip, route, FULL compile reusing the
  `onSave` path) and the per-module debounce; resource events call the existing `refreshResource`.
- `lathe.lua` — confirm `didChangeWatchedFiles.dynamicRegistration` is advertised; remove the now
  redundant resource `BufWritePost` autocmd.

No public API change and no new abstraction — the reaction reuses `onSave` / `afterModuleSave` /
`refreshReactorShard` and `refreshResource`.

## Testing and verification

- `LatheWorkspaceServiceTest` — `Created`/`Changed` for a `.java` file routes to `onExternalChange`; a
  resource routes to `refreshResource`; `Deleted` unchanged.
- `WorkspaceSessionTest` — an external `Changed` on a closed file updates the mirror and type index
  (an added `@Builder` type appears in `workspace/symbol`); an event for an **open** file is ignored;
  burst events debounce into per-file compiles; an external resource change lands in `.lathe/`.
- End-to-end probe against the `multi-module` invoker workspace (the method used to verify FR-015 and
  the save→`\ws` path): edit a **closed** `.java` on disk, deliver a `didChangeWatchedFiles`, then
  `sym`/`refs` to confirm the index reflects it; repeat for a resource.

## Related gaps and designs

- [WS-1](../gaps/gaps.md) — the freshness/invalidation umbrella; this doc is its cheapest concrete
  slice (source watching + recompile).
- WS-5 — the M2 gap that owns this design.
- WS-2 — the deferred source-only re-sync **prompt**; superseded for the light regime by auto-recompile
  (no prompt needed for a single-file source edit).
- WS-3 — the Maven sync **prompt** loop/actionability fix; the heavy-path companion for
  POM/structural changes.
- WS-4 — `workspace/symbol` staleness after an external `mvn`; the post-Maven pickup companion.
- [Sibling Recompilation](lathe-sibling-recompilation.md) — in-module dependents after an API change.
- [Reactor Type Index](lathe-reactor-type-index.md) — the index this refresh keeps current.
- [Lightweight Watcher](lathe-lightweight-watcher.md) — the `.lathe/`-poll redesign; orthogonal, but
  the reason detection is client-driven rather than a server tree-walk.
