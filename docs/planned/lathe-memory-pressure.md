# Lathe — Releasing Module Sessions Under Memory Pressure

## Status

Accepted (2026-10-30), not implemented; measured on Helidon.
Resolves [EG-058](../gaps/gaps.md) (module sessions never released),
and the pre-release finding that a large `verify_change` ends the server with `OutOfMemoryError` on a 2–3 GB heap.
Related: [Analysis Cache Bounding](../potential/lathe-analysis-cache-bounding.md), a deferred hard cap on per-open-file analyses.

## Problem

The server keeps three kinds of memory:

| | What | Size | Lifetime |
|---|---|---|---|
| 1 | Open-file analyses: each module session's cache of attributed analyses, one javac context each | ~6–7 MB a file, up to 100 files | until the file closes or the LRU evicts it |
| 2 | Module sessions: each touched module's `SourceAnalysisSession`, with a file manager holding a zip index per classpath jar | ~4–7 MB a session; 1.2–2 GB after a large `verify_change` on Helidon | never released |
| 3 | In-flight compiles: `verify_change` checks its files side by side, up to one compile per core | one module-sized javac working set each | for the call |

`CompilationWorker` creates its module's session on first use and closes it only when the workspace closes.
A reference search or a `verify_change` touches every candidate module, so each one leaves a session behind.
On Helidon a `verify_change` after a branch switch checks about 1,800 files in 178 modules:
every module keeps a session for the rest of the run, while up to 16 compiles run beside them.
At `-Xmx2g` and `-Xmx3g` the server runs out of memory and exits (any `Error` is fatal until GA).
0.1.24, which checked files one at a time, completes the same check at 2 GB.
The launchers set no `-Xmx`, so the JVM takes a quarter of RAM: 2 GB on an 8 GB laptop.

## Measurements

Helidon (2026-10-30): an MCP server on JDK 27 at `-Xmx6g` runs a `verify_change` of the 299 Java files that differ from `HEAD~60` (sources only) and of their callers.
Live heap comes from a class histogram after a full GC,
taken again with `-XX:SoftRefLRUPolicyMSPerMB=0` so that soft references do not count.

| | Value |
|---|---|
| Idle server after startup | ~230 MB live, 58 module sessions |
| After the call | 283 module sessions (main and test trees); the same with 16 compile slots or 1 |
| Strongly retained after the call | ~1.2–2 GB, varying between identical runs with background work in flight |
| Of which javac's own objects | ~65 MB |
| Of which jar indexes | nearly all the rest: the zip central directories (`byte[]`) and index nodes of ~1,550–1,950 zip file systems over 189 archives (1,544 open descriptors; the JDK's `ct.sym` alone 100 times) |
| Per session | ~4–7 MB |
| 0.1.24, same call | the same sessions, archives and retained heap, within the run-to-run spread |

So the sessions a large check leaves behind take most of a 2 GB heap, whether the files are checked one at a time or side by side.
0.1.24 fits because its single compile in flight adds little on top; up to 16 compiles do not fit.
Releasing idle sessions under pressure frees that part in the middle of the call.
Whether that alone keeps the call alive at 2 GB is checked on the same call once it is built (see [Validation](#validation)).

## Approach

A session holds only rebuildable state:
the open-file analyses (a miss recompiles, as the LRU eviction path does today),
and each file's last clean parse (refilled by its next analysis).
So a session can be released at any time between requests.
The next request into the module rebuilds it, at the cost of a cold start (0.2–1.6 s, EG-058) and a recompile of that module's open files.

Two triggers release sessions:

- **Under memory pressure.**
  The JVM reports when a heap pool is still above a threshold right after a garbage collection
  (the `MemoryPoolMXBean` collection-usage threshold, supported by G1, Parallel, Serial and ZGC).
  The notification costs nothing until it fires, and fires only when memory is really tight.
- **When idle.**
  A session unused for 5 minutes, in a module with no open document, is released.
  This brings the footprint back down after a reactor-wide search or a `verify_change` without waiting for pressure,
  as EG-058's fix direction proposed.

No cap on the number of sessions:
a search or `verify_change` touches every module it may concern, so a cap below the reactor's module count would make each one cold.

## Design

### Releasing one session

`CompilationWorker` gets `releaseSession()`.
On its own executor, after anything already queued, it closes the session (its file manager, Lombok's class loader, its temp directory) and forgets it,
as `closeAsync` does for the whole workspace.
The next request rebuilds it through `contextFactory`.
The worker records when its session was last used, and whether a request is queued or running.
A release requested while a request is queued runs after it; a request arriving after the release rebuilds the session.

### Choosing what to release

The event loop owns the choice, as it owns which documents are open.
`WorkspaceModuleRegistry` lists the workers with a live session, their last use, and whether they are busy;
the workspace session supplies the modules with an open document.
A busy worker's session is never chosen.

- **Pressure.**
  At startup the server sets a collection-usage threshold of 70% of the maximum on every heap pool that supports one and has a maximum,
  and listens for the JVM's threshold notification.
  Young pools are nearly empty right after a collection, so in practice the old generation fires;
  no pool is named, so it works on G1, Parallel, Serial and ZGC alike.
  On the notification, every idle session of a module with no open document is released.
  If the pool is still above its threshold at the next change-scan tick (a field read, no extra wakeup),
  the idle sessions of modules with open documents are released too.
  One `INFO` line per release round: the trigger, how many sessions, and the old generation's use and maximum.
- **Idle.**
  On the existing change-scan tick, each idle session older than 5 minutes, in a module with no open document, is released (`FINE`).
  The tick only compares timestamps, so an idle editor wakes no more than today.

### Threading

Session state belongs to its module's worker, whose single-threaded executor already serializes every use of the session;
a release runs there, exactly as closing the workspace does today.
The threshold notification arrives on a JMX thread and only posts to the event loop.

### Module descriptor

`lathe-server` reads `java.management` for the memory pool beans: one `requires` line, a JDK module, no new dependency.

### Tests

- A worker whose session is released answers its next request from a new session, with the same result.
- An idle session of a module with no open document is released after the idle time; one with an open document is not;
  a busy one is released only after its request.
- On a pressure round, sessions of modules without open documents go first.
- The threshold is set on a pool that supports it, and a notification posts a release round.

### Validation

The Helidon call above at `-Xmx2g` and `-Xmx3g`, where the server ran out of memory before:
it must complete, with the release round in the log.
If it does not, the number of compiles in flight follows the heap as well
(one per core, at most the heap divided by a measured per-compile budget).
Dropwizard's `verify_change` timing and the editor's idle CPU are measured before and after, to show neither gets worse.

## Non-goals

- Soft or weak references to sessions:
  a session owns open jar file systems and a temp directory, which a cleared reference would leak,
  and clearing many at once would turn the next requests cold all together.
- A cap on open-file analyses: [Analysis Cache Bounding](../potential/lathe-analysis-cache-bounding.md).
- Sharing jar indexes between module sessions: the same 189 archives are opened about 1,550 times, so sharing would save more than releasing,
  but it means replacing how javac's file manager opens the class path.
