# Completion Gaps

## Plan

Open gaps in priority order.

| Gap | Title | Difficulty | Depends on |
|-----|-------|------------|------------|
| J | No completions after `::` | Hard | — |

Gap E has been revised — see the Closed section.

Gap discovery now starts from [completion-semantics-audit.md](completion-semantics-audit.md).
That matrix records syntax-site behavior before fixes are made.

**J last** because it requires a new `MEMBER_REFERENCE` sentinel context,
functional-interface compatibility filtering, and no existing path to build on.

---

## Open

### Gap J — No completions after `::` (method reference)

**Difficulty:** Hard

**Symptom:** Typing `String::` or any `Type::` returns no completions.

```
inject "Stream.of("").map(String::" at method body  →  (no completions)
```

**Root cause:** `SentinelParser` does not recognise `MemberReferenceTree` as a
completion context.
The sentinel lands on the wrong node and the context is invalid.

**Resolution:** Add `MEMBER_REFERENCE` to `SentinelContext`.
In `SentinelParser.extractContext`, handle `MemberReferenceTree` as a parent
with the LHS as receiver text.
In `CompletionEngine`, route `MEMBER_REFERENCE` through the member-access path
to get LHS members.

The hard part is filtering: candidates should be methods compatible with the
functional interface expected at the call site, which requires resolving the
SAM type of the target parameter and matching arity and parameter types against
each candidate.
This needs significant new resolution logic — no existing path to build on.
Defer until the higher-priority gaps are closed.

---

## Closed

### Gap U — Annotation element value completion

**Resolution:** `SentinelParser` now extracts both the annotation type name and the element name
when the sentinel is the value side of an `AssignmentTree` inside an `AnnotationTree` (named
annotation argument: `@Deprecated(since = §)`). Both are stored in `ParsedSentinel` via
`annotationTypeText` (annotation type) and `enclosingMethodName` (element name, repurposed for
this context).

`CompletionEngine.completeAnnotationArgumentValue` resolves the element's return type from the
attributed snapshot, constructs a `SemanticCompletionContext` with that type as the expected
value, and passes value-expression keyword candidates through `CompletionCandidateRanker`. The
ranker's existing type-filtering logic then keeps `true`/`false` only for boolean elements and
suppresses them for String or other reference types, while `null` is offered for all non-primitive
element types.

Remaining open within Gap U: enum-valued elements (e.g. `@Retention(§)` shorthand offering
`RetentionPolicy` constants), array-valued element positions (`@Target({§})`), annotation
declaration bodies (`@interface A { § }`), and annotation element default values.

**Tests:** `annotationArgumentValue_booleanElement_offersTrueAndFalse`,
`annotationArgumentValue_stringElement_offersNullNotBooleans`

---

### Gap T — Declaration name slots suppress completions

**Resolution:** `SentinelParser.classifyVariableDeclaration` now sets `declaredTypeText` to null
when the variable's type tree is erroneous (javac error-recovery artifact).
`CompletionEngine` and `KeywordProvider` use `isRealNameSlot` — true when `declaredTypeText` is
non-null and differs from the enclosing class name — to distinguish real name slots (user typed
an explicit type) from error-recovery artifacts (javac invented a synthetic type using the
enclosing class name).

Real name slots return empty completions in both the engine and the keyword provider.
Method-scope name slots already returned empty; class-scope field name slots now do too.
Ambiguous positions (bare `§` in class/enum body, where javac's recovery uses the class name as
a synthetic type) continue to offer class-body keywords unchanged.

**Tests:** `declarationName_fieldNameSlot_suppressesAllCandidates`,
`declarationName_localVarNameSlot_suppressesAllCandidates`

---

### Gap M — Keyword ranking by semantic fit

