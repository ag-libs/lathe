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
| `CQ-NNNN` | completion | Completion quality; checked against the completion [expectations](../planned/lathe-completion-expectations.md) contract |

## Finding the work for a release

The slice for a release is derived, not hand-maintained: every gap with `Status: accepted` and the
matching `Target` (see [gap-process.md](gap-process.md)).

```bash
grep -nE '^(Status|Target):|^\*\*Status' docs/gaps/gaps.md     # scan active entries
grep -n 'Target: M1' docs/gaps/gaps.md                         # the M1 slice
```

Entries follow, grouped by area: exploration (EG) below, then Find References (FR), Code Actions
(CA), and Completion (CQ).

EG-003 is deferred until after M2 because it requires `DocTrees` attribution of Javadoc comment
positions,
which is a non-trivial hover extension.

---

## EG-001 — Signature help selects the inner method's signature when the argument is itself a method call

**Status: done — Target: M1**

### Observed behaviour

When the cursor is positioned inside a method call's argument list and that argument is itself a
method call, `textDocument/signatureHelp` returns the signature of the **argument's** method
instead of the **containing** method.

```java
// CronTask.java
cron = parser.parse(config.expression());
//                  ↑ cursor here, after 'parse('
// expected: CronParser.parse(String expression)
// actual:   String expression()
```

```java
// TaskManagerImpl.java
tasks.put(task.id(), task);
//        ↑ cursor here, after 'put('
// expected: Map.put(K key, V value)
// actual:   String id()
```

Both cases are confirmed by `FINE` log lines:
`[signatureHelp] sig=String expression() param=0` and
`[signatureHelp] sig=String id() param=0`.

Simple-argument calls (constant or field reference as first argument) resolve correctly:
`LOGGER.log(` correctly shows 8 `System.Logger.log` overloads with active param 0.

### Root cause

The signature help algorithm must scan backward from the cursor position for the innermost
unmatched `(`.
When the cursor lands at the start of a method-call argument, there is no unmatched `(`
between the cursor position and the outer method's `(`.
The current implementation appears to scan forward or use the AST to detect the innermost
enclosing invocation, which incorrectly resolves to the argument's invocation rather than the
containing one when the cursor falls at the argument's start position.

### Proposed fix

In `SignatureHelpLocator` (or equivalent), identify the active method call by scanning backward
from the cursor for the nearest unmatched `(`, then resolve the method or constructor that owns
that `(`.
Forward scanning or AST-based inner-invocation detection must be gated to only fire once the
cursor is past the argument method's own `(`.

### Probe commands

```bash
printf 'sig after "parser.parse("\nlog 5\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/helidon/scheduling/src/main/java/io/helidon/scheduling/CronTask.java

printf 'sig after "tasks.put("\nlog 5\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/helidon/scheduling/src/main/java/io/helidon/scheduling/TaskManagerImpl.java
```

### Regression targets

`SignatureHelpTest.signatureHelp_outerCall_firstArgIsMethodCall_returnsOuterSignature`
`SignatureHelpTest.signatureHelp_mapPut_firstArgIsMethodCall_returnsMapPutSignature`

---

## EG-002 — Wrap-with-try/catch action absent for `UNREPORTED_EXCEPTION` in regular method bodies

**Status: done — Target: M1**

### Observed behaviour

When a method body contains a checked exception that is neither caught nor declared, the
code-action response returns only `"Add 'throws ...' to method"`.
`"Wrap with try/catch"` is never returned, even though `status.md` lists it as implemented.

```java
// triggered with: throw new IOException("x"); in a regular void method body
// didSave → diagnostic: UNREPORTED_EXCEPTION / java.io.IOException
// codeAction request → ["Add 'throws IOException' to method"]   // only this
//                      no "Wrap with try/catch" offered
```

This was tested in both Helidon and Dropwizard module method bodies (non-lambda context) using a
Python test script that called `didSave` with injected source and then called `codeAction`.

### Relationship to code-action gap CA-1

Code-action gap CA-1 (below) identifies this for the **lambda** case and proposes
`TryCatchWrapProvider` as the fix.
This gap confirms that `TryCatchWrapProvider` is absent entirely — the lambda-context
route is not the only missing branch; the baseline non-lambda method-body route is also missing.

`status.md` has been corrected: try/catch wrapping is **not implemented**.

### Proposed fix

Implement `TryCatchWrapProvider` as described in code-action gap CA-1 below.
The provider must handle both contexts:

- Regular method body: wrap the statement containing the throw or checked call in a
  `try { … } catch (ExceptionType e) { }` block.
- Lambda/anonymous-class body: same wrapping, targeted at the statement within the lambda.

Once `TryCatchWrapProvider` is implemented, the `AddThrowsProvider` lambda-suppression from
code-action gap CA-1 should be applied alongside it.

### Regression targets

`CodeActionTest.codeAction_unreportedException_methodBody_offersBothWrapAndThrows`
`CodeActionTest.codeAction_unreportedException_lambdaBody_offersOnlyWrap`

---

## EG-003 — Hover returns null on positions inside Javadoc type-reference tags

**Status: accepted — Target: M2**

### Observed behaviour

Pressing `K` (hover) on a type name inside a Javadoc `{@link …}` or `{@see …}` reference tag
returns no result.

```java
/**
 * ... {@link Scheduling} ...     ← hover on 'Scheduling' → null
 * @see TaskManager               ← hover on 'TaskManager' → null
 */
```

Type names at the same or nearby positions in source code resolve correctly.

### Root cause

The Javadoc region is not attributed for reference resolution.
The cursor position falls inside a `DocCommentTree` or raw comment block that javac does not
include in the attributed element table.
`HoverLocator` (or equivalent) receives a position whose `TreePath` resolves to a Javadoc
comment node, finds no attributed element, and returns null.

### Proposed fix

Two-phase lookup for positions inside Javadoc:

1. Detect that the cursor falls inside a `DocCommentTree` (by checking `DocTrees.getDocComment`
   and comparing character offsets).
2. Extract the referenced type name from the `{@link}`, `{@see}`, or `@throws` tag using
   `DocTrees.getElement(DocTreePath)`.
3. Delegate to the normal hover path with that resolved element.

This is a bounded change: only `HoverLocator` and possibly a helper on `SourceAnalysisSession`
need modification.

### Probe commands

```bash
printf 'hover "Scheduling"\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/helidon/scheduling/src/main/java/io/helidon/scheduling/Scheduling.java
```

### Regression targets

`HoverTest.hover_javadocLinkTag_resolvesReferencedType`
`HoverTest.hover_javadocSeeTag_resolvesReferencedType`

---

## EG-004 — Hover returns null on positions inside import declarations

**Status: done — Target: M1**

### Observed behaviour

`textDocument/hover` at a type name inside an import statement returns no result.

```java
import io.helidon.scheduling.TaskManager;
//                           ↑ hover here → null
```

Type names in class bodies or method bodies resolve correctly.

### Root cause

`HoverLocator` likely resolves the position to an `ImportTree` node, which does not carry an
attributed `Element` through the normal `Trees.getElement(TreePath)` path, or the element is
resolved as a package rather than a type.

### Proposed fix

In `HoverLocator`, detect the `ImportTree` case and extract the imported type element directly
from `Trees.getType(importPath)` or from the symbol table via `elements().getTypeElement(fqn)`.
Delegate to the normal hover path with that type element.

### Regression targets

`HoverTest.hover_importDeclaration_resolvesImportedType`

---

---

## EG-006 — Workspace symbol results rank reactor-local types below dependency and JDK types

**Status: done — Target: M1**

### Observed behaviour

```
sym "Application"  → 28 results:
  1. javax.ws.rs.core.Application  (JAX-RS dependency)
  2. org.glassfish.jersey.…Application  (Jersey dependency)
  3. com.sun.…ApplicationProtocolSelector  (JDK internal)
  4. io.dropwizard.core.Application  ← reactor-local type, rank 4
```

A developer working in the Dropwizard workspace and typing `Application` almost certainly
wants the project-local type first.

### Root cause

`WorkspaceTypeIndex` returns candidates sorted by simple name lexicographic order (or insertion
order) with no reactor-origin boost.
Dependency and JDK entries with the same name or an earlier alphabetic order naturally rank ahead
of reactor types.

### Proposed fix

Apply a sort-key boost to reactor-origin candidates.
The `TypeIndexEntry` or its source shard already carries origin information.
In the result comparator, assign reactor entries a higher primary sort key than dependency or JDK
entries when the simple name matches the query exactly.

### Regression targets

`WorkspaceTypeIndexTest.search_exactName_ranksReactorTypeBeforeDependencyType`

---

## EG-007 — Type-index startup emits hundreds of WARNING-level duplicate-type messages, obscuring real warnings

**Status: done — Target: M1**

### Observed behaviour

Every server start on both projects emits 150–200 WARNING lines:

```
WARNING  [type-index] org.objectweb.asm.Type duplicate type in shard … hierarchy navigation skipped
WARNING  [type-index] org.hamcrest.Matcher duplicate type in shard … hierarchy navigation skipped
WARNING  [type-index] org.junit.jupiter.api.Test duplicate type in shard … hierarchy navigation skipped
… (150+ more)
```

These types appear in both helidon and dropwizard because the projects' test-scoped JARs
(hamcrest, JUnit, ASM, Plexus, etc.) also exist on the lathe server's own classpath and are
indexed twice: once from the workspace's dependency shards and once from the server's own shards.

The volume of WARNING noise makes it impossible to spot genuine WARNING-level conditions such as
missing module metadata or a missing workspace config file.

### Root cause

`WorkspaceTypeIndex` merges multiple shard sources (dependency, JDK, reactor).
When the same fully-qualified type appears in more than one shard, it logs a WARNING per
duplicate.
Common test-infrastructure JARs (JUnit, Hamcrest, ASM) are almost always duplicated across the
server classpath and any workspace that uses the same testing stack.

### Fix

`WorkspaceTypeIndex.deduplicate()` keeps the first-seen entry per binary name and logs at `FINE`
for each skipped duplicate.
The `duplicateBinaryNames` tracking and the `isDuplicate()` method were removed; hierarchy
navigation now works for cross-shard duplicates since only one entry per binary name is stored.

### Regression targets

`WorkspaceTypeIndexTest.graph_duplicateBinaryName_withinShard_keepsFirst`
`WorkspaceTypeIndexTest.merge_duplicateTypeAcrossShards_keepsFirstEntry`

---

## EG-008 — Object synchronization methods appear in member-access completion results

**Status: done — Target: M1**

### Observed behaviour

Member-access completion on any object receiver includes `wait()`, `notify()`, and
`notifyAll()` as candidates, even in contexts where they are never useful.

```
complete after "handler.getServletContext()."  → 64 items
  ...
  wait   [Method]   void
  notify [Method]   void
  notifyAll [Method] void
```

These inherited `Object` methods appear at the bottom of the list but are rarely (if ever)
the intended completion.
Their presence adds noise without value and they can cause errors if accepted in an
incompatible synchronisation context.

### Relationship to the memory note on type filtering

A project-level note records that `java.lang` classes and `Object` methods (`wait`) wrongly
appear in argument positions, and that the type-index assignability filter is too expensive to
fix broadly.
This gap extends that observation to **member-access** completion results, where
`Object` synchronization methods are inherited by every type but should be suppressed by default.

### Proposed fix

Add `wait`, `notify`, and `notifyAll` to a static suppression list in `MemberAccessCandidates`
or the equivalent candidate filter layer.
These three methods are the canonical "do not show" set in Java IDEs (IntelliJ, JDT both suppress
them by default).
`hashCode`, `equals`, and `toString` are genuinely useful and should not be suppressed.
`getClass` is occasionally useful and may be suppressed or ranked last.

The suppression should apply to the member-access completion path only.
It should not affect other completion contexts.

### Probe command

```bash
printf 'complete after "handler.getServletContext()." filter wait\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/dropwizard/dropwizard-core/src/main/java/io/dropwizard/core/server/AbstractServerFactory.java
```

### Regression targets

`CompletionMemberAccessTest.memberAccess_anyReceiver_suppressesSynchronizationMethods`

---

## Status.md Correction Required

The current `status.md` LSP Capability Matrix states:

> Code actions | Implemented with M1 gaps | Missing imports, add throws,
> try/catch wrapping, and variable declaration work. …

Based on this session, the following items are **not working**:

| Claim | Actual state |
|---|---|
| try/catch wrapping works | `TryCatchWrapProvider` is not implemented; the action is never returned (EG-002) |
| variable declaration works | `DeclareVariableProvider` is not implemented; see code-action gap CA-2 below |

Both items should be removed from the "implemented" list in `status.md` until the providers
are in place and covered by tests.

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

## EG-009 — Outgoing calls includes anonymous class constructor instantiations with empty name

**Status: done — Target: M1**

### Observed behaviour

`callHierarchy/outgoingCalls` on a method that instantiates an anonymous class returns one extra
callee entry with an empty name and the declaring file as its URI.

```java
// CronTask.java — void run()
actualTask.run(new CronInvocation() { ... });
//              ^^^ anonymous class instantiation → callee with name="" uri=CronTask.java
```

Probe against Helidon `CronTask.run()` yields:

```
3 callee(s):
    scheduleNext  CronTask.java:88
    run           ScheduledConsumer.java:92
                  CronTask.java:92        ← empty name
```

### Root cause

`CallHierarchyOutgoingLocator` visits `NewClassTree` nodes.
When the instantiated type is an anonymous class, `SourceLocator.declarationName()` returns
`""` because `element.getSimpleName()` is empty for anonymous types.
`findSourceFile` resolves to the declaring file itself (the anonymous body is defined there),
so the entry is not suppressed by the missing-source-file guard.

### Proposed fix

In `CallHierarchyOutgoingLocator`, skip any `NewClassTree` whose resolved element is an
anonymous class (i.e., `element.getSimpleName().isEmpty()`).
Anonymous class instantiations are not meaningful callee targets in a call hierarchy view.

### Regression target

`CallHierarchyOutgoingLocatorTest.outgoingCalls_anonymousClassInstantiation_excludedFromResults`

### Related observation

The same anonymous-class empty-name pattern also appears in document symbols.
Probing Dropwizard `PersonResourceTest.java` returns a blank class symbol for
`new GenericType<List<Person>>() {}`:

```text
[Method] testGetImmutableListOfPersons  63:10
  [Class]   64:104
```

Do not open a separate gap record for this until the anonymous-class naming policy is revisited.
Either anonymous classes should be suppressed from user-facing symbol/call outputs,
or they should use a stable synthetic label.

---

## EG-010 — `explore.py` cannot probe dep/JDK source files — no workspace context for cache paths

**Status: done — Target: M1**

### Observed behaviour

Attempting to open a dependency or JDK source file in `explore.py` by passing its path on the
command line fails immediately:

```
error: No .lathe/ directory found above /home/ag-libs/.cache/lathe/deps/io.dropwizard.metrics:metrics-core:4.2.38/com/codahale/metrics/MetricRegistry.java
```

This blocks two probe scenarios:
- Finding **callers of dep/JDK methods** (e.g. all reactor callers of `MetricRegistry.register`)
  by opening the dep source file and running `callers`.
- Finding **callers of JDK methods** by opening JDK source from the cache.

The limitation also applies to the `refs` command, making it impossible to probe
external-source reference scope from `explore.py`.

### Root cause

