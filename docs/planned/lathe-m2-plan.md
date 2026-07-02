# Lathe — M2 Gap Work Plan

This is the working implementation plan for all gaps accepted into M2.
Gap detail lives in [gaps.md](../gaps/gaps.md); this document tracks sequence, grouping,
and subsumption relationships.
Mark items `[x]` as work completes; do not reorder completed waves.

---

## Subsumptions and dependencies

Before coding, note these structural relationships between gaps:

- **EG-014 is subsumed by FR-006.**
  FR-006's fix covers both override directions in `ReferenceTarget.matches`:
  the `elements.overrides(targetMethod, ee, targetOwner)` branch handles exactly the
  "search from override, miss interface-typed calls" case that EG-014 describes.
  Implementing FR-006 resolves EG-014 for free; they are treated as one unit.

- **CQ-0046 requires CQ-0043 as a sub-fix.**
  CQ-0046 explicitly identifies the asymmetric boolean filter from CQ-0043 as its second
  root cause.
  Both must be fixed in the same pass; CQ-0043 alone is insufficient because the constructor
  argument's expected type is also mis-resolved.

- **CQ-0047 is the same filter family as CQ-0043/0046.**
  The deleted candidates are void methods (via the `voidMethod` check) rather than booleans,
  and the mis-resolution comes from the void-lambda-body fallback rather than a missing
  `NewClassTree` handler.
  Same code area; fix in the same pass.

- **EG-023 is EG-008 extended.**
  EG-008 (done, M1) suppressed `wait`/`notify`/`notifyAll` on the value-receiver member-access path.
  EG-023 applies the same suppression to `this.`/`super.` and additionally suppresses
  `clone`/`finalize`.

- **EG-021 is EG-006 extended.**
  EG-006 (done, M1) boosted reactor-origin entries in workspace symbol results.
  EG-021 applies the same boost in the type-completion candidate comparator — different code
  path, same concept.

- **EG-029 is a prerequisite for EG-028.**
  EG-028's conservative `}`/`;` on-type formatting uses EG-029's range-aware `JavaFormatter`
  path.
  EG-029 must land and be verified independently before EG-028 is implemented.

- **CQ-0030, CQ-0042, and CQ-0044** all fail at the `TypeResolver`/`MemberAccessCompleter`
  boundary and are addressed in one investigation pass.
  CQ-0044's root cause is still a hypothesis; run the three isolation probes in the gap doc
  before writing any code.

---

## Wave 1 — Quick wins

No design required.
Each item is a 1–10 line change in a single well-understood location.
Deliver as a single PR.

- [x] **EG-027** — Add bounds check in `SourceLocator.toOffset`; return `OptionalInt.empty()`
  for out-of-range positions so references, implementation, call hierarchy, and type hierarchy
  return empty results instead of `ArrayIndexOutOfBoundsException`.
  Enable the four regression targets listed in the gap.

- [x] **CQ-0045** — In `CandidateFactory.variableCandidate`, format `type` with
  `TypeDisplayFormatter` and pass it as `labelDescription`, mirroring `fieldCandidate`.
  Guard the `null`-type case with no placeholder.
  Enable the two `@Disabled` tests in `CompletionPresentationTest`.

- [x] **EG-023** — Extend the Object-method suppression applied by EG-008 to the `this.` and
  `super.` member-access paths.
  Add `clone` and `finalize` to the suppression set alongside `wait`, `notify`, `notifyAll`.

- [x] **EG-033** — In `WorkspaceSymbolResolver`, resolve the declaration name position via
  `SourceLocator.declarationNamePosition` (already used by `MethodImplementationLocator`) and
  emit it as the symbol's `Range` instead of the constant `FILE_START`.
  Fall back to `FILE_START` when position resolution fails.
  Enable the two regression targets in the gap.

- ~~**CQ-0011** — gate `this`/`super` constructor-invocation keyword candidates~~ **Deferred to
  backlog (2026-07-01).** The gate would suppress the bare `this`/`super` keywords, which are valid
  expressions at every constructor statement position, so it removes a needed completion rather than
  fixing a defect. The correct treatment is a future `this(...)`/`super(...)` call-shape snippet at
  the legal first-statement slot, blocked until snippet completion exists. See the CQ-0011 re-triage
  note in gaps.md.

