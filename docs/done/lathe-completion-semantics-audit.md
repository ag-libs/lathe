# Completion Semantics Audit

This document is a historical gap-discovery aid for completion behavior.
It is not the normative design.

Current completion expectations live in
[`../planned/lathe-completion-expectations.md`](../planned/lathe-completion-expectations.md); active
gap tracking is in the registry [`../gaps.md`](../gaps/gaps.md).

The goal is to make Java syntax-site behavior explicit before implementation fixes are made.
Each row should eventually map to active tests, a documented gap, or a deliberate non-goal.

## Matrix

| Site | Example | Expected candidates | Forbidden candidates | Current coverage | Status |
|---|---|---|---|---|---|
| Member access, instance receiver | `list.sub§` | accessible instance members | unrelated prefix, inaccessible members, static-only members | strong | covered |
| Member access, static receiver | `Collections.empty§` | accessible static members | instance-only members | partial | covered |
| Member access, enum type | `TimeUnit.§` | enum constants and static members | instance-only members | partial | covered |
| Member access, enum constant | `TimeUnit.SECONDS.to§` | instance members | enum constants and static-only members | partial | discovery |
| Package navigation in code | `java.util.§` | immediate sub-packages and direct types | keywords, deep descendants | strong | covered |
| Import declaration | `import java.util.§` | packages and importable types | static members | strong | covered |
| Static import declaration | `import static TimeUnit.§` | static methods, fields, enum constants | instance members | partial | covered |
| Static-imported enum constant as simple name | `import static TimeUnit.SECONDS; SE§` | `SECONDS` | unrelated constants | partial | covered |
| Empty class body | `class C { § }` | member declaration starters | statement keywords | partial | covered |
| Enum body after constants | `enum E { A; § }` | member declarations and nested types | value keywords | partial | discovery |
| Enum constructor argument | `enum E { A(§); E(String s) {} }` | value expressions | statement keywords | partial | covered |
| Empty method statement | `void m() { § }` | statement starters and visible values | class-body-only keywords | partial | covered |
| Return expression | `return §` | value expressions | statement-only keywords | strong | covered |
| Throw expression | `throw §` | throwable-producing expressions | statement-only keywords | partial | covered |
| Variable initializer, reference type | `String s = §` | assignable values, `null`, constructible values | void methods, Object methods, boolean literals | partial | covered |
| Variable initializer, boolean type | `boolean b = §` | boolean values, `true`, `false` | `null`, void methods | partial | covered |
| Argument position | `accept(§)` | visible values, assignable values | void methods, Object methods when value-sensitive | strong | covered |
| Zero-parameter argument slot | `noArgs(§)` | none | all candidates | strong | covered |
| Constructor argument slot | `new Receiver(§)` | visible values, assignable values | type names, Object methods | strong | covered |
| Constructor type | `new Str§` | constructible accessible classes | interfaces, enums, abstract classes, inaccessible/private-constructor classes | strong | covered |
| Field type | `ArrayD§ field` | visible/importable types | value keywords | partial | covered |
| Method parameter type | `void m(ArrayD§ p)` | visible/importable types | value keywords | partial | covered |
| Method return type | `ArrayD§ m()` | visible/importable types, `void` where appropriate | value keywords | partial | covered |
| Generic type argument | `List<§>` | visible/importable types | keywords | partial | covered |
| Cast type | `(Str§) value` | visible/importable types | value keywords | none | discovery |
| Class extends | `class C extends §` | non-final accessible classes | interfaces, enums, records, final classes | strong | covered |
| Class implements | `class C implements §` | interfaces | classes, enums, records | strong | covered |
| Interface extends | `interface I extends §` | interfaces | classes, enums, records | strong | covered |
| Record implements | `record R() implements §` | interfaces | classes, enums, records | strong | covered |
| Throws clause | `throws IOEx§` | `Throwable` subtypes | non-Throwable types, interfaces, enums | partial | covered |
| Annotation type | `@Over§` | annotation types | ordinary classes, interfaces, enums | partial | covered |
| Annotation empty argument list | `@Deprecated(§)` | annotation element names: `since`, `forRemoval` | annotation types such as `Override`, `SuppressWarnings`; statement/value keywords | partial | covered |
| Annotation single value | `@SuppressWarnings(§)` | values assignable to the annotation's `value` element | statement keywords, unrelated types, void methods | none | discovery |
| Annotation named element | `@SuppressWarnings(va§ = "")` | annotation element names | local variables, fields, methods, types | partial | covered |
| Annotation named value | `@Deprecated(since = §)` | values assignable to the named element | annotation element names such as `since`, `forRemoval`; statement keywords, unrelated types, void methods | partial | covered |
| Annotation enum value | `@Retention(§)` | `RetentionPolicy` constants or assignable enum values | unrelated enum constants, booleans, arbitrary values | none | discovery |
| Annotation array value | `@Target({§})` | values assignable to the array component type | unrelated values and statement keywords | none | discovery |
| Annotation declaration body | `@interface A { § }` | annotation element/member declaration starters | method-body statements, value keywords | none | discovery |
| Annotation element return type | `@interface A { Str§ value(); }` | legal annotation element return types | value keywords, illegal return types | none | discovery |
| Annotation element default value | `@interface A { int value() default § }` | values assignable to the element return type | statement keywords, unrelated values | none | discovery |
| Declaration name slot | `class §`, `String §` | none or narrowly scoped name snippets | types, values, statement keywords | partial | covered |
| Method reference | `String::§` | compatible methods | incompatible members | none | deferred |
| Switch enum case | `case §` | enum constants of selector type | unrelated values | none | discovery |
| Module directive | `requires §`, `exports §` | directive-specific modules/packages/types | unrelated symbols | none | discovery |
| In-token completion | `accept(§connectionString)` | insert/replace-safe candidates | duplicate suffix insertion | future work | open |

## Current Use

Use this matrix as historical context when expanding
[`../planned/lathe-completion-expectations.md`](../planned/lathe-completion-expectations.md).
New discrepancies should be recorded as `CQ-*` entries in the gap registry
[`../gaps.md`](../gaps/gaps.md).