`explore.py` derives the workspace root from the opened file path by walking up the directory
tree looking for a `.lathe/` directory.
Dependency source files are extracted to `~/.cache/lathe/deps/<gav>/` and JDK sources to
`~/.cache/lathe/jdks/<jdk-key>/`, neither of which are workspace directories.
`find_workspace_root` exhausts the tree and raises an error before the LSP server is started.

### Proposed fix

Add a `--workspace <path>` argument to `explore.py`.
When provided, it overrides `find_workspace_root` and is passed directly to `initialize`.
This allows callers to pair any source file (dep cache, JDK cache, absolute path) with any
workspace root, enabling:

```bash
python3 dev/explore.py --workspace /home/ag-libs/git/dropwizard \
    /home/ag-libs/.cache/lathe/deps/io.dropwizard.metrics:metrics-core:4.2.38/com/codahale/metrics/MetricRegistry.java
callers "register" min 1
```

### Regression targets

`explore.py --workspace` integration test or inline doc-test in the script's own test section.

---

## EG-011 — Outgoing calls silently omits callees whose source is in extracted dep or JDK dirs

**Status: accepted — Target: M2**

### Observed behaviour

`callHierarchy/outgoingCalls` on a method that calls into a dependency type returns only reactor
callees; dep and JDK callees are silently absent.

Probing `Bootstrap.registerMetrics()` (which calls `MetricRegistry.register()`) yields:

```
1 callee(s):
    getMetricRegistry  Bootstrap.java:218
```

`MetricRegistry.register(String, Metric)` is not shown even though the Dropwizard workspace has
207 extracted dependency sources and `MetricRegistry.java` exists at
`~/.cache/lathe/deps/io.dropwizard.metrics:metrics-core:4.2.38/com/codahale/metrics/MetricRegistry.java`.

### Root cause

`WorkspaceSession.outgoingCallsFuture` passes `workspace.allSourceRoots()` to
`CallHierarchyOutgoingLocator`.
`allSourceRoots()` returns only reactor module source directories.
`DefinitionLocator.findSourceFile` therefore cannot resolve callees whose source lives under
`~/.cache/lathe/deps/` or `~/.cache/lathe/jdks/`, so those callees are silently dropped.

This is the mirror limitation to external-source Find References scope (M2).

### Proposed fix

Include extracted dependency and JDK source directories in the search roots passed to
`CallHierarchyOutgoingLocator`, paralleling the M2 work to expand Find References scope.
`WorkspaceModuleRegistry` or `Workspace` already tracks `dependencySources` with their `dir`
fields; exposing them alongside `allSourceRoots()` would let `findSourceFile` resolve dep/JDK
callees.

The callee items returned for dep/JDK targets should be marked with `SymbolKind.Method` and
have the cache path as their `uri`, consistent with how `definition` navigates to dep sources.

### Regression targets

`CallHierarchyServiceTest.outgoingCalls_calleeInDependencySource_returnsDepCallee`

---

## EG-013 — Find References candidate discovery excludes generated annotation sources

**Status: done — Target: M2**

### Observed behaviour

Find References on a record component never returns the generated `@Builder` class that uses that
component, even when the generated builder calls the component's accessor.

Probed against the sample-workspace workspace, which generates a `*Builder` per `@Builder` record
under each module's `target/generated-sources/annotations/`:

```
refs "requestId,"  on Entity (app-alpha, builder present)
  → progress: 0 / 1 candidates       ← only the record's own file is a candidate
  → 1 reference (the accessor call inside Entity itself)
  → EntityBuilder.builder(existing) calls existing.requestId() but is never found

refs "customerReference,"  on CreateEntity (app-core)
  → progress: 0 / 1 candidates
  → 0 references
```

The decisive signal is the candidate count: `0 / 1 candidates`.
`EntityBuilder.builder(Entity existing)` contains
`builder.requestId = existing.requestId();`, so the generated file does reference the accessor,
yet it is never even offered as a candidate to scan.

### Root cause

Two scopes are inconsistent:

- **Resolution scope** — `WorkspaceModuleRegistry.allSourceRoots()` already includes the generated
  directory (`ModuleSourceConfig.originalGenSourcesDir()`, which points at
  `target/generated-sources/annotations`) when it is non-null.
- **Candidate discovery** — `ReferenceCandidateIndex.build(...)` builds the token-to-file index
  from `config.sourceRoots()` only, which contains just `src/main/java`.
  The generated directory is never tokenized, so generated files never appear in the candidate
  set.

The server can resolve a reference that lives in a generated builder, but candidate discovery
filters those files out first, so the reference search never reaches them.
This is independent of the editor and of explore.py positioning — it is in the index.

### Proposed fix

In `ReferenceCandidateIndex.build(...)`, include each config's `originalGenSourcesDir()` in the
set of indexed roots, mirroring the exact logic already used by
`WorkspaceModuleRegistry.allSourceRoots()`:

```java
allConfigs.stream()
    .flatMap(
        config ->
            config.originalGenSourcesDir() != null
                ? Stream.concat(
                    config.sourceRoots().stream(), Stream.of(config.originalGenSourcesDir()))
                : config.sourceRoots().stream())
    .distinct()
    .filter(Files::isDirectory)
```

Returning `target/generated-sources/annotations/...` URIs from a reference search is consistent
with how `textDocument/definition` already navigates into generated sources via the same
`allSourceRoots()` scope.

### Related observation — incomplete `.lathe` generated-sources mirror

Separately, the `.lathe/<module>/generated-sources` mirror is inconsistent across modules
(`app-core` 0 of 208, `app-api` 0 of 46, `app-config` 0 of 108; `app-alpha` 14 of 14;
`app-server` 6 of 120).
This mirror feeds `ModuleSourceCompiler`, not the reference candidate path (which reads the
original `target` directory), so it is **not** the cause of this gap.
It appears generated sources are only captured for modules that actually recompiled during the
lathe-instrumented build, and warrants a separate investigation under the build/sync lifecycle.

### Probe commands

```bash
python3 dev/explore.py \
    /workspace/app-alpha/src/main/java/com/example/app/alpha/Entity.java \
    refs "requestId," min 2 expect "EntityBuilder.java"
```

### Regression targets

- `ReferenceCandidateIndexTest.build_includesGeneratedSourcesDir_whenPresent`
- `ReferenceServiceTest.references_recordComponent_findsGeneratedBuilderUsage`

---

## EG-015 — Override/implement completion missing in class bodies

**Status: accepted — Target: M2**

### Observed behaviour

Typing a method-name prefix inside a class body offers only type-name candidates; no
override-stub completion item is ever returned.

Probed against `DummyAdapter`, which extends a base class and implements an
interface:

```
inject "toString"  in class body
  → 9 items, all types: ToString, ToStringStyle, ToStringBuilder, ToStringSerializer, …
  → no "@Override public String toString() { … }" stub

inject "createP"   in class body  (createPin is an inherited contract method)
  → 8 items, all types: CreatePartitionsResult, CreatePartitionsOptions, …
  → no override stub for createPin
```

A developer typing a method name in a class body expects an override/implement completion that
inserts the full overriding signature with `@Override`, as IntelliJ and Eclipse JDT do.

### Root cause

The completion engine has providers for simple names, types, imports, keywords, and members, but
no provider that enumerates overridable methods from the enclosing type's supertypes.
The enclosing-type and supertype information is already available (the same walk used by type
hierarchy and proposed for EG-012), but it is not wired into a completion provider.

### Relationship to `MissingMethodImplProvider`

This is the completion-driven path to implementing a method.
The code-action-driven path (`MissingMethodImplProvider`) is a separate, also-unimplemented
M1 blocker.
With both absent there is currently no assisted way to implement or override a method.

### Proposed fix

Add an override-completion provider that, when the cursor is at a member-declaration position in a
class body, enumerates the inherited methods of the enclosing `TypeElement` (via supertype walk
plus `Object` methods), filters to those overridable and matching the typed prefix, and returns
completion items whose insert text is the full overriding signature annotated with `@Override`.

### Probe commands

```bash
python3 dev/explore.py \
    /workspace/app-server/src/main/java/com/example/app/server/operator/dummy/DummyAdapter.java \
    inject "toString" at 46 expect toString
```

### Regression targets

- `CompletionOverrideTest.completion_methodPrefixInClassBody_offersOverrideStub`
- `CompletionOverrideTest.completion_objectMethodPrefix_offersToStringOverride`

---

## EG-016 — Annotation-member completion missing

**Status: accepted — Target: M2**

### Observed behaviour

Completion inside an annotation's parentheses returns nothing.

```
inject "@JsonProperty("  before a record component
  → (no completions returned)
```

Developers expect the annotation's element names (`value`, `required`, `defaultValue`, …) and,
for enum-valued elements, the permitted constants.

This workspace is annotation-heavy — 112 `@JsonProperty`, 125 `@Path`, plus Swagger,
`@RolesAllowed`, and Jackson XML annotations — so annotation-member completion is a frequent need.

### Root cause

The completion engine has no annotation-context provider.
When the cursor is inside an `AnnotationTree`'s argument list, no candidate generator recognises the
context, so the engine returns an empty result.

### Proposed fix

Add an annotation-member provider that detects the `AnnotationTree` enclosing the cursor, resolves
its annotation `TypeElement`, and offers its `ExecutableElement` members as completion items
(`name = ` insert text).
For an element whose type is an enum, additionally offer the enum constants once the cursor is past
the `=`.

### Probe commands

```bash
python3 dev/explore.py \
    /workspace/app-core/src/main/java/com/example/app/model/CreateEntity.java \
    inject "@JsonProperty(" at 14 expect value
```

### Regression targets

- `CompletionAnnotationTest.completion_insideAnnotationArgs_offersElementNames`
- `CompletionAnnotationTest.completion_enumValuedElement_offersConstants`

---

## EG-017 — `textDocument/documentHighlight` not implemented

**Status: accepted — Target: M2**

### Observed behaviour

The server does not advertise `documentHighlightProvider`, and no handler exists.
Cursor-occurrence highlighting — the read/write highlight an editor draws for every occurrence of
the symbol under the cursor as the cursor rests — is therefore unavailable.

### Root cause

`LatheLanguageServer.initialize` registers no `documentHighlightProvider`, and there is no
`documentHighlight` request handler in the server.

### Proposed fix

Implement `textDocument/documentHighlight` as a file-scoped specialisation of the existing exact
same-file reference matching.
Reuse the `ReferenceTarget` identity already used by Find References, restrict the scan to the
current document, and map each occurrence to a `DocumentHighlight` with `Read` or `Write` kind
based on whether the occurrence is an assignment target.

This is the highest value-to-effort item in this set: the same-file matching machinery already
exists, and the feature is exercised continuously during normal editing.

### Probe commands

Not probeable through `explore.py` (no `documentHighlight` command); confirmed by the absent
capability and the absent handler in `LatheLanguageServer`.

### Regression targets

- `DocumentHighlightTest.documentHighlight_localVariable_highlightsReadAndWriteOccurrences`
- `DocumentHighlightTest.documentHighlight_methodName_highlightsSameFileCalls`

---

## EG-018 — `textDocument/selectionRange` not implemented

**Status: accepted — Target: M2**

### Observed behaviour

The server does not advertise `selectionRangeProvider`, and no handler exists.
Expand-selection and shrink-selection (a common editing keystroke) are unavailable.

The `selectionRange` occurrences in the server source are unrelated: they are the
`DocumentSymbol.selectionRange` and `CallHierarchyItem.selectionRange` fields, not the
`textDocument/selectionRange` feature.

### Root cause

`LatheLanguageServer.initialize` registers no `selectionRangeProvider`, and there is no
`selectionRange` request handler.

### Proposed fix

Implement `textDocument/selectionRange` syntactically.
For each requested position, walk the enclosing `TreePath` from the leaf outward and emit a nested
chain of `SelectionRange` entries (identifier → expression → statement → block → member → type).
This needs only source positions, not type resolution, so it can run on the parsed tree without a
full attribution pass.

### Probe commands

Not probeable through `explore.py` (no `selectionRange` command); confirmed by the absent
capability and the absent handler in `LatheLanguageServer`.

### Regression targets

- `SelectionRangeTest.selectionRange_insideExpression_returnsNestedSyntacticRanges`
- `SelectionRangeTest.selectionRange_atMethodName_expandsToMemberThenType`

---

## EG-019 — Unused-declaration diagnostic message is the bare word `Unused`

**Status: done — Target: M1**

### Observed behaviour

The unused-declaration hint carries the correct `Unnecessary` tag but a non-descriptive message and
a null code.

Probed against `RegionAdapter.java`, where local variable `billingStatus` is unused:

```json
{"severity": 4, "code": null, "source": "lathe", "message": "Unused", "tags": [1],
 "range": {"start": {"line": 236, "character": 20}, "end": {"line": 236, "character": 29}}}
```

`tags: [1]` (`DiagnosticTag.Unnecessary`) is correctly present, so editors do gray the
declaration.
The message is the single word `Unused`, and `code` is `null`.

### Root cause

The unused-declaration scan emits a fixed `"Unused"` message and sets no diagnostic `code`.
It does not distinguish the kind of declaration (local variable, private field, private method) or
include the declaration name.

### Proposed fix

Produce a descriptive message that names the declaration and its kind, for example
`Unused local variable 'billingStatus'`, `Unused private method 'foo'`, and set a stable
diagnostic `code` (for example `lathe.unused`) so clients can filter the hint and map it to a
future remove-declaration quick fix.
Keep the `Unnecessary` tag.

### Probe command

```bash
python3 dev/lsp.py \
    /workspace/app-server/src/main/java/com/example/app/server/operator/region/RegionAdapter.java
```

### Regression targets

- `UnusedDiagnosticTest.unused_localVariable_messageNamesVariableAndKind`
- `UnusedDiagnosticTest.unused_diagnostic_setsStableCode`

---

## EG-020 — `module-info.java` and `package-info.java` return no document symbols, folding ranges, or semantic tokens

**Status: done**

### Observed behaviour

`textDocument/documentSymbol`,
`textDocument/foldingRange`,
and `textDocument/semanticTokens/full` return no useful structural results for Java info files,
even when those files have meaningful structure and declarations worth highlighting.

Helidon `module-info.java`:

```bash
printf 'symbols min 1\nfolds min 1\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/helidon/health/health/src/main/java/module-info.java
```

Observed:

```text
(no document symbols returned)
[FAIL]
  ✗  expected ≥1 items, got 0
(no folding ranges returned)
[FAIL]
  ✗  expected ≥1 items, got 0
```

Helidon `package-info.java`:

```bash
printf 'symbols min 1\nfolds min 1\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/helidon/health/health/src/main/java/io/helidon/health/package-info.java
```

Observed:

```text
(no document symbols returned)
[FAIL]
  ✗  expected ≥1 items, got 0
(no folding ranges returned)
[FAIL]
  ✗  expected ≥1 items, got 0
```

Controls in the same probing session showed ordinary Java files work:
Helidon `HealthCheck.java` returned 5 document symbols and 4 folding ranges,
and Dropwizard `PersonResourceTest.java` returned 18 document symbols and 15 folding ranges.

Direct semantic-token probes show the same special-file gap:

