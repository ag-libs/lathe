This document records the gaps found during live probing of `textDocument/codeAction`
on the dropwizard and helidon codebases after the initial code-action implementation
(ImportQuickFixProvider + AddThrowsProvider).
Each gap describes the observed behaviour, the root cause, and the proposed fix.

---

## Gap 1 — `UNREPORTED_EXCEPTION` inside a lambda body has no action

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

---

## Gap 2 — `VARIABLE_REF` has no action

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

---

## Gap 3 — `MISSING_METHOD_IMPL` is never classified

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

---

## Gap 4 — `TYPE_REF` in a `throws` declaration with no index match produces no action

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

Low: the gap only manifests when a custom exception class is created but `lathe:sync`
has not run since its creation.
Running `mvn process-test-classes` re-indexes the module and the action appears immediately.

### Note

No code-action change is needed.
This is a type-index freshness issue, not a provider bug.
Document here for completeness.
