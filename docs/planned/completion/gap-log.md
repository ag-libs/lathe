# Lathe — Completion Gap Log

This file is a lightweight queue for current completion discrepancies.
Entries should be short and mechanical.

Move durable rules into [`expectations.md`](expectations.md).
Move process changes into [`discovery-workflow.md`](discovery-workflow.md).
Move resolved implementation notes to `docs/done/` only when they are useful after the fix.

## Status Values

| Status | Meaning |
|---|---|
| `new` | Captured but not triaged. |
| `accepted` | Lathe should support this behavior. |
| `deferred` | Valid behavior, but not in the current slice. |
| `non-goal` | Deliberately outside Lathe's current completion contract. |
| `covered` | Regression test or durable probe exists. |
| `fixed` | Implementation changed and verification passed. |

## Template

```text
## CQ-0001 — Short description

ID: CQ-0001
Status:
Tier:
Failure mode:
Owner component:

Project/file:
Probe command:
Cursor context:

IntelliJ or JDT behavior:
Lathe behavior:
Expected Lathe behavior:
Accepted edit, if relevant:

Regression target:
Notes:
```

## Open Entries

## Current Triage

Pause further explorer discovery until the accepted gaps below have been reduced and fixed.

Implementation order:

1. `CQ-0003` — fix first.
   In-token accepted-edit correctness is narrow,
   highly visible,
   and independent of deeper Java semantic modeling.
2. `CQ-0001` — fix second.
   Annotation enum and array value completion is accepted Java semantics,
   but it needs a more careful parser/engine slice.
3. `CQ-0002` — keep deferred until after v1.
   Method-reference completion is valid IDE behavior,
   but it should remain a separate design and implementation slice.

After `CQ-0003` and `CQ-0001` are fixed and covered by tests,
run a second Dropwizard/Helidon explorer pass with a different focus:
expected-type ranking,
static member fit,
constructor type completion,
import edits,
and accepted method-call insertion shape.

## CQ-0001 — Annotation enum value completion routes to element-name completion

ID: CQ-0001
Status: accepted
Tier: typed
Failure mode: missing-candidate
Owner component: SentinelParser / CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-auth/src/main/java/io/dropwizard/auth/Auth.java`

Probe command:
```bash
printf 'complete after "@Retention(" expect RetentionPolicy RUNTIME min 1\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-auth/src/main/java/io/dropwizard/auth/Auth.java
```

Related probe:
```bash
printf 'complete after "RetentionPolicy." expect RUNTIME min 1\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-auth/src/main/java/io/dropwizard/auth/Auth.java
```

Related array-value probes:
```bash
python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-auth/src/main/java/io/dropwizard/auth/Auth.java \
  complete after "ElementType." expect FIELD PARAMETER min 2

python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-auth/src/main/java/io/dropwizard/auth/Auth.java \
  complete after "@Target({ " expect ElementType FIELD PARAMETER min 1