```bash
python3 -c 'import sys; from pathlib import Path; sys.path.insert(0,"dev"); from lsp import LatheClient, find_workspace_root; files=[Path("../helidon/health/health/src/main/java/module-info.java"), Path("../helidon/health/health/src/main/java/io/helidon/health/package-info.java"), Path("../dropwizard/dropwizard-testing/src/test/java/io/dropwizard/testing/junit5/PersonResourceTest.java")];
for f in files:
    with LatheClient.start(find_workspace_root(f), debug=False) as c:
        d=c.open(f); r=c.request("textDocument/semanticTokens/full", {"textDocument":{"uri":f.resolve().as_uri()}}); print(f.name, "diag", len(d), "tokens", 0 if not r else len(r.get("data",[]))//5)'
```

Observed:

```text
module-info.java diag 0 tokens 0
package-info.java diag 0 tokens 0
PersonResourceTest.java diag 0 tokens 50
```

An extracted dependency-source control also returned semantic tokens:
cached Dropwizard Metrics `MetricRegistry.java` returned 120 tokens.

### Expected behaviour

`module-info.java` should expose at least a module document symbol and folding ranges for the module
body and directive groups where source ranges are available.
Annotated module descriptors should also make the leading annotation block foldable.
Semantic tokens should highlight module annotations,
directive keywords,
module names,
exported package names,
and service/provider type names where the LSP token legend has a suitable token type.

`package-info.java` should expose at least a package document symbol.
Its package Javadoc should be foldable,
and any package annotations should participate in the fold range when present.
Semantic tokens should highlight package annotations and the package declaration at minimum.

### Related navigation observation

Package declarations are also weak in hover/definition:
hover on package-name segments reports only the package name,
and definition returns no target for both ordinary package declarations and `package-info.java`.
That is not split out as a separate open gap here to avoid duplicating package-info handling work.

### Proposed fix

Extend the document-symbol,
folding-range,
and semantic-token providers to handle compilation units whose primary top-level declaration is a
module declaration or package declaration rather than a class,
interface,
enum,
record,
or annotation type.

### Regression targets

- `DocumentSymbolTest.documentSymbol_moduleInfo_returnsModuleSymbol`
- `DocumentSymbolTest.documentSymbol_packageInfo_returnsPackageSymbol`
- `FoldingRangeTest.foldingRange_moduleInfo_returnsModuleBodyRange`
- `FoldingRangeTest.foldingRange_packageInfo_returnsJavadocRange`
- `SemanticTokensTest.semanticTokens_moduleInfo_returnsModuleDescriptorTokens`
- `SemanticTokensTest.semanticTokens_packageInfo_returnsPackageDeclarationTokens`

---

## EG-021 — Type-name completion ranks reactor-local types below dependency and JDK types

**Status: accepted — Target: M2**

### Observed behaviour

Type-name completion ranks dependency and JDK types ahead of project-local types.

```
inject "Object o = new Oper"  (in a app-server file)
  → org.mvel2.*, com.sun.xml.ws.*, com.mysql.cj.* candidates rank above
    com.example.app.* reactor-local types
```

A developer authoring code in the reactor almost always wants the project-local type first.

### Root cause

The type-completion candidate comparator does not boost reactor-origin entries.
This is the completion-context analog of EG-006, which covers the same mis-ranking in workspace
symbol search; the two share the underlying `WorkspaceTypeIndex` ordering.

### Proposed fix

Apply a reactor-origin sort boost in the type-completion result comparator, reusing the
`TypeIndexEntry` origin information proposed for EG-006.
Reactor entries should outrank dependency and JDK entries for an equal prefix match.

### Probe commands

```bash
python3 dev/explore.py /path/to/Scratch.java inject "Object o = new Oper"
```

### Regression targets

- `CompletionTypeRankingTest.completion_typePrefix_ranksReactorTypeFirst`

---

## EG-022 — Sealed-type `switch`/`case` pattern completion offers arbitrary types

**Status: accepted — Target: M2**

### Observed behaviour

Inside a `switch` over a sealed reference type, `case` completion offers arbitrary types instead of
the type's permitted subtypes as pattern labels.

```java
String handle(OperationResponse r) {   // sealed interface, 8 permitted subtypes
  switch (r) {
    case ▮          // → 112 items: StrictMath, Short, ScopedValue, RuntimePermission, …
                    //   none of the 8 permitted subtypes are offered as patterns
  }
}
```

Enum `case` completion works correctly in the same session (a `switch` over a `ResultCode` enum
offers all 48 constants), so the gap is specific to sealed/reference-type pattern labels.

### Root cause

The `case`-label completion path handles the enum-constant case but does not recognise a `switch`
selector whose type is a sealed reference type.
It falls back to general type completion, which dumps the type index unranked.

### Proposed fix

When the enclosing `switch` selector resolves to a sealed type (or any reference type), offer its
permitted subtypes (for sealed types) or assignable subtypes as type-pattern `case` labels, with
insert text of the form `case SubType name ->`.

### Probe commands

```bash
python3 dev/explore.py /path/to/Scratch.java inject "case " at <line-in-sealed-switch>
```

### Regression targets

- `CompletionCaseTest.completion_caseInSealedSwitch_offersPermittedSubtypes`
- `CompletionCaseTest.completion_caseInEnumSwitch_unchanged`

---

## EG-023 — `this.` completion leaks low-value `Object` methods

**Status: done — Target: M2**

### Observed behaviour

Member completion on `this.` offers `clone`, `finalize`, `notify`, `notifyAll`, and `wait`, while
value-receiver member-access suppresses them.

```
inject "names."   (List<String> field receiver)
  → no clone / finalize / notify / notifyAll / wait

inject "this."
  → clone, finalize, notify, notifyAll, wait(), wait(long), wait(long, int) all present
```

### Root cause

The Object-method suppression that EG-008 applies on the value-receiver member-access path is not
applied on the `this.` (and likely `super.`) completion path.
The suppression list is keyed to one candidate-generation route and is not shared across all
member-completion routes.

### Relationship to EG-008

EG-008 covers suppressing `wait`/`notify`/`notifyAll` on member-access results.
This gap is the same suppression list applied inconsistently: it must also cover the `this.` and
`super.` routes, and should additionally consider `clone` and `finalize`.
Implement alongside EG-008.

### Probe commands

```bash
python3 dev/explore.py /path/to/Scratch.java inject "this."
```

### Regression targets

- `CompletionThisTest.completion_thisReceiver_suppressesObjectInternalMethods`

---

## EG-024 — Type-name completion can offer types from modules the current module does not read

**Status: accepted — Target: M2**

### Scope correction

An earlier version of this record claimed that transitive-dependency types such as
`org.mvel2.*`, `com.sun.xml.ws.*`, and `com.mysql.cj.*` were offered but not importable from
`app-server`.
That claim was wrong.
`app-server/module-info.java` explicitly declares `requires mvel2`, `requires com.sun.xml.ws`,
`requires mysql.connector.j`, and `requires jakarta.xml.ws`, so those types are genuinely readable
and importable; offering them is correct (their volume and ranking are covered by EG-021, not
here).
The actual gap is narrow and is described below.

### Observed behaviour

Type-name completion can offer a type whose package is not readable from the current module, which
lathe's own diagnostics then reject if the candidate is accepted.

Confirmed by opening a `app-server` file that imports three candidates that completion offered for
the `Oper` prefix:

| Import | lathe diagnostic on open |
|---|---|
| `org.mvel2.ast.OperativeAssign` | none — importable (`requires mvel2`) |
| `com.sun.xml.ws.wsdl.OperationDispatcher` | none — importable (`requires com.sun.xml.ws`) |
| `com.sun.management.OperatingSystemMXBean` | ERROR: `package com.sun.management is not visible (declared in module jdk.management)` |

`com.sun.management.OperatingSystemMXBean` was offered by completion (as
`new Oper` candidate `OperatingSystemMXBean [Interface] com.sun.management.OperatingSystemMXBean`),
but `app-server` does not read `jdk.management`, so accepting it produces a not-visible error that
lathe reports correctly.

The completion candidate set is therefore slightly broader than the module graph allows.
The discrepancy is limited to modules the current module does not read — in practice JDK modules
(and any dependency) that are present on the analysis path but not in the module's `requires`
graph.
It is not the transitive-dependency flood originally described.

### Root cause

The workspace type index includes every type on the combined classpath and modulepath.
Type completion does not intersect candidates with the set of packages readable from the current
source module, so a type from a present-but-unread module can appear.
lathe's javac-backed diagnostics already enforce module readability, so the inconsistency is
between the completion candidate set and the compiler's own accessibility rules.

### Proposed fix

For modular sources, restrict type-completion candidates to types whose package is exported by a
module the current module reads (directly or via `requires transitive`).
This requires resolving the module readability graph for the current source module — the same
information javac already uses to produce the not-visible diagnostic — and intersecting candidates
against it.

This is a usefulness refinement, not a correctness blocker: accepting an unreadable candidate
yields an immediate, accurate diagnostic, so the user is not silently misled.

### Probe commands

```bash
# Open a app-server file importing com.sun.management.OperatingSystemMXBean and confirm the
# not-visible diagnostic, then confirm completion still offers the type for the "Oper" prefix.
python3 dev/lsp.py /path/to/ScratchImports.java
python3 dev/explore.py /path/to/Scratch.java inject "Object o = new Oper"
```

### Regression targets

- `CompletionTypeFilterTest.completion_modularSource_excludesUnreadableModuleTypes`
- `CompletionTypeFilterTest.completion_modularSource_keepsRequiredModuleTypes`

---

## EG-025 — Stale class files from removed or renamed types are never cleaned up

**Status: done — Target: M1**

### Observed behaviour

When a source file is **edited** to remove or rename any type — a nested class, anonymous class,
local class, or package-private sibling top-level type — the corresponding `.class` file persists
indefinitely in `latheClassesDir`.

`deleteClassOutputs()` is called only on file deletion (`didClose` with a removed path),
where it removes `Foo.class`, `Foo$Inner.class`, etc. matching the deleted source.
There is no equivalent cleanup triggered by a successful recompilation of a file that still exists.

A stale `Foo$OldInner.class` or `Helper.class` that no longer corresponds to any source type
remains in `latheClassesDir`, where `ClassFileTypeScanner` picks it up and adds the stale type to
the reactor type index.
This causes removed or renamed types to continue appearing in type-name completion, workspace symbol
search, and type-hierarchy results until the server restarts.

### Scope

The problem affects all class file types produced from a single source file:

| Type | Class file | Covered by `Foo$` prefix? |
|---|---|---|
| Named inner class | `Foo$Inner.class` | Yes |
| Anonymous class | `Foo$1.class` | Yes |
| Local class | `Foo$1LocalName.class` | Yes |
| Package-private sibling | `Helper.class` | **No** |

### Decided implementation — `task.generate()` return value

`JavacTask.generate()` is a public `com.sun.source.util` API already used by Lathe.
It returns `Iterable<? extends JavaFileObject>` — the exact set of class files javac wrote during a
compile, including all `Foo$Inner.class`, `Foo$1.class`, and AP-generated `Foo$Something.class`
files produced in the same run.

The implementation requires changes to three existing classes only.
No new classes, no sidecar files, no in-memory state map.

1. **`JavacRunner.compileFull()`** — switch from `task.call()` to `task.analyze()` +
   `task.generate()`.
   Collect the returned `JavaFileObject`s; convert each to a binary name by resolving its URI
   relative to `latheClassesDir`, replacing `/` with `.`, and stripping the `.class` suffix.

2. **`CompilerResult`** — add `Set<String> writtenBinaryNames` field
   (`Set.of()` for `FAST`/`OPEN`, populated from `task.generate()` for `FULL`).

3. **`WorkspaceSession.deleteStaleClassOutputs(config, savedSource, writtenBinaryNames)`** —
   replace the stub: scan the package dir for files matching `Foo$*.class`; delete any whose binary
   name is absent from `writtenBinaryNames`.
   Call this from `onSave` after `FULL` compile, before `refreshReactorShard`.

AP-generated `Foo$Something.class` files are safe: they are written during the same compile that
produces `Foo.class`, so they appear in `task.generate()` output and are retained.

The package-private sibling case (`Helper.class`) is a documented gap.
`Helper.class` carries no `Foo$` prefix so `deleteStaleClassOutputs` never considers it.
Accepting this gap avoids source-root inference, which would incorrectly delete AP-generated
files that share a name with a type from another source file.

### Regression tests

Disabled tests in `WorkspaceSessionTest` cover the expected behaviour of `deleteStaleClassOutputs`:

- `deleteStaleClassOutputs_namedInnerClassRemoved_deletesStaleClassFile`
- `deleteStaleClassOutputs_anonymousClassRemoved_deletesStaleClassFile`
- `deleteStaleClassOutputs_outerClass_isUntouched`
- `deleteStaleClassOutputs_sibling_isUntouched`
- `deleteStaleClassOutputs_packagePrivateSiblingRemoved_deletesStaleClassFile`

---

## EG-026 — Workspace symbol search excludes test classes

**Status: done — Target: M1**

### Observed behaviour

`workspace/symbol` does not return classes declared under `src/test/java`,
even though those files open,
compile,
and produce document symbols.

Dropwizard probe:

```bash
printf 'sym PersonResourceTest expect PersonResourceTest min 1\nsym DropwizardAppExtensionRandomPortsConfigOverrideTest expect DropwizardAppExtensionRandomPortsConfigOverrideTest min 1\nsym ResourceExtension expect ResourceExtension min 1\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/dropwizard/dropwizard-testing/src/test/java/io/dropwizard/testing/junit5/PersonResourceTest.java
```

Observed:

```text
(no symbols found for 'PersonResourceTest')
[FAIL]
  ✗  expected label starting with 'PersonResourceTest' — not found
  ✗  expected ≥1 items, got 0
(no symbols found for 'DropwizardAppExtensionRandomPortsConfigOverrideTest')
[FAIL]
  ✗  expected label starting with 'DropwizardAppExtensionRandomPortsConfigOverrideTest' — not found
  ✗  expected ≥1 items, got 0
1 symbol(s) for 'ResourceExtension':
  [Class] ResourceExtension  io.dropwizard.testing.junit5  /home/ag-libs/git/dropwizard/dropwizard-testing/src/main/java/io/dropwizard/testing/junit5/ResourceExtension.java
[PASS]
```

The same file's document symbols are present:

```bash
printf 'symbols expect PersonResourceTest min 1\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/dropwizard/dropwizard-testing/src/test/java/io/dropwizard/testing/junit5/PersonResourceTest.java
```

Helidon shows the same workspace-symbol omission:

```bash
printf 'symbols expect DiskSpaceHealthCheckTest min 1\nsym DiskSpaceHealthCheckTest expect DiskSpaceHealthCheckTest min 1\nsym DeadlockHealthCheckTest expect DeadlockHealthCheckTest min 1\nsym MemoryHealthCheckTest expect MemoryHealthCheckTest min 1\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/helidon/health/health-checks/src/test/java/io/helidon/health/checks/DiskSpaceHealthCheckTest.java
```

Observed controls:
`DiskSpaceHealthCheckTest` returns document symbols,
but `DiskSpaceHealthCheckTest`,
`DeadlockHealthCheckTest`,
and `MemoryHealthCheckTest` return no workspace-symbol results.

Type-name completion is more mixed:
it can offer the open test class in some contexts,
but sibling test classes are not reliably offered.
That points at workspace type-index coverage rather than a general parser or file-open failure.

### Expected behaviour

