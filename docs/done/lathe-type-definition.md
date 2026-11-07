# Lathe — Type Definition Navigation

## 1. Goal

Implement LSP `textDocument/typeDefinition` so Neovim 0.12's default `grt` mapping works with Lathe.

The feature should jump from an expression or symbol under the cursor to the declaration of that symbol's static type.
It is distinct from:

- `textDocument/definition`, which jumps to the declaration of the referenced symbol itself.
- `textDocument/declaration`, which jumps from overriding methods to their contract.
- `typeHierarchy`, which navigates inheritance relationships from a type declaration.

---

## 2. Current State

Neovim 0.12 maps `grt` to `vim.lsp.buf.type_definition()`.
That sends `textDocument/typeDefinition`.

Lathe currently advertises and implements nearby navigation capabilities:

- `textDocument/definition`
- `textDocument/declaration`
- `textDocument/implementation`
- `typeHierarchy`
- `callHierarchy`

Lathe does not advertise `typeDefinitionProvider`, and `LatheTextDocumentService` has no
`typeDefinition(...)` handler.
Neovim therefore reports that the method is unsupported even though the keymap exists.

---

## 3. User-Facing Behavior

For Java source:

| Cursor target | Expected type-definition result |
|---|---|
| Local variable usage `value` where `value` is `Widget` | `Widget` declaration |
| Field usage `this.config` where `config` is `Config` | `Config` declaration |
| Method call `service.find()` returning `Result` | `Result` declaration |
| Constructor call `new Widget()` | `Widget` declaration |
| Type reference `Widget` | `Widget` declaration |
| Array value `widgets` where type is `Widget[]` | `Widget` declaration |
| Generic value `items` where type is `List<String>` | `List` declaration |
| Primitive value `count` where type is `int` | no result |
| `void`, `null`, unresolved, or error type | no result |

The first implementation should return a normal LSP location list, matching the existing
definition/declaration response shape.

---

## 4. Technical Design

### 4.1 Server capability

Add `typeDefinitionProvider` to `LatheLanguageServer.createCapabilities()`:

```java
capabilities.setTypeDefinitionProvider(true);
```

This enables clients to call `textDocument/typeDefinition` and lets Neovim's default `grt`
mapping route to Lathe.

### 4.2 LSP service routing

Add a `typeDefinition(TypeDefinitionParams params)` override to `LatheTextDocumentService`.

The method should mirror `definition(...)`:

1. Extract URI and position.
2. Submit work to the server event loop.
3. Return `Either<List<? extends Location>, List<? extends LocationLink>>`.

No custom client-side keymap is required once this exists.

### 4.3 Workspace routing

Add `WorkspaceSession.typeDefinitionFuture(uri, pos)`.

It should follow the same pattern as `definitionFuture(...)` and `declarationFuture(...)`:

1. Log one INFO line for the user action.
2. Use `openDocFeature(...)`.
3. Build a `SourceFeatureRequest` from the open document and workspace source roots.
4. Call `SourceAnalysisSession.typeDefinition(request)`.
5. Convert the optional location to `Either.forLeft(List.of(location))`, or an empty list.

### 4.4 Type resolution

Add `SourceAnalysisSession.typeDefinition(SourceFeatureRequest request)`.

The method should reuse the existing `resolve(request)` path, then determine the semantic type
using javac APIs only.
No text parsing, regex matching, or custom token scanning should be introduced.

Suggested algorithm:

1. Resolve the cursor to a `TreePath`.
2. Try `trees.getTypeMirror(path)` for expression and type paths.
3. If that is missing or unusable, inspect `SourceLocator.elementAt(trees, path)`.
4. Convert the selected type to a declaration element.
5. Locate that declaration with the existing `DefinitionLocator`.

Type-to-element conversion should handle common wrappers:

- `DeclaredType` -> `asElement()` if it is a `TypeElement`.
- `ArrayType` -> recurse into component type.
- `TypeVariable` -> prefer its upper bound when useful.
- `ExecutableType` -> use return type for method-call paths.
- `NoType`, primitive, null, wildcard without a declared bound, and error types -> empty.

For symbols whose element is already a type element, return that type element.
For variables and fields, use `VariableElement.asType()`.
For executable elements only use the executable return type when the cursor path represents an invocation or method
reference result; ordinary method-name definition remains covered by `textDocument/definition`.

### 4.5 Location lookup

Use `DefinitionLocator.locate(element, trees, sourceRoots, request.uri())` for the final location.

This keeps behavior aligned with existing definition navigation:

- same-file declarations use `trees.getPath(element)`;
- reactor source declarations use source roots;
- dependency and JDK source lookups follow the same source-root inputs already available through the workspace manifest.

If `DefinitionLocator` cannot locate the type declaration, return empty.

---

## 5. Required Changes

| Change | File | Notes |
|---|---|---|
| Advertise provider | `LatheLanguageServer.java` | `setTypeDefinitionProvider(true)` |
| Add LSP handler | `LatheTextDocumentService.java` | route `TypeDefinitionParams` |
| Add workspace future | `WorkspaceSession.java` | mirror `definitionFuture` |
| Add analysis method | `SourceAnalysisSession.java` | resolve type with javac APIs |
| Add focused tests | `analysis/*Test.java` and server capability tests | positive and edge cases |
| Update README | `README.md` | document `grt` once implemented |

No Neovim plugin changes should be needed.

---

## 6. Test Plan

Follow neighboring `lathe-server` analysis test patterns.
Before adding tests, read at least two nearby test classes in the same package.

Suggested analysis tests:

- `typeDefinition_localVariable_returnsDeclaredType`
- `typeDefinition_fieldAccess_returnsFieldType`
- `typeDefinition_methodCall_returnsReturnType`
- `typeDefinition_constructorCall_returnsConstructedType`
- `typeDefinition_arrayValue_returnsComponentType`
- `typeDefinition_genericValue_returnsRawDeclaredType`
- `typeDefinition_primitiveValue_returnsEmpty`
- `typeDefinition_unresolvedExpression_returnsEmpty`

Suggested service/capability tests:

- `createCapabilities_typeDefinition_advertisesProvider`
- `typeDefinition_openDocument_returnsLocation`

Run focused tests first:

```bash
mvn -pl lathe-server -Dtest=TypeDefinitionTest test
```

Then run the broader server tests if the implementation changes shared analysis behavior:

```bash
mvn -pl lathe-server test
```

Run `mvn spotless:apply` immediately after Java edits.

---

## 7. Edge Cases and Non-Goals

- Do not implement text-level fallbacks.
  Type information must come from javac attribution.
- Do not special-case Neovim.
  The server should implement the standard LSP method.
- Do not add a custom `workspace/executeCommand`.
- Do not return inheritance results.
  `typeDefinition` is about the static declared type, not subtypes or supertypes.
- Do not try to infer Lombok-generated types.
  Lathe does not support Lombok-generated source semantics.

---

## 8. Open Questions

1. Should method-name cursor positions return the method return type, or only method-call expression positions?

   Conservative answer: return the method return type only when javac resolves the current `TreePath` as an invocation
   expression.
   Keep method-name declaration behavior under `textDocument/definition`.

2. Should `TypeVariable` navigate to the type parameter declaration or to its upper bound?

   Conservative answer: navigate to the type parameter declaration when that element is available.
   Fall back to the upper bound only when the request target is a value whose static type is the type variable.

3. Should dependency and JDK source misses fall back to class metadata locations?

   Initial answer: no.
   Return empty when source cannot be located, consistent with current source-based navigation behavior.
