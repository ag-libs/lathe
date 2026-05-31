# Completion Gaps

## Plan

Open gaps in priority order.

| Gap | Title | Difficulty | Depends on |
|-----|-------|------------|------------|
| K | Keywords not filtered by syntactic context | Medium | — |
| H | No subpackage navigation for FQN in code | Medium | — |
| L | Context-sensitive statement keywords | Medium | K |
| M | Keyword ranking by semantic fit | Medium | K |
| J | No completions after `::` | Hard | — |

Gap E has been revised — see the Closed section.

**K first** because the changes are contained in `KeywordProvider` and
`SentinelParser`, have no dependencies, and fix obvious wrong behaviour
(statement keywords leaking into return and initializer positions).

**H second** because it is self-contained inside `CompletionEngine.completeMemberAccess`
and uses the existing `WorkspaceTypeIndex` without new infrastructure.

**L and M third and fourth** because both depend on K landing first — L extends
the syntactic context classification K introduces, and M adds ranking on top of
the corrected keyword sets.

**J last** because it requires a new `MEMBER_REFERENCE` sentinel context,
functional-interface compatibility filtering, and no existing path to build on.

---

## Open

### Gap K — Keywords not filtered by syntactic context

**Difficulty:** Medium

**Symptom:** In several positions the engine offers keywords that are
syntactically invalid there.

| Position | Wrongly offered | Should be |
|---|---|---|
| `return §` | `if`, `for`, `while`, `do`, `switch`, `try`, `throw`, and the rest of the statement set | Expression keywords only: `new`, `null`, `true`, `false`, `this`, `super` |
| `String foo = §` | Same full statement set | Expression keywords only: `new`, `null`, `true`, `false`, `this`, `super`, `var` |
| `import §` | Nothing | `static` keyword + top-level package segments |

**IntelliJ behavior (reference):**
Both JDT.LS and IntelliJ offer statement keywords only at genuine statement
positions.
In expression positions (`return`, variable initializer, argument) they restrict
to expression keywords.
IntelliJ includes `new` in the expression set; JDT does not.
Lathe follows IntelliJ here — `new` belongs in expression positions because
`return new Foo()` is common.

`var` must not appear in expression positions — it is only valid as a local
variable type declaration.

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
- Parent is `ReturnTree` → set `inExpression = true` on `ParsedSentinel`.
- Parent is `VariableTree` where the sentinel is the initialiser → same flag.

Make `KeywordProvider.suggestCandidates` check `inExpression` and
restrict to the expression keyword set in those positions.

**Resolution — bare import:**
Handle `receiverText == null` in `ImportCompletionProvider` to emit:
- the keyword `static` (filtered by prefix),
- top-level package segments reachable from the type index (filtered by prefix).

**Tests:** `keywords_returnPosition_expressionKeywordsOnly`,
`keywords_variableInitializer_expressionKeywordsOnly`

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

---

### Gap L — Context-sensitive statement keywords

**Difficulty:** Medium  
**Depends on:** Gap K

**Symptom:** Several statement keywords are offered unconditionally in all
statement positions, even when they are syntactically invalid there.

| Keyword | Should appear only when |
|---|---|
| `else` | Previous sibling statement is an `if` body |
| `catch` | Previous sibling statement is a `try` or `catch` block |
| `finally` | Previous sibling statement is a `try` or `catch` block |
| `break` | Inside a loop (`for`, `while`, `do`) or `switch` |
| `continue` | Inside a loop (`for`, `while`, `do`) |
| `yield` | Inside a `switch` expression (not a `switch` statement) |

**Root cause:** `KeywordProvider` emits context-sensitive keywords
unconditionally — it does not inspect the surrounding AST to check whether
the position allows them.

**Resolution:** Use the `ParsedSentinel` ancestor chain to detect the
enclosing construct.
For each context-sensitive keyword, add a predicate that walks the sentinel
parse tree to verify the required enclosing node or sibling is present.
This is purely a `KeywordProvider` change — no sentinel context variants needed.

---

### Gap M — Keyword ranking by semantic fit

**Difficulty:** Medium  
**Depends on:** Gap K

**Symptom:** All keyword candidates receive equal rank regardless of how well
they fit the current semantic context.
IntelliJ promotes certain keywords based on expected type and position:

| Condition | Promoted keywords |
|---|---|
| Boolean type expected | `true`, `false` ranked first |
| Equality comparison (`==`, `!=`) | `null` ranked high |
| Last statement of a non-void method | `return` ranked high |

**Root cause:** `CompletionCandidateRanker` does not have keyword-specific
ranking rules.

**Resolution:** Extend `CompletionCandidateRanker` to apply keyword sort
buckets based on `SemanticCompletionContext`:
- If `expectedValue` is `ExpectedValue.Type` where the type is `boolean` or
  `Boolean`, promote `true` and `false` to the top bucket.
- If the position is an equality comparison operand, promote `null`.
- If the cursor is at the last statement of a method with a non-void return
  type, promote `return`.

The last two conditions may require additional sentinel context signals.
Implement the boolean case first as it is the most common and easiest to detect
from `ExpectedValue`.

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

All gaps identified up to 2026-05-31 have been addressed.

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