Workspace symbol search should include reactor test classes.
Test types are part of the active editing workspace,
and `docs/status.md` already states that classes,
test classes,
and generated sources are mirrored under `.lathe/`.

Search results should preserve the existing source-origin ordering rules once `EG-006` is fixed:
reactor test classes should rank as reactor-local entries,
not as dependency or external entries.

### Proposed fix

Include test-output type entries in the workspace type index shard consumed by `workspace/symbol`.
If the compiler already writes test classes into `.lathe/`,
verify whether `ClassFileTypeScanner`,
`WorkspaceTypeIndex`,
or the workspace-symbol handler filters them out.

Completion should reuse the same candidate source where possible,
so sibling test classes are consistently available when the current source has test-scope access.

### Regression targets

- `WorkspaceSymbolTest.workspaceSymbol_testClass_returnsResult`
- `WorkspaceSymbolTest.workspaceSymbol_mainClass_andTestClass_sameWorkspace_bothVisible`
- `CompletionSimpleNameTest.completion_testSource_offersSiblingTestClass`

---

## EG-027 — Out-of-range LSP positions throw internal errors on navigation endpoints

**Status: done — Target: M2**

### Observed behaviour

Several position-based LSP endpoints throw an internal error when the client sends a line outside the
opened document's range.

Dropwizard probe:

```bash
printf 'refs 9999:0 max 0\nimpl 9999:0 max 0\ncallees 9999:0 max 0\nhierarchy 9999:0\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/dropwizard/dropwizard-testing/src/test/java/io/dropwizard/testing/junit5/PersonResourceTest.java
```

Observed failures include:

```text
error: textDocument/references error: {'code': -32603, 'message': 'Internal error.', 'data': 'java.util.concurrent.CompletionException: java.lang.ArrayIndexOutOfBoundsException: Index 9999 out of bounds for length 128 ... SourceLocator.toOffset(SourceLocator.java:35) ...'}
error: textDocument/implementation error: {'code': -32603, 'message': 'Internal error.', 'data': 'java.util.concurrent.CompletionException: java.lang.ArrayIndexOutOfBoundsException: Index 9999 out of bounds for length 128 ... SourceLocator.toOffset(SourceLocator.java:35) ...'}
error: textDocument/prepareCallHierarchy error: {'code': -32603, 'message': 'Internal error.', 'data': 'java.util.concurrent.CompletionException: java.lang.ArrayIndexOutOfBoundsException: Index 9999 out of bounds for length 128 ... SourceLocator.toOffset(SourceLocator.java:35) ...'}
error: textDocument/prepareTypeHierarchy error: {'code': -32603, 'message': 'Internal error.', 'data': 'java.util.concurrent.CompletionException: java.lang.ArrayIndexOutOfBoundsException: Index 9999 out of bounds for length 128 ... SourceLocator.toOffset(SourceLocator.java:35) ...'}
```

Controls in the same probe showed that other endpoints degrade cleanly:
`completion 9999:0` returned no completions,
`sig 9999:0` returned no signature help,
`hover 9999:0` returned no hover result,
and `definition 9999:0` returned no definition.

### Expected behaviour

Out-of-range positions should not surface as server internal errors.
For read-only navigation requests,
the server should return an empty result or `null` consistently with hover,
definition,
completion,
and signature help.

Editors should normally send valid positions,
but stale-buffer races,
delayed responses,
or client bugs can still produce impossible coordinates.
Those should be cheap to reject before invoking javac's `LineMap`.

### Proposed fix

Add a bounds check before every call path that resolves an LSP position through
`SourceLocator.toOffset`.
The shared fix should likely live in `SourceLocator.toOffset` or a small wrapper around it,
so references,
implementation,
call hierarchy,
and type hierarchy use the same policy.

Returning `OptionalInt.empty()` or a sentinel failure result is preferable to catching
`ArrayIndexOutOfBoundsException` at each feature boundary.

### Regression targets

- `ReferenceLocatorTest.references_outOfRangePosition_returnsEmpty`
- `ImplementationTest.implementation_outOfRangePosition_returnsEmpty`
- `CallHierarchyTest.prepareCallHierarchy_outOfRangePosition_returnsEmpty`
- `TypeHierarchyTest.prepareTypeHierarchy_outOfRangePosition_returnsEmpty`

---

## EG-028 — `textDocument/onTypeFormatting` is a stub and is not registered

**Status: accepted — Target: M2**

### Observed behaviour

Typing in Google-Java-Format code with complex wrapped structure (assignment continuations at +8,
wrapped call arguments at +12, multi-line record headers at +4) leaves the cursor at the wrong
indentation when a new line is started.
The server provides no type-time indentation assistance.

### Root cause

`LatheTextDocumentService.onTypeFormatting` (around line 312) is a TODO stub that returns
`List.of()` for every request, and `LatheLanguageServer.initialize` does not register a
`documentOnTypeFormattingProvider` (no `firstTriggerCharacter` / `moreTriggerCharacter`), so most
clients never send the request at all.

### Constraint (why this cannot fully close the indentation gap)

Lathe's only formatting engine is Google Java Format, which parses the **entire compilation unit**
before emitting anything and throws `FormatterException` on unparseable input (`JavaFormatter`
catches it and returns no edits).
Its range API parses the whole file too — ranges only limit which edits are emitted.
The most useful `onTypeFormatting` trigger, newline (`\n`) inside a wrapped expression or record
header, fires precisely when the buffer is **not parseable**, so a GJF-backed handler can return
nothing there.
The CLAUDE.md "no ad hoc Java parsing" rule forbids a hand-rolled indentation model as the
alternative.

### Realistic scope

`onTypeFormatting` is at best a **partial** improvement, scoped to triggers that tend to *complete*
a parseable file (`}`, `;`): when the file parses again, run GJF (range-scoped to the touched lines)
and return conservative edits for brace/statement layout.
It will not fix the live-newline cursor case.
Conservative behaviour depends on EG-029 (real range formatting) landing first.
Because the editor already formats on save (full-document GJF), the saved result is GJF-correct
regardless, so this is a live-typing nicety, not a correctness requirement — which is why it is
parked at `backlog`.
The lever that actually fixes the newline/record-component cursor is error-tolerant client-side
indentation (e.g. tree-sitter), not the server.

### Probe commands

Not probeable through `explore.py`; confirmed by the stub handler and the absent capability
registration in `LatheLanguageServer`.

### Regression targets

- `OnTypeFormattingTest.onTypeFormatting_closingBraceCompletesFile_returnsConservativeEdits`
- `OnTypeFormattingTest.onTypeFormatting_unparseableNewline_returnsNoEdits`

---

## EG-029 — `rangeFormat` ignores its range and formats the whole document

**Status: accepted — Target: M3**

### Observed behaviour

`textDocument/rangeFormatting` reformats the entire document instead of only the requested range,
so a range-format request can move and reflow code far outside the selection.

### Root cause

Both the `formatting` and `rangeFormatting` endpoints delegate to the same
`WorkspaceSession.format(tag, uri)` (`LatheTextDocumentService` lines ~302 and ~309), which calls
`JavaFormatter.format(content)` — a whole-document `Formatter().formatSourceAndFixImports(content)`.
The selection range carried by the request is never read.

### Proposed fix

Add a range-aware path in `JavaFormatter` using GJF's `formatSource(text, ranges)` with the
character range derived from the request's LSP range, and emit only the resulting in-range edits.
Keep the whole-document path for `formatting`.
This is a prerequisite for a conservative EG-028 (`}`/`;` on-type formatting that touches only the
edited region).

### Probe commands

Not probeable through `explore.py`; confirmed by both endpoints sharing the whole-document
`JavaFormatter.format` path in `WorkspaceSession`.

### Regression targets

- `RangeFormattingTest.rangeFormat_selectionInsideMethod_editsOnlySelectedLines`
- `RangeFormattingTest.rangeFormat_unchangedSelection_returnsNoEdits`

---

## EG-030 — Neovim indenter Google-Java-Format continuation handling

**Status: done — Target: M2 (regression). One sub-case deferred (see below).**

### Observed behaviour (before)

The Neovim `indentexpr` (`lathe-maven-plugin/src/main/neovim/lua/lathe/indent.lua`) used a
`tree_indent` block-depth model that could not reproduce Google Java Format's mixed
selector/continuation/lambda indentation, and a previous-line continuation rule that stair-stepped
list items deeper on every line. Pressing Enter in wrapped GJF structures left the cursor in the
wrong column: record components, wrapped arguments, blank lines inside calls and lambda bodies, and
the line after a completed multi-line statement were all mis-indented.

### Fix

Made the text heuristic authoritative and used tree-sitter only for closer-to-block matching:

- Split list separators (align) from binary operators (one continuation level), and recognise
  trailing `(`/`[` openers — fixes stair-stepping and wrapped-argument / record-component indent.
- Added `statement_start_indent` so the line after a completed multi-line statement dedents to the
  statement base.
- Guarded the blank-line selector rule so a blank line inside a `selector(`-opened call indents into
  the call rather than aligning to the selector.
- Removed the unreliable `tree_indent`/`CONTINUATION_NODES` path; the indent value is now purely
  text-derived, so behaviour is identical whether or not the buffer parses (the common mid-edit
  case).

### Regression targets

- `lathe-maven-plugin/src/test/neovim/indent_spec.lua` — 19 project-neutral fixtures covering record
  components, wrapped arguments, assignment-RHS continuation, block bodies/closers, statement-end
  dedent, blank lines inside calls and lambda bodies, nested wrapped calls, and selector chains.
- Run during the `test` phase via `exec:exec@neovim-indent-spec`, which hard-fails under CI when
  `nvim` is absent and degrades gracefully on local machines without it. The fixtures are
  editor-neutral so they also serve as the acceptance spec for the future VS Code indentation rules.

### Deferred sub-case

A method-chain selector that **resumes after a multi-line wrapped argument** still anchors to the
closing line rather than the chain:

```java
return source.of(req)
    .request(Type.class)
    .recover(
        e -> {
          log(e);
          return fallback();
        })
    .get();        // indenter yields closer-indent + 4; GJF puts this at the chain column (probe: 16 vs 8)
```

`selector_indent` anchors a `.`-led line to the previous line; when that line is the `})` closing a
multi-line argument, it indents one level past the closer instead of back to the chain's first
selector. Resolving the chain anchor across an intervening multi-line lambda needs tree-sitter
structure (walk to the outermost `method_invocation`/`field_access` receiver), which the text
heuristic cannot do. Two minor relatives are also deferred: a continuation line that *starts* with a
binary operator (Google breaks before `&&`/`+`) gets no extra level, and a lone `)`/`]` wrap-closer
aligns to the block rather than the wrap opener.

Deferred, not fixed, because `format_on_save` (full-document GJF, on by default in the Neovim plugin)
corrects the file on every save; the only impact is a transient cursor column on a rare shape. If
revisited, add the tree-sitter chain-anchor rule and a fixture for the `.get()`-after-`})` tail.

---

## EG-031 — JDK source resolution depends solely on `JAVA_HOME`, and its absence is silent and undiagnosable

**Status: accepted — Target: M2**

### Observed behaviour

`JdkSourceResolver` locates `src.zip` only through the `JAVA_HOME` environment variable.
When `JAVA_HOME` is not set, `lathe:sync` produces no JDK source cache and the only trace is a single
unexplained INFO line:

```
[sync] jdk sources missing
```

Two distinct, unrelated situations produce that same line:

1. `JAVA_HOME` is unset — even when the build is genuinely running on a full JDK. This includes the
   common setup where the `java` launcher on `PATH` is a symlink into a valid JDK directory (so the
   running JVM has a real JDK home and a `lib/src.zip`), but `JAVA_HOME` was never exported.
2. `JAVA_HOME` is set, but the JDK ships no `lib/src.zip` (a JRE, or a JDK installed without sources).

The two causes are indistinguishable from the log, and neither tells the user that setting
`JAVA_HOME` (or installing a full JDK) would enable JDK source navigation, hover, and the JDK type
index.
Case 1 is the surprising one: the JDK home is knowable from the running JVM, yet lathe reports the
sources as missing purely because one env var is absent.

Downstream, definition and hover into JDK types silently fall back to class-only behaviour because
`JdkSourceData.status` is `MISSING` with no recorded reason.

### Root cause

`JdkSourceResolver.resolve(env)` reads `home` exclusively from `env.get("JAVA_HOME")` and never
consults the running JVM's authoritative `java.home` system property (`System.getProperty("java.home")`
is used nowhere in the codebase).
It then collapses two distinct conditions into one `SourceStatus.MISSING`:

```java
final String javaHome = env.get("JAVA_HOME");
if (javaHome == null) {
  return JdkSource.missing(vendor, version, cacheKey(null, vendor, version), null); // home == null
}
...
if (Files.exists(sourceZip)) {
  return JdkSource.present(...);
}
return JdkSource.missing(vendor, version, key, home);                                // home != null
```

`JdkSource.missing` records `SourceStatus.MISSING` and drops the reason.
The `home` field is the only surviving signal (`null` ⇒ `JAVA_HOME` unset, non-`null` ⇒ `src.zip`
absent), and it is never surfaced.
`JdkSourceSync.extract` then logs the bare `"[sync] jdk sources missing"` for both cases, with no
reason, no inspected path, and no remediation — and the message does not follow the project's
`[operation] target detail outcome` log convention.

### Proposed fix

Two independent improvements:

1. **Resolve the JDK home robustly, not from `JAVA_HOME` alone.** When `JAVA_HOME` is unset, fall back
   to the running JVM's `java.home` (with symlinks resolved via `toRealPath()`), which is the JDK that
   actually compiles the workspace and carries the matching `lib/src.zip`. This covers the
   symlinked-launcher case and any environment that omits `JAVA_HOME`. `JAVA_HOME`, when present, may
   still take precedence so a user can point lathe at a different JDK's sources.
2. **Diagnose the remaining genuine-missing cases actionably**, distinguishing them:
   - no JDK home resolvable at all → a WARNING naming the cause and the remedy, e.g.
     `[sync] jdk-sources unresolved no-jdk-home set JAVA_HOME or run the build on a JDK to enable JDK source navigation`.
   - JDK home resolved but `lib/src.zip` absent → a WARNING naming the inspected path, e.g.
     `[sync] jdk-sources missing <home>/lib/src.zip not-found install a full JDK with sources`.

Optionally carry the reason on the model (a `MISSING` sub-reason on `JdkSource`/`JdkSourceData`, or a
distinct status) so the server can explain JDK-source absence on a definition or hover into a JDK
type rather than silently degrading.

### Probe commands

```bash
# JAVA_HOME unset while running on a real JDK (e.g. a symlinked launcher on PATH):
env -u JAVA_HOME mvn -q -pl lathe-maven-plugin -Dinvoker.test=<jdk-source-project> verify
# observe the single "[sync] jdk sources missing" line; confirm no cause/remedy is reported and that
# no JDK source cache was produced even though the build ran on a JDK with lib/src.zip
```

### Regression targets

- `JdkSourceResolverTest.resolve_javaHomeUnset_fallsBackToRunningJavaHome` (added, `@Disabled`)
- `JdkSourceResolverTest.resolve_symlinkedHome_resolvesToRealPath` (added, `@Disabled`)
- `JdkSourceResolverTest.resolve_srcZipAbsent_recordsAbsentReason` (proposed; needs a `MISSING` sub-reason on the model)
- `JdkSourceSyncTest.extract_noJdkHome_logsActionableWarning` (proposed; no `JdkSourceSyncTest` exists yet)

