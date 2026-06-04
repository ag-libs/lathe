# Lathe — Import Optimization

## Problem

Lathe can already format Java files with google-java-format.
It can also insert missing imports from completion items,
with a planned code-action surface for the same insertion behavior.

The next import feature is optimization:
deduplicate imports,
sort import groups,
remove unused imports,
and replace wildcard imports with direct imports when javac can prove which symbols are used.

## Goal

Run conservative import optimization before full-document formatting.
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
  -> semantic import optimization when a current attributed tree is available
  -> google-java-format
  -> one full-document TextEdit
```

If semantic import optimization cannot run confidently, Lathe should skip it and still run google-java-format.

Range formatting and on-type formatting should continue to call google-java-format without import optimization.

## Javac Inputs

Use the cached `AttributedFileAnalysis` when it matches the current document content/version.
The optimizer needs:

- `CompilationUnitTree.getImports()`
- `ImportTree.isStatic()`
- `ImportTree.getQualifiedIdentifier()`
- `Trees.getSourcePositions()`
- `Trees.getElement(TreePath)` from an attributed tree

If the cached analysis is missing, stale, or failed attribution around imports/names,
return the source unchanged.

## Used Symbol Collection

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

## Explicit Import Removal

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

## Wildcard Expansion

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

## Ordering

Render the optimized import block as:

1. sorted normal imports
2. blank line when both groups are present
3. sorted static imports

google-java-format runs afterward and can normalize whitespace.
The optimizer should still render a clean block so tests do not depend on formatter side effects.

## Conservative Guards

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
  static String optimize(String source, AttributedFileAnalysis analysis) { ... }
}
```

The helper should:

1. read imports and source ranges from the compilation unit
2. collect used type and static-member keys with a scanner
3. derive the optimized normal and static import sets
4. replace only the import block in the source string
5. return the original source unchanged when any guard fails

Formatting integration should apply the optimizer only for full-document formatting before calling
`JavaFormatter.format(...)`.

## Tests

Unit tests should cover:

- duplicate explicit imports are removed
- normal imports are sorted
- static imports are sorted separately
- unused explicit normal imports are removed
- unused explicit static imports are removed
- normal wildcard imports are expanded to direct imports
- static wildcard imports are expanded to direct imports
- enum constants from static wildcards are expanded
- qualified source references are not shortened or imported
- comments inside the import block cause optimization to be skipped
- missing or stale analysis leaves source unchanged

Formatting integration tests should verify:

- full-document formatting runs optimization before google-java-format
- range formatting does not optimize imports
- on-type formatting does not optimize imports
