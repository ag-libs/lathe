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

EG-003 and EG-005 are deferred to M2:
EG-003 requires `DocTrees` attribution of Javadoc comment positions, which is a non-trivial
hover extension;
EG-005 requires building a secondary CamelCase initial index alongside the existing prefix
structure, which is an enhancement rather than a correctness gap.

---

## EG-001 — Signature help selects the inner method's signature when the argument is itself a method call

**Status: accepted — Target: M1**

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

**Status: accepted — Target: M1**

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

**Status: accepted — Target: M1**

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

## EG-005 — Workspace symbol search uses strict prefix matching; CamelCase and infix queries find nothing

**Status: accepted — Target: M2**

### Observed behaviour

```
sym "ServerFactory"   → ServerFactory (interface), ServerFactoryImpl (JDK SASL internal)
                        missing: AbstractServerFactory, DefaultServerFactory, SimpleServerFactory
sym "TaskMgr"         → 0 results (no CamelCase abbreviation matching)
sym "AbstractServer"  → AbstractServerFactory (correctly found by prefix)
```

Developers routinely search for types by a substring that appears in the middle of the class name,
or by CamelCase initials.
The current prefix-only contract makes workspace symbol search significantly less useful.

### Root cause

`WorkspaceTypeIndex.search(prefix, limit)` uses a prefix trie or sorted key structure.
The lookup starts from the prefix and stops at the end of that alphabetic range.
There is no infix scan or CamelCase decomposition path.

### Proposed fix

Add a secondary CamelCase initialism index entry alongside each type name.
For example, `AbstractServerFactory` generates entries keyed on `ASF`, `ASFac`, `AS`, etc.
For substring search, the most practical approach is a trigram or suffix index, or a sorted list
of `(reverse-suffix, fqn)` pairs enabling suffix-prefix lookups.

The minimal fix is CamelCase initial matching:
decompose each type name into its uppercase-initial subsequences at index build time and store
them in a secondary map.
At query time, if no prefix hit is found, check whether the query matches any CamelCase
abbreviation.

### Regression targets

`WorkspaceTypeIndexTest.search_camelCaseAbbreviation_findsMatchingTypes`
`WorkspaceTypeIndexTest.search_infixSubstring_findsContainingTypes`

---

## EG-006 — Workspace symbol results rank reactor-local types below dependency and JDK types

**Status: accepted — Target: M1**

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

**Status: accepted — Target: M1**

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

### Proposed fix

Two complementary changes:

1. **Downgrade duplicate-type messages to FINE.**
   Duplicate types are a structural inevitability for common JARs and are not actionable by the
   user.
   Only log at WARNING when the duplicates are in the same shard namespace (which would indicate
   a real indexing error).

2. **Deduplicate at merge time rather than at first-occurrence.**
   When building the merged index, keep the first seen entry per fully-qualified name and skip
   subsequent entries silently (or at FINE).
   This prevents the O(N) WARNING flood without sacrificing correctness, since all entries for a
   given type should be structurally equivalent.

### Regression targets

`WorkspaceTypeIndexTest.merge_duplicateTypeAcrossShards_logsAtFineNotWarning`
`WorkspaceTypeIndexTest.merge_duplicateTypeAcrossShards_keepsFirstEntry`

---

## EG-008 — Object synchronization methods appear in member-access completion results

**Status: accepted — Target: M1**

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

**Status: accepted — Target: M1**

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

---

## EG-010 — `explore.py` cannot probe dep/JDK source files — no workspace context for cache paths

**Status: accepted — Target: M1**

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

**Status: accepted — Target: M2**

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

## EG-014 — Find References on an overriding method returns only exact-static-type call sites

**Status: accepted — Target: M2**

### Observed behaviour

Find References on an overriding method declaration returns no polymorphic call sites; the same
search on the overridden interface or superclass method returns them all.

```
refs "getType" on Request.getType()       (interface declaration)  → 14 references
refs "getType" on CreateEntity.getType() (@Override declaration)   → 0 references
```

`CreateEntity implements Request`, and every `getType()` call site in the workspace has a
statically `Request`-typed receiver, so each call resolves to `Request.getType()`.
There are zero call sites with a statically `CreateEntity`-typed receiver.

### Analysis

The 0-result is exact-element-correct: no call statically dispatches to `CreateEntity.getType()`.
But a developer invoking Find References from an override expects to see the usages of the method
they are looking at, which in practice are the polymorphic uses of the overridden contract.

This is the Find References mirror of EG-012 (`textDocument/declaration` from an override to its
contract).
EG-012 navigates override → contract; this gap is about pulling the contract's usages into the
override's reference results.

### Proposed fix

When Find References is invoked on an overriding method declaration, additionally search for
references to the method(s) it overrides:

1. Resolve the `ExecutableElement` at the cursor and its enclosing `TypeElement`.
2. Walk supertypes with `Types.directSupertypes(...)` and, for each candidate method, test
   `Elements.overrides(current, candidate, enclosingType)`.
3. Union the reference results for the override and each overridden declaration, de-duplicated by
   location.

This reuses the EG-012 override-resolution walk, so the two should be implemented together.
The reverse direction (Find References on a base method already includes override declarations)
is out of scope for this gap.

### Probe commands

```bash
printf 'refs "getType"\n' \
  | python3 dev/explore.py \
      /workspace/app-core/src/main/java/com/example/app/model/CreateEntity.java
```