---

## EG-033 — Workspace symbol (Telescope) results always jump to the file's first line, not the declaration

**Status: done — Target: M2**

### Observed behaviour

Selecting a type from the workspace-symbol picker (Telescope's `lsp_workspace_symbols`, or any
`workspace/symbol` client) opens the correct file but places the cursor at line 1, column 0 rather
than on the selected type's declaration. For files with a license header, package statement, and
imports, the declaration can be dozens of lines down, so every pick lands far from the symbol.

### Root cause

`WorkspaceSymbolResolver` assigns every result a constant location:

```java
private static final Range FILE_START = new Range(new Position(0, 0), new Position(0, 0));
...
final var location = new Location(file.toUri().toString(), FILE_START); // WorkspaceSymbolResolver.java:37
```

The `TypeIndexEntry` carries the file but no declaration position, so the resolver has nothing better
than `(0,0)` to emit. The range is correct as a *file* reference but useless as a *navigation* target.

### Proposed fix

Carry the declaration's name position in the type index (or resolve it on demand when building the
`SymbolInformation`) and emit a `Range` on the type's identifier rather than `FILE_START`. Reuse
`SourceLocator.declarationNamePosition` (already used by `MethodImplementationLocator`) so the range
matches what definition/implementation navigation produces. If a position cannot be resolved, fall back
to `FILE_START` rather than dropping the symbol.

### Probe commands

```bash
# In Neovim against a workspace: :Telescope lsp_workspace_symbols, pick a type whose declaration is
# below a license header / imports, and observe the cursor lands on line 1 instead of the declaration.
```

### Regression targets

- `WorkspaceSymbolResolverTest.resolve_typeBelowHeader_locationPointsAtDeclaration` (proposed)
- `WorkspaceSymbolResolverTest.resolve_positionUnresolvable_fallsBackToFileStart` (proposed, guard)

---

## EG-035 — Unused-declaration scan treats an assignment target as a use, so write-only variables are never flagged

**Status: accepted — Target: M2**

### Observed behaviour

A local variable (or private field) that is assigned but never read is not reported as unused,
even though its value is never observed. The declaration and every write are dead code.

```java
class Test {
  public void method() {
    int count = 0;       // declared
    count = compute();   // assigned, never read
  }
  private int compute() { return 1; }
}
```

`int count` receives an initializer and a later assignment, but the value is never read.
No `Unused local variable 'count'` hint is produced, whereas an unread variable with no assignment
(`int count = 0;` alone) is correctly flagged.

### Root cause

`UnusedDeclarationScanner` counts any reference to a declaration's element as a use.
`visitIdentifier` calls `markReference` for the `IdentifierTree` on the **left-hand side** of an
assignment (`count = ...`), because the scanner has no `visitAssignment` override and does not
distinguish read positions from write positions:

```java
public Void visitIdentifier(final IdentifierTree node, final Void v) {
  if (!declarationPhase) {
    markReference(trees.getElement(getCurrentPath()));   // fires on assignment LHS too
  }
  return super.visitIdentifier(node, v);
}
```

The assignment target is therefore added to `referencedLocals` / `referencedFields`, and the
declaration escapes `collectUnused`. Only a variable with no reference of any kind survives to a
hint, so "assigned but never read" is invisible.

### Scope

Write-only detection applies to `LOCAL_VARIABLE` and `PRIVATE_FIELD` only. Private methods are not
assignable, so the `PRIVATE_METHOD` reachability analysis is unchanged. Both targeted kinds are
confined to a single compilation unit (locals are method-scoped; private members are accessible only
within their declaring top-level class), so the existing per-file scan sees every possible read and
no cross-file analysis is required.

### Proposed fix

Distinguish writes from reads in the reference phase, for locals and private fields. Add a
`visitAssignment` override that scans the right-hand side normally but treats a bare
`IdentifierTree` / `this`-qualified `MemberSelectTree` on the left-hand side as a **write**, not a
use — a pure write must not mark the declaration referenced. Reads through the same node still
count: leave `visitCompoundAssignment` (`+=`, which reads then writes), `visitUnary` (`x++`), and any
use in the RHS or a qualifier/index expression (`a.f = x` reads `a`; `arr[i] = x` reads `arr`)
unchanged so they continue to mark the declaration used. Only the bare simple-assignment target is
suppressed, which keeps false positives near zero at the cost of leaving `++`/`--`-only variables
conservatively counted as used.

Keep the existing behaviour for every non-assignment position, and keep the `Unnecessary` tag,
`lathe.unused` code, and message format from EG-019. A follow-on remove-declaration quick fix would
also need to remove the now-dead assignment statements, which is out of scope here.

### Regression targets

- `UnusedDeclarationScannerTest.compile_localVariableAssignedNeverRead_reportsHint` (added,
  `@Disabled`)
- On implementation, add:
  - a negative case where the same variable is read after assignment (`compile_localVariableAssignedThenRead_noHint`);
  - a compound-assignment case (`count += 1;`) asserting the read half keeps it used;
  - a private-field variant asserting write-only fields are flagged.

---

## Implementation notes

The release slice is derived from the gap fields, not maintained as an ordered list here: the work
for a release is every gap with `Status: accepted` and the matching `Target` (see
[gap-process.md](gap-process.md)).

Guidance that does not fall out of the fields:

- Do **EG-007** (WARNING flood) early — it improves log signal for debugging everything else.
- Implement **EG-023 with EG-008** (shared `Object`-method suppression list).
- Implement **EG-021 with EG-006** (shared reactor-origin ranking).
- **EG-014** and **EG-015** reuse the override-resolution walk from **EG-012** (already implemented).

---

# Find References Gaps (FR-NNN)

Current correctness, policy, and test-coverage gaps in `textDocument/references`.
The original feature design remains in [lathe-find-references.md](../done/lathe-find-references.md);
this section is the current gap tracker when the original design and implemented behavior differ.

### Current behaviour

Find References uses javac attribution for exact symbol matching and a textual candidate index to
avoid compiling every workspace file.

The implemented search supports:

- same-file references;
- open and closed files in the declaring module;
- transitive downstream reactor modules;
- private and local symbol restriction to the declaring file;
- package-private restriction to the declaring package;
- explicit imports, wildcard imports, static imports, and implicit `java.lang` type candidates.

Confirmed working:

- `java.lang.String` returns project references from project source;
- `java.time.Duration` returns project references from project source;
- `java.lang.String` from the cached JDK `String.java` declaration returns reactor usages (FR-001 fixed);
- the Helidon `Duration` incident produced two server-side locations in 12 ms.

The remaining gaps concern external-symbol scope policy, failure reporting, and end-to-end
verification.

## FR-002 — External-symbol search scope policy is unresolved

Status: done — Target: M1.
Decision recorded in `docs/done/lathe-find-references.md` section 15.

The original design says JDK and third-party symbols should search open files only.
The implementation instead searches reactor files selected from the cursor module's downstream
graph.

The current implementation is more useful for users asking for project-wide references to common
types, but it can be expensive:

- `String` may select a large part of the workspace;
- common dependency methods may require attribution of many candidate files;
- the result depends on which project module contains the cursor usage.

Restricting external symbols to open files would satisfy the original performance policy but would
make Find References incomplete in a way that is surprising for a workspace operation.

### Recommended direction

Preserve project-wide correctness and improve execution rather than silently restricting results to
open files.

The preferred progression is:

1. retain candidate-index filtering;
2. search all relevant workspace modules when the target is external;
3. add cancellation propagation;
4. support LSP partial results for large result sets;
5. consider a user-visible warning only when candidate counts exceed a measured threshold.

The roadmap's open-file-only statement should not be implemented until this policy is explicitly
confirmed.

### Required measurement

Record candidate count, attributed-file count, elapsed time, and result count for representative
symbols:

- `java.lang.String`;
- `java.time.Duration`;
- one frequently used dependency type;
- one static dependency method;
- one external method reference.

## FR-003 — Failures are converted into empty results

Status: done — Target: M1.
Verified error-handling gap.

The references pipeline currently has two silent-recovery boundaries:

- `SourceAnalysisSession.searchReferences()` catches `IOException`, logs it, and returns an empty
  list;
- `WorkspaceSession.referencesFuture()` catches any exceptional completion, logs it, and returns an
  empty list.

Consequently, the client cannot distinguish:

- a symbol with no references;
- a source-read failure;
- a compiler or attribution failure;
- a worker failure;
- a bug in result aggregation.

This conflicts with the fail-fast policy in
[lathe-m1-refactoring.md](../planned/lathe-m1-refactoring.md).

### Required behavior

- Lower layers preserve and propagate failures with useful URI or path context.
- Lower layers do not log and then return an empty result.
- The nearest upstream references-operation boundary logs the failure once with the `Throwable`.
- The LSP request completes exceptionally rather than reporting a successful empty result.
- Legitimate absence, including an unresolved cursor element, remains an empty result.

### Required tests

- A source-read failure completes the references request exceptionally.
- A module-worker failure reaches the upstream request boundary.
- The failure is logged once rather than at both analysis and workspace layers.
- A valid symbol with no references still returns an empty list.

## FR-004 — No end-to-end invoker coverage

Status: done — Target: M1.
Verified test gap.

The Maven invoker `LspSmokeTest` checks that `referencesProvider` is advertised but never sends a
`textDocument/references` request.

Existing server tests cover important pieces independently:

- `ReferenceCandidateIndexTest` covers token and import indexing;
- `ReferenceCandidatePlannerTest` covers explicit imports, wildcard imports, static members, and
  implicit `java.lang.String` candidates;
- `ReferenceLocatorTest` covers attributed identity matching, roles, scope classification, and
  cross-compilation matching.

No test covers the complete path:

```text
LSP request
  -> open-document lookup
  -> cursor target resolution
  -> workspace scope planning
  -> candidate selection
  -> module-worker attribution
  -> Location aggregation
  -> JSON-RPC response
  -> client receipt
```

### Required invoker cases

Extend the existing multi-module LSP smoke test rather than creating a second server launcher.

At minimum:

1. Open a project source file through `didOpen`.
2. Request references for a reactor symbol and assert same-module or cross-module locations.
3. Request references for `java.lang.String` and assert at least one project location.
4. Request references for `java.time.Duration` and assert project locations.
5. Open the cached JDK `Duration.java` returned by definition navigation.
6. Request references from its declaration and assert project locations.

The test must inspect returned URIs and ranges, not only result count.


# Code Action Gaps (CA-N)

Gaps found during live probing of `textDocument/codeAction` on the Dropwizard and Helidon
codebases after the initial code-action implementation (`ImportQuickFixProvider` +
`AddThrowsProvider`).
Each gap describes the observed behaviour, the root cause, and the proposed fix.

## CA-1 — `UNREPORTED_EXCEPTION` inside a lambda body has no action

**Status: done — Target: M1.**

### Observed behaviour

```java
Runnable r = () -> { throw new IOException("x"); };
```

Diagnostic: `UNREPORTED_EXCEPTION / java.io.IOException` — correctly classified and published
with a `JsonObject` payload.
Code-action request: returns zero actions.

### Root cause

`AddThrowsProvider.provide()` walks the AST up from the diagnostic position looking for the first
enclosing `MethodTree`.
A lambda body is a `LambdaExpressionTree`, not a `MethodTree`, so the walk continues past it.

When the lambda is a **field initializer** there is no enclosing `MethodTree` at all —
the walk reaches the `CompilationUnitTree` and the path becomes `null`, causing the provider to
return `List.of()`.

When the lambda is inside a **method body**, the walk does find the outer method and offers
"Add throws IOException to method".
This is semantically wrong: the exception cannot propagate past the lambda boundary regardless
of what the outer method declares.

### Proposed fix

Add a `TryCatchWrapProvider` for `UNREPORTED_EXCEPTION` that targets the statement containing
the throw site and wraps it in a `try { … } catch (ExceptionType e) { }` block.

The `AddThrowsProvider` should be suppressed (or ranked lower) when the throw site is inside a
`LambdaExpressionTree` or `AnonymousClassTree`, because adding `throws` to the outer method does
not silence the error.

Detection: walk the path between the diagnostic position and the nearest `MethodTree`;
if a `LambdaExpressionTree` or `NewClassTree` (anonymous class) is encountered along the way,
classify the context as "inside closure" and route to the try/catch provider instead.

**Files to change**: `AddThrowsProvider.java` (suppress in closure context),
new `TryCatchWrapProvider.java`, dispatcher in `SourceAnalysisSession.codeAction()`.

This is the M1 gap also referenced by EG-002.

## CA-2 — `VARIABLE_REF` has no action

**Status: done — Target: M1.**

### Observed behaviour

```java
void m() { int x = unknownVar + 1; }
```

Diagnostic: `VARIABLE_REF / unknownVar` — correctly classified.
Code-action request: returns zero actions.

### Root cause

`DeclareVariableProvider` does not exist yet.
The dispatcher routes `VARIABLE_REF` to `List.of()`.

### Proposed fix

Implement `DeclareVariableProvider` as described in `lathe-code-actions.md` §2.7:
find the assignment or local-variable declaration at the diagnostic offset,
infer the RHS type via `trees().getTypeMirror(rhsPath)`,
emit `TypeName varName = …` (with import if needed) or `var varName = …` as a fallback.

**Files to change**: new `DeclareVariableProvider.java`, dispatcher in `SourceAnalysisSession`.

## CA-3 — `MISSING_METHOD_IMPL` is never classified

**Status: done — Target: M1.**

### Observed behaviour

```java
public class Foo implements Runnable { }  // missing run()
```

The compiler emits `compiler.err.does.not.override.abstract`.
The diagnostic arrives with `data = null` — no payload is set.
No code action is offered.

### Root cause

`enrichWithContext()` only handles two diagnostic codes:
`compiler.err.cant.resolve` and `compiler.err.unreported.exception`.
The `MISSING_METHOD_IMPL` `Kind` exists in the enum but the corresponding classification branch
is missing.

`MissingMethodImplProvider` is also not yet implemented.

### Proposed fix

**Part A — classify in `enrichWithContext()`.**
Add a branch for `compiler.err.does.not.override.abstract`.
The message has the form `"Foo is not abstract and does not override abstract method run() in Runnable"`.
Extract the class simple name (first token before `"is not abstract"`) and set
`DiagnosticPayload(MISSING_METHOD_IMPL, className)`.

**Part B — implement `MissingMethodImplProvider`.**
Look up the `ClassTree` for `payload.name()` in the attributed analysis.
Use `elements().getAllMembers(classElement)` to enumerate abstract methods not yet overridden.
Generate `@Override` stubs for each, insert them before the closing `}` of the class body,
and add any needed imports.

**Files to change**: `SourceAnalysisSession.enrichWithContext()` (classification),
new `MissingMethodImplProvider.java`, dispatcher in `SourceAnalysisSession`.

## CA-4 — `TYPE_REF` in a `throws` declaration with no index match produces no action

**Status: done — Target: M1.**

### Observed behaviour

```java
void m() throws MyCustomException {}
```

When `MyCustomException` is unresolvable, the diagnostic is classified as `TYPE_REF / MyCustomException`
and the payload is published correctly.
If `MyCustomException` is not in the type index, `ImportQuickFixProvider` returns zero actions.

