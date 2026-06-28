# Lathe — Declaration Name Completion

Proposed M2 completion enhancement.
Builds on the completion engine in `lathe-design.md` and the completion contract in
`planned/lathe-completion-expectations.md`.

## Motivation

When the user has already typed a Java type,
the next useful completion often is not another type or keyword.
It is the declaration name:

```java
ConnectionString §
String §
void connect(ConnectionString §)
private static final Duration §
```

Lathe currently suppresses these real declaration-name slots.
That is correct for avoiding noisy invalid candidates,
but it leaves a common IDE workflow unsupported:
suggesting variable,
field,
parameter,
and type-parameter names derived from the declared type and surrounding context.

This is assistive completion.
It should improve editing speed without weakening the existing rule that completion must not offer
syntactically invalid candidates.

## External Models

Eclipse JDT exposes `NamingConventions` for this problem.
It distinguishes local variables,
parameters,
instance fields,
static fields,
and static final fields;
derives names from either a Java name or a type name;
applies configured prefixes and suffixes;
excludes already-used names;
and orders proposals by relevance.

IntelliJ IDEA exposes the same concept through `JavaCodeStyleManager`.
Its API suggests names by variable kind,
property name,
expression,
and type,
then provides unique-name helpers that avoid shadowing.
It also has semantic-name extraction from expressions and combines semantic names with variable kind
and type.

Lathe should adopt the deterministic,
syntax-aware part of these models.
It should not attempt ML ranking,
project-wide naming mining,
or full-line generation.

References:

- Eclipse JDT `NamingConventions` API:
  `https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/NamingConventions.html`
- IntelliJ `JavaCodeStyleManager` API:
  `https://github.com/JetBrains/intellij-community/blob/master/java/java-psi-api/src/com/intellij/psi/codeStyle/JavaCodeStyleManager.java`

## Current Lathe Behavior

`SentinelParser` already classifies variable declarations with `SentinelContext.VARIABLE_DECLARATION`.
`CompletionEngine` then detects real declaration-name slots with `isRealNameSlot(...)` and returns
an empty completion list.

Existing tests intentionally assert that:

```java
class Test { String §; }
class Test { String S§; }
class Test { void m() { String S§; } }
```

return no candidates.

This design changes that behavior for real declaration-name slots only.
Ambiguous class-body recovery positions,
expression slots,
and type-reference positions keep their existing routing.

## Goals

- Suggest names for local variables,
  fields,
  parameters,
  catch parameters,
  enhanced-for parameters,
  and basic type parameters.
- Derive useful names from explicit declared types.
- Derive better names from initializer or argument expressions where javac/AST context makes that
  cheap and reliable.
- Avoid names already visible in the declaration scope.
- Preserve the user's typed prefix and replacement range.
- Keep candidate count small and deterministic.
- Use standard LSP completion items with ordinary text edits.

## Non-Goals

- AI,
  corpus,
  or project-history based naming.
- Full-line declaration generation.
- Rename refactoring.
- Getter/setter generation.
- Postfix templates.
- Naming-style configuration beyond a small built-in baseline in the first slice.
- Suggesting names in expression positions.

## Completion Sites

### Local Variables

```java
String §;
ConnectionString c§;
List<String> §;
Map<String, User> §;
```

Expected candidates:

- `String §` -> `string`,
  `value`,
  `text`
- `ConnectionString §` -> `connectionString`,
  `string`
- `List<String> §` -> `list`,
  `strings`,
  `values`
- `Map<String, User> §` -> `map`,
  `users`,
  `usersByString` in a later slice

### Fields

```java
private ConnectionString §;
private static final Duration §;
```

Expected candidates:

- instance fields use lower camel case:
  `connectionString`,
  `string`
- static final fields use screaming snake case:
  `DURATION`,
  `TIMEOUT`

Field prefix/suffix settings are deferred.
The first implementation should follow plain Java style.

### Parameters

```java
void connect(ConnectionString §) {}
void setName(String §) {}
```

Expected candidates:

- `ConnectionString §` -> `connectionString`,
  `string`
- setter-like contexts may use the property name:
  `setName(String §)` -> `name`,
  `string`

### Catch Parameters

```java
catch (IOException §) {}
```

Expected candidates:

- `e`
- `ex`
- `exception`
- `ioException`

Keep `e` high for catch blocks because it is the common Java idiom.

### Enhanced-For Parameters

```java
for (User § : users) {}
for (String § : names) {}
```

Expected candidates:

- derive from the element type first:
  `user`,
  `string`
- if the iterable expression has a plural-looking name,
  offer the singularized expression name first:
  `users` -> `user`,
  `names` -> `name`

Only simple English plural stripping is in scope initially:
`users` -> `user`,
`names` -> `name`,
`entries` -> `entry`.

### Type Parameters

```java
class Box<§> {}
interface Mapper<§> {}
class Pair<§> {}
```

Expected candidates:

- default:
  `T`
- result-like contexts:
  `R`
- map-like contexts:
  `K`,
  `V`
- pair-like contexts:
  `L`,
  `R`

Type-parameter completion should be a separate narrow path.
It should not interfere with type-bound completion such as:

```java
class C<T extends RuntimeEx§> {}
```

## Name Derivation Rules

### From Type Names

Split the simple type name into words using Java identifier case boundaries.

Examples:

| Type | Candidates |
|---|---|
| `String` | `string`, `value`, `text` |
| `ConnectionString` | `connectionString`, `string` |
| `HttpClient` | `httpClient`, `client` |
| `URI` | `uri` |
| `Optional<User>` | `optional`, `user` |
| `List<User>` | `users`, `userList`, `list` |
| `Set<Role>` | `roles`, `roleSet`, `set` |
| `Map<String, User>` | `users`, `userMap`, `map` |
| `Duration` | `duration`, `timeout` only when surrounding text indicates timeout |

Use imported/simple source spelling when available,
but prefer javac type mirrors for generic arguments where possible.

### From Expressions

Expression-derived names are a second slice.
They should rank before type-derived names when they are confident.

Examples:

| Initializer | Candidates |
|---|---|
| `getUser()` | `user` |
| `user.getName()` | `name`, `userName` |
| `findConnectionString()` | `connectionString` |
| `service.isReady()` | `ready` |
| `new ConnectionString(...)` | `connectionString` |
| `URI.create(...)` | `uri` |

Only inspect the expression AST.
Do not parse source text manually.

### Prefix Filtering

If the user typed a prefix,
only return names matching that prefix case-insensitively.

```java
ConnectionString c§
```

should offer `connectionString`,
not `string`.

The inserted text replaces the typed prefix only.

### Keyword Correction

Generated names must be valid Java identifiers and must not be Java keywords.
If a derived name is a keyword,
append a small suffix:

- `class` -> `clazz`
- `default` -> `defaultValue`
- other keywords -> `<name>Value`

### Collision Avoidance

Collect names already visible in the declaration scope:

- local variables and parameters in the enclosing method or block;
- fields in the enclosing class for field declarations;
- type parameters in the enclosing type or method for type-parameter declarations.

If a candidate conflicts,
offer the next unique variant:

```java
String value;
String §;
```

Candidates:

- `value2`
- `string`

Keep uniqueness deterministic.
Do not scan the entire project.

## Architecture

Add a focused provider:

```java
final class DeclarationNameCompletionProvider {
    List<CompletionCandidate> complete(DeclarationNameContext context)
}
```

The context should be a record containing:

- declaration kind:
  local,
  parameter,
  field,
  static field,
  static final field,
  catch parameter,
  enhanced-for parameter,
  type parameter;
- declared type text;
- declared `TypeMirror` when available;
- modifier flags;
- initializer or source expression path when available;
- typed prefix;
- excluded names.

Routing:

1. Keep `SentinelContext.VARIABLE_DECLARATION`.
2. When `CompletionEngine.isRealNameSlot(parsed)` is true,
   call `DeclarationNameCompletionProvider` instead of returning `List.of()`.
3. Add a separate type-parameter name context only for the declaration identifier position.
   Keep `TypeParameterTree` bound positions routed to type-reference completion.

Presentation:

- suggested local/parameter names use `CompletionItemKind.Variable`;
- field names use `CompletionItemKind.Field`;
- static final names may use `CompletionItemKind.Constant`;
- type-parameter names use `CompletionItemKind.TypeParameter`;
- labels and insert text are the same identifier;
- details should be short:
  `suggested local name`,
  `suggested parameter name`,
  `suggested field name`.

If `CandidateKind` does not currently cover these distinctions,
extend it narrowly rather than overloading unrelated type or value candidates.

## Testing

Add a dedicated test class:
`CompletionDeclarationNameTest`.

Initial tests:

- `declarationName_localString_offersStringAndValue`
- `declarationName_localConnectionString_offersConnectionString`
- `declarationName_localPrefix_filtersSuggestions`
- `declarationName_fieldConnectionString_usesFieldKind`
- `declarationName_staticFinalDuration_usesConstantStyle`
- `declarationName_parameterSetter_usesPropertyName`
- `declarationName_catchIOException_offersExceptionNames`
- `declarationName_enhancedForUsers_offersUser`
- `declarationName_existingLocalName_usesUniqueVariant`
- `declarationName_typeParameterDefault_offersT`
- `declarationName_typeParameterBound_keepsTypeReferenceCompletion`

Update or replace the existing suppression tests:

- `declarationName_fieldNameSlot_suppressesAllCandidates`
- `declarationName_localVarNameSlot_suppressesAllCandidates`

They should become positive tests for the new feature.
Keep separate negative tests proving that expression slots and ambiguous class-body recovery still do
not receive declaration-name suggestions.

## Rollout

Slice 1:

- explicit type to local,
  field,
  parameter,
  and static-final names;
- prefix filtering;
- collision avoidance;
- no expression-derived names yet.

Slice 2:

- catch parameters;
- enhanced-for parameters;
- generic collection element names.

Slice 3:

- initializer and argument expression-derived names.

Slice 4:

- type-parameter name suggestions.

This ordering keeps the first implementation small,
useful,
and easy to test against the current completion architecture.
