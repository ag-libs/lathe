# Lathe — M1 Exploration Gaps

This document records gaps found during a systematic live-probing session against the
Helidon (332-module) and Dropwizard (68-module) workspaces.
EG-013 and EG-014 were added from a later session against the sample-workspace (25-module)
workspace, which makes heavy use of the `record-companion` `@Builder` annotation processor.
EG-020 through EG-024 were added from a completion-focused session in the same workspace that
emulated authoring a new source file from scratch inside a JPMS module.
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
| EG-012 | `textDocument/declaration` not implemented; overriding method declarations have no path to the contract/interface method | M1 |
| EG-013 | Find References candidate discovery excludes generated annotation sources; references in `@Builder`-generated classes are never found | M2 |
| EG-014 | Find References on an overriding method returns only exact-static-type call sites, not polymorphic uses of the overridden method | M2 |
| EG-015 | Override/implement completion missing; a method-name prefix in a class body offers only type candidates, no override stubs | M2 |
| EG-016 | Annotation-member completion missing; completion inside an annotation's parentheses returns nothing | M2 |
| EG-017 | `textDocument/documentHighlight` not implemented; cursor-occurrence highlighting is unavailable | M2 |
| EG-018 | `textDocument/selectionRange` not implemented; expand/shrink selection is unavailable | M2 |
| EG-019 | Unused-declaration diagnostic message is the bare word `Unused` with a null code | M1 |
| EG-020 | `new` expression completion is not slot-aware; it offers non-instantiable types and ignores the expected type | M2 |
| EG-021 | Type-name completion ranks reactor-local types below dependency and JDK types | M2 |
| EG-022 | Sealed-type `switch`/`case` pattern completion offers arbitrary types instead of the permitted subtypes | M2 |
| EG-023 | `this.` completion leaks low-value `Object` methods that value-receiver member-access suppresses | M2 |
| EG-024 | Type-name completion can offer types from modules the current module does not read | M2 |

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

**Milestone: M1**

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
| `DbClient.execute()` declaration | `DbClient.execute()` | `DbClient.execute()` |

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

### Implementation notes

**Call-site rows (rows 1–2) are more ambitious than declaration-site (row 3).**
The proposed fix focuses on overriding method *declarations*.
Rows 1 and 2 are *call sites*: getting declaration to navigate from `dbClient.execute()` (where
`dbClient` is `MongoDbClient`) to `DbClient.execute()` requires resolving the concrete callee at
the call site and then walking up to the interface — a separate code path.
V1 should cover declaration-site overrides (row 3) only; call sites can fall back to definition
until a follow-up extends the logic there.

**Decide between immediate override and root contract.**
Walking only *direct* supertypes may not reach the root interface.
For `MongoDbClient extends DbClientBase implements DbClient`, if `DbClientBase` also declares
`execute()`, a single-level walk lands on `DbClientBase.execute()`, not `DbClient.execute()`.
Whether to return the *nearest* overridden declaration or the *most abstract* one is a product
decision; the implementation must choose one consistently.
Returning the nearest overridden declaration is simpler; returning the root interface is arguably
more aligned with "jump to contract".

**Row 4 — interface method is its own declaration.**
`DbClient.execute()` IS the declaration, so `declaration` should return itself, not empty.
"Or empty" was removed from the table above.

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

## EG-013 — Find References candidate discovery excludes generated annotation sources

**Milestone: M2**

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

**Milestone: M2**

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

**Milestone: M2**

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

**Milestone: M2**

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

**Milestone: M2**

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

**Milestone: M2**

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

**Milestone: M1**

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

## EG-020 — `new` expression completion is not slot-aware

**Milestone: M2**

### Observed behaviour

Completion in a `new` expression neither filters to instantiable types nor uses the expected type
of the assignment.

```
inject "Object o = new Oper"
  → offers interfaces and a sealed interface that cannot be instantiated:
    Operator [Interface], OperationResponse [Interface] (sealed), OperatorAdapter [Interface], …

inject "java.util.List<String> ls = new "
  → 93 items led by List [Interface] and unrelated types: Short, Integer, Double,
    RuntimePermission, ProcessHandle, …
  → no ArrayList; nothing assignable to List<String> is prioritised
```

This is in sharp contrast to the other type-position slots, which filter correctly in the same
session:

- `extends` offers only non-final classes (interfaces and final classes such as `String`,
  `Integer`, `StringBuilder` are excluded);
- `implements` offers only interfaces;
- `throws` offers only `Throwable` subtypes;
- `catch` offers only exception types.

The slot-aware filtering machinery clearly exists; the `new` path does not use it, and it does not
consult the expected (target) type that is already known at the assignment site.

### Root cause

The `new`-expression branch of the completion engine resolves type candidates from the workspace
type index without applying an instantiability filter (concrete, non-abstract, non-sealed-from-here
classes) and without restricting or ranking by the assignment's target type.

### Proposed fix

In the `new`-expression completion branch:

1. Filter candidates to instantiable types — exclude interfaces (unless an anonymous-class body is
   being offered), abstract classes, and sealed types that cannot be subclassed from this location.
2. When the expression has a known target type (assignment, return, argument), restrict or rank
   candidates to subtypes assignable to that target, so `List<String> x = new ` surfaces
   `ArrayList`, `LinkedList`, etc. first.

### Probe commands

```bash
python3 dev/explore.py /path/to/Scratch.java inject "java.util.List<String> ls = new " expect ArrayList
```

(Probed with a temporary `ScratchLathe.java` authored inside `app-server`.)

### Regression targets

- `CompletionNewExprTest.completion_newWithListTarget_prioritisesInstantiableSubtypes`
- `CompletionNewExprTest.completion_newExpr_excludesInterfacesAndSealedTypes`

---

## EG-021 — Type-name completion ranks reactor-local types below dependency and JDK types

**Milestone: M2**

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

**Milestone: M2**

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

**Milestone: M2**

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

**Milestone: M2**

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

9. **EG-019** (unused-diagnostic message) — emit a descriptive message naming the declaration and
   its kind and set a stable diagnostic code.
   Small, bounded change to the unused-declaration scan.

10. **EG-012** (`textDocument/declaration`) — implement `textDocument/declaration` to allow
    navigating from an override to its contract method.

EG-011, EG-013 through EG-018, and EG-020 through EG-024 are M2 work, tracked alongside the
external-source Find References, navigation, completion, and editor-feature scope expansion.
EG-023 should be implemented together with EG-008 (shared Object-method suppression list), and
EG-021 together with EG-006 (shared reactor-origin ranking).
