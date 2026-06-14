# Lathe — MissingMethodImplProvider Design

## 1. Goal

Implement a `codeAction` quick-fix for `compiler.err.does.not.override.abstract`
that generates `@Override` stubs for all unimplemented abstract methods in a class.

---

## 2. Current State

Classification is complete.
`SourceAnalysisSession.enrichWithContext()` already classifies
`compiler.err.does.not.override.abstract` as `DiagnosticPayload.Kind.MISSING_METHOD_IMPL`
with the concrete class simple name in `payload.name()`.

The dispatcher in `SourceAnalysisSession.codeAction()` routes `MISSING_METHOD_IMPL`
to `List.of()` — a one-line change is all that's needed there once the provider exists.

---

## 3. What's Already in Place

| Piece | Location | Notes |
|---|---|---|
| `payload.name()` — class simple name | classifier in `SourceAnalysisSession` | ✅ done |
| `TypeDisplayFormatter` | `analysis/TypeDisplayFormatter.java` | formats `TypeMirror` → simple name with generics |
| `ImportAnalyzer.importEdit(fqn)` | `analysis/ImportAnalyzer.java` | builds import `TextEdit`, checks java.lang / same-package / already-imported |
| `CodeActionSupport.typeFqn()` | `analysis/CodeActionSupport.java` | FQN from `DeclaredType`; does not handle arrays or parameterized args |
| Provider interface + dispatcher | `CodeActionProvider`, `SourceAnalysisSession` | one-line dispatcher change needed |

---

## 4. Provider Design

### 4.1 Find the TypeElement

Scan `cu.getTypeDecls()` recursively for a `ClassTree` whose simple name matches
`payload.name()`.
Call `analysis.trees().getElement(path)` to get the `TypeElement`.

### 4.2 Collect Unimplemented Abstract Methods

```java
elements().getAllMembers(classElement).stream()
    .filter(e -> e instanceof ExecutableElement)
    .map(ExecutableElement.class::cast)
    .filter(e -> e.getModifiers().contains(Modifier.ABSTRACT))
    .filter(e -> !e.getEnclosingElement().equals(classElement))
    .toList()
```

`getAllMembers` applies supertype type-parameter substitution, so `E` is already
resolved to `String` when the class implements `Iterable<String>`.

### 4.3 Generate Stub Text

For each abstract method:

```
\n    @Override\n    public <returnType> <name>(<params>) {\n        throw new UnsupportedOperationException();\n    }\n
```

Use `TypeDisplayFormatter` for return type and parameter types.
Parameter names are available from javac's symbol table even without `-parameters`.
Visibility: use `public` unconditionally (interface methods are always public; abstract
class methods should at minimum be public for concrete subclass override).

### 4.4 Collect Import FQNs

`CodeActionSupport.typeFqn()` handles `DeclaredType` only.
The provider needs a small recursive collector:

```java
private static void collectFqns(TypeMirror type, Set<String> out) {
    switch (type) {
        case DeclaredType dt -> {
            if (dt.asElement() instanceof TypeElement te) {
                out.add(te.getQualifiedName().toString());
            }
            dt.getTypeArguments().forEach(arg -> collectFqns(arg, out));
        }
        case ArrayType at -> collectFqns(at.getComponentType(), out);
        case WildcardType wt -> {
            if (wt.getExtendsBound() != null) collectFqns(wt.getExtendsBound(), out);
            if (wt.getSuperBound() != null) collectFqns(wt.getSuperBound(), out);
        }
        default -> {} // primitives, void, type variables — no import needed
    }
}
```

For each method: collect from return type, each parameter type, each thrown type.
Then call `importAnalyzer.importEdit(fqn)` for each collected FQN;
`needsImport` inside handles java.lang, same-package, and already-imported filtering.

### 4.5 Insertion Point

`positions.getEndPosition(cu, classTree)` gives the byte offset past the closing `}`.
`endOffset - 1` is the `}` itself.
The insertion range is the start of that line:

```java
final int closingBraceLine = (int) lineMap.getLineNumber(endOffset - 1) - 1; // 0-based
final var insertPos = new Position(closingBraceLine, 0);
final var insertRange = new Range(insertPos, insertPos);
```

This works uniformly whether the class body is empty or has existing members.

### 4.6 Build the CodeAction

Title: `"Implement all abstract methods"`.
Edits: one stub-insert `TextEdit` + one `TextEdit` per needed import.

The `seen` title dedup in `SourceAnalysisSession.codeAction()` ensures that multiple
`MISSING_METHOD_IMPL` diagnostics for the same class (one per unimplemented interface)
collapse to a single action.

---

## 5. Required Changes

| Change | File | Size |
|---|---|---|
| New `MissingMethodImplProvider` | `analysis/MissingMethodImplProvider.java` | ~150 lines |
| Dispatch to provider | `SourceAnalysisSession.codeAction()` L280 | 1 line |

No changes needed to `CodeActionSupport`, `ImportAnalyzer`, `TypeDisplayFormatter`,
or the classifier.

---

## 6. Edge Cases

- **Class with no unimplemented methods** — can happen if the diagnostic fires on a
  class that already has all methods but via a different error path. Return `List.of()`.
- **Empty class body** — insertion still works; `endOffset - 1` always points to `}`.
- **Multiple interfaces with overlapping method signatures** — `getAllMembers` deduplicates
  by signature; each method appears once.
- **Type parameters on the method itself** — `<T> void foo(T t)` — `TypeVariable` renders
  as the type-variable name (e.g. `T`) via `TypeDisplayFormatter`; no import needed.
- **`@Override` on default interface methods** — `getAllMembers` only returns abstract
  methods when filtered by `Modifier.ABSTRACT`; default methods are not abstract and
  are excluded.
