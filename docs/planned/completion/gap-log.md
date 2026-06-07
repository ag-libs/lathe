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

Five accepted completion-quality gaps are currently open from the
`DropwizardResourceConfig` explorer pass.

`CQ-0008`,
`CQ-0010`,
`CQ-0011`,
`CQ-0014`,
and `CQ-0016` are documented probe gaps and need tests before implementation.

`CQ-0001`,
`CQ-0003`,
`CQ-0004`,
`CQ-0005`,
`CQ-0006`,
`CQ-0007`,
`CQ-0009`,
`CQ-0012`,
`CQ-0013`,
`CQ-0015`,
and `CQ-0017` are fixed and covered by regression tests.
The follow-up Dropwizard/Helidon explorer pass covered expected-type ranking,
static member fit,
constructor type completion,
import edits,
and accepted method-call insertion shape without finding a new high-confidence gap.

`CQ-0002` remains deferred.
Method-reference completion is valid IDE behavior,
but it should remain a separate post-v1 design and implementation slice.

Next completion work should either:

- fix the accepted `DropwizardResourceConfig` gaps below;
- or run a new explorer pass with a different focus area and record any confirmed discrepancies as new `CQ-*` entries.

## CQ-0004 — Dotted member access can fall back to simple-name completion in incomplete assignments

ID: CQ-0004
Status: fixed
Tier: typed
Failure mode: wrong-candidate-set
Owner component: SentinelParser / CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'inject "resources = event." at 239\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Related probe:
```bash
printf 'inject "responseType.getTypeBindings()." at 273\nlog 12\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
resources = event.§
responseType.getTypeBindings().§
```

IntelliJ or JDT behavior:
Expected IDE behavior is receiver-member completion after the explicit dot.

Lathe behavior:
The `event.` probe returns `resources`,
`new`,
`null`,
`super`,
and `this`.
The `responseType.getTypeBindings().` probe returns `handler`,
`new`,
`null`,
`super`,
and `this`.
The log shows `hasDot=true` but `sentinelCtx=SIMPLE_NAME`.

Expected Lathe behavior:
When the injector captured an explicit receiver and dot,
the parser should not route the site to simple-name completion.
`event.` should offer `ApplicationEvent` members such as `getResourceModel`.
`responseType.getTypeBindings().` should offer `TypeBindings` members such as `getBoundType`.

Accepted edit, if relevant:
Accepting `getResourceModel` after `resources = event.` should produce
`resources = event.getResourceModel()`.

Regression target:
`CompletionMemberAccessTest.memberAccess_inIncompleteAssignment_doesNotLeakSimpleNameCandidates`.

Notes:
This is the same parser-recovery failure shape in assignment and ternary-adjacent code.
Fixed by forcing explicit-dot sites with a captured receiver out of javac's
simple-name recovery path.

## CQ-0005 — Type-dot completion misses the `class` literal

ID: CQ-0005
Status: fixed
Tier: typed
Failure mode: missing-candidate
Owner component: CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'inject "Object."\nlog 40\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Related probes:
```bash
printf 'inject "Class." at 239\nlog 12\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java

printf 'inject "Class<?>." at 239\nlog 12\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
Object.§
Class.§
Class<?>.§
```

IntelliJ or JDT behavior:
Expected IDE behavior is to suggest the `class` literal after a type name.

Lathe behavior:
No completions are returned.
The log shows `sentinelCtx=TYPE_REFERENCE`.

Expected Lathe behavior:
Type-reference member completion should offer `class` when the receiver is a resolvable type.

Accepted edit, if relevant:
Accepting `class` after `Object.` should produce `Object.class`.

Regression target:
`CompletionTypeReferenceTest.typeReference_typeDot_suggestsClassLiteral`.

Notes:
Lowercase package receivers such as `java.util.` should not receive `class`.
Fixed for static type member access and for javac-recovered type-reference sites with a
type-like receiver.

## CQ-0006 — Enum switch case labels do not use the selector enum type

ID: CQ-0006
Status: fixed
Tier: typed
Failure mode: wrong-candidate-set
Owner component: SentinelParser / CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'complete after "case " expect RESOURCE_METHOD SUB_RESOURCE_LOCATOR min 2\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Related probe:
```bash
printf 'inject "switch (method.getType()) { case " at 267\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
switch (method.getType()) {
    case §RESOURCE_METHOD:
```

