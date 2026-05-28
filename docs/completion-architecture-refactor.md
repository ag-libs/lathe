# Completion Architecture Refactor

## Purpose

The current completion engine has broad regression coverage and most known behavioral gaps are closed.
This makes it a good point to refactor the architecture before adding smarter semantic completion.

The goal is to make completion decisions follow this shape:

1. identify the cursor site from the live buffer and sentinel parse
2. derive semantic context from the current javac snapshot
3. discover candidates from independent sources
4. apply one shared fit/ranking pass
5. present candidates as LSP completion items

The target behavior is not arbitrary chain synthesis.
Completion should first offer symbols, imported/static symbols, accessible types, and static members that directly fit the current context.
Derived chains such as `factory.user().name()` are explicitly out of scope for this refactor.

Receiver-less completion should not dump every possible type and symbol by default.
It should start from a narrow candidate family based on syntactic context and prefix shape,
then support deliberate widening for explicit user-triggered completion.

Auto-import is a must-have part of the target behavior.
Type and static-member completions that introduce symbols not already visible must carry import edits once the presenter supports them.

## Problems In The Current Shape

`CompletionEngine` currently performs orchestration, context interpretation, type-index lookup, fallback handling, merging, and LSP text-edit application.
This makes future semantic filtering harder because each path owns part of the decision.

`SimpleNameProposalCollector`, `ProposalGenerator`, `CompletionEngine`, and `CompletionItemFactory` all participate in filtering, ranking, or item presentation.
Rules such as value-context filtering, Object-method demotion, type-index validation, `java.lang` fallback, and import insertion are spread across several classes.

Expected type is currently nullable.
That cannot distinguish:

- no expected type is known
- an expected type is known
- the cursor is in a place with no valid argument slot, such as `noArgs(§)`

The next architecture should make these states explicit.

## Design Principles

- Keep `CompletionEngine` orchestration-first.
- Providers discover candidates; they do not make final presentation decisions.
- One shared fit/ranking pass decides semantic suitability and ordering.
- One presenter converts internal candidates to LSP `CompletionItem`.
- Keep the first refactor behavior-preserving except where a disabled regression is intentionally fixed.
- Prefer small package-private records/classes over broad abstractions.
- Do not add derived-chain completion in this refactor.

## IDE Behavior Target

Lathe completion should be close enough to IntelliJ IDEA's completion model to be efficient as a daily-driver editor feature.
The implementation should remain simpler than IntelliJ,
but the user-visible behavior should follow the same broad shape.

### Basic Automatic Completion

Automatic receiver-less completion should be narrow, fast, and context-sensitive.
It should not show every indexed type for every prefix.

Examples:

- lowercase receiver-less prefixes primarily show locals, parameters, fields, methods, and keywords
- uppercase receiver-less prefixes open type-oriented candidates
- empty prefix in a statement should not flood the menu with classpath types
- member access after `.` shows members of the receiver
- import context shows packages and types

### Explicit Basic Completion

Manual or repeated basic completion should widen the candidate set.
This gives access to lower-confidence results without making automatic typing noisy.

Widening may include:

- broader project/classpath type candidates
- lowercase type-index matches
- candidates that are visible only through import insertion
- lower-priority fallback candidates when javac context is incomplete

### Smart Completion

Smart completion is expected-type driven.
It should prioritize candidates that fit the semantic context.

Required smart contexts:

- right side of assignments
- variable initializers
- `return` statements
- method-call argument lists
- constructor-call argument lists
- after `new`
- casts and type-use positions where javac exposes the expected type

Smart completion should rank direct fits first:

- locals and parameters assignable to the expected type
- fields assignable to the expected type
- methods whose return type is assignable to the expected type
- static-imported members assignable to the expected type
- accessible static fields/methods assignable to the expected type
- constructible or importable types where accepting the completion can produce the expected type

### Second Smart Completion

Later explicit smart-completion widening may include conversions and derived expressions.
This refactor should only reserve space for that behavior.

Out of scope for this refactor:

- arbitrary method-chain synthesis
- collection/array conversion suggestions
- data-flow-dependent expression construction

### Behavior Matrix

