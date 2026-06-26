# Lathe — `textDocument/declaration` Design

## Objective
Implement the `textDocument/declaration` LSP endpoint (Gap EG-012) to allow users to navigate from an overriding method (either at its declaration site or at a call site) to the original contract method in its superclass or interface.

## Architecture & Flow

### 1. New Analysis Component: `DeclarationLocator`
We will introduce a new analysis component, `DeclarationLocator`, in `io.github.aglibs.lathe.server.analysis`. This class will be responsible for resolving the "root contract" of an `ExecutableElement`.

**Algorithm:**
1. Given an `Element` (resolved by `SourceLocator.elementAt`), check if it is an `ExecutableElement` (a method).
2. If it is not a method, fall back to returning empty (which will trigger a fallback to `definition`).
3. If it is a method, recursively walk its type hierarchy (using `Types.directSupertypes()` via BFS).
4. At each type in the hierarchy, iterate over its enclosed elements.
5. If an enclosed method matches using `Elements.overrides(current, candidate, enclosingType)`, record it.
6. Continue up the hierarchy to find the most abstract contract method.
7. Return the `Location` of the contract method using `SourceLocator.declarationNamePosition` and the existing file-finding mechanisms.

### 2. `SourceAnalysisSession` Integration
Add a new method `declaration(SourceFeatureRequest request)` to `SourceAnalysisSession`.
* It will first call `DeclarationLocator.locate()`.
* If a contract is found, it returns that location.
* If no contract is found (e.g., the cursor is on a class, field, local variable, or a non-overriding method), it will fall back to calling `definition(request)`. This ensures seamless user experience where "Go to Declaration" acts just like "Go to Definition" for anything that isn't an overridden method.

### 3. Server Wiring
* **`LatheTextDocumentService`**: Implement the `declaration(DeclarationParams)` method from LSP4J.
* **`WorkspaceSession`**: Route the request to the active module's `SourceAnalysisSession.declaration()`.

### 4. Testing
Create `lathe-server/src/test/java/io/github/aglibs/lathe/server/DeclarationTest.java`. We will add the regression targets specified in the gap log:
* `declaration_overridingMethodDeclaration_returnsInterfaceMethod`
* `declaration_overridingMethodDeclaration_returnsSuperclassMethod`
* `declaration_nonOverridingMethod_fallsBackToDefinition`
* `declaration_callSiteWithConcreteType_fallsBackToDefinition` (Note: since we use a unified approach, this will actually return the contract method seamlessly, improving upon the baseline requirement).

## Affected Files
* **Added:** `lathe-server/src/main/java/io/github/aglibs/lathe/server/analysis/DeclarationLocator.java`
* **Added:** `lathe-server/src/test/java/io/github/aglibs/lathe/server/DeclarationTest.java`
* **Modified:** `lathe-server/src/main/java/io/github/aglibs/lathe/server/analysis/SourceAnalysisSession.java`
* **Modified:** `lathe-server/src/main/java/io/github/aglibs/lathe/server/WorkspaceSession.java`
* **Modified:** `lathe-server/src/main/java/io/github/aglibs/lathe/server/LatheTextDocumentService.java`
