# Lathe — Resolved Gaps (Archive)

Resolved (`done` / `non-goal`) gap entries, moved out of the active [gaps.md](gaps.md) per the
[gap lifecycle](gap-process.md). Kept for the historical record and regression-target references.

---

# Navigation, references, code actions (resolved)

## EG-012 — `textDocument/declaration`: navigate an override to its contract method

**Status: done — Target: M1.**
The design of record is [lathe-declaration.md](../done/lathe-declaration.md).
The implementation resolves to the **root contract** (walking the full supertype hierarchy, not just
direct supertypes) and handles **both** the declaration site and the call site, with a fallback to
`definition` for non-overriding symbols.
The "Proposed fix" and "Implementation notes" below are retained as the historical gap record; where
they pose open questions, those were resolved in favour of root-contract resolution and call-site
support per the design doc.

### Observed behaviour (historical)

Java IDEs commonly provide a quick way to navigate from an overriding method declaration to the
method it overrides in a superclass or interface.
Before EG-012 was implemented, Lathe did not expose that navigation.

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

## EG-014 — Find References on an overriding method returns only exact-static-type call sites

**Status: done — Target: M2.**
Resolved with FR-006 and FR-007.

Find References from an overriding method now resolves the method's overridden contract before
planning the workspace search.
That makes override-origin searches include call sites whose static receiver type is the interface
or superclass contract, including sibling downstream modules of the contract owner.

The implementation reuses javac attribution and override checks rather than text parsing:

1. Resolve the cursor target as a `ReferenceTarget`.
2. For method targets, ask the cursor module analysis for the root contract method.
3. Plan reference search from the contract owner's module when a contract is found.
4. Match method references using javac override-aware element comparison.

Regression coverage:

- `ReferenceLocatorTest.method_override_findsCallThroughInterfaceType`
- `ReferenceLocatorTest.method_interfaceDeclaration_findsOverridingDeclarationAndCallThroughImplementingType`
- `ReferenceLocatorTest.method_interfaceDeclaration_findsCallThroughImplementingPublicOverride`
- `LspSmokeTest.references_fromOverrideMethod_findsSupertypeTypedCallSite`

---

## EG-020 — `new` expression completion is not slot-aware

**Status: done — Target: M2.**

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

## FR-001 — References from external source have no workspace search root

Status: done — Target: M1.

When Find References is invoked from a cached JDK or dependency source file,
`WorkspaceSession.referencesFuture()` derives the search scope from `cursorConfig`, which is empty
for external paths not belonging to any reactor `ModuleSourceConfig`.
The `REACTOR_MODULES` branch previously called `.orElse(List.of())`, so no project file was ever
searched.

### Fix

`WorkspaceSession.referencesFuture()` — change `orElse(List.of())` to
`orElseGet(workspace::allConfigs)` for the `REACTOR_MODULES` scope branch.
When the cursor is in an external source file, all reactor module configs are searched;
`ReferenceCandidatePlanner` and javac identity matching filter out files that do not actually
reference the target.

The `DECLARING_MODULE` branch retains `orElse(List.of())` — there is no meaningful reactor
module scope to derive for a package-private external symbol.

### Regression test

`LspSmokeTest.references_fromCachedJdkSource_findsReactorUsages` — opens the JDK `String.java`
from the Lathe cache, requests references at the class declaration, and asserts that
`StringUtils.java` (which declares `String upper(String s)`) appears in the results.
The reactor-origin case is covered by
`LspSmokeTest.references_fromReactorSource_findsUsageAcrossModules`.

## FR-006 — Method references are override-aware

Status: done — Target: M2.
Discovered 2026-06-30, gap validation pass.

Find References previously compared method references by exact declaring owner plus erased
descriptor.
That meant a search from an interface or superclass method missed call sites whose static receiver
type was an implementation, and a search from an override missed call sites whose static receiver
type was the supertype.

The resolved implementation reconstructs the target method in each javac analysis and accepts
matches when either executable overrides the other according to `Elements.overrides`, while keeping
constructors and unrelated overloads exact.

Regression coverage:

- `ReferenceLocatorTest.method_interfaceDeclaration_findsCallThroughImplementingType`
- `ReferenceLocatorTest.method_override_findsCallThroughInterfaceType`

## FR-007 — Override-method references search inherited hook calls in supertype sources

Status: done — Target: M2.
Discovered 2026-06-30, Dropwizard `SessionFactoryProvider` validation.

Find References on a reactor override such as
`SessionFactoryProvider.createValueProvider(...)` previously missed inherited framework hook calls
in cached superclass sources, because candidate discovery required method candidates to contain both
the method simple name and the override owner's exact simple name.

The resolved implementation treats method candidate discovery as simple-name based and leaves exact
owner and override validation to javac attribution.
Fields, constructors, and enum constants keep the narrower owner filter.

Regression coverage:

- `ReferenceCandidatePlannerTest.planCandidates_overrideMethodTarget_returnsSuperclassSelfCallFile`

---

# Completion (CQ) — resolved

## CQ-0036 — Empty constructor completion returns a complete narrow list

ID: CQ-0036
Status: fixed
Tier: basic
Failure mode: missing-candidate-after-client-filtering
Owner component: CompletionEngine / TypeIndexCandidateProvider
Discovery: 2026-06-17, Helidon MongoDB client

Project/file:
`/home/ag-libs/git/helidon/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClient.java`

Probe command:
```bash
printf 'inject "final var x = new " min 1\nlog 120\n' \
  | LATHE_DEBUG=1 python3 dev/explore.py \
      /home/ag-libs/git/helidon/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClient.java
```

Control probe:
```bash
printf 'inject "final var x = new Connection" expect Connection min 1\nlog 80\n' \
  | LATHE_DEBUG=1 python3 dev/explore.py \
      /home/ag-libs/git/helidon/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClient.java
```

Expected-type probe:
```bash
printf 'inject "ConnectionString x = new " min 1\nlog 100\n' \
  | LATHE_DEBUG=1 python3 dev/explore.py \
      /home/ag-libs/git/helidon/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClient.java
```

Cursor context:
```java
final var x = new §
final var x = new Connection§
ConnectionString x = new §
```

Observed behavior before the fix:
The direct prefixed request works.
`new Connection§` is parsed as `sentinelCtx=CONSTRUCTOR_CALL`,
queries the type index with prefix `Connection`,
and returns 28 type candidates including:

- `Connection` from `java.sql`
- `ConnectionString` from `com.mongodb`
- `ConnectionId` from `com.mongodb.connection`
- `Connection` from `com.mongodb.internal.connection`

The empty-prefix request was the gap.
`new §` is also parsed as `sentinelCtx=CONSTRUCTOR_CALL`,
but returns only 88 broad java.lang/current-scope candidates and marks the completion list
as complete:

```text
[completion] inject prefix=|| receiver=|null| ctx=STATEMENT hasDot=false
[completion] parsed valid=true sentinelCtx=CONSTRUCTOR_CALL receiver=|null| class=MongoDbClient method=<init> role=CONSTRUCTOR
[completion] ... items=88 reattributed=false
[completion:lsp] ... items=88 incomplete=false
```

The expected-type variant failed the same way.
`ConnectionString x = new §` returns the same 88 broad items,
does not include or rank `ConnectionString` first,
and also marks the result complete:

```text
[completion] inject prefix=|| receiver=|null| ctx=STATEMENT hasDot=false
[completion] parsed valid=true sentinelCtx=CONSTRUCTOR_CALL receiver=|null| class=MongoDbClient method=<init> role=CONSTRUCTOR
[completion] ... items=88 reattributed=false
[completion:lsp] ... items=88 incomplete=false
```

Why this matters:
In an editor,
the user naturally triggers completion after typing `new ` and then continues with `Connection`.
Because the empty-prefix response is marked complete,
the client can filter the cached 88 items instead of asking Lathe again.
`Connection` is not in that initial list,
so the menu can appear to miss `final var x = new Connection§`
even though an explicit fresh request at that final cursor position succeeds.

Expected Lathe behavior:
Constructor-call completion at `new §` must either:

- return an incomplete list so clients retrigger as the prefix grows; or
- include a sufficiently broad type-index-backed candidate set for constructor-call filtering.

When the enclosing expression has an expected type,
Lathe should use it before falling back to broad constructor type discovery.
For `ConnectionString x = new §`,
`ConnectionString` should be the first completion candidate and should carry the same import/replace
behavior as the prefixed type item.

The first slice should prefer `isIncomplete=true` for empty-prefix constructor-call type completion
when there is no useful expected type.
That keeps the response small while making client-side incremental filtering safe.

Accepted edit, if relevant:
The prefixed `Connection` item has a correct replacement range and import edit:

```json
{
  "label": "Connection",
  "detail": "java.sql.Connection",
  "textEdit": {
    "range": {
      "start": { "line": 43, "character": 24 },
      "end": { "line": 43, "character": 34 }
    },
    "newText": "Connection"
  },
  "additionalTextEdits": [
    { "newText": "import java.sql.Connection;\n" }
  ]
}
```

Regression target:
`CompletionTypeIndexTest.constructorCall_emptyPrefix_returnsIncomplete`
`CompletionTypeIndexTest.constructorCall_prefixAfterEmptyPrefix_canDiscoverIndexedTypes`
`CompletionTypeIndexTest.constructorCall_emptyPrefixExpectedType_ranksExpectedTypeFirst`

Notes:
This was not a candidate-generation failure for non-empty prefixes.
The bug was the empty-prefix constructor-call response contract with LSP clients.
Fixed by marking broad empty-prefix constructor completions incomplete and by using the semantic
expected type for constructor-call type completion before falling back to broad discovery.

## CQ-0039 — RejectedExecutionException logged as SEVERE at shutdown

ID: CQ-0039
Status: fixed
Tier: lifecycle
Discovery: 2026-06-17

### Description

When Neovim exits, a `RejectedExecutionException` is logged at SEVERE severity in the client's
LSP log, polluting the user's error output even though the server exits cleanly afterward.

### Observed log

```
[shutdown] shutdown requested
SEVERE  Internal error: Task ... rejected from
  java.util.concurrent.ScheduledThreadPoolExecutor@...[Terminated, pool size = 0,
  active threads = 0, queued tasks = 0, completed tasks = 39]
java.util.concurrent.RejectedExecutionException: Task ... rejected from ...
  at ServerEventLoop.submit(ServerEventLoop.java:37)
  at LatheTextDocumentService.close(LatheTextDocumentService.java:45)
  at LatheLanguageServer.shutdown(LatheLanguageServer.java:80)
```