### Regression targets

- `ReferenceServiceTest.references_overridingMethod_includesOverriddenContractUsages`
- `ReferenceServiceTest.references_nonOverridingMethod_unchanged`

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

**Status: accepted — Target: M1**

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

**Status: accepted — Target: M2**

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

Status: accepted — Target: M2.
Requires a product decision on the external-symbol search-scope policy (see below).

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

Status: accepted — Target: M2.
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

Status: accepted — Target: M2.
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

## FR-005 — Client response incident is not reproducibly covered

Status: deferred — Target: backlog.
Observed incident; server defect not established.

In the Helidon incident, the server log ended immediately after:

```text
[references] MongoDbClient.java element=Duration hits=2
[references] MongoDbExecute.java element=Duration hits=0
[references] MongoDbClient.java 12ms target=Duration hits=2
```

There was no server exception, fatal JVM message, shutdown record, or RPC exit record.
Lathe therefore completed reference computation successfully, but Neovim did not display the two
locations before the editor/session connection ended.

This does not establish a Lathe crash.
It establishes that the current tests stop before JSON-RPC client receipt and cannot distinguish a
server response failure from an editor-side display or process failure.

### Required investigation support

- The invoker client must await and assert the actual references response.
- A focused service test should verify that the `CompletableFuture` completes with serializable LSP
  `Location` values.
- Operation logging should retain the request URI, target, elapsed time, and final hit count.
- If another incident occurs, capture the Neovim process exit status and RPC client-exit event in
  addition to the server log.

No production fix should be attributed to this incident until it is reproduced outside the editor or
an RPC/client error is captured.

### Find References notes

The FR slice is derived by `Target` like every other family.
Guidance that does not fall out of the fields: the external-source correctness fix (FR-002) and the
failure-propagation change (FR-003) should be independently reviewable, and neither should be
combined with candidate-index optimization.

### Find References verification

```bash
mvn spotless:apply
mvn test -pl lathe-server
mvn verify -pl lathe-maven-plugin -Dinvoker.test=multi-module
```

Final verification must demonstrate all four request origins:

| Target origin | Symbol origin | Expected search result |
|---|---|---|
| Project source | Reactor | Exact project references |
| Project source | JDK/dependency | Exact project references |
| Cached external source | JDK | Exact project references |
| Cached external source | Dependency | Exact project references |

---

# Code Action Gaps (CA-N)

Gaps found during live probing of `textDocument/codeAction` on the Dropwizard and Helidon
codebases after the initial code-action implementation (`ImportQuickFixProvider` +
`AddThrowsProvider`).
Each gap describes the observed behaviour, the root cause, and the proposed fix.

## CA-1 — `UNREPORTED_EXCEPTION` inside a lambda body has no action

**Status: accepted — Target: M1.**

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

**Status: accepted — Target: M1.**

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

**Status: accepted — Target: M1.**

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

**Status: accepted — Target: M1.**

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

## CQ-0040 — `bind(...).to(...)` chain offers no members on the captured-wildcard result

ID: CQ-0040
Status: accepted
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
Status: deferred
Target: backlog
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
Status: accepted
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

Lathe behavior:
All wildcard probes return no completion items.
The log shows `sentinelCtx=MEMBER_ACCESS` but receiver resolution fails:
```text
resolve receiver=|numbers.iterator().next()| type=null static=null reattributed=true
resolve receiver=|numbers.get(0)| type=null static=null reattributed=true
resolve receiver=|numbers.get("x")| type=null static=null reattributed=true
resolve receiver=|values.iterator().next()| type=null static=null reattributed=true
```

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

Regression target:
`CompletionMemberAccessTest.memberAccess_wildcardExtendsCollectionElement_usesUpperBound`
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
Status: accepted
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

Lathe behavior:
Bounded type-variable receivers return no completion items.
The log preserves the type variable but does not expand its bound:
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

Expected Lathe behavior:
Type-variable member completion should use the effective upper bound.
If the bound is parameterized,
the substituted type should be used for method signatures:
for `T extends Collection<String>`,
`iterator()` should be shown as returning `Iterator<String>`,
`stream()` as `Stream<String>`,
and `forEach` as accepting `Consumer<? super String>`.

Accepted edit, if relevant:
Not applicable.
No candidate is returned.

Regression target:
`CompletionMemberAccessTest.memberAccess_classTypeVariable_usesDeclaredBound`
`CompletionMemberAccessTest.memberAccess_methodTypeVariable_usesDeclaredBound`
`CompletionMemberAccessTest.memberAccess_unboundedMethodTypeVariable_usesObjectBound`

Notes:
Generic type-reference completion while declaring bounds works:
`public <T extends RuntimeEx§> T identity(T value) { return value; }`
offers `RuntimeException` and accepts to
`public <T extends RuntimeException§> T identity(T value) { return value; }`.
Local generic class bounds also work for
`class Local<T extends RuntimeEx§`.

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

Next completion work should run a new explorer pass with a different focus area,
or pick up one of the gaps explicitly assigned to M2.

## CQ-0011 — Constructor invocation keywords can be offered when an explicit invocation already exists

ID: CQ-0011
Status: deferred
Target: backlog
Tier: semantic
Failure mode: invalid-keyword-candidate
Owner component: KeywordProvider / SentinelParser

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
Target: M2
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
Method-reference completion is M2 work.
It is not required for M1 Internal Preview.
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
