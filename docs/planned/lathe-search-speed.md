# Search Speed on Large Codebases

**Status: IN PROGRESS — (0) fixed in #157; (e) and (d) in #158; (b) tried and shelved (below); an exposure graph from class files and (c) are post-release candidates.**

Gaps: FR-029 (references to a method whose name many files spell take 12–28 s),
FR-032 (implementations of an interface method and incoming calls take seconds),
and the worst case EG-090 left behind (incoming calls of an override of Helidon's `Builder.build()`, 12 s cold).

## Problem

Every search that has to attribute code (references, implementations of a method, incoming calls) compiles the
candidate files on the module's single worker thread.
Three costs multiply:

1. **How many files are compiled.** The planner compiles every file spelling the name for an instance method;
   `getId` is spelled in 4,239 Hibernate ORM files, `of` in 1,174 Fineract files.
2. **What one compile costs.** Each javac task loads the classes the file needs afresh (20–30% of a batch in the
   2026-11-05 profile) before attributing it (about 35%).
   References compile 8 files per task; implementations and incoming calls compile **one file per task**.
3. **How much runs at once.** A module's tasks run one after another on its worker; on Hibernate ORM,
   `hibernate-core`'s main sources took about 9.8 s of CPU per query while most of 16 hardware threads idled.

## Measurement (2026-11-09, main at 784e4615)

Method: the benchmark's query positions (`dev/lsp-bench/cases`), one fresh server per query on the project's
synced `.lathe/`, `-Xmx4g`, one cold request then 5 warm ones, sequentially on an otherwise idle machine
(load average recorded per run).

| Query | Cold | Warm median | Answer | Benchmark 2026-11-08 (warm) |
|---|---|---|---|---|
| Hibernate ORM references `EntityAction.getId` | 38.7 s | 32.0 s | 47 | 27.7 s |
| Fineract references `Money.of(CurrencyData, …)` | 53.1 s | 28.7 s | 125 | 21.8 s |
| Fineract references `Money.of(MonetaryCurrency, …)` | 44.4 s | 27.4 s | 441 | 22.3 s |
| Fineract implementations `processCommand` | 9.2 s | 4.9 s | 461 | 3.8 s |
| Fineract incoming `parameter` | 14.0 s | 5.8 s | 584 callers | 4.4 s |
| OpenMetadata incoming `getEntityRepository` | 20.7 s | 14.4 s | 874 callers | 13.5 s |
| Helidon incoming `WebClientMetrics.Builder.build()` | 13.6 s | 6.0 s | 3 | — |

The load average was 3–8 (16 hardware threads) during these runs; the benchmark's rows were measured on an idle
machine, so part of the difference may be load.

### Where the time goes (JFR, same queries)

**Startup, before any query: a regression.** `ReactorTypeIndex.replaceShard` merges each type's `Declaration` into
the list of each of its direct supertypes with `Stream.concat(...).distinct()`, hashing every declaration already
there; a `Declaration` hashes its `ModuleSourceConfig`, a record holding the module's whole classpath, module path and
source lists. A common parent (`java.lang.Object`) gathers tens of thousands of subtypes, so the merge is quadratic,
each step hashing whole classpaths. It runs on the event loop, which nothing else reaches until it ends:
**about 260 s on Hibernate ORM, 33 s on Fineract, 32 s on OpenMetadata** (two thirds of all of Hibernate's samples).
It came with `a65e91f9` (#142, 2026-11-08), after the benchmark's runs.

**The queries:**

| Profile (query phase) | Class reading | Attribution | `ct.sym` / zip | Other javac | The busiest thread |
|---|---|---|---|---|---|
| Hibernate references `getId` | 43% | 28% | 11% | about 10% | `hibernate-core` tests: 67% of samples |
| Fineract references `Money.of` | 38% | 24% | — | about 26% | spread over 5 modules |
| OpenMetadata incoming `getEntityRepository` | 57% | 7% | — | about 27% | `openmetadata-service`: two thirds |

Class reading (each javac task loading the classes its files need again) is the largest cost, and largest where
each file is its own task (incoming calls); one module's worker carries most of a query.

## Levers

### (0) Fix the startup merge (regression, not a design question)

Keep each name's declarations without re-hashing the list on every merge (append, deduplicated by a cheap key:
the owner's identity and the binary name), and let `Declaration` compare its owner by identity or by a small key
instead of the whole `ModuleSourceConfig`. Size: S. Its own PR, first: it blocks every request on large reactors
for up to minutes after a start or reload.

### (e) Larger batches — done: 32

References compiled 8 files per javac task; class reading is paid per task, so a larger batch shares it across more
files, at the cost of memory and cancellation latency per batch.
Measured 2026-11-10 (warm median of 3, `-Xmx4g`; the machine was partly loaded, load average 2–14):

| Query | 8 | 16 | 32 | 64 |
|---|---|---|---|---|
| Hibernate ORM references `getId` | 29.1 s | 23.3 s | 17.2 s | 16.4 s |
| Fineract references `Money.of(CurrencyData, …)` | 26.8 s | 24.9 s | 22.3 s | 19.8 s |
| Fineract references `Money.of(MonetaryCurrency, …)` | 25.8 s | 21.1 s | 21.2 s | 19.3 s |

At `-Xmx2g` batch 32 completed every query at the same peak resident memory as 8 (within about 100 MB):
Hibernate ORM 2,424 → 2,520 MB, Fineract 3,226 → 3,171 MB and 3,288 → 3,217 MB. 32 keeps nearly all of 64's gain at
half the batch; `SEARCH_BATCH_SIZE` is 32.

### (d) Batch implementations and incoming calls as references are — done

`ReferenceSearch.fileQueryFuture` (implementations) and `incomingCallFutures` (incoming calls) attribute each
candidate file in its own javac task (`session.transientAnalysis`), while references attribute 8 files per task
(`searchBatch`, `session.transientAnalyses`). Class loading is paid per task, so these two pay it per file.

- Change: run both through the same batch path, `REFERENCE_BATCH_SIZE` files per task, the locator applied to each
  analysis of the batch; open documents keep their cached analysis.
- Contract: unchanged (still the module's worker); cancellation and progress per batch, as references.
- Measured with a prototype (warm median of 3): Fineract incoming `parameter` 6.2 → 4.2 (batch 8) → 3.7 s (batch
  32); OpenMetadata incoming `getEntityRepository` 14.5 → 8.8 → 7.5 s; Helidon incoming `Builder.build()` 6.3 →
  4.2 → 4.1 s; the same answers.
- Size: S. Risk: low, the path exists.

### (b) Follow exposures transitively, for instance and static methods

Static methods already search in two phases (`ReferenceCandidatePlanner.planStaticMethod`, `FamilyExposure`):
first the files naming the method's family (its declaring type and subtypes) or importing it statically, then the
rest of the files spelling the name, but only if a file of the first phase exposes a family type to files that
never name it (a non-family type's non-private member, supertype or type parameter handing out a family value).
Instance methods use one phase: every file spelling the name.

Today the second phase is all or nothing: a single exposure anywhere compiles every deferred file. A widely used
type is almost always exposed somewhere (a getter of another class returning it), so the narrowing rarely pays;
Fineract's `Money.of`, a static method, still compiles every file spelling `of`.

The context says more than "exposed somewhere": it says **by which types**. A file that never names the family
reaches a family value only through a member of an exposing type, so it names that type, or a subtype of it
(inheriting the member), or reaches it through another exposure in turn. So:

1. Phase 1: the files naming the family (as today), checked for exposures.
2. Each following phase: the files spelling the method's name that name an exposing type found so far, or a
   subtype of one, and were not searched yet; checked for exposures in turn.
3. Stop when a phase finds no new exposing type. Files never reached are not compiled.

- Instance methods: the family is the method's declaring type and its subtypes, plus the supertypes declaring a
  method it overrides (a call through them is a reference too).
- Static methods: the same closure replaces today's all-or-nothing second phase.
- Expected: the number of files compiled for `getId` and `Money.of`, measured before deciding (a planner-only
  count first: how far the closure grows on Hibernate ORM and Fineract before anything is compiled).
- Size: M. Risk: a value reaching a file without any declaration in the reactor naming its type; the exposure
  check already covers members, supertypes, type parameters and lambda parameters, and javac's model includes
  Lombok's and records' generated members. A library type cannot expose a reactor type. Every phase's exposure
  check must keep that guarantee, and the existing exposure tests extend to the closure.

**Outcome (2026-11-10): shelved.** A prototype checking, in each round, only the deferred files that spell the
method's name gave identical answers on the benchmark's 18 method reference queries and cut Hibernate ORM's
`EntityAction.getId` from 20.1 s to 1.7 s warm, Fineract's `Money.of` from about 24 s to 12.9 s and `parameter` from
8.6 s to 4.1 s. But it is incomplete: a chain of exposures can pass through a file that never spells the name
(`Outer` hands out the `Holder` that hands out the family), and a test of that shape found the call missed. The
correct version checks every file naming an exposing type and took `getId` to 71.5 s, slower than main, as the
exposing types are named in thousands of files. The work is kept on the unmerged branch `perf/exposure-closure`.

**Post-release candidate: an exposure graph from class files.** Whether a type hands out a family value is visible in
its class file (supertypes, non-private members' signatures, generated members included), and the type index already
reads every reactor class file. Recording, per reactor type, the reactor types its non-private signatures mention
would make the closure a graph walk that compiles nothing; only the files spelling the name and naming a type in the
closure would be attributed. Drawbacks: class files lag edited sources (unsaved buffers, changes outside the editor,
an unsynced project), so those files must always be searched; the exposure rule would exist twice (source model and
class files) unless the graph replaces the source check; and `lathe-core`'s index entries and shard format change.

### (a) Compile a module's batches in parallel — rejected

Ruled out (2026-11-10): the javac file manager a module's compiles share is not thread-safe, and the module
worker's threading contract keeps one thread per module. The levers below make each task cheaper and fewer.

### (c) Cache each module's answer until the module changes

A module's part of an answer depends only on its own files and its classpath, so each module keeps the hits it
found for a query and reuses them while nothing it depends on has changed; a repeated query compiles only the
modules that changed.

- **What:** per module worker, the module's `SearchHits` (references, implementations, incoming calls), keyed by
  the query kind and the target (owner's binary name, method name, erased descriptor; for incoming calls also the
  overridden methods and receiver types the item carries). Never the attributed analyses: those are megabytes
  each, and keeping them is what the memory-pressure work removed.
- **Validity:** a per-module generation counter, bumped on a change to any of the module's sources (an editor
  change, a save, a change on disk the candidate index notices), on a rebuild of a module it depends on, and on a
  workspace reload. An entry from an older generation is ignored and replaced.
- **Memory:** entries held through `SoftReference`, which the JVM clears only when memory runs short (a
  `WeakReference` would be cleared at the next collection, seconds away), plus a bound on entries per module.
  The answers themselves are small: a few hundred locations per query.
- **Effect:** a repeated query on an unchanged workspace answers from memory (agents repeat queries; editors
  repeat references after navigating); after an edit, only the edited module compiles again. A first query is
  unchanged, so this complements (e), (d) and (b) rather than replacing them.
- **Threading:** the cache belongs to the module worker and is read and written on its thread; the generation
  bumps arrive as tasks on it, so no locks or concurrent collections are needed (CLAUDE.md, server threading).
- Size: M. Risk: a missed invalidation event shows stale hits; each event above needs a test that a cached answer
  is replaced after it.

## Standing against jdtls

The benchmark (`dev/lsp-bench`, 2026-11-07/08, before #157 and #158) against today's numbers from this document's
method (not strictly comparable: another harness and a partly loaded machine), median of the warm requests:

| Query | jdtls 1.63 | Lathe 2026-11-08 | Lathe after #158 |
|---|---|---|---|
| Hibernate ORM references, common name (`getId`) | 4.3 s | 27.7 s | ~18 s |
| OpenMetadata incoming calls (`getEntityRepository`) | 2.7 s | 13.2 s | ~7.8 s |
| OpenMetadata references, widely used method | 2.2 s | 7.8 s | ~8 s |
| Hibernate ORM implementations, interface method | 0.22 s | 0.73 s | not re-measured |

jdtls answers these first-time searches for common names from a prebuilt index; Lathe compiles the candidate files.
Lathe is faster on field reads through a getter (milliseconds against 7–25 s), outgoing calls and subtype
implementations, and on most of Helidon; jdtls does not import Fineract. The cache (c) would answer a repeated
query from memory; a first query needs the exposure graph above.

## Proposed order

0. (0): the startup regression — fixed in #157.
1. (e) and (d): batches of 32, implementations and incoming calls batched — done in #158.
2. (b): shelved (above).
3. After the next release: (c), each module caching its answers until it changes; then the exposure graph from class
   files, if first-time searches for common names still matter.

## Open questions

- Which invalidation events (c) needs beyond those listed, e.g. an annotation processor's generated sources
  changing without a source edit, or `.lathe/` rewritten by another server.

- Batch size for implementations and incoming calls: the same 8 as references, or measured.
- Whether (b) should apply to every instance method or only above a candidate-count threshold.
