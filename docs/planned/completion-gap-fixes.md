# Completion gap fixes

Tracks the 8 disabled/crashing tests added in commit 1435423 and the
exact code changes needed to make each pass.

---

## Group A — Non-assignable locals appear in typed expression slots (5 tests)

**Disabled tests**
- `argumentPosition_nonAssignableLocal_excluded`
- `argumentPosition_nonAssignableLocal_excludedInReceiverQualifiedCall`
- `variableInitializer_nonAssignableLocal_excluded`
- `returnPosition_nonAssignableLocal_excluded`
- `constructorCallArgument_nonAssignableLocal_excluded`

All five share the same root cause: a `StringBuilder sb` local (or any
wrong-type variable) is offered as a completion candidate when the
expected slot type is `String`.

### Why it happens

`CompletionCandidateRanker.valid()` already excludes object methods and
void methods, but has no assignability check for other candidates:

```java
// CompletionCandidateRanker.java:30-38
return !objectMethod(candidate)
    && !voidMethod(candidate)
    && compatibleKeyword(candidate, context);
```

`sortText()` ranks wrong-type items to slot `1_name` (vs `0_name` for
matching), but **does not exclude them**.  The ranking and the filtering
use the same `types().isAssignable()` call, but filtering never fires.

### Fix 1 — `CompletionCandidateRanker.valid()` (necessary for all 5)

Add an assignability gate for non-keyword candidates:

```java
return !objectMethod(candidate)
    && !voidMethod(candidate)
    && compatibleKeyword(candidate, context)
    && assignableToExpected(candidate, context);   // NEW

private static boolean assignableToExpected(
    CompletionCandidate c, SemanticCompletionContext ctx) {
  if (c.kind() == CandidateKind.KEYWORD) return true;
  if (!(ctx.expectedValue() instanceof ExpectedValue.Type(TypeMirror expected))) return true;
  TypeMirror vt = c.valueType();
  if (vt == null || vt.getKind().isPrimitive()
      || vt.getKind() == TypeKind.ERROR
      || vt.getKind() == TypeKind.NONE) return true;   // unknown — keep
  return ctx.analysis().types().isAssignable(vt, expected);
}
```

The `isPrimitive()` guard ensures primitives (int, boolean…) are not
silently dropped when the expected type is their wrapper — `isAssignable`
already handles boxing, but the guard avoids edge cases with
`TypeKind.NONE` / `ERROR` produced by erroneous code.

### Fix 2 — `TypeResolver.resolveExpectedValue()` (needed for return position)

For the four non-return tests, `resolveArgumentValue()` and
`resolveInitializerValue()` already resolve `ExpectedValue.Type(T)` for
the slot.  For **`return §`** they both return `Unknown`, so the ranker
never sees the expected type and fix 1 has no effect.

`resolveInitializerValue()` only visits `VariableTree`. It must also
handle `ReturnTree`:

```java
// TypeResolver.java — inside resolveInitializerValue(), the scanner
@Override
public Void visitReturn(ReturnTree node, Void unused) {
  if (result.get() != null || node.getExpression() == null) {
    return super.visitReturn(node, unused);
  }
  long start = positions.getStartPosition(snapshot.tree(), node);
  long end   = positions.getEndPosition(snapshot.tree(), node);
  if (start >= 0 && start < site.cursorOffset() && end >= site.cursorOffset()) {
    // cursor is inside this return — use the enclosing method's return type
    TypeMirror ret = resolveMethodReturnType(site.enclosingClass(),
                                             site.enclosingMethod(), snapshot);
    if (ret != null && ret.getKind() != TypeKind.VOID) {
      result.set(ret);
    }
  }
  return super.visitReturn(node, unused);
}
```

`resolveMethodReturnType()` is a new private helper that mirrors
`isNonVoidMethod()` but returns the `TypeMirror` instead of a boolean:

```java
private static TypeMirror resolveMethodReturnType(
    String className, String methodName, AttributedFileAnalysis snapshot) {
  var classEl = findClassElement(className, snapshot);
  if (classEl == null) return null;
  return snapshot.elements().getAllMembers(classEl).stream()
      .filter(el -> el.getKind() == ElementKind.METHOD)
      .filter(el -> methodName.equals(el.getSimpleName().toString()))
      .map(el -> ((ExecutableElement) el).getReturnType())
      .filter(t -> t.getKind() != TypeKind.VOID)
      .findFirst()
      .orElse(null);
}
```

### Fix 3 — `SemanticCompletionContext.from()` (needed so `valueContext` is true for return)

`valueSensitiveContext()` gates the entire filtering path:

```java
return context.valueContext() || context.expectedValue() instanceof ExpectedValue.Type;
```

Once fix 2 resolves a return type, `expectedValue` is `Type(T)` and the
condition is already satisfied.  Fix 3 is therefore **only needed if**
the implementer chooses to omit fix 2 and use `valueContext` as the
gate instead.  Document for completeness:

`valueContext` is currently set from the *injector* context
(`EXPRESSION` vs `STATEMENT`).  For `return §`, the backward scan sees
`{` at depth 0 and produces `STATEMENT`, even though the sentinel is
semantically in an expression slot.  If both fixes 1 and 2 are
implemented together, fix 3 is not required.

---

## Group B — Statement keywords bleed into expression positions after a complete subexpression (2 tests)

**Disabled tests**
- `keywords_variableInitializer_afterCompleteExpression_noStatementKeywords`
  — snippet: `String s = m()§;`
- `keywords_methodCallArgument_afterCompleteExpression_noStatementKeywords`
  — snippet: `consume(m()§)`

### Why it happens

**Case 1 — `String s = m()§;`**

`SentinelInjector.backwardScan()` scans from the cursor (which sits
between `)` and `;`).  It sees `)` → depth 1, `(` → depth 0, then
continues past `=` (not handled in the switch) until it hits `{` at
depth 0 and stops with `Context.STATEMENT`.

The injected source becomes `String s = m()__SENTINEL__;`.  Since
`m()__SENTINEL__` is not valid Java, javac's error recovery produces an
erroneous tree.  The sentinel identifier ends up as a standalone
`SIMPLE_NAME` with `inExpression = false`.  `KeywordProvider` then
takes the `selectByScope()` path → `methodBodyKeywords()` → full
`CONTROL_FLOW` list (`if`, `for`, `while`, `do`, `switch`, `try`).

**Case 2 — `consume(m()§)`**

The backward scan correctly produces `Context.EXPRESSION` (the outer
`(` of `consume(` drops parenDepth to -1).  The injected source is
`consume(m()__SENTINEL__)`.  Again `m()__SENTINEL__` is invalid Java;
javac error recovery does not place `__SENTINEL__` directly in
`consume()`'s argument list, so `SentinelParser` falls through to
`classifyDefault()` → `SIMPLE_NAME` with `inExpression = false` →
same full keyword set.

### Fix — `SentinelInjector.backwardScan()`

Both cases share the root cause: the sentinel is injected *immediately
after* a complete balanced `()` expression with no dot and an empty
prefix.  This position has no meaningful completion slot and must
produce `Context.EXPRESSION` (which suppresses statement keywords via
`ARGUMENT_POSITION → VALUE_EXPRESSIONS` path) **or** return an empty
result.

Cleanest approach: at the very top of `backwardScan()`, after computing
`prefix` and `hasDot`, add an early exit when the cursor is flush
against a closing `)`:

```java
// SentinelInjector.backwardScan() — after computing prefix and hasDot
if (prefix.isEmpty() && !hasDot && tokenStart > 0) {
  char before = content.charAt(tokenStart - 1);
  if (before == ')' || before == ']') {
    // Cursor sits immediately after a complete expression — no new token started.
    // Return EXPRESSION context; the parser will produce no useful context,
    // and the engine should return an empty list.
    return new BackwardResult("", tokenStart, Context.EXPRESSION, null, false);
  }
}
```

