# Lathe — Releasing Module Sessions Under Memory Pressure

## Status

Implemented (2026-10-30) and validated on Helidon and Dropwizard; see [Validation](#validation).
Resolves [EG-058](../gaps/gaps-archive.md#eg-058--module-analysis-sessions-are-never-released-a-reactor-wide-search-pins-every-touched-modules-jars-for-the-session--done) (module sessions never released),
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

### Why releasing sessions alone was not enough

Built as first designed, the release did not save the call: the same Helidon call still ran out of memory at 2, 3, 4 and 5 GB.
Two measurements found why.

- **The memory was the checks' analyses, not the sessions.**
  `verify_change` checked each file as an open document: it registered the file and cached its attributed analysis (~6–7 MB).
  Since 3c94561a started every check at once, each module's cache evictions waited behind that module's remaining checks,
  so hundreds of analyses were held at once.
  With one compile slot instead of 16 the call still ran out of memory; with the checks awaited one at a time, as in 0.1.24, it completed at 2 GB.
- **The release never ran.**
  Each check also did its routing, registration and indexing on the event loop, about 1,800 tasks for this call.
  The JVM's notifications arrived (dozens of them), but the release round, posted to the event loop, waited behind those tasks until the server died.
  And with every checked file an open document, every module the call touched counted as having an open file.

So `verify_change` now checks its files without opening them (see [Checking without opening](#checking-without-opening)),
which bounds the call's memory by the compiles in flight and leaves the event loop free for the release.

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

### Checking without opening

`verify_change` checks each file with a transient compile:
the worker compiles the disk content and maps its diagnostics exactly as an open compile does,
but the file is not registered as an open document, not indexed, and its analysis is not kept.
On the event loop a check only routes to the module's worker.
The checks still start side by side, so 3c94561a's speedup stays.
The other tools keep opening the file, as their follow-up queries use its analysis.

### Releasing one session

`CompilationWorker` gets `releaseSession()`.
On its own executor, after anything already queued, it closes the session (its file manager, Lombok's class loader, its temp directory) and forgets it,
as `closeAsync` does for the whole workspace.
The next request rebuilds it through `contextFactory`.
The worker records when its session was last used, and whether a request is queued or running.
A release requested while a request is queued runs after it; a request arriving after the release rebuilds the session.

### Choosing what to release

The event loop owns the choice, as it owns which documents are open.
`WorkspaceModuleRegistry` lists the workers with a live session and their last request;
the workspace session supplies the modules with an open document.
A busy worker's session is released once its queued requests are done,
so a large `verify_change` frees each module's session as soon as that module has been checked.

- **Pressure.**
  At startup the server sets a collection-usage threshold of 70% of the maximum on every heap pool that supports one and has a maximum,
  and listens for the JVM's threshold notification.
  Young pools are nearly empty right after a collection, so in practice the old generation fires;
  no pool is named, so it works on G1, Parallel, Serial and ZGC alike.
  The JVM reports after every collection that ends above the threshold, many times a second under pressure,
  so one round at a time is queued on the event loop and the reports meanwhile are part of it.
  A round releases every session unused for 10 seconds in a module with no open document;
  a session asked for more recently would only be rebuilt at once.
  If the pool is still above its threshold at the next change-scan tick (a field read, no extra wakeup),
  the idle sessions of modules with open documents are released too.
  An `INFO` line for each round that released something (`FINE` otherwise): the trigger, how many sessions, and the old generation's use and maximum.
- **Idle.**
  On the existing change-scan tick, each session unused for 5 minutes, in a module with no open document, is released.
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
  a busy one is released only after its queued requests.
- On a pressure round, sessions of modules without open documents go first.
- The threshold is set on a pool that supports it; a collection report queues a release round, and reports while one is queued join it.
- A transient check finds the same diagnostics as an open compile and keeps no analysis.

### Validation

Helidon, the call above (299 changed files and their callers), MCP server on JDK 27:

| | `-Xmx` | Result |
|---|---|---|
| 0.1.24, checks one at a time | 2 GB | completes in 65 s, 283 sessions left |
| Before, side by side and opened | 2, 3, 4, 5 GB | out of memory; 6 GB completes |
| Side by side, transient checks, sessions released | 2 GB | completes in 27.5 s with the same diagnostics (158 errors, 12 warnings); five release rounds free all 283 sessions; 166 MB live after |

Dropwizard, `verify_change` of `Duration.java` and its callers (70 files in 17 modules): 1.2–1.5 s warm before and after, so the speedup stays.
An editor server idle on Dropwizard uses 0.8–1.1 s of CPU a minute before and after.

The JVM's collection-usage notification is a best-effort signal:
its own documentation does not intend it for recovering from low memory, and on G1 it follows mixed and full collections, not young ones
([JDK-8195115](https://bugs.openjdk.org/browse/JDK-8195115), [JDK-8224030](https://bugs.openjdk.org/browse/JDK-8224030)).
So the bound on `verify_change` comes from the transient checks; releasing sessions under pressure is defence in depth, as Infinispan's `MemoryMonitor` uses it.

## Non-goals

- Soft or weak references to sessions:
  a session owns open jar file systems and a temp directory, which a cleared reference would leak,
  and clearing many at once would turn the next requests cold all together.
- A cap on open-file analyses: [Analysis Cache Bounding](../potential/lathe-analysis-cache-bounding.md).
- Sharing jar indexes between module sessions: the same 189 archives are opened about 1,550 times, so sharing would save more than releasing,
  but it means replacing how javac's file manager opens the class path.