### Root cause

`LatheTextDocumentService.close()` calls `worker.submit(session::close)` followed by
`worker.close()`.
The `[Terminated]` state on the executor (`completed tasks = 39`) means `executor.shutdownNow()`
was already called before `close()` reached `worker.submit()`.
The only call site for `worker.close()` is inside `LatheTextDocumentService.close()` itself,
so `close()` is most likely being called twice:
the first call completes normally (submits session.close(), joins, then shuts down the executor),
and the second call finds the executor already terminated and throws at `worker.submit()`.

The most probable trigger: the LSP4J `ConcurrentMessageProcessor` dispatches `shutdown`
(a request) and `exit` (a notification) concurrently on its thread pool.
Neovim sends `exit` without waiting for the `shutdown` response,
so both handlers can race.
If `exit()` fires `System.exit(0)` before `shutdown()` completes,
the JVM halt can interrupt the `LatheLanguageServer.shutdown()` path in a way that causes
a second dispatch or re-entry.
An alternative scenario is that the LSP4J framework calls `shutdown()` more than once
under certain client disconnect conditions.

### Impact

A spurious SEVERE log appears in Neovim's LSP log on every normal exit.
Users see it as a server crash rather than a clean shutdown.
The server exits correctly through `exit()` → `System.exit(0)`, so there is no functional data loss.

### Fix area

Two complementary hardening points:

1. **`LatheTextDocumentService.close()` — idempotent guard.**
   Add an `AtomicBoolean closed` field; return immediately on the second call.

2. **`ServerEventLoop.submit()` / `execute()` — graceful rejection.**
   Catch `RejectedExecutionException` when the executor is already shut down and return a
   cancelled future (for `submit`) or silently no-op (for `execute`) instead of propagating.
   This prevents the exception from surfacing to LSP4J as an unhandled SEVERE error
   even if the idempotent guard is the primary fix.

### Regression target

Lifecycle test: `LatheTextDocumentServiceTest.close_calledTwice_doesNotThrow`
Lifecycle test: `LatheTextDocumentServiceTest.close_afterWorkerShutdown_doesNotThrow`

## CQ-0038 — Private methods falsely marked unused when declared after their callers

ID: CQ-0038
Status: fixed
Tier: Basic
Discovery: 2026-06-17

### Description

Private methods that are actually called are marked with an "Unused" hint whenever their
declaration appears later in the file than the method that calls them.
In the conventional Java style (public API first, private helpers below), this affects
every private helper method — they all show as unused even when called.

### Root cause

`UnusedDeclarationScanner` uses a single tree-walk. `visitMethod` registers private methods
into `privateMethods` and `visitIdentifier` / `visitMemberSelect` register callers via
`markReference`, which does a `privateMethods.containsKey(element)` check.
Because the tree is walked top-to-bottom in source order, a call inside a public method that
appears before the private method's declaration reaches `markReference` before the callee is
in `privateMethods`. The check returns false and the reference is permanently dropped.
The same bug affects `this.helper()` calls (via `visitMemberSelect`) and private fields.

### Fix

Two-pass scan:
- Pass 1 (`declarationPhase = true`): `visitMethod` and `visitVariable` populate the maps;
  reference visitors do nothing.
- Pass 2 (`declarationPhase = false`): reference visitors fire; declaration visitors skip.

All declarations are known before any reference is checked, making the scan order-independent.

### Regression targets

`UnusedDeclarationScannerTest.compile_privateMethodDeclaredAfterCaller_noHint`
`UnusedDeclarationScannerTest.compile_privateMethodCalledViaThis_noHint`

## CQ-0037 — Primitive types not suggested in variable type position

ID: CQ-0037
Status: fixed
Tier: Basic
Discovery: 2026-06-17

### Description

Primitive types (`boolean`, `byte`, `char`, `double`, `float`, `int`, `long`, `short`) never
appear in completion candidates when the cursor is in a type-name position:
local variable type, method parameter type, method return type, field type, or cast expression.

### Failure mode

`KeywordProvider` has no PRIMITIVES list.
The two contexts that cover type-name positions — `TYPE_REFERENCE` and `VARIABLE_DECLARATION` —
return keywords through `classBodyKeywordsIfApplicable`, which returns `List.of()` inside any
method body and `classBodyKeywords()` at class scope.
Neither path includes primitives at any scope.

### Probe

```java
class Foo {
  void test() {
    i§           // VARIABLE_DECLARATION — expects int, Integer, ...
  }
  b§ bar() {}   // TYPE_REFERENCE (return type) — expects boolean, byte, Boolean, ...
}
```

### Expected Lathe behavior

`int`, `long`, `boolean`, `byte`, `char`, `short`, `float`, `double` appear whenever the cursor
is in a type-name slot (variable type, parameter type, return type, field type, cast).
`void` is already in `TYPE_DECLARATIONS` (class-body level only) and needs no change.

### Fix

Add to `KeywordProvider`:

```java
private static final List<String> PRIMITIVES =
    List.of("boolean", "byte", "char", "double", "float", "int", "long", "short");
```

Include PRIMITIVES unconditionally in:
- `classBodyKeywords()` — covers field types and method return types
- `selectKeywords` `TYPE_REFERENCE` branch when inside a method — currently returns `List.of()`
  via `classBodyKeywordsIfApplicable`; add `PRIMITIVES` alongside
- `selectKeywords` `VARIABLE_DECLARATION` branch (not-name-slot path) — same as above

The change is entirely inside `KeywordProvider`; no other completion component is affected.

### Regression target

`KeywordProviderTest.typeReference_inMethodBody_includesPrimitives`
`KeywordProviderTest.variableDeclaration_typeSlot_includesPrimitives`
`KeywordProviderTest.classBody_returnType_includesPrimitives`

## CQ-0036 — Goto definition fails for annotation-processor generated sources

ID: CQ-0036
Status: fixed
Tier: Basic
Discovery: 2026-06-16, sample-workspace (SampleConfigBuilder, SampleBuilder)

### Description

`textDocument/definition` returns no result when the cursor is on a class generated by an
annotation processor (e.g. `@Builder`-generated `*Builder` classes from `record-companion`).
The generated `.java` files exist on disk under `target/generated-sources/annotations/` but are
not reachable because they are absent from `allSourceRoots()`.

### Reproduction

File: `SampleLandingPageServiceTest.java`
Generated sources:
- `app-config/target/generated-sources/annotations/.../SampleConfigBuilder.java`
- `app-core/target/generated-sources/annotations/.../SampleBuilder.java`

```
def 82:24   # SampleConfigBuilder.builder()  → (no definition found)
def 161:11  # SampleBuilder.builder() → (no definition found)
```

Regular (non-generated) classes at the same positions resolve correctly, e.g.:
```
def 16:7  # SampleCode → .../app-core/src/main/java/.../SampleCode.java:9:13  ✓
def 19:7  # SampleFlow  → .../app-core/src/main/java/.../SampleFlow.java:9:13   ✓
```

### Root cause