- [x] **EG-013** — In `ReferenceCandidateIndex.build()`, include each module config's
  `originalGenSourcesDir()` alongside its `sourceRoots()`, mirroring the logic already used
  by `WorkspaceModuleRegistry.allSourceRoots()`.
  Enable the two regression targets in the gap.

---

## Wave 2 — Core correctness

Each item has a fully-specified implementation in the gap doc and most have disabled tests ready.
Deliver as two or three PRs: FR-006/EG-014 together; EG-031 alone; EG-032, EG-034, and the
completion filter cluster together or as two PRs.

- [x] **FR-006 + EG-014** — Make `ReferenceTarget.matches` override-aware for `ElementKind.METHOD`.
  Extract `ReferenceTarget.resolveMethodElement(elements)` as a shared helper, reused by
  `MethodImplementationLocator` (DRY).
  Accept a match when any of three conditions holds: exact owner+descriptor (current fast path),
  `elements.overrides(ee, targetMethod, ...)`, or `elements.overrides(targetMethod, ee, ...)`.
  Keep `CONSTRUCTOR` branch exact.
  Fall back to exact comparison when the owner type cannot be resolved in the current compilation.
  Enable the two `@Disabled` tests in `ReferenceLocatorTest`.
  Add: cross-file variant, negative case (sibling override + unrelated overload not matched),
  constructor unchanged case.
  EG-014 (`ReferenceServiceTest.references_overridingMethod_includesOverriddenContractUsages`)
  is added as part of this work.

- [ ] **EG-031** — In `JdkSourceResolver`, fall back to `System.getProperty("java.home")`
  (with `toRealPath()`) when `JAVA_HOME` is unset.
  `JAVA_HOME` takes precedence when present.
  Distinguish two missing cases in log output:
  no JDK home resolvable (WARNING with remedy) vs `lib/src.zip` absent at a known path
  (WARNING naming the inspected path).
  Enable the two `@Disabled` tests in `JdkSourceResolverTest`.

- [x] **EG-034** — In `MethodImplementationLocator`, when the implementing method is an implicit
  record component accessor (`getPath(method)` yields no usable path), fall back to the matching
  `VariableTree` member exposed for the record component in the `RECORD` class tree.
  The regression now asserts the component declaration location.

- [ ] **EG-032** — In `SignatureHelpResolver`, when the member-select path's `getElement` does
  not yield an `ExecutableElement` (because javac overload resolution failed with empty args),
  fall back to a by-name overload lookup on the receiver's declared type, mirroring the
  `IdentifierTree` branch.
  The active-parameter computation already handles empty arg lists.
  Add the two proposed regression targets in `SignatureHelpTest`.

- [ ] **CQ-0043 + CQ-0046 + CQ-0047** (one PR, three related defects):
  CQ-0046 is done (`6cc9e5e`); CQ-0043 and CQ-0047 are still accepted and gate this item.
  - *CQ-0043*: Remove the boolean-only branch from `CompletionCandidateRanker.expectedTypeAllows`
    so boolean-returning candidates are demoted by `sortText` rather than excluded.
    Re-baseline `argumentPosition_referenceTypeParam_booleansExcluded` to assert ranking, not absence.
  - *CQ-0046* (done): Handle `NewClassTree` in `TypeResolver.resolveArgumentValueByPosition` so
    constructor argument slots resolve the constructor parameter type, mirroring the existing
    `visitMethodInvocation` branch.
    Enable the two `@Disabled` tests in `CompletionArgumentTest`.
    Add `TypeResolverTest.resolveExpectedValue_constructorArgumentByPosition_resolvesParamType`.
  - *CQ-0047*: When the completion site is inside a lambda body whose SAM returns `void`,
    suppress the `resolveExpectedArgumentValue` fallback in `MemberAccessCompleter` so void-returning
    methods are not excluded.
    Model as a distinct "void/statement" expected value or skip the fallback when the nearest
    enclosing lambda has a void SAM.
    Add the two proposed regression targets in `CompletionMemberAccessTest`.