IntelliJ or JDT behavior:
Expected IDE behavior is enum-constant completion for the switch selector type.

Lathe behavior:
The existing `case ` site returns locals,
fields,
methods,
and statement keywords.
The injected single-line switch probe has the same simple-name fallback.

Expected Lathe behavior:
`case ` inside `switch (method.getType())` should offer `RESOURCE_METHOD`
and `SUB_RESOURCE_LOCATOR`.

Accepted edit, if relevant:
Accepting `RESOURCE_METHOD` should produce `case RESOURCE_METHOD:`
without adding `final` or a qualified enum type.

Regression target:
Future switch-case completion test in the completion suite.

Notes:
This is distinct from general enum member access because case labels use the selector type
and should not require a qualified enum receiver.

## CQ-0007 — Qualified enum type-dot completion labels and filters incorrectly

ID: CQ-0007
Status: fixed
Tier: presentation
Failure mode: bad-label-or-filter
Owner component: CompletionEngine / CompletionItemPresenter

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'complete after "ApplicationEvent.Type." expect INITIALIZATION_APP_FINISHED min 1\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Related probe:
```bash
printf 'complete after "ApplicationEvent.Type.I" expect INITIALIZATION_APP_FINISHED min 1\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
event.getType() == ApplicationEvent.Type.§INITIALIZATION_APP_FINISHED
```

IntelliJ or JDT behavior:
Expected IDE behavior is enum-constant completion whose visible labels and filtering use
the constant names.

Lathe behavior:
After `ApplicationEvent.Type.`,
Lathe returns labels such as `Type.INITIALIZATION_APP_FINISHED`.
After `ApplicationEvent.Type.I`,
Lathe returns no completions.
The log shows `hasDot=true` but `sentinelCtx=SIMPLE_NAME`.

Expected Lathe behavior:
The row should be filterable by `I` and expose the constant label
`INITIALIZATION_APP_FINISHED`.
The accepted edit should insert only the constant suffix after the existing receiver.

Accepted edit, if relevant:
Accepting `INITIALIZATION_APP_FINISHED` after `ApplicationEvent.Type.`
should produce `ApplicationEvent.Type.INITIALIZATION_APP_FINISHED`.

Regression target:
`CompletionMemberAccessTest.memberAccess_nestedEnumReceiver_usesConstantLabels`.

Notes:
The no-prefix site had enough semantic information to find constants,
but the label/filter contract was wrong.
Fixed by routing explicit dotted enum type receivers through member access instead of
simple-name recovery.

## CQ-0008 — Enum comparison RHS does not use the expected enum type

