# Lathe — Gaps

This is the single active gap registry for Lathe.
Every open gap, across all areas, lives here, follows the shared [gap lifecycle](gap-process.md)
(a `Status` and a `Target`), and is discovered and triaged through the [gap workflow](gap-workflow.md).
Resolved (`done` / `non-goal`) gaps move to [gaps-archive.md](gaps-archive.md).

## Areas

Each gap keeps its area prefix; the area is the discovery family, not a strict feature taxonomy.

| Prefix | Area | Notes |
|---|---|---|
| `EG-NNN` | exploration | Live-probing of nav, hover, search, completion, code actions, hierarchies, against Helidon, Dropwizard, and the `@Builder`-heavy sample-workspace workspace |
| `FR-NNN` | references | `textDocument/references` scope, failure propagation, coverage |
| `CA-N` | code-action | `textDocument/codeAction` providers |
| `CQ-NNNN` | completion | Completion quality; checked against the completion [expectations](../done/lathe-completion-expectations.md) contract |
| `WS-N` | workspace lifecycle | Workspace freshness and lifecycle: reactor mirror / type-index staleness, source watching, sync prompting, and reload |
| `TE-N` | test execution | Maven test-fork capture, replay launch fidelity, and test-classpath isolation |
| `DB-N` | debug & evaluation | In-process DAP adapter and expression-evaluator scope, fidelity, and coverage |
| `NV-N` | neovim client | The shipped Neovim plugin (`lua/lathe/…`) and its recommended configuration |
| `MC-N` | MCP / agent facade | The in-process `LatheEngine` facade and `lathe-mcp-server` tool surface that exposes Lathe to AI agents |

## Finding the work for a release

The slice for a release is derived, not hand-maintained: every gap with `Status: accepted` and the
matching `Target` (see [gap-process.md](gap-process.md)). Post-beta, releases are demand-driven — work
accrues under `Target: next` and is version-named when cut, so `next` is the live slice.

```bash
grep -nE '^(Status|Target):|^\*\*Status' docs/gaps/gaps.md     # scan active entries
grep -n 'Target: next' docs/gaps/gaps.md                       # what the next cut is trending toward
```

Entries follow, grouped by area: exploration (EG) below, then Find References (FR), Code Actions
(CA), Completion (CQ), Workspace Lifecycle (WS), Test Execution (TE), Debug & Evaluation (DB),
Neovim Client (NV), and MCP / Agent Facade (MC).

---

## Timing Observations

Collected from `FINE` logs during the session.
These are reference data, not gap items.

| Operation | Helidon (332 mods) | Dropwizard (68 mods) |
|---|---|---|
| Server + workspace load | ~3.4s | ~3.6s |
| Type-index full shard load | 333–405ms | 354–447ms |
| Reactor index refresh | 149–218ms | 162–193ms |
| Member-access completion | 33–71ms | 54ms |
| Full-document formatting | 134ms | 187ms |
| Code action response | 178–293ms | 261ms |
| `compile:open` | ~280ms | ~250ms |
| `compile:full` (on save) | — | 79ms |
| References (153 results, 15+ modules) | — | ~4s |

---

## EG-018 — `textDocument/selectionRange` not implemented

**Status: deferred — Target: backlog**

### Observed behaviour

The server does not advertise `selectionRangeProvider`, and no handler exists.
Expand-selection and shrink-selection (a common editing keystroke) are unavailable.

The `selectionRange` occurrences in the server source are unrelated: they are the
`DocumentSymbol.selectionRange` and `CallHierarchyItem.selectionRange` fields, not the
`textDocument/selectionRange` feature.