---

## Wave 3 — TypeResolver receiver resolution

All three gaps are in `TypeResolver`/`MemberAccessCompleter`.
Investigate CQ-0044 first; the root cause is still a hypothesis.
Deliver CQ-0030 and CQ-0042 first if CQ-0044 investigation takes time.

- [x] **CQ-0030** — Force reattribution in `MemberAccessCompleter` when the initial resolved
  receiver type kind is `TYPEVAR`, just as it already does for `null` and `ERROR`.
  Enable `CompletionMemberAccessTest.memberAccess_classTypeVariable_afterChange_usesDeclaredBound`.

- [x] **CQ-0042** — In the receiver-resolution path, when the current path is a method invocation
  whose attributed type is `TypeKind.ERROR`, fall back to `Trees.getElement(path)` cast to
  `ExecutableElement` and use its declared return type.
  Run through the existing effective-completion-type logic (wildcard/type-variable unwrapping).
  Keep the fallback narrow: method-invocation receivers only.
  Enable `CompletionMemberAccessTest.memberAccess_typeErrorReceiver_fallsBackToElementReturnType`.

- [ ] **CQ-0044** — Run the three isolation probes from the gap doc before writing any code.
  If `var` inference is the cause: route through the sentinel/recovery pipeline so the initializer
  type is available.
  If the lambda-body recovery path is the cause: fix the attribution scope for incomplete lambda
  bodies.
  Add regression targets once root cause is confirmed.

---

## Wave 4 — New LSP features

Independent features; EG-029 must land before EG-028.

- [ ] **EG-017** — Implement `textDocument/documentHighlight`.
  Register `documentHighlightProvider` in `LatheLanguageServer.initialize`.
  Reuse `ReferenceTarget` identity and restrict the scan to the current document.
  Map each occurrence to a `DocumentHighlight` with `Read` or `Write` kind based on whether the
  occurrence is an assignment target.
  Add the two proposed regression targets in `DocumentHighlightTest`.

- [ ] **EG-018** — Implement `textDocument/selectionRange`.
  Register `selectionRangeProvider` in `LatheLanguageServer.initialize`.
  For each requested position, walk the enclosing `TreePath` from leaf outward and emit a nested
  `SelectionRange` chain (identifier → expression → statement → block → member → type).
  No type resolution required; runs on the parsed tree only.
  Add the two proposed regression targets in `SelectionRangeTest`.

- [ ] **EG-029** — Add a range-aware formatting path in `JavaFormatter` using GJF's
  `formatSource(text, ranges)` with the character range derived from the LSP request.
  Keep the whole-document path for `textDocument/formatting`.
  Add `RangeFormattingTest.rangeFormat_selectionInsideMethod_editsOnlySelectedLines` and
  `rangeFormat_unchangedSelection_returnsNoEdits`.

- [ ] **EG-028** — Wire `textDocument/onTypeFormatting` for `}` and `;` triggers using
  EG-029's range-scoped path.
  Register `documentOnTypeFormattingProvider` with `firstTriggerCharacter = "}"` and
  `moreTriggerCharacter = [";"]`.
  Return no edits when the file does not parse after the trigger.
  Add the two proposed regression targets in `OnTypeFormattingTest`.

---

## Wave 5 — Completion context expansions

Discrete providers or ranker changes; no shared prerequisite.

- [ ] **EG-021** — Apply a reactor-origin sort boost in the type-completion candidate comparator,
  reusing `TypeIndexEntry` origin information (same field used by EG-006 for workspace symbol).
  Reactor entries outrank dependency and JDK entries for an equal prefix match.
  Add `CompletionTypeRankingTest.completion_typePrefix_ranksReactorTypeFirst`.

- [ ] **CQ-0048** — In `KeywordProvider`, add `instanceof` as a candidate in expression position
  when the preceding expression resolves (via javac attribution) to a reference type.
  Gate on non-primitive receiver; suppress after primitive expressions.
  Add the two proposed regression targets in `CompletionSimpleNameTest`.

