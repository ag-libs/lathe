# Lathe — Lessons from Metals v2

## Status

**Potential.**
A review of the Metals v2 Java language server (2026-10-25), with the ideas worth adopting in Lathe, ranked, with pros and cons.
Nothing here is approved; each adopted idea gets its own design summary first.

Source: `scalameta/metals`, branch `main-v2`, commit `507e22d` (2026-10-23).

## What Metals v2 is

`main-v2` is the Databricks-internal fork of Metals 1.6.9 that became Metals 2.0,
aimed at large monorepos and at working "even if your codebase is not compiling".
Java support in Metals dates from 2021 (navigation through SemanticDB) and 2023 (hover and completion on javac).
The design described here is recent:
the javac-based diagnostics compiler landed in October 2025,
the turbine classpath in December 2025,
`mbt.json` in January 2026,
and the Maven exporter in May 2026.

### How it works

- **Build model.**
  The server never reads build files, only `.metals/mbt.json`.
  A Maven goal invoked by its full coordinate (`metals:export`, no pom change), a Gradle Tooling API init script, a Bazel importer, or a user script writes it.
  Dependency jars are listed once in a top-level table and referenced by id from each module.
  A digest of the build files decides when to re-import.
- **Compiler.**
  In-process javac, one `JavacTask` per request, with `-sourcepath "" -proc:none` and a classpath of external jars only.
  A javac plugin strips method bodies from every file except the edited one.
  `SafeAttr`, `SafeEnter` and `SafeTypeEnter` replace javac internals (the NetBeans pattern) so a javac crash on broken code becomes an error type.
  At most three tasks are cached, keyed by a hash of options, URI and text.
- **Workspace types.**
  No class output is used.
  A vendored copy of Google's turbine header-compiles every workspace Java file (listed by `git ls-files`) into in-memory class bytes;
  it recompiles a minute after the last change, and changed files reach javac as sources until then.
  Module boundaries are effectively ignored: the workspace is one pool of sources.
- **References.**
  A parse-only indexer writes a bloom filter per file to `.metals/index.mbt`,
  updated incrementally by comparing git blob ids.
  A reference search narrows candidate files with the bloom filters, then gets SemanticDB for them
  (from a cache, from the build, or by compiling batches of 100 files), all under a time budget.
- **Threads.**
  lsp4j handlers run on a shared pool and touch state held in concurrent maps (`TrieMap`).
  Each compiler owns one thread whose queue runs the newest job first.
  Cancellation interrupts that thread; a crash restarts the compiler and retries the job once.
- **Run and test.**
  They shell out to the build tool (`mvn -pl :a --also-make install -DskipTests`, `mvn test -Dtest=…`) and read the Surefire XML reports.
- **MCP.**
  The MCP server runs inside the LSP process or standalone, over HTTP or stdio.
  On start-up it writes its entry into each agent's config file (`.mcp.json`, `.cursor/mcp.json`, `.vscode/mcp.json`, `.codex/config.toml`) and removes it on shutdown.

### Where Lathe is already ahead

- **Correct workspace types.**
  Lathe resolves sibling modules from real per-module class output in the `.lathe/` mirror, so module boundaries and annotation processors are honoured.
  Metals' turbine pool gives up both.
- **Annotation processors.**
  Metals never runs them in the editor and only sees what an earlier build left in `target/generated-sources`.
- **Run and test.**
  Lathe replays the captured Surefire fork without running Maven.
- **Thread confinement.**
  Lathe keeps session state on the event-loop thread.
  Metals' own code carries a comment about `ConcurrentHashMap.computeIfAbsent` deadlocking, the class of bug confinement avoids.
- **Build contract.**
  The [workspace contract](../lathe-workspace-contract.md) already lets any front-end write `.lathe/`, which covers Metals' user-script importer.

## Constraint

