# Lathe — Completion Expectations

This document defines the completion contract Lathe should converge on.
It is informed by IntelliJ IDEA and Eclipse JDT,
but it is not a promise to clone either implementation.

The purpose is operational:
when a completion discrepancy is found,
we should be able to classify it as a bug,
a planned capability,
or a deliberate non-goal without re-litigating the basic rules.

## External Models

IntelliJ IDEA separates basic completion from type-matching completion.
Basic completion suggests reachable classes,
methods,
fields,
variables,
and keywords within the caret context.
Type-matching completion uses the expected type in assignments,
variable initializers,
return statements,
method arguments,
`new` expressions,
and chained expressions.

Eclipse JDT models completion as typed proposals with a completion context,
visibility-sensitive filtering,
insertion positions,
and relevance.
JDT relevance can depend on the expected type,
the current prefix,
and the surrounding source suffix.

Lathe should adopt the principles that matter for Java correctness and editing feel:
context-specific candidate sources,
visibility and accessibility filtering,
expected-type ranking,
stable insertion edits,
and presentation that separates labels from detail.

## Completion Tiers

### Basic Completion

Basic completion is the default contract for ordinary completion requests.

It should offer syntactically valid symbols reachable from the current caret position:

- visible locals,
parameters,
fields,
and methods;
- accessible members after member access;
- visible and importable types in type positions;
- packages and types in import or fully-qualified-name navigation;
- Java keywords that are legal at the current site.

Basic completion must prefer correctness over cleverness.
It must avoid invalid candidates,
even if another IDE sometimes offers them behind a second invocation or a quick-fix path.

### Typed Completion

Typed completion uses the expected Java type of the current expression slot.

Lathe may apply typed behavior automatically where it is cheap and reliable,
instead of requiring a separate smart-completion trigger.

Typed completion applies especially to:

- assignment right-hand sides;
- variable initializers;
- return expressions;
- method and constructor arguments;
- annotation element values;
- enum equality and switch cases;
- constructor type selection after `new`.

Typed behavior may either filter candidates or rank better candidates first.
The expected type must not make completion disappear unless the position has no valid slot,
such as an argument inside a zero-argument call.

### Assistive Completion

Assistive completion helps users reach symbols that are not already in simple lexical scope.

Examples:

- importable type names from the type index;
- static member fit candidates with static import edits;
- package-prefix navigation in code;
- future method-reference completion;
- future chain completion or conversion helpers.

Assistive completion should be optically conservative.
It should not flood lowercase value positions with unrelated type-index results.

### Presentation

Presentation covers the final LSP item shape:
label,
kind,
detail,
`labelDetails`,
`filterText`,
`insertText`,
snippets,
sort text,
replacement ranges,
and additional text edits.

Presentation must not change semantic filtering.
Candidate discovery and candidate display are separate concerns.

Accepted-completion edits are part of presentation.
The completion contract includes the source text produced when the user accepts a completion item,
not only whether the item appears in the menu.

The planned JDT LS-style presentation work is tracked in
[`../lathe-completion-presentation.md`](../lathe-completion-presentation.md).

## Universal Invariants

Completion should preserve these rules across all sites:

- Do not suggest candidates that are syntactically invalid at the caret.
- Do not suggest inaccessible members in ordinary completion.
- Do not suggest static-only members through an instance receiver.
- Do not suggest instance-only members through a static receiver.
- Preserve the user's explicit prefix.
- Replacement must cover the typed prefix and must not duplicate already-typed text.
- Import edits must be deterministic and minimal.
- `java.lang` types do not need import edits.
- Same-package types do not need import edits.
- Method candidates should insert a call shape that remains valid Java.
- Method candidates with parameters should insert `name($1)` or the client-equivalent snippet,
placing the caret inside the parentheses.
- Method candidates without parameters should insert `name()` and place the caret after the closing parenthesis.
- Method completion must not produce `name` without parentheses in ordinary expression or statement call contexts.
- Static import declaration completion is an exception:
static imported methods insert the bare member name because the import declaration supplies the call site later.
- Candidates that produce no value,
such as void methods,
must be excluded from value-required typed slots.
- `java.lang.Object` methods should be demoted or suppressed in value-sensitive simple-name contexts.
- Completion should return quickly from common editing states,
including syntactically incomplete code.
- A stale attributed snapshot may be used for speed,
but live-buffer parsing must still determine the current caret site and prefix.

## Site Expectations

This section starts with the high-value sites.
It should grow as real discrepancies are classified.

### Member Access

Examples:

```java
list.§
list.sub§
Collections.§
TimeUnit.SECONDS.§
```

Expected:

- instance receivers offer accessible instance members;
- static receivers offer accessible static members;
- enum type receivers offer enum constants and static members;
- enum constant receivers offer instance members of the enum type;
- inherited accessible members are included;
- members declared by `java.lang.Object` are ranked below domain members.

Forbidden:

- unrelated symbols;
- statement keywords;
- inaccessible members;
- static-only members on instance receivers;
- instance-only members on static receivers.

Insertion:

- replace only the typed member prefix;
- method items insert method-call text;
- method items with parameters place the caret inside `(...)`;
- zero-argument method items place the caret after `()`;
- static import declarations insert bare static member names.

### Simple Name Expression

Examples:

```java
void m() {
  co§
  String s = co§
}
```

Expected:

- visible locals,
parameters,
fields,
methods,
and static-imported members matching the prefix;
- legal expression keywords;
- uppercase prefixes may offer importable types;
- typed slots rank or filter by expected value type.

Forbidden:

- declaration-only keywords;
- variables in their own initializer;
- void methods when a value is required;
- unrelated type-index results for lowercase value prefixes.

Casing:

- Casing is a ranking hint,
not a hard semantic filter.
- Uppercase prefixes in method bodies should use mixed completion:
visible values remain eligible,
and importable type candidates may be merged in.
- Visible values that match the prefix,
such as constants and static fields,
should rank before importable type-index candidates when both are legal.
- Lowercase prefixes in ordinary value positions should avoid unrelated type-index candidates
unless the surrounding syntax requires a type.
- Type positions,
member access,
imports,
constructors,
and other syntactically typed sites should still use their normal site-specific candidate families.

`CQ-0009` is the regression guard for `LOG§` in a method body returning type-index results
while also preserving the visible static field `LOGGER`.

Insertion:

- accepted method candidates insert a call expression,
not only the method name;
- parameterized methods place the caret in the argument list;
- zero-argument methods place the caret after the call.

### Method And Constructor Arguments

Examples:

```java
accept(§)
accept(na§)
new Receiver(§)
```

Expected:

- visible values are offered;
- candidates assignable to the target parameter type rank first or are filtered;
- `true` and `false` are offered for boolean parameters;
- `null` is offered for reference parameters;
- enum constants are offered when the expected type is an enum;
- a zero-parameter target at the argument slot returns no candidates.

Forbidden:

- statement keywords;
- type names unless the prefix and mode justify assistive type completion;
- void methods;
- object methods in value-sensitive contexts.

Insertion:

- accepting a method candidate inside an argument slot must produce a value expression;
- parameterized method candidates place the caret inside the nested call's argument list;
- zero-argument method candidates complete to `name()` with the caret after the nested call.

### Assignment, Initializer, And Return Values

Examples:

```java
String s = §
field = §
return §
```

Expected:

- visible values are offered;
- assignable candidates rank first or incompatible candidates are filtered;
- boolean literal candidates follow boolean expected types;
- `null` follows reference expected types;
- `return` can be ranked high when the cursor is in a non-void method statement position.

Forbidden:

- void methods when an expression value is needed;
- statement-only keywords inside expression slots;
- incompatible boolean literals for non-boolean expected types.

### Type References

Examples:

```java
ArrayL§ field;
void m(ArrayL§ p) {}
List<ArrayL§> values;
class C extends AbstractL§ {}
class C implements Runn§ {}
throws IOEx§
@Over§
```

Expected:

- visible,
same-file,
`java.lang`,
dependency,
JDK,
and reactor type-index candidates;
- role-specific filtering for `extends`,
`implements`,
`throws`,
annotation type,
and constructor type positions;
- import edits for importable external types;
- package-prefix navigation when typing a fully-qualified name.

Forbidden:

- value keywords;
- interfaces in class `extends`;
- classes in `implements`;
- non-throwable types in `throws`;
- non-annotation types in annotation positions.

### Imports And Static Imports

Examples:

```java
import java.util.§
import static java.util.Collections.§
```

Expected:

- import declarations offer immediate subpackages and direct importable types;
- static import declarations offer packages,
types,
and static members once the receiver type is known;
- inserted type or member imports include a trailing semicolon when needed;
- package segment candidates do not append a semicolon.

Forbidden:

- deep descendant packages when an immediate package segment is required;
- ordinary instance members in static imports;
- statement keywords.

### Annotation Arguments And Values

Examples:

```java
@Deprecated(§)
@Deprecated(sin§ = "")
@Deprecated(since = §)
@Retention(§)
@Target({§})
```

Expected:

- annotation argument-name positions offer annotation element names;
- named values use the named element's return type as the expected type;
- shorthand values use the `value` element when present;
- enum-valued annotation elements should offer matching enum constants;
- array-valued annotation elements should use the component type inside `{}`.

Forbidden:

- unrelated local variables in annotation element-name slots;
- annotation element names in value slots;
- statement keywords inside annotation values.

### Method References

Examples:

```java
String::§
this::§
service::§
```

Expected:

- offer compatible methods for the receiver and target functional interface when available;
- distinguish static and instance method-reference forms.

Status:

- deferred.
Method-reference completion remains outside the current basic-completion contract.

### In-Token Completion

Example:

```java
accept(§connectionString)
```

Expected:

- candidates should replace the typed prefix and preserve or replace the suffix deliberately;
- accepting a candidate must not duplicate suffix text.
- method-call insertion must still preserve the intended caret placement:
inside `(...)` for parameterized methods and after `()` for zero-argument methods.

Status:

- planned.
This area is important because it strongly affects real editing feel.

## Non-Goals

The following are not part of the current completion contract:

- AI or full-line completion.
- Postfix templates.
- Live templates.
- Data-flow-heavy expression synthesis.
- Second or third invocation semantics that intentionally include inaccessible or unrelated symbols.
- Completion that searches the whole project regardless of Maven or JPMS reachability.
- Chain completion that invents multi-call expressions.

These may become separate assistive features later,
but basic completion should not depend on them.
