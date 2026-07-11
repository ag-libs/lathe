# Lathe — Resolved Gaps (Archive)

Resolved (`done` / `non-goal`) gap entries, moved out of the active [gaps.md](gaps.md) per the
[gap lifecycle](gap-process.md). Kept for the historical record and regression-target references.

---

# Navigation, references, code actions (resolved)

## EG-042 — Call hierarchy "does not resolve from a call site" — invalid (probe-positioning artifact)

**Status: non-goal.**
Reclassified after live re-verification: `prepareCallHierarchy` already resolves from a call site
when the cursor is on the **method name**. Confirmed on a live workspace across unqualified (`foo()`),
qualified (`x.foo()`), chained-receiver (`a.b().c()`), and JDK-target call sites — each prepares an
item and finds callers. Same-CU unit coverage in `CallHierarchyPrepareTest` and
`CallHierarchyIncomingLocatorTest` corroborates it.

The original report was a **cursor-positioning artifact of the probe**, not a server defect: the
probe command `callers "next.handler().handle"` places the cursor at the *start* of the match — on
the receiver `next` (a variable), whose element is not a method, so `prepareCallHierarchy` correctly
returns "(no call hierarchy item at this position)". The gap's own "works" probe
(`callers "handle(ServerRequest"`) started on the method name, which is why declaration vs. call-site
looked asymmetric; on a genuine method-name cursor there is no asymmetry.

No code change; nothing to implement. (Helidon, used in the original probe, was not checked out at
re-verification time; the artifact/contrast was reproduced on an equivalent live workspace instead.)

---

## EG-044 — Call hierarchy now aggregates polymorphic calls made through a supertype/interface reference when queried from a concrete override

**Status: done — Target: M3.**

Incoming call hierarchy queried from a concrete `@Override` previously returned zero callers: real
call sites bind statically to the interface/supertype method (`feature.setup(...)` where `feature`
is interface-typed), and the incoming search matched call sites by *exact* resolved method, so the
override's own binary-name + erased descriptor never matched them.

Root cause: `CallHierarchyIncomingLocator` gated every candidate call site with
`ReferenceTarget.matches(...)` (exact owner + descriptor). Candidate-*file* discovery was already
override-aware (the report noted "61 candidates scanned"), so only the per-site matcher was too
strict.

Fix (one class, `CallHierarchyIncomingLocator`): resolve the target's `ExecutableElement` once via
`ReferenceTarget.resolveMethodElement`, then match call sites with
`ReferenceTarget.matchesWithOverrides(...)` — the same override-aware matcher already shipped for
Find References (EG-014) and `textDocument/implementation`. It accepts a call site whose resolved
method overrides, or is overridden by, the queried method (both directions), so querying from either
the interface method or a concrete override now yields the polymorphic call sites. Constructors are
unaffected (the override branch applies only to `METHOD`; exact calls still hit the `matches`
fast-path). No `ReferenceTarget` API change.

### Regression targets

- `CallHierarchyIncomingLocatorTest.searchIncomingCalls_polymorphicCallThroughInterface_findsCallerFromOverride`
- `CallHierarchyIncomingLocatorTest.searchIncomingCalls_unrelatedSameNamedMethod_notReportedFromOverride`
  (boundary: an unrelated same-named method on an unrelated type must not be over-matched)

---

## EG-043 — Type hierarchy relations required the type's declaration file to be open

**Status: done — Target: M3.**

Type-hierarchy subtypes/supertypes came back empty whenever the type's **declaration** source file
was not currently open — most visibly when the feature was invoked from a *usage* site (a field /
parameter / local declared type), where the declaration is typically closed.

Root cause: `WorkspaceSession.typeHierarchySubtypesFuture` / `typeHierarchySupertypesFuture` routed
the computation through `openDocFeature(data.routingUri(), …)`, which returns the empty fallback when
`docs.get(routingUri) == null`. `routingUri` is the type's **declaration** source (set by
`TypeHierarchyResolver` to the located declaration file). The lambda ignored the `doc` argument
entirely — the computation needs only the workspace type index + global source dirs + the parser,
which reads declaration sources from disk (`TypeSourceLocator.locate`), so the open-document gate was
spurious.

Fix: both futures now use `routeFeature(data.routingUri(), …)` instead of `openDocFeature`, routing
by URI to a worker without requiring the document to be open (mirrors `codeActionFuture`). A reactor
type's `routingUri` routes to its `Module` worker and computes regardless of which file is open.

Corrections to the earlier (re-scoped) triage: the defect affected **both** supertypes and subtypes
(prior probing only saw subtypes differ because the probed interface had no supertypes); the trigger
is really "declaration file not open", of which usage-site invocation is the common case, not a
distinct "usage-site resolution" defect; and it **is** unit-reproducible — the earlier test exercised
`SourceAnalysisSession` directly, one layer below the `WorkspaceSession` gate where the bug lived.

### Regression targets

- `LatheTextDocumentServiceTest.typeHierarchySubtypes_declarationFileNotOpen_stillResolvesSubtypes`

---

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

## EG-034 — `textDocument/implementation` on an interface method omits record component accessors that implement it

**Status: done — Target: M2.**

`textDocument/implementation` now returns records that implement an interface method through an
implicit record component accessor.
The accessor method was already found by javac and accepted by `Elements.overrides`; the result was
dropped only because the synthesized accessor has no `MethodTree`.

The resolved implementation first uses the normal method declaration path.
When that path is absent for a zero-argument method in a `RECORD` class tree, it falls back to the
matching `VariableTree` member for the record component and returns the component name location.
This uses javac's AST shape for records and does not parse Java text.

Regression coverage:

- `MethodImplementationTest.methodImplementations_recordComponentAccessor_returnsComponentDeclaration`

---

## EG-032 — Signature help returns nothing for a qualified call with empty parentheses when the method has parameters

**Status: done — Target: M2.**

Qualified empty-argument calls now use the same by-name overload fallback that unqualified calls
already used.
When javac cannot resolve `receiver.method()` to an applicable `ExecutableElement`, the resolver
looks up the receiver expression's declared type and lists methods with the selected name.
This preserves the existing exact-resolution path when javac can resolve the call.

Regression coverage:

- `SignatureHelpTest.signatureHelp_qualifiedEmptyParens_paramMethod_returnsSignature`
- `SignatureHelpTest.signatureHelp_qualifiedEmptyParens_zeroParamMethod_stillWorks`

---

## EG-005 — Workspace symbol search uses strict prefix matching; CamelCase and infix queries find nothing

Status: done — Target: M2.

### Observed behaviour (original)

```
sym "ServerFactory"   → ServerFactory (interface), ServerFactoryImpl (JDK SASL internal)
                        missing: AbstractServerFactory, DefaultServerFactory, SimpleServerFactory
sym "TaskMgr"         → 0 results (no CamelCase abbreviation matching)
sym "AbstractServer"  → AbstractServerFactory (correctly found by prefix)
```

### Resolution

Implemented CamelCase-hump matching (`CamelCaseMatcher`), scoped to reactor-owned types and
merged alongside the existing exact prefix search rather than replacing it:

- `AbstractServerFactory`/`DefaultServerFactory`/`SimpleServerFactory` are now all reachable via
  `"ServerFactory"` — the humps `[Server, Factory]` match a subsequence of candidate humps,
  skipping the leading `Abstract`/`Default`/`Simple` hump.
- `"TaskMgr"` now finds `TaskManager` — humps align, and `Mgr` matches `Manager` via a
  subsequence-within-hump test (`M` anchors, `g`/`r` found in order within `anager`), not a
  literal prefix.
- `"AbstractServer"` (plain prefix) is unaffected, matches as before.

Implemented differently than originally proposed: rather than a secondary pre-built CamelCase
initialism index maintained alongside the reactor-scan lifecycle, matching is done as a live
per-query scan (`WorkspaceTypeIndex.searchCamelCase`) over reactor-owned entries only. This was
judged simpler with no meaningfully worse performance — the per-query scan is cheap (reactor-only,
not the full JDK+dependency universe), and the expensive part (per-result `declarationRange`
parse) stays bounded by the same result cap as the existing prefix path, so no new index structure
or reactor-reload-lifecycle hook was needed.