Lathe uses only the public javac API (`javax.tools`, `com.sun.source`), with newer-JDK API behind `CompilerTrees`.
Ideas that need `com.sun.tools.javac` are listed under [Rejected](#rejected) and not ranked.

## Suggested improvements

| # | Idea | Value | Cost |
|---|---|---|---|
| 1 | Save the reference candidate index to disk | High | Medium |
| 2 | Stop a cancelled compile part-way, with a `TaskListener` | High | Low |
| 3 | Run the newest interactive request first | Medium | Low |
| 4 | Let git report changed files to the stale scan | Medium | Medium |
| 5 | Write the MCP server into agents' config files | Low | Low |
| 6 | Header-only types for modules with no compiled output | Uncertain | High |

### 1. Save the reference candidate index to disk

**Today.**
`ReferenceCandidateIndex.build` tokenizes every source file on each workspace load (`WorkspaceSession.loadWorkspace`), on the event-loop thread.
An earlier probe measured about 12 s after start before `find_references` returned complete results on a large project;
how much of that is this index has not been measured.

**Proposal.**
Write the index under `.lathe/` after it is built and after each batch of updates,
with each file's fingerprint (the existing CRC32C and size, or mtime).
On load, read the file and re-tokenize only the files whose fingerprint changed, plus new ones; drop deleted ones.
Optionally store a bloom filter per file instead of its token set, as Metals does, to cut memory and file size.

**Pros.**
- `find_references`, rename, document highlight and call hierarchy answer right after start.
- Every MCP session starts cold, so agents benefit most: their first `find_references` or `rename_symbol` is no longer slow or incomplete.
- Less work on the event-loop thread during load.
- The fingerprints already exist for compile stamps and the stale scan.

**Cons.**
- A new on-disk format: it needs a version, and a corrupt or foreign file must be ignored, not trusted.
- Two code paths (load versus rebuild) that must agree; a test has to prove the loaded index equals a fresh build.
- Bloom filters give false positives (more candidates to compile), never false negatives; token sets stay exact.
- No gain if the load time turns out to be elsewhere.

**First step.** Measure `[candidate-index] built … ms` on Dropwizard and Helidon.

### 2. Stop a cancelled compile part-way, with a `TaskListener`

**Today.**
`JavaSourceCompiler` checks the `CancelChecker` between files only.
A superseded FULL compile of a large file or module runs to its end.
The planned [interactive request priority](../planned/lathe-interactive-request-priority.md) design leaves open whether to cancel in-flight compiles (open decision 1);
this is the mechanism it would need.

**Proposal.**
Register a `com.sun.source.util.TaskListener` on each task that calls `checkCanceled()` on every `started` and `finished` event (parse, enter, analyze, generate, per compilation unit and per class).
The resulting `CancellationException` aborts the task; the caller discards it.
Metals interrupts the compiler thread instead, which javac itself does not check.

**Pros.**
- Public API on every JDK from 17 on.
- Fast typing on a slow module (OpenJDK `java.base`, compiles of several seconds) no longer queues whole compiles behind each other.
- The event-loop worker is freed sooner, so the next hover or completion request returns faster.
- Small: one listener and its wiring.

**Cons.**
- The granularity is javac's events: one huge class still runs to the end of its analyze phase.
- An exception thrown out of javac must not leave half-written class files; a write compile must cancel only before `GENERATE` or write to a temporary location.
- A cancelled compile must leave the analysis cache untouched, never with a partial result.

### 3. Run the newest interactive request first

**Today.**
Requests run in arrival order on the worker; `ServerEventLoop.cancel` drops superseded work by key.

**Proposal.**
For hover, completion, signature help and code actions, take the newest request first (Metals' `CompilerJobQueue`),
and fail queued requests whose LSP request was cancelled without running them.
This is a refinement inside the interactive-request-priority design, not a separate change.

**Pros.**
- In a burst of hover or completion requests (cursor movement, fast typing), the one the user is looking at runs first.
- Clients that send code actions on every cursor move stop delaying the request that matters.

**Cons.**
- Older requests may wait or time out; this is acceptable only for requests a newer one supersedes, never for edits or saves.
- A second ordering policy on the worker to reason about and test.

### 4. Let git report changed files to the stale scan

**Today.**
The stale scan stats the source trees every 2 s ([external-change detection](../done/lathe-external-change-detection.md)).

**Proposal.**
When the workspace is a git repository, ask git which files changed (`git status --porcelain`, and `git ls-files --stage` blob ids for the saved index of idea 1).
Fall back to the stat scan for generated sources, ignored files and non-git workspaces.

**Pros.**
- Cheaper on large trees, especially when git's file-system monitor is on.
- Blob ids are free content fingerprints for idea 1.
- A branch switch shows up as one batch.

**Cons.**
- Starts an external process (it must be the full path of a recorded git, never a bare `git`), with its own failures and Windows cost.
- Two change-detection paths, and the fallback must stay correct on its own.
- Generated sources and the `.lathe/` mirror are not tracked by git.
- The gain is unmeasured; the 2 s scan may already be cheap enough.

### 5. Write the MCP server into agents' config files

**Today.**
[AI agents](../guide/ai-agents.md) documents `claude mcp add`, including a one-time user-scope registration of the user-global launcher that covers every project.

**Proposal.**
An opt-in `lathe:sync` option (or a command) that merges a Lathe entry into `.mcp.json`, `.cursor/mcp.json`, `.vscode/mcp.json` and `.codex/config.toml`.

**Pros.**
- Zero setup in Cursor and Codex, which have no equivalent of the user-scope registration.
- One code path for every agent.

**Cons.**
- Mostly covered already for Claude Code by the user-scope registration.
- These files are often committed: it must merge only its own entry, keep paths relative, and never run unasked.
- Metals removes its entry on shutdown; for a launcher that outlives the server, leaving it is better, so the behaviour differs from Metals'.

### 6. Header-only types for modules with no compiled output

**Today.**
A module resolves its siblings from the `.lathe/` mirror, so a fresh clone needs one build (`mvn process-test-classes`) before Lathe works,
and a module that has never compiled has no types to offer.

**Proposal.**
Header-compile a module's sources with turbine when its mirror is empty, and serve the result as its class output until a real compile replaces it.
Turbine has its own parser and no javac dependency, so it fits the constraint.

**Pros.**
- Lathe works on a fresh clone, or a reactor that does not build, before the first successful build.

**Cons.**
- A new dependency (vendored or from Maven Central) on code Lathe does not control.
- Annotation-processor output is missing, so types that depend on it resolve wrongly; this is the correctness Lathe has over Metals today.
- A second source of class output to keep in step with the mirror.
- The need is unproven: the Maven extension already builds the mirror as part of a normal build.

**Recommendation.** Defer until users report the fresh-clone case as a real problem.

## Rejected

- **`SafeAttr` / `SafeEnter` / `SafeTypeEnter`.**
  Crash tolerance for broken code, but it replaces `com.sun.tools.javac` internals.
- **Body-stripping javac plugin.**
  It rewrites javac's internal tree classes, and Lathe does not need it: siblings come from class files, not sources.
- **Shared state in concurrent maps.**
  Against Lathe's event-loop confinement.
- **One build-model file with a shared dependency table.**
  The per-module `.lathe/` contract works; the saving is file size only.
- **Running tests through the build tool.**
  Slower than Lathe's captured-fork replay.
- **`-proc:none` in the editor.**
  Lathe supports annotation processors; dropping them gives up correctness for speed.

## Smaller notes

- **Test fixtures.**
  Metals writes end-to-end LSP tests as one string holding several files (`/path` headers, `@@` for the cursor, `<<>>` for ranges).
  Lathe's tests already use cursor markers in places; worth a look only if fixture setup grows.
- **MCP tools.**
  Metals' `get-source` returns library and JDK source, and `find-dep` completes Maven coordinates and versions.
  Lathe's `get_definition` already reaches dependency and JDK code; `find-dep` would need dependency resolution the server does not have.
- **MCP inside the editor's server.**
  Metals can serve MCP from the editor's LSP process, sharing its warm state.
  Lathe's [shared workspace server](lathe-shared-workspace-server.md) analysis covers this and keeps one process per client for now.
