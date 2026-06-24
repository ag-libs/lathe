# Lathe — Call Hierarchy Design

This document is the implementation design for `textDocument/prepareCallHierarchy`,
`callHierarchy/incomingCalls`, and `callHierarchy/outgoingCalls`.
Targeted at M1.

---

## 1. Goal

Call hierarchy answers two editor questions:

- **Incoming calls** (`gri` / `vim.lsp.buf.incoming_calls()`): who calls this method?
- **Outgoing calls** (`gro` / `vim.lsp.buf.outgoing_calls()`): what does this method call?

The feature reuses the same symbol identity, candidate index, and scope planning already in
place for Find References.
No new architectural components are required.

Incoming calls should be implemented as a specialized Find References search.
The implementation should prefer DRY reuse of the existing reference candidate pipeline over a second parallel fan-out path,
while still avoiding a new scheduler, engine framework, or public abstraction.

---

## 2. LSP Protocol

Three requests, always in this order:

1. **`textDocument/prepareCallHierarchy`** — map a cursor position to a
   `CallHierarchyItem[]` (typically length 1, empty if the cursor is not on a
   method or constructor).
   The item carries an opaque `data` payload used by the next two requests.
2. **`callHierarchy/incomingCalls`** — take a `CallHierarchyItem` and return
   `CallHierarchyIncomingCall[]`.
   Each entry pairs a *caller* `CallHierarchyItem` with the `Range[]` of call sites within
   that caller.
3. **`callHierarchy/outgoingCalls`** — take a `CallHierarchyItem` and return
   `CallHierarchyOutgoingCall[]`.
   Each entry pairs a *callee* `CallHierarchyItem` with the `Range[]` of call sites within
   the target method's body.

---

## 3. Item Data

`CallHierarchyItem.data` carries the information needed to reconstruct the symbol identity and
route subsequent requests without re-attributing the source file.

```java
record CallHierarchyItemData(
    String ownerBinaryName,          // binary name of the declaring class
    String methodName,               // simple method/constructor name
    String erasedDescriptor,         // erased parameter descriptor, e.g. "(java.lang.String,int)"
    ElementKind kind,                // METHOD or CONSTRUCTOR
    String routingUri,               // file:// URI of the declaring source file
    ReferenceTarget.SearchScope scope // DECLARING_FILE / DECLARING_MODULE / REACTOR_MODULES
) {}
```

`scope` is stored directly rather than re-derived.
Re-derivation would require re-attributing the declaring file (which may be closed, forcing a
transient compile) just to read visibility modifiers.
Storing it avoids that cost and makes `incomingCallsFuture` independent of the declaring file.

`CallHierarchyItemDataCodec` follows `TypeHierarchyItemDataCodec` exactly:
`encode` writes a `JsonObject` with `scope` serialized as its `name()` string;
`decode` accepts either a `CallHierarchyItemData` instance (same JVM round-trip) or a `JsonObject`
(cross-request deserialization).

From `CallHierarchyItemData`, a `ReferenceTarget` is reconstructed directly:

```java
new ReferenceTarget(kind, ownerBinaryName, methodName, erasedDescriptor, scope)
```

---

## 4. Prepare Call Hierarchy

```
prepareCallHierarchy(uri, pos)
  → WorkspaceSession.prepareCallHierarchyFuture(uri, pos)
    → routeCompiler(uri)  (same as references and definition)
    → CompilationWorker.submit(session::prepareCallHierarchy)
      → SourceAnalysisSession.prepareCallHierarchy(uri, pos)
        → SourceLocator.elementAt(trees, path)
        → if kind == METHOD or CONSTRUCTOR: build CallHierarchyItem
        → else: return List.of()
```

`SourceAnalysisSession.prepareCallHierarchy` builds the item:

- `name`: `element.getSimpleName()`
- `detail`: binary name of the enclosing `TypeElement`
- `kind`: `SymbolKind.Function` for methods, `SymbolKind.Constructor` for constructors
- `uri`: the source file URI
- `range`: full `MethodTree` span (start of modifiers to closing `}`)
- `selectionRange`: identifier token only
- `data`: `CallHierarchyItemDataCodec.encode(new CallHierarchyItemData(...))`

If the cursor is on a method *invocation* rather than a method *declaration*,
`SourceLocator.elementAt` resolves the element of the target method, not the caller.
`prepareCallHierarchy` must then find the declaring source file via
`DefinitionLocator.findSourceFile` to produce a correct `uri` and `routingUri`.
For the item `range` and `selectionRange`, use `DefinitionLocator.parsePosition(file, element)`
(which parses the declaring file without attribution) to get the method name position.
Set both `range` and `selectionRange` to a point range at that position for the cross-file case;
this is consistent with how `DefinitionLocator.locate` builds external-source locations.