| Site | Automatic | Explicit basic | Smart |
|---|---|---|---|
| `foo.§` | receiver members | receiver members, wider fallback | not applicable |
| `Str§` in method body | type-prioritized mixed results | broader type/project results | type-biased if expected type exists |
| `str§` in method body | visible values and keywords | may widen to lowercase type matches | value-biased if expected type exists |
| `String s = §` | visible values and expression keywords | values plus importable types | assignable values first |
| `return §` | visible values and expression keywords | values plus importable types | return-type matches first |
| `accept(§)` | visible values and expression keywords | values plus importable types | parameter-type matches first |
| `new Arr§` | constructible type candidates | broader constructible/project types | expected constructible types first |
| `import java.ut§` | packages and types | packages and types | not applicable |
| `import static java.util.Objects.§` | static members | static members | not applicable |

### Auto-Import Requirement

Auto-import is required for IDE-grade completion.
Importable type and static-member candidates must be able to insert the symbol at the cursor and add the missing import through `additionalTextEdits`.

The completion item must contain those edits in the initial `textDocument/completion` response.
Resolve may add documentation later,
but it must not change insertion fields, replacement ranges, or import edits.

## Target Data Model

### `CompletionSite`

`CompletionSite` is live-buffer and sentinel-parse context only.
It should not contain javac types.

Suggested fields:

```java
record CompletionSite(
    String uri,
    String prefix,
    int cursorOffset,
    Range replacementRange,
    SentinelContext sentinelContext,
    String receiverText,
    int receiverEndOffset,
    String enclosingClass,
    String enclosingMethod,
    int argIndex,
    String enclosingReceiver,
    String enclosingMethodName,
    String declaredTypeText) {}
```

It is built from `CompletionRequest`, `SentinelResult`, and `ParsedSentinel`.

`CompletionSite` should own replacement-range calculation.
The final text-edit application should stop recomputing the prefix range from raw cursor position.

### `ExpectedValue`

Expected value context must be explicit.

Suggested sealed type:

```java
sealed interface ExpectedValue {
  record Unknown() implements ExpectedValue {}

  record Type(TypeMirror type) implements ExpectedValue {}

  record NoSlot() implements ExpectedValue {}
}
```

`NoSlot` means completion is syntactically inside an invocation or constructor argument list,
but the target callable has no parameter at that index.
For example, `noArgs(§)` should produce `NoSlot`.

### `SemanticCompletionContext`

`SemanticCompletionContext` contains javac-derived state for the site.

Suggested fields:

```java
record SemanticCompletionContext(
    AttributedFileAnalysis analysis,
    Scope scope,
    TypeElement enclosingType,
    ExpectedValue expectedValue,
    boolean staticContext,
    boolean valueContext) {}
```

`analysis` may be absent for fallback type-index results if the current path supports no-snapshot completion.
If nullable analysis is retained temporarily, methods consuming this record should make that fallback explicit.

### `CompletionMode`

Provider selection should be driven by a small mode derived before candidate discovery.
This keeps prefix-shape rules out of individual providers.

Suggested enum:

```java
enum CompletionMode {
  VALUE,
  TYPE,
  MIXED,
  IMPORT,
  STATIC_IMPORT,
  PACKAGE,
  MEMBER,
  KEYWORD_ONLY
}
```

Suggested first-pass rules:

- `MEMBER_ACCESS` -> `MEMBER`
- `IMPORT` -> `IMPORT`
- `STATIC_IMPORT` -> `STATIC_IMPORT`
- `TYPE_REFERENCE` -> `TYPE`
- constructor-call type position -> `TYPE`
- receiver-less simple name with an uppercase prefix -> `MIXED`, with type candidates ranked high
- receiver-less simple name with a lowercase prefix -> `VALUE`
- empty prefix in statement context -> visible values and keywords, no broad type-index flood

Java permits lowercase type names, so lowercase type-index candidates should not be impossible forever.
They should be hidden from narrow automatic completion unless the context is definitely type-only
or the request asks for widened completion.

### Completion Widening

The architecture should leave room for a widening signal from the LSP request path.
The first implementation can infer only a narrow mode,
but the model should not prevent later support for explicit user-triggered broad completion.

Suggested levels:

```java
enum CompletionBreadth {
  NARROW,
  BROAD
}
```

