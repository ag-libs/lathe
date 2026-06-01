# Completion Gaps

## Plan

Open gaps in priority order.

| Gap | Title | Difficulty | Depends on |
|-----|-------|------------|------------|
| U | Annotation element completion is under-specified | Medium | — |
| T | Declaration name slots are under-specified | Medium | — |
| J | No completions after `::` | Hard | — |

Gap E has been revised — see the Closed section.

Gap discovery now starts from [completion-semantics-audit.md](completion-semantics-audit.md).
That matrix records syntax-site behavior before fixes are made.

**J last** because it requires a new `MEMBER_REFERENCE` sentinel context,
functional-interface compatibility filtering, and no existing path to build on.

---

## Open

### Gap T — Declaration name slots are under-specified

**Difficulty:** Medium

**Symptom:** Name positions need explicit semantics so type/value candidates do
not leak into places where the user is declaring a new symbol.

Examples from the discovery matrix:

```
class §
class Test { String §; }
class Test { void m() { String §; } }
```

**Expected behavior:** Declaration name slots should generally suppress normal
symbol completion.
If snippets are later added, they should be explicit declaration snippets rather
than imported types, local values, or statement keywords.

**Likely root cause:** `VARIABLE_DECLARATION` exists, but declaration-name
semantics are not documented across class, method, field, and local-variable
name slots.

**Discovery test:** `completionSemantics_gapDiscoveryMatrix`

---

### Gap U — Annotation element completion is under-specified

**Difficulty:** Medium

**Symptom:** The current completion design now covers annotation type names
after `@`, but not the rest of the annotation surface.

Examples from the discovery matrix:

```
@SuppressWarnings(§)
@SuppressWarnings(va§ = "")
@Deprecated(since = §)
@Retention(§)
@Target({§})
@interface A { § }
@interface A { Str§ value(); }
@interface A { int value() default § }
```

**Expected behavior:** Annotation completion needs site-specific semantics:
- Annotation element name positions should offer element names only.
- Element value positions should use the annotation method return type as the
  expected value.
  For `@Deprecated(since = §)`, completion must not offer annotation element
  names such as `since` or `forRemoval`.
- Enum-valued elements should prefer compatible enum constants.
- Array-valued elements should use the component type inside `{ ... }`.
- Annotation declaration bodies should offer annotation member declarations,
  not method-body statements or value keywords.
- Annotation element return types should be restricted to legal Java annotation
  element types.

**Likely root cause:** `ANNOTATION_CONTEXT` currently means only “type after
`@`”.
There is no parsed site model for annotation argument names, argument values,
array values, declaration bodies, or default values.

**Discovery test:** `completionSemantics_gapDiscoveryMatrix`

---

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
