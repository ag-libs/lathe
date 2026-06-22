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
    String ownerBinaryName,   // binary name of the declaring class
    String methodName,        // simple method/constructor name
    String erasedDescriptor,  // erased parameter descriptor, e.g. "(java.lang.String,int)"
    ElementKind kind,         // METHOD or CONSTRUCTOR
    String routingUri         // file:// URI of the declaring source file
) {}
```

`CallHierarchyItemDataCodec` follows `TypeHierarchyItemDataCodec` exactly:
`encode` writes a `JsonObject`; `decode` accepts either a `CallHierarchyItemData` instance
(same JVM round-trip) or a `JsonObject` (cross-request deserialization).

From `CallHierarchyItemData`, a `ReferenceTarget` is reconstructed as:

```java
new ReferenceTarget(kind, ownerBinaryName, methodName, erasedDescriptor, scope)
```

`scope` is re-derived from the element when the declaring file is re-attributed during incoming
calls, or can be stored in the data for cheaper reconstruction.

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

---

## 5. Incoming Calls

Incoming calls is Find References scoped to `INVOCATION` roles and grouped by enclosing caller.

```
incomingCalls(item)
  → CallHierarchyItemDataCodec.decode(item.getData())
  → reconstruct ReferenceTarget
  → WorkspaceSession.incomingCallsFuture(item)
    → planSearchScope(target, cursorConfig)   ← extracted helper (see §7)
    → for each candidate config:
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

Handles three node kinds:

- `visitMethodInvocation` — resolve the invoked element via
  `trees.getElement(getCurrentPath().getParentPath())`; if it matches, record the call site
  and find the enclosing caller.
- `visitNewClass` — resolve via `trees.getElement(getCurrentPath())`; same pattern.
- `visitMemberReference` — resolve via `trees.getElement(getCurrentPath())`; same pattern.

For each match, `enclosingExecutable()` walks `getCurrentPath()` upward through
`getParentPath()` until it finds a `MethodTree` node.
The caller `Element` is `trees.getElement(methodPath)`.
If no `MethodTree` is found before reaching the `ClassTree`, the call is inside a static or
instance initializer block; attribute the call to a synthetic `<clinit>` or `<init>` entry.

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

## 7. Shared Scope-Planning Helper

`WorkspaceSession.referencesFuture` contains scope-planning logic that `incomingCallsFuture`
must duplicate without extraction.
Before implementing incoming calls, extract this into a package-private helper:

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

`referencesFuture` and `incomingCallsFuture` both call this.
No other change to `referencesFuture` is needed.

---

## 8. Wiring

### `LatheLanguageServer.createCapabilities()`

```java
capabilities.setCallHierarchyProvider(true);
```

### `LatheTextDocumentService`

Three new overrides following the existing `prepareTypeHierarchy` / `typeHierarchySupertypes` /
`typeHierarchySubtypes` pattern:

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
  return worker.submit(() -> session.incomingCallsFuture(params.getItem())).thenCompose(f -> f);
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

1. **Extract `planSearchScope`** from `WorkspaceSession.referencesFuture`.
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

6. **Tests** — see §11.

---

## 11. Tests

### Unit

`CallHierarchyItemDataCodecTest`
- `encode_decode_roundTrip_preservesAllFields`
- `decode_fromJsonObject_reconstructsRecord`

`CallHierarchyIncomingLocatorTest` (uses `WorkbenchFixture`)
- `scan_directMethodCall_returnsCallerAndRange`
- `scan_constructorCall_returnsCallerAndRange`
- `scan_memberReference_returnsCallerAndRange`
- `scan_callInStaticInitializer_attributesToClinitSynthetic`
- `scan_noMatch_returnsEmpty`

`CallHierarchyOutgoingLocatorTest` (uses `WorkbenchFixture`)
- `scan_methodCallInBody_returnsCalleeAndRange`
- `scan_nestedClassMethod_notAttributedToOuterMethod`
- `scan_constructorInvocationInBody_returnsCallee`
- `scan_nonSourceCallee_omitted`

### Integration (invoker `multi-module`)

Extend `LspSmokeTest`:
- `prepareCallHierarchy_onMethodDeclaration_returnsItem`
- `prepareCallHierarchy_notOnMethod_returnsEmpty`
- `outgoingCalls_returnsCalleesInMethodBody`
- `incomingCalls_publicMethod_findsCrossModuleCallers`

### Capability assertion

`LatheLanguageServerTest.createCapabilities_includesCallHierarchyProvider`

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