`NARROW` is used for normal automatic completion.
It follows prefix-shape and context rules strictly.

`BROAD` is used when the editor explicitly asks for completion,
or later when the same completion command is invoked again.
It may include lower-priority candidate families that narrow mode suppresses,
such as lowercase type-index matches in receiver-less expression context.

This mirrors the desired IDE behavior:
automatic completion is focused,
while intentional completion can widen the result set without making normal typing noisy.

### `CompletionCandidate`

Providers should return internal candidates instead of LSP items.

Suggested shape:

```java
record CompletionCandidate(
    String name,
    String label,
    CandidateKind kind,
    TypeMirror valueType,
    TypeElement declaringType,
    String insertText,
    boolean snippet,
    ImportEdit importEdit,
    int baseRank) {}
```

`valueType` is the type produced by accepting the completion in a value context:

- variable or field type
- method return type
- constructed type for constructor-oriented type candidates
- type mirror for class literals/type-use contexts only when relevant

For type-index entries that cannot be resolved to `TypeElement`, use either a separate candidate kind or a candidate with missing semantic type.
Unresolved type-index candidates can remain broad fallback results with `isIncomplete=true`.

### `CandidateKind`

Keep this enum small at first:

```java
enum CandidateKind {
  KEYWORD,
  LOCAL_VARIABLE,
  FIELD,
  METHOD,
  TYPE,
  PACKAGE,
  STATIC_MEMBER
}
```

Only split further when behavior requires it.

### `ImportEdit`

Import insertion should be represented before LSP presentation.

Suggested shape:

```java
record ImportEdit(String qualifiedName, boolean isStatic) {}
```

The presenter can later turn this into `additionalTextEdits`.
The first structural slices may carry the field before producing LSP edits,
but the refactor is not complete until type and static-member candidates can produce `additionalTextEdits`.

## Candidate Providers

Use a package-private interface:

```java
interface CompletionCandidateProvider {
  List<CompletionCandidate> provide(CompletionSite site, SemanticCompletionContext context);
}
```

Initial providers:

| Provider | Responsibility |
|---|---|
| `ScopeCandidateProvider` | locals, parameters, fields, and same-class methods |
| `MemberAccessCandidateProvider` | members of resolved receiver |
| `StaticImportCandidateProvider` | simple-name members made visible by static imports |
| `TypeIndexCandidateProvider` | dependency/JDK/reactor type-index candidates |
| `JavaLangCandidateProvider` | `java.lang` type fallback when needed |
| `ImportCandidateProvider` | import/package context candidates |
| `KeywordCandidateProvider` | keywords for the syntactic context |

These providers may be introduced gradually.
The first slice does not need every provider to implement the interface.

Provider execution should be selected by `CompletionMode` and `CompletionBreadth`.
For example, `VALUE`/`NARROW` should not run broad type-index lookup,
while `MIXED`/`NARROW` may run it for uppercase prefixes and rank type candidates high.

## Fit And Ranking

Add a shared component, for example `CompletionCandidateRanker`.

It should apply rules in one place:

- `ExpectedValue.NoSlot` returns no value candidates.
- Void-returning methods are excluded in value contexts.
- Object-declared methods are excluded in expected-value contexts and demoted in statement/member contexts.
- Static members are offered only where static access is valid.
- Instance members are offered only where instance access is valid.
- If `ExpectedValue.Type` is available, assignable candidates rank above non-assignable candidates.
- Non-assignable but syntactically valid expression candidates may remain lower-ranked.
- Current package, already imported types, and `java.lang` rank above unrelated import-needed types.
- Unresolved broad type-index fallback candidates remain incomplete and low confidence.

The ranker should not construct `CompletionItem`.
It should return ranked candidates or attach a sort bucket to candidates.

## Presentation

Add `CompletionItemPresenter`.

It owns:

- `label`
- `filterText`
- `sortText`
- `kind`
- `insertText`
- `insertTextFormat`
- `textEdit`
- `additionalTextEdits`

`CompletionItemFactory` can either be folded into the presenter or retained as a private helper while the migration is in progress.

The presenter should be the only class that creates LSP `CompletionItem` after the migration is complete.

Auto-import edits belong in the initial completion response.
They must not be added during `completionItem/resolve`,
because LSP clients use insertion and edit fields from the original completion item.

