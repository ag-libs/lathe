# Fix: False "Unused" Hints for Record Component Backing Fields

## Problem

`UnusedDeclarationScanner` emits spurious `Unused` hint diagnostics for every component of a Java `record`.
For example, `AppServerConfig` (41 components) produces 40 false hints — one per non-`static` component — even though every component is publicly accessible via its accessor method.

## Root Cause

When javac's `TreePathScanner` visits a record's `ClassTree`, it exposes **two distinct sets of variable nodes** for each component:

1. **`PARAMETER` nodes** inside the canonical constructor's `MethodTree` — these are correctly skipped by the existing `!(parent instanceof MethodTree)` guard.
2. **Synthesized `private final` backing field nodes** directly in the `ClassTree` — these have `kind=FIELD`, `mods=[private, final]`, `parent=RECORD` and pass the `PRIVATE` check, so they are added to `privateFields`.

The **accessor methods** (e.g., `name()`, `environment()`) are also synthetic and therefore not present as source `MethodTree` nodes.
The scanner never visits them, so the backing fields are never marked as referenced — and every one is reported as unused.

Confirmed from probe output on `AppServerConfig.java`:
```
[var] name=name          kind=FIELD  mods=[private, final]  parent=RECORD
[var] name=environment   kind=FIELD  mods=[private, final]  parent=RECORD
...  (41 entries total, all non-static)
[var] name=INSTANCE_COUNT kind=FIELD mods=[public, static, final]  parent=RECORD
```

## How ECJ Avoids This

Eclipse's compiler (ECJ) uses an **architectural** separation rather than an explicit runtime guard:

- Record component backing fields are represented as `SyntheticFieldBinding` instances.
  They are **never added to the type's regular field list** (`SourceTypeBinding.fields`);
  instead they are stored in a separate `synthetics` table via `addSyntheticRecordState()`.
- The unused-field warning is emitted exclusively from `FieldDeclaration.analyseCode()`.
  Because there is no `FieldDeclaration` node for a backing field, that method is never called for them.
- `ProblemReporter.unusedPrivateField()` contains no record-specific guard — it doesn't need one.

ECJ's canonical discriminator is `FieldBinding.isRecordComponent()`:
```java
public boolean isRecordComponent() {
    return this.declaringClass != null
        && this.declaringClass.isRecord()
        && !this.isStatic()
        && this instanceof SyntheticFieldBinding;
}
```
Three conditions: enclosing class is a record, field is not static, field is a synthetic binding.
This method guards other paths (annotation propagation, bytecode emission) but the unused-field analysis path never reaches it by design.

## Proposed Fix for Lathe

Lathe's `TreePathScanner` operates on the source AST after attribution.
It **does** see the synthesized backing field `VariableTree` nodes (javac includes them in `ClassTree.getMembers()`), so we must guard explicitly.

The ECJ `isRecordComponent()` logic translates directly to the tree visitor:

```java
if (parent instanceof ClassTree classTree) {
    final boolean isRecordComponent =
        classTree.getKind() == Tree.Kind.RECORD
            && !element.getModifiers().contains(Modifier.STATIC);
    if (!isRecordComponent
        && element.getModifiers().contains(Modifier.PRIVATE)
        && !EXCLUDED_FIELD_NAMES.contains(node.getName().toString())) {
      privateFields.put(element, candidateFor(node, node.getName().toString()));
    }
}
```

This precisely mirrors the ECJ rule:

| Condition | ECJ check | Lathe equivalent |
|---|---|---|
| Enclosing class is a record | `declaringClass.isRecord()` | `classTree.getKind() == Tree.Kind.RECORD` |
| Field is not static | `!isStatic()` | `!element.getModifiers().contains(Modifier.STATIC)` |
| Field is a backing field | `instanceof SyntheticFieldBinding` | implied: all non-static record fields in the AST are backing fields |

### What This Correctly Preserves

- **Explicitly declared `private static` fields in records** (e.g. `private static final Logger LOG`) are still tracked and reported if unused.
- **Private fields in regular classes** are unaffected.
- **Local variables and private method** tracking is unaffected.

### What the Current (Too-Broad) Fix Does Wrong

The interim fix committed as part of initial investigation uses:
```java
classTree.getKind() != Tree.Kind.RECORD
```
This skips **all** fields in records — including explicit `private static` fields that a developer adds to the record body.
That fix should be replaced with the targeted check above.

## Testing

Add a test case in `UnusedDeclarationScannerTest` covering:

1. A record with several components — zero hints expected for the components themselves.
2. A record with an **unused** `private static final` field — one hint expected (static field not a component).
3. A record with an **unused** `private static` method — one hint expected.
4. A regular class with a `private` field — behaviour unchanged.
