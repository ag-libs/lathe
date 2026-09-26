# Lathe — Method-Reference Completion

Completion after `::` in a method-reference expression (`String::§`, `this::§`, `service::§`,
`Type::new`). Tracked as [CQ-0060](../gaps/gaps.md#cq-0060--no-completions-after--method-reference)
(historically completion "Gap J", see [lathe-completion-gaps.md](../done/lathe-completion-gaps.md)).

## Motivation

Method references are everyday Java at any functional-interface call site — `stream.map(String::trim)`,
`list.forEach(System.out::println)`, `supplier(Foo::new)`. Today Lathe offers **nothing** after `::`,
so the developer must type the whole member name blind. Both IntelliJ and Eclipse JDT LS complete here,
and the completion [expectations](../done/lathe-completion-expectations.md) already list "Method References" as
an expected site.

## Current Lathe behavior (probed)

Probed against the `multi-module` invoker workspace, `app/.../Main.java`, via `dev/explore.py`
(`LATHE_DEBUG=1`):

```
inject "String::"       → (no completions returned)
inject "StringUtils::"  → (no completions returned)    # imported reactor type
inject "args::"         → (no completions returned)    # String[] parameter
inject "this::"         → (no completions returned)
```

Server log for `String::`:

```
[completion] inject prefix=|| receiver=|null| ctx=EXPRESSION hasDot=false
[completion] parsed valid=false sentinelCtx=null receiver=|null| class=null method=null role=ORDINARY
[completion] … items=0 reattributed=false
```

Contrast — member access on the same type receiver works and returns 17 items, but **static-only**:

```
inject "String."        → valueOf, format, join, copyValueOf, CASE_INSENSITIVE_ORDER, class …
                          (no length/charAt — instance members excluded for a type receiver)
```

## Root cause (confirmed)

Three coupled defects, in pipeline order:

1. **Injector does not recognize `::`.** `SentinelInjector` (`SentinelInjector.java`) detects only a
   preceding `.` (`hasDot = content.charAt(i) == '.'`). For `String::` the char before the token is
   `:`, so `hasDot = false` and `receiverText = null`. The bare position is classified as a statement
   expression (`ctx=EXPRESSION`), and the empty-prefix guard in `SentinelParser`
   (`if (prefix.isEmpty() && !hasDot) …`) would reject it regardless.
2. **The sentinel is not findable in the parse tree.** `String::__LATHE_SENTINEL__` parses as a
   `MemberReferenceTree` whose name (`__LATHE_SENTINEL__`) is a `Name`, **not** a child `Tree` node.
   `SentinelParser.SentinelFinder` overrides `visitVariable`, `visitMemberSelect`, `visitIdentifier`,
   and `visitErroneous` — there is no `visitMemberReference`, and the reference name is not a visitable
   identifier — so `scan` returns `null`, the parser yields `ParsedSentinel.invalid`
   (`valid=false sentinelCtx=null`), and completion returns nothing.
3. **The static/instance filter is a strict XOR.** `CandidateGenerator.membersOf` filters
   `el.getModifiers().contains(STATIC) == isStaticAccess`. A method reference on a **type** qualifier
   is legal in *both* forms — `String::valueOf` (static) **and** `String::length` (unbound instance) —
   so reusing the member-access filter (static-only for a type receiver, as the probe shows) would drop
   half the valid candidates.

## Goals

- Offer the receiver's methods after `::`, for every qualifier kind:
  - **type qualifier** (`String::`, `StringUtils::`) → both static and unbound-instance methods;
  - **expression / `this` / `super` qualifier** (`user::`, `this::`) → instance methods;
  - **`Type::new`** → a `new` constructor-reference candidate when the type has an accessible
    constructor.
- Prefix filtering (`String::to` → `toString`, …), identical to member-access.
- When the target functional-interface (SAM) type is resolvable from context, keep only candidates
  whose arity and parameter types are compatible, and rank the compatible ones first.
- Presentation and import behavior identical to member-access completion (no new import edits — the
  qualifier type is already in scope).

## Non-Goals

- Bound/unbound overload disambiguation beyond arity/parameter-type compatibility (e.g. resolving
  which of two same-arity SAM overloads a candidate binds to) — the ranking is a best-effort fit, not a
  full applicability inference.
- Generic-bound receivers (`T::`, where `T` is a type variable) — shares the deferred generic-bound
  receiver limitation of member access.
- Array-constructor references (`int[]::new`) — low value; deferred.

## Architecture

The design reuses the member-access pipeline end to end; the receiver resolution, reattribution,
snapshot handling, ranking, and presentation are all shared. The only genuinely new logic is SAM
matching (Layer 2).

### Layer 1 — offer members after `::`

1. **`SentinelContext`** — add `MEMBER_REFERENCE`.
2. **`SentinelInjector`** — recognize `::` immediately before the token: set a new `memberReference`
   flag on the injection result and collect the qualifier into `receiverText` via the existing
   `collectReceiver`. This unblocks the empty-prefix guard and gives the text-based fallback and
   package-candidate paths a receiver, mirroring the `hasDot` handling.
3. **`SentinelParser.SentinelFinder`** — add `visitMemberReference(MemberReferenceTree)` returning the
   current path when `node.getName()` equals the sentinel (mirrors `visitMemberSelect`).
   `visitErroneous` already descends into the wrapping error tree, so the node is reachable.
4. **`SentinelParser` (main parse + `classifySentinel`)** — extend the `receiverEndOffset` computation
   with a `MemberReferenceTree` branch using `getEndPosition(cu, ref.getQualifierExpression())`, and
   classify a `MemberReferenceTree` leaf directly as `MEMBER_REFERENCE` (a leaf-keyed special case like
   the existing `VariableTree` one at the top of `classifySentinel`). Skip the empty-prefix guard for
   this leaf kind.
5. **`CompletionEngine`** — route `case MEMBER_REFERENCE -> memberAccessCompleter.complete(...)`.
   Receiver resolution then flows through the position-based `TypeResolver.resolveReceiver`
   (`receiverEndOffset`), exactly as member access does.
6. **`MemberAccessCompleter.completeResolved`** — for `MEMBER_REFERENCE`:
   - **type qualifier** (`resolved.staticAccess()`): propose both static and instance members —
     smallest form is to call `CandidateGenerator.proposeMemberAccessCandidates` twice (static and
     instance) and dedup, or thread a "both" mode through it;
   - **expression / `this` / `super` qualifier**: instance members only (unchanged filter);
   - append a `new` candidate when the qualifier is a type with an accessible constructor
     (`CandidateFactory`), so `Type::new` completes.

### Layer 2 — SAM-aware filtering and ranking

When the reference sits in an argument slot, assignment, or return whose expected type is a functional
interface, filter and rank by the SAM descriptor:

1. Resolve the expected target type with the existing `TypeResolver.resolveExpectedArgumentValue`
   (already used by `MemberAccessCompleter` for the void-lambda case) and, for assignment/return, the
   corresponding expected-value path.
2. Obtain the SAM via the existing `TypeResolver.findFunctionalInterfaceMethod(declared, snapshot)`
   (already used by `isVoidFunctionalInterface`), giving the abstract method's parameter count and
   types.
3. For each candidate method, test method-reference compatibility against the SAM descriptor,
   accounting for the two forms:
   - **static / bound-instance**: candidate arity == SAM arity, parameter types assignable;
   - **unbound-instance** (type qualifier): the SAM's first parameter is the receiver; the remaining
     SAM parameters match the candidate's parameters.
   `Type::new` matches when a constructor's arity/parameters fit the SAM.
4. Keep compatible candidates; rank them ahead of the rest through `CompletionCandidateRanker` (reuse
   the existing `0_`/`1_` sort buckets rather than deleting incompatible ones outright, consistent with
   argument-position ranking).

When no expected SAM is available (e.g. `var r = String::` with no target), Layer 1's unfiltered
member list is returned as-is.

## Files touched

`SentinelContext`, `SentinelInjector`, `SentinelInjectionResult` (new `memberReference` flag),
`SentinelParser` (`SentinelFinder` + main parse + `classifySentinel`), `CompletionEngine` (routing),
`MemberAccessCompleter` (static+instance merge, `new` candidate, SAM filtering hook), and
`CandidateGenerator` / `CandidateFactory` (static+instance mode and the `Type::new` candidate).

## Testing

New `CompletionMethodReferenceTest` (mirroring `CompletionMemberAccessTest` fixtures):

- `methodReference_typeReceiver_offersStaticAndInstanceForms` — `String::` includes both `valueOf`
  (static) and `length` (instance).
- `methodReference_instanceReceiver_offersInstanceOnly` — `user::` excludes statics.
- `methodReference_thisReceiver_offersEnclosingInstanceMethods`.
- `methodReference_typeReceiver_offersNewConstructorReference` — `Type::` includes `new`.
- `methodReference_prefix_filtersByName` — `String::to` → `toString`.
- `methodReference_incompatibleArity_excludedWhenSamKnown` (Layer 2) — in `Stream<String>.map(§)`,
  a candidate whose arity does not fit `Function<String, R>` is demoted/excluded.
- `methodReference_noExpectedType_returnsUnfilteredMembers` (negative for Layer 2).

## Delivery order

Layer 1 and Layer 2 land together (per the accepted scope), but Layer 1 is independently testable and
should be the first commit; Layer 2's SAM matching is the second. The gap is `done` only when the
positive and negative regression targets above pass.