With `Context.EXPRESSION` the `SemanticCompletionContext.valueContext`
is `true`.  The `SentinelParser` will classify the resulting
`__SENTINEL__` as `SIMPLE_NAME` with `inExpression = true` (because
the parser still puts it in an erroneous argument/expression position).
`KeywordProvider` for `SIMPLE_NAME` + `inExpression = true` →
`VALUE_EXPRESSIONS` = `{new, null, true, false, this, super}` — no
statement keywords.

**Alternative (more surgical)**: in `SentinelParser`, detect when the
sentinel sits inside an ErroneousTree immediately following a
`MethodInvocationTree` and classify the result as `ParsedSentinel.invalid()`,
which causes the engine to return an empty list.  This is harder to
implement correctly across all error-recovery patterns and is not
recommended.

---

## Group C — javac NPE on `@SuppressWarnings(va§ = "")` (1 test)

**Crashing test** (currently passes on JDK 26, crashes on JDK ≤ 21/22)
- `annotationArgument_namedElementPrefix_beforeEquals_suggestsElementName`

### Why it happens

After sentinel injection, the snippet becomes something like
`@SuppressWarnings(__SENTINEL__ = "")`.  When javac processes this
annotation during `task.analyze()`, `Lint.suppressionsFrom()` reads the
annotation's `values` array, which is null for the malformed annotation,
causing:

```
java.lang.NullPointerException: Cannot read field "values" because "values" is null
    at jdk.compiler/com.sun.tools.javac.code.Lint.suppressionsFrom(Lint.java:532)
```

This NPE is wrapped in `IllegalStateException` by `JavacTaskImpl.analyze()`
and propagates uncaught through both `TempSourceCompiler.compile()` (test
infrastructure) and `JavacRunner.analyze()` (production).

### Fix A — defensive catch in `TempSourceCompiler.compile()` and `JavacRunner.analyze()`

Both callers of `task.analyze()` currently only catch `IOException`.
Extend to also catch `IllegalStateException`:

```java
// TempSourceCompiler.java:60  and  JavacRunner.java:56
try {
  task.analyze();
} catch (IllegalStateException e) {
  // javac NPE on pathological input (e.g., sentinel in SuppressWarnings)
  // Return whatever was parsed; diagnostics may be incomplete.
  LOG.log(Level.FINE, e, () -> "javac analyze() crashed — returning partial result");
}
```

This is a broad defensive fix that makes the completion engine resilient
to any current or future javac crash on malformed sentinel-injected
source.

### Fix B — sentinel injection for annotation named-value positions (root cause)

The deeper fix is to not produce `@SuppressWarnings(__SENTINEL__ = "")`.
In `SentinelInjector`, when the backward scan detects that the cursor is
inside an annotation argument list AND what follows the cursor is
`= "..."` (an annotation named-value assignment), the sentinel should be
injected differently — e.g., produce `@SuppressWarnings(value)` instead
of `@SuppressWarnings(__SENTINEL__ = "")`.

This requires `SentinelContext.ANNOTATION_ARGUMENT` detection in the
backward scanner and special-casing the suffix rewrite.  Fix A is
sufficient in the short term; Fix B is a hardening improvement.

---

## Implementation order

1. **Fix A (crash guard)** — one-line catch in both `TempSourceCompiler`
   and `JavacRunner`; zero risk; enables the crashing test to run and
   fail with an assertion error instead of an exception.

2. **Fix 1 (ranker assignability gate)** — single method in
   `CompletionCandidateRanker`; enables the 4 non-return argument/
   initializer tests once the expected type is known.

3. **Fix 2 (return-type resolution)** — new `visitReturn` branch and
   helper in `TypeResolver`; enables `returnPosition_nonAssignableLocal_excluded`.

4. **Group B fix (SentinelInjector early exit)** — small guard in
   `backwardScan()`; enables the two after-complete-expression tests.
