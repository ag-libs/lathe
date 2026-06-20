# Lathe — Call Hierarchy Design

Working design draft.
This document describes the implementation of `textDocument/prepareCallHierarchy`, `callHierarchy/incomingCalls`, and `callHierarchy/outgoingCalls`.

---

## 1. Goal

Call hierarchy answers the editor questions:
> "Who calls this method?" (Incoming Calls)
> "What methods does this method call?" (Outgoing Calls)

The feature relies on Lathe's existing `ReferenceTarget` symbol identity and the `ReferenceCandidateIndex` architecture used by "Find References".
For M2, Call Hierarchy focuses on exact method matches (no hierarchy-aware semantic expansion to overrides/implementations) and leverages `javac` attribution as the single source of truth.

---

## 2. LSP Overview

The Call Hierarchy feature involves three distinct requests:

1.  **`textDocument/prepareCallHierarchy`**: Maps a cursor position to a target method/constructor and returns a `CallHierarchyItem[]` (usually length 1).
2.  **`callHierarchy/incomingCalls`**: Takes a `CallHierarchyItem` and returns a list of `CallHierarchyIncomingCall` objects (which pairs a *caller* `CallHierarchyItem` with the `Range`s where the calls occurred).
3.  **`callHierarchy/outgoingCalls`**: Takes a `CallHierarchyItem` and returns a list of `CallHierarchyOutgoingCall` objects (which pairs a *callee* `CallHierarchyItem` with the `Range`s where the calls occurred).

---

## 3. Symbol Identity & Prepare Call Hierarchy

When a user triggers call hierarchy, the server must resolve the cursor to a method or constructor.

```text
LSP prepareCallHierarchy
  -> WorkspaceSession routes to the owning CompilationWorker
  -> SourceAnalysisSession resolves the cached attributed file
  -> SourceLocator.elementAt(...) returns the javac Element at the cursor
  -> If the element is an ExecutableElement (method/constructor), build a CallHierarchyItem
```

### Passing State via `CallHierarchyItem.data`
The LSP `CallHierarchyItem` specification allows an arbitrary `data` payload.
We should serialize a `CallHierarchyItemData` object here that captures enough information to recreate the `ReferenceTarget` later without re-attributing the defining file.

```java
record CallHierarchyItemData(
    String uri, // The defining file
    String ownerBinaryName,
    String methodName,
    String erasedDescriptor,
    ElementKind kind
) {}
```
When `prepareCallHierarchy` succeeds, we return the `CallHierarchyItem` populated with this payload, along with the method's `name`, `detail` (e.g., the owning class name), `uri`, full `range`, and `selectionRange` (the method identifier).

---

## 4. Incoming Calls (`callHierarchy/incomingCalls`)

Finding incoming calls is effectively a specialized **Find References** request.

1.  **Reconstruct Target**: Deserialize `CallHierarchyItem.data` back into a `ReferenceTarget`.
2.  **Candidate Discovery**: Reuse `ReferenceCandidatePlanner` and the `ReferenceCandidateIndex` to find files that contain the method's spelling.
3.  **Attribution**: Route candidate files to their respective `CompilationWorker`s.
4.  **Tree Scanning (`CallHierarchyIncomingLocator`)**:
    We cannot simply reuse `ReferenceLocator` as-is, because `ReferenceLocator` only returns the exact token `Location` of the reference. For incoming calls, we must know the **enclosing ExecutableElement** (or ClassTree for field initializers/static blocks) where the reference token is located.

We should create a new `CallHierarchyIncomingLocator` (or extend `TreePathScanner`) that:
- Finds occurrences of `ReferenceTarget`.
- For each occurrence, walks up the `TreePath` (`getCurrentPath().getParentPath()`) until it finds an enclosing `MethodTree` (or `ClassTree`).
- Resolves the `Element` of that enclosing tree to create the caller `CallHierarchyItem`.
- Groups the occurrences by the caller `CallHierarchyItem`.

### Scope
The search scope uses the exact same conservative `SearchScope` rules and `WorkspaceModuleGraph` as Find References. If the method is package-private, we search the package. If public, we search the module and downstream modules.

---

## 5. Outgoing Calls (`callHierarchy/outgoingCalls`)

Outgoing calls only need to analyze a single file—the file where the target method is declared.

1.  **Resolve File**: The client sends the `CallHierarchyItem` which contains the `uri`.
2.  **Attribution**: Send an attribution request to the `CompilationWorker` for that `uri`.
3.  **Locate Method**: Use the `CallHierarchyItem.selectionRange` or `range` to find the exact `MethodTree` in the `CompilationUnitTree`.
4.  **Tree Scanning (`CallHierarchyOutgoingLocator`)**:
    Scan only the body of that `MethodTree`. Look for:
    - `MethodInvocationTree`
    - `NewClassTree` (constructor calls)
    - `MemberReferenceTree` (method references)
5.  **Grouping**: For every method/constructor invocation found, resolve its `Element`. Convert the resolved `Element` into a `CallHierarchyItem` (the callee) and record the token's `Range` where the call occurred. Group results by callee.

---

## 6. Architecture & Threading

The threading model matches `references`:
- `LatheTextDocumentService` exposes the three LSP endpoints.
- `WorkspaceSession` handles the routing.
- The `ReferenceCandidateIndex` (living on `lathe-worker`) is queried for incoming calls.
- `CompilationWorker` threads perform the actual `javac` attribution and AST scanning.
- We map `AttributedFileAnalysis` to `CallHierarchyIncomingCall` and `CallHierarchyOutgoingCall` records, which are then translated to LSP objects at the edge.

---

## 7. DRY & KISS Principles

To keep the implementation simple and avoid duplication, we will maximize reuse of existing codebase components:

1.  **Target Identity (`ReferenceTarget`)**: Instead of creating a custom payload, we reuse `ReferenceTarget` directly. `prepareCallHierarchy` will serialize it into `CallHierarchyItem.data`. `incomingCalls` will deserialize it, allowing us to instantly resume search logic.
2.  **Search Orchestration**: `WorkspaceSession.referencesFuture` already calculates search scopes using `WorkspaceModuleGraph` and prefilters using `ReferenceCandidateIndex`. We will extract this logic into a helper method (`planSearchScope(ReferenceTarget target)`) so both Find References and Incoming Calls can share it without duplication.
3.  **Source Resolution**: When `prepareCallHierarchy` is invoked on a method invocation, we must find the original declaration file to build the `CallHierarchyItem`. We will reuse `DefinitionLocator.findSourceFile()` for this exact purpose.
4.  **AST Scanning**: We will **not** modify `ReferenceLocator` to return enclosing methods, as that would bloat it. Instead, following KISS principles, we will write two tiny, dedicated classes: `CallHierarchyIncomingLocator` and `CallHierarchyOutgoingLocator`. Both will extend `TreePathScanner` and reuse `target.matches()` to evaluate elements.

---

## 8. Deferred Items (Post-M2)

- **Hierarchy Expansion**: Finding incoming calls to overrides (e.g., finding calls to `List.add` when looking at `ArrayList.add`). This should be designed separately alongside Rename's hierarchy expansion policy.
- **Anonymous Class Callers**: If an incoming call is inside an anonymous class or lambda, M2 can attribute the call to the nearest enclosing named method, or gracefully format the anonymous class as `<anonymous>` or `<lambda>`.
