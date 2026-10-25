# Lathe — Lessons from Metals v2

## Status

**Potential.**
A review of the Metals v2 Java language server (2026-10-25), judged by what each idea would add to Lathe's simplicity.
Nothing here is approved; each adopted idea gets its own design summary first.

Source: `scalameta/metals`, branch `main-v2`, commit `507e22d` (2026-10-23).

## The main lesson

Most of Metals v2's complexity comes from not trusting the build:
turbine header compilation, a global pool of sources, bloom-filter indexes, crash-proofing javac on code that never compiled, and per-tool build-model importers.
Lathe avoids all of that by using the real build's output.
That is its strength, and the test applied to every idea below: adopt only what stays small and keeps one way of doing each thing.

What survives is one small code change, one measurement that decides a second, and a docs update.

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

## Constraints

- Lathe uses only the public javac API (`javax.tools`, `com.sun.source`), with newer-JDK API behind `CompilerTrees`.
- An idea must not add a second way of doing something Lathe already does one way.

## Recommended

| # | Idea | What it adds | Recommendation |
|---|---|---|---|
| 1 | Stop a cancelled compile part-way, with a `TaskListener` | One small listener | Do it |
| 2 | Save the reference candidate index to disk | An on-disk format and a load path | Only if measurement justifies it |
| 3 | Validated MCP config snippets for other agents | Docs only | Do it |

### 1. Stop a cancelled compile part-way, with a `TaskListener`

**Today.**
`JavaSourceCompiler` checks the `CancelChecker` between files only.
A superseded FULL compile of a large file or module runs to its end.
The planned [interactive request priority](../planned/lathe-interactive-request-priority.md) design leaves open whether to cancel in-flight compiles (open decision 1);
this is the mechanism it would need, so the change belongs in that design rather than a new one.

**Proposal.**
Register a `com.sun.source.util.TaskListener` on each task that calls `checkCanceled()` on every `started` and `finished` event (parse, enter, analyze, generate, per compilation unit and per class).
The resulting `CancellationException` aborts the task; the caller discards it.
Metals interrupts the compiler thread instead, which javac itself does not check.

**Pros.**
- Public API on every JDK from 17 on.
- Uses the cancellation Lathe already has; it only makes it reach inside a compile.
- Fast typing on a slow module (OpenJDK `java.base`, compiles of several seconds) no longer queues whole compiles behind each other.
- The event-loop worker is freed sooner, so the next hover or completion request returns faster.

**Cons.**
- The granularity is javac's events: one huge class still runs to the end of its analyze phase.
- An exception thrown out of javac must not leave half-written class files; a write compile must cancel only before `GENERATE` or write to a temporary location.
- A cancelled compile must leave the analysis cache untouched, never with a partial result.

### 2. Save the reference candidate index to disk — only if measured

**Today.**
`ReferenceCandidateIndex.build` tokenizes every source file on each workspace load (`WorkspaceSession.loadWorkspace`), on the event-loop thread.
An earlier probe measured about 12 s after start before `find_references` returned complete results on a large project;
how much of that is this index has not been measured.

**Proposal.**
Write the index under `.lathe/` after it is built and after each batch of updates,
with each file's fingerprint (the existing CRC32C and size, or mtime).
On load, read the file and re-tokenize only the files whose fingerprint changed, plus new ones; drop deleted ones.
Keep exact token sets; Metals' bloom filters would add false positives for a memory saving Lathe has not needed.

**Pros.**
- `find_references`, rename, document highlight and call hierarchy answer right after start.
- Every MCP session starts cold, so agents benefit most: their first `find_references` or `rename_symbol` is no longer slow or incomplete.
- The fingerprints already exist for compile stamps and the stale scan.

**Cons.**
- A new on-disk format: it needs a version, and a corrupt or foreign file must be ignored, not trusted.
- Two code paths (load versus rebuild) that must agree; a test has to prove the loaded index equals a fresh build.
- No gain if the load time turns out to be elsewhere.

**Gate.**
On Dropwizard and Helidon, measure `[candidate-index] built … ms`, `[workspace] ready`, and the time a fresh MCP session waits for its first complete `find_references`.
Build this only if the index is most of the wait; otherwise drop it.

### 3. Validated MCP config snippets for other agents

**Today.**
[AI agents](../guide/ai-agents.md) documents `claude mcp add` and says only the Claude Code setup has been validated.

**Proposal.**
Add a tested config snippet per agent: Cursor (a Windows target), VS Code, Zed, Gemini CLI and Codex.
Verify each one live before it goes in.

**Pros.**
- Gives the benefit of Metals' config writing (rejected below) with no code.

**Cons.**
- Snippets drift when agents change their config formats; each needs a re-check now and then.

**Documenting the working directory (to address later).**
Both launchers read `.lathe/java-home` and `.lathe/jvm.args` relative to the directory the agent was started in,
while the server itself finds `.lathe/` by walking up.
Started from a subdirectory, the per-project launcher (a relative path) does not start at all,
and the user-global `~/.cache/lathe/latest/lathe-mcp-launcher.sh` starts but quietly uses the `java` on `PATH` and drops the `jvm.args` flags (the `--add-exports` the formatter needs).
`LATHE_JAVA_HOME` fixes the JDK but not the flags.
[AI agents](../guide/ai-agents.md) should say so plainly:

- Start the agent from the workspace root.
- For a local-scope registration, the per-project launcher by absolute path is the robust choice:
  it never leaves the machine and runs the version the project pins.
- Prefer the user-global launcher only for one registration across projects that track the same Lathe version, and never from a subdirectory.

The fix that removes the caveat is for `LauncherScript` to walk up to the nearest `.lathe/` before reading those files, in both the POSIX and `.cmd` forms.

**Steering the agent.**
Connecting the server does not make an agent use it.
[`examples/agents/lathe-mcp.md`](../../examples/agents/lathe-mcp.md) is a sample block for a project's `CLAUDE.md` or `AGENTS.md` (Claude Code can import it with `@examples/agents/lathe-mcp.md`):
it points the agent at the tools where Lathe beats text search (renames, `verify_change`, imports, formatting, polymorphic references, single tests) and leaves plain search to `grep`.

## Rejected

- **Run the newest interactive request first.**
  Metals' per-compiler queue takes the newest job first.
  In Lathe it would be a second ordering policy next to cancel-by-key, which already drops superseded work;
  with idea 1, cancelling also stops the compile, which covers most of the benefit.
- **Let git report changed files to the stale scan.**
  It would add an external process and a second change-detection path with a fallback, and git does not track generated sources or the `.lathe/` mirror.
  The stat scan works; tune its interval if measurements show a cost.
- **Header-only types for modules with no compiled output (turbine).**
  It would let Lathe work before the first build, but adds a dependency and a second source of class output to keep in step with the mirror,
  and loses annotation-processor output: the correctness Lathe has over Metals.
  It is the main source of Metals' complexity.
- **Writing the MCP server into agents' config files.**
  Metals must rewrite `.mcp.json` and the like on every start because its MCP server listens on a random HTTP port.
  Lathe's stdio launcher is a fixed relative path that every build re-creates, so a registration never goes stale:
  the gain is one command or one pasted snippet, once, which the user-scope `claude mcp add` already covers for Claude Code.
  The costs are merging other tools' config files, a committed file that works on one OS family only, TOML for Codex, and tracking five config formats.
  Idea 3 gives the same benefit.
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