### Root cause

`ImportQuickFixProvider` queries `typeIndex.search(simpleName, 100)` and returns nothing when
there is no matching entry.
This is expected for types that have never been compiled (e.g. a new exception class defined
in the same project but not yet indexed by `lathe:sync`).

### Impact

Medium for M1: this appears when a project type is created or renamed and Lathe has not yet refreshed the reactor
type index through a full sync.
Running `mvn process-test-classes` restores the action,
but the M1 goal is to avoid requiring that round trip when Lathe already has enough local source or reactor-index
information to answer safely.

### Proposed M1 direction

Treat this as a type-index freshness problem rather than an `ImportQuickFixProvider` provider bug.
The fix should make newly-created project types available to missing-import code actions from current reactor source or
fresh in-memory reactor index state,
without weakening the provider's existing type-index validation path for dependencies and JDK types.

---

# Completion Gaps (CQ)

Active completion-quality gaps. Discovered and triaged via the completion appendix of the
[gap workflow](gap-workflow.md); checked against the completion [expectations](../planned/lathe-completion-expectations.md)
contract. Resolved CQ entries are in [gaps-archive.md](gaps-archive.md).

## CQ-0041 — `module-info.java` directive slots return no completion candidates

ID: CQ-0041
Status: done
Target: M1
Tier: basic
Failure mode: missing-candidate
Owner component: SentinelParser / CompletionEngine
Discovery: 2026-06-28, Helidon `module-info.java` live probes
Resolved: 2026-06-28, module-name completion uses `ModuleFinder.ofSystem()` plus the current
module path.

Project/file:
`/home/ag-libs/git/helidon/health/health/src/main/java/module-info.java`

Probe command:
```bash
printf 'complete after "module io.helidon." min 1\ncomplete after "requires " min 1\ncomplete after "requires transitive " min 1\ncomplete after "requires transitive io.helidon." min 1\ncomplete after "exports " min 1\ncomplete after "exports io.helidon." min 1\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/helidon/health/health/src/main/java/module-info.java
```

Related annotation-context probes:
```bash
printf 'complete after "@Features."\ncomplete after "@Features.Aot("\ncomplete after "HelidonFlavor."\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/helidon/dbclient/jdbc/src/main/java/module-info.java
```

Cursor context:
```java
module io.helidon.health {

    requires transitive io.helidon.common;
    requires transitive io.helidon.config;

    exports io.helidon.health;
    exports io.helidon.health.spi;
}
```

IntelliJ or JDT behavior:
Expected IDE behavior is context-specific completion inside module descriptors:
module-name candidates after `module`,
reachable module-name candidates after `requires` and `requires transitive`,
package-name candidates after `exports` and `opens`,
service types after `uses`,
and provider types after `provides ... with`.

Lathe behavior:
All probed directive slots return 0 items.
The failure reproduces both at bare directive positions and after typed prefixes:
`module io.helidon.`,
`requires `,
`requires transitive io.helidon.`,
`exports `,
and `exports io.helidon.`.

`module-info.java` annotation contexts are also incomplete.
Basic annotation type completion can work in some injected positions,
such as `@Dep` before `module`,
but annotation member completion and annotation-value member access return no useful candidates:
`@Features.`,
`@Features.Aot(`,
and `HelidonFlavor.` all return 0 items in real Helidon module descriptors.

Expected Lathe behavior:
Completion should provide legal directive-specific candidates in `module-info.java`.
For M1,
the required slice is basic candidate discovery and insertion for:

- module names after `module`, `requires`, and `requires transitive`;
- package names after `exports` and `opens`;
- type names after `uses`;
- provider type names after `provides ... with`;
- directive keywords where the cursor is inside the module body and no directive prefix has been typed.

Annotation completion inside module descriptors should behave like annotation completion before a
`package-info.java` package declaration:
annotation type names,
annotation element names,
and basic typed values such as booleans and enum constants should be available.

Accepted edit, if relevant:
Accepting a module candidate after `requires transitive io.helidon.` should complete only the
remaining module-name segment and preserve the trailing semicolon if present.
Accepting a package candidate after `exports io.helidon.` should complete only the remaining
package segment and preserve the trailing semicolon if present.

Root cause:
`SentinelParser` recognises module descriptor positions as `MODULE_DIRECTIVE`.
`CompletionEngine` has no handler for that context,
so it falls through to an empty `CompletionOutcome`.

Regression targets:

- `CompletionModuleInfoTest.completion_requiresDirective_offersReachableModules`
- `CompletionModuleInfoTest.completion_exportsDirective_offersModulePackages`
- `CompletionModuleInfoTest.completion_moduleAnnotation_offersAnnotationMembers`

Notes:
Dropwizard has no source `module-info.java` or `package-info.java`,
but a Dropwizard control probe confirmed normal member completion is live in that workspace:
`inject "String.valueOf(1)."` returned 73 `String` members.
This gap is therefore specific to module descriptor contexts,
not a dead LSP session.

---

## CQ-0040 — `bind(...).to(...)` chain offers no members on the captured-wildcard result

ID: CQ-0040
Status: done
Target: M1
Tier: typed
Failure mode: missing-candidate
Owner component: TypeResolver / CompletionEngine
Discovery: 2026-06-26, sample-workspace (AppServer anonymous `AbstractBinder.configure()`)

Project/file:
`/workspace/app-server/src/main/java/com/example/app/server/AppServer.java`

Probe command (real source, line 624 has a double `.to(...).to(...)` chain):
```bash
python3 dev/explore.py \
  /workspace/app-server/src/main/java/com/example/app/server/AppServer.java \
  complete after "bind(pipelineService).to(RequestPipelineService.class)."
```

Control probe (first hop works):
```bash
python3 dev/explore.py \
  /workspace/app-server/src/main/java/com/example/app/server/AppServer.java \
  complete after "bind(onboardingService)." expect to min 1
```

Single-`.to()` injection probe (gap reproduces without the double chain):
```bash
printf 'inject "bind(rpcServer).to(RpcServer.class)." at 617\ninject "bind(rpcServer)." at 617\n' \
  | python3 dev/explore.py \
    /workspace/app-server/src/main/java/com/example/app/server/AppServer.java
```

Cursor context:
```java
resourceConfig.register(
    new AbstractBinder() {
      @Override
      protected void configure() {
        bind(onboardingService).§                                  // works: 28 Binding<OnboardingService> members
        bind(pipelineService).to(RequestPipelineService.class).§   // gap: no completions
      }
    });
```

IntelliJ or JDT behavior:
After `bind(x).to(Y.class).`, both IDEs expose the binding-builder's self-type members so the
fluent chain continues — `to`, `in`, `named`, `qualifiedBy`, `ranked`, `proxy`, `proxyForSameScope`.
This is the standard Jersey/HK2 DI registration DSL.

Lathe behavior:
`bind(x).` resolves to a concrete `Binding<T>` and returns 28 members, including the `to` overloads
whose return type is surfaced as `<captured wildcard>`:
```text
to  [Method]  Binding.to(Class<? super RpcServer> contract) : <captured wildcard>
```
Hover on `to` shows the declared signature `D to(Class<? super T> contract)`.
Completing on the result of any `.to(...)` call returns 0 items — the receiver's static type is the
type variable `D` / its captured wildcard, and member discovery on that receiver yields nothing.

Expected Lathe behavior:
Member completion on a captured-wildcard / type-variable receiver should use the capture's effective
upper bound (here the binding-builder self-type), so `bind(x).to(Y.class).` offers `to`, `in`,
`named`, `ranked`, etc., and chained `bind(x).to(Y.class).to(Z.class)` completes.

Accepted edit, if relevant:
Not applicable — no candidate is returned.

Regression target:
`CompletionMemberAccessTest.memberAccess_typeVariableReturn_usesCapturedBound`

Notes:
Same root-cause family as `CQ-0029` (wildcard generic receivers do not expose usable bound members)
and `CQ-0030` (type-variable receivers do not expose declared bounds), both planned for M2.
This entry is pulled into M1 because it breaks completion in the ubiquitous HK2/Jersey `AbstractBinder`
`bind(x).to(Y.class)` registration DSL on a real workspace, not just synthetic generics.
The first `bind(x).` hop is unaffected because it resolves to the concrete `Binding<T>`; the failure
begins at the first `.to(...)`.

Also observed in the same `configure()` body, needs separate isolation before filing:
statement-position identifier completion returned nothing for an inherited-method prefix
(`inject "b"` / `inject "bind"` at line 617 → 0 items, where `bind` was expected), and `inject "this."`
resolved `this` to `TypeLiteral` (from the `new TypeLiteral<...>(){}` at line 658) rather than the
enclosing `AbstractBinder`. A captured-local member access (`inject "onboardingService."`) works
correctly in the same body. These injection-based observations may be incomplete-statement artifacts
and should be reconfirmed against valid syntax before being recorded as gaps.

## CQ-0035 — Parser fails to recognise enclosing method when closing `}` is missing (typed over)

ID: CQ-0035
Status: accepted
Target: M2
Tier: Basic
Discovery: 2025-07-25, AppServerConfig.java compact constructor (sample-workspace)

### Description

When a user accidentally overwrites the closing `}` of a constructor (or method) with new text, the
parser returns `valid=false, class=null, method=null` and 0 completions are returned.
This is a parse-recovery failure: the parser cannot locate the enclosing method scope without the
closing brace, so no context is available and completion bails out entirely.

### Reproduction