Spec: [LSP 3.17 — Selection Range Request](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_selectionRange).
Params carry `positions: Position[]` (multiple cursors); the response is a `SelectionRange[]` aligned
to those positions, each a linked chain `{ range, parent? }` where every `parent` is a strictly
larger enclosing range. This is request-driven (an explicit keystroke), not ambient, so latency is
not critical. Eclipse JDT LS implements it (`SelectionRangeHandler`, PR #1101) by chaining ranges
built from the AST, with extra handling for line/block comments — so this is parity work.

### Root cause

`LatheLanguageServer.initialize` registers no `selectionRangeProvider`, and there is no
`selectionRange` request handler.

### Proposed fix

Implement `textDocument/selectionRange` syntactically.
For each requested position, walk the enclosing `TreePath` from the leaf outward and emit a nested
chain of `SelectionRange` entries (identifier → expression → statement → block → member → type).
This needs only source positions, not type resolution, so it can run on the parsed tree without a
full attribution pass. Emit strictly-increasing ranges (dedup identical spans), and — as jdtls does
— handle a cursor inside a comment, which is not an AST node and would otherwise have no enclosing
leaf to walk from.

### Client integration (Neovim) — low marginal value here

Unlike EG-017, this needs no plugin wiring, and its payoff in Neovim is limited:

- Neovim 0.12 exposes it built-in via `vim.lsp.buf.selection_range(direction, timeout_ms)` (positive
  expands, negative shrinks) plus visual-mode `an`/`in` text objects; pre-0.12 users used the
  `nvim-lsp-selection-range` plugin. A coder just binds keys — no `lathe.lua` change required beyond
  advertising the capability.
- Crucially, Neovim's `an`/`in` uses **Treesitter as the primary provider and LSP only as a
  fallback**. Lathe's `ftplugin/java.lua` already starts the Java Treesitter parser, so users already
  have expand/shrink selection today; the LSP version would rarely be reached. This is why EG-018 is
  a weaker candidate than EG-017 (which filled a genuinely absent feature and auto-upgraded
  vim-illuminate users).

### Probe commands

Not probeable through `explore.py` (no `selectionRange` command); confirmed by the absent
capability and the absent handler in `LatheLanguageServer`.

### Regression targets

- `SelectionRangeTest.selectionRange_insideExpression_returnsNestedSyntacticRanges`
- `SelectionRangeTest.selectionRange_atMethodName_expandsToMemberThenType`

---

## EG-048 — Go-to-definition on an incomplete method call lands on the file, not the method

**Status: deferred — Target: backlog**

### Observed behaviour

With a partially-typed call (`foo(`, missing arguments), `textDocument/definition` navigates to the
file top instead of the method. When the name is overloaded it should return multiple candidate
locations so the client shows a picker; when unambiguous it should resolve to the single method.

```bash
python3 dev/explore.py <ws>/.../Foo.java def <line>:<col>   # cursor in `foo(<incomplete>`
# today: Foo.java 0:0 ; expected: the method declaration, or a location list for overloads
```

### Root cause

The invocation is erroneous, so javac cannot attribute the method element; `SourceLocator.elementAt`
yields null and definition takes the file-top fallback. The pipeline also returns at most one
location — `WorkspaceSession.definitionFuture` maps `Optional<Location>` to a 0-or-1 list (`:1250`) —
so overloads cannot be offered even though the LSP result type (`Either<List<Location>, …>`) allows
many.

### Proposed direction

Recover the intended target with the existing sentinel/recovery pipeline
(`SentinelInjector` / `SentinelParser`) already used for completion on incomplete input: obtain the
receiver, method name, and argument index from `ParsedSentinel`. Enumerate the accessible
`ExecutableElement`s of that name on the receiver type and return **all** declaration locations
(single → jump, many → picker); extend `definitionFuture` to build a `List<Location>`. No ad-hoc
parsing — sentinel pipeline + javac elements only (AGENTS.md).

Deferred: the recovery integration, overload enumeration, and multi-location plumbing are the largest
of the three and are not in the current release slice.

### Regression targets

None yet — re-triaged from backlog when scheduled.

---

<!-- EG-049..EG-055 were discovered by differential testing against jdtls (dev/jdtls_diff.py; see
docs/done/lathe-jdtls-differential-testing.md) on a real multi-module reactor. Each is a behaviour
where jdtls returns a result Lathe does not, or the two disagree substantively. -->

## EG-050 — Javadoc `{@link}` / `{@code}` regions not resolved for references, highlight, completion

**Status: deferred — Target: backlog (references/highlight shipped; completion deferred).**

### Delivered — references, highlight, and rename over Javadoc references

`textDocument/references`, `textDocument/documentHighlight`, and rename now cover a symbol's Javadoc
reference-tag mentions (`{@link #of(Class)}`, `{@linkplain}`, `@see`, `@throws`), for both members and
types; and go-to-definition / hover resolve a reference under the cursor (hover is the archived
EG-003). Implemented via javac's structured `DocTrees` — the comment is read as a parsed
`DocCommentTree`, never by scanning its text: a shared `JavadocReferences.forEachReference` delivers
each reference resolved to an element + source span, consumed by `JavadocReferenceResolver` (cursor →
element) and `ReferenceLocator.scanJavadocReferences` (match + emit).

Rename can also be started from a `{@link}` to a workspace type. It only proceeds when the identifier
under the cursor names the target, so Javadoc prose, comments, and keywords are refused instead of
renaming the enclosing class.

Regression targets:
`ReferenceLocatorTest.javadocLinkMentions_includedForMemberAndType`,
`HoverTest.hover_javadocLinkTags_resolveReferencedTypeAndMember`,
`RenameProviderTest.resolveRenameTarget_cursorNotNamingTarget_isRefusedButJavadocLinkTargetsLinkedType`.

### Remaining — completion inside `{@link}` / `{@code}` (deferred)

`textDocument/completion` still returns nothing inside a `{@link …}` / `{@code …}` tag, where jdtls
offers type/member completion. This is the one part that cannot use structured `DocTrees`: an
in-progress tag in a not-yet-parseable comment needs detecting "the cursor is after `{@link `" from the
raw comment text — the comment-text parsing deliberately deferred here. A separate, larger slice.

```bash
python3 dev/jdtls_diff.py --methods completion <ws>/.../Api.java
```

---

## EG-053 — Multi-line comment / Javadoc blocks are not folded

**Status: deferred — Target: backlog (multi-line Javadoc folding shipped; non-Javadoc comments deferred).**

### Delivered — multi-line Javadoc blocks fold

`FoldingRangeScanner` now folds a declaration's leading multi-line Javadoc (`/** … */`) as a
`FoldingRangeKind.Comment` range spanning the `/**`…`*/` delimiters. Javadoc is reachable without a
token stream: `Trees.getDocCommentTree(path)` attaches the doc comment to its class / method / field
declaration node, and the reported content span is widened outward to the enclosing delimiters.
Import-group, class-body, and method-body folds continue to work.

Regression targets:
`FoldingRangeScannerTest.scan_multilineJavadocOnDeclarations_returnsCommentFoldsSpanningDelimiters`,
`FoldingRangeScannerTest.scan_singleLineJavadocAndNonJavadocComments_returnNoCommentFold`.

### Remaining — non-Javadoc comments (deferred)

jdtls also folds plain multi-line block comments (`/* … */` not attached to a declaration) and runs of
consecutive single-line `//` comments. These are **not** folded: a free-floating comment is not an AST
node and is not reachable through `getDocCommentTree`, so a fold pass would need javac's token/comment
stream (not regex, per the no-ad-hoc-parsing constraint). Single-line Javadoc and single-line/plain
comments correctly produce no fold today (asserted by the second test above).

```bash
python3 dev/jdtls_diff.py --methods foldingRange <ws>/.../Api.java
```

---


## EG-057 — Symbol search matches CamelHumps only in reactor types, not in JDK and dependency types

**Status: deferred — Target: backlog** (by design since EG-005; the docs that claimed more are corrected).

### Observed behaviour

On Dropwizard, through both `workspace/symbol` and MCP `search_symbols`:

| Query | Result |
|---|---|
| `DiSuRe` | `DiscoverableSubtypeResolver` (reactor) — humps match |
| `ConcurrentHashMap` | found (JDK), by prefix |
| `ConcHashMap` | nothing: no humps match for a JDK type |
| `CHM` | `CHMaxFragmentLengthConsumer`, `CHMaxFragmentLengthProducer` (JDK, by prefix), not `ConcurrentHashMap` |

For reactor types, all-capitals queries are humps patterns too (`ASF` finds `AbstractServerFactory`).

### Root cause

Not a bug: `WorkspaceTypeIndex.searchCamelCase` filters to `reactorBinaryNames` on purpose.
EG-005 added humps as a layer on top of the prefix search for reactor types only, so it would not
touch the prefix contract that completion and the import quick fix rely on.
JDK and dependency types match by name prefix only.

The defect was in the docs: the README, `docs/status.md`, `docs/guide/ai-agents.md` and the
`search_symbols` tool's `query` description said CamelHumps worked "across reactor, dependencies,
JDK". They now say prefix everywhere, CamelHumps for reactor types.

### If it is ever wanted

Humps over JDK and dependency types means scanning their whole index for every query, which the
reactor-only layer avoids (it runs on each keystroke of an editor's symbol picker).
Measure that cost on a large workspace first, and keep it out of `search()` so completion and the
import quick fix keep their prefix contract.
IntelliJ's Search Everywhere matches humps everywhere, so the demand would come from there.

### Regression targets

None: the behaviour is unchanged; only the docs and the tool description were corrected.
---

## EG-058 — Module analysis sessions are never released: a reactor-wide search pins every touched module's jars for the session

**Status: deferred — Target: backlog.**
Waiting for a user report of memory pressure before acting; normal heaps (2 GB and up) are unaffected.

### Observed behaviour

Memory probe against Dropwizard and Helidon at `-Xmx` 1, 2 and 4 GB (read-only requests: cross-module
references, hover, definition, implementation, call hierarchy, then 100 files opened across modules).
Live heap after a full GC:

| Measurement | Dropwizard | Helidon |
|---|---|---|
| After reference searches | ~550–650 MB | ~560–720 MB |
| Per additional open file | ~6–7 MB | ~6–7 MB |
| 100 files open | ~1.16 GB | ~1.0–1.1 GB |
| Non-heap on top of `-Xmx` (NMT) | ~350 MB | ~900 MB |

Closing every file does not return the heap to its pre-open level:
Dropwizard drops only to ~920 MB, against ~465 MB before the 100 opens.
Both projects work fully at 2 GB and 4 GB; at 1 GB both run reference search fine but die once ~80–100
files are open on top of it.

### Root cause

`CompilationWorker` creates its `SourceAnalysisSession` lazily and keeps it until the workspace closes.
Each session owns a `ModuleSourceCompiler` with its own `StandardJavaFileManager`, which opens a zip
filesystem per classpath jar.
A reference search touches every candidate module, so after one reactor-wide search every touched
module's file manager, with all its jar indexes, stays for the rest of the session.
Dropwizard has 233 unique jars but ~4,300 live `ZipFileSystem` instances (each jar opened ~18×),
almost all still alive after every file is closed; Helidon has 209 unique jars and ~1,700 instances.

### Fix direction

Release a module's session after it has been idle for a while (for example 5 minutes), on the worker's
own executor; the next request rebuilds it through the existing `contextFactory`.
This is safe because a cache miss already recompiles (the `AnalysisLru` eviction path), and the
single-threaded executor orders the release after any in-flight search.
Cost: the first request into a module idle past the threshold pays a cold start (~0.2–1.6 s), and a
warm reactor-wide search after a long pause runs at cold speed.
A count-capped LRU of module sessions would bound memory during a burst as well, but would make every
large reactor-wide search cold.

### Regression targets

None yet.

---

## EG-063 — A dependency without a sources jar has no source to navigate to

**Status: documented**
Found by the agent-tooling landscape review (2026-10-24).

### Gap

Definition, hover javadoc and the MCP location answers rely on extracted sources jars.
A dependency that publishes none leaves an editor and an agent with nothing to read.
IntelliJ's MCP `read_file` serves decompiled classes, LSP4J-MCP has `decompile_class`, and vscode-java
decompiles too.

### Fix direction

A decompiler fallback (for example an embedded Vineflower) writing a read-only source into the cache
for a class with no source; mark such answers as decompiled.

### Regression targets

None yet.

---

## EG-064 — No code lens: reference counts and run/debug actions above declarations

**Status: documented**
Found by the agent-tooling landscape review (2026-10-24).

### Gap

jdtls offers reference and implementation counts as code lenses, and vscode-java 1.56 added them for
fields. Run and Debug lenses above test methods and `main` would also give the VS Code client run/test
without client-side work; today its run/test is Neovim-only.

### Fix direction

`textDocument/codeLens` from the runnables and the reference counts, resolved lazily
(`codeLens/resolve`) so a large file does not pay for every count up front.

### Regression targets

None yet.

---

## EG-065 — Lombok projects: the server exits on the first compile, and generated members are invisible

**Status: documented**
**Affects: the editor and MCP.**
Found by the pre-release probes (2026-10-27) on a custom Maven project (JDK 21, Lombok 1.18.38 in the parent's
`annotationProcessorPaths`, `@Data`/`@Builder`/`@Value`/`@Log`), and reported by a user in
[issue #38](https://github.com/ag-libs/lathe/issues/38) (0.1.22).

### Observed behaviour

**1. The server exits on any compile in a Lombok module — a regression in 0.1.23, fixed in 1bc16c11.**
Any write-pass compile of a module that has Lombok on its processor path makes the server exit with status 1:
an editor save, an agent's edit on disk, or a `git pull` or branch switch (any file, not only an annotated one).
The MCP server exits the same way on its next tool call.

```
SEVERE  [compiler] process fatal-error fatal 373ms
java.lang.AbstractMethodError: Receiver class lombok.javac.apt.Javac9BaseFileObjectWrapper does not define
or inherit an implementation of the resolved method 'abstract java.lang.String inferBinaryName(java.lang.Iterable)'
  at jdk.compiler/com.sun.tools.javac.file.JavacFileManager.inferBinaryName
  at ...server.module.JavacRunner.writtenBinaryNames(JavacRunner.java:155)
```

- The defect is Lombok's: `Javac9BaseFileObjectWrapper` extends javac's internal `PathFileObject` without
  implementing its abstract `inferBinaryName(Iterable)`.
  javac's own compile never asks an output file for its binary name, so Lombok never hits it.
  Lathe does: `JavacRunner.writtenBinaryNames` has called the public
  `JavaFileManager.inferBinaryName(CLASS_OUTPUT, jfo)` since the symlinked-workspace fix (5d034fe9, first
  shipped in 0.1.23), and javac's implementation reaches the missing method.
  Before that it derived the name from `jfo.toUri()`, which the wrapper implements; the 0.1.22 report in
  issue #38 sees saves and diagnostics work.
  So it is a Lathe regression for Lombok users, even though the call is correct use of public API.
- The crash repeats on every start: a restarted server compiles the files changed on disk while it was
  down, and exits within a second.
  Only a full Maven build, which brings the compile stamps up to date, lets it start.
- `CompilationWorker` treats every `Error` in a failed compile as fatal to the process, so a `LinkageError`
  thrown by any annotation processor or javac plugin takes the whole server down, not just this one.
- A debug session's test JVM, launched suspended, is left running when the server exits.

**2. Annotated files show false errors until saved.**
The live pass runs no annotation processors ([how it works](../guide/how-it-works.md#diagnostics-live-vs-on-save)),
so every use of a generated member inside the annotated class is an error on open and while editing:
`cannot find symbol: variable log` (`@Log`), `method getName()` (`@Data`), `method getCents()` (`@Value`).
MCP `get_diagnostics` and `verify_change` report the same errors, and an agent will try to fix them.
Files that only use the class from another module are clean, since they compile against its built classes.
With part 1 fixed, the save's full compile runs Lombok (the mirror class gains the generated members),
yet the diagnostics published after the save still show these errors, contrary to
[how it works](../guide/how-it-works.md#diagnostics-live-vs-on-save).

**3. Generated members have no source link** (issue #38).
Go to definition on a call to a generated accessor finds nothing.
Find references on a field returns only direct field accesses, so `analyze_change` reports one reference
and a field rename leaves `getX()`/`setX()` callers broken without a warning.
References from a usage of the accessor itself work.

The guides do not mention Lombok.

### Fix direction

1. Done in 1bc16c11: written classes are named from `jfo.toUri()` and the class-output root, both
   resolved with `toRealPath()` (`FileUtil.classBinaryName`), which gives the same names as
   `inferBinaryName`, symlinked workspaces and `module-info` included. The wrapper could still be
   reported to Lombok.
   Narrowing the process-fatal set to `VirtualMachineError` is deferred until GA (decision
   2026-10-27): until then a processor's `Error` should end the server visibly, and its cause be fixed.
2. Parts 2 and 3: see the investigation below; the decision is pending.
3. Document Lombok support and its limits in the guides.

### Investigation (2026-10-28)

**Cause of part 2.** Every analysis compile adds `-proc:none` (`ModuleSourceCompiler.buildOptions`); only a
save's write (`FULL`) runs processors. Lombok changes the class it compiles in place, so the analysed tree has
no generated members, and part 3 follows from that: no tree declares `getName`.

**How other tools do it.**
- jdtls starts with `-javaagent:lombok.jar` (`java.jdt.ls.lombokSupport.enabled`): Lombok's agent patches
  the Eclipse compiler in jdtls's JVM. It works because jdtls runs ECJ; it does not carry to javac.
- IntelliJ's Lombok plugin synthesizes the members in its own model, without running Lombok: a
  reimplementation of Lombok's rules, which lags new features and `lombok.config`.
- NetBeans, javac-based like Lathe, runs Lombok as an annotation processor.

**Running Lombok on analysis compiles, measured** (`JavacTask.analyze`, Lombok 1.18.38, JDK 21 / 26):

| Compile | `-proc:none` today | Lombok, one instance reused | Extra |
|---|---|---|---|
| one `@Data @Builder` file, warm | 11 / 14 ms, 3 false errors | 23 / 20 ms, none | about 10 ms |
| 50 files, 25 with Lombok, warm | 13 / 12 ms, 100 false errors | 91–130 / 84–133 ms, none | about 2 ms a file |
| the first compile, as Lombok loads | 22 / 23 ms | 908 / 985 ms | about 0.9 s, once a module |

Lombok given through `-processorpath` instead costs about 110 ms a compile (javac loads it each time);
the measured path keeps one Lombok processor per module and hands it to each task with the public
`JavacTask.setProcessors`. The "today" column compiles with false errors, which may save javac some work.
Not measured: Lathe's own pipeline, code mid-edit, Lombok-heavy hierarchies.

**What Lombok leaves in the tree.** Each generated member carries a real source span: `getName()` the
field's name, `setName()` the `@Data` annotation, `builder()` the `@Builder` annotation; an accessor's
body is `return this.name;` or `this.name = name;`. So, with Lombok on analysis compiles:
- 3a, definition on a generated getter: land on that span. The declaration-name search must stay inside
  the declaration's span (today it would run on to a later `getName()` call); hours of work.
- 3b, field references with the accessors' calls: find the class's accessors by their bodies, with no
  naming rules (`lombok.config` prefixes and fluent accessors come for free), and merge their
  references; about a day.
- Every feature reading the tree (semantic tokens, document symbols, unused-code hints, references, the
  MCP position check) needs checking against generated members; part of the 1–2 days part 2 takes.

**Options.**
- Part 2 as above, then 3a and 3b on top: about a week together. Cost: about 10 ms a keystroke, about
  2 ms a file in batch work, about 1 s per module once. Risk: a JDK Lombok does not support (27 for
  1.18.38) stops the server on open, not only on save; the project's own build fails there too.
- Defer part 2. Then 3a and 3b could only guess accessors by name (`getName` → `name`), which breaks with
  `lombok.config`; defer them with it.
- 3c, rename of a field with generated accessors: refuse or warn, from the built classes (the mirror's
  class has `getName()`); hours, independent of part 2. Renaming the accessors' calls too would need
  Lombok re-run on the renamed field to learn the new names: days.

### Regression targets

- Part 1: `FileUtilTest.classBinaryName_classFilesBelowRoot_dottedWithNestedAndDefaultPackage` and
  `ModuleSourceCompilerTest.compileBatch_workspaceBehindSymlink_reportsBinaryNames`; the Lombok case itself
  was verified by hand on a Lombok 1.18.38 project (no Lombok test dependency).
- Parts 2 and 3: the `lombok` invoker fixture's `LombokTest` (JDK 17 to 26, as Lombok 1.18.38 runs) pins
  today's answers: a save compiles with Lombok, yet the false "cannot find symbol: getName()" stays;
  definition on a generated getter finds nothing; field references miss the getter's calls. Each test
  says the right answer, so it fails once a part is fixed, and is then turned around.

---

## EG-066 — Started from a module subdirectory, Lathe runs on the wrong JDK or does not start

**Status: deferred — Target: backlog.**
Decision (2026-10-27): not before the release; the fix touches the generated launchers on every OS.
**Affects: the editor and MCP.**
Found by the pre-release probes (2026-10-27) on EqualsVerifier.

### Observed behaviour

- **Launchers.** `lathe-launcher.sh` and `lathe-mcp-launcher.sh` read `.lathe/java-home` and
  `.lathe/jvm.args` relative to the working directory, while the server itself walks up to the nearest
  `.lathe/`.
  The user-global `~/.cache/lathe/latest/lathe-mcp-launcher.sh`, started in `equalsverifier-core/` (an agent
  launched in a module), finds neither and runs on the `java` on `PATH`.
  With JDK 17 first on `PATH`, files of the release-17 modules work, but a file of `equalsverifier-26` takes
  30 s and answers `the request failed; see the server log`; the log has
  `IllegalArgumentException: error: release version 26 not supported`.
  The module-access flags of `jvm.args` are dropped too.
  The `[startup]` line does not name the JVM, so the mismatch is invisible.
- **VS Code.** The extension looks only for `<opened folder>/.lathe/lathe-launcher.sh`.
  Opening a module folder answers "no launcher … run a Lathe-enabled build to generate it", although the
  build already ran at the root.

### Fix direction

Launchers resolve `.lathe/` the way the server does (walk up from the working directory) and read
`java-home` and `jvm.args` from there.
The VS Code extension walks up from the opened folder.
`[startup]` logs `java.home` and the Java version.

### Regression targets

None yet.

---

## EG-067 — The first build floods the log with warnings, and a disabled Lathe says nothing

**Status: documented**
Found by the pre-release probes (2026-10-27) on EqualsVerifier.

### Observed behaviour

The README quick start (`mvn clean test -Dlathe.capture.only=true`) prints 199 `[WARNING]` lines on
EqualsVerifier, a small reactor:

- 164 are Surefire's `Tests run: N, Failures: 0, Errors: 0, Skipped: N`, one per test class, because
  capture-only excludes every test and Surefire reports skipped classes as warnings.
- 9 are blocks of `The following dependencies could not be resolved at this point of the build but seem to
  be part of the reactor`, printed before `lathe:sync` on the parent.
  `lathe:sync` is an aggregator with `requiresDependencyResolution = TEST`, bound to the parent's
  `process-test-classes`, which runs before any child module is built.
  The sync itself is complete.

A first-time user sees a wall of warnings from a command the README told them to run; a Helidon-size
reactor prints thousands.

The other way round, `CI=true` or `-Dlathe.disabled=true` switches Lathe off with no line at the default
log level, so a developer whose environment sets `CI` gets no hint why `.lathe/` stops updating.

### Fix direction

Find a capture-only filter that Surefire does not count as skipped, or say in the README and test-capture
guide that the warnings are expected.
Resolve the sync's dependencies without the reactor warnings, or bind the sync to a later project.
Log one `INFO` line when Lathe is disabled, naming the switch.

### Regression targets

None yet.

---

## EG-068 — Hover and `describe_symbol` render javadoc loosely and describe the class at a constructor call

**Status: documented**
**Affects: the editor and MCP.**
Found by the pre-release probes (2026-10-27) on Dropwizard, Helidon and EqualsVerifier.

### Observed behaviour

- Each `<p>` becomes three or four blank lines (`Warning.NONFINAL_FIELDS`, `java.util.function.Function`).
- An `@see <a href=…>` link breaks across lines.
- On `new BlockingArrayQueue<>(min, max)` (Dropwizard), the answer describes the class, not the constructor
  the call resolves to.

### Fix direction

Collapse paragraph breaks to one blank line and keep links on one line.
At a `new` expression, describe the resolved constructor (its parameters and javadoc), with the class as context.

### Regression targets

None yet.

---

## Implementation notes

The release slice is derived from the gap fields, not maintained as an ordered list here: the work
for a release is every gap with `Status: accepted` and the matching `Target` (see
[gap-process.md](gap-process.md)).

---

# Find References Gaps (FR)

Active `textDocument/references` gaps discovered by live probing against a large `@Builder`-heavy
reactor workspace. Resolved FR entries are in [gaps-archive.md](gaps-archive.md).

No active FR gaps remain; resolved entries are in [gaps-archive.md](gaps-archive.md).

---

# Code Action Gaps (CA)

Active `textDocument/codeAction` provider gaps. Resolved CA entries are in
[gaps-archive.md](gaps-archive.md).

## CA-7 — Pasting code with unresolved types requires per-symbol quick fixes; no whole-file "add missing imports"

**Status: deferred — Target: backlog (add-missing slice shipped; automatic invocation deferred).**

Signal: user feedback — pasting a snippet with several unimported types was recurring friction: the
only path was moving the cursor onto each unresolved name and invoking the single-name import quick fix
in turn.

### Delivered — whole-file "add missing imports" (slice 1)

`:LatheMissingImports` (server command `lathe.missingImports`, also surfaced as the "Add missing
imports…" code action when the cursor is on an unresolved type) resolves every unresolved type in the
buffer in one pass. It works off the live buffer's debounce compile — no save round-trip needed, since
a syntactically-valid paste already yields `cant.resolve` diagnostics on change. The server enumerates
those TYPE_REF names and resolves each against the type index via a shared `ImportCandidates` helper
(extracted from `ImportQuickFixProvider`), returning each name with its candidate FQNs. The Neovim
client auto-adds unambiguous names, prompts one at a time (`vim.ui.select`) for ambiguous ones, inserts
all chosen imports in one edit, and summarises what was added and any name it could not resolve.

No `source.*` capability is claimed: `source.organizeImports` would over-promise (it implies unused
removal + sorting) and `source.addMissingImports` is non-standard, so the feature is an
`executeCommand`, consistent with `lathe.typeHierarchy` / `lathe.createType`.

Regression targets: `CodeActionTest.missingImports_mixedNames_partitionsCandidatesByAmbiguity`,
`CodeActionTest.missingImports_allTypesResolved_returnsNoItems`,
`CodeActionTest.codeAction_typeRef_offersAddMissingImportsCommand`; client `imports_spec.lua`.

### Remaining — automatic invocation (deferred, backlog)

Running the command **automatically** — on paste and/or as an on-save step alongside format-on-save —
so the common case needs no manual invocation. A Neovim-client concern layered on the shipped command;
keep it opt-in like `format_on_save`. Unused-import removal and import sorting for this command (a fuller
"organize imports") are separate later slices; formatting with a pinned google-java-format or palantir already
removes unused imports and reorders them as the project's Spotless does.

---

## CA-12 — Import candidates are not ranked: `java.awt.List` and `kotlin.Function` tie with the obvious choice

**Status: documented**
**Affects: MCP `fix_imports`, and the editor's import quick fix.**
Found by the pre-release probes (2026-10-27) on EqualsVerifier.

### Observed behaviour

`fix_imports` reports `List: java.awt.List, java.util.List` and
`Function: kotlin.Function, java.util.function.Function` as ambiguous, and the editor quick fix offers
`Import 'kotlin.Function'` first.
An agent has to pick, and in a Java module the answer is almost always the `java.util` one.

### Fix direction

Rank candidates: types the module already imports elsewhere, then `java.*` packages other than `java.awt`,
then the module's own dependencies, then the rest.
`fix_imports` could add the top candidate when the ranking is decisive, and list the others.

### Regression targets

None yet.

---

# Completion Gaps (CQ)

Active completion-quality gaps. Discovered and triaged via the completion appendix of the
[gap workflow](gap-workflow.md); checked against the completion [expectations](../done/lathe-completion-expectations.md)
contract. Resolved CQ entries are in [gaps-archive.md](gaps-archive.md).

---

# Workspace Lifecycle Gaps (WS)

Workspace freshness and lifecycle gaps: reactor mirror / type-index staleness, source watching, sync
prompting, and reload. Resolved WS entries are in [gaps-archive.md](gaps-archive.md).

## WS-17 — The server JVM has no heap cap and grows toward a quarter of the machine's memory

**Status: deferred — Target: backlog.**
Decision (2026-10-24): Lathe sets no default limit until users report how much memory they want it to
use; machines with plenty of memory are expected to use it. The installation guide documents capping
it with `LATHE_JVM_OPTS` (and `-XX:G1PeriodicGCInterval` to return memory when idle), and the agent
guide's troubleshooting points there. Lathe's own dev tools cap their servers at 2 GB.
**Affects: the editor and MCP.**
Found by the MCP-quality probes (2026-10-24) on Helidon.

### Observed behaviour

An MCP session on Helidon started at 752 MB RSS; three `verify_change` calls on `GenericType`
(235–311 caller files in 36–60 modules each) took it to 9.6 GB.
The launchers pass no `-Xmx`, so on a 60 GB machine the default maximum heap is about 15 GB, and the
heap grows rather than collects.
The same workload under `LATHE_JVM_OPTS=-Xmx2g` ran at the same speed (5.5–11 s per call) and stayed
at about 2.6 GB, so most of the growth is uncollected garbage, not memory Lathe holds.
An editor server and an MCP server per agent, all day, add up.

### Fix direction

Give the launchers a default heap policy: a cap (absolute, or `-XX:MaxRAMPercentage`), and G1's
periodic collection (`-XX:G1PeriodicGCInterval`) so an idle server returns memory; keep
`LATHE_JVM_OPTS` as the override. Measure the cap against the largest probes first.
Related: [EG-058](#eg-058--module-analysis-sessions-are-never-released-a-reactor-wide-search-pins-every-touched-modules-jars-for-the-session)
(sessions never released), which an uncapped `verify_change` exercises more than before.

### Regression targets

None yet.

---

## WS-19 — An outside edit to a Helidon blueprint sometimes does not regenerate its builder

**Status: documented**
**Affects: the editor and MCP.** Intermittent: 1 of 3 full runs.
Found by the pre-release probes (2026-10-27) on Helidon, with `dev/mcp_staleness.py` (scenarios N1, N1b).

### Observed behaviour

N1 adds an option to a blueprint outside the editor and then uses the generated accessor.
In the failing run the use stayed `cannot find symbol latheProbeOpt` after 8 s, and the generated
`AllowListConfig.java` in `.lathe/` never gained the option.
That run's first in-process compiles in the scenario took 24 ms and 86 ms (`failed=1`); whenever the
processors ran, it took 1.1–1.3 s, so the blueprint change was missed or compiled without its processors.
The processors' `META-INF/services` files were in the mirror, so this is not the cause fixed in
[WS-15](gaps-archive.md#ws-15--annotation-processors-built-in-the-reactor-never-run-in-process--done).
It did not reproduce alone, after the `pom` or `two` scenarios, after a sync during the session, or in two
more full runs (one right after a fresh sync).
The failing run had no `LATHE_DEBUG`.

### Fix direction

Repeat the full Helidon staleness run with `LATHE_DEBUG=1` until it fails, then follow the blueprint
through the write pass: was it detected, and which options and processor path did its compile get.

### Regression targets

None yet.

---

## WS-20 — A class added outside the editor takes about 8 s to resolve in an open file

**Status: documented**
**Affects: the editor.**
Found by the pre-release probes (2026-10-27) on EqualsVerifier, with `dev/lsp_staleness.py` (scenario P1).

### Observed behaviour

After an agent or `git` adds a class in an upstream module, an open downstream file that uses it shows
`cannot find symbol` for about 8 s before the server republishes clean diagnostics.
The scan runs every 2 s, and the other outside-change scenarios settle in 2–4 s.
Adding a class and using it straight away is the common agent pattern this delay hits.

### Fix direction

Measure where the 8 s goes (scan, write pass, type-index refresh, re-check of open files) and bring it
in line with the other outside changes.

### Regression targets

None yet.

---

# Test Execution Gaps (TE)

Capture/replay gaps involving the Maven test fork, the recorded launch template, and the standalone
replay JVM. Resolved TE entries are in [gaps-archive.md](gaps-archive.md).

## TE-3 — `<systemPropertyVariables>` are not carried into the replay launch

**Status: deferred — Target: backlog**

(Renumbered from TE-1, which is already used by the archived capture-dependency-leak gap in
[gaps-archive.md](gaps-archive.md#te-1--capture-only-dependencies-leak-into-the-recorded-replay-classpath).)

Surefire applies `<systemPropertyVariables>` inside the fork via `System.setProperty` from a program
argument that never appears in `getInputArguments()`, so the capture listener cannot see them and
replay omits them. No project validated against Lathe uses them today; the escape hatch is
`<argLine>-Dkey=value</argLine>` (captured as `jvmArgs`). The design — `lathe:sync` reads the
effective Surefire model (or a hybrid where sync supplies the key names and the fork supplies the
values) — is in [lathe-run-test-debug.md](../done/lathe-run-test-debug.md) §15.1.

### Regression targets

None yet — to be defined when the fix is scheduled.

---

## TE-7 — A captured launch naming files the build deleted replays into a class-loading failure

**Status: documented**
**Affects: run, debug and replay on Gradle builds that run tests through the Develocity plugin.**
Found by the Gradle plugin's end-to-end check (2026-10-25) on JUnit's own build.

### Observed behaviour

The Develocity Gradle plugin starts the test JVM through its own launcher, with that launcher's jar
and a copy of `opentest4j` in a temporary directory (`/tmp/test-distribution-test-launcher-forked…/`)
that is deleted after the run; it does so even with the build's own switch for Test Distribution off.
The capture records the launch it saw, so `test-launch.json` names files that no longer exist.
The server's completeness gate checks only the reactor's trees, so the replay launches anyway and
fails with `NoClassDefFoundError: org/opentest4j/AssertionFailedError`.

### Fix direction

Decided 2026-10-25: a known limitation, not worked around (mapping the temporary copies back to
same-named jars is a guess; switching Develocity off needs the Develocity plugin's API).
The gate blocks a replay whose class or module path names a missing file outside the reactor, with
a reason naming the file and suggesting a launch outside the Develocity plugin's test acceleration;
the Gradle guide documents the limitation.

### Regression targets

None yet — to be defined when the fix is scheduled.

---

## TE-8 — Failsafe integration tests are never captured, yet the remedy says to capture them

**Status: documented**
**Affects: MCP `run_test` and `workspace_summary`.**
Found by the pre-release probes (2026-10-27) on EqualsVerifier.

### Observed behaviour

`equalsverifier-test-signedjar` has only `SignedJarIT`, run by Failsafe.
`run_test` on it answers
`Test run blocked: no captured test launch for equalsverifier-test-signedjar; run
.lathe/lathe-sync.sh --tests equalsverifier-test-signedjar to capture it`,
and `workspace_summary` lists the module as `tests: not captured` with the same command.
That command succeeds (`No tests to run.`) and captures nothing, since capture rides the Surefire `test`
fork, so an agent that follows the advice loops.
The limit is stated only in the Neovim guide, not in the test-capture guide's limits or the agent guide.

### Fix direction

Tell a Failsafe test (a class matching the Failsafe includes, or a module whose only test plugin is Failsafe)
apart from a missing capture, and say it cannot be run from Lathe yet instead of naming a sync.
Add the limit to `test-capture.md` and `ai-agents.md`.
Capturing the Failsafe fork is the larger, separate step.

### Regression targets

None yet.

---

## TE-9 — Running a file with two top-level test classes runs only the first

**Status: documented**
**Affects: MCP `run_test` (class scope); the editor's run of a file was not probed.**
Found by the pre-release probes (2026-10-27) on Dropwizard.

### Observed behaviour

`DropwizardExtensionsSupportDeepNestingTest.java` declares a second top-level test class
(`…ExtensionAtEachLevelTest`), which Surefire runs.
`run_test` with the default class scope answers `PASS — 4 passed` for the first class only, and its output
does not name the class it ran, so the skipped one goes unnoticed.
A method of the second class runs when named.

### Fix direction

Class scope runs every top-level test class in the file, and the result names the classes it ran.

### Regression targets

None yet.

---

# Debug & Evaluation Gaps (DB)

Gaps in the in-process DAP adapter and the two-stage expression evaluator (see
[lathe-run-test-debug.md](../done/lathe-run-test-debug.md) §12.11). The debug surface is complete
for the current milestone — breakpoints, stepping, inspection, conditional breakpoints, and
read/invoke expression evaluation all work — so the entries below are the deliberately deferred
remainder, re-triaged in a future round.

## DB-1 — Expression evaluation is read/invoke only; no assignment or `setVariable`

**Status: deferred — Target: backlog**

### Observed behaviour

The evaluator computes values (reads, method/constructor invocation, `String` concat) but cannot
write: an assignment expression raises `EvaluationException`, and the DAP `setVariable` request is not
backed by a write path. A watch or console `x = 5`, a field write, or editing a variable in the
Variables pane therefore does nothing.

### Expected behaviour

`x = expr`, field writes, and array-element writes assign into the suspended frame and return the
assigned value; the DAP `setVariable` request mutates the named variable. This is the deferred eval
"v3" — it mutates debuggee state and warrants its own design (type coercion/boxing on the write side,
final-field policy, event suppression) before implementation.

### Probe commands

```bash
python3 dev/debug_probe.py --workspace <ws> <MainFile.java> --line <N> --main <Class> \
  --eval "x = 5"          # today: EvaluationException (assignment unsupported)
```

### Regression targets

None yet — to be defined when the fix is scheduled.

---

## DB-2 — Array-creation expressions (`new T[]{…}` / `new T[n]`) are unsupported

**Status: deferred — Target: backlog**

### Observed behaviour

A `NewArray` tree raises `EvaluationException: unsupported expression: NEW_ARRAY`. Reads of existing
arrays (indexing, `.length`) work; only *creating* an array in an expression fails, which also blocks
calls whose argument is an array literal (e.g. `java.util.BitSet.valueOf(new long[]{…})`).

### Expected behaviour

`new int[]{1, 2, 3}`, `new String[4]`, and `new long[]{}` allocate a JDI array (via
`ArrayType.newInstance`), fill any initialiser elements, and evaluate to the mirror. Low marginal
value in watches, hence deferred.

### Probe commands

```bash
python3 dev/debug_probe.py --workspace <ws> <MainFile.java> --line <N> --main <Class> \
  --eval "new int[]{1,2,3}[0]"   # today: EvaluationException NEW_ARRAY
```

### Regression targets

None yet — to be defined when the fix is scheduled.

---

## DB-7 — Breakpoints in a class outside the launched module resolve to no source (and only arm when the file is open)

**Status: accepted — Target: next (primary fix shipped; arming breakpoints in unopened files remains).**

Signal: user feedback — setting breakpoints across several project source files and debugging suspends
at seemingly "random" places along the execution path rather than at the set breakpoints. Reproduced
and diagnosed (below); it is **not** a staleness / line-table issue.

### Observed behaviour

With breakpoints spread across more than one class, a debug run appears to stop at locations that
follow the runtime flow but do not correspond to the breakpoints. In the editor a stop in another
module's class lands on a wrong/blank location because the adapter returns a stack frame with **no
source**, so nvim-dap cannot highlight where it actually stopped — it reads as a "random" halt.

### Reproduced (multi-module invoker workspace, fresh bytecode)

`dev/debug_probe.py` (extended with `--bp path:line`, multi-file) against the `multi-module` invoker
project, launching `com.example.app.Main` with breakpoints in `Main` (module `app`) and in
`StringUtils.upper` (module `core`, called at `Main:10`):

- Breakpoints in `Main` (the launched class's own module) all hit on their exact lines.
- The `StringUtils` breakpoint, when that file is **not open**, never fires — the debuggee runs past it.
- When `StringUtils.java` **is** opened, the breakpoint fires, but the stop frame comes back as
  `<NO-SOURCE>:8 (frame=StringUtils.upper(String))` — it stopped correctly, but with no source path.

Fresh `mvn process-test-classes` before the run (no edits) — so staleness is ruled out.

### Root cause (confirmed)

Two coupled defects in the debug source-lookup path, both from scoping the debug session to the
launched module:

1. **No cross-module source resolution.** `WorkspaceSession` builds the debug provider context with
   only the launched module's source roots
   (`configsFor(moduleRel).flatMap(c -> c.sourceRoots())`, WorkspaceSession.java ~474), where every
   other feature uses `workspace.allSourceRoots()`. So `LatheSourceLookUpProvider.getSource(fqn)` →
   `TypeSourceLocator.findSourceFile(fqn, sourceRoots)` returns `null` for any class in another reactor
   module, and the DAP stack frame has no source. This is the "cross-module reverse lookup is Phase 2"
   deferral noted in `LatheSourceLookUpProvider`'s javadoc, now biting in practice.
2. **Arming needs the file open.** `LatheSourceLookUpProvider.classNameAt` → `enclosingBinaryName`
   reads only the module worker's open-file analysis cache (`cache.get(uri)`), returning empty for a
   file that is not currently attributed — so a breakpoint in an unopened file gets no class name and is
   never armed.

### Fix direction

- **Primary — shipped:** the debug provider context now receives `workspace.allSourceRoots()` plus the
  manifest's dependency and per-module JDK source dirs, so cross-module (and library) frames resolve to
  their real source files.
  Defect 1 is fixed: a stop in another module's class shows the right file:line when its file is open.
- **Follow-up — remaining:** arm breakpoints in files that are not open — attribute/compile the
  breakpoint file on demand in the owning module worker rather than requiring the open-file cache.
  Today `LatheSourceLookUpProvider` logs a `[breakpoint] … not armed` warning and the breakpoint never
  suspends.

### Probe commands

```bash
# Multi-file breakpoints across modules; every requested file:line must appear in the actual stops:
python3 dev/debug_probe.py --workspace <ws> <app/Main.java> --main com.example.app.Main \
  --bp <app/Main.java>:8 --bp <core/StringUtils.java>:8
# Today: the core stop reports <NO-SOURCE>; with the primary fix it reports StringUtils.java:8.
```

### Regression targets

To be added with the fix (positive: a breakpoint in a different reactor module than the launched class
suspends and its frame resolves to that module's source file:line; negative: a same-simple-name class
in another module is not confused for the launched one).

---

# Neovim Client Gaps (NV)

Gaps in the shipped Neovim client plugin (`lua/lathe/…`) and its recommended configuration, as
distinct from the server's LSP/DAP surface. Resolved NV entries move to
[gaps-archive.md](gaps-archive.md).

---

No active NV gaps remain; resolved entries are in [gaps-archive.md](gaps-archive.md).

---

# MCP / Agent Facade Gaps (MC)

Gaps in the in-process `LatheEngine` facade and the `lathe-mcp-server` tool surface that exposes
Lathe to AI agents, as distinct from the LSP/DAP server behaviour those tools sit on top of.
Resolved MC entries move to [gaps-archive.md](gaps-archive.md).

## MC-26 — `search_symbols` finds types only, not methods or fields

**Status: documented**
Found by the agent-tooling landscape review (2026-10-24).

### Gap

IntelliJ's `search_symbol` finds classes, methods and fields, and gopls's `go_search` searches all
symbols. For "where is method X declared" Lathe has nothing, so the agent greps.

### Fix direction

Needs a member-name index; reactor members first (the reactor bytecode and shards hold most of the
data), dependencies later. Design first: index size and freshness on Helidon.
Related: [EG-057](#eg-057--symbol-search-matches-camelhumps-only-in-reactor-types-not-in-jdk-and-dependency-types).

### Regression targets

None yet.

---

## MC-27 — No view of a type's API by name: members, supertypes and the first javadoc line

**Status: documented**
Found by the agent-tooling landscape review (2026-10-24).

### Gap

To learn what a dependency's `Builder` offers, an agent must locate and read its source.
gopls's `go_package_api` and Serena's `find_symbol(depth=1)` answer that in one call.
Lathe's `describe_symbol` needs a position, and MCP has no type hierarchy.

### Fix direction

A `describe_type` tool taking a (qualified) type name: its supertypes, and each member's signature with
its first javadoc line, from javac's `Elements` — so it works for a dependency without sources too.
It also covers the type hierarchy MCP lacks.

### Regression targets

None yet.

---

## MC-28 — `rename_symbol` does not say what text it left unchanged

**Status: documented**
Found by the agent-tooling landscape review (2026-10-24).

### Gap

A rename changes the code that resolves to the symbol, and the javadoc references, but not the name in
string literals, comments, `META-INF/services` files or framework XML.
An agent believes the rename is complete.
A study of agent renames found location-only LSP renames missed such text in most multi-file renames
([arXiv 2608.13568](https://arxiv.org/abs/2608.13568); figures not checked).

### Fix direction

After the rename, list the remaining occurrences of the old name (qualified name for resources) in
reactor files as "not renamed — review", without editing them.

### Regression targets

None yet.

---

## MC-29 — No tool maps a stack trace to source

**Status: documented**
Found by the agent-tooling landscape review (2026-10-24).

### Gap

A failing test or a log gives an agent a stack trace; each frame names a class and a line, not a file.
LSP4J-MCP has `resolve_stack_trace`. Lathe's editor already resolves stack frames through the type
index, including package-private dependency types.

### Fix direction

A `resolve_stack_trace` tool turning each frame into a `path:line: code` line (reactor, generated,
dependency and JDK sources), and `run_test` failures could use it for their frames.

### Regression targets

None yet.

---

## MC-30 — Position tools need an exact column, which agents get wrong

**Status: documented**
Found by the agent-tooling landscape review (2026-10-24).

### Gap

Agents miscount columns; [MC-10](gaps-archive.md#mc-10--position-tools-answer-for-an-unrelated-symbol-when-the-position-is-not-on-one--done)
now makes them retry, at the cost of a round trip.
mcp-language-server takes a symbol name for `definition` and `references`, and Serena addresses
symbols by name path (`Class/method`).

### Fix direction

Cheap: accept `line` plus the symbol's text (`symbol`) instead of a column, resolving the column from
the line. Full: a name path (`pkg.Type#method(params)`), which needs overload handling.

### Regression targets

None yet.

---

## MC-31 — `get_definition` cannot return the definition's body

**Status: documented**
Found by the agent-tooling landscape review (2026-10-24).

### Gap

`get_definition` returns one line; reading a dependency or JDK method then takes a second read of a
cache path. mcp-language-server's `definition` returns the implementation, and Serena's
`find_symbol` has `include_body`.

### Fix direction

An optional `includeBody` returning the declaration's source range (the javac tree span), capped.

### Regression targets

None yet.

---

## MC-32 — Nothing makes an agent run `verify_change` before it reports done

**Status: documented**
Found by the agent-tooling landscape review (2026-10-24).

### Gap

An agent that skips `verify_change` finishes with code that does not compile.
Pyrefly documents a skill, an `AGENTS.md` directive and a Claude Code `Stop` hook for exactly this
([Pyrefly blog](https://pyrefly.org/blog/pyrefly-agentic-loop/)).

### Fix direction

Document an `AGENTS.md` / `CLAUDE.md` snippet and a hook recipe in the agent guide.
A hook cannot call an MCP tool, so a hook proper needs a command-line entry point to the engine —
a later, separate step.

### Regression targets

None yet.

---

## MC-33 — Tools return structured content but declare no `outputSchema`

**Status: deferred — Target: backlog**
Found by the agent-tooling landscape review (2026-10-24).

### Gap

The 2025-06-18 spec lets a tool declare the schema of its `structuredContent`
([changelog](https://modelcontextprotocol.io/specification/2025-06-18/changelog)); Lathe returns
structured content without one. It helps programmatic clients, not models, so it waits for a client
that needs it.

### Regression targets

None yet.

---

## MC-35 — Remedies that cannot help: a sync for a missing formatter, a file outside every source root, or a profile-excluded test

**Status: documented**
Found by the pre-release probes (2026-10-27) on Dropwizard and Helidon.

### Observed behaviour

- `format_file` on a project that pins no formatter (Dropwizard, Helidon) answers
  `No formatter for this workspace: the project pins none, or its formatter's jars are not resolved yet
  (run .lathe/lathe-sync.sh)`.
  `LatheTextDocumentService` already knows which case it is (no formatter spec means nothing is pinned),
  and an agent following the advice runs a 2–3 minute sync for nothing.
- `Not checked:` for `dropwizard/docs/LatheOut.java`, in no source root at all, says
  `is in no synced module — run .lathe/lathe-sync.sh`.
- `run_test` on a test in a module the build leaves out (Helidon `webserver/tests/static-content`, behind the
  `tests` profile) answers `no class test target in StaticContentTest.java`, as if the file held no test;
  `get_diagnostics` on the same file correctly says the module is outside the build.
  On a non-test file the same terse line gives no hint toward the matching test class.

### Fix direction

Give each case its own message: "the project pins no formatter" (with the `lathe-style.json` pointer),
"this file is in no source root", and "this module is not in the last build (a profile may add it)".
For a non-test file, name the test class that tests it when one exists.

### Regression targets

None yet.

---

## MC-36 — With no `.lathe/`, the MCP server exits before the handshake, so the agent never sees why

**Status: documented**
Found by the pre-release probes (2026-10-27).

### Observed behaviour

Started where no `.lathe/` is above the working directory, `LatheMcpServer` logs one `SEVERE` line,
`If this project is not set up for Lathe yet: for Maven, add the lathe-maven-extension and run …`, and exits
with status 2.
The line does not say what is missing or where it looked.
An MCP client shows only that the server failed to connect; the agent gets no tool, no instructions and no hint.

### Fix direction

Start anyway and answer the handshake: the server instructions and every tool say that no `.lathe/` was
found above the working directory, and give the setup remediation.
The log line names the missing `.lathe/` and the directory searched.

### Regression targets

None yet.

---

## MC-37 — `workspace_summary` is silent when `.lathe/` was written by another Lathe version

**Status: documented**
Found by the pre-release probes (2026-10-27) on EqualsVerifier.

### Observed behaviour

With `workspace.json` at an older `schemaVersion`, the server ignores the manifest.
`get_diagnostics` then ends with the right note, but with a doubled prefix:
`Sync required: Lathe: .lathe/ was written by another Lathe version (schema 5, expected 6)`.
`workspace_summary`, the tool the instructions tell an agent to call first, drops every `depends on`
edge and carries no note.

### Fix direction

`workspace_summary` carries the same `Sync required:` note; drop the inner `Lathe:` prefix.

### Regression targets

None yet.

---

## MC-38 — `search_symbols` lists JDK internals, and a glob query finds nothing

**Status: documented**
**Affects: MCP `search_symbols` and the editor's workspace symbols.**
Found by the pre-release probes (2026-10-27) on EqualsVerifier.

### Observed behaviour

`warning` returns, after the two reactor types, `com.sun.tools.javac.comp.ThisEscapeAnalyzer.Warning`,
`com.sun.tools.javac.comp.WarningAnalyzer`, `com.sun.tools.javac.util.JCDiagnostic.Warning` and
`javax.swing.plaf.metal.MetalBorders.WarningDialogBorder`: types in packages the JDK does not export, and
no code in the project can use them.
`*Checker` answers `No symbols matching '*Checker'`, although agents write globs by habit.

### Fix direction

Leave out JDK packages a module does not export, at least for a classpath module.
Strip a leading or trailing `*` (or treat the query as a glob).
Related: [EG-057](#eg-057--symbol-search-matches-camelhumps-only-in-reactor-types-not-in-jdk-and-dependency-types),
[MC-26](#mc-26--search_symbols-finds-types-only-not-methods-or-fields).

### Regression targets

None yet.

---

## MC-39 — `find_references` lists generated code before the user's call sites

**Status: documented**
**Affects: MCP `find_references`.**
Found by the pre-release probes (2026-10-27) on Helidon.

### Observed behaviour

On a Helidon builder method (`allowAll`), all of the first `maxResults=4` results are `[GENERATED]` builder
internals, and none is a user call site.
`rename_symbol` there correctly refuses generated code, but does not name the blueprint method to rename
instead.

### Fix direction

Order hand-written sources before generated ones (and production before tests, as `analyze_change`
counts them), so a capped answer shows what an agent edits.
When refusing a generated symbol, name its blueprint declaration when the generator links one.

### Regression targets

None yet.

---

## MC-40 — `verify_change` right after a rename recompiles for seconds

**Status: documented**
Found by the pre-release probes (2026-10-27) on EqualsVerifier and Dropwizard.

### Observed behaviour

`rename_symbol` already compiles the changed files into the mirror before it returns, yet the
`verify_change` that follows takes 6.8 s on EqualsVerifier (16 files) and 6.2 s on Dropwizard, and 18–23 s on
Dropwizard while a Helidon build runs on the machine.
The agent guide steers every rename into a `verify_change`, so each rename costs twice.

### Fix direction

Measure what `verify_change` recompiles after a rename; reuse the rename's compile results for the files it
already compiled and check only their callers.

### Regression targets

None yet.

---

## MC-41 — MCP edits written to a CRLF file add LF line endings

**Status: deferred — Target: backlog.**
Decision (2026-10-27): a real but narrow problem; a fix was written and reverted (see below).
**Affects: MCP `fix_imports`.**
Found by the pre-release probes (2026-10-27) on EqualsVerifier.

### Observed behaviour

Server edits break lines with `\n`, which is right for an editor: it applies them in its buffer, in the
document's own line separator.
`LatheEngine.applyFileEdits` writes them to disk verbatim, so in a CRLF file (Git for Windows'
default `core.autocrlf=true` checkout) `fix_imports` leaves mixed line endings:

```
package nl.jqno.equalsverifier;\r\n
\n
import java.util.Objects;\n
\r\n
public class LatheCrlf {\r\n
```

Only `fix_imports` inserts line breaks today: `rename_symbol` replaces identifiers, and `format_file`
writes the formatter's output, which keeps one separator.
The effect is small: `git` with `autocrlf=true` normalizes the lines on commit; a local Spotless
`check` (line endings from git attributes, so CRLF on such a machine) likely reports the file until it is
formatted; Vim and Emacs show `^M` on the original lines; VS Code picks one separator. Placement is
unaffected, since javac's line map counts `\r\n` as one break.
The tests do not cover it: their sources are text blocks, so LF even on the Windows CI row.

### Fix direction

Convert at the disk-write boundary only, never in the edits an editor applies (a `\r` inside Neovim
buffer lines): in `LatheEngine.applyFileEdits`, write each edit's line breaks in the file's separator,
normalizing a `\r\n` already in an edit first so a CRLF formatter result does not become `\r\r\n`.
Keep the check private to that method; the reverted attempt shared a `lineSeparatorOf` helper with
`EclipseFormatEngine` through `SourceLocator`, which was the wrong home.
Matching the separator of the line an edit lands next to, instead of a whole-file guess, would also
handle files that are already mixed.
Tests must build CRLF content explicitly (`"…\r\n"`): `fix_imports` and `format_file` on a CRLF file,
asserting no bare `\n` and no `\r\r`.

### Regression targets

None yet.
