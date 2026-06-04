This document records the root-cause analysis and proposed fixes for the five disabled tests in `CompletionEngineTest`.
The tests cover three distinct gaps: inner-class constructor completion, enum constants in equality comparisons, and local variables inside inner-class methods.

---

## Gap 1 — Constructor completion for same-file inner classes (tests 1 & 2)

### Failing tests

- `typeReference_constructorCall_innerClassFromSameFile` — `new I§` should offer `Inner`
- `typeReference_constructorCall_privateStaticInnerClass` — `new B§` should offer `Builder`

### Root cause

`CONSTRUCTOR_CALL` with `argIndex < 0` routes to `completeSimpleNameTypeReferenceWithLang`,
which searches the type index (compiled class files) and then merges in `java.lang` types.
Nested classes defined in the same source file are not present in the type index, so they are never offered.

### Proposed fix

Add a third merge step after the type-index and `java.lang` merge:
scan the current compilation unit tree for `ClassTree` nodes, convert each matching node to a candidate via `CandidateFactory.typeElementCandidate`, filter by prefix and `typeReferenceRoleAllows`, and merge the result using the same `putIfAbsent` deduplication already used by `mergeLangTypes`.

The scanning method is a `TreePathScanner` that always recurses via `super.visitClass` so that nested classes inside top-level classes are also found.

This merge step is added to `completeSimpleNameTypeReferenceWithLang`, which is also called for `ANNOTATION_CONTEXT`, so in-file annotation types will similarly become available there as a side effect.

**Files changed**: `CompletionEngine.java` only — two new private methods (`mergeInFileTypes`, `proposeInFileTypeCandidates`).

---

## Gap 2 — Enum constants on the RHS of equality comparisons (tests 3 & 4)

### Failing tests

- `equalityComparison_enumLhs_suggestsEnumConstants` — `if (s == §)` should offer `Status.ACTIVE`, `Status.INACTIVE`, `Status.PENDING`
- `equalityComparison_enumLhs_suggestsUnqualifiedConstantsWithStaticImport` — `if (s == S§)` should offer `Status.ACTIVE`, `Status.INACTIVE`

### Root cause

Two issues compound here.

First, `TypeResolver.resolveInitializerValue` handles variable initializers, return statements, and assignments but not `==` / `!=` binary expressions.
The cursor in `if (s == §)` falls inside none of those patterns, so `expectedValue` stays `Unknown`.
The `inEqualityComparison` flag is already set correctly by `SentinelParser` and propagated through `ParsedSentinel` and `SemanticCompletionContext`, but nothing consumes it to generate candidates.

Second, even if the expected type were resolved to `Status`, no code path generates qualified-label candidates of the form `Status.ACTIVE`.
Regular `SimpleNameProvider` output contains locals and members with unqualified labels; enum constants of related types are never surfaced.

### Proposed fix

**Part A — resolve expected type from equality comparisons.**
Add a `visitBinary` handler inside the existing `TreePathScanner` in `resolveInitializerValue`.
When the binary operator is `EQUAL_TO` or `NOT_EQUAL_TO` and the cursor lies within the expression,
determine which operand does not contain the cursor and resolve its type via `snapshot.trees().getTypeMirror(otherPath)`.
Set that as the result, giving the semantic context an `ExpectedValue.Type` pointing at the other operand's type (e.g. `Status`).

**Part B — generate qualified enum constant candidates.**
Add `proposeEnumConstantCandidates(TypeElement enumType, String prefix)` to `CandidateGenerator`.
It enumerates `ENUM_CONSTANT` members of the enum type and creates candidates whose `filterText`, `label`, and `insertText` are all `EnumType.CONSTANT`.
Prefix matching uses the full qualified label (so prefix `"S"` matches `"Status.ACTIVE"`).
The candidate's `valueType` is set to the enum type so the ranker can score it against the expected value.

**Part C — wire into `completeSimpleName`.**
Add `enumEqualityCandidates` in `CompletionEngine` that gates on `parsed.inEqualityComparison()` and `semanticContext.expectedValue()` being an enum type, then delegates to `CandidateGenerator.proposeEnumConstantCandidates`.
Merge the result into the stream alongside the javac and keyword candidates.

**Files changed**: `TypeResolver.java`, `CandidateGenerator.java`, `CompletionEngine.java`.

---

## Gap 3 — Local variables not visible inside inner-class methods (test 5)

### Failing test

- `simpleName_innerClassMethod_localsVisible` — `accept(loc§)` inside `Inner.process` should offer `localVar`

### Root cause

Three private scanner methods — `SimpleNameProvider.findScopeMethodPath`, `TypeResolver.findScopeMethodPath`, and `TypeResolver.findMethodPath` — use a `visitClass` override that returns `null` (stops tree recursion) for any class whose simple name does not match the target `className`.

For an inner class `Inner` nested inside `Outer`, the scanner starts at the root, visits `Outer` (whose name does not match `"Inner"`), returns `null`, and never descends into `Outer`'s body.
As a result, `Inner`'s methods are never found, the method path is `null`, and `SimpleNameProvider.addMethodLocals` is a no-op.
The sentinel parser correctly reports `enclosingClass = "Inner"` and `enclosingMethod = "process"`, but the downstream lookup cannot locate them.

### Proposed fix

In all three methods, change `visitClass` to unconditionally call `super.visitClass(node, unused)`.
Move the class-name restriction into `visitMethod`: before recording the path, check that the method's immediate parent in the tree path is a `ClassTree` whose simple name matches `className`.
This ensures that only methods directly enclosed in the expected class are recorded, while still allowing traversal through outer classes to reach them.

The position-based disambiguation in `findScopeMethodPath` (prefer the method that contains the cursor offset) is preserved and handles multiple methods with the same name within the same class.

**Files changed**: `SimpleNameProvider.java`, `TypeResolver.java`.