Target: `AppServerConfig.java`, inject at line 122 (the compact constructor's `}`):
- `inject "n" at 122` → 0 items, `parsed valid=false sentinelCtx=null class=null method=null`
- `inject "ValidCheck.check()." at 122` → 9 Object methods only, `method=<error>`, `type=null`

In both cases the parser sees code at the class body level (no enclosing `{}`), so
the method context is lost entirely.

### Root cause

Parse error recovery does not synthesise a closing brace when a sentinel injection replaces the
sole `}` that closes a method body.
This is distinct from a normal "open block" recovery because the sentinel is at a position
the parser was already expecting `}` — the recovery heuristic doesn't re-close the block.

### Impact

Edge case: affects only the exact keystroke that replaces the closing `}`.
However, it is a hard failure (0 items, no degraded result), so it is worth noting.

### Fix area

Parser error-recovery in the sentinel-inject pipeline: if `class != null` but `method == null`,
attempt to re-scan backward for the containing method declaration and synthesise a
virtual block close before the sentinel.

### Deferral note

Any fix requires scanning backward through raw source text to locate the enclosing method
declaration — effectively manual Java parsing. Simple injected cases are already handled
correctly by `forwardScan` recovering the missing `}`. The hard failure only occurs in
complex real-world files with specific brace-count contexts (confirmed in AppServerConfig.java).
Deferred pending a cleaner approach.

---

## CQ-0029 — Wildcard generic receivers do not expose usable bound members

ID: CQ-0029
Status: done
Target: M2
Tier: typed
Failure mode: missing-candidates
Owner component: TypeResolver / CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-e2e/src/main/java/com/example/app1/App1Resource.java`

Probe command:
```bash
printf 'diagnostics\ninject "final java.util.Collection<? extends Number> numbers = java.util.List.of(1); numbers.iterator().next()." at 37\nlog 45\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-e2e/src/main/java/com/example/app1/App1Resource.java
```

Related probes:
```bash
printf 'diagnostics\ninject "final java.util.List<? extends Number> numbers = java.util.List.of(1); numbers.get(0)." at 37\nlog 45\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-e2e/src/main/java/com/example/app1/App1Resource.java

printf 'diagnostics\ninject "final java.util.Map<String, ? extends Number> numbers = java.util.Map.of(\"x\", 1); numbers.get(\"x\")." at 37\nlog 45\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-e2e/src/main/java/com/example/app1/App1Resource.java

printf 'diagnostics\ninject "final java.util.Collection<?> values = java.util.List.of(\"a\"); values.iterator().next()." at 37\nlog 45\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-e2e/src/main/java/com/example/app1/App1Resource.java
```

Cursor context:
```java
final java.util.Collection<? extends Number> numbers = java.util.List.of(1);
numbers.iterator().next().§

final java.util.List<? extends Number> numbers = java.util.List.of(1);
numbers.get(0).§

final java.util.Map<String, ? extends Number> numbers = java.util.Map.of("x", 1);
numbers.get("x").§

final java.util.Collection<?> values = java.util.List.of("a");
values.iterator().next().§
```

IntelliJ or JDT behavior:
Expected IDE behavior is to expose members from the capture's usable upper bound.
For `? extends Number`,
member completion should show `Number` methods such as `intValue`,
`longValue`,
and `doubleValue`.
For unbounded `?`,
member completion should at least show `Object` methods.

Original Lathe behavior:
All wildcard probes returned no completion items.
The log showed `sentinelCtx=MEMBER_ACCESS` but receiver resolution failed:
```text
resolve receiver=|numbers.iterator().next()| type=null static=null reattributed=true
resolve receiver=|numbers.get(0)| type=null static=null reattributed=true
resolve receiver=|numbers.get("x")| type=null static=null reattributed=true
resolve receiver=|values.iterator().next()| type=null static=null reattributed=true
```

Current Lathe behavior:
Retested on current code after adding regression coverage.
The documented probes now return the expected `Number` methods for `? extends Number`
and `Object` methods for unbounded `?`.

Expected Lathe behavior:
When a generic member returns a captured wildcard,
Lathe should use the capture's upper bound for completion.
Examples:

- `Collection<? extends Number>.iterator().next().§` should complete as `Number`.
- `List<? extends Number>.get(0).§` should complete as `Number`.
- `Map<String, ? extends Number>.get("x").§` should complete as `Number`.
- `Collection<?>.iterator().next().§` should complete as `Object`.

Accepted edit, if relevant:
Not applicable.
No candidate is returned.

Regression coverage:
`CompletionMemberAccessTest.memberAccess_wildcardExtendsCollectionElement_usesUpperBound`
`CompletionMemberAccessTest.memberAccess_wildcardExtendsListGet_usesUpperBound`
`CompletionMemberAccessTest.memberAccess_wildcardExtendsMapValue_usesUpperBound`
`CompletionMemberAccessTest.memberAccess_unboundedWildcardCollectionElement_usesObjectBound`

Notes:
Non-wildcard generic controls work correctly:
`Map<String, String>.entrySet().iterator().next().§` returns `Entry.getKey() : String`
and `Entry.getValue() : String`;
`Map<String, List<String>>.get("x").§` returns `List<String>` methods;
`Map<String, List<String>>.get("x").get(0).§` returns `String` methods;
and `Collection<String>.iterator().next().§` returns `String` methods.

## CQ-0030 — Type-variable receivers do not expose declared bounds

ID: CQ-0030
Status: done
Target: M2
Tier: typed
Failure mode: missing-candidates
Owner component: TypeResolver / CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-core/src/main/java/io/dropwizard/core/setup/Bootstrap.java`

Probe command:
```bash
printf 'diagnostics\ninject "configuration." at 199\nlog 45\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-core/src/main/java/io/dropwizard/core/setup/Bootstrap.java
```

Related probes:
```bash
printf 'diagnostics\ninject "public <T extends java.util.Collection<String>> void use(T value) { value.§ }" at 40\nlog 50\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-e2e/src/main/java/com/example/app1/App1Resource.java

printf 'diagnostics\ninject "return call.call()." at 181\nlog 45\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-testing/src/main/java/io/dropwizard/testing/common/DAOTest.java
```

Cursor context:
```java
public class Bootstrap<T extends Configuration> {
    public void run(T configuration, Environment environment) throws Exception {
        configuration.§
    }
}

public <T extends java.util.Collection<String>> void use(T value) {
    value.§
}

public <T> T inTransaction(Callable<T> call) {
    return call.call().§
}
```

IntelliJ or JDT behavior:
Expected IDE behavior is to expose members available through the type variable's declared bound.
For `T extends Configuration`,
completion should show `Configuration` and `Object` members.
For `T extends Collection<String>`,
completion should show `Collection<String>` members.
For unbounded `T`,
completion should at least show `Object` members.

Original Lathe behavior:
Bounded type-variable receivers returned no completion items.
The log preserved the type variable but did not expand its bound:
```text
resolve receiver=|configuration| type=T static=false reattributed=false
proposals count=0 labels=[]

resolve receiver=|value| type=T static=false reattributed=true
proposals count=0 labels=[]
```

For the generic method return probe,
`Callable<T>.call().§` also returns no items:
```text
resolve receiver=|call.call()| type=null static=null reattributed=true
```

Resolution (7c38a0b — `fix: recover method-chain member completion`):
Both dimensions now resolve. Direct source fixtures resolve type-variable receivers (class type
variables, method type variables, unbounded method type variables, and unbounded
`Callable<T>.call()` returns), and the stale-cache/edit-buffer path is recovered. When the cached
attributed source is the previous valid file and the changed buffer adds `configuration.§`,
sentinel attribution is now used as a local recovery analysis so a `TYPEVAR` receiver is normalized
to its declared bound before candidate generation, and the recovery result is not cached as if it
represented the real editor content. `memberAccess_classTypeVariable_afterChange_usesDeclaredBound`
covers the recovered stale-cache case.

Expected Lathe behavior:
Type-variable member completion should use the effective upper bound.
If the bound is parameterized,
the substituted type should be used for method signatures:
for `T extends Collection<String>`,
`iterator()` should be shown as returning `Iterator<String>`,
`stream()` as `Stream<String>`,
and `forEach` as accepting `Consumer<? super String>`.

Suggested fix:
Treat a resolved `TYPEVAR` receiver from cached analysis as insufficient for member access.
Either:

- force fresh reattribution in `MemberAccessCompleter` when `initialResolved.type().getKind()` is
  `TYPEVAR`, just as it already does for `null` and `ERROR`; or
- normalize receiver types through a shared effective-completion-type helper before candidate
  generation,
  so `T` becomes its upper bound before `CandidateGenerator.proposeMemberAccessCandidates(...)`.

The second option is cleaner long-term because it also centralizes wildcard,
type-variable,
and future error-type fallback logic,
but the first option is likely the minimal fix for the currently reproduced stale-cache failure.

Accepted edit, if relevant:
Not applicable.
No candidate is returned.

Regression target:
`CompletionMemberAccessTest.memberAccess_classTypeVariable_usesDeclaredBound`
`CompletionMemberAccessTest.memberAccess_classTypeVariable_afterChange_usesDeclaredBound`
`CompletionMemberAccessTest.memberAccess_methodTypeVariable_usesDeclaredBound`
`CompletionMemberAccessTest.memberAccess_unboundedMethodTypeVariable_usesObjectBound`
`CompletionMemberAccessTest.memberAccess_unboundedTypeVariableMethodReturn_usesObjectBound`

Notes:
Generic type-reference completion while declaring bounds works:
`public <T extends RuntimeEx§> T identity(T value) { return value; }`
offers `RuntimeException` and accepts to
`public <T extends RuntimeException§> T identity(T value) { return value; }`.
Local generic class bounds also work for
`class Local<T extends RuntimeEx§`.

## CQ-0042 — Member access on a type-error receiver returns no candidates

ID: CQ-0042
Status: done
Target: M2
Tier: typed
Failure mode: missing-candidates
Owner component: TypeResolver / CompletionEngine
Discovery: 2026-06-28, CQ-0040 regression test authoring

Cursor context:
```java
// to() declared as: <D extends Binding<D>> D to(Class<? super T> contract)
// T inferred as Object; Runnable.class is Class<Runnable>, not Class<? super Object>
bind(new Object()).to(Runnable.class).§
```

Lathe behavior:
When the receiver expression has a compile error (javac attributes it as `TypeKind.ERROR`),
`TypeResolver.resolveByPosition` gets `null` from `effectiveDeclaredType` and returns no receiver.
Completion returns 0 items.

Expected Lathe behavior:
When `getTypeMirror` returns `ERROR` for a method invocation, fall back to the method element's
declared return type (obtained via `trees().getElement()` on the invocation path cast to
`ExecutableElement`).
Apply the same type-variable and wildcard unwrapping so the effective declared type is used for
member lookup.
This would expose `Binding` members even though the argument type is wrong — the user is
mid-edit and still wants completion to continue.

Root cause:
`effectiveDeclaredType` in `TypeResolver` returns `null` for `TypeKind.ERROR`.
No fallback to the element-level return type is attempted.

Suggested fix:
Keep javac as the source of truth.
Do not parse the invocation text.

1. In the receiver-resolution path,
   when the current path is a method invocation whose attributed type is `TypeKind.ERROR`,
   ask `Trees.getElement(path)` for the invoked method element.
2. If the element is an `ExecutableElement`,
   use its declared return type as a recovery candidate.
3. Run that candidate through the same effective-completion-type logic used for captured wildcards
   and type variables,
   so `<D extends Binding<D>> D` resolves to the usable `Binding` bound.
4. Keep the fallback narrow:
   only use it for method-invocation receivers with an `ERROR` attributed type.
   Do not broaden arbitrary unresolved expressions to declared-element fallback,
   because an unresolved variable or malformed selector does not have an equivalent declared return
   type.

Regression coverage:
`CompletionMemberAccessTest.memberAccess_typeErrorReceiver_fallsBackToElementReturnType`
and `CompletionMemberAccessTest.memberAccess_typeErrorMethodChain_returnsMembersWithoutFreshAnalysis`
are enabled and passing after the 7c38a0b fix. The error-typed method invocation's return type is
now recovered through javac trees and elements — with a conservative same-name/arity fallback when
javac misattributes the selector during recovery — and the recovery analysis is not returned as a
cacheable sentinel.

Notes:
Same root-cause family as CQ-0029, CQ-0030, and CQ-0040.
CQ-0040 (type-variable return, no type error) is fixed for M1.
This entry covers the error-recovery dimension: the call itself is type-incorrect but the
return type is still knowable from the method declaration.
It is also distinct from archived CQ-0032,
which forced reattribution when a stale snapshot had already resolved the receiver to
`TypeKind.ERROR`.

---

## CQ-0044 — Member access on a `var` local declared inside a lambda body returns no candidates

ID: CQ-0044
Status: done
Target: M2
Tier: typed
Failure mode: missing-candidates
Owner component: TypeResolver / CandidateGenerator
Discovery: 2026-06-29, real-workspace validation pass
Resolution: 2026-07-02, current explorer validation shows this no longer reproduces

Cursor context:
```java
// inside a forEach lambda block body
items.forEach(
    item -> {
      final var runner = new Runner(client, item);
      runner.§
    });
```

Lathe behavior:
Completion after `runner.` returns an **empty popup** — zero members.
`Runner` is a concrete type with a `public void validate()` declared directly on it, so the
expected behavior is that `validate()` (and the rest of its members) are offered.

Current behavior:
Completion after `runner.` now offers `Runner.name()`, `Runner.validate()`, and inherited
`Object` methods for all isolation probes below.
The original failing shape, an explicitly typed local inside the same lambda, the same `var` local
outside the lambda, and direct `new Runner().§` member access all pass through explorer.

Expected Lathe behavior:
Offer the members of `Runner`, including `validate()`, exactly as for any other concrete
receiver.

Root cause (hypothesis, to be verified):
The receiver is a `var` local whose type is *inferred* from `new Runner(...)`, and it is
declared **inside a lambda block body** that is incomplete while the user is typing `runner.`.
When the snapshot cannot fully attribute the enclosing lambda body, the `var` local has no resolved
declared type, so `TypeResolver.resolveReceiver` yields no receiver and `CandidateGenerator`
produces zero candidates.
The fact that `Runner` comes from a dependency JAR is believed incidental — member access on
dependency-JAR concrete types works elsewhere (Dropwizard/Helidon fluent chains).
The distinguishing factors are (1) `var` inference and (2) the lambda-body-under-edit recovery path.

Reproduction probes (to isolate the decisive factor):
1. Change `var` to an explicit `Runner runner = ...` and retype `.` — if members
   appear, the `var` inference path is the cause.
2. Move the same `var runner = new Runner(...)` + `runner.` out of the
   lambda to plain method-body scope — if members appear, the lambda-body recovery is the trigger.
3. Try `new Runner(...).§` directly with no local — isolates constructor/JAR resolution.

Suggested fix:
No code fix is currently needed.
Keep this entry as a regression note for the lambda-local `var` shape; if it regresses, preserve the
constraint that javac remains the source of truth and avoid parsing the lambda or declaration text.

Notes:
Adjacent to CQ-0042 (type-error receiver returns no candidates) but distinct: there the receiver
expression has an explicit compile error in a method chain; here the receiver is a well-formed `var`
local whose inferred type is unavailable during in-lambda editing.
Not a match for CQ-0029/CQ-0030/CQ-0040 (wildcard, type-variable, and captured-wildcard receivers) —
no generics are involved.

---

## CQ-0045 — Local-variable and parameter completion items carry no type detail

ID: CQ-0045
Status: done
Target: M2
Tier: presentation
Failure mode: missing-detail
Owner component: CandidateFactory
Discovery: 2026-06-30, gap validation pass

Cursor context:
```java
class Test {
    void m() {
        String greeting = "hi";
        System.out.println(gree§);
    }
}
```

Lathe behavior:
The `greeting` completion item is offered but its `detail`, `labelDetails.detail`, and
`labelDescription` are all `null`, so the popup shows only the bare name `greeting` with no type.
A field of the same name and type, by contrast, is presented with `detail = "String"` and
`labelDetails.description = "String"`.
The type is known at candidate-build time — it is stored on the candidate's `valueType` and used for
ranking — but it is never rendered.

Confirmed by a probe at the cursor above: the local-variable item resolves to
`label=greeting detail=null labelDetails=null`, while the field variant resolves to
`label=greeting detail=String labelDescription=String`.

Expected Lathe behavior:
Local-variable and method-parameter items should show their type the same way fields do —
`labelDescription` (and/or `detail`) set to the formatted type, so the popup reads `greeting : String`.
Presentation only; semantic filtering and ranking are unchanged.

Root cause:
`CandidateFactory.variableCandidate(name, type)` constructs the candidate through the 10-arg
`CompletionCandidate` constructor with `detail = null` and no `labelDetail`/`labelDescription`,
passing the type solely as `valueType`:

```java
CompletionCandidate variableCandidate(final String name, final TypeMirror type) {
  return new CompletionCandidate(
      name, name, CandidateKind.LOCAL_VARIABLE, null, name, false, null, type, null, null);
}
```

`fieldCandidate`, in contrast, formats the type via `TypeDisplayFormatter` and passes it as `detail`
and `labelDescription`.
`CompletionItemPresenter` faithfully renders whatever the candidate carries, so the empty detail
originates entirely in `variableCandidate`.

Suggested fix:
Format `type` with the existing `TypeDisplayFormatter` and pass it as `labelDescription` (mirroring
`fieldCandidate`) so locals, parameters, and fields present consistently.
Guard the `null`-type case (parameters whose element did not resolve are added with a `null` type in
`SimpleNameProvider.addMethodLocals`): omit the detail when the type is unknown rather than rendering
a placeholder.
Keep `valueType` for ranking. No change to candidate discovery or filtering.

Regression targets (added, `@Disabled` pending the fix):
- `CompletionPresentationTest.completionItem_localVariable_usesFormattedTypeDetail`
- `CompletionPresentationTest.completionItem_methodParameter_usesFormattedTypeDetail`

Notes:
Distinct from the typed-tier completion gaps (CQ-0029/0030/0040/0042/0044), which are about *missing
candidates*; here the candidate is present and correctly ranked, only its display detail is absent.
This is a presentation-tier gap — the tier the completion
[expectations](../planned/lathe-completion-expectations.md) defines as the "label vs detail"
separation.

---

## CQ-0046 — Boolean members are dropped in constructor-call argument completion, emptying the popup

ID: CQ-0046
Status: done
Target: M2
Tier: typed
Failure mode: missing-candidate
Owner component: TypeResolver / CompletionCandidateRanker
Discovery: 2026-06-30, real-workspace validation pass (`AppServer` constructor wiring)

Cursor context:
```java
class Config {
    boolean isReady() { return true; }
    String name() { return ""; }
}
class Service {
    Service(boolean flag) {}
}
class Test {
    void m() {
        Config config = new Config();
        final var svc = new Service(config.§);   // member access at a constructor boolean slot
    }
}
```

Lathe behavior:
Member-access completion after `config.` inside a **constructor** argument drops every
boolean-returning member.
`isReady()` (returns `boolean`) is absent while `name()` (returns `String`) is offered.
When the typed prefix matches only the boolean member (`config.isR§`), the popup is **empty**.
The same `config.` at a **method-call** argument slot (`accept(config.§)`) correctly offers
`isReady()`.

Confirmed by probes:
- `new Service(config.§)` → `[name, equals, getClass, hashCode, toString]` — `isReady` excluded.
- `new Service(config.isR§)` → `[]` — empty popup.
- `accept(config.§)` (method call, same types) → `[isReady, ...]` — present.
The defect is **not** `var`-specific: an explicitly-typed target (`final Service svc = ...`) behaves
identically, and it reproduces at any constructor argument index (observed on a real workspace at both
the first and the sixth argument of two different constructor calls).

Expected Lathe behavior:
A boolean-returning member is a valid completion at a `boolean` constructor argument and must be
offered (and, when the slot is boolean, ranked first).
More generally, constructor-argument slots should resolve the expected type from the constructor
parameter, exactly as method-argument slots do.

Root cause:
Two defects compound.

1. **Constructor-argument expected-type resolution is missing for the by-position path.**
   `TypeResolver.resolveArgumentValueByPosition` overrides only `visitMethodInvocation`; it never
   visits `NewClassTree`. For a member-access cursor inside a constructor argument, neither the
   name-based `resolveArgumentValue` nor the position-based scan resolves the constructor parameter
   type, so resolution falls through to `resolveInitializerValue`, which yields the **constructed /
   declared** type (here `Service`, a non-boolean) as the expected value.
2. **The asymmetric boolean-only filter then deletes the candidate.**
   With a non-boolean expected type in hand, `CompletionCandidateRanker.expectedTypeAllows`
   hard-excludes any candidate whose value type is `TypeKind.BOOLEAN` (returning
   `booleanCompatible(expected)`), while non-boolean mismatches fall through to `return true`. This is
   the exact asymmetry recorded in CQ-0043; it is what removes `isReady()` while keeping `name()`.

Suggested fix:
Keep javac as the source of truth; route through the existing sentinel/recovery pipeline rather than
parsing.

1. Resolve constructor-argument expected types by position: handle `NewClassTree` in the
   argument-by-position scan (mirroring the `visitMethodInvocation` branch and the existing
   `resolveConstructorArgumentValue` lookup) so the `boolean` parameter type is recovered for a
   member-access cursor.
2. Fix CQ-0043 (rank rather than hard-exclude boolean value types) so a boolean member is never
   *deleted* even when the expected type is mis-resolved — only demoted. Either fix alone restores the
   user-visible candidate; both are warranted, and they should be implemented together.

Regression targets:
- `CompletionArgumentTest.constructorArgument_memberAccess_booleanReturn_offeredAtBooleanSlot` (added, `@Disabled`)
- `CompletionArgumentTest.constructorArgument_booleanPrefix_popupNotEmpty` (added, `@Disabled`)
- `TypeResolverTest.resolveExpectedValue_constructorArgumentByPosition_resolvesParamType` (proposed; no `TypeResolverTest` exists yet)

Notes:
This is the real-workspace reproduction of CQ-0043: the user's symptom (an empty popup after
`config.isR` in `new Service(config.isReady())`) is the boolean-only exclusion firing against a
mis-resolved constructor-argument expected type.
Related to CQ-0044 (member access under in-edit `var` in a lambda) only superficially — there the
receiver type is unavailable; here the receiver (`config`) resolves fine and its members are computed,
but the boolean ones are filtered out.

