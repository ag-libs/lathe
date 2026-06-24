# Lathe — M1 Exploration Gaps

This document records gaps found during a systematic live-probing session against the
Helidon (332-module) and Dropwizard (68-module) workspaces.
Each gap was confirmed against a running Lathe server with `LATHE_DEBUG=1` and is independent of
explore.py positioning behaviour.

Gaps that are already tracked under an existing design document are cross-referenced rather than
duplicated here.

## Status

| Gap | Title | Milestone |
|---|---|---|
| EG-001 | Signature help selects inner method when argument is itself a method call | M1 |
| EG-002 | Wrap-with-try/catch absent for `UNREPORTED_EXCEPTION` in method bodies | M1 |
| EG-003 | Hover returns null inside Javadoc type-reference tags | M2 |
| EG-004 | Hover returns null on import declarations | M1 |
| EG-005 | Workspace symbol search is prefix-only; CamelCase and infix queries miss results | M2 |
| EG-006 | Workspace symbol results rank reactor-local types below dependency and JDK types | M1 |
| EG-007 | Type-index startup emits hundreds of WARNING-level duplicate-type messages | M1 |
| EG-008 | Object synchronization methods appear in member-access completion results | M1 |
| EG-009 | Outgoing calls includes anonymous class constructor instantiations with empty name | M1 |
| EG-010 | `explore.py` cannot probe dep/JDK source files — no workspace context for cache paths | M1 |
| EG-011 | Outgoing calls silently omits callees whose source is in extracted dep or JDK dirs | M2 |
| EG-012 | `textDocument/declaration` not implemented; overriding method declarations have no path to the contract/interface method | M2 |

EG-003 and EG-005 are deferred to M2:
EG-003 requires `DocTrees` attribution of Javadoc comment positions, which is a non-trivial
hover extension;
EG-005 requires building a secondary CamelCase initial index alongside the existing prefix
structure, which is an enhancement rather than a correctness gap.

---

## EG-001 — Signature help selects the inner method's signature when the argument is itself a method call

**Milestone: M1**

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

**Milestone: M1**

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

### Relationship to existing Gap 1

`lathe-code-actions-gaps.md` Gap 1 identifies this for the **lambda** case and proposes
`TryCatchWrapProvider` as the fix.
This gap confirms that `TryCatchWrapProvider` is absent entirely — the lambda-context
route is not the only missing branch; the baseline non-lambda method-body route is also missing.

`status.md` has been corrected: try/catch wrapping is **not implemented**.

### Proposed fix

Implement `TryCatchWrapProvider` as described in `lathe-code-actions-gaps.md` Gap 1.
The provider must handle both contexts:

- Regular method body: wrap the statement containing the throw or checked call in a
  `try { … } catch (ExceptionType e) { }` block.
- Lambda/anonymous-class body: same wrapping, targeted at the statement within the lambda.

Once `TryCatchWrapProvider` is implemented, the `AddThrowsProvider` lambda-suppression from
`lathe-code-actions-gaps.md` Gap 1 should be applied alongside it.

### Regression targets

`CodeActionTest.codeAction_unreportedException_methodBody_offersBothWrapAndThrows`
`CodeActionTest.codeAction_unreportedException_lambdaBody_offersOnlyWrap`

---

## EG-003 — Hover returns null on positions inside Javadoc type-reference tags

**Milestone: M2**

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

**Milestone: M1**

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

**Milestone: M2**

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

**Milestone: M1**

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

**Milestone: M1**

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

**Milestone: M1**

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
| variable declaration works | `DeclareVariableProvider` is not implemented; see code-actions-gaps.md Gap 2 |

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

**Milestone: M1**

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

**Milestone: M1**

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

**Milestone: M2**

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

## EG-012 — `textDocument/declaration` not implemented; no path from override to contract method

**Milestone: M2**

### Observed behaviour

Java IDEs commonly provide a quick way to navigate from an overriding method declaration to the
method it overrides in a superclass or interface.
Lathe does not currently expose that navigation.

Probing `MongoDbClient` in Helidon confirms:

```java
public class MongoDbClient extends DbClientBase implements DbClient {
  @Override
  public DbExecute execute() { ... }

  @Override
  public DbTransaction transaction() { ... }

  @Override
  public String dbType() { ... }

  @Override
  public <C> C unwrap(Class<C> cls) { ... }
}
```

Running `definition` on the overriding method declarations returns the declaration itself:

| Cursor target | Actual definition result |
|---|---|
| `MongoDbClient.execute()` | `MongoDbClient.execute()` |
| `MongoDbClient.transaction()` | `MongoDbClient.transaction()` |
| `MongoDbClient.dbType()` | `MongoDbClient.dbType()` |
| `MongoDbClient.unwrap(...)` | `MongoDbClient.unwrap(...)` |

The reverse direction works through `textDocument/implementation`:
running `impl` on the corresponding `DbClient` interface declarations returns both
`JdbcClient` and `MongoDbClient` implementations.

Normal call-site definition also behaves correctly:

- `MongoDbClient dbClient; dbClient.execute()` jumps to `MongoDbClient.execute()`;
- `DbClient dbClient; dbClient.execute()` jumps to `DbClient.execute()`;
- inherited superclass calls such as `context()` inside `MongoDbClient.execute()` jump to
  `DbClientBase.context()`.

The missing behavior is specifically declaration-site navigation from an override to the
overridden method.

### Definition vs. declaration in LSP

In LSP the intended split is language-dependent, but the general contract is:

- **Definition** — where the symbol is actually defined/implemented.
- **Declaration** — where the symbol is declared as an API/contract, which may not be the implementation.

In Java, most symbols do not have a separate declaration and definition.
A class, field, local variable, or normal method has one source location, so both can
reasonably return the same place.

The distinction becomes useful for overrides:

```java
interface DbClient {
    DbExecute execute();       // declaration / contract
}

class MongoDbClient implements DbClient {
    @Override
    public DbExecute execute() {   // definition / implementation
        ...
    }
}
```

Expected navigation model:

| Cursor location | Go to Definition | Go to Declaration |
|---|---|---|
| `dbClient.execute()` where `dbClient` is `MongoDbClient` | `MongoDbClient.execute()` | `DbClient.execute()` |
| `dbClient.execute()` where `dbClient` is `DbClient` | `DbClient.execute()` | `DbClient.execute()` |
| `MongoDbClient.execute()` declaration itself | `MongoDbClient.execute()` | `DbClient.execute()` |
| `DbClient.execute()` declaration | `DbClient.execute()` | `DbClient.execute()` or empty |

### Proposed fix

Implement `textDocument/declaration`.
Do not change `textDocument/definition`.

- For an overriding method declaration, `declaration` resolves the overridden superclass or
  interface method and returns its source location.
- For everything else (non-override methods, fields, types, locals), `declaration` falls back
  to `definition` for usability.

Implementation uses javac override checks:

1. Resolve the `ExecutableElement` at the cursor and the enclosing `TypeElement`.
2. Walk direct supertypes using `Types.directSupertypes(...)`.
3. For each candidate method in each supertype, call `Elements.overrides(current, candidate, enclosingType)`.
4. Return source locations for matching candidates using the existing definition/source-location machinery.

Multiple inherited interface declarations may return multiple locations.
The declaration result for a non-overriding method falls back to the definition location.

### Probe commands

```bash
printf 'definition 96:19\ndefinition 101:23\ndefinition 106:16\ndefinition 111:15\nquit\n' \
  | env LATHE_TIMEOUT=90 python3 dev/explore.py \
      /home/ag-libs/git/helidon/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClient.java

printf 'impl 52:14\nimpl 64:18\nimpl 45:11\nimpl 77:10\nquit\n' \
  | env LATHE_TIMEOUT=90 python3 dev/explore.py \
      /home/ag-libs/git/helidon/dbclient/dbclient/src/main/java/io/helidon/dbclient/DbClient.java
```

### Regression targets

- `DeclarationTest.declaration_overridingMethodDeclaration_returnsInterfaceMethod`
- `DeclarationTest.declaration_overridingMethodDeclaration_returnsSuperclassMethod`
- `DeclarationTest.declaration_nonOverridingMethod_fallsBackToDefinition`
- `DeclarationTest.declaration_callSiteWithConcreteType_fallsBackToDefinition`

---

## M1 Implementation Order

Items without dependencies may proceed in parallel.

1. **EG-007** (WARNING flood) — downgrade duplicate-type log messages to FINE and deduplicate
   at merge time.
   Self-contained; improves log signal for all subsequent debugging.

2. **EG-002** (try/catch wrap) — implement `TryCatchWrapProvider` for both regular method and
   lambda contexts.
   Closes the main code-action gap and fixes a false status.md claim.
   See also `lathe-code-actions-gaps.md` Gap 1 for the lambda-suppression companion change to
   `AddThrowsProvider`.

3. **EG-001** (signature help inner method) — fix backward-scan anchor in `SignatureHelpLocator`.
   No design dependencies.

4. **EG-008** (Object sync methods) — add three-entry suppression list to the member-access
   candidate filter.
   Minimal change, no design dependencies.

5. **EG-006** (workspace symbol ranking) — add reactor-origin sort boost to the result
   comparator in `WorkspaceTypeIndex`.
   No structural design needed; requires reading `TypeIndexEntry` origin information.

6. **EG-004** (hover on import) — add `ImportTree` element extraction to `HoverLocator`.
   Small, bounded change.

7. **EG-009** (anonymous callee) — skip `NewClassTree` nodes with an empty simple name in
   `CallHierarchyOutgoingLocator`.
   One-line guard, no design dependencies.

8. **EG-010** (`explore.py` workspace flag) — add `--workspace <path>` argument to `explore.py`.
   Dev-tooling only; self-contained.

EG-011 and EG-012 are M2 work, tracked alongside the external-source Find References and
navigation scope expansion.
