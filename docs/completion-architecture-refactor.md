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

Smart completion is not just "more results".
It is a different ranking/filtering mode based on semantic fit.

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

### Automatic vs Smart

Automatic completion is invoked while the user is typing.
It should be fast, narrow, and driven primarily by syntactic context plus prefix shape.

Examples:

```text
str§ -> visible values and keywords
Str§ -> type-prioritized mixed results
```

Smart completion is explicitly requested in a context where javac can provide an expected type.
It should prioritize candidates that produce a value assignable to that expected type.

Examples:

```text
String s = § -> String-producing values first
return § -> return-type matches first
accept(§) -> parameter-type matches first
```

Broad or repeated completion is a separate axis.
It widens candidate families.
Smart completion changes ranking and filtering around expected type.

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

## Target Data Flow And Responsibilities

The refactored completion path should follow this flow:

```text
CompletionRequest
  -> SentinelInjector
  -> SentinelParser
  -> CompletionSite
  -> SemanticCompletionContext
  -> providers selected by mode/breadth
  -> CompletionCandidate list
  -> CompletionCandidateRanker
  -> RankedCompletionCandidate list
  -> CompletionItemPresenter
  -> CompletionOutcome
```

### `CompletionEngine`

`CompletionEngine` is orchestration only.

Responsibilities:

- receive `CompletionRequest`
- run injection and sentinel parse
- build `CompletionSite`
- get or refresh javac analysis when needed
- build `SemanticCompletionContext`
- choose candidate providers based on `CompletionSite.mode()` and `CompletionSite.breadth()`
- call the ranker
- call the presenter
- return `CompletionOutcome`

It should not contain detailed rules such as uppercase-prefix type lookup,
Object-method demotion,
or expected-type ranking.
Those belong in site creation or ranking.

### `CompletionSite`

`CompletionSite` is pure cursor/request context.

It describes where completion is happening and how broad this request should be.
It owns the replacement range.

Examples:

```text
Str§ in method body
  -> mode = MIXED
  -> breadth = NARROW
  -> prefix = "Str"

str§ in method body
  -> mode = VALUE
  -> breadth = NARROW
  -> prefix = "str"
```

### `SemanticCompletionContext`

`SemanticCompletionContext` is javac semantic context for the site.

Responsibilities:

- expose the current `AttributedFileAnalysis`
- expose the current javac `Scope`
- expose the enclosing type
- expose expected value context through `ExpectedValue`
- expose whether values are legal here
- expose static-context information

The important rule is that expected type must not be nullable.
`ExpectedValue.NoSlot` is how `noArgs(§)` becomes "return no value completions",
not "unknown, show everything".

### Candidate Providers

Providers discover candidates.
They do not make final ranking or presentation decisions.

Provider input:

```java
provide(CompletionSite site, SemanticCompletionContext context)
```

Provider output:

```java
List<CompletionCandidate>
```

Provider responsibilities:

- `ScopeCandidateProvider`: locals, parameters, fields, and same-class methods
- `MemberAccessCandidateProvider`: members of an expression or type receiver
- `StaticImportCandidateProvider`: members visible through existing static imports
- `TypeIndexCandidateProvider`: indexed dependency, JDK, and reactor types
- `JavaLangCandidateProvider`: temporary fallback for `java.lang` types if not covered by the index
- `ImportCandidateProvider`: packages, types, and static members inside import declarations
- `KeywordCandidateProvider`: context-valid keywords

Providers may attach semantic metadata such as value type, declaring type, visibility, and import intent.
They should not create LSP `CompletionItem`.

### `CompletionCandidate`

`CompletionCandidate` is the internal semantic model for an offered thing.
It describes what the candidate is and what accepting it means.

Example candidates:

```text
local String name
  kind = LOCAL_VARIABLE
  valueType = java.lang.String
  visibility = IN_SCOPE
  importEdit = null

java.util.ArrayList
  kind = TYPE
  valueType = java.util.ArrayList where applicable
  visibility = IMPORTABLE
  importEdit = import java.util.ArrayList

Objects.requireNonNull
  kind = STATIC_MEMBER
  valueType = T
  visibility = IMPORTABLE
  importEdit = import static java.util.Objects.requireNonNull
```