```

Cursor context:
```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD, ElementType.PARAMETER })
```

IntelliJ or JDT behavior:
Expected IDE behavior is enum-value completion for the `Retention.value()` slot.
At `@Retention(`,
`RetentionPolicy` or `RUNTIME` should be reachable.
At `RetentionPolicy.`,
`RUNTIME` should be offered.

Lathe behavior:
The `@Retention` probes return only `value [Property] java.lang.annotation.RetentionPolicy`.
The `@Target` probes return only `value [Property] java.lang.annotation.ElementType[]`.
The log shows `sentinelCtx=ANNOTATION_ARGUMENT`,
including after `RetentionPolicy.` where member-access completion should apply.

Expected Lathe behavior:
Annotation value slots should use the annotation element return type as the expected type.
Enum-valued elements should offer matching enum constants,
and member access on the enum type inside an annotation value should offer enum constants.
Array-valued annotation elements should use the array component type inside `{}`.

Accepted edit, if relevant:
Accepting `RUNTIME` after `RetentionPolicy.` should produce `RetentionPolicy.RUNTIME`.
Accepting a shorthand enum constant after `@Retention(` can be revisited during design;
the first required behavior is not to route the value expression to annotation element-name completion.

Regression target:
`CompletionAnnotationTest` or the current `CompletionEngineTest` annotation-value section.

Notes:
This is a real Dropwizard probe with no diagnostics.

## CQ-0002 — Method-reference completion returns no candidates

ID: CQ-0002
Status: deferred
Tier: assistive
Failure mode: missing-candidate
Owner component: SentinelInjector / SentinelParser

Project/file:
`/home/ag-libs/git/helidon/dbclient/tracing/src/main/java/io/helidon/dbclient/tracing/DbClientTracingProvider.java`

Probe command:
```bash
printf 'complete after "List::" expect of min 1\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/dbclient/tracing/src/main/java/io/helidon/dbclient/tracing/DbClientTracingProvider.java
```

Related project/file:
`/home/ag-libs/git/helidon/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClientBuilder.java`

Related probe:
```bash
printf 'complete after "this::" expect url username password min 1\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClientBuilder.java
```

Cursor context:
```java
config.asNodeList().orElseGet(List::of)
connConfig.get("url").asString().ifPresent(this::url)
```

IntelliJ or JDT behavior:
Expected IDE behavior is method-reference completion after `Type::` and `this::`.

Lathe behavior:
No completions are returned.
The log shows `parsed valid=false sentinelCtx=null` after `List::` and after `this::`.

Expected Lathe behavior:
Eventually,
method-reference completion should offer compatible methods for the receiver and target functional interface.

Accepted edit, if relevant:
Accepting `of` after `List::` should produce `List::of`.
Accepting `url` after `this::` should produce `this::url`.

Future design:
Method-reference completion is post-v1 work.
The first implementation slice should be basic receiver-member listing,
not full smart compatibility filtering.
Add a `METHOD_REFERENCE` sentinel site,
detect `::`,
capture receiver text similarly to member access,
and route simple cases through member candidate generation.
`TypeName::` should offer static methods such as `List::of`;
`this::` should offer visible instance methods such as `this::url`;
ordinary expression receivers such as `service::` should offer instance methods.
Expected functional-interface filtering should be a later slice,
because robust compatibility needs the target type from contexts such as `orElseGet`,
`ifPresent`,
and `stream.map`.
Constructor references such as `TypeName::new` and array constructor references are also later slices.

Regression target:
Future method-reference completion test class or `CompletionEngineTest` method-reference section.

Notes:
This matches the existing deferred method-reference gap in the historical completion docs.

## CQ-0003 — In-token method completion does not replace the suffix

ID: CQ-0003
Status: accepted
Tier: presentation
Failure mode: bad-replacement-range
Owner component: CompletionSite / CompletionItemPresenter

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-configuration/src/main/java/io/dropwizard/configuration/BaseConfigurationFactory.java`

Probe command:
```bash
python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-configuration/src/main/java/io/dropwizard/configuration/BaseConfigurationFactory.java \
  complete 155:20 expect setFieldPath min 1
```

Raw item inspection:
```text
label: setFieldPath(List)
insertText: setFieldPath($1)
textEdit.range: 155:17-155:20
```

Cursor context:
```java
.set§FieldPath(e.getPath())
```

IntelliJ or JDT behavior:
Expected IDE behavior is insert/replace-safe in-token completion.
Accepting `setFieldPath` should replace the whole current identifier or use an insert/replace edit so the suffix is not duplicated.

Lathe behavior:
The menu contains `setFieldPath(List)`,
but the edit replaces only `set`.
Accepting the item would leave the existing `FieldPath` suffix after the inserted call text.

Expected Lathe behavior:
Completion at `.set§FieldPath(...)` should produce a source edit equivalent to replacing `setFieldPath`
with `setFieldPath($1)`,
or otherwise use LSP insert/replace semantics so accepting the item does not duplicate suffix text.

Accepted edit, if relevant:
The accepted edit should not produce `setFieldPath($1)FieldPath(e.getPath())`.

Regression target:
`CompletionInsertionTest` or the current `CompletionEngineTest` insertion/replacement section.

Notes:
This is a real Dropwizard probe with no diagnostics.

For the current discovery phase,
new entries should come only from Dropwizard or Helidon explorer probes.