---

## CQ-0048 — `instanceof` is never offered as a keyword candidate in expression position

ID: CQ-0048
Status: done
Target: M2
Tier: assistive
Failure mode: missing-candidate
Owner component: KeywordProvider
Resolution: 2026-07-02, `instanceof` is offered after reference-typed expressions and suppressed after primitives

Cursor context:
```java
class Test {
    void m(Object o) {
        if (o ins§) {}        // expect: instanceof
        boolean b = o ins§;   // expect: instanceof
    }
}
```

Lathe behavior:
`instanceof` is never suggested. Code inspection confirms the literal `"instanceof"` appears in **no**
keyword list in `KeywordProvider` (`VALUE_EXPRESSIONS`, `CONTROL_FLOW`, etc.) and nowhere else in the
completion module, so no sentinel context can ever produce it. After a reference-typed expression in an
expression slot — where `instanceof` is the natural continuation — completion offers only the
value-expression starters (`new`, `null`, `true`, `false`, `this`, `super`).

Expected Lathe behavior:
When the cursor follows a **reference-typed** expression in a position where a boolean/expression
continuation is legal, `instanceof` should be offered as a keyword candidate (accepting it would yield
`o instanceof `, ready for a type). It must **not** be offered after a primitive-typed expression, where
`instanceof` is illegal, nor as a standalone statement starter.

Root cause:
`KeywordProvider` has no `instanceof` entry in any list, and `selectKeywords` for an expression-position
`SIMPLE_NAME` returns `VALUE_EXPRESSIONS`, which contains only value starters. There is no rule that
inspects the preceding expression's type to offer the infix `instanceof` operator keyword.

Suggested fix:
Add `instanceof` as a keyword candidate offered in expression position when the preceding expression
resolves (via javac attribution, not text scanning) to a reference type. Gate it on the receiver type
being non-primitive so it is suppressed for primitive expressions.

Implemented behavior:
The simple-name completion path now supplements keyword candidates with `instanceof` when javac's
recovered AST and attribution identify the preceding expression as reference-typed.
Primitive expressions and statement-start keyword slots do not receive the candidate.

Regression targets:
- `CompletionSimpleNameTest.simpleName_afterReferenceExpression_offersInstanceof`
- `CompletionSimpleNameTest.simpleName_afterPrimitiveExpression_omitsInstanceof`
- `CompletionSimpleNameTest.simpleName_atStatementStart_omitsInstanceof`

Notes:
Distinct from CQ-0011 (constructor-invocation keyword over-offering): this is a *missing* assistive
keyword, not an invalid one. The type-gating requirement (reference vs primitive LHS) is what keeps it
from being a plain unconditional keyword addition.

---

## CQ-0049 — Type-index completion offers types from modules the current module does not read

ID: CQ-0049
Status: accepted
Target: M1
Tier: correctness
Failure mode: invalid-candidate
Owner component: TypeIndexValidator

Cursor context:
```java
// module-info.java:  module com.example.app { }   (no `requires java.desktop`)
package com.example.app;

class Test {
    JBut§ field;    // expect: no JButton — javax.swing is not readable here
}
```

Lathe behavior:
`JButton` (`javax.swing.JButton`, module `java.desktop`) is offered as a completion candidate even
though the enclosing module never `requires java.desktop`. Confirmed on the `payment-dob-lathe`
workspace: in `dob-core` (whose `module-info.java` does not read `java.desktop`) Swing and other
unread-module types complete via the type-index path, while a real `import javax.swing.JButton;`
correctly fails to compile. `java.desktop` is pulled into the module graph by a dependency's
non-transitive `requires`, so it is *observable* but not *readable* by `dob-core`.

Expected Lathe behavior:
Type-index candidates must be filtered by JPMS readability from the current compilation unit's
module, matching what a real `import` would accept. A type in a module the current module does not
read must not be offered. `java.base` types and types in read modules continue to be offered.

Root cause:
`TypeIndexValidator.isResolvable` gates candidates on `Elements.getTypeElement(qualifiedName) != null`
(`TypeIndexValidator.java:30`). Single-arg `getTypeElement` performs a global lookup across every
*observable* module in the graph and ignores whether the current module *reads* the type's module, so
observable-but-unreadable types pass the filter. The correct primitive is
`Trees.isAccessible(scope, typeElement)`, already used on the member-access path
(`CandidateGenerator`) and the import path (`ImportCompletionProvider.java:70`); the type-index path
is the only one that skips it. The comment in `TypeIndexValidator` claiming `getTypeElement` "follows
JPMS module-boundary semantics automatically" is incorrect — it conflates observability with
readability.

Suggested fix:
Gate `TypeIndexValidator` on `Trees.isAccessible(scope, typeElement)` in addition to resolving the
element, threading the completion `scope` (already computed in
`TypeReferenceCompleter.completeSimpleNameTypeReference`) into the validator, and computing a scope
for the `CompletionEngine.staticMemberFitCandidates` call site. Preserve the permissive fallback when
no scope is available (mirroring `ImportCompletionProvider`'s `scope == null || …` guard).

Regression targets:
- `CompletionTypeIndexTest.typeIndex_jpmsObservableButUnreadableModule_doesNotSuggestIndexedType`
  (present, `@Disabled` pending fix — reproduces the gap via `--add-modules java.desktop` on a module
  that does not `requires` it)
- `CompletionTypeIndexTest.typeIndex_jpmsReadablePackage_suggestsIndexedType` (guard: `requires
  java.desktop` still offers `JButton`)
- `CompletionTypeIndexTest.typeIndex_platformType_survivesValidator_jpmsModule` (guard: `java.base`
  types survive)

Notes:
Only the type-index type-reference path leaks; the member-access and import paths already enforce
readability via `Trees.isAccessible`. The unit test that previously "covered" this
(`typeIndex_jpmsUnreadablePackage_doesNotSuggestIndexedType`) passed only because its minimal module
graph never made `java.desktop` observable, so `getTypeElement` returned `null` for the wrong reason
— it never exercised the observable-but-unreadable case that occurs in real multi-module workspaces.

---

## Current Triage

All accepted completion-quality gaps from the `DropwizardResourceConfig` explorer pass have been resolved or triaged.

The latest Helidon/Dropwizard explorer pass added `CQ-0023`,
`CQ-0024`,
`CQ-0025`,
`CQ-0026`,
`CQ-0028`,
`CQ-0029`,
and `CQ-0030`.
It also reconfirmed deferred `CQ-0002` with additional method-reference probes on
`List::stream`,
`Duration::toMilliseconds`,
and `poolConfig::setValidationQuery`.

`CQ-0001`,
`CQ-0003`,
`CQ-0004`,
`CQ-0005`,
`CQ-0006`,
`CQ-0007`,
`CQ-0008`,
`CQ-0009`,
`CQ-0012`,
`CQ-0013`,
`CQ-0014`,
`CQ-0015`,
`CQ-0016`,
`CQ-0017`,
`CQ-0018`,
`CQ-0019`,
`CQ-0020`,
and `CQ-0021` are fixed and covered by regression tests.
A second explorer pass covering `LoomServer`, `DropwizardTestSupport`, `BaseConfigurationFactory`,
`ProxyProtocolHandler`, and `Environment` confirmed that lambda body member access,
fluent builder chains,
catch block exception types,
instanceof pattern variables,
static import completion,
multi-catch second type,
ternary branch member access,
stream map lambda parameter types,
and record accessor member access all work correctly.

Two new high-confidence gaps were found and recorded as `CQ-0020` and `CQ-0021`.

`CQ-0002` is planned for M2.
`CQ-0011` remains deferred.
`CQ-0029` and `CQ-0030` are planned for M2.
`CQ-0010` is closed as an editor-side capability gap.

A 2026-06-26 sample-workspace pass on `AppServer` (anonymous `AbstractBinder.configure()`) recorded
`CQ-0040`: member completion on the captured-wildcard result of `bind(x).to(Y.class)` returns nothing.
It shares the `CQ-0029`/`CQ-0030` root cause but is pulled into M1 because it breaks the ubiquitous
HK2/Jersey binder DSL on a real workspace.

A 2026-06-29 validation pass recorded two new `documented` gaps awaiting triage:
`CQ-0043` (argument-position type filtering excludes boolean returns but lets other mismatched types
through) and `CQ-0044` (member access on a `var` local declared inside a lambda body returns no
candidates, found on `AppServer` `runner.validate()`).

A 2026-06-30 validation pass recorded two more `documented` gaps:
`CQ-0045` (local-variable and parameter completion items carry no type detail, while fields do) and
`CQ-0046` (boolean members are dropped in constructor-call argument completion, emptying the popup —
the real-workspace reproduction of `CQ-0043`, found on `AppServer` constructor wiring
`new Service(config.isReady())`).
The same pass investigated a reported "boolean variable or boolean-returning method not offered for a
boolean parameter" and could **not** reproduce it at ordinary boolean argument slots: at a `boolean`
(or boxed `Boolean`) parameter, boolean locals and boolean-returning methods are offered and
top-ranked across typed-prefix, second-parameter, overloaded, and `if`-condition contexts. The only
failing case is the constructor-argument member-access path, which is captured as `CQ-0046`; no
separate gap was opened for the boolean-slot claim.

A 2026-07-01 validation pass on `payment-dob-lathe` recorded `CQ-0049`: type-index completion offers
types from modules the current module does not read (Swing and AWS types complete in `dob-core`,
which does not `requires java.desktop`). It is a correctness gap pulled into M1 — the type-index path
gates on `Elements.getTypeElement` (observability) instead of `Trees.isAccessible` (JPMS readability),
unlike the already-correct member-access and import paths. A `@Disabled` regression test reproduces it.

Next completion work should run a new explorer pass with a different focus area,
or pick up one of the gaps explicitly assigned to M2.

## CQ-0011 — Constructor invocation keywords can be offered when an explicit invocation already exists

ID: CQ-0011
Status: deferred
Target: backlog
Tier: semantic
Failure mode: invalid-keyword-candidate
Owner component: KeywordProvider / SentinelParser

Re-triage (2026-07-01):
Deferred out of M2. On review the accepted M2 approach — withholding the `this`/`super` keywords in
constructor statement slots — is wrong from a developer's point of view. Lathe's keyword completion
only ever inserts the bare identifier `this`/`super`, which is a valid and very common expression at
every statement position in a constructor (`this.field = x;`, `super.init();`). Suppressing it to
avoid the rare, self-diagnosing case of a second explicit invocation would remove a constantly
needed completion and would violate this gap's own note ("should not block ordinary `this`
expression completion"). The correct treatment is a future `this(...)`/`super(...)` call-shape
snippet offered *only* at the legal first-statement slot, alongside the always-available bare
keyword — a completion feature, not a suppression — which is blocked until constructor-invocation
snippet completion exists.

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'inject "this" at 63\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
public DropwizardResourceConfig(@Nullable MetricRegistry metricRegistry) {
    this§
    super();
    ...
}
```

IntelliJ or JDT behavior:
Expected IDE behavior is to avoid suggesting an explicit constructor invocation
when the constructor body already contains one.
Java permits at most one explicit constructor invocation,
and it must be the first statement in the constructor body.

Lathe behavior:
Lathe offers the `this` keyword at the first statement slot before an existing `super();`.
Accepting it as a constructor invocation starter would leave both `this...` and `super();`
in the same constructor body.

Expected Lathe behavior:
`this` and `super` constructor-invocation keyword candidates should be offered only when the current
constructor does not already contain an explicit `this(...)` or `super(...)` invocation.
They should also be constrained to the first-statement position.

Accepted edit, if relevant:
Not applicable until constructor-invocation completion adds call-shape snippets.

Regression target:
Future keyword completion test for constructor first-statement rules.

Notes:
This gap is about `this` and `super` as explicit constructor invocation starters.
It should not block ordinary `this` expression completion,
`this.member` access,
or `super.member` access where those are otherwise legal.

## CQ-0002 — Method-reference completion returns no candidates

ID: CQ-0002
Status: accepted
Target: M3
Tier: assistive
Failure mode: missing-candidate
Owner component: SentinelInjector / SentinelParser

Project/file:
`/home/ag-libs/git/helidon/dbclient/tracing/src/main/java/io/helidon/dbclient/tracing/DbClientTracingProvider.java`

Probe command:
```bash
printf 'complete after "List::" expect of min 1\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/dbclient/tracing/src/main/java/io/helidon/dbclient/tracing/DbClientTracingProvider.java
```

Related project/file:
`/home/ag-libs/git/helidon/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClientBuilder.java`

Related probe:
```bash
printf 'complete after "this::" expect url username password min 1\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClientBuilder.java
```

Cursor context:
```java
config.asNodeList().orElseGet(List::of)
connConfig.get("url").asString().ifPresent(this::url)
```

IntelliJ or JDT behavior:
Expected IDE behavior is method-reference completion after `Type::` and `this::`.

Lathe behavior:
No completions are returned.
The log shows `parsed valid=false sentinelCtx=null` after `List::` and after `this::`.

Expected Lathe behavior:
Eventually,
method-reference completion should offer compatible methods for the receiver and target functional interface.

Accepted edit, if relevant:
Accepting `of` after `List::` should produce `List::of`.
Accepting `url` after `this::` should produce `this::url`.

Future design:
Method-reference completion is deferred until after M2.
The first implementation slice should be basic receiver-member listing,
not full smart compatibility filtering.
Add a `METHOD_REFERENCE` sentinel site,
detect `::`,
capture receiver text similarly to member access,
and route simple cases through member candidate generation.
`TypeName::` should offer static methods such as `List::of`;
`this::` should offer visible instance methods such as `this::url`;
ordinary expression receivers such as `service::` should offer instance methods.
Expected functional-interface filtering should be a later slice,
because robust compatibility needs the target type from contexts such as `orElseGet`,
`ifPresent`,
and `stream.map`.
Constructor references such as `TypeName::new` and array constructor references are also later slices.

Regression target:
Future method-reference completion test class or `CompletionEngineTest` method-reference section.

Notes:
This matches the existing deferred method-reference gap in the historical completion docs.