## Direct Static Member Fit

The architecture should support direct static member candidates.

Example:

```java
accept(String value);
```

If an accessible static method or field returns `String`, it may be offered and ranked as fitting.

This refactor should not search arbitrary chains such as:

```java
Factory.user().name()
```

One-hop derived-expression completion may be considered later as a separate feature with strict limits.

## Suggested Slice Plan

### Slice 1 — Introduce `CompletionSite`

- Add `CompletionSite`.
- Build it in `CompletionEngine` after sentinel parse.
- Move replacement range calculation into it.
- Derive an initial `CompletionMode` from sentinel context and prefix shape.
- Keep existing `CompletionItem` producing code.
- Preserve behavior.

### Slice 2 — Introduce `ExpectedValue` And `SemanticCompletionContext`

- Add `ExpectedValue`.
- Change expected-type resolution to return `ExpectedValue`.
- Add `SemanticCompletionContext`.
- Preserve current behavior for `Unknown` and `Type`.
- Re-enable and fix the disabled zero-argument regression with `NoSlot`.

### Slice 3 — Convert Simple-Name Candidates

- Convert `SimpleNameProposalCollector` to return `CompletionCandidate`.
- Move simple-name value filtering and ranking into the shared ranker.
- Keep LSP conversion local if needed, but route through the new presenter as soon as practical.

### Slice 4 — Convert Type Candidates

- Convert `java.lang` and type-index results to `CompletionCandidate`.
- Resolve type-index candidates through `Elements.getTypeElement` when a snapshot exists.
- Apply expected-type fit/ranking to type candidates.
- Use `CompletionMode` so broad receiver-less type lookup only runs in type or uppercase mixed contexts.
- Keep unresolved no-snapshot fallback as incomplete broad results.

### Slice 5 — Convert Member Access

- Convert `ProposalGenerator.proposeMemberAccess` to return `CompletionCandidate`.
- Move Object-method demotion and static/instance rules to the shared ranker.
- Preserve existing member-access behavior and tests.

### Slice 6 — Centralize Presentation

- Move all `CompletionItem` creation into `CompletionItemPresenter`.
- Remove or shrink `CompletionItemFactory`.
- Ensure all completion items have stable `textEdit`, `filterText`, `kind`, and `sortText`.

### Slice 7 — Direct Static Member Fit

- Add a provider or extend static-import/type providers to discover accessible static members.
- Only include direct candidates whose value type is assignable to the expected type.
- Do not add derived-chain search.

### Slice 8 — Required Import Edits

- Use `ImportEdit` from candidates to produce `additionalTextEdits`.
- Add import insertion tests separately from semantic filtering tests.
- Cover regular type imports and static imports.
- Treat this slice as required before the refactor is considered complete.

## Testing Strategy

Keep existing completion tests as regression coverage during every slice.

Add focused tests as each slice lands:

- `argumentPosition_zeroParamMethod_suppressesCompletions`
- expected type `String` ranks `String`-returning methods and fields above unrelated values
- expected type excludes void methods
- expected type excludes Object-declared methods
- type-index candidate assignable to expected type ranks high
- type-index candidate not assignable to expected type ranks lower or is excluded according to the final rule
- static-imported member participates in expected-type ranking
- direct static member returning expected type is offered
- unresolvable type-index candidate is not included when a javac snapshot exists
- no-snapshot fallback remains incomplete

For each provider conversion, keep positive and negative cases.
Tests should assert behavior, not internal class structure.

## Non-Goals

- Arbitrary derived-chain completion.
- Multi-hop expression synthesis.
- Full overload-constraint solving beyond the current expected-parameter lookup.
- Replacing the sentinel parser with a different parser.

## Completion Criteria

The refactor is complete when:

- `CompletionEngine` primarily orchestrates site creation, semantic context creation, provider selection, ranking, and presentation.
- Providers return internal candidates rather than LSP items.
- Expected context is represented without nullable ambiguity.
- Filtering/ranking rules are centralized.
- LSP item construction is centralized.
- Type and static-member completions can add required imports through `additionalTextEdits`.
- Existing completion tests pass.
- The disabled zero-argument argument-position regression is re-enabled and passing.