`WorkspaceModuleRegistry.allSourceRoots()` only includes `ModuleSourceConfig.sourceRoots()`
(the project's hand-written `src/main/java`, `src/test/java` roots).
`ModuleSourceConfig.generatedSourcesDir()` — the Lathe-internal `SOURCE_OUTPUT` directory used
by javac during incremental compilation — is not added to `allSourceRoots()`.
More importantly, the original Maven `target/generated-sources/annotations/` directories are
never registered in the workspace manifest at all (`workspace.json` has no `sourceRoots` entry
for generated sources from other modules in the reactor).

`DefinitionLocator.findSourceFile` searches `sourceRoots` then `manifest.externalSourceRoot()`
(dependency JARs). Neither path covers intra-reactor generated sources.

### Fix area

### Fix

`ParamsWriter` already writes `generatedSourcesDir` (Maven's `getGeneratedSourcesDirectory()`)
into the per-module params file, and `ModuleSourceConfig` loads it as `originalGenSourcesDir`.
The only missing piece was that `WorkspaceModuleRegistry.allSourceRoots()` did not include it.

Fix: extend `allSourceRoots()` to append `originalGenSourcesDir` when non-null.

### Impact

Any class produced by an annotation processor (`@Builder`, Lombok, MapStruct, Immutables, etc.)
is unreachable via goto-definition. This is a complete failure for generated APIs that
developers commonly navigate to.

Regression target:
`WorkspaceModuleRegistryTest.allSourceRoots_includesOriginalGenSourcesDir_whenPresent`

---

## CQ-0034 — Lambda-scope locals leak into simple-name candidates after their enclosing block closes

ID: CQ-0034
Status: fixed
Tier: Basic
Discovery: 2025-07-25, AppServer.java constructor (sample-workspace)

### Description

Lambda parameters and variables declared inside a lambda body remain visible as simple-name candidates at cursor positions outside (after) their enclosing lambda block.

### Reproduction

Target: `AppServer.java`, constructor.
The lambda block spans lines 192–197:
```java
config.itemsToProcess().forEach(schema -> {
    final var runner = new Runner(schema, jdbi);
    runner.validate();
});
```
And a stream lambda on lines 205–207:
```java
.collect(Collectors.toMap(Entry::getKey, e -> new BaseJedisService(e.getValue())));
```

Inject `schema` at line 211 (after the lambda block closes):
→ 1 item returned: `schema [Variable]` — expected 0

Inject `runner` at line 211:
→ 1 item returned: `runner [Variable]` — expected 0

Inject `e` inside `new CallbackService(` at line 211 (argument position):
→ `e [Variable]` appears among 52 candidates — expected absent

### Root cause

`SimpleNameProvider.addMethodLocals` (or its scope visitor) collects all local variable and lambda-parameter declarations up to the cursor line number, without verifying that the cursor falls inside the declaring scope's block range.
Javac's own in-scope check also returns these as in-scope (javac=1), suggesting the injected parse tree may present them as reachable under error-recovery conditions.

### Impact

A developer typing `s` or `m` just after a `forEach` block will see closed-over lambda variables as top suggestions.
Severity is medium: wrong candidates appear but correct ones are usually present too.

### Fix area

`SimpleNameProvider` / the local-declaration scanner: record the enclosing block end line for each local and skip any local whose block does not contain the cursor position.

### Fix

`SimpleNameProvider.addVariableIfVisible` walks the ancestor path from each local's `TreePath`
to the enclosing method, checking whether any `LambdaExpressionTree` ancestor ends before
the cursor. If so, the local is suppressed. `LambdaExpressionTree` is checked specifically
(not all block types) to avoid incorrectly filtering `instanceof` binding pattern variables,
whose `InstanceOfTree` parent ends before the if-body where they remain in scope.
Fix: commit `9f9e6b6`.

Regression target:
`CompletionSimpleNameTest.simpleName_lambdaParam_notVisibleAfterLambdaCloses`
`CompletionSimpleNameTest.simpleName_lambdaBodyLocal_notVisibleAfterLambdaCloses`
`CompletionSimpleNameTest.simpleName_methodLocal_visibleAfterNestedLambdaCloses`

---

## CQ-0032 — Member-access returns 0 items when stale snapshot holds an error type for the receiver

ID: CQ-0032
Status: fixed
Tier: basic
Failure mode: missing-candidate
Owner component: CompletionEngine / TypeResolver
Fix: commit 8355554 — extend reattribution guard to also trigger on TypeKind.ERROR

Project/file:
`/workspace/app-server/src/main/java/com/example/app/server/AppServer.java`

Probe command:
```bash
printf 'diagnostics\ninject "resourceConfig." at 158\nlog 20\n' \
  | python3 dev/explore.py \
    /workspace/app-server/src/main/java/com/example/app/server/AppServer.java
```

Cursor context:
```java
resourceConfig.packages("com.example.app.server.access.rs");
resourceConfig.§          ← new blank line typed after pressing Enter
```

IntelliJ or JDT behavior:
Both IDEs correctly offer all accessible members of `ResourceConfig` at the new line.

Lathe behavior:
Confirmed from `LATHE_DEBUG=1` editor log:

```
[completion] resolve receiver=|resourceConfig| type=re static=false reattributed=false
[completion] proposals count=0 labels=[]
[completion] items=0 reattributed=false
```

The server resolves the receiver to the bogus type `re` and returns 0 proposals.
The issue is a race between the `didChange` debounce compile and the completion request.
When the background compile fires while the user has only typed `re` (the start of `resourceConfig`),
the stale snapshot attributes `re` at that position as an unresolved error identifier with `TypeKind.ERROR`.
When the user finishes typing `resourceConfig.` and triggers completion,
`initialResolved` is non-null (the error type `re`),
so the `initialResolved == null` guard in `completeMemberAccess` does not trigger reattribution.
The engine trusts the error type, finds no members, and returns 0 proposals.

Expected Lathe behavior:
Member-access completion should return the 54 accessible members of `ResourceConfig`.
When the stale snapshot resolves the receiver to a `TypeKind.ERROR` type,
reattribution must be triggered just as it would be for a null resolution.

Accepted edit, if relevant:
Not probed — the gap is in candidate discovery, not insertion.

Regression target:
`CompletionMemberAccessTest.memberAccess_receiverResolvedToErrorType_triggersReattribution`

Notes:
The gap is intermittent because it depends on the debounce compile racing the user's typing speed.
If the debounce fires after `resourceConfig.` is complete, the snapshot has the correct type and
completion works normally (`reattributed=false`, ~80ms).
If the debounce fires mid-word, the snapshot captures an error type and the completion fails.
The fix point is `completeMemberAccess` in `CompletionEngine`:
extend the reattribution condition from `initialResolved == null` to also cover
`initialResolved.type().getKind() == TypeKind.ERROR`.
`resolveByPosition` already filters out error types (it only accepts `TypeKind.DECLARED`),
so the error type is returned by the text-based fallback path in `resolveReceiver`,
which resolves a field whose attributed type in the error-recovery snapshot is garbage.

---

## CQ-0033 — Uppercase prefix in statement position floods results with unrelated type-index classes

ID: CQ-0033
Status: fixed
Tier: basic
Failure mode: wrong-candidate-set
Owner component: CompletionEngine / TypeIndexValidator

Project/file:
`/workspace/app-server/src/main/java/com/example/app/server/AppServer.java`

Probe command:
```bash
printf 'diagnostics\ninject "R" at 163\nlog 15\n' \
  | python3 dev/explore.py \
    /workspace/app-server/src/main/java/com/example/app/server/AppServer.java
```

Cursor context:
```java
// TODO: don't use Jersey resource to proxy
R§               ← statement position inside constructor body
```

IntelliJ or JDT behavior:
IntelliJ basic completion in a statement position with an uppercase prefix offers visible locals,
fields, methods, and matching importable types.
Unrelated type-index candidates that are not accessible from the current scope are suppressed
or at least ranked below visible value candidates.

Lathe behavior:
```
sentinelCtx=SIMPLE_NAME  javac=0  keywords=0  items=54  incomplete=true
```

With prefix `R` and no local values matching `R`, the type-index supplies 54+ unrelated classes:
`Record`, `Readable`, `ReflectiveOperationException`, `Ref`, `Reader`, `Random`, `Recording`, …

The log shows `javac=0` — no visible scope candidates match.
`incomplete=true` signals the type-index result set was truncated at the limit.
The result is entirely type-index noise with no value candidates visible.

Expected Lathe behavior:
Uppercase prefixes in method bodies should include importable types alongside visible values,
but visible scope candidates (locals, fields, methods) must rank before type-index candidates.
The 54-item alphabetical type-index dump with no scope candidates visible is the failure:
ranking is missing, not the presence of types.

Accepted edit, if relevant:
Not probed.

Regression target:
`CompletionSimpleNameTest.simpleName_uppercasePrefix_scopeCandidateRanksBeforeTypeIndex`

Notes:
**Corrected diagnosis (2026-06-16)**: JDT and NetBeans both always return importable type
candidates in statement position regardless of whether local/field scope candidates match —
they run scope completion and type completion as parallel independent streams.
Gating type-index on `javac > 0` would diverge from this established behavior.
Verified that scope candidates already rank before type-index candidates when both are present:
`CompletionSimpleNameTest.simpleName_uppercasePrefix_scopeCandidateRanksBeforeTypeIndex` passes
against current code, confirming the ranking is correct.
The original `javac=0` flood case (54 type-index results with no scope match) is expected
behavior per JDT/NetBeans and does not require a fix.

---

## CQ-0022 — String switch `case §` offers type-index classes

ID: CQ-0022
Status: fixed
Tier: basic
Failure mode: wrong-candidate-set
Owner component: CompletionEngine / TypeResolver

Project/file:
`/home/ag-libs/git/helidon/dbclient/metrics/src/main/java/io/helidon/dbclient/metrics/DbClientMetricsProvider.java`

Probe command:
```bash
printf 'diagnostics\ninject "return switch (type) { case " at 55\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/dbclient/metrics/src/main/java/io/helidon/dbclient/metrics/DbClientMetricsProvider.java
```

Cursor context:
```java
String type = config.get("type").asString().orElse("COUNTER");
return switch (type) { case §
```

IntelliJ or JDT behavior:
Expected IDE behavior is not to offer arbitrary type names in a `String` switch label.
Useful candidates would be visible constant `String` values or no basic candidates.

Lathe behavior:
Fixed.
Lathe now treats `String` switch case labels as value-label sites and suppresses broad
type-index/type-reference candidates.
Enum case labels still use enum constants,
and non-`String` declared selectors still route to type-pattern completion.

Before the fix,
Lathe returned 112 type-index candidates such as `Thread`,
`String`,
`Process`,
`Object`,
and `Math`.
The log shows `sentinelCtx=CASE_LABEL`.

Expected Lathe behavior:
For a `CASE_LABEL` site whose selector type is `String`,
Lathe should suppress unrelated type-index classes.
Type names are not syntactically useful case constants in this context.

Accepted edit, if relevant:
Not probed.
Accepting `Thread` would produce an invalid or irrelevant `case Thread` label.

Regression target:
`CompletionSimpleNameTest.simpleName_switchCaseLabel_stringSubject_suppressesTypeIndexClasses`

Notes:
Fixed by adding an explicit `java.lang.String` case-label guard in `CompletionEngine`
before the type-pattern fallback.

This is separate from enum switch labels (`CQ-0006`) and type-pattern switch labels (`CQ-0021`).
The case-label path should branch by selector type:
enum selectors use enum constants,
non-enum reference selectors may use type-pattern candidates where pattern matching applies,
and `String` selectors should not fall through to broad type-index completion.

## CQ-0023 — Member-access in-token method completion still duplicates existing calls

ID: CQ-0023
Status: fixed
Tier: presentation
Failure mode: bad-replacement-range
Owner component: CompletionSite / CompletionItemPresenter

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-lifecycle/src/main/java/io/dropwizard/lifecycle/setup/ExecutorServiceBuilder.java`

Probe command:
```bash
printf 'diagnostics\ncomplete after "Duration.s"\naccept after "Duration.s" label seconds\nlog 50\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-lifecycle/src/main/java/io/dropwizard/lifecycle/setup/ExecutorServiceBuilder.java
```

Related probes:
```bash
printf 'diagnostics\naccept inject "DbClientService svc = DbClientMetrics.c§ounter()" at 55 label counter\nlog 60\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/dbclient/metrics/src/main/java/io/helidon/dbclient/metrics/DbClientMetricsProvider.java

printf 'diagnostics\naccept inject "Entity.j§son(\"{}\")" at 91 label json\nlog 60\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-testing/src/test/java/io/dropwizard/testing/junit5/PersonResourceTest.java
```

Cursor context:
```java
this.keepAliveTime = Duration.s§econds(60);
DbClientService svc = DbClientMetrics.c§ounter()
Entity.j§son("{}")
```

IntelliJ or JDT behavior:
Expected IDE behavior is insert/replace-safe in-token completion.
Accepting `seconds` should not duplicate the already-present suffix.

Lathe behavior:
The menu correctly returns `seconds`.
The selected completion item has:
```text
insertText: seconds($1)
textEdit.range: 43:38-43:45
```

The accepted source becomes:
```java
this.keepAliveTime = Duration.seconds(§)(60);
DbClientService svc = DbClientMetrics.counter()§()
Entity.json(§)("{}")
```

Expected Lathe behavior:
Completion at `Duration.s§econds(60)` should replace the whole existing method call target,
or otherwise use insert/replace semantics so the accepted text remains:
```java
this.keepAliveTime = Duration.seconds(§60);
DbClientService svc = DbClientMetrics.counter()§
Entity.json(§"{}")
```

Accepted edit, if relevant:
The accepted edit must not produce `Duration.seconds()(60)`,
`counter()()`,
or `json()("{}")`.

Regression target:
`CompletionPresentationTest.memberAccess_inTokenExistingCall_replacesSuffixWithoutDuplicatingCall`

Notes:
`CQ-0003` fixed a similar in-token suffix problem for another member-access probe,
but these real Helidon/Dropwizard probes show the method-call form still duplicates existing calls.

Fixed by detecting an existing `(` immediately after the completed identifier and replacing method
completion insert text with the bare method name so the existing call parentheses and arguments
remain intact.

## CQ-0025 — Nested type completion after unimported outer type lacks an import edit

ID: CQ-0025
Status: fixed
Tier: presentation
Failure mode: bad-import-edit
Owner component: CompletionEngine / CompletionItemPresenter

Project/file:
`/home/ag-libs/git/helidon/dbclient/metrics/src/main/java/io/helidon/dbclient/metrics/DbClientMetricsProvider.java`

Probe command:
```bash
printf 'diagnostics\naccept inject "Map.En" at 55 label Entry\nlog 40\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/dbclient/metrics/src/main/java/io/helidon/dbclient/metrics/DbClientMetricsProvider.java
```

Cursor context:
```java
Map.En§try
```

IntelliJ or JDT behavior:
Expected IDE behavior is for accepting `Entry` through an unimported outer type to leave compilable source,
either by importing `java.util.Map` or by preserving a fully qualified receiver.

Lathe behavior:
The selected item is:
```text
label: Entry
detail: java.util.Map.Entry
insertText: Entry
additionalTextEdits: none
```

The accepted source remains:
```java
Map.Entry§
```

`DbClientMetricsProvider.java` imports `java.util.Collection`,
`java.util.LinkedList`,
and `java.util.List`,
but does not import `java.util.Map`,
so the accepted source leaves the outer type unresolved.

Expected Lathe behavior:
When a nested type candidate depends on an unimported outer type,
the completion item should add a deterministic import edit for the outer type,
for example:
```java
import java.util.Map;
```

Accepted edit, if relevant:
Accepting `Entry` after `Map.En` should produce source equivalent to:
```java
import java.util.Map;

Map.Entry§
```

Regression target:
`CompletionPresentationTest.completionItem_nestedType_addsOuterTypeImportEdit`

Notes:
The fully qualified control probe `java.util.Map.En§` accepts to `java.util.Map.Entry§`,
which is self-contained and does not need an import.
Fixed by adding a completion post-process that detects nested type items owned by a simple receiver
and adds an import edit for the outer type.

## CQ-0024 — Enum type completion ranks nested type before enum constants

ID: CQ-0024
Status: fixed
Tier: basic
Failure mode: poor-ranking
Owner component: CandidateGenerator / CompletionCandidateRanker

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-testing/src/test/java/io/dropwizard/testing/junit5/PersonResourceTest.java`

Probe command:
```bash
printf 'diagnostics\ninject "Response.Status." at 72\ninject "Response.Status.B" at 72\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-testing/src/test/java/io/dropwizard/testing/junit5/PersonResourceTest.java
```

Cursor context:
```java
Response.Status.§
```

IntelliJ or JDT behavior:
Expected IDE behavior is to rank enum constants first after an enum type receiver.

Lathe behavior:
`Response.Status.` returns `Family` first,
then enum constants such as `ACCEPTED`,
`BAD_GATEWAY`,
and `BAD_REQUEST`.
With prefix `B`,
only the enum constants remain and sorting is useful.

Expected Lathe behavior:
After an enum type receiver with no prefix,
enum constants should rank before nested types,
static methods,
and the `class` literal.
`Family` is syntactically valid,
but it is a less likely completion than `OK`,
`BAD_REQUEST`,
or another status constant.

Accepted edit, if relevant:
Not probed.

Regression target:
`CompletionMemberAccessTest.memberAccess_enumTypeReceiver_ranksConstantsBeforeNestedTypes`

Notes:
This is a ranking-only issue.
The candidate set is valid,
and prefix filtering behaves correctly for `Response.Status.B`.
Fixed by assigning nested type candidates an explicit later sort key,
so enum constants and regular members no longer sort behind null sort text.

## CQ-0026 — Class-body completion after modifiers offers invalid modifier keywords

ID: CQ-0026
Status: fixed
Tier: basic
Failure mode: wrong-candidate-set
Owner component: KeywordCompletion / SentinelParser

Project/file:
`/home/ag-libs/git/helidon/health/health/src/main/java/io/helidon/health/HealthCheck.java`

Probe command:
```bash
printf 'diagnostics\ninject "public " at 30\ninject "private final " at 30\nlog 80\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/health/health/src/main/java/io/helidon/health/HealthCheck.java
```

Cursor context:
```java
public §
private final §
```

IntelliJ or JDT behavior:
Expected IDE behavior is member-declaration completion appropriate to the modifiers already typed.
After `private final `,
useful candidates are field types,
`class`,
`interface`,
`enum`,
or `record` where legal.
Repeating access modifiers should not be offered.

Regression target:
`CompletionKeywordAndNoSlotTest.classBody_afterPrivateFinal_suppressesInvalidModifiers`

Notes:
Fixed by filtering class-body keyword candidates against modifiers already present on the same line.
Type and nested declaration candidates remain available.

Lathe behavior:
Both probes return 125 items.
The first items are modifier keywords:
```text
public
private
protected
static
final
abstract
synchronized
transient
volatile
class
interface
enum
record
void
```

For `private final §`,
accepting `public`,
`private`,
`protected`,
`final`,
`abstract`,
or several other modifier candidates would produce an invalid declaration.
The log shows `sentinelCtx=TYPE_REFERENCE`.

Expected Lathe behavior:
Class-body completion should account for already-typed declaration modifiers.
It should suppress duplicate or mutually exclusive modifiers and prefer legal declaration continuations.
For `private final §`,
type candidates such as `String`,
`Map`,
or project types should remain available for field declarations.

Accepted edit, if relevant:
Not probed.
The issue is candidate validity before acceptance.

Regression target:
`CompletionKeywordAndNoSlotTest.classBody_afterPrivateFinal_suppressesInvalidModifiers`

Notes:
This was found while simulating a developer adding a new class member by typing modifiers first.
The empty class-body case is broader and can still offer member declaration starters;
the gap is the modifier-sensitive filtering after the user has already committed part of the declaration.

## CQ-0027 — `Collectors.` inside return-stream `collect` is not ranked by result type

ID: CQ-0027
Status: fixed
Tier: typed
Failure mode: poor-ranking
Owner component: TypeResolver / CompletionCandidateRanker

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-migrations/src/main/java/io/dropwizard/migrations/DbMigrateCommand.java`

Probe command:
```bash
printf 'diagnostics\ninject "return contexts.stream().map(Object::toString).collect(Collectors." at 76\nlog 80\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-migrations/src/main/java/io/dropwizard/migrations/DbMigrateCommand.java
```

Related probes:
```bash
printf 'diagnostics\ninject "return headers.entrySet().stream().collect(Collectors." at 91\nlog 80\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-json-logging/src/main/java/io/dropwizard/logging/json/layout/AccessJsonLayout.java

printf 'diagnostics\ninject "return names.stream().map(healthStateAggregator::healthStateView).flatMap(Optional::stream).collect(Collectors." at 90\nlog 80\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-health/src/main/java/io/dropwizard/health/response/JsonHealthResponseProvider.java
```

Cursor context:
```java
private String getContext(...) {
    return contexts.stream().map(Object::toString).collect(Collectors.§
}

private Map<String, String> filterHeaders(...) {
    return headers.entrySet().stream().collect(Collectors.§
}

private List<HealthStateView> getViews(...) {
    return names.stream()
        .map(healthStateAggregator::healthStateView)
        .flatMap(Optional::stream)
        .collect(Collectors.§
}
```

IntelliJ or JDT behavior:
Expected IDE behavior is to use the enclosing return type and the `Stream.collect` target type
to rank compatible collector factories first.
For these probes,
`joining`,
`toMap`,
and `toUnmodifiableList` or `toList` should be near the top respectively.

Lathe behavior:
Fixed.
Lathe now recognizes the configured static-member result rule for
`Collectors.§` inside `Stream.collect(...)`,
propagates the expected result type from the enclosing expression,
and ranks compatible collector factories before unrelated collectors.

Before the fix,
all three probes returned 45 `Collectors` members in alphabetical order:
```text
averagingDouble
averagingInt
averagingLong
collectingAndThen
counting
filtering
flatMapping
groupingBy
...
joining
...
toList
toMap
toSet
toUnmodifiableList
...
```

The log shows `sentinelCtx=MEMBER_ACCESS` and resolves `Collectors` correctly,
but the candidate ranking does not reflect the expected result type of the surrounding return expression.

Expected Lathe behavior:
When `Collectors.§` is the argument to `Stream.collect(...)`,
Lathe should propagate the expected result type from the enclosing expression.
Collectors whose result type matches that expected type should rank before unrelated collectors.
Examples:

- `String` return: rank `joining(...)` before numeric summarizing and grouping collectors.
- `Map<String, String>` return: rank `toMap(...)` before `toList`, `joining`, and numeric collectors.
- `List<HealthStateView>` return: rank `toUnmodifiableList()` or `toList()` before `toMap` and grouping collectors.

Accepted edit, if relevant:
Parameterized collector methods correctly place the cursor inside their argument list,
for example accepting `toMap` produces `Collectors.toMap(§)`.
The gap is ranking,
not insertion.

Regression target:
`CompletionMemberAccessTest.memberAccess_collectorsReceiverInsideReturnCollect_rankedByReturnType`

Notes:
Fixed by commit `6896d2c`.
The implementation deliberately keeps Java-library-specific method and type names in
`CompletionLibraryRules`,
alongside the existing lambda projection rules for `Optional`,
`Stream`,
and `CompletionStage`.
`TypeResolver` resolves a generic static-member result context from the rule table,
and `CompletionCandidateRanker` ranks candidates through that context without hardcoding
`Collectors` or `Collector`.

This remains related to `CQ-0020`,
but it was found through return statements rather than assignment initializers.
The same exploration also reconfirmed deferred `CQ-0002`:
method-reference completion such as `Map.Entry::getV§` and `Object::to§` still returns no candidates.

## CQ-0028 — Fresh local declaration and assignment sites borrow enclosing return type

ID: CQ-0028
Status: fixed
Tier: typed
Failure mode: poor-ranking
Owner component: TypeResolver

Project/file:
`/home/ag-libs/git/helidon/dbclient/metrics/src/main/java/io/helidon/dbclient/metrics/DbClientMetricsProvider.java`

Probe command:
```bash
printf 'diagnostics\ninject "var value = " at 55\ninject "String value = \"\"; value = " at 55\nlog 80\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/dbclient/metrics/src/main/java/io/helidon/dbclient/metrics/DbClientMetricsProvider.java
```

Control probes:
```bash
printf 'diagnostics\ninject "var value = " at 76\ninject "String value = \"\"; value = " at 76\nlog 80\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-migrations/src/main/java/io/dropwizard/migrations/DbMigrateCommand.java

printf 'diagnostics\ninject "var value = " at 91\ninject "var value = java.util.Collections.emptyMap(); value = " at 91\nlog 80\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-json-logging/src/main/java/io/dropwizard/logging/json/layout/AccessJsonLayout.java
```

Cursor context:
```java
// inside DbClientMetricsProvider.fromConfig(...), whose return type is DbClientService
var value = §
String value = ""; value = §

// inside DbMigrateCommand.getContext(...), whose return type is String
var value = §

// inside AccessJsonLayout.filterHeaders(...), whose return type is Map<String, String>
var value = §
```

IntelliJ or JDT behavior:
Expected IDE behavior is that a `var` initializer has no declared expected type;
the initializer determines the local variable type.
For assignment to an already declared local,
the assignee type should be used as the expected type.

Lathe behavior:
In all probes,
the debug log shows the expected type comes from the enclosing method return type:
```text
semantic=Type[type=io.helidon.dbclient.DbClientService]
semantic=Type[type=java.lang.String]
semantic=Type[type=java.util.Map<java.lang.String,java.lang.String>]
```

This affects ranking.
Inside `fromConfig`, `var value = §` ranks `fromConfig` first because it returns `DbClientService`,
even though a `var` initializer should not be constrained by the method return type.
In the same method,
`String value = ""; value = §` also uses `DbClientService` instead of the local variable's `String` type.

Expected Lathe behavior:
`var value = §` should use `ExpectedValue.Unknown` or an equivalent unconstrained value slot.
`String value = ""; value = §` should use `String` as the expected type,
even when the enclosing method returns some other type.

Accepted edit, if relevant:
No bad edit payload was found.
This gap is about ranking and semantic context.

Regression target:
`CompletionSimpleNameTest.varInitializer_doesNotUseEnclosingMethodReturnType`
`CompletionSimpleNameTest.assignmentToFreshLocal_usesAssigneeTypeNotEnclosingReturnType`

Notes:
Fixed by checking the cursor's direct AST expression path before the broader enclosing-method scan.
`var` initializers now remain unconstrained,
and assignments use the left-hand side type when it is available.

The same pass confirmed useful `var` behavior elsewhere:
`var list = List.of("a"); list.§`,
`var map = new java.util.HashMap<String, String>(); map.§`,
and `var service = fromConfig(config); service.§` all resolve the inferred local variable type for subsequent member access.
Editing `var list = List.o§f("a")` still duplicates the existing call suffix,
which is already covered by `CQ-0023`.

## CQ-0004 — Dotted member access can fall back to simple-name completion in incomplete assignments

ID: CQ-0004
Status: fixed
Tier: typed
Failure mode: wrong-candidate-set
Owner component: SentinelParser / CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'inject "resources = event." at 239\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Related probe:
```bash
printf 'inject "responseType.getTypeBindings()." at 273\nlog 12\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
resources = event.§
responseType.getTypeBindings().§
```

IntelliJ or JDT behavior:
Expected IDE behavior is receiver-member completion after the explicit dot.

Lathe behavior:
The `event.` probe returns `resources`,
`new`,
`null`,
`super`,
and `this`.
The `responseType.getTypeBindings().` probe returns `handler`,
`new`,
`null`,
`super`,
and `this`.
The log shows `hasDot=true` but `sentinelCtx=SIMPLE_NAME`.

Expected Lathe behavior:
When the injector captured an explicit receiver and dot,
the parser should not route the site to simple-name completion.
`event.` should offer `ApplicationEvent` members such as `getResourceModel`.
`responseType.getTypeBindings().` should offer `TypeBindings` members such as `getBoundType`.

Accepted edit, if relevant:
Accepting `getResourceModel` after `resources = event.` should produce
`resources = event.getResourceModel()`.

Regression target:
`CompletionMemberAccessTest.memberAccess_inIncompleteAssignment_doesNotLeakSimpleNameCandidates`.

Notes:
This is the same parser-recovery failure shape in assignment and ternary-adjacent code.
Fixed by forcing explicit-dot sites with a captured receiver out of javac's
simple-name recovery path.

## CQ-0005 — Type-dot completion misses the `class` literal

ID: CQ-0005
Status: fixed
Tier: typed
Failure mode: missing-candidate
Owner component: CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'inject "Object."\nlog 40\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Related probes:
```bash
printf 'inject "Class." at 239\nlog 12\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java

printf 'inject "Class<?>." at 239\nlog 12\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
Object.§
Class.§
Class<?>.§
```

IntelliJ or JDT behavior:
Expected IDE behavior is to suggest the `class` literal after a type name.

Lathe behavior:
No completions are returned.
The log shows `sentinelCtx=TYPE_REFERENCE`.

Expected Lathe behavior:
Type-reference member completion should offer `class` when the receiver is a resolvable type.

Accepted edit, if relevant:
Accepting `class` after `Object.` should produce `Object.class`.

Regression target:
`CompletionTypeReferenceTest.typeReference_typeDot_suggestsClassLiteral`.

Notes:
Lowercase package receivers such as `java.util.` should not receive `class`.
Fixed for static type member access and for javac-recovered type-reference sites with a
type-like receiver.

## CQ-0006 — Enum switch case labels do not use the selector enum type

ID: CQ-0006
Status: fixed
Tier: typed
Failure mode: wrong-candidate-set
Owner component: SentinelParser / CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'complete after "case " expect RESOURCE_METHOD SUB_RESOURCE_LOCATOR min 2\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Related probe:
```bash
printf 'inject "switch (method.getType()) { case " at 267\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
switch (method.getType()) {
    case §RESOURCE_METHOD:
```

IntelliJ or JDT behavior:
Expected IDE behavior is enum-constant completion for the switch selector type.

Lathe behavior:
The existing `case ` site returns locals,
fields,
methods,
and statement keywords.
The injected single-line switch probe has the same simple-name fallback.

Expected Lathe behavior:
`case ` inside `switch (method.getType())` should offer `RESOURCE_METHOD`
and `SUB_RESOURCE_LOCATOR`.

Accepted edit, if relevant:
Accepting `RESOURCE_METHOD` should produce `case RESOURCE_METHOD:`
without adding `final` or a qualified enum type.

Regression target:
Future switch-case completion test in the completion suite.

Notes:
This is distinct from general enum member access because case labels use the selector type
and should not require a qualified enum receiver.

## CQ-0007 — Qualified enum type-dot completion labels and filters incorrectly

ID: CQ-0007
Status: fixed
Tier: presentation
Failure mode: bad-label-or-filter
Owner component: CompletionEngine / CompletionItemPresenter

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'complete after "ApplicationEvent.Type." expect INITIALIZATION_APP_FINISHED min 1\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Related probe:
```bash
printf 'complete after "ApplicationEvent.Type.I" expect INITIALIZATION_APP_FINISHED min 1\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
event.getType() == ApplicationEvent.Type.§INITIALIZATION_APP_FINISHED
```

IntelliJ or JDT behavior:
Expected IDE behavior is enum-constant completion whose visible labels and filtering use
the constant names.

Lathe behavior:
After `ApplicationEvent.Type.`,
Lathe returns labels such as `Type.INITIALIZATION_APP_FINISHED`.
After `ApplicationEvent.Type.I`,
Lathe returns no completions.
The log shows `hasDot=true` but `sentinelCtx=SIMPLE_NAME`.

Expected Lathe behavior:
The row should be filterable by `I` and expose the constant label
`INITIALIZATION_APP_FINISHED`.
The accepted edit should insert only the constant suffix after the existing receiver.

Accepted edit, if relevant:
Accepting `INITIALIZATION_APP_FINISHED` after `ApplicationEvent.Type.`
should produce `ApplicationEvent.Type.INITIALIZATION_APP_FINISHED`.

Regression target:
`CompletionMemberAccessTest.memberAccess_nestedEnumReceiver_usesConstantLabels`.

Notes:
The no-prefix site had enough semantic information to find constants,
but the label/filter contract was wrong.
Fixed by routing explicit dotted enum type receivers through member access instead of
simple-name recovery.

## CQ-0008 — Enum comparison RHS does not use the expected enum type

ID: CQ-0008
Status: fixed
Tier: typed
Failure mode: missing-candidate
Owner component: CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'inject "if (event.getType() == " at 239\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
if (event.getType() == §) {
```

IntelliJ or JDT behavior:
Expected IDE behavior is enum-value completion from the left-hand operand type.

Lathe behavior:
Lathe returns `null`,
`resources`,
`new`,
`super`,
and `this`.
It does not offer `ApplicationEvent.Type` enum constants.

Expected Lathe behavior:
The equality right-hand side should use the left-hand enum type as the expected type
and offer constants such as `INITIALIZATION_APP_FINISHED`.

Accepted edit, if relevant:
Accepting `INITIALIZATION_APP_FINISHED` should produce a valid comparison against
`ApplicationEvent.Type.INITIALIZATION_APP_FINISHED`,
either by inserting the qualified constant or by adding an import-safe shorthand if that is later designed.

Regression target:
Future expected-enum comparison completion test.

Notes:
This is similar in spirit to annotation enum expected-type completion,
but the expected type comes from a binary comparison instead of an annotation element.

## CQ-0009 — Uppercase simple-name completion hides visible static fields in method bodies

ID: CQ-0009
Status: fixed
Tier: typed
Failure mode: missing-candidate
Owner component: SentinelParser / CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'inject "LOGGER" at 239\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Related probe:
```bash
printf 'inject "LOG" at 239\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
private static final Logger LOGGER = LoggerFactory.getLogger(DropwizardResourceConfig.class);

public void onEvent(ApplicationEvent event) {
    LOG§
}
```

IntelliJ or JDT behavior:
Expected IDE behavior is mixed simple-name completion in expression/statement positions,
including visible fields and matching types.

Lathe behavior:
Before the fix,
the `LOG` and `LOGGER` probes returned type-index entries such as `Logger` and `LoggerFactory`,
but they do not return the visible static field `LOGGER`.
The log shows `sentinelCtx=TYPE_REFERENCE`.

Expected Lathe behavior:
Uppercase prefixes in a method body should not suppress ordinary visible values.
`LOGGER` should appear alongside any relevant type candidates.

Accepted edit, if relevant:
Accepting `LOGGER` should insert `LOGGER` without an import edit.

Regression target:
`CompletionSimpleNameTest.simpleName_uppercasePrefix_inRecoveredStatementsIncludesVisibleValuesAndTypes`.

Notes:
Fixed by routing uppercase,
receiverless statement sites recovered as ordinary type references through mixed simple-name completion.
That path intentionally skips expected-value ranking,
because recovery can borrow a following assignment's expected type and incorrectly filter visible values.

## CQ-0010 — Method label details can render without a separator before the return type

ID: CQ-0010
Status: closed (editor capability)
Tier: presentation
Failure mode: bad-label-details
Owner component: CandidateFactory / CompletionItemPresenter

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
python3 - <<'PY'
import json
import sys
from pathlib import Path
sys.path.insert(0, 'dev')
from lsp import LatheClient, find_workspace_root

p = Path('/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java')
with LatheClient.start(find_workspace_root(p), debug=True) as c:
    c.open(p)
    items = c.completion(p, 249, 25)
    for item in items:
        if item.get('filterText') == 'debug':
            print(json.dumps(item, indent=2, sort_keys=True))
            break
PY
```

Cursor context:
```java
LOGGER.de§
```

IntelliJ or JDT behavior:
Expected IDE behavior is visually separated method signature and return type,
for example `debug(String) void` or an equivalent aligned return-type column.

Lathe behavior:
The raw item uses:

```json
"label": "debug",
"labelDetails": {
  "detail": "(String)",
  "description": "void"
}
```

Some clients render that as `debug(String)void`,
with no separator between the closing parenthesis and return type.

Expected Lathe behavior:
Method completion label details should include or otherwise produce a separator before the return type
on clients that concatenate `label`,
`labelDetails.detail`,
and `labelDetails.description`.

Accepted edit, if relevant:
Not applicable.

Regression target:
Client-side completion menu rendering probe.

Notes:
This is presentation-only.
`detail` already contains the full fallback string `Logger.debug(String) : void`.
JDT LS sends the same label-details shape:
method parameters in `labelDetails.detail`,
return type alone in `labelDetails.description`,
and the ` : ` separator only in the fallback `detail` string.
The spacing should be handled by the Neovim completion renderer,
not by changing Lathe's server-side LSP data away from the JDT LS shape.

## CQ-0012 — Member completion after assignment can be misclassified as a type reference

ID: CQ-0012
Status: fixed
Tier: semantic
Failure mode: missing-candidate
Owner component: SentinelParser / CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'inject "LOGGER.de" at 239\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Control probe:
```bash
printf 'inject "LOGGER.de" at 249\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
public void onEvent(ApplicationEvent event) {
    if (event.getType() == ApplicationEvent.Type.INITIALIZATION_APP_FINISHED) {
        resources = event.getResourceModel().getResources();
        LOGGER.de§
        providers = event.getProviders();
        ...
        LOGGER.debug("resources = {}", resourceClasses);
    }
}
```

IntelliJ or JDT behavior:
Expected IDE behavior is to offer `Logger.debug(...)` overloads for `LOGGER.de§`
at both positions.

Lathe behavior:
At line 239,
Lathe returns no completions.
The debug log shows:

```text
[completion] inject prefix=|de| receiver=|LOGGER| ctx=STATEMENT hasDot=true
[completion] parsed valid=true sentinelCtx=TYPE_REFERENCE receiver=|LOGGER| class=ComponentLoggingListener method=onEvent role=ORDINARY
```

At line 249 in the same method,
the same `LOGGER.de§` probe is classified as `MEMBER_ACCESS`
and returns the expected ten `debug` overloads.

Expected Lathe behavior:
Receiver-qualified expression completion should remain `MEMBER_ACCESS`
after ordinary assignment statements in a method body.
The preceding `resources = ...;` statement should not cause `LOGGER.de§`
to route through type-reference completion.

Accepted edit, if relevant:
For `Logger.debug(String)`,
`textEdit.newText` is `debug($1)`,
the replacement range covers only the typed `de`,
and accepting the item should produce `LOGGER.debug(§)`.

Regression target:
`CompletionMemberAccessTest.memberAccess_afterAssignmentStatement_remainsMemberAccess`.

Notes:
Fixed in two parts:
1. Updated `SentinelInjector.shouldSuppressSemicolon` to only skip horizontal whitespace (space/tab), stopping at newlines to prevent crossing statements.
2. Refined `TypeResolver.resolveExpectedValue` to bypass expected type resolution if the cursor is completing a variable type or assignment assignee target (LHS).

## CQ-0013 — Simple-name method completion drops overloads with the same name

ID: CQ-0013
Status: fixed
Tier: semantic
Failure mode: missing-candidate
Owner component: SimpleNameProvider

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'inject "forT" at 63\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Control probe:
```bash
printf 'inject "DropwizardResourceConfig.forT" at 63\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Accepted-edit control:
```bash
python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java \
  accept inject 'DropwizardResourceConfig.forT' at 63 label forTesting index 1
```

Cursor context:
```java
public DropwizardResourceConfig(@Nullable MetricRegistry metricRegistry) {
    super();

    forT§
    if (metricRegistry == null) {
        metricRegistry = new MetricRegistry();
    }
}

public static DropwizardResourceConfig forTesting() {
    return forTesting(null);
}

public static DropwizardResourceConfig forTesting(@Nullable MetricRegistry metricRegistry) {
    ...
}
```

IntelliJ or JDT behavior:
Expected IDE behavior is to offer both `forTesting()` and `forTesting(MetricRegistry)`.
Overloads are separate selectable method candidates even when their simple-name label is the same.

Lathe behavior:
Simple-name completion returns only:

```text
forTesting  [Method]  DropwizardResourceConfig.forTesting() : DropwizardResourceConfig
```

The debug log shows `simple-name candidates javac=1`.
The type-qualified control probe returns both overloads:

```text
forTesting  [Method]  DropwizardResourceConfig.forTesting() : DropwizardResourceConfig
forTesting  [Method]  DropwizardResourceConfig.forTesting(MetricRegistry metricRegistry) : DropwizardResourceConfig
```

Expected Lathe behavior:
Simple-name completion should keep method overloads as distinct candidates.
Deduplication should not use only the simple name for methods.
Fields, variables, and methods with the same visible name still need Java shadowing rules,
but overloaded methods must survive long enough for presentation and selection.

Accepted edit, if relevant:
The type-qualified control confirms the parameterized overload item edits correctly:
`textEdit.newText` is `forTesting($1)`,
the replacement range covers only the typed `forT`,
and accepting the item produces `DropwizardResourceConfig.forTesting(§)`.

Regression target:
Simple-name completion test where a class declares two visible overloads with the same name.
Both overloads should be returned with distinct `labelDetails.detail` values.

Fixed by:
`CompletionSimpleNameTest.simpleName_overloadedMethods_preservesEachOverload`
and a `SimpleNameProvider` dedupe key that preserves method overload signatures.

Verification:
```bash
mvn -pl lathe-server -Dtest='Completion*Test' test
mvn spotless:check -pl lathe-server
mvn install -pl lathe-server -am -DskipTests
printf 'inject "forT" at 63\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Notes:
Inspection points at `SimpleNameProvider`.
It keeps a `seen` set keyed by `el.getSimpleName().toString()`,
so the first method overload suppresses later overloads before presentation.

## CQ-0014 — Nested classes are missing from simple-name and type-dot suggestions

ID: CQ-0014
Status: fixed
Tier: semantic
Failure mode: missing-candidate
Owner component: CompletionEngine / CandidateGenerator / SimpleNameProvider

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe commands:
```bash
printf 'inject "Com" at 63\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java

printf 'inject "DropwizardResourceConfig.Com" at 63\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
public class DropwizardResourceConfig extends ResourceConfig {
    private static class ComponentLoggingListener implements ApplicationEventListener {
        ...
    }

    public DropwizardResourceConfig(@Nullable MetricRegistry metricRegistry) {
        super();

        Com§
        DropwizardResourceConfig.Com§
    }
}
```

IntelliJ or JDT behavior:
Expected IDE behavior is to offer reachable nested classes where a type can be written.
For the Dropwizard probe,
`ComponentLoggingListener` should be available as an in-file nested type.
The qualified form `DropwizardResourceConfig.Com§` should also suggest
`ComponentLoggingListener`.

Lathe behavior:
Bare `Com§` returns external type-index and `java.lang` suggestions such as `Comparable`,
but it does not include `ComponentLoggingListener`.
The debug log shows `simple-name candidates javac=0`.

Qualified `DropwizardResourceConfig.Com§` returns no completions.
The debug log shows the request routes through `MEMBER_ACCESS`,
resolves the receiver type,
and then proposes zero field/method/enum candidates.

Expected Lathe behavior:
Nested type candidates should be included in type-capable simple-name contexts.
For type-qualified access,
static nested classes should be offered alongside static members when the receiver is a type.
Candidate presentation should use the existing type item shape,
with a type kind and the containing type/package detail.

Accepted edit, if relevant:
Not yet probed through acceptance because no candidate is returned.
Expected insertion is the nested simple name,
for example `ComponentLoggingListener`.

Regression target:
Type-reference and method-body completion tests for:

- bare nested class simple-name completion inside the enclosing top-level class;
- qualified nested class completion after `Outer.Nes§`;
- no instance-only member pollution in the qualified type context.

Notes:
`CandidateGenerator.proposeNestedTypes(...)` exists,
but it is currently reached from qualified `TYPE_REFERENCE` paths.
The Dropwizard qualified probe is classified as `MEMBER_ACCESS`,
whose candidate stream includes fields,
methods,
enum constants,
and `class`,
but not nested types.

## CQ-0015 — Member-access completion does not rank candidates by assignment expected type

ID: CQ-0015
Status: fixed
Tier: typed
Failure mode: poor-ranking
Owner component: CompletionEngine / CompletionCandidateRanker / SemanticCompletionContext

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'inject "boolean x = Providers." at 153\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
boolean x = Providers.§
```

IntelliJ or JDT behavior:
Expected IDE behavior is to use the assignment target type as a ranking signal.
For `boolean x = Providers.§`,
static methods returning `boolean` should appear before collection-returning or `void` methods.

Lathe behavior:
Lathe returned `Providers` static methods in member order.
`checkProviderRuntime(...) : boolean` appeared first,
but `isJaxRsProvider(...) : boolean`,
`isProvider(...) : boolean`,
and `isSupportedContract(...) : boolean` were below many non-boolean `get*` methods.

The debug log shows the receiver resolves correctly:

```text
[completion] parsed valid=true sentinelCtx=MEMBER_ACCESS receiver=|Providers| class=DropwizardResourceConfig method=register role=ORDINARY
[completion] resolve receiver=|Providers| type=org.glassfish.jersey.internal.inject.Providers static=true reattributed=true
```

Inspection shows member-access completion currently ranks with
`memberAccessSemanticContext(snapshot)`,
which always uses `ExpectedValue.Unknown`.

Expected Lathe behavior:
Member-access completion should derive the expected value for expression slots,
including assignment right-hand sides and variable initializers.
The expected type should affect ranking,
and possibly filtering where the completion contract already allows value-sensitive filtering.

Accepted edit, if relevant:
Not applicable.

Regression target:
Member-access completion test for `boolean value = Providers.§`
or an equivalent local static receiver fixture.
Assert boolean-returning methods sort before non-boolean methods.

Fixed by:
`CompletionMemberAccessTest.memberAccess_staticReceiver_booleanExpectedType_ranksBooleanMembersFirst`,
`CompletionMemberAccessTest.memberAccess_instanceReceiver_booleanExpectedType_ranksBooleanMembersFirst`,
member-access completion using the real semantic completion context,
and expected-type sort buckets that wrap existing member sort keys.

Verification:
```bash
mvn -pl lathe-server -Dtest='Completion*Test' test
mvn spotless:check -pl lathe-server
mvn install -pl lathe-server -am -DskipTests
printf 'inject "boolean x = Providers." at 153\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Notes:
This is not a receiver-resolution problem.
The candidate set includes the expected boolean methods;
only ranking was missing expected-type awareness.

## CQ-0016 — No-arg method accepted in assignment initializer does not insert a semicolon

ID: CQ-0016
Status: fixed
Tier: presentation
Failure mode: bad-accepted-edit
Owner component: CompletionItemPresenter / CompletionSite / SemanticCompletionContext

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java \
  accept inject 'boolean x = Boolean.TRUE.booleanV' at 153 label booleanValue
```

Related no-arg member probe:
```bash
python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java \
  accept inject 'Object x = LOGGER.atI' at 63 label atInfo
```

Cursor context:
```java
boolean x = Boolean.TRUE.booleanV§
Object x = LOGGER.atI§
```

IntelliJ or JDT behavior:
Expected editor behavior is to complete a no-argument method in a declaration/assignment
initializer to a finished statement when no semicolon is already present:

```java
boolean x = Boolean.TRUE.booleanValue();§
```

Lathe behavior:
Lathe inserts the method call only and places the cursor after `)`:

```java
boolean x = Boolean.TRUE.booleanValue()§
Object x = LOGGER.atInfo()§
```

Expected Lathe behavior:
In declaration or assignment initializer contexts,
accepting a no-arg method completion at the end of a statement should insert `();`
and place the cursor after the semicolon.
If a semicolon or suffix already exists,
the edit should avoid duplicating it.
Parameterized methods need a separate snippet shape,
likely `method($1);$0`,
and should be handled deliberately rather than accidentally.

Accepted edit, if relevant:
Current item for `booleanValue`:

```json
"insertText": "booleanValue()",
"textEdit": {
  "newText": "booleanValue()"
}
```

Desired accepted source:

```java
boolean x = Boolean.TRUE.booleanValue();§
```

Regression target:
Acceptance test for no-arg method completion in a variable initializer,
with and without an existing semicolon after the cursor.

Notes:
This intentionally narrows the current general expectation that zero-argument methods place
the cursor after `()`.
That remains correct for chained calls and unfinished expressions.
The semicolon behavior applies only when completion can identify a statement-ending initializer
or assignment context.

## CQ-0017 — Argument expected-type filtering hides useful chain receiver locals

ID: CQ-0017
Status: fixed
Tier: typed
Failure mode: missing-candidate
Owner component: CompletionCandidateRanker / SemanticCompletionContext

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe commands:
```bash
printf 'inject "cc.setSuperclass(" at 165\nlog 40\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java

printf 'inject "cc.setSuperclass(p" at 165\nlog 40\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Control probes:
```bash
printf 'inject "cc.setSuperclass(pool." at 165\nlog 40\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java

python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java \
  accept inject 'cc.setSuperclass(pool.g' at 165 label get
```

Cursor context:
```java
final ClassPool pool = ClassPool.getDefault();
final CtClass cc = pool.makeClass(...);
cc.setSuperclass(§
cc.setSuperclass(p§
```

IntelliJ or JDT behavior:
Expected IDE behavior is to include `pool` as a useful local variable candidate,
even though `pool` itself is not assignable to the `CtClass` parameter.
The user can continue with `pool.get(...)`,
which does return the expected `CtClass`.

Lathe behavior:
At the empty argument slot,
Lathe returns `cc` and other expression starters but not `pool`.
With prefix `p`,
Lathe returns no completions.

The debug log shows the slot and expected type are detected:

```text
[completion] parsed valid=true sentinelCtx=ARGUMENT_POSITION receiver=|null| class=DropwizardResourceConfig method=register role=ORDINARY
[completion] simple-name candidates javac=5 enum=0 keywords=0 semantic=Type[type=javassist.CtClass]
```

The `pool.` control probe confirms the chain is useful:
`ClassPool.get(String) : CtClass`,
`getCtClass(String) : CtClass`,
`makeClass(String) : CtClass`,
and related methods are available from `pool`.

Expected Lathe behavior:
Expected-type ranking in argument positions should not hide non-assignable reference values.
Directly assignable candidates should rank first,
while reference-typed values remain visible as possible chain receivers.

Accepted edit, if relevant:
Accepting `pool.get` currently produces:

```java
cc.setSuperclass(pool.get(§)
```

Regression target:
Argument-position completion test where the expected type is `Target`,
the visible local is `Factory`,
and `Factory.create()` returns `Target`.
The local `factory` should remain visible with prefix `f`.

Fixed by:
`CompletionArgumentTest.argumentPosition_chainReceiverLocal_visibleWhenItCanProduceExpectedType`
and a ranker policy that keeps reference-typed candidates visible in typed value slots.

Verification:
```bash
mvn -pl lathe-server -Dtest='Completion*Test' test
mvn spotless:check -pl lathe-server
mvn install -pl lathe-server -am -DskipTests
printf 'inject "cc.setSuperclass(p" at 165\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Notes:
This is a typed-completion tradeoff.
The fix intentionally favors ranking over filtering for reference values,
because references can become receivers for chained expression construction.

## CQ-0001 — Annotation enum value completion routes to element-name completion

ID: CQ-0001
Status: fixed
Tier: typed
Failure mode: missing-candidate
Owner component: SentinelParser / CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-auth/src/main/java/io/dropwizard/auth/Auth.java`

Probe command:
```bash
printf 'complete after "@Retention(" expect RetentionPolicy RUNTIME min 1\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-auth/src/main/java/io/dropwizard/auth/Auth.java
```

Related probe:
```bash
printf 'complete after "RetentionPolicy." expect RUNTIME min 1\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-auth/src/main/java/io/dropwizard/auth/Auth.java
```

Related array-value probes:
```bash
python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-auth/src/main/java/io/dropwizard/auth/Auth.java \
  complete after "ElementType." expect FIELD PARAMETER min 2

python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-auth/src/main/java/io/dropwizard/auth/Auth.java \
  complete after "@Target({ " expect ElementType FIELD PARAMETER min 1
```

Cursor context:
```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD, ElementType.PARAMETER })
```

IntelliJ or JDT behavior:
Expected IDE behavior is enum-value completion for the `Retention.value()` slot.
At `@Retention(`,
`RetentionPolicy` or `RUNTIME` should be reachable.
At `RetentionPolicy.`,
`RUNTIME` should be offered.

Lathe behavior:
The `@Retention` probes return only `value [Property] java.lang.annotation.RetentionPolicy`.
The `@Target` probes return only `value [Property] java.lang.annotation.ElementType[]`.
The log shows `sentinelCtx=ANNOTATION_ARGUMENT`,
including after `RetentionPolicy.` where member-access completion should apply.

Expected Lathe behavior:
Annotation value slots should use the annotation element return type as the expected type.
Enum-valued elements should offer matching enum constants,
and member access on the enum type inside an annotation value should offer enum constants.
Array-valued annotation elements should use the array component type inside `{}`.

Accepted edit, if relevant:
Accepting `RUNTIME` after `RetentionPolicy.` should produce `RetentionPolicy.RUNTIME`.
Accepting a shorthand enum constant after `@Retention(` can be revisited during design;
the first required behavior is not to route the value expression to annotation element-name completion.

Regression target:
`CompletionAnnotationTest`.

Notes:
This is a real Dropwizard probe with no diagnostics.
Fixed for enum member access,
unnamed enum `value()` elements,
and unnamed enum-array `value()` elements.

## CQ-0003 — In-token method completion does not replace the suffix

ID: CQ-0003
Status: fixed
Tier: presentation
Failure mode: bad-replacement-range
Owner component: CompletionSite / CompletionItemPresenter

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-configuration/src/main/java/io/dropwizard/configuration/BaseConfigurationFactory.java`

Probe command:
```bash
python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-configuration/src/main/java/io/dropwizard/configuration/BaseConfigurationFactory.java \
  complete 155:20 expect setFieldPath min 1
```

Raw item inspection:
```text
label: setFieldPath(List)
insertText: setFieldPath($1)
textEdit.range: 155:17-155:20
```

Cursor context:
```java
.set§FieldPath(e.getPath())
```

IntelliJ or JDT behavior:
Expected IDE behavior is insert/replace-safe in-token completion.
Accepting `setFieldPath` should replace the whole current identifier or use an insert/replace edit so the suffix is not duplicated.

Lathe behavior:
The menu contains `setFieldPath(List)`,
but the edit replaces only `set`.
Accepting the item would leave the existing `FieldPath` suffix after the inserted call text.

Expected Lathe behavior:
Completion at `.set§FieldPath(...)` should produce a source edit equivalent to replacing `setFieldPath`
with `setFieldPath($1)`,
or otherwise use LSP insert/replace semantics so accepting the item does not duplicate suffix text.

Accepted edit, if relevant:
The accepted edit should not produce `setFieldPath($1)FieldPath(e.getPath())`.

Regression target:
`CompletionPresentationTest.memberAccess_inTokenCompletion_replacesWholeIdentifier`.

Notes:
This is a real Dropwizard probe with no diagnostics.
Fixed by extending completion text-edit replacement ranges over the current Java identifier suffix.

For the current discovery phase,
new entries should come only from Dropwizard or Helidon explorer probes.

## CQ-0018 — Expected return type inside lambda bodies is not resolved

ID: CQ-0018
Status: fixed
Tier: typed
Failure mode: wrong-candidate-set
Owner component: TypeResolver

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Cursor context:
```java
Stream.of("").map(s -> §)
```

IntelliJ or JDT behavior:
Expected IDE behavior is to recognize that the expected return type of the lambda body is String (or boolean for filter), and rank compatible candidates higher.

Lathe behavior:
Lathe does not resolve the expected type of a lambda body, returning ExpectedValue.Unknown.

Expected Lathe behavior:
Lathe should resolve the expected return type of a lambda body by finding its enclosing LambdaExpressionTree, determining its target functional interface type, locating its Single Abstract Method (SAM), and resolving the SAM's return type parameterization.

Accepted edit, if relevant:
Not applicable.

Regression target:
`CompletionArgumentTest.lambdaBody_mapReturnExpectedType_ranksStringHigher` and `CompletionArgumentTest.lambdaBody_filterExpectedType_ranksBooleanHigher`

Notes:
This helps prioritize correct return values inside lambda bodies (e.g. map, filter, etc.).

## CQ-0019 — Throwables not ranked higher/filtered in throw statements

ID: CQ-0019
Status: fixed
Tier: typed
Failure mode: wrong-candidate-set
Owner component: TypeResolver / CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Cursor context:
```java
throw §
throw new §
```

IntelliJ or JDT behavior:
Expected IDE behavior is to rank local variables/methods returning Throwables higher for throw statement simple names, and to restrict constructible type completions to Throwable subclasses in throw statement constructor calls.

Lathe behavior:
Lathe does not recognize that a throw statement expects java.lang.Throwable, leaving the expected value as Unknown.

Expected Lathe behavior:
Lathe should resolve the expected type of a ThrowTree expression to java.lang.Throwable, and use this to filter constructor completions and rank simple name completions.

Accepted edit, if relevant:
Not applicable.

Regression target:
`CompletionSimpleNameTest.throwStatement_simpleName_ranksThrowablesHigher` and `CompletionSimpleNameTest.throwStatement_constructorCall_ranksThrowablesHigher`

Notes:
Requires overriding visitThrow in TypeResolver's scanners.

## CQ-0020 — Static member access inside argument not ranked by expected type

ID: CQ-0020
Status: fixed
Tier: typed
Failure mode: poor-ranking
Owner component: TypeResolver / CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-configuration/src/main/java/io/dropwizard/configuration/BaseConfigurationFactory.java`

Probe command:
```bash
printf 'inject "List<String> ps = e.getKnownPropertyIds().stream().map(Object::toString).collect(Collectors." at 152\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-configuration/src/main/java/io/dropwizard/configuration/BaseConfigurationFactory.java 2>&1
```

Cursor context:
```java
List<String> ps = e.getKnownPropertyIds().stream().map(Object::toString).collect(Collectors.§)
```

IntelliJ or JDT behavior:
Expected IDE behavior is to rank `toList()` (and other `List`-returning collectors) first,
because the outer `collect()` call's argument type is `Collector<String, ?, List<String>>`.

Lathe behavior:
Fixed.
Lathe now uses the shared static-member result rule path for `Collectors.§` inside
`Stream.collect(...)`.
It derives the expected result type from the enclosing assignment or initializer context and
ranks compatible collector factories before unrelated collectors.

Before the fix,
the probe returned 45 items in alphabetical order.
`toList` appears at position 36.
The `sentinelCtx=MEMBER_ACCESS` path routed through `Collectors` static members,
but no semantic expected type was applied.

Expected Lathe behavior:
When a static member access (`Collectors.§`) is the argument to a call (`collect(§)`),
the expected type from the argument position should flow into the member access ranking.
`toList()` returning `Collector<T, ?, List<T>>` should rank first for a `Stream<String>.collect(§)` context expecting `List<String>`.

Accepted edit, if relevant:
`toList` accepted edit is correct: inserts `toList()` at the position, producing `Collectors.toList()`.

Regression target:
`CompletionMemberAccessTest.memberAccess_staticReceiverInArgumentPosition_rankedByOuterArgumentExpectedType`
`CompletionMemberAccessTest.memberAccess_collectorsReceiverInsideCollectArgument_suggestsCollectorMethods`

Notes:
The accepted edit for `toList` is correct.
The gap was ranking only, not insertion.
The fix propagates the outer argument's expected type into the nested member-access ranking context
through the centralized `CompletionLibraryRules` table.
Related to CQ-0015 (member-access expected type from assignment),
but that fix addressed only the direct-assignment context, not the nested-argument context.

## CQ-0021 — Type-pattern switch `case §` (no prefix) returns simple-name candidates instead of types

ID: CQ-0021
Status: fixed
Tier: typed
Failure mode: wrong-candidate-set
Owner component: CompletionEngine / CandidateGenerator

Project/file:
`/home/ag-libs/git/helidon/webserver/webserver/src/main/java/io/helidon/webserver/ProxyProtocolHandler.java`

Probe command:
```bash
printf 'inject "case " at 512\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/webserver/webserver/src/main/java/io/helidon/webserver/ProxyProtocolHandler.java 2>&1
```

Control (prefix shows types correctly):
```bash
printf 'inject "case Inet" at 512\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/webserver/webserver/src/main/java/io/helidon/webserver/ProxyProtocolHandler.java 2>&1
```

Cursor context:
```java
return switch (sourceSocketAddress) {
    case InetSocketAddress socket -> socket.getHostString();
    case §
```

IntelliJ or JDT behavior:
Expected IDE behavior is to offer type names that are compatible with the switch selector type (`SocketAddress`).
`InetSocketAddress`, `UnixDomainSocketAddress`, and other `SocketAddress` subtypes should appear as top candidates.

Lathe behavior:
Without a prefix, returns 46 simple-name candidates (fields, variables, methods).
No `[Class]` or `[Interface]` type candidates appear.
Log shows `sentinelCtx=CASE_LABEL` and `semantic=Type[type=java.net.SocketAddress]` are correctly detected,
but `simple-name candidates javac=59` dominates and type-index is not queried.

With prefix `case Inet§`, returns 6 type-index candidates (`InetSocketAddress`, `InetAddress`, etc.) correctly.

Expected Lathe behavior:
For a `CASE_LABEL` site in a type-pattern switch, the no-prefix path should query the type-index
and return type candidates rather than falling through to javac's simple-name candidates.
Simple-name candidates (fields, variables, methods) are not valid type-pattern case labels
and should be suppressed or ranked after type candidates.

Accepted edit, if relevant:
Not yet probed.
Expected: accepting `InetSocketAddress` should insert `InetSocketAddress ` (with trailing space for the binding variable name).

Regression target:
`CompletionSimpleNameTest.simpleName_switchCaseLabel_typePatternSubject_suggestsTypes`

Notes:
CQ-0006 fixed enum-switch case labels (routing through `enumCase` candidates).
This gap is the parallel fix for type-pattern-switch case labels.
The enum case fix detects enum selector type and adds enum constants.
The type-pattern fix needs to detect non-enum selector types and route through the type-index.
Subtype filtering (only `SocketAddress` subtypes) would be ideal but is not required in the first slice.

## CQ-0031 — Statement member access can hide `equals` after parser recovery

ID: CQ-0031
Status: fixed
Tier: basic
Failure mode: missing-candidate
Owner component: SemanticCompletionContext / CompletionCandidateRanker

Project/file:
`/home/ag-libs/git/helidon/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClient.java`

Observed context:
After adding a new line after the local variable declaration near line 53,
typing `config.` sometimes omits `equals`.
Selecting another method and backspacing back to `config.` can make `equals` appear again.

Log evidence:
Both requests resolve the same member-access receiver:
`receiver=|config| type=io.helidon.dbclient.mongodb.MongoDbClientConfig`.

One request returns 13 candidates:
`[credDb, password, url, username, equals, getClass, hashCode, notify, notifyAll, toString, wait, wait, wait]`.

Another request returns only 7 candidates:
`[credDb, password, url, username, getClass, hashCode, toString]`.

Likely cause:
Parser recovery can treat `config.` as the initializer expression for a previous incomplete
`String` declaration.
`CompletionCandidateRanker` then filters member candidates by that inferred expected value context.
That explains the missing `void` Object methods (`notify`, `notifyAll`, `wait`).
It also explains `equals` disappearing when the inferred expected type is not boolean-compatible,
because `equals` returns `boolean`.

Expected Lathe behavior:
A standalone member-access statement should show normal accessible members even when the previous
statement is incomplete or parser recovery has introduced temporary errors.
Expected-type filtering should not hide `equals` for `config.` in statement position.

Regression target:
`CompletionMemberAccessTest.memberAccess_afterIncompletePreviousStatement_includesEquals`

Notes:
The reproducer is an incomplete declaration followed by member access:
```java
String selected =
config.§
```

The current failing candidate set is:
`[credDb, password, url, username, toString, getClass, hashCode]`.
The assertion includes the string-valued domain methods and `equals`.

Fixed by letting `java.lang.Object` methods pass expected-type compatibility filtering after
the existing `void`-method filter has removed `notify`, `notifyAll`, and `wait`.