---

## 5. Incoming Calls

Incoming calls is Find References scoped to invocation-like roles and grouped by enclosing caller.
It must reuse the same candidate discovery and transient closed-file compilation path as `textDocument/references`,
so the reference-search OOM fix also applies to call hierarchy.

```
incomingCalls(item)
  → CallHierarchyItemDataCodec.decode(item.getData())
  → reconstruct ReferenceTarget
  → WorkspaceSession.incomingCallsFuture(item)
    → run shared reference-candidate fan-out (see §7)
      → for each candidate:
        CompilationWorker.searchIncomingCalls(target, ...)
          → SourceAnalysisSession.searchIncomingCalls(...)
            → CallHierarchyIncomingLocator.scan(attributedAnalysis, target)
              → returns Map<ExecutableElement, List<Range>>
          → convert to List<CallHierarchyIncomingCall>
    → merge and return
```

### `CallHierarchyIncomingLocator`

New class, extends `TreePathScanner<Void, Void>`.
Reuses `target.matches(element, types, elements)` from `ReferenceTarget` for element comparison.

**Do not override `visitMethodInvocation`.**
`trees.getElement` on a `MethodInvocationTree` path returns `null` in javac's implementation.
The correct element-resolution pattern — already used in `SourceLocator.elementAt` and
`SignatureHelpResolver` — is `trees.getElement(new TreePath(invocationPath, inv.getMethodSelect()))`.
Instead, mirror the approach `ReferenceLocator` uses and handle call sites at the identifier and
member-select level:

- `visitIdentifier` — `trees.getElement(getCurrentPath())`; if it matches the target and
  `getCurrentPath().getParentPath().getLeaf()` is not a declaration context (i.e., not a
  `MethodTree`, `VariableTree`, or `ClassTree` name position), record the call site and find the
  enclosing caller.
- `visitMemberSelect` — `SourceLocator.elementAt(trees, getCurrentPath())`; confirm the parent is a
  `MethodInvocationTree` with `inv.getMethodSelect() == getCurrentPath().getLeaf()` before recording.
- `visitNewClass` — `trees.getElement(getCurrentPath())`; same grouping pattern.
- `visitMemberReference` — `trees.getElement(getCurrentPath())`; same grouping pattern.

This is the same four-visitor structure as `ReferenceLocator`, scoped to invocation roles only.

For each match, `enclosingExecutable()` walks upward via `getParentPath()` until it finds a
`MethodTree` node.
The caller `Element` is `trees.getElement(methodPath)`.
If no `MethodTree` is found before reaching the `ClassTree`, the call is inside a static or
instance initializer block; skip it in M1 (return no result for that call site).

Results are grouped by caller element:

```java
Map<ExecutableElement, CallHierarchyIncomingCall> grouped = new LinkedHashMap<>();
```

Each `CallHierarchyIncomingCall` pairs:
- `from`: a `CallHierarchyItem` built from the caller `ExecutableElement` (name, uri, range,
  selectionRange, data)
- `fromRanges`: all `Range` values where the call occurs inside that caller

The caller's `uri` is known from the file being scanned; no definition lookup is needed for the
caller itself.

### Scope

Identical to Find References: `private` → declaring file; package-private → declaring module;
`public`/`protected` → `moduleGraph.referenceSearchScope(cursorConfig)`.

---

## 6. Outgoing Calls

Outgoing calls scans a **single file** — the file named in `routingUri`.
No candidate index and no cross-module fan-out are required.

```
outgoingCalls(item)
  → CallHierarchyItemDataCodec.decode(item.getData())
  → WorkspaceSession.outgoingCallsFuture(item)
    → routeCompiler(data.routingUri())
    → CompilationWorker.submit(session::outgoingCalls)
      → SourceAnalysisSession.outgoingCalls(item)
        → locate target MethodTree by selectionRange
        → CallHierarchyOutgoingLocator.scan(methodBody, analysis)
          → returns Map<Element, List<Range>>
        → for each callee Element:
            uri ← DefinitionLocator.findSourceFile(element)
            build CallHierarchyOutgoingCall(callee item, ranges)
        → return List<CallHierarchyOutgoingCall>
```

### `CallHierarchyOutgoingLocator`

New class, extends `TreePathScanner<Void, Void>`.