- [ ] **EG-016** — Add an annotation-member completion provider.
  Detect the `AnnotationTree` enclosing the cursor, resolve its `TypeElement`, and offer its
  `ExecutableElement` members as `name =` insert-text candidates.
  For enum-valued elements, additionally offer enum constants once the cursor is past the `=`.
  Add the two proposed regression targets in `CompletionAnnotationTest`.

- [ ] **EG-022** — In the `case`-label completion path, detect when the enclosing `switch`
  selector resolves to a sealed type and offer its permitted subtypes as type-pattern `case`
  labels (`case SubType name ->`).
  Fall back to the current general type completion for non-sealed switches.
  Add the two proposed regression targets in `CompletionCaseTest`.

---

## Wave 6 — Larger scope

Higher design effort or requiring significant new infrastructure.
Tackle in order; EG-015 and CQ-0002 are the most complex.

- [ ] **EG-005** — Add a secondary CamelCase initialism index entry per type name in
  `WorkspaceTypeIndex` (e.g. `AbstractServerFactory` → `ASF`, `AS`, `ASFac`).
  At query time, fall back to the CamelCase index when the prefix trie finds no result.
  Add the two proposed regression targets in `WorkspaceTypeIndexTest`.

- [ ] **EG-011** — Include extracted dependency and JDK source directories in the search roots
  passed to `CallHierarchyOutgoingLocator`, mirroring the M2 Find References scope expansion.
  Expose `dependencySources` from `Workspace` alongside `allSourceRoots()`.
  Add `CallHierarchyServiceTest.outgoingCalls_calleeInDependencySource_returnsDepCallee`.

- [ ] **EG-024** — For modular sources, restrict type-completion candidates to types whose
  package is exported by a module the current source module reads (directly or transitively).
  Intersect candidates against the module readability graph already available to javac.
  Add the two proposed regression targets in `CompletionTypeFilterTest`.

- [ ] **EG-015** — Add an override-completion provider that, when the cursor is at a
  member-declaration position in a class body, enumerates the enclosing `TypeElement`'s
  overridable inherited methods, filters by prefix, and returns `@Override` stub items.
  Reuse the supertype-walk already present in `MethodImplementationLocator` and EG-012.
  Add the two proposed regression targets in `CompletionOverrideTest`.

- [ ] **EG-003** — Two-phase hover for positions inside Javadoc:
  detect that the cursor falls inside a `DocCommentTree`, extract the referenced type via
  `DocTrees.getElement(DocTreePath)`, then delegate to the normal hover path with that element.
  Limit change scope to `HoverLocator` and a helper on `SourceAnalysisSession`.
  Add the two proposed regression targets in `HoverTest`.

- [ ] **CQ-0002** — Add a `METHOD_REFERENCE` sentinel site in `SentinelInjector`/`SentinelParser`
  triggered on `::`.
  For `TypeName::` offer static methods; for `this::` offer visible instance methods; for
  expression receivers offer instance methods.
  First slice: basic receiver-member listing without functional-interface compatibility filtering.
  Constructor references and array constructor references are a later slice.
  Add regression targets in a new `CompletionMethodReferenceTest`.

- [ ] **FR-005** — Add invoker client assertions that await and inspect the actual references
  response (URI and range, not only count).
  Add a focused service test verifying the references `CompletableFuture` completes with
  serializable `Location` values.
  Ensure operation logging retains request URI, target, elapsed time, and final hit count.

- [ ] **CQ-0035** — Investigate whether a backward scan from the sentinel position can synthesise
  a missing closing `}` for the enclosing method when `class != null` but `method == null`.
  Defer if no approach satisfies the "no ad hoc Java parsing" rule from CLAUDE.md.

---

## Exit criteria

- All gaps listed above are either `done` or explicitly deferred with a documented reason.
- Every implemented gap has at least one passing regression test.
- `mvn verify -pl lathe-maven-plugin` (unit + invoker) passes clean.
- Neovim workflow verified against Helidon and Dropwizard end-to-end:
  document highlight, selection range, override-aware references, and override completion
  all exercised manually.
- `roadmap.md` M2 section updated to reflect completed work.