### `SymbolVisibility`

Use explicit symbol visibility instead of a single `needsImport` boolean:

```java
enum SymbolVisibility {
  IN_SCOPE,
  IMPORTABLE,
  NOT_ACCESSIBLE
}
```

Responsibilities:

- prefer already-visible symbols
- allow importable symbols with `additionalTextEdits`
- drop inaccessible symbols

### `ImportEdit`

`ImportEdit` represents import intent before LSP presentation:

```java
record ImportEdit(String qualifiedName, boolean isStatic) {}
```

Responsibilities:

- keep auto-import part of the candidate model
- let the presenter create `additionalTextEdits`
- support both normal type imports and static imports

### `CompletionCandidateRanker`

The ranker is the central semantic fit and ordering component.

Input:

```java
List<CompletionCandidate>
CompletionSite
SemanticCompletionContext
```

Output:

```java
List<RankedCompletionCandidate>
```

Responsibilities:

- drop invalid candidates
- apply expected-type fit
- apply static/instance legality
- exclude void methods in value contexts
- demote or exclude Object methods depending on context
- prefer `IN_SCOPE` over `IMPORTABLE`
- rank direct assignable values high
- preserve broad fallback candidates with low rank or incomplete marker

It should not create LSP items.

### `RankedCompletionCandidate`

`RankedCompletionCandidate` is the result of semantic ranking.

Suggested shape:

```java
record RankedCompletionCandidate(
    CompletionCandidate candidate,
    String sortText,
    MatchQuality quality,
    boolean incomplete) {}
```

This keeps ranking testable without inspecting LSP objects.

### `CompletionItemPresenter`

The presenter is the only class that creates LSP `CompletionItem`.

Responsibilities:

- set `label`
- set `kind`
- set `filterText`
- set `sortText`
- set `insertText`
- set `insertTextFormat`
- set `textEdit` using `CompletionSite.replacementRange()`
- set `additionalTextEdits` from `ImportEdit`
- leave documentation for `completionItem/resolve`

This is where auto-import becomes LSP edits.

### Responsibility Boundary

The core separation is:

- `CompletionSite`: where am I?
- `SemanticCompletionContext`: what does javac know?
- providers: what symbols exist?
- ranker: what fits and in what order?
- presenter: how does LSP display and insert it?

## Sharing With Hover And Definition

Hover and definition already share a stable-source path in `SourceAnalysisSession`.
Both resolve the current open document against the cached analysis,
convert the LSP position to an offset,
find the `TreePath` at that offset,
and then use `SourceLocator` to resolve the element, type, parameter, declaration, source file, or Javadoc.

Completion should not be forced onto the same model.
Completion often runs in broken or partial code,
and still needs `SentinelInjector` and `SentinelParser` to classify the cursor site from the live buffer.

The useful shared piece is a lightweight cursor context resolver.

Possible shape:

```java
record SourceCursorContext(
    String uri,
    String content,
    Position position,
    int offset,
    CachedFileAnalysis cached,
    AttributedFileAnalysis analysis,
    TreePath path) {}
```

Responsibilities:

- validate that cached analysis matches the current content
- convert LSP position to source offset
- find the attributed `TreePath` when cached analysis exists
- expose the current analysis for semantic features

Hover and definition can use this context directly.
Completion can use it to build `SemanticCompletionContext`,
while still using sentinel parsing to build `CompletionSite`.

Do not share sentinel parsing with hover/definition.
Do not make completion depend only on `TreePath pathAt(cursor)`.
The intended split is:

```text
CompletionSite = live buffer + sentinel parse
SemanticCompletionContext = SourceCursorContext + javac helpers + ExpectedValue
```

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

## Implementation Progress

Current branch: `completion-architecture-refactor`.

Completed so far:

- Slice 1: `CompletionSite` and `CompletionMode` exist,
  and `CompletionEngine` uses `CompletionSite.replacementRange()` for simple text edits.
- Slice 2: `ExpectedValue` and `SemanticCompletionContext` exist.
  The zero-argument argument-position regression is enabled and passing.
- Slice 3 partial: simple-name and keyword completions flow through `CompletionCandidate`,
  `CompletionCandidateRanker`,
  and `CompletionItemPresenter`.
  `CompletionEngine` combines simple-name javac candidates and keyword candidates before ranking/presentation.
- Slice 4 partial: `java.lang` and type-index type completions are represented as
  `CompletionCandidate` before LSP presentation.
- Simple-name ranker rules now own expected-type sort buckets,
  Object-method demotion,
  and value-context Object/void-method filtering.

Known temporary compromises:

- Typed initializer expected type is still discovered inside `SimpleNameProposalCollector`.
  The collector preserves initializer-derived sort text,
  and the ranker treats candidates with existing sort text as value-sensitive.
  Later work should lift initializer expected type into `SemanticCompletionContext`.
- Import and member-access paths still produce `CompletionItem` directly.
- `CompletionItemFactory` still exists as a bridge for legacy item paths and candidate construction.

Next likely slice:

- Convert member-access candidates to `CompletionCandidate`,
  then route them through the ranker and presenter while preserving current behavior.

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

The current tests cover many common-sense cases, regressions, and gaps found during development.
The refactor should keep that coverage,
but new tests should use a systematic set of scenarios so completion behavior stays coherent as the architecture changes.

Prefer explicit scenario tests backed by strong fixtures over large parameterized matrices.
Parameterized tests are useful for small uniform rules,
but semantic completion tests are usually clearer when each scenario has a descriptive test method.

### No Mocked Javac Model Objects

Do not mock javac semantic objects.

This includes:

- `CompilationUnitTree`
- `Trees`
- `Elements`
- `Types`
- `Scope`
- `Element`
- `TypeElement`
- `ExecutableElement`
- `TypeMirror`

Tests that need semantic Java information must obtain it from real compilation of minimal source fixtures.
Use existing utilities such as `TempSourceCompiler`, `TestCompiler`, and small inline source snippets.

Mocking javac internals makes completion tests pass against fake type behavior that javac may never produce.
Completion correctness depends on javac's actual attribution, accessibility, and assignability behavior,
so semantic tests should use real javac contexts.

LSP DTOs and internal pure data records may be instantiated directly.
Pure presenter tests may use synthetic `CompletionCandidate` values when no javac type behavior is involved.

### Test Dimensions

Use these axes when adding or reorganizing tests.
They describe the behavior matrix,
but they do not require every test to be parameterized:

- site kind: member access, simple name, type reference, import, static import, constructor call, argument position, return, initializer
- invocation kind: automatic/narrow, explicit/broad, smart
- prefix shape: empty, lowercase, uppercase, qualified, partial member
- semantic expectation: unknown, expected type, no argument slot
- symbol source: local, parameter, field, method, static import, importable type, `java.lang`, dependency/JDK/reactor index
- visibility: in scope, importable, inaccessible, unreadable module
- candidate fit: assignable, non-assignable, void, Object method, static/instance mismatch
- presentation: `textEdit`, `filterText`, `sortText`, `kind`, `additionalTextEdits`

### Completion Test Fixture

Add or evolve a small fixture DSL so tests read like completion scenarios rather than compiler setup.
The fixture should hide boilerplate, not behavior.

Fixture responsibilities:

- parse the `§` cursor marker
- compile source with real javac when semantic context is needed
- create `CompletionRequest`
- allow invocation kind such as automatic, broad basic, smart, and broad smart
- expose labels, kinds, details, sort order, text edits, and import edits
- support type-index shard setup
- support JPMS `module-info.java` setup

Suggested helper entry points:

```java
completeAutomatic(source)
completeBasicBroad(source)
completeSmart(source)
completeSmartBroad(source)
```

Suggested assertion style:

```java
final var result =
    completeSmart(
        """
        class Test {
          void accept(String value) {}
          String name() { return ""; }
          int count() { return 0; }

          void m() {
            accept(§);
          }
        }
        """);

assertThat(result)
    .containsLabel("name()")
    .ordersBefore("name()", "count()")
    .doesNotContainLabels("wait", "notify", "doWork");
```

For import assertions:

```java
assertThat(result)
    .item("ArrayList")
    .hasImportEdit("java.util.ArrayList");
```

Fixture assertions should make failures easy to read.
Avoid hiding core behavior behind overly broad helpers.

### Parameterized Tests

Use parameterized tests only where the setup and assertion shape are truly uniform.

Good candidates:

- site mode classification
- keyword set per syntactic context
- type kind to LSP completion kind mapping
- import insertion location variants
- simple rank bucket ordering where candidates are synthetic and no javac type behavior is needed

Avoid large parameterized tests for semantic completion behavior when each row needs different source setup,
different assertions,
or different explanation.
Those scenarios are clearer as explicit tests using the fixture.

### Test Layers

#### Site Classification Tests

Validate `CompletionSite` creation from source strings.
These tests should be pure string/parser tests unless semantic expected-type state is needed.

Examples:

- `Str§` -> `MIXED`, narrow
- `str§` -> `VALUE`, narrow
- `new Arr§` -> `TYPE`
- `foo.§` -> `MEMBER`
- `import java.ut§` -> `IMPORT`

#### Semantic Context Tests

Validate expected value and scope derivation using real javac compilation.

Examples:

- assignment RHS resolves the assigned type
- variable initializer resolves the declared type
- `return §` resolves the enclosing method return type
- method-call argument resolves the parameter type
- zero-parameter invocation resolves `ExpectedValue.NoSlot`
- unknown expression context resolves `ExpectedValue.Unknown`

#### Provider Tests

Provider tests should use real `SemanticCompletionContext` when semantic data is involved.

Examples:

- scope provider returns locals, parameters, fields, and same-class methods
- static-import provider returns imported static members
- type-index provider returns importable indexed types
- member provider respects receiver type
- keyword provider respects syntactic context

#### Ranker Tests

Ranker tests should focus on fit and ordering.
If assignability or javac accessibility is involved,
create candidates from real compiled elements and type mirrors.

Examples:

- assignable candidates rank before non-assignable candidates
- void methods are excluded in value contexts
- Object methods are demoted or excluded according to context
- `IN_SCOPE` ranks before `IMPORTABLE`
- `NOT_ACCESSIBLE` is dropped
- `ExpectedValue.NoSlot` drops value candidates

#### Presenter Tests

Presenter tests should focus only on LSP shape.
They may use synthetic candidates when no javac type behavior is involved.

Examples:

- text edit range replaces the typed prefix
- filter text is the symbol name
- method insertion uses the expected insert text and snippet format
- type kind maps to class, interface, enum, or record
- import edits become `additionalTextEdits`

#### End-To-End Completion Scenarios

End-to-end tests should be fewer but high-value.
They should exercise the full `CompletionEngine` with real compilation where needed.

Examples:

- `String s = §`
- `return §`
- `accept(§)`
- `new Arr§`
- `Objects.§`
- `import java.util.§`
- unimported `ArrayList` inserts an import
- static member inserts a static import
- JPMS unreadable candidate is absent

### Test Naming

Use names that encode the layer, condition, and expected result.

Examples:

```text
site_lowercaseSimpleName_selectsValueMode
expected_methodArgument_resolvesParameterType
ranker_expectedString_assignableMethodRanksFirst
presenter_importableType_addsImportEdit
completion_variableInitializer_prefersAssignableValues
```

The fixture may make tests compact,
but the method name should still say what behavior is protected.

### Regression Tests

Do not delete existing gap/regression tests just because they are ad hoc.
During refactor work,
move or rename them gradually into the systematic structure when doing so improves clarity.
Weird bug tests are useful as long as the behavior they protect remains relevant.

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