**Resolution:** `ParsedSentinel` gains `inEqualityComparison` (set in `SentinelParser` when the
sentinel's ancestor chain contains a `BinaryTree` with kind `EQUAL_TO` or `NOT_EQUAL_TO`).
`SemanticCompletionContext` gains `inEqualityComparison` and `inNonVoidMethod` (the latter
computed in `TypeResolver.isNonVoidMethod` from the attributed snapshot).
`CompletionCandidateRanker.sortText` routes keyword candidates through `keywordSortText`, which
promotes `true`/`false` to `0_` when the expected type is boolean, `null` to `0_null` inside
equality comparisons, and `return` to `0_return` inside non-void methods.
`SentinelParser.classifySentinel` was also extended with a comprehensive set of expression-parent
cases so `parsed.inExpression()` is fully derived from the javac tree, removing the dependency on
the injector backward-scanner for the `SIMPLE_NAME` keyword path.

**Tests:** `keywords_trueAndFalse_rankedFirstForBooleanExpectedType`,
`keywords_null_rankedFirstInEqualityComparison`,
`keywords_returnPosition_expressionKeywordsOnly` (extended with bare-method-body return ranking)

---

### Gap L — Context-sensitive statement keywords

**Resolution:** `SentinelParser` now computes three boolean flags from the sentinel's javac
tree ancestor chain: `enclosedByLoop`, `enclosedBySwitchStatement`, `enclosedBySwitchExpression`.
`KeywordProvider.methodBodyKeywords` uses these to gate `break` (loop or switch statement),
`continue` (loop only), and `yield` (switch expression only).

`SentinelParser.classifySentinel` was extended with explicit expression-parent cases
(`ConditionalExpressionTree`, `BinaryTree`, `IfTree` condition, `MemberSelectTree` receiver, etc.)
so `parsed.inExpression()` is set purely from the javac tree. The `SIMPLE_NAME` keyword path
no longer consults the injector context, eliminating the false-EXPRESSION classification that
the backward scanner produced for switch-case label colons.

`else`, `catch`, and `finally` remain deferred — they require previous-sibling detection
rather than ancestor-chain inspection.

**Tests:** `keywords_breakAndContinue_suppressedInBareMethodBody`,
`keywords_breakAndContinue_offeredInsideLoop`,
`keywords_break_offeredInsideSwitchStatement`,
`keywords_yield_offeredInsideSwitchExpression`

---

### Gap K — Keywords not filtered by syntactic context

**Resolution:** `SentinelParser` now marks return, throw, and variable-initializer
sentinel positions as expression contexts.
`KeywordProvider` restricts those positions to expression keywords.
Bare import completion now emits `static` and top-level package candidates.

**Tests:** `keywords_returnPosition_expressionKeywordsOnly`,
`keywords_throwPosition_expressionKeywordsOnly`,
`keywords_variableInitializer_expressionKeywordsOnly`,
`keywords_bareImport_suggestsStaticAndTopLevelPackages`

---

### Gap Q — Static-imported enum constants are not offered as simple names

**Resolution:** `SimpleNameProvider.addStaticImportMembers` now includes
`ElementKind.ENUM_CONSTANT` when collecting static-imported members.

**Test:** `simpleName_staticImportedMethod_offeredWithoutQualifier`

---

### Gap P — Keyword literals are not filtered by expected type

**Resolution:** `CompletionCandidateRanker` now filters keyword literals when
an expected type is available.
`true` and `false` are retained only for `boolean` and `Boolean` expected types.
`null` is suppressed for primitive expected types.

**Test:** `simpleName_voidMethod_excludedWhenExpectedTypeKnown`

---

### Gaps N, O, R, S — Type-reference role filtering

**Resolution:** `ParsedSentinel` now carries a `TypeReferenceRole` for ordinary
types, constructor type positions, class/interface/record headers, `throws`
clauses, and annotation sites.
`CompletionEngine` applies role-specific type filtering to both type-index
results and `java.lang` fallback candidates when semantic analysis is
available.

`new` now suppresses interfaces, enums, abstract classes, and classes without
an accessible constructor.
Header roles filter extends/implements candidates by Java kind.
`throws` filters to `Throwable` subtypes.
Annotation sites filter to annotation types.

**Tests:** `methodBody_afterNew_suggestsConstructibleTypes`,
`classHeader_suggestsSuperAndInterfaceTypes`,
`typeReference_simpleNamePrefixes_suggestMatchingTypes`

---

### Gap H — No subpackage navigation when typing a fully-qualified name

**Resolution:** FQN package-prefix completion now falls back to package
navigation when no type receiver can be resolved.
This works in method bodies, imports, static imports, and class-body type
references, with JPMS accessibility filtering.

**Tests:** `fqnNavigation_topLevelPackage_suggestsSubPackages`,
`fqnNavigation_nestedPackage_suggestsTypesAndSubPackages`,
`fqnNavigation_deepPackage_suggestsTypes`,
`fqnNavigation_classBody_packagePrefix_suggestsSubPackages`

---

### Gap A — Static-import members not offered as simple names

**Resolution:** `SimpleNameProvider` now walks `CompilationUnitTree.getImports()`,
filters to static imports, resolves the declaring type via `getTypeElement`, and emits
its static members filtered by the declared member name and current prefix.
Wildcard static imports (`import static Foo.*`) emit all static members of the type.

**Test:** `simpleName_staticImportedMethod_offeredWithoutQualifier`

---

### Gap B — Member-access on an unimported simple type name returns nothing

**Resolution:** When the attributed receiver is an error node, the engine extracts the
receiver text, looks it up in `WorkspaceTypeIndex`, resolves the top match via
`getTypeElement`, and completes on its members through the normal member-access path.

**Test:** `memberAccess_unimportedSimpleName_typeIndexFallback_suggestsMembers`

---

### Gap C — Variable offered as completion in its own initializer

**Resolution:** `SimpleNameProvider.addMethodLocals` now skips any
`VariableTree` whose source range brackets the cursor offset, not just those
whose start position is before the cursor.

**Test:** `simpleName_variableNotOfferedInOwnInitializer`

---

### Gap D — No type-based ranking after `=`

**Resolution:** Expected-type ranking (`0_` / `1_` sort buckets) is now applied by
`CompletionCandidateRanker` for both locals and class members when
`SemanticCompletionContext.expectedValue` is `ExpectedValue.Type`.

**Test:** `simpleName_classMember_matchingDeclaredType_rankedBeforeNonMatching`

---

### Gap E — Type-index suggestions in expression context (revised)

**Original description:** After `String foo = ` with no prefix or a lowercase
prefix, no type names from the type index are offered.

**Revised:** This is not a basic-completion gap.
IntelliJ and JDT.LS both restrict type-index candidates to uppercase prefixes
in basic completion.
Lowercase prefix in any position — including expression positions with a known
expected type — does not trigger type-index lookup in basic completion.
The current behaviour (`String foo = arr§` → no type candidates) is correct.

Uppercase prefix already works: `String foo = Arr§` → `ArrayList`, `ArrayDeque`, …

Type-index candidates for lowercase prefix belong to smart/explicit completion,
which is out of scope until basic completion gaps are closed.

---

### Gap F — Import completions missing trailing semicolon

**Resolution:** `CompletionEngine.completeImport` checks `req.charAfterCursor() == ';'`.
When no semicolon already follows the cursor, it appends `";"` to each import item's
insert text.

**Test:** `importDeclaration_nonStatic_suggestsSegmentsAndTypes`

---

### Gap G — Static import inserts method snippet instead of bare name

**Resolution:** `CompletionEngine.completeImport` detects the `STATIC_IMPORT` context
and produces items with `newText = simpleName + ";"`, overriding the snippet factory
used for call-site completions.

**Test:** `importDeclaration_staticImport_suggestsSegmentsAndBareNames`

---

### Gap I — Static methods offered in instance member-access context

**Resolution:** `ProposalGenerator.proposeMemberAccessCandidates` now applies a two-sided
static/instance filter: static access receives only static members; instance access
receives only instance members (plus enum constants).

**Test:** `memberAccess_instanceReceiver_staticMethodsExcluded`