Descoped, not part of this fix: general infix/substring matching independent of CamelCase-hump
boundaries (e.g. a raw substring like `"erverFac"` that doesn't start at a hump boundary). The
gap's title mentioned both; only the CamelCase half is resolved here. File a new gap if plain
infix matching is wanted later — it is a distinct feature, not an extension of this one.

### Regression targets

- `CamelCaseMatcherTest` — the matching algorithm in isolation (initials, partial-hump
  abbreviations, hump-skipping, case-insensitivity, digit boundaries, negative cases).
- `WorkspaceTypeIndexTest.searchCamelCase_reactorOnly_excludesStaticTypes`,
  `searchCamelCase_abbreviatedHump_findsReactorMatch`, `searchCamelCase_limitsResults`,
  `searchCamelCase_emptyIndex_returnsEmpty`.
- `WorkspaceSymbolTest.resolve_camelCaseAbbreviation_findsReactorMatchMissedByPrefix`,
  `resolve_prefixAndCamelCaseOverlap_deduplicatesResult`.

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

## FR-005 — Client response incident is not reproducibly covered

Status: done — Target: M2.

The original incident: a Helidon session's server log ended immediately after logging reference
hits, with no exception, fatal JVM message, or RPC exit record, while Neovim never displayed the
two locations. That did not establish a Lathe crash — it established that the test suite at the
time stopped before JSON-RPC client receipt and could not distinguish a server response failure
from an editor-side display or process failure.

### Resolution

`LspSmokeTest` (added for FR-004) now closes that gap directly: `references_fromReactorSource_-
findsUsageAcrossModules`, `references_fromCachedJdkSource_findsReactorUsages`,
`references_fromBaseMethod_findsOverrideDeclarationAndCallSite`, and
`references_fromOverrideMethod_findsSupertypeTypedCallSite` each drive a real subprocess server
through a real LSP4J client and call
`server.getTextDocumentService().references(params).get(30, SECONDS)`, asserting on the actual
`Location` values received — not server-side logs. That is the invoker-side requirement from this
gap's "Required investigation support" list.

The logging requirement is also already met: `WorkspaceSession.referencesFuture`'s existing log
line, `"[references] %s %dms target=%s hits=%d"`, retains request URI, elapsed time, target name,
and hit count.

Not covered: an isolated unit-level test asserting `Location` serializability in isolation (the
invoker test covers this implicitly, since LSP4J actually serializes the response over the wire).
If that narrower gap matters later, open a new `documented` entry rather than reopening this one.

### Regression test

`LspSmokeTest.references_fromReactorSource_findsUsageAcrossModules`,
`references_fromCachedJdkSource_findsReactorUsages`,
`references_fromBaseMethod_findsOverrideDeclarationAndCallSite`,
`references_fromOverrideMethod_findsSupertypeTypedCallSite`.

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

Later superseded by **FR-009**, which replaced the broad simple-name method discovery with
override-family narrowing (declaring type + overridden declarers + subtypes) while preserving this
supertype-self-call coverage; the regression test was renamed accordingly.

Regression coverage:

- `ReferenceCandidatePlannerTest.planCandidates_overriddenMethod_includesOverrideFamilyFiles`

---

## FR-009 — Member-reference search compiles every token-matching file (no hierarchy narrowing)

**Status: done — Target: M2.**
Discovered against an `@Builder`-heavy reactor; confirmed on `sample-workspace`
(`DobServerConfig`).

### Observed behaviour (historical)

Find References on a member whose simple name is common compiled hundreds of files to return a
handful of hits. `ReferenceCandidatePlanner.planCandidates` returned the raw simple-name candidate
set for `METHOD` targets with no type filter; record components normalise to their accessor (a
`METHOD`) via `ReferenceTarget.recordAccessorFor`, so component searches took the same broad path.
Each candidate was then compiled with FAST javac in `searchReferencesTransient` — compilation, not
the O(1) token lookup, dominated the wall-clock. The `FIELD` branch narrowed only by the enclosing
type's simple name, which also *missed* a protected field read through a subtype-typed receiver in a
file that never spelled the declaring type.

### Resolution

`planCandidates` now bounds method and field searches to the member's **override family** instead of
the raw simple-name set (`files(memberName) ∩ ⋃ candidateUris(familySimpleName) ∪ static-import
spellings`, via the shared `narrowToFamily`/`overrideFamily` helpers):

- **method** — declaring type + the supertypes whose method it overrides (reactor *or* dependency) +
  its overriding subtypes;
- **field** — declaring type + subtypes that inherit it;
- **constructor / enum constant** — declaring type only.

The overridden declarers require javac member inspection, so they are computed in
`ReferenceTarget.from` and carried on the record (`overriddenDeclarers` + `overrideFamilyBounded`);
subtypes come from the reactor `WorkspaceTypeIndex`, now passed to the planner.

Broad search is retained in exactly two cases so no genuine reference is missed: a target
reconstructed from a call-hierarchy item (no javac context, `overrideFamilyBounded == false`), and a
method overriding a `java.lang.Object` member (`equals`/`hashCode`/`toString`) — every type is an
`Object` and receivers are rarely spelled `Object`, so family narrowing would drop most call sites.

Accepted trade-off (as designed): a reference whose owner/receiver type is never textually spelled in
its file is missed — the same surface the `FIELD` branch already accepted, now consistent across
kinds.

### Measured (sample-workspace)

| Search | Before | After |
|---|---|---|
| `DobServerConfig.name()` record accessor (overrides dependency `BaseConfig.name`) | 112 candidates, ~16.6 s, 1 hit | 8 candidates, ~4.8 s, 1 hit |
| `ValidCheck.check()` static (overrides nothing) | 45 candidates, 74 hits | unchanged |

The `name()` accessor is the interesting case: it overrides `BaseConfig.name()` in the `tdbase`
dependency, so the fix narrows by the declarer simple names (`DobServerConfig`, `JerseyServiceConfig`,
`ServiceConfig`, `BaseConfig`) rather than falling back to broad.

### Probe commands

```bash
# Against an @Builder record: refs on a component accessor; watch "N / M candidates" vs hit count.
printf 'refs <line>:<col>\n' \
  | LATHE_DEBUG=1 python3 dev/explore.py /path/to/workspace/.../SomeRecord.java
```

### Regression targets

- `ReferenceCandidatePlannerTest.planCandidates_recordAccessorNoInterface_limitsToDeclaringTypeFiles`
- `ReferenceCandidatePlannerTest.planCandidates_overriddenMethod_includesOverrideFamilyFiles`
- `ReferenceCandidatePlannerTest.planCandidates_overridesObjectMethod_fallsBackToBroad`
- `ReferenceCandidatePlannerTest.planCandidates_overridesExternalInterface_narrowsByDeclarerName`
- `ReferenceCandidatePlannerTest.planCandidates_protectedFieldInheritedInSubclass_includesSubclassFiles`
- `ReferenceCandidatePlannerTest.planCandidates_fieldNoSubtypes_limitsToDeclaringTypeFiles`

---

## EG-045 — Hover on a component reference inside a record's compact constructor resolves to the callee's parameter

**Status: done — Target: M3**

Fixed: `SourceLocator.masksParameterContext` now suppresses the argument param-hint for a record's
canonical-constructor `PARAMETER` (a compact-ctor component reference), mirroring the existing
`FIELD` mask, so hover falls through to normal resolution and shows the component. Verified live.
Regression targets below are implemented and passing.

### Observed behaviour

Hover on a bare reference to a record component **inside the compact constructor body** returns the
wrong symbol: it resolves to the formal parameter of the method the component is passed to, not to the
component itself.

```java
public record Config(String bucket) {
    public Config {
        check(bucket, "bucket");   // ← hover on `bucket` → "Object value" (check's parameter!)
    }
    static void check(Object value, String name) {}
}
```

Hover is correct everywhere else the same component appears — verified against a validation workspace:

| Hover position | Result | Correct? |
|---|---|---|
| component in the record header | the component (`T bucket`) | ✓ |
| accessor call `x.bucket()` | the accessor (`T bucket()`) | ✓ |
| ordinary method argument (a local/param elsewhere) | that argument's own type | ✓ |
| **bare component ref inside the compact constructor** | the *callee's* first parameter (`Object value`) | ✗ |

At the same failing position, `definition` correctly jumps to the component declaration, so the
symbol is resolvable — only the hover path mis-resolves it. Shares its trigger with the references
defect [FR-014](#fr-014--find-references-from-a-component-reference-inside-a-records-compact-constructor-returns-only-that-one-occurrence).

### Root cause (hypothesis)

The bare reference is the implicit canonical-constructor `PARAMETER`, whose source position overlaps
the record header (the backing field and the canonical-ctor parameter share the header range). The
hover position→element path appears not to find that parameter's attributed element at the in-body
position and falls back to the enclosing `MethodInvocationTree`, resolving to the invoked method's
formal parameter (`check(Object value, …)` → `value`). `definition` uses a different resolver
(`DeclarationLocator`) and is unaffected — so this is specific to the hover resolution path, not the
shared `resolve()`/`elementAt` position mapping (ordinary arguments hover correctly).

### Probe commands

```bash
# `bucket,` lands the cursor on the compact-constructor use.
# Expected: the component's type. Bug: the callee's parameter ("Object value").
printf 'hover "bucket,"\n' | python3 dev/explore.py /path/to/workspace/.../Config.java
```

### Regression targets

- `HoverTest.hover_recordComponentInCompactConstructor_resolvesComponentNotCalleeParameter`
  (positive — the failing case)
- `HoverTest.hover_ordinaryMethodArgument_resolvesArgumentNotCalleeParameter`
  (boundary — ordinary arguments must keep resolving to the argument, not the callee's parameter)

---

## EG-046 — Hover on a call argument shows synthetic `arg0` parameter names while signature help shows the real names

**Status: done — Target: M3**

Fixed: the `SourceAnalysisSession.hover` argument param-hint branch now formats through the shared
`resolveParamNames` + `isSyntheticName` + `TypeDisplayFormatter` path (via `HoverFormatter.formatParam`),
matching signature help and method-name hover; the divergent `HoverFormatter.formatParameter` was
removed. Verified live on line 84 of the validation workspace — string-literal arguments now show
`name` / `defaultObj` instead of `arg0` / `arg1`. Regression target below is implemented and passing.

### Observed behaviour

Hovering an argument at a call site shows a synthetic parameter name (`arg0`, `arg1`, …) whenever the
callee comes from a dependency or the JDK compiled without javac `-parameters`. Signature help on the
same call shows the **real** names (and highlights the active parameter). Confirmed against a
validation workspace, hovering the arguments of a JDK call `Objects.requireNonNullElse(x, 10)`:

| Probe | Result |
|---|---|
| `hover` on argument 0 (`x`) | `T arg0` |
| `hover` on argument 1 (`10`) | `T arg1` |
| `sig` on the call | `T requireNonNullElse([T obj], T defaultObj)` — real names, active parameter highlighted |

The two features disagree about the same method's parameters: hover surfaces the raw class-file
placeholder, signature help surfaces the declared source name.

### Root cause — and why signature help is correct

The `arg0` class-file limitation was already solved once (see
`docs/done/lathe-signature-help.md`): when a class file lacks a `MethodParameters` attribute, javac
synthesises `arg0`/`arg1`, so `SourceParser.resolveParamNames` does an on-demand, AST-only parse of
the callee's `.java` source (from the dependency / JDK source cache) to recover the declared names,
and `SourceParser.isSyntheticName` suppresses any remaining `argN` (falling back to type-only
display). Both consumers that show good names wire this in:

- **Signature help** — `SignatureHelpResolver` calls `resolveParamNames` (line ~248). **This is why it
  is correct.**
- **Method-name hover** — `SourceAnalysisSession.hover` (line ~239) resolves `sourceParamNames` and
  passes them to `HoverFormatter.format` → `formatParam`, which also applies `isSyntheticName`.

The **argument param-hint** branch of hover never got this treatment. When the cursor is on an
argument, `SourceAnalysisSession.hover` (lines ~221-225) takes an early branch on
`SourceLocator.parameterElementAt` and formats via a *separate* method, `HoverFormatter.formatParameter`:

```java
public static String formatParameter(final VariableElement param) {
  return "```java\n%s %s\n```".formatted(param.asType(), param.getSimpleName());  // raw name + raw type
}
```

It prints `param.getSimpleName()` directly — no `resolveParamNames`, no `isSyntheticName` suppression,
no `TypeDisplayFormatter`. So it shows the class-file placeholder `arg0`, and the type without the
display formatting the rest of hover uses. It is simply a path that drifted from the resolution the
other two share.

### Proposed fix

Format the param-hint through the same resolution the other paths use. From the `param` returned by
`parameterElementAt`, recover the callee and index without changing its signature —
`callee = (ExecutableElement) param.getEnclosingElement()`,
`idx = callee.getParameters().indexOf(param)` — then resolve
`parser.resolveParamNames(callee, allRoots)`, apply `isSyntheticName` suppression, and format with a
`TypeDisplayFormatter` (reusing `HoverFormatter.formatParam`). Delete the divergent
`formatParameter`. This makes argument hover consistent with signature help and method-name hover.

Shares the param-hint code path with [EG-045](#eg-045--hover-on-a-component-reference-inside-a-records-compact-constructor-resolves-to-the-callees-parameter)
(which fires the branch for the wrong element); the two are best fixed together.

### Probe commands

```bash
# Hover an argument of a JDK/dependency call whose class file lacks -parameters, then compare to sig.
# Expected (after fix): hover shows the same real names as sig; today it shows argN.
printf 'hover "<arg-token>"\nsig after "requireNonNullElse("\n' \
  | python3 dev/explore.py /path/to/workspace/.../SomeFile.java
```

### Regression targets

- `HoverTest.hover_classFileDependencyArgument_showsSourceParameterName`
  (positive — argument hover shows the resolved source name, mirroring
  `signatureHelp_classFileDependency_showsSourceParameterNames`)
- `HoverTest.hover_classFileArgumentNoSource_fallsBackToTypeOnly`
  (boundary — when no source is resolvable, suppress `argN` and show type-only, never the placeholder)

---

## FR-011 — Method call on a `var`/chained receiver is dropped when the receiver type is never spelled

**Status: done — Target: M2**

Fixed: candidate planning no longer narrows method targets to the override family, so a call on a `var`/chained/unspelled receiver is compiled and matched. Merged in 0b9d366; regression targets below pass.

### Observed behaviour

Find References on a record component reports **no matches outside the declaring record**, even though
a genuine call site exists. Reproduced against a validation workspace: a record accessor is invoked
on a receiver whose type is inferred and never written in the calling file.

```java
// FeatureConfig.java — the record; component 'whitelist' has an implicit accessor whitelist()
public record FeatureConfig(boolean enabled, List<String> whitelist, ...) { ... }

// RequestContext.java — the call site, in a different module. Note: FeatureConfig is NEVER spelled
// here (no import, no explicit type); its type flows in through `var` from a getter chain.
final var config = getConfig().feature();          // config's type FeatureConfig is inferred
return config != null
    && config.enabled()
    && (msisdn == null || !config.whitelist().contains(msisdn));   // <-- missed reference
```

Clicking `whitelist` in the record returns only the declaration; `config.whitelist()` above is not
reported.

### Root cause

The defect is in **candidate planning**, not in reference matching. `ReferenceLocator`/
`ReferenceTarget.matches` would match `config.whitelist()` correctly — but the file is pruned before
javac ever compiles it.

1. The record component is normalised to its public accessor method (`ReferenceTarget.from` →
   `recordAccessorFor`), producing a `METHOD` target with `overrideFamilyBounded = true` and empty
   `overriddenDeclarers` (a record accessor overrides nothing).
2. `ReferenceCandidatePlanner.planMethodCandidates` (`ReferenceCandidatePlanner.java:116-131`) takes
   the override-family narrowing path and calls `narrowToFamily`.
3. `narrowToFamily` (`ReferenceCandidatePlanner.java:153-170`) **intersects** the files that spell the
   member simple name (`whitelist`) with the files that spell a family type's simple name
   (`FeatureConfig`). The call site spells `whitelist` but not `FeatureConfig`, so it is filtered out.

The override-family heuristic assumes a genuine call site textually spells the receiver's static type
name (the doc comment at `ReferenceCandidatePlanner.java:105-114` explicitly relies on
`baseConfig.name()` spelling the type). That assumption breaks whenever the receiver type is never
written: `var` bound from a factory/getter chain, a chained call (`getConfig().feature().whitelist()`),
a generic type variable, or a wildcard capture. The result is a **false negative** — a real reference
silently pruned.

This is not record-specific; any public-method reference has the same blind spot. Records merely make
it common, because config accessors are routinely reached through `var`/chained getters.

### Fix (approach A — drop override-family narrowing for methods)

Method targets now use the **broad simple-name candidate set** — every file that spells the method's
simple name — with no override-family narrowing. `planMethodCandidates`, `overrideFamilyBounded`, and
the `java.lang.Object` special-case are removed from the method path (`overrideFamily` /
`narrowToFamily` remain for fields, constructors, and enum constants).

Rationale: the narrowing was a candidate-pruning optimization, not a correctness mechanism — javac
still decides real matches in `ReferenceLocator`. It was the source of the false negative, and no
purely textual heuristic can tell a genuine call on an unspelled receiver (`config.whitelist()`, want
kept) from an unrelated same-named method (want dropped) without compiling. Reference search is an
explicit, batched, cancellable, progress-reported action, and the batch-compilation path is cheap
enough that compiling every file spelling the name is an acceptable trade for full correctness and a
simpler planner. A member-invocation index (former "approach B") was considered but rejected as it
adds a second index dimension and a position-aware text scan for no correctness gain over A.

Trade-off: a reference search on a very common method name (`get`, `size`, `toString`) compiles more
files than before. Mitigated by batching + cancellation; if a pathological case ever bites, a
high-threshold cap (`size > N ? narrow : broad`) can be reintroduced without an index change.

### Regression targets

- `ReferenceCandidatePlannerTest.planCandidates_methodInvokedOnUnspelledReceiver_includesCallSite`
  (positive — a call on a `var`/unspelled receiver is a candidate)
- `ReferenceCandidatePlannerTest.planCandidates_methodNameNeverSpelled_excludesFile`
  (negative — a file that never spells the method's simple name is not a candidate)

---

## FR-012 — Type references miss a same-package generated file that uses the type without importing it

**Status: done — Target: M2**

Fixed: the same-package candidate filter now also considers the generated-sources root, so a generated builder in the same package (no import) is found. Merged in 4cc7196; regression targets below pass.

### Observed behaviour

Find References on a `@Builder` record **type** does not report the generated builder, even though the
builder plainly references the record. Reproduced against a validation workspace: the record's
generated builder lives in the same package, in the annotation-processor output root, and names the
record without an import.

```java
// FeatureConfig.java — src/main/java, the annotated record
package com.example.app.config;
public record FeatureConfig(boolean enabled, List<String> whitelist, ...) { ... }

// FeatureConfigBuilder.java — target/generated-sources/annotations, SAME package, NO import of the
// record; references the type by simple name only.
package com.example.app.config;
public final class FeatureConfigBuilder {
  public FeatureConfig build() { return new FeatureConfig(...); }   // <-- missed type reference
}
```

References on `FeatureConfig` return the source-tree usages but omit the generated builder.

### Root cause

Candidate-planning prune, not a matching defect. The generated builder **is** in the candidate index
(`ReferenceCandidateIndex.allSourceRoots` indexes `originalGenSourcesDir` alongside `sourceRoots`), so
it appears in `simpleCandidates` for the token `FeatureConfig`. But `planTypeCandidates`
(`ReferenceCandidatePlanner.java:64-103`) keeps a same-package file only through
`isInPackage(path, config.sourceRoots(), packageRel)` (`ReferenceCandidatePlanner.java:101, 177-182`),
which resolves `packageRel` against **`config.sourceRoots()` only** — never
`config.originalGenSourcesDir()`. The builder's parent directory sits under the generated-sources
root, matches no regular source root, and is pruned.

The import-token branch still finds generated files that use an explicit or wildcard import, so only
the **same-package, no-import** shape is lost — which is exactly how the generated builder (and
updater/companion types) reference the record they belong to.

### Proposed fix

Have the same-package filter consider the generated-sources root in addition to the regular source
roots: resolve `packageRel` against `config.sourceRoots()` ∪ `{config.originalGenSourcesDir()}` (when
non-null). Minimal and localized to `planTypeCandidates` / `isInPackage`.

### Regression targets

- `ReferenceCandidatePlannerTest.planCandidates_typeUsedInSamePackageGeneratedSource_includesGeneratedFile`
  (positive — fails before the fix)
- `ReferenceCandidatePlannerTest.planCandidates_typeInGeneratedSourceDifferentPackage_excludesFile`
  (negative — generated-root awareness must still respect package boundaries)

---

## FR-013 — Constructor references select no candidates because the target is keyed on `<init>`

**Status: done — Target: M2**

Fixed: a CONSTRUCTOR target now keys candidate lookup on the declaring type's simple name instead of `<init>`, so `new Type(...)` sites (including the generated builder) are found. Merged in 4cc7196; regression targets below pass.

### Observed behaviour

Find References on a record/class **constructor** returns nothing outside the declaring file — in
particular it omits the generated builder's `new FeatureConfig(...)` call. Reproduced against a
validation workspace:

```java
// FeatureConfigBuilder.java — generated
public FeatureConfig build() {
  return new FeatureConfig(enabled, whitelist, ...);   // <-- missed constructor reference
}
```

### Root cause

Candidate-planning prune. A constructor's `ReferenceTarget.simpleName` is javac's constructor name
**`<init>`** (`ReferenceTarget.from` uses `element.getSimpleName()` for the `CONSTRUCTOR` case,
`ReferenceTarget.java:54-78`). `planCandidates` opens with
`simpleCandidates = index.candidateUris(target.simpleName())` — i.e. `candidateUris("<init>")`.
`<init>` is never an identifier token in any source file, so the set is **empty**, and the guard at
`ReferenceCandidatePlanner.java:31-33` returns `Set.of()` before the constructor branch
(`ReferenceCandidatePlanner.java:49-51`) ever runs. Every constructor reference search therefore
selects zero external candidates; the `new FeatureConfig(...)` call site is never compiled or matched.

### Proposed fix

For a `CONSTRUCTOR` target, key the initial candidate lookup on the **declaring type's simple name**
(derived from `target.qualifiedName()`), not `target.simpleName()`. The existing constructor branch
`narrowToFamily(Set.of(target.qualifiedName()), …)` then bounds the search to files spelling the type
name — the generated builder among them. Pairs with FR-012 so the same-package generated builder both
survives the simple-name lookup and passes the package filter.

### Regression targets

- `ReferenceCandidatePlannerTest.planCandidates_constructorInvokedViaNew_includesCallSite`
  (positive — fails before the fix; target keyed on `<init>`)
- `ReferenceCandidatePlannerTest.planCandidates_constructorOwnerNameNotSpelled_excludesFile`
  (negative — a file that never spells the type stays out)

---

## FR-014 — Find References from a component reference inside a record's compact constructor returns only that one occurrence

**Status: done — Target: M2**

Fixed: `ReferenceTarget.recordAccessorFor` now normalises the implicit canonical-constructor
`PARAMETER` to the component's accessor (as it already did for `RECORD_COMPONENT` and the backing
`FIELD`), so Find References is symmetric from the header and the compact-ctor use. Verified live —
the compact-ctor query now returns all sites. Regression targets below are implemented and passing.

### Observed behaviour

Find References is asymmetric for a record component. Invoked from the component in the record
header it finds every use; invoked from a bare reference to that same component **inside the compact
constructor body** it returns only that one occurrence.

```java
// Config.java — component `bucket` has an implicit accessor bucket()
public record Config(String bucket) {
    public Config {
        check(bucket, "bucket");   // ← Find References here: only this line is reported
    }
    static void check(Object value, String name) {}
    String read() { return bucket; }   // this genuine use is missed from the compact-ctor query
}
```

From the header `bucket`: all uses (backing-field reads, the compact-ctor reference, external
accessor calls). From the compact-ctor `bucket`: a single self-match, and the candidate planner only
even evaluates one candidate (the search is silently file-scoped).

`definition` from the same compact-ctor position resolves correctly to the component; only
`references` collapses. Shares its trigger with the hover defect [EG-045](#eg-045--hover-on-a-component-reference-inside-a-records-compact-constructor-resolves-to-the-callees-parameter).

### Root cause

`SourceAnalysisSession.resolveTarget` resolves the cursor via `SourceLocator.elementAt`, which inside
the compact constructor returns the **implicit canonical-constructor `PARAMETER`** (javac models each
record component as a same-named formal parameter of the canonical constructor). `ReferenceTarget.from`
then calls `recordAccessorFor` (`ReferenceTarget.java:109`), which normalises a `RECORD_COMPONENT`
element and a record's backing `FIELD` to the public accessor — **but not the canonical-constructor
`PARAMETER`**. So `from` falls through to the `default` branch and builds a `kind=PARAMETER`,
`scope=DECLARING_FILE` target that matches only same-named parameters in the one file → the single
self-hit.

The matching side already handles this member: `matchesRecordComponentMember`
(`ReferenceTarget.java:246`) explicitly counts "the canonical constructor `PARAMETER` that javac
reports for compact/canonical constructor bodies" — but that only fires when the target was built
**from the accessor**. The gap is purely entry-point normalisation, which is why the search works
from the declaration and not from the use. Every existing record test in `ReferenceLocatorTest` builds
its target from the header, so the asymmetry was never exercised.

### Proposed fix

Extend `recordAccessorFor` (threading `Types` through its single caller in `from`) to also normalise a
canonical-constructor `PARAMETER` of a record to its component's accessor, reusing the existing
`enclosingRecord`, `isCanonicalConstructorParameter`, and `componentNamed` helpers. Single-class change
in `ReferenceTarget`; no public API change, no new abstraction. Makes references (and the shared
`resolveTarget` consumers) symmetric from either end.

### Probe commands

```bash
# `bucket,` (component + comma) lands the cursor on the compact-constructor use, not the header.
# Expected: all reference sites. Bug: a single self-match.
printf 'refs "bucket,"\n' | python3 dev/explore.py /path/to/workspace/.../Config.java
```

### Regression targets

- `ReferenceLocatorTest.recordComponent_fromCompactConstructorParameterUse_findsAllReferences`
  (positive — target built from the compact-ctor use finds the same sites as from the header)
- `ReferenceLocatorTest.recordComponent_fromNonCanonicalConstructorParameter_notNormalized`
  (negative — a non-canonical constructor's same-named parameter stays file-scoped, not normalised)

---

# Completion (CQ) — resolved

## CQ-0043 — Argument-position type filtering excludes boolean returns but lets other mismatched types through

Status: done — Target: M2.

Re-evaluation on 2026-07-02 found this gap was already resolved by prior completion-ranking work.
Boolean-returning methods are present at non-boolean argument slots and are demoted behind directly
assignable candidates instead of being deleted.
Boolean literals remain filtered for incompatible non-boolean expected types, which matches the
completion expectations.

Regression coverage:

- `CompletionArgumentTest.argumentPosition_referenceTypeParam_booleansDemoted`

---

## CQ-0047 — Member access inside a void-returning lambda body offers void methods

Status: done — Target: M2.

Member-access completion inside a lambda whose functional-interface method returns `void` now treats
the site as a statement context.
This prevents the enclosing argument type, such as `Runnable`, from making completion value-sensitive
and filtering out void-returning receiver methods.

Regression coverage:

- `CompletionMemberAccessTest.memberAccess_insideRunnableLambdaBody_offersVoidMethods`

---

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

**Status: done — Target: M2**

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

### Resolution (2026-07-04)

`WorkspaceSession.outgoingCallsFuture` now passes `typeSourceDirs()` (reactor + JDK-module +
dependency source dirs) instead of `workspace.allSourceRoots()` (reactor-only) — reusing the exact
accessor already used by `workspaceSymbol` and the type-hierarchy features. `DefinitionLocator.findSourceFile`
then resolves callees under `~/.cache/lathe/deps/` and `~/.cache/lathe/jdks/`, and the callee items
carry the cache path as their `uri`, matching `definition`.

Validated before/after with `dev/explore.py callees` on `DobServerConfig.java`'s compact
constructor: reactor-only roots returned **0** callees (all its callees are JDK/dep), the fix returns
**15**, including `check`/`notNull`/`validate` under the extracted `validcheck` dependency and the JDK
`Objects`/`Duration`/etc. sources.

### Regression targets

`LspSmokeTest.outgoingCalls_calleeInCachedJdkSource_returnsJdkCallee` (invoker `multi-module` IT) —
outgoing calls on `StringUtils.upper`, whose sole callee is `String.toUpperCase`, must include that
JDK callee with a `/jdks/` cache URI; a reactor-only search root returns empty. This end-to-end
placement mirrors how the external-source Find References scope (FR-004) is tested, exercising the
real `WorkspaceSession` against a workspace with extracted dep/JDK sources (a service-level unit
harness for `WorkspaceSession` does not exist).

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

**Status: done — Target: M2**

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

### Resolution (2026-07-04)

New `OverrideCompletionProvider`: resolves the enclosing `TypeElement` from the cursor scope
(`TypeResolver.resolveScope(...).getEnclosingClass()`), enumerates `Elements.getAllMembers`, keeps
inherited `METHOD`s that are overridable (not `final`/`static`/`private`, not already declared in the
class — `getAllMembers` returns the class's own version for overridden methods, which the
enclosing-element check drops), matching the typed prefix (incl. `Object`'s `toString`/`equals`/
`hashCode`), and renders each as an `@Override` stub candidate. `CompletionEngine.mergeOverrideCandidates`
runs once after the context switch and merges these into the outcome — gated to a class-body
member-start slot (`TYPE_REFERENCE`/`VARIABLE_DECLARATION`, not a real name slot, enclosing class
present, not inside a method) so annotation value positions and new-member name slots are untouched.

Stub text is shared with the implement-missing-methods code action via a new `MethodStubRenderer`
(extracted from `MissingMethodImplProvider.buildStub` — the code action now delegates, so the two
cannot drift). Validated with `dev/explore.py` on `ThailandCountryAdapter`: `toString` in the class
body offers `@Override public String toString() { throw new UnsupportedOperationException(); }`,
replacing the typed prefix.

**v1 limitations (recorded, follow-ups):**
- **No auto-import** of stub types — rendered as simple names; types outside `java.lang`/same package
  need a manual import. `CompletionCandidate` carries a single `importEdit`; multi-import needs
  `additionalTextEdits` plumbing. This is the natural first follow-on.
- **No method-level type parameters** — overriding `<T> T m(...)` omits the leading `<T>`.
- Body is always `throw new UnsupportedOperationException();` (no `super.m(...)` for concrete
  overrides); visibility always `public`; `throws` omitted. Continuation-line indentation is
  client-reindented (fixed relative indent otherwise).

### Probe commands

```bash
python3 dev/explore.py \
    /workspace/app-server/src/main/java/com/example/app/server/operator/dummy/DummyAdapter.java \
    inject "toString" at 46 expect toString
```

### Regression targets

- `CompletionOverrideTest.completion_objectMethodPrefix_offersToStringOverride`
- `CompletionOverrideTest.completion_methodPrefixInClassBody_offersOverrideStub`
- `CompletionOverrideTest.completion_insideMethodBody_noOverrideStub` (negative: not inside a method)
- `CompletionOverrideTest.completion_finalInheritedMethod_notOffered` (negative: `final` excluded)

---

## EG-016 — Annotation-member completion missing

**Status: done — Target: M2**

Resolved: validated as implemented. `AnnotationCompletionProvider` (wired into `CompletionEngine`
for the `ANNOTATION_ARGUMENT` / `ANNOTATION_ARGUMENT_VALUE` sentinel contexts) offers the annotation
type's element methods as `name = ` items and, for enum-valued elements, the permitted constants.
Validated with `dev/explore.py` on `HasMoreItems.java`: `inject "@Schema("` returns 73 element-name
items (`description`, `required`, `requiredMode`, …). An earlier false-negative probe used
`@JsonProperty(` in a file that did not import `JsonProperty`, so the type could not resolve.

Regression targets (existing): `CompletionAnnotationTest.annotationArgument_emptyList_suggestsElementNames`
(element names) and `CompletionAnnotationTest.annotationArgumentValue_enumElement_offersEnumConstants`
(enum constants), plus siblings.

Not covered here (see EG-038): a `Class`-typed value element offers no type candidates.

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

**Status: done — Target: M2**

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
`WorkspaceTypeIndex.isReactorType` origin information from EG-006.
Reactor entries should outrank dependency and JDK entries for an equal prefix match.

### Resolution (2026-07-04)

`TypeReferenceCompleter.typeCandidateComparator` was made reactor-aware. Verified first that this
matches modern IDEs: IntelliJ ranks project code above JDK/library via proximity weighers
(`sdkOrLibrary`, `sameModule`) applied *beneath* match quality (JDT relies mainly on expected-type
and case/prefix, with no strong project boost — so IntelliJ is the model here). Reactor origin is
therefore a **tiebreaker beneath match quality**, not an override.

Because Lathe lacks the usage statistics IDEs use to keep ubiquitous JDK types on top, a blanket
reactor boost would bury `java.util.List` etc. under obscure project types (chosen approach: keep a
common-platform tier above reactor). The comparator now grades candidates by `originRank` after the
prefix-match key:

- rank 0 — `java.lang` (exact; auto-imported, most ubiquitous)
- rank 1 — other common platform packages, **exact match** (`java.util`, `java.io`, `java.time`,
  `java.nio`, `java.math`)
- rank 2 — reactor-local types (`WorkspaceTypeIndex.isReactorType`)
- rank 3 — everything else (dependency / other JDK)

then shorter-FQN, then lexicographic. Exact match matters: a live probe showed prefix-matching
`java.lang.` wrongly elevated specialized subpackages (`java.lang.management.OperatingSystemMXBean`,
`java.lang.classfile.…`) above reactor types; only the exact `java.lang`/`java.util`/… packages get
the platform tier, subpackages fall to rank 3.

Confirmed on `app-server`: for prefix `Oper`, `com.example.app.*` reactor types now
lead, above JDK `java.lang.management`/`java.lang.classfile` types.

### Probe commands

```bash
python3 dev/explore.py /path/to/Scratch.java inject "Object o = new Oper"
```

### Regression targets

- `CompletionTypeRankingTest.completion_typePrefix_ranksReactorTypeFirst` (reactor above dependency;
  verified failing without the reactor tier)
- `CompletionTypeRankingTest.completion_typePrefix_commonPlatformOutranksReactor` (java.util above
  reactor)
- `CompletionTypeRankingTest.completion_typePrefix_javaLangSubpackageDoesNotOutrankReactor`
  (java.lang subpackages are not platform-tier)
- `CompletionTypeIndexTest.typeIndex_candidates_javaLangRanksBeforeOtherPackages` (java.lang above
  other platform packages — the rank 0 vs 1 boundary)

---

## EG-022 — Sealed-type `switch`/`case` pattern completion offers arbitrary types

**Status: done — Target: M2**

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

### Resolution (2026-07-04)

`CompletionEngine.sealedCaseLabelCandidates` mirrors the enum-case path: in a `CASE_LABEL` slot whose
expected (selector) type is a sealed `DECLARED` type, it offers the type's
`getPermittedSubclasses()` as type-pattern labels (insert `SubType subType ->`). The general-type
fallback (`isCaseLabelTypePattern`) is suppressed when sealed candidates exist, and the uppercase
type-index merge is skipped for the sealed case-label so each permitted subtype appears once. Enum
case completion is unchanged.

Validated in a compiled sealed switch: `case Cir` offers a single `Circle` (`permitted subtype`),
not the previous 112-type dump. Note: the explorer's `inject` cannot validate a *freshly injected*
switch because it does not reattribute (stale cached analysis → selector type unresolved); the
CompletionCaseTest fixtures and a compiled scratch file exercise the real (recompiled) editor flow.

**v1 limitations (recorded):**
- **Direct permitted subclasses only** — no recursion into nested sealed permits (e.g.
  `ProvisionResponse` is itself sealed; its leaves are not expanded).
- **No auto-import** of the subtype — rendered as a simple name (same limitation family as EG-015);
  same-package works, otherwise a manual import is needed.

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

**Status: done — Target: M2**

Resolved by the CQ-0049 fix (2026-07-04): both type-index completion paths now gate candidates on
`Trees.isAccessible(scope, typeElement)`, so an observable-but-unreadable type (here
`com.sun.management.OperatingSystemMXBean` in `jdk.management`, not read by the current module) is no
longer offered. The mechanism is type-agnostic and was live-verified on the equivalent JDK-module case
(`javax.swing.JButton` / `java.desktop`) on `app-core`; regression coverage lives on CQ-0049.

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

**Status: done — Target: M2**

Implemented in `7fe3e4b` (`JdkSourceResolver` falls back to the running JVM's `java.home` and
canonicalizes via `toRealPath()`). Regression: `JdkSourceResolverTest.resolve_javaHomeUnset_fallsBackToRunningJavaHome`,
`JdkSourceResolverTest.resolve_symlinkedHome_resolvesToRealPath`.

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

**Status: done — Target: M2**

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

### Resolution (2026-07-04)

`UnusedDeclarationScanner` gained a `visitAssignment` override (reference phase). When the LHS is a
pure write target — a bare `IdentifierTree` or a `this`-qualified `MemberSelectTree` — it scans only
the right-hand side (whose reads still count) and skips the target, so a write no longer marks the
declaration used. Every other position is unchanged: `a.f = x` / `arr[i] = x` fall through to the
default traversal (reading the qualifier/index and marking the member), and `+=` / `++` still read
before writing. This keeps false positives near zero at the cost of leaving `++`/`--`-only variables
conservatively counted as used. Scope is `LOCAL_VARIABLE` and `PRIVATE_FIELD` only, both confined to
a single compilation unit, so no cross-file analysis is needed.

### Regression targets

- `UnusedDeclarationScannerTest.compile_localVariableAssignedNeverRead_reportsHint` (write-only local;
  verified failing without the fix)
- `UnusedDeclarationScannerTest.compile_localVariableAssignedThenRead_noHint` (read after assignment)
- `UnusedDeclarationScannerTest.compile_localVariableCompoundAssignment_noHint` (`+=` reads first)
- `UnusedDeclarationScannerTest.compile_privateFieldWriteOnly_reportsHint` (write-only `this.field`;
  verified failing without the fix)

The field-read negative (a read keeps a private field used) is already covered by the existing
`UnusedDeclarationScannerTest.compile_privateFieldReadInMethod_noHint`.

---

## EG-036 — Write-only unused hint reads "Unused", not "assigned but never read"

**Status: done — Target: M2**

### Observed behaviour

After EG-035, a variable that is assigned but never read is flagged, but with the same generic
message as one never referenced at all (`Unused local variable 'count'`). IntelliJ distinguishes the
two — *"Variable 'x' is never used"* vs *"Variable 'x' is assigned but never accessed"* — which tells
the developer whether removing the declaration also means removing live-looking assignments.

### Resolution (2026-07-04)

`UnusedDeclarationScanner` records the target of each suppressed pure-write assignment in an
`assignedTargets` set (populated in `visitAssignment`, kept out of the `referenced*` sets so it still
counts as unused). `unusedDiag` then words the hint by that flag:

- assigned but never read → `"<kind> '<name>' is assigned but never read"`
- never referenced → the existing `"Unused <kind> '<name>'"`

The `lathe.unused` code and `Unnecessary` tag are unchanged, so rendering and any quick fix are
unaffected — only the human-readable text branches. A declaration with only an initializer and no
later assignment (`int x = 0;`) keeps the "Unused" wording, matching IntelliJ (which reserves
"assigned but never accessed" for explicit assignments).

### Regression targets

- `UnusedDeclarationScannerTest.compile_localVariableAssignedNeverRead_reportsHint` (asserts the
  "assigned but never read" wording)
- `UnusedDeclarationScannerTest.compile_privateFieldWriteOnly_reportsHint` (same, for a field)
- `UnusedDeclarationScannerTest.unused_localVariable_messageNamesVariableAndKind` (never-referenced,
  incl. an initialized-only `int x = 42;`, keeps the "Unused" wording)

---

## EG-037 — Body after a wrapped method/constructor declaration is over-indented

**Status: done — Target: M2 (regression)**

### Observed behaviour

When a method or constructor declaration wraps its parameters onto a second line, the first line of
the body (pressing Enter to start coding) indents one continuation level too deep. Validated with the
Neovim indenter (`lathe-maven-plugin/src/main/neovim/lua/lathe/indent.lua`) on the text-heuristic
(mid-edit) path:

```java
void method(
    int a) {
        // body lands at column 6, should be 2
}
```

Headless indent computation returned 6 for the body line (single-line `void method() {` correctly
returned 2).

### Root cause

`continuation_indent`'s `ends_with_block_opener` branch returned `prev_indent + BLOCK_INDENT`. When
the `{` sits at the end of a wrapped declaration's continuation line (`    int a) {`, indent 4), that
adds 2 to the continuation depth → 6, instead of anchoring to the statement's first line
(`void method(` at 0) → 2.

### Fix

Anchor the block body to the statement start:

```lua
if ends_with_block_opener(prev) then
  return statement_start_indent(prev_lnum) + BLOCK_INDENT
end
```

`statement_start_indent` (added for EG-030) walks back across continuation lines; for a non-wrapped
opener it equals `prev_indent`, so the common single-line case, nested blocks, and class bodies are
unchanged.

### Regression targets

`lathe-maven-plugin/src/test/neovim/indent_spec.lua` — two fixtures: body after a wrapped single-param
declaration, and after a multi-line multi-param declaration (which forces `statement_start_indent` to
walk back across several continuation lines). Both expect the body at the declaration's base
indent + 2. A constructor fixture is omitted deliberately: the indenter is text-based and cannot
distinguish a method from a constructor (`Foo(` and `void method(` take the identical path), so it
would add no coverage.

---

## EG-038 — Annotation value completion offers no type candidates for `Class`-typed elements

**Status: done — Target: M2**

### Observed behaviour

Completion in the value position of an annotation whose element is `Class`-typed offers no type
candidates. The canonical case is a JUnit `@ExtendWith`:

```
inject "@ExtendWith(Mockito"   → (no completions)
complete after "@ExtendWith("  → only `null` [Keyword]
```

`org.mockito.junit.jupiter.MockitoExtension` is present in the workspace type index (source extracted
under `~/.cache/lathe/deps/org.mockito:mockito-junit-jupiter:...`) and the files compile cleanly, so
the type resolves — it is simply never offered. A developer typing an extension class inside
`@ExtendWith(...)` (very common in test code) gets nothing.

This is distinct from EG-016 (element names + enum constants), which is implemented and works. The
difference: an annotation with a `value` element is classified as `ANNOTATION_ARGUMENT_VALUE`, and
`@ExtendWith`'s `value` is `Class<? extends Extension>[]`.

### Root cause

`AnnotationCompletionProvider.completeArgumentValue` handles only enum-valued elements (enum constants)
and keywords. It has no branch for a `Class`-typed (or otherwise reference-typed) value element, so
for `@ExtendWith(` it computes the expected type `Class<? extends Extension>[]`, finds no enum
constants, and returns only the `null` keyword — no type candidates.

### Proposed fix

When the annotation element's (array-component) type is `java.lang.Class` (raw or `Class<? extends
Bound>`), offer type-reference candidates in the value position, reusing the existing type-index /
type-reference completion path rather than adding a parallel one.

### Resolution (2026-07-04)

`AnnotationCompletionProvider.isClassValuedElement` detects a `java.lang.Class` value element
(unwrapping an array component). When true, `CompletionEngine.completeAnnotationArgumentValue` runs
the existing `typeReferenceCompleter.completeSimpleNameTypeReferenceWithLang` and merges its type
candidates with the value keywords — the same path the annotation-name (`ANNOTATION_CONTEXT`) and
type-reference cases already use, so no parallel machinery. Validated with `dev/explore.py`:
`inject "@ExtendWith(Mockito"` now returns 27 type candidates including
`MockitoExtension  org.mockito.junit.jupiter.MockitoExtension`.

Deliberately unbounded: candidates are filtered by the typed prefix but not by the wildcard bound
(`Class<? extends Extension>` still offers any matching type), matching how type-reference completion
behaves elsewhere. Bound-filtering to the declared upper bound is a possible future refinement.

### Probe commands

```bash
python3 dev/explore.py /path/to/SomethingTest.java complete after "@ExtendWith("
python3 dev/explore.py /path/to/SomethingTest.java inject "@ExtendWith(Mockito" expect MockitoExtension
```

### Regression targets

- `CompletionAnnotationTest.annotationValue_classTypedElement_offersTypeCandidates` — a
  `Class<?>`-valued element offers a resolvable in-scope type in value position (verified failing
  before the fix, passing after).

---

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


## FR-008 — Find references on a record component returns nothing

Status: done — Target: M2.

### Observed behaviour

Invoking `textDocument/references` on a record component in the record header returns no results,
even when the component's generated accessor is called across the reactor.

Reduced from a private reactor workspace (identifiers genericized). Given a record

```java
public record AppServerConfig(String bucket, ...) { }
```

and callers such as `AppServer.java`:

```java
new ReportService(config.bucket(), ...);
```

probing the `bucket` component declaration:

```text
def  <line>:<col>   -> resolves to the component declaration (correct)
refs <line>:<col>   -> progress "0 / 1", then "(no references found)"
```

Definition resolves correctly, but references finds nothing despite multiple `config.bucket()`
accessor call sites.

### Root cause

`SourceLocator.elementAt` resolves the cursor on a record-header component to an `Element` of kind
`RECORD_COMPONENT`. In `ReferenceTarget.from`, `RECORD_COMPONENT` has no explicit case and falls
through to the `default` branch, which stores `kind = RECORD_COMPONENT`.

`ReferenceTarget.matches` then rejects every candidate at its first guard,
`element.getKind() != kind`: accessor invocation sites resolve to an `ExecutableElement` of kind
`METHOD` and the backing field to a `VariableElement` of kind `FIELD`, neither of which equals
`RECORD_COMPONENT`. `resolveMethodElement` also short-circuits (`kind != METHOD`), so override-aware
matching never runs. The candidate index still finds the file by name (hence `0 / 1`), but zero
usages match inside it.

### Proposed fix

In `ReferenceTarget.from`, normalise a record component to its generated accessor via a
`recordAccessorFor` helper that covers both the `RECORD_COMPONENT` element and the backing `FIELD`
that javac actually reports for the header (`RecordComponentElement.getAccessor()`). The target then
reuses the existing `METHOD` path: broad candidate discovery, descriptor matching, and
public-accessor reactor scope all apply, so `x.name()` accessor invocations match reactor-wide —
fixing the private-field `DECLARING_FILE` scope that would otherwise prevent any cross-file match.

Other members backing the same component are the same logical member, so `ReferenceTarget.matches`
additionally accepts them via `matchesRecordComponentMember`:

- the backing `FIELD` (in-body reads and writes), and
- the canonical constructor `PARAMETER` that javac reports for compact/canonical constructor bodies
  (`Config { bucket = bucket.trim(); }`).

A same-named `PARAMETER` of a *non-canonical* constructor is a distinct member and is excluded by
comparing the constructor's parameter types against the record components in order. A same-named
component in a *different* record is excluded by the enclosing binary-name check.

Because the backing field and the canonical-constructor parameter share the component's header source
range, `includeDeclaration` would report that single declaration once per synthetic member.
`ReferenceLocator.addMatch` now enforces the invariant that a source range is never reported twice, so
the header declaration appears exactly once.

### Regression targets

- `ReferenceLocatorTest.recordComponent_accessorInvocation_reportedThroughAccessor`
- `ReferenceLocatorTest.recordComponent_backingFieldRead_countedAlongsideAccessor`
- `ReferenceLocatorTest.recordComponent_compactConstructorUses_counted`
- `ReferenceLocatorTest.recordComponent_nonCanonicalConstructorParameter_notMatched`
- `ReferenceLocatorTest.recordComponent_includeDeclaration_reportsHeaderExactlyOnce`
- `ReferenceLocatorTest.recordComponent_sameNameComponentInOtherRecord_notMatched`
- `ReferenceLocatorTest.recordComponent_scope_reactorModulesFromPublicAccessor`


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

### Resolution

Resolved 2026-06-28 (`fix(CA-4): offer import actions for reactor types not yet on the classpath`)
in three parts:

1. `WorkspaceTypeIndex.isReactorType(fqName)` exposes the tracked reactor binary-name set.
2. `ImportQuickFixProvider` trusts a known reactor entry when `getTypeElement()` returns null and
   offers the import without the accessibility guard, preserving that guard for dependency/JDK
   entries.
3. `WorkspaceSession.codeActionFuture()` enriches the type-index snapshot with entries derived from
   already-compiled open files via `CompilationUnitTree.getTypeDecls()` (no text parsing).

This covers types from a prior sync and types declared in an open, already-compiled file. The
residual case — types created or renamed in a **closed** file, discoverable today only after a
Maven sync — is folded into the broader source/branch-switch staleness gap WS-1 (see
[gaps.md](gaps.md)) rather than tracked as a code-action bug.

---

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
Status: done
Target: M2
Tier: Basic
Discovery: 2025-07-25, AppServerConfig.java compact constructor (sample-workspace)

Resolved (2026-07-04): validated as no longer reproducing. Re-probing the compact constructor's
overwritten closing brace on the sample workspace now shows `parsed valid=true class=DobServerConfig
method=<init>` with context-aware completions (record component accessors for `n`, the full
`ValidCheck.check()` receiver type for member access) — where the gap recorded `valid=false
class=null method=null` and 0 items. Fixed incidentally by intervening sentinel/parse-recovery
improvements (the deferral note already observed that simple injected cases were recovered by
`forwardScan`; that recovery now covers this brace-count context too).

Regression targets:
- `CompletionSimpleNameTest.simpleName_overwrittenConstructorCloseBrace_recoversEnclosingScope` — a
  reduced compact-constructor fixture whose closing brace is typed over; asserts the enclosing scope
  is recovered and the component is offered. Lightweight guard only: per the note below the hard
  failure needed a complex brace-count context that does not reduce to a minimal fixture, so the
  documented `DobServerConfig.java` explorer probe remains the faithful reproduction.

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
Status: done
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
though the enclosing module never `requires java.desktop`. Confirmed on the `sample-workspace`
workspace: in `app-core` (whose `module-info.java` does not read `java.desktop`) Swing and other
unread-module types complete via the type-index path, while a real `import javax.swing.JButton;`
correctly fails to compile. `java.desktop` is pulled into the module graph by a dependency's
non-transitive `requires`, so it is *observable* but not *readable* by `app-core`.

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

Resolved: 2026-07-04. Two leak sites shared the same root cause and were both gated on
`Trees.isAccessible(scope, typeElement)`:
1. `TypeIndexValidator` (plain type-reference via `TypeReferenceCompleter.completeSimpleNameTypeReference`
   and static-member-fit via `CompletionEngine.staticMemberFitCandidates`) — the validator now takes the
   completion `Scope` and enforces readability.
2. `TypeReferenceCompleter.resolvesToInstantiableSubtype` (the `new X` / expected-type constructor-subtype
   path, which never used the validator) — gained the same gate. The first pass missed this because the
   original regression test only exercised the plain type-reference form; a live probe of `new JBut` on
   `app-core` surfaced it.

Regression targets:
- `CompletionTypeIndexTest.typeIndex_jpmsObservableButUnreadableModule_doesNotSuggestIndexedType`
  (enabled — plain type-reference path; reproduces via `--add-modules java.desktop` on a module that
  does not `requires` it)
- `CompletionTypeIndexTest.typeIndex_constructorSubtype_jpmsUnreadableModule_doesNotSuggestSubtype`
  (the `new X` subtype path; verified failing without the fix)
- `CompletionTypeIndexTest.typeIndex_constructorSubtype_jpmsReadablePackage_suggestsSubtype`
  (guard: readable subtype still offered)
- `CompletionTypeIndexTest.typeIndex_jpmsReadablePackage_suggestsIndexedType` (guard: `requires
  java.desktop` still offers `JButton`)
- `CompletionTypeIndexTest.typeIndex_platformType_survivesValidator_jpmsModule` (guard: `java.base`
  types survive)

Notes:
The member-access and import paths already enforce readability via `Trees.isAccessible`; only the two
type-index paths above skipped it. The unit test that previously "covered" this
(`typeIndex_jpmsUnreadablePackage_doesNotSuggestIndexedType`) passed only because its minimal module
graph never made `java.desktop` observable, so `getTypeElement` returned `null` for the wrong reason
— it never exercised the observable-but-unreadable case that occurs in real multi-module workspaces.

---

## CQ-0011 — Constructor invocation keywords can be offered when an explicit invocation already exists

ID: CQ-0011
Status: non-goal
Target: backlog
Tier: semantic
Failure mode: invalid-keyword-candidate
Owner component: KeywordProvider / SentinelParser

Closed (2026-07-04) as non-goal: suppressing the `this`/`super` keyword to avoid a second explicit
invocation is the wrong treatment (see re-triage below). The only valid residual — a
`this(...)`/`super(...)` call-shape snippet offered at the legal first-statement slot — is a separate
future completion feature, not this suppression gap.

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

---

## CQ-0050 — Member-access completion crashes when its result feeds a typed value slot

ID: CQ-0050
Status: done
Tier: typed
Failure mode: server-side crash (empty completion)
Owner component: CompletionCandidateRanker

Discovered by exploration probing of a JAX-RS resource: `@Produces(MediaType.<caret>APPLICATION_JSON)`
returned no completions. Context detection was correct (`sentinelCtx=MEMBER_ACCESS`, receiver
resolved to `jakarta.ws.rs.core.MediaType`); the ranker then crashed and the empty result surfaced
via the `WorkspaceSession` completion catch — so it degraded silently ("no completions", no visible
error).

### Root cause

`CompletionCandidateRanker.sortText` used `context.analysis().types().isAssignable(candidate
.valueType(), type)` as a value-slot ranking tie-break, reached only when `expectedValue` is an
`ExpectedValue.Type` (e.g. an annotation element's declared type). `JavacTypes.isAssignable` throws
`IllegalArgumentException` (via `validateTypeNotIn`) when handed a mirror whose kind is not a value
type — a candidate whose `valueType()` is a package/executable/module/etc. mirror. That reached the
ranker on the **attributed, complete** member-access path (an existing identifier after the cursor,
as in real code), which is why an incomplete sentinel probe (`MediaType.<caret>` with nothing after)
did not reproduce it.

```
java.lang.IllegalArgumentException
  at com.sun.tools.javac.model.JavacTypes.validateTypeNotIn(JavacTypes.java:326)
  at com.sun.tools.javac.model.JavacTypes.isAssignable(JavacTypes.java:107)
  at CompletionCandidateRanker.sortText(CompletionCandidateRanker.java:62)
```

### Resolution

The assignability check is a best-effort ranking signal, so it must never abort completion. Extracted
`assignableToExpected(valueType, expected, context)`: returns `false` for a null `valueType` and
catches `IllegalArgumentException` from `isAssignable` (a non-value-type candidate is simply not
assignable to a value slot). Single-class change; no behavior change beyond not crashing.

Distinct from the resolved **CQ-0001** (enum-typed annotation values → enum constants) and **EG-016**
(annotation element-name completion): this was a ranker crash on a member select whose result feeds a
typed slot, not an annotation routing choice — it can occur in any `ExpectedValue.Type` slot, with the
annotation value being the observed trigger.

Verified live after the fix: `@Produces(MediaType.<caret>)` returns the 34 `MediaType` members.

### Regression target

- `CompletionAnnotationTest.annotationValue_memberAccessOnNonEnumType_offersConstants`
  (`@SuppressWarnings(java.io.File.<caret>separator)` — JDK-only, reproduces the crash without a
  third-party dependency).

---

## EG-040 — Attributed-analysis retention is unbounded; heavy sessions can OOM and kill the server

**Status: done — Target: M2.** Fixed with an event-loop LRU that bounds interactive attributed-analysis
retention at 100 open-document analyses and delegates eviction to the owning module worker.

### Observed behaviour

Each open file's attributed javac `Context` is cached (`SourceAnalysisSession.cache`) and evicted only
on `didClose`, so retained heap grows ~linearly with the number of open files — measured at ~29–31 MB
per open file (open + member-access completion) against a large private workspace. Under an abusive
sweep (~210 open files plus back-to-back reference searches) the JVM hit a fatal `Error`, and
`CompilationWorker`'s "treat `Error` as fatal" path (`processTerminator.accept(FATAL_EXIT_STATUS)`)
terminated the **whole server** — every LSP feature lost until the client relaunches.

Normal editing (a few dozen buffers ≈ <1–2 GB) stays well within the ergonomic heap (~25% of RAM;
≥8 GB on a ≥32 GB workstation), so this bites only pathological / bulk-access sessions.

### Root cause

One javac `Context` retained per open file, unshareable (javac symbols are per-`Context`).
Retention is bounded by open-document count, not by any cap.
See the analysis in the deferred design
[lathe-analysis-cache-bounding.md](../potential/lathe-analysis-cache-bounding.md).

### Resolution

`WorkspaceSession` owns an event-loop-confined `AnalysisLru` over open-document URIs.
On overflow, the event loop removes the eldest URI from the LRU and delegates the actual analysis drop through
`WorkspaceModuleRegistry.dropFromAllCaches(uri)`, so javac-backed objects remain confined to module workers.
Cache-only readers now recompile on miss:
`SourceAnalysisSession.resolve()` uses `ensureAttributedAnalysis(...)`, and semantic tokens receive current content
and rebuild when the cached version is missing or stale.

The disk-candidate implementation-search leak is also fixed:
closed candidate files use a transient FAST compile path and do not populate the interactive analysis cache.
The cap was validated with 300-file probes against Helidon and import-heavy Dropwizard test classes;
Dropwizard stayed stable with semantic tokens, hover, and definition requests after eviction.
Raising the JVM heap can still provide more headroom for transient compile/search spikes
(the M3 `LATHE_JVM_OPTS` knob, [lathe-launcher-jvm-opts.md](../planned/lathe-launcher-jvm-opts.md),
is the planned first-class way; `JAVA_TOOL_OPTIONS=-Xmx…` works today).

### Regression targets

- `AnalysisLruTest.touch_beyondCap_returnsEldest`
- `SourceAnalysisSessionTest.semanticTokens_afterEviction_recompilesAndReturnsTokens`
- `SourceAnalysisSessionTest.semanticTokens_versionMismatch_recompiles`
- `MethodImplementationTest.methodImplementationsTransient_candidateFile_doesNotCacheAnalysis`

---

## FR-011 — `builder()` reference search surfaces test hits — verified correct, not a defect

**Status: non-goal — verified correct behaviour; breadth cost tracked by FR-009**

### Observed behaviour

Find References on a generated `@Builder` factory `builder()` returned matches in test sources that
appeared unexpected, while instance setters (`amount(BigDecimal)`, `taxAmount(BigDecimal)`) on the
same builder returned zero references.

### Investigation (resolved)

Reproduced against the generated builder of an `@Builder` record, searching each member from its
declaration with reactor scope.
Every result was correct:

| Symbol | Hits | Verdict |
|---|---|---|
| `builder()` (static factory) | 5, all in tests | genuine cross-module callers |
| `kind(Kind)` (instance setter) | 5, all in tests | genuine cross-module callers |
| `amount(BigDecimal)` (instance setter) | 0 | no callers exist |
| `taxAmount(BigDecimal)` (instance setter) | 0 | no callers exist |

The confusion had two sources, both correct matcher behaviour:
(1) the record's component **accessor** `amount()` and the builder's **setter** `amount(BigDecimal)`
share a simple name but differ by owner + descriptor, so they do not cross-match;
(2) a sibling generated builder exposes identically-named setters, and its call sites correctly do
not match the first builder's methods.
The zero-reference setters simply have no callers — every chain of the builder under test only calls
`.type(...).build()`.
Instance-setter search from a generated source works:
`type()` returned exactly its five call sites cross-module.

No matching defect exists.
The only real cost — hundreds of files compiled to return ≤5 hits — is the candidate-breadth problem tracked by
**FR-009**.

### Probe commands

```bash
# From the generated builder, each search returns exactly the genuine call sites:
printf 'refs "kind(Kind"\n' \
  | python3 dev/explore.py --workspace /path/to/workspace /path/to/.../SomeRecordBuilder.java
```

### Regression targets

None — no code change.
Candidate-breadth coverage is under FR-009.