`visitMethod` is overridden to enter **only** the target method (matched by comparing
`selectionRange` against the element's position) and skip all nested method declarations,
so that calls made by locally-defined anonymous classes or lambdas are attributed to the
correct scope.

```java
@Override
public Void visitMethod(MethodTree node, Void ignored) {
    if (isTargetMethod(node)) {
        scan(node.getBody(), null);
    }
    return null; // skip all other MethodTree nodes entirely
}
```

Handles:

- `visitMethodInvocation` — `trees.getElement(parentPath)` gives the resolved callee.
- `visitNewClass` — `trees.getElement(currentPath)` gives the constructor element.
- `visitMemberReference` — `trees.getElement(currentPath)` gives the referenced method.

Results are grouped by callee `Element`.
Each `CallHierarchyOutgoingCall` pairs:
- `to`: a `CallHierarchyItem` for the callee; `uri` is resolved via
  `DefinitionLocator.findSourceFile(calleeElement)` (returns `null` for non-source callees,
  which are omitted).
- `fromRanges`: `Range[]` of the call sites within the target method's body.

---

## 7. Shared Reference Candidate Pipeline

`WorkspaceSession.referencesFuture` already owns the broad-search shape that incoming calls needs:

1. resolve or receive a `ReferenceTarget`;
2. choose legal module/source-config scope from target visibility;
3. ask `ReferenceCandidateIndex` for candidate files;
4. route each candidate to the owning `CompilationWorker`;
5. use cached analysis for open files and transient analysis for closed files;
6. pass the request `CancelChecker` into queued candidate work;
7. join candidate futures and flatten immutable per-candidate results.

Incoming calls should not duplicate that fan-out.
Before implementing incoming calls, extract the reusable parts of `referencesFuture` into private helpers inside
`WorkspaceSession`.
Prefer private generic helper methods if the signatures stay readable.
If generics obscure the code, keep two explicit feature methods and share only candidate planning, routing, and joining helpers.

Do not introduce a public search abstraction, a top-level engine class, or a strategy hierarchy in the first implementation.
Reconsider a concrete package-private helper class only after incoming calls are implemented and `WorkspaceSession` becomes visibly harder to read.

At minimum, extract shared scope planning:

```java
private List<ModuleSourceConfig> planSearchScope(
    ReferenceTarget target, Optional<ModuleSourceConfig> cursorConfig) {
  return switch (target.scope()) {
    case DECLARING_FILE -> List.of();   // handled separately upstream
    case DECLARING_MODULE ->
        cursorConfig.map(c -> moduleGraph.configsForModule(c.moduleDir())).orElse(List.of());
    case REACTOR_MODULES ->
        cursorConfig.map(moduleGraph::referenceSearchScope).orElseGet(workspace::allConfigs);
  };
}
```

### `packageRel` for package-private incoming calls

`referencesFuture` computes a `packageRel` path for `DECLARING_MODULE` scope that restricts
candidate files to the declaring class's package directory.
The existing `declaringPackageRel(toPath(uri), cursorConfig)` derives this from the **cursor URI**.

For incoming calls there is no cursor — the declaring file is `CallHierarchyItemData.routingUri`.
The shared candidate fan-out helper must accept a `packageRel` parameter and callers must supply it:

- `referencesFuture` passes `declaringPackageRel(toPath(cursorUri), cursorConfig)` as before.
- `incomingCallsFuture` passes `declaringPackageRel(toPath(data.routingUri()), declaringConfig)`
  where `declaringConfig = workspace.moduleSourceFor(toPath(data.routingUri()))`.

This ensures package-private incoming-call searches are restricted to the correct package, exactly
as Find References does today.

The preferred DRY shape is a private candidate fan-out helper used by both references and incoming calls:

```java
private <T> CompletableFuture<List<T>> searchReferenceCandidates(
    ReferenceTarget target,
    String routingUri,
    Path packageRel,          // null for public/protected; non-null for package-private
    CancelChecker cancelChecker,
    CandidateOperation<T> operation) {
  ...
}
```

However, repository style forbids new interfaces for single implementations.
If a private functional interface would be the only clean way to express this,
use a small set of private helper methods instead:

- `planSearchScope(...)`
- `referenceCandidates(...)`  — accepts `packageRel`
- `searchCandidateReferences(...)`
- `searchCandidateIncomingCalls(...)`
- `joinCandidateResults(...)`

The important invariant is that references and incoming calls share candidate discovery, worker routing,
cancellation checks, and transient closed-file lifetime.
They should differ only in the per-candidate analysis operation and final aggregation shape.

Outgoing calls must not use this pipeline.
It scans exactly one attributed source file named by `routingUri`.

---

## 8. Wiring

### `LatheLanguageServer.createCapabilities()`

```java
capabilities.setCallHierarchyProvider(true);
```

### `LatheTextDocumentService`

Three new overrides.
`prepareCallHierarchy` and `outgoingCalls` follow the `prepareTypeHierarchy` /
`typeHierarchySupertypes` pattern.
`incomingCalls` follows the `references` pattern because it can fan out across a large reactor
and must support cancellation:

```java
@Override
public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(
    final CallHierarchyPrepareParams params) {
  final var uri = params.getTextDocument().getUri();
  final var pos = params.getPosition();
  return worker.submit(() -> session.prepareCallHierarchyFuture(uri, pos)).thenCompose(f -> f);
}

@Override
public CompletableFuture<List<CallHierarchyIncomingCall>> callHierarchyIncomingCalls(
    final CallHierarchyIncomingCallsParams params) {
  final var response = new CompletableFuture<List<CallHierarchyIncomingCall>>();
  final CancelChecker cancelChecker = new CompletableFutures.FutureCancelChecker(response);
  final CompletableFuture<List<CallHierarchyIncomingCall>> work =
      worker
          .submit(() -> session.incomingCallsFuture(params.getItem(), cancelChecker))
          .thenCompose(f -> f);
  work.whenComplete(
      (result, failure) -> {
        if (failure == null) {
          response.complete(result);
        } else {
          response.completeExceptionally(failure);
        }
      });
  return response;
}

@Override
public CompletableFuture<List<CallHierarchyOutgoingCall>> callHierarchyOutgoingCalls(
    final CallHierarchyOutgoingCallsParams params) {
  return worker.submit(() -> session.outgoingCallsFuture(params.getItem())).thenCompose(f -> f);
}
```

### `CompilationWorker`

Three new delegating methods following the existing `resolveTarget` / `searchReferences`
pattern.
Incoming-call candidate methods should mirror the current transient reference-search methods closely enough that
`WorkspaceSession` can route both through the same candidate fan-out helpers.

### `SourceAnalysisSession`

Two new analysis methods: `prepareCallHierarchy(uri, pos)` and `outgoingCalls(item)`.
Incoming calls reuse `searchReferences` infrastructure via `CompilationWorker`, just as the
existing transient search path does.

---

## 9. New Files

| File | Role |
|---|---|
| `CallHierarchyItemData.java` | Record for item data payload |
| `CallHierarchyItemDataCodec.java` | JSON encode/decode |
| `CallHierarchyIncomingLocator.java` | Scanner: finds callers grouped by enclosing method |
| `CallHierarchyOutgoingLocator.java` | Scanner: finds callees within one method body |

All four files live in `io.github.aglibs.lathe.server.analysis`.

---

## 10. Implementation Order

Steps are ordered to minimise blocked dependencies.
Steps 3 and 4 are independent once step 2 is done.

1. **Extract the shared reference-candidate pipeline** from `WorkspaceSession.referencesFuture`.
   At minimum extract `planSearchScope`; preferably also share candidate discovery, worker routing, cancellation-aware
   closed/open candidate handling, future joining, and flattening where this remains readable.
   Verify `referencesFuture` behaviour is unchanged.

2. **`CallHierarchyItemData` + codec + capability flag.**
   Add `setCallHierarchyProvider(true)` to `createCapabilities()` and the
   `LatheLanguageServerTest` capability assertion.

3. **`prepareCallHierarchy`** — `SourceAnalysisSession`, `CompilationWorker`,
   `WorkspaceSession`, `LatheTextDocumentService`.
   Smoke-test via `dev/explore.py` against a known method.

4. **`outgoingCalls`** — `CallHierarchyOutgoingLocator`, `SourceAnalysisSession`,
   `CompilationWorker`, `WorkspaceSession`, `LatheTextDocumentService`.
   Validate the single-file path before tackling cross-module incoming.

5. **`incomingCalls`** — `CallHierarchyIncomingLocator`, `CompilationWorker`,
   `WorkspaceSession`, `LatheTextDocumentService`.
   Plug incoming-call candidate work into the shared reference-candidate pipeline rather than copying the references
   fan-out.

6. **Tests** — see §11.

---

## 11. Tests

Before adding tests, read at least two existing test classes in every affected package and follow the nearest fixtures.
The goal is to prove each behavior once at the lowest reliable layer.
Higher layers verify routing and protocol shape only.

### Test ownership and anti-duplication

Tests are organized by behavior ownership, not by implementation layer.
Do not add a test at every layer because a feature crosses every layer.
Add a higher-layer test only when that layer owns a distinct risk such as LSP parameter mapping, request routing,
cross-module wiring, or real protocol workflow.

| Behavior | Primary owner | Higher-layer coverage |
|---|---|---|
| Item data JSON round trip | `CallHierarchyItemDataCodecTest` | none |
| Capability advertisement | `LatheLanguageServerTest` | none |
| Cursor-to-method item creation | `SourceAnalysisSessionTest` or nearest existing session test | one service smoke only if needed |
| Incoming call AST grouping | `CallHierarchyIncomingLocatorTest` | one workspace/service cross-module scenario |
| Outgoing call AST grouping | `CallHierarchyOutgoingLocatorTest` | one service scenario only if locator cannot prove source URI conversion |
| Reference candidate fan-out reuse | existing Find References tests | no duplicate call-hierarchy fan-out test unless extraction creates a new helper contract |
| Cross-module incoming scope | `WorkspaceSessionTest` or existing workspace reference-scope owner | one invoker smoke if not already covered by service tests |
| LSP request parameter/response wiring | `LatheTextDocumentServiceTest` | no locator details |
| Real editor-like workflow | explorer manual validation | no Neovim-in-build test |

Each implementation slice should include a small coverage map:

```text
behavior -> existing coverage -> new/changed coverage -> owning test class
```

If existing reference tests already prove candidate planning, open/closed routing, cancellation-aware transient analysis,
or future joining after extraction, do not add equivalent call hierarchy tests for those mechanics.
Incoming-call tests should focus on the new grouping semantics.
Outgoing-call tests should focus on the single-method-body scan semantics.
Service tests should assert protocol shape and dispatch only.
Invoker or explorer validation should cover one representative real workflow, not every node kind.

### Unit and service tests

`CallHierarchyItemDataCodecTest`

- `encode_decode_roundTrip_preservesAllFields`
- `decode_fromJsonObject_reconstructsRecord`

`CallHierarchyIncomingLocatorTest`

- `scan_directMethodCall_returnsCallerAndRange`
- `scan_constructorCall_returnsCallerAndRange`
- `scan_memberReference_returnsCallerAndRange`
- `scan_noMatch_returnsEmpty`

Initializer attribution is deferred unless the implementation supports it naturally without extra display/range policy.

`CallHierarchyOutgoingLocatorTest`

- `scan_methodCallInBody_returnsCalleeAndRange`
- `scan_nestedClassMethod_notAttributedToOuterMethod`
- `scan_constructorInvocationInBody_returnsCallee`
- `scan_nonSourceCallee_omitted`

`LatheLanguageServerTest`

- `createCapabilities_includesCallHierarchyProvider`

`LatheTextDocumentServiceTest` or the nearest existing service/workspace owner

- `prepareCallHierarchy_onMethodDeclaration_returnsItem`
- `prepareCallHierarchy_notOnMethod_returnsEmpty`
- `outgoingCalls_fromPreparedItem_returnsCallees`
- `incomingCalls_fromPreparedItem_returnsCallerAndRanges`

These service tests should use simple source fixtures and assert protocol-level structure.
They should not reassert every locator node kind.

### Invoker and explorer validation

Add invoker coverage only for behavior that cannot be proven reliably by unit/service tests.
The preferred invoker scope is one multi-module smoke:

- `incomingCalls_publicMethod_findsCrossModuleCaller`

Do not add separate invoker tests for every locator node kind.
Those belong in locator unit tests.

Manual explorer validation covers the real JSON-RPC workflow:

- prepare on a known method;
- outgoing calls from that prepared item;
- incoming calls to a public method with at least one reactor caller;
- empty prepare result when the cursor is not on a method or constructor.

### Reuse regression for extraction

Existing Find References tests remain the primary regression suite for the extracted candidate pipeline.
Add new tests only where the extraction creates a genuinely new helper contract that is not already covered through
`referencesFuture`.
Do not duplicate the same successful reference search at every layer only to prove that the helper is shared.

---

## 12. Deferred

The following are explicitly out of scope for M1 and should be designed separately before
implementation:

- **Override expansion**: finding incoming calls to all overrides or implementations of a
  method.
  This shares design space with rename and should be addressed alongside it in M2.
- **Anonymous-class and lambda caller labelling**: `<anonymous Runnable>` or `<lambda>`
  display names for calls inside anonymous classes or lambda bodies.
  M1 can attribute these to the nearest enclosing named method.
- **Partial-result streaming**: not needed until measurements show latency on large reactors.
