# Lathe — Signature Help

## Problem

When typing arguments inside a method invocation `m(...)` or a constructor call `new C(...)`, developers need real-time guidance on the expected parameter names and types.
Without signature help, parameter type information disappears immediately after committing a method completion, forcing the developer to manually trigger hover or consult documentation.

## Goal

Implement the `textDocument/signatureHelp` LSP request in `lathe-server` to provide method signature hints.
It should support:
- Listing all overloads for the target method or constructor.
- Highlighting the currently active parameter as the user types (by parsing the argument list and commas).
- Reusing type formatting, receiver resolution, and Javadoc retrieval to keep the codebase DRY and KISS.
- Automatically triggering signature help on `(` and `,` characters.

## Non-Goals

- Providing signature help for lambda parameters or anonymous classes (which are not method invocations).
- Complex type inference for unresolved arguments where javac fails completely.

## Architecture & Reuse Strategy

To avoid duplication, Signature Help integrates with existing Hover and Completion Presentation systems through three shared layers:

### 1. Unified Signature Formatter (`SignatureFormatter`)
Extends or delegates to the existing concise `TypeMirror` formatter to format parameter and return types consistently:
- **Concise Rendering**: Parameter and return types render as clean Java types without fully qualified package prefixes (matching the JDT LS completion row style).
- **Generic Substitution**: Parameter types are resolved with generic substitutions (e.g., `T` is formatted as `String` inside a `List<String>` context).
- **Ranges Mapping**: The formatter calculates the character offsets `[start, end]` of each parameter label within the full signature string, which is required by LSP's `ParameterInformation`.

### 2. Shared Javadoc & Metadata Lookup
- Signature Help queries the existing `JavadocLocator` for the `ExecutableElement` associated with each overload.
- The Javadoc description is mapped directly to the `documentation` field of LSP `SignatureInformation`, showing documentation tooltips inline while the user enters arguments.

### 3. Shared Member Resolution
- **Receiver Resolution**: The resolver uses the same AST traversal to locate the call site (`MethodInvocationTree` or `NewClassTree`) and resolve the type of its receiver expression.
- **Overload Candidate Discovery**: It queries the resolved `TypeElement`'s members to locate all methods matching the name of the call site, mirroring the candidate list returned during completion.

## LSP Surface

Register the signature help provider in `LatheLanguageServer`:

```java
final var options = new SignatureHelpOptions(List.of("(", ","));
capabilities.setSignatureHelpProvider(options);
```

Add the `signatureHelp` request handler to `LatheTextDocumentService`:

```java
@Override
public CompletableFuture<SignatureHelp> signatureHelp(final SignatureHelpParams params) {
  final var uri = params.getTextDocument().getUri();
  final var pos = params.getPosition();
  return worker.submit(() -> session.signatureHelpFuture(uri, pos)).thenCompose(f -> f);
}
```

## Resolver Logic

The signature help resolution will be handled in a new helper class `SignatureHelpResolver` under `SourceAnalysisSession`:

### 1. Locate the Call Site
Traverse the AST upwards from the cursor's offset using `SourceLocator.pathAt()` to find the nearest enclosing invocation tree:
- `MethodInvocationTree` (for method calls)
- `NewClassTree` (for constructor calls)

```java
private static TreePath findEnclosingCall(final TreePath path) {
  var p = path;
  while (p != null) {
    final var leaf = p.getLeaf();
    if (leaf instanceof MethodInvocationTree || leaf instanceof NewClassTree) {
      return p;
    }
    p = p.getParentPath();
  }
  return null;
}
```

### 2. Compute Active Parameter Index
To determine which parameter is currently active:
- Count the number of commas at the current nesting level within the argument list up to the cursor position.
- If the cursor sits before the first argument or the argument list is empty, the active parameter index is `0`.

### 3. Build LSP `SignatureHelp`
Map each candidate `ExecutableElement` to a `SignatureInformation` object:
- **Label**: e.g., `void myMethod(String paramName, int times)`
- **Parameters**: A list of `ParameterInformation` showing the parameter's name and type, with start/end character offsets within the label.
- **Documentation**: Attach Javadoc if available.

Determine the `activeSignature` index:
- If a specific overload is already matched/resolved by javac, default to that index.
- Otherwise, default to the first overload candidate.

## Neovim / Vim Behavior

Neovim handles the signature help UI automatically when trigger characters are typed:
- Typing `(` opens the signature help float showing the list of overloads with the first parameter highlighted.
- Typing `,` updates the highlighted parameter to the next index.
- The float automatically closes when the cursor exits the method arguments range.

## Tests

Unit and integration tests will cover:
- Standard method invocations with zero, one, or multiple parameters.
- Static and instance method overloads.
- Constructor calls with overloads.
- Correct active parameter indexing (e.g., cursor placed in the second argument position highlights the second parameter).
- Handling of nested invocations (e.g., `outer(inner(§))` correctly resolves to `inner` signature help when the cursor is inside `inner`).
