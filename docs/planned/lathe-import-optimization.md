# Lathe — Import Optimization

## Problem

Lathe can already format Java files with google-java-format.
It can also insert missing imports from completion items,
with a planned code-action surface for the same insertion behavior.

The next import feature is optimization:
deduplicate imports,
sort import groups,
remove unused imports,
and (in post-v1) replace wildcard imports with direct imports.

## Goal

For v1, run a syntactic import optimization using the built-in capabilities of google-java-format before full-document formatting.
Specifically:
- Remove unused imports syntactically using `com.google.googlejavaformat.java.RemoveUnusedImports`.
- Sort import groups natively using `com.google.googlejavaformat.java.Formatter`.

In post-v1, we will introduce a semantic import optimizer that leverages compiler attribution to handle wildcard expansion and robust static import resolution.

The editor still invokes normal `textDocument/formatting`,
but Lathe transforms the source in memory before passing it to google-java-format.

The final LSP response should remain one full-document replacement edit when the formatted optimized source differs
from the original source.

## Non-goals

- Range-format import optimization.
- On-type import optimization.
- Rewriting source references from qualified names to simple names.
- Creating wildcard imports.
- Changing import thresholds based on style settings.
- Broad source cleanup beyond imports.

## Formatting Pipeline

Full-document formatting should become:

```text
current source
  -> syntactic import optimization (RemoveUnusedImports)
  -> google-java-format (Formatter)
  -> one full-document TextEdit
```

If syntactic import optimization fails due to a syntax error or a FormatterException, Lathe should fall back to standard formatting of the original source.

Range formatting and on-type formatting should continue to call google-java-format without import optimization.

## Post-v1: Semantic Import Optimization (Future Scope)

These features are deferred to post-v1 as they require compiler attribution and classpath information.

### Javac Inputs

Use the cached `AttributedFileAnalysis` when it matches the current document content/version.
The optimizer needs:

- `CompilationUnitTree.getImports()`
- `ImportTree.isStatic()`
- `ImportTree.getQualifiedIdentifier()`
- `Trees.getSourcePositions()`
- `Trees.getElement(TreePath)` from an attributed tree

If the cached analysis is missing, stale, or failed attribution around imports/names,
return the source unchanged.

### Used Symbol Collection

Walk the attributed tree with `TreePathScanner`.
The optimizer should collect symbols from `IdentifierTree` nodes,
because imports are only needed when the source uses simple names.

For normal imports, collect referenced `TypeElement` qualified names:

```text
List<String> values;
```

collects:

```text
java.util.List
```

Qualified source references do not require imports and should not be shortened:

```text
java.util.List<String> values;
```

does not force `import java.util.List;`.

For static imports, collect referenced static members from `IdentifierTree` nodes:

- static methods
- static fields
- enum constants

The static key is owner qualified name plus simple member name:

```text
java.util.Objects.requireNonNull
java.time.DayOfWeek.MONDAY
```

Method overloads intentionally collapse to the same key.
One direct static import covers all overloads with the same member name.

### Explicit Import Removal

For an explicit normal import:

```java
import java.util.List;
```

keep it only when `java.util.List` appears in the used type set.

For an explicit static import:

```java
import static java.util.Objects.requireNonNull;
```

keep it only when `java.util.Objects.requireNonNull` appears in the used static-member set.

Duplicate explicit imports should be removed.

### Wildcard Expansion

For a normal wildcard import:

```java
import java.util.*;
```

replace it with direct imports for used simple-name type references whose resolved qualified name belongs to that
package:

```java
import java.util.List;
import java.util.Map;
```

Do not import `java.lang` types.
Do not expand a wildcard when diagnostics indicate unresolved or ambiguous names.

### Static Wildcard Expansion

For a static wildcard import:

```java
import static java.util.Objects.*;
```

replace it with direct static imports for used static members whose owner is that type:

```java
import static java.util.Objects.isNull;
import static java.util.Objects.requireNonNull;
```

Enum constants follow the same rule:

```java
import static java.time.DayOfWeek.*;
```

may become:

```java
import static java.time.DayOfWeek.MONDAY;
```

### Ordering

Render the optimized import block as:

1. sorted normal imports
2. blank line when both groups are present
3. sorted static imports

google-java-format runs afterward and can normalize whitespace.
The optimizer should still render a clean block so tests do not depend on formatter side effects.

### Conservative Guards

Skip semantic optimization and return the original source when:

- there is no import block
- import source positions are unavailable
- the import block contains comments
- the current cached analysis does not match the source being formatted
- attribution failed badly enough that used symbols are unreliable
- diagnostics include unresolved or ambiguous imports/names

These guards can be relaxed later when tests cover the behavior.

## Implementation Shape

Add an import optimizer helper in `lathe-server`,
for example:

```java
final class ImportOptimizer {
  static String optimize(String source) {
    try {
      return RemoveUnusedImports.removeUnusedImports(source);
    } catch (FormatterException e) {
      return source;
    }
  }
}
```

Formatting integration should apply the optimizer only for full-document formatting before calling
`JavaFormatter.format(...)`.

## Tests

Unit tests should cover:

- duplicate explicit imports are removed
- normal imports are sorted (via Formatter)
- static imports are sorted separately (via Formatter)
- unused explicit normal imports are removed (via RemoveUnusedImports)
- unused explicit static imports are removed (via RemoveUnusedImports)
- normal wildcard imports are NOT expanded in v1 (retained as-is)
- static wildcard imports are NOT expanded in v1 (retained as-is)

Formatting integration tests should verify:

- full-document formatting runs optimization before google-java-format
- range formatting does not optimize imports
- on-type formatting does not optimize imports