ID: CQ-0008
Status: fixed
Tier: typed
Failure mode: missing-candidate
Owner component: CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'inject "if (event.getType() == " at 239\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
if (event.getType() == §) {
```

IntelliJ or JDT behavior:
Expected IDE behavior is enum-value completion from the left-hand operand type.

Lathe behavior:
Lathe returns `null`,
`resources`,
`new`,
`super`,
and `this`.
It does not offer `ApplicationEvent.Type` enum constants.

Expected Lathe behavior:
The equality right-hand side should use the left-hand enum type as the expected type
and offer constants such as `INITIALIZATION_APP_FINISHED`.

Accepted edit, if relevant:
Accepting `INITIALIZATION_APP_FINISHED` should produce a valid comparison against
`ApplicationEvent.Type.INITIALIZATION_APP_FINISHED`,
either by inserting the qualified constant or by adding an import-safe shorthand if that is later designed.

Regression target:
Future expected-enum comparison completion test.

Notes:
This is similar in spirit to annotation enum expected-type completion,
but the expected type comes from a binary comparison instead of an annotation element.

## CQ-0009 — Uppercase simple-name completion hides visible static fields in method bodies

ID: CQ-0009
Status: fixed
Tier: typed
Failure mode: missing-candidate
Owner component: SentinelParser / CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'inject "LOGGER" at 239\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Related probe:
```bash
printf 'inject "LOG" at 239\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
private static final Logger LOGGER = LoggerFactory.getLogger(DropwizardResourceConfig.class);

public void onEvent(ApplicationEvent event) {
    LOG§
}
```

IntelliJ or JDT behavior:
Expected IDE behavior is mixed simple-name completion in expression/statement positions,
including visible fields and matching types.

Lathe behavior:
Before the fix,
the `LOG` and `LOGGER` probes returned type-index entries such as `Logger` and `LoggerFactory`,
but they do not return the visible static field `LOGGER`.
The log shows `sentinelCtx=TYPE_REFERENCE`.

Expected Lathe behavior:
Uppercase prefixes in a method body should not suppress ordinary visible values.
`LOGGER` should appear alongside any relevant type candidates.

Accepted edit, if relevant:
Accepting `LOGGER` should insert `LOGGER` without an import edit.

Regression target:
`CompletionSimpleNameTest.simpleName_uppercasePrefix_inRecoveredStatementsIncludesVisibleValuesAndTypes`.

Notes:
Fixed by routing uppercase,
receiverless statement sites recovered as ordinary type references through mixed simple-name completion.
That path intentionally skips expected-value ranking,
because recovery can borrow a following assignment's expected type and incorrectly filter visible values.

## CQ-0010 — Method label details can render without a separator before the return type

ID: CQ-0010
Status: closed (editor capability)
Tier: presentation
Failure mode: bad-label-details
Owner component: CandidateFactory / CompletionItemPresenter

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
python3 - <<'PY'
import json
import sys
from pathlib import Path
sys.path.insert(0, 'dev')
from lsp import LatheClient, find_workspace_root

p = Path('/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java')
with LatheClient.start(find_workspace_root(p), debug=True) as c:
    c.open(p)
    items = c.completion(p, 249, 25)
    for item in items:
        if item.get('filterText') == 'debug':
            print(json.dumps(item, indent=2, sort_keys=True))
            break
PY
```

Cursor context:
```java
LOGGER.de§
```

IntelliJ or JDT behavior:
Expected IDE behavior is visually separated method signature and return type,
for example `debug(String) void` or an equivalent aligned return-type column.

Lathe behavior:
The raw item uses:

```json
"label": "debug",
"labelDetails": {
  "detail": "(String)",
  "description": "void"
}
```

Some clients render that as `debug(String)void`,
with no separator between the closing parenthesis and return type.

Expected Lathe behavior:
Method completion label details should include or otherwise produce a separator before the return type
on clients that concatenate `label`,
`labelDetails.detail`,
and `labelDetails.description`.

Accepted edit, if relevant:
Not applicable.

Regression target:
Client-side completion menu rendering probe.

Notes:
This is presentation-only.
`detail` already contains the full fallback string `Logger.debug(String) : void`.
JDT LS sends the same label-details shape:
method parameters in `labelDetails.detail`,
return type alone in `labelDetails.description`,
and the ` : ` separator only in the fallback `detail` string.
The spacing should be handled by the Neovim completion renderer,
not by changing Lathe's server-side LSP data away from the JDT LS shape.

## CQ-0011 — Constructor invocation keywords can be offered when an explicit invocation already exists

ID: CQ-0011
Status: accepted
Tier: semantic
Failure mode: invalid-keyword-candidate
Owner component: KeywordProvider / SentinelParser

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'inject "this" at 63\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
public DropwizardResourceConfig(@Nullable MetricRegistry metricRegistry) {
    this§
    super();
    ...
}
```

IntelliJ or JDT behavior:
Expected IDE behavior is to avoid suggesting an explicit constructor invocation
when the constructor body already contains one.
Java permits at most one explicit constructor invocation,
and it must be the first statement in the constructor body.

Lathe behavior:
Lathe offers the `this` keyword at the first statement slot before an existing `super();`.
Accepting it as a constructor invocation starter would leave both `this...` and `super();`
in the same constructor body.

Expected Lathe behavior:
`this` and `super` constructor-invocation keyword candidates should be offered only when the current
constructor does not already contain an explicit `this(...)` or `super(...)` invocation.
They should also be constrained to the first-statement position.

Accepted edit, if relevant:
Not applicable until constructor-invocation completion adds call-shape snippets.

Regression target:
Future keyword completion test for constructor first-statement rules.

Notes:
This gap is about `this` and `super` as explicit constructor invocation starters.
It should not block ordinary `this` expression completion,
`this.member` access,
or `super.member` access where those are otherwise legal.

## CQ-0012 — Member completion after assignment can be misclassified as a type reference

ID: CQ-0012
Status: fixed
Tier: semantic
Failure mode: missing-candidate
Owner component: SentinelParser / CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'inject "LOGGER.de" at 239\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Control probe:
```bash
printf 'inject "LOGGER.de" at 249\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
public void onEvent(ApplicationEvent event) {
    if (event.getType() == ApplicationEvent.Type.INITIALIZATION_APP_FINISHED) {
        resources = event.getResourceModel().getResources();
        LOGGER.de§
        providers = event.getProviders();
        ...
        LOGGER.debug("resources = {}", resourceClasses);
    }
}
```

IntelliJ or JDT behavior:
Expected IDE behavior is to offer `Logger.debug(...)` overloads for `LOGGER.de§`
at both positions.

Lathe behavior:
At line 239,
Lathe returns no completions.
The debug log shows:

```text
[completion] inject prefix=|de| receiver=|LOGGER| ctx=STATEMENT hasDot=true
[completion] parsed valid=true sentinelCtx=TYPE_REFERENCE receiver=|LOGGER| class=ComponentLoggingListener method=onEvent role=ORDINARY
```

At line 249 in the same method,
the same `LOGGER.de§` probe is classified as `MEMBER_ACCESS`
and returns the expected ten `debug` overloads.

Expected Lathe behavior:
Receiver-qualified expression completion should remain `MEMBER_ACCESS`
after ordinary assignment statements in a method body.
The preceding `resources = ...;` statement should not cause `LOGGER.de§`
to route through type-reference completion.

Accepted edit, if relevant:
For `Logger.debug(String)`,
`textEdit.newText` is `debug($1)`,
the replacement range covers only the typed `de`,
and accepting the item should produce `LOGGER.debug(§)`.

Regression target:
`CompletionMemberAccessTest.memberAccess_afterAssignmentStatement_remainsMemberAccess`.

Notes:
Fixed in two parts:
1. Updated `SentinelInjector.shouldSuppressSemicolon` to only skip horizontal whitespace (space/tab), stopping at newlines to prevent crossing statements.
2. Refined `TypeResolver.resolveExpectedValue` to bypass expected type resolution if the cursor is completing a variable type or assignment assignee target (LHS).

## CQ-0013 — Simple-name method completion drops overloads with the same name

ID: CQ-0013
Status: fixed
Tier: semantic
Failure mode: missing-candidate
Owner component: SimpleNameProvider

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'inject "forT" at 63\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Control probe:
```bash
printf 'inject "DropwizardResourceConfig.forT" at 63\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Accepted-edit control:
```bash
python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java \
  accept inject 'DropwizardResourceConfig.forT' at 63 label forTesting index 1
```

Cursor context:
```java
public DropwizardResourceConfig(@Nullable MetricRegistry metricRegistry) {
    super();

    forT§
    if (metricRegistry == null) {
        metricRegistry = new MetricRegistry();
    }
}

public static DropwizardResourceConfig forTesting() {
    return forTesting(null);
}

public static DropwizardResourceConfig forTesting(@Nullable MetricRegistry metricRegistry) {
    ...
}
```

IntelliJ or JDT behavior:
Expected IDE behavior is to offer both `forTesting()` and `forTesting(MetricRegistry)`.
Overloads are separate selectable method candidates even when their simple-name label is the same.

Lathe behavior:
Simple-name completion returns only:

```text
forTesting  [Method]  DropwizardResourceConfig.forTesting() : DropwizardResourceConfig
```

The debug log shows `simple-name candidates javac=1`.
The type-qualified control probe returns both overloads:

```text
forTesting  [Method]  DropwizardResourceConfig.forTesting() : DropwizardResourceConfig
forTesting  [Method]  DropwizardResourceConfig.forTesting(MetricRegistry metricRegistry) : DropwizardResourceConfig
```

Expected Lathe behavior:
Simple-name completion should keep method overloads as distinct candidates.
Deduplication should not use only the simple name for methods.
Fields, variables, and methods with the same visible name still need Java shadowing rules,
but overloaded methods must survive long enough for presentation and selection.

Accepted edit, if relevant:
The type-qualified control confirms the parameterized overload item edits correctly:
`textEdit.newText` is `forTesting($1)`,
the replacement range covers only the typed `forT`,
and accepting the item produces `DropwizardResourceConfig.forTesting(§)`.

Regression target:
Simple-name completion test where a class declares two visible overloads with the same name.
Both overloads should be returned with distinct `labelDetails.detail` values.

Fixed by:
`CompletionSimpleNameTest.simpleName_overloadedMethods_preservesEachOverload`
and a `SimpleNameProvider` dedupe key that preserves method overload signatures.

Verification:
```bash
mvn -pl lathe-server -Dtest='Completion*Test' test
mvn spotless:check -pl lathe-server
mvn install -pl lathe-server -am -DskipTests
printf 'inject "forT" at 63\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Notes:
Inspection points at `SimpleNameProvider`.
It keeps a `seen` set keyed by `el.getSimpleName().toString()`,
so the first method overload suppresses later overloads before presentation.

## CQ-0014 — Nested classes are missing from simple-name and type-dot suggestions

ID: CQ-0014
Status: fixed
Tier: semantic
Failure mode: missing-candidate
Owner component: CompletionEngine / CandidateGenerator / SimpleNameProvider

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe commands:
```bash
printf 'inject "Com" at 63\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java

printf 'inject "DropwizardResourceConfig.Com" at 63\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
public class DropwizardResourceConfig extends ResourceConfig {
    private static class ComponentLoggingListener implements ApplicationEventListener {
        ...
    }

    public DropwizardResourceConfig(@Nullable MetricRegistry metricRegistry) {
        super();

        Com§
        DropwizardResourceConfig.Com§
    }
}
```

IntelliJ or JDT behavior:
Expected IDE behavior is to offer reachable nested classes where a type can be written.
For the Dropwizard probe,
`ComponentLoggingListener` should be available as an in-file nested type.
The qualified form `DropwizardResourceConfig.Com§` should also suggest
`ComponentLoggingListener`.

Lathe behavior:
Bare `Com§` returns external type-index and `java.lang` suggestions such as `Comparable`,
but it does not include `ComponentLoggingListener`.
The debug log shows `simple-name candidates javac=0`.

Qualified `DropwizardResourceConfig.Com§` returns no completions.
The debug log shows the request routes through `MEMBER_ACCESS`,
resolves the receiver type,
and then proposes zero field/method/enum candidates.

Expected Lathe behavior:
Nested type candidates should be included in type-capable simple-name contexts.
For type-qualified access,
static nested classes should be offered alongside static members when the receiver is a type.
Candidate presentation should use the existing type item shape,
with a type kind and the containing type/package detail.

Accepted edit, if relevant:
Not yet probed through acceptance because no candidate is returned.
Expected insertion is the nested simple name,
for example `ComponentLoggingListener`.

Regression target:
Type-reference and method-body completion tests for:

- bare nested class simple-name completion inside the enclosing top-level class;
- qualified nested class completion after `Outer.Nes§`;
- no instance-only member pollution in the qualified type context.

Notes:
`CandidateGenerator.proposeNestedTypes(...)` exists,
but it is currently reached from qualified `TYPE_REFERENCE` paths.
The Dropwizard qualified probe is classified as `MEMBER_ACCESS`,
whose candidate stream includes fields,
methods,
enum constants,
and `class`,
but not nested types.

## CQ-0015 — Member-access completion does not rank candidates by assignment expected type

ID: CQ-0015
Status: fixed
Tier: typed
Failure mode: poor-ranking
Owner component: CompletionEngine / CompletionCandidateRanker / SemanticCompletionContext

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
printf 'inject "boolean x = Providers." at 153\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Cursor context:
```java
boolean x = Providers.§
```

IntelliJ or JDT behavior:
Expected IDE behavior is to use the assignment target type as a ranking signal.
For `boolean x = Providers.§`,
static methods returning `boolean` should appear before collection-returning or `void` methods.

Lathe behavior:
Lathe returned `Providers` static methods in member order.
`checkProviderRuntime(...) : boolean` appeared first,
but `isJaxRsProvider(...) : boolean`,
`isProvider(...) : boolean`,
and `isSupportedContract(...) : boolean` were below many non-boolean `get*` methods.

The debug log shows the receiver resolves correctly:

```text
[completion] parsed valid=true sentinelCtx=MEMBER_ACCESS receiver=|Providers| class=DropwizardResourceConfig method=register role=ORDINARY
[completion] resolve receiver=|Providers| type=org.glassfish.jersey.internal.inject.Providers static=true reattributed=true
```

Inspection shows member-access completion currently ranks with
`memberAccessSemanticContext(snapshot)`,
which always uses `ExpectedValue.Unknown`.

Expected Lathe behavior:
Member-access completion should derive the expected value for expression slots,
including assignment right-hand sides and variable initializers.
The expected type should affect ranking,
and possibly filtering where the completion contract already allows value-sensitive filtering.

Accepted edit, if relevant:
Not applicable.

Regression target:
Member-access completion test for `boolean value = Providers.§`
or an equivalent local static receiver fixture.
Assert boolean-returning methods sort before non-boolean methods.

Fixed by:
`CompletionMemberAccessTest.memberAccess_staticReceiver_booleanExpectedType_ranksBooleanMembersFirst`,
`CompletionMemberAccessTest.memberAccess_instanceReceiver_booleanExpectedType_ranksBooleanMembersFirst`,
member-access completion using the real semantic completion context,
and expected-type sort buckets that wrap existing member sort keys.

Verification:
```bash
mvn -pl lathe-server -Dtest='Completion*Test' test
mvn spotless:check -pl lathe-server
mvn install -pl lathe-server -am -DskipTests
printf 'inject "boolean x = Providers." at 153\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Notes:
This is not a receiver-resolution problem.
The candidate set includes the expected boolean methods;
only ranking was missing expected-type awareness.

## CQ-0016 — No-arg method accepted in assignment initializer does not insert a semicolon

ID: CQ-0016
Status: accepted
Tier: presentation
Failure mode: bad-accepted-edit
Owner component: CompletionItemPresenter / CompletionSite / SemanticCompletionContext

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe command:
```bash
python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java \
  accept inject 'boolean x = Boolean.TRUE.booleanV' at 153 label booleanValue
```

Related no-arg member probe:
```bash
python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java \
  accept inject 'Object x = LOGGER.atI' at 63 label atInfo
```

Cursor context:
```java
boolean x = Boolean.TRUE.booleanV§
Object x = LOGGER.atI§
```

IntelliJ or JDT behavior:
Expected editor behavior is to complete a no-argument method in a declaration/assignment
initializer to a finished statement when no semicolon is already present:

```java
boolean x = Boolean.TRUE.booleanValue();§
```

Lathe behavior:
Lathe inserts the method call only and places the cursor after `)`:

```java
boolean x = Boolean.TRUE.booleanValue()§
Object x = LOGGER.atInfo()§
```

Expected Lathe behavior:
In declaration or assignment initializer contexts,
accepting a no-arg method completion at the end of a statement should insert `();`
and place the cursor after the semicolon.
If a semicolon or suffix already exists,
the edit should avoid duplicating it.
Parameterized methods need a separate snippet shape,
likely `method($1);$0`,
and should be handled deliberately rather than accidentally.

Accepted edit, if relevant:
Current item for `booleanValue`:

```json
"insertText": "booleanValue()",
"textEdit": {
  "newText": "booleanValue()"
}
```

Desired accepted source:

```java
boolean x = Boolean.TRUE.booleanValue();§
```

Regression target:
Acceptance test for no-arg method completion in a variable initializer,
with and without an existing semicolon after the cursor.

Notes:
This intentionally narrows the current general expectation that zero-argument methods place
the cursor after `()`.
That remains correct for chained calls and unfinished expressions.
The semicolon behavior applies only when completion can identify a statement-ending initializer
or assignment context.

## CQ-0017 — Argument expected-type filtering hides useful chain receiver locals

ID: CQ-0017
Status: fixed
Tier: typed
Failure mode: missing-candidate
Owner component: CompletionCandidateRanker / SemanticCompletionContext

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Probe commands:
```bash
printf 'inject "cc.setSuperclass(" at 165\nlog 40\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java

printf 'inject "cc.setSuperclass(p" at 165\nlog 40\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Control probes:
```bash
printf 'inject "cc.setSuperclass(pool." at 165\nlog 40\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java

python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java \
  accept inject 'cc.setSuperclass(pool.g' at 165 label get
```

Cursor context:
```java
final ClassPool pool = ClassPool.getDefault();
final CtClass cc = pool.makeClass(...);
cc.setSuperclass(§
cc.setSuperclass(p§
```

IntelliJ or JDT behavior:
Expected IDE behavior is to include `pool` as a useful local variable candidate,
even though `pool` itself is not assignable to the `CtClass` parameter.
The user can continue with `pool.get(...)`,
which does return the expected `CtClass`.

Lathe behavior:
At the empty argument slot,
Lathe returns `cc` and other expression starters but not `pool`.
With prefix `p`,
Lathe returns no completions.

The debug log shows the slot and expected type are detected:

```text
[completion] parsed valid=true sentinelCtx=ARGUMENT_POSITION receiver=|null| class=DropwizardResourceConfig method=register role=ORDINARY
[completion] simple-name candidates javac=5 enum=0 keywords=0 semantic=Type[type=javassist.CtClass]
```

The `pool.` control probe confirms the chain is useful:
`ClassPool.get(String) : CtClass`,
`getCtClass(String) : CtClass`,
`makeClass(String) : CtClass`,
and related methods are available from `pool`.

Expected Lathe behavior:
Expected-type ranking in argument positions should not hide non-assignable reference values.
Directly assignable candidates should rank first,
while reference-typed values remain visible as possible chain receivers.

Accepted edit, if relevant:
Accepting `pool.get` currently produces:

```java
cc.setSuperclass(pool.get(§)
```

Regression target:
Argument-position completion test where the expected type is `Target`,
the visible local is `Factory`,
and `Factory.create()` returns `Target`.
The local `factory` should remain visible with prefix `f`.

Fixed by:
`CompletionArgumentTest.argumentPosition_chainReceiverLocal_visibleWhenItCanProduceExpectedType`
and a ranker policy that keeps reference-typed candidates visible in typed value slots.

Verification:
```bash
mvn -pl lathe-server -Dtest='Completion*Test' test
mvn spotless:check -pl lathe-server
mvn install -pl lathe-server -am -DskipTests
printf 'inject "cc.setSuperclass(p" at 165\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java
```

Notes:
This is a typed-completion tradeoff.
The fix intentionally favors ranking over filtering for reference values,
because references can become receivers for chained expression construction.

## CQ-0001 — Annotation enum value completion routes to element-name completion

ID: CQ-0001
Status: fixed
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
`CompletionAnnotationTest`.

Notes:
This is a real Dropwizard probe with no diagnostics.
Fixed for enum member access,
unnamed enum `value()` elements,
and unnamed enum-array `value()` elements.

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
Status: fixed
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
`CompletionPresentationTest.memberAccess_inTokenCompletion_replacesWholeIdentifier`.

Notes:
This is a real Dropwizard probe with no diagnostics.
Fixed by extending completion text-edit replacement ranges over the current Java identifier suffix.

For the current discovery phase,
new entries should come only from Dropwizard or Helidon explorer probes.

## CQ-0018 — Expected return type inside lambda bodies is not resolved

ID: CQ-0018
Status: fixed
Tier: typed
Failure mode: wrong-candidate-set
Owner component: TypeResolver

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Cursor context:
```java
Stream.of("").map(s -> §)
```

IntelliJ or JDT behavior:
Expected IDE behavior is to recognize that the expected return type of the lambda body is String (or boolean for filter), and rank compatible candidates higher.

Lathe behavior:
Lathe does not resolve the expected type of a lambda body, returning ExpectedValue.Unknown.

Expected Lathe behavior:
Lathe should resolve the expected return type of a lambda body by finding its enclosing LambdaExpressionTree, determining its target functional interface type, locating its Single Abstract Method (SAM), and resolving the SAM's return type parameterization.

Accepted edit, if relevant:
Not applicable.

Regression target:
`CompletionArgumentTest.lambdaBody_mapReturnExpectedType_ranksStringHigher` and `CompletionArgumentTest.lambdaBody_filterExpectedType_ranksBooleanHigher`

Notes:
This helps prioritize correct return values inside lambda bodies (e.g. map, filter, etc.).

## CQ-0019 — Throwables not ranked higher/filtered in throw statements

ID: CQ-0019
Status: fixed
Tier: typed
Failure mode: wrong-candidate-set
Owner component: TypeResolver / CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-jersey/src/main/java/io/dropwizard/jersey/DropwizardResourceConfig.java`

Cursor context:
```java
throw §
throw new §
```

IntelliJ or JDT behavior:
Expected IDE behavior is to rank local variables/methods returning Throwables higher for throw statement simple names, and to restrict constructible type completions to Throwable subclasses in throw statement constructor calls.

Lathe behavior:
Lathe does not recognize that a throw statement expects java.lang.Throwable, leaving the expected value as Unknown.

Expected Lathe behavior:
Lathe should resolve the expected type of a ThrowTree expression to java.lang.Throwable, and use this to filter constructor completions and rank simple name completions.

Accepted edit, if relevant:
Not applicable.

Regression target:
`CompletionSimpleNameTest.throwStatement_simpleName_ranksThrowablesHigher` and `CompletionSimpleNameTest.throwStatement_constructorCall_ranksThrowablesHigher`

Notes:
Requires overriding visitThrow in TypeResolver's scanners.
