# Completion Gaps

## Plan

Open gaps in priority order.

| Gap | Title | Difficulty | Depends on |
|-----|-------|------------|------------|
| K | Keywords not filtered by syntactic context | Medium | — |
| H | No subpackage navigation for FQN in code | Medium | — |
| E | No type-index suggestions in expression context | Medium | K |
| J | No completions after `::` | Hard | — |

**K first** because the changes are contained in `KeywordProvider` and
`SentinelParser`, have no dependencies, and fix obvious wrong behaviour
(statement keywords leaking into return and initializer positions).

**H second** because it is self-contained inside `CompletionEngine.completeMemberAccess`
and uses the existing `WorkspaceTypeIndex` without new infrastructure.

**E third** because its natural implementation extends the expression path
that K already touches — specifically, offering type-index candidates when
`SemanticCompletionContext.expectedValue` is `ExpectedValue.Type` and the
prefix is empty or lowercase, which overlaps with the expression-keyword
filtering rule.

**J last** because it requires a new `MEMBER_REFERENCE` sentinel context,
functional-interface compatibility filtering, and no existing path to build on.

---

## Open

### Gap E — No type-index suggestions in expression context

**Difficulty:** Medium

**Symptom:** After `String foo = ` with no prefix or a lowercase prefix, no
type names from the type index are offered.
Uppercase prefixes already work because `shouldOfferBareTypeReference` fires for
`STATEMENT` context + uppercase first character.
The gap is empty-prefix and lowercase-prefix positions where an expected type
is available.

```
String foo = §          →  0 type-index candidates
String foo = Arr§       →  ArrayList, ArrayDeque, …  ← already works
String foo = arr§       →  0 type-index candidates
```

**Root cause:** `shouldOfferBareTypeReference` guards on `!prefix.isEmpty() &&
isUpperCase(prefix.charAt(0))`.
Empty and lowercase prefixes never enter `completeSimpleNameTypeReference`.

**Resolution:** When `SemanticCompletionContext.expectedValue` is
`ExpectedValue.Type`, query the type index with the current prefix regardless
of case and filter candidates to those whose qualified name is assignable to the
expected type.
Unresolvable candidates (no snapshot yet) remain as incomplete/low-rank results.
Guard the fallback with a non-empty prefix to avoid flooding the menu at `§`
in an untyped statement.

**Depends on:** Gap K (which extends the expression-context classification that
this gap also touches).

---

### Gap H — No subpackage navigation when typing a fully-qualified name

**Difficulty:** Medium

**Symptom:** Typing `java.` in a method body returns no completions.
Navigation from a package prefix to a sub-package or type is impossible
through a member-access chain in code.

```
inject "java."                    at method body  →  (no completions)
inject "java.util."               at method body  →  (no completions)
inject "java.util.stream.Stream." at method body  →  9 static methods  ← works only at type level
```

**Root cause:** The member-access path requires a `TypeElement` receiver.
When the attributed receiver is a `PackageElement`, the engine returns nothing.

**Resolution:** After `TypeResolver` fails to find a `TypeElement` for the
receiver text, check whether `elements.getPackageElement(receiverText)` returns
non-null.
If it does, switch to a package-navigation path: scan all `WorkspaceTypeIndex`
entries whose `qualifiedName` starts with `receiverText + "."`, extract the
next dot-segment, deduplicate, and return each unique segment as a
`CompletionCandidate` with `CandidateKind.PACKAGE`.
Type entries at exactly one segment deeper get `CandidateKind.TYPE_*`.

No new external API is needed — this is a self-contained extension in
`CompletionEngine.completeMemberAccess`.
When the reactor type index lands, it automatically extends coverage through
the same `WorkspaceTypeIndex` scan.

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

### Gap K — Keywords not filtered by syntactic context

**Difficulty:** Medium

**Symptom:** In several positions the engine offers keywords that are
syntactically invalid there.

| Position | Wrongly offered | Should be |
|---|---|---|
| `return §` | `if`, `for`, `while`, `do`, `switch`, `try`, `final`, `var`, … | expression keywords only (`new`, `null`, `true`, `false`, `this`, `super`) |
| `String foo = §` | same full statement set | expression keywords only |
| `import §` | nothing | `static` + top-level package segments |

**Root cause — statement/expression mismatch:**
The backward scan marks `return §` and `String s = §` as `STATEMENT` context
because the scan crosses `{` or `;` before the cursor.
`KeywordProvider` then offers the full statement keyword set.
The actual position is an expression — `return` and `=` are both value
consumers — but the backward scan cannot distinguish them from a bare statement.

**Root cause — bare import:**
`import §` with an empty receiver is not handled by `ImportCompletionProvider`,
which requires a non-null receiver text.

**Resolution — statement/expression mismatch:**
The sentinel parent in the attributed tree already carries the answer:
`ReturnTree` and `VariableTree` (initialiser) parents mean an expression
position.
Add two new cases in `SentinelParser.extractContext`:
- Parent is `ReturnTree` → set a `RETURN_VALUE` sentinel context
  (or reuse `ARGUMENT_POSITION`'s expression-keyword rule).
- Parent is `VariableTree` where the sentinel is the initialiser → add
  `valueContext = true` to `SemanticCompletionContext`.

Then make `KeywordProvider.suggestCandidates` check `valueContext` (or the new
context variant) and suppress statement keywords in those positions.

**Resolution — bare import:**
Handle `receiverText == null` in `ImportCompletionProvider` to emit:
- the keyword `static` (filtered by prefix),
- top-level package segments reachable from the type index (filtered by prefix).

This mirrors the behaviour the engine already provides for `import java.§`
but starting one level higher.

---

## Closed

All gaps identified up to 2026-05-27 have been addressed.

### Gap A — Static-import members not offered as simple names

**Resolution:** `SimpleNameProposalCollector` now walks `CompilationUnitTree.getImports()`,
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

**Resolution:** `SimpleNameProposalCollector.addMethodLocals` now skips any
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

**Resolution:** `ProposalGenerator.proposeMemberAccess` now applies a two-sided
static/instance filter: static access receives only static members; instance access
receives only instance members (plus enum constants).

**Test:** `memberAccess_instanceReceiver_staticMethodsExcluded`
