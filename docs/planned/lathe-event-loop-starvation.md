# ServerEventLoop Starvation from Synchronous Type-Index Builds

## Status

Confirmed bug — blocks `typeHierarchy/supertypes`, `typeHierarchy/subtypes`,
`textDocument/implementation`, and any slow post-open request on large workspaces.
Manifests on Helidon (203 modules, 535 shards total).

---

## Symptom

After opening a file in a large workspace and getting diagnostics, follow-up
requests like `typeHierarchy/supertypes` and `typeHierarchy/subtypes` time out at
the LSP client's default timeout (15 s) even though `prepareTypeHierarchy` itself
returned successfully.

Explorer output:

```
opening MongoDbClient.java ... ok
  diagnostics: 1 warning(s)
  found 'MongoDbClient' at 33:13
  MongoDbClient  [Class]  io.helidon.dbclient.mongodb
  supertypes: TIMEOUT
  supertypes: (none)
  subtypes: TIMEOUT
  subtypes: (none)
  10:28:41.541 FINE    [type-index] built index: 32835 simple names from 203/203 shard(s) + 332 reactor shard(s)
```

The log entry for the type-index build appears at 10:28:41 — well after the client
already gave up.

---

## Root Cause

`WorkspaceTypeIndex.build(...)` is synchronous and runs on the `lathe-worker`
thread (ServerEventLoop) in two places:

### 1. Workspace initialization (`WorkspaceSession.initialize`)

```java
typeIndex = WorkspaceTypeIndex.build(manifest.typeIndexShardPaths(), reactorShards.values());
```

This runs inside a `worker.execute(...)` task.  For Helidon — 203 static shards +
332 reactor shards = 535 shards total — the build takes tens of seconds, blocking
the ServerEventLoop for the entire duration.

All LSP requests that arrive during this time (including `didOpen`) are queued and
cannot be processed until the build finishes.

### 2. Post-save reactor-shard refresh (`WorkspaceSession.refreshReactorShard`)

```java
private void refreshReactorShard(final ModuleSourceConfig config) {
    reactorShards.put(config, scanReactorDir(config));
    typeIndex = WorkspaceTypeIndex.build(manifest.typeIndexShardPaths(), reactorShards.values());
}
```

Called from the `afterCompile` callback (also on ServerEventLoop) after every
`onSave` for a module-tracked file.  Rebuilds the **entire** type index from
scratch, blocking the event loop again after each save.

### Why `prepareTypeHierarchy` succeeds but `supertypes` times out

`prepareTypeHierarchy` is processed in the brief window where the event loop is
free — typically right after diagnostics are published but before the
`refreshReactorShard` task runs (or during initialization, before the index build
catches up to the `didOpen` task).  `supertypes` arrives immediately after but
finds the event loop blocked.

---

## Impact

- `typeHierarchy/supertypes` and `typeHierarchy/subtypes` time out on first use
  after file open in large workspaces.
- `textDocument/implementation` is subject to the same race on large workspaces.
- Any other non-trivial LSP request submitted immediately after diagnostics
  (hover chain, definition, references) may also stall if the refresh lands first.

---

## Fix

### Option A — Incremental index replacement (recommended)

Keep the `WorkspaceTypeIndex` reference in `WorkspaceSession` as an
`AtomicReference<WorkspaceTypeIndex>`.  Build the new index on a dedicated
background thread or a `CompletableFuture.supplyAsync(...)` and swap it in
atomically when done.  Callers read the current reference; they may briefly see a
slightly stale index, which is acceptable.

```java
// WorkspaceSession field
private volatile WorkspaceTypeIndex typeIndex;

// initialization: seed with empty index, then build in background
typeIndex = WorkspaceTypeIndex.empty();
CompletableFuture.runAsync(
    () -> {
        final var built = WorkspaceTypeIndex.build(manifest.typeIndexShardPaths(), reactorShards.values());
        worker.execute(() -> typeIndex = built);
    },
    backgroundPool);

// refreshReactorShard: same pattern
private void refreshReactorShard(final ModuleSourceConfig config) {
    reactorShards.put(config, scanReactorDir(config));
    final var snapshot = Map.copyOf(reactorShards);
    CompletableFuture.runAsync(
        () -> {
            final var built = WorkspaceTypeIndex.build(manifest.typeIndexShardPaths(), snapshot.values());
            worker.execute(() -> typeIndex = built);
        },
        backgroundPool);
}
```

A single-threaded `backgroundPool` (e.g. `Executors.newSingleThreadExecutor`)
serializes index rebuilds and prevents multiple concurrent builds from racing.

### Option B — Build only the changed reactor shard

For `refreshReactorShard`, only rebuild the shard for the changed module and merge
it into the existing index, rather than rebuilding everything from 535 sources.
This requires a mutable/copyable `WorkspaceTypeIndex`.

### Option C — Coalesce rapid rebuilds

Debounce `refreshReactorShard` calls so back-to-back saves trigger only one
rebuild.  Does not fix the initialization stall.

---

## Recommended Approach

Start with **Option A** for initialization (it is straightforward and eliminates
the worst case — a 30+ second stall on server start).  Address `refreshReactorShard`
with a combination of Option A (background build) and Option C (100 ms debounce to
coalesce rapid saves).

Option B is a follow-up optimization once Option A is in place and the correctness
of incremental merge is validated.

---

## Files to Change

| File | Change |
|---|---|
| `WorkspaceSession.java` | make `typeIndex` field `volatile`; extract `buildTypeIndexAsync()` helper; call from `initialize` and `refreshReactorShard`; add background executor |
| `WorkspaceTypeIndex.java` | add `WorkspaceTypeIndex.empty()` factory for the pre-build state |
| `WorkspaceSessionTest` (if it exists) | verify that requests issued during initialization are served without timeout |
