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

## Completed Scope

Full-document formatting now uses google-java-format's combined import-fixing formatter:

```java
new Formatter().formatSourceAndFixImports(content)
```

This runs the google-java-format import orderer,
removes unused imports syntactically,
and then formats the source.

In post-v1, we will introduce a semantic import optimizer that leverages compiler attribution to handle wildcard expansion and robust static import resolution.

The editor still invokes normal `textDocument/formatting`.

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

Full-document formatting now runs:

```text
current source
  -> google-java-format import ordering
  -> google-java-format unused import removal
  -> google-java-format source formatting
  -> one full-document TextEdit
```

If formatting or import fixing fails due to a syntax error or a `FormatterException`,
Lathe returns no edits.

Lathe advertises full-document formatting.
Range formatting and on-type formatting are not advertised.

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

`JavaFormatter.format(...)` calls `Formatter.formatSourceAndFixImports(...)`.
This keeps formatting and import optimization on the existing formatting path.

## Tests

Unit tests cover:

- an unformatted source is reformatted to the import-optimized result
- an already formatted and import-optimized source returns no edits
- an unused explicit normal import is removed
- syntax errors return no edits

Remaining useful follow-up tests:

- duplicate explicit imports are removed
- static imports are sorted separately
- unused explicit static imports are removed
- normal wildcard imports are NOT expanded in v1
- static wildcard imports are NOT expanded in v1
