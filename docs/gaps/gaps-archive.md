# Lathe — Resolved Gaps (Archive)

Resolved (`done` / `non-goal`) gap entries, moved out of the active [gaps.md](gaps.md) per the
[gap lifecycle](gap-process.md). Kept for the historical record and regression-target references.

---

# Navigation, references, code actions (resolved)

## EG-069 — Deeply nested code that javac compiles ends the server with a stack overflow — done

**Status: done** (2026-11-23)

**Kind: bug**

**Value: medium — Size: S**

**Affects: the editor and MCP.**

Found by the pre-release fuzzing (2026-10-29) on EqualsVerifier.

### Observed behaviour

Valid code nested deeply enough overflows the stack of the thread analysing it.
A `StackOverflowError` is a `VirtualMachineError`, which ends the server by design, so the server exits:

- a call chain about 700 long (`new StringBuilder().append(1).append(1)…`), verified on main (2849e785);
- by the fuzzing report, an `if`/`else if` chain about 1000 long, or about 3000 terms joined with `+`.

It fires when such a file is opened or edited (the live analysis), and on MCP `outline` and `describe_symbol`;
the MCP server exits with status 1.
The write pass and `verify_change` compile such a file without overflowing.
javac itself compiles it.

The overflowing frames are Lathe's own tree walk, `TokenScanner.visitMemberSelect` under `WrittenSourceScanner.scan`, on top of javac's attribution.
Lathe's compile workers are plain threads with the JVM's default stack (1 MB on 64-bit Linux),
while command-line javac runs on the main thread, which gets about 8 MB.

Hand-written code rarely nests this deep;
generated sources can (long builder or `append` chains, large concatenated string constants, big dispatch `if` chains).

### Fix

The compile workers' threads now get a 16 MB stack, twice what command-line javac's main thread has; it is reserved address space, used only as a thread goes deep.
`StackOverflowError` stays fatal, in line with the fail-fast policy until GA.

Measured through a real `CompilationWorker` compiling in the live-analysis mode: with the 1 MB default a 700-call chain overflowed, as reported; with 16 MB a 10,000-call chain and a 5,000-branch `else if` chain compile (4 MB already covered the depths reported).
MCP's `outline` goes through the same workers (`DocumentFeatures.documentSymbolFuture`).
A chain of 30,000 constant strings joined with `+` did not overflow even the 1 MB stack, so the fuzzing report's concatenation case did not reproduce in that form and has no test.

### Not covered

- Code deeper than the larger stack still ends the server; the limit moves from a 700-call chain to well past what generated sources reach.

### Regression targets

- `CompilationWorkerTest.compile_deeplyNestedCallChain_compilesWithoutFatalError`
- `CompilationWorkerTest.compile_longElseIfChain_compilesWithoutFatalError`

---

## EG-119 — Workspace symbol initials do not match a nested type — done

**Status: done** (2026-11-23)

**Kind: bug**

**Value: low — Size: S**

**Affects: workspace symbol search in the editor and through MCP.**

Found by the differential run against jdtls on spring-petclinic (2026-11-22, Lathe `0.1.0-SNAPSHOT` at `c0d49de`, jdtls 1.63.0, `dev/jdtls_diff.py` over all 50 files), and reproduced by hand.

### Observed behaviour

`PCFHE` and `ProcCreFormHasErr` find nothing, while `ProcessCreationForm` finds `ProcessCreationFormHasErrors`, a `@Nested` class in `PetControllerTests`.
Top-level types match by initials and humps (`OCT` finds `OwnerControllerTests`, `PTF` `PetTypeFormatter`, `VC` the controllers), as EG-005 made them; jdtls matches the nested type too.

### Fix direction

Run EG-005's CamelCase matching over nested types as well as top-level ones.

### Resolution

CamelCase matching read the completion and import map, which holds reactor types only when they are top-level or public, so a package-private nested type such as a `@Nested` test class was never matched.
It now matches every named reactor type, nested and package-private ones among them (anonymous and local classes have no name to match), and lists the top-level ones first, so nested types cannot crowd them out of the capped list; workspace symbol search is its only caller.

### Regression targets

- `WorkspaceTypeIndexTest.searchCamelCase_packagePrivateNestedType_findsItByItsHumps`

---

## EG-118 — Type hierarchy with the cursor on a member answers nothing — done

**Status: done** (2026-11-23)

**Kind: bug**

**Value: medium — Size: S**

**Affects: type hierarchy in the editor, when the command runs with the cursor anywhere but on a type name.**

Found by the differential run against jdtls on spring-petclinic (2026-11-22, Lathe `0.1.0-SNAPSHOT` at `c0d49de`, jdtls 1.63.0, `dev/jdtls_diff.py` over all 50 files), and reproduced by hand.

### Observed behaviour

With the cursor on a method or a field name, such as `getId` in `BaseEntity` or `getName` in `NamedEntity`, `textDocument/prepareTypeHierarchy` answers nothing; on the class name it answers.
jdtls answers with the hierarchy of the enclosing class (`NamedEntity`, `Person`, `Visit` for `BaseEntity`).
28 of the run's 30 type-hierarchy misses were such positions, so an editor's type-hierarchy command finds nothing unless the cursor is on a type name.

### Fix direction

Prepare the hierarchy of the innermost type enclosing the position when the position is not on a type name, as jdtls does.

### Resolution

Off a type name, the hierarchy is now prepared for the type the position is about.
On a member's own name, at its declaration or at a use, that is the member's declaring class (`getId` in `BaseEntity` gives `BaseEntity`, as does `owner.getId()` elsewhere); anywhere else, a literal or a keyword in a call's arguments among them, it is the class around the position.
An anonymous or local class is not in the type index, so the named class around it stands for it; `Object` stands for no member's class (`x.toString()`), as its hierarchy is the whole index.
A type name still prepares that type, and a position outside every class (an import, the package line) still answers nothing.

### Regression targets

- `TypeHierarchyTest.prepareTypeHierarchy_cursorOffATypeName_preparesTheClassThePositionIsAbout`

---

## EG-120 — The `class` of a class literal is highlighted as a field — done

**Status: done** (2026-11-23)

**Kind: bug**

**Affects: semantic tokens in the editor (VS Code and Neovim), on every build.**

Found by the editor exploratory round (2026-11-22, Lathe `0.1.0-SNAPSHOT` at `c0d49de`, the working-tree Neovim client in a headless Neovim, on the Dropwizard `08e290b764`, Helidon `27a692eb0d`, EqualsVerifier `ab54bfce` and jdbi `567155b` clones), and reproduced by hand.

### Observed behaviour

In `Map.class`, `Configuration.class` or `Map.Entry.class`, the server sends a `property` token for the keyword `class`, so the editor colours it as a field.
Every class literal in code gets it: the three in jdbi's `GenericTypes.java` and `SerializableTransactionRunner.java` (`final Configuration config = handle.getConfig(Configuration.class);`); the ten in their Javadoc get no token.
Definition on that `class` answers nothing, which is right for a keyword.

javac models a class literal as a member select whose element is a field named `class`, and `TokenScanner.visitMemberSelect` emits a reference token for the element it resolves.
The hover formatter already meets the same modelling ("`this`, `super` and a class literal, which javac models as fields, get no modifiers").

### Fix direction

Emit no token for the `class` of a class literal (a member select named `class` whose element is the synthetic field), as for other keywords; check `Outer.this` and `Outer.super` the same way.

### Resolution

`TokenScanner.visitMemberSelect` now emits no token for a member select whose name is a keyword, as `visitIdentifier` already did for a bare `this` and `super`.
That covers the three members javac models as fields named after a keyword: a class literal's `class`, `Outer.this` and `Outer.super`; no real member can have such a name, so no other token is lost.
The type before them keeps its token, and a field or method selected after `Outer.this` or `Outer.super` keeps its own.

Checked in the editor on the jdbi clone: the class literals in code (`Configuration.class` in `SerializableTransactionRunner.java`, `Map.class` and `Map.Entry.class` in `GenericTypes.java`) had a `property` token and now have none, as the ones in Javadoc.

### Regression targets

- `SemanticTokensTest.memberSelect_keywordMember_noToken`

---

## EG-117 — Incoming calls leave out calls in an anonymous class's instance initializer — done

**Status: done** (2026-11-23)

**Kind: bug**

**Value: medium — Size: S**

**Affects: call hierarchy (incoming calls) in the editor, and what is built on it.**

Found by the differential run against jdtls on spring-petclinic (2026-11-22, Lathe `0.1.0-SNAPSHOT` at `c0d49de`, jdtls 1.63.0, `dev/jdtls_diff.py` over all 50 files), and reproduced by hand.

### Observed behaviour

In `PetTypeFormatterTests.java`, `makePetTypes()` builds `new PetType() {{ setName("Dog"); }}` and a second one the same way (lines 86 and 91).
References of `NamedEntity.setName` find all three calls in the file (58, 86 and 91); its incoming calls list only `testPrint` (58), neither initializer nor the enclosing `makePetTypes`.
jdtls lists the initializer as a caller.

### Fix direction

Credit a call in an initializer block to that initializer (as jdtls does) or to the enclosing method, the way a call in a lambda is credited to the enclosing method; never drop it.

### Resolution

A call outside the methods of an anonymous or local class (in an initializer block, or in a field's initializer, a lambda there included) is now credited to the method or constructor declaring the class, as a call in a lambda is credited to the method holding it.
That is where an anonymous class is created; a local class may also be created by a method nested in the declaring one, and its static initializer runs once, but the call is still credited to the declaring method rather than dropped.
So the two `setName` calls in petclinic's `makePetTypes()` are credited to `makePetTypes`.
Outgoing calls of the creating method already listed them, so the two directions now agree.
A call in a method of such a class is still that method's own.

Not covered: a call in a top-level or member class's initializer block or field initializer is still left out, as no method holds it; jdtls lists the field or the initializer as the caller.

### Regression targets

- `CallHierarchyIncomingLocatorTest.searchIncomingCalls_callInAnAnonymousOrLocalClassInitializer_creditsTheMethodDeclaringIt`

---

## EG-116 — Signature help is empty for a call on a type-variable receiver — done

**Status: done** (2026-11-23)

**Kind: bug**

**Value: medium — Size: S**

**Affects: signature help in the editor, in generic code.**

Found by the differential run against jdtls on spring-petclinic (2026-11-22, Lathe `0.1.0-SNAPSHOT` at `c0d49de`, jdtls 1.63.0, `dev/jdtls_diff.py` over all 50 files), and reproduced by hand.

### Observed behaviour

In `EntityUtils.java:46` (petclinic's tests), in `<T extends BaseEntity> T getById(Collection<T> entities, …)`, signature help inside `entity.getId(|`, where `entity` is a `T`, answers nothing; jdtls answers `getId() : Integer`.

### Fix direction

Look the method up in the type variable's bounds, as javac's member lookup does, when the receiver's type is a type variable.

### Resolution

A call on a receiver typed as a type variable now takes its overloads from the variable's bound, as javac looks the method up: from each type of an intersection bound (`<T extends A & B>`, JLS 4.9), and of the methods with the same parameters two of them have, the first bound's.
A captured wildcard is such a variable, so a call on `pets.get(0)` of a `List<? extends Pet>` is covered too.
Access is checked through each bound, as for any other receiver, except that a private method of the bound is left out: a type variable does not inherit it (JLS 4.4).
Definition on such a call while it is being typed offers the same overloads, as it does for a receiver of a class type.

Not covered: a label shows the method's declared parameter types (`put(K key, V value)`), not the bound's type arguments, as for any receiver.

### Regression targets

- `SignatureHelpTest.signatureHelp_typeVariableReceiver_listsTheOverloadsOfItsBound`
- `SignatureHelpTest.signatureHelp_intersectionBoundReceiver_listsAMethodOnlyItsSecondTypeHas`
- `SignatureHelpTest.signatureHelp_intersectionBoundsShareAMethod_listsItOnce`
- `SignatureHelpTest.signatureHelp_typeVariableReceiver_leavesOutThePrivateMethodsOfItsBound`
- `SignatureHelpTest.signatureHelp_capturedWildcardReceiver_listsTheOverloadsOfItsBound`
- `SignatureHelpTest.signatureHelp_typeVariableBoundHasNoMethodOfTheName_returnsNull`
- `DefinitionLocatorTest.definition_typeVariableReceiverCallBeingTyped_offersTheOverloadsOfItsBound`

---

## FR-038 — References to a `toString` override list calls that cannot reach it — done

**Status: done** (2026-11-22)

**Kind: bug**

**Value: high — Size: S–M**

**Affects: references, highlights of uses and what they feed (the MCP `find_references`, rename's search, change impact), for an override of an `Object` method, `equals` and `hashCode` alike.**

Found by the differential run against jdtls on spring-petclinic (2026-11-22, Lathe `0.1.0-SNAPSHOT` at `c0d49de`, jdtls 1.63.0, `dev/jdtls_diff.py` over all 50 files), and reproduced by hand.

### Observed behaviour

References of `NamedEntity.toString` list 15 uses, of which only the 2 on an `Object` receiver can reach it (`sourceProperty.toString()` in `PostgresIntegrationTests`), and those 2 are what jdtls lists.
The other 13 are calls on receivers that can never be a `NamedEntity`: `LocalDate.toString()` (a final class; 4 calls in `PetControllerTests` and `VisitControllerTests`), `ToStringCreator.toString()` (`Owner.java:168`) and `Path.toString()` (8 calls in `I18nPropertiesSyncTest`).
The override seems to be searched as every override of `Object.toString`; on a large project its real uses are buried.

### Fix direction

Keep a call whose receiver's static type could be the overriding class: a supertype of it, a subtype of it, or an interface a subclass of it could implement; drop calls on a class unrelated to it, which no instance of the overriding class can be. Check `equals` and `hashCode` the same way.

### Resolution

The cause was not the matching: before searching, references swap the method for its contract, the topmost method it overrides (`Object.toString` for any `toString`), and so matched every override of that contract.
The search still runs on the contract, with its modules and planning, and rename is unchanged; but a reference search now names the class asked about (`ReferenceTarget.reachingClass`), and in a file where that class resolves, a use of the contract is kept only when its receiver is typed as the class, a subtype or a supertype of it (`Object` included), as jdtls counts them.
A call on an unrelated class (`LocalDate`, `ToStringCreator`) or an interface the class does not implement (`Path`) is dropped; on spring-petclinic this leaves the 2 calls jdtls lists.
The receiver of an unqualified call is the innermost enclosing class with the method as a member, as javac binds it, so a call in an anonymous class inside the class still counts; that of a `super` call is the calling class, and of `X.super` the class X; a Javadoc link counts through the class declaring the method it resolves to.
Where the class does not resolve, the file keeps every use of the contract, as before: a module upstream of the class or a sibling of its module, a class with no name in source (an anonymous class, a local class, an enum constant's body), a file outside the workspace.
An override written since the last compile still finds its calls, as only its class, not the method, needs to resolve.
`equals` and `hashCode` overrides follow the same path, checked for `equals`.
Not covered, by the rule chosen to match jdtls: a call through an interface the class does not implement (`Runnable r` holding a subclass that implements it, or `CharSequence.toString()`) is not listed, though a subclass could implement that interface; nor a call on a receiver typed as a type variable whose bound is such an interface; and with the cursor on an interface's method, a call on a class not implementing the interface is not listed either.
A class whose own name contains `$` is looked up by a name with `.` in its place, as the search's other lookups are, so it does not resolve and its uses are listed as before this fix.
Also not covered: a sibling implementation in a module where the class is not visible is still listed; and a method that implements an interface method only through a subclass (`class Impl2 extends Base implements Service`) is not found from `Base.handle`, as before this fix.

### Regression targets

- `LatheEngineNavigationTest.references_overrideOfObjectMethod_omitsCallsOnUnrelatedReceivers`
- `LatheEngineNavigationTest.references_implementationOfAnInterface_omitsCallsOnASiblingImplementation`
- `LatheEngineNavigationTest.references_implementationInADownstreamModule_listsTheCallThroughTheInterfaceUpstream`
- `LatheEngineNavigationTest.references_methodOfAnEnumConstantBody_listsTheCallsThroughTheEnum`
- `LatheEngineNavigationTest.references_overrideNotCompiledYet_listsTheCallsThroughItsClassAndObject`
- `ReferenceLocatorTest.method_contractReachingOnlyAClass_keepsTheUsesWhoseReceiverCanHoldIt` (the per-file filter: `Path`, `LocalDate`, `Runnable`, chained calls, an unrelated class with its own `toString`, a `super` call, Javadoc links)
- `ReferenceLocatorTest.method_contractReachingOnlyAClass_creditsAnUnqualifiedCallToTheClassItRunsOn`

---

## EG-115 — Signature help is empty inside a call to a statically imported method — done

**Status: done** (2026-11-22)

**Kind: bug**

**Value: high — Size: S**

**Affects: signature help in the editor, on every build; most of all in test code (AssertJ, JUnit, Mockito, MockMvc).**

Found by the differential run against jdtls on spring-petclinic (2026-11-22, Lathe `0.1.0-SNAPSHOT` at `c0d49de`, jdtls 1.63.0, `dev/jdtls_diff.py` over all 50 files), and reproduced by hand.

### Observed behaviour

In `OwnerTests.java:34`, `assertTrue(owner.getPets().isEmpty())`: signature help inside `assertTrue(|` answers nothing, while inside `owner.getPets(|` on the same line it answers `List<Pet> getPets()`.
29 of the run's 31 signature-help misses were such calls, and Lathe answered none of them: AssertJ `assertThat` (15), JUnit `assertTrue`, `assertFalse`, `assertEquals` and `assumeTrue`, Mockito `given`, MockMvc `get`, `status` and `view`, `assertThatExceptionOfType`.
jdtls answers each, for example `assertThat(String actual) : AbstractStringAssert<?>`.

### Fix direction

Resolve the method of an unqualified call through the static imports as javac does (single-static and on-demand), as a call to a method of the enclosing class already is.

### Resolution

An unqualified call whose name no enclosing class has a method of now takes its overloads from the file's static imports, as javac does (JLS 15.12.1): the accessible static methods a single-static import names, and only when none does, those an on-demand import brings (JLS 6.4.1); a method two imports reach is listed once.
A method of the name in an enclosing class still shadows every import.

### Regression targets

- `SignatureHelpTest.signatureHelp_singleStaticImport_listsTheImportedOverloads`
- `SignatureHelpTest.signatureHelp_onDemandStaticImport_listsTheStaticOverloads`
- `SignatureHelpTest.signatureHelp_staticImportShadowed_listsOnlyTheNearerOverloads`
- `SignatureHelpTest.signatureHelp_unqualifiedCallNoClassOrImportHasTheName_returnsNull`
- `DefinitionLocatorTest.definition_staticallyImportedCallBeingTyped_offersEveryImportedOverload` (definition, which takes a call's overloads from signature help while the call is being typed)

---

## EG-110 — Definition on a parameter type in a Javadoc `@see` goes to the method, not the type — done

**Status: done** (2026-11-19)

**Kind: bug**

**Value: low — Size: S**

**Affects: go to definition on a parameter type inside a Javadoc member reference (`@see #m(Type)`, `{@link #m(Type)}`).**

Found by the editor exploratory round on jdbi (2026-11-17).

### Observed behaviour

In jdbi's `Jdbi.java`, line 362 reads `@see #withHandle(HandleCallback)`.
Definition with the cursor on `HandleCallback` lands on `Jdbi.java:410`, the `withHandle` method, as it does on `withHandle` itself.
The same type in code (`withHandle(HandleCallback<R, X> callback)`) goes to `HandleCallback.java:22`.

### Fix direction

Inside a reference's parameter list, resolve the type under the cursor, as for a type in code.
Still on main `dbbdabf1` (2026-11-19): `JavadocReferences.visitReference` reports the whole reference as one span resolved to the member, so no offset inside it reaches a parameter type.

### Resolution

`JavadocReferences` reports a span for the type before `#` and for each parameter type of a member reference, ahead of the reference itself, so the narrowest span under the cursor wins.
Each element is javac's: the type before `#` is resolved as a reference of its own in the same comment, so `Sub#inherited` reaches `Sub`, not the member's owner, and keeps its span when the member does not resolve; a parameter type is the resolved method's parameter, through any array and a type variable's erasure, and a primitive gets no span and still reaches the member.
The public DocTree API gives these parts no position, so each span is the element's simple name, found as an identifier in the signature text, as `ReferenceLocator` finds every Javadoc mention; the parameter types are found one after the other after `(`.
A qualifier of a parameter type (`Test.Payload`) or a parameter name (`#take(Payload payload)`) stays the member's, and a reference wrapped over comment lines, whose signature text no longer matches its source span, is left out.
References, highlight and rename of these types now include the mentions too.

### Regression targets

- `DefinitionLocatorTest.definition_javadocReferenceSignature_reachesTheNameUnderTheCursor`
- `ReferenceLocatorTest.javadocLinkMentions_parameterTypeOfAMember_includedForTheTypeAndTheMember`
- `ReferenceLocatorTest.javadocLinkMentions_qualifiedArrayParameterType_matchesOnlyTheSimpleName`
- `ReferenceLocatorTest.javadocLinkMentions_typeBeforeTheMember_includedForThatTypeOnly`

---

## FR-035 — A rename started outside a file whose package is not its directory misses the declaration in it — non-goal

**Status: non-goal** (2026-11-18: not reproduced; the probe's rename edits the declaration in the misplaced file too)

**Kind: bug**

**Affects: the editor's rename and MCP `rename_symbol`, on a member declared in a file whose package names another directory.**

Found reviewing the MC-90 fix (#373, follow-up #375, 2026-11-18); suspected from the code, not reproduced.

### Suspected behaviour

MC-90 refuses a rename started in such a file, for any target searched beyond it, since Lathe finds a declaration's file from its package.
A rename started elsewhere is not refused.
`Right.java`, correctly placed, uses `wrong.count`, declared in `com/example/Wrong.java` with `package com.example.other;`: renaming `count` from `Right.java` resolves the declaration to `com.example.other.Wrong`, whose file `TypeSourceLocator.findSourceFile` does not find at `com/example/other/Wrong.java`.
The reactor index still knows the type, so `RenameService.requireRenameable` does not refuse, and the rename would edit the uses and leave the declaration, breaking the build.
The obvious refusal, a reactor type with no file at its package's path, cannot tell this from a secondary top-level class, which has no file of its own name either.

Related, from the same review: in a file whose package is not its directory, every compile-checked code action now finds an error after its edit (the mismatch), so it always compiles the original too to compare, doubling its resolve time there.

### Probe

The two files above, compiled into the mirror; `rename_symbol` on `count` at its use in `Right.java`: the answer should refuse, naming the misplaced file, and leave both files unchanged.

### Fix direction

Refuse when a declaration the rename must edit has no file at its package's path and is not a secondary top-level class.
The type index records no file name, but the class file's `SourceFile` attribute (`ClassSourceFile`) tells the two apart: a secondary top-level class names the file it shares, while a misplaced class names a file of its own name, which is just not where its package says.

### Probe result (2026-11-18, main `4838f70`)

The probe above, as an engine test: `rename_symbol` on `count` at its use in `Right.java` answered 2 edits in 2 files, `wrong.total` in `Right.java` and `public int total;` in `Wrong.java`, the misplaced file.
The rename finds the declaration in the file, though not at its package's path, so the build is not broken and nothing needs refusing.
The related note on code actions (a misplaced file's compile check compiles the original too) is a cost on a rare file, not a defect.

### Regression targets

None: closed without a change.

---

## FR-037 — Without a workspace manifest, cross-module answers stay in one module and claim to be complete — done

**Status: done** (2026-11-18)

**Kind: bug**

**Affects: MCP `find_references`, `call_hierarchy`, `rename_symbol`, `verify_change`, and the editor's references and rename.**

Found by the exploratory round on Keycloak (2026-11-17, Lathe 0.3.1).

### Observed behaviour

Keycloak was set up with the first build Lathe runs, sliced with `-pl services -am` (16 modules, all captured).
A sync under `-pl` writes no `workspace.json`, by design, so the server ran without the module graph.
`find_references` on `RealmModel.getName` then answered 7 references, all in `server-spi`; `rename_symbol` of `RealmModel.getDisplayNameHtml` edited its declaration alone, though `analyze_change` had just listed three overrides in other modules; and `verify_change` said "No errors".
Every answer still ended "All javac-resolved references to this exact declaration ... no need to re-verify with grep".
With the manifest written by a whole-reactor sync, the same calls answer 430 references and a rename of 11 edits in 8 files, overrides included, and `verify_change` checks the dependent modules.
`find_implementations` was right in both setups (718 implementers of `ProviderFactory`): it reads the type index.

### Resolution

A missing `workspace.json` is now a load problem, as one from another schema or one that cannot be read already was: `LatheLayout.MANIFEST_MISSING_REMEDIATION` says the answers cover each file's own module and names the sync that writes it.
Every MCP answer carries it as "Sync required:", `verify_change`'s too, and while the manifest has a load problem (`LatheNotes.moduleGraphMissing`) an answer about uses or implementations no longer says "no need to re-verify with grep"; a build-file change, which leaves the search whole, keeps it.
The editor shows it as a warning on load, unless the workspace has no module at all (`WorkspaceManifest.missing`), whose own message stays.
`rename_symbol` and the editor's rename refuse while the manifest has a load problem, however few modules the server has (a `-pl` sync of one module leaves one), and `run_test` says the runner is missing and why.
Re-checked on Keycloak sliced with `-pl`: references carry the note, the rename of `getDisplayNameHtml` is refused and edits nothing, and `run_test` names the missing manifest.

### Regression targets

- `LatheEngineNotesTest.notes_workspaceWithoutTheManifest_syncRequiredNamingIt_untilWritten`
- `LatheEngineRenameTest.rename_withoutTheManifest_refusesNamingItAndEditsNothing`
- `McpTextTest.referencesResult_moduleGraphMissingOrBuildFilesChanged_dropsTheGrepAdviceOnlyWithoutTheGraph`
- `LatheTextDocumentServiceTest.runTestFuture_freshWorkspace_returnsBlockedOnMissingRunnerJar`
- `LatheWorkspaceServiceTest.executeCommand_runTestFreshWorkspace_returnsBlockedOnMissingRunnerJar`

---

## EG-112 — A type from a dependency without sources is reported as not existing — done

**Status: done** (2026-11-18, PR #372)

**Kind: bug**


**Affects: `search_symbols` and `get_definition` (MCP), and the editor's workspace symbols, which run the same search, on a type whose dependency publishes no sources jar or has an unreadable one ([WS-39](gaps-archive.md#ws-39--a-dependencys-sources-jar-that-is-not-a-zip-fails-the-whole-sync--done)).**

Found while verifying WS-39 end to end (2026-11-17).

### Observed behaviour

In a project using the DB2 driver's `com.ibm.db2.jcc.DB2SimpleDataSource` (its dependency synced as one without sources):

- `describe_symbol` answers the declaration from the class file, `source: com.ibm.db2:jcc:12.1.4.0`, and `find_references` finds the 3 uses.
- `search_symbols DB2SimpleDataSource` answers `No symbols matching 'DB2SimpleDataSource'`, though the dependency's type index lists it.
- `get_definition` on it answers `No definition found.`

`WorkspaceSymbolResolver.toSymbolInformation` keeps only a type whose source file it can locate, and drops the rest without a word.
An agent concludes the type does not exist and searches elsewhere.

### Fix direction

Short of a decompiler ([EG-063](gaps.md#eg-063--a-dependency-without-a-sources-jar-has-no-source-to-navigate-to)), say what is known: list the type with its dependency instead of a file (`DB2SimpleDataSource (Class) — com.ibm.db2.jcc  com.ibm.db2:jcc:12.1.4.0, no sources`), and answer a definition with the dependency that declares it and a pointer to `describe_symbol`.

### Resolution

Go to definition on such a type says which dependency declares it: the editor once per dependency, MCP's `get_definition` with every answer, pointing to `describe_symbol`, which reads the class file.
`search_symbols` lists such types after the other dependency types, with the dependency in place of a file; the manifest names the type-index shards of the dependencies without sources (by a jar's first entry), and the type index maps only their types to the GAV.
The editor's workspace symbols still leave them out, as an LSP symbol needs a file the client can open: [EG-063](gaps.md#eg-063--a-dependency-without-a-sources-jar-has-no-source-to-navigate-to)'s stubs would give them one.

### Regression targets

- `LibrarySourcesTest.sourcelessDeclaration_jarWithoutSources_namesTypeAndDependency_otherwiseEmpty`
- `LatheEngineNavigationTest.definition_typeFromADependencyWithoutSources_namesItsDependency`
- `LatheTextDocumentServiceTest.definition_typeFromADependencyWithoutSources_saysWhereItIsDeclaredOnce`
- `WorkspaceSymbolResolverTest.sourceless_typeOfADependencyWithoutSources_namesItsDependency_otherDependencyNot`
- `WorkspaceSymbolResolverTest.sourceless_moreTypesWithSourcesMatchFirst_stillFindsTheSourcelessOne`
- `LatheEngineNavigationTest.searchSymbols_typeFromADependencyWithoutSources_listedWithItsDependency`
- `WorkspaceManifestTest.load_jarListedTwice_sourcelessShardByTheFirstEntry`
- `McpTextTest.searchResult_typeFromADependencyWithoutSources_namesTheDependency`

---

## EG-067 — The first build floods the log with warnings — non-goal

**Status: non-goal** (2026-11-17: both warnings are documented as expected in the [test-capture guide](../guide/test-capture.md) and the README; reopen if users report them)

**Kind: bug**

**Value: medium — Size: S**

Found by the pre-release probes (2026-10-27) on EqualsVerifier.

### Observed behaviour

The README quick start (`mvn clean test -Dlathe.capture.only=true`) prints 56 `[WARNING]` lines on
EqualsVerifier, a small reactor (2026-11-15):

- 21 are Surefire's `Tests run: N, Failures: 0, Errors: 0, Skipped: N`, one per test class, because
  capture-only excludes every test and Surefire reports skipped classes as warnings.
  They come from the Kotlin, `-21`/`-25`/`-26`, mockito and signedjar modules;
  `equalsverifier-core` and `equalsverifier-test` report `Tests run: 0, Skipped: 0` and no warning.
- 27 lines, 9 blocks of `The following dependencies could not be resolved at this point of the build but seem to
  be part of the reactor`, printed before `lathe:sync` on the parent.
  `lathe:sync` is an aggregator with `requiresDependencyResolution = TEST`, bound to the parent's
  `process-test-classes`, which runs before any child module is built.
  The sync itself is complete.
- 3 are maven-compiler's `I/O error reading dependency state` after `clean`, not Lathe's.

A first-time user sees a wall of warnings from a command the README told them to run; a Helidon-size
reactor prints thousands.

### Fix direction

Surefire's warnings: set `surefire.printSummary=false` in capture-only runs, which should drop the per-class lines
but likely not the final results line (unverified), or exclude every test instead of JUnit's dry run, which reports
`Tests run: 0` but gives up what the dry run keeps (a `@Suite` and Develocity see the tests).
The reactor warnings: bind the sync to the reactor's last project, so everything is built when it resolves, or
resolve its dependencies through Lathe's own resolver instead of Maven's aggregator resolution.
Either needs a probe on a real reactor (EqualsVerifier) with this build's extension.

### Delivered

The other half of this entry, a disabled Lathe saying nothing: with `CI` set or `lathe.disabled=true`, the Maven extension
and the Gradle settings plugin now say once that Lathe is off and which switch did it (`LatheFlags.disabledNotice`).

### Regression targets

- `LatheFlagsTest.disabledNotice_explicitSwitchOrCi_namesIt_noneWhenOn`
- `LatheSettingsPluginFunctionalTest.apply_switches_commandLineWinsThenDotLatheThenCiAndGradleProperties`

---

## FR-020 — Renaming edits sources a build task generates, which the next build overwrites — done

**Status: done** (2026-11-17)

**Kind: bug**

**Affects: MCP `rename_symbol` and the editor's rename.**

Found by the pre-release Gradle round (2026-11-15) on Caffeine.
Its `codeGen` source set compiles `build/generated/sources/local-cache`, written by its own JavaPoet task, which Lathe took for hand-written code:
`rename_symbol` on `FrequencySketch.ensureCapacity` edited 16 of its `*EvictionPolicy.java` files, which the next build overwrites and `git checkout` cannot undo.

Maven (through the extension), Gradle and OpenJDK now record the build directory in each tree's params (`buildDir`).
A source under it, other than the compile's processor output, is build-task output (`ModuleSourceConfig.isBuildOutput`), kept apart from annotation-processor output (`isGenerated`), so the write pass, the reference index and `verify_change` still treat it as any source.
A symbol declared there is refused up front, and a rename with an occurrence there is refused whole, naming the file, before any edit is written.
A Maven module records its build directory from its next compile, so an up-to-date module is protected after a clean build.
Processor output is still left unedited while the rename goes ahead.

The Helidon blueprint case first filed here, a processor deriving a separate member from the renamed one, is FR-034.

### Regression targets

- `LatheEngineRenameTest.rename_methodCalledFromBuildTaskOutput_refusesNamingTheFileAndEditsNothing`
- `LatheEngineRenameTest.rename_methodUnusedInBuildTaskOutput_renamesTheHandWrittenSources`
- `ModuleSourceConfigTest.isBuildOutput_rootUnderBuildDir_true_handWrittenOrAdjacentRoot_false`
- `ModuleSourceConfigTest.isBuildOutput_projectDirRoot_fileUnderBuildDir_true`
- `ModuleSourceConfigTest.isBuildOutput_processorOutputUnderBuildDir_false`
- `ModuleSourceConfigTest.isBuildOutput_noBuildDirRecorded_false`
- `OpenJdkSyncTest.sync_builtModuleAndTool_writesPinnedScriptAndBatchMarkersAndReleasesLock` (its `buildDir`)
- `MavenModuleConfigTest.write_buildDirDeclaredByExtension_recordsIt`
- `LatheModelInjectorTest.injectTreeScopes_buildDirectory_declaredForBothOutputDirs`
- `LatheCaptureTaskFunctionalTest.capture_javaProject_capturesFromCacheRefillsAWipedLatheAndKeepsTheLastGoodOne` (its `buildDir`)

---

## EG-111 — Signatures drop type-parameter bounds and `throws` clauses — done

**Status: done** (2026-11-17)

**Kind: bug**

**Affects: hover, signature help, and the rendered signature of `describe_symbol` and `analyze_change` (MCP).**

Found by the MCP exploratory round on jdbi (2026-11-17): `Jdbi.withHandle`, declared `public <R, X extends Exception> R withHandle(HandleCallback<R, X> callback) throws X`, rendered as `public <R, X> R withHandle(HandleCallback<R, X> callback)`, and `DriverManager.getConnection` lost its `throws SQLException`.
An agent writing the call could not see the checked exception it must handle.

### Resolution

Fixed in #362.
Hover renders each type parameter's bounds as declared, intersections included, and a method's or constructor's thrown types, capped like `permits` and `implements`; signature help shares the same helpers.
A type parameter is rendered by `MethodStubRenderer`, which override stubs already used, so the two cannot drift.
Both now leave out only a lone `Object` bound: `Collections.max` declares `<T extends Object & Comparable<? super T>>`, where the `Object` fixes the erasure.
A type variable's own hover is its declaration (`T extends Number`), no longer `T T`.

### Regression targets

`HoverTest.hover_methodWithBoundAndThrows_rendersBothAsDeclared`, `hover_jdkMethodDeclaringThrows_rendersThrowsClause`, `hover_constructorDeclaringThrows_rendersThrowsClause`, `hover_explicitObjectBound_keptBesideAnotherBound`, `hover_typeWithIntersectionBound_rendersTheBound`, `hover_typeVariable_rendersItsDeclaration`; `SignatureHelpTest.signatureHelp_boundAndThrows_inLabelWithParameterRangeShifted`, `signatureHelp_plainMethod_labelHasNoTypeParametersOrThrows`.

---

## EG-083 — The outline of `module-info.java` shows only the module line — done

**Status: done** (2026-11-16)

**Kind: feature**

**Affects: MCP `outline` and editor document symbols.**

Previously the module declaration was the only symbol emitted for a module descriptor.
The shared `DocumentSymbolScanner` now emits `requires`, `exports`, `opens`, `uses`, and `provides` as children of that module, in source order.
Each name combines the directive keyword with its primary module, package, or service target.
The full directive range and target selection range come from javac trees, including multiline directives and targets separated by comments.
An incomplete directive with no positioned target is omitted rather than exposing javac's synthesized error name.

Validated on JDK 26: 16 scanner tests and 29 engine navigation tests passed.
A live Helidon descriptor returned its five directive children through both LSP and MCP.

### Regression targets

- `DocumentSymbolScannerTest.scan_moduleDirectives_listsAllFiveKindsInSourceOrder`
- `DocumentSymbolScannerTest.scan_serviceClassDirectives_useANeutralSymbolKind`
- `DocumentSymbolScannerTest.scan_multilineDirectiveWithComments_rangesComeFromTheJavacTrees`
- `DocumentSymbolScannerTest.scan_openModuleWithoutDirectives_hasNoChildren`
- `DocumentSymbolScannerTest.scan_incompleteDirective_doesNotEmitAnInvalidSelectionRange`
- `LatheEngineNavigationTest.outline_moduleInfo_listsDirectivesAsModuleChildrenAtTheirTargets`

---

## EG-108 — Semantic tokens mark deprecated methods but not deprecated types — done

**Status: done.**

**Value: low — Size: S**

**Affects: the editor's semantic highlighting.**

Found by the pre-release JUnit editor round (2026-11-15).

### Observed behaviour

In a JUnit test, a call to the deprecated `TestPlan.getTestIdentifier(String)` gets the `deprecated` modifier; a reference to the deprecated class `org.junit.jupiter.api.extension.MediaType` gets none, though javac warns about both and hover says the class is deprecated.
A deprecated class is what a reader most wants struck through.

### Resolution

A type's and an enum constant's use carried no modifier at all; it now carries `deprecated` when the element is, as a field's and a method's use already did.
`static` stays on members only, so a nested type's uses look as before.

### Regression targets

`SemanticTokensTest.deprecatedTypeAndEnumConstant_usages_haveDeprecatedModifier`

---

## EG-084 — Diagnostics skip the build's javac plugins, and contradict the notes that run them — done

**Status: done.**

**Value: medium — Size: M**

**Affects: MCP `get_diagnostics` (and the editor's diagnostics, which compile the same way).**

Found by an agent's review of the MCP tools on Dropwizard (2026-11-01). Related: CA-18.

### Observed behaviour

`get_diagnostics` answers "No errors or warnings in LatheProbe.java" and, in the same reply,
"Not compiled: … [NullAway] …" (also seen with `[ReturnValueIgnored]`). Plain javac errors are reported,
and `--release 17` is honoured: the open compile runs without the build's `-Xplugin` (Error Prone,
NullAway), while the check behind the notes runs with them.

### Resolution

The analysis compile still runs no javac plugin and no processor, which keeps it fast; the editor already showed their findings after a save, merged from the file's last write.
`get_diagnostics` now merges them too, so the answer and its notes agree: a failed write's errors, and the warnings a successful write reported, while the file still has the content written.
The session keeps every written file's findings, not only an open one's, as the engine opens a file only after the write it asks about; javac's `-Werror` marker, which it puts on whichever file it compiled last, is left out.

### Regression targets

- `LatheEngineVerifyTest.diagnostics_fileFailsOnlyTheBuildsCompile_listsItsErrorBesideTheNote`
- `LatheEngineVerifyTest.diagnostics_buildOnlyWarning_listedForItsFileOnly`

---

## EG-106 — Hover drops the text of a Markdown doc comment, keeping only its links — done

**Status: done.**

**Value: high — Size: S**

**Affects: the editor's hover and MCP `describe_symbol`, on code documented with `///` (JEP 467, JDK 23+).**

Found by the pre-release Hibernate ORM round (2026-11-15); `hibernate-core` documents 973 of its main files with `///`.

### Observed behaviour

Hover on `Session` (`hibernate-core/src/main/java/org/hibernate/Session.java`, `public interface Session`) shows the
signature, then only the comment's link targets run together (`` `get(Class,Object)``find(Class,Object)` ``…), then
its `@see` lines: the prose, `@since` and `@author` are gone.
The same on `AbstractSqlAstTranslator`, and in MCP `describe_symbol`.
A `/** */` comment on the same kind of member renders in full.
0.3.1 behaves the same.

### Root cause

`JavadocMarkdownPrinter` has no case for javac's `RawTextTree`, the node holding a `///`
comment's Markdown text, so the scan skips the text and prints only the reference nodes between it, with no separator.

### Resolution

The printer catches a `MARKDOWN` node in `scan`, before the scanner's own `visitRawText` drops it, and prints its text as it is, as Markdown; links between the text print as code where they stood, and block tags as for a javadoc comment.
`RawTextTree` is newer than the JDK Lathe compiles against, so the node is known by its kind's name and its text is its `toString`, which equals its content (checked on JDK 26).
`@since` and `@author` stay out, as for a javadoc comment.

### Regression targets

`JavadocMarkdownPrinterTest.format_markdownDocComment_keepsItsTextLinksAndTags` (JDK 23+).

---

## EG-105 — Every keystroke cleared the file's diagnostics until the next compile — done

**Status: done.**

**Affects: the editor (every client), most visibly Neovim.**

Found by the jdtls comparison's harness (2026-11-11), measuring errors while typing on spring-petclinic.
On every `didChange` the server published an empty diagnostics list for the file before its 500 ms debounced compile,
so every error and warning in the file flashed off at each keystroke and came back half a second after typing stopped.
jdtls keeps the previous diagnostics until its next result replaces them.

### Resolution

A change no longer publishes an empty list; the next analysis replaces the diagnostics.
The clearing dated from the first commit, with no recorded reason, and nothing depended on it:
the clean publishes tests wait for come from analyses, closing a file still clears it, and a save always writes and republishes the file, an edit undone before its analysis included.
In Neovim the drawn diagnostics and `]d` follow the edited text at once; `vim.diagnostic.get()` keeps the old line until the next analysis, about half a second.

### Regression targets

- `LiveAnalysisTest.didChange_errorStillThere_keepsTheDiagnosticsUntilTheNextAnalysis`
- `LiveAnalysisTest.didSave_editUndoneBeforeItsAnalysis_clearsTheEditsDiagnostics`

---

## EG-078 — No formatter detection on a Gradle build — done

**Status: done.** Fixed by [Declared Formatters](../done/lathe-declared-formatters.md) (2026-11-11).

**Value: medium — Size: M**

**Affects: formatting in the editor and `format_file` on Gradle builds.**

Found while documenting the Gradle plugin (2026-10-30), from its design notes.

### Observed behaviour

On Maven the sync reads the project's Spotless configuration and runs the exact formatter release it pins.
The Gradle plugin reads no formatting plugin, so a Gradle project formats only through a committed
`lathe-style.json` with a command engine.

### Fix direction

[Declared Formatters](../done/lathe-declared-formatters.md): the project names its formatter in
`lathe-style.json`, and every sync, Gradle's included, resolves its jars.
Reading the Spotless Gradle plugin's step was rejected: its options are out of reach, and observing what it resolves
is wrong on real builds.

### Regression targets

- `LatheFormatterFunctionalTest` (Gradle TestKit): a declared formatter resolved and re-resolved under the
  configuration cache; the Spotless and pinned-defaults warnings; a declaration that cannot be completed.
- `declared-formatter` (Maven invoker fixture): a project without Spotless resolving a declared `aosp`.
- `FormatterStepsTest`, `WorkspaceStyleTest.read_declarationAndItsResolution_generatedSpecRuns`,
  `WorkspaceWatcherTest.poll_styleFileCreatedAfterSync_reportedAsBuildFileChange`.

---

## EG-074 — Log noise: a per-module INFO line, and a JDK warning on the MCP server's stderr — done

**Status: done.**

**Affects: the editor and MCP.**

Found by the pre-release test rounds (2026-10-29/30) on EqualsVerifier and Dropwizard:
`[compile] dropped N forked-javac -J option(s) …` was logged at INFO for every module compiler created (four times in one second on EqualsVerifier),
and on JDK 24 and later the first `sun.misc.Unsafe` memory access of a library the project's javac plugins bring (Error Prone's Guava) printed
`WARNING: A terminally deprecated method in sun.misc.Unsafe has been called` to the server's stderr, which an MCP client shows the user.

### Resolution

The dropped-options note is logged at FINE (`ModuleSourceCompiler.dropForkedLauncherArgs`): the sync already carries their module access over in `.lathe/jvm.args`.
The server and MCP argfiles set `-Dsun.misc.unsafe.memory.access=allow` (`ServerInstaller`), the property the JDK's `--sun-misc-unsafe-memory-access` option sets;
unlike that option, which JDK 17 and 21 reject at startup, the property is accepted by every supported JDK and read from JDK 23 on.
The project's own build keeps its warnings; only the server, which runs the project's plugins in process, stops printing them.
The release battery's stderr check is no longer a known failure.

### Regression targets

- `ServerInstallerTest.serverAndMcpArgs_joinJarsWithThePathSeparator_endWithTheEntryPoint`
- `ModuleSourceCompilerTest.dropForkedLauncherArgs_removesOnlyForkedJvmOptions_notedAtFine`
- `dev/mcp_battery.py` "server stderr: no JDK warnings" (Dropwizard on JDK 25)

---

## EG-031 — JDK home fallback works, but missing JDK sources had no actionable explanation — done

**Status: done.**

**Affects: the build's sync, the editor's go-to-definition into the JDK, and MCP `get_definition`.**

`JdkSourceResolver.resolve` already fell back from an unset `JAVA_HOME` to the running JVM's `java.home` and resolved symlinks.
What remained: when the JDK had no `lib/src.zip`, the sync logged only `[sync] jdk sources missing`, naming neither the archive looked for nor how to install it,
and go-to-definition into the JDK found nothing without saying why.

### Resolution

`JdkSourceData.missingSourcesNote` (shared through `lathe-core`) names the cause and the remedy:
the `<home>/lib/src.zip` that was not found, or that no JDK home was found;
how to install the sources (the `openjdk-<major>-source` package on Debian/Ubuntu, named only when the version gives a real major, or the installer's source option);
or to build with a JDK that has them (`JAVA_HOME`, or the Gradle toolchain), and to sync again.
The sync logs it at INFO: a build on a JDK without sources (a CI image) is fine, and the editor says so itself.
A definition that finds nothing reports, in the same pass, whether it named a JDK element while the workspace has no JDK sources (`NavigationFeatures.definitionResult`);
a project compiled for Java 8 sees the JDK's classes in no module, so those are looked up in the running JDK's modules.
The editor shows the note once per session (`window/showMessage`); MCP `get_definition` gives it in place of "No definition found." with every such answer (`LatheDefinition`).
`LatheLayout.JDK_SOURCE_ZIP` names the archive for the resolver and the note.
The troubleshooting guide has the matching entry.

### Regression targets

- `JdkSourceDataTest` (the note with a known home, with none, without a real major version, and empty when the sources are there)
- `LibrarySourcesTest.jdkElementWithoutSources_jdkTypeWithoutJdkSources_isTrue`
- `LibrarySourcesTest.jdkElementWithoutSources_jdkSourcesPresentOrWorkspaceType_isFalse`
- `LibrarySourcesTest.inSystemModules_jdkClassFound_projectClassNot`
- `LatheTextDocumentServiceTest.definition_jdkTypeWithSourcesMissing_explainsOnceWhatToInstall`
- `LatheEngineNavigationTest.definition_jdkTypeWithSourcesMissing_explainsWithEveryAnswer`
- `McpTextTest.definitionResult_emptyWithJdkSourcesNote_showsTheNote_otherwiseNoneFound`
- `JdkSourceResolverTest` (the home fallback: unset `JAVA_HOME`, symlinked home, no `src.zip`)

---

## EG-103 — A partly documented override does not inherit the parts it leaves out — done

**Status: done.**

**Affects: the editor and MCP.**

Found by the review of EG-068's fix (2026-11-09).
Hover inherited javadoc only when an override had no comment at all, or where it wrote `{@inheritDoc}`:
an override documented only with `/** @throws IllegalStateException if closed */` showed just that line.

### Resolution

As the javadoc tool does, what an override's doc leaves out, or writes with no text, comes from the nearest overridden method that has it:
the main description, a `@param` (by position, shown under the override's own name), the `@return` when the method returns a value,
and the `@throws` of an exception the override's `throws` clause declares, matched by simple name; what the override writes itself wins.
Block tags print in javadoc's order: each parameter in its signature position, then the return value, exceptions and references,
so an inherited part sits where it belongs.
A void override never looks for an `@return`, so a documented one reads no overridden doc for it.
`{@inheritDoc}` and this automatic inheritance find the overridden part through one helper and the same tag-matching rules.
Signature help gets the same inherited description and parameter docs.

### Regression targets

- `HoverTest.hover_partlyDocumentedOverride_inheritsWhatItLeavesOut`
- `HoverTest.hover_overrideDocumentingLaterParam_printsParamsInSignatureOrder`
- `HoverTest.hover_voidOverride_inheritsNoReturn`
- `HoverTest.hover_inheritDocAndEmptyOwnParam_bothTakeTheParentsText`
- `SignatureHelpTest.signatureHelp_partlyDocumentedOverride_inheritsWhatItLeavesOut`

---

## EG-101 — Hover and `describe_symbol` signatures leave out modifiers and supertypes — done

**Status: done.**

**Affects: the editor and MCP.**

Split from EG-068 (an agent's review on Dropwizard, 2026-11-01).
A hover signature showed a method as `int run(int value)` and a type as `class Child`:
no modifiers and no `extends`/`implements` clause, so an agent could not tell a `default` interface method from an abstract one, or what a type extends, without opening the file.

### Resolution

`HoverFormatter` writes the modifiers the source would write, in the conventional order:
javac also reports those a declaration's kind or place implies, which are left out everywhere, so a hover shows what one reads in the file.
So an interface's methods read `void save(T item)` and `default void log()`, its constant `int LIMIT`, an enum constant `Color RED`, and a class nested in an interface `class Nested`;
`public static int run(int value)` keeps what it declares.
A type's line reads as its declaration: `final class Child extends Base<String> implements Runnable, Closeable`, `interface Repo<T> extends Crud<T>`, `sealed interface Shape permits Circle, Square`.
Supertypes its kind implies are left out (`extends Object`, an enum's `Enum`, a record's `Record`, an annotation's `Annotation`), as are an enum's `sealed` and `permits` when its constants have bodies.
A `permits` list names bare classes, and a long `implements` or `permits` list shows its first five, then how many more.
A field's type reads as a method's does (`private List<String> names`); `this`, `super` and a class literal, which javac models as fields, get no modifiers.
The class line above a constructor stays short, as context.
Annotations are not shown; deprecation is a note since EG-093.

### Regression targets

- `HoverTest.hover_members_showModifiersTheSourceWouldWrite`
- `HoverTest.hover_types_showModifiersAndDeclaredSupertypes`
- `HoverTest.hover_types_leaveOutWhatTheirKindOrPlaceImplies`
- `HoverTest.hover_types_omitSupertypesTheirKindImplies`

---

## EG-048 — Go-to-definition on a call with the wrong arguments finds nothing, and offers one overload only — done

**Status: done.**

**Signal:** requested in daily use (2026-10-31): after a refactoring changes a method's parameter types, its
not-yet-updated call sites are exactly where one wants to jump to the new declaration, and the jump found nothing.

Re-probed 2026-10-30/31 through `LatheEngine.definition` and MCP `get_definition`:
a call whose arguments matched no overload (`solo(42)` where `solo(String)`, or a wrong argument count) found nothing,
and an unclosed call (`c.greet(`, `greet` overloaded) led to one overload only.

### Resolution

**A complete call whose arguments match no overload** (PR #147): definition returns every accessible method or constructor the call can name,
through signature help's lookup (`SignatureHelpResolver.unresolvedOverloads`), so access rules, `super`/`this` and anonymous classes behave the same in both features;
one jumps directly, several open the client's picker. `NavigationFeatures.definition`, `declaration` and `typeDefinition` return a `List<Location>`.

**A call still being typed** (`c.greet(`, `new C(`): javac binds it to an overload anyway, so definition led to that guess.
A call counts as being typed (`SignatureHelpResolver.beingTyped`) when an argument has an error type
(javac's placeholder for a missing argument, a half-typed `name.` or `trim(`, an unknown name)
or when it has no closing `)` and javac took the next statement for its last argument, so the call ends where that argument does;
measured on the common editing states, typing above existing code included. Definition then offers every overload.
A call whose arguments are typed keeps its one overload, even with only its `)` missing; a complete call with an unknown argument (`c.greet(nmae)`) offers them all, as its binding is a guess too.
This reads javac's own types and positions; the sentinel pipeline the first proposal named was not needed.
Hover and declaration still describe the overload javac bound; signature help lists them all while a call is typed.

### Regression targets

- `DefinitionLocatorTest.definition_mismatchedArguments_returnsEveryNamedOverload`
- `DefinitionLocatorTest.definition_callBeingTyped_offersEveryOverloadUnlessArgumentsAreComplete`
- `LatheEngineNavigationTest.definition_callWithMismatchedArguments_returnsTheMethodInAnotherFile`

---

## EG-087 — `Type::new` and `var` are not navigable, and outgoing calls drop constructor references — done

**Status: done.**

**Affects: the editor and MCP `call_hierarchy` / `get_definition`.**

Found by an agent's review of the MCP tools on Dropwizard (2026-11-01):
outgoing calls listed `this::compute` but not `LatheProbe::new` (`CallHierarchyOutgoingLocator` skipped `<init>` member references),
and the `new` of `::new` and the `var` keyword resolved to nothing.

### Resolution

Outgoing calls list `Type::new` as a call to that constructor, at its `new`, as incoming calls already did; an array constructor reference (`int[]::new`) has no declaration and stays out.
Definition and hover on the `var` keyword of a local reach the inferred type (`CursorElements.varTypeAt`);
a primitive, array or unresolved type has no declaration, and a type without source falls back to the variable, so there `var` keeps the variable's own, as before.
Whether a declaration uses `var` comes from its type tree (implicit through JDK 26, on the keyword from 27), so a variable named `var` or a `"var"` in an annotation is never taken for the keyword.
Rename, references and MCP's position tools keep the plain element, and MCP's on-a-name check still refuses `var`: `var` is not a name of the type.
Definition and hover on the `new` of `Type::new` already reached the constructor (EG-096); a regression test now holds it.
The `var` keyword lookup moved from the refactor package to `DeclarationNames.varKeywordStart`, shared by both.

### Regression targets

- `CallHierarchyOutgoingLocatorTest.outgoingCalls_constructorReference_listedAtNew`
- `CallHierarchyOutgoingLocatorTest.outgoingCalls_arrayConstructorReference_skipped`
- `DefinitionLocatorTest.definition_varKeyword_reachesInferredType`
- `DefinitionLocatorTest.definition_varWithoutTypeSource_staysOnVariable`
- `DefinitionLocatorTest.definition_wordVarThatIsNoKeyword_isNotTheType`
- `DefinitionLocatorTest.definition_constructorReferenceNew_reachesConstructor`
- `DefinitionLocatorTest.onName_varKeyword_isNoName`
- `HoverTest.hover_varKeyword_describesInferredType`
- `HoverTest.hover_varWithoutDeclaredType_describesVariable`
- `HoverTest.hover_constructorReferenceNew_describesConstructor`

---

## EG-080 — Incoming calls of an override include callers that can never reach it — done

**Status: done.**

**Value: medium — Size: M**

**Affects: the editor's call hierarchy and MCP `call_hierarchy`.**

Found by an agent's review of the MCP tools on Helidon (2026-11-01).

### Observed behaviour

Incoming calls of `HttpRouteImpl.beforeStart`, which overrides `ServerLifecycle.beforeStart`, list every caller
of the interface method, including `Filter::beforeStart` and `OidcFeatureTest:741`.
`Filter` is unrelated to `HttpRoute`, so those calls never reach `HttpRouteImpl`.
Since EG-044 an override's incoming calls aggregate the parent method's callers by design;
nothing prunes the callers whose receiver type cannot hold the override's class.

### Fix direction

Keep a parent-method caller only when its receiver's static type is a supertype of the override's class
(or the override's class or a subtype), as find references already reasons about receivers;
a method reference's qualifier type counts as the receiver.

Also (exploratory round, 2026-11-04): incoming calls on EqualsVerifier's core stub `get()` include a call through the abstract base class.

### Fix

`CallHierarchyIncomingLocator` keeps a call through a method the override overrides only when its receiver can hold the
override's class: the receiver's static type is a supertype or a subtype of it.
An unqualified call's receiver is the innermost enclosing class inheriting the method, a method reference's is its
qualifier, a `super` call never dispatches to an override, and a receiver of unknown type (code with errors) is kept.
Find references and rename are unchanged: they still cover every call.
On Helidon, `HttpRouteImpl.beforeStart` now lists its four route callers, without `Filter::beforeStart`.
EqualsVerifier's call through the abstract base class is kept, as that receiver can hold the override.

Known limit: the check is static, so a call on an interface the override's class does not implement is left out even
if some subclass of that class implements it too. IntelliJ keeps a call whose receiver class merely inherits the base
method; this keeps it only when the receiver can hold the override's class.

### Regression targets

`CallHierarchyIncomingLocatorTest.searchIncomingCalls_callThroughOverriddenMethod_keptOnlyOnAReceiverThatCanHoldTheOverride`,
`LatheEngineNavigationTest.callHierarchy_overrideOfAnUpstreamMethod_findsCallersThroughItAcrossModules`.

---

## EG-090 — Incoming calls of an override miss callers that dispatch to it from an upstream module — done

**Status: done.**

**Value: medium — Size: S–M**

**Affects: the editor's call hierarchy and MCP `call_hierarchy`.**

Found by the exploratory MCP round (2026-11-04) on Dropwizard.

### Observed behaviour

Incoming calls answer "No callers found" for `App1.java:22 initialize`, `HelloWorldApplication:82 run`,
`MyConfiguredBundle:15 initialize` and the anonymous `getDataSourceFactory` overrides at `HelloWorldApplication:44/68`.
Their callers dispatch through the base method in an upstream module (`Application.java:88`, `EnvironmentCommand:66`,
`Bootstrap.java:150`, `HibernateBundle:72`, `AbstractLiquibaseCommand:78`); asked on the base method, they are found.
`HibernateBundle:47` lists only its own module's tests.
EG-044 (done) made an override's incoming calls include calls through its base method; across modules they are missing.
The opposite of EG-080, which lists too many.

### Fix direction

Search the base method's callers in the modules upstream of the override's, not only in the override's module and its
dependents.

### Fix

Two causes: the search covered only the override's module and its dependents, and in an upstream module the override's
class is not on the classpath, so its method could not be resolved there to match calls through the base method.

- The call-hierarchy item carries the methods the override overrides, each by its own class and erased descriptor (a
  generic base method's differs from the override's), and the override's class with its supertypes.
- `ReferenceSearch` also reads each reactor module declaring an overridden method, every source set of it, and the
  modules depending on it; in those, only the files spelling a supertype's simple name, as a call there reaches the
  override only on a receiver of such a type.
- Where the override's class is not visible, `CallHierarchyIncomingLocator` matches the base method by its own class and
  descriptor, and checks the receiver by name against the override's class and supertypes.

Measured on 2026-11-09 (one server per run; cold is the first request, warm the second; jdtls 1.51 on the same files):

| Override | Before: cold / warm, callers | After: cold / warm, callers | jdtls: cold / warm, callers |
|---|---|---|---|
| Dropwizard `App1.initialize` | 0.9 / 0.5 s, 0 | 1.6 / 0.6 s, 4 | 4.6 / 1.1 s, 4 |
| Dropwizard `HelloWorldApplication.run` | 0.6 / 0.3 s, 0 | 2.0 / 1.1 s, 2 | 2.5 / 0.9 s, 2 |
| Dropwizard `MyConfiguredBundle.initialize` | 0.2 / 0.2 s, 0 | 0.8 / 0.3 s, 1 | 3.1 / 1.0 s, 1 |
| Helidon `HttpRouteImpl.beforeStart` | 2.4 / 1.0 s, 4 | 2.0 / 1.0 s, 4 | failed (no item) |
| Helidon `WebClientMetrics.Builder.build()` | 0.3 / 0.2 s, 1 | 12.0 / 7.0 s, 3 | failed (no item) |

The Dropwizard callers are jdtls's, one for one (`Application.java:88`, `DropwizardTestSupport:302`,
`InjectValidatorFeatureTest:44`, `ApplicationTest:39/44`, `EnvironmentCommand:66`, `Bootstrap.java:150`).
Reading every dependent module whole (as IntelliJ and jdtls do) cost Helidon's `build()` 21.3 / 12.4 s; keeping only
the files that import a supertype cost 7.3 / 4.2 s but missed same-package tests and lambda receivers. Listing only
the supertypes that have the method (not `Supplier`) took the chosen filter from 14.4 / 8.1 s to 12.0 / 7.0 s.

Known limits: in a module depending on the base's, a call on a `var` or a lambda parameter of a supertype, in a file
never spelling it, is missed; an override of a common base method (Helidon's `Builder.build()`) still takes seconds.

### Regression targets

`CallHierarchyIncomingLocatorTest.searchIncomingCalls_overrideNotOnClasspath_findsCallsThroughItsBaseMethodByReceiver`,
`LatheEngineNavigationTest.callHierarchy_overrideOfAnUpstreamMethod_findsCallersThroughItAcrossModules`,
`CallHierarchyItemDataCodecTest.encode_decode_roundTrip_preservesAllFields`.

---

## EG-102 — Signature help does not resolve `{@inheritDoc}` or an undocumented override's javadoc — done

**Status: done.**

**Affects: the editor.**

Found by the review of EG-068's fix (2026-11-09).
Hover resolved javadoc inheritance (EG-068), but signature help read only the method's own comment:
for a call to an override whose doc is `/** {@inheritDoc} */`, or that has no doc, it showed no description and no parameter docs.

### Resolution

`JavadocLocator` resolves a method's doc chain once (`docChain`: its own doc, else the nearest overridden method's, with the overridden methods `{@inheritDoc}` can reach),
and both hover (`markdown`) and signature help (`signatureDocs`) print from it.
A `DocReader` caches the docs one request reads, so signature help parses an ancestor shared by several overloads once;
it lives for that request on the module's worker thread and is never stored.
Signature help takes the main description and each parameter's description by position, so an override with other parameter names still gets its parent's parameter docs,
and `{@inheritDoc}` inside an `@param` resolves too.

### Regression targets

- `SignatureHelpTest.signatureHelp_inheritDoc_resolvesDescriptionAndParamFromOverriddenMethod`
- `SignatureHelpTest.signatureHelp_undocumentedOverride_takesParentDocsByPosition`
- `JavadocMarkdownPrinterTest.signatureDocs_paramDocs_byPositionWithBlanksAndTypeParamsIgnored`

---

## EG-093 — Hover shows no overrides or deprecation — done

**Status: done.**

**Affects: the editor's hover and MCP `describe_symbol`.**

Found by the comparison with [java-index](https://github.com/slgobinath/java-index) (2026-11-05), whose hover adds what a method overrides, how often a symbol is used, and a deprecation note.
`HoverFormatter` rendered the signature and javadoc only, and the javadoc printer dropped the `@deprecated` tag.

### Resolution

Hover now opens a deprecated element's doc with `**Deprecated.**` and the `@deprecated` tag's text, which usually names the replacement, as the javadoc tool does;
the printer used to drop the tag. An element deprecated without the tag, or a constructor of a deprecated class, still gets the bare note.
`{@inheritDoc}` in a `@deprecated` tag takes the overridden method's deprecation text.
An override gets `**Overrides** Type.method`, the nearest method up the superclass chain, or else `**Specified by** Type.method`, the interface method it implements.
A same-type link (`{@link #other()}`) renders as `other()` rather than `.other()`.
The usage count is EG-104: it needs a cheap reference count (FR-029).

### Regression targets

- `HoverTest.hover_overridingMethod_namesNearestOverriddenMethod_plainMethodDoesNot`
- `HoverTest.hover_deprecatedMethod_showsDeprecationAndItsText_currentMethodDoesNot`
- `HoverTest.hover_overrideOfDistantSuperclass_beatsDirectInterface_implementationIsSpecifiedBy`
- `HoverTest.hover_deprecationWithoutTag_constructorOfDeprecatedClass_andInheritedTag`
- `JavadocMarkdownPrinterTest.format_linkToMember_readsAsMemberOrTypeDotMember`

---

## EG-068 — Hover and `describe_symbol` render javadoc loosely and describe the class at a constructor call — done

**Status: done.**

**Affects: the editor and MCP.**

Found by the pre-release probes (2026-10-27) on Dropwizard, Helidon and EqualsVerifier:
each `<p>` became three or four blank lines, an `@see <a href=…>` link broke across lines,
`{@inheritDoc}` was not resolved (no javadoc for an overriding `equals`),
and on `new BlockingArrayQueue<>(min, max)` the answer described the class, not the constructor.

### Resolution

`JavadocMarkdownPrinter` holds a paragraph break until the next text, so any run of `<p>`/`</p>` and source line breaks yields one blank line.
An `<a href>` renders as a one-line markdown link, `[label](url)`, with parentheses in the URL escaped;
an anchor with no `href`, or one relative to `{@docRoot}`, keeps its label only, and an unclosed anchor ends at the next paragraph or tag.
`JavadocLocator.markdown` resolves inheritance: a method with no comment takes the nearest overridden method's, its `@param` names renamed to the override's by position,
and `{@inheritDoc}` in the main text, `@param` (by position), `@return` or `@throws` takes the matching part, following the chain.
Overridden methods' docs are parsed on demand, only as far as a lookup reaches.
Hover on the type name of `new X(…)` describes the constructor the call resolves to (`CursorElements.constructorAtNewClassType`, shared with EG-096),
under a `class X<T>` context line; an undocumented constructor shows its class's javadoc, however the cursor reached it.
Follow-ups: missing signature modifiers and supertypes (EG-101), signature help's inheritance (EG-102),
and inheriting the parts a partial comment omits (EG-103).

### Regression targets

- `JavadocMarkdownPrinterTest.format_paragraphTags_collapseToOneBlankLine`
- `JavadocMarkdownPrinterTest.format_anchorWithHref_rendersOneLineMarkdownLink`
- `JavadocMarkdownPrinterTest.format_anchorEdgeCases_escapeParensDropDocRootAndCloseUnclosed`
- `JavadocMarkdownPrinterTest.format_anchorWithoutHref_printsLabelOnly`
- `HoverTest.hover_newExpression_describesConstructorWithClassContext`
- `HoverTest.hover_anonymousClassConstructor_omitsNamelessClassLine`
- `HoverTest.hover_inheritDocTags_resolveFromOverriddenMethod`
- `HoverTest.hover_undocumentedOverride_inheritsThroughChain_ownDocWins`
- `HoverTest.hover_undocumentedOverride_namesInheritedParamsAsOverrideDoes`

---

## EG-030 — Neovim indentation: resumed chains and continuation edge cases — done

**Status: done.**

**Affects: Neovim live indentation (`lathe-neovim/runtime/lua/lathe/indent.lua`).**

The core Google-Java-Format fixtures shipped earlier (wrapped arguments, record components, lambda bodies, statement-end dedenting, basic selector chains).
Three shapes remained, all because each rule looked only at the previous line:

- a selector resuming after wrapped arguments (`        })` then `.get();`) indented past the closer (12) instead of to the chain (4),
  and the statement after the chain stayed at the closer's depth;
- a continuation line led by a binary operator (`+ "b"`, `? 1`) did not indent, and the statement after one did not dedent;
- a line led by `)` or `]` dedented one block from the previous line, and with a parser took the enclosing block's indent, instead of aligning with the line that opened it.

Checking against google-java-format's own output (below) found more, also on main:
the first statement of a lambda body inside wrapped arguments went back to the enclosing statement's start;
a block body or `}` after a wrapped `throws` clause, or under a `switch` expression after `=`, took the wrong owner line;
and a line after a `//` comment ending in punctuation (`// see below:`) took an extra continuation.

### Resolution

`balancing_line` finds the line holding the opener that matches the closers a line leaves unmatched,
by a bracket count over text that skips string/char literals, line and block comments, and text-block content.
It is a text count, not a parse, because the indenter must work mid-edit and tree-sitter models only `{}` blocks for it.

- A selector or leading-operator line anchors to that line: it aligns under a line of the same kind, otherwise one continuation past it.
  A leading operator counts as a continuation only when the previous line's code left the expression open without a marker of its own (so `-1,` after `{` stays an element).
- The statement-start walk crosses bracket groups, operator-led lines (`*` and `/` included) and wrapped `throws`/`extends` clauses.
- A block's body and its `}` follow `block_owner_indent`: the first line of the bracket groups its `{` line closes,
  climbing wrapped declaration lines and a comma-wrapped `implements`/`throws` list, but stopping where a new expression starts (after `=` or `->`).
- A line led by `)` or `]` takes the indent of its opener's line; `}` without a parser takes its block owner's.
- A comment's last character (a whole-line or trailing `//` comment, a `*/`) never reads as a trailing operator, nor does an old-style `case A:` label;
  a `//` line inside a chain does not break its alignment.

### Verification against google-java-format

Every `.java` file in this repository is formatted by google-java-format (Spotless), so the indenter was run over each code line of all 692 files (83,673 lines, comments and text blocks skipped),
with the whole file in the buffer and no tree-sitter parser, and compared with the formatter's column:
lines that differ went from 9,759 (11.66%) to 2,135 (2.55%); 7,628 lines were fixed and 4 regressed (wrapped `case` labels, a `+ """` concatenation, a method name wrapped below its return type).
Most of the remainder is the deeper nesting google-java-format gives arguments of chained calls (+8 rather than +4), which a previous-line model does not track.

### Regression targets

`lathe-neovim/test/indent_spec.lua` (57 fixtures; the 28 added here are named `resumed chain:`, `leading operator:`, `closing bracket line:`, `lambda body:`, `block body:`, `block closer:`, `statement end:` and `line comment:`).

---

## CA-19 — Import fixes ignore nested types — done

**Status: done.**

**Value: medium — Size: S**

**Affects: MCP `fix_imports`, and the editor's missing-import fixes if they share the candidates.**

Found by an agent's review of the MCP tools on Dropwizard (2026-11-01).

### Observed behaviour

`fix_imports` says "No import found for: Entry" although `java.util.Map.Entry` is available.

### Fix direction

Offer nested types whose simple name matches (importing the nested type, or the outer type and
qualifying), ranked after top-level types.

### Fix

The type index already held nested types (`java.util.Map$Entry`, simple name `Entry`), but `ImportCandidates` named
a candidate by its package and simple name, `java.util.Entry`, which javac could not find, so it was dropped.
A nested type is now never imported itself, by a plain or a static import: it is written as code there names it,
from a type the code can name, whose import is added.
`internal.SourceTypeNames` (moved from `CodeActionSupport`, CA-17's helpers) decides how a type is written and what it
imports, for the refactors, the import fixes and completion alike: `Map.Entry` with `import java.util.Map;`, plain
`Entry` where the file already sees it (an import of it, an inherited member type), and `java.util.Map.Entry` with no
import where the file gives `Map` another type.

- The quick fix "Qualify as 'Map.Entry' (java.util.Map.Entry)" replaces the name and adds the import; a name
  nested in many types (`Builder`) offers the ten most used.
- Completion writes a type that way wherever it adds an import: a type-index type, the type after `new`, an enum
  constant of a type the file does not name, and an unimported receiver (`Entry.` and `Entry.comparingByKey().`
  are rewritten at their leading name to `Map.Entry`). `SourceTypeNames` is made once per request, reading the
  file's imports and declared types once rather than for every candidate.
- "Add missing imports" and MCP `fix_imports` keep only top-level candidates, so a name with one top-level type is
  still added automatically; `MissingImport.nested` names the nested types as a hint, which `fix_imports` reports
  for an unresolved or ambiguous name, a qualifier (`Tool.run()`) included.
- Learned static imports were already limited to methods and fields, so they never offered a nested type.

Known limit: a reactor nested type with no class file yet (created, not compiled) has no element to check, so
completion writes it from its top-level type by name, without the clash check; when the file gives that top-level name
another type, the result does not compile until the type is built and completion is repeated.

### Regression targets

`QuickFixCodeActionTest.codeAction_nestedType_qualifiedFromItsTopLevelTypeNeverImported`,
`QuickFixCodeActionTest.codeAction_nestedType_inAHeaderAndNestedInManyTypes_qualifiedAtMostTenWays`,
`CompletionTypeIndexTest.typeIndex_nestedType_writtenAsTheFileNamesItNeverImported`,
`CompletionTypeIndexTest.typeIndex_nestedType_callChainReceiverAndNoAnalysisYet_writtenFromTopLevelType`,
`CompletionArgumentTest.argumentPosition_externalNestedEnumParam_offersConstantsQualifiedFromTopLevelType`,
`LatheEngineEditTest.fixImports_nestedTypes_neverImportedButNamedForAQualifiedName`,
`McpTextTest.importsResult_addedAmbiguousAndUnknown_listsEachKind_onlyTheAddedLineWhenNothingIsLeft`.

---

## EG-096 — Definition on a constructor call goes to the class — done

**Status: done.**

**Affects: the editor's definition and MCP `get_definition` on `new X(...)`.**

Found by the jdtls comparison's benchmark (2026-11-08) on Hibernate ORM.
Definition on `PersistEvent` in a constructor call selected its class declaration instead of the invoked overload.

### Resolution

Definition now consults the existing javac constructor resolver before the ordinary cursor element.
Generic, diamond, qualified, and anonymous-subclass creations select the invoked overload.
An implicit constructor without a source declaration falls back to its type.
Ordinary type references and generic arguments retain type navigation, and the existing locator preserves real workspace URIs.
See [Type Definition Navigation](../done/lathe-type-definition.md#1-goal) for the distinction between definition and type definition.

### Regression targets

- `DefinitionLocatorTest.definition_constructorCall_returnsInvokedOverload`
- `DefinitionLocatorTest.definition_typeReference_returnsTypeDeclaration`
- `DefinitionLocatorTest.locate_sameFile_overlayCompilationUnit_returnsRealWorkspaceFile` (extended to constructors)
- `LatheEngineNavigationTest.definition_constructorInAnotherFile_returnsSelectedOverloadAtWorkspacePath` (explicit and implicit constructors)

---

## FR-028 — Renaming a parameter or record component leaves its `@param` tag — done

**Status: done.**

**Value: medium — Size: S**

**Affects: rename, the editor and MCP.**

Found by the exploratory MCP round (2026-11-04) on Helidon.
Renaming a method parameter or record component left its Javadoc `@param` tag unchanged.
The MCP review list also included same-named string literals in unrelated modules for a method-local parameter.

### Resolution

The shared javac `DocTrees` traversal now resolves `@param` name nodes for method and constructor parameters, generic type parameters, and record components.
Reference search and rename consume the exact identifier spans, including when the rename starts from the tag.
Descriptions, unresolved tags, and unrelated declarations are left untouched.

For local symbols, the MCP review list scans string literals only within the declaring syntax scope.
Mention locations are mapped back to the document URI and adjusted for applied edits.
Members and types retain reactor-wide review, including a canonical constructor parameter normalized to its record component.
See [Rename](../done/lathe-rename.md#javadoc-parameter-names-and-local-review-fr-028).

### Regression targets

- `RenameProviderTest.rename_constructorParameter_updatesOnlyMatchingParamName`
- `RenameProviderTest.rename_typeParameter_updatesIdentifierInsideAngleBracketsOnly`
- `RenameProviderTest.rename_recordAccessorOverload_leavesComponentAndParamTagUntouched`
- `LatheEngineRenameTest.rename_parameter_updatesOnlyItsParamTagAndListsOnlyLocalText` (declaration and Javadoc cursors)
- `LatheEngineRenameTest.rename_recordComponent_updatesRecordParamTagAndAccessorUses` (declaration and Javadoc cursors)
- `LatheEngineRenameTest.rename_localVariable_listsOnlyTextInsideDeclaringBlock`
- `LatheEngineRenameTest.rename_canonicalRecordParameter_keepsMemberTextReviewAcrossFiles`

---

## FR-026 — A class declared with the same name in two modules resolves to one of them, and rename edits the wrong one — done

**Status: done.**

**Value: high — Size: M**

**Affects: references, implementations, definition, rename, MCP `search_symbols` and `analyze_change`.**

Found by the exploratory MCP round (2026-11-04) on EqualsVerifier.

### Observed behaviour

`equalsverifier-core` has a stub `nl.jqno.equalsverifier.internal.versionspecific.SequencedCollectionsValueSupplier`;
`equalsverifier-21` has the real class under the same name and depends on core (a multi-release overlay).
`search_symbols` lists only the `-21` one; definition from `equalsverifier-core/.../BuiltinVersionSpecificValueProvider.java:24`
lands in `-21`; `find_references` from the core declaration answers the two `-21` lines and misses core's own uses (lines 8
and 24), still saying "no need to re-verify with grep"; `find_implementations` of `GenericValueSupplier.get()` finds 8 of 10
(not the two core stubs).
`rename_symbol` on the core stub renamed and moved the `-21` file, left core untouched, and `verify_change` said "No errors".
The log shows `[type-index] duplicate type … skipped`: the index keeps one entry per binary name.

### Resolution

`ReactorTypeIndex` retains owner-qualified declarations and subtype edges instead of using the deduplicated candidate index as declaration identity.
Lookup follows the asking source tree and its captured classpath/module-path order.
Reference searches, incoming calls, rename guards, and implementation searches keep the selected declaration's identity; symbol search emits each copy with its own metadata and location.
Ownership filtering also applies to package-private searches across main and test source trees in the same module.
Duplicate subtypes and descendants are attributed in their own compile so differing inheritance cannot produce false implementations.
Refresh removes the replaced tree's old ownership and subtype edges and leaves other copies intact.
No persisted schema changes are required.

The existing refusal to introduce a conflicting type name is preserved.
The regression checks a refused collision and two successful renames to fresh names, verifying that old names disappear while the untouched copy stays searchable.

Verified with full analysis/server `verify`, targeted tests on JDK 17, and a live LSP probe of a neutral three-module Maven workspace on JDK 25.
The consumer prefers the first captured dependency; both copies appear in symbols and type/method implementations; each copy's reference and rename-preview file set is isolated.
The probe applies no source edits.

### Regression targets

- `DuplicateTypeTest.references_sameBinaryName_keepsDeclaringTreeSeparate` (types and members, both owners)
- `DuplicateTypeTest.definition_sameBinaryName_prefersTheAskingTree`
- `DuplicateTypeTest.rename_sameBinaryName_editsOnlyTheSelectedDeclarationAndItsCallers`
- `DuplicateTypeTest.searchSymbols_sameBinaryName_returnsBothDeclarations`
- `DuplicateTypeTest.implementations_sameBinaryName_returnsBothImplementations`
- `DuplicateTypeTest.implementations_differentHierarchyUnderSameBinaryName_checksEachOwnersCompile`
- `DuplicateTypeTest.renameTwice_duplicateTypeIndexRefresh_keepsTheUntouchedCopyAndRejectsCollision`
- `DuplicateTypeTest.referencesAndRename_packagePrivateDuplicateInOneModule_keepsSourceTreesSeparate`

---

## FR-025 — A constructor's uses through `super(...)`, `this(...)` and anonymous subclasses are not found — done

**Status: done.**

**Value: high — Size: S–M**

**Affects: the editor's references and call hierarchy, MCP `find_references`, `call_hierarchy` and `analyze_change`.**

Found by the exploratory MCP round (2026-11-04) on Dropwizard.

### Observed behaviour

`find_references` on `HibernateBundle.java:31` (`protected HibernateBundle(Class<?> entity, Class<?>... entities)`) answers
only the declaration. It misses `super(...)` at `dual/HibernateBundle.java:21` and the anonymous `new HibernateBundle<X>(Person.class) { … }`
at `HelloWorldApplication:42`, `MultipleUnitOfWorkTest:105/117`, `SubResourcesTest:132` and more; incoming calls say
"No callers found" and `analyze_change` "1 production, 0 test; relevant tests: none".
`JdbiHealthCheck:19` misses the `this(...)` at line 27.
Changing the constructor's signature, `verify_change` reports 8 errors at exactly those sites.
Definition and hover on the `this`/`super` keyword answer that the position is not on a name.

### Resolution

Reference and incoming-call matching include explicit constructor delegation identifiers, while javac's missing end positions exclude compiler-inserted calls from source results.
Parameterized and annotated creations use the underlying type identifier.
For anonymous subclasses, `CursorElements.newClassConstructor` follows the synthetic constructor's attributed delegation to the actual superclass overload; references and both call-hierarchy directions share it.
Anonymous interface creation remains an interface target, and type arguments remain type references.
Definition, hover, and name recognition on explicit constructor keywords already work in attributed analysis and are preserved by regression tests.

Verified on Dropwizard (2026-11-08), on JDK 25 with the rebuilt server and a freshly synced workspace:
`JdbiHealthCheck` has three references including `this(...)` at line 27, and `HibernateBundle` has five including `super(...)` and four anonymous constructions, including `HelloWorldApplication`'s field initializer.
Incoming calls and keyword definition/hover probes pass.
The old schema-3 workspace lacked reactor-output mappings and excluded the example module from search; installing the current SNAPSHOT and resyncing produced schema 6 with the dependency edges.
No probe edits were applied to sources.

### Regression targets

- `ReferenceLocatorTest.references_constructorDelegationAndGenericAnonymousCreation_keepsOverloadsSeparate`
- `ReferenceLocatorTest.constructorAtNewClassType_genericAnonymousCreation_resolvesConstructorButNotTypeArgument`
- `ReferenceLocatorTest.constructorAtNewClassType_anonymousInterface_keepsTheInterfaceTypeTarget`
- `ReferenceLocatorTest.resolveTarget_explicitConstructorKeywords_resolvesInvokedOverload`
- `CallHierarchyIncomingLocatorTest.searchIncomingCalls_delegationAndAnonymousGenericCreation_keepsConstructorOverloadsSeparate`
- `CallHierarchyOutgoingLocatorTest.outgoingCalls_anonymousGenericConstruction_resolvesSuperclassOverloads`

The full analysis/server verification and the constructor regressions on JDK 17 pass.

---

## FR-027 — `module-info.java` `provides … with` is missed by references and rename, and listed once per package segment — done

**Status: done.**

**Value: high — Size: S–M**

**Affects: references, rename, the editor and MCP.**

Found by the exploratory MCP round (2026-11-04) on Helidon.

### Observed behaviour

In a fresh session, `find_references` on `HstsFeatureProvider` (`webserver/hsts/.../HstsFeatureProvider.java:29`) answers 2
references, not the `with io.helidon.webserver.hsts.HstsFeatureProvider;` at `module-info.java:42`; renaming it reports
"2 edit(s) across 1 file(s)", moves the file and leaves `module-info.java` on the old name (`verify_change` then reports
`cannot find symbol` there). The same for `ServerFeatureProvider`: 79 references, none in a `module-info.java`.
Once `module-info.java` has been analyzed in the session, the line is found, but four times (once per package segment), and
renaming from that line writes `with io.HstsServerFeatureProvider.HstsServerFeatureProvider.HstsServerFeatureProvider.HstsServerFeatureProvider;`.
Related to EG-071 (per-segment matching of package names in `module-info.java`).

### Resolution

Type-reference candidate selection retains module descriptors that spell the target's simple name even when no import or package directory matches.
`ReferenceLocator` resolves member-select trees directly through `Trees.getElement`, so package prefixes no longer recover outward to the final type.
The referenced identifier contributes exactly one occurrence and rename edit.
The same search works before and after opening the descriptor, and when rename starts on the provider name in the directive.

Verified on Helidon's `HstsFeatureProvider` (2026-11-08) with the rebuilt server: one descriptor reference with the file closed or open, and one rename edit covering only `HstsFeatureProvider`.
The probe inspected the edit without applying it.

### Regression targets

- `ReferenceCandidatePlannerTest.planCandidates_moduleDirectiveIncludesType_returnsDescriptorWithoutImports`
- `ReferenceLocatorTest.references_moduleDirectives_matchesOnlyTypeIdentifierRanges` (provider, service in `provides`/`uses`, unrelated type, and exact identifier range)
- `RenameTest.rename_serviceProvider_findsDescriptorAndKeepsPackageSegments` (descriptor closed/open, rename from declaration/directive, and file move)

---

## EG-089 — Definition and hover on an interface call land on a nested implementation in the same file — done

**Status: done.**

**Value: medium — Size: S–M**

**Affects: the editor's definition and hover, MCP `get_definition` and `describe_symbol`.**

Found by the exploratory MCP round (2026-11-04) on Helidon.

### Observed behaviour

At `SocketTransportBinding.java:192` (`listenerConfig.backlog()`), definition goes to `ListenerConfig.java:3455`, a method of
the nested `ListenerConfigImpl`, not to the interface method at `:403` the call binds to (`invokeinterface
ListenerConfig.backlog`), and hover shows no javadoc ("Accept backlog." is on `:403`).
Every generated Helidon `*Config` has this shape; hand-written code too (`builder/codegen Utils.java:139 packageName` →
`TypeName.java:1475` in `TypeNameImpl`, not `:265`).
It needs the call resolved through compiled classes and the source file holding a nested class that implements the method;
the same layout compiled from source in one file resolves right. References and implementations from those sites are right.

### Resolution

`ec760788` matches a parsed declaration only under its own enclosing type chain, then matches method parameters from their type trees.
Definition lookup and parsed Javadoc lookup both use `SourceParser.declarationPath`, so a nested implementation no longer replaces its interface declaration.
The remaining ambiguity between overloads whose parameter types have the same simple name is outside this gap.

### Regression targets

`SourceParserTest.declarationPath_sameNamedNestedTypes_findsTheTargetsOwn` and
`SourceParserTest.declarationPath_overloadsByParameterType_findsEach`.

---

## EG-070 — `typeDefinition` is missing — done

**Status: done.**

**Value: medium — Size: M**

**Affects: the editor.**

Found by the pre-release test rounds (2026-10-29/30) on Dropwizard.

### Observed behaviour

`textDocument/typeDefinition` is not advertised, so Neovim's go-to-type-definition is unavailable.
A client that sends it anyway, or any other request Lathe does not implement, gets the protocol's "method not found"
and an INFO line in the log.

### Resolution

`textDocument/typeDefinition` is advertised and answers the declaration of the type of the symbol at the cursor: a
variable's or field's type, a method's return type (at its call or declaration), a constructor's class, a type itself;
an array's element type and a generic's declared type; nothing for a primitive or `void`. Sources are found as go to
definition finds them, so a dependency or JDK type opens its extracted source. Neovim 0.12's default `grt` and VS Code's
Go to Type Definition work with no client change. Definition, declaration, type definition and implementation now share
one request path in the services instead of four copies.

### Resolved part

Unsupported requests used to answer with an internal error and log a SEVERE line with a stack trace:
lsp4j's service interfaces implement every method Lathe does not as a default method that throws.
`LatheServer.responseError`, the launcher's exception handler, answers those with `MethodNotFound`.
Regression target: `LatheServerTest.run_unsupportedRequest_answersMethodNotFound`.

### Regression targets

`LatheTextDocumentServiceTest.typeDefinition_symbolsOfEachKind_goToTheirTypeDeclaration`,
`LatheLanguageServerTest.createCapabilities_includesTypeDefinitionProvider`,
`LatheServerTest.run_unsupportedRequest_answersMethodNotFound` (the unsupported-request part).

---

## EG-086 — An enum constant with a body cannot be resolved at its declaration — done

**Status: done.**

**Value: medium — Size: S**

**Affects: MCP tools by name or column, likely the editor's hover and definition there.**

Found by an agent's review of the MCP tools on Dropwizard (2026-11-01).

### Observed behaviour

At `RED {`, tools answer "no symbol named 'RED'", by name and by column, although `outline` lists it at
16:9. Constants without a body, and every use site, resolve.

### Resolution

javac's anonymous class tree for a constant's body starts at the constant's name, ahead of the `new Color() { … }` that
holds it, so the position found there was the anonymous class, whose element is the enum: tools answered "no symbol
named 'RED'" and hover and definition described the wrong thing. `SourceLocator.pathAt` now returns the constant at its
name, before the creation itself starts; positions inside the body are unchanged. Hover, definition and every tool that
finds the symbol at a position resolve the constant, as for a constant without a body.

### Regression targets

`SourceLocatorTest.elementAt_nameOfEnumConstantWithBody_isTheConstant`,
`LatheEngineNavigationTest.describeAndDefinition_enumConstantWithBody_resolveTheConstant`.

---

## EG-085 — Implementations miss anonymous classes and enum constant bodies — done

**Status: done.**

**Value: medium — Size: S**

**Affects: MCP `find_implementations` and `analyze_change`, likely the editor's go to implementation.**

Found by an agent's review of the MCP tools on Dropwizard (2026-11-01).

### Observed behaviour

Three `new Managed() { … stop() … }` classes (`JerseyClientBuilder:362`, `HttpClientBuilder:284`,
`MessageQueueFactory:41`) and an enum constant body's override (`RED { label() }`) are absent, so
`analyze_change` reports an override family of 7 where there are 10.
`rename_symbol` and `find_references` include them.

### Resolution

Go to implementation, which MCP `find_implementations` shares, now searches the type index's unnamed subtypes too
(`Outer$1` maps to `Outer`'s file), and `MethodImplementationLocator` recognises an anonymous class from the attributed
tree, a subtype of the target's owner, not by javac's numbering; an enum constant's body and a local class are the same
case. On a method the result is the overriding method in the body; on a type, the `new Handler() {` site, or a local
class's name.
Dropwizard's `Managed.stop` now answers 10 implementations, the three anonymous classes included (7 before).
MCP `analyze_change` builds its override family from the same search, so it now lists them too (the downward half of
MC-67); MC-67's upward half and MC-69 remain open.

### Regression targets

`MethodImplementationTest.methodImplementations_anonymousClassAndEnumConstantBody_foundUnrelatedNot`,
`LatheEngineNavigationTest.findImplementations_anonymousEnumBodyAndLambdas_foundForMethodAndType`.

---

## EG-081 — Implementations of an interface omit lambdas that implementations of its method include — done

**Status: done.**

**Value: medium — Size: S**

**Affects: the editor's go to implementation and MCP `find_implementations`.**

Found by an agent's review of the MCP tools on Helidon (2026-11-01).

### Observed behaviour

On Helidon's `Handler` interface, `find_implementations` answers 23 classes and no lambdas;
on `Handler.handle` it answers 225, lambdas and method references such as `this::handle` included.
The tool's description says it includes lambdas, but asked on the type it misses the most common kind of
implementation of a functional interface.

### Resolution

On a type, go to implementation now adds to its named subtypes the anonymous subclasses and, for a functional
interface, its lambdas and method references: those of the type itself or a sub-interface, not of its parent or a
sibling, nor converted to another functional interface. They come from the bytecode lambda index and the open documents,
not from every file naming the interface. Helidon's `Handler` now answers 226 (23 before) in about 2.7 s, the files with
lambdas attributed; a failure there still leaves the named subtypes.

### Regression targets

`MethodImplementationTest.typeImplementations_functionalInterface_anonymousClassesLambdasAndMethodReferences`,
`MethodImplementationTest.typeImplementations_subInterface_ownLambdasAndLocalClassNotParentOrSiblingLambdas`,
`LatheEngineNavigationTest.findImplementations_anonymousEnumBodyAndLambdas_foundForMethodAndType`.

---

## FR-031 — A method with a type-use-annotated parameter, searched from its declaration, misses every call compiled against its class file — done

**Status: done.**

**Value: high — Size: S**

**Affects: references, incoming calls and rename from a method's declaration, and running one test method, when a parameter carries a type-use annotation (Lombok's `@NonNull`, JSpecify's `@Nullable` and `@NonNull`, Checker Framework's) and the server runs on JDK 21 or older, or on any JDK for an annotation with `SOURCE` retention.**

Found by the jdtls comparison's benchmark (2026-11-08) on OpenMetadata at `9a2ee8b124`, Lathe `0.3.99-c15d3a55` running on JDK 21.

### Observed behaviour

References to `Entity.getEntityRepository(@NonNull String)` (`openmetadata-service/.../service/Entity.java:780`) from its declaration returned 22 locations, all in `Entity.java`, in 7.8 s; incoming calls listed only `Entity`'s own methods.
The same query from a call site (`AutoPilotApp.java`) returned 1,057.

### Cause

A method target is matched by its owner, name and a parameter descriptor, built from the text of each parameter's erased type (`types.erasure(type).toString()`).
javac on JDK 21 and older keeps type-use annotations through `erasure()`, and for a method compiled from source the parameter's type carries them: the declaration's descriptor read `(java.lang.@lombok.NonNull String)`.
A caller compiled against the class file sees no annotation there (JDK 21 does not read type annotations from class files; no JDK reads a `SOURCE`-retained one), so its descriptor read `(java.lang.String)` and never matched; only `Entity.java`'s own calls, whose method is the source-compiled one, did.
Test method ids for running one test method were built the same way, so on JDK 17 to 21 a test method with such a parameter got an id like `bar(java.lang.@NonNull String)`.
javac 22 and later drop the annotations in `erasure()`, which hid both on newer JDKs.
The review of the fix found a second, older flaw in those ids: a nested parameter type was written in source form (`java.util.Map.Entry`), which JUnit's `selectMethod` cannot load ("Failed to load parameter type"), so running one test method with such a parameter failed on every JDK.

### Resolution

A parameter's erased type is now named from its structure, not its text (`ReferenceTarget.erasedTypeName`): a class by its binary name (`java.util.Map$Entry`), an array by its component and `[]`, a primitive by its keyword, anything else (an error type, a local class) by its text as before.
The reference descriptor and the runnable ids both use it, and `TestId`, which maps JUnit's results back to those ids, keeps JUnit's binary names instead of turning them into source form.

### Regression targets

`ReferenceLocatorTest.method_typeUseAnnotatedParameter_matchesCallsCompiledAgainstTheClassFile` (a `SOURCE`-retained type-use annotation, a caller compiled against the class file, an overload left alone),
`RunnableScannerTest.runnables_typeUseAnnotatedParams_idCarriesNoAnnotation` (both fail on JDK 21 without the fix, which CI's JDK 17 and 21 rows run),
`RunnableScannerTest.runnables_nestedTypeParam_idNamesItLoadably`, `TestIdTest.positionId_jvmFormParameterTypes_matchTheRunnableIdForm`.
Related: CQ-0062, member completion's duplicate check, which keys on the same annotated text.

---

## FR-030 — A field or enum constant reached through an expression that never names its type is missed — done

**Status: done.**

**Value: high — Size: S**

**Affects: references, rename and incoming calls for fields and enum constants.**

Found while designing the static-method narrowing of FR-029 (2026-11-05); reproduced 2026-11-06.

### Observed behaviour

`ReferenceCandidatePlanner` narrowed a field's candidates to the files spelling its name and a family type (the declaring type or a subtype), and an enum constant's to the files spelling its enum.
A file reaching either through an expression whose type it never spells was not searched:
with `Holder.owner()` returning an `Owner`, references to `Owner.name` missed `h.owner().name`, and renaming `name` left that read behind, so the build broke;
with `Holder.kind()` returning a `Kind`, references to `Kind.ALPHA` missed `switch (h.kind()) { case ALPHA: ... }`, which names the constant and never the enum.

### Resolution

Fields and enum constants are now searched, as instance methods are, in every file spelling their name, in one pass.
FR-029's two phases (the files naming the family first, the rest once a declaration exposes it) were tried first and dropped in review:
some declaration hands out the type of most fields and enums (a getter returning `Owner`), so the second phase nearly always ran, and the two phases then compiled more files than one pass, serially.
Constructors keep their narrowing: `new Owner()`, `Owner::new` and a subclass's `super(...)` all name the type.
The same review restricted the static-method two phases to reactor types (a library's type can be handed out by a declaration in a jar, which no phase checks) and decided them on the module's own files.

### Regression targets

`FieldAndEnumConstantSearchTest` (references and rename for a field read and an enum `case` through an unnamed type, and a same-named member of another type left alone),
`ReferenceCandidatePlannerTest.planCandidates_fieldOrEnumConstant_searchesEveryFileSpellingItsNameInOnePhase`.

---

## FR-022 — A rename to a name a use already sees silently rebinds the use — done

**Status: done.**

**Value: high — Size: M**

**Affects: the editor's rename and MCP `rename_symbol`.**

Found by an agent's review of the MCP tools on Dropwizard (2026-11-01). Reproduced on a scratch project.

### Observed behaviour

Renaming parameter `x` to `value` in `int compute(int x) { return x + value; }`, where `value` is a field,
gives `return value + value;`: the field's use now reads the parameter.
The code still compiles, so `verify_change` reports nothing, and the rename reports success.
`RenameConflicts` says as much: it does not detect "the new name shadowing the symbol at a use".

### Resolution

Before editing, a rename now attributes the files it can change as it would leave them, in one batch per module source
config that sees the symbol, nothing written, and runs the reference locator for the renamed declaration under its new
name. A rename adds one declaration of the new name and removes the old one, so a name means something else exactly when
those uses differ from the renamed sites: an extra use is one the new name captures (a field a renamed parameter shadows,
another call picking the renamed overload, a subclass method that would override the renamed one), a missing site one
that now means something else ([FR-023](#fr-023--a-method-rename-can-capture-another-overloads-calls--done)).
Either refuses the rename, naming the file and line.
The files are the edited ones and, unless only the declaring file sees the symbol, those already using the new name
(identifier index); each module's are attributed, in batches of at most 100 untouched files, with the edited files of
the modules it depends on, so every renamed declaration it sees is the edited one.
Type renames are checked too, the moved file under its new name.
A renamed site that no longer resolves is a compile error the diagnostics report, as before.

Not checked: uses javac cannot see (reflection, strings, configuration, sources a processor generates later), and in a
JPMS reactor another module's files, whose task cannot take the declaring module's sources.

jdtls 1.51 applies all of these renames silently over LSP (spike on a scratch two-module project, 2026-11-02:
shadowing by a parameter, lambda parameter and local, a field captured by a local, overload capture, capture through an
inherited member, reverse capture in an untouched file of the same and another module, a type capturing an on-demand
import); Lathe matched it until this check.

### Regression targets

`LatheEngineRenameTest.rename_changingWhatANameMeans_refusesNamingThePlaceAndOtherwiseRenames`,
`LatheEngineRenameTest.rename_capturingACallInAnotherModule_refusesUnlessNoCallChangesMeaning`,
`LatheEngineRenameTest.rename_typeCapturingAnImportedName_refusesAndOtherwiseRenamesAndMoves`,
`ModuleSourceCompilerTest.detached_otherText_cachedTreeKeepsItsOwnText`.

---

## FR-023 — A method rename can capture another overload's calls — done

**Status: done.**

**Value: high — Size: M**

**Affects: the editor's rename and MCP `rename_symbol`.**

Found by an agent's review of the MCP tools on Dropwizard (2026-11-01). Reproduced on a scratch project.

### Observed behaviour

With `void setValue(int)` and `long twice(long)`, renaming `twice` to `setValue` turns `twice(7)` into
`setValue(7)`, which now binds to `setValue(int)`; find references afterwards no longer sees the call.
It compiles, so nothing reports it, although the tool advertises overload-correct renames.
The collision check compares only signatures, so a different overload of the new name passes.

### Resolution

Fixed by the meaning check of [FR-022](#fr-022--a-rename-to-a-name-a-use-already-sees-silently-rebinds-the-use--done):
`twice(7)` renamed to `setValue(7)` would call `setValue(int)`, a renamed site that now means something else, and an
untouched call in this or another module that the renamed method would capture is an extra use; both refuse the rename,
naming the call.

### Regression targets

`LatheEngineRenameTest.rename_changingWhatANameMeans_refusesNamingThePlaceAndOtherwiseRenames`,
`LatheEngineRenameTest.rename_capturingACallInAnotherModule_refusesUnlessNoCallChangesMeaning`.

---

## FR-024 — Renaming an interface method misses implementations inherited from a dependency — done

**Status: done.**

**Value: high — Size: M**

**Affects: the editor's rename and MCP `rename_symbol`.**

Found by an agent's review of the MCP tools on Dropwizard (2026-11-01).

### Observed behaviour

`JettyManaged extends AbstractLifeCycle implements Managed`, so Jetty's `stop()` implements
`Managed.stop()`. Renaming `Managed.stop` leaves `JettyManaged` with the interface's no-op default,
with no warning, although the tool refuses the mirror case (overriding a method declared in a dependency).

### Resolution

Before a method rename edits anything, each reactor subtype of the renamed method's type is checked in its own module
(the per-file routing go to implementation uses): when the member that implements the method there is inherited from
a class whose method is outside the override family, the rename is refused, naming both, e.g.
"`stop` cannot be renamed: JettyManaged implements it through AbstractLifeCycle.stop, which the rename would leave behind".
The check is not limited to dependencies: a project superclass with a same-signature method that overrides nothing
in the family is left behind the same way, and is refused too.
It runs against the family's contract, the method the rename starts from, and only for an interface method:
a class method is what its subtypes inherit, so it cannot be implemented from outside the family.
It costs one attribution per implementing file that is not open.

### Regression targets

`LatheEngineRenameTest.rename_methodImplementedThroughASuperclassOutsideTheFamily_refusesNamingTheSubtype`.

---

## EG-088 — Signature help is empty inside `new P(|)` and `super(|)` when no constructor takes zero arguments — done

**Status: done.**

**Value: high — Size: S**

**Affects: the editor.**

Found in a colleague's daily use (2026-11-02), confirmed with a throwaway `SignatureHelpTest`-style probe.

### Observed behaviour

With the cursor between the empty parentheses of a constructor call, signature help answers nothing
when the class has no no-arg constructor: `new P(|)` for `P(int)` / `P(String)` / `P(int, int)`,
and `super(|)` in a subclass of such a `P`.
The same call without its closing paren, `new P(|`, lists both constructors, and so does a class with a no-arg
constructor (`new StringBuilder(|)`, `new ArrayList<String>(|)`).
Method calls are not affected: `m(|)`, `new T().m(|)`, `String.valueOf(|)` list every overload.

Two smaller differences from IntelliJ's parameter info turned up on the way:

- Overloads the caller cannot access are listed: `new ArrayList<String>().add(|)` shows the private
  `void add(E, Object[], int)` beside the two public `add`s.
- The active overload of an unresolved call is the first declared, not one whose arity fits the arguments typed so far
  (also noted under [NV-10](#nv-10--signature-help-highlights-no-parameter-when-the-active-overload-is-not-the-first--non-goal)).

### Root cause

javac resolves no constructor for `()` when none takes zero arguments, so `Trees.getElement` on the
`NewClassTree` is null and `SignatureHelpResolver.resolve` returns null.
The method branch falls back to every overload of the name (`methodsNamed`, `memberSelectCandidates`);
the constructor branch has no such fallback.
`super(|)` / `this(|)` are `MethodInvocationTree`s whose select element is likewise null, and the identifier fallback
looks for methods named `super`, of which there are none.

### Resolution

Signature help now lists every accessible overload a call can name, whether or not it attributes:
the constructors of the type `new` names, of the enclosing class for `this(…)`, and of its superclass for `super(…)`.
Overloads the caller cannot access are dropped, checked as javac does through the type the call reaches them from
(the receiver, the calling class for `super.m(…)`, the innermost class declaring the name for an unqualified call);
`super(…)` and an anonymous class body apply the subclass rule to their superclass's constructors, which javac's public
access check cannot express.
For a call that does not attribute yet the active overload is the first whose arity fits the arguments typed.

### Regression targets

`SignatureHelpTest.signatureHelp_constructorCallWithoutNoArgConstructor_listsEveryConstructor`,
`SignatureHelpTest.signatureHelp_inaccessibleOverload_isNotListed`,
`SignatureHelpTest.signatureHelp_unattributedCall_activeOverloadFitsArgumentsTyped`,
`SignatureHelpTest.signatureHelp_protectedMembersFromASubclassInAnotherPackage_areListed`.

---

## EG-073 — Find references on a local in a 20,000-line file takes more than 90 seconds — done

**Status: done.**

**Value: medium — Size: S**

**Affects: the editor and MCP.**

Found by the pre-release test rounds (2026-10-29/30) on EqualsVerifier (fuzzing).

### Observed behaviour

On a synthetic 20,000-line buffer, find references did not answer within 90 s.
Reproduced on generated files of 2,000 methods, each with its own local `i`: references on one `i` took
274 ms at 2,254 lines, 884 ms at 4,504, 3,259 ms at 9,004, and 13,654 ms at 18,004, four times as long for each
doubling, while a field took under 100 ms and the compile under 0.5 s.
documentHighlight and rename run the same search, highlight on every cursor move.

### Resolution

Every same-named local in a file matches a local target's structural family, and each match was told apart by its
declaration offset, which `Trees.getPath(Element)` finds by walking the whole file: one walk per same-named local.
The target's own element is now found once, by one walk to its declaration offset, and compared by identity; the
duplicate check on matched ranges is a set instead of a scan of the results.
References on the local at 18,004 lines now take 14 ms.

### Regression targets

`ReferenceLocatorTest.localScope_lambdaCatchAndResourceInTwoMethods_eachScopedToItsDeclaration`,
`ReferenceLocatorTest.localScope_sameNameInAnotherMethod_notMerged`,
`ReferenceLocatorTest.localScope_blockScopeShadowing_distinctDeclarationsNotMerged`.

---

## EG-079 — Outgoing calls include calls from the class's field and enum initializers — done

**Status: done.**

**Value: high — Size: S**

**Affects: the editor's call hierarchy and MCP `call_hierarchy`.**

Found by an agent's review of the MCP tools on Helidon (2026-11-01).

### Observed behaviour

Outgoing calls of `HttpRoutingImpl.doRoute` (both, lines 196 and 319) included `builder()` and `build()`
from a static field initializer (line 48), `System.getLogger` from another (line 47, with `scope: all`),
and three `RoutingResult` entries on a line of `call()` (164).

### Resolution

The locator scanned only the target method's body among methods but still walked the class's field
initializers, so their calls counted as the method's; enum constants are implicit constructor calls, and their
position, found by searching forward for the type's name, landed on its next mention.
Call sites are now recorded only inside the target method's body, a lambda's included (ddd6b13a).
Not a regression: 0.2.1 only made it visible, by printing each call's line.

### Regression targets

`CallHierarchyOutgoingLocatorTest.outgoingCalls_classWithFieldInitializerAndEnum_returnsOnlyTheMethodsCalls`.

---

## EG-076 — Spotless activated per module, not in the root, leaves formatting off without a word — done

**Status: done.**

**Value: high — Size: S**

**Affects: the editor and MCP `format_file`, in a Maven reactor whose root only manages Spotless.**

Found dogfooding Lathe on its own repository (2026-10-30).

### Observed behaviour

Lathe's root pom configures `spotless-maven-plugin` (google-java-format, pinned version) in `<pluginManagement>`,
and each of its ten Java modules activates it in its own `<build><plugins>`.
`lathe:sync` writes no `.lathe/style.json`, so the server starts with `[initialize] … formatting=false`,
does not advertise formatting, and the editor never sends a request.
The sync reports the skip only at debug level (`[sync] no spotless java formatter — skipping style.json`), so a normal build says nothing.
The root pom never activated Spotless in any revision, so detection never worked for this repository.

### Root cause

`MavenStyle.spotlessPlugin` reads only the root project's effective `<build><plugins>`;
a plugin declared only in `<pluginManagement>` is not there, and the modules that do run it are not looked at.
Managing Spotless in a parent and activating it per module is a common Maven layout.

### Fix direction

Keep one workspace style.
When the root does not run Spotless, take it from the reactor modules that do, but only when every module compiling Java
runs the same Spotless Java configuration; otherwise skip `style.json` with a warning,
since a workspace-wide formatter would reformat the modules whose build does not format them.
Log the skip at warning level whenever reactor modules run Spotless but no style is written.
Per-module gating or per-module formatters are a later step, for a project that needs them.

Workaround: activate Spotless in the root's `<build><plugins>` (this repository does, as of the commit recording this gap).

### Resolution

`MavenStyle.styleSource` picks the project whose Spotless configuration becomes the workspace style:
the root when it runs Spotless, as before, else any Java module (one that is not `pom`-packaged and has a Java
source) when every Java module runs Spotless with the same version and Java configuration.
Otherwise no `style.json` is written, and the sync warns, naming how many Java modules run Spotless;
the debug line remains only for a build with no Spotless at all.
An Eclipse profile is resolved against the module the configuration came from.

The root `pom.xml` workaround (Spotless declared in the root with `<skip>`) stays until the dogfood pin moves to
a release with this fix.

### Regression targets

`MavenStyleTest.styleSource_rootOnlyManagesSpotless_takesTheJavaModulesOnlyWhenAllAgree`.

---

## CA-17 — Replace `var`, extract field, variable and constant write a type the file names differently — done

**Status: done.**

**Value: medium — Size: S**

**Affects: Neovim, VS Code, any client.**

Found by the extract method sweep of the Lathe repo (2026-11-01), confirmed with a probe test.

### Observed behaviour

These refactors write a type by its simple name and import it through `CodeActionSupport.importEditFor`,
which looks only at the type's own qualified name.
When the file already gives that simple name to another type, the result does not compile:

| Source | Action | Result |
|---|---|---|
| `import java.sql.Date;` … `var d = new java.util.Date();` | Replace `var` | `Date d = …` plus `import java.util.Date;`: `Date` is ambiguous |
| `import java.sql.Date;` … `new java.util.Date().getTime()` | Extract field | `private final Date date = …` plus the same second import |
| a type the source only names fully qualified | Extract variable, extract constant | the simple name, with no import at all |

Only the top-level type is imported, never a generic argument (`Map<String, AtomicLong>`).

### Fix direction

Use what extract method and implement-abstract-methods already share:
`CodeActionSupport.sourceTypeFormatter` writes a type whose simple name the file gives another type (by an import or
a declaration) fully qualified, and `CodeActionSupport.importEditsFor` imports every other type the text names,
generic arguments included, skipping those.
Then `importEditFor` and `typeFqn` can go.

### Resolution

Replace `var`, extract variable, extract field and declare local variable now write their type through
`CodeActionSupport.sourceType`, which pairs what extract method and implement-abstract-methods already shared:
`sourceTypeFormatter` writes a type whose simple name the file gives another type fully qualified, and
`importEditsFor` imports every other type the text names, generic arguments included.
`importEditFor` and `typeFqn` are gone.
Declare local variable also writes the full type now (`List<String> x`) rather than its raw simple name.
Extract constant takes the same path, but only ever writes a primitive or `String`, so it was never affected.

### Regression targets

`ExtractCodeActionTest.codeAction_typeWritingRefactors_typeNamedLikeAnotherInTheFile_writtenQualified`,
`ExtractCodeActionTest.codeAction_extractVariable_typeOnlyNamedQualified_importsItAndItsArguments`.

---

## CA-12 — Import candidates are not ranked: `java.awt.List` and `kotlin.Function` tie with the obvious choice — done

**Status: done.**

**Value: medium — Size: S**

**Affects: MCP `fix_imports`, and the editor's import quick fix.**

Found by the pre-release probes (2026-10-27) on EqualsVerifier.

### Observed behaviour

`fix_imports` reports `List: java.awt.List, java.util.List` and
`Function: kotlin.Function, java.util.function.Function` as ambiguous, and the editor quick fix offers
`Import 'kotlin.Function'` first.
An agent has to pick, and in a Java module the answer is almost always the `java.util` one.

### Fix direction

Rank candidates: types the module already imports elsewhere, then `java.*` packages other than `java.awt`,
then the module's own dependencies, then the rest.
`fix_imports` could add the top candidate when the ranking is decisive, and list the others.

### Resolution

Size S, not M. Both the import quick fix and MCP `fix_imports` (and `:LatheMissingImports`) take their candidates from
`ImportCandidates.resolve`, which keeps the order of `WorkspaceTypeIndex.searchExact`; that returned each simple name's group
in index build order, never ranked. Completion's `prefixMatches` already ranked by the project's usage counts, then reactor types,
then binary name, which is why completion put `java.util.List` first and the import fix did not.
Reproduced through MCP `fix_imports` on this repository before the fix:
`List: java.awt.List, java.util.List` and
`Function: com.google.common.base.Function, io.reactivex.functions.Function, java.util.function.Function`.

`searchExact` now sorts its group with the same ranking, so a type the project references leads its namesakes.
Completion had a second, richer comparator of its own (`TypeReferenceCompleter`), with origin tiers below usage:
`java.lang`, then everyday platform packages (`java.util`, `java.io`, `java.time`, …), then reactor types, then the rest.
Those tiers moved into the one public `WorkspaceTypeIndex.ranking()`, which completion now applies after its prefix-match term,
so every type list (completion, import candidates, workspace symbols) has one order,
and a cold start, before usage counts exist, also offers `java.util.List` before `java.awt.List`.
The gap's other proposed tier, the module's own dependencies, is not needed.
Not done: `fix_imports` still lists a name with several matches as ambiguous, now with the likely one first, rather than adding it.

### Regression targets

- `WorkspaceTypeIndexTest.searchAndSearchExact_usageThenOrigin_referencedTypeFirstElsePlatformType`: completion and import candidates rank alike; cold, `java.util.List` leads `java.awt.List`; usage outranks origin.
- `QuickFixCodeActionTest.missingImports_unresolvedQualifiers_listedWithTypeAndStaticCandidates`: with usage counts, the referenced `javax.xml.datatype.Duration` leads the alphabetically-earlier `java.time.Duration`.

---

## EG-066 — Started from a module subdirectory, Lathe runs on the wrong JDK or does not start — done

**Status: done.**

Decision (2026-10-27): not before the release; the fix touches the generated launchers on every OS.
Done 2026-11-01.
**Affects: the editor and MCP.**

Found by the pre-release probes (2026-10-27) on EqualsVerifier.

### Observed behaviour

- **Launchers.** `lathe-launcher.sh` and `lathe-mcp-launcher.sh` read `.lathe/java-home` and
  `.lathe/jvm.args` relative to the working directory, while the server itself walks up to the nearest
  `.lathe/`.
  The user-global `~/.cache/lathe/latest/lathe-mcp-launcher.sh`, started in `equalsverifier-core/` (an agent
  launched in a module), finds neither and runs on the `java` on `PATH`.
  With JDK 17 first on `PATH`, files of the release-17 modules work, but a file of `equalsverifier-26` takes
  30 s and answers `the request failed; see the server log`; the log has
  `IllegalArgumentException: error: release version 26 not supported`.
  The module-access flags of `jvm.args` are dropped too.
  The `[startup]` line does not name the JVM, so the mismatch is invisible.
- **The 30 s wait.** A compile that throws (here javac refusing the release) only publishes the error to the
  editor (`WorkspaceSession.submitTo` → `publishError`); the engine's `diagnosticsFuture` waiting on it is never
  completed, so the request runs into its timeout instead of failing at once with javac's reason.
  The extract method sweep (2026-11-01) hung the same way on Helidon's release-27 modules from a JDK 26 JVM.
- **VS Code.** The extension looks only for `<opened folder>/.lathe/lathe-launcher.sh`.
  Opening a module folder answers "no launcher … run a Lathe-enabled build to generate it", although the
  build already ran at the root.

### Fix direction

Launchers resolve `.lathe/` the way the server does (walk up from the working directory) and read
`java-home` and `jvm.args` from there.
The VS Code extension walks up from the opened folder.
`[startup]` logs `java.home` and the Java version.
A failed compile completes the engine's waiting future exceptionally, so the request fails at once with the
compiler's message.

### Delivered (2026-11-01)

- The server and MCP launchers (POSIX and cmd) walk up from the working directory to the nearest `.lathe/`, as
  the server does, and read `java-home` and `jvm.args` there.
- The VS Code extension starts the launcher of the nearest `.lathe/` above the opened folder, from that root.
- The `[startup]` lines (and the compiler's build-log line) end with `java=<version> java.home=<…>`.
- A compile that throws completes the engine's diagnostics future exceptionally, so MCP tools fail at once with
  the cause instead of after 30 s.
- The MCP server's walk-up is `LatheWorkspace.nearestRoot`, shared with builds' `findRoot`.

### Regression targets

- `LauncherScriptTest.render_startedInAModuleDirectory_readsTheWorkspaceRootLathe` (runs the `.cmd` on Windows).
- `lathe-vscode` `launch.test.ts`: `findWorkspaceRoot walks up from a module folder to the nearest .lathe`.
- `LatheBuildInfoTest.summary_anyClass_namesTheRunningJvm`.
- `LatheTextDocumentServiceTest.diagnosticsFuture_compileThrows_failsAtOnceWithTheCause`.
- `LatheWorkspaceTest.findRoot_disabled_returnsEmptyButNearestRootFindsIt`.

---

## EG-075 — A build on a root `pom` module looks up the newest compiler plugin online — done

**Status: done.**

**Value: medium — Size: S**

**Affects: the build.**

Found by the pre-release test rounds (2026-10-29/30) on a fresh multi-module project.

### Observed behaviour

The first build of a reactor whose root is a `pom` module resolves the newest `maven-compiler-plugin` online,
ignoring the version the project pins, and prints five "Ignoring incompatible plugin version 4.0.0-beta-*" warnings.
0.1.24 behaves the same.

### Root cause

`LatheModelInjector.injectCompilerShim` calls `findOrCreatePlugin`; a `pom` root has no compiler plugin in its build, so it creates one with no version and no `<pluginManagement>` lookup, and Maven resolves the newest.

### Fix direction

Skip the compiler shim on `pom` packaging, or take the version from `<pluginManagement>`; resolve nothing online.

### Resolution

Re-measured before the fix (Lathe 0.2.0, a `pom` root pinning `maven-compiler-plugin` 3.14.0 in `<pluginManagement>`):
the five "Ignoring incompatible plugin version 4.0.0-beta-*" warnings and two INFO lines appear on every build, not only the first, offline too.
Nothing else goes wrong: the `pom` root never runs the plugin it gets (3.16.0, the newest compatible), the modules compile with the pinned 3.14.0,
and an offline build succeeds once the plugin metadata is cached. So the cost was noise on every build of the common multi-module layout, and its value is medium, not high.

`LatheModelInjector.injectProject` now wires a module only when its build already has the compiler plugin,
which Maven puts there, at the project's version, in every module whose packaging compiles;
a `pom` module has none and gets nothing: no compiler plugin, no compiler id, no tree scopes, and no capture dependency.
Nothing creates a versionless compiler plugin any more. `LatheRequirements` reuses the same compiler-plugin lookup.

### Regression targets

- `LatheModelInjectorTest.injectProject_moduleWithoutCompiler_addsNothing`: a module without a compiler plugin gets no plugin, property or dependency.
- `LatheModelInjectorTest.injectProject_compilingModule_shimsTheCompilerAtItsVersionAndAddsCapture`: the shim attaches to the pinned plugin and keeps its version.

---

## CA-13 — Missing-import fixes skip chained qualifiers, and can land above `package` — done

**Status: done.**

**Value: high — Size: S–M**

**Affects: the editor and MCP `fix_imports`.**

Found by the pre-release test rounds (2026-10-29/30) on a fresh Maven project.

### Observed behaviour

CA-11 made a type used as a method-call qualifier fixable, but not a chained one:
`TimeUnit.SECONDS.toMillis(1)` and `Map.Entry.comparingByKey()` (javac: "package X does not exist") get no quick fix, and `fix_imports` neither adds nor lists them.
With `package` and an `import` on one line, the import is inserted above `package`, which breaks the file
(the editor and `fix_imports` alike).
An inserted import has no blank line before the class.

### Fix direction

Treat the leftmost resolvable segment of a "package does not exist" qualifier as the type to import.
Place imports from the end of the package declaration's tree, not by line.

### Resolution

Chained qualifiers: `JavacDiagnosticMapper.enrich` gives a `compiler.err.doesnt.exist` diagnostic the qualifier payload,
named after the identifier its chain starts with (`TimeUnit` in `TimeUnit.SECONDS.toMillis(1)`, `Map` in `Map.Entry.comparingByKey()`),
so the quick fix, `:LatheMissingImports` and MCP `fix_imports` offer that type's import; a diagnostic inside an `import` is left alone.

Placement: `ImportAnalyzer` records each import's exact start and end and where the package clause ends, and `ImportLayout`
inserts in front of an import at its start, or behind the last import or the package clause at its end,
so an import on the package's line, or a class on the last import's line, no longer sends a new import above `package` or into the class.
Normally formatted files get the same text as before.

Not done: a file written without a blank line between its package clause and its class keeps that style when its first import is added;
adding the blank line would need a guess about what follows, and the formatter normalizes it.

### Regression targets

- `QuickFixCodeActionTest.compile_unresolvedQualifier_setsQualifierRefPayloadOnlyForTheQualifierItself`: both chains get the payload, an import of a missing package does not.
- `QuickFixCodeActionTest.codeAction_unresolvedQualifier_offersTypeAndStaticImports` and `missingImports_unresolvedQualifiers_listedWithTypeAndStaticCandidates`: both chains are offered their import.
- `QuickFixCodeActionTest.missingImports_chosenImports_insertInPlaceAndCompile`: the two chains' imports make their file compile,
  and so does an import added before an import on the package's line, behind an import on the class's line, and behind a package on the class's line.
- `ImportAnalyzerTest`: the exact anchors and text for each group and for a file without imports.

---

## FR-021 — Rename accepts a contextual keyword or a dotted name as the new name — done

**Status: done.**

**Value: high — Size: S**

**Affects: MCP and the editor.**

Found by the pre-release test rounds (2026-10-29/30) on EqualsVerifier (fuzzing).

### Observed behaviour

`rename_symbol` accepts `var`, `record` and `yield` as a new name, which break the code where they are not identifiers; `a.b` renames nothing and says nothing.
`RenameProvider.isValidName` rejects `a.b`, but the rename then answers a null edit instead of a refusal with a reason; the three words pass because `SourceVersion.isKeyword` does not count them.
0.1.24 behaves the same.

### Fix direction

Refuse restricted identifiers where the symbol kind cannot take them, and any name that is not one identifier.

### Resolution

A name that is not one Java identifier (`a.b`, a keyword) is refused with that reason, where rename answered a null edit
that did nothing and said nothing; the MCP tool's own pre-check, which accepted a dotted name, is gone, so both paths share the server's.
`RenameConflicts` refuses a restricted identifier the symbol's kind cannot take (JLS 3.9):
a type or type parameter cannot be named `permits`, `record`, `sealed`, `var` or `yield`,
and a method cannot be named `yield`, since an unqualified call to it does not compile.
A variable or field can take any of them, so renaming one to `record` is still allowed.

### Regression targets

- `RenameProviderTest.renameConflict_eachKind_refusesAClashButNotANearMiss`: `var` and `record` refused for a type and a type parameter,
  `yield` for a method; `record` allowed for a method, a field, and `var` for a local.
- `LatheEngineRenameTest.rename_toATakenOrInvalidName_refusesWithTheReasonAndEditsNothing`: `a.b` refused with the reason, nothing edited.
- `LatheMcpToolsTest.renameSymbol_invalidIdentifier_returnsTheReasonAsAnError`: the agent gets the server's reason.

---

## EG-058 — Module analysis sessions are never released: a reactor-wide search pins every touched module's jars for the session — done

**Status: done — Target: next.**
Fixed by 48f701b8 (2026-10-30): an idle module session is released after 5 idle minutes,
and under memory pressure, when the JVM reports the old generation still above 70% after a collection.
Design: [Releasing Module Sessions Under Memory Pressure](../done/lathe-memory-pressure.md).

### Observed behaviour

Memory probe against Dropwizard and Helidon at `-Xmx` 1, 2 and 4 GB (read-only requests: cross-module
references, hover, definition, implementation, call hierarchy, then 100 files opened across modules).
Live heap after a full GC:

| Measurement | Dropwizard | Helidon |
|---|---|---|
| After reference searches | ~550–650 MB | ~560–720 MB |
| Per additional open file | ~6–7 MB | ~6–7 MB |
| 100 files open | ~1.16 GB | ~1.0–1.1 GB |
| Non-heap on top of `-Xmx` (NMT) | ~350 MB | ~900 MB |

Closing every file does not return the heap to its pre-open level:
Dropwizard drops only to ~920 MB, against ~465 MB before the 100 opens.
Both projects work fully at 2 GB and 4 GB; at 1 GB both run reference search fine but die once ~80–100
files are open on top of it.

### Root cause

`CompilationWorker` creates its `SourceAnalysisSession` lazily and keeps it until the workspace closes.
Each session owns a `ModuleSourceCompiler` with its own `StandardJavaFileManager`, which opens a zip
filesystem per classpath jar.
A reference search touches every candidate module, so after one reactor-wide search every touched
module's file manager, with all its jar indexes, stays for the rest of the session.
Dropwizard has 233 unique jars but ~4,300 live `ZipFileSystem` instances (each jar opened ~18×),
almost all still alive after every file is closed; Helidon has 209 unique jars and ~1,700 instances.

### Fix direction

Release a module's session after it has been idle for a while (for example 5 minutes), on the worker's
own executor; the next request rebuilds it through the existing `contextFactory`.
This is safe because a cache miss already recompiles (the `AnalysisLru` eviction path), and the
single-threaded executor orders the release after any in-flight search.
Cost: the first request into a module idle past the threshold pays a cold start (~0.2–1.6 s), and a
warm reactor-wide search after a long pause runs at cold speed.
A count-capped LRU of module sessions would bound memory during a burst as well, but would make every
large reactor-wide search cold.

### Regression targets

None yet.

---

## EG-065 — Lombok projects: the server exits on the first compile, and generated members are invisible — done

**Status: done — Target: next.**
Decision (2026-10-28): Lathe runs Lombok on every analysis compile, keystrokes included, when a module has
it on its processor path; the measured cost below is accepted.
**Affects: the editor and MCP.**
Found by the pre-release probes (2026-10-27) on a custom Maven project (JDK 21, Lombok 1.18.38 in the parent's
`annotationProcessorPaths`, `@Data`/`@Builder`/`@Value`/`@Log`), and reported by a user in
[issue #38](https://github.com/ag-libs/lathe/issues/38) (0.1.22).

### Observed behaviour

**1. The server exits on any compile in a Lombok module — a regression in 0.1.23, fixed in 1bc16c11.**
Any write-pass compile of a module that has Lombok on its processor path makes the server exit with status 1:
an editor save, an agent's edit on disk, or a `git pull` or branch switch (any file, not only an annotated one).
The MCP server exits the same way on its next tool call.

```
SEVERE  [compiler] process fatal-error fatal 373ms
java.lang.AbstractMethodError: Receiver class lombok.javac.apt.Javac9BaseFileObjectWrapper does not define
or inherit an implementation of the resolved method 'abstract java.lang.String inferBinaryName(java.lang.Iterable)'
  at jdk.compiler/com.sun.tools.javac.file.JavacFileManager.inferBinaryName
  at ...server.module.JavacRunner.writtenBinaryNames(JavacRunner.java:155)
```

- The defect is Lombok's: `Javac9BaseFileObjectWrapper` extends javac's internal `PathFileObject` without
  implementing its abstract `inferBinaryName(Iterable)`.
  javac's own compile never asks an output file for its binary name, so Lombok never hits it.
  Lathe does: `JavacRunner.writtenBinaryNames` has called the public
  `JavaFileManager.inferBinaryName(CLASS_OUTPUT, jfo)` since the symlinked-workspace fix (5d034fe9, first
  shipped in 0.1.23), and javac's implementation reaches the missing method.
  Before that it derived the name from `jfo.toUri()`, which the wrapper implements; the 0.1.22 report in
  issue #38 sees saves and diagnostics work.
  So it is a Lathe regression for Lombok users, even though the call is correct use of public API.
- The crash repeats on every start: a restarted server compiles the files changed on disk while it was
  down, and exits within a second.
  Only a full Maven build, which brings the compile stamps up to date, lets it start.
- `CompilationWorker` treats every `Error` in a failed compile as fatal to the process, so a `LinkageError`
  thrown by any annotation processor or javac plugin takes the whole server down, not just this one.
- A debug session's test JVM, launched suspended, is left running when the server exits.

**2. Annotated files show false errors until saved.**
The live pass runs no annotation processors ([how it works](../guide/how-it-works.md#diagnostics-live-vs-on-save)),
so every use of a generated member inside the annotated class is an error on open and while editing:
`cannot find symbol: variable log` (`@Log`), `method getName()` (`@Data`), `method getCents()` (`@Value`).
MCP `get_diagnostics` and `verify_change` report the same errors, and an agent will try to fix them.
Files that only use the class from another module are clean, since they compile against its built classes.
With part 1 fixed, the save's full compile runs Lombok (the mirror class gains the generated members),
yet the diagnostics published after the save still show these errors, contrary to
[how it works](../guide/how-it-works.md#diagnostics-live-vs-on-save).

**3. Generated members have no source link** (issue #38).
Go to definition on a call to a generated accessor finds nothing.
Find references on a field returns only direct field accesses, so `analyze_change` reports one reference
and a field rename leaves `getX()`/`setX()` callers broken without a warning.
References from a usage of the accessor itself work.

The guides do not mention Lombok.

### Fix direction

1. Done in 1bc16c11: written classes are named from `jfo.toUri()` and the class-output root, both
   resolved with `toRealPath()` (`FileUtil.classBinaryName`), which gives the same names as
   `inferBinaryName`, symlinked workspaces and `module-info` included. The wrapper could still be
   reported to Lombok.
   Narrowing the process-fatal set to `VirtualMachineError` is deferred until GA (decision
   2026-10-27): until then a processor's `Error` should end the server visibly, and its cause be fixed.
2. Parts 2 and 3: run Lombok on analysis compiles (decided 2026-10-28), then build definition and
   references for generated members on it; see the investigation below.
3. Document Lombok support and its limits in the guides.

### Investigation (2026-10-28)

**Cause of part 2.** Every analysis compile adds `-proc:none` (`ModuleSourceCompiler.buildOptions`); only a
save's write (`FULL`) runs processors. Lombok changes the class it compiles in place, so the analysed tree has
no generated members, and part 3 follows from that: no tree declares `getName`.

**How other tools do it.**
- jdtls starts with `-javaagent:lombok.jar` (`java.jdt.ls.lombokSupport.enabled`): Lombok's agent patches
  the Eclipse compiler in jdtls's JVM. It works because jdtls runs ECJ; it does not carry to javac.
- IntelliJ's Lombok plugin synthesizes the members in its own model, without running Lombok: a
  reimplementation of Lombok's rules, which lags new features and `lombok.config`.
- NetBeans, javac-based like Lathe, runs Lombok as an annotation processor.

**Running Lombok on analysis compiles, measured** (`JavacTask.analyze`, Lombok 1.18.38, JDK 21 / 26):

| Compile | `-proc:none` today | Lombok, one instance reused | Extra |
|---|---|---|---|
| one `@Data @Builder` file, warm | 11 / 14 ms, 3 false errors | 23 / 20 ms, none | about 10 ms |
| 50 files, 25 with Lombok, warm | 13 / 12 ms, 100 false errors | 91–130 / 84–133 ms, none | about 2 ms a file |
| the first compile, as Lombok loads | 22 / 23 ms | 908 / 985 ms | about 0.9 s, once a module |

Lombok given through `-processorpath` instead costs about 110 ms a compile (javac loads it each time);
the measured path keeps one Lombok processor per module and hands it to each task with the public
`JavacTask.setProcessors`. The "today" column compiles with false errors, which may save javac some work.
Not measured: Lathe's own pipeline, code mid-edit, Lombok-heavy hierarchies.

**What Lombok leaves in the tree.** Each generated member carries a real source span: `getName()` the
field's name, `setName()` the `@Data` annotation, `builder()` the `@Builder` annotation; an accessor's
body is `return this.name;` or `this.name = name;`. So, with Lombok on analysis compiles:
- 3a, definition on a generated getter: land on that span. The declaration-name search must stay inside
  the declaration's span (today it would run on to a later `getName()` call); hours of work.
- 3b, field references with the accessors' calls: find the class's accessors by their bodies, with no
  naming rules (`lombok.config` prefixes and fluent accessors come for free), and merge their
  references; about a day.
- Every feature reading the tree (semantic tokens, document symbols, unused-code hints, references, the
  MCP position check) needs checking against generated members; part of the 1–2 days part 2 takes.

**Options** (the first is chosen).
- Part 2 as above, then 3a and 3b on top: about a week together. Cost: about 10 ms a keystroke, about
  2 ms a file in batch work, about 1 s per module once. Risk: a JDK Lombok does not support (27 for
  1.18.38) stops the server on open, not only on save; the project's own build fails there too.
- Defer part 2. Then 3a and 3b could only guess accessors by name (`getName` → `name`), which breaks with
  `lombok.config`; defer them with it.
- 3c, rename of a field with generated accessors: refuse or warn, from the built classes (the mirror's
  class has `getName()`); hours, independent of part 2. Renaming the accessors' calls too would need
  Lombok re-run on the renamed field to learn the new names: days.

### Resolution

- Part 1: 1bc16c11, as above.
- Part 2: an analysis compile hands Lombok to its javac task when the module's processor path has it and
  `<proc>` is not `none` (`LombokProcessors`: one class loader a module, fresh processors a task,
  `JavacTask.setProcessors`); every other processor stays off.
  The false errors are gone on open, while editing and after a save, in the editor and through MCP.
- Lombok marks what it generates with `@lombok.Generated`. What maps source positions to trees skips
  those members (`WrittenSourceScanner`, `SourceLocator.pathAt`), so semantic tokens, the outline,
  folding, hover, references and unused hints answer for the code as written.
  Unused hints still count a field read only by its generated getter as used.
- 3a: definition on a generated member lands on the field it comes from (by Lombok's default accessor
  names), else on its class.
- 3b: references on a field also search its generated accessors and merge their calls; rename does not.
- 3c: renaming a field Lombok generates accessors from, or a generated member, is refused with the
  reason, in the editor and through `rename_symbol`.
  Renaming the accessors' calls along with the field is left for later.
- Fix direction 3: the guides describe Lombok support and its limits
  ([how it works](../guide/how-it-works.md#lombok), [troubleshooting](../guide/troubleshooting.md)).

Known limits:
- `lombok.addLombokGeneratedAnnotation = false` in `lombok.config`: nothing is recognized as generated,
  so a getter's token covers its field's name, definition on it finds nothing, the field's references
  miss its accessors' calls, and its rename is not refused.
- Accessor names from `lombok.config` (`lombok.accessors.prefix`): definition lands on the class, and
  references miss those calls.
- Statements Lombok adds to written methods (`@NonNull` checks, `@SneakyThrows`, `@Cleanup`) carry no
  mark; not checked.
- Not exercised: `@SuperBuilder`, `@Delegate`, `@ExtensionMethod`, `val`.
- javac runs no processor over a file that does not parse, so a file opened mid-edit sees no generated
  members until it parses; completion while typing uses the last clean analysis.
- A JDK the project's Lombok does not support (27 for 1.18.38) now fails on opening a Lombok file, not
  only on a save, and ends the server; the project's own build fails there too.

### Regression targets

- Part 1: `ServerFilesTest.classBinaryName_classFilesBelowRoot_dottedWithNestedAndDefaultPackage` and
  `ModuleSourceCompilerTest.compileBatch_workspaceBehindSymlink_reportsBinaryNames`.
- Part 2: `ModuleSourceCompilerTest.compile_lombokOnTheProcessorPath_analysisSeesGeneratedMembersUnlessProcNone`
  and `LombokAnalysisTest.compile_lombokClass_noFalseErrorsAndNoFalseUnusedHints`.
- Part 3: the rest of `LombokAnalysisTest` (tokens, hover and references, definition, accessor targets,
  rename refusal, completion), and the `lombok` invoker fixture's `LombokTest`: the same through a real
  server, over LSP and MCP, with Lombok on `annotationProcessorPaths` (JDK 17 to 26, as Lombok 1.18.38
  runs).

---

## CA-16 — Extractions are missing in common places, wrong in others, and there is no extract method — done

**Status: done.**

**Value: high — Size: L**

**Affects: Neovim, VS Code, any client.**

Found in daily use (2026-10-30/31): extraction "does not work in many places", on multi-line statements above all.
Investigated with a probe matrix of 59 Java contexts in `CodeActionTest` (each compiled clean),
each asked four ways: a caret at the target's start, the exact expression, the expression through its `;`,
and the whole lines (`V`: column 0 to the last line's end); then the offered edits were applied and recompiled.
A read-only probe of a 387-line service class in the running editor (a caret code action at each of 800 identifiers)
matched: extract variable at 201 of them, and every miss on a code line falls under the cases below.

### Delivered (2026-10-31)

Sections 1–3 are fixed; the matrix's misses on code lines and its broken or unsafe offers are gone.
Design notes in [Extract Variable](../done/lathe-extract-variable.md#selections-and-placement-ca-16).

- 5818ef69, 3ad4ed54 — selection (section 1): `ExtractionSupport.selectedExpression` takes the one outermost
  tree inside a selection, a statement meaning its value; shared by extract variable, field and constant.
- 19962f60 — safety (section 3): `ExtractionSupport.pinnedInPlace` and a deep `isDenotable`.
- 195c9dc8 — placement without restructuring (section 2): `case:` lists, resources, `for` initializers,
  whole expression statements.
- fa655223 — placement with a new block (section 2): expression lambdas, switch rules, brace-less bodies.

Section 4 is fixed too: extract method, designed and built as in [Extract Method](../done/lathe-extract-method.md).
An expression, or a run of whole statements, becomes a private method; the statements may leave one value behind
(`T x = extracted(…);`, `x = extracted(…);`) or return on every path (`return extracted(…);`); a generic method's
type parameters and the uncaught checked exceptions carry over; replace-all also covers copies of the statements
that use other locals. The edit is compile-checked when the action is picked (`codeAction/resolve`), so an approximate
flow analysis never leaves broken code.

Not changed: a lambda or anonymous class as the extracted value (needs a target type or `var`), and extract
field's own placement rules.

### 1. A selection must be character-exact

| Selection | Offered |
|---|---|
| caret at the expression, or exactly the expression (multi-line too) | yes |
| through the `;` | no, in every context |
| line-wise (`V`), or starting in the indentation | no, in every context |

Selecting several lines is usually line-wise, so on multi-line code the extractions look absent;
`V` on a single line fails the same way.
`ExtractionSupport.coveringExpression` starts at the tree under the selection's start and walks up to an expression
covering the whole selection: a start in the indentation is above every expression, and an end past the expression
is covered by none.

### 2. Extract variable is refused where the expression's statement is not directly in a block

| Context | Example | Offered |
|---|---|---|
| expression lambda | `l.forEach(x -> use(x.trim()))`, `orElseThrow(() -> new X(a.trim()))`, `.map(x -> x.length() * 2)` | nothing |
| switch, colon case | `case "x": use(a.trim()); break;` | nothing |
| switch, arrow case / expression arm | `case "x" -> use(a.trim());`, `case "x" -> a.length() + 1;` | nothing |
| brace-less body | `if (ok) use(a.trim());`, `else use(…)`, `for (…) use(…)` | nothing |
| try-with-resources | `try (var in = open(f.getPath()))` | nothing |
| `for` initializer | `for (int i = seed(); …)` | field only |
| a whole expression statement | `a.trim();` (non-void) | nothing |

`ExtractVariableProvider` declares before the expression's statement and requires that statement's parent to be a block;
`CodeActionSupport.nearestEnclosingStatement` stops at a lambda, and these statements' parents are a `case`, an `if`, a `for` or a `try`.
Extract field and constant are still offered in some of these places, which makes the gap look arbitrary.
Block lambdas, `yield`, `try`/`catch`/`synchronized` bodies, initializer blocks, constructors, anonymous and local classes,
and record compact constructors all work.

Also not offered: a lambda or anonymous class as a value (`l.forEach(x -> use(x))`, `use(new Object() { })`):
the first needs its target type, the second a `var`.

### 3. Extract variable is offered where it breaks the code or changes what it does

It always declares before the statement, so an expression that is evaluated conditionally, repeatedly, or in a
narrower scope is hoisted:

| Context | Result |
|---|---|
| `if (o instanceof String s && s.isEmpty())` | `s.isEmpty()` hoisted above the pattern that declares `s`: does not compile |
| `use(List.of(1, "a"))` (intersection element type) | `List<> of = List.of(1, "a");`: does not compile |
| `if (a != null && a.isEmpty())` | `a.isEmpty()` evaluated before the null check: throws |
| `use(ok(a) ? a.trim() : "")` | the branch evaluated unconditionally |
| `while (l.isEmpty())`, `do … while (!l.isEmpty())`, `i < l.size()` | the condition evaluated once, before the loop |
| `super(a.length())` | a statement before `super(…)`, legal only from Java 25, offered whatever the project's release |

### 4. No extract method

The only extractions are variable, field and constant (`ExtractionSupport.KINDS`); selecting statements offers nothing.
It was proposed in [Basic Refactorings](../potential/lathe-basic-refactorings.md) but never planned.

### Fix direction

1. Match the selection by the tree: ignore whitespace around it, and when it covers a whole statement take that statement's
   expression (an expression statement's expression, a declaration's initializer, a `return`'s value).
2. Make room where there is no block, as IntelliJ does: an expression lambda's body becomes a block
   (`x -> { var v = …; return …; }`), a case arm or brace-less body is wrapped in braces, a resource or `for` initializer
   declares before the `try`/`for`; a non-void expression statement becomes a declaration.
   Never hoist out of a lambda.
3. Refuse (or declare in place) what cannot move: the right side of `&&`/`||`, a `?:` branch, a loop condition or update,
   and anything reading a pattern binding; insert before `super(…)` only from Java 25.
   Use `var` for a non-denotable type (intersection, anonymous class) instead of writing a broken one.
4. Extract method: selected statements become a method, captured locals its parameters, a value used after the selection
   its return value (refused when more than one); needs javac flow analysis, and rule 1 for its selections.

The test helper `CodeActionTestBase.applyEdits` garbled two edits that start at the same offset; fixed in 06bcde58.

### Regression targets

For sections 1–3, in `ExtractCodeActionTest`, each offered edit applied and compiled:
`codeAction_extraction_lineWiseSelection_findsTheExpression`,
`codeAction_extractVariable_evaluatedOnlyInPlace_offersNothing`,
`codeAction_extractVariable_evaluatedOnceBeforeStatement_rewritesToCompilingCode`,
`codeAction_extractVariable_outsideABlock_declaresWhereItFits`,
`codeAction_extractVariable_bodyWithoutRoom_becomesABlock`,
`codeAction_extractVariable_atStatementStart_rewritesToCompilingCode`.
Section 4, in `ExtractMethodCodeActionTest`:
`codeAction_extractMethod_expression_becomesPrivateMethodAfterItsMember`,
`codeAction_extractMethod_contexts_rewriteToCompilingCode`,
`codeAction_extractMethod_notMovableOrPointless_offersNothing`,
`codeAction_extractMethod_statements_becomeVoidMethod`,
`codeAction_extractMethod_declarationUsedLater_movesToTheCall`,
`codeAction_extractMethod_statementsReturningOnEveryPath_returnTheCall`,
`codeAction_extractMethod_statementContexts_rewriteToCompilingCode`,
`codeAction_extractMethod_statementsChangingControlOrState_offerNothing`,
`codeAction_extractMethod_copies_replacedInTheClassOwnMembers`,
`codeAction_extractMethod_statementCopies_calledWithTheirOwnLocals`,
`codeAction_extractMethod_statementLookalikes_notReplaced`;
the compile check, in `CompileCheckedActionsTest`; the check's compile leaving the document's cached tree alone,
`ModuleSourceCompilerTest.detached_otherText_cachedTreeKeepsItsOwnText`.

---

## CA-15 — Extract constant on an annotated class inserts the constant above the class — done

**Status: done.**

**Value: high — Size: S**

**Affects: Neovim, VS Code.**

Found by a refactoring review of Lathe itself (2026-10-30), confirmed with a probe test.

### Observed behaviour

```java
@SuppressWarnings({"unused"})
class Test {
  void m() {
    use("hello world");
  }
  void use(String s) {}
}
```

Extract constant on `"hello world"` inserts `private static final String HELLO_WORLD = "hello world";`
at line 2, column 0: between the annotation and `class Test {`, outside the class, so the file no longer compiles.
Expected: before `void m()`.

### Root cause

The providers find the class body with `source.indexOf('{', classStart)`, but a `ClassTree`'s start position
includes its modifiers, so a `{` in an annotation argument is taken for the body's.
With the brace that early, the synthesized default constructor (positioned at the class header) passes the
"member after the brace" filter and becomes the anchor.
The same text scan is in `ExtractFieldProvider.insertFieldEdit` and in
`AddConstructorParameterProvider` (class body, and a constructor body behind an annotated constructor);
those are likely to fail the same way but were not probed.
It is also the ad hoc Java parsing CLAUDE.md rules out.

### Fix direction

One helper for "the class body's opening brace" that starts after the modifiers
(`getEndPosition` of `cls.getModifiers()`, or after the name and type parameters), used by all four sites.
`insertFieldEdit` and `insertConstantEdit` share about 25 lines (brace, first-member anchor, empty-body fallback);
the shared helper can own the anchor logic too, leaving each provider its own placement rule
(after the last field, or before the first member).

### Resolution

Fixed in 9dabdfd0. `CodeActionSupport.indexAfter` finds punctuation no tree carries past the owner's header
trees: `classBodyBrace` searches for the body's `{` after the modifiers, type parameters, `extends`, `implements`,
`permits` and a record's components, and an empty constructor parameter list's `(` is searched for after the
constructor's modifiers and type parameters.
An empty constructor body's `{` comes from its `BlockTree`.
Fixing it also found the same bug in add-constructor-parameter, which put the new parameter inside a
constructor annotation's parentheses.
`CodeActionSupport.insertBeforeFirstMember` replaced the placement code extract field and extract constant
each had a copy of.

### Regression targets

- `ExtractCodeActionTest.codeAction_annotatedDeclarations_insertInsideTheBody`: an annotated class and a record with
  an annotated component (extract constant), and an annotated class with an annotated empty constructor
  (add-constructor-parameter), each rewritten and compiled.


---

## CA-11 — Missing-import fixes skip a type used as a method-call qualifier — done

**Status: done — Target: next.**
**Affects: MCP `fix_imports`; the editor's import quick fix and "Add missing imports" read the same
diagnostic classification but were not probed.**
Found by the pre-release probes (2026-10-27) on Dropwizard; reproduced on EqualsVerifier.

### Observed behaviour

A file with no imports that uses `Objects.requireNonNull(o)`, `Collections.emptyList()` and
`ConcurrentHashMap<String, String> map`:
`fix_imports` adds only `java.util.concurrent.ConcurrentHashMap`.
`Objects` and `Collections` each have one JDK match, yet they are neither added nor listed as unmatched.
For `Duration.seconds(1)` with two candidates it answers "No import added" with no ambiguous list,
although the agent guide promises both lists.
The added import also lands on the line right under `package`, with no blank line between them.

`JavacDiagnosticMapper.resolveKind` sends a name whose parent is a member select to the
`default -> VARIABLE_REF` branch, and `SourceAnalysisSession` offers type imports only for `TYPE_REF`.

### Fix direction

Classify an unresolved simple name that qualifies a member select (`Name.member(...)`, `Name.FIELD`) as a
possible type, and offer the type imports for it as well.
Keep one blank line between `package` and the first import.

### Resolution

Fixed in a1eadaab. A member-select qualifier gets its own kind, `QUALIFIER_REF`, since it may be a type
to import or a statically imported field (Helidon uses statically imported constants as qualifiers in
about 75 files). `resolveKind` returns it only when the unresolved name is the qualifier identifier itself
(`java.util.Lisst` starts at a qualifier but names a member of it). `DiagnosticPayload.Kind` gained
`offersTypeImport()` and `offersStaticImport()`, so the code actions, the "Add missing imports" trigger,
missing imports and the type-index lookup ask what a kind offers. Missing imports lists each name once
with candidates of both kinds; a name with none is still listed only as a plain type.
`ImportLayout` puts a blank line between the package clause and a file's first import.

### Regression targets

- `QuickFixCodeActionTest.compile_unresolvedQualifier_setsQualifierRefPayloadOnlyForTheQualifierItself`
- `QuickFixCodeActionTest.codeAction_unresolvedQualifier_offersTypeAndStaticImports`
- `QuickFixCodeActionTest.missingImports_unresolvedQualifiers_listedWithTypeAndStaticCandidates`
- `ImportAnalyzerTest.layout_noImports_placesBelowThePackageAfterABlankLineOrAtTheStart`

---

## EG-061 — Renaming a method also renames an override that implements a second, unrelated method — done

**Status: done — Target: next.**

Found while fixing EG-059 and reproduced on `main` (2026-10-25): with `interface Task { void run(); }`
and `class Worker extends Thread implements Task { public void run() {} }`, renaming `Task.run` edited
`Task.java` and `Worker.java`, so `Worker.execute` silently stopped overriding `Thread.run`.
The same happened with the second supertype in the reactor (`class Job extends Base implements Step`).
Both still compiled, because `@Override` was satisfied by the renamed interface method.

**Cause.** The EG-059 check ran on the method under the cursor only; the overrides the reference
search adds to the rename were never checked for a second, unrelated parent.

**Resolution.** Where the reference search matches an override's declaration, it records what that
override also overrides outside the searched family (`ReferenceTarget.overriddenOutsideFamily`, on the
same supertype walk as `overriddenDeclarers`), on the match (`ReferenceMatch.alsoOverrides`).
Rename refuses when any match carries one, naming the file and the method it would leave behind; the
check runs in each searched module, so an override in a dependent module is covered.
Starting from the override itself is refused too, since the search runs from the top-most contract.
A diamond whose every parent is in the family (`Impl implements Plan, Phase`, `Phase extends Plan`)
still renames.

Regression:
`LatheEngineRenameTest.rename_overrideAlsoOverridingAnUnrelatedSupertype_refusesButAFamilyDiamondRenames`.

## EG-062 — Find-references and rename find nothing for a non-private nested type — done

**Status: done — Target: next.**

Found while fixing EG-059 and confirmed on `main` (2026-10-24): with
`package com.example; class Outer { static class Inner {} }` and a user `class User { Outer.Inner a; }`,
find-references on `Inner` returned 0 and renaming it, from the declaration or from the use, edited
nothing. A `private` nested type worked, because its search stays in its own file.

**Cause.** A type's reference target named it canonically (`com.example.Outer.Inner`), a member's owner
by its binary name (`com.example.Outer$Inner`), and the reference planner split the type's name at its
last `.` into a package: `com.example.Outer`. Its same-package filter then looked in a directory that
does not exist, dropping the declaring file and every user, and its import tokens missed
`import com.example.Outer;` followed by `Outer.Inner`. Other consumers already treated the name as
binary (the subtype lookup for implementations, the functional-interface family, module scoping), so
nested types were mis-handled there too.

**Resolution.** Type targets carry their binary name like member targets, and match on it; a top-level
type's binary name is its canonical name, so only nested types change. The planner spells import tokens
as source does (`$` → `.`) and adds each enclosing type, so `import com.example.Outer;` finds the users
of `Outer.Inner`. The canonical-name lookup EG-059 had added for nested types is gone; every target
resolves to its file the same way.

Regression: `LatheEngineNavigationTest.references_nestedType_findsUsesInItsPackageAndInAPackageImportingTheOuterType`,
`LatheEngineRenameTest.rename_nestedType_renamesTheDeclarationAndEveryUse`,
`LatheEngineNavigationTest.findImplementations_nestedInterface_findsTheImplementingClass`.

## EG-060 — Javadoc rendering joins the first sentence to the body and splits inline code across lines — done

**Status: done — Target: next** (commit 9afdca4d).

Found through MCP `describe_symbol` on EqualsVerifier (JDK 17); hover and signature help share the
renderer:

| Javadoc source | Rendered before the fix |
|---|---|
| `Factory method. For general use.` (`EqualsVerifier.forClass`) | `Factory method.For general use.` |
| `… running Java application. An enum class …` (`java.lang.Class`) | `… running Java application.An enum class …` |
| `{@code` / `int}` split across two source lines | a code span holding the newline and indent: `` `\n int` `` |

### Resolution

`JavadocMarkdownPrinter` printed the first sentence and then the body, but javac splits the two at
the sentence boundary and drops the whitespace between them; it now prints
`DocCommentTree.getFullBody()`, the unsplit original, in `format()` and `mainDescription()`.
Inline `{@code}`/`{@literal}` text outside `<pre>` has its whitespace runs collapsed; inside `<pre>`
it keeps its layout.
Verified end to end: `describe_symbol` on `forClass` and `java.lang.Class` renders both sentences
intact.

### Regression targets

- `JavadocMarkdownPrinterTest.format_multiSentenceDescription_keepsSpaceBetweenSentences`
  (failed before the fix).
- `JavadocMarkdownPrinterTest.format_inlineCodeWrappedAcrossLines_collapsesToOneSpan` (failed before
  the fix).
- `JavadocMarkdownPrinterTest.format_codeBlockInPre_keepsLineBreaks`.

---

## EG-059 — Rename accepts a JDK or dependency symbol and rewrites every use in the reactor — done

**Status: done — Target: next** (commit c6d84e28).

Probed on EqualsVerifier (JDK 17), on `Class` in `EqualsVerifier.forClass(Class<T> type)`: MCP
`rename_symbol` → `Klass` applied "448 edit(s) across 107 file(s)" to disk, and LSP `prepareRename`
and `rename` accepted it the same way.
Every use of `java.lang.Class` was rewritten to a type that does not exist.
The MCP guard against editing files outside the reactor did not fire, because only the declaration
lives outside it.
Renaming a project method that overrides a JDK one (`toString`) followed `Object.toString` and renamed
every `toString` in the reactor.

**Resolution.** `WorkspaceSession` refuses the rename, in `prepareRename` and in `rename` (which the
MCP tool goes through), when the target's declaration, or a method it overrides, has no source in a
reactor source root, or has one only in generated source (whose processor would regenerate it under
the old name).
The refusal is a `RequestFailed` response error that names the symbol and why; `LatheEngine` passes
its reason through to the MCP tool result.
A nested reactor type stays renameable: its file is found from its binary name (`Outer$Inner`), which
since EG-062 every reference target carries (that change replaced a canonical-name lookup added here).
A secondary top-level type (`interface Task` in `Sample.java`) has no file of its own name, so the
workspace type index vouches for it instead; before that, its members were refused as outside the
project.
An override in the renamed family that also overrides an unrelated method is refused since
[EG-061](#eg-061--renaming-a-method-also-renames-an-override-that-implements-a-second-unrelated-method--done).

Regression: `LatheEngineRenameTest.rename_symbolDeclaredOutsideTheProject_refusesWithTheReasonAndEditsNothing`
(JDK type, JDK method, `toString` override),
`LatheEngineRenameTest.rename_symbolDeclaredInGeneratedSource_refusesWithTheReasonAndEditsNothing`,
`LatheEngineRenameTest.rename_memberOfASecondaryTopLevelType_renamesItAndStillRefusesAJdkOverride`,
`LatheTextDocumentServiceTest.prepareRename_projectOrJdkSymbol_acceptsTheProjectOneAndRefusesTheJdkOneWithTheReason`.

## EG-051 — Hover signature rendering: varargs as array, constructor `<init>`, method type params dropped — done

**Status: done — Target: next.**

Hover rendered method/constructor signatures unlike the source and jdtls: a varargs parameter showed
as an array (`Option[] options`), a constructor as `void <init>()`, and a generic method dropped its
own type-parameter declaration (`Builder<T> of(…)` instead of `<T> Builder<T> of(…)`).

**Resolution.** `HoverFormatter` now: renders a varargs parameter with `...` (the shared
`formatParam` gained a `vararg` flag that swaps a trailing `[]` for `...`); renders a constructor as
the enclosing type's name with no return type (was the element's `<init>` name and `void` return);
and prepends the method's own type parameters via `formatTypeParameters` (names only, e.g. `<T> `).
The `formatParam` change flows through `SignatureHelpResolver` and the hover-on-parameter path in
`SourceAnalysisSession`, so varargs render as `...` there too. Found by jdtls differential testing.

Regression: `HoverTest` (varargs renders `...`, constructor renders `Type(...)` not `void <init>`,
generic method shows leading `<T>`).

## EG-055 — `workspace/symbol` misses nested / inner type declarations — done

**Status: done — Target: next.**

Workspace symbol search found only top-level types: nested / inner type declarations (classes,
records, enums, interfaces) were absent from `workspace/symbol` across the reactor, dependencies, and
the JDK (e.g. `Map.Entry`) — even though they were present in the index data and already offered by
completion.

**Resolution.** `WorkspaceTypeIndex` built its symbol map with a top-level-only filter
(`isTopLevel()`); replaced with `isNamedSymbol()`, which admits any named type — top-level and nested,
any visibility — while excluding synthetic anonymous/local classes (digit-started simple names) and
blank obfuscator names. `WorkspaceSymbolResolver` now sets `containerName` to the enclosing type for
nested entries (was always the package), so a result shows `Entry — java.util.Map`. Discovered by
jdtls differential testing (`dev/jdtls_diff.py`).

Regression: `WorkspaceTypeIndexTest` (named-nested included, public JDK nested found, synthetic
excluded), `WorkspaceSymbolTest`.

## EG-052 — Go-to-implementation misses lambda / method-reference implementations of functional interfaces — done

**Status: done — Target: next.**

`textDocument/implementation` on a functional interface's single abstract method now returns, besides
the named overriding classes, every reactor lambda and method reference that implements it — whether
the enclosing source names the interface (a typed variable, field, parameter, return, or cast) or
passes the lambda straight as an argument (`register(x -> …)`) where the interface is never named,
and whether the target is the interface itself or a functional sub-interface that inherits/overrides
the SAM.

How it works:

- `MethodImplementationLocator` matches a lambda / method reference when the converted type's single
  abstract method is (or overrides) the target method (`FunctionalInterfaces.singleAbstractMethod`), so
  sub-interface conversions resolve while a sub-interface whose SAM comes from a different parent is
  rejected.
- Argument-position lambdas (invisible to the identifier index, since the source never names the
  interface) are discovered from a compiled-bytecode index: `ClassMetadataReader` records each class's
  `invokedynamic` target interfaces (the descriptor return type) during the reactor `.class` scan Lathe
  already runs; `WorkspaceSession` aggregates a `functional interface → implementing classes` map,
  refreshed with the type index, and `implementationsForMethod` queries it over the SAM interface plus
  its functional sub-interfaces. The AST locator then pins precise positions.

Verified on a real project (EqualsVerifier): go-to-implementation on `Rethrow.ThrowingSupplier.get()`
returns 19 argument-position `rethrow(() -> …)` lambdas across files that never name the interface.

Residual (universal to type navigation, not specific to this feature): discovery reflects the last
compile, so an argument-position lambda added to an unopened, not-yet-recompiled file appears only
after the next sync.

### Regression targets

- `ClassMetadataReaderTest.read_argumentPositionLambdaAndMethodRef_capturesFunctionalInterfaceTargets`,
  `ClassMetadataReaderTest.read_signatureUseWithoutLambda_hasNoLambdaTargets` (bytecode extraction).
- `MethodImplementationTest.methodImplementations_functionalInterface_returnsSamImplsAndIgnoresOtherInterface`,
  `MethodImplementationTest.methodImplementations_subInterfaces_matchInheritedSamButNotUnrelatedSam`,
  `MethodImplementationTest.functionalInterfaceName_samVsNonSam_returnsNameOnlyForSam`.
- Multi-module invoker `LspSmokeTest.implementation_samMethodCursor_findsLambdaAndMethodReference`.

---

## EG-054 — Completion on a method call that returns an array resolves to the wrong receiver type — done

**Status: done — Target: next.**

Member-access completion on a method-chain receiver whose return type is an array (`"x".toCharArray().`,
`type.getEnumConstants().`) now offers the array's members (the `length` field, `clone()`, and the
inherited Object methods) instead of the chain's base-type members or nothing. A simple array variable
already worked.

Root cause: `Trees.getTypeMirror` on a method invocation yields its result type directly, so an
array-returning chain arrives as an `ArrayType`; the receiver resolver (`TypeResolver.check`) ran it
through `effectiveDeclaredType`, which only returns `DECLARED` types and dropped the array to null,
whereupon the text fallback resolved the chain's base expression instead. The fix keeps an `ArrayType`
receiver as-is — `CandidateGenerator` already synthesises the array members.

### Regression targets

- `CompletionMemberAccessTest.memberAccess_methodCallReturningArray_offersArrayMembers` (parameterized
  over a concrete `char[]` return and a generic `T[]` return).

---

## EG-049 — Rename unsupported for types, enum constants, and constructors — done

**Status: done — Target: next.**

`textDocument/rename` / `prepareRename` now support enum constants, type declarations (class /
interface / enum / `@interface` / record), and constructors, in addition to the methods, fields,
locals, and parameters that already worked.

- Enum constants were simply missing from the rename allow-list; the find-references + edit pipeline
  already handled them (declaration, uses, and `case` labels).
- Public top-level types rename across the reactor and move the declaring file (`Foo.java` ->
  `Bar.java`) via a `documentChanges` `RenameFile` resource operation; nested and secondary top-level
  types rename in place. The move is gated on the client advertising
  `workspace.workspaceEdit.resourceOperations = rename` — otherwise the rename is refused rather than
  leaving a public type in a mismatched file.
- Constructors redirect to their enclosing type, and a type rename now rewrites the constructor
  declaration names too (`Foo() {}` -> `Bar()`), which the reference search previously missed.

Verified by probe on the multi-module workspace: renaming the public interface `Greeter` produces
cross-module edits plus `RenameFile Greeter.java -> Salutation.java`.

### Regression targets

- `RenameProviderTest` — enum constant renameable + edits (declaration, use, `case` label); top-level
  and nested types renameable; a type rename edits the class declaration, both constructor
  declarations, and `new X(...)` sites; `toWorkspaceEditWithFileRename` emits text edits then a
  `RenameFile`.
- Multi-module invoker `LspSmokeTest.rename_publicType_editsAcrossModulesAndRenamesDeclaringFile`.

---

## EG-003 — Hover returns null on positions inside Javadoc type-reference tags — done

**Status: done — Target: next.**

Hover on a type or member inside a Javadoc reference tag (`{@link Foo}`, `{@link #bar()}`, `@see`,
`@throws`) now resolves the referenced element and renders its signature + doc, instead of returning
nothing. Implemented via javac's structured `DocTrees`, not by parsing the comment text: a new
`JavadocReferenceResolver` maps the cursor offset to the `ReferenceTree` and resolves it with
`DocTrees.getElement`; a shared `elementOrJavadocReference` in `SourceAnalysisSession` feeds hover (and,
through the same resolver, definition and references).

The sibling features (references / highlight / rename over Javadoc mentions) shipped with EG-050;
completion inside a tag stays deferred there.

Follow-up fix: links in a member's or nested type's Javadoc first resolved to the enclosing class,
because that Javadoc lies outside the member's tree and the cursor path ends at the class. The
Javadoc resolver is now consulted first whenever the path ends at a class or the compilation unit.

### Regression targets

- `HoverTest.hover_javadocLinkTags_resolveReferencedTypeAndMember` (incl. member and nested-type links).

---

## CA-10 — No code action to add a `final` field as a constructor parameter — done

**Status: done — Target: next.**

After adding a blank `final` field, the developer had to hand-edit every constructor to accept and store
the value; Lathe offered nothing on the field declaration.

**Resolution.** `AddConstructorParameterProvider` (`CodeActionKind.RefactorRewrite`, title
`Add constructor parameter '<field>'`), offered on a `final`, non-static, initializer-less instance
field of a class/enum: it appends a `final` parameter of the field's type to each constructor and binds
it (`this.<field> = <field>;`), forwards through `this(...)`-delegating constructors, and generates a
constructor when none exists — one atomic `WorkspaceEdit`, no import edit. Durable design:
[lathe-add-constructor-parameter.md](../done/lathe-add-constructor-parameter.md).

Regression: `CodeActionTest`.

## EG-017 — `textDocument/documentHighlight` not implemented

**Status: done — Target: M2.**

Resolution: implemented as a file-scoped specialisation of the same-file reference search
(Read/Write/Text kinds); in Neovim the standard `vim.lsp.buf.document_highlight()` endpoint.
Regression: `ReferenceMatchTest.toHighlight_everyRole_mapsReadWriteDistinctlyAndRestToText`.

### Observed behaviour

The server does not advertise `documentHighlightProvider`, and no handler exists.
Cursor-occurrence highlighting — the read/write highlight an editor draws for every occurrence of
the symbol under the cursor as the cursor rests — is therefore unavailable.

### Root cause

`LatheLanguageServer.initialize` registers no `documentHighlightProvider`, and there is no
`documentHighlight` request handler in the server.

Spec: [LSP 3.17 — Document Highlights Request](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#textDocument_documentHighlight).
Params are `TextDocumentPositionParams` (same cursor target as references); the response is
`DocumentHighlight[]`, each a `range` plus optional `DocumentHighlightKind` (`Text=1`, `Read=2`,
`Write=3`, default `Text`). Eclipse JDT LS implements this via `DocumentHighlightHandler`/
`computeOccurrences` and returns the same LSP4J types Lathe uses, so this is parity work.

### Proposed fix

Server side: implement `textDocument/documentHighlight` as a file-scoped specialisation of the
existing exact same-file reference matching.
Reuse the `ReferenceTarget` identity already used by Find References, restrict the scan to the
current document (run against the already-attributed open-file analysis — never recompile — and make
it cancellable, since it fires per cursor-rest), and map each occurrence to a `DocumentHighlight`
with `Read`/`Write` kind (`ReferenceRole.READ/WRITE → Read/Write`, otherwise `Text`) based on whether
the occurrence is an assignment target. The range-dedup added for FR-008 already prevents a record
component's header from being highlighted once per synthetic member.

The same-file matching machinery already exists, so the server work is small and the feature is
exercised continuously during normal editing.

### Client integration (Neovim) — not server-only

Advertising the capability is not sufficient for a visible effect in the shipped plugin:

- `lua/lathe.lua` sends full `make_client_capabilities()` (so the capability negotiates), but wires
  only a `format_on_save` `LspAttach` autocmd — there is **no** `document_highlight` autocmd, so
  nothing highlights automatically.
- Stock Neovim users need a buffer-local `LspAttach` block (mirroring the `format_on_save` one,
  capability-guarded on `client:supports_method('textDocument/documentHighlight')`) that calls
  `vim.lsp.buf.document_highlight()` on `CursorHold`/`CursorHoldI` and `vim.lsp.buf.clear_references()`
  on `CursorMoved`/`CursorMovedI`, plus guidance to lower `updatetime` (~250ms; default 4000ms is too
  slow) and ensure `LspReferenceText/Read/Write` highlight groups are visible.
- `vim-illuminate` users get it for free: its default provider order is `{'lsp','treesitter','regex'}`,
  so advertising the capability silently upgrades them to semantic LSP highlighting.

This gap therefore ships as **server handler + a small `lathe.lua` autocmd** (behind an opt-out flag
like `format_on_save`); a server-only change would leave the feature half-wired for stock users.

### Probe commands

Not probeable through `explore.py` (no `documentHighlight` command); confirmed by the absent
capability and the absent handler in `LatheLanguageServer`.

### Regression targets

- `ReferenceMatchTest.toHighlight_everyRole_mapsReadWriteDistinctlyAndRestToText`
- `ReferenceLocatorTest.role_fieldRead_isRead`
- `ReferenceLocatorTest.role_fieldWrite_isWrite`
- `ReferenceLocatorTest.role_methodInvocation_isInvocation`

The formerly proposed `DocumentHighlightTest` methods were not added under those names; the current tests cover the shared reference roles and highlight conversion.

---

## CA-6 — Extract variable: `var` variant follow-up

**Status: non-goal (superseded) — Target: n/a.**

The `var` variant is retired rather than built: Extract Variable emits the explicit type, and a
standalone `type → var` conversion (`ConvertToVarProvider`) now flips a local declaration to `var` in
one action — the inverse of `ReplaceVarProvider`, sharing the `refactor.rewrite` kind so a single
editor shortcut toggles either direction. Extract-then-convert is strictly better than a second
extract action, since the conversion also handles declarations the user wrote by hand.

Residual enhancement, deferred (not part of this resolution):

- **Lambda extraction** — standalone lambdas are refused (no denotable independent type); extracting
  one would require inferring the target functional-interface type from context. Method references
  that javac has target-typed already extract.

See [extract-variable](../done/lathe-extract-variable.md).

---

## EG-099 — Go-to-definition on a generated type landed on the stale Maven `target/` copy after an in-editor edit — done

Registry correction (2026-11-08): formerly `EG-052`, also assigned to a different gap; `EG-099` uniquely identifies this historical entry.

**Status: done — Target: next.**

After editing an annotation-processed source (e.g. adding a component to a `@Builder` record) and
saving, completion reflected the regenerated API immediately, but `textDocument/definition` on the
generated type still resolved to Maven's `target/generated-sources/annotations/…Builder.java` — the
stale copy only `mvn` refreshes — so `gd` showed pre-edit code (and a spurious `…Builder is not
abstract` diagnostic) until a Maven run. The server regenerates into `.lathe/<module>/generated-sources`
on save (so the fresh copy exists), but definition never searched there: the source roots carried each
module's `originalGenSourcesDir` (Maven's `target/`), not `generatedSourcesDir()` (the `.lathe` mirror).

Fixed by making the server resolve generated types against the `.lathe` mirror it owns and keeps fresh,
never Maven's `target/`. New `ModuleSourceConfig.searchRoots()` (regular roots + the `.lathe`
generated-sources mirror when the module has generated sources) replaced three duplicated
"roots + generated root" copies — `WorkspaceModuleRegistry.allSourceRoots`,
`ReferenceCandidatePlanner.packageSearchRoots` (deleted), and `ReferenceCandidateIndex.allSourceRoots`
(deleted). The two containment checks — `WorkspaceModuleRegistry.moduleSourceFor` and
`ModuleSourceCompiler.generatedSourceRoot` — switched to `generatedSourcesDir()` so an opened generated
file is attributed and analyzed. No fallback to `target/`: an empty `.lathe` mirror is a re-sync
condition (consistent with WS-1/WS-5), not something to mask with a stale copy; non-annotation codegen
was never on these roots, so nothing there regresses. It also survives `mvn clean` (which wipes
`target/`). No `lathe-compiler` change was needed.

Verified live on a large real workspace: `def`/`hover` on a `@Builder`-generated type resolve to
`…/.lathe/<module>/generated-sources/…Builder.java`, not `target/`.

Regression targets:
- `ModuleSourceConfigTest.searchRoots_withGeneratedSources_usesLatheMirrorNotMavenTarget` /
  `searchRoots_noGeneratedSources_returnsSourceRootsOnly`.
- `WorkspaceModuleRegistryTest.allSourceRoots_withGeneratedSources_includesLatheMirrorNotMavenTarget` /
  `moduleSourceFor_fileInLatheGeneratedSources_returnsModule`.
- `ReferenceSearchTest.isInPackageScope_generatedSourcesCandidate_reactorScope_inScope` (the `.lathe`
  candidate is in scope, the `target/` candidate is not).
- `ReferenceCandidatePlannerTest.planCandidates_typeUsedInSamePackageGeneratedSource_includesGeneratedFile`,
  `ReferenceCandidateIndexTest.build_includesGeneratedSourcesDir_whenPresent`,
  `ModuleSourceCompilerTest.compile_openMode_fileUnderGeneratedSourcesRoot_compilesInsteadOfThrowing`
  (all now exercise the `.lathe` mirror).

## EG-098 — Unnamed variable `_` (JEP 456) was reported as an unused declaration with an empty name

Registry correction (2026-11-08): formerly `EG-051`, also assigned to a different gap; `EG-098` uniquely identifies this historical entry.

**Status: done — Target: next.**

An unnamed variable `_` (the JLS unnamed-variable marker, JEP 456, standard since Java 22) drew a Lathe
"unused" hint whose declaration name rendered as an empty string (`Unused parameter ''`) — a
false-positive on an intentionally-unnamed declaration plus an empty-name presentation. `_` cannot be
referenced, so `UnusedDeclarationScanner` always kept it as an unused candidate, and its empty javac
tree name produced the `''`.

Fixed by excluding unnamed variables in `UnusedDeclarationScanner.visitVariable`: an
`isUnnamedVariable(node)` guard (empty simple name, or `_` defensively) skips candidate registration, so
no hint is emitted for `_` in any position (catch clause, lambda parameter, enhanced-for variable, or a
`var _` binding). The exclusion is scoped to `_` — a genuinely unused named local declared alongside is
still reported. Mirrors the existing `EXCLUDED_FIELD_NAMES` name-based skip.

Regression: `UnusedDeclarationScannerTest.compile_unnamedVariableUnderscore_notReportedButNamedStillIs`
(`_` in a catch clause / lambda / enhanced-for / `var _` yields no hint, while a sibling unused named
local is still reported). The test compiler passes no `--release`, so it defaults to the build JVM
(≥ 22) where `_` is a standard feature.

---

## EG-097 — Requests on a non-`file` document URI crash the request with an `InternalError`

Registry correction (2026-11-08): formerly `EG-050`, also assigned to a different gap; `EG-097` uniquely identifies this historical entry.

**Status: done — Target: M2.**

Opening a Java file through a virtual buffer whose URI scheme is not `file` — reproduced with the
Neovim **diffview** git-diff view (`diffview:///…/SpendLimitChecker.java`) — made the server throw
on both the initial `didOpen` and on follow-up requests. `LatheUri.toPath` is `Path.of(URI.create(…))`,
which only resolves for the `file` scheme; any other scheme has no NIO `FileSystemProvider`, so
`Path.of` threw `FileSystemNotFoundException`, surfacing as an `InternalError` RPC (and an
`UnhandledPromiseRejection` in Neovim).

Fixed at the LSP boundary in two stages:

- **Notification path** (earlier commit): `LatheUri.isFileUri(String)` returns true only for a `file:`
  URI with a non-empty path (false, never throwing, for `file://`, `diffview://`, `untitled:`, …), and
  `LatheTextDocumentService` early-returns from `didOpen`/`didChange`/`didClose`/`didSave` for a
  non-file URI (one `FINE` `[<op>] ignored non-file uri` line) so nothing reaches `LatheUri.toPath`.
- **Request path** (this change): every document-URI request handler (`completion`, `codeAction`,
  `semanticTokens`, `documentSymbol`, `foldingRange`, `references`, `signatureHelp`, `hover`,
  `definition`, `declaration`, `implementation`, `prepareCallHierarchy`, `prepareTypeHierarchy`,
  `formatting`) short-circuits to a completed future of its own empty type (empty list / empty
  `SemanticTokens` / `Either.forLeft(List.of())`, or `null` for `Hover`/`SignatureHelp`) via the same
  `ignoreNonFile` guard before routing into `WorkspaceSession.routeCompiler → LatheUri.toPath`. A small
  shared `emptyLocationResult()` helper backs the three identical location-returning handlers. Because
  `didOpen` is dropped, the request guards are defense-in-depth; a per-method one-line guard was chosen
  over a generic higher-order wrapper.

Regression coverage:

- `LatheUriTest.isFileUri_*` (true/false/`null`)
- `LatheTextDocumentServiceTest.didOpen_nonFileUri_ignoredWithoutCrashOrPublish`
- `LatheTextDocumentServiceTest.foldingRange_nonFileUri_returnsEmptyWithoutThrowing`
- `LatheTextDocumentServiceTest.hover_nonFileUri_returnsNullWithoutThrowing`

Client-side hardening (avoid attaching the Lathe client to non-`file` buffers, sparing the wasted
round-trip) remains an optional NV follow-up; the server fix stands on its own.

---

## EG-041 — JDK/library source files get live diagnostics and code actions with no "read-only source" affordance

**Status: done — Target: M2.**

Opening a JDK or dependency source (typically reached via go-to-definition into the cached sources
under `~/.cache/lathe/`) was treated like a workspace file: the `CompilerRoute.External` route
compiled it with real javac and the server published real diagnostics and offered code actions on a
file the user cannot edit. Live repro: opening the workspace JDK's `String.java` published 9
unused-private-method hints.

Fixed per the triaged option 1 (suppress diagnostics and code actions for `External`), server-only:
- `DiagnosticPublisher` gains `publishEmptyIfCurrent`, which publishes an empty diagnostics list with
  the same staleness gate and semantic-token refresh as `publishIfCurrent` (a shared private
  `publish(...)` backs both).
- `WorkspaceSession.publishDiagnostics(route, ...)` routes `External` to `publishEmptyIfCurrent` and
  everything else to `publishIfCurrent`; `compileAndPublish` and the save-path `publishThen` helpers
  use it, so open/change/save all suppress External diagnostics.
- `codeActionFuture` early-returns no actions for an `External` route.

External files are still compiled, so hover, definition, folding, and semantic tokens on library
sources are unchanged; only diagnostics and code actions are suppressed. Verified end-to-end: the JDK
`String.java` went from 9 published hints to none, with hover still resolving.

Regression targets:
`DiagnosticPublisherTest.publishEmptyIfCurrent_currentSnapshotWithDiagnostics_publishesEmptyAndRefreshesTokens`
and `publishEmptyIfCurrent_staleSnapshot_doesNotPublish`. The route-level wiring is verified by the
live probe (a full External-route session harness would be disproportionate at the unit level).

---

## EG-039 — Unused-hint mislabels exception and lambda parameters as "local variable"

**Status: done — Target: M2.**

The unused-declaration hint labelled every non-field, non-method variable it flagged as a *local
variable*, including kinds that are not — an unused catch parameter and an unused lambda parameter.
Detection was correct (both are genuinely unused); the defect was the JLS §4.12.3 kind label. Root
cause: `UnusedDeclarationScanner.visitVariable` assigned `LOCAL_VARIABLE` to any `VariableTree` whose
parent was neither a `ClassTree` nor a `MethodTree`, so a catch parameter (parent `CatchTree`) and a
lambda parameter (parent `LambdaExpressionTree`) fell into that catch-all.

Fixed by adding two `Kind` labels (`exception parameter`, `parameter`) and a `localVariableKind(Tree
parent)` classifier that maps a `CatchTree` parent to the exception-parameter kind, a
`LambdaExpressionTree` parent to the parameter kind, and everything else (block local, enhanced-for
variable, pattern binding) to the local-variable kind. Message wording and which declarations are
flagged are otherwise unchanged; method/constructor parameters remain excluded.

Regression targets:
`UnusedDeclarationScannerTest.unused_exceptionParameter_messageUsesExceptionParameterKind`,
`unused_lambdaParameter_messageUsesParameterKind`, and the boundary
`unused_enhancedForVariable_remainsLocalVariable` (an enhanced-for variable stays "local variable").

---

## EG-047 — Go-to-definition on a record accessor lands on the file, not the component

**Status: done — Target: M2.**

`textDocument/definition` on a record-accessor use (e.g. `config.bucket()`) or on the synthetic
accessor itself resolved to nothing (originally the file top `0:0`; after EG-100, no result) instead
of the component in the record header. A record accessor is synthetic — `Trees.getPath` is null and
no declaration site is found — so the normal resolution is empty.

Fixed in `DefinitionLocator`: when the normal resolution of an element is empty **and** the element
is a record's canonical accessor (a no-arg `METHOD` on a `RECORD` whose `RecordComponentElement`
reports it as its accessor), retry resolution against the component's backing field. The backing
field is kind `FIELD`, so the existing `SourceLocator.declarationPath` field-in-`ClassTree` matcher
lands on the component name in the record header with no new tree-scanning code. The retry only fires
on empty, so an explicitly-declared accessor (which has its own declaration site) resolves directly
and is never redirected.

Regression targets: `DefinitionLocatorTest.locate_recordAccessor_externalFile_redirectsToComponent`
(synthetic accessor → header component position) and
`locate_explicitRecordAccessor_externalFile_returnsAccessorNotComponent` (user-written accessor body
resolves to its own declaration, not the header).

---

## EG-100 — Go-to-definition falls back to the file top on unresolved targets

Registry correction (2026-11-08): formerly `EG-049`, also assigned to a different gap; `EG-100` uniquely identifies this historical entry.

**Status: done — Target: M2.**

`textDocument/definition` returned a location at the top of the file (`0:0`) whenever it could not
locate a declaration position for the resolved target — a synthetic member, or any element whose
declaration site was not found — instead of returning no result. Jumping to the top of a file reads
as a bug and is worse than returning nothing.

Root cause: `DefinitionLocator.parsePosition` returned `new Position(0, 0)` on a missing declaration
name position, and the caller wrapped it in a `Location`. Fixed by changing `parsePosition` to return
`Optional<Position>` (empty when no position is found) and having the two definition callers —
`DefinitionLocator.locate` (reactor path) and `SourceAnalysisSession.findDefinitionLocation`
(external-source fallback) — propagate the empty as "no result". The call-hierarchy callers
(`CallHierarchyOutgoingLocator`, `SourceAnalysisSession` outgoing item) keep their prior behaviour by
defaulting to `0:0`. This is the general safety net behind the record-accessor redirect (EG-047).

Regression targets: `DefinitionLocatorTest.locate_syntheticMember_sourceFileButNoDeclaration_returnsEmpty`
(a record's synthetic `toString` resolves to a source file but has no declaration site → empty result);
`locate_method_externalFile_returnsLocation` and `locate_field_externalFile_returnsLocation` still
resolve normal external symbols to their declaration.

---

## EG-042 — Call hierarchy "does not resolve from a call site" — invalid (probe-positioning artifact)

**Status: non-goal.**
Reclassified after live re-verification: `prepareCallHierarchy` already resolves from a call site
when the cursor is on the **method name**. Confirmed on a live workspace across unqualified (`foo()`),
qualified (`x.foo()`), chained-receiver (`a.b().c()`), and JDK-target call sites — each prepares an
item and finds callers. Same-CU unit coverage in `CallHierarchyPrepareTest` and
`CallHierarchyIncomingLocatorTest` corroborates it.

The original report was a **cursor-positioning artifact of the probe**, not a server defect: the
probe command `callers "next.handler().handle"` places the cursor at the *start* of the match — on
the receiver `next` (a variable), whose element is not a method, so `prepareCallHierarchy` correctly
returns "(no call hierarchy item at this position)". The gap's own "works" probe
(`callers "handle(ServerRequest"`) started on the method name, which is why declaration vs. call-site
looked asymmetric; on a genuine method-name cursor there is no asymmetry.

No code change; nothing to implement. (Helidon, used in the original probe, was not checked out at
re-verification time; the artifact/contrast was reproduced on an equivalent live workspace instead.)

---

## EG-044 — Call hierarchy now aggregates polymorphic calls made through a supertype/interface reference when queried from a concrete override

**Status: done — Target: M3.**

Incoming call hierarchy queried from a concrete `@Override` previously returned zero callers: real
call sites bind statically to the interface/supertype method (`feature.setup(...)` where `feature`
is interface-typed), and the incoming search matched call sites by *exact* resolved method, so the
override's own binary-name + erased descriptor never matched them.

Root cause: `CallHierarchyIncomingLocator` gated every candidate call site with
`ReferenceTarget.matches(...)` (exact owner + descriptor). Candidate-*file* discovery was already
override-aware (the report noted "61 candidates scanned"), so only the per-site matcher was too
strict.

Fix (one class, `CallHierarchyIncomingLocator`): resolve the target's `ExecutableElement` once via
`ReferenceTarget.resolveMethodElement`, then match call sites with
`ReferenceTarget.matchesWithOverrides(...)` — the same override-aware matcher already shipped for
Find References (EG-014) and `textDocument/implementation`. It accepts a call site whose resolved
method overrides, or is overridden by, the queried method (both directions), so querying from either
the interface method or a concrete override now yields the polymorphic call sites. Constructors are
unaffected (the override branch applies only to `METHOD`; exact calls still hit the `matches`
fast-path). No `ReferenceTarget` API change.

### Regression targets

- `CallHierarchyIncomingLocatorTest.searchIncomingCalls_polymorphicCallThroughInterface_findsCallerFromOverride`
- `CallHierarchyIncomingLocatorTest.searchIncomingCalls_unrelatedSameNamedMethod_notReportedFromOverride`
  (boundary: an unrelated same-named method on an unrelated type must not be over-matched)

---

## EG-043 — Type hierarchy relations required the type's declaration file to be open

**Status: done — Target: M3.**

Type-hierarchy subtypes/supertypes came back empty whenever the type's **declaration** source file
was not currently open — most visibly when the feature was invoked from a *usage* site (a field /
parameter / local declared type), where the declaration is typically closed.

Root cause: `WorkspaceSession.typeHierarchySubtypesFuture` / `typeHierarchySupertypesFuture` routed
the computation through `openDocFeature(data.routingUri(), …)`, which returns the empty fallback when
`docs.get(routingUri) == null`. `routingUri` is the type's **declaration** source (set by
`TypeHierarchyResolver` to the located declaration file). The lambda ignored the `doc` argument
entirely — the computation needs only the workspace type index + global source dirs + the parser,
which reads declaration sources from disk (`TypeSourceLocator.locate`), so the open-document gate was
spurious.

Fix: both futures now use `routeFeature(data.routingUri(), …)` instead of `openDocFeature`, routing
by URI to a worker without requiring the document to be open (mirrors `codeActionFuture`). A reactor
type's `routingUri` routes to its `Module` worker and computes regardless of which file is open.

Corrections to the earlier (re-scoped) triage: the defect affected **both** supertypes and subtypes
(prior probing only saw subtypes differ because the probed interface had no supertypes); the trigger
is really "declaration file not open", of which usage-site invocation is the common case, not a
distinct "usage-site resolution" defect; and it **is** unit-reproducible — the earlier test exercised
`SourceAnalysisSession` directly, one layer below the `WorkspaceSession` gate where the bug lived.

### Regression targets

- `LatheTextDocumentServiceTest.typeHierarchySubtypes_declarationFileNotOpen_stillResolvesSubtypes`

---

## EG-012 — `textDocument/declaration`: navigate an override to its contract method

**Status: done — Target: M1.**
The design of record is [lathe-declaration.md](../done/lathe-declaration.md).
The implementation resolves to the **root contract** (walking the full supertype hierarchy, not just
direct supertypes) and handles **both** the declaration site and the call site, with a fallback to
`definition` for non-overriding symbols.
The "Proposed fix" and "Implementation notes" below are retained as the historical gap record; where
they pose open questions, those were resolved in favour of root-contract resolution and call-site
support per the design doc.

### Observed behaviour (historical)

Java IDEs commonly provide a quick way to navigate from an overriding method declaration to the
method it overrides in a superclass or interface.
Before EG-012 was implemented, Lathe did not expose that navigation.

Probing `MongoDbClient` in Helidon confirms:

```java
public class MongoDbClient extends DbClientBase implements DbClient {
  @Override
  public DbExecute execute() { ... }

  @Override
  public DbTransaction transaction() { ... }

  @Override
  public String dbType() { ... }

  @Override
  public <C> C unwrap(Class<C> cls) { ... }
}
```

Running `definition` on the overriding method declarations returns the declaration itself:

| Cursor target | Actual definition result |
|---|---|
| `MongoDbClient.execute()` | `MongoDbClient.execute()` |
| `MongoDbClient.transaction()` | `MongoDbClient.transaction()` |
| `MongoDbClient.dbType()` | `MongoDbClient.dbType()` |
| `MongoDbClient.unwrap(...)` | `MongoDbClient.unwrap(...)` |

The reverse direction works through `textDocument/implementation`:
running `impl` on the corresponding `DbClient` interface declarations returns both
`JdbcClient` and `MongoDbClient` implementations.

Normal call-site definition also behaves correctly:

- `MongoDbClient dbClient; dbClient.execute()` jumps to `MongoDbClient.execute()`;
- `DbClient dbClient; dbClient.execute()` jumps to `DbClient.execute()`;
- inherited superclass calls such as `context()` inside `MongoDbClient.execute()` jump to
  `DbClientBase.context()`.

The missing behavior is specifically declaration-site navigation from an override to the
overridden method.

### Definition vs. declaration in LSP

In LSP the intended split is language-dependent, but the general contract is:

- **Definition** — where the symbol is actually defined/implemented.
- **Declaration** — where the symbol is declared as an API/contract, which may not be the implementation.

In Java, most symbols do not have a separate declaration and definition.
A class, field, local variable, or normal method has one source location, so both can
reasonably return the same place.

The distinction becomes useful for overrides:

```java
interface DbClient {
    DbExecute execute();       // declaration / contract
}

class MongoDbClient implements DbClient {
    @Override
    public DbExecute execute() {   // definition / implementation
        ...
    }
}
```

Expected navigation model:

| Cursor location | Go to Definition | Go to Declaration |
|---|---|---|
| `dbClient.execute()` where `dbClient` is `MongoDbClient` | `MongoDbClient.execute()` | `DbClient.execute()` |
| `dbClient.execute()` where `dbClient` is `DbClient` | `DbClient.execute()` | `DbClient.execute()` |
| `MongoDbClient.execute()` declaration itself | `MongoDbClient.execute()` | `DbClient.execute()` |
| `DbClient.execute()` declaration | `DbClient.execute()` | `DbClient.execute()` |

### Proposed fix

Implement `textDocument/declaration`.
Do not change `textDocument/definition`.

- For an overriding method declaration, `declaration` resolves the overridden superclass or
  interface method and returns its source location.
- For everything else (non-override methods, fields, types, locals), `declaration` falls back
  to `definition` for usability.

Implementation uses javac override checks:

1. Resolve the `ExecutableElement` at the cursor and the enclosing `TypeElement`.
2. Walk direct supertypes using `Types.directSupertypes(...)`.
3. For each candidate method in each supertype, call `Elements.overrides(current, candidate, enclosingType)`.
4. Return source locations for matching candidates using the existing definition/source-location machinery.

Multiple inherited interface declarations may return multiple locations.
The declaration result for a non-overriding method falls back to the definition location.

### Implementation notes

**Call-site rows (rows 1–2) are more ambitious than declaration-site (row 3).**
The proposed fix focuses on overriding method *declarations*.
Rows 1 and 2 are *call sites*: getting declaration to navigate from `dbClient.execute()` (where
`dbClient` is `MongoDbClient`) to `DbClient.execute()` requires resolving the concrete callee at
the call site and then walking up to the interface — a separate code path.
V1 should cover declaration-site overrides (row 3) only; call sites can fall back to definition
until a follow-up extends the logic there.

**Decide between immediate override and root contract.**
Walking only *direct* supertypes may not reach the root interface.
For `MongoDbClient extends DbClientBase implements DbClient`, if `DbClientBase` also declares
`execute()`, a single-level walk lands on `DbClientBase.execute()`, not `DbClient.execute()`.
Whether to return the *nearest* overridden declaration or the *most abstract* one is a product
decision; the implementation must choose one consistently.
Returning the nearest overridden declaration is simpler; returning the root interface is arguably
more aligned with "jump to contract".

**Row 4 — interface method is its own declaration.**
`DbClient.execute()` IS the declaration, so `declaration` should return itself, not empty.
"Or empty" was removed from the table above.

### Probe commands

```bash
printf 'definition 96:19\ndefinition 101:23\ndefinition 106:16\ndefinition 111:15\nquit\n' \
  | env LATHE_TIMEOUT=90 python3 dev/explore.py \
      /home/ag-libs/git/helidon/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClient.java

printf 'impl 52:14\nimpl 64:18\nimpl 45:11\nimpl 77:10\nquit\n' \
  | env LATHE_TIMEOUT=90 python3 dev/explore.py \
      /home/ag-libs/git/helidon/dbclient/dbclient/src/main/java/io/helidon/dbclient/DbClient.java
```

### Regression targets

- `DeclarationLocatorTest.findContract_implementsInterface_returnsInterfaceMethod`
- `DeclarationLocatorTest.findContract_extendsAbstractClass_returnsAbstractMethod`
- `DeclarationLocatorTest.findContract_nonOverriddenMethod_returnsEmpty`

The former `DeclarationTest` names were proposals, not current tests.
Call-site fallback is implemented by `NavigationFeatures.declaration`; the locator tests above cover the contract lookup, not a separate LSP call-site regression.

---

## EG-014 — Find References on an overriding method returns only exact-static-type call sites

**Status: done — Target: M2.**
Resolved with FR-006 and FR-007.

Find References from an overriding method now resolves the method's overridden contract before
planning the workspace search.
That makes override-origin searches include call sites whose static receiver type is the interface
or superclass contract, including sibling downstream modules of the contract owner.

The implementation reuses javac attribution and override checks rather than text parsing:

1. Resolve the cursor target as a `ReferenceTarget`.
2. For method targets, ask the cursor module analysis for the root contract method.
3. Plan reference search from the contract owner's module when a contract is found.
4. Match method references using javac override-aware element comparison.

Regression coverage:

- `ReferenceLocatorTest.method_override_findsCallThroughInterfaceType`
- `ReferenceLocatorTest.method_interfaceDeclaration_includeDeclarationAddsOverrideDeclaration`
- `ReferenceLocatorTest.method_interfaceDeclaration_findsCallThroughImplementingType`
- `LspSmokeTest.references_fromOverrideMethod_findsSupertypeTypedCallSite`

---

## EG-028 — `textDocument/onTypeFormatting` is a stub and is not registered

**Status: non-goal (as a standalone gap) — findings folded into the formatting design.**

`textDocument/onTypeFormatting` is a stub returning no edits, and its capability is not registered,
so no client invokes it. It can only ever be a partial improvement (GJF parses the whole compilation
unit and returns nothing on the unparseable-newline trigger), its priority is low, and it is mainly
relevant to a later VS Code integration rather than the Neovim focus — where error-tolerant
client-side indentation already covers live typing. Retired as a standalone gap: the finding and its
deferred scope are tracked in
[lathe-formatting-profiles.md](../done/lathe-formatting-profiles.md), "Future Work — Range-Aware
Formatting" (On-type formatting), to be considered only after that design and the range-aware path
land.

---

## EG-029 — `rangeFormat` ignores its range and formats the whole document

**Status: non-goal (as a standalone gap) — findings folded into the formatting design.**

`textDocument/rangeFormatting` delegates to the whole-document `JavaFormatter.format`
(`formatSourceAndFixImports`), ignoring the request's range. In practice it is dormant — the
`documentRangeFormattingProvider` capability is not advertised, so a compliant client never calls it.
Retired as a standalone gap: the finding, the near-term defensive gating, and the range-aware
formatting feature (GJF `formatSource(text, ranges)`, the prerequisite for conservative
`onTypeFormatting`) are now tracked in
[lathe-formatting-profiles.md](../done/lathe-formatting-profiles.md), "Future Work — Range-Aware
Formatting", to be implemented once that formatting design lands.

---

## EG-034 — `textDocument/implementation` on an interface method omits record component accessors that implement it

**Status: done — Target: M2.**

`textDocument/implementation` now returns records that implement an interface method through an
implicit record component accessor.
The accessor method was already found by javac and accepted by `Elements.overrides`; the result was
dropped only because the synthesized accessor has no `MethodTree`.

The resolved implementation first uses the normal method declaration path.
When that path is absent for a zero-argument method in a `RECORD` class tree, it falls back to the
matching `VariableTree` member for the record component and returns the component name location.
This uses javac's AST shape for records and does not parse Java text.

Regression coverage:

- `MethodImplementationTest.methodImplementations_recordComponentAccessor_returnsComponentDeclaration`

---

## EG-032 — Signature help returns nothing for a qualified call with empty parentheses when the method has parameters

**Status: done — Target: M2.**

Qualified empty-argument calls now use the same by-name overload fallback that unqualified calls
already used.
When javac cannot resolve `receiver.method()` to an applicable `ExecutableElement`, the resolver
looks up the receiver expression's declared type and lists methods with the selected name.
This preserves the existing exact-resolution path when javac can resolve the call.

Regression coverage:

- `SignatureHelpTest.signatureHelp_qualifiedEmptyParens_paramMethod_returnsSignature`
- `SignatureHelpTest.signatureHelp_qualifiedEmptyParens_zeroParamMethod_stillWorks`

---

## EG-005 — Workspace symbol search uses strict prefix matching; CamelCase and infix queries find nothing

Status: done — Target: M2.

### Observed behaviour (original)

```
sym "ServerFactory"   → ServerFactory (interface), ServerFactoryImpl (JDK SASL internal)
                        missing: AbstractServerFactory, DefaultServerFactory, SimpleServerFactory
sym "TaskMgr"         → 0 results (no CamelCase abbreviation matching)
sym "AbstractServer"  → AbstractServerFactory (correctly found by prefix)
```

### Resolution

Implemented CamelCase-hump matching (`CamelCaseMatcher`), scoped to reactor-owned types and
merged alongside the existing exact prefix search rather than replacing it:

- `AbstractServerFactory`/`DefaultServerFactory`/`SimpleServerFactory` are now all reachable via
  `"ServerFactory"` — the humps `[Server, Factory]` match a subsequence of candidate humps,
  skipping the leading `Abstract`/`Default`/`Simple` hump.
- `"TaskMgr"` now finds `TaskManager` — humps align, and `Mgr` matches `Manager` via a
  subsequence-within-hump test (`M` anchors, `g`/`r` found in order within `anager`), not a
  literal prefix.
- `"AbstractServer"` (plain prefix) is unaffected, matches as before.

Implemented differently than originally proposed: rather than a secondary pre-built CamelCase
initialism index maintained alongside the reactor-scan lifecycle, matching is done as a live
per-query scan (`WorkspaceTypeIndex.searchCamelCase`) over reactor-owned entries only. This was
judged simpler with no meaningfully worse performance — the per-query scan is cheap (reactor-only,
not the full JDK+dependency universe), and the expensive part (per-result `declarationRange`
parse) stays bounded by the same result cap as the existing prefix path, so no new index structure
or reactor-reload-lifecycle hook was needed.

Descoped, not part of this fix: general infix/substring matching independent of CamelCase-hump
boundaries (e.g. a raw substring like `"erverFac"` that doesn't start at a hump boundary). The
gap's title mentioned both; only the CamelCase half is resolved here. File a new gap if plain
infix matching is wanted later — it is a distinct feature, not an extension of this one.

### Regression targets

- `CamelCaseMatcherTest` — the matching algorithm in isolation (initials, partial-hump
  abbreviations, hump-skipping, case-insensitivity, digit boundaries, negative cases).
- `WorkspaceTypeIndexTest.searchCamelCase_reactorOnly_excludesStaticTypes`,
  `searchCamelCase_abbreviatedHump_findsReactorMatch`, `searchCamelCase_limitsResults`,
  `searchCamelCase_emptyIndex_returnsEmpty`.
- `WorkspaceSymbolTest.resolve_camelCaseAbbreviation_findsReactorMatchMissedByPrefix`,
  `resolve_prefixAndCamelCaseOverlap_deduplicatesResult`.

---

## FR-017 — Cross-module rename end-to-end coverage — non-goal (functionality confirmed by probe)

**Status: non-goal — Target: n/a.**

Filed while scoping an MCP `rename_symbol` tool: cross-module rename is implemented (RenameProvider
builds the `WorkspaceEdit` from the tested cross-module reference search), but the composition
"cross-module reference search + rename edit application" had no automated regression test —
`RenameProviderTest` is single-file only, and the multi-module/invoker rename tests the rename design
doc specced were never written.

Closed as a non-goal after confirming the behaviour directly with a probe rather than adding automated
coverage. Probe (2026-09-19, published `0.1.0-SNAPSHOT` server, on a large private multi-module
reactor): renamed a public static method declared in a shared module; `prepareRename` returned the
exact token range, and `textDocument/rename` returned a `WorkspaceEdit` spanning **4 modules / 6 files
/ 10 edits**, matching the `references` result exactly (declaration + 9 uses, same modules). The edit
was inspected, not applied.

Residual risk accepted: the probe covered only the public-method kind (not field / override-family /
type), and there is no automated guard against a future regression in the reference pipeline silently
breaking cross-module rename. Re-file if such a regression is observed. Cross-module reach is now
documented on the rename feature (README).

## FR-016 — Find the instantiation sites of a type ("where is a new instance created")

Status: done — Target: M2.

A focused search that, from the type under the cursor, returns **only** its `new XXX(...)` sites (all
overloads) — not every use of the type. Complements FR-015 (cursor on a `new XXX(` → that
constructor's call sites). Because the result set is intrinsically only construction sites, it is plain
`Location[]` into the quickfix — no per-reference kind, no glyph, no custom renderer (the role-glyph
alternative was investigated and rejected as heavier).

Implementation reuses the references pipeline:
- `SourceAnalysisSession.instantiationTargets` resolves the `TypeElement` at the cursor and maps its
  constructors (incl. the synthesized default) to `ReferenceTarget`s; `CompilationWorker` adds the
  submit wrapper.
- `WorkspaceSession.instantiationsFuture` searches each via the existing `searchReferencesForTarget`
  (progress title parameterised so references and instantiations share the one method) and unions with
  `joinCandidateResults`.
- Exposed as the `lathe.instantiations` `workspace/executeCommand` (`LatheWorkspaceService`, arg
  deserialised into the standard `TextDocumentPositionParams` via Gson; advertised in
  `createCapabilities`). Client: `lua/lathe/instances.lua` (`:LatheInstances` → quickfix), suggested
  mapping `grN`.

Verified end to end via the probe and a live Neovim against a real workspace (a `new ProbeType()` site
found, the bare `ProbeType` return-type use excluded).

Regression coverage:
- `ReferenceLocatorTest.instantiationSites_type_returnsOnlyNewSites`,
  `..._implicitDefaultConstructor_returnsNewSites`, `..._cursorNotOnType_returnsEmpty`
- `LatheLanguageServerTest.createCapabilities_includesExecuteCommandProvider` (advertises the command)
- `instances_spec.lua` — dispatch + quickfix population, no-client warn, empty-result notify, command
  registration

---

## FR-015 — Find References at a `new XXX(...)` site targets the type, not the constructor

Status: done — Target: M2.

With the cursor on the type-name of an object-creation expression (`new XXX(…)`), Find References
returned every use of the type (imports, field/variable types, other `new` sites, casts) instead of
the constructor's invocation sites. `SourceLocator.elementAt` walks the `TreePath` outward and returns
the first non-package element, so the type-name identifier resolved to the type element before the
walk reached the enclosing `NewClassTree` (whose element is the constructor).

The original fix was references-only; constructor definition subsequently shipped as EG-096 in PR #145.
The original references implementation was:
- `SourceLocator.constructorAtNewClassType(trees, path)` (package-private) walks up from the cursor
  path and, when it reaches a `NewClassTree` whose `getIdentifier()` subtree the cursor sits in
  (simple, qualified `new a.b.X()`, or parameterized `new X<T>()`), returns the invoked `CONSTRUCTOR`
  element — else null (cursor on arguments/body, or a non-constructor). `elementAt` is intentionally
  left unchanged because ~12 callers (hover, semantic tokens, type/call hierarchy, unused-declaration)
  depend on it resolving the type at a `new` site.
- `SourceAnalysisSession.resolveTarget` consults it first, falling back to `elementAt`. The existing
  constructor machinery does the rest: `ReferenceTarget.from` builds a `CONSTRUCTOR` target and
  `ReferenceLocator.visitNewClass` matches `new XXX(...)` sites (incl. an implicit default constructor
  via FR-013 keying, and generated builders); overloads resolve per-overload via the descriptor match.

Regression coverage:

- `ReferenceLocatorTest.references_atNewClassSite_returnsConstructorCallSitesNotTypeUses` (positive —
  finds the `new Widget(...)` sites, excludes a bare `Widget c` declaration)
- `ReferenceLocatorTest.references_atNewClassSite_implicitDefaultConstructor_findsCallSites` (positive)
- `ReferenceLocatorTest.references_atBareTypeUsage_stillReturnsTypeReferences` (negative — a non-`new`
  type usage stays a type target; `constructorAtNewClassType` returns null there)

---

## FR-001 — References from external source have no workspace search root

Status: done — Target: M1.

When Find References is invoked from a cached JDK or dependency source file,
`WorkspaceSession.referencesFuture()` derives the search scope from `cursorConfig`, which is empty
for external paths not belonging to any reactor `ModuleSourceConfig`.
The `REACTOR_MODULES` branch previously called `.orElse(List.of())`, so no project file was ever
searched.

### Fix

`WorkspaceSession.referencesFuture()` — change `orElse(List.of())` to
`orElseGet(workspace::allConfigs)` for the `REACTOR_MODULES` scope branch.
When the cursor is in an external source file, all reactor module configs are searched;
`ReferenceCandidatePlanner` and javac identity matching filter out files that do not actually
reference the target.

The `DECLARING_MODULE` branch retains `orElse(List.of())` — there is no meaningful reactor
module scope to derive for a package-private external symbol.

### Regression test

`LspSmokeTest.references_fromCachedJdkSource_findsReactorUsages` — opens the JDK `String.java`
from the Lathe cache, requests references at the class declaration, and asserts that
`StringUtils.java` (which declares `String upper(String s)`) appears in the results.
The reactor-origin case is covered by
`LspSmokeTest.references_fromReactorSource_findsUsageAcrossModules`.

## FR-005 — Client response incident is not reproducibly covered

Status: done — Target: M2.

The original incident: a Helidon session's server log ended immediately after logging reference
hits, with no exception, fatal JVM message, or RPC exit record, while Neovim never displayed the
two locations. That did not establish a Lathe crash — it established that the test suite at the
time stopped before JSON-RPC client receipt and could not distinguish a server response failure
from an editor-side display or process failure.

### Resolution

`LspSmokeTest` (added for FR-004) now closes that gap directly: `references_fromReactorSource_-
findsUsageAcrossModules`, `references_fromCachedJdkSource_findsReactorUsages`,
`references_fromBaseMethod_findsOverrideDeclarationAndCallSite`, and
`references_fromOverrideMethod_findsSupertypeTypedCallSite` each drive a real subprocess server
through a real LSP4J client and call
`server.getTextDocumentService().references(params).get(30, SECONDS)`, asserting on the actual
`Location` values received — not server-side logs. That is the invoker-side requirement from this
gap's "Required investigation support" list.

The logging requirement is also already met: `WorkspaceSession.referencesFuture`'s existing log
line, `"[references] %s %dms target=%s hits=%d"`, retains request URI, elapsed time, target name,
and hit count.

Not covered: an isolated unit-level test asserting `Location` serializability in isolation (the
invoker test covers this implicitly, since LSP4J actually serializes the response over the wire).
If that narrower gap matters later, open a new `documented` entry rather than reopening this one.

### Regression test

`LspSmokeTest.references_fromReactorSource_findsUsageAcrossModules`,
`references_fromCachedJdkSource_findsReactorUsages`,
`references_fromBaseMethod_findsOverrideDeclarationAndCallSite`,
`references_fromOverrideMethod_findsSupertypeTypedCallSite`.

## FR-006 — Method references are override-aware

Status: done — Target: M2.
Discovered 2026-06-30, gap validation pass.

Find References previously compared method references by exact declaring owner plus erased
descriptor.
That meant a search from an interface or superclass method missed call sites whose static receiver
type was an implementation, and a search from an override missed call sites whose static receiver
type was the supertype.

The resolved implementation reconstructs the target method in each javac analysis and accepts
matches when either executable overrides the other according to `Elements.overrides`, while keeping
constructors and unrelated overloads exact.

Regression coverage:

- `ReferenceLocatorTest.method_interfaceDeclaration_findsCallThroughImplementingType`
- `ReferenceLocatorTest.method_override_findsCallThroughInterfaceType`

## FR-007 — Override-method references search inherited hook calls in supertype sources

Status: done — Target: M2.
Discovered 2026-06-30, Dropwizard `SessionFactoryProvider` validation.

Find References on a reactor override such as
`SessionFactoryProvider.createValueProvider(...)` previously missed inherited framework hook calls
in cached superclass sources, because candidate discovery required method candidates to contain both
the method simple name and the override owner's exact simple name.

The resolved implementation treats method candidate discovery as simple-name based and leaves exact
owner and override validation to javac attribution.
Fields, constructors, and enum constants keep the narrower owner filter.

Later superseded by **FR-009**, which replaced the broad simple-name method discovery with
override-family narrowing (declaring type + overridden declarers + subtypes) while preserving this
supertype-self-call coverage; the regression test was renamed accordingly.

Regression coverage:

- `ReferenceCandidatePlannerTest.planCandidates_instanceMethod_defersNothing`

---

## FR-009 — Member-reference search compiles every token-matching file (no hierarchy narrowing)

**Status: done (historical mitigation; current performance remainder tracked as FR-029).**
Discovered against an `@Builder`-heavy reactor; confirmed on `sample-workspace`
(`AppServerConfig`).

### Observed behaviour (historical)

Find References on a member whose simple name is common compiled hundreds of files to return a
handful of hits. `ReferenceCandidatePlanner.planCandidates` returned the raw simple-name candidate
set for `METHOD` targets with no type filter; record components normalise to their accessor (a
`METHOD`) via `ReferenceTarget.recordAccessorFor`, so component searches took the same broad path.
Each candidate was then compiled with FAST javac in `searchReferencesTransient` — compilation, not
the O(1) token lookup, dominated the wall-clock. The `FIELD` branch narrowed only by the enclosing
type's simple name, which also *missed* a protected field read through a subtype-typed receiver in a
file that never spelled the declaring type.

### Historical resolution (subsequently superseded)

`planCandidates` now bounds method and field searches to the member's **override family** instead of
the raw simple-name set (`files(memberName) ∩ ⋃ candidateUris(familySimpleName) ∪ static-import
spellings`, via the shared `narrowToFamily`/`overrideFamily` helpers):

- **method** — declaring type + the supertypes whose method it overrides (reactor *or* dependency) +
  its overriding subtypes;
- **field** — declaring type + subtypes that inherit it;
- **constructor / enum constant** — declaring type only.

The overridden declarers require javac member inspection, so they are computed in
`ReferenceTarget.from` and carried on the record (`overriddenDeclarers` + `overrideFamilyBounded`);
subtypes come from the reactor `WorkspaceTypeIndex`, now passed to the planner.

Broad search is retained in exactly two cases so no genuine reference is missed: a target
reconstructed from a call-hierarchy item (no javac context, `overrideFamilyBounded == false`), and a
method overriding a `java.lang.Object` member (`equals`/`hashCode`/`toString`) — every type is an
`Object` and receivers are rarely spelled `Object`, so family narrowing would drop most call sites.

Accepted trade-off (as designed): a reference whose owner/receiver type is never textually spelled in
its file is missed — the same surface the `FIELD` branch already accepted, now consistent across
kinds.

### Historical measurements (sample-workspace)

| Search | Before | After |
|---|---|---|
| `AppServerConfig.name()` record accessor (overrides dependency `BaseConfig.name`) | 112 candidates, ~16.6 s, 1 hit | 8 candidates, ~4.8 s, 1 hit |
| `ValidCheck.check()` static (overrides nothing) | 45 candidates, 74 hits | unchanged |

The `name()` accessor is the interesting case: it overrides `BaseConfig.name()` in the `app-base`
dependency, so the fix narrows by the declarer simple names (`AppServerConfig`, `HttpServiceConfig`,
`ServiceConfig`, `BaseConfig`) rather than falling back to broad.

### Probe commands

```bash
# Against an @Builder record: refs on a component accessor; watch "N / M candidates" vs hit count.
printf 'refs <line>:<col>\n' \
  | LATHE_DEBUG=1 python3 dev/explore.py /path/to/workspace/.../SomeRecord.java
```

### Regression targets

The initial hard family narrowing described above was superseded after FR-011 exposed missed chained/inferred receivers.
Current instance-method searches do not defer candidates; static class methods may search family files first and defer the rest, while fields and enum constants search every file spelling the name in one phase.
These current tests replace the obsolete proposed narrowing tests:

- `ReferenceCandidatePlannerTest.planCandidates_methodInvokedOnUnspelledReceiver_includesCallSite`
- `ReferenceCandidatePlannerTest.planCandidates_instanceMethod_defersNothing`
- `ReferenceCandidatePlannerTest.planCandidates_staticMethod_searchesTheFamilyFirstAndDefersTheRest`
- `ReferenceCandidatePlannerTest.planCandidates_fieldOrEnumConstant_searchesEveryFileSpellingItsNameInOnePhase`
- `ReferenceCandidatePlannerTest.planCandidates_protectedFieldInheritedInSubclass_includesSubclassFiles`

See active FR-029 for the remaining latency issue; this archive does not claim those performance limits are solved.

---

## EG-045 — Hover on a component reference inside a record's compact constructor resolves to the callee's parameter

**Status: done — Target: M3**

Fixed: `SourceLocator.masksParameterContext` now suppresses the argument param-hint for a record's
canonical-constructor `PARAMETER` (a compact-ctor component reference), mirroring the existing
`FIELD` mask, so hover falls through to normal resolution and shows the component. Verified live.
Regression targets below are implemented and passing.

### Observed behaviour

Hover on a bare reference to a record component **inside the compact constructor body** returns the
wrong symbol: it resolves to the formal parameter of the method the component is passed to, not to the
component itself.

```java
public record Config(String bucket) {
    public Config {
        check(bucket, "bucket");   // ← hover on `bucket` → "Object value" (check's parameter!)
    }
    static void check(Object value, String name) {}
}
```

Hover is correct everywhere else the same component appears — verified against a validation workspace:

| Hover position | Result | Correct? |
|---|---|---|
| component in the record header | the component (`T bucket`) | ✓ |
| accessor call `x.bucket()` | the accessor (`T bucket()`) | ✓ |
| ordinary method argument (a local/param elsewhere) | that argument's own type | ✓ |
| **bare component ref inside the compact constructor** | the *callee's* first parameter (`Object value`) | ✗ |

At the same failing position, `definition` correctly jumps to the component declaration, so the
symbol is resolvable — only the hover path mis-resolves it. Shares its trigger with the references
defect [FR-014](#fr-014--find-references-from-a-component-reference-inside-a-records-compact-constructor-returns-only-that-one-occurrence).

### Root cause (hypothesis)

The bare reference is the implicit canonical-constructor `PARAMETER`, whose source position overlaps
the record header (the backing field and the canonical-ctor parameter share the header range). The
hover position→element path appears not to find that parameter's attributed element at the in-body
position and falls back to the enclosing `MethodInvocationTree`, resolving to the invoked method's
formal parameter (`check(Object value, …)` → `value`). `definition` uses a different resolver
(`DeclarationLocator`) and is unaffected — so this is specific to the hover resolution path, not the
shared `resolve()`/`elementAt` position mapping (ordinary arguments hover correctly).

### Probe commands

```bash
# `bucket,` lands the cursor on the compact-constructor use.
# Expected: the component's type. Bug: the callee's parameter ("Object value").
printf 'hover "bucket,"\n' | python3 dev/explore.py /path/to/workspace/.../Config.java
```

### Regression targets

- `HoverTest.hover_recordComponentInCompactConstructor_resolvesComponentNotCalleeParameter`
  (positive — the failing case)
- `HoverTest.hover_insideMethodRefArgument_showsOwnSymbol_plainArgumentShowsParameter`
  (boundary — ordinary arguments must keep resolving to the argument, not the callee's parameter)

---

## EG-046 — Hover on a call argument shows synthetic `arg0` parameter names while signature help shows the real names

**Status: done — Target: M3**

Fixed: the `SourceAnalysisSession.hover` argument param-hint branch now formats through the shared
`resolveParamNames` + `isSyntheticName` + `TypeDisplayFormatter` path (via `HoverFormatter.formatParam`),
matching signature help and method-name hover; the divergent `HoverFormatter.formatParameter` was
removed. Verified live on line 84 of the validation workspace — string-literal arguments now show
`name` / `defaultObj` instead of `arg0` / `arg1`. Regression target below is implemented and passing.

### Observed behaviour

Hovering an argument at a call site shows a synthetic parameter name (`arg0`, `arg1`, …) whenever the
callee comes from a dependency or the JDK compiled without javac `-parameters`. Signature help on the
same call shows the **real** names (and highlights the active parameter). Confirmed against a
validation workspace, hovering the arguments of a JDK call `Objects.requireNonNullElse(x, 10)`:

| Probe | Result |
|---|---|
| `hover` on argument 0 (`x`) | `T arg0` |
| `hover` on argument 1 (`10`) | `T arg1` |
| `sig` on the call | `T requireNonNullElse([T obj], T defaultObj)` — real names, active parameter highlighted |

The two features disagree about the same method's parameters: hover surfaces the raw class-file
placeholder, signature help surfaces the declared source name.

### Root cause — and why signature help is correct

The `arg0` class-file limitation was already solved once (see
`docs/done/lathe-signature-help.md`): when a class file lacks a `MethodParameters` attribute, javac
synthesises `arg0`/`arg1`, so `SourceParser.resolveParamNames` does an on-demand, AST-only parse of
the callee's `.java` source (from the dependency / JDK source cache) to recover the declared names,
and `SourceParser.isSyntheticName` suppresses any remaining `argN` (falling back to type-only
display). Both consumers that show good names wire this in:

- **Signature help** — `SignatureHelpResolver` calls `resolveParamNames` (line ~248). **This is why it
  is correct.**
- **Method-name hover** — `SourceAnalysisSession.hover` (line ~239) resolves `sourceParamNames` and
  passes them to `HoverFormatter.format` → `formatParam`, which also applies `isSyntheticName`.

The **argument param-hint** branch of hover never got this treatment. When the cursor is on an
argument, `SourceAnalysisSession.hover` (lines ~221-225) takes an early branch on
`SourceLocator.parameterElementAt` and formats via a *separate* method, `HoverFormatter.formatParameter`:

```java
public static String formatParameter(final VariableElement param) {
  return "```java\n%s %s\n```".formatted(param.asType(), param.getSimpleName());  // raw name + raw type
}
```

It prints `param.getSimpleName()` directly — no `resolveParamNames`, no `isSyntheticName` suppression,
no `TypeDisplayFormatter`. So it shows the class-file placeholder `arg0`, and the type without the
display formatting the rest of hover uses. It is simply a path that drifted from the resolution the
other two share.

### Proposed fix

Format the param-hint through the same resolution the other paths use. From the `param` returned by
`parameterElementAt`, recover the callee and index without changing its signature —
`callee = (ExecutableElement) param.getEnclosingElement()`,
`idx = callee.getParameters().indexOf(param)` — then resolve
`parser.resolveParamNames(callee, allRoots)`, apply `isSyntheticName` suppression, and format with a
`TypeDisplayFormatter` (reusing `HoverFormatter.formatParam`). Delete the divergent
`formatParameter`. This makes argument hover consistent with signature help and method-name hover.

Shares the param-hint code path with [EG-045](#eg-045--hover-on-a-component-reference-inside-a-records-compact-constructor-resolves-to-the-callees-parameter)
(which fires the branch for the wrong element); the two are best fixed together.

### Probe commands

```bash
# Hover an argument of a JDK/dependency call whose class file lacks -parameters, then compare to sig.
# Expected (after fix): hover shows the same real names as sig; today it shows argN.
printf 'hover "<arg-token>"\nsig after "requireNonNullElse("\n' \
  | python3 dev/explore.py /path/to/workspace/.../SomeFile.java
```

### Regression targets

- `HoverTest.hover_classFileDependencyArgument_showsSourceParameterName`
  (positive — argument hover shows the resolved source name, mirroring
  `signatureHelp_classFileDependency_showsSourceParameterNames`)
The originally proposed no-source fallback test is not in the current suite.
`HoverFormatter.formatParam` suppresses synthetic names in its fallback, but the positive source-name test above must not be described as direct no-source regression coverage.

---

## FR-010 — Reference highlight lands on the wrong identifier in generated code

**Status: done — Target: M2**

### Observed behaviour

Find References on a member reported a match whose highlighted range covered a *neighbouring*
identifier (a similarly-named sibling such as `taxAmount` when searching `amount`) inside a generated
builder class, instead of the intended occurrence. Reported against generated `@Builder` sources.

### Root cause

`ReferenceLocator.addMatchAtIdentifier` computed the identifier start as
`endPosition(node) - name.length()`, assuming the node ends exactly at the target identifier. When
javac source positions are approximate (as they can be for generated sources), `getEndPosition` need
not land at the identifier end, so the end-minus-length arithmetic slices the range onto an adjacent
token. The semantic matcher is name-exact, so this was a range-computation defect, not a matching
defect — the match count was right, the drawn range was wrong.

### Resolution

`addMatchAtIdentifier` now locates the selector by text from a reliable anchor instead of the
arithmetic: it anchors on the receiver end (`getEndPosition(node.getExpression())` for a
`MemberSelectTree`, `getQualifierExpression()` for a `MemberReferenceTree`, else the node start) and
reuses the existing `SourceLocator.findIdentifierFrom` helper — the same approach `addDeclarationMatch`
already uses. Anchoring after the receiver also prevents matching an earlier occurrence of the name
inside the receiver (e.g. `amount.amount`). No new abstraction; the existing range-start tests in
`ReferenceLocatorTest` (`field_memberSelect_reportsNamePosition`,
`field_parameterWithSameName_notConfusedWithField`, `method_overload_doesNotMatchSibling`) guard
against regressions.

Note: the original symptom is **no longer reproducible** in the validation workspace. Live probing
with `dev/explore.py` over the `@Builder`-heavy reactor (including the literal `amount`/`taxAmount`
record shape) returned correct ranges for every reference — the on-disk generated sources now carry
accurate javac positions, so the end-minus-length arithmetic no longer had an inaccurate position to
trip on. The fix is therefore defensive hardening that removes the latent fragility rather than a
change verified against a live reproduction. A synthetic-position unit repro was declined as
disproportionate (it would require faking `SourcePositions`); a Unicode-escaped-identifier repro was
also explored and dropped because covering it would need either javac internals or bespoke
escape scanning, both out of scope.

---

## FR-011 — Method call on a `var`/chained receiver is dropped when the receiver type is never spelled

**Status: done — Target: M2**

Fixed: candidate planning no longer narrows method targets to the override family, so a call on a `var`/chained/unspelled receiver is compiled and matched. Merged in 0b9d366; regression targets below pass.

### Observed behaviour

Find References on a record component reports **no matches outside the declaring record**, even though
a genuine call site exists. Reproduced against a validation workspace: a record accessor is invoked
on a receiver whose type is inferred and never written in the calling file.

```java
// FeatureConfig.java — the record; component 'whitelist' has an implicit accessor whitelist()
public record FeatureConfig(boolean enabled, List<String> whitelist, ...) { ... }

// RequestContext.java — the call site, in a different module. Note: FeatureConfig is NEVER spelled
// here (no import, no explicit type); its type flows in through `var` from a getter chain.
final var config = getConfig().feature();          // config's type FeatureConfig is inferred
return config != null
    && config.enabled()
    && (userId == null || !config.whitelist().contains(userId));   // <-- missed reference
```

Clicking `whitelist` in the record returns only the declaration; `config.whitelist()` above is not
reported.

### Root cause

The defect is in **candidate planning**, not in reference matching. `ReferenceLocator`/
`ReferenceTarget.matches` would match `config.whitelist()` correctly — but the file is pruned before
javac ever compiles it.

1. The record component is normalised to its public accessor method (`ReferenceTarget.from` →
   `recordAccessorFor`), producing a `METHOD` target with `overrideFamilyBounded = true` and empty
   `overriddenDeclarers` (a record accessor overrides nothing).
2. `ReferenceCandidatePlanner.planMethodCandidates` (`ReferenceCandidatePlanner.java:116-131`) takes
   the override-family narrowing path and calls `narrowToFamily`.
3. `narrowToFamily` (`ReferenceCandidatePlanner.java:153-170`) **intersects** the files that spell the
   member simple name (`whitelist`) with the files that spell a family type's simple name
   (`FeatureConfig`). The call site spells `whitelist` but not `FeatureConfig`, so it is filtered out.

The override-family heuristic assumes a genuine call site textually spells the receiver's static type
name (the doc comment at `ReferenceCandidatePlanner.java:105-114` explicitly relies on
`baseConfig.name()` spelling the type). That assumption breaks whenever the receiver type is never
written: `var` bound from a factory/getter chain, a chained call (`getConfig().feature().whitelist()`),
a generic type variable, or a wildcard capture. The result is a **false negative** — a real reference
silently pruned.

This is not record-specific; any public-method reference has the same blind spot. Records merely make
it common, because config accessors are routinely reached through `var`/chained getters.

### Fix (approach A — drop override-family narrowing for methods)

Method targets now use the **broad simple-name candidate set** — every file that spells the method's
simple name — with no override-family narrowing. `planMethodCandidates`, `overrideFamilyBounded`, and
the `java.lang.Object` special-case are removed from the method path (`overrideFamily` /
`narrowToFamily` remain for fields, constructors, and enum constants).

Rationale: the narrowing was a candidate-pruning optimization, not a correctness mechanism — javac
still decides real matches in `ReferenceLocator`. It was the source of the false negative, and no
purely textual heuristic can tell a genuine call on an unspelled receiver (`config.whitelist()`, want
kept) from an unrelated same-named method (want dropped) without compiling. Reference search is an
explicit, batched, cancellable, progress-reported action, and the batch-compilation path is cheap
enough that compiling every file spelling the name is an acceptable trade for full correctness and a
simpler planner. A member-invocation index (former "approach B") was considered but rejected as it
adds a second index dimension and a position-aware text scan for no correctness gain over A.

Trade-off: a reference search on a very common method name (`get`, `size`, `toString`) compiles more
files than before. Mitigated by batching + cancellation; if a pathological case ever bites, a
high-threshold cap (`size > N ? narrow : broad`) can be reintroduced without an index change.

### Regression targets

- `ReferenceCandidatePlannerTest.planCandidates_methodInvokedOnUnspelledReceiver_includesCallSite`
  (positive — a call on a `var`/unspelled receiver is a candidate)
- `ReferenceCandidatePlannerTest.planCandidates_methodNameNeverSpelled_excludesFile`
  (negative — a file that never spells the method's simple name is not a candidate)

---

## FR-012 — Type references miss a same-package generated file that uses the type without importing it

**Status: done — Target: M2**

Fixed: the same-package candidate filter now also considers the generated-sources root, so a generated builder in the same package (no import) is found. Merged in 4cc7196; regression targets below pass.

### Observed behaviour

Find References on a `@Builder` record **type** does not report the generated builder, even though the
builder plainly references the record. Reproduced against a validation workspace: the record's
generated builder lives in the same package, in the annotation-processor output root, and names the
record without an import.

```java
// FeatureConfig.java — src/main/java, the annotated record
package com.example.app.config;
public record FeatureConfig(boolean enabled, List<String> whitelist, ...) { ... }

// FeatureConfigBuilder.java — target/generated-sources/annotations, SAME package, NO import of the
// record; references the type by simple name only.
package com.example.app.config;
public final class FeatureConfigBuilder {
  public FeatureConfig build() { return new FeatureConfig(...); }   // <-- missed type reference
}
```

References on `FeatureConfig` return the source-tree usages but omit the generated builder.

### Root cause

Candidate-planning prune, not a matching defect. The generated builder **is** in the candidate index
(`ReferenceCandidateIndex.allSourceRoots` indexes `originalGenSourcesDir` alongside `sourceRoots`), so
it appears in `simpleCandidates` for the token `FeatureConfig`. But `planTypeCandidates`
(`ReferenceCandidatePlanner.java:64-103`) keeps a same-package file only through
`isInPackage(path, config.sourceRoots(), packageRel)` (`ReferenceCandidatePlanner.java:101, 177-182`),
which resolves `packageRel` against **`config.sourceRoots()` only** — never
`config.originalGenSourcesDir()`. The builder's parent directory sits under the generated-sources
root, matches no regular source root, and is pruned.

The import-token branch still finds generated files that use an explicit or wildcard import, so only
the **same-package, no-import** shape is lost — which is exactly how the generated builder (and
updater/companion types) reference the record they belong to.

### Proposed fix

Have the same-package filter consider the generated-sources root in addition to the regular source
roots: resolve `packageRel` against `config.sourceRoots()` ∪ `{config.originalGenSourcesDir()}` (when
non-null). Minimal and localized to `planTypeCandidates` / `isInPackage`.

### Regression targets

- `ReferenceCandidatePlannerTest.planCandidates_typeUsedInSamePackageGeneratedSource_includesGeneratedFile`
  (positive — fails before the fix)
- `ReferenceCandidatePlannerTest.planCandidates_typeInGeneratedSourceDifferentPackage_excludesFile`
  (negative — generated-root awareness must still respect package boundaries)

---

## FR-013 — Constructor references select no candidates because the target is keyed on `<init>`

**Status: done — Target: M2**

Fixed: a CONSTRUCTOR target now keys candidate lookup on the declaring type's simple name instead of `<init>`, so `new Type(...)` sites (including the generated builder) are found. Merged in 4cc7196; regression targets below pass.

### Observed behaviour

Find References on a record/class **constructor** returns nothing outside the declaring file — in
particular it omits the generated builder's `new FeatureConfig(...)` call. Reproduced against a
validation workspace:

```java
// FeatureConfigBuilder.java — generated
public FeatureConfig build() {
  return new FeatureConfig(enabled, whitelist, ...);   // <-- missed constructor reference
}
```

### Root cause

Candidate-planning prune. A constructor's `ReferenceTarget.simpleName` is javac's constructor name
**`<init>`** (`ReferenceTarget.from` uses `element.getSimpleName()` for the `CONSTRUCTOR` case,
`ReferenceTarget.java:54-78`). `planCandidates` opens with
`simpleCandidates = index.candidateUris(target.simpleName())` — i.e. `candidateUris("<init>")`.
`<init>` is never an identifier token in any source file, so the set is **empty**, and the guard at
`ReferenceCandidatePlanner.java:31-33` returns `Set.of()` before the constructor branch
(`ReferenceCandidatePlanner.java:49-51`) ever runs. Every constructor reference search therefore
selects zero external candidates; the `new FeatureConfig(...)` call site is never compiled or matched.

### Proposed fix

For a `CONSTRUCTOR` target, key the initial candidate lookup on the **declaring type's simple name**
(derived from `target.qualifiedName()`), not `target.simpleName()`. The existing constructor branch
`narrowToFamily(Set.of(target.qualifiedName()), …)` then bounds the search to files spelling the type
name — the generated builder among them. Pairs with FR-012 so the same-package generated builder both
survives the simple-name lookup and passes the package filter.

### Regression targets

- `ReferenceCandidatePlannerTest.planCandidates_constructorInvokedViaNew_includesCallSite`
  (positive — fails before the fix; target keyed on `<init>`)
- `ReferenceCandidatePlannerTest.planCandidates_constructorOwnerNameNotSpelled_excludesFile`
  (negative — a file that never spells the type stays out)

---

## FR-014 — Find References from a component reference inside a record's compact constructor returns only that one occurrence

**Status: done — Target: M2**

Fixed: `ReferenceTarget.recordAccessorFor` now normalises the implicit canonical-constructor
`PARAMETER` to the component's accessor (as it already did for `RECORD_COMPONENT` and the backing
`FIELD`), so Find References is symmetric from the header and the compact-ctor use. Verified live —
the compact-ctor query now returns all sites. Regression targets below are implemented and passing.

### Observed behaviour

Find References is asymmetric for a record component. Invoked from the component in the record
header it finds every use; invoked from a bare reference to that same component **inside the compact
constructor body** it returns only that one occurrence.

```java
// Config.java — component `bucket` has an implicit accessor bucket()
public record Config(String bucket) {
    public Config {
        check(bucket, "bucket");   // ← Find References here: only this line is reported
    }
    static void check(Object value, String name) {}
    String read() { return bucket; }   // this genuine use is missed from the compact-ctor query
}
```

From the header `bucket`: all uses (backing-field reads, the compact-ctor reference, external
accessor calls). From the compact-ctor `bucket`: a single self-match, and the candidate planner only
even evaluates one candidate (the search is silently file-scoped).

`definition` from the same compact-ctor position resolves correctly to the component; only
`references` collapses. Shares its trigger with the hover defect [EG-045](#eg-045--hover-on-a-component-reference-inside-a-records-compact-constructor-resolves-to-the-callees-parameter).

### Root cause

`SourceAnalysisSession.resolveTarget` resolves the cursor via `SourceLocator.elementAt`, which inside
the compact constructor returns the **implicit canonical-constructor `PARAMETER`** (javac models each
record component as a same-named formal parameter of the canonical constructor). `ReferenceTarget.from`
then calls `recordAccessorFor` (`ReferenceTarget.java:109`), which normalises a `RECORD_COMPONENT`
element and a record's backing `FIELD` to the public accessor — **but not the canonical-constructor
`PARAMETER`**. So `from` falls through to the `default` branch and builds a `kind=PARAMETER`,
`scope=DECLARING_FILE` target that matches only same-named parameters in the one file → the single
self-hit.

The matching side already handles this member: `matchesRecordComponentMember`
(`ReferenceTarget.java:246`) explicitly counts "the canonical constructor `PARAMETER` that javac
reports for compact/canonical constructor bodies" — but that only fires when the target was built
**from the accessor**. The gap is purely entry-point normalisation, which is why the search works
from the declaration and not from the use. Every existing record test in `ReferenceLocatorTest` builds
its target from the header, so the asymmetry was never exercised.

### Proposed fix

Extend `recordAccessorFor` (threading `Types` through its single caller in `from`) to also normalise a
canonical-constructor `PARAMETER` of a record to its component's accessor, reusing the existing
`enclosingRecord`, `isCanonicalConstructorParameter`, and `componentNamed` helpers. Single-class change
in `ReferenceTarget`; no public API change, no new abstraction. Makes references (and the shared
`resolveTarget` consumers) symmetric from either end.

### Probe commands

```bash
# `bucket,` (component + comma) lands the cursor on the compact-constructor use, not the header.
# Expected: all reference sites. Bug: a single self-match.
printf 'refs "bucket,"\n' | python3 dev/explore.py /path/to/workspace/.../Config.java
```

### Regression targets

- `ReferenceLocatorTest.recordComponent_fromCompactConstructorParameterUse_findsAllReferences`
  (positive — target built from the compact-ctor use finds the same sites as from the header)
- `ReferenceLocatorTest.recordComponent_fromNonCanonicalConstructorParameter_notNormalized`
  (negative — a non-canonical constructor's same-named parameter stays file-scoped, not normalised)

---

## EG-056 — Private method called only from a private constructor is hinted unused — done

**Status: done — Target: next** (commit 40d98ea0).

### Observed behaviour

User report: a `private static` method whose only call site is in the class's constructor gets the
`lathe.unused` hint ("Unused private method '…'").
Reproduced when the constructor is `private`, the usual shape for factory and singleton classes.
The hint is wrong in every compile mode (FAST, OPEN, FULL), on open and after a save.

```java
public class Foo {
  private final int size;

  private Foo() {
    this.size = defaultSize();   // the only use
  }

  private static int defaultSize() {   // hinted "Unused private method 'defaultSize'"
    return 10;
  }

  public static Foo create() {
    return new Foo();
  }
}
```

With a `public` or package-private constructor there is no false hint, whether the file goes through
a single-file compile or the multi-file open batch (`SourceAnalysisSession.compileOpenBatch`).

The reported class has exactly this shape: a private constructor, called from public static
factory methods, is the only caller of a `private static` helper.
The report also said the hint goes away after an edit and a save.
That did not reproduce: a plain `didSave` with no edit keeps the hint, so the hint most likely
cleared because of what the edit changed.

```bash
# multi-module invoker workspace, with the class above in core/src/main/java/com/example/core/
printf 'diag\nsave\ndiag\n' | python3 dev/explore.py core/src/main/java/com/example/core/Foo.java
# [HINT ] 10:22  Unused private method 'defaultSize' [unused]   (before and after save)
```

### Root cause

In `UnusedDeclarationScanner.markReference`, a reference to a private method from inside another
method records a call *edge* when `enclosingPrivateMethod()` returns a private enclosing method;
otherwise it marks the callee directly reached.
`enclosingPrivateMethod()` only checks the `PRIVATE` modifier, so a `private` constructor counts too,
and the call becomes an edge `Foo() → defaultSize()`.
But only `ElementKind.METHOD` elements are collected into `privateMethods`, and only directly reached
methods start the BFS in `reachableMethods()`.
The constructor is never a BFS root, so the edge is never followed and the callee stays unreached.

### Proposed direction

Record a call edge only when the enclosing method is itself a tracked candidate.
In `enclosingPrivateMethod()`, return the element only if `privateMethods.containsKey(element)`,
otherwise null (directly reached).
Then any constructor, private or not, is a reachability root.
Edge sources and unused candidates come from the same set, so the two can't disagree again.
Trade-off: an uncalled private constructor still roots its callees.
That is acceptable, since private constructors are deliberately never hinted
(`compile_privateNoArgConstructor_noHint`).
Initializer blocks and field initializers already go through the `ClassTree` → null path.

Rejected alternative: track private constructors as candidates and count `new Foo()` calls.
It is more precise but larger, and it would start hinting the conventional `private Utility() {}`.

### Fix

As proposed: `enclosingPrivateMethod()` returns the enclosing element only if `privateMethods` tracks
it, so any constructor is a reachability root and its callees, and their private callees, are
reached.

### Regression targets

- `UnusedDeclarationScannerTest.compile_privateMethodsReachedOnlyFromConstructors_noHint` (a private
  constructor, a private-to-private chain from it, and a package-private constructor; fails without
  the fix).
- `UnusedDeclarationScannerTest.compile_unusedPrivateMethodBesidePrivateConstructor_reportsHint`.

---

## FR-018 — References miss uses written to disk outside the editor: the candidate index never re-reads a changed file — done

**Status: done — Target: next** (PR #35).

Signal: release smoke of the 0.1.0-SNAPSHOT at `6c3f120` (2026-10-21) on Dropwizard, EqualsVerifier
and Helidon.
An MCP `rename_symbol` round-trip (rename, then rename back) left the tree broken on all three.

### Observed behaviour

A file changed on disk by anything but the editor (an agent's write, an MCP `rename_symbol`, a
`git checkout`) is recompiled within a pass, about 2 s, but reference search keeps answering from
the file's old text until the server restarts or the workspace reloads.

- **MCP rename round-trip** (Dropwizard, `Duration.toMilliseconds`): the rename writes 52 edits
  across 15 files.
  `find_references` on the new name then returns 1 (the declaration) at 0, 5, 15 and 40 s.
  Renaming back therefore edits only the declaration and leaves 14 files calling a method that no
  longer exists.
  The same happens on EqualsVerifier (`Warning.ALL_FIELDS_SHOULD_BE_USED`: 31 edits across 16 files,
  1 back) and Helidon (`MediaType.text()`: 77 edits across 33 files, 3 back).
- **External edit over LSP**: appending a `Duration.seconds(1).toMilliseconds()` call to the closed
  `Jackson.java` is picked up and written (`[react] writing 1 file(s) in dropwizard-jackson:
  Jackson.java`), but `textDocument/references` on `toMilliseconds` never lists `Jackson.java`
  (5, 15 and 30 s later).
- **Editor rename is fine**: the same rename applied the way Neovim does it (`grn` then `:wa`, so
  every edited file is opened, changed and saved) re-resolves 51/51 references within 10 s.

### Root cause

`ReferenceCandidateIndex` maps identifier tokens to the files that contain them.
It is built from disk at workspace load and reload, and after that it is updated only from
document events: `onOpen`, `onChange`, `onClose` (which re-reads the file from disk), and the MCP
`diagnosticsFuture` warm-up.
The write pass (`runPass`) that reacts to stale sources recompiles them but never re-indexes them,
so a file changed on disk keeps its old tokens.
A use added outside the editor is never a candidate, and a removed or renamed one stays a candidate,
which is harmless because the candidate is re-checked by javac.

MCP makes this worse in two ways:

- `LatheEngine.writeAndCommit` writes the rename's files directly and reports them through
  `writeReported`; only the declaring file passes through `diagnosticsFuture`.
- An editor's server on the same workspace never sees those files as stale, because the engine
  stamps them.
  The engine touches `workspace.json` so that server re-scans (`REACTOR_REFRESH`), but that only
  rebuilds the type index, not the candidate index.

### Fix direction

Keep the candidate index in step with disk from the pass, by file mtime rather than compile stamp:

- The index records the mtime each file was indexed at; content from an open document records none.
- Each pass already reads every non-generated source's mtime (`TreeState.read`).
  Hand those to the index: re-read each file that is not open and whose mtime differs, add new
  files, and drop indexed files under those roots that are gone.
  This is a map comparison per tick plus a read of only the changed files.
- Mtime rather than stamp covers both the editor's server (whose stamps the MCP engine already wrote)
  and the MCP server itself, and also covers branch switches, deletions and new files.

Also check while there: the index builds its URIs with `Path.toUri()` while documents arrive through
`LatheUri.canonical`, which upper-cases a Windows drive letter, so on Windows one file may be indexed
under two URIs.

### Regression targets

- `ReferenceCandidateIndexTest.syncFromDisk_changedNewAndDeletedFiles_followDiskButOpenFileKeepsBuffer`
- `ReferenceCandidateIndexTest.syncFromDisk_unchangedMtimeOrGeneratedSource_leavesTokensAlone`
- `WritePassTest.reconcileNow_callersChangedExternally_referencesFollowDisk` (fails without the fix:
  the caller added in another package is missing)
- `LatheEngineRenameTest.rename_thenRenameBack_afterAQueryOnTheCaller_restoresTheCaller` (fails without
  the fix: the rename back edits only the declaration)

Verified on Dropwizard with the fixed build: references on the new name right after the MCP rename
return 52 (was 1), the rename back edits 52 uses across 15 files (was 1) and leaves the tree clean,
and the external `Jackson.java` call is found within 5 s.
With MC-6 also fixed, the MCP battery's rename round-trip leaves the tree clean on Dropwizard (52
edits across 15 files), EqualsVerifier (31 across 16) and Helidon (77 across 33).

---

# Completion (CQ) — resolved

## CQ-0077 — A name declared since the last compile is not offered by simple-name completion, and the list is marked complete — done

**Status: done** (2026-11-24)

**Kind: bug**

**Affects: completion of simple names in the editor, while typing.**

Found by the 2026-11-24 QA round on both Helidon and Dropwizard (`0.1.0-SNAPSHOT` at `7a4ab21`; reports `dev/reports/qa-2026-11-24-helidon.md` B-1, with its evaluation, and `dev/reports/qa-2026-11-24-dropwizard.md` N1).

### Observed behaviour

In Helidon's `LoomServer.java`, inside `validateRoutingsHaveNamedListener`, one `didChange` adding `String zqxLocal = "";` and a next line `zqx§`, then completion at once, gives nothing; from about 0.6 s after the change (the 500 ms debounce plus the compile) it gives `zqxLocal`.
Every keystroke restarts the debounce, so typing the declaration, Enter and `zqx` at 120 ms a character still gets nothing.
Dropwizard's round adds that the empty list is marked `incomplete=false`, so a client that narrows the list does not ask again as the name is typed.

The same holds for a new field, method, parameter, block, `for`, enhanced-`for`, lambda, catch and pattern variable.
Member access on the new local (`zqxLocal.§`) works at once.

The unit fixture reproduces it: `CompletionFixture.completeWithCache(old, current)`, with `old` an empty method and `current` declaring `zqxLocal`, `zqxField` or `zqxParam`, gives `[]`; `complete(current)` gives the name.

### Cause

`SimpleNameProvider` takes every name from the cached snapshot (the method's locals by scanning its tree, class members from its `Scope`), and a name typed since that compile is not in it.
`CompletionEngine` recompiles the current text when the cache differs before the completion token (`MemberAccessCompleter.staleBeforeCompletionToken`) and returns the recompile as `freshAnalysis`, which `SourceAnalysisSession` caches, but only for a case label; member access does the same on its own.

### Resolution

The recompile now runs for every request that ends in simple-name completion: a simple name, an argument, a case label, a constructor call's argument, a lambda body without a receiver, and a type reference read as a mixed simple name.
One rule (`CompletionEngine.completesSimpleName`) decides both the recompile and the routing, so the two cannot drift apart.
It runs when the cache differs from the text anywhere but in the name at the cursor, above it or below (a member added or removed further down counts); the recompile is returned as `freshAnalysis` and cached, so the next keystrokes of the name, which change only the name, stay on the fast path.
That is about one recompile per word typed in a burst: about 30 ms more in a 650-line file and 210 ms more in a 3127-line one, measured on a shared machine.
The `incomplete` flag is unchanged: the list now holds the name, so it is no longer empty.

### Regression targets

- `CompletionSimpleNameTest.simpleName_staleCacheBeforeTheName_offersNamesDeclaredSince` (a local, a field, a parameter, a lambda parameter, an argument, an unclosed argument list, a constructor argument)
- `CompletionSimpleNameTest.simpleName_staleCacheAfterTheName_offersAMemberAddedBelow`
- `CompletionSimpleNameTest.simpleName_staleCache_dropsANameDeletedSince` (above and below the cursor)
- `CompletionSimpleNameTest.simpleName_cacheMatchesUpToTheName_recompilesOnlyWhenStale`

---

## CQ-0068 — A qualified name in a type argument completes nothing — done

**Status: done** (2026-11-24)

**Kind: bug**

**Affects: completion of a qualified type inside `<…>`, in the editor.**

Found while fixing CQ-0066 (2026-11-23), and rechecked on `main` at `1abfa570` through the completion test fixture (`CompletionFixture`).

### Observed behaviour

`class Test { java.util.List<java.util.Ma§> x; }` offers nothing; the same qualifier outside a type argument, `java.util.Ma§ x;`, offers `Map`.
`SentinelInjector`'s receiver scan runs back across the `<`, so the receiver is `java.util.List<java.util`, which resolves to nothing.

### Expected

The members of `java.util` matching `Ma` (`Map`), as for the qualifier outside a type argument.

### Fix

javac parsed the position right; only the receiver text was wrong.
`SentinelInjector` collects the text before the dot back to a space, `;`, `{` or `,`, keeping parentheses balanced, so after a `<` with no space it ran on into the type the argument belongs to: `java.util.List<java.util`.
A `<` the scan meets before any `>` now ends the receiver, as such a `<` cannot be matched: it opens the type argument (`List<java.util.`, `Collections.<java.util.`) or is a comparison (`a<b.` gives `b`).
Past a `>` the `<` may be its match (`Collections.<String>emptyList().`), and past a quote it may sit in a literal or a comment, so there the scan keeps to parentheses as before.
The receiver text stays a text scan, which cannot tell a generic `<…>` from a comparison in general, and member access, qualified type and import completion all read it beside the parse tree, so the rule is kept to the one case that is never a match.

Checked in the editor on Dropwizard: `java.util.List<java.util.Ma` in a field offers `Map` (it offered nothing), while `java.util.Map<String, java.util.concurrent.Con` and `Collections.<String>emptyList().ite` complete as before.

Not covered: past a `>` or a quote the receiver text is unchanged, so an operator written without spaces before the receiver still joins it (`a>b.`, `x<y&&a>b.`, `y=s.`), and a space in a witness or a literal still cuts it (`Collections.<String, Integer>emptyMap().`); `a<=b.` gives `=b`, where it gave `a<=b`, neither of which resolves; the compiled tree resolves these receivers.

### Regression targets

- `SentinelInjectorTest.receiverText_afterUnmatchedAngleBracket_startsThere`
- `SentinelInjectorTest.receiverText_holdingAngleBrackets_keepsThemWhole`
- `SentinelInjectorTest.receiverText_pastQuote_ignoresAngleBrackets`
- `CompletionTypeReferenceTest.typeArgument_qualifiedName_completesQualifierMembers`

---

## CQ-0069 — A method call's explicit type argument completes as an expression — done

**Status: done** (2026-11-23)

**Kind: bug**

**Affects: completion inside `<…>` before a method name (`Collections.<T>emptyList()`), in the editor.**

Found while fixing CQ-0066 (2026-11-23), and rechecked on `main` at `1abfa570` through the completion test fixture (`CompletionFixture`).

### Observed behaviour

`java.util.Collections.<i§>emptyList()` offers `if`, `instanceof` and `int`, the keywords of an expression, and no type.
The sentinel is parsed as a simple name in an expression, not as a type reference, so the type-argument role of CQ-0066 does not reach it.

### Expected

Reference types, as in any other type argument (`Integer` here), and no keyword or primitive.

### Fix

`SentinelParser.inferTypeReferenceRole` gave the type-argument role only to the arguments of a parameterized type, so the type arguments a call carries (`Collections.<T>emptyList()`, `this.<T>id(x)`, `new <T>Foo()`), which javac keeps on the method invocation, the `new` or the method reference (`String::<T>valueOf`), fell through to an expression.
They now take the same role, so they complete as any type argument does: reference types, the file's own types and the type variables in scope, with no keyword or primitive.
The call's ordinary arguments are untouched.

Checked in the editor on Dropwizard's `CachingAuthorizer<P>`: in a method body, `Collections.<i` offers reference types where it offered `if`, `instanceof` and `int`, and `Collections.<P` offers the type variable `P` first, which CQ-0070 left out of a call's type argument.

Not covered:

- a call's type argument still being typed, with no `>` or method name after it (`Collections.<i`), completes nothing, before and after this fix: javac's recovery leaves the sentinel out of the tree, though it keeps it after `this.<`, `new <` and `String::<`;
- a qualified name there (`Collections.<java.util.Ma`) still completed nothing; CQ-0068 has since fixed it.

### Regression targets

- `CompletionTypeReferenceTest.typeArgument_anyPosition_suggestsAnyReferenceType`
- `CompletionTypeReferenceTest.typeArgument_anyPosition_offersNoKeywordPrimitiveOrOverride`

---

## CQ-0072 — With an empty prefix, a completion list that leaves out the type index is marked complete — done

**Status: done** (2026-11-23)

**Kind: bug**

**Affects: completion invoked with no prefix where a type may follow (a type position such as `Comparable<§>` or `implements §`, a statement start, a `Class`-valued annotation argument) and then narrowed by typing, in the editor.**

Found while fixing CQ-0066 (2026-11-23), and rechecked on `main` at `1abfa570` through the completion test fixture (`CompletionFixture`); the statement start and the annotation argument were found by the fix's review.

### Observed behaviour

In `class Test implements Comparable<§> {}`, the result is `isIncomplete: false`, while with a prefix (`Comparable<Str§>`) it is `true`.
With no prefix, the type index is not queried, so the list holds only `java.lang` and the file's own types (109 items on a Lathe source file, no `Duration`), yet it was marked complete.
blink.cmp (1.10.2) keeps a complete list for the rest of the completion session and narrows it as the user types, without asking again (its `lsp/cache.lua` and `provider/list.lua`, read, not run in an editor): invoked by hand right after `<`, then `Dur` typed, it would show nothing, though the server offers `Duration` for `<Dur`.
Typing `<Dur` without invoking by hand was not affected: completion opens at `D` and asks with a prefix.

The same held at a statement start (`void m() { § }`, where only a capitalised prefix brings in the index) and in a `Class`-valued annotation argument (`@ExtendWith(§)`), which dropped the type list's mark when it merged the list with the value keywords; that one also dropped it with a prefix, when the index list was cut at its limit.

### Fix

Wherever the type index is left out for an empty prefix, the list is marked incomplete, so a client asks again as the user types: the shared type lookup (every type position, including `new §`, which had its own copy of the rule), a statement start, and a `Class`-valued annotation argument, which now keeps the type list's mark.
Without a type index there is nothing to ask again for, and the list stays complete; a member access (`s.§`) does too.

### Not covered

- In a file of the default package, a `Class`-valued annotation argument with no prefix offers nothing: the annotation's element type is not resolved there.

### Regression targets

- `CompletionTypeIndexTest.typeArgument_emptyPrefix_returnsIncompleteWithoutIndexTypes`
- `CompletionTypeIndexTest.implementsClause_emptyPrefix_returnsIncomplete`
- `CompletionTypeIndexTest.statementStart_emptyPrefix_returnsIncomplete`
- `CompletionTypeIndexTest.annotationValue_classTypedElementEmptyPrefix_returnsIncomplete`
- `CompletionTypeIndexTest.typeArgument_emptyPrefixWithoutTypeIndex_returnsComplete`
- `CompletionTypeIndexTest.memberAccess_emptyPrefix_returnsComplete`
- `CompletionTypeIndexTest.constructorCall_emptyPrefixWithoutExpectedType_returnsIncomplete`
- `CompletionTypeIndexTest.constructorCall_emptyPrefixExpectedType_ranksExpectedTypeFirst`

---

## CQ-0073 — A type declared in the file is not offered after `extends`, after `implements` or in a cast — done

**Status: done** (2026-11-23)

**Kind: bug**

**Affects: type completion outside a statement and outside type arguments, for a type the workspace type index has not seen yet (a new or not yet compiled type), in the editor.**

Found while fixing CQ-0066 (2026-11-23), and rechecked on `main` at `1abfa570` through the completion test fixture (`CompletionFixture`).

### Observed behaviour

`class Test implements Lis§ {} interface Listener {}` offers nothing; `class Test extends Bas§ {} class Base {}` offers only `BaseVirtualThread`; `class Test { static class Inner {} Object o = (Inn§) null; }` offers nothing.
At a field's type, `Inn§ x;` offers `Inner`: only statement-context type positions, constructors and (since CQ-0066) type arguments merge the file's own types.
A compiled type reaches these positions through the type index, so the gap shows while the type is new.

### Expected

The file's own types at every type position, filtered by the position's role (interfaces after `implements`, extendable classes after `extends`).

### Fix

The gap was wider than filed: a method parameter's type, a record component's, a lambda parameter's and `throws` missed the file's own types too, while a field's, a local's and a return type had them.
Those positions complete through the mixed simple-name path, which merges the file's types; the others go to `TypeReferenceCompleter.completeTypeReference`, which merged them only in a type argument (CQ-0066).
It now merges them at every type reference, as it does `java.lang`'s, filtered by the position's role as before: interfaces after `implements`, a class that is not final after `extends`, a `Throwable` after `throws`.
After `extends` or `implements`, javac rejects the type being declared, any type nested in it, and any subtype of it or type nested in one (cyclic inheritance), and a sealed type that does not permit it, so those are left out, from the file and from the type index alike.
They are compared by qualified name, so a type of the same simple name elsewhere stays.
The declaring type is taken from the file's last compile only when it carries the name the current parse gives: that compile may predate a type just typed, which then cannot be in the file's types or the index yet; before the file's first compile, only the name is known.
A sealed type of this file with no `permits` clause permits this file's types, but no local class.
A private type of another class, out of reach at most of these positions, is left out too.

Checked in the editor on Dropwizard's `CachingAuthorizer`, with a new nested type on the same line: a parameter's type, a cast, `implements` and `extends` each offer the new type (on `main`, nothing).

Left as they are, both rare:

- a local class, or a member of one, offered as a supertype is not checked for a cycle or a sealed type, as its qualified name cannot be looked up;
- the access check uses the scope of the file's last compile at the cursor, which edits that move the text across top-level classes can shift into another one.

### Regression targets

- `CompletionTypeReferenceTest.inFileType_anyTypePosition_suggested`
- `CompletionTypeReferenceTest.inFileType_invalidAtPosition_notSuggested`
- `CompletionTypeReferenceTest.inFileType_supertypeOfNewNestedType_suggestedFromStaleCompile`

---

## CQ-0076 — A record's instance member gets no name suggestion — done

**Status: done** (2026-11-23)

**Kind: bug**

**Affects: completion of the name in a member declaration in a record body, in the editor.**

Found while fixing CQ-0065 (2026-11-22), and rechecked on `main` at `1abfa570` through the completion test fixture (`CompletionFixture`).

### Observed behaviour

`String §` suggests the name `string` in a class body, for a record's `static` member and for a local; in a record body (`record Test(int x) { String § }`) it suggests nothing.
The member-stub parse of CQ-0065 takes the sentinel only as a stub's return type, and here it is the stub's name.

### Expected

The same name suggestion as in a class body.

### Fix

At a member start the injection ends the sentinel with a semicolon, so `String §` reads as the instance field `String __LATHE_SENTINEL__;`, which javac's parser drops from a record body.
CQ-0065's retry turns the sentinel into a method stub's return type (`String __LATHE_SENTINEL__ __latheMember() {}`), which javac drops too where the sentinel is the member's name.
Where that retry finds nothing, a second stub makes the sentinel the method's name (`String __LATHE_SENTINEL__() {}`), which javac keeps in a record body with any modifiers and type, checked on JDK 17 and 26.
`SentinelParser.parseMemberStub` takes it only as a member of a record body, and only after a type that a field could have: after `void` or a type parameter the name can only be a method's, for which a class body offers no name either.
It classifies the slot as the name of a declaration of the method's return type, as a field's name is classified, so completion answers as in a class body.
At a type position the name stub reads as a constructor and loses the sentinel, so the two stubs never both answer: positions that completed before parse as they did.
The third parse runs only where the first two found no sentinel.

Checked in the editor on the EqualsVerifier clone, in the record `PackageScanOptions.MustExtend`: `String §` offers `string`, where `main` offered nothing, and `private Class<?> §` offers `value`, as in the enclosing class; `void §` offers nothing, as in a class, and `public St` still completes the type (`String`, 50 items).

Not covered: the suggestion is labelled a field name, as in a class body, though in a record body a member that is not `static` can only be a method.

### Regression targets

- `CompletionDeclarationNameTest.declarationName_recordMemberSlot_offersNamesAsInAClassBody`
- `SentinelParserTest.parseMemberStub_recordMemberName_findsTheNameSlot`
- `SentinelParserTest.parseMemberStub_nameStubAtTypePositionOrOutsideRecordBody_answersNothing`

---

## CQ-0070 — A type variable is never offered as a type — done

**Status: done** (2026-11-23)

**Kind: bug**

**Affects: completion of a type wherever a type variable is in scope, in the editor.**

Found while fixing CQ-0066 (2026-11-23), and rechecked on `main` at `1abfa570` through the completion test fixture (`CompletionFixture`).

### Observed behaviour

In `class Box<Item> { It§ x; }` and `class Box<Item> { java.util.List<It§> items; }`, `Item` is not offered (only `Iterable`); a method's own type variable is not either (`<Elem> void m() { El§ x; }` offers nothing).
Type completion merges the type index, `java.lang` and the file's class declarations, none of which holds a type variable.

### Expected

The class's and the method's type variables in scope, at every type position (a field, a local, a parameter, a type argument, a bound).

### Fix

No source of type candidates held type variables: the type index, `java.lang` and the file's class declarations hold only classes.
`SentinelParser` now collects the type variables visible at the cursor from the parse tree, walking outwards: a method adds its own, and a class its own; a `static` method, field or initializer hides its class's, as does an interface's field, which is static; a static type (a static member class, a member of an interface, every interface, enum and record, a local record) sees none declared around it, while an inner, local or anonymous class sees them; an inner type variable hides an outer one of the same name.
It records them in `ParsedSentinel` only where a type variable can stand: a type written in an ordinary position, a type argument or bound, or the start of a statement in a block or a `case 1:` group that becomes a declaration (`T value`).
Not before `.class`, in `instanceof` (its operand or a pattern's type), in an initializer or argument, after `new`, as a supertype, permitted subclass or annotation, as a catch parameter's type, or as a switch rule's body.
The type completer and the simple-name completer add them as `TypeParameter` items, and leave out a type of the same name, which the type variable hides.

Checked in the editor on Dropwizard's `CachingAuthorizer<P extends Principal>`: `P` is offered first (it was not offered) for a field's type, a type argument, a statement start, a local's type and a cast; not for a `static` field, nor in an initializer.

Left as they are:

- after `throws`, no type variable is offered, though one bounded by `Throwable` may stand there (`<E extends Exception> void m() throws E`): the parse sees a bound only as a name, so it cannot tell which;
- a method call's explicit type argument (`this.<T>m()`) gets none, as it is completed as an expression (CQ-0069);
- at a statement start that goes on as an expression, `It|.foo()` or `It| = null;`, the type variable is offered too: the injection ends the sentinel there, so the parse cannot see what follows.

### Regression targets

- `CompletionTypeReferenceTest.typeVariable_inScope_suggestedAsType`
- `CompletionTypeReferenceTest.typeVariable_staticContextOrExpression_notSuggested`

---

## CQ-0075 — A record's `toString`, `equals` and `hashCode` are not offered as override stubs — done

**Status: done** (2026-11-23)

**Kind: bug**

**Affects: override completion in a record body, in the editor.**

Found while fixing CQ-0065 (2026-11-22), and rechecked on `main` at `1abfa570` through the completion test fixture (`CompletionFixture`).

### Observed behaviour

In a class body, `toS§` offers the `toString` override stub; in a record body (`record Test(int x) { toS§ }`) it offers nothing, nor `hashC§` the `hashCode` stub.
A record has these methods implicitly, so they look like members it already declares; but a record may still declare them explicitly, and doing so is common.

### Expected

The `toString`, `equals` and `hashCode` stubs in a record body, as in a class body, unless the record already declares one explicitly.

### Fix

`OverrideCompletionProvider` offers the enclosing type's inherited methods and leaves out those the type declares itself.
A record's `toString`, `equals` and `hashCode` are implicitly declared by the record unless it declares them (JLS 8.10.3), so they were its own members and left out.
javac enters these implicit members without a source tree, while one the record declares has a tree; checked on JDK 17 and 26.
An implicit one now stands for the `java.lang.Record` method it overrides, which is offered as a stub as any inherited method is; one the record declares is still left out, and an implicit accessor, which overrides nothing, too.

Checked in the editor on the EqualsVerifier clone, in the record `PackageScanOptions.MustExtend`: `toS`, `hashC` and `equ` offer the `toString`, `hashCode` and `equals` stubs, where `main` offered none.

Not covered: an accessor written out explicitly (`public Class<?> type()`) is not offered as a stub either.

### Regression targets

- `CompletionOverrideTest.completion_objectMethodPrefixInRecordBody_offersImplicitMemberStub`
- `CompletionOverrideTest.completion_recordDeclaringToStringOrAccessor_offersNoStubForIt`

---

## CQ-0074 — A local declared in a block that has closed is still offered — done

**Status: done** (2026-11-23)

**Kind: bug**

**Affects: completion of simple names in a method body after a block, a loop or an `if` that declared locals, in the editor.**

Found while fixing CQ-0067 (2026-11-22), and rechecked on `main` at `1abfa570` through the completion test fixture (`CompletionFixture`).

### Observed behaviour

`void m() { { int hiddenLocal = 1; } hidden§ }` offers `hiddenLocal`; so do `for (int loopIdx = 0; …) {} loopI§` (`loopIdx`) and `if (b) { int ifLocal = 1; } ifLo§` (`ifLocal`).
Picking one does not compile: the name is out of scope there.
CQ-0067 left out the locals of a lambda, class or method that closed before the cursor; a plain block, a loop and an `if` are not checked the same way.

### Expected

Only the locals whose scope contains the cursor.

### Fix

`SimpleNameProvider` collects the method's locals declared before the cursor and leaves out those of a construct that closed before it; since CQ-0067 only a lambda, a class or a method counted as one.
Now any construct around the local counts (a block, a loop, an `if`, a `try` or `catch`), with two exceptions where Java's scope reaches past it: a local of an old-style `case` group is in scope for the rest of the switch block (JLS 6.3), and a pattern's binding is flow-scoped (`if (!(o instanceof String s)) return;` introduces `s` after the `if`, JLS 6.3.2), so only a block, lambda, class or method ends a binding's scope.

The positions compared are the cached snapshot's, and that snapshot predates the last keystrokes: typing `loopI` just before a loop's `}` puts the cursor past the snapshot's `}`, and the loop's own locals would have been hidden as closed.
So the cursor is first moved into the snapshot's text (`CompletionRequest.snapshotOffset`): the two texts are compared from both ends, and an offset past the changed region shifts by the edit's length.
Inside the region it becomes at most the region's end in the snapshot: for typing, a pure insertion, that is where the edit starts, so a `}` after it counts as still open; with edits both above and below the cursor it is the raw offset, as before, so the locals declared between them stay.

Checked in the editor on the Dropwizard clone (`Duration.parse`), the token typed after the setup had compiled: after `for (int loopIdx …) { }`, `loopI` no longer offers `loopIdx`, nor `hidden` the `hiddenLocal` of a closed `{ }` (both were offered on `main`); inside the loop, `loopI` typed just before its `}` offers `loopIdx`, and `dur` the parameter `duration`.

Not covered: the method around the cursor (`NameLookup.findMethodPath`) and the class members' scope (`ReceiverResolver.resolveScope`) still compare the snapshot's positions with the unmoved cursor; a binding's flow scope is not followed, so `if (o instanceof String s) {} s` still offers `s`, as do an expression statement's binding and an earlier `case String s:`; an enhanced `for`'s variable is offered in its own iterable expression, and a `try`'s resource in its `catch` and `finally`.

### Regression targets

- `CompletionSimpleNameTest.simpleName_afterClosedBlock_itsLocalsAreNotVisible`
- `CompletionSimpleNameTest.simpleName_insideOpenBlock_itsLocalsStayVisible`

---

## CQ-0066 — A class's own name is not offered as a type argument in its own header — done

**Status: done** (2026-11-23)

**Kind: bug**

**Affects: completion in a class or record declaration's `extends` and `implements` clauses.**

Found by the editor exploratory round (2026-11-22, Lathe `0.1.0-SNAPSHOT` at `c0d49de`, the working-tree Neovim client in a headless Neovim, on the Dropwizard `08e290b764`, Helidon `27a692eb0d`, EqualsVerifier `ab54bfce` and jdbi `567155b` clones), and reproduced by hand.

### Observed behaviour

| Header, with the type argument cut to two letters | Offered |
|---|---|
| Dropwizard `public class Duration implements Comparable<Du` | nothing |
| jdbi `public static class Configuration implements JdbiConfig<Co` | 50 types (`ColumnMapper`, `Connection`, …), not `Configuration` |

The supertypes in the same headers complete (`Comparable` rank 5, `Serializable` rank 2, `JdbiConfig` rank 2, EqualsVerifier's `ScanOption` rank 1); only the type being declared is missing, which `Comparable<Self>` and `Builder<Self>` headers need.

### Fix

The class's own name was one case of a wider one: a type argument took the role of the clause around it.
`SentinelParser.inferTypeReferenceRole` walks up from the cursor, and reaching the header through `Comparable<Du>` it gave `Du` the `implements` clause's role, interfaces only, so the class `Duration` was filtered out, as was `String` in `implements Comparable<Str`; after `extends`, an interface type argument was filtered out the same way.
Outside headers, a type argument had the ordinary role, which offers keywords and primitives (`List<i` offered `int` and `interface` in a field or method body, a type parameter's bound `<T extends i` too), and in a header type argument override stubs.

A type argument, a wildcard's bound in it and a type parameter's bound now have their own role, `TYPE_ARGUMENT`: any reference type, the file's own types merged as for a constructor (the type being declared and a nested type, before the index has them), and no keyword, primitive or override stub.
An array's element type within a type argument (`List<int[]>`, `List<? extends int[]>`) has the role `ARRAY_TYPE_ARGUMENT`, the same with the primitives added.
A type parameter's name, a declaration, still completes nothing, and every other type position is unchanged.

Checked in the editor with the fix: Dropwizard's `implements Comparable<Du` offers `Duration` first (5 items, where it had none), jdbi's `implements JdbiConfig<Co` offers `Configuration`, and the supertypes themselves complete as before (`Comparable` rank 5, `Serializable` and `JdbiConfig` rank 2); in a method body, `List<i` offers reference types without `int`, and `Map<String, ? extends Dur` offers `Duration`.

Left as they were, each its own fix:

- a qualified name in a type argument (`List<java.util.Ma`) completes nothing, as the injection's receiver text runs back across the `<` (`java.util.List<java.util`);
- a method call's explicit type argument (`Collections.<i>emptyList()`) is completed as an expression, keywords included, not as a type;
- a type variable (`T`, a class's `Item`) is offered at no type position, a type argument or any other;
- a type parameter's second bound (`<T extends Number & Ser`) offers classes too, where only an interface may stand;
- outside type arguments, a type declared in the file and not yet compiled is not offered where the position is no statement (after `extends` or `implements`, in a cast), as the index does not have it yet.

### Regression targets

- `CompletionTypeReferenceTest.typeArgument_anyPosition_suggestsAnyReferenceType`
- `CompletionTypeReferenceTest.typeArgument_anyPosition_offersNoKeywordPrimitiveOrOverride`
- `CompletionTypeReferenceTest.typeArgument_arrayElementType_suggestsPrimitivesOnly`
- `CompletionTypeReferenceTest.typeParameterName_declaredName_offersNothing`

---

## CQ-0065 — In a record, a member's type completes only when the member is `static` — done

**Status: done** (2026-11-22)

**Kind: bug**

**Affects: completion of the type at the start of a member declaration in a record body (top-level or nested), in the editor.**

Found by the editor exploratory round (2026-11-22, Lathe `0.1.0-SNAPSHOT` at `c0d49de`, the working-tree Neovim client in a headless Neovim, on the Dropwizard `08e290b764`, Helidon `27a692eb0d`, EqualsVerifier `ab54bfce` and jdbi `567155b` clones), and reproduced by hand.

### Observed behaviour

In EqualsVerifier's nested record `PackageScanOptions.MustExtend`, with `St` typed:

| Member typed in the record body | Items |
|---|---|
| `public static St probeStaticMethod() { return null; }` | 50 (`String`, `StringBuilder`, …) |
| `static St` | 50 |
| `public St probeMethod() { return null; }` (an instance method, legal in a record) | 0 |
| `private St probeInstanceField;` | 0 |
| `public St`, `St`, `St probe() { return null; }` | 0 each |
| the same instance method in the enclosing class | 50 |

The same in Helidon's records `Http2FrameData` (every member position, `Ht`, `St`, even the full `Http2FrameData`) and `CodegenScope` (`private static St` completes, a bare `St` does not).
Inside a record's method body, locals complete as anywhere (`Ht[] local` offers `Http2FrameData` and 47 more).
Instance methods in records (helpers, accessors written out) are common.


### Fix

Completion injects a sentinel name at the cursor, and at a member start `SentinelInjector` ends it with a semicolon: `St` becomes `__LATHE_SENTINEL__;` and `public St helper() {` becomes `public __LATHE_SENTINEL__; helper() {`, both an instance field to javac's parser.
In a record body the parser drops a member it reads as an instance field ("field declaration must be static"), so the sentinel was not in the tree and completion answered nothing; `static` made it a static field, which a record keeps.
Checked on javac's parse tree: in a record, `__LATHE_SENTINEL__;`, `public __LATHE_SENTINEL__; helper() {}` and `private __LATHE_SENTINEL__ f;` leave no sentinel, while `static __LATHE_SENTINEL__;` and `public __LATHE_SENTINEL__ helper() {}` keep it; in a class all five keep it.

Where the injection adds that semicolon, it also records where, so the same text with the sentinel as the return type of a method stub (`__LATHE_SENTINEL__ __latheMember() {}`) can be built when needed.
`CompletionEngine` parses the stub only when the first parse gave no usable sentinel, and `SentinelParser.parseMemberStub` takes it only where the stub is a member of a record body, so a stub that landed in a block or in another type never answers; everything after the parse reads the injection that was parsed.
The stub is no enclosing method, so the slot keeps its class-body keywords and override stubs.
The first parse is unchanged: positions that completed before parse exactly as they did.

Checked in the editor with the fix: in Helidon's `Http2FrameData`, `public St`, `St` and `public Ht[] probeA(int size) { … }` complete (48 to 50 items, where they had none); in EqualsVerifier's `PackageScanOptions.MustExtend`, `public St probeMethod() { … }`, `St` and `public St` do; the `static` and class cases are unchanged.
An instance field in a record (`Ht probeAfterMethod;`) still completes nothing: javac drops it as it would without the sentinel, and a record cannot declare one.
A record's implicit `toString()`, `equals()` and `hashCode()` are not offered as override stubs, as members the record already has; the other overridable methods are.
A member's name in a record (`String na`) still completes nothing: there the stub holds the sentinel as its name, not its return type, and is not taken.

### Regression targets

- `CompletionTypeReferenceTest.memberType_inRecordBody_completesTheInstanceMemberType`
- `CompletionKeywordAndNoSlotTest.recordBody_modifierPrefix_suggestsMatchingModifier`
- `CompletionOverrideTest.completion_methodPrefixInRecordBody_offersOverrideStub`
- `SentinelParserTest.parseMemberStub_recordMemberStart_findsTheSentinel`
- `SentinelParserTest.parseMemberStub_classMemberStartOrRecordMethodBody_answersNothing`

---

## CQ-0067 — Inside an anonymous class's method, local variables do not complete — done

**Status: done** (2026-11-22)

**Kind: bug**

**Affects: completion of simple names in the methods of anonymous and local classes declared in a method body, in the editor.**

Found while fixing CQ-0064 (2026-11-22, Lathe `0.1.0-SNAPSHOT` at `e95458f6`), and reproduced in the editor on the Dropwizard clone (`08e290b764`).

### Observed behaviour

In Dropwizard's `Duration.parse(String duration)`, with a local `matcher` declared above:

| Typed | Offered |
|---|---|
| `Object o = new Object() { String text() { return du` | nothing |
| `Runnable r = new Runnable() { public void run() { String own = ""; String s = ow` | nothing |
| `Runnable r = new Runnable() { public void run() { mat` | nothing |
| `Runnable r = new Runnable() { public void run() { Object x = DURA` | `DURATION_PATTERN` and the `Duration` types (fields and types completed) |
| `Runnable r = () -> { String s = du` | `duration` |

So inside an anonymous class in a method, neither its own method's locals nor the enclosing method's parameters and locals completed; a lambda in the same place had them.
And after an anonymous class closed, its method's locals were offered to the code that followed it in the enclosing method.

### Fix

Three causes, in the completion's local lookup.
`NameLookup.findMethodPath`, asked for the method around the cursor, stopped at a method of another name and never looked inside it, so a method of a class in a method body was not found.
It compared the method's direct class with the name the completion site gives, which `SentinelParser` takes from the nearest named class, past anonymous ones; it now compares the nearest named class too, and the innermost matching method around the cursor wins.
`SimpleNameProvider` collected only that method's parameters and locals; it now also adds each enclosing method's, inner names first, so a shadowing one wins.
A local record, enum or interface is static (JLS 14.3), so the walk outwards stops there; and with no method around the cursor (a stale snapshot), only a member method, not a nested one, is taken as the fallback, as before.
A local inside a class or method that closed before the cursor is left out, as one inside a closed lambda already was.

Checked in the editor on Dropwizard with the fix: the three rows above offer `duration`, `own` and `matcher`; the field and the lambda rows are unchanged.

Left as they were: a field of the anonymous class (declared or inherited) does not yet shadow an enclosing local of the same name, which completion lists first; and a local of an ordinary block or loop that closed before the cursor is still offered, in any method.

### Regression targets

- `CompletionSimpleNameTest.simpleName_insideAnonymousClassInMethod_completesItsOwnAndTheEnclosingLocals`
- `CompletionSimpleNameTest.simpleName_afterAnonymousClassCloses_itsLocalsAreNotVisible`
- `CompletionSimpleNameTest.simpleName_insideLocalRecord_enclosingLocalsAreNotVisible`

---

## CQ-0064 — Inside a `throw` statement, a constructor's arguments complete only exception types — done

**Status: done** (2026-11-22)

**Kind: bug**

**Affects: completion in the editor, anywhere in the arguments of the thrown expression.**

Found by the editor exploratory round (2026-11-22, Lathe `0.1.0-SNAPSHOT` at `c0d49de`, the working-tree Neovim client in a headless Neovim, on the Dropwizard `08e290b764`, Helidon `27a692eb0d`, EqualsVerifier `ab54bfce` and jdbi `567155b` clones), and reproduced by hand.

### Observed behaviour

In Dropwizard's `Duration.parse(String duration)`, with `du` typed:

| Line typed | Offered |
|---|---|
| `throw new IllegalArgumentException("Invalid duration: " + du` | `DuplicateFormatFlagsException`, `DuplicateRequestException` |
| `throw new IllegalArgumentException(du` | the same two |
| `Object p = new IllegalArgumentException("Invalid duration: " + du` | `duration` |
| `String s = "x" + du` | `duration` |

So an exception's message cannot be built with completion: the parameter, locals and fields are missing, and only throwable types are offered.
The same in jdbi: `throw new IllegalStateException(Sneaky.th(toThrow))` answers nothing, where `Object o = Sneaky.th(toThrow)` offers `throwAnyway`.
`throw Sneaky.th(toThrow)` answers nothing too, while `throw Sneaky.` offers `throwAnyway` (it returns jdbi's `DummyException`): the same cause, as the fix shows.

### Fix

The completion context, not the expected value, was wrong.
`SentinelParser.inferTypeReferenceRole` walks up from the cursor, and at a `throw` whose expression held the cursor it took the cursor for the type a thrown `new` constructs (`TypeReferenceRole.THROWS`), so only throwable types were completed: anywhere in the thrown constructor's arguments, and in a thrown method call.
The walk now keeps the `THROWS` role for the type a thrown `new` constructs alone: entering a `new` through its arguments or anonymous body, or climbing through any call, member select or lambda above it, clears it, while parentheses, a conditional and javac's recovery tree around `throw new` keep it; so a `new` inside an argument, or inside a thrown call (`throw wrap(new StringBuilder())`), is an ordinary type again.
`ExpectedValueResolver.visitThrow`'s `Throwable` only ranks throwables first, as the existing tests expect, and is unchanged.

Checked in the editor on Dropwizard and jdbi with the fix: `throw new IllegalArgumentException("x" + du` and `(du` offer `duration`, `throw Sneaky.th(toThrow)` and `throw new IllegalStateException(Sneaky.th(toThrow))` offer `throwAnyway`, and `throw new Ill` still offers only exception types.

### Regression targets

- `CompletionSimpleNameTest.throwStatement_constructorArgument_completesValuesNotOnlyThrowables`
- `CompletionSimpleNameTest.throwStatement_newInsideConstructorArgument_completesAnyType`
- `CompletionSimpleNameTest.throwStatement_newInsideThrownCallArgument_completesAnyType`
- `CompletionSimpleNameTest.throwStatement_callInsideConstructorArgument_completesTheMethod`
- `CompletionSimpleNameTest.throwStatement_staticCallReturningAThrowable_completesTheMethod`

---

## CQ-0062 — Simple-name completion offers an enclosing class's methods the simple name cannot reach — done

**Status: done** (2026-11-21, #430)

**Kind: bug**

**Value: low — Size: S**

### Observed behaviour

Reproduced on JDK 21, where a class file's parameter types lack the type-use annotations a source's carry.
In a class extending `ArrayList<String>`, give an inner class a method `remove(@Mark Object value)`, where `Mark` is a type-use annotation.
Completing `remove§("")` inside the inner class offers its own method and the enclosing class's inherited `ArrayList.remove(Object)` and `remove(int)`.
The duplicate came from keying methods on their parameters' text, which the annotation changes.

The enclosing class's methods should not be offered at all: Java searches the innermost class that has a method of the name and no further out (JLS 15.12.1), so `remove(0)` there binds to the inner class's `remove(Object)`.
A statically imported method of the same name is hidden the same way.
Review of the first fix also found an inner `add(@Mark String)` offered beside the inherited `add(String)`, and two statically imported overloads merged when one mentioned a missing type outside its type arguments.

### Fix

Methods are told apart as javac does, never by the text of their types: an enclosing class's methods and static imports are left out when a nearer class has a method of the name, and the same method reached by two static imports is offered once.

### Regression targets

- `CompletionSimpleNameTest.simpleName_innerClassDeclaresTheName_onlyItsOverloadsOffered`
- `CompletionSimpleNameTest.simpleName_innerMethodBesideInheritedGenericOne_onlyTheInnerOffered`
- `CompletionSimpleNameTest.simpleName_classDeclaresTheName_staticImportOfItHidden`
- `CompletionSimpleNameTest.simpleName_importedOverloadMentionsAMissingTypeDeeply_keepsBoth`
- `CompletionSimpleNameTest.simpleName_staticImportsWithDifferentTypeArguments_keepsBothMethods`
- `CompletionSimpleNameTest.simpleName_sameMethodImportedTwice_offeredOnce`
- `CompletionSimpleNameTest.simpleName_overrideOfGenericSuperclassMethod_offeredOnce`
---

## CQ-0063 — The first completion in a module not analysed yet answers empty and complete — not a Lathe bug

**Status: non-goal (not a Lathe bug)** (2026-11-17)

**Kind: bug**

**Affects: nothing in Lathe; a test driver calling `client:request_sync(…, 0)` in Neovim.**

Found by the editor exploratory round on jdbi (2026-11-17): the first completion after opening `TestJdbi.java` and typing `h.create` answered no items, `isIncomplete=false`, logging `[completion] parsed valid=false sentinelCtx=null receiver=|null|`.

### Resolution

Reproduced with the round's own driver (`comp4.lua`) on jdbi.
Neovim's debug LSP log shows the client sent `textDocument/completion` before the `didChange` holding the typed line, so the server completed the text before the edit (`[sentinel-parse] sentinel moved`) and answered correctly for it.
The driver's helper passed buffer `0` to `request_sync`; Neovim 0.12's `Client:request` flushes pending changes for the buffer number it is given before resolving `0` to the current buffer, so it flushed none, and the edit went out when the 150 ms debounce fired.
Passing the real buffer number, the same driver's first completion answers 9 items while the module's first analysis is still running (about 1.8 s).
blink.cmp and `vim.lsp.completion` pass a real buffer number, and Lathe's Neovim client requests with none (flushing every buffer) or a real one.
On Dropwizard, completion right after opening, whole-text or keystroke by keystroke, answers from the first request.

### Regression targets

None: no Lathe change.

---

## CQ-0061 — While typing, a `switch` case label offers no enum constants — done

**Status: done.**

**Value: high — Size: M**

**Affects: the editor.**

Found by the pre-release test rounds (2026-10-29/30) on Dropwizard.

### Observed behaviour

`switch (unit) { case MIL` offers nothing; a bare `case ` offers unrelated JDK classes instead of `TimeUnit`'s constants.
0.1.24 behaves the same.

Case-label completion itself works (archived [CQ-0006](#cq-0006--enum-switch-case-labels-do-not-use-the-selector-enum-type)):
on a fresh compile both cases offer `TimeUnit`'s constants, and `CompletionSimpleNameTest.simpleName_switchCaseLabel_suggestsEnumConstants` covers it.
What fails is the while-typing path, which completes from the last clean analysis (`CompletionFixture.completeWithCache`):
a bare `case ` offers the in-scope names and `Object`'s methods, and `case MIL` offers nothing.
The existing tests only exercise the fresh compile.

### Resolution

The case label finds its enum through the switch selector, read from the tree at the cursor offset;
the cached tree is the text before the edit, so that offset no longer falls inside the `switch`.
When the cache differs from the current text before the token being typed, the case label is now attributed
from a recompile of the current text, the same staleness check member access already uses,
and the recompile is cached as member access caches its own, so the next keystroke reuses it.

### Regression targets

`CompletionSimpleNameTest.simpleName_switchCaseLabelWhileTyping_suggestsEnumConstantsOfTheSelectorOnly`.

---

## CQ-0060 — No completions after `::` (method reference) — done

**Status: done — Target: next.**
Tier: basic. Failure mode: missing-candidate.

Typing `Type::`, `expr::`, or `this::` returned no completions: `SentinelInjector` did not recognise
`::` and `SentinelParser.SentinelFinder` had no `visitMemberReference`, so the sentinel after `::` was
never found and the parse was invalid.

**Resolution.** The `::` sentinel is recognised as `SentinelContext.MEMBER_REFERENCE`
(`SentinelInjector` + `SentinelParser.visitMemberReference`). Layer 1 offers the receiver's members via
`MemberAccessCompleter.methodReferenceMembers` — static+instance for a type qualifier, instance-only for
an expression/`this`/`super` qualifier, plus a `Type::new` `constructorReference`; candidates insert the
bare name as `CompletionItemKind.Reference` (no auto-parens). Layer 2 adds SAM-aware arity ranking
(`TypeResolver.methodReferenceSamArity`, `CandidateGenerator.methodReferenceSortKey`). Durable design:
[lathe-method-reference-completion.md](../done/lathe-method-reference-completion.md).

Regression: `CompletionMethodReferenceTest`.

## CQ-0002 — Method-reference completion returns no candidates — done (duplicate of CQ-0060)

**Status: done (duplicate of CQ-0060).**

The original report proposed a future method-reference completion implementation and was archived with an incorrect `accepted` status.
The implementation shipped under CQ-0060: type receivers offer static and instance methods plus constructor references, expression receivers offer instance methods, and accepted items insert bare names without parentheses.
SAM arity affects ranking; this closure does not claim full generic functional-interface compatibility filtering or array-constructor completion.
See [Method Reference Completion](../done/lathe-method-reference-completion.md).

### Regression targets

- `CompletionMethodReferenceTest.methodReference_typeReceiver_offersStaticAndInstanceMethodsButNoFields`
- `CompletionMethodReferenceTest.methodReference_instanceReceiver_offersInstanceMethodsOnly`
- `CompletionMethodReferenceTest.methodReference_thisReceiver_offersEnclosingInstanceMethodsOnly`
- `CompletionMethodReferenceTest.methodReference_typeReceiver_offersNewConstructorReference`
- `CompletionMethodReferenceTest.methodReference_argumentSlot_ranksArityCompatibleAboveIncompatible`
- `CompletionMethodReferenceTest.methodReference_accepted_insertsBareNameWithoutParens`

---

## CQ-0057 — Nested types are missing from type completion and auto-import

ID: CQ-0057
Status: done — the class-file scanner now marks nested public types as name candidates
(`ClassFileTypeScanner.toEntry`), guarding out only synthetic anonymous/local classes (simple name
starting with a digit). They flow through every shard, so `Map.Entry` / `HttpClient.Version` complete
by simple name and auto-import via their canonical dotted name. Shard `SCHEMA_VERSION` bumped to force
a re-scan.
Target: backlog
Tier: assistive
Failure mode: missing-candidate
Owner component: WorkspaceTypeIndex / type-index shard builder

Observed behaviour:
Completing a type by its simple name never offers a nested (inner) type — only top-level types.
Typing `Version` offers `com.fasterxml.jackson.core.Version` and other top-level `Version*` types but
**not** `java.net.http.HttpClient.Version` (a nested enum), so the user is pushed to the wrong type.
The gap is general: completing `Entry` returns zero candidates — `java.util.Map.Entry` is never
offered. It also means no missing-import quick fix is offered for a nested type referenced by simple
name.

Probe command:
```bash
# in a workspace with a loaded type index, complete a bare nested-type simple name
python3 - <<'PY'
import sys; sys.path.insert(0, "dev")
from lsp import LatheClient, find_workspace_root
# open any Java file, insert `Entry ee = null;` in a method body, complete after `Entry`
# → expect java.util.Map.Entry; actual: no nested types offered
PY
```

Cursor context:
```java
Version httpVersion = ...   // expect java.net.http.HttpClient.Version, offered only jackson Version
Map.Entry<K, V> e = ...     // typing `Entry` offers nothing
```

IntelliJ or JDT behavior:
Simple-name completion and auto-import include nested types (`Map.Entry`, `HttpClient.Version`,
`Thread.State`, nested builders/enums), usually shown as `Entry (java.util.Map)`.

Lathe behavior:
The type index holds only top-level types; nested types are not indexed, so they are absent from
type-name completion and from the missing-import quick fix.

Expected Lathe behavior:
Nested types are indexed (as `Outer.Inner`, displayed with the enclosing type) across the dependency,
JDK, and reactor shards, so they complete by simple name and auto-import correctly.

### Current verification

`CompletionTypeIndexTest.typeIndex_nestedType_completesBySimpleNameAndImportsCanonicalName` covers nested-type completion and its import edit.
The separate missing-import quick fix was CA-19, since done; this closure did not cover that provider.

---

## CQ-0058 — Argument completion does not offer the expected type's constants

ID: CQ-0058
Status: done — any value slot whose expected type is an enum (argument, assignment, return,
equality) now proposes that enum's constants (`enumValueSlotCandidates` in CompletionEngine, was
equality-only). Matching keys on the enum name or the bare constant, and an unreachable enum is
auto-imported by its canonical dotted name.
Target: backlog
Tier: assistive
Failure mode: missing-candidate
Owner component: CompletionEngine (argument / typed-slot completion)

Observed behaviour:
At an argument slot whose parameter is an enum (or a type with well-known constants/static factories),
completion offers only in-scope locals and keywords — not the expected type's constants, and not even
filtered to the expected type. At a `Version` parameter it offered `args` (`String[]`) and a local of
an unrelated type; it did not offer `Version.HTTP_2` (or the `Version` type). Same at an enum
parameter: no `<Enum>.<CONSTANT>` proposals.

Cursor context:
```java
builder.httpVersion(▮)   // expect Version.HTTP_1_1 / Version.HTTP_2; none offered
builder.dialect(▮)       // expect the enum's constants; none offered
```

IntelliJ or JDT behavior:
At such a slot the enum's constants are proposed first (e.g. `Version.HTTP_2`), and locals are filtered
to the expected type.

Lathe behavior:
Argument completion lists in-scope names and keywords without expected-type awareness, so the value
you want is not proposed at the call site (member completion after `Version.` / `Enum.` works fine —
only the proactive proposal at the slot is missing).

Expected Lathe behavior:
When the expected parameter type is an enum (or has public constants/static factories), propose those
constants at the slot, and rank type-matching locals first.

### Current verification

`CompletionArgumentTest.argumentPosition_enumParam_offersQualifiedConstantsByNameOrValue` and
`CompletionArgumentTest.argumentPosition_externalEnumParam_offersConstantsWithImport` cover the delivered enum constants.
The broader non-enum static-factory proposal in the original observation is not claimed by this enum-only closure.

---

## CQ-0055 — `:LatheNew` — scaffold a new type through the server

ID: CQ-0055
Status: done — v3 shipped. A single `:LatheNew` with all Java/Maven semantics in the LSP server
(`lathe.types.create` + the `lathe.workspace.modules` / `lathe.workspace.packages` / `lathe.workspace.context` discovery queries);
the Neovim client is a thin picker shell (Kind → module → package/`＋ New package…` → name) that writes
the server-rendered file. Works anchored (current package preselected) and cold-start (module → package);
main/test comes from the chosen package. The v1/v2 client-side `:LatheNewClass`/`Interface`/`Record`/`Enum`
commands and their in-client Java logic are removed.
Target: M2 (done)
Tier: assistive
Failure mode: missing-affordance
Owner component: split — `lathe-server` (new `workspace/executeCommand` queries + a `lathe.createType`
command that owns placement, skeleton, formatting, caret) and the Neovim client plugin
(`lua/lathe/new.lua`, reduced to the `:LatheNew` command + pickers + file IO).

**Decision (v3 — server owns the Java semantics; supersedes the earlier client-scaffold framing).** The
feature *creates* a new `.java` file — in the right package/directory, with the `package` line and a
named type skeleton — and opens it. Because it is file creation, not a completion item, it sidesteps the
completion "live templates" Non-Goal
([expectations](../done/lathe-completion-expectations.md) § Non-Goals) entirely. The v1/v2
implementation put every Java/Maven decision in the Lua client (regex-parse the `package` line, split on
`/src/main|test/java` markers, split dotted names, emit skeleton syntax); v3 **moves all of that into the
LSP**, per the project's own "no ad-hoc Java in the client" rule and the jdtls / vscode-java reference
architecture (see *Reference* below). The previously-listed completion-snippet and tree-node-targeting
options are **not** pursued; the earlier "editor-agnostic server (Option B) deferred until a second
client" framing is **reversed** — the server owns the Java facts now, because that is where Java
knowledge belongs, not because a second client arrived.

> The `UX flow` / `Placement` / `Skeletons and style` / `v2` sections below document the **implemented
> v1/v2 client-side** behaviour and are retained as history; the **v3 redesign** section is the
> authoritative plan and supersedes them.

### UX flow

The kind is the **command**, so it is never picked — the only prompt is the name:

1. **Choose the kind by command** — `:LatheNewClass` / `:LatheNewInterface` / `:LatheNewRecord` /
   `:LatheNewEnum`.
2. **Name** — pass it as an argument for zero prompts (`:LatheNewClass Foo`, or a dotted
   `:LatheNewClass com.example.sub.Foo`); with no argument the command asks once via
   `vim.ui.input('<kind> name in <dir>: ')`, where `<dir>` is the target directory relative to the
   workspace (`.lathe`) root (so it shows the module / source-root / package path). The name is both
   the file name (`<Name>.java`) and the type name.
3. **Resolve the target directory** from the current buffer (see Placement).
4. **Create + open + format** — write `<dir>/<Name>.java` with the package line and skeleton, open the
   buffer, normalise its style via the on-save formatter (see *Skeletons and style*), then drop the
   cursor in the body (or record component list). Refuse (message, no-op) if the file exists.

### Placement — how the target directory and package are figured out

Resolution order, from the current buffer (no server round-trip in v1):

1. If the current buffer is a **directory buffer** (oil.nvim `oil://…`, or netrw `b:netrw_curdir`) →
   use that directory. (Covers "create a class where I'm browsing.")
2. Else if it is a **`.java` file** → use its directory — i.e. the **same package** as the open file.
3. Else → error cleanly ("open a file or directory inside a source package first").

The **package** for that directory is taken from the open file's `package …;` line when present
(`^%s*package%s+([%w.]+)`), falling back to path-derivation: split the directory on the source-root
marker (`/src/main/java/` or `/src/test/java/`) and dot-join the trailing segments. **main vs test**
follows whichever root the current buffer sits under — no separate prompt.

### Skeletons and style

The scaffold writes a **minimal, valid** skeleton and **defers style to the on-save formatter** rather
than hardcoding indentation or brace placement (one source of style truth; avoids guessing — e.g. GJF
uses 2-space indentation, so a hardcoded 4-space body would be wrong for exactly the users who format):

- If Lathe's Google formatter is enabled — the same gate that wires the BufWritePre hook
  (`formatter == 'google'` and `format_on_save`, `lathe.lua`) — the command runs the identical
  `vim.lsp.buf.format({ bufnr = …, name = 'lathe', async = false })` on the freshly-opened buffer, so
  the scaffold is byte-identical to what a save would produce. The Lathe client attaches to the new
  buffer *asynchronously*, so the command waits briefly for the attach before formatting (only when a
  Lathe client is running) — otherwise the format runs before attach and no-ops ("no matching language
  servers"), which an early implementation hit and a live-editor test caught.
- Otherwise it emits a built-in fallback honouring the buffer's `expandtab` / `shiftwidth`.

Name = the entered `<Name>`; minimal visibility (`public`, no `final`/`sealed`). Rough shape before
formatting (the formatter fixes indentation/spacing):

```java
public class <Name> {
}
public interface <Name> {
}
public enum <Name> {
}
public record <Name>() {
}
```

The file starts with `package <pkg>;` (omitted for the default package). The **caret** is placed
*after* formatting (which can shift lines): the empty body line for class/interface/enum, or inside the
`()` component list for record. Only Lathe's own formatter is invoked (gated on the existing config); a
buffer using a different on-save formatter gets the fallback skeleton and is normalised by its own hook
on first save.

### v2 — dotted-name creation (client-side, implemented)

Accept a **dotted name** (as the command argument or the name prompt): `com.example.sub.Foo` creates
`Foo` in package `com.example.sub`, making the package directories as needed; a bare `Foo` stays
same-package (v1). Done **entirely in the client**, reusing the existing helpers — no server
round-trip, no new LSP command, no cross-language duplication of the create path:

- Split the input on the last dot → `{ package = 'com.example.sub', name = 'Foo' }`; no dot → the bare
  same-package v1 path.
- Resolve the module **source root** from the current context directory by taking the path up to and
  including the `/src/main/java` or `/src/test/java` marker — the same marker `_package_from_dir`
  already keys on (reused, not duplicated). main vs test follows the current buffer.
- Target directory = `sourceRoot/<package-as-path>`, then hand off to the **existing**
  `_write_and_open(dir, package, kind, name)`, which already does `mkdir -p`, write, open, format, and
  caret. So v2 only computes `(dir, package, name)` differently; the file-creation path is unchanged.
- If the current context is not under a `src/main|test/java` root, a package-qualified name cannot be
  placed — warn and bail (a bare name still works same-directory).

### v3 — server-owned semantics, thin `:LatheNew` picker client (shipped)

**Principle.** Every Java/Maven decision — which module, which source root, main vs test, the package,
the skeleton syntax, the formatting, the caret, name validity — is decided by the **LSP server**, never
ad-hoc in the Lua client. The v1/v2 client violated this (it regex-parsed `package` lines, split on
`/src/main|test/java` markers, split dotted names, and emitted Java skeleton strings). v3 moves those
decisions to `lathe-server` and reduces the client to a **thin shell**: one command, pickers, and file
IO. This reverses the earlier "client resolution is reliable enough / Option B deferred" call — a
weaker argument than "Java knowledge has exactly one home, the LSP."

**No client cache — pickers fetch on open (KISS).** An intermediate design cached the workspace model
client-side to feed command-line `<Tab>` completion, which must return candidates *synchronously*. The
objection to querying the LSP there was never the server's speed (a `lathe.modules` read is single-digit
ms); it is that a cmdline `complete=` function can only consume an async reply by **blocking the main
thread** (`request_sync` / `vim.wait`) on a keypress — and because the server is a single worker thread
(`ServerEventLoop`), during active editing that query queues behind compile work, so the *tail* latency
that would freeze the UI is not ms. The fix is to **not block**: every surface is a `vim.ui.select`
picker, which is command-invoked and therefore *fetches then shows* — it calls `lathe.modules` /
`lathe.packages` directly and populates the picker in the callback. This removes the synchronous
constraint **and** the stale-cache problem at once: the picker always reflects the current workspace,
and **no client cache exists anywhere** (no `<Tab>`-completion special case to feed).

**Server surface — lazy, `getChildren`-style queries + a create command.** Modelled on jdtls
(`java.project.getChildren` / `java.getPackageData` / `java.resolvePath`), returning one level at a time
rather than a flat cartesian `module × main/test × package` list — which keeps payloads small and
avoids the documented jdtls memory/latency cliff when a big module's whole package set is materialised
at once:

- `lathe.modules` → the reactor's modules (reactor-relative names).
- `lathe.packages(module)` → the packages in that module, tagged main/test, read from **current disk**
  so a package created since the last build shows up without a rebuild.
- `lathe.resolveContext(uri)` → the `{ module, kind: main|test, package }` a URI belongs to, for the
  picker's preselect (the `resolvePath` analogue).
- `lathe.createType({ module, kind: main|test, package, type: class|interface|record|enum, name })` →
  the slim `{ path, content, caret }`: the server resolves the target path, generates the correct Java
  skeleton, formats it with `JavaFormatter` (the same engine as on-save, so byte-identical), and
  computes the caret offset. **This is Option B, revived on the principle** — not a `WorkspaceEdit`
  (whose resource-operation plumbing the client does not need); the client writes `content` to `path`,
  opens it, and places the caret.

**Client — a thin shell (`:LatheNew` only).** The four `:LatheNewClass`/`Interface`/`Record`/`Enum`
commands are **removed**; a single `:LatheNew` drives the flow. The client keeps *no* Java knowledge —
`_package_from_lines` / `_package_from_dir` / `_source_root` / `_split_qualified` / `_target` /
`_skeleton` / `_caret` are all deleted. It owns only: the command, the `vim.ui.select` / `vim.ui.input`
pickers, writing the returned content, opening the buffer, applying the caret, and error notifications.

**UX flow — Kind → Destination → Name (decided), progressive narrowing, small lists.**

1. **Kind** — a fixed four-item `vim.ui.select` (Class / Interface / Record / Enum). Cannot be inferred,
   so it is always the first pick; it is instant.
2. **Destination** — never a global package list; narrow coarse→fine, each list short:
   - **Module** (`lathe.modules`) — *skipped* when there is exactly one; otherwise the current module
     (from `lathe.resolveContext`) is preselected.
   - **Package** within that module (`lathe.packages(module)`) — existing packages plus a `＋ New
     package…` entry (→ `vim.ui.input` **seeded** with the current/highlighted package + trailing dot,
     e.g. `com.acme.▮`, so a new sub-package is a short extension) and a `⇄ Other module…` escape. The
     current package is preselected when anchored.
   - **main vs test** — inferred, never a standing question: default `main`, infer `test` when anchored
     in a test file, and surface a two-item main/test pick *only* when the chosen module has both roots
     and there is no anchor.
3. **Name** — typed last (`vim.ui.input`); the final Enter is what triggers `lathe.createType` and
   creates the file.

Skip-when-one + preselect-from-context make the common anchored case fast: `:LatheNew` → pick Kind →
Enter (module skipped/preselected) → Enter (current package) → type Name — two real decisions.

**Freshness (fetch-on-open) — behaviour after a workspace update.** Because there is no cache, nothing
has to be invalidated when the workspace changes: the next `:LatheNew` fetches `lathe.modules` /
`lathe.packages` fresh and sees the current state. Refreshing *is* reopening the picker. The two data
kinds update on different clocks, both correct-by-construction:

- **Packages — live immediately.** `lathe.packages(module)` reads **current disk**, so a package you
  (or a `git pull`, or another editor) just created shows up on the next open, *before* any build or
  reload.
- **Modules — after the server reloads.** `lathe.modules` reads the server's loaded registry
  (`workspace.allConfigs()`), which reflects a new module only once the server reloads `workspace.json`.
  That reload is driven by `WorkspaceWatcher` polling `workspace.json` mtime + POM fingerprints after a
  build regenerates the manifest (the existing WS-3/WS-5 sync path) — correct, since a module does not
  exist until built.

Two properties keep this clean rather than racy: **single-worker serialization** — the query runs on the
same `ServerEventLoop` worker as `reload()`, so a fetch issued during/after a reload runs *after* it and
never observes a half-reloaded model; and **self-healing** — the only stale window is between "build
done" and "server reloaded", and since it is fetch-on-open, reopening the picker a moment later (or after
the sync completes) is current. There is no background push: Lathe registers no file watchers
(`didChangeWatchedFiles` is a deliberate no-op), and the picker's fetch-on-open is the sole refresh
point.

**Consequence — creation now requires the server attached.** With all Java logic server-side there is no
offline fallback (that would reintroduce ad-hoc client Java); `:LatheNew` with no Lathe client attached
errors cleanly (`server not attached — creation needs the language server`). This is a behaviour change
from the client-only v1/v2, and is the intended trade: it is a Java operation, so it uses the LSP.

**Reference — this mirrors jdtls / vscode-java, and avoids the nvim-jdtls client-convention trap.**
Eclipse jdtls keeps all Java knowledge server-side behind custom `java.*` `executeCommand` extensions
(`java.project.list`, `java.project.getChildren` / `java.getPackageData` for lazy project→root→package
→type children, `java.resolvePath`, plus server-side templates); vscode-java's "New Java Class" is a
thin wizard over them. Two lessons transfer directly: **lazy children, not flat lists**, and a
**documented perf cliff** when a large module's package set is fetched whole — both argue for the
per-node queries above. Conversely, `nvim-jdtls` ships *no* new-class wizard, so the nvim community uses
`java-helpers.nvim`, which does placement **client-side in Lua** (marker-based package guessing + Lua
templates) — exactly the School-B approach our current `new.lua` falls into and this redesign leaves
behind.

### Scope

- **v1 + v2 (superseded):** the four `:LatheNew*` kind commands with **client-side** resolution and
  skeleton generation. Removed by v3.
- **v3 (shipped):** a single `:LatheNew`; all Java/Maven semantics in `lathe-server` via lazy
  `getChildren`-style queries (`lathe.modules`, `lathe.packages`, `lathe.resolveContext`) plus
  `lathe.createType` (path + skeleton + caret); a Kind → module → package → name picker with
  skip-when-one, context preselect, seeded new-package input, and inferred main/test; fetch-on-open
  freshness; creation requires the server. The client holds no Java knowledge.

### Regression targets

**Server** (`lathe-server`; JUnit, real workspace fixtures — the executeCommand handlers and the
`WorkspaceSession` methods behind them, mirroring the `lathe.instantiations` path):

- `modules_listsReactorModules` (positive) / `modules_singleModule_returnsOne` (edge)
- `packages_listsModulePackagesTaggedMainTest` (positive) /
  `packages_reflectNewlyCreatedDirWithoutRebuild` (positive — server reads current disk) /
  `packages_moduleWithoutTestRoot_returnsMainOnly` (edge)
- `resolveContext_uriUnderMain_returnsModulePackageMain` /
  `resolveContext_uriUnderTest_returnsTest` (positive) /
  `resolveContext_uriOutsideAnyRoot_returnsNull` (negative)
- `createType_generatesFormattedSkeletonWithPathAndCaret` (positive, per kind — path resolved under the
  right root, package dirs implied, content formatted by `JavaFormatter`, caret in body / record
  component list) / `createType_invalidJavaName_rejected` (negative) /
  `createType_existingFile_refuses` (negative)

**Client** (Neovim busted spec `new_spec.lua`; `vim.ui.select` / `vim.ui.input` and the
`workspace/executeCommand` requests stubbed — the client carries no Java logic to unit-test, so the
tests assert flow + IO, not placement):

- `latheNew_kindThenDestinationThenName_createsViaServer` (positive — the three steps issue
  `lathe.createType` and the returned `{path, content, caret}` is written / opened / caret-placed)
- `latheNew_anchored_preselectsCurrentModuleAndPackage` (positive — `resolveContext` result preselects)
- `latheNew_singleModule_skipsModuleStep` (positive — skip-when-one)
- `latheNew_newPackage_seedsInputFromContext` (positive — `＋ New package…` input seeded with the
  current package + dot)
- `latheNew_bothRoots_noAnchor_promptsMainTest` (positive) /
  `latheNew_anchoredTest_infersTest` (positive — no main/test prompt)
- `latheNew_serverNotAttached_errorsCleanly` (negative — creation needs the server)

Verify end-to-end in a live Neovim against the invoker workspace: `:LatheNew` from a cold start creates
a class under a chosen (or new) package and formats it via the running server.

Notes:
Pairs with CQ-0054 (keyword insertion) but is independent of it.

---

## CQ-0059 — Common JDK types were not ranked first in type-name completion — done

**Status: done — Target: next.**
Tier: assistive. Failure mode: bad-ranking.

Type-name completion ranked by a coarse per-**package** tier (`java.lang`=0, a fixed common-platform
set=1, reactor=2, else=3), but "commonness" is a per-**type**, per-**project** property. Live probing on
a large real workspace showed three systematic failures: the blanket `java.lang`/`java.io` boost floated
*rare* types to the top (`Logger`→`java.lang.System.Logger`, `Function`→`java.lang.FunctionalInterface`,
`Stream`→`java.io.StreamTokenizer`); JDK **subpackage** types got the bottom tier
(`java.util.function/stream`, `java.nio.file` lost to reactor + short-FQN deps, e.g. `Path`→reactor
`…Config.Paths`); and test source roots (no JPMS filtering) amplified the noise. No package list can
distinguish `java.lang.String` (ubiquitous) from `java.lang.System.Logger` (rare), or know a project uses
slf4j rather than `java.util.logging`.

**Resolution — usage-frequency ranking.** The primary ranking signal is now the **document frequency of
each type's references across the reactor** (how many reactor classes reference it), computed from the
`CONSTANT_Class` constant-pool entries the class scanner already reads. It subsumes `java.lang` with no
special-casing (`String` is referenced everywhere → ranks first; `System.Logger` ~never → drops) and is
project-specific (slf4j beats JUL). The comparator is
`exact-prefix → usageCount(desc) → originRank tier → shorter-FQN → lexical`; the package tiers survive as
the **cold-start tie-break** for equal (typically zero) counts, so the existing EG-021 tests — whose
synthetic indices carry no usage data — stay green.

Design: constant-pool document frequency from reactor classes; **server-side, in-memory**, aggregated
when the reactor is scanned (startup / reload) and attached via `WorkspaceTypeIndex.withUsageCounts`.
No persisted shard-format change, no `module-info` change, no Maven change, and **no re-sync**: the
references live in the compiled `.lathe/<module>/classes` bytecode the server already scans, so counts
are derived from data that is already on disk. Incremental saves keep the existing counts (refreshed on
a full scan only), so there is zero per-save cost. Performance is negligible: the constant pool is
already parsed (~+10ms on the reactor scan), the count map is <1 MB (~3–5k distinct types), and ranking
adds one O(1) lookup per candidate.

Verified live on the probed workspace — `Logger`→`org.slf4j.Logger`, `Stream`→`java.util.stream.Stream`,
`Function`→`java.util.function.Function`, `Path`→`java.nio.file.Path` all now rank first, with the
previous noise offered but demoted.

Regression targets:
- `ClassFileTypeScannerTest.scanReactorDirectory_aggregatesReferenceCounts_excludingSelfAndArrays`
  (lathe-maven-plugin) — document frequency per type; self and array descriptors excluded.
- `WorkspaceTypeIndexTest.usageCount_reflectsAttachedCounts_andDefaultsToZero`.
- `CompletionTypeRankingTest.completion_typePrefix_usageOutranksColdStartTier` — a reactor-referenced
  subpackage type outranks a zero-usage same-prefix higher-tier type, which is still offered lower; the
  existing EG-021 tier cases (zero usage) stay green.

Shipped across three slices (per-class references + reactor scan in `lathe-core`; aggregation +
`usageCount` in `lathe-server`; the comparator key). Deferred follow-up: a curated ubiquitous-JDK prior
for cold-start subpackage types, only if beta feedback shows it is needed.

## CQ-0054 — Accepted keyword completion inserts the bare keyword with no trailing space

Status: done — Target: M2.

Accepting a keyword that must be followed by a further construct now inserts the keyword plus a
single trailing space (caret after it), matching IntelliJ/JDT — `class `, `interface `, `enum `,
`record `, `void `, `new `, `instanceof `, `return `, modifiers, primitives, etc. Standalone/value
keywords stay bare: `this`, `super`, `true`, `false`, `null`, `break`, `continue` (an editor never
wants `null ;`).

`KeywordProvider.keywordCandidate` sets `insertText` to `keyword + " "` unless the keyword is in a
small `STANDALONE_KEYWORDS` set (bare). This is presentation-only — `name`/`label`/`filterText` stay
the bare lexeme, so filtering and which keywords are legal at a site are unchanged.

A doubled-space guard, `CompletionEditApplier.absorbFollowingSpaceAfterKeyword` (called from
`CompletionEngine` right after `applyReplacementRange`, mirroring `preserveExistingMethodCall`),
handles the case where a space already follows the completed token (`new§ ArrayList`). When exactly
one literal space follows, it grows the keyword item's edit range by one column to overwrite that
space, so the result keeps a single space with the caret after it. Absorbing (rather than stripping
the suffix) stays correct at an empty slot (`cla§ }`, where the name is still to be typed), and only a
literal space is absorbed so a trailing newline is left untouched.

Regression coverage:

- `CompletionKeywordAndNoSlotTest.keyword_requiresFollowingConstruct_insertsTrailingSpace` (positive)
- `CompletionKeywordAndNoSlotTest.keyword_standaloneValue_insertsNoTrailingSpace` (negative)
- `CompletionEditApplierTest.absorbFollowingSpaceAfterKeyword_*` (space absorbed; newline and bare
  standalone keyword left unchanged)

---

## CQ-0053 — Member completion on an array-typed receiver returns nothing

Status: done — Target: M2.

Member completion after `.` on an array-typed receiver (`args.` where `args` is `String[]`) returned
no candidates, though `args[0].` (the element type) and object/`String`/`this` receivers all worked.
Root cause: `CandidateGenerator.proposeMemberAccessCandidates` bailed for any non-`DeclaredType`
receiver, and an array has no `TypeElement` (`Types.asElement` → null) while its `length`/`clone`
members are not modeled as `Element`s.

Fixed with an `ArrayType` branch (public `javax.lang.model` API only): the inherited members are
exactly `java.lang.Object`'s instance methods, enumerated through the existing member filter chain
(now extracted into a shared `membersOf`); `length` is synthesized via `variableCandidate("length",
int)`; and `clone()` — `public` with covariant return `T[]`, so neither the protected `Object.clone`
element nor any other element fits — is synthesized by a new `CandidateFactory.arrayCloneCandidate`.
Verified live: `args.` now offers `length`, `clone() : String[]`, `equals`, `getClass`, `hashCode`,
`toString` (no `wait`/`notify*`, no statics), while `args[0].` is unchanged.

Regression coverage:

- `CompletionMemberAccessTest.memberAccess_arrayReceiver_offersLengthCloneAndObjectMembers`
- `CompletionMemberAccessTest` `arrayElementReceiver` case (element receiver `args[0].` unchanged)

## CQ-0043 — Argument-position type filtering excludes boolean returns but lets other mismatched types through

Status: done — Target: M2.

Re-evaluation on 2026-07-02 found this gap was already resolved by prior completion-ranking work.
Boolean-returning methods are present at non-boolean argument slots and are demoted behind directly
assignable candidates instead of being deleted.
Boolean literals remain filtered for incompatible non-boolean expected types, which matches the
completion expectations.

Regression coverage:

- `CompletionArgumentTest.argumentPosition_referenceTypeParam_booleansDemoted`

---

## CQ-0047 — Member access inside a void-returning lambda body offers void methods

Status: done — Target: M2.

Member-access completion inside a lambda whose functional-interface method returns `void` now treats
the site as a statement context.
This prevents the enclosing argument type, such as `Runnable`, from making completion value-sensitive
and filtering out void-returning receiver methods.

Regression coverage:

- `CompletionMemberAccessTest.memberAccess_insideRunnableLambdaBody_offersVoidMethods`

---

## CQ-0036 — Empty constructor completion returns a complete narrow list

ID: CQ-0036
Status: done
Tier: basic
Failure mode: missing-candidate-after-client-filtering
Owner component: CompletionEngine / TypeIndexCandidateProvider
Discovery: 2026-06-17, Helidon MongoDB client

Project/file:
`/home/ag-libs/git/helidon/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClient.java`

Probe command:
```bash
printf 'inject "final var x = new " min 1\nlog 120\n' \
  | LATHE_DEBUG=1 python3 dev/explore.py \
      /home/ag-libs/git/helidon/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClient.java
```

Control probe:
```bash
printf 'inject "final var x = new Connection" expect Connection min 1\nlog 80\n' \
  | LATHE_DEBUG=1 python3 dev/explore.py \
      /home/ag-libs/git/helidon/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClient.java
```

Expected-type probe:
```bash
printf 'inject "ConnectionString x = new " min 1\nlog 100\n' \
  | LATHE_DEBUG=1 python3 dev/explore.py \
      /home/ag-libs/git/helidon/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClient.java
```

Cursor context:
```java
final var x = new §
final var x = new Connection§
ConnectionString x = new §
```

Observed behavior before the fix:
The direct prefixed request works.
`new Connection§` is parsed as `sentinelCtx=CONSTRUCTOR_CALL`,
queries the type index with prefix `Connection`,
and returns 28 type candidates including:

- `Connection` from `java.sql`
- `ConnectionString` from `com.mongodb`
- `ConnectionId` from `com.mongodb.connection`
- `Connection` from `com.mongodb.internal.connection`

The empty-prefix request was the gap.
`new §` is also parsed as `sentinelCtx=CONSTRUCTOR_CALL`,
but returns only 88 broad java.lang/current-scope candidates and marks the completion list
as complete:

```text
[completion] inject prefix=|| receiver=|null| ctx=STATEMENT hasDot=false
[completion] parsed valid=true sentinelCtx=CONSTRUCTOR_CALL receiver=|null| class=MongoDbClient method=<init> role=CONSTRUCTOR
[completion] ... items=88 reattributed=false
[completion:lsp] ... items=88 incomplete=false
```

The expected-type variant failed the same way.
`ConnectionString x = new §` returns the same 88 broad items,
does not include or rank `ConnectionString` first,
and also marks the result complete:

```text
[completion] inject prefix=|| receiver=|null| ctx=STATEMENT hasDot=false
[completion] parsed valid=true sentinelCtx=CONSTRUCTOR_CALL receiver=|null| class=MongoDbClient method=<init> role=CONSTRUCTOR
[completion] ... items=88 reattributed=false
[completion:lsp] ... items=88 incomplete=false
```

Why this matters:
In an editor,
the user naturally triggers completion after typing `new ` and then continues with `Connection`.
Because the empty-prefix response is marked complete,
the client can filter the cached 88 items instead of asking Lathe again.
`Connection` is not in that initial list,
so the menu can appear to miss `final var x = new Connection§`
even though an explicit fresh request at that final cursor position succeeds.

Expected Lathe behavior:
Constructor-call completion at `new §` must either:

- return an incomplete list so clients retrigger as the prefix grows; or
- include a sufficiently broad type-index-backed candidate set for constructor-call filtering.

When the enclosing expression has an expected type,
Lathe should use it before falling back to broad constructor type discovery.
For `ConnectionString x = new §`,
`ConnectionString` should be the first completion candidate and should carry the same import/replace
behavior as the prefixed type item.

The first slice should prefer `isIncomplete=true` for empty-prefix constructor-call type completion
when there is no useful expected type.
That keeps the response small while making client-side incremental filtering safe.

Accepted edit, if relevant:
The prefixed `Connection` item has a correct replacement range and import edit:

```json
{
  "label": "Connection",
  "detail": "java.sql.Connection",
  "textEdit": {
    "range": {
      "start": { "line": 43, "character": 24 },
      "end": { "line": 43, "character": 34 }
    },
    "newText": "Connection"
  },
  "additionalTextEdits": [
    { "newText": "import java.sql.Connection;\n" }
  ]
}
```

Regression target:
`CompletionTypeIndexTest.constructorCall_emptyPrefixWithoutExpectedType_returnsIncomplete`
`CompletionTypeIndexTest.typeIndex_suggestsIndexedTypeInCodeStatements`
`CompletionTypeIndexTest.constructorCall_emptyPrefixExpectedType_ranksExpectedTypeFirst`

Notes:
This was not a candidate-generation failure for non-empty prefixes.
The bug was the empty-prefix constructor-call response contract with LSP clients.
Fixed by marking broad empty-prefix constructor completions incomplete and by using the semantic
expected type for constructor-call type completion before falling back to broad discovery.

## CQ-0039 — RejectedExecutionException logged as SEVERE at shutdown

ID: CQ-0039
Status: done
Tier: lifecycle
Discovery: 2026-06-17

### Description

When Neovim exits, a `RejectedExecutionException` is logged at SEVERE severity in the client's
LSP log, polluting the user's error output even though the server exits cleanly afterward.

### Observed log

```
[shutdown] shutdown requested
SEVERE  Internal error: Task ... rejected from
  java.util.concurrent.ScheduledThreadPoolExecutor@...[Terminated, pool size = 0,
  active threads = 0, queued tasks = 0, completed tasks = 39]
java.util.concurrent.RejectedExecutionException: Task ... rejected from ...
  at ServerEventLoop.submit(ServerEventLoop.java:37)
  at LatheTextDocumentService.close(LatheTextDocumentService.java:45)
  at LatheLanguageServer.shutdown(LatheLanguageServer.java:80)
```

### Root cause

`LatheTextDocumentService.close()` calls `worker.submit(session::close)` followed by
`worker.close()`.
The `[Terminated]` state on the executor (`completed tasks = 39`) means `executor.shutdownNow()`
was already called before `close()` reached `worker.submit()`.
The only call site for `worker.close()` is inside `LatheTextDocumentService.close()` itself,
so `close()` is most likely being called twice:
the first call completes normally (submits session.close(), joins, then shuts down the executor),
and the second call finds the executor already terminated and throws at `worker.submit()`.

The most probable trigger: the LSP4J `ConcurrentMessageProcessor` dispatches `shutdown`
(a request) and `exit` (a notification) concurrently on its thread pool.
Neovim sends `exit` without waiting for the `shutdown` response,
so both handlers can race.
If `exit()` fires `System.exit(0)` before `shutdown()` completes,
the JVM halt can interrupt the `LatheLanguageServer.shutdown()` path in a way that causes
a second dispatch or re-entry.
An alternative scenario is that the LSP4J framework calls `shutdown()` more than once
under certain client disconnect conditions.

### Impact

A spurious SEVERE log appears in Neovim's LSP log on every normal exit.
Users see it as a server crash rather than a clean shutdown.
The server exits correctly through `exit()` → `System.exit(0)`, so there is no functional data loss.

### Fix area

Two complementary hardening points:

1. **`LatheTextDocumentService.close()` — idempotent guard.**
   Add an `AtomicBoolean closed` field; return immediately on the second call.

2. **`ServerEventLoop.submit()` / `execute()` — graceful rejection.**
   Catch `RejectedExecutionException` when the executor is already shut down and return a
   cancelled future (for `submit`) or silently no-op (for `execute`) instead of propagating.
   This prevents the exception from surfacing to LSP4J as an unhandled SEVERE error
   even if the idempotent guard is the primary fix.

### Regression targets

`SessionHost.close` now owns the idempotent close guard, and `ServerEventLoop.close` owns shutdown delivery.
`ServerEventLoopTest.close_runsLastOnTheLoop_droppingQueuedTasksButCompletingQueuedResults` covers the current shutdown behavior.
The originally proposed duplicate-close methods on `LatheTextDocumentServiceTest` do not exist; they are not evidence of a separately tested service-close case.

---

## CQ-0038 — Private methods falsely marked unused when declared after their callers

ID: CQ-0038
Status: done
Tier: Basic
Discovery: 2026-06-17

### Description

Private methods that are actually called are marked with an "Unused" hint whenever their
declaration appears later in the file than the method that calls them.
In the conventional Java style (public API first, private helpers below), this affects
every private helper method — they all show as unused even when called.

### Root cause

`UnusedDeclarationScanner` uses a single tree-walk. `visitMethod` registers private methods
into `privateMethods` and `visitIdentifier` / `visitMemberSelect` register callers via
`markReference`, which does a `privateMethods.containsKey(element)` check.
Because the tree is walked top-to-bottom in source order, a call inside a public method that
appears before the private method's declaration reaches `markReference` before the callee is
in `privateMethods`. The check returns false and the reference is permanently dropped.
The same bug affects `this.helper()` calls (via `visitMemberSelect`) and private fields.

### Fix

Two-pass scan:
- Pass 1 (`declarationPhase = true`): `visitMethod` and `visitVariable` populate the maps;
  reference visitors do nothing.
- Pass 2 (`declarationPhase = false`): reference visitors fire; declaration visitors skip.

All declarations are known before any reference is checked, making the scan order-independent.

### Regression targets

`UnusedDeclarationScannerTest.compile_privateMethodDeclaredAfterCaller_noHint`
`UnusedDeclarationScannerTest.compile_privateMethodCalledViaThis_noHint`

## CQ-0037 — Primitive types not suggested in variable type position

ID: CQ-0037
Status: done
Tier: Basic
Discovery: 2026-06-17

### Description

Primitive types (`boolean`, `byte`, `char`, `double`, `float`, `int`, `long`, `short`) never
appear in completion candidates when the cursor is in a type-name position:
local variable type, method parameter type, method return type, field type, or cast expression.

### Failure mode

`KeywordProvider` has no PRIMITIVES list.
The two contexts that cover type-name positions — `TYPE_REFERENCE` and `VARIABLE_DECLARATION` —
return keywords through `classBodyKeywordsIfApplicable`, which returns `List.of()` inside any
method body and `classBodyKeywords()` at class scope.
Neither path includes primitives at any scope.

### Probe

```java
class Foo {
  void test() {
    i§           // VARIABLE_DECLARATION — expects int, Integer, ...
  }
  b§ bar() {}   // TYPE_REFERENCE (return type) — expects boolean, byte, Boolean, ...
}
```

### Expected Lathe behavior

`int`, `long`, `boolean`, `byte`, `char`, `short`, `float`, `double` appear whenever the cursor
is in a type-name slot (variable type, parameter type, return type, field type, cast).
`void` is already in `TYPE_DECLARATIONS` (class-body level only) and needs no change.

### Fix

Add to `KeywordProvider`:

```java
private static final List<String> PRIMITIVES =
    List.of("boolean", "byte", "char", "double", "float", "int", "long", "short");
```

Include PRIMITIVES unconditionally in:
- `classBodyKeywords()` — covers field types and method return types
- `selectKeywords` `TYPE_REFERENCE` branch when inside a method — currently returns `List.of()`
  via `classBodyKeywordsIfApplicable`; add `PRIMITIVES` alongside
- `selectKeywords` `VARIABLE_DECLARATION` branch (not-name-slot path) — same as above

The change is entirely inside `KeywordProvider`; no other completion component is affected.

### Regression target

`CompletionKeywordAndNoSlotTest.typeReference_inMethodBody_includesPrimitives`
`CompletionKeywordAndNoSlotTest.variableDeclaration_typeSlot_includesPrimitives`
`CompletionKeywordAndNoSlotTest.classBody_returnType_includesPrimitives`

## CQ-0034 — Lambda-scope locals leak into simple-name candidates after their enclosing block closes

ID: CQ-0034
Status: done
Tier: Basic
Discovery: 2025-07-25, AppServer.java constructor (sample-workspace)

### Description

Lambda parameters and variables declared inside a lambda body remain visible as simple-name candidates at cursor positions outside (after) their enclosing lambda block.

### Reproduction

Target: `AppServer.java`, constructor.
The lambda block spans lines 192–197:
```java
config.itemsToProcess().forEach(schema -> {
    final var runner = new Runner(schema, jdbi);
    runner.validate();
});
```
And a stream lambda on lines 205–207:
```java
.collect(Collectors.toMap(Entry::getKey, e -> new BaseJedisService(e.getValue())));
```

Inject `schema` at line 211 (after the lambda block closes):
→ 1 item returned: `schema [Variable]` — expected 0

Inject `runner` at line 211:
→ 1 item returned: `runner [Variable]` — expected 0

Inject `e` inside `new CallbackService(` at line 211 (argument position):
→ `e [Variable]` appears among 52 candidates — expected absent

### Root cause

`SimpleNameProvider.addMethodLocals` (or its scope visitor) collects all local variable and lambda-parameter declarations up to the cursor line number, without verifying that the cursor falls inside the declaring scope's block range.
Javac's own in-scope check also returns these as in-scope (javac=1), suggesting the injected parse tree may present them as reachable under error-recovery conditions.

### Impact

A developer typing `s` or `m` just after a `forEach` block will see closed-over lambda variables as top suggestions.
Severity is medium: wrong candidates appear but correct ones are usually present too.

### Fix area

`SimpleNameProvider` / the local-declaration scanner: record the enclosing block end line for each local and skip any local whose block does not contain the cursor position.

### Fix

`SimpleNameProvider.addVariableIfVisible` walks the ancestor path from each local's `TreePath`
to the enclosing method, checking whether any `LambdaExpressionTree` ancestor ends before
the cursor. If so, the local is suppressed. `LambdaExpressionTree` is checked specifically
(not all block types) to avoid incorrectly filtering `instanceof` binding pattern variables,
whose `InstanceOfTree` parent ends before the if-body where they remain in scope.
Fix: commit `9f9e6b6`.

Regression target:
`CompletionSimpleNameTest.simpleName_lambdaParam_notVisibleAfterLambdaCloses`
`CompletionSimpleNameTest.simpleName_lambdaBodyLocal_notVisibleAfterLambdaCloses`
`CompletionSimpleNameTest.simpleName_methodLocal_visibleAfterNestedLambdaCloses`

---

## CQ-0032 — Member-access returns 0 items when stale snapshot holds an error type for the receiver

ID: CQ-0032
Status: done
Tier: basic
Failure mode: missing-candidate
Owner component: CompletionEngine / TypeResolver
Fix: commit 8355554 — extend reattribution guard to also trigger on TypeKind.ERROR

Project/file:
`/workspace/app-server/src/main/java/com/example/app/server/AppServer.java`

Probe command:
```bash
printf 'diagnostics\ninject "resourceConfig." at 158\nlog 20\n' \
  | python3 dev/explore.py \
    /workspace/app-server/src/main/java/com/example/app/server/AppServer.java
```

Cursor context:
```java
resourceConfig.packages("com.example.app.server.access.rs");
resourceConfig.§          ← new blank line typed after pressing Enter
```

IntelliJ or JDT behavior:
Both IDEs correctly offer all accessible members of `ResourceConfig` at the new line.

Lathe behavior:
Confirmed from `LATHE_DEBUG=1` editor log:

```
[completion] resolve receiver=|resourceConfig| type=re static=false reattributed=false
[completion] proposals count=0 labels=[]
[completion] items=0 reattributed=false
```

The server resolves the receiver to the bogus type `re` and returns 0 proposals.
The issue is a race between the `didChange` debounce compile and the completion request.
When the background compile fires while the user has only typed `re` (the start of `resourceConfig`),
the stale snapshot attributes `re` at that position as an unresolved error identifier with `TypeKind.ERROR`.
When the user finishes typing `resourceConfig.` and triggers completion,
`initialResolved` is non-null (the error type `re`),
so the `initialResolved == null` guard in `completeMemberAccess` does not trigger reattribution.
The engine trusts the error type, finds no members, and returns 0 proposals.

Expected Lathe behavior:
Member-access completion should return the 54 accessible members of `ResourceConfig`.
When the stale snapshot resolves the receiver to a `TypeKind.ERROR` type,
reattribution must be triggered just as it would be for a null resolution.

Accepted edit, if relevant:
Not probed — the gap is in candidate discovery, not insertion.

Regression target:
`CompletionMemberAccessTest.memberAccess_staleCacheNewLine_typeResolved`

Notes:
The gap is intermittent because it depends on the debounce compile racing the user's typing speed.
If the debounce fires after `resourceConfig.` is complete, the snapshot has the correct type and
completion works normally (`reattributed=false`, ~80ms).
If the debounce fires mid-word, the snapshot captures an error type and the completion fails.
The fix point is `completeMemberAccess` in `CompletionEngine`:
extend the reattribution condition from `initialResolved == null` to also cover
`initialResolved.type().getKind() == TypeKind.ERROR`.
`resolveByPosition` already filters out error types (it only accepts `TypeKind.DECLARED`),
so the error type is returned by the text-based fallback path in `resolveReceiver`,
which resolves a field whose attributed type in the error-recovery snapshot is garbage.

---

## CQ-0033 — Uppercase prefix in statement position floods results with unrelated type-index classes

ID: CQ-0033
Status: done
Tier: basic
Failure mode: wrong-candidate-set
Owner component: CompletionEngine / TypeIndexValidator

Project/file:
`/workspace/app-server/src/main/java/com/example/app/server/AppServer.java`

Probe command:
```bash
printf 'diagnostics\ninject "R" at 163\nlog 15\n' \
  | python3 dev/explore.py \
    /workspace/app-server/src/main/java/com/example/app/server/AppServer.java
```

Cursor context:
```java
// TODO: don't use Jersey resource to proxy
R§               ← statement position inside constructor body
```

IntelliJ or JDT behavior:
IntelliJ basic completion in a statement position with an uppercase prefix offers visible locals,
fields, methods, and matching importable types.
Unrelated type-index candidates that are not accessible from the current scope are suppressed
or at least ranked below visible value candidates.

Lathe behavior:
```
sentinelCtx=SIMPLE_NAME  javac=0  keywords=0  items=54  incomplete=true
```

With prefix `R` and no local values matching `R`, the type-index supplies 54+ unrelated classes:
`Record`, `Readable`, `ReflectiveOperationException`, `Ref`, `Reader`, `Random`, `Recording`, …

The log shows `javac=0` — no visible scope candidates match.
`incomplete=true` signals the type-index result set was truncated at the limit.
The result is entirely type-index noise with no value candidates visible.

Expected Lathe behavior:
Uppercase prefixes in method bodies should include importable types alongside visible values,
but visible scope candidates (locals, fields, methods) must rank before type-index candidates.
The 54-item alphabetical type-index dump with no scope candidates visible is the failure:
ranking is missing, not the presence of types.

Accepted edit, if relevant:
Not probed.

Regression target:
`CompletionSimpleNameTest.simpleName_uppercasePrefix_scopeCandidateRanksBeforeTypeIndex`

Notes:
**Corrected diagnosis (2026-06-16)**: JDT and NetBeans both always return importable type
candidates in statement position regardless of whether local/field scope candidates match —
they run scope completion and type completion as parallel independent streams.
Gating type-index on `javac > 0` would diverge from this established behavior.
Verified that scope candidates already rank before type-index candidates when both are present:
`CompletionSimpleNameTest.simpleName_uppercasePrefix_scopeCandidateRanksBeforeTypeIndex` passes
against current code, confirming the ranking is correct.
The original `javac=0` flood case (54 type-index results with no scope match) is expected
behavior per JDT/NetBeans and does not require a fix.

---

## CQ-0022 — String switch `case §` offers type-index classes

ID: CQ-0022
Status: done
Tier: basic
Failure mode: wrong-candidate-set
Owner component: CompletionEngine / TypeResolver

Project/file:
`/home/ag-libs/git/helidon/dbclient/metrics/src/main/java/io/helidon/dbclient/metrics/DbClientMetricsProvider.java`

Probe command:
```bash
printf 'diagnostics\ninject "return switch (type) { case " at 55\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/dbclient/metrics/src/main/java/io/helidon/dbclient/metrics/DbClientMetricsProvider.java
```

Cursor context:
```java
String type = config.get("type").asString().orElse("COUNTER");
return switch (type) { case §
```

IntelliJ or JDT behavior:
Expected IDE behavior is not to offer arbitrary type names in a `String` switch label.
Useful candidates would be visible constant `String` values or no basic candidates.

Lathe behavior:
Fixed.
Lathe now treats `String` switch case labels as value-label sites and suppresses broad
type-index/type-reference candidates.
Enum case labels still use enum constants,
and non-`String` declared selectors still route to type-pattern completion.

Before the fix,
Lathe returned 112 type-index candidates such as `Thread`,
`String`,
`Process`,
`Object`,
and `Math`.
The log shows `sentinelCtx=CASE_LABEL`.

Expected Lathe behavior:
For a `CASE_LABEL` site whose selector type is `String`,
Lathe should suppress unrelated type-index classes.
Type names are not syntactically useful case constants in this context.

Accepted edit, if relevant:
Not probed.
Accepting `Thread` would produce an invalid or irrelevant `case Thread` label.

Regression target:
`CompletionSimpleNameTest.simpleName_switchCaseLabel_stringSubject_suppressesTypeIndexClasses`

Notes:
Fixed by adding an explicit `java.lang.String` case-label guard in `CompletionEngine`
before the type-pattern fallback.

This is separate from enum switch labels (`CQ-0006`) and type-pattern switch labels (`CQ-0021`).
The case-label path should branch by selector type:
enum selectors use enum constants,
non-enum reference selectors may use type-pattern candidates where pattern matching applies,
and `String` selectors should not fall through to broad type-index completion.

## CQ-0023 — Member-access in-token method completion still duplicates existing calls

ID: CQ-0023
Status: done
Tier: presentation
Failure mode: bad-replacement-range
Owner component: CompletionSite / CompletionItemPresenter

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-lifecycle/src/main/java/io/dropwizard/lifecycle/setup/ExecutorServiceBuilder.java`

Probe command:
```bash
printf 'diagnostics\ncomplete after "Duration.s"\naccept after "Duration.s" label seconds\nlog 50\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-lifecycle/src/main/java/io/dropwizard/lifecycle/setup/ExecutorServiceBuilder.java
```

Related probes:
```bash
printf 'diagnostics\naccept inject "DbClientService svc = DbClientMetrics.c§ounter()" at 55 label counter\nlog 60\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/dbclient/metrics/src/main/java/io/helidon/dbclient/metrics/DbClientMetricsProvider.java

printf 'diagnostics\naccept inject "Entity.j§son(\"{}\")" at 91 label json\nlog 60\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-testing/src/test/java/io/dropwizard/testing/junit5/PersonResourceTest.java
```

Cursor context:
```java
this.keepAliveTime = Duration.s§econds(60);
DbClientService svc = DbClientMetrics.c§ounter()
Entity.j§son("{}")
```

IntelliJ or JDT behavior:
Expected IDE behavior is insert/replace-safe in-token completion.
Accepting `seconds` should not duplicate the already-present suffix.

Lathe behavior:
The menu correctly returns `seconds`.
The selected completion item has:
```text
insertText: seconds($1)
textEdit.range: 43:38-43:45
```

The accepted source becomes:
```java
this.keepAliveTime = Duration.seconds(§)(60);
DbClientService svc = DbClientMetrics.counter()§()
Entity.json(§)("{}")
```

Expected Lathe behavior:
Completion at `Duration.s§econds(60)` should replace the whole existing method call target,
or otherwise use insert/replace semantics so the accepted text remains:
```java
this.keepAliveTime = Duration.seconds(§60);
DbClientService svc = DbClientMetrics.counter()§
Entity.json(§"{}")
```

Accepted edit, if relevant:
The accepted edit must not produce `Duration.seconds()(60)`,
`counter()()`,
or `json()("{}")`.

Regression target:
`CompletionAcceptanceTest.accept_inTokenExistingNoArgCall_reusesExistingCall`

Notes:
`CQ-0003` fixed a similar in-token suffix problem for another member-access probe,
but these real Helidon/Dropwizard probes show the method-call form still duplicates existing calls.

Fixed by detecting an existing `(` immediately after the completed identifier and replacing method
completion insert text with the bare method name so the existing call parentheses and arguments
remain intact.

## CQ-0025 — Nested type completion after unimported outer type lacks an import edit

ID: CQ-0025
Status: done
Tier: presentation
Failure mode: bad-import-edit
Owner component: CompletionEngine / CompletionItemPresenter

Project/file:
`/home/ag-libs/git/helidon/dbclient/metrics/src/main/java/io/helidon/dbclient/metrics/DbClientMetricsProvider.java`

Probe command:
```bash
printf 'diagnostics\naccept inject "Map.En" at 55 label Entry\nlog 40\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/dbclient/metrics/src/main/java/io/helidon/dbclient/metrics/DbClientMetricsProvider.java
```

Cursor context:
```java
Map.En§try
```

IntelliJ or JDT behavior:
Expected IDE behavior is for accepting `Entry` through an unimported outer type to leave compilable source,
either by importing `java.util.Map` or by preserving a fully qualified receiver.

Lathe behavior:
The selected item is:
```text
label: Entry
detail: java.util.Map.Entry
insertText: Entry
additionalTextEdits: none
```

The accepted source remains:
```java
Map.Entry§
```

`DbClientMetricsProvider.java` imports `java.util.Collection`,
`java.util.LinkedList`,
and `java.util.List`,
but does not import `java.util.Map`,
so the accepted source leaves the outer type unresolved.

Expected Lathe behavior:
When a nested type candidate depends on an unimported outer type,
the completion item should add a deterministic import edit for the outer type,
for example:
```java
import java.util.Map;
```

Accepted edit, if relevant:
Accepting `Entry` after `Map.En` should produce source equivalent to:
```java
import java.util.Map;

Map.Entry§
```

Regression target:
`CompletionPresentationTest.completionItem_nestedType_addsOuterTypeImportEdit`

Notes:
The fully qualified control probe `java.util.Map.En§` accepts to `java.util.Map.Entry§`,
which is self-contained and does not need an import.
Fixed by adding a completion post-process that detects nested type items owned by a simple receiver
and adds an import edit for the outer type.

## CQ-0024 — Enum type completion ranks nested type before enum constants

ID: CQ-0024
Status: done
Tier: basic
Failure mode: poor-ranking
Owner component: CandidateGenerator / CompletionCandidateRanker

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-testing/src/test/java/io/dropwizard/testing/junit5/PersonResourceTest.java`

Probe command:
```bash
printf 'diagnostics\ninject "Response.Status." at 72\ninject "Response.Status.B" at 72\nlog 30\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-testing/src/test/java/io/dropwizard/testing/junit5/PersonResourceTest.java
```

Cursor context:
```java
Response.Status.§
```

IntelliJ or JDT behavior:
Expected IDE behavior is to rank enum constants first after an enum type receiver.

Lathe behavior:
`Response.Status.` returns `Family` first,
then enum constants such as `ACCEPTED`,
`BAD_GATEWAY`,
and `BAD_REQUEST`.
With prefix `B`,
only the enum constants remain and sorting is useful.

Expected Lathe behavior:
After an enum type receiver with no prefix,
enum constants should rank before nested types,
static methods,
and the `class` literal.
`Family` is syntactically valid,
but it is a less likely completion than `OK`,
`BAD_REQUEST`,
or another status constant.

Accepted edit, if relevant:
Not probed.

Regression target:
`CompletionMemberAccessTest.memberAccess_enumTypeReceiver_ranksConstantsBeforeNestedTypes`

Notes:
This is a ranking-only issue.
The candidate set is valid,
and prefix filtering behaves correctly for `Response.Status.B`.
Fixed by assigning nested type candidates an explicit later sort key,
so enum constants and regular members no longer sort behind null sort text.

## CQ-0026 — Class-body completion after modifiers offers invalid modifier keywords

ID: CQ-0026
Status: done
Tier: basic
Failure mode: wrong-candidate-set
Owner component: KeywordCompletion / SentinelParser

Project/file:
`/home/ag-libs/git/helidon/health/health/src/main/java/io/helidon/health/HealthCheck.java`

Probe command:
```bash
printf 'diagnostics\ninject "public " at 30\ninject "private final " at 30\nlog 80\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/health/health/src/main/java/io/helidon/health/HealthCheck.java
```

Cursor context:
```java
public §
private final §
```

IntelliJ or JDT behavior:
Expected IDE behavior is member-declaration completion appropriate to the modifiers already typed.
After `private final `,
useful candidates are field types,
`class`,
`interface`,
`enum`,
or `record` where legal.
Repeating access modifiers should not be offered.

Regression target:
`CompletionKeywordAndNoSlotTest.classBody_afterPrivateFinal_suppressesInvalidModifiers`

Notes:
Fixed by filtering class-body keyword candidates against modifiers already present on the same line.
Type and nested declaration candidates remain available.

Lathe behavior:
Both probes return 125 items.
The first items are modifier keywords:
```text
public
private
protected
static
final
abstract
synchronized
transient
volatile
class
interface
enum
record
void
```

For `private final §`,
accepting `public`,
`private`,
`protected`,
`final`,
`abstract`,
or several other modifier candidates would produce an invalid declaration.
The log shows `sentinelCtx=TYPE_REFERENCE`.

Expected Lathe behavior:
Class-body completion should account for already-typed declaration modifiers.
It should suppress duplicate or mutually exclusive modifiers and prefer legal declaration continuations.
For `private final §`,
type candidates such as `String`,
`Map`,
or project types should remain available for field declarations.

Accepted edit, if relevant:
Not probed.
The issue is candidate validity before acceptance.

Regression target:
`CompletionKeywordAndNoSlotTest.classBody_afterPrivateFinal_suppressesInvalidModifiers`

Notes:
This was found while simulating a developer adding a new class member by typing modifiers first.
The empty class-body case is broader and can still offer member declaration starters;
the gap is the modifier-sensitive filtering after the user has already committed part of the declaration.

## CQ-0027 — `Collectors.` inside return-stream `collect` is not ranked by result type

ID: CQ-0027
Status: done
Tier: typed
Failure mode: poor-ranking
Owner component: TypeResolver / CompletionCandidateRanker

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-migrations/src/main/java/io/dropwizard/migrations/DbMigrateCommand.java`

Probe command:
```bash
printf 'diagnostics\ninject "return contexts.stream().map(Object::toString).collect(Collectors." at 76\nlog 80\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-migrations/src/main/java/io/dropwizard/migrations/DbMigrateCommand.java
```

Related probes:
```bash
printf 'diagnostics\ninject "return headers.entrySet().stream().collect(Collectors." at 91\nlog 80\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-json-logging/src/main/java/io/dropwizard/logging/json/layout/AccessJsonLayout.java

printf 'diagnostics\ninject "return names.stream().map(healthStateAggregator::healthStateView).flatMap(Optional::stream).collect(Collectors." at 90\nlog 80\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-health/src/main/java/io/dropwizard/health/response/JsonHealthResponseProvider.java
```

Cursor context:
```java
private String getContext(...) {
    return contexts.stream().map(Object::toString).collect(Collectors.§
}

private Map<String, String> filterHeaders(...) {
    return headers.entrySet().stream().collect(Collectors.§
}

private List<HealthStateView> getViews(...) {
    return names.stream()
        .map(healthStateAggregator::healthStateView)
        .flatMap(Optional::stream)
        .collect(Collectors.§
}
```

IntelliJ or JDT behavior:
Expected IDE behavior is to use the enclosing return type and the `Stream.collect` target type
to rank compatible collector factories first.
For these probes,
`joining`,
`toMap`,
and `toUnmodifiableList` or `toList` should be near the top respectively.

Lathe behavior:
Fixed.
Lathe now recognizes the configured static-member result rule for
`Collectors.§` inside `Stream.collect(...)`,
propagates the expected result type from the enclosing expression,
and ranks compatible collector factories before unrelated collectors.

Before the fix,
all three probes returned 45 `Collectors` members in alphabetical order:
```text
averagingDouble
averagingInt
averagingLong
collectingAndThen
counting
filtering
flatMapping
groupingBy
...
joining
...
toList
toMap
toSet
toUnmodifiableList
...
```

The log shows `sentinelCtx=MEMBER_ACCESS` and resolves `Collectors` correctly,
but the candidate ranking does not reflect the expected result type of the surrounding return expression.

Expected Lathe behavior:
When `Collectors.§` is the argument to `Stream.collect(...)`,
Lathe should propagate the expected result type from the enclosing expression.
Collectors whose result type matches that expected type should rank before unrelated collectors.
Examples:

- `String` return: rank `joining(...)` before numeric summarizing and grouping collectors.
- `Map<String, String>` return: rank `toMap(...)` before `toList`, `joining`, and numeric collectors.
- `List<HealthStateView>` return: rank `toUnmodifiableList()` or `toList()` before `toMap` and grouping collectors.

Accepted edit, if relevant:
Parameterized collector methods correctly place the cursor inside their argument list,
for example accepting `toMap` produces `Collectors.toMap(§)`.
The gap is ranking,
not insertion.

Regression target:
`CompletionMemberAccessTest.memberAccess_collectorsReceiverInsideReturnCollect_rankedByReturnType`

Notes:
Fixed by commit `6896d2c`.
The implementation deliberately keeps Java-library-specific method and type names in
`CompletionLibraryRules`,
alongside the existing lambda projection rules for `Optional`,
`Stream`,
and `CompletionStage`.
`TypeResolver` resolves a generic static-member result context from the rule table,
and `CompletionCandidateRanker` ranks candidates through that context without hardcoding
`Collectors` or `Collector`.

This remains related to `CQ-0020`,
but it was found through return statements rather than assignment initializers.
That historical exploration also reconfirmed the then-open CQ-0002 method-reference case.
Method-reference completion subsequently shipped as CQ-0060; CQ-0002 is its completed duplicate.

## CQ-0028 — Fresh local declaration and assignment sites borrow enclosing return type

ID: CQ-0028
Status: done
Tier: typed
Failure mode: poor-ranking
Owner component: TypeResolver

Project/file:
`/home/ag-libs/git/helidon/dbclient/metrics/src/main/java/io/helidon/dbclient/metrics/DbClientMetricsProvider.java`

Probe command:
```bash
printf 'diagnostics\ninject "var value = " at 55\ninject "String value = \"\"; value = " at 55\nlog 80\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/dbclient/metrics/src/main/java/io/helidon/dbclient/metrics/DbClientMetricsProvider.java
```

Control probes:
```bash
printf 'diagnostics\ninject "var value = " at 76\ninject "String value = \"\"; value = " at 76\nlog 80\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-migrations/src/main/java/io/dropwizard/migrations/DbMigrateCommand.java

printf 'diagnostics\ninject "var value = " at 91\ninject "var value = java.util.Collections.emptyMap(); value = " at 91\nlog 80\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-json-logging/src/main/java/io/dropwizard/logging/json/layout/AccessJsonLayout.java
```

Cursor context:
```java
// inside DbClientMetricsProvider.fromConfig(...), whose return type is DbClientService
var value = §
String value = ""; value = §

// inside DbMigrateCommand.getContext(...), whose return type is String
var value = §

// inside AccessJsonLayout.filterHeaders(...), whose return type is Map<String, String>
var value = §
```

IntelliJ or JDT behavior:
Expected IDE behavior is that a `var` initializer has no declared expected type;
the initializer determines the local variable type.
For assignment to an already declared local,
the assignee type should be used as the expected type.

Lathe behavior:
In all probes,
the debug log shows the expected type comes from the enclosing method return type:
```text
semantic=Type[type=io.helidon.dbclient.DbClientService]
semantic=Type[type=java.lang.String]
semantic=Type[type=java.util.Map<java.lang.String,java.lang.String>]
```

This affects ranking.
Inside `fromConfig`, `var value = §` ranks `fromConfig` first because it returns `DbClientService`,
even though a `var` initializer should not be constrained by the method return type.
In the same method,
`String value = ""; value = §` also uses `DbClientService` instead of the local variable's `String` type.

Expected Lathe behavior:
`var value = §` should use `ExpectedValue.Unknown` or an equivalent unconstrained value slot.
`String value = ""; value = §` should use `String` as the expected type,
even when the enclosing method returns some other type.

Accepted edit, if relevant:
No bad edit payload was found.
This gap is about ranking and semantic context.

Regression target:
`CompletionSimpleNameTest.varInitializer_doesNotUseEnclosingMethodReturnType`
`CompletionSimpleNameTest.assignmentToFreshLocal_usesAssigneeTypeNotEnclosingReturnType`

Notes:
Fixed by checking the cursor's direct AST expression path before the broader enclosing-method scan.
`var` initializers now remain unconstrained,
and assignments use the left-hand side type when it is available.

The same pass confirmed useful `var` behavior elsewhere:
`var list = List.of("a"); list.§`,
`var map = new java.util.HashMap<String, String>(); map.§`,
and `var service = fromConfig(config); service.§` all resolve the inferred local variable type for subsequent member access.
Editing `var list = List.o§f("a")` still duplicates the existing call suffix,
which is already covered by `CQ-0023`.

## CQ-0004 — Dotted member access can fall back to simple-name completion in incomplete assignments

ID: CQ-0004
Status: done
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
Status: done
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
Status: done
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
Status: done
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
Status: done
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

### Current verification

`CompletionSimpleNameTest.equalityComparison_enumLhs_suggestsEnumConstants` and
`CompletionSimpleNameTest.equalityComparison_methodCallLhs_suggestsEnumConstants` cover the comparison cases.

---

## CQ-0009 — Uppercase simple-name completion hides visible static fields in method bodies

ID: CQ-0009
Status: done
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
Status: non-goal (editor capability)
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

## CQ-0012 — Member completion after assignment can be misclassified as a type reference

ID: CQ-0012
Status: done
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
Status: done
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
Status: done
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
Status: done
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
Status: done
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
Status: done
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
Status: done
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

## CQ-0003 — In-token method completion does not replace the suffix

ID: CQ-0003
Status: done
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
Status: done
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
Status: done
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

## CQ-0020 — Static member access inside argument not ranked by expected type

ID: CQ-0020
Status: done
Tier: typed
Failure mode: poor-ranking
Owner component: TypeResolver / CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-configuration/src/main/java/io/dropwizard/configuration/BaseConfigurationFactory.java`

Probe command:
```bash
printf 'inject "List<String> ps = e.getKnownPropertyIds().stream().map(Object::toString).collect(Collectors." at 152\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-configuration/src/main/java/io/dropwizard/configuration/BaseConfigurationFactory.java 2>&1
```

Cursor context:
```java
List<String> ps = e.getKnownPropertyIds().stream().map(Object::toString).collect(Collectors.§)
```

IntelliJ or JDT behavior:
Expected IDE behavior is to rank `toList()` (and other `List`-returning collectors) first,
because the outer `collect()` call's argument type is `Collector<String, ?, List<String>>`.

Lathe behavior:
Fixed.
Lathe now uses the shared static-member result rule path for `Collectors.§` inside
`Stream.collect(...)`.
It derives the expected result type from the enclosing assignment or initializer context and
ranks compatible collector factories before unrelated collectors.

Before the fix,
the probe returned 45 items in alphabetical order.
`toList` appears at position 36.
The `sentinelCtx=MEMBER_ACCESS` path routed through `Collectors` static members,
but no semantic expected type was applied.

Expected Lathe behavior:
When a static member access (`Collectors.§`) is the argument to a call (`collect(§)`),
the expected type from the argument position should flow into the member access ranking.
`toList()` returning `Collector<T, ?, List<T>>` should rank first for a `Stream<String>.collect(§)` context expecting `List<String>`.

Accepted edit, if relevant:
`toList` accepted edit is correct: inserts `toList()` at the position, producing `Collectors.toList()`.

Regression target:
`CompletionMemberAccessTest.memberAccess_staticReceiverInArgumentPosition_rankedByOuterArgumentExpectedType`
`CompletionMemberAccessTest.memberAccess_collectorsReceiverInsideCollectArgument_suggestsCollectorMethods`

Notes:
The accepted edit for `toList` is correct.
The gap was ranking only, not insertion.
The fix propagates the outer argument's expected type into the nested member-access ranking context
through the centralized `CompletionLibraryRules` table.
Related to CQ-0015 (member-access expected type from assignment),
but that fix addressed only the direct-assignment context, not the nested-argument context.

## CQ-0021 — Type-pattern switch `case §` (no prefix) returns simple-name candidates instead of types

ID: CQ-0021
Status: done
Tier: typed
Failure mode: wrong-candidate-set
Owner component: CompletionEngine / CandidateGenerator

Project/file:
`/home/ag-libs/git/helidon/webserver/webserver/src/main/java/io/helidon/webserver/ProxyProtocolHandler.java`

Probe command:
```bash
printf 'inject "case " at 512\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/webserver/webserver/src/main/java/io/helidon/webserver/ProxyProtocolHandler.java 2>&1
```

Control (prefix shows types correctly):
```bash
printf 'inject "case Inet" at 512\nlog 20\n' \
  | python3 dev/explore.py /home/ag-libs/git/helidon/webserver/webserver/src/main/java/io/helidon/webserver/ProxyProtocolHandler.java 2>&1
```

Cursor context:
```java
return switch (sourceSocketAddress) {
    case InetSocketAddress socket -> socket.getHostString();
    case §
```

IntelliJ or JDT behavior:
Expected IDE behavior is to offer type names that are compatible with the switch selector type (`SocketAddress`).
`InetSocketAddress`, `UnixDomainSocketAddress`, and other `SocketAddress` subtypes should appear as top candidates.

Lathe behavior:
Without a prefix, returns 46 simple-name candidates (fields, variables, methods).
No `[Class]` or `[Interface]` type candidates appear.
Log shows `sentinelCtx=CASE_LABEL` and `semantic=Type[type=java.net.SocketAddress]` are correctly detected,
but `simple-name candidates javac=59` dominates and type-index is not queried.

With prefix `case Inet§`, returns 6 type-index candidates (`InetSocketAddress`, `InetAddress`, etc.) correctly.

Expected Lathe behavior:
For a `CASE_LABEL` site in a type-pattern switch, the no-prefix path should query the type-index
and return type candidates rather than falling through to javac's simple-name candidates.
Simple-name candidates (fields, variables, methods) are not valid type-pattern case labels
and should be suppressed or ranked after type candidates.

Accepted edit, if relevant:
Not yet probed.
Expected: accepting `InetSocketAddress` should insert `InetSocketAddress ` (with trailing space for the binding variable name).

Regression target:
`CompletionSimpleNameTest.simpleName_switchCaseLabel_typePatternSubject_suggestsTypes`

Notes:
CQ-0006 fixed enum-switch case labels (routing through `enumCase` candidates).
This gap is the parallel fix for type-pattern-switch case labels.
The enum case fix detects enum selector type and adds enum constants.
The type-pattern fix needs to detect non-enum selector types and route through the type-index.
Subtype filtering (only `SocketAddress` subtypes) would be ideal but is not required in the first slice.

## CQ-0031 — Statement member access can hide `equals` after parser recovery

ID: CQ-0031
Status: done
Tier: basic
Failure mode: missing-candidate
Owner component: SemanticCompletionContext / CompletionCandidateRanker

Project/file:
`/home/ag-libs/git/helidon/dbclient/mongodb/src/main/java/io/helidon/dbclient/mongodb/MongoDbClient.java`

Observed context:
After adding a new line after the local variable declaration near line 53,
typing `config.` sometimes omits `equals`.
Selecting another method and backspacing back to `config.` can make `equals` appear again.

Log evidence:
Both requests resolve the same member-access receiver:
`receiver=|config| type=io.helidon.dbclient.mongodb.MongoDbClientConfig`.

One request returns 13 candidates:
`[credDb, password, url, username, equals, getClass, hashCode, notify, notifyAll, toString, wait, wait, wait]`.

Another request returns only 7 candidates:
`[credDb, password, url, username, getClass, hashCode, toString]`.

Likely cause:
Parser recovery can treat `config.` as the initializer expression for a previous incomplete
`String` declaration.
`CompletionCandidateRanker` then filters member candidates by that inferred expected value context.
That explains the missing `void` Object methods (`notify`, `notifyAll`, `wait`).
It also explains `equals` disappearing when the inferred expected type is not boolean-compatible,
because `equals` returns `boolean`.

Expected Lathe behavior:
A standalone member-access statement should show normal accessible members even when the previous
statement is incomplete or parser recovery has introduced temporary errors.
Expected-type filtering should not hide `equals` for `config.` in statement position.

Regression target:
`CompletionMemberAccessTest.memberAccess_afterIncompletePreviousStatement_includesEquals`

Notes:
The reproducer is an incomplete declaration followed by member access:
```java
String selected =
config.§
```

The current failing candidate set is:
`[credDb, password, url, username, toString, getClass, hashCode]`.
The assertion includes the string-valued domain methods and `equals`.

Fixed by letting `java.lang.Object` methods pass expected-type compatibility filtering after
the existing `void`-method filter has removed `notify`, `notifyAll`, and `wait`.

## EG-001 — Signature help selects the inner method's signature when the argument is itself a method call

**Status: done — Target: M1**

### Observed behaviour

When the cursor is positioned inside a method call's argument list and that argument is itself a
method call, `textDocument/signatureHelp` returns the signature of the **argument's** method
instead of the **containing** method.

```java
// CronTask.java
cron = parser.parse(config.expression());
//                  ↑ cursor here, after 'parse('
// expected: CronParser.parse(String expression)
// actual:   String expression()
```

```java
// TaskManagerImpl.java
tasks.put(task.id(), task);
//        ↑ cursor here, after 'put('
// expected: Map.put(K key, V value)
// actual:   String id()
```

Both cases are confirmed by `FINE` log lines:
`[signatureHelp] sig=String expression() param=0` and
`[signatureHelp] sig=String id() param=0`.

Simple-argument calls (constant or field reference as first argument) resolve correctly:
`LOGGER.log(` correctly shows 8 `System.Logger.log` overloads with active param 0.

### Root cause

The signature help algorithm must scan backward from the cursor position for the innermost
unmatched `(`.
When the cursor lands at the start of a method-call argument, there is no unmatched `(`
between the cursor position and the outer method's `(`.
The current implementation appears to scan forward or use the AST to detect the innermost
enclosing invocation, which incorrectly resolves to the argument's invocation rather than the
containing one when the cursor falls at the argument's start position.

### Proposed fix

In `SignatureHelpLocator` (or equivalent), identify the active method call by scanning backward
from the cursor for the nearest unmatched `(`, then resolve the method or constructor that owns
that `(`.
Forward scanning or AST-based inner-invocation detection must be gated to only fire once the
cursor is past the argument method's own `(`.

### Probe commands

```bash
printf 'sig after "parser.parse("\nlog 5\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/helidon/scheduling/src/main/java/io/helidon/scheduling/CronTask.java

printf 'sig after "tasks.put("\nlog 5\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/helidon/scheduling/src/main/java/io/helidon/scheduling/TaskManagerImpl.java
```

### Regression targets

`SignatureHelpTest.signatureHelp_outerCall_firstArgIsMethodCall_returnsOuterSignature`
`SignatureHelpTest.signatureHelp_mapPut_firstArgIsMethodCall_returnsMapPutSignature`

---

## EG-002 — Wrap-with-try/catch action absent for `UNREPORTED_EXCEPTION` in regular method bodies

**Status: done — Target: M1**

### Observed behaviour

When a method body contains a checked exception that is neither caught nor declared, the
code-action response returns only `"Add 'throws ...' to method"`.
`"Wrap with try/catch"` is never returned, even though `status.md` lists it as implemented.

```java
// triggered with: throw new IOException("x"); in a regular void method body
// didSave → diagnostic: UNREPORTED_EXCEPTION / java.io.IOException
// codeAction request → ["Add 'throws IOException' to method"]   // only this
//                      no "Wrap with try/catch" offered
```

This was tested in both Helidon and Dropwizard module method bodies (non-lambda context) using a
Python test script that called `didSave` with injected source and then called `codeAction`.

### Relationship to code-action gap CA-1

Code-action gap CA-1 (below) identifies this for the **lambda** case and proposes
`TryCatchWrapProvider` as the fix.
This gap confirms that `TryCatchWrapProvider` is absent entirely — the lambda-context
route is not the only missing branch; the baseline non-lambda method-body route is also missing.

`status.md` has been corrected: try/catch wrapping is **not implemented**.

### Proposed fix

Implement `TryCatchWrapProvider` as described in code-action gap CA-1 below.
The provider must handle both contexts:

- Regular method body: wrap the statement containing the throw or checked call in a
  `try { … } catch (ExceptionType e) { }` block.
- Lambda/anonymous-class body: same wrapping, targeted at the statement within the lambda.

Once `TryCatchWrapProvider` is implemented, the `AddThrowsProvider` lambda-suppression from
code-action gap CA-1 should be applied alongside it.

### Regression targets

`QuickFixCodeActionTest.codeAction_unreportedException_methodBody_offersBothWrapAndThrows`
`QuickFixCodeActionTest.codeAction_unreportedException_lambdaBody_offersOnlyWrap`

---

## EG-004 — Hover returns null on positions inside import declarations

**Status: done — Target: M1**

### Observed behaviour

`textDocument/hover` at a type name inside an import statement returns no result.

```java
import io.helidon.scheduling.TaskManager;
//                           ↑ hover here → null
```

Type names in class bodies or method bodies resolve correctly.

### Root cause

`HoverLocator` likely resolves the position to an `ImportTree` node, which does not carry an
attributed `Element` through the normal `Trees.getElement(TreePath)` path, or the element is
resolved as a package rather than a type.

### Proposed fix

In `HoverLocator`, detect the `ImportTree` case and extract the imported type element directly
from `Trees.getType(importPath)` or from the symbol table via `elements().getTypeElement(fqn)`.
Delegate to the normal hover path with that type element.

### Regression targets

`HoverTest.hover_importDeclaration_resolvesImportedType`

---

---

## EG-006 — Workspace symbol results rank reactor-local types below dependency and JDK types

**Status: done — Target: M1**

### Observed behaviour

```
sym "Application"  → 28 results:
  1. javax.ws.rs.core.Application  (JAX-RS dependency)
  2. org.glassfish.jersey.…Application  (Jersey dependency)
  3. com.sun.…ApplicationProtocolSelector  (JDK internal)
  4. io.dropwizard.core.Application  ← reactor-local type, rank 4
```

A developer working in the Dropwizard workspace and typing `Application` almost certainly
wants the project-local type first.

### Root cause

`WorkspaceTypeIndex` returns candidates sorted by simple name lexicographic order (or insertion
order) with no reactor-origin boost.
Dependency and JDK entries with the same name or an earlier alphabetic order naturally rank ahead
of reactor types.

### Proposed fix

Apply a sort-key boost to reactor-origin candidates.
The `TypeIndexEntry` or its source shard already carries origin information.
In the result comparator, assign reactor entries a higher primary sort key than dependency or JDK
entries when the simple name matches the query exactly.

### Regression targets

`WorkspaceTypeIndexTest.search_exactName_ranksReactorTypeBeforeDependencyType`

---

## EG-007 — Type-index startup emits hundreds of WARNING-level duplicate-type messages, obscuring real warnings

**Status: done — Target: M1**

### Observed behaviour

Every server start on both projects emits 150–200 WARNING lines:

```
WARNING  [type-index] org.objectweb.asm.Type duplicate type in shard … hierarchy navigation skipped
WARNING  [type-index] org.hamcrest.Matcher duplicate type in shard … hierarchy navigation skipped
WARNING  [type-index] org.junit.jupiter.api.Test duplicate type in shard … hierarchy navigation skipped
… (150+ more)
```

These types appear in both helidon and dropwizard because the projects' test-scoped JARs
(hamcrest, JUnit, ASM, Plexus, etc.) also exist on the lathe server's own classpath and are
indexed twice: once from the workspace's dependency shards and once from the server's own shards.

The volume of WARNING noise makes it impossible to spot genuine WARNING-level conditions such as
missing module metadata or a missing workspace config file.

### Root cause

`WorkspaceTypeIndex` merges multiple shard sources (dependency, JDK, reactor).
When the same fully-qualified type appears in more than one shard, it logs a WARNING per
duplicate.
Common test-infrastructure JARs (JUnit, Hamcrest, ASM) are almost always duplicated across the
server classpath and any workspace that uses the same testing stack.

### Fix

`WorkspaceTypeIndex.deduplicate()` keeps the first-seen entry per binary name and logs at `FINE`
for each skipped duplicate.
The `duplicateBinaryNames` tracking and the `isDuplicate()` method were removed; hierarchy
navigation now works for cross-shard duplicates since only one entry per binary name is stored.

### Regression targets

`WorkspaceTypeIndexTest.graph_duplicateBinaryName_withinShard_keepsFirst`
`WorkspaceTypeIndexTest.merge_duplicateTypeAcrossShards_keepsFirstEntry`

---

## EG-008 — Object synchronization methods appear in member-access completion results

**Status: done — Target: M1**

### Observed behaviour

Member-access completion on any object receiver includes `wait()`, `notify()`, and
`notifyAll()` as candidates, even in contexts where they are never useful.

```
complete after "handler.getServletContext()."  → 64 items
  ...
  wait   [Method]   void
  notify [Method]   void
  notifyAll [Method] void
```

These inherited `Object` methods appear at the bottom of the list but are rarely (if ever)
the intended completion.
Their presence adds noise without value and they can cause errors if accepted in an
incompatible synchronisation context.

### Relationship to the memory note on type filtering

A project-level note records that `java.lang` classes and `Object` methods (`wait`) wrongly
appear in argument positions, and that the type-index assignability filter is too expensive to
fix broadly.
This gap extends that observation to **member-access** completion results, where
`Object` synchronization methods are inherited by every type but should be suppressed by default.

### Proposed fix

Add `wait`, `notify`, and `notifyAll` to a static suppression list in `MemberAccessCandidates`
or the equivalent candidate filter layer.
These three methods are the canonical "do not show" set in Java IDEs (IntelliJ, JDT both suppress
them by default).
`hashCode`, `equals`, and `toString` are genuinely useful and should not be suppressed.
`getClass` is occasionally useful and may be suppressed or ranked last.

The suppression should apply to the member-access completion path only.
It should not affect other completion contexts.

### Probe command

```bash
printf 'complete after "handler.getServletContext()." filter wait\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/dropwizard/dropwizard-core/src/main/java/io/dropwizard/core/server/AbstractServerFactory.java
```

### Regression targets

`CompletionMemberAccessTest.memberAccess_anyReceiver_suppressesSynchronizationMethods`

---

## EG-009 — Outgoing calls includes anonymous class constructor instantiations with empty name

**Status: done — Target: M1**

### Observed behaviour

`callHierarchy/outgoingCalls` on a method that instantiates an anonymous class returns one extra
callee entry with an empty name and the declaring file as its URI.

```java
// CronTask.java — void run()
actualTask.run(new CronInvocation() { ... });
//              ^^^ anonymous class instantiation → callee with name="" uri=CronTask.java
```

Probe against Helidon `CronTask.run()` yields:

```
3 callee(s):
    scheduleNext  CronTask.java:88
    run           ScheduledConsumer.java:92
                  CronTask.java:92        ← empty name
```

### Root cause

`CallHierarchyOutgoingLocator` visits `NewClassTree` nodes.
When the instantiated type is an anonymous class, `SourceLocator.declarationName()` returns
`""` because `element.getSimpleName()` is empty for anonymous types.
`findSourceFile` resolves to the declaring file itself (the anonymous body is defined there),
so the entry is not suppressed by the missing-source-file guard.

### Proposed fix

In `CallHierarchyOutgoingLocator`, skip any `NewClassTree` whose resolved element is an
anonymous class (i.e., `element.getSimpleName().isEmpty()`).
Anonymous class instantiations are not meaningful callee targets in a call hierarchy view.

### Regression target

`CallHierarchyOutgoingLocatorTest.outgoingCalls_anonymousClassInstantiation_excludedFromResults`

### Related observation

The same anonymous-class empty-name pattern also appears in document symbols.
Probing Dropwizard `PersonResourceTest.java` returns a blank class symbol for
`new GenericType<List<Person>>() {}`:

```text
[Method] testGetImmutableListOfPersons  63:10
  [Class]   64:104
```

Do not open a separate gap record for this until the anonymous-class naming policy is revisited.
Either anonymous classes should be suppressed from user-facing symbol/call outputs,
or they should use a stable synthetic label.

---

## EG-010 — `explore.py` cannot probe dep/JDK source files — no workspace context for cache paths

**Status: done — Target: M1**

### Observed behaviour

Attempting to open a dependency or JDK source file in `explore.py` by passing its path on the
command line fails immediately:

```
error: No .lathe/ directory found above /home/ag-libs/.cache/lathe/deps/io.dropwizard.metrics:metrics-core:4.2.38/com/codahale/metrics/MetricRegistry.java
```

This blocks two probe scenarios:
- Finding **callers of dep/JDK methods** (e.g. all reactor callers of `MetricRegistry.register`)
  by opening the dep source file and running `callers`.
- Finding **callers of JDK methods** by opening JDK source from the cache.

The limitation also applies to the `refs` command, making it impossible to probe
external-source reference scope from `explore.py`.

### Root cause

`explore.py` derives the workspace root from the opened file path by walking up the directory
tree looking for a `.lathe/` directory.
Dependency source files are extracted to `~/.cache/lathe/deps/<gav>/` and JDK sources to
`~/.cache/lathe/jdks/<jdk-key>/`, neither of which are workspace directories.
`find_workspace_root` exhausts the tree and raises an error before the LSP server is started.

### Proposed fix

Add a `--workspace <path>` argument to `explore.py`.
When provided, it overrides `find_workspace_root` and is passed directly to `initialize`.
This allows callers to pair any source file (dep cache, JDK cache, absolute path) with any
workspace root, enabling:

```bash
python3 dev/explore.py --workspace /home/ag-libs/git/dropwizard \
    /home/ag-libs/.cache/lathe/deps/io.dropwizard.metrics:metrics-core:4.2.38/com/codahale/metrics/MetricRegistry.java
callers "register" min 1
```

### Regression targets

`explore.py --workspace` integration test or inline doc-test in the script's own test section.

---

## EG-011 — Outgoing calls silently omits callees whose source is in extracted dep or JDK dirs

**Status: done — Target: M2**

### Observed behaviour

`callHierarchy/outgoingCalls` on a method that calls into a dependency type returns only reactor
callees; dep and JDK callees are silently absent.

Probing `Bootstrap.registerMetrics()` (which calls `MetricRegistry.register()`) yields:

```
1 callee(s):
    getMetricRegistry  Bootstrap.java:218
```

`MetricRegistry.register(String, Metric)` is not shown even though the Dropwizard workspace has
207 extracted dependency sources and `MetricRegistry.java` exists at
`~/.cache/lathe/deps/io.dropwizard.metrics:metrics-core:4.2.38/com/codahale/metrics/MetricRegistry.java`.

### Root cause

`WorkspaceSession.outgoingCallsFuture` passes `workspace.allSourceRoots()` to
`CallHierarchyOutgoingLocator`.
`allSourceRoots()` returns only reactor module source directories.
`DefinitionLocator.findSourceFile` therefore cannot resolve callees whose source lives under
`~/.cache/lathe/deps/` or `~/.cache/lathe/jdks/`, so those callees are silently dropped.

This is the mirror limitation to external-source Find References scope (M2).

### Proposed fix

Include extracted dependency and JDK source directories in the search roots passed to
`CallHierarchyOutgoingLocator`, paralleling the M2 work to expand Find References scope.
`WorkspaceModuleRegistry` or `Workspace` already tracks `dependencySources` with their `dir`
fields; exposing them alongside `allSourceRoots()` would let `findSourceFile` resolve dep/JDK
callees.

The callee items returned for dep/JDK targets should be marked with `SymbolKind.Method` and
have the cache path as their `uri`, consistent with how `definition` navigates to dep sources.

### Resolution (2026-07-04)

`WorkspaceSession.outgoingCallsFuture` now passes `typeSourceDirs()` (reactor + JDK-module +
dependency source dirs) instead of `workspace.allSourceRoots()` (reactor-only) — reusing the exact
accessor already used by `workspaceSymbol` and the type-hierarchy features. `DefinitionLocator.findSourceFile`
then resolves callees under `~/.cache/lathe/deps/` and `~/.cache/lathe/jdks/`, and the callee items
carry the cache path as their `uri`, matching `definition`.

Validated before/after with `dev/explore.py callees` on `AppServerConfig.java`'s compact
constructor: reactor-only roots returned **0** callees (all its callees are JDK/dep), the fix returns
**15**, including `check`/`notNull`/`validate` under the extracted `validcheck` dependency and the JDK
`Objects`/`Duration`/etc. sources.

### Regression targets

`LspSmokeTest.outgoingCalls_calleeInCachedJdkSource_returnsJdkCallee` (invoker `multi-module` IT) —
outgoing calls on `StringUtils.upper`, whose sole callee is `String.toUpperCase`, must include that
JDK callee with a `/jdks/` cache URI; a reactor-only search root returns empty. This end-to-end
placement mirrors how the external-source Find References scope (FR-004) is tested, exercising the
real `WorkspaceSession` against a workspace with extracted dep/JDK sources (a service-level unit
harness for `WorkspaceSession` does not exist).

---

## EG-013 — Find References candidate discovery excludes generated annotation sources

**Status: done — Target: M2**

### Observed behaviour

Find References on a record component never returns the generated `@Builder` class that uses that
component, even when the generated builder calls the component's accessor.

Probed against the sample-workspace workspace, which generates a `*Builder` per `@Builder` record
under each module's `target/generated-sources/annotations/`:

```
refs "requestId,"  on Entity (app-alpha, builder present)
  → progress: 0 / 1 candidates       ← only the record's own file is a candidate
  → 1 reference (the accessor call inside Entity itself)
  → EntityBuilder.builder(existing) calls existing.requestId() but is never found

refs "ownerRef,"  on CreateEntity (app-core)
  → progress: 0 / 1 candidates
  → 0 references
```

The decisive signal is the candidate count: `0 / 1 candidates`.
`EntityBuilder.builder(Entity existing)` contains
`builder.requestId = existing.requestId();`, so the generated file does reference the accessor,
yet it is never even offered as a candidate to scan.

### Root cause

Two scopes are inconsistent:

- **Resolution scope** — `WorkspaceModuleRegistry.allSourceRoots()` already includes the generated
  directory (`ModuleSourceConfig.originalGenSourcesDir()`, which points at
  `target/generated-sources/annotations`) when it is non-null.
- **Candidate discovery** — `ReferenceCandidateIndex.build(...)` builds the token-to-file index
  from `config.sourceRoots()` only, which contains just `src/main/java`.
  The generated directory is never tokenized, so generated files never appear in the candidate
  set.

The server can resolve a reference that lives in a generated builder, but candidate discovery
filters those files out first, so the reference search never reaches them.
This is independent of the editor and of explore.py positioning — it is in the index.

### Proposed fix

In `ReferenceCandidateIndex.build(...)`, include each config's `originalGenSourcesDir()` in the
set of indexed roots, mirroring the exact logic already used by
`WorkspaceModuleRegistry.allSourceRoots()`:

```java
allConfigs.stream()
    .flatMap(
        config ->
            config.originalGenSourcesDir() != null
                ? Stream.concat(
                    config.sourceRoots().stream(), Stream.of(config.originalGenSourcesDir()))
                : config.sourceRoots().stream())
    .distinct()
    .filter(Files::isDirectory)
```

Returning `target/generated-sources/annotations/...` URIs from a reference search is consistent
with how `textDocument/definition` already navigates into generated sources via the same
`allSourceRoots()` scope.

### Related observation — incomplete `.lathe` generated-sources mirror

Separately, the `.lathe/<module>/generated-sources` mirror is inconsistent across modules
(`app-core` 0 of 208, `app-api` 0 of 46, `app-config` 0 of 108; `app-alpha` 14 of 14;
`app-server` 6 of 120).
This mirror feeds `ModuleSourceCompiler`, not the reference candidate path (which reads the
original `target` directory), so it is **not** the cause of this gap.
It appears generated sources are only captured for modules that actually recompiled during the
lathe-instrumented build, and warrants a separate investigation under the build/sync lifecycle.

### Probe commands

```bash
python3 dev/explore.py \
    /workspace/app-alpha/src/main/java/com/example/app/alpha/Entity.java \
    refs "requestId," min 2 expect "EntityBuilder.java"
```

### Regression targets

- `ReferenceLocatorTest.recordComponent_accessorInvocation_reportedThroughAccessor`
- `ReferenceCandidatePlannerTest.planCandidates_typeUsedInSamePackageGeneratedSource_includesGeneratedFile`

The originally listed `ReferenceServiceTest` method is not present; current coverage separates component matching from generated-source candidate discovery.

---

## EG-015 — Override/implement completion missing in class bodies

**Status: done — Target: M2**

### Observed behaviour

Typing a method-name prefix inside a class body offers only type-name candidates; no
override-stub completion item is ever returned.

Probed against `DummyAdapter`, which extends a base class and implements an
interface:

```
inject "toString"  in class body
  → 9 items, all types: ToString, ToStringStyle, ToStringBuilder, ToStringSerializer, …
  → no "@Override public String toString() { … }" stub

inject "createP"   in class body  (createPin is an inherited contract method)
  → 8 items, all types: CreatePartitionsResult, CreatePartitionsOptions, …
  → no override stub for createPin
```

A developer typing a method name in a class body expects an override/implement completion that
inserts the full overriding signature with `@Override`, as IntelliJ and Eclipse JDT do.

### Root cause

The completion engine has providers for simple names, types, imports, keywords, and members, but
no provider that enumerates overridable methods from the enclosing type's supertypes.
The enclosing-type and supertype information is already available (the same walk used by type
hierarchy and proposed for EG-012), but it is not wired into a completion provider.

### Relationship to `MissingMethodImplProvider`

This is the completion-driven path to implementing a method.
The code-action-driven path (`MissingMethodImplProvider`) is a separate, also-unimplemented
M1 blocker.
With both absent there is currently no assisted way to implement or override a method.

### Proposed fix

Add an override-completion provider that, when the cursor is at a member-declaration position in a
class body, enumerates the inherited methods of the enclosing `TypeElement` (via supertype walk
plus `Object` methods), filters to those overridable and matching the typed prefix, and returns
completion items whose insert text is the full overriding signature annotated with `@Override`.

### Resolution (2026-07-04)

New `OverrideCompletionProvider`: resolves the enclosing `TypeElement` from the cursor scope
(`TypeResolver.resolveScope(...).getEnclosingClass()`), enumerates `Elements.getAllMembers`, keeps
inherited `METHOD`s that are overridable (not `final`/`static`/`private`, not already declared in the
class — `getAllMembers` returns the class's own version for overridden methods, which the
enclosing-element check drops), matching the typed prefix (incl. `Object`'s `toString`/`equals`/
`hashCode`), and renders each as an `@Override` stub candidate. `CompletionEngine.mergeOverrideCandidates`
runs once after the context switch and merges these into the outcome — gated to a class-body
member-start slot (`TYPE_REFERENCE`/`VARIABLE_DECLARATION`, not a real name slot, enclosing class
present, not inside a method) so annotation value positions and new-member name slots are untouched.

Stub text is shared with the implement-missing-methods code action via a new `MethodStubRenderer`
(extracted from `MissingMethodImplProvider.buildStub` — the code action now delegates, so the two
cannot drift). Validated with `dev/explore.py` on `ThailandCountryAdapter`: `toString` in the class
body offers `@Override public String toString() { throw new UnsupportedOperationException(); }`,
replacing the typed prefix.

**v1 limitations (recorded, follow-ups):**
- **No auto-import** of stub types — rendered as simple names; types outside `java.lang`/same package
  need a manual import. `CompletionCandidate` carries a single `importEdit`; multi-import needs
  `additionalTextEdits` plumbing. This is the natural first follow-on.
- **No method-level type parameters** — overriding `<T> T m(...)` omits the leading `<T>`.
- Body is always `throw new UnsupportedOperationException();` (no `super.m(...)` for concrete
  overrides); visibility always `public`; `throws` omitted. Continuation-line indentation is
  client-reindented (fixed relative indent otherwise).

### Probe commands

```bash
python3 dev/explore.py \
    /workspace/app-server/src/main/java/com/example/app/server/operator/dummy/DummyAdapter.java \
    inject "toString" at 46 expect toString
```

### Regression targets

- `CompletionOverrideTest.completion_objectMethodPrefix_offersToStringOverride`
- `CompletionOverrideTest.completion_methodPrefixInClassBody_offersOverrideStub`
- `CompletionOverrideTest.completion_insideMethodBody_noOverrideStub` (negative: not inside a method)
- `CompletionOverrideTest.completion_finalInheritedMethod_notOffered` (negative: `final` excluded)

---

## EG-016 — Annotation-member completion missing

**Status: done — Target: M2**

Resolved: validated as implemented. `AnnotationCompletionProvider` (wired into `CompletionEngine`
for the `ANNOTATION_ARGUMENT` / `ANNOTATION_ARGUMENT_VALUE` sentinel contexts) offers the annotation
type's element methods as `name = ` items and, for enum-valued elements, the permitted constants.
Validated with `dev/explore.py` on `HasMoreItems.java`: `inject "@Schema("` returns 73 element-name
items (`description`, `required`, `requiredMode`, …). An earlier false-negative probe used
`@JsonProperty(` in a file that did not import `JsonProperty`, so the type could not resolve.

Regression targets (existing): `CompletionAnnotationTest.annotationArgument_emptyList_suggestsElementNames`
(element names) and `CompletionAnnotationTest.annotationArgumentValue_enumElement_offersEnumConstants`
(enum constants), plus siblings.

Not covered here (see EG-038): a `Class`-typed value element offers no type candidates.

### Observed behaviour

Completion inside an annotation's parentheses returns nothing.

```
inject "@JsonProperty("  before a record component
  → (no completions returned)
```

Developers expect the annotation's element names (`value`, `required`, `defaultValue`, …) and,
for enum-valued elements, the permitted constants.

This workspace is annotation-heavy — 112 `@JsonProperty`, 125 `@Path`, plus Swagger,
`@RolesAllowed`, and Jackson XML annotations — so annotation-member completion is a frequent need.

### Root cause

The completion engine has no annotation-context provider.
When the cursor is inside an `AnnotationTree`'s argument list, no candidate generator recognises the
context, so the engine returns an empty result.

### Proposed fix

Add an annotation-member provider that detects the `AnnotationTree` enclosing the cursor, resolves
its annotation `TypeElement`, and offers its `ExecutableElement` members as completion items
(`name = ` insert text).
For an element whose type is an enum, additionally offer the enum constants once the cursor is past
the `=`.

### Probe commands

```bash
python3 dev/explore.py \
    /workspace/app-core/src/main/java/com/example/app/model/CreateEntity.java \
    inject "@JsonProperty(" at 14 expect value
```

### Regression targets

- `CompletionAnnotationTest.annotationArgument_emptyList_suggestsElementNames`
- `CompletionAnnotationTest.annotationArgumentValue_enumElement_offersEnumConstants`

---

## EG-019 — Unused-declaration diagnostic message is the bare word `Unused`

**Status: done — Target: M1**

### Observed behaviour

The unused-declaration hint carries the correct `Unnecessary` tag but a non-descriptive message and
a null code.

Probed against `RegionAdapter.java`, where local variable `resolvedStatus` is unused:

```json
{"severity": 4, "code": null, "source": "lathe", "message": "Unused", "tags": [1],
 "range": {"start": {"line": 236, "character": 20}, "end": {"line": 236, "character": 29}}}
```

`tags: [1]` (`DiagnosticTag.Unnecessary`) is correctly present, so editors do gray the
declaration.
The message is the single word `Unused`, and `code` is `null`.

### Root cause

The unused-declaration scan emits a fixed `"Unused"` message and sets no diagnostic `code`.
It does not distinguish the kind of declaration (local variable, private field, private method) or
include the declaration name.

### Proposed fix

Produce a descriptive message that names the declaration and its kind, for example
`Unused local variable 'resolvedStatus'`, `Unused private method 'foo'`, and set a stable
diagnostic `code` (for example `lathe.unused`) so clients can filter the hint and map it to a
future remove-declaration quick fix.
Keep the `Unnecessary` tag.

### Probe command

```bash
python3 dev/lsp.py \
    /workspace/app-server/src/main/java/com/example/app/server/operator/region/RegionAdapter.java
```

### Regression targets

- `UnusedDeclarationScannerTest.unused_localVariable_messageNamesVariableAndKind`
- `UnusedDeclarationScannerTest.unused_diagnostic_setsStableCode`

---

## EG-021 — Type-name completion ranks reactor-local types below dependency and JDK types

**Status: done — Target: M2**

### Observed behaviour

Type-name completion ranks dependency and JDK types ahead of project-local types.

```
inject "Object o = new Oper"  (in a app-server file)
  → org.mvel2.*, com.sun.xml.ws.*, com.mysql.cj.* candidates rank above
    com.example.app.* reactor-local types
```

A developer authoring code in the reactor almost always wants the project-local type first.

### Root cause

The type-completion candidate comparator does not boost reactor-origin entries.
This is the completion-context analog of EG-006, which covers the same mis-ranking in workspace
symbol search; the two share the underlying `WorkspaceTypeIndex` ordering.

### Proposed fix

Apply a reactor-origin sort boost in the type-completion result comparator, reusing the
`WorkspaceTypeIndex.isReactorType` origin information from EG-006.
Reactor entries should outrank dependency and JDK entries for an equal prefix match.

### Resolution (2026-07-04)

`TypeReferenceCompleter.typeCandidateComparator` was made reactor-aware. Verified first that this
matches modern IDEs: IntelliJ ranks project code above JDK/library via proximity weighers
(`sdkOrLibrary`, `sameModule`) applied *beneath* match quality (JDT relies mainly on expected-type
and case/prefix, with no strong project boost — so IntelliJ is the model here). Reactor origin is
therefore a **tiebreaker beneath match quality**, not an override.

Because Lathe lacks the usage statistics IDEs use to keep ubiquitous JDK types on top, a blanket
reactor boost would bury `java.util.List` etc. under obscure project types (chosen approach: keep a
common-platform tier above reactor). The comparator now grades candidates by `originRank` after the
prefix-match key:

- rank 0 — `java.lang` (exact; auto-imported, most ubiquitous)
- rank 1 — other common platform packages, **exact match** (`java.util`, `java.io`, `java.time`,
  `java.nio`, `java.math`)
- rank 2 — reactor-local types (`WorkspaceTypeIndex.isReactorType`)
- rank 3 — everything else (dependency / other JDK)

then shorter-FQN, then lexicographic. Exact match matters: a live probe showed prefix-matching
`java.lang.` wrongly elevated specialized subpackages (`java.lang.management.OperatingSystemMXBean`,
`java.lang.classfile.…`) above reactor types; only the exact `java.lang`/`java.util`/… packages get
the platform tier, subpackages fall to rank 3.

Confirmed on `app-server`: for prefix `Oper`, `com.example.app.*` reactor types now
lead, above JDK `java.lang.management`/`java.lang.classfile` types.

### Probe commands

```bash
python3 dev/explore.py /path/to/Scratch.java inject "Object o = new Oper"
```

### Regression targets

- `CompletionTypeRankingTest.completion_typePrefix_ranksReactorTypeFirst` (reactor above dependency;
  verified failing without the reactor tier)
- `CompletionTypeRankingTest.completion_typePrefix_commonPlatformOutranksReactor` (java.util above
  reactor)
- `CompletionTypeRankingTest.completion_typePrefix_javaLangSubpackageDoesNotOutrankReactor`
  (java.lang subpackages are not platform-tier)
- `CompletionTypeIndexTest.typeIndex_candidates_javaLangRanksBeforeOtherPackages` (java.lang above
  other platform packages — the rank 0 vs 1 boundary)

---

## EG-022 — Sealed-type `switch`/`case` pattern completion offers arbitrary types

**Status: done — Target: M2**

### Observed behaviour

Inside a `switch` over a sealed reference type, `case` completion offers arbitrary types instead of
the type's permitted subtypes as pattern labels.

```java
String handle(OperationResponse r) {   // sealed interface, 8 permitted subtypes
  switch (r) {
    case ▮          // → 112 items: StrictMath, Short, ScopedValue, RuntimePermission, …
                    //   none of the 8 permitted subtypes are offered as patterns
  }
}
```

Enum `case` completion works correctly in the same session (a `switch` over a `ResultCode` enum
offers all 48 constants), so the gap is specific to sealed/reference-type pattern labels.

### Root cause

The `case`-label completion path handles the enum-constant case but does not recognise a `switch`
selector whose type is a sealed reference type.
It falls back to general type completion, which dumps the type index unranked.

### Proposed fix

When the enclosing `switch` selector resolves to a sealed type (or any reference type), offer its
permitted subtypes (for sealed types) or assignable subtypes as type-pattern `case` labels, with
insert text of the form `case SubType name ->`.

### Resolution (2026-07-04)

`CompletionEngine.sealedCaseLabelCandidates` mirrors the enum-case path: in a `CASE_LABEL` slot whose
expected (selector) type is a sealed `DECLARED` type, it offers the type's
`getPermittedSubclasses()` as type-pattern labels (insert `SubType subType ->`). The general-type
fallback (`isCaseLabelTypePattern`) is suppressed when sealed candidates exist, and the uppercase
type-index merge is skipped for the sealed case-label so each permitted subtype appears once. Enum
case completion is unchanged.

Validated in a compiled sealed switch: `case Cir` offers a single `Circle` (`permitted subtype`),
not the previous 112-type dump. Note: the explorer's `inject` cannot validate a *freshly injected*
switch because it does not reattribute (stale cached analysis → selector type unresolved); the
CompletionCaseTest fixtures and a compiled scratch file exercise the real (recompiled) editor flow.

**v1 limitations (recorded):**
- **Direct permitted subclasses only** — no recursion into nested sealed permits (e.g.
  `ProvisionResponse` is itself sealed; its leaves are not expanded).
- **No auto-import** of the subtype — rendered as a simple name (same limitation family as EG-015);
  same-package works, otherwise a manual import is needed.

### Probe commands

```bash
python3 dev/explore.py /path/to/Scratch.java inject "case " at <line-in-sealed-switch>
```

### Regression targets

- `CompletionCaseTest.completion_caseInSealedSwitch_offersPermittedSubtypes`
- `CompletionCaseTest.completion_caseInEnumSwitch_unchanged`

---

## EG-023 — `this.` completion leaks low-value `Object` methods

**Status: done — Target: M2**

### Observed behaviour

Member completion on `this.` offers `clone`, `finalize`, `notify`, `notifyAll`, and `wait`, while
value-receiver member-access suppresses them.

```
inject "names."   (List<String> field receiver)
  → no clone / finalize / notify / notifyAll / wait

inject "this."
  → clone, finalize, notify, notifyAll, wait(), wait(long), wait(long, int) all present
```

### Root cause

The Object-method suppression that EG-008 applies on the value-receiver member-access path is not
applied on the `this.` (and likely `super.`) completion path.
The suppression list is keyed to one candidate-generation route and is not shared across all
member-completion routes.

### Relationship to EG-008

EG-008 covers suppressing `wait`/`notify`/`notifyAll` on member-access results.
This gap is the same suppression list applied inconsistently: it must also cover the `this.` and
`super.` routes, and should additionally consider `clone` and `finalize`.
Implement alongside EG-008.

### Probe commands

```bash
python3 dev/explore.py /path/to/Scratch.java inject "this."
```

### Regression targets

- `CompletionThisTest.completion_thisReceiver_suppressesObjectSyncMethods`

---

## EG-024 — Type-name completion can offer types from modules the current module does not read

**Status: done — Target: M2**

Resolved by the CQ-0049 fix (2026-07-04): both type-index completion paths now gate candidates on
`Trees.isAccessible(scope, typeElement)`, so an observable-but-unreadable type (here
`com.sun.management.OperatingSystemMXBean` in `jdk.management`, not read by the current module) is no
longer offered. The mechanism is type-agnostic and was live-verified on the equivalent JDK-module case
(`javax.swing.JButton` / `java.desktop`) on `app-core`; regression coverage lives on CQ-0049.

### Scope correction

An earlier version of this record claimed that transitive-dependency types such as
`org.mvel2.*`, `com.sun.xml.ws.*`, and `com.mysql.cj.*` were offered but not importable from
`app-server`.
That claim was wrong.
`app-server/module-info.java` explicitly declares `requires mvel2`, `requires com.sun.xml.ws`,
`requires mysql.connector.j`, and `requires jakarta.xml.ws`, so those types are genuinely readable
and importable; offering them is correct (their volume and ranking are covered by EG-021, not
here).
The actual gap is narrow and is described below.

### Observed behaviour

Type-name completion can offer a type whose package is not readable from the current module, which
lathe's own diagnostics then reject if the candidate is accepted.

Confirmed by opening a `app-server` file that imports three candidates that completion offered for
the `Oper` prefix:

| Import | lathe diagnostic on open |
|---|---|
| `org.mvel2.ast.OperativeAssign` | none — importable (`requires mvel2`) |
| `com.sun.xml.ws.wsdl.OperationDispatcher` | none — importable (`requires com.sun.xml.ws`) |
| `com.sun.management.OperatingSystemMXBean` | ERROR: `package com.sun.management is not visible (declared in module jdk.management)` |

`com.sun.management.OperatingSystemMXBean` was offered by completion (as
`new Oper` candidate `OperatingSystemMXBean [Interface] com.sun.management.OperatingSystemMXBean`),
but `app-server` does not read `jdk.management`, so accepting it produces a not-visible error that
lathe reports correctly.

The completion candidate set is therefore slightly broader than the module graph allows.
The discrepancy is limited to modules the current module does not read — in practice JDK modules
(and any dependency) that are present on the analysis path but not in the module's `requires`
graph.
It is not the transitive-dependency flood originally described.

### Root cause

The workspace type index includes every type on the combined classpath and modulepath.
Type completion does not intersect candidates with the set of packages readable from the current
source module, so a type from a present-but-unread module can appear.
lathe's javac-backed diagnostics already enforce module readability, so the inconsistency is
between the completion candidate set and the compiler's own accessibility rules.

### Proposed fix

For modular sources, restrict type-completion candidates to types whose package is exported by a
module the current module reads (directly or via `requires transitive`).
This requires resolving the module readability graph for the current source module — the same
information javac already uses to produce the not-visible diagnostic — and intersecting candidates
against it.

This is a usefulness refinement, not a correctness blocker: accepting an unreadable candidate
yields an immediate, accurate diagnostic, so the user is not silently misled.

### Probe commands

```bash
# Open a app-server file importing com.sun.management.OperatingSystemMXBean and confirm the
# not-visible diagnostic, then confirm completion still offers the type for the "Oper" prefix.
python3 dev/lsp.py /path/to/ScratchImports.java
python3 dev/explore.py /path/to/Scratch.java inject "Object o = new Oper"
```

### Regression targets

- `CompletionTypeIndexTest.typeIndex_jpmsObservableButUnreadableModule_doesNotSuggestIndexedType`
- `CompletionTypeIndexTest.typeIndex_jpmsReadablePackage_suggestsIndexedType`

---

## EG-025 — Stale class files from removed or renamed types are never cleaned up

**Status: done — Target: M1**

### Observed behaviour

When a source file is **edited** to remove or rename any type — a nested class, anonymous class,
local class, or package-private sibling top-level type — the corresponding `.class` file persists
indefinitely in `latheClassesDir`.

`deleteClassOutputs()` is called only on file deletion (`didClose` with a removed path),
where it removes `Foo.class`, `Foo$Inner.class`, etc. matching the deleted source.
There is no equivalent cleanup triggered by a successful recompilation of a file that still exists.

A stale `Foo$OldInner.class` or `Helper.class` that no longer corresponds to any source type
remains in `latheClassesDir`, where `ClassFileTypeScanner` picks it up and adds the stale type to
the reactor type index.
This causes removed or renamed types to continue appearing in type-name completion, workspace symbol
search, and type-hierarchy results until the server restarts.

### Scope

The problem affects all class file types produced from a single source file:

| Type | Class file | Covered by `Foo$` prefix? |
|---|---|---|
| Named inner class | `Foo$Inner.class` | Yes |
| Anonymous class | `Foo$1.class` | Yes |
| Local class | `Foo$1LocalName.class` | Yes |
| Package-private sibling | `Helper.class` | **No** |

### Decided implementation — `task.generate()` return value

`JavacTask.generate()` is a public `com.sun.source.util` API already used by Lathe.
It returns `Iterable<? extends JavaFileObject>` — the exact set of class files javac wrote during a
compile, including all `Foo$Inner.class`, `Foo$1.class`, and AP-generated `Foo$Something.class`
files produced in the same run.

The implementation requires changes to three existing classes only.
No new classes, no sidecar files, no in-memory state map.

1. **`JavacRunner.compileFull()`** — switch from `task.call()` to `task.analyze()` +
   `task.generate()`.
   Collect the returned `JavaFileObject`s; convert each to a binary name by resolving its URI
   relative to `latheClassesDir`, replacing `/` with `.`, and stripping the `.class` suffix.

2. **`CompilerResult`** — add `Set<String> writtenBinaryNames` field
   (`Set.of()` for `FAST`/`OPEN`, populated from `task.generate()` for `FULL`).

3. **`WorkspaceSession.deleteStaleClassOutputs(config, savedSource, writtenBinaryNames)`** —
   replace the stub: scan the package dir for files matching `Foo$*.class`; delete any whose binary
   name is absent from `writtenBinaryNames`.
   Call this from `onSave` after `FULL` compile, before `refreshReactorShard`.

AP-generated `Foo$Something.class` files are safe: they are written during the same compile that
produces `Foo.class`, so they appear in `task.generate()` output and are retained.

The package-private sibling case (`Helper.class`) was initially left as a documented gap:
`Helper.class` carries no `Foo$` prefix, and inferring its source from its name would incorrectly
delete AP-generated files that share a name with a type from another source file.

**Since fixed** without name inference or sidecar state: the class file's `SourceFile` attribute
(read by `ClassSourceFile` in lathe-core) names the source it was compiled from.
The save-time prune and the deleted-source cleanup are now one method,
`WorkspaceSession.deleteClassOutputs(config, source, keptBinaryNames)`, which treats a class whose
`SourceFile` is `Foo.java` as one of `Foo.java`'s outputs.
An AP-generated class names its own generated source, so it is never matched; a class compiled
without the attribute (`-g:none`) is always kept.

### Regression tests

`WorkspaceSessionTest` covers the expected behaviour of `deleteClassOutputs`:

- `deleteClassOutputs_javaSource_removesTopLevelAndNestedClassFiles`
- `deleteClassOutputs_nestedAndAnonymousRemoved_deletesOnlyUnkeptOutputsOfTheSource`
- `deleteClassOutputs_packagePrivateSiblingRemoved_deletesOnlyItsOwnSibling`
- `ClassSourceFileTest` (lathe-core) for the attribute reader itself.

---

## EG-026 — Workspace symbol search excludes test classes

**Status: done — Target: M1**

### Observed behaviour

`workspace/symbol` does not return classes declared under `src/test/java`,
even though those files open,
compile,
and produce document symbols.

Dropwizard probe:

```bash
printf 'sym PersonResourceTest expect PersonResourceTest min 1\nsym DropwizardAppExtensionRandomPortsConfigOverrideTest expect DropwizardAppExtensionRandomPortsConfigOverrideTest min 1\nsym ResourceExtension expect ResourceExtension min 1\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/dropwizard/dropwizard-testing/src/test/java/io/dropwizard/testing/junit5/PersonResourceTest.java
```

Observed:

```text
(no symbols found for 'PersonResourceTest')
[FAIL]
  ✗  expected label starting with 'PersonResourceTest' — not found
  ✗  expected ≥1 items, got 0
(no symbols found for 'DropwizardAppExtensionRandomPortsConfigOverrideTest')
[FAIL]
  ✗  expected label starting with 'DropwizardAppExtensionRandomPortsConfigOverrideTest' — not found
  ✗  expected ≥1 items, got 0
1 symbol(s) for 'ResourceExtension':
  [Class] ResourceExtension  io.dropwizard.testing.junit5  /home/ag-libs/git/dropwizard/dropwizard-testing/src/main/java/io/dropwizard/testing/junit5/ResourceExtension.java
[PASS]
```

The same file's document symbols are present:

```bash
printf 'symbols expect PersonResourceTest min 1\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/dropwizard/dropwizard-testing/src/test/java/io/dropwizard/testing/junit5/PersonResourceTest.java
```

Helidon shows the same workspace-symbol omission:

```bash
printf 'symbols expect DiskSpaceHealthCheckTest min 1\nsym DiskSpaceHealthCheckTest expect DiskSpaceHealthCheckTest min 1\nsym DeadlockHealthCheckTest expect DeadlockHealthCheckTest min 1\nsym MemoryHealthCheckTest expect MemoryHealthCheckTest min 1\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/helidon/health/health-checks/src/test/java/io/helidon/health/checks/DiskSpaceHealthCheckTest.java
```

Observed controls:
`DiskSpaceHealthCheckTest` returns document symbols,
but `DiskSpaceHealthCheckTest`,
`DeadlockHealthCheckTest`,
and `MemoryHealthCheckTest` return no workspace-symbol results.

Type-name completion is more mixed:
it can offer the open test class in some contexts,
but sibling test classes are not reliably offered.
That points at workspace type-index coverage rather than a general parser or file-open failure.

### Expected behaviour

Workspace symbol search should include reactor test classes.
Test types are part of the active editing workspace,
and `docs/status.md` already states that classes,
test classes,
and generated sources are mirrored under `.lathe/`.

Search results should preserve the existing source-origin ordering rules once `EG-006` is fixed:
reactor test classes should rank as reactor-local entries,
not as dependency or external entries.

### Proposed fix

Include test-output type entries in the workspace type index shard consumed by `workspace/symbol`.
If the compiler already writes test classes into `.lathe/`,
verify whether `ClassFileTypeScanner`,
`WorkspaceTypeIndex`,
or the workspace-symbol handler filters them out.

Completion should reuse the same candidate source where possible,
so sibling test classes are consistently available when the current source has test-scope access.

### Regression targets

- `WorkspaceSymbolTest.workspaceSymbol_mainClass_andTestClass_sameWorkspace_bothVisible`
- `WorkspaceSymbolTest.workspaceSymbol_mainClass_andTestClass_sameWorkspace_bothVisible`
- `WorkspaceSymbolTest.workspaceSymbol_mainClass_andTestClass_sameWorkspace_bothVisible` (current test-source visibility coverage; the originally proposed sibling-completion test is not present)

---

## EG-027 — Out-of-range LSP positions throw internal errors on navigation endpoints

**Status: done — Target: M2**

### Observed behaviour

Several position-based LSP endpoints throw an internal error when the client sends a line outside the
opened document's range.

Dropwizard probe:

```bash
printf 'refs 9999:0 max 0\nimpl 9999:0 max 0\ncallees 9999:0 max 0\nhierarchy 9999:0\n' \
  | python3 dev/explore.py \
      /home/ag-libs/git/dropwizard/dropwizard-testing/src/test/java/io/dropwizard/testing/junit5/PersonResourceTest.java
```

Observed failures include:

```text
error: textDocument/references error: {'code': -32603, 'message': 'Internal error.', 'data': 'java.util.concurrent.CompletionException: java.lang.ArrayIndexOutOfBoundsException: Index 9999 out of bounds for length 128 ... SourceLocator.toOffset(SourceLocator.java:35) ...'}
error: textDocument/implementation error: {'code': -32603, 'message': 'Internal error.', 'data': 'java.util.concurrent.CompletionException: java.lang.ArrayIndexOutOfBoundsException: Index 9999 out of bounds for length 128 ... SourceLocator.toOffset(SourceLocator.java:35) ...'}
error: textDocument/prepareCallHierarchy error: {'code': -32603, 'message': 'Internal error.', 'data': 'java.util.concurrent.CompletionException: java.lang.ArrayIndexOutOfBoundsException: Index 9999 out of bounds for length 128 ... SourceLocator.toOffset(SourceLocator.java:35) ...'}
error: textDocument/prepareTypeHierarchy error: {'code': -32603, 'message': 'Internal error.', 'data': 'java.util.concurrent.CompletionException: java.lang.ArrayIndexOutOfBoundsException: Index 9999 out of bounds for length 128 ... SourceLocator.toOffset(SourceLocator.java:35) ...'}
```

Controls in the same probe showed that other endpoints degrade cleanly:
`completion 9999:0` returned no completions,
`sig 9999:0` returned no signature help,
`hover 9999:0` returned no hover result,
and `definition 9999:0` returned no definition.

### Expected behaviour

Out-of-range positions should not surface as server internal errors.
For read-only navigation requests,
the server should return an empty result or `null` consistently with hover,
definition,
completion,
and signature help.

Editors should normally send valid positions,
but stale-buffer races,
delayed responses,
or client bugs can still produce impossible coordinates.
Those should be cheap to reject before invoking javac's `LineMap`.

### Proposed fix

Add a bounds check before every call path that resolves an LSP position through
`SourceLocator.toOffset`.
The shared fix should likely live in `SourceLocator.toOffset` or a small wrapper around it,
so references,
implementation,
call hierarchy,
and type hierarchy use the same policy.

Returning `OptionalInt.empty()` or a sentinel failure result is preferable to catching
`ArrayIndexOutOfBoundsException` at each feature boundary.

### Regression targets

- `ReferenceLocatorTest.references_outOfRangePosition_returnsEmpty`
- `MethodImplementationTest.implementation_outOfRangePosition_returnsEmpty`
- `CallHierarchyPrepareTest.prepareCallHierarchy_outOfRangePosition_returnsEmpty`
- `TypeHierarchyTest.prepareTypeHierarchy_outOfRangePosition_returnsEmpty`

---

## EG-033 — Workspace symbol (Telescope) results always jump to the file's first line, not the declaration

**Status: done — Target: M2**

### Observed behaviour

Selecting a type from the workspace-symbol picker (Telescope's `lsp_workspace_symbols`, or any
`workspace/symbol` client) opens the correct file but places the cursor at line 1, column 0 rather
than on the selected type's declaration. For files with a license header, package statement, and
imports, the declaration can be dozens of lines down, so every pick lands far from the symbol.

### Root cause

`WorkspaceSymbolResolver` assigns every result a constant location:

```java
private static final Range FILE_START = new Range(new Position(0, 0), new Position(0, 0));
...
final var location = new Location(file.toUri().toString(), FILE_START); // WorkspaceSymbolResolver.java:37
```

The `TypeIndexEntry` carries the file but no declaration position, so the resolver has nothing better
than `(0,0)` to emit. The range is correct as a *file* reference but useless as a *navigation* target.

### Proposed fix

Carry the declaration's name position in the type index (or resolve it on demand when building the
`SymbolInformation`) and emit a `Range` on the type's identifier rather than `FILE_START`. Reuse
`SourceLocator.declarationNamePosition` (already used by `MethodImplementationLocator`) so the range
matches what definition/implementation navigation produces. If a position cannot be resolved, fall back
to `FILE_START` rather than dropping the symbol.

### Probe commands

```bash
# In Neovim against a workspace: :Telescope lsp_workspace_symbols, pick a type whose declaration is
# below a license header / imports, and observe the cursor lands on line 1 instead of the declaration.
```

### Regression targets

- `WorkspaceSymbolTest.resolve_class_rangePointsToDeclaration` (proposed)
- `WorkspaceSymbolTest.resolve_class_fallsBackToFileStartWhenNoSource` (proposed, guard)

---

## EG-035 — Unused-declaration scan treats an assignment target as a use, so write-only variables are never flagged

**Status: done — Target: M2**

### Observed behaviour

A local variable (or private field) that is assigned but never read is not reported as unused,
even though its value is never observed. The declaration and every write are dead code.

```java
class Test {
  public void method() {
    int count = 0;       // declared
    count = compute();   // assigned, never read
  }
  private int compute() { return 1; }
}
```

`int count` receives an initializer and a later assignment, but the value is never read.
No `Unused local variable 'count'` hint is produced, whereas an unread variable with no assignment
(`int count = 0;` alone) is correctly flagged.

### Root cause

`UnusedDeclarationScanner` counts any reference to a declaration's element as a use.
`visitIdentifier` calls `markReference` for the `IdentifierTree` on the **left-hand side** of an
assignment (`count = ...`), because the scanner has no `visitAssignment` override and does not
distinguish read positions from write positions:

```java
public Void visitIdentifier(final IdentifierTree node, final Void v) {
  if (!declarationPhase) {
    markReference(trees.getElement(getCurrentPath()));   // fires on assignment LHS too
  }
  return super.visitIdentifier(node, v);
}
```

The assignment target is therefore added to `referencedLocals` / `referencedFields`, and the
declaration escapes `collectUnused`. Only a variable with no reference of any kind survives to a
hint, so "assigned but never read" is invisible.

### Scope

Write-only detection applies to `LOCAL_VARIABLE` and `PRIVATE_FIELD` only. Private methods are not
assignable, so the `PRIVATE_METHOD` reachability analysis is unchanged. Both targeted kinds are
confined to a single compilation unit (locals are method-scoped; private members are accessible only
within their declaring top-level class), so the existing per-file scan sees every possible read and
no cross-file analysis is required.

### Proposed fix

Distinguish writes from reads in the reference phase, for locals and private fields. Add a
`visitAssignment` override that scans the right-hand side normally but treats a bare
`IdentifierTree` / `this`-qualified `MemberSelectTree` on the left-hand side as a **write**, not a
use — a pure write must not mark the declaration referenced. Reads through the same node still
count: leave `visitCompoundAssignment` (`+=`, which reads then writes), `visitUnary` (`x++`), and any
use in the RHS or a qualifier/index expression (`a.f = x` reads `a`; `arr[i] = x` reads `arr`)
unchanged so they continue to mark the declaration used. Only the bare simple-assignment target is
suppressed, which keeps false positives near zero at the cost of leaving `++`/`--`-only variables
conservatively counted as used.

Keep the existing behaviour for every non-assignment position, and keep the `Unnecessary` tag,
`lathe.unused` code, and message format from EG-019. A follow-on remove-declaration quick fix would
also need to remove the now-dead assignment statements, which is out of scope here.

### Resolution (2026-07-04)

`UnusedDeclarationScanner` gained a `visitAssignment` override (reference phase). When the LHS is a
pure write target — a bare `IdentifierTree` or a `this`-qualified `MemberSelectTree` — it scans only
the right-hand side (whose reads still count) and skips the target, so a write no longer marks the
declaration used. Every other position is unchanged: `a.f = x` / `arr[i] = x` fall through to the
default traversal (reading the qualifier/index and marking the member), and `+=` / `++` still read
before writing. This keeps false positives near zero at the cost of leaving `++`/`--`-only variables
conservatively counted as used. Scope is `LOCAL_VARIABLE` and `PRIVATE_FIELD` only, both confined to
a single compilation unit, so no cross-file analysis is needed.

### Regression targets

- `UnusedDeclarationScannerTest.compile_localVariableAssignedNeverRead_reportsHint` (write-only local;
  verified failing without the fix)
- `UnusedDeclarationScannerTest.compile_localVariableAssignedThenRead_noHint` (read after assignment)
- `UnusedDeclarationScannerTest.compile_localVariableCompoundAssignment_noHint` (`+=` reads first)
- `UnusedDeclarationScannerTest.compile_privateFieldWriteOnly_reportsHint` (write-only `this.field`;
  verified failing without the fix)

The field-read negative (a read keeps a private field used) is already covered by the existing
`UnusedDeclarationScannerTest.compile_privateFieldReadInMethod_noHint`.

---

## EG-036 — Write-only unused hint reads "Unused", not "assigned but never read"

**Status: done — Target: M2**

### Observed behaviour

After EG-035, a variable that is assigned but never read is flagged, but with the same generic
message as one never referenced at all (`Unused local variable 'count'`). IntelliJ distinguishes the
two — *"Variable 'x' is never used"* vs *"Variable 'x' is assigned but never accessed"* — which tells
the developer whether removing the declaration also means removing live-looking assignments.

### Resolution (2026-07-04)

`UnusedDeclarationScanner` records the target of each suppressed pure-write assignment in an
`assignedTargets` set (populated in `visitAssignment`, kept out of the `referenced*` sets so it still
counts as unused). `unusedDiag` then words the hint by that flag:

- assigned but never read → `"<kind> '<name>' is assigned but never read"`
- never referenced → the existing `"Unused <kind> '<name>'"`

The `lathe.unused` code and `Unnecessary` tag are unchanged, so rendering and any quick fix are
unaffected — only the human-readable text branches. A declaration with only an initializer and no
later assignment (`int x = 0;`) keeps the "Unused" wording, matching IntelliJ (which reserves
"assigned but never accessed" for explicit assignments).

### Regression targets

- `UnusedDeclarationScannerTest.compile_localVariableAssignedNeverRead_reportsHint` (asserts the
  "assigned but never read" wording)
- `UnusedDeclarationScannerTest.compile_privateFieldWriteOnly_reportsHint` (same, for a field)
- `UnusedDeclarationScannerTest.unused_localVariable_messageNamesVariableAndKind` (never-referenced,
  incl. an initialized-only `int x = 42;`, keeps the "Unused" wording)

---

## EG-037 — Body after a wrapped method/constructor declaration is over-indented

**Status: done — Target: M2 (regression)**

### Observed behaviour

When a method or constructor declaration wraps its parameters onto a second line, the first line of
the body (pressing Enter to start coding) indents one continuation level too deep. Validated with the
Neovim indenter (`lathe-maven-plugin/src/main/neovim/lua/lathe/indent.lua`) on the text-heuristic
(mid-edit) path:

```java
void method(
    int a) {
        // body lands at column 6, should be 2
}
```

Headless indent computation returned 6 for the body line (single-line `void method() {` correctly
returned 2).

### Root cause

`continuation_indent`'s `ends_with_block_opener` branch returned `prev_indent + BLOCK_INDENT`. When
the `{` sits at the end of a wrapped declaration's continuation line (`    int a) {`, indent 4), that
adds 2 to the continuation depth → 6, instead of anchoring to the statement's first line
(`void method(` at 0) → 2.

### Fix

Anchor the block body to the statement start:

```lua
if ends_with_block_opener(prev) then
  return statement_start_indent(prev_lnum) + BLOCK_INDENT
end
```

`statement_start_indent` (added for EG-030) walks back across continuation lines; for a non-wrapped
opener it equals `prev_indent`, so the common single-line case, nested blocks, and class bodies are
unchanged.

### Regression targets

`lathe-maven-plugin/src/test/neovim/indent_spec.lua` — two fixtures: body after a wrapped single-param
declaration, and after a multi-line multi-param declaration (which forces `statement_start_indent` to
walk back across several continuation lines). Both expect the body at the declaration's base
indent + 2. A constructor fixture is omitted deliberately: the indenter is text-based and cannot
distinguish a method from a constructor (`Foo(` and `void method(` take the identical path), so it
would add no coverage.

---

## EG-038 — Annotation value completion offers no type candidates for `Class`-typed elements

**Status: done — Target: M2**

### Observed behaviour

Completion in the value position of an annotation whose element is `Class`-typed offers no type
candidates. The canonical case is a JUnit `@ExtendWith`:

```
inject "@ExtendWith(Mockito"   → (no completions)
complete after "@ExtendWith("  → only `null` [Keyword]
```

`org.mockito.junit.jupiter.MockitoExtension` is present in the workspace type index (source extracted
under `~/.cache/lathe/deps/org.mockito:mockito-junit-jupiter:...`) and the files compile cleanly, so
the type resolves — it is simply never offered. A developer typing an extension class inside
`@ExtendWith(...)` (very common in test code) gets nothing.

This is distinct from EG-016 (element names + enum constants), which is implemented and works. The
difference: an annotation with a `value` element is classified as `ANNOTATION_ARGUMENT_VALUE`, and
`@ExtendWith`'s `value` is `Class<? extends Extension>[]`.

### Root cause

`AnnotationCompletionProvider.completeArgumentValue` handles only enum-valued elements (enum constants)
and keywords. It has no branch for a `Class`-typed (or otherwise reference-typed) value element, so
for `@ExtendWith(` it computes the expected type `Class<? extends Extension>[]`, finds no enum
constants, and returns only the `null` keyword — no type candidates.

### Proposed fix

When the annotation element's (array-component) type is `java.lang.Class` (raw or `Class<? extends
Bound>`), offer type-reference candidates in the value position, reusing the existing type-index /
type-reference completion path rather than adding a parallel one.

### Resolution (2026-07-04)

`AnnotationCompletionProvider.isClassValuedElement` detects a `java.lang.Class` value element
(unwrapping an array component). When true, `CompletionEngine.completeAnnotationArgumentValue` runs
the existing `typeReferenceCompleter.completeSimpleNameTypeReferenceWithLang` and merges its type
candidates with the value keywords — the same path the annotation-name (`ANNOTATION_CONTEXT`) and
type-reference cases already use, so no parallel machinery. Validated with `dev/explore.py`:
`inject "@ExtendWith(Mockito"` now returns 27 type candidates including
`MockitoExtension  org.mockito.junit.jupiter.MockitoExtension`.

Deliberately unbounded: candidates are filtered by the typed prefix but not by the wildcard bound
(`Class<? extends Extension>` still offers any matching type), matching how type-reference completion
behaves elsewhere. Bound-filtering to the declared upper bound is a possible future refinement.

### Probe commands

```bash
python3 dev/explore.py /path/to/SomethingTest.java complete after "@ExtendWith("
python3 dev/explore.py /path/to/SomethingTest.java inject "@ExtendWith(Mockito" expect MockitoExtension
```

### Regression targets

- `CompletionAnnotationTest.annotationValue_classTypedElement_offersTypeCandidates` — a
  `Class<?>`-valued element offers a resolvable in-scope type in value position (verified failing
  before the fix, passing after).

---

## FR-002 — External-symbol search scope policy is unresolved

Status: done — Target: M1.
Decision recorded in `docs/done/lathe-find-references.md` section 15.

The original design says JDK and third-party symbols should search open files only.
The implementation instead searches reactor files selected from the cursor module's downstream
graph.

The current implementation is more useful for users asking for project-wide references to common
types, but it can be expensive:

- `String` may select a large part of the workspace;
- common dependency methods may require attribution of many candidate files;
- the result depends on which project module contains the cursor usage.

Restricting external symbols to open files would satisfy the original performance policy but would
make Find References incomplete in a way that is surprising for a workspace operation.

### Recommended direction

Preserve project-wide correctness and improve execution rather than silently restricting results to
open files.

The preferred progression is:

1. retain candidate-index filtering;
2. search all relevant workspace modules when the target is external;
3. add cancellation propagation;
4. support LSP partial results for large result sets;
5. consider a user-visible warning only when candidate counts exceed a measured threshold.

The roadmap's open-file-only statement should not be implemented until this policy is explicitly
confirmed.

### Required measurement

Record candidate count, attributed-file count, elapsed time, and result count for representative
symbols:

- `java.lang.String`;
- `java.time.Duration`;
- one frequently used dependency type;
- one static dependency method;
- one external method reference.

## FR-003 — Failures are converted into empty results

Status: done — Target: M1.
Verified error-handling gap.

The references pipeline currently has two silent-recovery boundaries:

- `SourceAnalysisSession.searchReferences()` catches `IOException`, logs it, and returns an empty
  list;
- `WorkspaceSession.referencesFuture()` catches any exceptional completion, logs it, and returns an
  empty list.

Consequently, the client cannot distinguish:

- a symbol with no references;
- a source-read failure;
- a compiler or attribution failure;
- a worker failure;
- a bug in result aggregation.

This conflicts with the fail-fast policy in
[lathe-m1-refactoring.md](../done/lathe-m1-refactoring.md).

### Required behavior

- Lower layers preserve and propagate failures with useful URI or path context.
- Lower layers do not log and then return an empty result.
- The nearest upstream references-operation boundary logs the failure once with the `Throwable`.
- The LSP request completes exceptionally rather than reporting a successful empty result.
- Legitimate absence, including an unresolved cursor element, remains an empty result.

### Required tests

- A source-read failure completes the references request exceptionally.
- A module-worker failure reaches the upstream request boundary.
- The failure is logged once rather than at both analysis and workspace layers.
- A valid symbol with no references still returns an empty list.

## FR-004 — No end-to-end invoker coverage

Status: done — Target: M1.
Verified test gap.

The Maven invoker `LspSmokeTest` checks that `referencesProvider` is advertised but never sends a
`textDocument/references` request.

Existing server tests cover important pieces independently:

- `ReferenceCandidateIndexTest` covers token and import indexing;
- `ReferenceCandidatePlannerTest` covers explicit imports, wildcard imports, static members, and
  implicit `java.lang.String` candidates;
- `ReferenceLocatorTest` covers attributed identity matching, roles, scope classification, and
  cross-compilation matching.

No test covers the complete path:

```text
LSP request
  -> open-document lookup
  -> cursor target resolution
  -> workspace scope planning
  -> candidate selection
  -> module-worker attribution
  -> Location aggregation
  -> JSON-RPC response
  -> client receipt
```

### Required invoker cases

Extend the existing multi-module LSP smoke test rather than creating a second server launcher.

At minimum:

1. Open a project source file through `didOpen`.
2. Request references for a reactor symbol and assert same-module or cross-module locations.
3. Request references for `java.lang.String` and assert at least one project location.
4. Request references for `java.time.Duration` and assert project locations.
5. Open the cached JDK `Duration.java` returned by definition navigation.
6. Request references from its declaration and assert project locations.

The test must inspect returned URIs and ranges, not only result count.


## FR-008 — Find references on a record component returns nothing

Status: done — Target: M2.

### Observed behaviour

Invoking `textDocument/references` on a record component in the record header returns no results,
even when the component's generated accessor is called across the reactor.

Reduced from a private reactor workspace (identifiers genericized). Given a record

```java
public record AppServerConfig(String bucket, ...) { }
```

and callers such as `AppServer.java`:

```java
new ReportService(config.bucket(), ...);
```

probing the `bucket` component declaration:

```text
def  <line>:<col>   -> resolves to the component declaration (correct)
refs <line>:<col>   -> progress "0 / 1", then "(no references found)"
```

Definition resolves correctly, but references finds nothing despite multiple `config.bucket()`
accessor call sites.

### Root cause

`SourceLocator.elementAt` resolves the cursor on a record-header component to an `Element` of kind
`RECORD_COMPONENT`. In `ReferenceTarget.from`, `RECORD_COMPONENT` has no explicit case and falls
through to the `default` branch, which stores `kind = RECORD_COMPONENT`.

`ReferenceTarget.matches` then rejects every candidate at its first guard,
`element.getKind() != kind`: accessor invocation sites resolve to an `ExecutableElement` of kind
`METHOD` and the backing field to a `VariableElement` of kind `FIELD`, neither of which equals
`RECORD_COMPONENT`. `resolveMethodElement` also short-circuits (`kind != METHOD`), so override-aware
matching never runs. The candidate index still finds the file by name (hence `0 / 1`), but zero
usages match inside it.

### Proposed fix

In `ReferenceTarget.from`, normalise a record component to its generated accessor via a
`recordAccessorFor` helper that covers both the `RECORD_COMPONENT` element and the backing `FIELD`
that javac actually reports for the header (`RecordComponentElement.getAccessor()`). The target then
reuses the existing `METHOD` path: broad candidate discovery, descriptor matching, and
public-accessor reactor scope all apply, so `x.name()` accessor invocations match reactor-wide —
fixing the private-field `DECLARING_FILE` scope that would otherwise prevent any cross-file match.

Other members backing the same component are the same logical member, so `ReferenceTarget.matches`
additionally accepts them via `matchesRecordComponentMember`:

- the backing `FIELD` (in-body reads and writes), and
- the canonical constructor `PARAMETER` that javac reports for compact/canonical constructor bodies
  (`Config { bucket = bucket.trim(); }`).

A same-named `PARAMETER` of a *non-canonical* constructor is a distinct member and is excluded by
comparing the constructor's parameter types against the record components in order. A same-named
component in a *different* record is excluded by the enclosing binary-name check.

Because the backing field and the canonical-constructor parameter share the component's header source
range, `includeDeclaration` would report that single declaration once per synthetic member.
`ReferenceLocator.addMatch` now enforces the invariant that a source range is never reported twice, so
the header declaration appears exactly once.

### Regression targets

- `ReferenceLocatorTest.recordComponent_accessorInvocation_reportedThroughAccessor`
- `ReferenceLocatorTest.recordComponent_backingFieldRead_countedAlongsideAccessor`
- `ReferenceLocatorTest.recordComponent_compactConstructorUses_counted`
- `ReferenceLocatorTest.recordComponent_nonCanonicalConstructorParameter_notMatched`
- `ReferenceLocatorTest.recordComponent_includeDeclaration_reportsHeaderExactlyOnce`
- `ReferenceLocatorTest.recordComponent_sameNameComponentInOtherRecord_notMatched`
- `ReferenceLocatorTest.recordComponent_scope_reactorModulesFromPublicAccessor`


## CA-9 — Common JDK types (`java.util.List`) got no import quick fix in dependency-heavy modules — done

**Status: done — Target: next.**

Signal: user feedback — adding a field typed `List<…>` to a new record in a large module offered no
"Import 'java.util.List'" code action, while an unresolved reactor type in the same file imported
fine.

Design: [Type-Index Name Resolution and Ranking](../done/lathe-type-index-name-resolution.md).

### Observed behaviour

```java
public record Order(List<String> lines) {}   // List unresolved → no import code action
```

`List` was detected as an unresolved `TYPE_REF` (it was listed by `lathe.missingImports`) but resolved
to zero import candidates, so `ImportQuickFixProvider` emitted nothing.

### Root cause

`ImportCandidates.resolve` asked `WorkspaceTypeIndex.search(simpleName, 100)` for the top-100 of the
`List*` **prefix** and filtered to exact matches afterward. `search`/`prefixMatches` ordered matches
only by `binaryName` (after a reactor-first split), with no relevance signal, and applied the limit
*before* the exact-name filter. In a dependency-heavy module, 100+ `List*`-prefixed dependency types
sorted before `java.util.List` (binary name starts `java.`), so the exact match fell outside the
window and never became a candidate. The same starvation hit `java.util.Map`/`Set` and other common
names. Completion (limit 200 + the CQ-0059 usage re-rank) mostly survived — hence the failure was
specific to the import path — while `workspace/symbol` (no re-rank, cap 100) dropped `java.util.List`
entirely.

### Resolution

- **A — exact-name import resolution.** Added `WorkspaceTypeIndex.searchExact(simpleName)`, a direct
  unbounded lookup of the exact simple-name group, and switched `ImportCandidates.resolve` to it.
  Import resolution no longer depends on the search limit or `binaryName` ordering.
- **B — usage-aware truncation.** Made `prefixMatches` order `usageCount(desc) → reactor → binaryName`
  so the cap keeps the types the project actually references. A cold-start index (no counts) ties on
  usage and degrades to the previous `(reactor, binaryName)` order, keeping existing
  completion/symbol/EG-021 expectations green. This subsumes the shared usage signal with completion's
  finer CQ-0059 comparator without relocating it; `workspace/symbol` now returns `java.util.List`
  first in a dependency-heavy workspace.

### Regression targets

- `WorkspaceTypeIndexTest.searchExact_manyPrefixSiblings_returnsAllExactMatchesUnaffectedByLimit`
- `WorkspaceTypeIndexTest.searchAndSearchExact_usageThenOrigin_referencedTypeFirstElsePlatformType`
- `QuickFixCodeActionTest.codeAction_typeRef_manyPrefixSiblingsInIndex_stillOffersExactImport`

---

## CA-8 — Extract Constant/Field/Variable crashed on a synthetic occurrence, killing the whole code-action menu

**Status: done — Target: next.**

At certain positions in record/generated-heavy files, invoking code actions returned nothing: the
server logged `SEVERE [codeAction] failed … ArrayIndexOutOfBoundsException: Index -1` and the whole
request failed, so **no** action was offered — not just the extract one.

Root cause: the replace-all path of the extract refactors mapped every structurally-equal occurrence
to a `TextEdit` via `positions.getStartPosition/getEndPosition`. A record that declares a
non-canonical constructor makes javac synthesize a separate canonical constructor whose nodes carry
no source position (`NOPOS` / `-1`); `SourceLocator.offsetToPosition` had no guard, so `-1` reached
javac's `LineTabMapImpl.getColumnNumber` and threw. Because `ExtractConstantProvider.provide` builds
the edit list eagerly, the exception propagated out of `SourceAnalysisSession.codeAction` and failed
the entire request. The shared `ExtractionSupport.occurrences(...)` helper exposed all three extract
refactors to it.

Fixed on two fronts: the shared `occurrences(...)` now drops any occurrence whose start/end is
`NOPOS` — a synthesized node has no source text to rewrite, so it never belonged in the replace set —
and emits one `FINE` log per request when it skips any (`[extract] occurrences skipped=N synthetic`).
As a defensive backstop, `SourceLocator.offsetToPosition` mirrors `toOffset`'s existing guard and
returns the origin instead of throwing on an invalid offset, so one positionless node can never crash
a whole feature again.

Verified before/after against a large real workspace: the buggy build produced 48 `SEVERE` AIOOBE
failures across several `@Builder` records (including a `…Response.java`); the fixed build produced
zero, with the `skipped=N synthetic` FINE log firing at exactly those positions and the menu
populating normally.

### Regression targets

- `ExtractionSupportTest.occurrences_synthesizedCanonicalConstructor_excludesPositionlessNodes`
- `SourceLocatorTest.offsetToPosition_noPosOffset_returnsOriginInsteadOfThrowing`

---

## CA-1 — `UNREPORTED_EXCEPTION` inside a lambda body has no action

**Status: done — Target: M1.**

### Observed behaviour

```java
Runnable r = () -> { throw new IOException("x"); };
```

Diagnostic: `UNREPORTED_EXCEPTION / java.io.IOException` — correctly classified and published
with a `JsonObject` payload.
Code-action request: returns zero actions.

### Root cause

`AddThrowsProvider.provide()` walks the AST up from the diagnostic position looking for the first
enclosing `MethodTree`.
A lambda body is a `LambdaExpressionTree`, not a `MethodTree`, so the walk continues past it.

When the lambda is a **field initializer** there is no enclosing `MethodTree` at all —
the walk reaches the `CompilationUnitTree` and the path becomes `null`, causing the provider to
return `List.of()`.

When the lambda is inside a **method body**, the walk does find the outer method and offers
"Add throws IOException to method".
This is semantically wrong: the exception cannot propagate past the lambda boundary regardless
of what the outer method declares.

### Proposed fix

Add a `TryCatchWrapProvider` for `UNREPORTED_EXCEPTION` that targets the statement containing
the throw site and wraps it in a `try { … } catch (ExceptionType e) { }` block.

The `AddThrowsProvider` should be suppressed (or ranked lower) when the throw site is inside a
`LambdaExpressionTree` or `AnonymousClassTree`, because adding `throws` to the outer method does
not silence the error.

Detection: walk the path between the diagnostic position and the nearest `MethodTree`;
if a `LambdaExpressionTree` or `NewClassTree` (anonymous class) is encountered along the way,
classify the context as "inside closure" and route to the try/catch provider instead.

**Files to change**: `AddThrowsProvider.java` (suppress in closure context),
new `TryCatchWrapProvider.java`, dispatcher in `SourceAnalysisSession.codeAction()`.

This is the M1 gap also referenced by EG-002.

### Current verification

`QuickFixCodeActionTest.codeAction_unreportedException_lambdaBody_offersOnlyWrap` and
`QuickFixCodeActionTest.codeAction_unreportedException_methodBody_offersBothWrapAndThrows` cover the delivered distinction.
The provider descriptions above describe the original proposal, not missing current classes.

---

## CA-2 — `VARIABLE_REF` has no action

**Status: done — Target: M1.**

### Observed behaviour

```java
void m() { int x = unknownVar + 1; }
```

Diagnostic: `VARIABLE_REF / unknownVar` — correctly classified.
Code-action request: returns zero actions.

### Root cause

`DeclareVariableProvider` does not exist yet.
The dispatcher routes `VARIABLE_REF` to `List.of()`.

### Proposed fix

Implement `DeclareVariableProvider` as described in `lathe-code-actions.md` §2.7:
find the assignment or local-variable declaration at the diagnostic offset,
infer the RHS type via `trees().getTypeMirror(rhsPath)`,
emit `TypeName varName = …` (with import if needed) or `var varName = …` as a fallback.

**Files to change**: new `DeclareVariableProvider.java`, dispatcher in `SourceAnalysisSession`.

### Current verification

`DeclareVariableProvider` is dispatched by `FileFeatures`; the original missing-provider description is historical.
`ConvertCodeActionTest.codeAction_variableRef_intAssignment_declaresLocalVariable` covers assignment-site declaration.
`ConvertCodeActionTest.codeAction_variableRef_rhsOfLocalDecl_returnsEmpty` explicitly refuses an unresolved RHS use; this closure does not claim arbitrary use-site declaration inference.

---

## CA-3 — `MISSING_METHOD_IMPL` is never classified

**Status: done — Target: M1.**

### Observed behaviour

```java
public class Foo implements Runnable { }  // missing run()
```

The compiler emits `compiler.err.does.not.override.abstract`.
The diagnostic arrives with `data = null` — no payload is set.
No code action is offered.

### Root cause

`enrichWithContext()` only handles two diagnostic codes:
`compiler.err.cant.resolve` and `compiler.err.unreported.exception`.
The `MISSING_METHOD_IMPL` `Kind` exists in the enum but the corresponding classification branch
is missing.

`MissingMethodImplProvider` is also not yet implemented.

### Proposed fix

**Part A — classify in `enrichWithContext()`.**
Add a branch for `compiler.err.does.not.override.abstract`.
The message has the form `"Foo is not abstract and does not override abstract method run() in Runnable"`.
Extract the class simple name (first token before `"is not abstract"`) and set
`DiagnosticPayload(MISSING_METHOD_IMPL, className)`.

**Part B — implement `MissingMethodImplProvider`.**
Look up the `ClassTree` for `payload.name()` in the attributed analysis.
Use `elements().getAllMembers(classElement)` to enumerate abstract methods not yet overridden.
Generate `@Override` stubs for each, insert them before the closing `}` of the class body,
and add any needed imports.

**Files to change**: `SourceAnalysisSession.enrichWithContext()` (classification),
new `MissingMethodImplProvider.java`, dispatcher in `SourceAnalysisSession`.

### Current verification

`QuickFixCodeActionTest.compile_doesNotOverrideAbstract_setsMissingMethodImplPayload` and
`QuickFixCodeActionTest.codeAction_missingMethodImpl_singleAbstractMethod_generatesStub` cover the implemented classifier and provider.

---

## CA-4 — `TYPE_REF` in a `throws` declaration with no index match produces no action

**Status: done — Target: M1.**

### Observed behaviour

```java
void m() throws MyCustomException {}
```

When `MyCustomException` is unresolvable, the diagnostic is classified as `TYPE_REF / MyCustomException`
and the payload is published correctly.
If `MyCustomException` is not in the type index, `ImportQuickFixProvider` returns zero actions.

### Root cause

`ImportQuickFixProvider` queries `typeIndex.search(simpleName, 100)` and returns nothing when
there is no matching entry.
This is expected for types that have never been compiled (e.g. a new exception class defined
in the same project but not yet indexed by `lathe:sync`).

### Impact

Medium for M1: this appears when a project type is created or renamed and Lathe has not yet refreshed the reactor
type index through a full sync.
Running `mvn process-test-classes` restores the action,
but the M1 goal is to avoid requiring that round trip when Lathe already has enough local source or reactor-index
information to answer safely.

### Proposed M1 direction

Treat this as a type-index freshness problem rather than an `ImportQuickFixProvider` provider bug.
The fix should make newly-created project types available to missing-import code actions from current reactor source or
fresh in-memory reactor index state,
without weakening the provider's existing type-index validation path for dependencies and JDK types.

### Resolution

Resolved 2026-06-28 (`fix(CA-4): offer import actions for reactor types not yet on the classpath`)
in three parts:

1. `WorkspaceTypeIndex.isReactorType(fqName)` exposes the tracked reactor binary-name set.
2. `ImportQuickFixProvider` trusts a known reactor entry when `getTypeElement()` returns null and
   offers the import without the accessibility guard, preserving that guard for dependency/JDK
   entries.
3. `WorkspaceSession.codeActionFuture()` enriches the type-index snapshot with entries derived from
   already-compiled open files via `CompilationUnitTree.getTypeDecls()` (no text parsing).

This covers types from a prior sync and types declared in an open, already-compiled file. The
residual case — types created or renamed in a **closed** file, discoverable today only after a
Maven sync — is folded into the broader source/branch-switch staleness gap WS-1 (see
[gaps.md](gaps.md)) rather than tracked as a code-action bug.

### Current verification

`QuickFixCodeActionTest.codeAction_typeRef_reactorEntryNotOnClasspath_offersImport` covers the reactor entry.
The later WS-1 write pass also handles closed-file additions; the resolution's closed-file freshness remainder is historical, not a requirement to run Maven today.

---

## CA-5 — No code action to replace `var` with the inferred type

**Status: done — Target: M2.**

A `var` local declaration had no code action to replace `var` with its inferred explicit type. It was
the first **request-driven** (non-diagnostic) code action: `codeAction` dispatched only by
`DiagnosticPayload.Kind`, and a valid `var` raises no diagnostic, so no provider was ever invoked.

Fixed by threading the code-action `Range` through the request (service → `WorkspaceSession` →
`CompilationWorker` → `SourceAnalysisSession`, dropping the empty-diagnostics early return) and adding
a range-driven `ReplaceVarProvider` that runs once per request alongside the diagnostic dispatch. It
locates the enclosing `var` local (attribution erases the `var` type tree to the inferred type with no
source position, so the keyword is found in source via `SourceLocator.findIdentifierFrom`), infers the
type from the element, renders it with `TypeDisplayFormatter` (simple names + generics), and adds an
import via the shared `CodeActionSupport.importEditFor`. The refactor is offered only for **denotable**
kinds — declared (non-anonymous), array, and primitive — skipping anonymous classes, intersection
types, and captured type variables where `var` is the only legal spelling; captures never reach it
because `var` upward-projects them to a denotable type (JLS 14.4.1). `Refactor` kind, no diagnostics.

Regression targets in `CodeActionTest`:
`codeAction_varLocal_offersReplaceWithInferredType` (`String`),
`codeAction_varGenericType_replacesWithGenericsAndAddsImport` (`ArrayList<String>` + import),
`codeAction_varPrimitive_replacesWithPrimitiveType` (`int`),
`codeAction_varUpwardProjectedType_replacesWithProjectedType` (`Number`),
`codeAction_varAnonymousClass_offersNoReplace`, and
`codeAction_explicitTypedLocal_offersNoReplace`.

---

## CQ-0040 — `bind(...).to(...)` chain offers no members on the captured-wildcard result

ID: CQ-0040
Status: done
Target: M1
Tier: typed
Failure mode: missing-candidate
Owner component: TypeResolver / CompletionEngine
Discovery: 2026-06-26, sample-workspace (AppServer anonymous `AbstractBinder.configure()`)

Project/file:
`/workspace/app-server/src/main/java/com/example/app/server/AppServer.java`

Probe command (real source, line 624 has a double `.to(...).to(...)` chain):
```bash
python3 dev/explore.py \
  /workspace/app-server/src/main/java/com/example/app/server/AppServer.java \
  complete after "bind(pipelineService).to(RequestPipelineService.class)."
```

Control probe (first hop works):
```bash
python3 dev/explore.py \
  /workspace/app-server/src/main/java/com/example/app/server/AppServer.java \
  complete after "bind(onboardingService)." expect to min 1
```

Single-`.to()` injection probe (gap reproduces without the double chain):
```bash
printf 'inject "bind(rpcServer).to(RpcServer.class)." at 617\ninject "bind(rpcServer)." at 617\n' \
  | python3 dev/explore.py \
    /workspace/app-server/src/main/java/com/example/app/server/AppServer.java
```

Cursor context:
```java
resourceConfig.register(
    new AbstractBinder() {
      @Override
      protected void configure() {
        bind(onboardingService).§                                  // works: 28 Binding<OnboardingService> members
        bind(pipelineService).to(RequestPipelineService.class).§   // gap: no completions
      }
    });
```

IntelliJ or JDT behavior:
After `bind(x).to(Y.class).`, both IDEs expose the binding-builder's self-type members so the
fluent chain continues — `to`, `in`, `named`, `qualifiedBy`, `ranked`, `proxy`, `proxyForSameScope`.
This is the standard Jersey/HK2 DI registration DSL.

Lathe behavior:
`bind(x).` resolves to a concrete `Binding<T>` and returns 28 members, including the `to` overloads
whose return type is surfaced as `<captured wildcard>`:
```text
to  [Method]  Binding.to(Class<? super RpcServer> contract) : <captured wildcard>
```
Hover on `to` shows the declared signature `D to(Class<? super T> contract)`.
Completing on the result of any `.to(...)` call returns 0 items — the receiver's static type is the
type variable `D` / its captured wildcard, and member discovery on that receiver yields nothing.

Expected Lathe behavior:
Member completion on a captured-wildcard / type-variable receiver should use the capture's effective
upper bound (here the binding-builder self-type), so `bind(x).to(Y.class).` offers `to`, `in`,
`named`, `ranked`, etc., and chained `bind(x).to(Y.class).to(Z.class)` completes.

Accepted edit, if relevant:
Not applicable — no candidate is returned.

Regression target:
`CompletionMemberAccessTest.memberAccess_typeVariableReturn_usesCapturedBound`

Notes:
Same root-cause family as `CQ-0029` (wildcard generic receivers do not expose usable bound members)
and `CQ-0030` (type-variable receivers do not expose declared bounds), both planned for M2.
This entry is pulled into M1 because it breaks completion in the ubiquitous HK2/Jersey `AbstractBinder`
`bind(x).to(Y.class)` registration DSL on a real workspace, not just synthetic generics.
The first `bind(x).` hop is unaffected because it resolves to the concrete `Binding<T>`; the failure
begins at the first `.to(...)`.

Also observed in the same `configure()` body, needs separate isolation before filing:
statement-position identifier completion returned nothing for an inherited-method prefix
(`inject "b"` / `inject "bind"` at line 617 → 0 items, where `bind` was expected), and `inject "this."`
resolved `this` to `TypeLiteral` (from the `new TypeLiteral<...>(){}` at line 658) rather than the
enclosing `AbstractBinder`. A captured-local member access (`inject "onboardingService."`) works
correctly in the same body. These injection-based observations may be incomplete-statement artifacts
and should be reconfirmed against valid syntax before being recorded as gaps.

## CQ-0035 — Parser fails to recognise enclosing method when closing `}` is missing (typed over)

ID: CQ-0035
Status: done
Target: M2
Tier: Basic
Discovery: 2025-07-25, AppServerConfig.java compact constructor (sample-workspace)

Resolved (2026-07-04): validated as no longer reproducing. Re-probing the compact constructor's
overwritten closing brace on the sample workspace now shows `parsed valid=true class=AppServerConfig
method=<init>` with context-aware completions (record component accessors for `n`, the full
`ValidCheck.check()` receiver type for member access) — where the gap recorded `valid=false
class=null method=null` and 0 items. Fixed incidentally by intervening sentinel/parse-recovery
improvements (the deferral note already observed that simple injected cases were recovered by
`forwardScan`; that recovery now covers this brace-count context too).

Regression targets:
- `CompletionSimpleNameTest.simpleName_overwrittenConstructorCloseBrace_recoversEnclosingScope` — a
  reduced compact-constructor fixture whose closing brace is typed over; asserts the enclosing scope
  is recovered and the component is offered. Lightweight guard only: per the note below the hard
  failure needed a complex brace-count context that does not reduce to a minimal fixture, so the
  documented `AppServerConfig.java` explorer probe remains the faithful reproduction.

### Description

When a user accidentally overwrites the closing `}` of a constructor (or method) with new text, the
parser returns `valid=false, class=null, method=null` and 0 completions are returned.
This is a parse-recovery failure: the parser cannot locate the enclosing method scope without the
closing brace, so no context is available and completion bails out entirely.

### Reproduction

Target: `AppServerConfig.java`, inject at line 122 (the compact constructor's `}`):
- `inject "n" at 122` → 0 items, `parsed valid=false sentinelCtx=null class=null method=null`
- `inject "ValidCheck.check()." at 122` → 9 Object methods only, `method=<error>`, `type=null`

In both cases the parser sees code at the class body level (no enclosing `{}`), so
the method context is lost entirely.

### Root cause

Parse error recovery does not synthesise a closing brace when a sentinel injection replaces the
sole `}` that closes a method body.
This is distinct from a normal "open block" recovery because the sentinel is at a position
the parser was already expecting `}` — the recovery heuristic doesn't re-close the block.

### Impact

Edge case: affects only the exact keystroke that replaces the closing `}`.
However, it is a hard failure (0 items, no degraded result), so it is worth noting.

### Fix area

Parser error-recovery in the sentinel-inject pipeline: if `class != null` but `method == null`,
attempt to re-scan backward for the containing method declaration and synthesise a
virtual block close before the sentinel.

### Deferral note

Any fix requires scanning backward through raw source text to locate the enclosing method
declaration — effectively manual Java parsing. Simple injected cases are already handled
correctly by `forwardScan` recovering the missing `}`. The hard failure only occurs in
complex real-world files with specific brace-count contexts (confirmed in AppServerConfig.java).
Deferred pending a cleaner approach.

---

## CQ-0029 — Wildcard generic receivers do not expose usable bound members

ID: CQ-0029
Status: done
Target: M2
Tier: typed
Failure mode: missing-candidates
Owner component: TypeResolver / CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-e2e/src/main/java/com/example/app1/App1Resource.java`

Probe command:
```bash
printf 'diagnostics\ninject "final java.util.Collection<? extends Number> numbers = java.util.List.of(1); numbers.iterator().next()." at 37\nlog 45\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-e2e/src/main/java/com/example/app1/App1Resource.java
```

Related probes:
```bash
printf 'diagnostics\ninject "final java.util.List<? extends Number> numbers = java.util.List.of(1); numbers.get(0)." at 37\nlog 45\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-e2e/src/main/java/com/example/app1/App1Resource.java

printf 'diagnostics\ninject "final java.util.Map<String, ? extends Number> numbers = java.util.Map.of(\"x\", 1); numbers.get(\"x\")." at 37\nlog 45\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-e2e/src/main/java/com/example/app1/App1Resource.java

printf 'diagnostics\ninject "final java.util.Collection<?> values = java.util.List.of(\"a\"); values.iterator().next()." at 37\nlog 45\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-e2e/src/main/java/com/example/app1/App1Resource.java
```

Cursor context:
```java
final java.util.Collection<? extends Number> numbers = java.util.List.of(1);
numbers.iterator().next().§

final java.util.List<? extends Number> numbers = java.util.List.of(1);
numbers.get(0).§

final java.util.Map<String, ? extends Number> numbers = java.util.Map.of("x", 1);
numbers.get("x").§

final java.util.Collection<?> values = java.util.List.of("a");
values.iterator().next().§
```

IntelliJ or JDT behavior:
Expected IDE behavior is to expose members from the capture's usable upper bound.
For `? extends Number`,
member completion should show `Number` methods such as `intValue`,
`longValue`,
and `doubleValue`.
For unbounded `?`,
member completion should at least show `Object` methods.

Original Lathe behavior:
All wildcard probes returned no completion items.
The log showed `sentinelCtx=MEMBER_ACCESS` but receiver resolution failed:
```text
resolve receiver=|numbers.iterator().next()| type=null static=null reattributed=true
resolve receiver=|numbers.get(0)| type=null static=null reattributed=true
resolve receiver=|numbers.get("x")| type=null static=null reattributed=true
resolve receiver=|values.iterator().next()| type=null static=null reattributed=true
```

Current Lathe behavior:
Retested on current code after adding regression coverage.
The documented probes now return the expected `Number` methods for `? extends Number`
and `Object` methods for unbounded `?`.

Expected Lathe behavior:
When a generic member returns a captured wildcard,
Lathe should use the capture's upper bound for completion.
Examples:

- `Collection<? extends Number>.iterator().next().§` should complete as `Number`.
- `List<? extends Number>.get(0).§` should complete as `Number`.
- `Map<String, ? extends Number>.get("x").§` should complete as `Number`.
- `Collection<?>.iterator().next().§` should complete as `Object`.

Accepted edit, if relevant:
Not applicable.
No candidate is returned.

Regression coverage:
`CompletionMemberAccessTest.memberAccess_wildcardExtendsCollectionElement_usesUpperBound`
`CompletionMemberAccessTest.memberAccess_wildcardExtendsListGet_usesUpperBound`
`CompletionMemberAccessTest.memberAccess_wildcardExtendsMapValue_usesUpperBound`
`CompletionMemberAccessTest.memberAccess_unboundedWildcardCollectionElement_usesObjectBound`

Notes:
Non-wildcard generic controls work correctly:
`Map<String, String>.entrySet().iterator().next().§` returns `Entry.getKey() : String`
and `Entry.getValue() : String`;
`Map<String, List<String>>.get("x").§` returns `List<String>` methods;
`Map<String, List<String>>.get("x").get(0).§` returns `String` methods;
and `Collection<String>.iterator().next().§` returns `String` methods.

## CQ-0030 — Type-variable receivers do not expose declared bounds

ID: CQ-0030
Status: done
Target: M2
Tier: typed
Failure mode: missing-candidates
Owner component: TypeResolver / CompletionEngine

Project/file:
`/home/ag-libs/git/dropwizard/dropwizard-core/src/main/java/io/dropwizard/core/setup/Bootstrap.java`

Probe command:
```bash
printf 'diagnostics\ninject "configuration." at 199\nlog 45\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-core/src/main/java/io/dropwizard/core/setup/Bootstrap.java
```

Related probes:
```bash
printf 'diagnostics\ninject "public <T extends java.util.Collection<String>> void use(T value) { value.§ }" at 40\nlog 50\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-e2e/src/main/java/com/example/app1/App1Resource.java

printf 'diagnostics\ninject "return call.call()." at 181\nlog 45\n' \
  | python3 dev/explore.py /home/ag-libs/git/dropwizard/dropwizard-testing/src/main/java/io/dropwizard/testing/common/DAOTest.java
```

Cursor context:
```java
public class Bootstrap<T extends Configuration> {
    public void run(T configuration, Environment environment) throws Exception {
        configuration.§
    }
}

public <T extends java.util.Collection<String>> void use(T value) {
    value.§
}

public <T> T inTransaction(Callable<T> call) {
    return call.call().§
}
```

IntelliJ or JDT behavior:
Expected IDE behavior is to expose members available through the type variable's declared bound.
For `T extends Configuration`,
completion should show `Configuration` and `Object` members.
For `T extends Collection<String>`,
completion should show `Collection<String>` members.
For unbounded `T`,
completion should at least show `Object` members.

Original Lathe behavior:
Bounded type-variable receivers returned no completion items.
The log preserved the type variable but did not expand its bound:
```text
resolve receiver=|configuration| type=T static=false reattributed=false
proposals count=0 labels=[]

resolve receiver=|value| type=T static=false reattributed=true
proposals count=0 labels=[]
```

For the generic method return probe,
`Callable<T>.call().§` also returns no items:
```text
resolve receiver=|call.call()| type=null static=null reattributed=true
```

Resolution (7c38a0b — `fix: recover method-chain member completion`):
Both dimensions now resolve. Direct source fixtures resolve type-variable receivers (class type
variables, method type variables, unbounded method type variables, and unbounded
`Callable<T>.call()` returns), and the stale-cache/edit-buffer path is recovered. When the cached
attributed source is the previous valid file and the changed buffer adds `configuration.§`,
sentinel attribution is now used as a local recovery analysis so a `TYPEVAR` receiver is normalized
to its declared bound before candidate generation, and the recovery result is not cached as if it
represented the real editor content. `memberAccess_classTypeVariable_afterChange_usesDeclaredBound`
covers the recovered stale-cache case.

Expected Lathe behavior:
Type-variable member completion should use the effective upper bound.
If the bound is parameterized,
the substituted type should be used for method signatures:
for `T extends Collection<String>`,
`iterator()` should be shown as returning `Iterator<String>`,
`stream()` as `Stream<String>`,
and `forEach` as accepting `Consumer<? super String>`.

Suggested fix:
Treat a resolved `TYPEVAR` receiver from cached analysis as insufficient for member access.
Either:

- force fresh reattribution in `MemberAccessCompleter` when `initialResolved.type().getKind()` is
  `TYPEVAR`, just as it already does for `null` and `ERROR`; or
- normalize receiver types through a shared effective-completion-type helper before candidate
  generation,
  so `T` becomes its upper bound before `CandidateGenerator.proposeMemberAccessCandidates(...)`.

The second option is cleaner long-term because it also centralizes wildcard,
type-variable,
and future error-type fallback logic,
but the first option is likely the minimal fix for the currently reproduced stale-cache failure.

Accepted edit, if relevant:
Not applicable.
No candidate is returned.

Regression target:
`CompletionMemberAccessTest.memberAccess_classTypeVariable_usesDeclaredBound`
`CompletionMemberAccessTest.memberAccess_classTypeVariable_afterChange_usesDeclaredBound`
`CompletionMemberAccessTest.memberAccess_methodTypeVariable_usesDeclaredBound`
`CompletionMemberAccessTest.memberAccess_unboundedMethodTypeVariable_usesObjectBound`
`CompletionMemberAccessTest.memberAccess_unboundedTypeVariableMethodReturn_usesObjectBound`

Notes:
Generic type-reference completion while declaring bounds works:
`public <T extends RuntimeEx§> T identity(T value) { return value; }`
offers `RuntimeException` and accepts to
`public <T extends RuntimeException§> T identity(T value) { return value; }`.
Local generic class bounds also work for
`class Local<T extends RuntimeEx§`.

## CQ-0042 — Member access on a type-error receiver returns no candidates

ID: CQ-0042
Status: done
Target: M2
Tier: typed
Failure mode: missing-candidates
Owner component: TypeResolver / CompletionEngine
Discovery: 2026-06-28, CQ-0040 regression test authoring

Cursor context:
```java
// to() declared as: <D extends Binding<D>> D to(Class<? super T> contract)
// T inferred as Object; Runnable.class is Class<Runnable>, not Class<? super Object>
bind(new Object()).to(Runnable.class).§
```

Lathe behavior:
When the receiver expression has a compile error (javac attributes it as `TypeKind.ERROR`),
`TypeResolver.resolveByPosition` gets `null` from `effectiveDeclaredType` and returns no receiver.
Completion returns 0 items.

Expected Lathe behavior:
When `getTypeMirror` returns `ERROR` for a method invocation, fall back to the method element's
declared return type (obtained via `trees().getElement()` on the invocation path cast to
`ExecutableElement`).
Apply the same type-variable and wildcard unwrapping so the effective declared type is used for
member lookup.
This would expose `Binding` members even though the argument type is wrong — the user is
mid-edit and still wants completion to continue.

Root cause:
`effectiveDeclaredType` in `TypeResolver` returns `null` for `TypeKind.ERROR`.
No fallback to the element-level return type is attempted.

Suggested fix:
Keep javac as the source of truth.
Do not parse the invocation text.

1. In the receiver-resolution path,
   when the current path is a method invocation whose attributed type is `TypeKind.ERROR`,
   ask `Trees.getElement(path)` for the invoked method element.
2. If the element is an `ExecutableElement`,
   use its declared return type as a recovery candidate.
3. Run that candidate through the same effective-completion-type logic used for captured wildcards
   and type variables,
   so `<D extends Binding<D>> D` resolves to the usable `Binding` bound.
4. Keep the fallback narrow:
   only use it for method-invocation receivers with an `ERROR` attributed type.
   Do not broaden arbitrary unresolved expressions to declared-element fallback,
   because an unresolved variable or malformed selector does not have an equivalent declared return
   type.

Regression coverage:
`CompletionMemberAccessTest.memberAccess_typeErrorReceiver_fallsBackToElementReturnType`
and `CompletionMemberAccessTest.memberAccess_typeErrorMethodChain_returnsMembersWithoutFreshAnalysis`
are enabled and passing after the 7c38a0b fix. The error-typed method invocation's return type is
now recovered through javac trees and elements — with a conservative same-name/arity fallback when
javac misattributes the selector during recovery — and the recovery analysis is not returned as a
cacheable sentinel.

Notes:
Same root-cause family as CQ-0029, CQ-0030, and CQ-0040.
CQ-0040 (type-variable return, no type error) is fixed for M1.
This entry covers the error-recovery dimension: the call itself is type-incorrect but the
return type is still knowable from the method declaration.
It is also distinct from archived CQ-0032,
which forced reattribution when a stale snapshot had already resolved the receiver to
`TypeKind.ERROR`.

---

## CQ-0044 — Member access on a `var` local declared inside a lambda body returns no candidates

ID: CQ-0044
Status: done
Target: M2
Tier: typed
Failure mode: missing-candidates
Owner component: TypeResolver / CandidateGenerator
Discovery: 2026-06-29, real-workspace validation pass
Resolution: 2026-07-02, current explorer validation shows this no longer reproduces

Cursor context:
```java
// inside a forEach lambda block body
items.forEach(
    item -> {
      final var runner = new Runner(client, item);
      runner.§
    });
```

Lathe behavior:
Completion after `runner.` returns an **empty popup** — zero members.
`Runner` is a concrete type with a `public void validate()` declared directly on it, so the
expected behavior is that `validate()` (and the rest of its members) are offered.

Current behavior:
Completion after `runner.` now offers `Runner.name()`, `Runner.validate()`, and inherited
`Object` methods for all isolation probes below.
The original failing shape, an explicitly typed local inside the same lambda, the same `var` local
outside the lambda, and direct `new Runner().§` member access all pass through explorer.

Expected Lathe behavior:
Offer the members of `Runner`, including `validate()`, exactly as for any other concrete
receiver.

Root cause (hypothesis, to be verified):
The receiver is a `var` local whose type is *inferred* from `new Runner(...)`, and it is
declared **inside a lambda block body** that is incomplete while the user is typing `runner.`.
When the snapshot cannot fully attribute the enclosing lambda body, the `var` local has no resolved
declared type, so `TypeResolver.resolveReceiver` yields no receiver and `CandidateGenerator`
produces zero candidates.
The fact that `Runner` comes from a dependency JAR is believed incidental — member access on
dependency-JAR concrete types works elsewhere (Dropwizard/Helidon fluent chains).
The distinguishing factors are (1) `var` inference and (2) the lambda-body-under-edit recovery path.

Reproduction probes (to isolate the decisive factor):
1. Change `var` to an explicit `Runner runner = ...` and retype `.` — if members
   appear, the `var` inference path is the cause.
2. Move the same `var runner = new Runner(...)` + `runner.` out of the
   lambda to plain method-body scope — if members appear, the lambda-body recovery is the trigger.
3. Try `new Runner(...).§` directly with no local — isolates constructor/JAR resolution.

Suggested fix:
No code fix is currently needed.
Keep this entry as a regression note for the lambda-local `var` shape; if it regresses, preserve the
constraint that javac remains the source of truth and avoid parsing the lambda or declaration text.

Notes:
Adjacent to CQ-0042 (type-error receiver returns no candidates) but distinct: there the receiver
expression has an explicit compile error in a method chain; here the receiver is a well-formed `var`
local whose inferred type is unavailable during in-lambda editing.
Not a match for CQ-0029/CQ-0030/CQ-0040 (wildcard, type-variable, and captured-wildcard receivers) —
no generics are involved.

---

## CQ-0045 — Local-variable and parameter completion items carry no type detail

ID: CQ-0045
Status: done
Target: M2
Tier: presentation
Failure mode: missing-detail
Owner component: CandidateFactory
Discovery: 2026-06-30, gap validation pass

Cursor context:
```java
class Test {
    void m() {
        String greeting = "hi";
        System.out.println(gree§);
    }
}
```

Lathe behavior:
The `greeting` completion item is offered but its `detail`, `labelDetails.detail`, and
`labelDescription` are all `null`, so the popup shows only the bare name `greeting` with no type.
A field of the same name and type, by contrast, is presented with `detail = "String"` and
`labelDetails.description = "String"`.
The type is known at candidate-build time — it is stored on the candidate's `valueType` and used for
ranking — but it is never rendered.

Confirmed by a probe at the cursor above: the local-variable item resolves to
`label=greeting detail=null labelDetails=null`, while the field variant resolves to
`label=greeting detail=String labelDescription=String`.

Expected Lathe behavior:
Local-variable and method-parameter items should show their type the same way fields do —
`labelDescription` (and/or `detail`) set to the formatted type, so the popup reads `greeting : String`.
Presentation only; semantic filtering and ranking are unchanged.

Root cause:
`CandidateFactory.variableCandidate(name, type)` constructs the candidate through the 10-arg
`CompletionCandidate` constructor with `detail = null` and no `labelDetail`/`labelDescription`,
passing the type solely as `valueType`:

```java
CompletionCandidate variableCandidate(final String name, final TypeMirror type) {
  return new CompletionCandidate(
      name, name, CandidateKind.LOCAL_VARIABLE, null, name, false, null, type, null, null);
}
```

`fieldCandidate`, in contrast, formats the type via `TypeDisplayFormatter` and passes it as `detail`
and `labelDescription`.
`CompletionItemPresenter` faithfully renders whatever the candidate carries, so the empty detail
originates entirely in `variableCandidate`.

Suggested fix:
Format `type` with the existing `TypeDisplayFormatter` and pass it as `labelDescription` (mirroring
`fieldCandidate`) so locals, parameters, and fields present consistently.
Guard the `null`-type case (parameters whose element did not resolve are added with a `null` type in
`SimpleNameProvider.addMethodLocals`): omit the detail when the type is unknown rather than rendering
a placeholder.
Keep `valueType` for ranking. No change to candidate discovery or filtering.

Regression targets (added, `@Disabled` pending the fix):
- `CompletionPresentationTest.completionItem_localVariable_usesFormattedTypeDetail`
- `CompletionPresentationTest.completionItem_methodParameter_usesFormattedTypeDetail`

Notes:
Distinct from the typed-tier completion gaps (CQ-0029/0030/0040/0042/0044), which are about *missing
candidates*; here the candidate is present and correctly ranked, only its display detail is absent.
This is a presentation-tier gap — the tier the completion
[expectations](../done/lathe-completion-expectations.md) defines as the "label vs detail"
separation.

---

## CQ-0046 — Boolean members are dropped in constructor-call argument completion, emptying the popup

ID: CQ-0046
Status: done
Target: M2
Tier: typed
Failure mode: missing-candidate
Owner component: TypeResolver / CompletionCandidateRanker
Discovery: 2026-06-30, real-workspace validation pass (`AppServer` constructor wiring)

Cursor context:
```java
class Config {
    boolean isReady() { return true; }
    String name() { return ""; }
}
class Service {
    Service(boolean flag) {}
}
class Test {
    void m() {
        Config config = new Config();
        final var svc = new Service(config.§);   // member access at a constructor boolean slot
    }
}
```

Lathe behavior:
Member-access completion after `config.` inside a **constructor** argument drops every
boolean-returning member.
`isReady()` (returns `boolean`) is absent while `name()` (returns `String`) is offered.
When the typed prefix matches only the boolean member (`config.isR§`), the popup is **empty**.
The same `config.` at a **method-call** argument slot (`accept(config.§)`) correctly offers
`isReady()`.

Confirmed by probes:
- `new Service(config.§)` → `[name, equals, getClass, hashCode, toString]` — `isReady` excluded.
- `new Service(config.isR§)` → `[]` — empty popup.
- `accept(config.§)` (method call, same types) → `[isReady, ...]` — present.
The defect is **not** `var`-specific: an explicitly-typed target (`final Service svc = ...`) behaves
identically, and it reproduces at any constructor argument index (observed on a real workspace at both
the first and the sixth argument of two different constructor calls).

Expected Lathe behavior:
A boolean-returning member is a valid completion at a `boolean` constructor argument and must be
offered (and, when the slot is boolean, ranked first).
More generally, constructor-argument slots should resolve the expected type from the constructor
parameter, exactly as method-argument slots do.

Root cause:
Two defects compound.

1. **Constructor-argument expected-type resolution is missing for the by-position path.**
   `TypeResolver.resolveArgumentValueByPosition` overrides only `visitMethodInvocation`; it never
   visits `NewClassTree`. For a member-access cursor inside a constructor argument, neither the
   name-based `resolveArgumentValue` nor the position-based scan resolves the constructor parameter
   type, so resolution falls through to `resolveInitializerValue`, which yields the **constructed /
   declared** type (here `Service`, a non-boolean) as the expected value.
2. **The asymmetric boolean-only filter then deletes the candidate.**
   With a non-boolean expected type in hand, `CompletionCandidateRanker.expectedTypeAllows`
   hard-excludes any candidate whose value type is `TypeKind.BOOLEAN` (returning
   `booleanCompatible(expected)`), while non-boolean mismatches fall through to `return true`. This is
   the exact asymmetry recorded in CQ-0043; it is what removes `isReady()` while keeping `name()`.

Suggested fix:
Keep javac as the source of truth; route through the existing sentinel/recovery pipeline rather than
parsing.

1. Resolve constructor-argument expected types by position: handle `NewClassTree` in the
   argument-by-position scan (mirroring the `visitMethodInvocation` branch and the existing
   `resolveConstructorArgumentValue` lookup) so the `boolean` parameter type is recovered for a
   member-access cursor.
2. Fix CQ-0043 (rank rather than hard-exclude boolean value types) so a boolean member is never
   *deleted* even when the expected type is mis-resolved — only demoted. Either fix alone restores the
   user-visible candidate; both are warranted, and they should be implemented together.

Regression targets:
- `CompletionArgumentTest.constructorArgument_memberAccess_booleanReturn_offeredAtBooleanSlot` (added, `@Disabled`)
- `CompletionArgumentTest.constructorArgument_booleanPrefix_popupNotEmpty` (added, `@Disabled`)
- `CompletionArgumentTest.constructorArgument_memberAccess_booleanReturn_offeredAtBooleanSlot` (proposed; no `TypeResolverTest` exists yet)

Notes:
This is the real-workspace reproduction of CQ-0043: the user's symptom (an empty popup after
`config.isR` in `new Service(config.isReady())`) is the boolean-only exclusion firing against a
mis-resolved constructor-argument expected type.
Related to CQ-0044 (member access under in-edit `var` in a lambda) only superficially — there the
receiver type is unavailable; here the receiver (`config`) resolves fine and its members are computed,
but the boolean ones are filtered out.

---

## CQ-0048 — `instanceof` is never offered as a keyword candidate in expression position

ID: CQ-0048
Status: done
Target: M2
Tier: assistive
Failure mode: missing-candidate
Owner component: KeywordProvider
Resolution: 2026-07-02, `instanceof` is offered after reference-typed expressions and suppressed after primitives

Cursor context:
```java
class Test {
    void m(Object o) {
        if (o ins§) {}        // expect: instanceof
        boolean b = o ins§;   // expect: instanceof
    }
}
```

Lathe behavior:
`instanceof` is never suggested. Code inspection confirms the literal `"instanceof"` appears in **no**
keyword list in `KeywordProvider` (`VALUE_EXPRESSIONS`, `CONTROL_FLOW`, etc.) and nowhere else in the
completion module, so no sentinel context can ever produce it. After a reference-typed expression in an
expression slot — where `instanceof` is the natural continuation — completion offers only the
value-expression starters (`new`, `null`, `true`, `false`, `this`, `super`).

Expected Lathe behavior:
When the cursor follows a **reference-typed** expression in a position where a boolean/expression
continuation is legal, `instanceof` should be offered as a keyword candidate (accepting it would yield
`o instanceof `, ready for a type). It must **not** be offered after a primitive-typed expression, where
`instanceof` is illegal, nor as a standalone statement starter.

Root cause:
`KeywordProvider` has no `instanceof` entry in any list, and `selectKeywords` for an expression-position
`SIMPLE_NAME` returns `VALUE_EXPRESSIONS`, which contains only value starters. There is no rule that
inspects the preceding expression's type to offer the infix `instanceof` operator keyword.

Suggested fix:
Add `instanceof` as a keyword candidate offered in expression position when the preceding expression
resolves (via javac attribution, not text scanning) to a reference type. Gate it on the receiver type
being non-primitive so it is suppressed for primitive expressions.

Implemented behavior:
The simple-name completion path now supplements keyword candidates with `instanceof` when javac's
recovered AST and attribution identify the preceding expression as reference-typed.
Primitive expressions and statement-start keyword slots do not receive the candidate.

Regression targets:
- `CompletionSimpleNameTest.simpleName_afterReferenceExpression_offersInstanceof`
- `CompletionSimpleNameTest.simpleName_afterPrimitiveExpression_omitsInstanceof`
- `CompletionSimpleNameTest.simpleName_atStatementStart_omitsInstanceof`

Notes:
Distinct from CQ-0011 (constructor-invocation keyword over-offering): this is a *missing* assistive
keyword, not an invalid one. The type-gating requirement (reference vs primitive LHS) is what keeps it
from being a plain unconditional keyword addition.

---

## CQ-0049 — Type-index completion offers types from modules the current module does not read

ID: CQ-0049
Status: done
Target: M1
Tier: correctness
Failure mode: invalid-candidate
Owner component: TypeIndexValidator

Cursor context:
```java
// module-info.java:  module com.example.app { }   (no `requires java.desktop`)
package com.example.app;

class Test {
    JBut§ field;    // expect: no JButton — javax.swing is not readable here
}
```

Lathe behavior:
`JButton` (`javax.swing.JButton`, module `java.desktop`) is offered as a completion candidate even
though the enclosing module never `requires java.desktop`. Confirmed on the `sample-workspace`
workspace: in `app-core` (whose `module-info.java` does not read `java.desktop`) Swing and other
unread-module types complete via the type-index path, while a real `import javax.swing.JButton;`
correctly fails to compile. `java.desktop` is pulled into the module graph by a dependency's
non-transitive `requires`, so it is *observable* but not *readable* by `app-core`.

Expected Lathe behavior:
Type-index candidates must be filtered by JPMS readability from the current compilation unit's
module, matching what a real `import` would accept. A type in a module the current module does not
read must not be offered. `java.base` types and types in read modules continue to be offered.

Root cause:
`TypeIndexValidator.isResolvable` gates candidates on `Elements.getTypeElement(qualifiedName) != null`
(`TypeIndexValidator.java:30`). Single-arg `getTypeElement` performs a global lookup across every
*observable* module in the graph and ignores whether the current module *reads* the type's module, so
observable-but-unreadable types pass the filter. The correct primitive is
`Trees.isAccessible(scope, typeElement)`, already used on the member-access path
(`CandidateGenerator`) and the import path (`ImportCompletionProvider.java:70`); the type-index path
is the only one that skips it. The comment in `TypeIndexValidator` claiming `getTypeElement` "follows
JPMS module-boundary semantics automatically" is incorrect — it conflates observability with
readability.

Suggested fix:
Gate `TypeIndexValidator` on `Trees.isAccessible(scope, typeElement)` in addition to resolving the
element, threading the completion `scope` (already computed in
`TypeReferenceCompleter.completeSimpleNameTypeReference`) into the validator, and computing a scope
for the `CompletionEngine.staticMemberFitCandidates` call site. Preserve the permissive fallback when
no scope is available (mirroring `ImportCompletionProvider`'s `scope == null || …` guard).

Resolved: 2026-07-04. Two leak sites shared the same root cause and were both gated on
`Trees.isAccessible(scope, typeElement)`:
1. `TypeIndexValidator` (plain type-reference via `TypeReferenceCompleter.completeSimpleNameTypeReference`
   and static-member-fit via `CompletionEngine.staticMemberFitCandidates`) — the validator now takes the
   completion `Scope` and enforces readability.
2. `TypeReferenceCompleter.resolvesToInstantiableSubtype` (the `new X` / expected-type constructor-subtype
   path, which never used the validator) — gained the same gate. The first pass missed this because the
   original regression test only exercised the plain type-reference form; a live probe of `new JBut` on
   `app-core` surfaced it.

Regression targets:
- `CompletionTypeIndexTest.typeIndex_jpmsObservableButUnreadableModule_doesNotSuggestIndexedType`
  (enabled — plain type-reference path; reproduces via `--add-modules java.desktop` on a module that
  does not `requires` it)
- `CompletionTypeIndexTest.typeIndex_constructorSubtype_jpmsUnreadableModule_doesNotSuggestSubtype`
  (the `new X` subtype path; verified failing without the fix)
- `CompletionTypeIndexTest.typeIndex_constructorSubtype_jpmsReadablePackage_suggestsSubtype`
  (guard: readable subtype still offered)
- `CompletionTypeIndexTest.typeIndex_jpmsReadablePackage_suggestsIndexedType` (guard: `requires
  java.desktop` still offers `JButton`)
- `CompletionTypeIndexTest.typeIndex_platformType_survivesValidator_jpmsModule` (guard: `java.base`
  types survive)

Notes:
The member-access and import paths already enforce readability via `Trees.isAccessible`; only the two
type-index paths above skipped it. The unit test that previously "covered" this
(`typeIndex_jpmsUnreadablePackage_doesNotSuggestIndexedType`) passed only because its minimal module
graph never made `java.desktop` observable, so `getTypeElement` returned `null` for the wrong reason
— it never exercised the observable-but-unreadable case that occurs in real multi-module workspaces.

---

## CQ-0011 — Constructor invocation keywords can be offered when an explicit invocation already exists

ID: CQ-0011
Status: non-goal
Target: backlog
Tier: semantic
Failure mode: invalid-keyword-candidate
Owner component: KeywordProvider / SentinelParser

Closed (2026-07-04) as non-goal: suppressing the `this`/`super` keyword to avoid a second explicit
invocation is the wrong treatment (see re-triage below). The only valid residual — a
`this(...)`/`super(...)` call-shape snippet offered at the legal first-statement slot — is a separate
future completion feature, not this suppression gap.

Re-triage (2026-07-01):
Deferred out of M2. On review the accepted M2 approach — withholding the `this`/`super` keywords in
constructor statement slots — is wrong from a developer's point of view. Lathe's keyword completion
only ever inserts the bare identifier `this`/`super`, which is a valid and very common expression at
every statement position in a constructor (`this.field = x;`, `super.init();`). Suppressing it to
avoid the rare, self-diagnosing case of a second explicit invocation would remove a constantly
needed completion and would violate this gap's own note ("should not block ordinary `this`
expression completion"). The correct treatment is a future `this(...)`/`super(...)` call-shape
snippet offered *only* at the legal first-statement slot, alongside the always-available bare
keyword — a completion feature, not a suppression — which is blocked until constructor-invocation
snippet completion exists.

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

---

## CQ-0050 — Member-access completion crashes when its result feeds a typed value slot

ID: CQ-0050
Status: done
Tier: typed
Failure mode: server-side crash (empty completion)
Owner component: CompletionCandidateRanker

Discovered by exploration probing of a JAX-RS resource: `@Produces(MediaType.<caret>APPLICATION_JSON)`
returned no completions. Context detection was correct (`sentinelCtx=MEMBER_ACCESS`, receiver
resolved to `jakarta.ws.rs.core.MediaType`); the ranker then crashed and the empty result surfaced
via the `WorkspaceSession` completion catch — so it degraded silently ("no completions", no visible
error).

### Root cause

`CompletionCandidateRanker.sortText` used `context.analysis().types().isAssignable(candidate
.valueType(), type)` as a value-slot ranking tie-break, reached only when `expectedValue` is an
`ExpectedValue.Type` (e.g. an annotation element's declared type). `JavacTypes.isAssignable` throws
`IllegalArgumentException` (via `validateTypeNotIn`) when handed a mirror whose kind is not a value
type — a candidate whose `valueType()` is a package/executable/module/etc. mirror. That reached the
ranker on the **attributed, complete** member-access path (an existing identifier after the cursor,
as in real code), which is why an incomplete sentinel probe (`MediaType.<caret>` with nothing after)
did not reproduce it.

```
java.lang.IllegalArgumentException
  at com.sun.tools.javac.model.JavacTypes.validateTypeNotIn(JavacTypes.java:326)
  at com.sun.tools.javac.model.JavacTypes.isAssignable(JavacTypes.java:107)
  at CompletionCandidateRanker.sortText(CompletionCandidateRanker.java:62)
```

### Resolution

The assignability check is a best-effort ranking signal, so it must never abort completion. Extracted
`assignableToExpected(valueType, expected, context)`: returns `false` for a null `valueType` and
catches `IllegalArgumentException` from `isAssignable` (a non-value-type candidate is simply not
assignable to a value slot). Single-class change; no behavior change beyond not crashing.

Distinct from the resolved **CQ-0001** (enum-typed annotation values → enum constants) and **EG-016**
(annotation element-name completion): this was a ranker crash on a member select whose result feeds a
typed slot, not an annotation routing choice — it can occur in any `ExpectedValue.Type` slot, with the
annotation value being the observed trigger.

Verified live after the fix: `@Produces(MediaType.<caret>)` returns the 34 `MediaType` members.

### Regression target

- `CompletionAnnotationTest.annotationValue_memberAccessOnNonEnumType_offersConstants`
  (`@SuppressWarnings(java.io.File.<caret>separator)` — JDK-only, reproduces the crash without a
  third-party dependency).

---

## EG-040 — Attributed-analysis retention is unbounded; heavy sessions can OOM and kill the server

**Status: done — Target: M2.** Fixed with an event-loop LRU that bounds interactive attributed-analysis
retention at 100 open-document analyses and delegates eviction to the owning module worker.

### Observed behaviour

Each open file's attributed javac `Context` is cached (`SourceAnalysisSession.cache`) and evicted only
on `didClose`, so retained heap grows ~linearly with the number of open files — measured at ~29–31 MB
per open file (open + member-access completion) against a large private workspace. Under an abusive
sweep (~210 open files plus back-to-back reference searches) the JVM hit a fatal `Error`, and
`CompilationWorker`'s "treat `Error` as fatal" path (`processTerminator.accept(FATAL_EXIT_STATUS)`)
terminated the **whole server** — every LSP feature lost until the client relaunches.

Normal editing (a few dozen buffers ≈ <1–2 GB) stays well within the ergonomic heap (~25% of RAM;
≥8 GB on a ≥32 GB workstation), so this bites only pathological / bulk-access sessions.

### Root cause

One javac `Context` retained per open file, unshareable (javac symbols are per-`Context`).
Retention is bounded by open-document count, not by any cap.
See the analysis in the deferred design
[lathe-analysis-cache-bounding.md](../potential/lathe-analysis-cache-bounding.md).

### Resolution

`WorkspaceSession` owns an event-loop-confined `AnalysisLru` over open-document URIs.
On overflow, the event loop removes the eldest URI from the LRU and delegates the actual analysis drop through
`WorkspaceModuleRegistry.dropFromAllCaches(uri)`, so javac-backed objects remain confined to module workers.
Cache-only readers now recompile on miss:
`SourceAnalysisSession.resolve()` uses `ensureAttributedAnalysis(...)`, and semantic tokens receive current content
and rebuild when the cached version is missing or stale.

The disk-candidate implementation-search leak is also fixed:
closed candidate files use a transient FAST compile path and do not populate the interactive analysis cache.
The cap was validated with 300-file probes against Helidon and import-heavy Dropwizard test classes;
Dropwizard stayed stable with semantic tokens, hover, and definition requests after eviction.
Raising the JVM heap can still provide more headroom for transient compile/search spikes
(the M3 `LATHE_JVM_OPTS` knob, [lathe-launcher-jvm-opts.md](../done/lathe-launcher-jvm-opts.md),
is the planned first-class way; `JAVA_TOOL_OPTIONS=-Xmx…` works today).

### Regression targets

- `AnalysisLruTest.touch_beyondCap_returnsEldest`
- `SourceAnalysisSessionTest.semanticTokens_afterEviction_recompilesAndReturnsTokens`
- `SourceAnalysisSessionTest.semanticTokens_versionMismatch_recompiles`
- `MethodImplementationTest.methodImplementationsTransient_candidateFile_doesNotCacheAnalysis`

---

## FR-033 — `builder()` reference search surfaces test hits — verified correct, not a defect

Registry correction (2026-11-08): formerly `FR-011`, also assigned to a different gap; `FR-033` uniquely identifies this historical entry.

**Status: non-goal — verified correct behaviour; breadth cost tracked by FR-009**

### Observed behaviour

Find References on a generated `@Builder` factory `builder()` returned matches in test sources that
appeared unexpected, while instance setters (`amount(BigDecimal)`, `taxAmount(BigDecimal)`) on the
same builder returned zero references.

### Investigation (resolved)

Reproduced against the generated builder of an `@Builder` record, searching each member from its
declaration with reactor scope.
Every result was correct:

| Symbol | Hits | Verdict |
|---|---|---|
| `builder()` (static factory) | 5, all in tests | genuine cross-module callers |
| `kind(Kind)` (instance setter) | 5, all in tests | genuine cross-module callers |
| `amount(BigDecimal)` (instance setter) | 0 | no callers exist |
| `taxAmount(BigDecimal)` (instance setter) | 0 | no callers exist |

The confusion had two sources, both correct matcher behaviour:
(1) the record's component **accessor** `amount()` and the builder's **setter** `amount(BigDecimal)`
share a simple name but differ by owner + descriptor, so they do not cross-match;
(2) a sibling generated builder exposes identically-named setters, and its call sites correctly do
not match the first builder's methods.
The zero-reference setters simply have no callers — every chain of the builder under test only calls
`.type(...).build()`.
Instance-setter search from a generated source works:
`type()` returned exactly its five call sites cross-module.

No matching defect exists.
The only real cost — hundreds of files compiled to return ≤5 hits — is the candidate-breadth problem tracked by
**FR-009**.

### Probe commands

```bash
# From the generated builder, each search returns exactly the genuine call sites:
printf 'refs "kind(Kind"\n' \
  | python3 dev/explore.py --workspace /path/to/workspace /path/to/.../SomeRecordBuilder.java
```

### Regression targets

None — no code change.
Candidate-breadth coverage is under FR-009.

## CQ-0051 — Void-returning members dropped from member completion (stale-cache value-slot misclassification)

Status: done — Target: M2.
Tier: correctness. Failure mode: missing-candidate.

Member-access completion after `Receiver.` dropped every `void`-returning method (e.g. a util type's
`sleepXxx`) at a plain statement, reappearing on retry. Completion answered from a stale completion
cache (`reattributed=false`): the just-typed `.` was not yet in the cache, so the current cursor
offset mapped, in the stale tree, into a following expression and marked the statement value-sensitive;
`CompletionCandidateRanker` then filtered the void members.

Fixed by keying the `MemberAccessCompleter` reattribution guard on the completion-token start
(`SentinelInjectionResult.tokenStart`, i.e. through the receiver's dot) rather than the receiver end,
so a cache missing the dot reattributes against the current content and the statement is classified
correctly.

Regression:
`CompletionMemberAccessTest#memberAccess_staleCacheValueSensitiveOffset_stillOffersVoidStaticMethods`
with the fresh-cache contrast `memberAccess_freshCacheStatement_offersVoidStaticMethods`.

## CQ-0052 — Member completion on an unimported ambiguous simple name surfaced only one candidate type

Status: done — Target: M3.
Tier: assistive. Failure mode: missing-candidates.

For an unimported `Receiver.` whose simple name matched several classpath types (e.g. multiple
`ThreadUtils`, or `java.util.Objects` vs Guava's `com.google.common.base.Objects`), member completion
offered only the first candidate type's members; members unique to the others were never shown, even
though the missing-import code action listed all candidates.

Fixed in `MemberAccessCompleter.fallback`: aggregate members across every same-named candidate type,
each carrying its own import edit, instead of returning on the first match.

Regression:
`CompletionTypeIndexTest#memberAccess_unimportedAmbiguousSimpleName_suggestsMembersFromAllCandidates`.

---

## CQ-0056 — Completion crashed on a qualified call in a class-level field initializer

Status: done — Target: backlog.
Tier: core. Failure mode: request-crash.

Completing inside the argument slot of a receiver-qualified call in a class-level field initializer
(e.g. `static Logger LOGGER = Slf4j.getLog(§)`) threw a `NullPointerException`, so the editor showed no
candidates at any prefix. A field initializer has no enclosing method, so `site.enclosingMethod()` was
null; expected-type resolution fell through to `resolveArgumentValueByPosition`, which called
`findMethodPath(…, enclosingMethod=null, …)`, and `findMethodPath` dereferenced the null method name
(`methodName.equals(…)`). Only qualified calls reached this path — unqualified calls resolve through
`findClassElement` and were unaffected.

Fixed in `TypeResolver`: `resolveArgumentValueByPosition` now returns `Unknown` when there is no
enclosing method (mirroring the guard the other `findMethodPath` callers already use), and
`findMethodPath` defensively returns null for a null method name. Completion falls back to the
type-index popup, exactly as method-body and unqualified-call slots already do. Resolving the actual
parameter type inside field initializers (for assignable ranking) remains a follow-up.

Regression:
`CompletionArgumentTest#argumentPosition_receiverQualifiedCall_inFieldInitializer_offersTypeIndexCandidates`.

---

# Workspace lifecycle (resolved)

## WS-46 — A server older than a module's JDK or language level cannot check it as the build does — done

**Status: done** (2026-11-21)

**Kind: bug**

**Affects: a server whose JDK is older than the JDK or language level a module compiles at (`LATHE_JAVA_HOME` set to an older JDK; possibly a Gradle build whose toolchain is newer than the JDK Gradle runs on, not checked); the editor and MCP on that module.**

Found reviewing the fix for the JDK 21 crash of the 2026-11-20 pre-release round (#422), which lets an older server compile a `-source 17` module the build compiled on a newer JDK.

### Observed behaviour

A module that compiles at a language level the server's javac does not know cannot be compiled by it at all.
javac 21 refuses `-source 25` ("invalid source release: 25") and `--release 25` ("release version 25 not supported"), checked with plain `javac`.
How the server reports that refusal to the editor and to an agent was not checked.

Where the server can compile the module, at a level it knows but against the build's newer JDK, it compiles against its own JDK instead (the fix above, and the existing fallback for a build JDK gone since the sync).
Then a use of an API the server's JDK does not have is a "cannot find symbol" error the build does not report.
There the only explanation is a server log `WARNING` (`[compile] JDK … newer than the server's …`), which the user does not see.
The warning is logged each time a module's compiler is created, so a reactor of many such modules repeats it once per module on every load and reload.

### Fix

Each load checks every module against the JDK the server runs on (`JdkSupport`): a `--release`, `-source` or `-target` newer than it, or, without `--release`, a newer JDK the build compiled against.
Such a module is left out of the registry, so no compile, search, write or check reaches it, and the user is told, as an error naming the module, both JDKs and `LATHE_JAVA_HOME`, again only when the set of such modules changes.
A rename of a symbol such a module compiles against is refused, naming the module, since its uses would stay unrenamed.
An agent's answers about uses name such modules as not searched, as they do Kotlin sources, so a search does not pass as complete.
An agent's answers about uses name such modules as not searched, as they do Kotlin sources, so a search does not pass as complete.
Each of its open files gets one error saying the same, an agent's `get_diagnostics` answers it, and its notes list the file as not checked with that reason.
A module with `--release` at or below the server's JDK is checked as before, whichever JDK the build ran on, as `--release` gives javac that version's API from its own JDK.
The crash that failed a whole search is WS-47.

### Regression targets

- `JdkSupportTest` (which modules a server on a given JDK can check, and the message)
- `UnsupportedJdkTest.open_moduleBuiltForANewerJava_toldOnceAndItsFileSaysWhyOtherModulesWork` and `UnsupportedJdkTest.diagnostics_fileOfAModuleBuiltForANewerJava_theReason`
- `LatheEngineNotesTest.notes_fileOfAModuleBuiltForANewerJava_notCheckedWithTheReasonAndTheFix`
- `LatheEngineRenameTest.rename_symbolAModuleBuiltForANewerJavaCompilesAgainst_refusesNamingItAndEditsNothing`
- `UnsupportedJdkTest.reload_sameModulesLeftOut_notToldAgain` and `UnsupportedJdkTest.initialize_rootModuleBuiltForANewerJava_namesItAsTheRoot`

---

## WS-40 — A build check that scans the whole project finds the class mirror in `.lathe/` — done

**Status: done** (2026-11-21: Lathe excludes `.lathe/` from the nohttp check on Maven and on Gradle; other whole-project scanners keep the documented exclusion, and the mirror moving out of the project stays the [External Content Directory](../potential/lathe-external-content-dir.md) design)

**Kind: bug**

**Value: medium — Size: M**

**Affects: Maven and Gradle builds with a check that scans the project directory itself (nohttp, license-header checks, Docker contexts).**

Found syncing spring-petclinic for the Declared Formatters probes (2026-11-11).

### Observed behaviour

From the second build on, petclinic's nohttp check fails.
On Maven it is the `maven-checkstyle-plugin` execution that runs `nohttp-checkstyle` over `${basedir}`;
on Gradle, the `io.spring.nohttp` plugin's `checkstyleNohttp`.
Both find `http://` URLs in `.lathe/classes/META-INF/sbom/application.cdx.json`,
a generated resource that Lathe's class and resource mirror copies.
The first build passes only because `.lathe/` does not exist yet.
The check runs in `validate`, before the sync, so the sync cannot change it.
Only the mirror (`classes`, `test-classes`, generated sources) holds such files; the small files in `.lathe/` hold none.

### Fix direction

The workaround is documented: exclude `.lathe/` from such checks (`troubleshooting.md`; #178).
Two ways to need no exclusion are open:

- **Lathe adds the exclusion itself.**
  The Maven extension appends `**/.lathe/**` to a nohttp checkstyle execution's `<excludes>` in memory,
  and the Gradle plugin adds it to the nohttp extension's `source`, when Lathe is active.
  It covers one tool, and editing an execution's configuration after the model is built needs a spike.
  Done on both builds (2026-11-21). Maven: an execution's own configuration, edited in `afterProjectsRead`, reaches the check, where a plugin-level node does not reach the executions (`lathe-maven-extension.md` §9); the plugin-level node is edited too, as a goal run from the command line (`default-cli`) takes it; excludes a project sets through the `checkstyle.excludes` property are kept.
  Gradle: the `nohttp` extension's source tree gets the exclude when the plugin is applied and again once the project is evaluated, as a build script may replace the tree.
- **Move the mirror out of the project** into the per-user cache, leaving `.lathe/` the small files.
  No scanner or Docker context would see it, at the cost of a per-workspace cache and its cleanup.
  The mirror must stay outside `target/` and `build/`: it outlives a clean and a failed build (`lathe-gradle-support.md`).
  This is the [External Content Directory](../potential/lathe-external-content-dir.md) design, which covers every
  scanner, not only nohttp: the proper fix, taken up if more users hit this.

### Regression targets

- Maven: `LatheModelInjectorTest.injectProject_nohttp*`, `injectProject_excludesAlreadyNameLathe_leftAsTheyAreAndAgainOnASecondPass`, `injectProject_excludesNameOnlyALookalike_latheStillAppended`, `injectProject_excludesSetByProperty_keptInTheElementLatheAdds` and `injectProject_checkstyleWithoutNohttp_excludesUntouched` (the excludes the extension writes); spring-petclinic's first build then its sync, which failed with 15 nohttp errors before and passes after (2026-11-21, by hand).
- Gradle: `LatheNohttpFunctionalTest.nohttpApplied_latheMirrorLeftOutOfItsSource_projectFilesKept` (a stand-in nohttp, with the real plugin's extension shape, no longer lists the mirror), `nohttpSourceReplacedByTheBuildScript_latheMirrorStillLeftOut` and `nohttpExtensionWithoutSource_buildGoesOnAndSaysWhy`.

---

## WS-45 — A build that rewrites its own POMs leaves the sync's fingerprints stale, so the next reload prompts to sync again — done

**Status: done** (2026-11-19)

**Kind: bug**

**Affects: Maven syncs of a build that rewrites a POM while it runs (sortpom, as jdbi; a POM tidy or formatter step); the editor's sync prompt, and MCP `verify_change`, which defers until another sync.**

Found by the pre-release round on jdbi (2026-11-19), main `8c099a5` (server, extension and `lathe:sync`).

### Observed behaviour

jdbi's build runs sortpom, which sorts each POM in place and saves it when the order changed ("Saved sorted pom file to …/core/pom.xml").
`lathe:sync` writes `.lathe/workspace.json`, with each POM's content fingerprint (`buildFiles[].fingerprint`), while the root project `jdbi3-parent` builds, before the reactor reaches `core`, whose sortpom then rewrites `core/pom.xml`.
After a dependency added at the top of `<dependencies>` in `core/pom.xml` and a sync (`.lathe/lathe-sync.sh`, 44 s, BUILD SUCCESS), the manifest was written at 22:13:07 and the build ended at 22:13:27; comparing the manifest with the files on disk, `core/pom.xml` differs (the other 43 match).
In the editor, 2 s after the sync's reload the server logs `[watcher] build files changed — sync needed` and asks "Build files changed. Lathe will run a full refresh." again, though nothing changed since the sync.
An agent is blocked the same way: right after that successful sync, MCP `verify_change` answers "Verification deferred: a pending build-file or dependency change needs a sync. Run `.lathe/lathe-sync.sh`, then re-check.", and `run_test` passes but ends "Sync required: build files changed".
A second sync records the sorted POM and settles it (`verify_change`: "No errors"), so each such POM edit costs one extra sync, and an agent that trusts the first one cannot verify until it runs another.

### Fix direction

Fingerprint the POMs when the build ends rather than when the root project writes the manifest: a build that edits its own POMs has then finished doing so.
Maven's session end (a lifecycle participant's `afterSessionEnd`, in the extension) is one place to re-fingerprint `buildFiles` and rewrite them in the manifest when any moved.

### Resolution

The extension records a POM the build only reformatted: at session end (`LatheLifecycleParticipant.afterSessionEnd`), a build file whose content moved since the sync is given its new fingerprint (`WorkspaceManifestData.withBuildFiles`) when two things hold.
The manifest's fingerprint is the one Maven read when this build started (taken in `afterProjectsRead`), so an older sync's entry, from before a POM was edited, is never replaced.
And the POM read now, written back by Maven's own model writer, equals the model Maven read (`MavenProject.getOriginalModel()`): the writer orders the elements and drops whitespace and comments, so sortpom's order and layout drop out, while an added dependency or a changed version does not.
A POM whose model changed during the build, by hand, by a `git pull` or by a plugin, keeps the synced fingerprint and still asks for a sync; one whose lists sortpom reordered (dependencies) does too, as the conservative answer.
It does not need the build lock, so the first sync of a fresh checkout, which takes none, is covered; the step is best-effort and never fails the build.
The server needs no change: while the build holds the lock the watcher does not poll, and the rewrite lands before the lock is released, so it reloads once.

Checked on jdbi with the fix: after adding a dependency at the top of `core/pom.xml` and one sync, sortpom expanded it onto several lines, the extension logged `build-file fingerprints updated for reformatted POMs 31ms`, all 44 build files match the manifest, and MCP `verify_change` answers "No errors" (before: "Verification deferred").

### Regression targets

- `LatheLifecycleParticipantTest.refreshBuildFiles_pomReformattedDuringTheBuild_recordedNowAndAChangedModelKept`
- `LatheLifecycleParticipantTest.refreshBuildFiles_syncedFingerprintIsNotWhatMavenRead_kept`
- `LatheLifecycleParticipantTest.afterSessionEnd_firstSyncOfAFreshCheckout_reformattedPomRecorded`

---

## WS-36 — A re-sync once recompiled a module's generated sources twice, holding a query for 44 s — non-goal

**Status: non-goal** (2026-11-19: not reproduced; three forced re-syncs of Caffeine each wrote the module once, with no reload)

**Kind: bug**

**Affects: MCP and editor answers right after a sync, on a module with many generated sources.**

Found by the pre-release Gradle round (2026-11-15) on Caffeine.

### Observed behaviour

After `.lathe/lathe-sync.sh caffeine`, the MCP server recompiled the module's 493 generated sources twice in a row
(28 s, then 22 s), and a `get_diagnostics` asked meanwhile answered after 43.8 s.
Two reduced attempts (add a file, sync, delete it, sync) did not repeat it.

### Fix direction

Reproduce first: find what made the pass write the generated sources twice (a reload landing mid-pass, or stamps the sync
rewrote after the first write).

Both are possible in the code (checked 2026-11-16): `WritePass.onReload` bumps the pass generation, but `writeLevels` checks it only between dependency levels, so a level already compiling finishes and the next pass may write it again;
the stamps guess would sit in `stableSources`/`pendingStale` and the stamp map.
No commit since the report targets either.

### Probe result (2026-11-19, main `6803a4b6`)

Caffeine (`10646b0`) synced with the Gradle plugin built from main, an MCP server running with `LATHE_DEBUG=1`, then `latheSync --module caffeine` three times as a no-op and three times with `--rerun-tasks`, which regenerates and recompiles the module.
The no-op syncs wrote nothing.
Each forced sync started one write pass, `[react] recompiling 592 changed file(s)`, compiled once in 3–4 s; no second pass and no `[reload]` followed.
`get_diagnostics` on `BoundedLocalCache.java` asked right after each sync answered in 3.6–5.0 s.
The race the fix direction names (a reload landing mid-pass) stays possible in the code, but nothing in a sync triggered it.

### Regression targets

None: closed without a change.

---

## WS-24 — An unreadable `workspace.json` is not reported to the editor and is never re-read — done

**Status: done** (2026-11-18)

**Kind: bug**


**Affects: the editor and MCP.**

Found by the pre-release test rounds (2026-10-29/30) on a fresh Maven project.

### Observed behaviour

A `workspace.json` that is not UTF-8 or not readable (chmod 000) makes `WorkspaceWatcher.readManifest` throw; the editor gets no message, the watcher does not start, and a fixed file is never re-read, so outside changes go unnoticed until a restart.
A missing file or one with no modules is silent.
An empty file is fixed: `Json.read` turns it into a load problem the editor is shown (re-checked 2026-11-17).

### Root cause

`WorkspaceManifest.load` already turns a read error into a load problem that `showWorkspaceStatus` would show,
but `loadWorkspace` constructs the `WorkspaceWatcher` first, and its `readManifest` throws, so the warning is never sent.

### Fix direction

Report a broken manifest to the client with the remedy (a sync), keep watching, and reload once it is readable.

### Resolution

`WorkspaceWatcher` no longer throws on a manifest that exists but cannot be read: a failed read is no change, and the last content stands (at startup, none), so the load continues and `showWorkspaceStatus` reports the manifest's load problem with its remedy.
The read is tried again when the file is rewritten (not UTF-8), or on every poll after an access failure (a permission, a lock while a sync replaces it), as restoring a permission leaves the mtime as it was; a file that reads again is compared with the last content.

### Regression targets

- `LatheTextDocumentServiceTest.initialize_unreadableWorkspaceJson_warnsWithTheRemedy`
- `WorkspaceWatcherTest.poll_unreadableManifestRewritten_workspaceChanged`
- `WorkspaceWatcherTest.poll_readableManifestTurnsUnreadable_noChangeUntilItReadsAgain`
- `WorkspaceWatcherTest.poll_unreadableManifestPermissionRestored_workspaceChanged`

---

## WS-41 — Setup fails when the project declares `maven-compiler-plugin` with `<extensions>true</extensions>` — done

**Status: done** (2026-11-18, PR #381)

**Kind: bug**


**Affects: Maven setup (`:LatheInit`, the sync); every module of such a build.**

Found by the exploratory round on Quarkus (2026-11-17, Lathe 0.3.1).

### Observed behaviour

Quarkus' `independent-projects/parent/pom.xml`, the parent of its whole reactor, declares the compiler plugin with `<extensions>true</extensions>`.
The first build Lathe runs fails at the first module that compiles (`quarkus-ide-config`): `Failed to execute goal maven-compiler-plugin:3.16.0:compile ... No such compiler 'lathe'` (`NoSuchCompilerException`).
The extension selects the compiler with `maven.compiler.compilerId=lathe` and adds `lathe-compiler` to the plugin's dependencies in `afterProjectsRead`, but Maven builds the class realm of a plugin with extensions before that, so the added dependency never reaches it.
With the flag removed from that one POM, the module builds and is captured.

### Fix direction

Reproduce with an invoker fixture whose compiler plugin has `<extensions>true</extensions>`.
Either add `lathe-compiler` where Maven still reads it for an extensions plugin (the model, before the realm is built), or leave such a project's compiler alone and say why instead of failing the build.

### Resolution

The Maven extension turns `<extensions>true</extensions>` off for `maven-compiler-plugin` in the in-memory model, and says so once per build: Maven then builds an ordinary plugin realm when the plugin first runs, with `lathe-compiler` in it, rather than an extension realm while it reads the projects, before the extension adds the compiler.
The compiler plugin contributes nothing as an extension, and the POM is not touched.

### Regression targets

- `LatheModelInjectorTest.injectProject_compilerAsBuildExtension_loadedAsPlainPluginWithTheShim`
- invoker fixture `compiler-extensions`

---

## WS-42 — A Gradle module path passed as a compiler argument gives no module dependencies — done

**Status: done** (2026-11-18)

**Kind: bug**

**Affects: references, callers, rename and `verify_change` across modules, on Gradle builds that set the module path themselves.**

Found by the exploratory round on Apache Lucene (2026-11-18, Lathe 0.3.1).

### Observed behaviour

Lucene's build plugin compiles each module with `--module-path` as a compiler argument, naming the dependency's packaged jar (`lucene/core/build/libs/lucene-core-12.0.0-SNAPSHOT.jar`), and leaves the task's classpath empty.
The capture keeps the argument as it is: `lucene/analysis/common`'s params have an empty `classpath` and `modulepath`, and `workspace_summary` lists no dependency for most modules.
Files there still compile (the argument reaches javac), but the module graph lacks the edges, so `find_references` on `TermQuery.getTerm` answers 10 references, all in `lucene/core`, while `highlighter` (`FieldQuery.java`, four calls) and `queries` use it too; the answer still says "no need to re-verify with grep".
Analysis of a dependent module also reads the dependency's jar from the last build, not its sources.
On main the Gradle capture still takes the module path only from the classpath (`ModulePathSplit.split(classpath)`).

### Resolution

The capture takes `--module-path`/`-p` and `--class-path`/`-classpath`/`-cp` (also in their `=` forms) out of the compiler arguments it records and adds their entries to the params' module path and classpath (`CompilerPathArgs`), after the compile task's own.
The server needed no change: Lucene's manifest already lists `lucene-core-12.0.0-SNAPSHOT.jar` as an output of `lucene/core`, so the entry maps to its mirror, which is the module-graph edge, and a dependent module reads the dependency's sources, not the last built jar.
Re-checked on Lucene: `lucene/analysis/common` now depends on `lucene/core`, and `find_references` on `TermQuery.getTerm` reaches `highlighter`, `queries` and `facet` besides `core`.

### Regression targets

- `CompilerPathArgsTest.extract_eachPathOptionForm_movesItsEntriesAndKeepsTheRest`
- `CompilerPathArgsTest.extract_noPathOptionOrNoValue_leavesTheArgumentsAsTheyAre`
- `CompilerPathArgsTest.extract_entryThroughParentSegment_normalized`
- `LatheCaptureTaskFunctionalTest.capture_modulePathAsCompilerArgument_movesItIntoTheModulePath`

---

## WS-43 — Lathe's Gradle plugin breaks a build that reads every configuration's dependencies while configuring — done

**Status: done** (2026-11-18)

**Kind: bug**


**Affects: Gradle setup (`:LatheInit`, `latheSync`) and every build of the project with Lathe's plugin applied.**

Found by the exploratory round on Apache Kafka (2026-11-18), on 0.3.1 and on main `cdf57f1`.

### Observed behaviour

Kafka's `build.gradle` (line 536), in `afterEvaluate`, walks every resolvable configuration's `incoming.dependencies` to look for Mockito.
That runs the `withDependencies` callback of Lathe's sources configuration (`LatheProjectPlugin.registerLibraries`, line 510), which resolves the test compile classpath at once, while other projects are still being configured.
Kafka then fails to configure: `Could not resolve all dependencies for configuration ':coordinator-common:compileClasspath'` … `Value for :server-common project components has not been calculated yet`.
Without Lathe's init script, `./gradlew :clients:dependencies` succeeds.

### Fix direction

Do not resolve anything when the sources configuration's dependencies are only listed: add the source dependencies when the facts task resolves them (a detached configuration in the task, or a provider the task forces), not in `withDependencies`.
A TestKit fixture whose build reads every configuration's `incoming.dependencies` in `afterEvaluate`.

### Cause (found 2026-11-18)

Reading `incoming.dependencies` runs a configuration's `withDependencies` actions, and Lathe's sources configuration was in each project's container, so Kafka's walk ran its action, which resolves the test classpath.
Under Kafka's consistent resolution (`useCompileClasspathVersions()`), that first resolves `coordinator-common`'s compile classpath, which configures `server-common` on demand.
`server-common`'s own walk then resolves its test classpath, which holds its own test fixtures, while its components are still being calculated.

### Resolution

The sources configuration is detached (`detachedConfiguration()`), outside the project's configurations: no build script reaches it, and the facts task resolves it when it needs the sources, after every project is configured.
A detached configuration does not get rules a build adds through `configurations.all`; its `-sources` coordinates come from the already resolved compile classpath, where they applied.
Checked on Kafka with the branch's plugin: `:clients:dependencies` succeeds, and a `latheSync` dry run configures every project and stores its configuration cache entry, which resolves every facts task's inputs.

### Regression targets

- `LatheSyncTaskFunctionalTest.sync_buildReadsEveryConfigurationsDependenciesInAfterEvaluate_configuresAndFindsSources` (fails on the code before the fix with Kafka's error)

---

## WS-35 — Lombok in the analysis compile reads a `lombok.config` up to 2 seconds old — non-goal

**Status: non-goal** (2026-11-17: documented in the [troubleshooting guide](../guide/troubleshooting.md); reopen if Lombok users ask)

**Kind: bug**

**Value: low — Size: S**

**Affects: the editor's diagnostics of Lombok classes right after a `lombok.config` edit; `verify_change` is not affected.**

Found while batching `verify_change`'s checks (2026-11-15).

### Observed behaviour

Lombok runs inside the analysis compile, which stays loaded between compiles, and caches each `lombok.config` it read:
it looks at the file again only when more than 2 seconds passed since it last did (`FileSystemSourceCache.RECHECK_FILESYSTEM`, Lombok 1.18.48).
An analysis within those 2 seconds of an edit to `lombok.config` generates members from the old settings:
after removing `lombok.accessors.prefix += f`, a class still gets `getCount()` for its field `fCount`, and a caller of it shows no error.
The write pass runs a fresh Lombok for each compile and is not affected.
`verify_change` checks a changed file alone, against the classes its write produced, so it is not affected either;
the editor's batched refresh of open files is.

### Fix direction

Release the analysis sessions of the trees below an edited `lombok.config` before the refresh of their open files:
each session loads Lombok in a class loader of its own, so the refresh runs a fresh Lombok that reads the new settings.

### Regression targets

None yet.

---

## WS-17 — The server JVM has no heap cap and grows toward a quarter of the machine's memory — non-goal

**Status: non-goal** (2026-11-17: Lathe sets no default heap limit, by the 2026-10-24 decision below; `LATHE_JVM_OPTS` caps it)

**Kind: bug**

Decision (2026-10-24): Lathe sets no default limit until users report how much memory they want it to
use; machines with plenty of memory are expected to use it. The installation guide documents capping
it with `LATHE_JVM_OPTS` (and `-XX:G1PeriodicGCInterval` to return memory when idle), and the agent
guide's troubleshooting points there. Lathe's own dev tools cap their servers at 2 GB.
**Affects: the editor and MCP.**

Found by the MCP-quality probes (2026-10-24) on Helidon.

### Observed behaviour

An MCP session on Helidon started at 752 MB RSS; three `verify_change` calls on `GenericType`
(235–311 caller files in 36–60 modules each) took it to 9.6 GB.
The launchers pass no `-Xmx`, so on a 60 GB machine the default maximum heap is about 15 GB, and the
heap grows rather than collects.
The same workload under `LATHE_JVM_OPTS=-Xmx2g` ran at the same speed (5.5–11 s per call) and stayed
at about 2.6 GB, so most of the growth is uncollected garbage, not memory Lathe holds.
An editor server and an MCP server per agent, all day, add up.
These numbers predate releasing idle module sessions (after 5 idle minutes, or earlier under memory pressure; [design](../done/lathe-memory-pressure.md)) and need re-measuring.

### Re-measured by the jdtls comparison's benchmark (2026-11-08)

After idle module sessions are released, Lathe `0.3.99-c15d3a55`, each server's summed RSS at its peak while answering the benchmark's 17 to 23 queries (warm runs, medians), against one run per project with `LATHE_JVM_OPTS=-Xmx2G`:

| Project | Uncapped | `-Xmx2G` | Queries at 2 GB | CPU at 2 GB |
|---|---|---|---|---|
| spring-petclinic | 1.1 GB | 0.8 GB | about the same | 1.3× |
| Dropwizard | 9.3 GB | 2.6 GB | 0–36% slower | 1.3× |
| Helidon | 6.3 GB | 2.6 GB | about the same | 1.1× |
| Hibernate ORM | 6.7 GB | 2.7 GB | 0–20% slower | 1.5× |
| Apache Fineract | 7.6 GB | 3.6 GB | up to 76% slower (FR-029's slowest queries) | 2.9× |
| OpenMetadata | 3.8 GB | 2.9 GB | 0–15% slower | 1.9× |

Every answer stayed the same at 2 GB.
So the earlier conclusion holds: most of the growth is uncollected garbage, and a 2 GB cap costs CPU, not correctness; Fineract's reference searches are the case a cap would visibly slow.

### Fix direction

Give the launchers a default heap policy: a cap (absolute, or `-XX:MaxRAMPercentage`), and G1's
periodic collection (`-XX:G1PeriodicGCInterval`) so an idle server returns memory; keep
`LATHE_JVM_OPTS` as the override. Measure the cap against the largest probes first.
Related: archived [EG-058](gaps-archive.md#eg-058--module-analysis-sessions-are-never-released-a-reactor-wide-search-pins-every-touched-modules-jars-for-the-session--done),
done: module sessions are now released when idle or under memory pressure.

### Regression targets

None yet.

---

## WS-38 — An empty dependency-source marker fails every later sync — done

**Status: done** (2026-11-17)

**Kind: bug**

**Affects: every sync (Maven, Gradle, OpenJDK), and every reader of a JSON file Lathe writes, after a crash leaves the file empty or cut off.**

Found while running `mvn clean install` after a machine crash (2026-11-16): a 0-byte `.lathe-source.json` in the Gradle fixtures' Lathe cache made every later sync fail with a `NullPointerException` (`[lathe] workspace files not refreshed`), writing no `workspace.json`, until the cache was deleted by hand.
Gson reads an empty file as `null`, and a cut-off or zero-filled one throws an unchecked `JsonParseException`; most `Json.read` callers handled neither.

### Resolution

Fixed in #352.
`Json.read` never returns `null`: an empty file or a parse error is an `IOException` naming the file, which every caller already handles, so the marker is taken as stale and the sources extracted again, and an empty type-index shard is skipped.
`TestDiscoveryStore` takes a damaged stored discovery as none; an empty run-config layer still holds no entries.

### Regression targets

`JsonTest.read_emptyCutOffOrZeroFilledFile_throwsIOExceptionNamingTheFile`, `DependencySourceSyncTest.extract_emptyMarker_reextractsAndRewritesMarker`, `WorkspaceTypeIndexTest.build_emptyShard_skippedAndOtherShardsLoaded`, `TestDiscoveryStoreTest.read_damagedFile_empty`, `RunConfigWriterTest.save_emptyLocalFile_writesTheConfig`.

---

## WS-39 — A dependency's sources jar that is not a zip fails the whole sync — done

**Status: done** (2026-11-17)

**Kind: bug**

**Affects: every sync (Maven, Gradle, OpenJDK) of a project with a dependency whose published `-sources.jar` is not a valid zip — `com.ibm.db2:jcc:12.1.4.0` on Maven Central, so any project using the DB2 JDBC driver.**

Found by the editor exploratory round on jdbi (2026-11-17), whose `testcontainers` module depends on the DB2 driver.

### Observed behaviour

`jcc-12.1.4.0-sources.jar` on Central is a 189-byte text file (an IBM copyright notice), published that way with a matching checksum.
`:LatheInit` on jdbi fails after the build compiles nothing:
`Failed to execute goal io.github.ag-libs:lathe-maven-plugin:0.1.0-SNAPSHOT:sync (lathe-sync) on project jdbi3-parent: Lathe sync failed to extract source artifacts: java.util.zip.ZipException: zip END header not found`.
`DependencySourceSync.extract` turns the first unreadable jar into a `SyncException`; the message does not name the jar, and no other module is set up.
Replacing that one file with an empty zip lets the same setup succeed.

### Resolution

`DependencySourceSync.extract` handles each sources jar on its own: one that is not a readable zip (`ZipException`) is skipped with a warning naming the dependency and the jar, and `prepare` records that dependency as one without sources (`MISSING`), as for a dependency that publishes none.
No marker is written, so a later sync tries the jar again.
Any other failure, writing the cache say, still fails the sync, now naming the dependency and the jar.
Related: [WS-38](#ws-38--an-empty-dependency-source-marker-fails-every-later-sync--done), another unreadable dependency-source file that fails every sync.

### Regression targets

`DependencySourceSyncTest.prepare_sourcesJarNotAZip_isMissingAndOthersExtracted`, `extract_sourcesJarNotAZip_answersItAndLeavesNoCache`, `extract_readableSourcesJar_answersNoneSkipped`.

---

## WS-21 — A rename racing a build can leave the tree broken — done

**Status: done.**

**Affects: MCP.**

Found by the pre-release test rounds (2026-10-29/30) on EqualsVerifier.
With a Maven sync running, an agent's `rename_symbol` succeeded on disk, but the next `verify_change` reported the new name as missing: the build stamped each source from the file on disk once javac was done, after javac had compiled the older content, so the server took the stale class for current and never rewrote it.
Any write during a compile raced the same way: an editor save, a `git checkout`.

### Resolution

The build records each source's mtime just before javac runs, and after it leaves unstamped, and out of the built record, every source whose mtime differs then or that was not there (`CompileCapture.sourceMtimes`).
The server compiles an unstamped source as never built.
Maven takes the snapshot in the compiler wrapper (`LatheCompiler.performCompile`); Gradle in a task the compile task depends on (`LatheSourceMtimesTask`), after whatever generates sources into the source set, as an action of the compile task itself would change its build cache key.
The stamps hash each source first and keep only those whose mtime still is the snapshot's, so a write while hashing does not slip through; with no snapshot, no source is stamped.
It holds as long as a write moves the file's mtime, as the server's own staleness check assumes.

### Regression targets

- `CompileCaptureTest.mirror_sourceWrittenOrAddedDuringTheCompile_leftUnstamped`
- `SourceMtimesTest.take_recordedBeforeTheCompile_readsTheMtimesOnce_noneWithoutASnapshot`
- `LatheCaptureTaskFunctionalTest.capture_javaProject_capturesFromCacheRefillsAWipedLatheAndKeepsTheLastGoodOne`: the snapshot task leaves the compile's build cache key alone.

---

## WS-30 — The JDK type index lists the build's JDK, not the project's — done

**Status: done.**

**Affects: completion and type search of JDK types, when a build compiles with a different JDK than it runs on (a toolchain).**

Found while reviewing the JDK cache lock (2026-11-05).
The JDK type index was recorded and cached as the project's JDK but scanned from the JVM the sync ran in, so a build on JDK 25 compiling with a JDK 17 toolchain indexed JDK 25's types under JDK 17's cache entry.

### Resolution

The sync scans the project JDK's own runtime image (`JdkTypeIndexSync.scanJdk` opens `jrt:/` with `java.home` set to that JDK), and the index schema was bumped so an index written from the build's JDK is rebuilt (#252).

### Regression targets

- `JdkTypeIndexSyncTest.index_homeIsNoJdk_failsOnItsImageInsteadOfScanningTheRunningJdk`

---

## WS-34 — After a sync, an open file keeps the error its last save reported against the old build — done

**Status: done.**

**Affects: diagnostics in the editor after a build-file change and its sync.**

Found by the jdtls comparison harness (2026-11-13) on Dropwizard: a dependency added to `dropwizard-core/pom.xml` and one of its classes used in the open, saved `Application.java`.
The prompted sync succeeded and the server reloaded, recompiling the file with no error, yet the client kept `package org.jdbi.v3.core does not exist` for the 10 minutes the harness waited: in the editor, until the file is edited.

### Cause

A save's write pass compiles the file into `.lathe/` and keeps what that compile reported and analysis cannot (an annotation processor's messages), keyed by the content written, to merge into each later publish of that content (`WorkspaceSession.withWriteDiagnostics`).
The save's compile ran against the old classpath, so it reported the missing package.
The sync's own build then compiled the file with the new dependency, so no later write pass found it stale and replaced the kept report, and the buffer still held the written content: every publish after the reload merged the old build's error back in.

### Resolution

A reload drops the kept write reports: the sync that changed the build compiled the sources against it, and the next save's write reports afresh.

### Regression targets

- `SaveTest.didSave_syncReloadsWithAnotherBuild_writeReportGoneFromThePublish` (fails without the fix: the reload republishes the old build's processor warning)
- `dev/lsp-bench` `--scenario build` (the file using the new dependency has no error after the sync)

---

## WS-26 — A second agent session can answer from stale state while the first one writes — done

**Status: done.**

**Affects: MCP, two sessions on one workspace (an editor's and an agent's server, or two agents).**

Found by the pre-release check (2026-11-04) on Helidon with `dev/mcp_staleness.py`: once in 13 runs, session B, asked about a downstream file straight after session A fixed its upstream file, still reported the error the fix removed;
B's queries took 2.05 s instead of 0.3–0.5 s.
The check of 2026-11-10 showed the wait without the stale answer: on Dropwizard B's I3 took 1.5–1.7 s in all 4 runs, against 0.2 s on 2026-11-06.

### Cause

Servers on one workspace take turns writing `.lathe/` under its write lock, and a pass that found the lock held tried again only at the next scan tick, 2 s on (`WritePass.runLocked`).
A query's pass queues behind the pass already running, so when B's own tick pass had found A's lock held a moment before, B's query waited for that tick pass's next try, though A had long finished:
a log line at each retry showed it, with nothing else between the query and the retry.
A query that itself found the lock held waited the same way, and a query gives up waiting after a 2 s grace, then answers from its own state: the stale answer of 2026-11-04.

### Resolution

A query no longer waits for the tick.
When a query's pass queues behind a pass waiting for the lock, that pass tries the lock at once,
and for the 2 s the query waits on it, a waiting pass tries again every 100 ms, as `LatheLock`'s own wait does, instead of every scan tick; past that grace the query has answered and the pass is back at the scan pace.
An idle editor's server still tries only at its tick, so it wakes no more often while another server's long pass (a branch switch) runs.
A query still answers after the 2 s grace when the other server's pass runs longer than that, as `verify_change` already says (`another Lathe server on this workspace is updating .lathe/`).

Measured on Dropwizard, three runs of the staleness probe: I3 255–283 ms (was 1.5–1.7 s), I1 1.3 s (was 2.2–3.6 s), I5 73–199 ms (was up to 1.5 s).

### Regression targets

- `WritePassTest.reconcileForVerify_tickPassWaitingForReleasedLock_queryAnswersWithoutWaitingForTheTick` (fails without the fix: the query waits for the tick)
- `WritePassTest.reconcile_anotherServerHoldsTheWriteLock_passWaitsThenWrites_queryStopsWaiting`
- `dev/mcp_staleness.py` scenario I (two sessions on one workspace)

---

## WS-23 — A killed build's locks make `verify_change` defer for about two minutes — done

**Status: done.**

**Value: high — Size: S–M**

**Affects: MCP.**

Found by the pre-release test rounds (2026-10-29/30) on EqualsVerifier.

### Observed behaviour

After `kill -9` of a Maven sync, the `lathe.lock`/`module.lock` it held stay, and `verify_change` answers
"Verification deferred: a build is in progress" for 100–120 s, until the locks count as stale.
The next good sync clears them.

### Resolution

Merged in [PR #143](https://github.com/ag-libs/lathe/pull/143) on 2026-11-08.
`LatheLock` records the PID and process start instant in both reactor and module locks.
Readers ignore fresh locks immediately when the owner has exited or the PID has been reused, without deleting the marker.
The two-minute mtime fallback remains for empty markers, malformed metadata and unavailable process information.

Verified with the full core suite on JDK 17, targeted Maven lifecycle and server write-pass tests on JDK 26, and Spotless.

### Regression targets

`LatheLockTest.isBuilding_terminatedOwner_ignoresFreshReactorAndModuleLocks` (checks fresh markers naming a JVM that has exited),
`LatheLockTest.isBuilding_reusedPid_ignoresFreshLock`,
`LatheLockTest.isBuilding_incompleteOrMalformedOwner_usesMtimeExpiry`,
`LatheLockTest.isBuilding_invalidUtf8Owner_usesMtimeExpiry`,
`LatheLockTest.isBuilding_staleLiveOwner_usesMtimeExpiry`.

---

## WS-33 — Gradle source sets beyond `main` and `test` get no editor features — done

**Status: done.**

**Value: high — Size: M**

**Affects: the editor and MCP on Gradle builds with test fixtures, JVM test suites, an `integrationTest`, `jmh` or any other source set.**

Found while checking the capture-only cost on Hibernate ORM (2026-11-07): its `test` task depends on test tasks of other source sets (`jakartaData`, `integrationTest`), whose files Lathe did not know.

### Observed behaviour

The Gradle plugin captured `main` and `test` only.
No module compiled a file of any other source set: no diagnostics, completion, navigation or rename there,
and references from other code missed its uses (in Caffeine, about 315 of the 603 references to `Caffeine.newBuilder`, in its benchmarks and suites).
Such source sets are common: test fixtures consumed across projects, JVM test suites, `jmh`, and custom ones such as Hibernate ORM's `jakartaData`.

### Fix direction

[Gradle Source Sets](../done/lathe-gradle-source-sets.md): a tree per source set, compiled against its own classpath,
rather than folding them into main and test as jdtls does through Buildship's Eclipse model, which lets the editor accept code the build rejects.

### Resolution

Every Java source set is captured as a tree of its own, `classes-<name>`, with its own generated-sources mirror, registered as the build script adds it.
`lsp-params` says whether a tree is test code (`testCode`), by Gradle's own convention: a JVM test suite's source set, or a name containing `test`.
The facts list each tree with its outputs, its jar when it has one (test fixtures), and its libraries.
The server reads test code from the flag, decides what rename's trees see from their class paths, searches the asking file's tree first when it maps a class to its source
(two source sets can declare the same class), and refuses to run a test or main method that only a source set's tree holds, until [TE-12](#te-12--on-gradle-only-each-projects-test-task-is-captured--done) captured their launches.

Checked on Hibernate ORM and Caffeine: files of `jakartaData`, `integrationTest` and a fuzz suite compile, navigate and find references;
an `integrationTest` file using a `test` class gets the error Gradle's compile reports.

### Regression targets

`LatheSyncTaskFunctionalTest.sync_sourceSetsBeyondMainAndTest_eachCapturedAsATreeOfItsOwn`,
`WorkspaceModuleRegistryTest.scan_sourceSetTrees_lookupPrefersTheAskingTreeAndOwnershipIsStable`,
`SourceSetScopeTest.treeOf_classOnlyInASourceSetTree_namesThatTree`,
`ModuleConfigDataTest.constructor_testCodeGivenOrAbsent_givenWinsAbsentFollowsTestClasses`,
`CapturePrunerTest.prune_removedModuleAndTree_deletesOnlyThoseCaptures`.

---

## WS-32 — A Gradle task writing into a directory Lathe's capture reads fails the build — done

**Status: done.**

**Value: high — Size: S**

**Affects: the Gradle front-end: `latheSync`, and so every Lathe feature, on such a build.**

Found by the jdtls comparison's setup check (2026-11-06) on Apache Fineract at `d63beed7c`, Lathe 0.3.1 (the Gradle plugin built from the `v0.3.1` tag).

### Observed behaviour

`./gradlew latheSync -Plathe.capture.only=true` fails after about 3 minutes:
`Task ':fineract-validation:latheCaptureMain' uses this output of task ':fineract-validation:compileTestJava' without declaring an explicit or implicit dependency`, on `build/generated/sources/annotationProcessor/java/main`.
Without Lathe the build succeeds.

Fineract's root `build.gradle` sets `options.generatedSourceOutputDirectory` to `$buildDir/generated/sources/annotationProcessor/java/main` for every `JavaCompile` task, so `compileTestJava` writes its processor output into the main tree's directory too.
`LatheCaptureTask` declares the main compile's generated-sources directory as a tracked input (`@InputFiles` on `getGeneratedSourcesDir()`), and `latheCaptureMain` depends on `compileJava` only, so Gradle 9 sees an input another task also writes, with no order between them, and stops the build.
Were the build to pass, the main tree's generated-sources mirror would also hold the test compile's generated sources.

With that fixed, the same build failed on a second directory: Fineract's `resolve` task depends on `classes`, generates the OpenAPI spec from the compiled code, and writes it into `fineract-provider/build/resources/main/static`, the processed-resources directory `LatheCaptureTask` tracked as an input (`getResourcesDir()`).
The third directory the capture reads, the classes, has no such writer in Fineract: its static weaving is a `doLast` of `compileJava` itself.

### Probe

Apache Fineract with Lathe 0.3.1 on Gradle: `JAVA_HOME=<JDK 25> ./gradlew --no-daemon --init-script <Lathe init script> latheSync -Plathe.capture.only=true`.

### Resolution

The plugin orders the tasks instead of hiding the overlap, so the capture never reads a directory while another task writes it:
- the source set's `classes` task depends on its capture, so a task that runs after the classes and writes into what the capture reads (Fineract's `resolve`) runs after the capture; the processed resources stay a tracked input, so the next build mirrors that task's file, and a resource `processResources` filters or expands is captured when it changes;
- the test compile runs after the main capture (`mustRunAfter`), so the main capture copies a shared generated-sources directory before the test compile cleans and refills it; that directory is read without being tracked (`@Internal`), as the compiled classes, a tracked input, change whenever it does.

A first version marked both directories `@Internal` alone; review found it only silenced Gradle: the capture could then copy a directory another task was writing, run after the test compile had emptied main's generated sources, and miss filtered resources.
The same order covers a task after the classes that writes into the classes directory (offline instrumentation, post-compile weaving). Not covered, as no project here has one: a task with no order to the classes at all that writes into a directory the capture reads.

### Regression targets

`LatheCaptureTaskFunctionalTest.capture_testCompileSharingMainsGeneratedSourcesDir_capturesMainsBeforeTheTestCompile` and
`LatheCaptureTaskFunctionalTest.capture_taskWritingIntoProcessedResourcesAfterClasses_runsAfterTheCaptureAndIsMirrored`, each of which fails with Fineract's error without the fix.

---

## WS-31 — A module that depends on a shaded reactor module shows false errors — done

**Status: done.**

**Value: high — Size: M**

**Affects: diagnostics and every feature that attributes the dependent module's files, in the editor and MCP.**

Found by the jdtls comparison's setup check (2026-11-06) on OpenMetadata at `9a2ee8b124`, Lathe 0.3.0.

### Observed behaviour

`openmetadata-shaded-deps` relocates the Elasticsearch client into `es.co.elastic.*` with `maven-shade-plugin`, which runs in `package`; `openmetadata-service` compiles against those relocated classes.
Maven compiles `ElasticSearchClient.java` cleanly, but Lathe reports 98 errors on it, starting with `package es.co.elastic.clients.elasticsearch does not exist`.
The build records the right classpath: `openmetadata-service` compiles against the shaded `elasticsearch-deps-2.0.0-SNAPSHOT.jar` (7,518 entries under `es/co/elastic`).
The Maven front-end (`ReactorProjects.outputs`) records every module's `<finalName>.jar` as standing for its compiled classes, so `ReactorRewrite.toLathe` swaps that jar for the module's `.lathe/` mirror, which holds only the module's own classes (one file here).
For a normal jar the swap is right, and keeps a dependent on the fresh mirror; `maven-shade-plugin` replaces the main jar with the shaded one (keeping the original as `original-<finalName>.jar`), so the swap drops every relocated class.

### Probe

Build OpenMetadata with Lathe (`mvn package -DskipTests -pl '!openmetadata-ui,!openmetadata-ui-core-components,!openmetadata-dist,!openmetadata-clients'`), open `openmetadata-service/src/main/java/org/openmetadata/service/search/elasticsearch/ElasticSearchClient.java`, and count the errors.

### Resolution

The Maven front-end no longer lists a shaded main jar as standing for its module's mirror: `ReactorProjects.shadesMainJar` recognizes a `maven-shade-plugin` execution of `shade` that neither attaches the shaded jar under a classifier nor writes it to an `outputFile`, reading each execution's configuration over the plugin-level one.
The jar then stays on a dependent's classpath as itself, for analysis and for run, test and debug launches; the module's own classes reach its dependents at its next `package`, as the jar holds them.

Only `package` makes the shaded jar, so a sync that stops at `process-test-classes` cannot compile the dependents.
A project chooses the phase its sync builds to with `lathe.sync.phase` (a `-D` on the build, `.mvn/maven.config`, or a root POM property), from `process-test-classes` to `install`; from `test` on, the sync skips the tests, and `--tests` captures them on its way to the phase.
The build warns when it sees a module compiling against a sibling's shaded jar while the phase is before `package`, naming the setting.
The Gradle front-end needs nothing: it lists only the `jar` task's archive, and the Shadow plugin's jar is a separate one.

### Regression targets

`ReactorProjectsTest.shadesMainJar_shadeExecutionReplacingTheMainJar_trueUnlessAttachedOrWrittenElsewhere`,
`ReactorProjectsTest.outputs_moduleShadingItsMainJar_listsNoMainJar`,
`ReactorProjectsTest.shadedUpstreams_modulesCompilingAgainstAShadedJar_namesItAndCountsThem`,
`MavenSyncScriptTest.phase_setOnTheBuildOrInThePom_followsItAndRejectsPhasesOutsideTheRange`,
`MavenSyncScriptTest.script_laterPhase_skipsTestsAndCapturesOnTheWayToIt`,
and the `shaded` invoker fixture's `ShadedTest`, which fails without the fix.

---

## WS-29 — On Gradle, the workspace merges facts a build did not refresh — done

**Status: done.**

**Value: low — Size: S**

**Affects: the editor and MCP on a Gradle build where a project's `latheFacts` did not run.**

Found while fixing the capture-only sync ordering (2026-11-05); what remains of the stale-workspace half once a failing
test task no longer keeps facts from running.

### Observed behaviour

`latheWorkspace` merges every `lathe-facts.json` under `.lathe/`, whether or not this build wrote it.
A project whose facts did not run (its compile failed, or on Gradle 8 under Isolated Projects a test task failed
before its facts started) contributes the facts of an earlier build.
That is mostly what Maven does too, but `runnerClasspath` takes the first non-empty value across all facts, so one
stale project can point the whole workspace at a runner jar from a build that no longer exists: every test run then
fails with `ClassNotFoundException: io.github.aglibs.lathe.runner.LatheTestRunner`, as seen on Caffeine before the
ordering fix.

### Fix direction

Take the runner classpath only from facts this build wrote (or skip one whose jars are gone), and say in the build
output which projects' facts are older than this build.

### Resolution

`latheWorkspace` still merges the facts each project last wrote, as a build of one project needs, but takes the
runner classpath from the first project whose runner jars are all in place, and warns for each project whose
recorded runner is gone, naming it.

### Regression targets

`LatheWorkspaceTaskTest.runnerClasspath_staleRunnerFirst_takesThePresentOneAndNamesTheStale`.

---

## WS-28 — On Gradle, a first sync whose test tasks fail leaves the workspace empty or stale — done

**Status: done.**

**Value: high — Size: S–M**

**Affects: the editor and MCP on a Gradle build whose capture-only test run fails.**

Found by the end-to-end Gradle check (2026-11-04): Lathe 0.3.0 as released, the plugin from a local repository standing in for the Gradle Plugin Portal, applied with the guide's personal init script and first-run command `./gradlew latheSync -Plathe.capture.only=true`.

### Observed behaviour

On Caffeine, the guide's first-run command fails: Caffeine's build rejects a test run in which tests are skipped ("Do not skip tests"), and capture-only mode reports every test skipped, so the `test` task of `caffeine`, `guava`, `jcache` and `simulator` fails.
Each module's `latheFacts` depends on that test task, so Gradle skips it; `latheWorkspace`, a finalizer, still runs and merges whatever `lathe-facts.json` files are already there.
On a first sync there are none: `workspace.json` lists no modules, no libraries and no test runner, so Lathe does nothing for that project, and only the failed build hints at why.
On a workspace synced before, the old facts are merged instead: here a `runnerClasspath` naming a `0.1.0-SNAPSHOT` runner jar from a deleted build, so every test run failed with `ClassNotFoundException: io.github.aglibs.lathe.runner.LatheTestRunner`.
A plain `./gradlew latheSync` works (all four modules, the 0.3.0 runner, 13 s) but captures no test launch.

### Fix direction

A failed test task must not keep a module out of the workspace: `latheFacts` should not depend on the test task succeeding, or `latheWorkspace` should refuse to merge facts that are missing or older than this build and say so.
The Gradle guide should mention builds that reject skipped tests, as the Maven guide does for `failIfNoTests`, with plain `latheSync` as the first step there.

### Resolution

As on Maven, where the sync runs before the test phase, a capture-only sync now writes the workspace before any test
runs.
`latheFacts` no longer depends on the test task: the test task finalizes it, so the sync still starts it, and runs
after it and after the root's `latheWorkspace`, so every project's facts and the merged workspace are written before
the first test task starts.
A failing test task fails the build, but cannot keep a project out of the workspace.
Under Isolated Projects on Gradle 8, where a project may not name the root's task, a test task waits for its own
project's facts only.
On Caffeine a fresh capture-only sync now lists all four modules and the runner, and captures `simulator`'s launch;
`caffeine`, `guava` and `jcache` test on Java 11, which Lathe warns it does not capture.
The Gradle guide says what a build that rejects skipped tests does, and that `--continue` captures every project.

### Regression targets

`LatheSyncTaskFunctionalTest.captureOnlySync_testTaskFails_stillWritesTheWorkspaceAndCaptures`.

---

## WS-20 — A class added outside the editor takes about 8 s to resolve in an open file — done

**Status: done.**

**Value: medium — Size: M**

**Affects: the editor.**

Found by the pre-release probes (2026-10-27) on EqualsVerifier, with `dev/lsp_staleness.py` (scenario P1).

### Observed behaviour

After an agent or `git` adds a class in an upstream module, an open downstream file that uses it shows
`cannot find symbol` for about 8 s before the server republishes clean diagnostics.
The scan runs every 2 s, and the other outside-change scenarios settle in 2–4 s.
Adding a class and using it straight away is the common agent pattern this delay hits.

### Resolution

The 8 s case is an open file that an agent edits on disk to use a class it adds next.
The use is written first and fails, and the failed write's `cannot find symbol` stays on the open file
while its content is the written one.
Once the class was written, the failure was only cleared, so the next scan took the file for a fresh
change and waited one more tick to settle before writing it again: two scans past the class.
A write now hands the downstream failures it may fix to a pass run right after it, as settled sources
(they sat unchanged since they failed), so the open file goes clean one write after the class.
EqualsVerifier, file open and edited on disk, then the class added: 8.5 s before, 5.0 s after;
the rest is the settle every outside change pays (P3 takes 3.0–4.5 s).
`dev/lsp_staleness.py` passes on EqualsVerifier and Dropwizard.

### Regression targets

`WritePassTest.reconcileNow_upstreamAddsWhatADependentFailedOn_dependentWrittenWithoutSettlingAgain`

---

## WS-25 — A first sync downloads dependency sources one at a time — done

**Status: done.**

**Value: low — Size: S**

**Affects: the first Maven sync on a machine (`mvn` and `mvnd` alike).**

Found by a code review of the sync path (2026-10-30).

### Observed behaviour

`DependencySourceResolver` calls `RepositorySystem.resolveArtifact` once per external artifact, so a cold `~/.m2` pays one sequential round trip per `-sources` jar (plus its checksum).
The jars are small, so round trips, not bandwidth, set the time:

| Project | Dependencies | With sources | Missing | Sources size | Median jar |
|---|---|---|---|---|---|
| Helidon | 209 | 206 | 3 | 50.6 MB | 62 KB |
| Dropwizard | 210 | 208 | 2 | 63.8 MB | 70 KB |
| Hibernate ORM | 254 | 252 | 2 | — | — |

Estimated for Helidon at 30–100 ms per request: about 15–45 s sequentially, against about 3–12 s batched, so 15–30 s saved once.
Not measured yet; a slower link (proxy, mirror, VPN) or extra repositories ahead of Central saves more.
A warm sync, the common case, gains nothing: every lookup is local.

### Root cause

Maven Resolver already downloads a batch in parallel: `resolveArtifacts` groups requests by repository, and the basic connector fetches each group on `aether.connector.basic.threads` (default 5).
Checked in Resolver 1.4.1 (`DefaultArtifactResolver`), the version Maven 3.6.3 ships, so it holds at the Maven floor.
The per-artifact calls never hand it a batch.

### Fix direction

Resolve all `-sources` requests in one `resolveArtifacts` call, inside `DependencySourceResolver` only.
A missing sources jar is normal, and the batch throws when any is missing, so read the per-request results from `ArtifactResolutionException.getResults()`;
a `RuntimeException` still fails the sync.
No Lathe threads, so nothing outlives a build in the `mvnd` daemon, and all logging stays on the Mojo thread.

Not worth doing alongside it: parallel extraction.
Unzipping all 206 Helidon sources jars (28,635 entries) takes about 1 s on Linux; Windows may differ.

### Resolution

`DependencySourceResolver` hands every `-sources` request to Maven Resolver in one `resolveArtifacts` call,
which downloads each repository's share in parallel on its own connector threads (`aether.connector.basic.threads`, default 5).
A missing sources jar makes the batch throw, with every request's result, in order; those results map back to the artifacts by index,
a missing one with the same debug line as before. Lathe starts no threads, and logging stays on the Mojo thread.

Measured on a probe project of 102 external artifacts (Dropwizard core and Hibernate ORM), 100 with sources,
with a private `-Dmaven.repo.local` and Lathe cache, timing the window between the sync's
`[sync] N unique external artifacts` and `[sync] sources … entries` lines:

| | One jar at a time | One batch |
|---|---|---|
| Cold: fresh repository | 12.9 s | 1.8 s |
| Sources jars only missing (five runs each, median) | 5.4 s | 2.2 s |

Extraction (about 0.7 s for the 100 jars) and the type indexes (about 1.2 s) are unchanged, as the gap expected.

### Regression targets

- `DependencySourceResolverTest.toSource_resolvedOrMissingResult_mapsToPresentOrMissing`: a resolved result maps to a present source, an unresolved one to a missing one.
- The invoker fixtures keep their `[sync] sources N dependency entries, M with sources` counts, those with missing sources jars included.

---

## WS-22 — One external jar listed twice in the manifest switches off cross-module features — done

**Status: done.**

**Value: high — Size: S**

**Affects: the editor and MCP.**

Found by the pre-release test rounds (2026-10-29/30) on a fresh Maven project.

### Observed behaviour

When one module depends on an external artifact as `<type>test-jar</type>` and another as `<classifier>tests`,
`workspace.json` lists the jar twice, and the manifest fails to load with "Duplicate key …":
cross-module references miss other modules, and the advice to re-sync cannot help.
`WorkspaceManifest.load` still has two jar-keyed `toUnmodifiableMap` collectors (`jarToGav`, `jarToSourceDir`),
the bug class an earlier fix removed for classifier jars sharing a sources dir.

### Fix direction

Merge duplicate jar entries when loading the manifest, or list each jar once when writing it.

### Resolution

`WorkspaceManifest.load` keeps the first entry for a jar listed twice, in its jar-to-GAV and jar-to-sources maps,
as its other dependency maps already did, and loads each type-index shard once.
The fix is in the reader, which every build front-end's manifest goes through;
the Maven writer still lists such a jar twice, keyed by type and classifier.

### Regression targets

- `WorkspaceManifestTest.load_jarListedTwice_loadsWithTheFirstEntry`: a test-jar and a tests-classifier entry for one jar
  load without a problem, map the jar to the first entry's GAV and sources, and list its shard once.

---

## WS-19 — Two servers on one workspace compile the same change at once, and one records a sound file as failed — done

**Status: done — Target: next.**
**Affects: the editor and MCP**, whenever two Lathe servers share a workspace: an editor's and an agent's,
or two agent sessions.
Found by the pre-release probes (2026-10-27) on Helidon, with `dev/mcp_staleness.py` (scenarios N1, N1b),
first recorded as "an outside edit to a Helidon blueprint sometimes does not regenerate its builder".

### Observed behaviour

N1 adds an option to a blueprint outside the editor and then uses the generated accessor.
In the failing run the use stayed `cannot find symbol latheProbeOpt` after 8 s, and the generated
`AllowListConfig.java` in `.lathe/` never gained the option; that run's first compiles took 24 ms and
86 ms (`failed=1`).
It did not reproduce alone, after the `pom` or `two` scenarios, or in two more full runs.

### Cause (2026-10-28)

With one server, 15 full Helidon runs with `LATHE_DEBUG=1` passed N1 and N1b every time.
With two servers on the checkout, both reacting to the same blueprint edit, it failed in the first
round: both compiled `common/configurable` at once into the same `.lathe/` output, one moved on to the
downstream test while the other was still rewriting the generated builder, read it half-written, and
failed. A failed file is recorded with its mtime and not retried until it changes again, so the
server kept the stale error. Servers shared only the compile stamps on disk; nothing kept two of them
from writing the same module together.

### Resolution

A server's write pass runs under `.lathe/write.lock`, an exclusive `FileLock` taken without waiting
(`LatheLock.tryExclusive`) before the pass scans and released when it ends; the operating system releases
it if the process dies. While another server holds it, the pass retries every 100 ms, so the server
keeps answering. When the other pass ends, its stamps show what it wrote as current, and this pass does
not compile it again.
On the Helidon race (two MCP servers, the N1 edit, both queried at once),
10 of 10 rounds pass, each change compiled once by one server, with no failed compile.

### Regression targets

- `WritePassTest.reconcile_anotherServerHoldsTheWriteLock_passWaitsThenWrites_queryStopsWaiting`: a pass does not
  write while another holder has the lock, and writes once it is released.
- `LatheLockTest.tryExclusive_heldOrReleased_emptyUntilTheHolderCloses`.

---

## WS-18 — A source whose time changed but whose content did not is recompiled in-process — done

**Status: done — Target: next.**

Found by the Gradle plugin's touch probe (2026-10-25) on JUnit's own build.
After `touch` on every source, `./gradlew testClasses` compiled nothing (Gradle decides by content),
so the compile stamps kept their old times.
The server's stale scan compared mtimes only, so it recompiled all 1,653 compiled sources in-process:
about 39 s of background compiles.
A branch switch and back did the same on either build tool.

**Why not compare with what the build compiled.**
`built` is what the *build* compiled, and the server's in-process compiles leave it,
so `verify_change` reads a change put back as no change.
Once the server has compiled an edit, a revert matches `built` but not the mirror,
so the scan needs what the mirror holds.

**Resolution.**
Each stamp is now a `SourceStamp(mtime, content)`: the source as its class was last written,
by the build (`CompileCapture`, from the hashes it already takes for `built`)
or by the server (the content it compiled, not a later read).
The scan compares the mtime as before, and reads only a source whose mtime moved.
If its content is the stamp's, the source is current and only re-stamped at its new mtime
(`TreeState.touched`, one write per tree, skipped while a build holds the lock).
OpenJDK records no content (fingerprinting the JDK tree on each sync costs more than it saves),
so the mtime alone decides there, as before.
`built` and `verify_change` are unchanged.

Regression:
`WritePassTest.reconcileNow_touchedSource_restampsWithoutCompiling_changedOrPutBackOneCompiles`
(touch, change, and a change put back after the server compiled it),
`WritePassTest.reconcileNow_touchedSourceStampedWithoutContent_compiles`,
and the writers' `CompiledStampsTest`, `CompileCaptureTest`, `OpenJdkSyncTest`.

## WS-16 — A source whose content changes while its mtime is put back is not recompiled — non-goal

**Status: non-goal.**
**Affects: the editor and MCP.**
Found by the external-change staleness probe (2026-10-24) on all three test workspaces, through MCP and
through a matching LSP probe the same day (an editor session driven through `didOpen`/`didSave`, polling up to 15 s); an edge case.

### Observed behaviour

A source was changed and its previous mtime restored (as `cp -p`, `rsync -t`, or extracting an
archive does).
Dependents kept compiling against the old classes, also after 8 s.
Editor: an open dependent kept its clean diagnostics for 10 s, and the mirror class kept the old method.

### Cause

Staleness is "mtime differs from the compile stamp"
([compile stamps](../done/lathe-staleness-compile-stamps.md)); content is not compared.

### Decision

Not fixed (2026-10-24).
Git, editors, and agents' file tools always bump the mtime; only tools that copy timestamps on
purpose leave it, and a sync then repairs the mirror.
Recording the size beside the mtime would not catch a same-size edit (the probe's `int a()` →
`int b()` is one), and hashing every source on each scan would cost far more than the case is worth.

---

## WS-15 — Annotation processors built in the reactor never run in-process — done

**Status: done — Target: next.**
**Affects: the editor and MCP, including the editor's own save.**
Found by the external-change staleness probe (2026-10-24) on Helidon, through MCP and through a matching LSP probe the same day (an editor session driven through `didOpen`/`didSave`, polling up to 15 s).

### Observed behaviour

An option (`String latheProbeOpt()` with `@Option.Configured` and `@Option.Default`) was added to
`common/configurable/.../AllowListConfigBlueprint.java` outside the server, and a test in
`webserver/webserver` called `AllowListConfig.builder().latheProbeOpt("x")`.
The pass rewrote `AllowListConfigBlueprint.class`, but `AllowListConfig.java` in
`.lathe/common/configurable/generated-sources` and its `AllowListConfig*.class` kept the dates of the
last sync, and the caller kept reporting `cannot find symbol` (also after 8 s).
Removing the option again was handled correctly, because the stale builder has no such option either.

Editor: the same external edit did not regenerate the builder within 15 s, and neither did an editor
save of the blueprint (`didChange`, the file written, `didSave`): the mirror's `AllowListConfig.java`
never gained the option, and an open dependent calling it kept `cannot find symbol`.
So this is not only an external-change gap; editing a Helidon blueprint in the editor never updates
its builder without a sync.
[EG-099](gaps-archive.md) is a different question (which generated copy navigation resolves to) and
still holds.

### Cause

The write sets `ANNOTATION_PROCESSOR_PATH` from `ModuleSourceConfig.remappedProcessorPath()`, which
maps reactor processors to their `.lathe/<module>/classes` mirror, e.g. `.lathe/codegen/apt/classes`.
That mirror holds the classes but not
`META-INF/services/javax.annotation.processing.Processor`, which exists in
`codegen/apt/target/classes` because the build generates it (there is no
`src/main/resources` in `codegen/apt`).
javac finds no processor, so the write silently compiles without code generation.
Processors from dependency jars are not remapped, so they presumably still run (not re-verified here).

### Fix

The services file is written by Helidon's services Maven plugin in `process-classes`, from the
`provides` in `module-info.java`, after the compiler shim has copied the compile's output into the
mirror; `lathe:sync` runs at the start of the build (in the root project, before any module
compiles), so it could not add it either.
The extension's `afterSessionEnd`, which runs after every module, now adds to each mirror the files
its build output has and the mirror lacks (`LatheLifecycleParticipant.mirrorLateOutputs`, using the
output-to-mirror map of `workspace.json` through `ReactorRewrite.mirrors()` and the new
`FileUtil.copyMissing`), never a class, which the server may have rewritten since.
It is best-effort, before the reactor lock is released, and works with mvnd.

Verified on Helidon after one `.lathe/lathe-sync.sh codegen/apt`: an external blueprint edit and an
editor save both regenerate `AllowListConfig`, through MCP and the editor.
Limit: it needs the extension; a manual POM setup still lacks such files.

### Regression targets

- `LatheLifecycleParticipantTest.mirrorLateOutputs_fileAddedAfterCompile_copiedIntoTheMirror_classesAndUnmirroredLeftAlone`
- `LatheLifecycleParticipantTest.mirrorLateOutputs_noOrUnreadableManifest_doesNothingAndNeverThrows`
- `FileUtilTest.copyMissing_keptFilesDestLacks_copiedExistingAndFilteredLeftAlone`

---

## WS-14 — A deleted resource stays in the mirror — done

**Status: done — Target: next.**
**Affects: the editor and MCP.**
Found by the external-change staleness probe (2026-10-24) on EqualsVerifier and Dropwizard through
MCP, and on all three test workspaces through a matching LSP probe the same day (an editor session driven through `didOpen`/`didSave`, polling up to 15 s).

### Observed behaviour

A test resource (`src/test/resources/latheprobe.txt`) was added outside the server, and a test that
asserts `getClass().getResource("/latheprobe.txt") != null` passed through MCP `run_test`, as expected.
After the resource was deleted, the same test still passed, after waiting 8 s as well:
`.lathe/<module>/test-classes/latheprobe.txt` was still there.

Editor: the added resource was copied into the mirror within 1 s; after the delete the mirror copy was
still there 15 s later, so the editor's test runs and the debugger see it too.

### Cause

The pass copies changed resources (`WorkspaceSession.reconcileResources` → `copyIfStale`), but only
copies; nothing removes the mirror copy of a resource whose source is gone, unlike classes, whose
deleted sources are dropped in the pass's first step.

### Fix

The pass remembers the resource files its last scan found (`scannedResources`, session-thread state)
and removes the mirror copy (`manifest.resourceDestination`) of each one that is gone since and does
not exist on disk.
It never deletes a mirror file only because it has no source: the mirror also holds the build's own
output, such as a generated `META-INF/services` entry.

Known limit: the first pass after startup has no earlier scan, so a resource deleted while no server
ran keeps its copy until a sync.

### Regression targets

Fails without the deletion:

- `WritePassTest.reconcile_resourceDeletedOnDisk_removesItsCopy_keepsOtherFilesInTheMirror`

---

## WS-13 — A build file changed while no server ran is never detected — done

**Status: done — Target: next.**
**Affects: the editor and MCP.**
Found by the external-change staleness probe (2026-10-24) on EqualsVerifier, Dropwizard, and Helidon,
through MCP and through a matching LSP probe the same day (an editor session driven through `didOpen`/`didSave`, polling up to 15 s).

### Observed behaviour

A module `pom.xml` was edited (a comment appended) while no server was running, then a session was
started and queried.
No `[watcher] build files changed` line was logged and no sync was requested, so the answers came from
a mirror built for the old POM.
The same edit made while the server runs is detected within one tick.

Editor: a live POM edit raised the "Build files changed" sync prompt within 0.5 s; an edit made before
the server started raised no prompt within 15 s, on all three workspaces.
MCP: neither case yielded a note (the live one was
[MC-14](#mc-14--a-changed-build-file-never-yields-sync-required--done)).

### Cause

`WorkspaceWatcher.updateBuildFiles` takes its fingerprint baseline (mtime and size) from the build
files as they are at startup, so a change made before startup is part of the baseline.
The startup pass checks sources against their compile stamps, but nothing compares build files against
the state the last sync saw.

### Fix

`WorkspaceWatcher.updateBuildFiles` baselines a build file newer than `workspace.json` as unseen,
so the first poll reports `BUILD_FILES_CHANGED` and the existing reaction follows: the editor's sync
prompt on the first tick, and through the MCP freshen step (which polls the watcher since the
[MC-14](#mc-14--a-changed-build-file-never-yields-sync-required--done) fix) a "Sync required" note on
the first query.
That first version compared mtimes, so a POM merely touched, or edited and put back (a branch switch
and back, a probe's revert), asked for a sync until the next full sync — a scoped `-pl` sync does not
rewrite `workspace.json`.
It now compares content: `lathe:sync` records each POM's fingerprint (size and CRC32C, schema 6) as
`buildFiles[].fingerprint`, and the watcher re-fingerprints a POM whose mtime or size moved, counting it
changed only while its content differs from the synced one; when every changed POM is back, it reports
`BUILD_FILES_RESTORED` and the session drops the pending sync (MCP's note goes; the next change
prompts again). A POM changed while no server ran is caught the same way at startup, by content.
OpenJDK's `_the.<batch>_batch` markers, which `make` only touches, carry no fingerprint and keep the
touch watch, startup included (newer than `workspace.json`).

### Regression targets

- `WorkspaceWatcherTest.poll_contentWatchedBuildFile_touchIgnored_editReported_revertReportedAsRestored`
- `WorkspaceWatcherTest.poll_contentWatchedBuildFileNewerThanManifestAtStartup_reportedOnlyIfContentDiffers`
- `WorkspaceWatcherTest.poll_buildFileEditedBeforeStartup_reportedOnFirstPollOnlyWhenNewerThanManifest`
  (touch-watched)
- `LatheEngineNotesTest.notes_buildFileEditedMidSession_syncRequiredUntilItsContentIsBack_editsStillCompile`
- `LatheEngineNotesTest.notes_buildFileChangedBeforeTheSessionStarted_syncRequiredOnlyIfItsContentDiffers`

---

## WS-12 — `lathe-sync.sh` drops the `-D` properties of the build it was generated from — non-goal

**Status: non-goal (2026-10-24): documented behaviour, no code change.**
Not a regression: no version of the script has replayed the build's flags (the 0.1.22 script ran
`"$mvn" --no-transfer-progress -Dmaven.build.cache.enabled=false $scope $goal` too).

### Observed behaviour

EqualsVerifier synced on JDK 17 only with `-DdisableStaticAnalysis`, because its Error Prone 2.50.0
needs JDK 21+ to run.
The generated `.lathe/lathe-sync.sh` runs
`mvn --no-transfer-progress -Dmaven.build.cache.enabled=false -pl equalsverifier-core -am process-test-classes`
without that property, so every scripted sync — including the one `verify_change` recommends —
fails with a compilation error, which before the
[WS-11](gaps-archive.md#ws-11--a-failed-maven-compile-empties-the-modules-lathe-mirror--done) fix
also emptied the mirror.

### Resolution

The preferred direction below was taken.

Preferred: do not replay the command line, and document that flags every build needs belong in the
build tool's persistent configuration — `.mvn/maven.config` (or `MAVEN_ARGS` on Maven 3.9+, set per
shell, so `cmd` and PowerShell differ on Windows), and for the planned Gradle front-end
`gradle.properties` or `~/.gradle/gradle.properties`; a failed scripted sync could point there.
Replaying is unsafe because every Lathe-enabled build rewrites the script: per-invocation flags
(`-Dtest=…`, `-DskipTests`, Gradle's `-x test`) would stick and silently narrow or disable capture,
and secrets passed as `-D`/`-P` (signing keys, tokens) would be written to `.lathe/` and echoed by
`lathe-sync: $*`.

The [workspace contract](../lathe-workspace-contract.md#lathe-syncsh) and the
[installation guide](../guide/installation.md) now say the script runs the bare sync goal and where flags
every build needs belong. The Gradle design keeps replaying only `--init-script` (without it the replayed
build has no Lathe) and no longer replays `-P`/`-D`.

### Regression targets

None: there is no behaviour to guard.

---

## WS-11 — A failed Maven compile empties the module's `.lathe` mirror — done

**Status: done — Target: next** (commit 3b31205a).

A single failed `mvn` run left Lathe broken after the code was fixed.
maven-compiler-plugin empties `target/classes` before a full recompile ("Recompiling the module
because of changed source code"), and `LatheCompiler.syncOutput` copied that empty output over the
module's `.lathe` mirror — it skipped only javac failures with no diagnostics — and rewrote the compile
stamps.
Every dependent lost the module's types (false `cannot find symbol` errors), and because the stamps
claimed every source freshly compiled, the server never rebuilt the missing classes in-process.
First seen through a sync that failed on Error Prone; confirmed with an ordinary type error.

Spike on EqualsVerifier (JDK 17): a type error in `equalsverifier-core`, a failing
`mvn process-test-classes`, then the error fixed and saved in the editor, with a server running:

| Step | Before the fix (mirror / dependent errors) | After the fix |
|---|---|---|
| failing Maven build | 161 → 0 classes / 4 false errors | 161 / 0 (core shows only its real error) |
| error fixed in the editor, +5 s and +35 s | 1 / 4 | 161 / 0 |
| server restarted | 1 / 4 | 161 / 0 |

### Resolution

`syncOutput` writes the params on every compile (a module that fails on its first sync must still
load), but after a failed compile keeps the class mirror, the generated-sources mirror and the compile
stamps: dependents keep resolving against the last good bytecode, and the sources changed since then
stay stale, so the server recompiles them in-process.
It logs "compile failed; kept the last good capture" (WARN when javac failed without diagnostics)
instead of "capture complete".
A Gradle front-end must keep the same rule: Gradle's `compileJava` also clears its outputs on a full
recompile.

### Regression targets

- `LatheCompilerSyncOutputTest.syncOutput_failedCompile_writesParamsKeepsMirrorsAndStamps`
  (failed before the fix).
- `LatheCompilerSyncOutputTest.syncOutput_successfulCompile_replacesMirrorsAndStamps`.

---

## WS-1 — Reactor mirror and type index go silently stale after a source change or branch switch

**Status: done — Target: M2.**

Resolution (updated): superseded the original detect → prompt with **in-process workspace sync** —
source changes made outside the editor are recompiled into the `.lathe/` mirror on the idle tick
(changed files in dependency order, deletions removing their classes, open dependents refreshed), with
the Maven prompt reserved for POM/structural and bulk changes. See
[In-Process Workspace Sync](../done/lathe-in-process-workspace-sync.md). The per-source compile stamps
introduced for detection remain the freshness signal the reaction reads.

Discovered by workflow analysis (not live probing) while reconciling CA-4; recorded here because it
is the general problem of which CA-4's closed-file residual is one facet.

### Observed behaviour

Switching git branches — or otherwise changing source files outside the editor — leaves Lathe
describing the **previous** state of the workspace, usually with no prompt:

- After `git checkout <branch>` where only Java sources differ (the common case), the watcher
  reports `NO_CHANGE` and the user is never told to re-sync. The `.lathe/` mirrored bytecode and the
  reactor type-index shards continue to reflect the old branch until the next
  `mvn process-test-classes`.
- Types **added** on the new branch are missing from completion, missing-import actions, and
  `workspace/symbol` (CA-4's open-file enrichment only softens this for a file the user actually
  opens).
- Types **removed** on the new branch linger as phantom entries: completion offers them and
  missing-import actions insert an `import` for a class that no longer exists.
- `definition`, `references`, and `typeHierarchy` into non-open reactor sources resolve against the
  stale mirror and can point at old-branch files or positions.

If the branch differs in POM files, `WorkspaceWatcher` fingerprints the POMs and does raise the
advisory "run `mvn process-test-classes`" prompt — but that path only fires on POM changes, not on
source changes, and `reload()` merely re-reads the still-stale `.lathe/` from disk (it does not
re-run Maven).

### Root cause

Staleness detection is intentionally coarse and keyed only to Lathe's own artifacts:

- `WorkspaceWatcher.poll()` checks exactly two things — `workspace.json` mtime (→ full `reload()`)
  and POM fingerprints (mtime + size → advisory sync prompt). It never inspects source-root
  contents.
- `LatheWorkspaceService.didChangeWatchedFiles` acts **only** on `FileChangeType.Deleted` events;
  `Created` and `Changed` events on non-open source files are dropped.
- The reactor type index and the `.lathe/` mirror are produced only by `lathe:sync` and are never
  invalidated by filesystem source changes.

The `lathe-lightweight-watcher.md` design's Non-Goals claim source watching "is already handled via
LSP `workspace/didChangeWatchedFiles`" — the deletion-only implementation makes that claim
inaccurate today.

### Proposed fix

Not yet decided; options to weigh when scheduled, cheapest first:

1. Detect that a tracked source root's newest mtime is ahead of the last recorded sync and raise the
   same advisory sync prompt already used for `POM_CHANGED` — no invalidation, just an honest nudge.
2. Act on `Created`/`Changed` watched-file events (not only `Deleted`) to invalidate or refresh the
   affected reactor type-index entries between syncs.
3. A fuller freshness model that reconciles the reactor index and mirror with on-disk sources
   without a Maven round trip; overlaps with [Sibling Recompilation](../done/lathe-sibling-recompilation.md)
   and the [Reactor Type Index](../done/lathe-reactor-type-index.md) freshness follow-ups.

Scheduled for M2 as **WS-5**. The original plan was option 2 (in-process recompile), but that is
**parked** — correct only for single-module change sets — in favour of **option 1**: detect source
staleness and reuse the shipped sync prompt, per
[lathe-external-change-detection.md](../done/lathe-external-change-detection.md). The parked compile
design is [lathe-external-change-recompilation.md](../potential/lathe-external-change-recompilation.md).
WS-1 remains the umbrella for the wider reconciliation (option 3) and cross-module cases.

This subsumes CA-4's remaining closed-file case (new/renamed types in files the user has not opened),
which is only discoverable today after a manual sync.

### Probe commands

Not probeable through `explore.py`; reproduced by checking out a branch that adds and removes a
reactor type, then requesting completion / missing-import actions without running
`mvn process-test-classes`.

### Regression targets

None yet — to be defined when the fix is scheduled.

---

## WS-3 — The POM-changed sync prompt re-appears every 2s, and "Sync" neither runs Maven nor is distinguished from "Later"

**Status: done — Target: M2**

### Observed behaviour

After a `git checkout` where the branches differ in POM files, the advisory prompt fires:

```
Maven project changed. Run 'mvn process-test-classes' to refresh Lathe.
1: Sync
2: Later
```

- Choosing **Later** (`2`) dismisses it, but it **re-appears ~2 seconds later**, and keeps re-appearing
  on every poll — an inescapable loop until the user actually runs `mvn process-test-classes`.
- Choosing **Sync** (`1`) behaves identically: it does **not** run Maven, does **not** reload, and the
  prompt returns 2 seconds later. The "Sync" label implies the server will sync; it does not.
- Cancelling (`q` / empty) has the same effect — the loop continues.

### Root cause

Two defects in `WorkspaceSession.checkForChanges` / the watcher baseline
(`WorkspaceSession.java:2005-2023`, `WorkspaceWatcher.java`):

1. **"Later" is not honoured across polls.** The poll runs every 2s
   (`worker.scheduleAtFixedRate(2_000L, this::checkForChanges)`, `:185`). `POM_CHANGED` is derived by
   `WorkspaceWatcher.detectPomChange()`, which compares live POM fingerprints against `pomBaseline`.
   The baseline is refreshed **only** in `reloadWorkspace()` (via `updatePomPaths`), which runs on a
   `workspace.json` change — never on a prompt response. So after the user answers, the live POMs still
   differ from the baseline and the very next poll returns `POM_CHANGED` again. The
   `pomNotificationPending` flag only de-dupes a *concurrent* prompt; it is reset as soon as the user
   responds (`thenAccept(action -> … pomNotificationPending = false)`, `:2018`), so it does nothing to
   suppress the *next* poll's prompt.

2. **The chosen action is discarded.** The `showMessageRequest` callback ignores its `action`
   argument entirely and just clears `pomNotificationPending` for both "Sync" and "Later" (and for a
   null/cancelled response). Nothing dispatches on `"Sync"`, so selecting it runs no Maven and triggers
   no reload — consistent with Lathe's rule that the LSP server never invokes Maven, but then the
   button is mislabeled and misleading.

### Proposed direction

Decided — this is now the actionable heavy-path prompt (the companion to the WS-5 light-regime
auto-recompile). Three parts:

- **Honour a dismissal (loop fix).** On "Later"/cancel, snapshot the *current* POM fingerprints as an
  "acknowledged" baseline so `detectPomChange()` stays quiet until the POMs change **again** (a further
  edit or another branch switch) — without treating the project as synced (the mirror/index are still
  stale; only the nagging stops). Distinct from `updatePomPaths`, which asserts freshness. The prompt
  then shows **once per POM change**, not every 2s.
- **Keep detection on the server poll.** POM/`workspace.json` detection stays in `WorkspaceWatcher`
  (small, bounded, server-authoritative) and is **not** folded into `workspace/didChangeWatchedFiles`
  — that mechanism is for the huge source/resource set (WS-5). Two mechanisms, clean split.
- **Actionable prompt (Alternative A — client runs Maven, server never does).** Dispatch on the
  returned `MessageActionItem` instead of discarding it; on a chosen action the server sends a custom
  `lathe/sync` notification and the **client** runs Maven as a job, after which the server picks up the
  refreshed `.lathe/` (WS-4). Offer **two** actions:
  - **"Sync"** → `mvn process-test-classes` — refresh types/mirror/main-launch + manifest (LSP + *main*
    run/debug).
  - **"Sync + capture tests"** → `mvn test` (capture flavor) — also (re)captures `test-launch.json`
    (which is *captured from a test fork*, not derived by `lathe:sync`), needed for neotest / test-run
    / test-debug on new or changed test modules.

  Both actions offered on every POM change for now; **auto-recommending** capture only when a
  new/uncaptured module is detected is deferred (Slice 2, backlog). Open question: whether a plain
  dependency change also invalidates the captured `test-launch.json` (making capture the default rather
  than the secondary action) — depends on the capture model (writer still in progress); confirm before
  wiring the default.

Relates to WS-1 (staleness/invalidation umbrella), WS-2 (the deferred *source-only* branch-switch
prompt — now revived as the chosen approach), WS-4 (post-Maven pickup), and WS-5 /
[lathe-external-change-detection.md](../done/lathe-external-change-detection.md) (source staleness
also routes to this same prompt). WS-3 itself is the shipped-behaviour reliability defect (looping
prompt + inert "Sync").

### Probe commands

Not probeable through `explore.py`. Reproduced by checking out a branch whose POMs differ, then
answering the prompt (either option) and observing it return after ~2s.

### Regression targets

Loop fix (server):
- `WorkspaceWatcherTest.poll_afterChange_secondPollReturnsNoChange`
  (positive — a changed POM reports on every poll until `acknowledgePoms()`, then goes quiet)

Actionable prompt (end-to-end):
- `LspSmokeTest.pomChange_triggersResyncPrompt`
  (a POM change prompts with the three actions `Sync` / `Sync + capture tests` / `Later`)

Client dispatch (Neovim):
- `sync_spec.lua` — the `lathe/sync` handler and `:LatheSync` run `mvn process-test-classes` (or
  `mvn test` with `captureTests`) at the workspace root; a concurrent sync for the same root is a
  no-op.

The server-side action→`lathe/sync` dispatch (`WorkspaceSession.onSyncPromptResponse`) is a small
switch exercised through the client handler above; a dedicated e2e assertion was skipped as too racy
against the shared-server prompt state.

---

## WS-4 — `workspace/symbol` misses newly added reactor types after an incremental `mvn process-test-classes` (no clean)

**Status: non-goal — not an issue**

### Observed behaviour

After switching to a branch that adds new classes and running `mvn process-test-classes` **without
`clean`**, the new types do not appear in the `workspace/symbol` picker (`\ws` in the reporter's
Neovim config). The sync ran, the classes compiled, yet they stay invisible to symbol search until
the server is restarted (or a manifest-changing sync happens).

### Root cause

The reactor type index is an in-memory cache that is only rebuilt on a `workspace.json` mtime change,
and the incremental sync does not change `workspace.json`:

1. `WorkspaceSession.typeIndex` (`:135`) is built once at init from the per-module shard files
   (`WorkspaceTypeIndex.build(manifest.typeIndexShardPaths(), reactorShards.values())`, `:182`) and
   rebuilt **only** in `reloadWorkspace()` (`:2049-2051`, which does `reactorShards.clear()` →
   `scanReactorShards()` → rebuild `typeIndex`). `workspace/symbol` queries this cached snapshot.
2. `reloadWorkspace()` runs only via `reload()`, triggered by `WorkspaceWatcher` returning
   `WORKSPACE_CHANGED`, which is derived purely from the `workspace.json` **mtime**
   (`WorkspaceWatcher.detectManifestChange`).
3. `WorkspaceManifestWriter.write` **skips the write when the content is unchanged**
   (`WorkspaceManifestWriter.java:48-50` — `"[sync] workspace unchanged — skipping write"`). Adding a
   class to an **existing** module leaves the manifest identical (same modules, source roots, and
   classpath; shards are per-module, so no new shard **path**), so the file is not rewritten and its
   mtime does not move.

Net: `lathe:sync` refreshes the module's type-index **shard content** on disk, but the server never
re-reads it because its only reload trigger (manifest mtime) never fires. The reporter's "not using
`clean`" detail fits: an incremental sync is exactly the case where the manifest content is unchanged.
The existing lighter refresh (`typeIndex.withReactorEntries(...)`, `:1870`) only runs for open-file
recompiles, so it does not cover types added by an external sync.

### Proposed direction

Not yet decided; options, cheapest first:

1. **Fingerprint the shard files** in `WorkspaceWatcher` (mtime + size of `manifest.typeIndexShardPaths()`),
   not just `workspace.json`, and refresh the type index when any shard changes — ideally a
   type-index-only refresh rather than a full `reloadWorkspace()` (which also rebuilds the candidate
   index and refreshes open documents).
2. Have `lathe:sync` bump `workspace.json`'s mtime whenever it rewrites any shard, even when the
   manifest content is unchanged, so the existing `WORKSPACE_CHANGED` path fires (simpler, but reuses
   the heavy reload).

Distinct from WS-1/WS-2, which cover staleness when **no** sync is run; WS-4 is the case where the
user **did** run the sync and the refreshed shards are still ignored. WS-1 remains the umbrella for a
fuller freshness model.

### Scope — the in-editor create-and-save path is NOT affected (verified)

Creating a type in the editor and **saving** it already updates `workspace/symbol` and needs no fix.
The module save route compiles into the `.lathe/<module>/classes` mirror
(`ModuleSourceCompiler` sets `CLASS_OUTPUT` to `config.latheClassesDir()`), and `afterModuleSave` →
`refreshReactorShard(config)` re-scans that dir and rebuilds `typeIndex`. Verified live against the
`multi-module` invoker workspace: a new `WsProbeWidget` type returned *no symbol* right after
`didOpen`, then — after a save — `workspace/symbol` returned exactly one hit pointing at its source,
and `.lathe/app/classes/.../WsProbeWidget.class` appeared. WS-4 is therefore scoped strictly to the
**external** refresh path (branch switch + `mvn process-test-classes`, no editor save), where nothing
triggers `refreshReactorShard`/`reload`.

Note the coverage context: the individual pieces are unit-tested only with hand-built indices
(`WorkspaceTypeIndexTest`, `WorkspaceSymbolTest`, `ClassFileTypeScannerTest`), and **no** end-to-end
test drives "workspace change → index reflects it" — `LspSmokeTest` never issues `workspace/symbol` at
all. The regression targets below therefore both fix WS-4 and close that end-to-end gap (the
save-path target locks in behaviour that works today but is otherwise untested).

### Probe commands

WS-4 itself (external re-sync) is not probeable through `explore.py`. Reproduce by adding a class to an
existing module, running `mvn process-test-classes` (no `clean`), and issuing `workspace/symbol` for
the new name without restarting the server. The sibling save-path *is* probeable and was used to
confirm the scope above:

```bash
# against a synced workspace, in one session: query before save, save, query after
printf 'sym NewType\ndiag\nsym NewType\n' | python3 dev/explore.py <ws>/.../NewType.java
```

### Regression targets

Implemented via the **manifest mtime-touch** signal: `lathe:sync` bumps `workspace.json`'s mtime on
every run (even when content is unchanged), and the watcher compares content to distinguish a
reactor-only refresh (`REACTOR_REFRESH`, silent reactor re-scan) from a structural change
(`WORKSPACE_CHANGED`, full reload).

- `WorkspaceManifestWriterTest.write_sameContent_touchesMtimeButKeepsContent`
  (positive — a skipped rewrite still bumps the mtime; content stays byte-identical)
- `WorkspaceWatcherTest.poll_manifestTouchedSameContent_returnsMirrorRefreshOnceThenNoChange`
  (positive — mtime bump, same content → one `REACTOR_REFRESH`, then quiet)
- `WorkspaceWatcherTest.poll_contentChangeAfterMirrorTouch_returnsWorkspaceChanged`
  (negative/boundary — a real content change is still `WORKSPACE_CHANGED`)

End-to-end coverage this gap exposed (invoker `LspSmokeTest`, previously never exercised
`workspace/symbol`):

- `LspSmokeTest.workspaceSymbol_reactorType_resolvesAcrossModules`
  (positive — reactor types from different modules resolve via `workspace/symbol`)
- `LspSmokeTest.workspaceReload_manifestContentChanged_notifiesUser`
  (positive — a manifest **content** change is structural → full reload + notification, distinct from
  the silent mtime touch)

Still open (deferred to the WS-5 / broader freshness work): a `workspaceSymbol_afterStaleTypeRemoved`
negative and a fabricated-`.class` "new external type appears" positive at the integration level.

---

## WS-5 — External on-disk edits to sources/resources are not picked up without a Maven build — done

**Status: done.**

### Historical observation

External source/resource edits stayed invisible until a build refreshed `.lathe/`.
The first fix detected changed sources and prompted for sync, while copying resources automatically.
That implementation was later superseded by the in-process write pass (WS-1); the old client watched-file handlers and debounce/prompt tests are not the current pipeline.

### Current resolution

`WritePass` detects and recompiles external source changes in dependency order, refreshes affected open dependents, and mirrors changed/deleted resources.
A build-file change still asks for sync.
Source deletion is handled by the current write pass, rather than remaining the deferred follow-up the historical entry described.
See [One Write Pass](../done/lathe-recompile-simplification.md) and [In-Process Workspace Sync](../done/lathe-in-process-workspace-sync.md).

### Regression targets

- `WritePassTest.reconcileNow_closedSourceChangedExternally_recompilesInProcessWithoutPrompting`
- `WritePassTest.initialize_moreExternalChangesThanTheOldBulkThreshold_compiledInProcessWithoutNotice`
- `WritePassTest.reconcileNow_deletedDependency_republishesOpenDependentWithError`
- `WritePassTest.initialize_copiesOnlyStaleResourcesIntoLathe`
- `WritePassTest.reconcile_resourceDeletedOnDisk_removesItsCopy_keepsOtherFilesInTheMirror`

---

## WS-6 — Open files in dependent modules keep stale diagnostics after an upstream open file is saved

**Status: done — Target: M2.**

Resolution: an upstream save marks dependent sources stale via compile stamps, surfaced as a Neovim
sync prompt (detect → prompt); the prompted re-sync refreshes downstream diagnostics.

Discovered while scoping WS-5. This is the *in-session, open-file* facet of cross-module freshness —
distinct from WS-5 (external/closed-file edits) and cheaper, because it only concerns files the user
already has open.

### Observed behaviour

With a file open in module B that depends on module A, editing and saving an open file in A does not
refresh B's open buffer: B's diagnostics, semantic tokens, and quick-fixes continue to reflect A's
previous state until B is itself edited or reopened. Adding or changing a public API in A — a new
method, a changed signature, a removed type — is therefore invisible to an already-open dependent file
even though A's new bytecode was written to `.lathe/A/classes` by the save.

What *does* work today: same-module open files refresh, and A's type-index shard is rebuilt, so
cross-module `workspace/symbol` / navigation / completion for A's new types resolve. Only the
**dependent module's live per-file analysis** (diagnostics/tokens) is left stale.

### Root cause

On save, [`WorkspaceSession.afterModuleSave`](../../lathe-server/src/main/java/io/github/aglibs/lathe/server/WorkspaceSession.java)
runs `scheduleOpenFilesInModule(savedUri, savedModule)`, which reschedules open documents filtered to
the **same** `moduleDir`:

```java
.filter(uri -> workspace.moduleSourceFor(...).map(m -> m.moduleDir().equals(savedModule.moduleDir())).orElse(false))
```

Open files in *downstream* modules are never rescheduled (the method's "…for dependents…" log line
overstates what the filter does). A full reschedule of every open file happens only on a whole
workspace `reload()` (`scheduleAllOpenFiles`), i.e. after a Maven sync — not on an ordinary in-editor
save.

### Proposed direction

The graph primitive already exists: `WorkspaceModuleGraph.referenceSearchScope(config)` returns the
declaring module plus its transitive downstream dependents (`downstreamOf`). The fix is to reschedule
open files in any module within that scope (recompiled in `OPEN` mode against the now-fresh
`.lathe/A/classes`), instead of filtering to the same module. It stays bounded — only files the user
has open, no closed-file compile, no reactor rebuild — which is what separates it from WS-5 and from
[Sibling Recompilation](../done/lathe-sibling-recompilation.md) (its closed-file, whole-module
counterpart).

**Caveat to resolve first:** module B's `CompilationWorker` holds a `StandardJavaFileManager` that may
cache A's *old* `.class`. Rescheduling B's open file is only correct if B's compiler first invalidates
its cached view of A (there is a `dropFromCache(uri)` on the worker; the right invalidation granularity
for a cross-module class change needs confirming). This caching concern is the likely reason the
current code restricts refresh to the same module.

### Probe commands

Not probeable through `explore.py`; reproduce in one session against the `multi-module` invoker
workspace: open a file in a downstream module, add a public method to an open upstream file, save the
upstream file, then request diagnostics/completion in the downstream file without editing it —
today the new method is absent.

### Regression targets

None yet — to be defined when scheduled (upstream open-file save → downstream open file re-analysed
against the new API; no spurious reschedule of open files in unrelated modules).

Relates to WS-1 (the staleness umbrella), WS-5 (the closed-file/external counterpart; its in-process
recompile is being reconsidered in favour of the WS-3 sync prompt — a docs reconciliation still
pending), and [Sibling Recompilation](../done/lathe-sibling-recompilation.md) (the closed-file,
whole-module dependent recompilation).

---

## WS-7 — A submodule build without a root `.mvn/` can create a stray `.lathe/` inside the module

**Status: done — Target: M2**

**Resolved.** `ReactorProjects.isReactorRootBuild` now decides purely from on-disk `<modules>`
aggregation: `isReactorRoot(basedir)` walks up ancestor directories and, at each `pom.xml` (read via
Maven's own `MavenXpp3Reader`), returns false when a `<module>` resolves **exactly** to the candidate.
No `.mvn/` dependence, correct at any nesting depth (incl. pom-less intermediate dirs), and — unlike a
bare "any ancestor pom/`.lathe`" existence check — it does not false-skip a project merely nested
*under* an unrelated pom or a declared module (e.g. an IT fixture under `lathe-maven-plugin/target`).
Only `InitMojo`/`SyncMojo` consult it; the compiler's class-refresh into an existing root `.lathe/`
(`-pl`, in-submodule) is untouched. Covered by `ReactorProjectsTest` (root / deep-submodule / nested-
under-a-module / symlink) and the `multi-module` invoker.

Discovered while verifying `ac1c4f0` (don't create a submodule `.lathe` on a `-pl` build) for the
WS-8 targeted-sync work.

### Observed behaviour

Running Maven directly under a module directory (`cd <module> && mvn compile`) in a multi-module
project that has **no `.mvn/` at the reactor root** creates a stray `.lathe/` inside that submodule.
The editor's `.lathe` root marker then resolves to the submodule (a module maps to a blank
`moduleRel`) — the same failure `ac1c4f0` fixed for `-pl`: runnables discovery crashes on `RunTarget`
validation.

`-pl <module> -am` **from the reactor root** is already correct: the compiler copies classes into the
existing root `.lathe/`, and `InitMojo`/`SyncMojo` skip (no manifest rewrite). This gap is only the
*direct in-submodule invocation without a root `.mvn/`*.

### Root cause

`ReactorProjects.isMultiModuleRootBuild(session)` gates `.lathe` creation on
`topLevelProject.basedir == request.multiModuleProjectDirectory`. Maven derives
`multiModuleProjectDirectory` by searching up for a `.mvn/` directory; with none present it defaults to
the invocation directory. So `cd <module> && mvn` sets `multiModuleProjectDirectory` to the module
dir, which equals `topLevelProject.basedir` → the guard passes → `InitMojo` runs
`Files.createDirectories(topLevel/.lathe)` inside the submodule. The `multi-module` invoker fixture
that "verified" `ac1c4f0` has a root `.mvn/`, so it cannot catch this case.

The class-transfer path is unaffected: `LatheCompiler`/`LatheWorkspace.findRoot` only ever *walk up to
an existing* `.lathe/` and never create one.

### Proposed fix

Strengthen the gate with an on-disk aggregator check in `ReactorProjects`:

- `hasAggregatorAncestorOnDisk(moduleBasedir)`: walk up ancestor directories; for each `pom.xml`, read
  its `<modules>` and resolve each entry against that directory. If any resolves onto the path down to
  `moduleBasedir`, the project is a submodule → not a root.
- `isMultiModuleRootBuild` becomes
  `isSameDirectory(topLevel, multiModuleDir) && !hasAggregatorAncestorOnDisk(topLevel.basedir)`.
- Read `<modules>` via Maven's `ModelReader` component (or `MavenXpp3Reader`) — entries are literal
  directory names, so a raw model read is enough. External framework parents resolved from `~/.m2` are
  not on the ancestor path, so a genuine single-module root still gets its `.lathe/`.

`InitMojo`/`SyncMojo` need no change — both already call the gate. Known limitation to document:
profile-activated `<modules>` in an ancestor are invisible to a raw read.

### Regression targets

- `ReactorProjectsTest.isReactorRoot_ancestorAggregatesViaModules_false`
- `ReactorProjectsTest.isReactorRoot_noAncestorAggregatesItExactly_true`
- A new invoker fixture **without** a root `.mvn/`: build a submodule from its own directory and assert
  no `.lathe/` is created in the submodule (the `-pl`-from-root and root-build cases stay covered by
  `multi-module`).

---

## WS-8 — Targeted `mvn -pl <changed> -am` sync instead of a full-reactor build

**Status: done — Target: M2**

**Resolved.** The source-staleness scan now returns the stale modules (`StaleScan{newestMtime, modules}`);
`checkSourceStaleness` derives their reactor-relative `moduleRel`s and carries them to `requestSync`.
`syncScope` falls back to a **full** reactor build once **≥ `FULL_SYNC_PERCENT` (75%)** of the reactor's
modules changed (or on a POM/structural change) — otherwise it targets the changed modules; a full build
is also the only one that regenerates `workspace.json`, which `-pl` cannot. The prompt reflects the real
action: a partial refresh names ≤ `MODULE_NAME_LIMIT` (3) modules in brackets (else a count), a full
refresh shows a count, and a structural change is generic (*"Maven project changed. Lathe will run a full
refresh."*). `LatheSyncParams.modules` carries the selectors; the client builds `mvn … -pl <modules> -am
<goal>` (`-am` is required because a partial `process-test-classes` cannot resolve reactor-sibling deps
from `~/.m2`; `-amd` is intentionally omitted — dependents are WS-6). New modules are structural (they
edit a tracked parent POM → `POM_CHANGED` → full), so they are handled by the POM path, not the targeted
scan. Verified end-to-end in a multi-module workspace (a `module-info.java` touch in one module → prompt
names that module → `mvn … -pl <module> -am process-test-classes`). Manual `:LatheSync <module>` targeting
+ completion is deferred to the CQ-0055 LSP module/package sharing.

Optimization on top of WS-5. On a large reactor a full `mvn process-test-classes` to refresh one edited
module is slow; the server already knows which modules are stale and could ask for a scoped build.

### Observed behaviour

When WS-5 detection fires (a closed source is stale), the sync prompt runs a **full-reactor**
`mvn process-test-classes`, even if a single module changed. On a 300-module reactor this is far more
work than needed to refresh the affected module's `.lathe/` mirror.

### Root cause

By design, the WS-3 prompt / `LatheSyncParams` carry only `workspaceRoot` + `captureTests`; the client
always runs a whole-reactor build. The staleness scan iterates per `ModuleSourceConfig` but discards
which modules were stale (`newestStaleMtime` returns only a max mtime).

### Proposed direction

Route a **source-only** change to a scoped build; keep full builds for structural changes.

- **Server:** have the staleness scan also collect the set of stale modules (their reactor-relative
  `moduleRel`, which is exactly what `-pl` accepts, since `workspaceRoot == reactorRoot`). Add
  `List<String> modules` to `LatheSyncParams` (empty ⇒ full). In `checkSourceStaleness`/`requestSync`:
  a POM/structure change (POM fingerprint / `pomNotificationPending`) ⇒ empty ⇒ full build (only a full
  build regenerates `workspace.json`, which `-pl` deliberately skips); source-only ⇒ the stale
  `moduleRel` set; all/most modules stale ⇒ drop `-pl`.
- **Client (`sync.lua`):** `run_maven` gains the module list; with modules present, build
  `mvn … -pl m1,m2 -am <goal>`; the `lathe/sync` handler forwards `result.modules`. The scoped build is
  driven entirely by the server's stale-module detection; manual `:LatheSync` stays a full-reactor
  build. No per-module manual selection — a user syncing by hand wants the whole reactor, so a
  module-picker / `<Tab>`-completion affordance is deliberately not built (KISS).

### Correctness caveats (to bake in and document)

- `-am` builds **upstream** deps only, not dependents (`-amd`). The edited module compiles against fresh
  upstreams; a downstream module's `.lathe` mirror is not refreshed by this build. Acceptable for a
  source-edit refresh (downstream sources aren't stale) and avoids `-amd` ballooning into a near-full
  build. The open-file dependent case is WS-6.
- `-pl` does **not** rewrite `workspace.json` (verified in the `ac1c4f0`/WS-7 trace) — correct here
  precisely because the module structure did not change.
- Use `moduleRel` path form for `-pl`; fall back to `:artifactId` (stored in the manifest) if
  nested-path forms prove fragile.

Depends on WS-7 (the `-pl`/submodule `.lathe` behaviour must be sound first).

### Regression targets

- `WorkspaceSessionTest` — source-only stale ⇒ sync params carry the stale `moduleRel`s; POM change ⇒
  empty (full).
- `sync_spec` — `-pl m1,m2 -am` present with modules, absent without.
- Optional invoker/e2e — `-pl <module> -am` refreshes that module's `.lathe/classes` and leaves
  `workspace.json` untouched.

---

## WS-2 — No re-sync prompt after a source-only branch switch

**Status: non-goal (superseded) — Target: n/a.**

**Resolved by in-process workspace sync** ([design](../done/lathe-in-process-workspace-sync.md)): a
source-only change made outside the editor is now recompiled into the `.lathe/` mirror on the idle tick
(changed files in dependency order, deletions removing their classes, open dependents refreshed) — the
non-intrusive resolution this gap called for, with no prompt. A prompt fires only for a POM/structural
change (WS-3) or a *bulk* branch-switch-scale change (the escape hatch). WS-2's original Sync/Later
prompt-for-source proposal is therefore not pursued. Original analysis retained below.

Deferred from M2. The proposed fix was a Sync/Later prompt, but a prompt is both intrusive and
non-binding: the user can dismiss it and keep working against stale state, so it adds friction without a
reliable payoff — and a developer who deliberately switches branches already knows Lathe needs a manual
re-sync. The behaviour is documented instead (README → "How it works → Build capture"): files you have
open are analysed live, and changes to files you don't have open are picked up at the next
`mvn process-test-classes`. Revisit only if beta users report silent-staleness confusion (e.g. after a
`git pull` or merge they did not initiate), and then with a non-intrusive signal rather than a prompt.
WS-1 remains the backlog umbrella for actual invalidation/reconciliation.

### Observed behaviour

After a `git checkout` that changes only Java sources, `WorkspaceWatcher.poll()` reports `NO_CHANGE` and
the user is never told to re-sync, so completion, missing-import, `workspace/symbol`, and navigation
silently reflect the *previous* branch until the next `mvn process-test-classes`. For a public user
switching branches daily, silent staleness reads as "Lathe is wrong."

### Root cause

Staleness detection is keyed only to Lathe's own artifacts: `WorkspaceWatcher.poll()` checks
`workspace.json` mtime and POM fingerprints, never source-root contents (see WS-1 root cause).

### Proposed direction

WS-1 option 1 (cheapest): detect that a tracked source root's newest mtime is ahead of the last recorded
sync and raise the same advisory "run `mvn process-test-classes`" prompt already used for `POM_CHANGED` —
detection and an honest nudge, no invalidation. Full auto-invalidation / no-Maven freshness (WS-1
options 2–3) stays backlog.

### Regression targets

None yet — to be defined when scheduled (source newer than last sync → advisory prompt; no prompt when
in sync).

---

## WS-10 — A `sealed` type and a newly added permitted subtype cannot both be saved: each single-file compile fails against the other's stale mirror bytecode

**Status: done — Target: next.**

Fixed by compiling mutually dependent same-tree sources together in one javac task (the batch FULL
primitive the design called for), so a sealed root and a new permitted subtype resolve against one
another instead of each other's stale mirror bytecode. Two paths, both shipped:

- **Background reaction** — `WorkspaceSession` now issues one FULL batch per source tree over its
  stable stale set (`compileChangedBatch` → `CompilationWorker.compileBatch` →
  `ModuleSourceCompiler.compileBatch` → `JavacRunner.compileBatch`), replacing the per-file loop.
  Verified live: an externally-changed sealed pair recompiles both `.class` into the mirror in one
  pass, either edit order.
- **Live editor diagnostics** — a single-file compile that fails with `compiler.err.cant.resolve*`
  or `compiler.err.cant.inherit.from.sealed` widens into the module's open + stale siblings
  (analyze-only, `diagnoseInBatch`) and republishes just the target's diagnostics, clearing the
  spurious squiggle.

Out of scope (rare, debugger-only): a sealed pair that is both *open and saved* leaves the mirror
`.class` stale until close or a Maven sync — the reaction skips open files and the single-file save
still can't write that mirror. Diagnostics are correct either way.

### Observed behaviour

Adding a new permitted subtype to an existing sealed hierarchy leaves both files un-saveable — every
save order produces a spurious, un-clearable error. Starting from a consistent, mirrored pair
(`sealed interface Shape permits Circle` + `final class Circle implements Shape`), the user edits
`Shape` to `permits Circle, Square` and creates `Square implements Shape`:

- Save `Shape` first → `cannot find symbol: class Square` (the new `Square.class` is not in the mirror yet).
- Save `Square` first → `class is not allowed to extend sealed class: Shape (as it is not listed in
  its 'permits' clause)` (the mirror's `Shape.class` still permits only `Circle`).

Neither file can advance, because each needs the *other's fresh* bytecode and the stale `.lathe/` mirror
has neither. The same red diagnostic also appears live on the open buffers before any save.

### Root cause

Lathe compiles each changed/open file **in isolation**:

- The compile unit is a single `JavaFileObject` — `JavacRunner.compileFull` wraps it as
  `List.of(sourceFile)`, and both the live path (`WorkspaceSession.onChange`/`onSave` →
  `compileAndPublish`) and the background reaction (`compileChangedSource` → one `CompileRequest`) drive
  one file at a time.
- `StandardLocation.CLASS_PATH` points at the `.lathe/` mirror's compiled `.class` files — *stale*
  bytecode (`ModuleSourceCompiler.initLocations`).
- **No `StandardLocation.SOURCE_PATH` is ever set**, so javac cannot pull a sibling `.java` on demand.

The `sealed`/`permits` relationship is encoded bidirectionally in bytecode (the parent's
`PermittedSubclasses` attribute and the child's superclass reference), and javac validates *both*
directions at compile time. So there is no valid intermediate single-file state — a genuine deadlock.

This is one instance of a general class: **any two same-module files where each, in its edited state,
references something newly added in the other.** Empirically, only `sealed` is un-stageable:

| Construction | Both save-orders fail? | Stageable (a valid save order exists)? |
|---|---|---|
| `sealed` / `permits` (new subtype) | yes | **no** — the two edits must land atomically |
| Mutual new methods/fields | yes | yes — add members first, then the calls (verified) |
| F-bounded generics `A<T extends B<T>>` ↔ `B<T extends A<T>>` | when both bounds are new | mostly |
| `module-info` `provides…with` + new impl | rarely | yes — save the impl first |

`sealed`/`permits` is therefore the only construction a user can actually get stuck on; the others share
the identical root cause and are fixed for free by the same primitive, but are worked around by save
order.

### Proposed fix — batch compilation, grouped by module

Compile mutually dependent sources **together in one javac task** so intra-batch cross-references
resolve. This needs the batch **FULL**-compile primitive already called for (but never built) in the
archived R1 design ([external-change-recompilation](../potential/lathe-external-change-recompilation.md)):
a multi-file `analyzeBatch` exists but writes no `.class`.

**Batch boundary: the module, not the package.** An explicit `permits` clause requires permitted
subtypes to be in the same *module* (named/JPMS) or same *package* (unnamed module — a Maven module with
no `module-info`). Same-package is exactly correct only for non-JPMS modules and **misses** sealed
families that cross packages inside a JPMS module (the `multi-module` invoker's `jpms` module is such a
case). The module boundary is a guaranteed-complete superset — a sealed family can never cross it — and a
broader batch is never *incorrect* (javac just compiles the stale set together). Grouping by module also
matches the existing `staleByModule` grouping and the R1 design. The batch only ever contains the
**stale/dirty set** (what actually changed), so it stays small.

**Rejected alternative — set `SOURCE_PATH` to the real source roots.** It works when the source is newer
than the mirror, but it is **mtime-fragile**: javac's default `-Xprefer:newer` reverts to the stale
`.class` whenever Lathe's own mirror rewrites push the counterpart's `.class` mtime ahead of an untouched
source, silently reintroducing the deadlock. Forcing `-Xprefer:source` removes the mtime dependence but
makes javac recompile everything reachable on the sourcepath from source on *every* compile — defeating
the `.lathe` mirror's latency purpose. Both were verified empirically.

**Two compile paths to cover:**

1. **Background reaction** (mirror freshness, `compileChangedInOrder` / `reactToChangedSources`): replace
   the per-file loop with **one FULL batch compile per source tree (`ModuleSourceConfig`)** over the
   *stable* stale set, iterated in the existing `compileOrder` (upstream modules first, main tree before
   test tree). The batch unit is the source tree — not the module dir — because main (`classes`) and test
   (`test-classes`) trees have distinct `CLASS_OUTPUT`/`CLASS_PATH`/file managers and cannot share one
   javac task; a sealed family always lives within a single tree, so nothing is lost. Batching all stable
   stale files of a tree together (rather than file-by-file, or by computed connected-components) is what
   makes mutual dependencies resolve *and* gives correct annotation-processor aggregation — an AP that
   aggregates across sources must see the whole changed set at once, as Maven runs it. Safety: keep the
   two-tick `stableSources` settle gate (don't pull mid-write files in — a split pair self-heals on the
   next tick); the set is already bounded by `BULK_CHANGE_THRESHOLD`; javac's per-type `generate()` still
   writes error-free files even if a sibling is broken, so the coupled blast radius is limited and no
   diagnostics are published for non-open reaction files. `afterModuleSave` then runs once per batch
   instead of once per file. Connected-component sub-grouping is rejected as over-engineering — it needs
   the reference graph the compile would produce, and the stale set is already small.
2. **Live editor diagnostics** (`compileAndPublish`, the red squiggle before save): keep the fast
   single-file path and **widen on failure** — when a single-file compile fails with an
   unresolved-same-module-symbol or a sealed-`permits` error, rebuild the batch from the module's
   dirty (`docs`) + stale set using current buffer content and retry, then publish only the target file's
   per-file diagnostics.

**Implementation sketch (no ad-hoc parsing; javac drives everything):**

- `JavacRunner` — generalize `compileFull` to an `Iterable<JavaFileObject>` (mirror of
  `attributeBatch`): `task.analyze()` → `task.generate()` → union of `writtenBinaryNames` + per-file
  diagnostics.
- `ModuleSourceCompiler` / `JavaSourceCompiler` — a `compileBatch(sources, mode)` FULL entry that writes
  temp files (like `analyzeBatch`) and returns the union.
- `CompilationWorker` — a `compileBatch(List<CompileRequest>)` returning one `CompileResponse` per uri.
- `WorkspaceSession` — reaction fans the module's stale set into one batch; `afterModuleSave` runs once
  per module. Live path adds the widen-on-failure retry.

No new abstraction beyond the batch primitive, and no `permits`-clause parsing: membership is decided by
module + stale/dirty, and javac resolves the hierarchy inside the batch.

### Investigation evidence (empirical, javac 26, `--release 21`)

| Config | `Shape` first | `Square` first |
|---|---|---|
| Single file, `CLASS_PATH`=stale mirror, no sourcepath (Lathe today) | ❌ | ❌ |
| Single file + `SOURCE_PATH`=src, source newer than mirror | ✅ | ✅ |
| Single file + `SOURCE_PATH`=src, mirror newer than source | ✅ | ❌ (reverts to stale `.class`) |
| **Batch both units, `CLASS_PATH`=stale mirror, no sourcepath** | **✅ writes both** | **✅ writes both** |

### Regression targets

- Batch compile of a sealed root + a new permitted subtype in the same module writes both `.class` files
  and reports no diagnostics, in either edit order (non-JPMS package and JPMS cross-package).
- The reaction path issues one FULL compile per source tree (`ModuleSourceConfig`) over the stable stale
  set (not N single-file compiles), in `compileOrder`.
- Live path: a single-file compile that fails on an unresolved same-module symbol / sealed-`permits`
  error is retried as a module batch and clears the spurious diagnostic; an unrelated real error still
  surfaces on the correct file.
- Negative: a single stale file (counterpart already consistent in the mirror) still takes the fast
  single-file path — no needless widening.

---

# Test Execution (TE) — resolved

## TE-24 — A test method marked by a custom annotation built on JUnit's `@Testable` gets no run entry — done

**Status: done** (2026-11-23)

**Kind: bug**

**Affects: the editor's runnables of a test file (run and debug entries, neotest) and MCP `run_test` on a file, for tests a project marks with its own annotation: Apache Kafka's `@ClusterTest`, `@ClusterTests` and `@ClusterTemplate` (about 150 files).**

Found evaluating Lathe on Apache Kafka (2026-11-18), from the code; not run live, as Kafka's `@ClusterTest` files are in `clients-integration-tests`, which needs the Scala `core` built.

### Observed behaviour

Kafka's `@ClusterTest` is annotated `@TestTemplate`, `@Tag("integration")` and `@Timeout(60)`; JUnit runs each such method as a test template.
`RunnableScanner.isTestAnnotation` takes a method as a test only by its annotation's simple name (`Test`, `ParameterizedTest`, `TestFactory`, `RepeatedTest`), so these methods get no run entry, and a file holding only them has no test to run.
A module run, through JUnit's own discovery of the captured launch, should still run them; not checked.

### Fix direction

Take a method as a test when one of its annotations is, or is itself annotated with, JUnit Platform's `org.junit.platform.commons.annotation.Testable`, which `@Test`, `@TestTemplate` and `@TestFactory` carry, keeping the names for JUnit 4's `org.junit.Test`, which does not.
That covers any custom test annotation built on JUnit's own; ArchUnit's `@ArchTest` (TE-18), on fields, likely carries `@Testable` too (to check).

### Resolution

A method is a test when one of its annotations is JUnit Platform's `@Testable` or reaches it through the annotations on it, at any depth (`@ClusterTest` → `@TestTemplate` → `@Testable`), as JUnit's own annotation search does.
The names it was recognized by before stay, for JUnit 4's and TestNG's `@Test`, which do not carry `@Testable`.
An annotation the classpath lacks is still known by its name in the class file that uses it, so the walk does not fail on it.

Checked on JUnit Pioneer (`af8ebb2`), whose `@CartesianTest` and `@RetryingTest` are built on `@TestTemplate`: `CartesianTestExtensionDemo.java`, 27 `@CartesianTest` methods and no `@Test`, gives 23 run targets where 0.3.1 gives none ("no test in CartesianTestExtensionDemo.java").
Its run then stops at the missing capture of Pioneer's `demo` source set, as any test there does.

Not covered: `@Testable` on a class or a field (ArchUnit's `@AnalyzeClasses` and `@ArchTest` fields), which the scan does not look at (TE-18).

### Regression targets

- `RunnableScannerTest.runnables_customAnnotationBuiltOnTestable_returnsMethodTargets`
- `RunnableScannerTest.runnables_metaAnnotationMissingFromClasspath_keepsOtherTests`

---

## TE-26 — A run that only some of its code fails to compile still runs on the old classes — done

**Status: done** (2026-11-18)

**Kind: bug**

**Affects: the editor's runs (`:LatheRun`, neotest, debug), `run_test` with `scope: package`, and a test that still compiles while code it calls no longer does.**

Split from MC-92 (2026-11-18), whose fix covers `run_test` on the test file itself.

### Observed behaviour

`run_test` now refuses a test file whose own compile has an error ("code it runs does not compile"), as when a class it uses was deleted or changed under it.
What still runs on the last good classes, and fails with `NoClassDefFoundError` or a changed signature at run time, found from the code:

- the editor's runs, through `RunCoordinator.failingUnder`, which checks failed build compiles only;
- the other test files of a `scope: package` run, as only the file asked about is compiled first;
- a test that compiles while an unchanged helper it calls uses a deleted class: the helper keeps its old class, and nothing recompiles it.

### Fix direction

Mark the unchanged dependents of a deleted or changed source as failing where the write pass handles the change (`WritePass.reconcileDeletedSourcesIn` and the write levels), so the existing check (`ChangeVerifier.failingInModule`) refuses every run path at once; or compile each file a run selects before it runs.

### Resolution

Every launch, the editor's runs and debugs and `run_test` alike, is checked first, as `verify_change` checks a change (`ChangeVerifier.failingForRun`): after the write pass has caught up, the changes since the build in the run's module and the modules it depends on, the files that use them, and the generated callers a rename left are compiled, and a launch whose code has an error is blocked, `code it runs does not compile: <file>:<line>: <error>`.
Nothing changed since the build: nothing is compiled.
`verify_change`'s compile step moved from `LatheEngine` into `ChangeVerifier` for it, and `verify_change` answers as before.
Checked on javaparser: with the helper `ExtractingVisitors.java` deleted, a package run through `JavaTokenTest.java` ran 3,070 tests on old classes on main; it is now blocked naming `ExpectedTokensTest.java:30: cannot find symbol`.

Left as they are: no cache between runs, so a large unbuilt change is compiled again before each run; a cancel during the check still starts the JVM, then kills it, as a cancel before the JVM exists does; a debug run's port is taken before the check.

### Regression targets

- `LatheWorkspaceServiceTest.executeCommand_runWhileAHelperUsesADeletedClass_blockedNamingTheHelper`
- `LatheWorkspaceServiceTest.executeCommand_runWhileModuleDoesNotCompile_blockedNamingTheFailure`
- `LatheEngineRunTest.runTest_classItUsesDeleted_notRunNamingItsError`

---

## TE-21 — A JUnit 6 build's tests replay on the JUnit 5 runner and fail before any test runs — done

**Status: done** (2026-11-18, PR #385)

**Kind: bug**

**Affects: editor test runs and MCP `run_test` on a build that uses JUnit 6.**

Found by the exploratory round on Keycloak (2026-11-17, Lathe 0.3.1).

### Observed behaviour

Keycloak's `services` tests run on JUnit 6.1.3 (its captured class path lists `junit-platform-commons-6.1.3.jar` and `junit-jupiter-api-6.1.3.jar`).
The workspace's `runnerClasspath` names `junit-platform-launcher-1.10.3.jar` and `junit-jupiter-engine-5.10.3.jar`, the fallback versions.
`run_test` on `VaultTranscriberTest` then fails in discovery, after 76 s: `NoSuchMethodError: 'boolean org.junit.platform.commons.util.ReflectionUtils.returnsVoid(java.lang.reflect.Method)'`, the 5.10 engine calling into the 6.1 platform commons.
The manifest came from a whole-reactor sync stopped after the root project's sync, which is the step that writes it in a full build too.

### Fix direction

Likely cause, unconfirmed: the runner's versions are read from the reactor's external artifacts when the root project syncs, and those do not include the modules' test-scoped JUnit.
Take the platform and Jupiter versions from the captured test class paths, per module if they differ, and never pair a launcher and engine of another major version with the project's platform.

### Resolution

The sync resolves a test runner for each JUnit platform version the reactor's test classpaths hold: the launcher, and the engine of the newest Jupiter of its generation (1.x for Jupiter 5, the same version from 6 on), or the launcher alone for the vintage engine.
`workspace.json` records them by version, and each module replays on the runner of the platform its own test classpath names, so a reactor on JUnit 5 and 6 at once runs both; Gradle files each project's aligned runner under its launcher's version.
The first fix alone (the newest version, compared as a version, rather than the first by name) broke a JUnit 5 module beside JUnit 6 ones, which the per-version runners fix.

### Regression targets

- `ServerInstallerTest.resolveRunnerClasspath_severalJUnitVersions_resolvesTheHighest`
- `ServerInstallerTest.resolveRunnerClasspath_newerPlatformThanJupiter_launcherOfJupitersGeneration`
- `ServerInstallerTest.resolveRunnerClasspath_versionThatDoesNotParse_stillResolved`
- `ServerInstallerTest.resolveRunnerClasspaths_platformWithoutJupiter_launcherAlone`
- `RunCoordinatorTest.runnerFor_moduleOnEitherPlatform_itsOwnRunnerElseTheWorkspaces`
- `FileUtilTest.jarVersion_repositoryJarOrOther_versionOrNone`

---

## TE-23 — A test JVM run outside its module's directory writes its capture under the wrong module — done

**Status: done** (2026-11-18)

**Kind: bug**

**Affects: editor test runs and MCP `run_test` on a build whose test JVM runs in another directory than its module's (Apache Lucene; any Gradle `test { workingDir }`); the setup build ran every such test in full.**

Found by the exploratory round on Apache Lucene (2026-11-18, Lathe 0.3.1); cause found on main `e2af3ade`.

### Observed behaviour

The capture-only setup ran every Lucene test task (31 in the first report, 37 on main `e2af3ade`), and the Gradle plugin reported for each `:lucene:core:test captured no test launch: its test JVM did not load lathe-junit, so its tests ran in full`, suggesting a plugin like Develocity's test selection, which Lucene does not use.
lathe-junit did load, and every test JVM captured, but at `.lathe/lucene/core/build/tests-cwd/test-launch.json` and the like, where the plugin does not look.
The capture took its module from the JVM's working directory, and Lucene runs each module's tests in `<module>/build/tests-cwd`.

### Resolution

The Gradle plugin names the module directory of every test task it captures in `LATHE_CAPTURE_MODULE` (`LatheFlags.CAPTURE_MODULE_ENV`), an environment variable, so no task input changes.
The capture writes its launch for that module when the tests run inside it, and otherwise for the working directory, as on Maven, whose Surefire runs in the module: a nested build the tests start in a temporary directory inherits the variable, and must not write over the module's launch.
A build that turns Lathe off (`lathe.disabled`, or on CI) still captures nothing.
The launch keeps the JVM's own working directory, since the build ran the tests there and they may read files from it, and the server makes that directory before a test replay when a `clean` removed it, never a run configuration's own `cwd`.
Re-checked on Lucene: all 37 captures are at their modules' paths with no "captured no test launch" message, and `run_test` on `TestDocValuesQueries` passes 41 tests after `build/tests-cwd` was deleted.

### Regression targets

- `LaunchCaptureTest.site_moduleNamedOrNot_capturesForTheNamedModuleKeepingTheWorkingDirectory`
- `LaunchCaptureTest.site_workingOutsideTheNamedModule_theWorkingDirectoryDecides`
- `RunOverlayTest.makeCapturedWorkingDir_capturedOrConfiguredCwd_onlyTheCapturedOneMade`
- `LatheCaptureTaskFunctionalTest.capture_testsRunOutsideTheProjectDirectory_launchIsTheModulesAndKeepsTheDirectory`

---

## TE-22 — The capture-only sync fails on a module whose POM sets `<failIfNoTests>true</failIfNoTests>` — done

**Status: done** (2026-11-18, by TE-19's fix, PR #376)

**Kind: bug**


**Affects: Maven setup (`:LatheInit`) and `.lathe/lathe-sync.sh --tests`.**

Found by the exploratory round on javaparser (2026-11-18, main `cdf57f1`).

### Observed behaviour

A capture-only build narrows Surefire to the first type of the module's test sources in name order (`LatheModelInjector.injectCaptureClass`).
In `javaparser-core-testing` that type has no tests, so Surefire runs none ("Tests run: 0"), and the module's own `<failIfNoTests>true</failIfNoTests>` fails the build: "No tests were executed!".
The `-DfailIfNoTests=false` the setup passes loses to the POM's explicit value, so 2 of 10 trees are captured and the rest of the reactor is skipped.
The Maven guide says the setting matters only on JUnit older than 5.10; javaparser is on JUnit 5.14.

### Fix direction

Pick a type that holds a test (a name Surefire's default includes match, `*Test`/`Test*`/`*Tests`/`*TestCase`), or keep the narrowing off for a module whose Surefire configuration sets `failIfNoTests`.

### Resolution

The cause is TE-19's: the capture-only run narrowed Surefire to the first test source in name order, here the abstract `AbstractLexicalPreservingTest`.
TE-19's fix selects up to five sources, the names Surefire's default includes match first and abstract bases by name after them, so a runnable class starts the test JVM and its tests count as run (skipped by the dry run), which `failIfNoTests` accepts.
Checked on javaparser with an extension built from main `d27bf8b`: `javaparser-core-testing` reports `Tests run: 38, Skipped: 38`, the build succeeds, and its test launch is captured.

### Regression targets

- `LatheModelInjectorTest.injectCaptureClass_helpersAndAbstractBasesSortFirst_selectsRunnableTestNamesFirst`
- `LatheModelInjectorTest.injectCaptureClass_otherNamesInSeveralRoots_selectedAfterTheTestNames`

---

## TE-19 — A capture-only sync leaves some Surefire modules uncaptured, without a word — done

**Status: done** (2026-11-18)

**Kind: bug**


**Affects: Maven modules whose capture-only test run reports `Tests run: 0` (seen on jdbi: `core`, `sqlobject`, `postgres`, `spring` and others); `run_test`, neotest and `:LatheRun` on their tests.**

Found by the editor exploratory round on jdbi (2026-11-17).

### Observed behaviour

`:LatheInit` on jdbi succeeds and captures 21 modules' test launches, but not `core`, `sqlobject` and other modules with tests; nothing in the output says so.
Running `TestJdbi` then answers `blocked: no captured test launch for core; run .lathe/lathe-sync.sh --tests core`, and that command again reports `Tests run: 0, Failures: 0, Errors: 0, Skipped: 0` and writes no `.lathe/core/test-launch.json`.
`lathe-junit` is on the fork's test classpath (`-X`), Surefire uses the JUnit Platform provider with `forkCount=4`, and no fork reports a test class.
`vavr`, with the same Surefire settings, captures (its dry run reports 3 skipped tests).
`-Dtest=TestJdbi -Dlathe.capture.only=true` on `core` captures the launch (8 skipped).
`CaptureLauncherSessionListener` swallows every `Throwable`, so a failed capture leaves no trace.
[EG-067](#eg-067--the-first-build-floods-the-log-with-warnings--non-goal) saw `Tests run: 0, Skipped: 0` on EqualsVerifier's `equalsverifier-core` and `equalsverifier-test`; whether those were captured is not recorded.

Probe: on jdbi `core`, `mvn -pl core test -Dlathe.capture.only=true -X` with a stack trace printed from the listener's catch, and a log of whether `launcherSessionOpened` runs and in which working directory.
The warning in the fix direction does not wait on it.

### Fix direction

Find why the whole-module dry run starts no test class in these modules; whatever the cause, have the sync say which modules ran tests but captured no launch, and have the listener print why a capture failed instead of nothing.

### Cause (found 2026-11-18)

The Maven extension narrows a capture-only run to one class, the first test source in name order (`LatheModelInjector.injectCaptureClass`), to start the test JVM without discovering every class.
When that class is one Surefire does not run, Surefire starts no test JVM at all: nothing is captured, and the build passes with `Tests run: 0`.
Every jdbi module left uncaptured started from an abstract base or a helper (`core`: `AbstractJavaTimeArrayTests`, `json`: `AbstractIssue2395Test`, `guice`: `GuiceTestSupport`, `postgres`: `FooBarPGType`, `spring`: `Callback`), and every captured one from a test class.
`mysql` and `postgis` exclude every test in their default profile, so they have nothing to capture.
The listener was not at fault: it never ran.

### Resolution

A capture-only run selects up to five test sources: the names Surefire's default includes match, then abstract bases by name (`Abstract*`), then the rest, each in name order. One runnable class among them starts the JVM, and a module's own includes (a `@Suite`) keep their classes in the selection.
A module with Kotlin, Groovy or Scala test sources, in a test root or beside a `src/<set>/java` one (jdbi's `src/test/kotlin`, a root only later in the build), is not narrowed: its Java sources may be only helpers.
A capture that fails in the test JVM now prints `[lathe] test launch not captured: <cause> at <frame>` to the build's test output; the report itself cannot fail the tests.
Checked on jdbi with the branch's extension: all ten modules left uncaptured now capture (`core`, `sqlobject`, `json`, `guice`, `kotlin`, `kotlin-sqlobject`, `postgres`, `spring`, `spring5`, `opentelemetry`); the `capture-only-suite` invoker fixture still discovers its suite.
The sync still cannot list the modules that captured nothing: it runs at the root's `process-test-classes`, before any module's tests.

### Regression targets

- `LatheModelInjectorTest.injectCaptureClass_helpersAndAbstractBasesSortFirst_selectsRunnableTestNamesFirst`
- `LatheModelInjectorTest.injectCaptureClass_otherNamesInSeveralRoots_selectedAfterTheTestNames`
- `LatheModelInjectorTest.injectCaptureClass_kotlinBesideTheJavaRoot_leavesTheSelection`
- `CaptureLauncherSessionListenerTest.notCaptured_failure_printsOneLineNamingTheCauseAndWhere`
- `CaptureLauncherSessionListenerTest.notCaptured_causeWhoseToStringThrows_returnsQuietly`
- invoker fixture `capture-only-suite`

---

## TE-10 — `run_test` on a method name shared by two top-level classes runs only the first — done

**Status: done.**

**Affects: MCP.**

Found by the pre-release test rounds (2026-10-29/30) on a fresh Maven project.
With two classes in one file declaring the same test method, top-level or `@Nested`, `run_test` with `scope=method` ran only the first and did not mention the other.

### Resolution

A method name runs in every class of the file that declares it, nested ones included (`LatheEngine.selectTargets`), and the `Ran:` line lists each.
Each class needs its own method selector, which the runner already takes several of.

### Regression targets

- `LatheEngineRunTest.selectTargets_nestedAndSecondTopLevelClass_runsEveryOutermostClassAndEveryMethodOfTheName`
- `LatheTestRunnerTest.run_selectMethodOfANestedClass_runsIt`

---

## TE-17 — A test that locates its sources from its own class file fails when replayed — done (workaround documented)

**Status: done (workaround documented).**

**Value: low — Size: M**

**Affects: run and debug of such tests from the editor and MCP, on Maven and Gradle.**

Found while checking the replay of Gradle source sets' tests on Hibernate ORM (2026-11-08).

### Observed behaviour

Hibernate ORM's annotation-processor tests (`TestUtil.getSourceBaseDir`, through `@CompilationTest`) find the sources they compile by walking up from the test's own class file: the folder they climb to must be named after the source set, as in the build's `build/classes/java/<sourceSet>`.
A replay loads the classes from Lathe's mirror, `.lathe/<module>/<tree>/`, so the test derives `src/test-classes/java` (or `src/classes-jakartaData/java`), finds no source there, and fails before its first assertion; it also creates that empty folder under the workspace.
This holds for the `test` tree and a source set's tree alike, so it predates source-set launches.

### Fix direction

Replay from the build's own output where it is current, or keep the build's folder names under the mirror; either changes what the mirror stands for, so it needs a design of its own.
Until then, such tests run with the build.

A first attempt (2026-11-08, pulled from the PR that added source-set launches) loaded the run's own tree from the build's output while that tree's compile stamps matched what the build compiled.
Its review showed what a correct version must also cover:
- every tree on the launch's classpath, not only the run's own: a save in `main` or an upstream module recompiles its copy, so test classes from the build's output next to it were compiled against the old API (`NoSuchMethodError`, a stale constant);
- resources: on Maven they share the test output directory, and the stamps record Java sources only, so a resource saved since the build was not seen;
- the module lock: the build's output can be mid-write during a sync, and the completeness gate no longer waits on it once the entry maps to no mirror;
- an output directory several trees share.

### Resolution

Closed by a documented workaround rather than a change to the replay (2026-11-08).

**Where it applies.** Of the eight builds Lathe is checked on, two have such tests, all annotation-processor or code-generator tests:
- Hibernate ORM's `tooling/metamodel-generator`: 132 test classes through `@CompilationTest` (92 in `test`, 40 in `jakartaData`), the whole module;
- Helidon's data modules: 5 test classes reading `<class folder>/../generated-sources/annotations`.

Micronaut, junit-framework and Dropwizard use a class's location only to load jars or isolate class loaders, which replay handles; Caffeine, Fineract and OpenMetadata have none.

**Why not in Lathe.** A safe version would load the run's own tree from the build's output only while every copy on the launch's classpath is unchanged since the build: a marker per tree that the server's three writers set (javac's class output on a save, stale-class deletions, resource copies) and the capture clears.
It would help a test's first run after a build, but not the loop these tests are run in: editing the processor under test is a change on their classpath, which falls back to the copies.
Covering that loop means keeping the build's own output in step with the copies, or running such tests through the build; both reverse a design choice (the build owns its outputs; the server never runs the build).

**The workaround** ([Troubleshooting](../guide/troubleshooting.md)): run such a test with the build; or, in the project, find the files without the class file's location, from the working directory (the module's folder for Gradle, Surefire and Lathe) or from a system property the build sets (Gradle's `systemProperty`, Maven's `argLine`, which Lathe captures, unlike `<systemPropertyVariables>`).

### Regression targets

None yet.

---

## TE-12 — On Gradle, only each project's `test` task is captured — done

**Status: done.**

**Value: medium — Size: L**

**Affects: run, debug and replay on Gradle builds with more than one test task or source set.**

Found while documenting the Gradle plugin (2026-10-30), from its design notes.

### Observed behaviour

The plugin loaded `lathe-junit` into each project's `test` task only.
Tests of other test tasks (an `integrationTest` task, a JVM test suite, Hibernate ORM's `jakartaDataTest`) were never captured, so they could not be run or debugged from the editor or an agent;
since [WS-33](#ws-33--gradle-source-sets-beyond-main-and-test-get-no-editor-features--done) their source sets had editor features, and a run in them was refused with the reason.

### Fix direction

Step 2 of [Gradle Source Sets](../done/lathe-gradle-source-sets.md#49-runs): a launch per source set's tree, captured by the test task that runs its classes, and runs routed by the tree a class is in.

### Resolution

Once a build script has run, each `Test` task is matched to the source set whose classes it runs.
A match loads `lathe-junit` as `test` does, with its tree named in its environment (`LATHE_CAPTURE_TREE`), so it writes `test-launch-<tree>.json`;
run capture-only (`-Plathe.capture.only=true`) it runs as `test` does, on one class, dry, where it was skipped before; the editor's sync does not pull it in, as its dependencies could start a database on every sync.
A class or method run, and a `main` in such a tree, replay from that tree's launch; package, directory and module runs stay with the `test` tree.

On Hibernate ORM a capture-only sync captures `jakartaData`, `integrationTest`, `dataIntegrationTest` and `legacyTest`, and a test of `integrationTest` run through the server passes.
Hibernate ORM's processor tests fail when replayed, `test`'s too, for a reason of their own, with a documented workaround: [TE-17](#te-17--a-test-that-locates-its-sources-from-its-own-class-file-fails-when-replayed--done-workaround-documented).

### Regression targets

`LatheSyncTaskFunctionalTest.sourceSetTestTask_anyRun_capturesItsTreesOwnLaunch`,
`LatheTextDocumentServiceTest.runFutures_classOfASourceSetTree_replayFromItsOwnLaunch`,
`LatheTextDocumentServiceTest.runTestFuture_classesOfSourceSetTrees_blockedNamingTheSourceSet`,
`LaunchReaderTest.read_presentLaunchFiles_returnsData`,
`SourceSetScopeTest.treeOf_classOnlyInASourceSetTree_namesThatTree`,
`LaunchCaptureTest.launchFile_treeNamedOrNot_namedTreesOwnFileElseTheTestLaunch`.

---

## TE-16 — A capture-only sync runs every test class, and on Gradle other test tasks, to capture one launch — done

**Status: done.**

**Value: medium — Size: S**

**Affects: the editor's sync with tests and the first build, on Maven and Gradle, most on large modules, builds that fork several test JVMs, and Gradle builds whose test task depends on other test tasks.**

Found while checking the Gradle plugin on the public Gradle projects (2026-11-06): the capture-only `latheSync` of Hibernate ORM ran 17 test JVMs at once and saturated a 16-core machine.

### Observed behaviour

A capture-only run captures the launch the moment JUnit's session opens in the test JVM, then lets the test task do all its usual work.
The build starts every test JVM its fork settings ask for (Hibernate ORM's `maxParallelForks`, a Surefire `forkCount`) and hands them every test class; JUnit discovers each, dry run reports each test as skipped, and the build writes its reports.
In Hibernate ORM's `hibernate-core` that was 94 s, 4,953 classes, 16,043 skipped tests, 25 MB of XML results and a 121 MB HTML report, which replaced the last real report.
Each test JVM wrote the same `test-launch.json`.

### Fix direction

Give a capture-only run one test class: the launch is the same whichever classes run, and any class starts the test JVM, one without tests too.
Both tools filter by class name before they start a test JVM, and start one only for a class they hand out, so one class means one JVM, one discovery and a one-class report.

### Resolution

On Gradle, the capture-only test task filters itself, as it starts, to the first top-level class in name order among those it scans (`Test.getCandidateClassFiles()`, so the task's own includes and excludes keep none out), replacing the build's own test filter and not failing for matching no tests.
On Maven, the extension sets each module's `test` property to the simple name of the first top-level Java test source, and `surefire.failIfNoSpecifiedTests` to `false`; nothing is compiled when it runs, hence the sources.
A `-Dtest` the user passes, or a `test` property the module sets, is kept; a module without Java test sources keeps running every class.

Measured with the plugin built into Lathe 0.3.1, compiles up to date, the recorded `test-launch.json` identical before and after:

| Capture-only sync | Before | After |
|---|---|---|
| Hibernate ORM, whole `latheSync` | 114.7 s | 76.7 s |
| Hibernate ORM, `hibernate-core`'s test task | 94 s, 4,953 report files | no report file |
| Micronaut, whole `latheSync` | stopped after more than 3½ minutes | 131.9 s |
| Dropwizard `dropwizard-jersey` (Maven, `-Dtest` by hand) | 4.6 s, 23.6 s CPU, 55 report files | 3.9 s, 18.8 s CPU, none |

On Maven the class picked can be abstract (`AbstractJerseyTest`), and Surefire still starts the JVM; on a JUnit `@Suite` the suite's members are still discovered and skipped.

Most of Hibernate ORM's remaining time was test tasks other than `test` that its `test` depends on (`jakartaDataTest`, `legacyTest` and two integration-test tasks, about 70 s), which ran their tests in full, since only `test` gets the capture-only treatment.
Their launches are not captured ([TE-12](#te-12--on-gradle-only-each-projects-test-task-is-captured--done)), so a capture-only sync now skips every test task other than `test`, saying why; a plain build still runs them.
The figures above predate this skip.

### Regression targets

`LatheSyncTaskFunctionalTest.captureOnlySync_manyTestClassesAndForks_runsOneClassInOneTestJvm`,
`LatheModelInjectorTest.injectCaptureClass_testSourcesInSeveralRoots_selectsTheFirstClassOnly`,
`LatheModelInjectorTest.injectCaptureClass_noJavaSourcesOrOwnSelection_leavesTheSelection`.

---

## TE-7 — A captured launch naming files the build deleted replays into a class-loading failure — done

**Status: done.**

**Value: low — Size: S**

**Affects: run, debug and replay on Gradle builds that run tests through the Develocity plugin.**

Found by the Gradle plugin's end-to-end check (2026-10-25) on JUnit's own build.
Gradle support is not on `main` yet (branch `feat/gradle-plugin`), so this gap applies once it lands.

### Observed behaviour

The Develocity Gradle plugin starts the test JVM through its own launcher, with that launcher's jar
and a copy of `opentest4j` in a temporary directory (`/tmp/test-distribution-test-launcher-forked…/`)
that is deleted after the run; it does so even with the build's own switch for Test Distribution off.
The capture records the launch it saw, so `test-launch.json` names files that no longer exist.
The server's completeness gate checks only the reactor's trees, so the replay launches anyway and
fails with `NoClassDefFoundError: org/opentest4j/AssertionFailedError`.

### Fix direction

Decided 2026-10-25: a known limitation, not worked around (mapping the temporary copies back to
same-named jars is a guess; switching Develocity off needs the Develocity plugin's API).
The gate blocks a replay whose class or module path names a missing file outside the reactor, with
a reason naming the file and suggesting a launch outside the Develocity plugin's test acceleration;
the Gradle guide documents the limitation.

### Resolution

The completeness gate now blocks a replay whose class or module path names a jar that no `.lathe/` mirror stands
for and that no longer exists, with a reason naming the jar and Develocity's test acceleration as the usual cause.
A missing directory is left alone, since builds list output directories that were never created.
The Gradle guide says to turn Develocity's Predictive Test Selection and Test Distribution off for the capture-only
sync; with them off (`-Pjunit.develocity.predictiveTestSelection.enabled=false` in JUnit's build), `jupiter-tests`
captures a launch whose 50 entries all exist.

### Regression targets

`CompletenessGateTest.verify_capturedJarDeleted_blockedNamingIt_missingDirectoryNot`.

---

## TE-13 — On Gradle, test runs in JUnit's own repository fail to load opentest4j — duplicate of TE-7

**Status: done (duplicate of TE-7).**

**Value: medium — Size: S**

**Affects: editor test runs and MCP `run_test` on JUnit's own repository; possibly any JUnit 6 build with parallel execution.**

Found by the end-to-end Gradle check (2026-11-04): Lathe 0.3.0 as released, the plugin from a local repository standing in for the Gradle Plugin Portal, applied with the guide's personal init script and first-run command `./gradlew latheSync -Plathe.capture.only=true`.

### Observed behaviour

In junit-framework, `documentation` is captured (its `test-launch.json` lists `opentest4j-1.3.0.jar` on its class path), yet a run of `example.MyFirstJUnitJupiterRecordTests`, from the editor or MCP, fails before any test: `NoClassDefFoundError: org/opentest4j/TestAbortedException`, thrown in a JUnit 6 parallel-execution worker (`WorkerThreadPoolHierarchicalTestExecutorService`) while initializing `OpenTest4JAndJUnit4AwareThrowableCollector`.
JUnit tests the platform it builds, so the replay rewrites the platform's own jars to their `.lathe/` mirrors; whether that or JUnit 6's worker threads is the cause is open.
Test runs in Micronaut, Hibernate ORM, Caffeine and a JUnit 5 fixture pass.

### Fix direction

Reproduce on a small JUnit 6 project with parallel execution enabled; if it passes there, it is specific to a build that tests the JUnit Platform itself.
Then compare the replay's class path and class-loader setup with Gradle's test worker for the failing run.

### Resolution

The same cause as TE-7: the captured `documentation` launch names
`/tmp/test-distribution-test-launcher-forked…/opentest4j-1.3.0.jar` and the launcher's own jar, both deleted after the
run, so the replay could not load `org.opentest4j.TestAbortedException`; JUnit 6's parallel worker only happened to
load it first.
The TE-7 gate now blocks that run with a reason naming the missing jar.

### Regression targets

See TE-7.

---

## TE-14 — On Gradle, JUnit's own repository captures one test module of five, silently — done

**Status: done.**

**Value: low — Size: S**

**Affects: editor test runs and MCP `run_test` on JUnit's own repository.**

Found by the end-to-end Gradle check (2026-11-04): Lathe 0.3.0 as released, the plugin from a local repository standing in for the Gradle Plugin Portal, applied with the guide's personal init script and first-run command `./gradlew latheSync -Plathe.capture.only=true`.

### Observed behaviour

The capture-only sync of junit-framework runs the `test` task of all five test modules (`documentation`, `jupiter-tests`, `platform-tests`, `junit-vintage-engine`, `platform-tooling-support-tests`), but only `documentation` writes a `test-launch.json`; the build succeeds and nothing says why the other four wrote none.
A run in one of them answers that no test launch was captured.
Micronaut (64 of its test modules) and Hibernate ORM (17) capture as expected.

### Fix direction

Find what keeps the capture listener from recording in those forks (JUnit tests its own platform, which may shadow the listener's), and report a test task that ran without a capture.

### Resolution

JUnit's build turns Develocity's Predictive Test Selection on by default.
Develocity then runs the selected tests in a JVM of its own, which does not get the test task's `JDK_JAVA_OPTIONS`:
lathe-junit never loads, so nothing is captured, and without its filter the capture-only sync ran every test in full.
Sometimes its test-selection JVM, which does load lathe-junit, captures a launch instead, naming jars in a temporary
directory deleted after the run (TE-7's launch).
Lathe cannot reach the JVM that runs the tests, so the capture-only test task now writes a marker as it starts and
warns, naming Develocity's test acceleration, when no launch was written after it
(`[lathe] :jupiter-tests:test captured no test launch …`) or when the launch written names a jar that is gone
(`… captured a launch naming …, a temporary jar …`): Develocity deletes its launcher's copies only later in the build,
so a jar in the temporary directory outside the build and Gradle's user home counts, as well as one already gone.
With Predictive Test Selection off, `jupiter-tests` captures and skips its tests as expected.

### Regression targets

`LatheSyncTaskFunctionalTest.captureOnlySync_testJvmWithoutTheAgent_warnsThatNothingWasCaptured`,
`LatheTestCaptureTest.temporaryJar_launcherCopyOrGone_namesIt_buildCachesAndDirectoriesNot`.

---

## TE-15 — A captured test launch keeps the options `JAVA_TOOL_OPTIONS` added, so a replay gets them twice — done

**Status: done.**

**Value: medium — Size: S**

**Affects: editor test runs, test discovery and MCP `run_test`, on Maven and Gradle, for anyone whose environment sets `JAVA_TOOL_OPTIONS` (or `JDK_JAVA_OPTIONS`), e.g. a corporate or profiling agent.**

Found while measuring coverage across the whole reactor (2026-11-05), with the JaCoCo agent given to every JVM through `JAVA_TOOL_OPTIONS`.

### Observed behaviour

`LaunchCapture` records the test fork's `RuntimeMXBean.getInputArguments()`, which includes the options the JVM read from `JAVA_TOOL_OPTIONS` as if they were on the command line.
The replayed JVM inherits the same environment, so it gets each of those options twice.
For a Java agent the second load is fatal: the replay exits 134 with `duplicate class definition for java.lang.$JaCoCo`, and the run reports that no test ran.
In the reactor build this failed `McpSmokeTest.runTest_reactorTestClass_replaysAndPasses`, 20 Neovim end-to-end checks (discovery, run status, streamed output) and the Gradle `LatheSyncTaskFunctionalTest.build_plainBuild_refreshesTheWorkspaceAndCapturesTestRuns`, which asserts that no `-javaagent` is captured.

### Resolution

A replay no longer inherits the variables, rather than the capture dropping their options.
A build can set them itself (a Gradle test task's `environment`, Surefire's `<environmentVariables>`; the Gradle plugin keeps the build's own `JDK_JAVA_OPTIONS` and appends `lathe-junit`), so dropping them at capture would lose build configuration.
The captured launch already holds everything the build's test JVM ran with, so a launch built from it (test runs and debug, discovery, a test-source `main`, MCP `run_test`) does not inherit `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and `_JAVA_OPTIONS`: `RunOverlay` lists them in the `ResolvedLaunch`'s `unsetEnv`, and `Launcher` removes them before applying the run configuration's `env`, so a variable set there on purpose still applies.
A main-class launch is built from the build's paths, not a captured JVM, so it keeps inheriting them.
When a dropped variable carries an option the captured launch lacks (set for the editor only, or since the last build), the run's output starts with a note naming it, which MCP `run_test` passes on as a `Note:`; the note splits the value on whitespace, so it only ever informs and never decides what the launch gets, and it names each option without its value, which may be a password the note would otherwise hand to an agent.
A launch captured with the JaCoCo agent in `JAVA_TOOL_OPTIONS`, replayed by MCP `run_test` with the same variable set, failed with exit 134 before and passes after.

### Regression targets

`LauncherTest.environment_unsetVariables_inheritedDropped_setOnesKept`, `LauncherTest.environment_nothingUnset_keepsTheInheritedVariables`, `LauncherTest.launch_jvmOptionVariableInTheRunConfiguration_reachesTheProgram`, `RunOverlayTest.applyToTestMain_capturedLaunch_unsetsTheJvmOptionVariables`, `LauncherTest.unappliedOptionNotes_optionsTheBuildRanWithout_namedOnce`, `LaunchSessionTest.constructor_noteThenProcessOutput_streamsEachLineTaggedToConsumer`, `LatheEngineRunTest.latheTestRun_blockedOrNothingRan_carriesTheReasonOrTheLastOutput`.

---

## TE-8 — Failsafe integration tests are never captured, yet the remedy says to capture them — done

**Status: done — Target: next.**
**Affects: MCP `run_test` and `workspace_summary`.**
Found by the pre-release probes (2026-10-27) on EqualsVerifier.

### Observed behaviour

`equalsverifier-test-signedjar` has only `SignedJarIT`, run by Failsafe.
`run_test` on it answers
`Test run blocked: no captured test launch for equalsverifier-test-signedjar; run
.lathe/lathe-sync.sh --tests equalsverifier-test-signedjar to capture it`,
and `workspace_summary` lists the module as `tests: not captured` with the same command.
That command succeeds (`No tests to run.`) and captures nothing, since capture rides the Surefire `test`
fork, so an agent that follows the advice loops.
The limit is stated only in the Neovim guide, not in the test-capture guide's limits or the agent guide.

### Fix direction

Tell a Failsafe test (a class matching the Failsafe includes, or a module whose only test plugin is Failsafe)
apart from a missing capture, and say it cannot be run from Lathe yet instead of naming a sync.
Add the limit to `test-capture.md` and `ai-agents.md`.
Capturing the Failsafe fork is the larger, separate step.

### Resolution

The server tells such a class apart through the module's test filter, which the build front-end writes at
sync (`.lathe/<module>/test-filter.json`: Surefire's includes and excludes for Maven): when a module has no
captured launch and the filter leaves every selected class out, a test run or debug answers that the build's
test filter leaves it out, so no capture includes it and Lathe cannot run it, with no sync named.
The server stays build-neutral; a front-end that writes no filter (Gradle, which captures every test task)
keeps the sync advice. `workspace_summary` is unchanged: it names no per-module command, so it sends no
agent round in circles. The limit is in `test-capture.md` and `ai-agents.md`.
On EqualsVerifier, `run_test` on `SignedJarIT` now answers with that reason.

### Regression targets

- `LatheTextDocumentServiceTest.runTestFuture_classTheTestFilterLeavesOut_blockedWithoutASync`.
- `TestFilterDataTest.includes_classNames_matchAnIncludeAndNoExclude_emptyIncludesTakeEverything`.

---

## TE-9 — Running a file with two top-level test classes runs only the first — done

**Status: done — Target: next.**
**Affects: MCP `run_test` (class scope); the editor's run of a file was not probed.**
Found by the pre-release probes (2026-10-27) on Dropwizard.

### Observed behaviour

`DropwizardExtensionsSupportDeepNestingTest.java` declares a second top-level test class
(`…ExtensionAtEachLevelTest`), which Surefire runs.
`run_test` with the default class scope answers `PASS — 4 passed` for the first class only, and its output
does not name the class it ran, so the skipped one goes unnoticed.
A method of the second class runs when named.

### Fix direction

Class scope runs every top-level test class in the file, and the result names the classes it ran.

### Resolution

`run_test` at class scope runs every test class at the file's outermost level in one launch
(`LatheEngine.selectTargets`); a `@Nested` class still runs inside its enclosing one, and method scope still
runs the one outermost method of its name. The answer names what it ran (`Ran:`, and `ran` in the
structured content). On the Dropwizard file it now answers `PASS — 5 passed` and names both classes.

### Regression targets

- `LatheEngineRunTest.selectTargets_nestedAndSecondTopLevelClass_runsEveryOutermostClassAndOneMethod`.
- The `multi-module` fixture's `McpSmokeTest.runTest_reactorTestClass_replaysAndPasses` checks the `Ran:`
  line.

---

## TE-1 — Capture-only dependencies leak into the recorded replay classpath

**Status: done — Target: backlog.**

Discovered by inspecting Helidon's captured `test-launch.json` files after enabling Lathe through
`.mvn/extensions.xml`.

### Observed behaviour

The extension adds `lathe-junit` at test scope to every Maven project.
That dependency also added `lathe-core` and its runtime dependency closure (Gson, ValidCheck, Error
Prone annotations) to the real test compile and Surefire/Failsafe runtime classpaths.
The capture listener removed only its own `lathe-junit` jar before writing `test-launch.json`, so the
captures omitted `lathe-junit` but retained the rest of the closure, and `ReplayTransform.forTest`
carried those entries into replay — masking missing project test dependencies, participating in
version mediation, and polluting the in-fork classloader.

### Root cause

`LaunchCapture.capturedClassPath` filters only the listener's own code-source path; it never
identified or removed the listener artifact's transitive dependency closure, which Maven had
legitimately resolved onto the fork classpath.

### Fix

Packaging-only change to `lathe-junit`, per
[lathe-capture-dependency-isolation.md](../done/lathe-capture-dependency-isolation.md) (Option A).
`lathe-junit` is repackaged with `maven-shade-plugin` as a shaded uber-jar that bundles its compile
closure (`lathe-core`, Gson, ValidCheck) under a relocated `io.github.aglibs.lathe.junit.shaded.*`
package and publishes a dependency-reduced POM. Error Prone annotations are excluded from the bundle,
`module-info.class` and each bundled dependency's `META-INF/maven`/`META-INF/proguard` metadata are
filtered out, and the `ServicesResourceTransformer` keeps the JUnit provider files consistent. Because
the capture listener already filters `lathe-junit`'s own jar by `CodeSource`, moving the closure
inside that jar means the single-jar filter now sweeps the entire closure out of both the fork
classpath and `test-launch.json`. Relocation also prevents any clash with a consumer's own copy of
those libraries. Dogfooding self-capture is disabled with a per-module Surefire `lathe.skip` flag;
no Java, schema, or server read-path change was needed.

### Regression targets

None — packaging-only change, verified manually via shaded-jar inspection (every class under
`io.github.aglibs.lathe.junit`, no un-relocated foreign packages, dependency-reduced POM leaves
consumers zero inherited compile dependencies). No automated regression test was added.

---

# Test execution (resolved)

## TE-5 — JPMS test module reflective access under replay — could not reproduce

**Status: non-goal (could not reproduce) — Target: M2.**

Reported: a JPMS module's tests failed under neotest replay with
`java.lang.reflect.InaccessibleObjectException` — JUnit, loaded in the unnamed module, could not
reflectively open the named test module's packages (`@RegisterExtension` fields, test
constructors) — while the same tests passed under `mvn`. On re-check the failure no longer
reproduces (possibly resolved by a re-sync regenerating the test-launch template — the TE-4 schema
change forces one — or environment-specific). Closed pending recurrence; reopen with a fresh repro
if it returns.

The original suspicion was a module-path-vs-classpath placement / `--add-opens` fidelity gap in the
captured replay launch versus Surefire's fork.

---

## TE-4 — Replay working directory does not match Maven's (module basedir) — done

**Status: done — Target: M2.**

### Observed behaviour

Tests and `main` runs that pass under `mvn` fail under Lathe's replay because they resolve
relative paths against a different current directory. Example, a test reading a fixture by a
project-relative source path:

```
java.lang.AssertionError: src/test/resources/fixtures/logo.png
    at com.example.app.AppResourceTest.getPutDelete(AppResourceTest.java:455)
```
(Genericised; the real class and path are from a private workspace.)

### Root cause

Maven runs the Surefire fork (and `exec`/`main`) with the JVM working directory set to the
module's `${project.basedir}`, so relative paths like `src/test/resources/…`,
`new File("target/…")`, or `Paths.get("config/…")` resolve against the module. Lathe replays a
fresh JVM whose current directory is not set the same way (it inherits the server/launcher cwd,
e.g. the reactor root), so those relative paths point elsewhere and the run fails.

### Proposed direction

Set the replay JVM's working directory to the module basedir (the same directory Surefire uses),
for both test and `main` replay, so relative-path resolution matches Maven. A run-configuration
overlay may later override it (see [run-configuration.md](../guide/run-configuration.md)), but the
default must match Maven. Confirm the basedir is captured (or derivable from the manifest) per
module.

### Resolution

Fixed in `fix(run): set the replay working directory to the module basedir (TE-4)`. A required
`workingDir` (module-relative to the workspace root) was added to `TestLaunchData` and
`MainLaunchData`; the capture side sets it to `moduleRel` (`LaunchCapture` /
`MainLaunchWriter.deriveLaunch`), and `RunOverlay.resolveCwd` resolves the replay cwd from it —
`workspaceRoot.resolve(workingDir)` — unless a run-configuration overlay supplies an explicit
`cwd`. An empty `workingDir` (the reactor-root module) resolves to the workspace root, matching
Maven's Surefire/exec fork.

### Regression targets

`RunOverlayTest` — asserts the resolved cwd equals the module basedir for the default case and
that an explicit overlay `cwd` wins.

---

## TE-2 — Named run-configuration selection (`:LatheRun {name}`)

**Status: done — Target: next.**

Implemented. The run-config file is now a `{ defaults, configs }` object: `defaults` are the
auto-applied baselines (the former `(module, kind)` overlays), and `configs` are name-keyed entries
that pin a target and are selected explicitly via `:LatheRun {name}` / `:LatheDebug {name}` (with
server-provided completion). `:LatheRunSave[!] [name]` scaffolds a config from the runnable under the
cursor into `.lathe/run.json`, and `:LatheRunOutput` reopens the run console. Design:
[lathe-named-run-configs.md](../done/lathe-named-run-configs.md).

### Regression targets

None yet — to be defined when the fix is scheduled.

### Current verification

`RunConfigReaderTest.read_namedConfig_composesOverMatchingBaseline` and
`RunConfigReaderTest.read_namedTestConfig_parsesSelectors` cover the named configuration model.
The editor selection UI is separate client behavior; these Java tests do not exercise it.

---

## TE-6 — Runnable discovery throws on a single-module project (reactor root is the module) — done

**Status: done — Target: next.**

### Observed behaviour

In a single-module Maven project (one POM, no `<modules>` — the reactor root *is* the module),
requesting runnables for any file with a `main` or `@Test` method fails the whole scan with an
internal error:

```
Internal error — ValidationException: 'moduleRel' must not be blank
  at RunTarget.<init>(RunTarget.java)
  at RunnableScanner.mainTarget(RunnableScanner.java)
```

The run/test code-lenses and neotest discovery are therefore dead for every root-is-module project.
Discovered against the public `jqno/AnnotationScript` repo (user report): `FizzBuzz#main` could not
be run even though diagnostics, folding, and the derived `main-launch.json` were all correct — the
file's heavily nested annotations were a red herring.

### Root cause

For the reactor-root module, the compiler params live directly in `.lathe/` (not `.lathe/<submodule>/`),
so `config.moduleDir()` equals `.lathe` itself and `WorkspaceSession.moduleRel()`
(`.lathe` ▷ `.lathe`) relativizes to the empty string. Empty is the server's *intended* root-module
key — `MainLaunchReader` (`.lathe/` + `""` → `.lathe/main-launch.json`) and `configsFor("")` both
resolve it, consistent with TE-4's empty `workingDir`. The sole over-strict check was `RunTarget`'s
compact constructor asserting `notBlank(moduleRel)`, which threw on the first target built and aborted
the entire scan.

### Resolution

`RunTarget` now validates `moduleRel` with `notNull` instead of `notBlank`, so `""` (the root-module
key) is accepted. No other change was needed: the run-execution path already resolves `""` to the
workspace root. Verified end-to-end against AnnotationScript — runnables list cleanly and
`FizzBuzz#main` runs to exit 0 through the Lathe run path.

### Regression targets

`RunnableScannerTest.runnables_rootModuleBlankModuleRel_returnsTargetsWithBlankModuleRel` — scans a
`main` in a root-is-module layout (`moduleRel == ""`) and asserts the MAIN / MAIN_CLASS targets come
back carrying `moduleRel == ""` instead of throwing.

---

# Debug (DB) — resolved

## DB-8 — A debugged test's JVM outlives the server — done

**Status: done.**

**Value: high — Size: S**

**Affects: the editor.**

Found by the pre-release test rounds (2026-10-29/30) on EqualsVerifier.

### Observed behaviour

A test launched by `lathe.debug.test` stays running, suspended, after the server exits: on a clean shutdown/exit, on stdin EOF and on SIGTERM.
0.1.24 behaves the same.

### Fix direction

Destroy the debuggee's process tree when the server shuts down, as test runs are.

### Resolution

Nothing ended the server's child processes when its JVM exited:
a test run or a debuggee suspended at a breakpoint kept running after the editor or agent went away.
`ChildProcesses.destroyOnExit`, registered first thing by both the language server and the MCP server,
adds a shutdown hook that kills every descendant process forcibly, without waiting.
It covers `exit`, the end of input and SIGTERM (not SIGKILL, which no hook sees),
and formatter and plain run processes as well as the debuggee.

### Regression targets

`ChildProcessesTest.destroyOnExit_parentExits_childIsKilledOnlyWithTheHook`.

---

## DB-3 — Object-scoped `evaluate` overload — done

**Status: done — Target: M2.**

Invoked only by java-debug's `JavaLogicalStructure` to render collections/maps in the Variables view
(it evaluates member expressions such as `size()` / `toArray()` with `this` = the object). Now
implemented: `LatheEvaluationProvider.evaluate(expression, object, thread)` attributes the expression
against an *accessible* type of the object (the runtime type when public, else the nearest public
supertype — since types like `java.util.ImmutableCollections$ListN` can't be named in source) via a
synthetic typed receiver (`SourceAnalysisSession.attributeReceiverExpression`), then interprets it
with that receiver bound to the object (a `JdiInterpreter` seed binding). Reuses the Stage-2
interpreter; any failure falls back to the raw field view. Collection/map logical views now populate.

### Regression targets

`dev/debug-e2e.sh` object-scoped case: at a test-scope `main`, expanding an `ArrayList` local yields
its logical elements (`0="alpha"`, …) rather than raw fields, via the `debug_probe.py --expand` mode.

### Observed behaviour

`IEvaluationProvider.evaluate(expression, ObjectReference, thread)` returns a failed future
("object-scoped evaluation is not supported yet"). Only the thread/frame-scoped overload and
`evaluateForBreakpoint` are implemented. The adapter uses the frame-scoped path for watches, hover,
console, and conditional breakpoints, so this is not user-visible today; it would matter for evaluate
requests that supply a receiver object instead of a frame.

### Expected behaviour

Evaluate an expression with a supplied `ObjectReference` as the implicit receiver/`this`, reusing the
Stage-2 interpreter against that object rather than a stack frame.

### Probe commands

Not reproducible through `debug_probe.py` today (the probe only issues frame-scoped `evaluate`);
exercised by a DAP client that sends `evaluate` with an object scope.

### Regression targets

None yet — to be defined when the fix is scheduled.

---

## DB-4 — Debug-console / REPL code completion returns nothing — done

**Status: done — Target: M2.**

### Observed behaviour (resolved)

The DAP `completions` request was fully plumbed but unanswered: java-debug advertises
`supportsCompletionsRequest = true` and Lathe registered a `LatheCompletionsProvider`, but the
provider was a stub returning an empty list, so the `nvim-dap` REPL offered nothing.

### Resolution

`LatheCompletionsProvider` now answers `codeComplete(frame, snippet, line, column)` by running
Lathe's ordinary completion engine at the frame's source line — reusing only javac/engine outputs,
with no ad-hoc Java parsing:

- Frame → source file → module worker via the shared `FrameSources` helper (extracted from
  `LatheEvaluationProvider`).
- The snippet is spliced as the initializer of a throwaway local (`var __LATHE_COMPLETE__ =` on the
  frame's line, the snippet on the next line) so it sits in an **expression** position — a bare
  statement only surfaces keywords. This is source-text construction (like `ExpressionSplice`), not
  parsing.
- Completion runs through a new `SourceAnalysisSession.completeTransient` /
  `CompilationWorker.completeTransient` that (a) never touches the open-document cache, and (b)
  attributes the **unmodified** file (FAST, uncached) as the completion's baseline analysis — which
  the semantic completer requires to resolve the frame's locals/params/members (without it only
  keywords and type-index candidates survive).
- `CompletionOutcome` items map to `Types.CompletionItem` using the engine's own javac-derived
  replacement range as the DAP `start`/`number`; the request's LSP trigger context is ignored by the
  engine (it derives context from the content), so no snippet inspection is needed.

The client is free: `nvim-dap` wires the REPL `omnifunc` to the `completions` request automatically,
and a completion plugin (`cmp-dap`, or a blink.cmp source) can surface it as-you-type.

Verified: locals (`arg`→`args`), static imports (`asser`→`assertEquals`), static members
(`System.`), members on `this`/objects/`String`, and types all complete. A frame with no debuggee
interaction — pure read-only source analysis, no JDI, no invocation lock.

**Resolved (separate gap, not DB-4):** member completion on an **array-typed** receiver (`args.` where
`args` is `String[]`) now offers `length`, `clone()`, and the inherited `Object` members — a shared
completion-engine fix (the editor path shares it), tracked as
[CQ-0053](gaps-archive.md) (resolved).

### Probe commands

```bash
python3 dev/debug_probe.py --workspace <ws> <MainFile.java> --line <N> --main <Class> \
  --complete "arg" --expect-item "args"          # local param
python3 dev/debug_probe.py --workspace <ws> <TestFile.java> --line <N> --method <m> \
  --complete "asser" --expect-item "assertEquals" # static import
```

### Regression targets

- `LatheCompletionsMappingTest` (LSP→DAP item mapping, replace-range and kind).
- `dev/debug-e2e.sh` debug-console completion cases (local, static member, static import, `this.`).

---

## DB-5 — Local inspection fails because on-save compiles strip the local-variable table — done

**Status: done — Target: M2.**

### Observed behaviour

At a breakpoint, both the Variables/scopes pane and local evaluation come up empty for a
genuine local in the suspended method — the pane silently shows no locals, and `dap> <name>`
fails with `EvaluationException: no local-variable table for this frame`. Breakpoints still
bind (the line stops), so only locals are affected. Reproduced on an ordinary `@Test` method
that a healthy `-g` build would debug fine.

### Root cause

Not the project's build flags — Lathe's own on-save compile.

- On every save, `didSave` → `WorkspaceSession.onSave` runs a **FULL** compile
  (`WorkspaceSession.java`, `submitCompile(..., CompileMode.FULL, ...)`).
- A FULL compile calls `task.generate()` (`JavacRunner.compileFull`), **writing `.class` files**
  to `config.latheClassesDir()` = `.lathe/<module>/classes` (or `test-classes`) — the **same**
  directory the Maven compiler shim mirrors into.
- `ModuleSourceCompiler.buildOptions` added `--release`, `-encoding`, `-parameters`,
  `--enable-preview`, and the project's captured `compilerArgs`, but **never `-g`**. javac's
  default without `-g` is `-g:source,lines` → `LineNumberTable` (breakpoints bind) but **no
  `LocalVariableTable`**.

So on save, the server overwrote the Maven-mirrored bytecode (which had the LVT, from
`maven-compiler-plugin`'s `<debug>true>` default) with its own `-g`-less version, and the debug
session — replaying `.lathe/` — loaded the class with no locals. Files not yet re-saved kept the
Maven mirror and debugged fine, which is why the failure looked build-specific.

### Resolution

`ModuleSourceCompiler.buildOptions` now adds `-g` for `CompileMode.FULL`, so the classes the
server writes into `.lathe/` carry a `LocalVariableTable` and the replayed bytecode always
exposes locals. FAST/OPEN only `analyze()` (no bytecode written), so they are unchanged.

The `no local-variable table` case is now rare — a class Lathe did not compile (a precompiled
dependency) or a synthetic lambda/bridge frame — so `JdiInterpreter.readLocal` surfaces a single
concise message (`local '<name>' is unavailable: this frame has no local-variable table`)
instead of the earlier build-advice wording, which the fix made misleading.

### Probe commands

Reproduce the *old* behaviour by compiling with `-g:lines,source` (line numbers, no vars) and
debugging a local; with the fix, a normal on-save workflow exposes locals in every module.

### Regression targets

None yet — to be defined when scheduled.

### Current verification

The FULL-mode option preparation in `ModuleSourceCompiler` adds `-g` when no debug option was captured.
The standalone compiler-option test named by a future reader should not be assumed to exist: this record's evidence is the implemented option branch and its historical debug validation, not a dedicated local-variable-table assertion.

---

## DB-6 — Variable inspection throws `InvalidStackFrameException` after an invocation-resume — done

**Status: done — Target: M2.**

### Observed behaviour

During a suspended debug session, requesting the Variables/scopes view fails:

```
Failed to get variables. Reason: com.sun.jdi.InvalidStackFrameException: Thread has been resumed
```

Evaluation of individual locals may still work, but expanding the scope (the Variables pane /
`variables` request) errors intermittently.

### Root cause

A JDI `StackFrame` is valid only while its thread stays suspended. Lathe's **evaluate** path is
built around this: `JdiInterpreter.frame()` (`JdiInterpreter.java:106`) never caches the frame,
re-fetching it after any invocation, because "a method/constructor call, or a cold-class
force-load … resumes the thread and permanently invalidates every prior `StackFrame`" (see also
`LatheEvaluationProvider.java:49`).

But the **variables/scopes** request is handled by Microsoft java-debug, which caches frames by
id in its own frame manager. When a Lathe evaluation invokes a method (or force-loads a cold
class), it momentarily resumes the thread and invalidates *all* frames — including the ones
java-debug cached for the Variables view. The next `variables` request then reads a stale frame
and throws `InvalidStackFrameException`. So Lathe's own guard does not extend to the DAP
variables path it hosts.

### Proposed direction

Ensure the hosted java-debug frame cache is refreshed (or invalidated) whenever a Lathe
evaluation resumes the thread — e.g. drop/rebuild java-debug's cached frames after an
invocation, or surface a retry — so an evaluate that invokes a method does not break the
subsequent Variables view. Confirm the exact trigger (invocation-based evaluate vs. any
step/resume) when scheduled.

### Resolution

Fixed in `fix(debug): reload java-debug's frame cache after an in-eval invocation (DB-6)`.
`LatheEvaluationProvider.initialize` captures the session's shared `IStackFrameManager` (the
same cache every java-debug handler mutates), and `invokeGuarded` refreshes it in a `finally`
via `reloadStackFrames(thread)` — the same primitive `StackTraceRequestHandler` uses — right
after each invocation. That is the one moment core cannot cover on its own: Lathe's
resume/suspend is hidden from java-debug because `isInEvaluation` suppresses those events, so no
`stackTrace` gets re-driven to rebuild the cache. The reload runs inside the existing per-thread
lock and is best-effort (a resumed thread yields an empty cache rather than an error), so no new
lock or shared state is introduced.

### Probe commands

Not reproducible through `debug_probe.py` (frame-scoped evaluate only). Reproduced in an
interactive DAP session: at a breakpoint, run an evaluation that invokes a method, then expand
the Variables view.

### Regression targets

`LatheEvaluationProviderTest` — asserts an invocation triggers `reloadStackFrames`, that a
missing frame manager is a no-op, and that a reload failure is swallowed rather than surfaced.

---

# Neovim client (resolved)

## NV-9 — `:LatheNew` → Test from a main-source file lists the main location first — done

**Status: done** (2026-11-19)

**Kind: bug**

**Value: low — Size: S**

**Affects: Neovim.**

Found in daily use (2026-10-30).

### Observed behaviour

From a file under `src/main/java`, `:LatheNew` → Test offers the buffer's package twice, and the main-source row comes first.
Accepting the top row is the natural move, yet a test belongs in the test root.

### Fix direction

`SourceLayout.destinations` now owns the ranking (moved from `lathe-neovim/runtime/lua/lathe/new.lua` in `6d40d341`).
Its unfiltered list ranks both scopes equally within the buffer's package and module,
so the alphabetical tie-break (`main` < `test`) still decides.
The typed-package path already prefers the kind's scope; that partial improvement does not fix the unfiltered list.
Within a rank, prefer the kind's natural scope (`test` for Test) before the module/package tie-break.
Still on main `dbbdabf1` (2026-11-19): `SourceLayout.rank` gives both scopes of the buffer's package rank 0, and the client keeps the server's order.

### Resolution

The unfiltered list orders each rank by the kind's scope before the module, package and scope tie-break, so Test from a main file leads with the test row of the buffer's package, then the rest of the module's test packages.
The scope check that ranked the other modules moved into that key.
For Class, the module's main packages now come before its test-only packages, where the package order alone used to mix them.

### Regression targets

- `SourceLayoutTest.destinations_testOnlyPackageInModule_kindsScopeLeadsEachRank`

---

## NV-11 — The Java indenter still disagrees with google-java-format on 1% of lines — non-goal

**Status: non-goal** (2026-11-17: stopped on purpose on 2026-11-12, to keep the indenter simple)

**Kind: bug**

**Value: low — Size: M**

**Affects: Neovim's live indentation (`indentexpr`, `gg=G`) of code the project formats with google-java-format.**

Found measuring the indenter against formatted code (#176).

### Observed behaviour

`dev/indent-corpus.sh` asks the indenter for each line of this repository's own sources, which google-java-format formats.
It disagrees on 536 of 53,893 non-blank lines (1.0%; 3.9% before #176), and on 591 of 57,020 (1.0%) after the 2026-11-15 fixes (`bfb12730`).
What remains is continuation shapes that would each need a rule of their own:
an argument after a multi-line argument,
the first argument of a call that roots a method chain broken onto the next lines (google-java-format indents it two continuations deep),
`&&`/`||` lines, and ternary branches.
Format-on-save rewrites them, so the cost is a line typed at the wrong column, not a wrong file.

### Fix direction

Two of these were implemented and dropped in #176:
the chain-root rule had to look ahead through the tree for a chain still being typed,
and the multi-line-argument rule regressed hand-formatted code.
Take one up only with a narrow, tree-based rule, measured with `dev/indent-corpus.sh` before and after.

### Regression targets

`lathe-neovim/test/indent_tree_spec.lua` and `indent_spec.lua`; the measure is `dev/indent-corpus.sh`.

---

## NV-10 — Signature help highlights no parameter when the active overload is not the first — non-goal

**Status: non-goal** (2026-11-17: Lathe answers correctly; the fix is blink.cmp's PR #2497, and the workaround is documented)

**Kind: bug**

**Value: medium — Size: S**

**Affects: Neovim with blink.cmp's signature window, the setup in `examples/nvim/init.lua`.**

Found in daily use (2026-10-30).

### Observed behaviour

Typing the second argument of `list.add(0, |)`, the signature window lists both `ArrayList.add` overloads
but highlights nothing.
The server answers correctly: `[signatureHelp] sig=void add(int index, E element) param=1`,
the second signature active and its second parameter (label offsets 20–29) active.
A method with one overload, or an active overload listed first, highlights as expected.

### Root cause

blink.cmp (v1.10.2, `lua/blink/cmp/signature/window.lua`) writes every signature's label into its window,
one per line, but takes the highlight from Neovim's `convert_signature_help_to_markdown_lines`,
which renders only the active signature, inside a code fence.
Its line (1, under the fence) minus one is 0, so blink paints the active parameter's columns on the first label:
`boolean add(E e)` is 16 characters, so columns 20–29 show nothing.
Introduced by blink's `b6f04e8` ("Update active parameter to use line and col range").
Neovim's own `vim.lsp.buf.signature_help` (`<C-s>` in normal mode in the example config) renders only the active
signature and highlights correctly.

### Fix direction

The fix is blink.cmp's: [PR #2497](https://github.com/saghen/blink.cmp/pull/2497) (open since 2026-04-19, for this
Java overload case) takes the line from the active signature's index in its own buffer. Not in v1.10.2, the latest
release on 2026-11-16; once a release has it, check the example config and close this gap.
The Neovim guide's troubleshooting names the workaround: `<C-s>`, Neovim's own signature help, highlights correctly.
Lathe listing the active signature first (`activeSignature` 0) was not taken: every client's overload order would
change as the arguments change, to work around one plugin.

Related, on the server: when the call does not attribute yet, `SignatureHelpResolver` now makes active the first
overload whose arity fits the arguments typed, not the first declared
([EG-088](gaps-archive.md#eg-088--signature-help-is-empty-inside-new-p-and-super-when-no-constructor-takes-zero-arguments--done)).

### Regression targets

None yet.

---

## NV-13 — A server killed by a signal ends without a Lathe notification — done

**Status: done** (2026-11-16)

**Kind: bug**

**Value: medium — Size: S**

**Affects: the Neovim client when the server process is killed, most often by the out-of-memory killer on a large project.**

Found by the editor exploratory round (2026-11-16).

### Observed behaviour

Killed with SIGKILL, the server exits with code 0 and signal 9.
The client passed only the code to `on_server_exit`, which took code 0 for a clean shutdown and stayed silent.
The user saw only Neovim's own `Client lathe quit with exit code 0 and signal 9`, with no word of how to get the server back; saving the file does not restart it, reopening it does.

### Resolution

The client's `on_exit` passes the signal on, and a signal counts as an unexpected exit, as a non-zero code already did.
SIGTERM stays silent: it is how Neovim itself stops a client (`:lsp restart`, a shutdown that times out), and Neovim's own check excludes it too.
The JVM handles SIGTERM with its shutdown hooks and exits with code 143 and no signal, so that code is silent as well; code 137, a SIGKILL reported by a launcher that does not exec the JVM, counts as a kill.
Neovim runs `on_exit` in a fast event, where `vim.notify` fails (E5560), so the notification is scheduled.
A kill's notification names the signal and says to run `:LatheStart`, which restarts the server without touching unsaved changes (`:edit` refuses a modified buffer).
An exit with an error code keeps its message without that hint: such a server failed on its own and would fail again.
Neovim's own `Client lathe quit…` warning still appears beside Lathe's.

### Regression targets

`lathestart_spec.lua`: `on_server_exit: killed by a signal notifies`, `on_server_exit: kill names the signal and :LatheStart`, `on_server_exit: SIGTERM from Neovim is silent`, `on_server_exit: code with a signal names the signal`, `on_server_exit: crash names the code, with no restart hint`, `on_server_exit: clean exit with no signal is silent`, `on_server_exit: JVM exit after SIGTERM is silent`, `on_server_exit: kill reported as a code names the signal and :LatheStart`, `on_server_exit: no notification in a fast event`.

---

## NV-12 — A refused clone of the neotest end-to-end check's plugins failed the CI build — done

**Status: done.**

**Affects: CI's Linux and macOS build rows (lathe-neovim's `neotest-e2e` step).**

Found when a macOS row failed on #176 (2026-11-11) with every Neovim spec passing.
`test/e2e/run-e2e.sh` clones neotest, nvim-nio and plenary from GitHub into `target/e2e-plugins` on every run, at the commits `plugins.txt` pins.
One clone was refused (`fatal: could not read Username for 'https://github.com'`), and under GitHub Actions a failed clone fails the build.

### Resolution

`run-e2e.sh` retries a refused clone twice, after a pause.
Caching the plugins across runs was left out on purpose: a refused clone is rare, and the retry is what keeps it from failing the build.

### Regression targets

None automated: CI's `neotest-e2e` step, which clones the plugins on every run.

---

## NV-8 — A neotest run started before a file's discovery finishes reports "no tests found" — done

**Status: done — Target: next.**

Resolution: with nothing discovered under the file, the neotest adapter's `build_spec` runs the class
the file's path names, in the module `lathe.resolveContext` gives (release track slice 7).
Regression: `neotest_spec.lua` and the neotest e2e's R7 check.

Found while testing slice 3 of the Neovim release track ([run/test design](../planned/lathe-vscode-run-test.md) §11)
through neotest's own client: a file run issued right after opening a test file, before the adapter's
discovery has returned its tests (1–4 s on the `multi-module` fixture, while the server compiles the
opened file), comes back `skipped` with "no tests found in this file". neotest still holds the file
position from before discovery, without its tests, and the adapter's `build_spec` skips an empty file.
A user who runs a file as soon as it opens, before the gutter signs appear, sees nothing run.
Already the case before slice 3. To be handled with slice 7, which reworks discovery: a file run
should wait for the file's discovery, or run the file's classes from the server, instead of skipping.

### Regression targets

- A file run on a file with no discovered tests runs its class (`neotest_spec.lua`; the neotest e2e's
  R7 against the live server).

## NV-7 — Collapsed import fold pops back open on every save

**Status: done — Target: M2.**

Resolution: the Neovim client (`lua/lathe/fold.lua`) snapshots the imports fold's closed state before
a format-on-save write and re-closes it once ufo settles. Regression: `fold_spec.lua`.

### Observed behaviour

Open a `.java` file with the recommended folding config: the import block auto-collapses.
Edit anywhere in the file and `:w` (save), and the previously-collapsed imports fold springs back
open. It re-collapses only on the next full buffer display (reopen / `BufReadPost`), so every save
during an editing session leaves the imports expanded and the user re-folds them by hand.

### Root cause

The reopen is **not** a server-side change — the fold geometry is byte-stable across a save. Probed
against the working-tree server (`dev/explore.py`, `folds` before and after `save` on a real file):
the `imports` range is identical both times (`3:1-14:27` → `3:1-14:27`).

The trigger is the recommended `nvim-ufo` config that Lathe ships in
[`docs/done/lathe-folding-ranges.md`](../done/lathe-folding-ranges.md):

```lua
close_fold_kinds_for_ft = { java = { "imports" } }
```

Per ufo's own docs this option closes the matching-kind folds **only "after the buffer is displayed
(opened for the first time)"** — it is a first-display action, not re-applied on later fold updates.
ufo re-requests `textDocument/foldingRange` and recomputes folds whenever the buffer text changes,
converting `foldmethod` to `manual`; the recomputed folds default to *open*, and because the
auto-close is first-display-only the imports never re-close. The save-time churn is amplified by the
server firing **two** `workspace/semanticTokens/refresh` requests per save (one from the FULL-compile
`DiagnosticPublisher.publish`, one from the scheduled AST-refresh `refreshTokensIfCurrent`) versus one
on open — confirmed by instrumenting the probe client.

Lathe ships no fold logic of its own (`lua/lathe.lua`'s save autocmds only refresh run-signs and
resources), so the fold behaviour is entirely the server's stable `foldingRange` plus the
Lathe-recommended ufo config — which is why this is an NV (recommended-configuration) gap.

### Proposed direction — what to present

Re-apply the imports auto-close after a post-save fold recompute settles, in the recommended config
(and optionally the shipped plugin), so the fold state a user last chose survives a save. Candidate
mechanisms, to be finalised against a live nvim + ufo repro:

- A `BufWritePost` autocmd (java buffers with a Lathe client attached, mirroring the existing
  run-signs autocmd in `lua/lathe.lua`) that defers briefly for ufo's fold update, then re-closes the
  imports fold — either via a ufo close-by-kind call if one is public, or by locating the `imports`
  range from the server's `foldingRange` (already stable and correct) and closing that fold.
- Alternatively, only re-close when the imports region itself was untouched, so a user who manually
  opened the imports is not overridden.

No server change is required — the fold geometry is already correct and stable. Reducing the
save-time refresh from two to one is a separate, minor server cleanup that does not fix the refold on
its own (ufo recomputes on any text change regardless).

## NV-6 — Type hierarchy showed only one level; no full-tree view — done

**Status: done — Target: next.**

Neovim's built-in `vim.lsp.buf.typehierarchy` renders one level in the location list and does not
recurse, and Lathe shipped no type-hierarchy UI of its own, so users only ever saw direct
sub/supertypes — unlike the IntelliJ Ctrl-H / JDT LS full-hierarchy view.

Resolved with a "show me everything" surface: a custom `lathe.typeHierarchy` `executeCommand` that
returns the type-under-cursor's full transitive supertypes (up to `Object`) and subtypes in one call
(from the in-memory subtype graph — `WorkspaceTypeIndex.transitiveSubtypes` plus a new
`transitiveSupertypes` walk), and a thin Neovim client (`:LatheTypeHierarchy`, mapped `grh`) that shows
them in one flat, fuzzy-searchable picker tagged `▲` supertype / `●` self / `▼` subtype — Telescope when
installed, the built-in `lathe.pick` fuzzy picker otherwise. The standard lazy
`prepareTypeHierarchy` / `subtypes` / `supertypes` endpoints are unchanged (built-in one-level use).
The result record lives in `server.analysis`, opened to Gson in `module-info` (each DTO-producing layer
opens its package).

Design: [Type Hierarchy Explorer](../done/lathe-type-hierarchy.md). Regression:
`WorkspaceTypeIndexTest.graph_transitiveSupertypes_*`, `TypeHierarchyTest.explore_*`,
`LatheLanguageServerTest.createCapabilities_includesExecuteCommandProvider` (advertises
`lathe.typeHierarchy`), and the Neovim `typehierarchy_spec.lua`.

---

## NV-1 — `:LatheStart` unavailable before a Java file is open — done

**Status: done — Target: M2.**

Opening Neovim in a `.lathe` workspace without opening a `.java` file, `:LatheStart` did not exist,
so the server could not be brought up for workspace navigation (e.g. `workspace/symbol`) from a
dashboard or empty buffer — the very case the command was added for. The recommended lazy.nvim
install loaded the plugin only on `ft = "java"`, so `config` → `require('lathe').setup()` (which
registers the command) never ran without a Java buffer.

Fixed in the install guide (`docs/guide/editors/neovim.md`): the recommended lazy spec now also
lists `cmd = "LatheStart"`, so lazy.nvim loads the plugin (running `setup()`, which registers the
real command) when `:LatheStart` is first invoked. No plugin code change — `M.start` is already
covered by `lathestart_spec`; the lazy-loading trigger is user config, not plugin behaviour.

### Current verification

The current client regression is `lathe-neovim/test/lathestart_spec.lua`.
It passed separately during this audit; the Java verification run does not execute Lua tests.

---

## NV-2 — A neotest test run gives no completion notification, only a "Running" start toast — done

**Status: done — Target: M2.**

### Observed behaviour

Starting a neotest run shows a single `vim.notify` toast — `Running <label>`
(`lua/lathe/neotest.lua`, `run_spec`) — and then nothing when the run finishes.
The user gets no at-a-glance summary of how the run went; they must open the summary panel or the
docked output to learn the outcome.

A `main` run, by contrast, notifies on **both** ends: `Running <class>` at launch and
`<class> exited <code>` on completion (`lua/lathe/run.lua`, `on_finished`), the latter at `WARN`
level on a non-zero exit. The server also logs a one-line INFO result per run
(mode, target, elapsed ms, counts) that never surfaces in the editor.

### Root cause

The neotest path emits only the start toast in `run_spec`; `results()` reconciles per-test statuses
into the summary tree and gutters but does not raise a run-level completion notification, and neither
does the `lathe/testEvent` stream handler. There is no completion-summary surface equivalent to
`run.lua`'s `on_finished`.

### Proposed direction — what to present

A single concise completion toast per run, mirroring the server's `[operation] target detail Xms
outcome` log convention and the summary already computed in `results()`:

- **Scope** — the run label (method / class / package) already passed to `run_spec`.
- **Counts** — passed / failed / skipped, plus the total, derived from the same
  `outcome.testResults` and fan-out `results()` already builds.
- **Duration** — elapsed wall-clock ms for the replay (the server can return it on the run outcome;
  the client otherwise times from the start toast).
- **Level** — `INFO` when nothing failed, `WARN` when any test failed or the run was blocked/errored,
  so a failure is visible without reading the text.

Example: `tests io.example.portal — 12 passed, 1 failed, 2 skipped (1.8s)` at `WARN`.
Keep it to one line (no per-test spam — the summary panel already itemises), fire it once from
`results()` (or the run-future completion), and suppress it for a still-streaming partial pass.
Reuse `run.lua`'s `notify` helper shape (`{ title = "Lathe" }`) for consistency.

### Resolution

Implemented client-side in `lua/lathe/neotest.lua`. `results()` fires one completion toast per run via
the pure `M._run_summary(ctx, real, elapsed_ms)`: the run label, passed/failed/skipped counts from the
method-level `real` map, and client-timed elapsed seconds (`run_spec` stamps a `started` timestamp on
the run context), at `INFO` when the run launched cleanly with no failures and `WARN` otherwise (a
failure, a blocked launch, an error, or a no-tests skip). Format:
`AppServerTest — 12 passed, 1 failed, 2 skipped (1.8s)`. Because it fires from `results()`, it covers a
debug run too (which now flows through `results()` — see
[lathe-debug-neotest-unification.md](../done/lathe-debug-neotest-unification.md)). No server change;
the duration is client-timed.

### Regression targets

`neotest_spec.lua` — `_run_summary`: an all-pass run → INFO with counts + `(T.Ts)`; a mixed run → WARN
with counts; blocked / errored / no-tests-skip → WARN; an untimed run omits the seconds suffix.

---

## NV-3 — Debugging a test does not update neotest gutters or the summary panel — done

**Status: done — Target: M2.** — design:
[lathe-debug-neotest-unification.md](../done/lathe-debug-neotest-unification.md) (Shape 1).

### Observed behaviour

Running a test via neotest paints the gutter signs and summary tree with pass/fail status.
Debugging the same test via `:LatheDebug` leaves both **unchanged** — the debug session runs, but
neotest never learns the result, so gutters and the summary panel stay at their previous state (or
blank).

### Root cause

`:LatheDebug` (`lua/lathe/dap.lua`, `M.debug`) resolves the runnable under the cursor and calls
`dap.run(M._test_config_for(test))` directly, launching the `lathe` nvim-dap adapter
(`lathe.debug.test`). This path is entirely parallel to neotest: it never goes through the adapter's
`build_spec`/`results()`, and neotest is not told a run started or finished, so it has no result to
apply to the gutter/summary consumers.

### Proposed direction

Route test-debugging through neotest so the same `results()` reconciliation updates the UI, rather
than bypassing it. Two candidate shapes to weigh when scheduled:

1. A neotest **`dap` strategy** for the adapter: `neotest.run.run({ strategy = "dap" })` builds the
   spec, launches the Lathe debug adapter (reusing `lathe.debug.test`'s DAP-port handshake), and
   reports the captured per-test results back to neotest on session end — the neotest-idiomatic path.
2. Failing that, have the debug session's terminal outcome feed the captured `testResults` into
   neotest's result state for the debugged position(s), so gutters/summary update even though the run
   was DAP-driven.

Ties into NV-4 (both stem from the debug path not sharing the run/neotest output+results surface).

### Resolution

Shape 1 (candidate 1 above). Test-debug now runs through neotest's `dap` strategy:

- `lua/lathe/dap.lua` `M.debug` routes a test under the cursor to
  `neotest.run.run({ strategy = "dap" })` (the neotest summary `d`/`D` mappings already issue that
  call), falling back to a direct `dap.run` only when neotest is absent; a `main` still uses
  `dap.run` directly. The route choice is the pure `M._debug_route`.
- `lua/lathe/neotest.lua` `run_spec` branches on `strategy == "dap"`: it returns a `spec.strategy`
  the dap strategy launches through the `lathe` adapter (`lathe.debug.test`) instead of firing
  `lathe.run.test`, so the streamed `lathe/testEvent`s paint the gutters live and `results()`
  reconciles the summary at session end.
- The server publishes the final `LaunchOutcome` at debug session end via a new `lathe/testFinished`
  notification (`WorkspaceSession.attachDebugHost` / `LatheLanguageClient.testFinished`), which
  resolves the run's future so `results()` completes; the client wait is bounded with an exit-code
  fallback so a lost notification can never leave a position stuck "running".

### Regression targets

`dap_spec.lua` — `_debug_route` routes a test to the neotest dap strategy and falls back to
`dap.run` when neotest is absent, and a `main` uses `dap.run`.
`neotest_spec.lua` — `build_spec` with `strategy == "dap"` returns a `spec.strategy` carrying the run
token and fires no `lathe.run.test` replay.
`RunCoordinatorTest.finishedOutcome_completedOutcome_returnedAsIs` /
`finishedOutcome_nullOutcome_returnsBlockedNamingTheFailure` — the session-end outcome is published
(the real outcome, or a blocked one when none was produced).

---

## NV-4 — Debugging a test flashes the output window and never shows pass/fail — done

**Status: done — Target: M2.** — design:
[lathe-debug-neotest-unification.md](../done/lathe-debug-neotest-unification.md) (Shape 1, NV-4
solved for free by going through build_spec/results()).

### Observed behaviour

When running a test, the docked output console opens and **stays** open, streaming the replay
transcript, and the run resolves to a visible pass/fail. When **debugging** a test, the output window
"opens and closes quickly," and there is no indication whether the test passed or failed — the user
wants the debug path to behave like the run path here.

### Root cause

The run and neotest paths stream into the shared docked console (`lua/lathe/output.lua`) and surface an
outcome (`run.lua on_finished`, or neotest's `results()`). Note: only the `main` path (`run.lua`) calls
`output.ensure_open()`; the neotest path calls `output.reset()` only, so a test run's console appears
"kept open" merely because the docked buffer/window persists once the user has opened it (`<leader>to`).
The debug path (`lua/lathe/dap.lua`) does neither: it hands off to nvim-dap (its own REPL/console) and
only calls `stackdecorate.decorate_live_output()` on `event_terminated`/`event_exited`. It never streams
into or keeps the shared console, and there is no results()/exit-code surface, so pass/fail is never
shown.

### Proposed direction

Give the debug path the same output+outcome surface as a run: open and keep the shared docked
console for the debug session, stream the debuggee's transcript into it, and on session end surface
the outcome (pass/fail from the captured test results or exit code) the same way `on_finished` /
`results()` do. Best delivered with NV-3 (a neotest `dap` strategy naturally reuses the run path's
output and results surfaces).

### Resolution

Resolved with NV-3 by routing debug through `build_spec`/`results()`. The `strategy == "dap"` path in
`lua/lathe/neotest.lua` `build_spec` calls `output.ensure_open()` (debug-only, since the neotest run
path deliberately does not auto-open) so the shared docked console opens and stays; because the debug
launch already streams `lathe/testOutput` under the run token, the transcript fills it live, and
`results()` surfaces the pass/fail outcome the same way a run does.

### Regression targets

`neotest_spec.lua` — `build_spec` with `strategy == "dap"` resets/opens the console and returns the
debug spec; `_synthesized_outcome` maps a dap-strategy exit code into a launched outcome so
`results()` reports pass/fail even if `lathe/testFinished` is lost. The docked console staying open
for a live debug session is verified manually (headless windowing is not asserted).

---

## NV-5 — No way to re-run only the failed tests from the last neotest run — done

**Status: done — Target: M2.**

**Design:** [Re-run Failed Tests](../planned/lathe-rerun-failed-tests.md). M2 scope is Option 1 —
re-run the *first* failing test, a single-position run with no `results()` change. Re-running *all*
failures in one JVM (Option 2) is a deferred follow-up on the unified `results()`.

### Observed behaviour

After a neotest run leaves some tests red, there is no command or keymap to re-run **just** those
failed tests. The user must re-run the whole class/package (paying for the passing tests again) or
navigate to each failure and run it individually.

### Root cause

The shipped neotest keymaps (`plugins/lathe.lua`: `<leader>tt` nearest, `<leader>tf` file,
`<leader>ts` summary, `<leader>tS` stop) expose run-nearest/file/stop but no "run failed" surface,
and neotest core has no built-in status-filtered run — the failed set has to be gathered from
neotest's result state and re-run explicitly.

### Proposed direction

Add a client helper (`require("lathe.neotest").run_first_failed()`, suggested `<leader>tF` — `tl` is
reserved for neotest's native `run_last`) that re-runs a failing test from the last run. The design
([Re-run Failed Tests](../planned/lathe-rerun-failed-tests.md)) splits this: M2 re-runs the *first*
failing test as a single-position run (no `results()` change); re-running *all* failures in one JVM is
a follow-up that reuses the previous run's launch with the selection narrowed to the failures, plus a
`run_set`/shadow retain on the unified `results()`.

### Resolution

Shipped as `require("lathe.neotest").run_first_failed()` (suggested `<leader>tF`), client-side in
`lua/lathe/neotest.lua`. `results()` records an ordered, self-shrinking set of individually-runnable
failures — a discovered position (`tree:get_key` finds it) that failed joins the list; one that now
passes, is skipped, or is undiscovered leaves it — keyed on the deduped worst-wins per-test status,
with execution order giving a stable "first". The helper re-runs the head via `neotest.run.run`, so
invoking it repeatedly walks the failures as each goes green (the red-green loop). The cheatsheet also
gained the previously-undocumented `run_last` / `<leader>tl` row.

**Future note (if more requests):** only the *first-failing* re-run shipped. A batch "re-run *all*
failures in one JVM" was deliberately deferred — it needs re-firing the previous run's broad position
with the selection narrowed to the failures, plus a `run_set`/shadow retain in `results()` to avoid
repainting the not-rerun tests (the tradeoff table and Option 2 are in the design doc). Class-level
`@BeforeAll`/container failures also can't be targeted individually until the runner captures container
results. Revive Option 2 if users ask for a whole-red-set re-run.

### Regression targets

`neotest_spec.lua` — failures captured in execution order (passes excluded); `run_first_failed` runs
the head, advances to the next after a passing re-run, ignores an undiscovered failure, and runs
nothing (WARN) when the list is empty.

---

# MCP / Agent Facade (resolved)

## MC-101 — `find_references` lists every override's declaration as a use — done

**Status: done** (2026-11-19)

**Kind: bug**

**Affects: MCP `find_references`.**

Found by the exploratory MCP round on Dropwizard (2026-11-19, `8c099a5`).

### Observed behaviour

`find_references` on the abstract `Application.run(T, Environment)` answers "55 references": 4 calls and 1 Javadoc `{@link}` mention, the 49 subclass declarations that override it, and its own declaration.
For the same method `analyze_change` counts 5 references (1 production, 4 test) and lists the 49 overrides separately, and `call_hierarchy` lists 4 callers.
The tool's description promises "every real use", and the answer ends "All javac-resolved references to this exact declaration", so an agent asking who calls the method gets eleven times the answer.
A method's own declaration is listed for every symbol (`Duration.toMilliseconds`, the field `Duration.count`).

### Fix direction

List uses only, as `analyze_change` counts them; leave the declaration and the overriding declarations out, or list them apart, as `analyze_change`'s `overridden by:` does.
Uses made through an override (FR-006) stay.

### Resolution

`find_references` lists uses only, as `analyze_change` counts them: neither the symbol's own declaration nor an override's is listed, so `Application.run` answers its 5 uses.
The search still finds the declarations, as a position naming a symbol is its declaration or a use, so an unused symbol answers "No uses: the symbol is declared, and nothing in the project uses it.", and a position the search finds nothing for (a file no module compiles) "No symbol at that position.", which an agent cannot take for dead code (`LatheReferences.resolved`).
The tool's description says the declarations are left out and that `find_implementations` and `analyze_change` list the overrides.
The editor's references are unchanged: they follow the client's `includeDeclaration`.

### Regression targets

- `LatheEngineNavigationTest.references_overriddenMethod_listsTheCallNotTheDeclarations`
- `FieldAndEnumConstantSearchTest.references_fieldUses_taggedReadOrWrite_filteredByAccess`

---

## MC-102 — A qualified name as `symbol` is refused on the line that reads it — done

**Status: done** (2026-11-19)

**Kind: bug**

**Affects: MCP position tools (`get_definition` seen; they share the `symbol` lookup).**

Found by the exploratory MCP round on Helidon (2026-11-19, `8c099a5`).

### Observed behaviour

`get_definition` with `symbol` `java.net.InetSocketAddress` on `import java.net.InetSocketAddress;` answers "Line 19 has no symbol named 'java.net.InetSocketAddress'. Line 19 reads: import java.net.InetSocketAddress;".
On `requires io.helidon.builder.api;` the module name is refused the same way.
The simple name, any one segment (`api`, `builder`), or a column resolves at once, to `InetSocketAddress.java` and to the `io.helidon.builder.api` module.
The answer contradicts itself, and the qualified name is what an agent copies from an import or a `requires`.

### Fix direction

Accept a dotted `symbol` that the line contains, and resolve it at its last segment.

### Resolution

A dotted `symbol` stands at its last segment that is a name, where the line spells the whole name as one word (`LatheEngine.nameColumns`): `java.util.List` and `util.List` on `import java.util.List;`, `java.util.*` (at `util`), `String.class` (at `String`), and a module name on its `requires`, resolve; a longer qualifier, a cut-off segment, or a name ending in a dot is refused as before.
The text only finds the column, as for a simple name; javac resolves it.

### Regression targets

- `LatheMcpToolsTest.describeSymbol_qualifiedName_resolvesAtItsLastSegment`
- `LatheEngineNavigationTest.nameColumns_qualifiedName_columnOfItsLastNamedSegment`


---

## MC-103 — A generated file at the build's own path is "not checked", without naming Lathe's copy — done

**Status: done** (2026-11-19)

**Kind: bug**

**Affects: MCP `get_diagnostics`, `find_references` (and the other file tools) on annotation-processor output under `target/`.**

Found by the exploratory MCP round on Helidon (2026-11-19, `8c099a5`).

### Observed behaviour

On `webserver/webserver/target/generated-sources/annotations/io/helidon/webserver/WebServerConfig.java`, the file the build generated, `get_diagnostics` answers "WebServerConfig.java was not checked", and `find_references` "No references found", both noting that it "is in no source root of module webserver/webserver, so a sync does not add it".
Lathe analyses the same generated file as `.lathe/webserver/webserver/generated-sources/io/helidon/webserver/WebServerConfig.java`, the path `search_symbols` and `get_definition` return; the answer does not say so.
`outline` on the `target/` path works.

### Fix direction

Map a file under a module's generated-sources output to Lathe's mirror of it, or name the mirror path in the "not checked" note.

### Resolution

The note names Lathe's copy: "`<path>` is processor output the build writes; Lathe checks its copy `.lathe/<module>/generated-sources/<path>` — pass that path" (`ChangeVerifier.notCheckedReason`, `ModuleSourceConfig.mirrorOf`).
A test file names the test tree's copy; a file with no copy yet says a sync makes one, where the plain note said a sync does not add it.
The path is not mapped to the copy where file tools take it, which would move the file's read onto the event loop; the agent passes the named path instead.

### Regression targets

- `LatheEngineNotesTest.notes_buildsProcessorOutput_namesLathesCopy`

---

## MC-95 — `analyze_change` prints an `override family` line for symbols that cannot be overridden — done

**Status: done** (2026-11-19)

**Kind: bug**

**Affects: `analyze_change` on any symbol that is not a method: a field, local variable, parameter or type.**

Found by the MCP exploratory round (2026-11-16).

### Observed behaviour

On a lambda parameter, `analyze_change` ends with `override family: none`; on a method it prints `overrides:` and `overridden by:`.
`McpText.analyzeResult` prints `override family:` for every symbol that is not a method, by design rather than as a leftover.
The line is meaningless for a field or variable, and an agent parsing the answer sees two formats.

### Fix direction

Leave the override lines out for a field or variable, which nothing can override, and decide what a type's line should say.

### Resolution

`LatheChangeImpact` says what kind of family it holds (`Family`: `METHOD`, `TYPE`, `NONE`), read in the same worker request that finds a method's overrides, so `analyze_change` prints one line format per kind.
A method keeps `overrides:`, `overridden by:` and `redeclared by:`; a type lists its subtypes as `subtypes:`; a field, variable or parameter, and no symbol, get no family line.

### Regression targets

- `McpTextTest.analyzeResult_impactAndNoSymbol_everyFieldOnItsLine_noneWhereEmpty`
- `LatheEngineVerifyTest.analyzeChange_typeAndField_typeListsSubtypesFieldHasNoFamily`

---

## MC-99 — `run_test` with a `method` but no `scope` runs the whole class — done

**Status: done** (2026-11-19)

**Kind: bug**

**Affects: MCP `run_test`.**

Found by the exploratory round on Spring Petclinic (2026-11-17).

### Observed behaviour

`run_test` with `file` `OwnerTests.java` and `method` `addPetAddsPersistedPet`, without `scope`, answers "PASS — 2 passed": it ran the class.
The schema says `method` is required when `scope` is `method`, but an agent that names a method means that method, and nothing says it was ignored.

### Fix direction

A `method` with no `scope` means `scope=method`; a `method` with another scope is refused, naming the conflict.

### Resolution

A `method` with no `scope` now runs that method: `run_test` reads it as `scope=method`.
A `method` with `scope` `class` or `package` is refused, `run_test 'method' runs one method: leave out 'scope' or set it to method, not class`, instead of being dropped.
A blank `method`, as a client may send for an unset field, counts as none.
The schema says so on both arguments.

### Regression targets

- `LatheMcpToolsTest.runTest_methodWithAnotherScope_returnsErrorNamingTheConflict`
- `LatheMcpToolsTest.testScope_noScope_methodWhenOneIsNamedElseClass`
- `McpSmokeTest.runTest_methodWithoutScope_runsOnlyThatMethod` (invoker `multi-module`)

---

## MC-94 — A `file://` URI as a tool's `file` is read as a relative path — done

**Status: done** (2026-11-18)

**Kind: bug**

**Affects: every file tool given a `file://` URI instead of a path.**

Found by the MCP exploratory round (2026-11-16).

### Observed behaviour

`outline` with `file="file:///…/Edge.java"` answers `/<workspace>/file:/…/Edge.java does not exist`.
Agents that also drive an LSP carry URIs and may pass one.

### Fix direction

Accept a `file://` URI by converting it to a path (`LatheUri.toPath`) before resolving against the workspace, or refuse it saying a path is expected.
`LatheUri.canonical` only normalizes a URI, and the file tools need a path.

### Resolution

Every file argument (`file`, and `verify_change`'s `files`) takes a `file:` URI as the path it names (`LatheUri.toPath`), and a path as before.
A URI no local path stands for (one naming a host) is taken as given, and so answered as a file that does not exist.

### Regression targets

- `LatheMcpToolsTest.outline_fileUri_outlinesTheFileItNames_hostUriNotFound`

---

## MC-92 — `run_test` runs a test whose dependency no longer compiles — done

**Status: done** (2026-11-18)

**Kind: bug**

**Affects: `run_test` after a class the test uses is deleted or broken, while the test file itself is unchanged.**

Found by the MCP exploratory round (2026-11-16).

### Observed behaviour

After `Edge.java` is deleted, `verify_change` reports `EdgeTest.java:10:25: Error: cannot find symbol class Edge`.
`run_test` on `EdgeTest` still runs it on the old classes and reports `java.lang.NoClassDefFoundError: com/example/core/Edge` for each test.
When the test's own code does not compile, `run_test` refuses: `EdgeTest.java was not run: code it runs does not compile: …`.
A dependency whose own compile fails is already caught: it is a failed compile of the test's module or one upstream, and the run is refused (`ChangeVerifier.failingInModule`).
What gets through, verified against the code (2026-11-17): a deleted dependency, which only removes class outputs (`WritePass.reconcileDeletedSourcesIn`), and a dependency change that still compiles but breaks the unchanged test, as nothing recompiles an unchanged dependent.
Not checked: whether the editor's runs (`:LatheRun`, neotest) behave the same.

### Fix direction

Refuse the run with the same "does not compile" answer when the test file would no longer compile against the current classes, not only when its own text changed.

### Resolution

`run_test` already compiled the test file as it is on disk before running it, and now refuses when that compile has an error, with the same "code it runs does not compile" answer, naming the error: a class the test uses may be gone or changed while the test is not, which no build compile failed.
It covers the test file itself, by its earliest error, and skips Lathe's own package-directory error (MC-90), which the build does not have.
The other test classes a package run runs, a helper the test calls that no longer compiles, and the editor's runs (`:LatheRun`, neotest), which go through `RunCoordinator`, are [TE-26](gaps.md#te-26--a-run-that-only-some-of-its-code-fails-to-compile-still-runs-on-the-old-classes).
Checked on javaparser: with `ExtractingVisitors.java` deleted, main ran `ExpectedTokensTest` and failed with `NoClassDefFoundError`; the fix answers `ExpectedTokensTest.java was not run: code it runs does not compile: …ExpectedTokensTest.java:30: cannot find symbol`.

### Regression targets

- `LatheEngineRunTest.runTest_classItUsesDeleted_notRunNamingItsError`
- `LatheEngineRunTest.runTest_classItUsesChangedUnderIt_notRunNamingItsError`
- `LatheEngineRunTest.runTest_packageNotItsDirectory_notRefused`

---

## MC-97 — A deferred `verify_change` tells the agent to run the sync that is already running — done

**Status: done** (2026-11-18)

**Kind: bug**

**Affects: `verify_change` while the project's own `.lathe/lathe-sync.sh` (or any build Lathe sees writing `.lathe/`) runs.**

Found by the MCP exploratory round on resilience4j (2026-11-17).

### Observed behaviour

During a `latheSync`, every `verify_change` answers at once ``Verification deferred: a build is in progress. Run `.lathe/lathe-sync.sh`, then re-check.``; the first call after the build ends checks normally.
Not stalling is right, but the advice is to start a second build while the first runs; an agent following it doubles the wait.
`LatheEngine.verifyChange` sets the sync command for every deferral but another server writing, a build in progress included, and `McpText` prints it.
The `LatheVerifyChange` javadoc also tells a caller to run `suggestedSync` for a running build.

### Fix direction

For a build in progress, say to wait for it to finish and re-check; name the sync only when what is missing is one, and correct the javadoc.

### Resolution

`LatheEngine.verifyChange` names the sync only for pending build files, the one deferral a sync resolves; a running build and another server updating `.lathe/` end on their own.
The answer then says ``Verification deferred: a build is in progress. Re-check once it ends.``, and the `LatheVerifyChange` javadoc says the same.

### Regression targets

- `LatheEngineVerifyTest.verifyChange_buildInProgress_defersWithoutNamingTheSync`
- `McpTextTest.verifyChangeResult_deferred_namesTheSyncOnlyWhenOneHelps`

---

## MC-91 — Tool columns are UTF-16 code units, and nothing says so — done

**Status: done** (2026-11-18, PR #390: the unit is stated; converting to characters is not done)

**Kind: bug**

**Affects: every position tool's `column` input and every `line:column` it reports, on a line with a character outside the Basic Multilingual Plane (an emoji) before the position.**

Found by the MCP exploratory round (2026-11-16).

### Observed behaviour

On `\tString label = "😀😀"; int after = count;`, `count` is at character column 35.
`get_definition` at column 35 answers `Line 9 has no symbol at column 35`; column 37 (UTF-16, two units per emoji) works.
`outline` lists `after` at `9:29` (character 27) and `find_references` reports `count` at `9:37`.
The tool descriptions say only "1-based column".
The MCP layer passes LSP positions through unchanged (`LatheMcpTools.fromWire`, `McpText.toWire`).
`symbol` avoids the problem, and edits on such a line are exact.

### Fix direction

Agents count characters (code points), as the files they read show them: convert at the MCP boundary both ways, or state the unit in every position tool's description.
Converting needs each line's text, for the column an agent gives (`fromWire`) and for every column `McpText` prints, including on files no client has open; a conversion that sees only some lines mixes the two units.

### Resolution

Every position tool's `column` input now says it counts UTF-16 code units, as every column the tools report does, and that a character outside the Basic Multilingual Plane counts as two; `symbol` needs no counting.
The agent guide says the same.
Converting to characters at the MCP boundary, as triaged on 2026-11-17, is not done: it needs each line's text for every column printed, files no client has open included; a request for it would be a feature.

### Regression targets

- `LatheMcpToolsTest.all_columnInputs_nameTheirUnit`
- `LatheMcpToolsTest.describeSymbol_positionNotOnAName_returnsErrorShowingTheLine_aNamedSymbolIsFound` (the error names the unit)

---

## MC-90 — A file whose package does not match its directory is reported clean and not compiled, and rename is a silent no-op — done

**Status: done** (2026-11-18, PRs #373 and #375)

**Kind: bug**


**Affects: `get_diagnostics`, `get_definition` and `rename_symbol` on a `.java` file with no `package` line, a `package` line naming another directory, or an illegal character before the `package` line.**

The lookups are in `lathe-analysis`, so the editor's definition and rename probably fail the same way; the editor was not checked.

Not to be confused with commit `fa56ea2d` (`Refs: MC-90`), which fixed MC-98: that gap was numbered MC-90 before it was renumbered.

Found by the MCP exploratory round (2026-11-16).

### Observed behaviour

javac and the Maven build compile `core/src/main/java/com/example/core/WrongPkg.java` declaring `package com.example.other;`.
Lathe answers, for that file:

- `get_diagnostics`: `No errors or warnings in WrongPkg.java.` followed by `Not compiled: …WrongPkg.java:no class was written — fix the code; cross-module answers use their last good classes.`, naming nothing to fix.
- `get_definition` of a field: `/tmp/lathe-compile-<pid>-<n>/com/example/core/WrongPkg.java:4:7 [EXTERNAL]`, a temporary compile copy instead of the file.
- `rename_symbol` of that field: `No references to rename.`, not an error, and nothing changes on disk.

A file with no `package` line behaves the same, including the silent rename.
A byte-order mark or a stray `#` before the `package` line hides the package from javac (`illegal character`): `get_definition` answers the temporary copy, and `rename_symbol` refuses with `` `f` is declared, or overrides a method declared, outside the project (JDK or a dependency) ``.
`find_references` maps every case back to the real file.
An agent that writes a new file with a wrong or missing package gets a contradictory answer, and a rename it believes was done.

### Cause (verified against the code, 2026-11-17)

`DefinitionLocator.findSourceFile`, and its twin `TypeSourceLocator.findSourceFile` that rename uses, build a source path from the declared package, so they miss a file whose directory names another package.
`DefinitionLocator.locate` then falls back to the compile copy, which `ServerFiles.tempSourceFile` places by directory.
"no class was written" is `WritePass.failedWrite`'s default when javac reported no error.

### Fix direction

Map the compile copy back to the source file whatever package javac reads, as `find_references` already does; compile a file whose package differs from its directory as the build does, or say plainly that Lathe needs them to match.
A rename that finds the symbol must never answer "no references".

### Resolution

A file whose package names another directory is reported as an error on its package line, as Eclipse does, where every compile's diagnostics are mapped, so the editor, `get_diagnostics` and `verify_change` all show it.
A rename that finds its target by its package (a type or a member searched beyond its declaring file) is refused there with the same reason; a local, a parameter, a type parameter or a private member is renamed in the file as before.
Definition answers the file asked about before any file its package names.

### Regression targets

- `PackageDirectoryTest.mismatch_packageNamesTheDirectory_none`
- `PackageDirectoryTest.mismatch_packageNamesAnotherDirectory_errorOnThePackageLine`
- `PackageDirectoryTest.mismatch_noPackageInASubdirectory_errorNamingTheDirectorysPackage`
- `PackageDirectoryTest.mismatch_defaultPackageAtTheSourceRoot_none`
- `PackageDirectoryTest.mismatch_innermostRootHoldingTheFile_isTheOneChecked`
- `PackageDirectoryTest.mismatch_fileInNoSourceRoot_none`
- `DefinitionLocatorTest.locate_sameFileWhosePackageIsNotItsDirectory_returnsTheFileAskedAbout`
- `PackageDirectoryEngineTest.diagnostics_packageNotItsDirectory_errorNamingBoth_matchingFileClean`
- `PackageDirectoryEngineTest.rename_inFileWhosePackageIsNotItsDirectory_refusesSayingWhy`
- `PackageDirectoryEngineTest.definition_inFileWhosePackageIsNotItsDirectory_answersTheFileItself`
- `PackageDirectoryEngineTest.verifyChange_fileWhosePackageIsNotItsDirectory_reportsTheMismatch`
- `PackageDirectoryEngineTest.rename_inFileWhosePackageIsNotItsDirectory_localsRenamedMemberRefused`
- `PackageDirectoryEngineTest.rename_inFileWhosePackageIsNotItsDirectory_fileScopedRenamedMemberRefused`
- `LatheTextDocumentServiceTest.prepareRename_fileWhosePackageIsNotItsDirectory_parameterAcceptedFieldRefused`

---

## MC-93 — `find_references` with `access` on a method answers "No references found" — done

**Status: done** (2026-11-17, PR #368)

**Kind: bug**

**Affects: `find_references` with `access` set, on a method, type or other symbol that is neither a field nor a variable.**

Found by the MCP exploratory round (2026-11-16).

### Observed behaviour

`find_references` on the method `total` with `access="write"` answers `No references found.`, although the method has a use.
The schema says `access` filters reads and writes of a field or variable.

### Fix direction

Refuse `access` for a symbol it does not apply to, or ignore it and say so; never answer an empty list for a used symbol.

### Resolution

`find_references` counts the uses the access filter left out, the declaration aside; when it left out every use, the answer says how many there are and why.
An unused symbol still answers "No references found."

### Regression targets

- `FieldAndEnumConstantSearchTest.references_accessFilter_countsTheUsesItLeftOutButNotTheDeclaration`
- `McpTextTest.referencesResult_accessFilterLeftOutEveryUse_saysHowManyUsesThereAre`

---

## MC-96 — `verify_change` fails whenever a dependent module has Kotlin sources in a Maven source root — done

**Status: done** (2026-11-17)

**Kind: bug**

**Affects: `verify_change` on a Maven reactor where a module that depends on the change registers a Kotlin source root (jdbi's `kotlin-sqlobject`, `src/test/kotlin`).**

Found by the MCP exploratory round on jdbi (2026-11-17).

### Observed behaviour

After any edit in jdbi's `core` (a new method nobody calls, or a 54-file rename), `verify_change` answers only `the request failed; see the server log`.
The log: `IllegalArgumentException: Compilation unit is not of SOURCE kind: "/tmp/lathe-compile-…/org/jdbi/v3/sqlobject/kotlin/TestIssue2961.kt"`, from `JavacRunner.createTask` under `JavacRunner.attributeBatch` while checking the dependents.
The `.kt` file is `kotlin-sqlobject/src/test/kotlin/…/TestIssue2961.kt`.
With nothing changed, or after a deletion, `verify_change` answers normally; on resilience4j (Gradle, Kotlin module `resilience4j-kotlin`) it works.
On jdbi an agent cannot check any change to `core`.

### Resolution

`verify_change` finds a change's callers partly by their compiled classes, and `ClassOutputs.sourceOf` maps a Kotlin class to its `.kt` source.
`ChangeVerifier` now diagnoses only `.java` files and names the other callers as not checked (`LatheVerifyChange.uncheckedCallers`), in the changed module as in a dependent one: the answer reads `No errors in what was checked; N Kotlin or Groovy caller(s) not checked.`, lists them, and no longer calls the build optional.
On jdbi, adding a method to `core` and renaming `Jdbi.open` across 54 files both verify again.

### Regression targets

`LatheEngineVerifyTest.verifyChange_callerCompiledFromKotlin_namesItAsNotCheckedAndChecksTheRest`, `verifyChange_kotlinCallerInTheChangedModule_namesItAsNotChecked`; `McpTextTest.verifyChangeResult_uncheckedKotlinCaller_namesItAndAsksForTheBuild`, `verifyHeadline_namedAndCallersNotChecked_namesBoth`.

---

## MC-41 — MCP edits written to a CRLF file add LF line endings — done

**Status: done** (2026-11-16)

**Kind: bug**

**Affects: MCP file edits, particularly `fix_imports`.**

Found by the pre-release probes on EqualsVerifier, then reproduced on Helidon and Caffeine.
Import edits contained LF line endings, and `DiskEdits` wrote them verbatim into CRLF files, leaving mixed line endings.
An earlier attempt was reverted because it shared separator detection through `SourceLocator`, outside the component that writes the files.

### Fix

`DiskEdits.applyFileEdits` converts each replacement to the separator terminating the original line at the edit's start.
For an unterminated last line it uses the preceding separator; a file with no line breaks uses LF.
It normalizes CRLF in the replacement before conversion, preventing doubled carriage returns.
Untouched file regions and the original editor-facing `TextEdit` objects remain unchanged.
This also respects local separators in a file that already has mixed line endings.

Validated on JDK 26: all 45 disk-edit, engine-edit and engine-rename tests passed.
The Caffeine MCP import probe reported zero bare LF line endings, no doubled carriage returns, and byte-for-byte stability on a repeated import fix.

### Regression targets

- `DiskEditsTest.apply_uniformLineEndings_preservesTheFileSeparatorAndTheOriginalEdit`
- `DiskEditsTest.apply_mixedLineEndings_matchesEachEditedLineWithoutRewritingTheOtherLines`
- `DiskEditsTest.apply_unterminatedLastLine_usesThePreviousLineSeparator`
- `DiskEditsTest.apply_noExistingLineBreak_usesLf`
- `DiskEditsTest.apply_identifierReplacement_preservesMixedLineEndingsExactly`
- `LatheEngineEditTest.fixImports_uniformLineEndings_preservesTheSeparator`
- `LatheEngineEditTest.format_uniformLineEndings_preservesTheSeparator`

---

## MC-68 — `analyze_change` has no caller-selected output limit or exact capped counts — done

**Status: done.**

**Value: low — Size: S**

**Affects: MCP `analyze_change`.**

Found by an agent's review of the MCP tools on Helidon (2026-11-01).

### Observed behaviour

On `Handler.handle` it printed all 225 implementations in its override family,
while the other tools take `maxResults`.

### Before the fix

`LatheEngine.IMPACT_MAX_RESULTS` already caps references and implementations at 1,000 each.
`McpText.analyzeResult` marks truncated references, but the tool has no `maxResults` argument, the override-family truncation is not exposed, and the displayed counts are derived from capped references.
The original claim of no limit at all is inaccurate; the user-controlled limit and exact counts remain open.

### Resolution

`analyze_change` takes `maxResults` (default 50) and lists that many members of each override-family list, under its true count: `overridden by: 225 (showing first 50)`.
The reference counts, affected modules and relevant tests are of every reference, not a capped list, so the "(truncated)" mark is gone.

### Regression targets

- `LatheEngineVerifyTest.analyzeChange_familyOverTheLimit_listsTheFirstCountsAll`
- `McpTextTest.analyzeResult_impactAndNoSymbol_everyFieldOnItsLine_noneWhereEmpty`

---

## MC-52 — External locations still repeat full URIs, with no dependency/JDK path shorthand — done

**Status: done.**

**Value: medium — Size: S**

**Affects: MCP tools that return locations.**

Found by an agent's exploration of a private 61-module Maven service (2026-10-30).

### Delivered — workspace-relative result paths

MC-18 already changed reactor and generated locations to workspace-relative paths.
`LatheEngine.displayPath` and the shared `McpText.position` renderer apply this to symbols, references, definitions and call hierarchies.
The original blanket claim that every result repeats a URI is obsolete.

### Remaining — external paths and round-trip shorthand

The original report was:

Each line of `search_symbols` and `call_hierarchy` carries `file:///home/<user>/<workspace>/…`,
the same long prefix repeated, about 30–40% of the tokens of those answers by the agent's estimate.

### Resolution

External locations are given by their absolute path instead of a `file://` URI: the agent can open it and hand it back to any tool as it is, as every `file` argument is an absolute path.
The gap's `~deps/` and `~jdk/` shorthands were not taken: they are no paths an agent's own file reader or shell knows, and every tool would have to expand them.
A location in no file (a class in a jar or the JDK image) keeps its URI.

### Regression targets

`LatheEngineNavigationTest.definition_jdkTypeWithSources_answersItsAbsolutePath`

---

## MC-54 — A dependency result does not say which version it came from — done

**Status: done.**

**Value: low — Size: S**

**Affects: MCP tools that return dependency locations.**

Found by an agent's exploration of a private 61-module Maven service (2026-10-30).

### Observed behaviour

The source cache held three versions of one internal library, and the agent had to work out which one
Lathe had resolved for the module it asked about.
The version is only implied by the cache path a result shows
(`…/deps/com.fasterxml.jackson.core/jackson-annotations/2.22/com/fasterxml/jackson/annotation/JsonCreator.java:46:19`),
never named.

### Resolution

A dependency's source lies under `deps/<group>/<artifact>/<version>/` in Lathe's cache, so its absolute path (MC-52) names the version resolved; `get_definition`'s description and the agent guide say so.

### Regression targets

`LatheEngineNavigationTest.definition_jdkTypeWithSources_answersItsAbsolutePath` (the path rendering)

---

## MC-71 — `outline` gives nothing for a file outside the reactor — done

**Status: done.**

**Value: low — Size: M**

**Affects: MCP `outline` (and the editor's document symbols) on files no module compiles.**

Found by an agent's review of the MCP tools on Helidon (2026-11-01).

### Observed behaviour

Helidon's `tests/`, `examples/` and `webserver/tests/*` are not reactor modules, so they are not indexed.
For such a file, `outline` answers "No declarations in BookService.java", then adds the "Not checked" note,
although a file's structure needs no compile.

### Resolution

A file's declarations and folds come from a parse alone, but they were asked only of the worker of the module that compiles the file, so a file no module compiles got none.
Such a file is now parsed on the worker that already analyzes dependency sources, which needs no module; the agent's outline reads it from the disk, as it never opens it.
`outline` still notes the file as not checked, and the editor gets the file's outline and folds too.

### Regression targets

`LatheEngineNavigationTest.outline_fileNoModuleCompiles_listsItsDeclarationsAndSaysNotChecked`,
`LatheTextDocumentServiceTest.documentSymbol_fileNoModuleCompiles_parsesItsDeclarationsAndFolds`.

---

## MC-77 — `workspace_summary` gives no test status for some modules — done

**Status: done.**

**Value: low — Size: S**

**Affects: MCP `workspace_summary`.**

Found by an agent's review of the MCP tools on Dropwizard (2026-11-01).

### Observed behaviour

Some modules show no test status at all, while others say `tests: not captured`.

Also (exploratory round, 2026-11-04): no test status for Dropwizard's docs/source/examples `conscrypt`, `getting-started` and `views`.

### Resolution

The modules without a status were those with no test sources, such as those three examples.
Every module line now ends with `tests: captured`, `tests: not captured` or `tests: none`; the summary's last line already gives the capture command.

### Regression targets

`McpTextTest.modulesResult_modules_oneLineEachWithUpstreamAndTestStatus_longListsCut`.

---

## MC-70 — Tool results come back unsorted, with modules mixed together — done

**Status: done.**

**Value: low — Size: S**

**Affects: MCP tools that list locations (`find_implementations`, `analyze_change`, and the like).**

Found by an agent's review of the MCP tools on Helidon (2026-11-01).

### Observed behaviour

Locations come in discovery order, so one module's results are scattered among another's,
and a long answer is hard to scan.

### Resolution

The engine sorts every capped location list in one order before capping it (`find_references`, `find_implementations`, `analyze_change`'s family, incoming `call_hierarchy`): the project's own sources, then its generated ones, then the JDK's and dependencies', by file within each, then by position.
By file keeps a module's results together, and in the usual layout its main sources before its tests; outgoing calls keep their source order.
`search_symbols` ranks the reactor's generated types after its hand-written ones (MC-39).

### Regression targets

- `LatheEngineNavigationTest.referencesAndCallers_handWrittenBeforeGenerated_cappedToTheHandWritten`
- `dev/mcp_battery.py`'s check "find_implementations: modules grouped".

---

## MC-74 — `search_symbols` lists types the project cannot use, though its description says it excludes them — done

**Status: done.**

**Value: low — Size: S**

**Affects: MCP `search_symbols`.**

Found by an agent's review of the MCP tools on Dropwizard (2026-11-01). Follow-up to MC-38, which ranks JDK internals last by design.

### Observed behaviour

Results include `ThreadLocal.ThreadLocalMap.Entry`, `Hashtable.Entry` (package-private nested types)
and `com.sun.tools.javac.util.StringUtils` (a JDK internal, ranked last per MC-38),
while the tool's description says such types are excluded.

### Resolution

The description now says what the tool does: the reactor's types first, JDK internals that need `--add-exports` last (MC-38), and the JDK's and dependencies' non-public types listed too.
They stay because the same symbol search serves the editor's workspace symbols and stack-frame navigation, which must find a dependency's package-private classes; dropping or ranking them for MCP alone would need each hit's visibility carried past the resolver, not worth it for a low-value gap.

### Regression targets

None: the description changed, not the behaviour.

---

## MC-75 — `outline` mislabels records and enum constants, and omits methods in enum constant bodies — done

**Status: done.**

**Value: low — Size: S**

**Affects: MCP `outline` (and the editor's document symbols, which share it).**

Found by an agent's review of the MCP tools on Dropwizard (2026-11-01).

### Observed behaviour

Records show as `struct`, enum constants as `field`, and an enum constant body's methods are missing.

### Resolution

An enum constant is an enum member in the document symbols (the editor shows it so too), and a constant's body lists its members under it.
The MCP answers name a record `record` and an enum constant `enum constant`; LSP has no record kind, so the editor keeps `Struct`.
The type index tells a record by its superclass, `java.lang.Record`, so `search_symbols` labels it `Record` instead of `Class`.
The JDK's and dependencies' type indexes already in the cache keep `Class` for their records until the cache schema next changes: rebuilding every cached index for this label alone was not worth it.

### Regression targets

- `DocumentSymbolScannerTest.scan_enumConstants_enumMembersWithTheirBodies_otherFieldsStayFields`
- `LatheEngineNavigationTest.outline_typeWithNestedTypes_listsEveryDeclarationAtItsDepthWithItsJavaKind`
- `ClassMetadataReaderTest.kind_recordAndPlainClass_recordOnlyForTheRecord`

---

## MC-63 — `find_references` cannot group its answer by the calling method — done

**Status: done.**

**Value: medium — Size: S**

**Affects: MCP `find_references`.**

Found by the survey of other Java MCP servers (2026-11-01).

### Gap

A method with forty call sites in ten methods answers forty lines; the agent's question is usually
"which methods call it", and it reads the list to work that out.
NetBeans' Dukehoff MCP takes `group_by: "caller"` and names each containing method by signature.

### Resolution

`call_hierarchy` (incoming) already answers "which methods call it": one line per calling method, named, following overrides and other modules.
`find_references`' description now points there; a `groupBy` would repeat it.
For a field, the `[write]` tag and the `access` filter (MC-53) name each place it changes.

### Regression targets

None: the answer exists; only the description changed.

---

## MC-53 — `find_references` does not tell reads from writes — done

**Status: done.**

**Value: medium — Size: S**

**Affects: MCP `find_references`.**

Found by an agent's exploration of a private 61-module Maven service (2026-10-30).

### Observed behaviour

"Where does the order's status change" needs the writes of a field or enum constant
(assignments, setter and builder calls) apart from its reads (comparisons, getters).
`find_references` lists them all alike, so the agent reads every site.

### Resolution

Each use of a field or variable is tagged `[read]` or `[write]` after its position, from the role the reference search already gives it: an assignment, a compound assignment or an increment writes.
A call of a Lombok-generated setter writes the field, and a call of its getter reads it.
Hand-written setters and builder methods are not references of the field, and naming a method a setter would be convention, so they stay out.
An optional `access` (`read`, `write`) keeps one kind, and the total counts what it keeps.

### Regression targets

- `FieldAndEnumConstantSearchTest.references_fieldUses_taggedReadOrWrite_filteredByAccess`
- `McpTextTest.referencesAndImplementationsResult_cappedListAndEmpty_headerLinesThenAuthority_orNone`

---

## MC-72 — MCP recompiles an unchanged file on every tool call; parallel calls compile it N times — done

**Status: done.**

**Value: medium — Size: M**

**Affects: MCP tools, on large files and with agents that call tools in parallel.**

Found watching an MCP session log on Dropwizard (2026-11-01).

### Observed behaviour

Every tool call reads the file from disk and passes it to the analysis as a new document
(`LatheEngine.fileWork`, formerly `compileFromDisk`), so the session compiles it again (`[compile:open]`) even when nothing changed.
Four parallel calls (`find_references` ×3, `describe_symbol`) compiled the same file four times in 50 ms;
three refused renames compiled it three times. It costs 15–40 ms per compile there (186 ms under load),
more on very large files.

### Resolution

The session keeps the engine's last compile of each file and answers from it while the file is the same document snapshot with the same content and no write pass has changed what its module compiles against.
Parallel calls share a compile still running.
A per-module generation, bumped as a write pass records a module to refresh (its own writes, another server's seen through the stamps, a build's), before a refresh it may hand to the next pass, tells a compile out of date; a reload or a change on disk replaces the snapshot.

### Regression targets

`LatheTextDocumentServiceTest.diagnosticsFuture_unchangedFile_compiledOnceUntilItOrWhatItUsesChanges`

---

## MC-67 — `analyze_change` reports no override family for a method that overrides one — done

**Status: done.**

**Affects: MCP `analyze_change`.**

Found by an agent's review of the MCP tools on Helidon (2026-11-01).
For `HttpRouteImpl.beforeStart`, which overrides `ServerLifecycle.beforeStart`, it answered "override family: none" while its reference count included the parent's callers: the family looked only down the hierarchy.

### Resolution

`analyze_change` lists the methods the target overrides under "overrides", nearest first, by binary type name, method name and descriptor (`ReferenceFeatures.overriddenMethods`): a JDK or dependency method has no location in the answer, and `describe_symbol` or `get_definition` follow a name.
The family below it is "overridden by", and its abstract redeclarations "redeclared by" (MC-78).
These three lines appear only for a method; any other symbol keeps one "override family" line.

### Regression targets

- `LatheEngineVerifyTest.analyzeChange_interfaceMethod_reportsOverrideFamilyAndReferences`
- `McpTextTest.analyzeResult_impactAndNoSymbol_everyFieldOnItsLine_noneWhereEmpty`

---

## MC-78 — `analyze_change`'s override family leaves out abstract redeclarations — done

**Status: done.**

**Affects: MCP `analyze_change`. Go to implementation and `find_implementations` leave them out by design.**

Found by the MCP release battery's reference round trip on Helidon (2026-11-02).
For `MediaType.text()` it left out `HttpMediaType.text()`, the subinterface's abstract redeclaration that `get_definition`, `find_references` and rename treat as part of the family: the implementation search dropped every abstract overrider.

### Resolution

The implementation search returns abstract overriders too, marked as redeclarations (`MethodImplementationLocator`, `ImplementationHit`), from the same one pass over the candidate files.
Go to implementation and `find_implementations` leave them out, as before; `analyze_change` lists them under "redeclared by" and counts their modules as affected.

### Regression targets

- `LatheEngineVerifyTest.analyzeChange_interfaceMethod_reportsOverrideFamilyAndReferences`: the redeclaring subinterface is listed apart, and `find_implementations` leaves it out.
- `MethodImplementationTest.methodImplementations_genericOverride_concreteDeclarationAndTheAbstractOneAsARedeclaration`

---

## MC-80 — `verify_change` skips callers that reach a changed type without naming it — done

**Status: done.**

**Affects: MCP `verify_change`.**

Found by the exploratory MCP round (2026-11-04) on Dropwizard.
It checked only the files that name the changed file's type, so 5 errors in three `dropwizard-health` tests that call `getSchedule().getDowntimeInterval().toSeconds()`, and never name `Duration`, went unreported.

### Resolution

The callers checked are also the files whose compiled classes use a class of the changed file, in the modules that can see it (`ChangeVerifier.referrers`): a class names every type it uses in its constant pool, as a class entry or inside a descriptor or signature, whatever its source spells.
The file's own type and the types nested in it are matched by name too, so the callers of a deleted or renamed type are still found after its classes left the mirror.
The `.lathe/` mirror is read once per check, for all changed files and for both the files to check and the sync suggested, and kept nowhere: reading all 9,586 classes of Helidon's mirror takes 1.3–1.8 s, and a check reads only the modules downstream of the change.
The editor is unchanged: it re-analyzes every open file downstream of a write.

### Regression targets

- `LatheEngineVerifyTest.verifyChange_callerReachesTheChangedTypeOnlyThroughAChain_reportsIt`
- `LatheEngineVerifyTest.verifyChange_typeReachedOnlyThroughAChainDeleted_reportsTheCaller`

---

## MC-98 — `verify_change` calls a rename clean while a generated caller still uses the old name — done

**Status: done.**

**Affects: MCP `rename_symbol` and `verify_change`.**

Found by the exploratory MCP round (2026-11-04) on Dropwizard, and first recorded under FR-020.
Renaming the JMH benchmark method `DurationBenchmark.parseDuration` left 10 generated callers on the old name, as a rename leaves annotation-processor output to its processor, but the answer did not say so, and `verify_change` said clean while `get_diagnostics` on the generated file showed the errors.
JMH's generated classes call the benchmark through a generated subclass (`*_jmhType`), so neither the name nor the class-file search connects them to the changed file.

### Resolution

`rename_symbol` lists the uses it left in annotation-processor output under "Left to the generator" (`LatheRename.generated`).
`verify_change` with no files checks those files as callers until their content changes from what it was before the rename's write (`ChangeVerifier.checkUntilRegenerated`), so a caller still on the old name is reported, and one the write regenerated is not.
A file list passed to `verify_change` is checked exactly, without them.

### Regression targets

- `LatheEngineRenameTest.rename_methodAlsoCalledFromGeneratedSource_leavesAndListsTheGeneratedSource`
- `LatheEngineRenameTest.verifyChange_afterRenameLeavingGeneratedCaller_reportsItUntilRegenerated`
- `McpTextTest.renameResult_editsMentionsAndGenerated_filesThenWhatToReview_orNothingToRename`

---

## MC-89 — `verify_change` checks the callers of a change the build's compile fails against its old classes — done

**Status: done.**

**Affects: MCP `verify_change`.**

Found by the review of the MC-82 fix (2026-11-15).
When a changed file's write failed, its classes in `.lathe/` stayed those of its last good compile and its callers were checked against them:
a change that both removed a method its callers used and added a warning `-Werror` fails reported the warning but no caller.

### Resolution

The callers of a changed file whose write failed are analyzed with its edited source, which declares what they see, and the answer says so.
The source is placed as rename's meaning check places another module's files; its own diagnostics stay those of its check alone.
The other changed files are checked with it too, and it goes only into the trees that can see it: in another, it would fail to resolve and make javac skip flow analysis there.
Errors in it make javac skip flow analysis for the callers analyzed with it, as an error in any file of a batch does.

### Regression targets

- `LatheEngineVerifyTest.verifyChange_changeFailsTheBuildsCompileAndBreaksACaller_reportsTheCaller`
- `LatheEngineVerifyTest.verifyChange_anotherChangedFileBrokenByAFailingChange_reportsIt`
- `LatheEngineVerifyTest.verifyChange_failingChangeBesideAnUnrelatedModule_keepsThatModulesFlowErrors`

---

## MC-59 — `outline` overflows on a large file — done

**Status: done.**

**Affects: MCP `outline`.**

Found by a refactoring review of Lathe itself through its own MCP server (2026-10-30).
`outline` on `WorkspaceSession.java` (428 declarations) answered 57,670 characters, over Claude Code's result limit, so the client saved it to a file and the agent sliced it with `jq`.

### Resolution

A file of more than 200 declarations answers its types instead, each with its position and own member count, and says to pass `type=<Name>` for one (`McpText.OUTLINE_MAX_DECLARATIONS`).
With `type`, `outline` answers that type's own members, a nested type as one line with its member count; a type whose own members pass the cap lists them without their code, about 35 characters each.
An unknown type name is an error listing the file's types.
No answer lists more than 600 lines (`McpText.OUTLINE_MAX_LINES`): past them it says how many more there are and the line they start at.
A class declared in a method is no member type: the types view leaves it out.

### Regression targets

- `McpTextTest.outlineResult_fileOverTheCap_answersItsTypes_oneTypeByName`
- `McpTextTest.outlineResult_typeOverTheCapOrUnknown_membersWithoutCodeOrAnErrorListingTheTypes`
- `McpTextTest.outlineResult_typeWithMoreMembersThanTheLineCap_listsTheCapAndWhereTheRestStart`
- `McpTextTest.outlineResult_localClassInAMethod_notAMemberType`

---

## MC-69 — `analyze_change` names too few affected modules — done

**Status: done.**

**Affects: MCP `analyze_change`.**

Found by an agent's review of the MCP tools on Helidon (2026-11-01).
On `Handler.handle` it named 5 affected modules, while the 225 implementations it found spanned 29; changing the method's signature would break all of them.
It counted the modules of reference sites only: a lambda implements a method without declaring one of its name, so a module that implements `Handler` only with lambdas and never calls `handle` was not counted.

### Resolution

`analyze_change` adds the modules of every implementation it found, not capped like the listed override family (`LatheEngine.analyzeChange`), and a test that implements the symbol is a relevant test.
The release battery's check "analyze_change modules cover the implementations" lost its known-failure mark.

### Regression targets

- `LatheEngineVerifyTest.analyzeChange_methodImplementedInAModuleThatNeverCallsIt_countsThatModule`
- `dev/mcp_battery.py`'s check "analyze_change modules cover the implementations" on Helidon's `Handler.handle`.

---

## MC-83 — Kotlin callers are invisible, yet references and rename call themselves complete — done

**Status: done.**

**Affects: MCP `find_references`, `rename_symbol`, `analyze_change`, `verify_change`, `workspace_summary`.**

Found by the exploratory MCP round (2026-11-04) on EqualsVerifier.
`find_references` missed the uses in `KotlinWithoutReflectTest.kt` and said "no need to re-verify with grep"; a rename left the Kotlin uses broken.

### Resolution

Lathe does not read Kotlin: its references, rename and checks stay Java-only.
In a workspace whose built modules hold Kotlin sources where the Kotlin plugins look by default (`src/<source set>/kotlin`), the answers of `find_references`, `find_implementations`, `rename_symbol`, `analyze_change`, `verify_change` and an incoming `call_hierarchy` no longer say grep is needless, and name those modules: "Kotlin sources in … are not searched; check them with grep."
`workspace_summary`, which lists the modules Lathe compiles, names the modules with Kotlin sources on a line of their own.
Kotlin sources elsewhere (a custom source directory, or `.kt` files beside the Java ones) go unnoticed.
Groovy sources (`src/<source set>/groovy`) are named the same way since 2026-11-16, each language with its own modules; the Java that Groovy compiles jointly there is [WS-37](gaps.md#ws-37--java-that-groovy-compiles-jointly-under-srcsource-setgroovy-is-in-no-module).

### Regression targets

- `WorkspaceManifestTest.kotlinModuleDirs_moduleWithKotlinSources_listed_javaModuleNot`
- `McpTextTest.referencesResult_workspaceWithKotlin_namesItsModulesInsteadOfTheGrepAdvice`
- `McpTextTest.noteLines_eachKindOfNote_rendersItsLine_noneWhenNothingToSay`
- `LatheEngineNotesTest.notes_moduleWithKotlinSources_namesIt`
- `McpTextTest.modulesResult_workspaceWithKotlin_namesEveryKotlinModule`

---

## MC-82 — `verify_change` says clean when the change fails only the build's Error Prone and `-Werror` — done

**Status: done.**

**Affects: MCP `verify_change`, `run_test` and the notes.**

Found by the exploratory MCP round (2026-11-04) on EqualsVerifier.
An Error Prone warning under `-Werror` (an unused new field in `Formatter.java`) failed the build's compile, but `verify_change` answered "No errors": it checks a changed file with Lathe's analysis compile, which runs without the build's processors and plugins, and checked the callers against the file's previous classes without saying so.
The other tools' note named only `Formatter.java:1: warnings found and -Werror specified`.

### Resolution

The write pass keeps why the build's compile failed a file it could not write (`WritePass.FailedWrite`): its own errors or, when `-Werror` failed the compile, its own warnings as errors.
javac's `-Werror` error itself, recognised by its code `compiler.err.warnings.and.werror`, is no cause: it lands on whichever file of the compile javac reached last.
`verify_change` adds those causes to what the analysis compile finds for a changed file, and says its callers were checked against the classes of the last good compile.
The "Not compiled" note on every tool, and `run_test`'s refusal, name the first cause.
The editor's open compile still runs without the build's plugins (EG-084).

### Regression targets

- `LatheEngineVerifyTest.verifyChange_changeFailsOnlyTheBuildsCompile_reportsTheWriteAndItsCallersOldClasses`
- `LatheEngineVerifyTest.verifyChange_buildOnlyWarningWithoutWerror_reportsNoFailedWrite`
- `McpTextTest.failedWritesNote_changedFileTheBuildFails_namesIt_noneOtherwise`

---

## MC-45 — Read tools open `.java` files outside the workspace, through symlinks too — done

**Status: done.**

**Affects: MCP.**

Found by the pre-release test rounds (2026-10-29/30) on EqualsVerifier (fuzzing).
The read tools accepted any existing `.java`-named file, including a symlink to `/etc/passwd`, and explained the refusal to check it as a module a profile left out.

### Resolution

The file and position tools answer only for a file whose real path is in the workspace, compiled by one of its modules, or among the dependency and JDK sources the build extracted (`LatheEngine.inWorkspaceOrSources`).
Any other file is refused with "`<file>` is outside the workspace and its dependency and JDK sources", a link in the workspace to a file outside it included.
`verify_change` names such a file as outside the workspace instead of blaming a profile.

### Regression targets

- `LatheMcpToolsTest.fileTools_fileOutsideTheWorkspace_returnError`
- `LatheMcpToolsTest.fileTools_linkInTheWorkspaceToAFileOutside_returnError`

---

## MC-81 — `verify_change` after a `module-info.java` edit checks only `module-info.java` — done

**Status: done.**

**Affects: MCP `verify_change`.**

Found by the exploratory MCP round (2026-11-04) on EqualsVerifier.
Removing `requires static java.sql;` from `equalsverifier-core`'s `module-info.java` got "No errors" from `verify_change`, which checked only the descriptor, while `JavaSqlValueSupplier.java` no longer compiled.

### Resolution

A changed `module-info.java` brings every source of its module into the check (`ChangeVerifier.seenBy`): callers are otherwise found by the changed file's type name, which no file spells for a descriptor.
The modules reading it are left to the sync the answer suggests, as before.
So that a descriptor edit stays fast on a large module, a change's callers are now checked up to 50 files of a source tree per javac task;
the changed files themselves are still checked one by one, against the classes their write produced.

### Regression targets

- `LatheEngineVerifyTest.verifyChange_moduleInfoDropsARequires_reportsTheModuleSourceThatNeedsIt`

---

## MC-76 — Two hits on one line show as duplicate rows without columns — done

**Status: done.**

**Affects: MCP tools that list locations.**

Found by an agent's review of the MCP tools on Dropwizard (2026-11-01).
When a line held two results, the answer showed the same `file:line: snippet` row twice, with no column to tell them apart:
the 2026-11-10 release check counted 11 such rows in 52 for one Dropwizard reference search.

### Resolution

Every located result now prints its column: `path:line:column: code`, as `grep -n --column` does (`McpText.position`).
Two hits on one line are two rows that differ by column, and the column is the position the other tools take, as the outline and the diagnostics already printed it.
The tool descriptions, the AI agents guide and the release battery's location parser (`dev/mcp_consistency.py`) follow the new form.

### Regression targets

- `McpTextTest.referencesResult_twoHitsOnOneLine_rowsDifferByColumn`
- `McpTextTest.locationLine_reactorAndExternal_oneGrepStyleLineTaggedOnlyOutsideTheReactor`

---

## MC-42 — An unparseable line on stdin leaves the MCP server running but deaf — done

**Status: done.**

**Affects: MCP.**

Found by the pre-release fuzzing (2026-10-29) on EqualsVerifier.

### Observed behaviour

The SDK's stdio transport (`StdioServerTransportProvider`) stops reading at the first line it cannot decode as JSON-RPC, a blank line included, and closes the session;
the server answers nothing more.
The process keeps running.
End of input closes the session the same way, but there the server now exits, as a client closing stdin means it is done.
A client that sent such a line waits on its requests until its own timeout.

### Resolution

`a7c30f5a` reads complete stdin lines through `ClientInput` and drops lines rejected by the SDK's own JSON-RPC parser before they reach its stdio transport.
The server keeps answering subsequent requests and still exits when stdin closes.
This supersedes the proposed transport-wrapper/exit approach.

### Regression targets

`LatheMcpServerTest.main_garbageLineThenStdinClosed_serverKeepsAnsweringThenExits` starts the real server,
sends an invalid line after initialization, checks that `tools/list` still answers, and checks exit after closing stdin.

---

## MC-73 — A file that does not compile gives an empty answer from stale classes, without saying so — done

**Status: done.**

**Value: medium — Size: S**

**Affects: MCP `find_implementations` (and other tools that fall back to the mirror).**

Found by an agent's review of the MCP tools on Dropwizard (2026-11-01).

### Observed behaviour

`find_implementations` answered "No implementations found" for a sealed interface with two records while
the file had a compile error: it fell back to stale classes. After the fix it found both.
`find_references` worked in the same state.

### Fix direction

When the answer comes from the last good classes, say so with the compile error, as the "Not compiled"
note does elsewhere; or answer from the file's parse where it suffices.

### Resolution

Fixed by the server's own changes since (found 2026-11-07, when the release battery's probe of it passed on every clone): with the probe file broken (`return listBroken;`), `find_implementations` on the sealed interface still lists both records.
Before closing it, the battery's other gap checks were checked against their gaps too: the checks marked as MC-69's and MC-70's passed for reasons of their own, so those gaps stay open with checks that reproduce them.

### Regression targets

`dev/mcp_battery.py`'s probe check "probe: broken file answers or says so", a regular check now.

---

## MC-79 — `verify_change` reports a broken change once; the next call says the callers compile — done

**Status: done.**

**Value: high — Size: S**

**Affects: MCP `verify_change`.**

Found by the exploratory MCP round (2026-11-04) on Dropwizard.

### Observed behaviour

Change `Duration.java:206` to `toSeconds(int scale)`: the first `verify_change` reports 5 errors; the next one, in the same
session or a new one, says "No errors — the changed files and their callers compile". `get_diagnostics` on
`DataSourceFactory` still shows both errors.
Fixing only `DataSourceFactory` then answers that the callers compile "so a full build is optional", while `JdbiHealthCheck`,
`SessionFactoryHealthCheck` and the tests are still broken.
With no files, `verify_change` checks what changed since the last check: the sources the pass just wrote and the tools'
writes, a set each call drains. The first check wrote the change into the mirror, so the next has nothing to check.

### Resolution

With no files, `verify_change` now also checks every source not as the last build compiled it, however long ago it
changed: one the session wrote (its compile stamp holds other content than the build's fingerprint), one the build
compiled that is gone, and one whose write failed. A broken change stays reported until it is fixed, reverted or built,
in this session and the next. A tree with no build fingerprints (OpenJDK) adds nothing, as before.
Dropwizard, `toSeconds(int scale)`: 5 errors on the first check, the second and in a new session (the second said
"No errors" before); reverted, none. A check with nothing changed on Helidon: 0.13–0.15 s (0.10–0.14 s before).

### Regression targets

`LatheEngineVerifyTest.verifyChange_breakLeftInPlace_reportedByEveryCheckAndSessionUntilReverted`

---

## MC-84 — Deleting a class created in the session is not caught by `verify_change` — done

**Status: done.**

**Value: medium — Size: S**

**Affects: MCP `verify_change`.**

Found by the exploratory MCP round (2026-11-04) on Dropwizard.

### Observed behaviour

Create `Zzz.java` in `dropwizard-util`, use it from `JdbiHealthCheck` (qualified or imported), verify, then delete it:
`verify_change` says "No errors" while `get_diagnostics` shows "cannot find symbol". Deleting an existing class
(`DirectExecutorService.java`) correctly reports 6 errors.
A deleted file that the last build never compiled counts as "back to what the build compiled" and is dropped.

### Resolution

Fixed with MC-79: the deleted class and its failed caller are in the set `verify_change` checks while they differ from
the build. Dropwizard: the deletion now reports 1 error (none before), and removing the use reports none.

### Regression targets

`LatheEngineVerifyTest.verifyChange_breakLeftInPlace_reportedByEveryCheckAndSessionUntilReverted` (the same mechanism).

---

## MC-32 — Nothing makes an agent run `verify_change` before it reports done — done

**Status: done.**

**Value: medium — Size: S**

Found by the agent-tooling landscape review (2026-10-24).

### Gap

An agent that skips `verify_change` finishes with code that does not compile.
Pyrefly documents a skill, an `AGENTS.md` directive and a Claude Code `Stop` hook for exactly this
([Pyrefly blog](https://pyrefly.org/blog/pyrefly-agentic-loop/)).

### Fix direction

The `AGENTS.md` / `CLAUDE.md` snippet exists ([examples/agents/lathe-mcp.md](../../examples/agents/lathe-mcp.md), which says to run `verify_change` before finishing) but the agent guide does not link it.
What remains: link it from the agent guide and add a hook recipe.
A hook cannot call an MCP tool, so a hook proper needs a command-line entry point to the engine —
a later, separate step.

### Resolution

The agent guide links the `AGENTS.md`/`CLAUDE.md` snippet (which says to run `verify_change` before
finishing) and now gives a Claude Code `Stop` hook,
[examples/agents/verify-before-stop.py](../../examples/agents/verify-before-stop.py): when the
session's transcript shows a Java edit (Edit, Write, MultiEdit, a Lathe rename or import fix) after the
last `verify_change`, it blocks the stop and tells the agent to run it, once per stop.
A hook cannot call an MCP tool, so the agent runs the check; a command-line entry point to the engine,
which would let a hook check itself, stays a later step.
The snippet also mentions `symbol` addressing.

### Regression targets

None in the build: the hook was checked by hand against synthetic transcripts (an edit with and
without a later check, a rename after a check, a non-Java edit, a second stop).

### Current verification

The delivered artifacts are `examples/agents/lathe-mcp.md` and `examples/agents/verify-before-stop.py`, linked by the agent guide.
This is workflow guidance and an optional hook, not a server guarantee that every agent invokes verification.

---

## MC-28 — `rename_symbol` does not say what text it left unchanged — done

**Status: done.**

**Value: high — Size: M**

Found by the agent-tooling landscape review (2026-10-24).

### Gap

A rename changes the code that resolves to the symbol, and the javadoc references, but not the name in
string literals, comments, `META-INF/services` files or framework XML.
An agent believes the rename is complete.
A study of agent renames found location-only LSP renames missed such text in most multi-file renames
([arXiv 2608.13568](https://arxiv.org/abs/2608.13568); figures not checked).

### Fix direction

After the rename, list the remaining occurrences of the old name (qualified name for resources) in
reactor files as "not renamed — review", without editing them.
NetBeans' Dukehoff MCP does exactly this for string literals (2026-11-01 survey); IntelliJ instead
edits comments and text only when asked (`searchInComments`, `searchInText`).

### Resolution

After a rename, `rename_symbol` lists where the old name is still text, edits none of it, and says
"Not renamed — review: the old name is still text in N place(s)" with the first 20
(`TextMentions`; the session only lists where to look, `WorkspaceSession.textMentionSources`, and the engine scans on its own thread, so the session's worker keeps serving other requests):

- Java string literals that name the symbol: the name alone (`getMethod("sell")`, a key) or qualified
  (`"com.example.Shop"`, `"Shop#sell"`), found through javac's trees in the files the name-token index
  lists, after a line-level filter so only files that may hold one are parsed. Prose that only contains
  the word is left out.
- Resource files under the reactor's resource roots naming it qualified, or by file name as a
  `META-INF/services` file does.

Comments are not listed: javac's public API does not expose them, and the rename already updates javadoc references.
Cost on Helidon (2026-11-01): 0.3 s for a type name like `MediaType`; 1.7 s for a symbol named `name`,
whose 739 exact `"name"` literals are counted, the first 20 listed.

Verified end to end on Dropwizard: renaming `DefaultHealthFactory` lists the
`META-INF/services/io.dropwizard.health.HealthFactory` line naming it.

### Regression targets

- `TextMentionsTest.inStringLiterals_literalNamingIt_notCodeProseOrALongerWord`
- `TextMentionsTest.inResources_qualifiedNameOrServiceFile_notAnUnqualifiedWord`
- `LatheEngineRenameTest.rename_methodUsedInAnotherFile_rewritesBothFilesOnDisk_listsTheNameLeftAsText`

---

## MC-60 — `run_test` does not return a failing test's output — done

**Status: done.**

**Value: medium — Size: S**

**Affects: MCP `run_test`.**

Found by a refactoring review of Lathe itself through its own MCP server (2026-10-30).

### Observed behaviour

A probe test printed the code action's edits with `System.out.println` before its assertion.
`run_test` returned the failure's message and line only (`LatheTestFailure`: test, summary, line),
so the agent had to move the value into the assertion's message and run again.
Logs and printed state are often what explains a failure.

### Fix direction

For each failed test, the last lines of its standard output and error, capped (say 40 lines),
from the transcript the launch already drains (`LaunchSession`).
Nothing for passing tests.
IntelliJ's run tool caps output at 10,000 characters and names a file holding the rest.

The same answer misleads when the test does not compile: on 2026-10-31 `run_test` reported
0 passed, 0 failed, 0 skipped for a test file with two errors
(see [MC-58](gaps-archive.md#mc-58--claude-code-shows-the-structured-content-so-an-agent-never-sees-the-notes--done)).
Say the test did not compile, with its errors, as numsu/java-lsp-mcp's `compile-failed` does.

### Resolution

- Each failed test carries the last 40 lines it printed (`LatheTestFailure.output`): transcript lines
  carry the test they belong to (`positionId`), as the test results do, so each failure gets its own.
- A run where no test reported back answers "No test ran (exit N)" with the run's last 40 lines, not
  "PASS — 0 passed".
- A test whose module, or a module it depends on, has a source that does not compile is not run on
  the last good classes: `run_test` answers "X.java was not run: code it runs does not compile:
  <file:line: error>", asking the session's failed compiles, not the notes' text.
- A class-level failure (`@BeforeAll`, a static initializer) shows the output printed outside any test.

Verified end to end on Dropwizard (2026-11-01): a failing probe test shows what it printed, and the
same file with a syntax error is refused with the error.

### Regression targets

- `LatheEngineRunTest.latheTestRun_completedOutcome_mapsCountsAndFailureWithItsOutput`
- `LatheEngineRunTest.latheTestRun_blockedOrNothingRan_carriesTheReasonOrTheLastOutput`
- `LatheEngineRunTest.runTest_fileWithNoTestOrNotCompiling_saysWhy`

---

## MC-38 — `search_symbols` lists JDK internals, and a glob query finds nothing — done

**Status: done.**

**Value: medium — Size: S**

**Affects: MCP `search_symbols` and the editor's workspace symbols.**

Found by the pre-release probes (2026-10-27) on EqualsVerifier.

### Observed behaviour

`warning` returns, after the two reactor types, `com.sun.tools.javac.comp.ThisEscapeAnalyzer.Warning`,
`com.sun.tools.javac.comp.WarningAnalyzer`, `com.sun.tools.javac.util.JCDiagnostic.Warning` and
`javax.swing.plaf.metal.MetalBorders.WarningDialogBorder`: types in packages the JDK does not export, and
no code in the project can use them.
`*Checker` answers `No symbols matching '*Checker'`, although agents write globs by habit.

On Lathe itself (2026-10-30), `Sync` with `maxResults` 40 filled the cap: the six reactor types
(`SyncScript`, `SyncCoordinator`, both `SyncMojo`s, …) came interleaved with Guava's `Synchronized*`
classes, the MCP SDK's `SyncToolSpecification` and javac's `SynchronizedTree`,
about 30 of the 40 from dependencies and the JDK.
The agent wanted the reactor's types and had no way to ask for only those.

### Fix direction

Leave out JDK packages a module does not export, at least for a classpath module.
Strip a leading or trailing `*` (or treat the query as a glob).
List reactor matches before any dependency or JDK match, and add a `scope` argument
(`reactor`, `all`) so an agent can leave the rest out.
Related: [EG-057](#eg-057--symbol-search-matches-camelhumps-only-in-reactor-types-not-in-jdk-and-dependency-types),
[MC-26](#mc-26--search_symbols-finds-types-only-not-methods-or-fields).

### Resolution

`LatheEngine.searchSymbols` (the MCP tool):

- lists reactor and generated types first, then JDK and dependency ones, then types in a JDK package
  its module does not export to everyone (javac's internals, `jdk.internal`, read from the running
  JDK's system modules): ranked last, not hidden, as a project that adds exports uses them;
- takes `scope` (`all`, the default, or `reactor`), through the `SymbolScope` `call_hierarchy` shares;
  `reactor` narrows the index search before its cap, so dependency matches cannot crowd the
  reactor's out;
- ignores a leading or trailing `*`.

Filtering and ordering run on the raw symbols, before any snippet is read.
The editor's workspace symbols are unchanged; they could take the same filter later.

Verified end to end on EqualsVerifier (2026-11-01): `warning` lists the two reactor types first and no
`com.sun.tools.javac` type, `*Checker` finds the checkers, and `scope: reactor` keeps the two reactor types.

### Regression targets

- `LatheEngineNavigationTest.searchSymbols_byName_findsReactorType` (glob, reactor scope).
- `LatheEngineNavigationTest.jdkInternal_containers_onlyTheJdksUnexportedPackages`

---

## MC-49 — `call_hierarchy` cannot leave out JDK and dependency calls — done

**Status: done.**

**Value: medium — Size: S**

**Affects: MCP `call_hierarchy`.**

Found by an agent's exploration of a private 61-module Maven service (2026-10-30).

### Observed behaviour

About 85 of the outgoing entries in one session were JDK calls (`Optional.get`, `Stream.map`, `String.formatted`),
which buried the reactor calls the agent was tracing and spent the `maxResults` budget.

### Fix direction

A scope argument (`reactor`, `dependencies`, `all`), defaulting to `reactor` for outgoing calls.
[MC-38](#mc-38--search_symbols-lists-jdk-internals-and-a-glob-query-finds-nothing) wants the same filter for `search_symbols`;
one origin classification can serve both.

### Resolution

`call_hierarchy` takes `scope` (`reactor`, the default, or `all`), through `LatheEngine.SymbolScope`,
which classifies by the existing location origin (reactor and generated code against the JDK and
dependencies) and which `search_symbols` shares.
Outgoing calls drop callees outside the reactor unless `scope` is `all`; incoming callers are reactor
code either way. An empty reactor-only answer says `scope=all` includes calls into the JDK and
dependencies, so a method that calls only the JDK does not read as calling nothing.

Verified end to end on Dropwizard (2026-11-01): `RoutingHandler.getHandlers` answers `handler` by
default and `stream`, `map`, `handler`, `toList` with `scope: all`; `handle`, which calls only Jetty and
the JDK, points at `scope=all`.

### Regression targets

- `LatheMcpToolsTest.callHierarchy_invalidDirectionOrScope_returnsError`
- `LatheEngineNavigationTest.callHierarchy_findsIncomingCallerAndOutgoingCallee` (reactor scope keeps the reactor callee).

---

## MC-30 — Position tools need an exact column, which agents get wrong — done

**Status: done.**

**Value: high — Size: M**

Found by the agent-tooling landscape review (2026-10-24);
confirmed by an agent's exploration of a private 61-module Maven service (2026-10-30).

### Gap

Agents miscount columns; [MC-10](gaps-archive.md#mc-10--position-tools-answer-for-an-unrelated-symbol-when-the-position-is-not-on-one--done)
now makes them retry, at the cost of a round trip; the refusal quotes the line's text, which makes the retry cheaper.
mcp-language-server takes a symbol name for `definition` and `references`, and Serena addresses
symbols by name path (`Class/method`).

In the 2026-10-30 session, every navigation question first needed an `outline` or `search_symbols` call
to find a position, and one of the agent's ten Lathe calls failed on a wrong position.
The agent asked for `com.example.app.OrderDao#markShipped` or `OrderDao.markShipped`,
which would save a round trip on almost every query.

A refactoring review of Lathe itself (2026-10-30) missed twice in 21 calls,
both times one column short on a method name taken from an `outline` line
(`private static ExpectedValue expectedMethodArgumentValue(`: the agent sent 31, the name starts at 32).
`outline` gives each member's line but not where its name starts, so the agent counts by hand.

### Fix direction

Cheap: accept `line` plus the symbol's text (`symbol`) instead of a column, resolving the column from
the line. Full: a name path (`pkg.Type#method(params)`), which needs overload handling.
Cheaper still for the `outline`-then-query pattern: give each `outline` item the name's column.
Prior art (2026-11-01): numsu/java-lsp-mcp makes the column optional (the innermost declaration on the
line) and clamps one past the end; IntelliJ's rename takes the symbol's name plus an optional exact
snippet; NetBeans' Dukehoff MCP takes `com.acme.Foo#bar(String,int)`.
This slice takes the cheap form: `column` optional, `symbol` picks the occurrence on the line.

### Resolution

The cheap form, as other servers do it (numsu/java-lsp-mcp's optional column, IntelliJ's symbol name):

- Every position tool takes `symbol`, the name as written on the line, and `column` becomes optional
  with it. Each place the name stands as a whole word on the line is a candidate (nearest the given
  column first); javac resolves them in turn, so one inside a string or comment is passed over.
  A name not on the line answers "Line N has no symbol named 'x'. Line N reads: …".
- `outline` gives each declaration as `line:column` of its name, the position the other tools take.

A name path (`pkg.Type#method(params)`) stays a possible later step.

Verified end to end on Dropwizard (2026-11-01): an `outline` position fed to `describe_symbol`, a
symbol without a column, and a name whose first occurrence on the line is in a string.

### Regression targets

- `LatheMcpToolsTest.describeSymbol_positionNotOnAName_returnsErrorShowingTheLine_aNamedSymbolIsFound`
- `McpTextTest.outlineResult_nestedDeclarations_indentedLinesUnderOneHeader_orNoDeclarations`

---

## MC-48 — Outgoing `call_hierarchy` lists where each callee is defined, not where it is called — done

**Status: done.**

**Value: high — Size: S**

**Affects: MCP `call_hierarchy` (`direction=outgoing`).**

Found by an agent's exploration of a private 61-module Maven service (2026-10-30).

### Observed behaviour

Each outgoing entry is the callee's declaration.
The agent could not see where in its own method each call happens, so it could not tell the order of the calls,
and had to read the method anyway.

### Fix direction

`LatheEngine.toCallee` keeps only the callee's `selectionRange` and drops the LSP `fromRanges`,
which already hold the call sites.
Emit one line per call site, in source order, naming the callee, as `incomingCalls` already does for callers.

### Resolution

`LatheEngine.outgoingCalls` emits one line per call site, at the LSP answer's `fromRanges` in the
method's own file, sorted in source order and naming the callee, sharing `callSites` with the incoming
direction. The callee's declaration is a `get_definition` away from any of those positions.

Verified end to end on Dropwizard (2026-11-01): outgoing calls of `RoutingHandler.getHandlers` list
`stream`, `map`, `handler`, `toList` at line 51, left to right.

### Regression targets

- `LatheEngineNavigationTest.callHierarchy_findsIncomingCallerAndOutgoingCallee` (the outgoing call is at its
  site in `Caller.java`, not the callee's declaration).

---

## MC-51 — A record's accessor resolves to the top of its file — done

**Status: done.**

**Value: high — Size: S**

**Affects: MCP `call_hierarchy`; probably the editor's outgoing calls too, which build the same items.**

Found by an agent's exploration of a private 61-module Maven service (2026-10-30).

### Observed behaviour

Outgoing calls to `Order.status()` and `Row.customerId()`, implicit accessors of records, came back as
`startLine: 1` with the `package` line as the snippet.

### Fix direction

An implicit accessor has no tree of its own.
Point it at its record component, as go-to-definition should; check that path and find references for the same fallback.

### Resolution

`DefinitionLocator.parsePosition`, which places each outgoing callee, falls back to the record
component when the element is a record's implicit accessor, through the same `recordComponentField`
go-to-definition already used; an implicit accessor has no tree of its own, so it used to fall back to
line 1. Find references already resolves an accessor through its component.

### Regression targets

- `CallHierarchyOutgoingLocatorTest.outgoingCalls_recordAccessor_pointsAtItsComponent` (fails without the change).

---

## MC-46 — After an agent edits `lombok.config`, `verify_change` reports no errors that exist — done

**Status: done.**

**Value: high — Size: M**

**Affects: MCP.**

Found by the pre-release test rounds (2026-10-29/30) on a Lombok project.

### Observed behaviour

After an agent removes `lombok.accessors.prefix` from a `lombok.config`, `verify_change` on the affected files answers "No errors" in milliseconds, while `get_diagnostics` shows the `getCount()` error.
Files whose `.java` content equals the last build count as unchanged, and a `lombok.config` change is not a change.

### Fix direction

Count a changed `lombok.config` as a change to the sources below it.

### Resolution

When a workspace loads, the server notes the `lombok.config` files over its source roots (one per
directory from a root up to the workspace root); each pass stats just those (most projects have
none). An edited or deleted one reports every source below its directory for a write, so the mirror's
classes follow the new settings, and those sources count as changed for `verify_change` until the
next sync, though their text is what the build compiled (`WorkspaceSession.reportLombokConfigChanges`).
Passes under a running build skip the check; the first pass after it compares.
A config added after the load counts from the next reload, and one inside a source root's packages
is not watched; the usual one sits above the roots.

Verified end to end on the `lombok` invoker fixture (2026-11-01): adding `lombok.accessors.fluent = true`
in a `model/lombok.config` makes `verify_change` on `Person.java` report the `getName()` error, and
removing it clears it (run before the check was limited to configs present at load).
Known limit: Lombok caches a config file in-process and rechecks the file system about every two
seconds, so a second config edit checked within two seconds of the previous compile can still see
the old settings; the next check sees the new ones.

### Regression targets

- `LatheEngineVerifyTest.verifyChange_lombokConfigEdited_checksTheSourcesBelowIt` (fails without the change).

---

## MC-62 — `verify_change` names dependent modules and a sync for changes no other module can see — done

**Status: done.**

**Value: high — Size: S**

**Affects: MCP `verify_change`.**

Found implementing gap CA-16 through Lathe's own MCP server (2026-10-31).

### Observed behaviour

Every `verify_change` in the session, about ten, answered
`"affectedModules":["lathe-maven-plugin","lathe-mcp-server"]` with
`"suggestedSync":".lathe/lathe-sync.sh lathe-maven-plugin lathe-mcp-server"`, whatever had changed:

- a test-only edit (a deliberate error in `lathe-server`'s `CodeActionTest`, no arguments): the error was
  reported, and so were both dependent modules and the sync, though test sources are on no other module's
  classpath;
- package-private classes (`ExtractVariableProvider`, `CodeActionSupport`) of `lathe-server`, a JPMS module whose
  dependents cannot reference them.

A suggestion that is always there gets ignored, or obeyed: an agent following it runs a reactor build for
nothing (minutes on a large reactor). [MC-8](gaps-archive.md#mc-8--the-sync-command-verify_change-suggests-does-not-build-the-dependent-modules-it-names--done)
made the command build what it names; this is about what it names.

### Fix direction

Name a dependent module only when the change can reach it: never for a test source tree; for main sources, only
when a changed file declares a type the dependent can see (public or protected, in an exported package for a
JPMS module), or, more precisely, when the reference index finds a use of a changed file's types there.
With none left, answer no `affectedModules` and no `suggestedSync`.

### Resolution

`verify_change` names a dependent module, and the sync that builds it, only when one of its files names
a type of a changed file (`WorkspaceSession.downstreamModuleRels` over the same name-token referrers
the check already compiles), so a test-only change, or a type no dependent names, names no module and
suggests no sync.
The name match can over-select (a file that only mentions the name), never miss a direct use.
A `module-info.java` or `package-info.java`, which no file names, still names every dependent.
A dependent that reaches the type only through another module's API (`b.getA().foo()`, never writing `A`) is not named; the check itself compiles only direct users too.

Verified end to end on Dropwizard (2026-11-01): an edit to `DurationTest` answers "No errors" with no
dependents; an edit to `Duration` still names its dependents and the sync.

### Regression targets

- `LatheEngineVerifyTest.verifyChange_twoOfSixModulesUseTheChange_suggestsTheScopedSyncOfThoseDependents`
  (a dependent that never names the type is left out).

---

## MC-43 — Expected refusals are logged at SEVERE with a stack trace — done

**Status: done.**

**Value: low — Size: S**

**Affects: MCP.**

Found by the pre-release test rounds (2026-10-29/30) on Dropwizard and EqualsVerifier.

### Observed behaviour

A refusal is the tool's normal answer, but each is logged as an unexpected error: a rename refused (JDK method,
Lombok member, shared static import, existing name), `run_test` with no test target, `format_file` with no formatter.
The editor's server does not log its refusals this way.

### Fix direction

Log refusals at FINE, or INFO without a stack trace; keep SEVERE for unexpected failures.

### Resolution

The engine throws `LatheEngine.Refused` (an `IllegalStateException`) for every answer that is a
refusal rather than a failure: a request the server refused with a reason (rename refusals, no
formatter, a formatter error), a write outside the reactor, and a file with no test to run.
`LatheMcpTools.guarded` logs a refusal at FINE, without a stack trace; SEVERE with the stack trace stays
for unexpected failures (a timeout, a request that failed without a reason).

Verified end to end on Dropwizard (2026-11-01): the `format_file` and `run_test` refusals leave no
SEVERE line in the session log.

### Regression targets

- `LatheMcpToolsTest.logged_recordsToolNameAndArguments_aRefusalWithoutAnError`

---

## MC-44 — A `.lathe/` created after the MCP server started is never picked up — done

**Status: done.**

**Value: high — Size: S–M**

**Affects: MCP.**

Found by the pre-release test rounds (2026-10-29/30) on a fresh Maven project.

### Observed behaviour

An MCP server started in a project with no `.lathe/` keeps answering "found no .lathe/" after a build creates it.
The tools do not say to restart the server; only the agent guide does.

### Fix direction

Look for `.lathe/` again on each call while none was found, or say in the answer that a restart picks it up.

### Resolution

Started where no `.lathe/` is, the server looks for it again on each tool call (`LatheMcpServer.AwaitedEngine`)
and opens the engine the first time it finds one, so the build that creates it is picked up without
restarting the agent's session; the "found no .lathe/" answer says so.
One engine opens the workspace however many calls arrive at once.

Verified end to end (2026-11-01): a server started in an empty directory answers "found no .lathe/",
then `0 module(s)` once `.lathe/` is created.

### Regression targets

- `LatheMcpToolsTest.all_noWorkspace_everyToolAnswersWhereItLookedAndTheSetup_untilABuildCreatesIt`

---

## MC-35 — Remedies that cannot help: a sync for a missing formatter, a file outside every source root, or a profile-excluded test — done

**Status: done.**

**Value: high — Size: S**

Found by the pre-release probes (2026-10-27) on Dropwizard and Helidon.

### Observed behaviour

- `format_file` on a project that pins no formatter (Dropwizard, Helidon) answers
  ``No formatter for this workspace: the project pins none, or its formatter's jars are not resolved yet (run `.lathe/lathe-sync.sh`).``
  `LatheTextDocumentService` already knows which case it is (no formatter spec means nothing is pinned),
  and an agent following the advice runs a 2–3 minute sync for nothing.
- `Not checked:` for `dropwizard/docs/LatheOut.java`, in no source root at all, says
  `is in no synced module — run .lathe/lathe-sync.sh`.
- `run_test` on a test in a module the build leaves out (Helidon `webserver/tests/static-content`, behind the
  `tests` profile) answers `no class test target in StaticContentTest.java`, as if the file held no test;
  `get_diagnostics` on the same file correctly says the module is outside the build.
  On a non-test file the same terse line gives no hint toward the matching test class.

### Fix direction

Give each case its own message: "the project pins no formatter" (with the `lathe-style.json` pointer),
"this file is in no source root", and "this module is not in the last build (a profile may add it)".
For a non-test file, name the test class that tests it when one exists.

### Resolution

Each refusal names its real cause, and only a cause a sync fixes names the sync:

- `format_file` with no formatter says the project pins none and points at `lathe-style.json`; only a
  pinned formatter whose jars are missing says "not resolved yet: run `.lathe/lathe-sync.sh`".
- `Not checked:` for a file outside every source root of a module the last sync wrote says
  "is in no source root of module X, so a sync does not add it"; the sync is named only for a built
  module the sync has not written (no `.lathe/<module>/`); a module outside the last build notes that
  a profile may leave it out.
- `run_test` on a file with no test gives the not-checked reason when no module compiles it, else
  "no test in X.java", naming `XTest`/`XTests` when the reactor has one.

Verified end to end (2026-11-01): Dropwizard's `format_file`, `docs/LatheLoose.java` (the `docs` module
has no sources) and `run_test` on `Duration.java` (names `DurationTest.java`); Helidon's
`webserver/tests/static-content` test (outside the default profile).

### Regression targets

- `LatheEngineEditTest.format_noFormatter_namesTheStyleFile_unresolvedOne_namesTheSync`
- `LatheEngineNotesTest.notes_fileNoModuleCompiles_notCheckedWithItsCause`
- `LatheEngineRunTest.runTest_fileWithNoTestOrNotCompiling_saysWhy`

---

## MC-47 — Notes that mislead: a moved file "not checked", no word of a pending sync — done

**Status: done.**

**Value: high — Size: S**

**Affects: MCP.**

Found by the pre-release test rounds (2026-10-29/30) on a fresh Maven project.

### Observed behaviour

- After renaming a type back (`State` to `Status`), the answer notes "Not checked: …/State.java does not exist",
  about the file the rename itself moved.
- After an agent edits a POM, `workspace_summary` does not mention the pending sync, while `get_diagnostics`
  ("Sync required") and `verify_change` (deferred) do.

### Fix direction

Leave the files a rename moved out of the not-checked notes; give `workspace_summary` the same sync note,
as [MC-37](gaps-archive.md#mc-37--workspace_summary-is-silent-when-lathe-was-written-by-another-lathe-version--done) asks for another schema.

### Resolution

- A rename's notes are about the files it wrote, at their paths after the rename (`LatheRename.paths`),
  so the file a type rename moved away is no longer reported "not checked … does not exist".
  A rename that changed nothing still notes the asked-about file.
- `workspace_summary` names the pending sync after a POM edit: it carries every tool's notes since
  [MC-37](#mc-37--workspace_summary-is-silent-when-lathe-was-written-by-another-lathe-version--done).

Verified end to end on Dropwizard (2026-11-01): renaming `JarLocation` and back notes nothing, and a
POM edit makes `workspace_summary` end with `Sync required: build files changed — run .lathe/lathe-sync.sh`.

### Regression targets

- `LatheEngineRenameTest.rename_publicTypeUsedInDownstreamModule_movesFileAndMirrorsOnlyTheNewClass`
  (the moved file's new path, and no not-checked note for the touched files).
- `LatheEngineNotesTest.notes_buildFileEditedMidSession_syncRequiredUntilItsContentIsBack_editsStillCompile`
  for the pending-sync note `workspace_summary` now carries.

---

## MC-37 — `workspace_summary` is silent when `.lathe/` was written by another Lathe version — done

**Status: done.**

**Value: high — Size: S**

Found by the pre-release probes (2026-10-27) on EqualsVerifier.

### Observed behaviour

With `workspace.json` at an older `schemaVersion`, the server ignores the manifest.
`get_diagnostics` then ends with the right note, but with a doubled prefix:
`Sync required: Lathe: .lathe/ was written by another Lathe version (schema 5, expected 6)`.
`workspace_summary`, the tool the instructions tell an agent to call first, drops every `depends on`
edge and carries no note.

### Fix direction

`workspace_summary` carries the same `Sync required:` note; drop the inner `Lathe:` prefix.
[MC-47](#mc-47--notes-that-mislead-a-moved-file-not-checked-no-word-of-a-pending-sync--done) asks for the same note for a pending POM sync; one change can serve both.

### Resolution

`workspace_summary` carries the notes every other tool does (`engine.notes` over no files), so a
`.lathe/` from another schema, or an unreadable one, answers `0 module(s)` followed by
`Sync required: .lathe/ was written by another Lathe version (schema 5, expected 6). Run … to regenerate it.`
The same note covers a pending build-file sync and a running build.
The doubled prefix is gone: the remediation texts in `LatheLayout` no longer start with `Lathe:`, and
the editor's warning adds it where it shows the message.

### Regression targets

- `LatheMcpToolsTest.workspaceSummary_latheFromAnotherVersion_notesTheSyncWithoutAPrefix`
- `LatheTextDocumentServiceTest.initialize_latheFromAnotherVersion_warnsWithThePrefixedRemedy` (the editor keeps the prefix)
- `LatheEngineNotesTest.notes_latheWrittenByAnotherVersion_syncRequiredWithTheCommand`

---

## MC-33 — Tools return structured content but declare no `outputSchema` — non-goal

**Status: non-goal.**

Found by the agent-tooling landscape review (2026-10-24).

### Gap

The 2025-06-18 spec lets a tool declare the schema of its `structuredContent`
([changelog](https://modelcontextprotocol.io/specification/2025-06-18/changelog)); Lathe returns
structured content without one. It helps programmatic clients, not models, so it waits for a client
that needs it.
Claude Code does show the model the structured content, not the text:
see [MC-58](#mc-58--claude-code-shows-the-structured-content-so-an-agent-never-sees-the-notes--done).

### Resolution

Closed as a non-goal with [MC-58](#mc-58--claude-code-shows-the-structured-content-so-an-agent-never-sees-the-notes--done):
the tools no longer return structured content, so there is no schema to declare.
Revisit only for a programmatic client that needs typed results (for example Codex's code mode), and
then put every note in the structured form too.

### Regression targets

None: nothing to regress.

---

## MC-58 — Claude Code shows the structured content, so an agent never sees the notes — done

**Status: done.**

**Value: high — Size: S**

**Affects: every MCP tool; seen in Claude Code.**

Found by a refactoring review of Lathe itself through its own MCP server (2026-10-30).

### Observed behaviour

Each tool answers with a compact text body and a `structuredContent` map (`LatheMcpTools.result`).
Claude Code gave the agent the structured map, not the text: every answer in the session arrived as JSON
(`{"references":[…],"total":7,"truncated":false}`), never the `path:line: code` lines the tool descriptions promise.
Two consequences:

- The notes ("Not compiled", "Sync required", "Not checked", "A build is running") are only in the text body,
  so the agent never sees them: an answer about code that does not compile, or that only a sync can fix,
  looks complete. The server instructions tell the agent to act on these notes.
- The JSON repeats each key on every item, so answers are larger than the text form
  (`outline` on a 5,324-line file came to 58k characters; see [MC-59](#mc-59--outline-overflows-on-a-large-file)).

Seen again on 2026-10-31: with a test file that did not compile, `run_test` answered
`{"passed":0,"failed":0,"skipped":0,"launched":true}` and nothing else, so the run looked empty rather than broken;
only a separate `get_diagnostics` showed the two errors.

This contradicts the premise of [MC-33](#mc-33--tools-return-structured-content-but-declare-no-outputschema--non-goal),
that structured content serves programmatic clients and not models.

### Fix direction

Decided 2026-11-01: drop `structuredContent` and answer in text only.
Claude Code, Codex CLI and VS Code Copilot give the model the structured content and drop the text;
Gemini CLI and Cursor give it only the text (Codex, VS Code and Gemini checked in their sources).
The spec says nothing about which part reaches the model, so text is the only form every client shows alike,
and no client relies on the structured form without an `outputSchema`.
Survey: [MCP answers an agent can trust](../done/lathe-mcp-trust.md).

### Resolution

Every tool answers in text only: `LatheMcpTools.result` no longer attaches `structuredContent`, and the
per-tool maps that fed it are gone, so each client shows the model the compact `path:line: code` text
and the notes after it.
Why text and not notes copied into the structured map: Claude Code, Codex CLI and VS Code Copilot give
the model the structured content and drop the text, Gemini CLI and Cursor give it only the text, and the
spec says nothing about which part reaches the model; no Lathe tool declares an `outputSchema`, so no
client relied on the structured form.

### Regression targets

- `LatheMcpToolsTest.verifyChange_emptyWorkspace_reportsCleanInTextOnly` (asserts no structured content).

---

## MC-36 — With no `.lathe/`, the MCP server exits before the handshake, so the agent never sees why — done

**Status: done — Target: next.**
Found by the pre-release probes (2026-10-27).

### Observed behaviour

Started where no `.lathe/` is above the working directory, `LatheMcpServer` logs one `SEVERE` line,
`If this project is not set up for Lathe yet: for Maven, add the lathe-maven-extension and run …`, and exits
with status 2.
The line does not say what is missing or where it looked.
An MCP client shows only that the server failed to connect; the agent gets no tool, no instructions and no hint.

### Fix direction

Start anyway and answer the handshake: the server instructions and every tool say that no `.lathe/` was
found above the working directory, and give the setup remediation.
The log line names the missing `.lathe/` and the directory searched.

### Resolution

With no `.lathe/` above the working directory the server no longer exits: it answers the handshake with
instructions that say where it looked and how to set the project up, lists every tool, and each tool
answers that same message as an error (`LatheMcpTools.all` takes the engine through a supplier, which
throws `NoWorkspaceException` when there is none). The startup log has the same line at `WARNING`.
Checked end to end with the MCP launcher in an empty directory.
The setup step it names is `LatheLayout.SETUP_REMEDIATION`, shared with the editor's warning: each
build front-end's first command (Maven, OpenJDK). The Gradle front-end adds its own there, and in the
Neovim client's `SETUP_HINT`, when it ships.

### Regression targets

`LatheMcpToolsTest.all_noWorkspace_everyToolAnswersWhereItLookedAndTheSetup_untilABuildCreatesIt`.

---

## MC-40 — `verify_change` right after a rename recompiles for seconds — done

**Status: done — Target: next.**
Found by the pre-release probes (2026-10-27) on EqualsVerifier and Dropwizard.

### Observed behaviour

`rename_symbol` already compiles the changed files into the mirror before it returns, yet the
`verify_change` that follows took 6.8 s on EqualsVerifier (16 files) and 6.2 s on Dropwizard, and 18–23 s on
Dropwizard while a Helidon build ran on the machine.
The agent guide steers every rename into a `verify_change`, so each rename costs twice.

### Cause (2026-10-28)

Not a recompile of what the rename wrote: `verify_change` checks the changed files and their callers, and
checked them one at a time. Renaming `Duration.toMilliseconds` on Dropwizard (15 files) gives 111 files in
22 modules to check, at about 45 ms each: 5.0 s of the call's 5.6 s.
On EqualsVerifier, renaming `usingGetClass` (14 files) now checks 19 files in 2 modules, and the call takes
0.8 s.

### Resolution

`LatheEngine.verifyChange` starts every file's check before it awaits any, so the modules compile side by
side, each on its own worker, within the compile admission's one-per-core limit.
On the same Dropwizard rename the check takes 1.9 s instead of 5.0 s, and the call 2.5 s instead of 5.6 s,
with the same result. One javac task per module for its files would cut more, if a larger reactor needs it.

### Regression targets

The engine's existing `verify_change` tests cover the result; the time was measured by hand on Dropwizard.

---

## MC-34 — Position tools still answer for the enclosing tree inside comments and string literals — done

**Status: done — Target: next.**
Found by the pre-release probes (2026-10-27) on EqualsVerifier.

### Observed behaviour

[MC-10](gaps-archive.md#mc-10--position-tools-answer-for-an-unrelated-symbol-when-the-position-is-not-on-one--done)
made a position off a name an error, but `LatheEngine.onName` checks only that the text under the column
is a word, so a word inside a comment, javadoc or string literal passes:

| Position (EqualsVerifier) | Answer |
|---|---|
| a word in the javadoc of `Warning.ALL_FIELDS_SHOULD_BE_USED` (`get_definition`) | the enclosing `enum Warning` |
| a word in the string argument of `new ModuleException("…", e)` in `Rethrow` (`get_definition`) | the `ModuleException` constructor |
| the same (`find_references`) | the constructor's 3 call sites |
| the same (`describe_symbol`) | the parameter `String message` |
| the same (`rename_symbol`) | `No references to rename.` with `isError=false` |

The guide promises an error that shows the line instead.
`line: 0` answers "the file is shorter" rather than saying positions are 1-based.

### Fix direction

After the text check, confirm the resolved tree's name covers the position: reject positions inside
comments (javac's comment ranges, or `DocTrees` for javadoc) and inside literals.
Answer `line: 0` and `column: 0` with "positions are 1-based".

### Resolution

After the word check, `LatheEngine.onName` asks the server whether javac's tree has a name exactly at
the position (`SourceLocator.onName`): an identifier, the name ending a member select or method
reference, a declaration's own name, or a Javadoc reference (`JavadocReferenceResolver`). Comments
are not in javac's tree and a literal has no name, so a word in either answers the "must be on a name"
error that shows the line; no comment is scanned. What cannot be checked (a file no module compiles)
passes, and the tool answers for it as before. A line or column below 1 answers "Positions are
1-based". The declaration-name check relies on names being found after a declaration's annotations
and type (`SourceLocator.declarationNameOffset`), which also fixed a rename that rewrote an annotation
string spelling the field's name instead of the field.

### Regression targets

- `SourceLocatorTest.onName_namesInCode_accepted_wordsInCommentsAndLiterals_rejected`
- `SourceLocatorTest.declarationNameOffset_nameRepeatedBeforeIt_findsTheDeclaredName`
- `LatheEngineNavigationTest.onName_wordsInCommentsAndStrings_falseButTrueOnANameAndAJavadocLink`
- `LatheMcpToolsTest.describeSymbol_positionNotOnAName_returnsErrorShowingTheLine_aNamedSymbolIsFound` (the 1-based answer)

---

## MC-25 — No workspace summary tool: an agent cannot ask which modules exist and what they depend on — done

**Status: done — Target: next**
Found by the agent-tooling landscape review (2026-10-24).

### Gap

gopls tells agents to call `go_workspace` first; IntelliJ's MCP server has `get_project_modules` and
`get_project_dependencies`.
Lathe holds the same facts in `.lathe/` — modules, source roots, inter-module dependencies, release
level, which modules have a captured test launch, the sync command — but an agent has to guess them
from POMs.

### Fix

- `workspace_summary` (no arguments) lists every reactor module with the reactor modules it depends
  on (those on its classpath, which with Maven include transitive ones) and, for a module with tests, whether its test launch is captured; the header names the
  sync command and its scoped form, the footer the command that captures a module's tests.
- The module graph now keeps the per-module dependencies it already computed from the classpaths
  (`WorkspaceModuleGraph.upstreamModuleDirs`); `LaunchReader.testCaptured` checks for the captured
  launch without reading it.
- The summary is built on the event loop, where session state lives, as a static core over
  `(configs, workspaceRoot, graph)` like the session's other module listings.
- A long dependency list names the first ten and counts the rest, as `verify_change` does; the
  structured result keeps every name.
- Left out: the Java release (a module may have none recorded, and diagnostics already reject syntax
  past it) and the build tool (the sync script runs the recorded one).

### Regression targets

- `WorkspaceModuleGraphTest.upstreamModuleDirs_chain_theModulesOnItsClasspath`
- `ChangeVerifierTest.moduleSummaries_mainAndTestTrees_hasTestsOnlyWhereATestTreeExists`
- `LatheEngineVerifyTest.modules_reactor_listsEachModuleWithItsUpstreamAndItsTestCapture`
- `McpTextTest.modulesResult_modules_oneLineEachWithUpstreamAndTestCapture_longListsCut`
- `LatheMcpToolsTest.workspaceSummary_emptyWorkspace_answersNoModulesNotAnError`

---

## MC-24 — No file outline tool: an agent reads a whole file to learn its structure — done

**Status: done — Target: next**
Found by the agent-tooling landscape review (2026-10-24).

### Gap

Claude Code's LSP tool (`documentSymbol`), Serena (`get_symbols_overview`,
[Serena tools](https://oraios.github.io/serena/01-about/035_tools.html)) and Java MCP servers such as
LSP4J-MCP (`document_symbols`) give an agent a file's members in a few hundred tokens.
Lathe answers `textDocument/documentSymbol` in the LSP but has no MCP tool for it.

### Fix

- `outline` answers from the editor's document-symbol request (`DocumentSymbolScanner`): every type,
  field, constructor and method in source order, indented by nesting, as
  `method sell  8: int sell(int count) {`.
- The scanner gives no signature, so each line carries the declaration's source line instead, as
  the other answers carry `path:line: code`; no Java is parsed for it.
- The path is in the header once, not on every line: a large type has dozens of members, and an
  agent pays for every repeated path.
- The kinds are the LSP symbol kinds the editor shows (a record is `struct`, its components
  `field`s).

### Regression targets

- `LatheEngineNavigationTest.outline_typeWithNestedType_listsEveryDeclarationAtItsDepthWithItsLine`
- `McpTextTest.outlineResult_nestedDeclarations_indentedLinesUnderOneHeader_orNoDeclarations`

---

## MC-23 — MCP cannot apply the fixes Lathe already computes (missing imports above all) — done

**Status: done — Target: next**
Found by the agent-tooling landscape review (2026-10-24).

### Gap

Missing imports are the most common error an agent makes in Java.
The LSP side computes the fixes (single-name import quick fix, whole-file `lathe.missingImports`
([CA-7](gaps.md#ca-7--pasting-code-with-unresolved-types-requires-per-symbol-quick-fixes-no-whole-file-add-missing-imports)),
add-throws), but MCP only reports the errors, so the agent guesses the import.
gopls's `go_diagnostics` returns the fixes with the diagnostics and tells the agent to apply them and
re-check ([gopls MCP instructions](https://github.com/golang/tools/blob/master/gopls/internal/mcp/instructions.md)).

### Fix

- `fix_imports` reads the file's unresolved type names and their candidates from the analysis behind
  the editor's "Add missing imports…" (`missingImportsFuture`), adds every name with exactly one
  candidate, and writes the result the way `rename_symbol` does.
- The added imports go in as one edit at the import insertion point
  (`MissingImportsResult.importEdit`, which the single-name quick fix now uses too): several edits at
  the same position apply in no defined order.
- The answer lists the imports added, each ambiguous name with its candidates
  (`List: java.util.List, java.awt.List`) for the agent to choose, and the names with none.
  The description points to `format_file` to put the imports in order.
- A failed analysis still answers "no import added", as the editor's command does; a file that does
  not compile already carries a `Not compiled:` note.
- Not done: listing the other available fixes (add-throws) beside `get_diagnostics` results; missing
  imports are the common case.

### Regression targets

- `LatheEngineEditTest.fixImports_mixedNames_addsTheUnambiguousOnes_leavesTheRestToTheAgent`
- `LatheEngineEditTest.fixImports_nothingMissing_writesNothing`
- `McpTextTest.importsResult_addedAmbiguousAndUnknown_listsEachKind_onlyTheAddedLineWhenNothingIsLeft`

---

## MC-22 — No MCP tool formats a file, so agent edits fail the project's formatting check — done

**Status: done — Target: next**
Found by the agent-tooling landscape review (2026-10-24).

### Gap

Spotless runs on `verify`, and an agent's edits rarely match google-java-format, so the build fails
after a correct change.
Lathe already runs the project's own pinned formatter in-process for the editor, but MCP has no tool
for it.
IntelliJ's MCP server has `reformat_file`
([IntelliJ MCP server](https://www.jetbrains.com/help/idea/mcp-server.html)).

### Fix

- `format_file` runs the formatter the project pins, the one the editor's format-on-save runs, and
  writes the result the way `rename_symbol` does: reactor files only, under the reactor lock,
  compiled into the mirror before it returns.
- The formatter choice moved out of `LatheLanguageServer` into `FormatEngines`, so the agent engine
  reads the same workspace style file (`LatheTextDocumentService.useWorkspaceFormatter`); the
  editor-only fallback from `initializationOptions` stays in the language server.
- The editor swallows a formatter failure into "no edits" (format-on-save must not nag); the agent
  path refuses instead, so a file that does not parse, or a workspace with no formatter (or one whose
  jars `lathe:sync` has not resolved), is an error, never "already formatted".
- The answer names the rewritten line range (`Formatted a/Foo.java: lines 6–8 changed.`): the
  formatter answers with one minimal edit, so an edit count would always be 1.
- No `files` list: one call per file keeps each answer short, and an agent formats what it edited.

### Regression targets

- `LatheEngineEditTest.format_pinnedFormatter_rewritesTheChangedLinesOnce_thenAlreadyFormatted`
- `LatheEngineEditTest.format_fileThatDoesNotParse_refusesWithTheReasonAndLeavesItAlone`
- `LatheEngineEditTest.format_noFormatter_namesTheStyleFile_unresolvedOne_namesTheSync`
- `LatheMcpToolsTest.formatFile_noFormatterPinned_returnsErrorWithTheRemedy`

---

## MC-21 — MCP tools declare no annotations, so clients cannot tell the read-only tools from the one that writes — done

**Status: done — Target: next**
Found by the agent-tooling landscape review (2026-10-24); see
[the landscape update](../planned/lathe-ai-agent-integration.md#landscape-update-oct-2026).

### Gap

The MCP spec (2025-03-26 onward) lets a tool declare `annotations`: `readOnlyHint`,
`destructiveHint`, `idempotentHint`, `openWorldHint`
([tools spec](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)).
Lathe registers its tools with none.
Reports say Claude Code's plan mode allows only MCP tools that declare `readOnlyHint` and runs them in
parallel, and that Codex auto-approves read-only tools; neither is confirmed against official docs yet.

### Fix

`LatheMcpTools` registers every tool with one of three annotation constants:
the read tools (diagnostics, definition, references, implementations, call hierarchy, describe,
search, analyze_change, verify_change) are `readOnlyHint`; `rename_symbol` writes and is
`destructiveHint`; `run_test` is neither, since it runs code but writes no source.
Its `destructiveHint` is set to `false` explicitly, because the spec reads an unset one as `true` on a
tool that is not read-only.
One `spec` helper now registers each tool and logs its calls under the tool's own name.
The guide states only what the tools declare, not what a client does with it, since that is
still unconfirmed.

### Regression targets

- `LatheMcpToolsTest.all_everyTool_declaresWhetherItWrites`

---

## MC-3 — MCP server logs are not observable: the client persists only startup stderr; the server has no durable log sink — done

**Status: done — Target: next**

Signal: attempting to read the server's per-call logs to diagnose [MC-1](#mc-1--describe_symbol-and-position-tools-return-empty-on-dependencyjdk-source-files-the-mcp-warmup-skips-the-external-compile-route--done)
— the logs were nowhere to be found despite the server emitting them.

### Observed behaviour

The server emits one `INFO` line per tool call to stderr, but Claude Code persists only the stderr of
the connection handshake (`~/.cache/claude-cli-nodejs/<project>/mcp-logs-<server>/*.jsonl`, and
`~/.claude/debug/<session>.txt` under `claude --debug=mcp`), so every later server line was lost.
A request for per-server logs is open upstream and marked "not planned"
(anthropics/claude-code#29035).

### Fix

- **File sink** (shipped earlier): `LatheMcpServer.main` attaches an appending JUL `FileHandler`
  (`LatheLogging.initFile`) writing `~/.cache/lathe/logs/mcp-<start-timestamp>-<pid>.log`
  (`LatheLayout.mcpSessionLog`), one file per server process, beside the `ConsoleHandler`.
- **Retention:** a starting server keeps the newest 100 session logs and deletes the rest
  (`FileUtil.deleteAllButLast`; the names start with the start time, so name order is age order).
  On 2026-10-22 the directory had accrued 612 files. A session log at INFO averages a few kilobytes,
  so no per-file cap. It also deletes the `.lck` files JUL's `FileHandler` leaves behind when a server
  is killed outright (286 of them by 2026-10-24), once their log is gone.
- **Timestamps:** `logging.properties` formats ISO-8601 with the zone offset
  (`2026-10-24T16:21:40.134+0200`), so lines correlate with a client's UTC logs; the editor's server
  log gains it too.
- **Discoverability:** the startup line names the file (`[startup] pid=… cwd=… log=<path>`), so the
  one stderr burst the client keeps points at the full log.
- **Won't do:** a `LATHE_LOG_DIR` override; nobody asked for it, and the startup line gives the path.

### Regression targets

- `LatheMcpServerTest.deleteAllButLast_sessionLogs_keepsTheNewestAndLeavesOtherFilesAlone`
- `LatheMcpServerTest.isOrphanLock_sessionLocks_trueOnlyForALockWhoseLogIsGone`
- `LatheLoggingTest.logFormat_record_isoTimestampWithTheZoneOffset`
- `LatheLoggingTest.initFile_missingParentDir_createsItAndWritesRecord`

---

## MC-20 — `verify_change` lists each diagnostic under its module, not its file — done

**Status: done — Target: next**
Found while reviewing the shorter MCP answers (2026-10-24) on Dropwizard.

### Observed behaviour

`verify_change` grouped diagnostics by module, each as `Warning [758:9] message`: an agent could not
tell which file line 758 was in.

### Fix

The engine pairs each diagnostic with its file (`LatheDiagnostic`, the path as on a location), and the
text lists them on one grep-style line each, errors first across all modules:
`dropwizard-jetty/src/main/java/…/HttpConnectorFactory.java:758:9: Warning: …`.
The structured result keeps `perModule` and gives each diagnostic its `path`.

### Regression targets

- `LatheEngineVerifyTest.verifyChange_externalEditBreaksSibling_reportsDiagnostic` (the caller's path)
- `McpTextTest.locatedDiagnosticLine_warning_grepStyleWithPathLineAndColumn`

---

## MC-11 — `run_test` does not give the remedy when no test launch was captured — done

**Status: done — Target: next**

### Observed behaviour

For a module whose tests never ran under Lathe, `run_test` answered
"Test run blocked: no captured test-launch.json for equalsverifier-core", without the command its own
description promises.

### Fix

The three places that block on a missing test launch (a test run, a test debug, a main class in test
scope) share one message, which names the command:
"no captured test launch for M; run `.lathe/lathe-sync.sh --tests M` to capture it".
The editor's run and debug messages carry it too.

### Regression targets

- `LatheTextDocumentServiceTest.runTestFuture_moduleWithoutACapturedLaunch_blockedWithTheCommandThatCapturesIt`

---

## MC-9 — `get_diagnostics` says "compiles cleanly" for files it never compiled — done

**Status: done — Target: next**

### Observed behaviour

On EqualsVerifier (JDK 17):

| `file` | Answer |
|---|---|
| `/etc/hosts` | "No diagnostics — hosts compiles cleanly." + "Sync required: ../../../etc/hosts is in no synced module — run `.lathe/lathe-sync.sh`" |
| `pom.xml` | "No diagnostics — pom.xml compiles cleanly." + the same sync hint |
| a Java file in `equalsverifier-21` (a module the JDK 17 build excludes by profile) | "No diagnostics — … compiles cleanly." + "run `.lathe/lathe-sync.sh`" |
| a missing `.java` file | a raw `java.nio.file.NoSuchFileException` |

The headline claimed a compile that never happened, and for the JDK-gated module the suggested sync
could never include it.
On Helidon a new public class answered "compiles cleanly" while its write failed on Helidon's
API-stability annotation processor, which the analysis behind the headline does not run.

### Fix

- The file tools (`get_diagnostics`, the position tools, `run_test`) take only an existing `.java`
  file: "pom.xml is not a .java file", "Foo.java does not exist".
- A file no module compiles gets its own note, `Not checked:`, instead of a `Sync required:` line.
  It advises the sync only for a file of a module the last build compiled (the manifest's outputs,
  `WorkspaceManifest.inBuiltModule`); outside them it says Lathe cannot check the file, since no sync
  would. A changed build file keeps its own sync note, so a new module still gets the advice.
- `get_diagnostics` answers "Foo.java was not checked." for such a file, and otherwise "No errors or
  warnings in Foo.java." instead of "compiles cleanly": the analysis is not the write, whose failure
  the `Not compiled:` note reports.

### Regression targets

- `LatheEngineNotesTest.notes_fileNoModuleCompiles_notCheckedWithItsCause`
- `LatheMcpToolsTest.fileTools_notAnExistingJavaSource_returnError`
- `LatheMcpToolsTest.getDiagnostics_fileNoModuleCompiles_saysItWasNotChecked`

---

## MC-10 — Position tools answer for an unrelated symbol when the position is not on one — done

**Status: done — Target: next**

### Observed behaviour

`describe_symbol` in `EqualsVerifier.java`, on the line declaring `forClass`:

| Position | Answer |
|---|---|
| column past the end of the line | the enclosing class `EqualsVerifier` |
| column 0, a whitespace column, or the keyword `public` | the method `forClass` |
| a line past the end of the file | "No symbol information at that position." |

An agent with an off-by-one column got a confident answer about the wrong symbol.

### Root cause

javac answers for the smallest tree around a position: on a method's modifiers or indentation that is
the method's declaration, and a column past a line's end spills into the next line, whose smallest
enclosing tree was the class.
`SourceLocator.pathAt` must keep doing that: code actions and the editor rely on positions that are not
on a name.

### Fix

Every MCP position tool goes through `LatheMcpTools.atPosition`, which now first asks
`LatheEngine.onName`: the line and column exist, the character there is part of a Java identifier, and
the word around it starts like one and is not a keyword.
Otherwise the tool answers with an error that shows the line, so the agent can correct its column:
"No symbol at line 120, column 5: the position must be on a name. Line 120 reads: …".
It is a text check of one word on one line, since javac's trees give a declaration's span, not its
name's.
A name inside a comment still passes, since a Javadoc `{@link}` is a position the server resolves.
LSP requests are unchanged: an editor's cursor is deliberate, and IntelliJ too renames a method with
the caret on its modifiers.

### Regression targets

- `LatheEngineNavigationTest.onName_positionsAroundAMethod_trueOnlyOnANameNotAKeywordBlankOrPastTheEnd`
- `LatheMcpToolsTest.describeSymbol_positionNotOnAName_returnsErrorShowingTheLine_aNamedSymbolIsFound`

---

## MC-12 — `search_symbols` finds nothing for a fully qualified name — done

**Status: done — Target: next**

### Observed behaviour

`search_symbols` with `nl.jqno.equalsverifier.EqualsVerifier` answered "No symbols matching", while
`EqualsVerifier` found it.
Agents often search by the qualified name they see in imports and stack traces.

### Fix

`WorkspaceSymbolResolver` splits a query with a dot at its last dot: the part after it is the
simple-name prefix, searched as before, and only the types whose container (the package, or the
enclosing type of a nested one) is the part before it, or ends with it, are kept.
So `nl.jqno.equalsverifier.EqualsVerifier`, `util.Duration`, `Map.Entry` and `java.util.Lis` all work.
The narrowing runs before the result cap, so a common name like `java.util.List` is not crowded out
by its `List*` siblings.
The resolver also serves the LSP `workspace/symbol` request, so an editor's symbol search gains it too.
A query without a dot behaves as before.

### Regression targets

- `WorkspaceSymbolTest.resolve_qualifiedName_matchesOnlyTypesDeclaredInThatPackageOrType`

---

## MC-18 — `search_symbols` and `find_references` answers run to hundreds of lines — done

**Status: done — Target: next**
Found by the MCP-quality probes (2026-10-24) on Dropwizard.

### Observed behaviour

`search_symbols "Duration"` returned 50 symbols with a source snippet of up to seven lines each: 388 lines to
find one class.
`find_references` showed the same snippet per reference, about 350 lines for
`Duration.toMilliseconds`' 52 uses.
Every line costs an agent context.

### Fix

Every located result is one line in grep's form, `path:line: code`, which agents already read.
The engine's snippet is the target line alone, and `LatheLocation` and `LatheFileEdit` carry a
`path` beside the URI: workspace-relative for reactor and generated files, the URI for a dependency or
the JDK, which have no workspace path.
Only results outside the reactor are tagged (`[EXTERNAL]`, `[GENERATED]`).
A symbol is its name, kind and container followed by `path:line`; a rename lists each file by its path.
This covers references, implementations, definitions, call hierarchies and the impact analysis's
override family alike, since all of them render through one line.
An agent that needs the surrounding code reads the file at that line.

### Regression targets

- `LatheEngineNavigationTest.definition_crossFileInModule_resolvesTargetWithItsWorkspacePathAndLine`
- `McpTextTest.locationLine_reactorAndExternal_oneGrepStyleLineTaggedOnlyOutsideTheReactor`

---

## MC-17 — `verify_change` reports unchanged files' old warnings as results, and does not lead with errors — done

**Status: done — Target: next**
Found by the MCP-quality probes (2026-10-24) on Dropwizard and Helidon.

### Observed behaviour

- After a correct rename of `Duration.toMilliseconds` on Dropwizard, `verify_change` answered
  "5 diagnostic(s) after recompile", all pre-existing Jetty deprecation warnings in callers the rename
  did not create.
- After a deliberate type error, it answered "6 diagnostic(s)" with the one error listed between those
  warnings; the headline did not say "1 error".
- On Helidon every call, a no-op included, answered "7 diagnostic(s)": `unchecked` warnings in callers.

An agent reads the count as something it caused and must fix.

### Fix

`LatheEngine.diagnose` keeps errors and warnings for the changed files but only errors for the
callers checked for breakage: a caller's warnings were there before the change.
Errors come first.
The text leads with the count that matters: "No errors — the changed files and their callers
compile." or "N error(s).", plus "M warning(s) in the changed files" when there are any.

### Regression targets

- `LatheEngineVerifyTest.verifyChange_callerWithAWarningAndAHint_reportsTheChangedFilesWarningAfterErrors`
  (fails without the filter or the ordering)
- `McpTextTest.verifyHeadline_errorsAndWarnings_leadsWithErrors`

---

## MC-19 — `verify_change`'s sync suggestion reads as required even when every caller was checked — done

**Status: done — Target: next**
Found by the MCP-quality probes (2026-10-24) on Dropwizard.

### Observed behaviour

After fixing one caller in `dropwizard-core`, with every caller checked clean, `verify_change` ended
with "18 module(s) depend on your change …; to build them fully, run: `.lathe/lathe-sync.sh` …".
Agents tend to run the commands they are given, so a clean check still cost a long build.

### Fix

The cross-module note now depends on the result.
With no errors: "their callers compile, so a full build is optional: `<sync>`".
With errors: "fix the errors and re-check, or build them with: `<sync>`".
A deferred check (a pending build-file sync, a running build) still asks for the sync, as before.

### Regression targets

- `McpTextTest.crossModuleNote_dependents_namesTheFirstTenAndSaysWhetherTheBuildIsNeeded`

---

## MC-16 — `verify_change` has no record of what the build compiled for modules Maven left untouched — done

**Status: done — Target: next**
Found by the MCP-quality probes (2026-10-24) on Helidon.

### Observed behaviour

On Helidon, `common/common` had no `built` entries in its compile stamps: the last sync found the
module up to date, so Maven did not compile it and the compiler shim, which records `built`, never ran
there.
Without that record a source only touched (same content) read as a change: `verify_change` named 211
dependent modules and suggested a full sync, and a full revert there still read as a change.

### Fix

At the end of a build the extension (`LatheLifecycleParticipant.recordBuilt`) records `built` for each
source tree of every module the build completed successfully and that has no such record yet
(`CompiledStamps.recordBuiltIfMissing`).
Maven compiled nothing there because every source was older than its classes, so each source's
current content is what was compiled.
Its mtimes stay; a tree that already has a record is left alone, and a module outside the build or one
that failed is never touched.
Like the late-output top-up, it is best-effort: a failure is logged at debug level and never fails the
build.
`CompileCapture` and the hook list a tree's sources the same way (`CompiledStamps.sourcesByKey`).

### Regression targets

- `LatheLifecycleParticipantTest.recordBuilt_moduleWithoutABuiltRecord_recordsItsSourcesAndKeepsAnExistingRecord`

---

## MC-13 — `verify_change` still advises a sync after a change has been fully reverted — done

**Status: done — Target: next**

### Observed behaviour

After a change to `EqualsVerifier.java` was reverted on disk, so the tree matched `HEAD` again,
`verify_change` still answered "3 module(s) depend on your change …; verify them with:
`.lathe/lathe-sync.sh equalsverifier-core`".
On a large reactor that sends an agent into a needless Maven run.

Since the [MC-7](gaps-archive.md#mc-7--verify_change-reports-a-clean-tree-while-dependent-modules-are-broken--done)
fix the pass records every file it writes, so the reverting writes are reported too: a full revert
now always reads as a change with dependents to build (seen on EqualsVerifier, 2026-10-24).

### Fix

The compile stamps record, beside each source's mtime, the content fingerprint (size plus CRC32C) the
build last compiled (`CompiledStampsData.built`, written by `CompileCapture` on every Maven compile;
the server's own writes leave it). `verify_change` drops any changed file whose content is back to
that (`WorkspaceSession.differingFromBuild`), so a full revert reports nothing and suggests no sync;
a deleted file stays, as its callers break. The OpenJDK front-end records no built content, so there
a revert still reads as a change.

### Regression targets

- `LatheEngineVerifyTest.verifyChange_changeFullyReverted_reportsNothingAndSuggestsNoSync` (fails without
  the filter)
- `CompiledStampsTest.writeAllThenLoad_roundTripsAndRecordUpdatesStampsButKeepsWhatTheBuildCompiled`

---

## MC-7 — `verify_change` reports a clean tree while dependent modules are broken — done

**Status: done — Target: next**

### Observed behaviour

On EqualsVerifier (JDK 17), `forPackage` was renamed on disk in `EqualsVerifier.java` only, so core
still compiles and `equalsverifier-test` breaks.
`verify_change` with no arguments answered "No new diagnostics — the changed files compile cleanly",
plus a sync suggestion for the dependent modules.
`get_diagnostics` on a dependent test file (`MultipleTypeEqualsVerifierTest.java`) immediately
returned 26 errors (`cannot find symbol … method forPackage(java.lang.String)`), so the server can see
the breakage in-process.
An agent that trusts the headline concludes its change is safe.

Reproduced on all three test workspaces by the external-change staleness probe (2026-10-24):
an upstream method renamed outside the server, with a caller in another module.
`get_diagnostics` on the caller reported `cannot find symbol`, then `verify_change` with no arguments
answered "No new diagnostics — the changed files compile cleanly".
MCP only: in the editor an open caller in the dependent module republished the `cannot find symbol`
within 3–4 s of the same external change.

### Fix

`verify_change` now also checks the callers of the changed files in dependent modules: the files that
mention a changed type (the reactor-wide candidate index) in its module or one downstream of it
(`WorkspaceSession.verifyTargetsByModule`), the changed files' modules first.
It checks every caller, uncapped, and logs each call (`[verify] <files> file(s) in <modules>
module(s) <ms>ms diags=<n>`), so a costly one shows in the log rather than being cut short; a broken
`MediaTypes.java` on Helidon, with 142 dependent modules, took about 12 s.
It freshens once and then analyzes each target without a pass of its own (`LatheEngine.analyze`).

With no `files`, it now also sees changes an earlier query already compiled: the pass records every
source it writes or whose classes it drops (design rule 8), not only the files the tools wrote, so a
deleted type's callers are checked too; a file that no longer exists is not itself diagnosed.

Verified on EqualsVerifier: the probe's upstream rename, after a query had compiled it, is reported
in `equalsverifier-test`.

### Regression targets

- `LatheEngineVerifyTest.verifyChange_apiChangeBreaksDependentModule_reportedAlsoAfterAnotherQueryCompiledIt`
  (fails with same-module callers only, and without the pass recording its writes)
- `McpTextTest.crossModuleNote_dependents_namesTheFirstTenAndSaysWhetherTheBuildIsNeeded`

---

## MC-8 — The sync command `verify_change` suggests does not build the dependent modules it names — done

**Status: done — Target: next**

### Observed behaviour

`verify_change` says, for example, "3 module(s) depend on your change (equalsverifier-test,
equalsverifier-test-mockito, equalsverifier-test-signedjar); verify them with:
`.lathe/lathe-sync.sh equalsverifier-core`".
After a type rename it named `equalsverifier-test-signedjar` but suggested
`.lathe/lathe-sync.sh equalsverifier-core equalsverifier-test equalsverifier-test-mockito`.
`lathe-sync.sh` scopes with `-pl <module> -am`, which builds the listed modules and their *upstream*
dependencies only, so the dependents the message names are never built.
Running the suggested command built parent, testhelpers and core only.

### Fix

The suggestion names the dependent modules (`LatheEngine.syncFor`), so `-pl … -am` builds them and
everything they depend on, the changed modules included.
When the dependents are more than half the reactor's modules it is the unscoped
`.lathe/lathe-sync.sh` instead: that is nearly a full build anyway, and a core module's list (Helidon's
`common/media-type` alone has 142 dependents) heads toward the 8,191-character Windows command line.

History, so the question does not come back: the script first scoped with `-am -amd`, which broke on
Helidon (ef6391a0): `-amd` adds the dependents but not their own upstream, so a dependent whose other
dependencies were outside the selection failed to build. The script has been `-am`-only since, and
stays so: a caller that needs the dependents built names them, as `verify_change` now does. The
script and the shared model are unchanged, so a Gradle front-end (its `--module` options build each
module's upstream through task dependencies) gets the same behaviour.

### Regression targets

- `LatheEngineVerifyTest.verifyChange_apiChangeBreaksDependentModule_reportedAlsoAfterAnotherQueryCompiledIt`
  (asserts `.lathe/lathe-sync.sh app` for a change in `api`)
- `LatheEngineVerifyTest.verifyChange_twoOfSixModulesUseTheChange_suggestsTheScopedSyncOfThoseDependents`
- `LatheEngineVerifyTest.verifyChange_moreThanHalfTheReactorDepends_suggestsTheFullSync`

---

## MC-15 — `search_symbols` and `find_implementations` miss a type added outside for a few seconds — done

**Status: done — Target: next.**
**Affects: MCP only.**
Found by the external-change staleness probe (2026-10-24) on EqualsVerifier, Dropwizard, and Helidon.

### Observed behaviour

A new type file was written outside the server, then queried at once:

| Query | First answer | 3 s later |
|---|---|---|
| `search_symbols LatheProbeType` | "No symbols matching" | found |
| `search_symbols` for a type just renamed by renaming its file | "No symbols matching" | found |
| `find_implementations` on an interface, with a new implementing class | the old list | includes it |

The pass had already written the class into the mirror (and `get_diagnostics`, `get_definition`,
and `find_references` saw it at once), so only these two tools lag.
An agent that creates a type and searches for it right away is told it does not exist.

The editor server lags the same way, but there it is expected: `workspace/symbol` and
`textDocument/implementation` caught up within 3–4 s of the external write, the latency of the
reconcile tick that every external change has in the editor (an open dependent also took 3–8 s to
republish).
It is a gap only for MCP, which promises that answers reflect the disk at query time.

### Cause

The [one-pass design](../done/lathe-recompile-simplification.md#threads-and-state) rebuilds the
reactor type index after the pass on a pooled thread; the engine waits for the pass
(`reconcileForVerify`) but not for that rebuild.

### Fix

`rebuildReactorIndex` keeps a future (`reactorIndexRebuilt`) completed on the session thread once its
rebuild is applied or superseded, and the engine's freshen step (`reconcileForVerify`) waits for it
after the pass.
The wait is chained through the session thread (`thenComposeAsync(…, worker::execute)`): a pass that
ran as another's follow-up completes the caller's future before its own `endPass`, which starts the
rebuild, so reading the future right in that callback would see the previous one.
The failure was deterministic, not a narrow race: the probe's "3 s" was its retry interval.
The editor keeps the non-blocking rebuild.

### Regression targets

Fails without the wait:

- `LatheEngineNavigationTest.searchSymbolsAndFindImplementations_typeAddedOnDiskMidSession_foundByTheNextQuery`

---

## MC-14 — A changed build file never yields "Sync required" — done

**Status: done — Target: next.**
**Affects: MCP only.** The editor raises its sync prompt for the same edit within 0.5 s.
Found by the external-change staleness probe (2026-10-24) on EqualsVerifier, Dropwizard, and Helidon.

### Observed behaviour

A module `pom.xml` was edited during a session, then `get_diagnostics` was called on an unrelated
file, again after 3 s and 8 s.
None of the answers carried the `Sync required: build files changed — run .lathe/lathe-sync.sh` note
that the [one-pass design](../done/lathe-recompile-simplification.md#mcp-fix-it-or-say-exactly-what-is-needed)
and `status.md` promise; the answers silently came from the mirror built for the old POM.

### Cause

The server detects the change: the session log has `[watcher] build files changed — sync needed`.
`promptForSync` sets `buildFilesNotificationPending` and asks the client, but the engine's
`NoopLanguageClient.showMessageRequest` answers `null` at once, and `onSyncPromptResponse` clears
`buildFilesNotificationPending` in the same millisecond (`[sync] answered action=null`).
`reconcileForVerify` therefore never returns `BUILD_FILES_PENDING`.
A build file changed before the session started is not detected at all ([WS-13](#ws-13--a-build-file-changed-while-no-server-ran-is-never-detected--done)).

### Fix

`NoopLanguageClient.showMessageRequest` leaves the prompt unanswered, so
`buildFilesNotificationPending` holds until a sync reloads the workspace (`reloadWorkspace` clears
it), and every answer carries the note until then — or, since the build files are compared by
content ([WS-13](#ws-13--a-build-file-changed-while-no-server-ran-is-never-detected--done)), until
every changed POM is back to what the sync saw.
The engine's freshen step (`reconcileForVerify`) now runs the watcher's check first, so a build file
the agent edited just before the query is reported by that query, not on the next tick.
While the sync is pending the pass still runs, so the agent's own edits keep being compiled;
the outcome only carries `BUILD_FILES_PENDING`, on which `verify_change` still defers to the sync.
A build file changed before the session started is [WS-13](#ws-13--a-build-file-changed-while-no-server-ran-is-never-detected--done).

### Regression targets

Fails with the old immediate `null` answer:

- `LatheEngineNotesTest.notes_buildFileEditedMidSession_syncRequiredUntilItsContentIsBack_editsStillCompile`

---

## MC-2 — Non-lathe (MCP SDK / Reactor) logs are silently dropped: no SLF4J provider on the server classpath — done

**Status: done — fixed in `417f296d` (added `slf4j-jdk14` runtime provider to `lathe-mcp-server`).**

Signal: every MCP server start prints an SLF4J NOP warning to stderr (visible in the client's
startup-stderr capture).

### Observed behaviour

The server logs at startup:

```
SLF4J(W): No SLF4J providers were found.
SLF4J(W): Defaulting to no-operation (NOP) logger implementation
SLF4J(W): See https://www.slf4j.org/codes.html#noProviders for further details.
```

`slf4j-api-2.0.16.jar` is on the runtime classpath (pulled transitively by
`io.modelcontextprotocol.sdk:mcp` and `io.projectreactor:reactor-core`), but **no SLF4J provider**
is bound (`slf4j-jdk14`/`logback`/`slf4j-simple` all absent). SLF4J falls back to NOP, so every log
line those libraries emit — including protocol-level warnings and errors from the MCP SDK and Reactor
— is discarded.

### Root cause

`lathe-mcp-server` declares no SLF4J provider. lathe's own code logs through JUL, which is
independent of SLF4J — that is why lathe's logs work while the third-party SLF4J logs vanish.

### Resolution

Added the SLF4J→JUL bridge so non-lathe logs route into the same JUL configuration lathe already
uses, pinned to the BOM-resolved `slf4j-api` version:

```xml
<dependency>
  <groupId>org.slf4j</groupId>
  <artifactId>slf4j-jdk14</artifactId>
  <version>2.0.16</version>
  <scope>runtime</scope>
</dependency>
```

`logging.properties` keeps non-lathe loggers at `.level=WARNING`, so this stays quiet by default but
finally lets real SDK/Reactor warnings and errors surface. Safe for the stdio protocol: JUL's
`ConsoleHandler` writes to `System.err`, never the JSON-RPC `stdout` channel. (`slf4j-nop` would only
mute the warning while keeping the logs suppressed — rejected.)

### Regression targets

Intentionally skipped — this is a dependency-only change with no lathe code to exercise; a startup
smoke assertion (`LatheMcpServerTest.startup_bindsSlf4jProvider_noNopWarning`) was discussed and
deliberately not added.

---

## MC-4 — `verify_change` with no files reports a clean tree after a tool's own write broke dependents — done

**Status: done — Target: next** (commit 00f06ae8).

Signal: the same release smoke as [FR-018](#fr-018--references-miss-uses-written-to-disk-outside-the-editor-the-candidate-index-never-re-reads-a-changed-file--done).

### Observed behaviour

After the broken rename-back of FR-018 (Dropwizard: the declaration renamed back, 14 files still
calling the old name), `verify_change` with no `files` answers "No new diagnostics — the changed
files compile cleanly." in about 10 ms.
Passing the 14 dirty files explicitly reports 72 errors.

### Root cause

With no `files`, `verify_change` checks `ReconcileOutcome.reacted()`: the stale sources that its
own freshen pass found.
`rename_symbol` has already written and stamped every file it edited (`writeAndCommit` →
`writeReported`), so nothing is stale when `verify_change` runs, and it checks nothing.
Even with the changed file named, the check covers that file and its same-module referrers;
dependents in other modules are only listed as "N module(s) depend on your change", with a
sync command.
The rename's own pass recompiled only the file it edited, so the broken callers in other modules were
never compiled at all.

### Fix

FR-018 removed the rename-back occurrence: a rename back now edits every caller.
What remained: a tool's write that compiles itself, and so is stamped, but breaks a same-module file
the tool did not edit.
A subclass declaring `int welcome()` stops compiling when `greet()` is renamed to `welcome()` in its
superclass, yet it has no use of `greet()`, so the rename never touches it.
A no-argument `verify_change` then found nothing stale and checked nothing.

The session now remembers the files written on report (`writeReported`) since a `verify_change` last
took them, and a no-argument `verify_change` adds them to the change set, so their same-module
referrers are compiled too.
`verify_change` takes the set itself (`takeWrittenSinceVerify`): `reconcileForVerify` runs at the
start of every engine call, so consuming it there would let a query between the write and the
verify swallow it.
The set lives on `lathe-worker` like all session state.

### Regression targets

- `LatheEngineVerifyTest.verifyChange_noFilesAfterARenameThatBreaksAnUneditedSubclass_reportsIt` (fails
  without the fix).

---

## MC-5 — `verify_change` counts unused-code hints as diagnostics after recompile — done

**Status: done — Target: next** (PR #36).

### Observed behaviour

On EqualsVerifier, `verify_change` for an unmodified `Warning.java` answers "11 diagnostic(s) after
recompile", all of them `Hint`s ("Unused private field …", "Unused exception parameter 'e'") in
same-module files it pulled in as referrers.
On Helidon, the same check on an unmodified `MediaType.java` reports "1 diagnostic(s)":
`Hint [46:50] Unused parameter 'it'`.
An agent reads the count as something to fix, though nothing changed and nothing is wrong.

### Fix

`LatheEngine.diagnose`, which every `verify_change` check goes through, drops `Hint` diagnostics, so
the count, the text and the structured result agree.
Warnings stay: a project compiled with `-Werror` fails on them.
`get_diagnostics` on a single file still shows hints.

The same review capped the "N module(s) depend on your change" list in the text at ten names plus
"and N more" (Helidon's `common/media-type` names 142); the structured result keeps every name.

### Regression targets

- `LatheEngineVerifyTest.verifyChange_callerWithAWarningAndAHint_reportsTheChangedFilesWarningAfterErrors` (fails without the filter); the
  error side stays covered by `verifyChange_externalEditBreaksSibling_reportsDiagnostic`.
- `McpTextTest.crossModuleNote_dependents_namesTheFirstTenAndSaysWhetherTheBuildIsNeeded`.

---

## MC-6 — A file an MCP tool touched stays a frozen open document for the rest of the session — done

**Status: done — Target: next** (PR #35).

Signal: verifying the [FR-018](#fr-018--references-miss-uses-written-to-disk-outside-the-editor-the-candidate-index-never-re-reads-a-changed-file--done)
fix with the MCP battery: the rename round-trip still left one to three files dirty per workspace.

### Observed behaviour

With the FR-018 fix, on Dropwizard:

- `run_test` on `DurationTest.java`, then a rename round-trip of `Duration.toMilliseconds`: the
  rename writes 52 edits across 15 files, the rename back only 51 across 14, and `DurationTest.java`
  is left calling the renamed method.
- `get_diagnostics` on `Jackson.java`, then an external edit that adds a `toMilliseconds()` call to
  it: `find_references` never lists `Jackson.java`.

Without any earlier tool call touching those files, both cases work.

### Root cause

`LatheEngine.compileFromDisk` registers its target through `diagnosticsFuture`, which `docs.put`s the
file's current disk content as an open document, and nothing ever closes it.
From then on the session treats it as an editor buffer: the reference search reads the document
instead of the disk (`openFile == null ? readDiskCandidate(uri)…`), and the FR-018 sync keeps the
document's tokens.
Only a later tool call aimed at that same file refreshes it.
Every file an agent has queried therefore answers from the text it had at that moment.

### Fix direction

Options, to decide before coding:

1. **Release after each call** (preferred): the engine closes the documents it registered once
   the tool call ends, so the disk stays the only truth.
   The cost is small: `compileFromDisk` already recompiles its target on every call.
   If tool calls can run concurrently, count registrations per file and close a file only when its
   last user is done.
2. **Disk-backed documents mode:** a session flag set by the engine that makes the pass re-read
   registered documents whose mtime moved.
   This keeps the analysis cache warm, but adds a second document lifecycle to `WorkspaceSession`.
3. Re-read every registered document before each call: the set grows with every file touched.

Only option 1 would also have given the eviction that [MC-1](#mc-1--describe_symbol-and-position-tools-return-empty-on-dependencyjdk-source-files-the-mcp-warmup-skips-the-external-compile-route--done)
suggests after a warmed external file; that stays with MC-1.

### Fix

Neither option 1 nor 3: a session flag, close to option 2.
Option 1 was built first and dropped in review.
MCP tool calls really do overlap (mcp-core dispatches requests through `Flux.flatMap` and runs sync
tools on `Schedulers.boundedElastic`), so releasing documents after each call needed a per-file user
count shared across those threads, plus a private twin of every engine method.

Instead, `LatheEngine` turns on `setDocumentsFollowDisk(true)`.
The session then treats its documents as copies of the disk:

- `diagnosticsFuture` indexes its file as a disk file, recording its mtime.
- In each pass the candidate sync no longer skips open documents, and every document whose file it
  re-read is replaced with the file's content as a new generation.
- Every engine call starts with a pass (`reconcileForVerify`), so it answers from the current disk,
  including after the engine's own rename writes.

All of this state is on the session's single `lathe-worker` thread, so it needs no synchronization.
A query already running keeps the immutable `OpenDocument` snapshot it took.
Editor sessions never set the flag, so editor buffers are untouched.

### Regression targets

Both fail with the flag off:

- `LatheEngineRenameTest.rename_thenRenameBack_afterAQueryOnTheCaller_restoresTheCaller`
- `LatheEngineNavigationTest.references_queriedFileThenEditedOnDisk_findsTheNewUse`

---

## MC-1 — `describe_symbol` (and position tools) return empty on dependency/JDK source files: the MCP warmup skips the External compile route — done

**Status: done — Target: next** (on `main`).

Signal: dogfooding `describe_symbol` while designing [EG-003](gaps-archive.md#eg-003--hover-returns-null-on-positions-inside-javadoc-type-reference-tags--done).
Pointing it at a javac `DocTrees` method to confirm an API returned nothing; the same symbol
described from a use-site in reactor code returned the full signature + javadoc.

### Observed behaviour

`describe_symbol` aimed at a symbol **in a dependency or JDK source file** (e.g.
`~/.cache/lathe/jdks/…/jdk.compiler/com/sun/source/util/DocTrees.java`) returns empty
`{"markup":""}` — on both declarations and references. The identical symbol described from a
**use-site inside a reactor file** (a `DocTrees` reference in `JavadocLocator.java`) resolves fully:

```
DocCommentTree getDocCommentTree(TreePath path)
Returns the doc comment tree, if any, for the Tree node identified by a given TreePath. …
source: jdk.compiler (JDK 26)
```

Crucially, **hovering directly inside the same JDK/dep source file works in the editor**, so this is
an MCP-facade discrepancy, not an inherent "external sources can't be attributed" limitation and not
a staleness issue.

### Root cause (confirmed by trace)

Every position-based engine tool calls `LatheEngine.compileFromDisk(file)` to register and warm the
file before issuing the LSP request. `compileFromDisk` routes through
`WorkspaceSession.diagnosticsFuture`, which switches on `routeCompiler(uri)`:

- `CompilerRoute.Module` → `docs.put(...)` + `submitCompile(...)` — the file is registered and
  attributed, so the later `hover` finds a cached analysis.
- `CompilerRoute.External` (dep/JDK sources tracked by the manifest) → short-circuits to
  `CompletableFuture.completedFuture(List.of())` — **no `docs.put`, no compile**.

So the external file is never attributed, and `service.hover(...)` returns null → empty markup. The
editor path (`WorkspaceSession.onOpen`) compiles **unconditionally** via
`workspace.externalWorker()`, which is why the same hover works in the editor. `External` genuinely
has a compiling worker (`routeCompiler` maps it to `externalWorker()`), so attribution is available —
the warmup just doesn't use it.

### Fix direction (small)

Give the `External` route the same register-and-compile the `Module` route already gets in
`diagnosticsFuture`, using `external.worker()`:

```java
case CompilerRoute.External external -> {
  final var snapshot = docs.put(uri, content, version);
  candidateIndex.update(uri, content);
  final var result = new CompletableFuture<List<Diagnostic>>();
  submitCompile(
      external, snapshot, CompileMode.OPEN, (ignored, response) -> result.complete(response.diagnostics()));
  yield result;
}
```

`submitCompile` already accepts a `CompilerRoute` (`onSave` passes an `External` route through it), so
this is localized — external files attribute and cache exactly as the editor's `onOpen` does, and
every position tool that pre-warms via `compileFromDisk` benefits (`describe_symbol`,
`get_definition`/`get_diagnostics` on external paths). The `Missing` route stays empty. Consider
evicting the warmed external document after the one-shot engine call so read-only dep/JDK files do not
accumulate in the open-document set.

### Probe commands

```bash
# Via MCP (returns empty today; expected: rendered signature + javadoc):
#   describe_symbol file=~/.cache/lathe/jdks/…/com/sun/source/util/DocTrees.java line=110 column=36
# Contrast (already works): describe_symbol at a `DocTrees` use-site in a reactor .java file.
```

### Fix

`WorkspaceSession.diagnosticsFuture` registers and compiles every route but `Missing`, through the
same `submitCompile` path, so an engine query on a dependency or JDK source finds an analysis.
Only a reactor (`Module`) source is indexed and reports its diagnostics; an external one reports none,
as the editor publishes none for read-only sources.
Checked on Dropwizard: `describe_symbol` on `TimeUnit.toMillis` inside the JDK source now returns its
signature and Javadoc (was "No symbol information at that position.").

### Regression targets

- `LatheEngineNavigationTest.describe_methodInADependencySource_returnsSignatureAndJavadoc` (fails without the
  fix; also checks that the dependency reports no diagnostics).
