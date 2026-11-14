# Code review — 2026-11-14 (round two)

A second whole-project review of `main` at `1b7521a6`, after the first round's fixes ([code-review-2026-11-13.md](code-review-2026-11-13.md)).
It looks for behaviour bugs, threading-contract breaks, methods that hand out mutable or concrete collections, and code that is hard to read.

A duplication is a finding only when one of these holds, and each such finding names it:

1. the copies have drifted, or are likely to drift into a bug;
2. a shared version would read simpler than any one copy;
3. every change has to touch all the copies.

Merging that needs a new record, factory or interface, or a helper with four or more parameters, and meets none of those, is not a finding.
Over-abstraction, dead code and stale comments are findings in their own right.

Findings continue the first round's numbering, from CR-140.
Items marked **(verified)** were re-read after the review; items marked **(run)** were confirmed by the reviewer with a throwaway probe; the others are as the reviewers read them.
Nothing was built or run for this document.
Line numbers are as of `1b7521a6`.

Path shorthands are as in round one: `server/`, `analysis/`, `mcp/`, `install/`, `core/`, `maven/`, `gradle/`, `openjdk/`, `nvim/`, `vscode/`.

## 1. Behaviour bugs

### Server and analysis

- **CR-140 (verified)** `lathe.imports.missing` ignores the visible-trees rule from #200: `DocumentFeatures.missingImportsFuture` passes `reactorIndex.current()`, not `seenFrom(route.seenTrees())`, so it offers a not-yet-compiled reactor type the file cannot see. `server/DocumentFeatures.java:428`. Fix: narrow it as completion and code actions do; better, let an index that was never narrowed see no tree, so a forgotten caller fails closed.
- **CR-141 (verified)** Temp sources are written in UTF-8 but compiled with the module's `-encoding`: in an ISO-8859-1 module a non-ASCII character shifts every later offset on its line, and FULL writes put garbled string constants into the `.lathe/` classes run and debug use. `ExternalCompiler` passes no `-encoding`, so on JDK 17 Windows javac reads its UTF-8 copy as Cp1252. `server/module/ModuleSourceCompiler.java:141, 148, 297, 505`; `server/module/ExternalCompiler.java:87-98`. Fix: write and restore temp copies in the module's charset; pass `-encoding UTF-8` in `ExternalCompiler`.
- **CR-142 (verified)** A reload waits on the event loop until every old worker drains its queue (`allOf(closeAsync).join()`), freezing every editor request behind a batch write or a reference search. `server/module/WorkspaceModuleRegistry.java:243-248`, called from `server/WorkspaceSession.java:768`. Fix: return the future and `rejoin` it, or do not wait.
- **CR-143 (verified, run)** "Wrap in try/catch" wraps the whole method or lambda body: `enclosingStatementToWrap` climbs to the body block, giving `void m() try { {…} } catch …`. The catch variable is always `e`, clashing with a local `e`. The tests only check `contains`. `analysis/refactor/CodeActionSupport.java:110-125`; `analysis/refactor/TryCatchWrapProvider.java:60-70`. Fix: stop at the statement inside the body block; pick the name with `VariableNameSuggester.uniquify`; assert the result compiles.
- **CR-144 (run)** Completion throws `ValidationException` inside an anonymous class body, and for `new §` in any file holding one: the anonymous class's empty name reaches `SimpleNameContext` and `CandidateFactory`. `analysis/completion/SentinelParser.java:110-117`; `analysis/completion/TypeReferenceCompleter.java:363-388`. Fix: skip classes with an empty simple name.
- **CR-145 (run)** Convert to `var` breaks code: a generic call takes its type from the target (`List<String> x = Collections.emptyList()` becomes `List<Object>`), and diamond arguments are written unimported. `analysis/refactor/ConvertToVarProvider.java:151-158, 175-187`. Fix: refuse a generic call with no explicit type arguments; render through `CodeActionSupport.sourceType` with its imports.
- **CR-146 (run)** Extract field moves an expression reading a field assigned in the constructor into a field initializer that does not compile, or that silently runs once for a mutable field. `analysis/refactor/ExtractFieldProvider.java:78-81`. Fix: refuse unless every field read is a constant or a `final` field with an initializer.
- **CR-147 (run)** Extract variable silently shadows a field the method reads later; extract field's replace-all can be captured by other methods' locals. `analysis/refactor/ExtractVariableProvider.java:450-476`; `analysis/refactor/ExtractFieldProvider.java:252-266`. Fix: count the class body's simple names as taken.
- **CR-148 (run)** Add constructor parameter rewrites a delegating constructor into a call no constructor matches, when the target constructor already assigns the field and was skipped. `analysis/refactor/AddConstructorParameterProvider.java:151-154, 178-192`. Fix: forward only into constructors it edited.
- **CR-149 (run)** Declare variable writes `var x = null;` and `var f = () -> {};`. `analysis/refactor/DeclareVariableProvider.java:71-76`. Fix: share `ConvertToVarProvider.isVarLegalInitializer` (condition 1).
- **CR-150 (run)** Try-with-resources closes a resource the method returns. `analysis/refactor/TryWithResourcesProvider.java:87-91`. Fix: refuse when a following statement returns or assigns the resource.
- **CR-151 (run)** Extract constant can name a field `404`. `analysis/refactor/ExtractConstantProvider.java:164-170`. Fix: fall back to the default name when the base is not an identifier.
- **CR-152 (run)** Add `throws` imports a type a member class of the same name hides, and the error stays. `analysis/refactor/AddThrowsProvider.java:61-63, 78` (CR-104). Fix: write the type with `CodeActionSupport.sourceType`, here and in `TryCatchWrapProvider`.
- **CR-153 (run)** Override completion inside an interface writes a body with no `default`, and offers package-private methods of another package. `analysis/completion/OverrideCompletionProvider.java:52-62, 84-89`.
- **CR-154 (run)** Implement abstract methods can write two identical stubs for one method inherited through a generic interface and a plain one (`A<T>.f(T)` and `B.f(String)`): the grouping key uses declared parameter types, the stub the member types. `analysis/refactor/MissingMethodImplProvider.java:139-153`. Fix: key on the erased `asMemberOf` parameter types, for grouping and for `ownSignatures`.
- **CR-155 (run)** `@Retention(R§)` inserts `RUNTIME` unqualified and unimported, where value-slot enum completion qualifies and imports it. `analysis/completion/AnnotationCompletionProvider.java:84-117`. Fix: use `CandidateGenerator.proposeEnumConstantCandidates`.
- **CR-156 (run)** `Outer.` completion adds a wrong import (`import p.Test.Outer;`, uncompilable for a private member class) and offers private nested types. `analysis/completion/CompletionEditApplier.java:53-77`; `analysis/completion/CandidateGenerator.java:125-137`. Fix: delete `applyNestedOuterImportEdits` (about 50 lines); filter nested types by access.
- **CR-157 (run)** In-file type completion inserts names not in scope (another class's nested type, a local class of another method). `analysis/completion/TypeReferenceCompleter.java:363-388`. Fix: build through `typeElementCandidate(te, names)`; skip local and anonymous classes the scope cannot see.
- **CR-158 (run)** Simple-name completion reads the wrong class when two nested classes share a name, and misses an enclosing instance's fields inside an inner class. `analysis/completion/SimpleNameProvider.java:152-158, 220-235` (CR-96). Fix: take the class from the scope's enclosing class.
- **CR-159** Outgoing calls are always empty for a method of an anonymous or local class: `visitMethod` skips the bodies of methods that are not the target. `analysis/CallHierarchyOutgoingLocator.java:66-75`.
- **CR-160** Signature help highlights the next parameter with the cursor at the end of an argument (`foo(abc|, x)`), and can run past a varargs parameter list. `analysis/SignatureHelpResolver.java:384-399`.
- **CR-161** Rename refuses a legal name used by a variable not in scope (an earlier `for (int i…)`, a local class's method). `analysis/RenameConflicts.java:312-332`.
- **CR-162** The two "canonical constructor parameter" rules have drifted (by name vs by type), suppressing a parameter hint for `record R(int a) { R(long a) { this((int) a); } }`. `analysis/CursorElements.java:302-322`; `analysis/ReferenceTarget.java:431-440` (CR-79).
- **CR-163** Semantic tokens miss a Javadoc-only `@deprecated` that hover shows. `analysis/TokenScanner.java:317-320` (CR-87). Fix: `elements.isDeprecated`.
- **CR-164** A reactor type's hover origin names the wrong module for nested modules and the root module. `analysis/LibrarySources.java:349-362`.
- **CR-165** For `Foo::new` the cursor on `::` counts as on the name. `analysis/DeclarationNames.java:110-112`. Fix: `referenceName(reference)`.
- **CR-166** A `private main` is offered as runnable. `analysis/RunnableScanner.java:126-137`.
- **CR-167** HTML tags in Javadoc are lower-cased with the default locale; `CodeActionSupport.typeSimpleName` too (`int` becomes `ınt` in Turkish). `analysis/JavadocMarkdownPrinter.java:497, 516`; `analysis/refactor/CodeActionSupport.java:69`.
- **CR-168** One lambda rule names `Stream.mapToObj`, which does not exist, so it never matches. `analysis/completion/CompletionLibraryRules.java:160-165`.

### Debugger

- **CR-169 (verified)** Evaluating `x / 0.5` or `x % 0.25` throws "/ by zero": both the floating and the integral result are computed before the kind picks one. `server/debug/JdiInterpreter.java:523-538`.
- **CR-170** Debug-console completion inserts snippet syntax literally (`list.add($1)`); its `InsertReplaceEdit` branch is dead. `server/debug/LatheCompletionsProvider.java:146, 164-181`.
- **CR-171** Evaluation semantics: long comparisons go through `double`; a hidden field reads the subclass's; `super.m()` dispatches virtually; a receiver with a call is evaluated twice and `SomeType.length` fails. `server/debug/JdiInterpreter.java:224-231, 317, 387-389, 483-486, 613-630`.

### Run, test and debug

- **CR-172** A debuggee stays suspended forever when its debug host fails to start, and its token is never released. `server/RunCoordinator.java:550-572, 593-605`. Fix: cancel the session, then rethrow.
- **CR-173** A blocked or failed test discovery is cached until the test classes change, so a module can show no tests until restart; an unreadable `test-discovery.json` is cached as a failure. `server/run/TestDiscoveryStore.java:28-55`. The trigger needs confirming.
- **CR-174** Reference-search progress counts past its total for a field with Lombok accessors ("45 / 32"), and the comment claims the opposite. `server/ReferenceSearch.java:242-279`; `server/ProgressReporter.java:150-171`.
- **CR-175** Eclipse range formatting of `module-info.java` always fails ("Invalid Java syntax"). `server/format/EclipseFormatEngine.java:304-307`.

### MCP

- **CR-176** Failures outside `guarded` reach the agent as JSON-RPC errors, not `isError` results: a call with no `arguments` (null, which the SDK passes on), a path containing NUL, `/`, or a failing engine start. `mcp/LatheMcpTools.java:384-401, 429, 528, 815-821`; `mcp/LatheMcpServer.java:329-338`. Fix: run the handler inside `guarded`; read arguments through one `args(request)` that maps null to `Map.of()`.

### Build front-ends

- **CR-177 (verified)** The JDK type index scans the running JVM's runtime image but files it under another JDK: a Gradle daemon on 21 with a 17 toolchain writes 21's types into "JDK 17", cached by home/vendor/version. `install/typeindex/JdkTypeIndexSync.java:96, 114-118`; `gradle/LatheWorkspaceTask.java:396-402`. Fix: open `jrt:/` with `java.home` set to the indexed JDK; bump `CACHE_SCHEMA_VERSION`.
- **CR-178** The compile shim stops its root search at `maven.multiModuleProjectDirectory`, `LaunchCapture` has no ceiling: a submodule build without `.mvn/` captures tests but not compiles, and a nested checkout writes into the parent's `.lathe/`. `compiler/LatheCompiler.java:149-166`; `lathe-junit/.../LaunchCapture.java:42-63` (CR-115). Which directory bounds the search is a decision.
  Deferred past the release: no results are wrong, and every boundary trades one case for another.
  A later fix must keep a worktree with no `.lathe/` yet from climbing into its parent's, and must not rely on `.git` or `.mvn`.
  One option: the test hook takes a `.lathe/` only when it already has a directory for its module.
- **CR-179** The extension's build lock and opt-out check use `multiModuleProjectDirectory/.lathe`, while the sync writes `topLevelProject/.lathe`. `lathe-maven-extension/.../LatheLifecycleParticipant.java:43, 142-144`. Fix: use the top-level project, as `SyncCoordinator` does.
- **CR-180** An OpenJDK sync fails when a source listed by the last `make` has been deleted. `openjdk/OpenJdkSync.java:262`.
- **CR-181** Under JUnit's parallel execution the results NDJSON can interleave: `write` makes two writer calls without synchronisation. `lathe-test-runner/.../ResultsListener.java:165-178`.
- **CR-182** On a module-lock failure Maven skips the capture, Gradle fails the build (CR-110, condition 1). `compiler/LatheCompiler.java:73-103`; `gradle/LatheCaptureTask.java:145-157`.
- **CR-183** About 18 shared install messages say "lathe:sync failed", shown as is by Gradle and OpenJDK (CR-111). Reword; no helper.
- **CR-184** The it-support stderr forwarders have drifted: `LspSession` reads in the default charset. `lathe-it-support/.../LspSession.java:187-203`, `McpSession.java:161-177`.

### Editor clients

- **CR-185** VS Code claims every Java file in every client (the selector has no folder pattern), doubling diagnostics with several folders open, and two folders of one reactor start two servers. `vscode/extension.ts:29, 38, 59` (CR-128).
- **CR-186** The neotest adapter always asks the first Lathe server, and its readiness flag is global. `nvim/neotest/common.lua:30-32`; `nvim/neotest/readiness.lua:16` (CR-5's remainder).
- **CR-187** Stop, stack-frame links and debug starts ask the current or first server, not the one that launched the run; the saved-config and package caches are global across workspaces. `nvim/run.lua:19-21, 346, 361-368`; `nvim/dap.lua:90, 230`; `nvim/stackdecorate.lua:120`; `nvim/new.lua:44, 519-521`. Fix: keep the launching client's id with the run token; key the caches by client.
- **CR-188** `:LatheRunStop` does nothing after a failed debug start (a stale token), and an earlier run finishing makes the newer run unstoppable. `nvim/dap.lua:98, 120-129`; `nvim/run.lua:85` (CR-125's drift).
- **CR-189** Stack-frame links stop working after the console is hidden and shown again: the state is cleared on `BufHidden`, but the buffer survives. `nvim/stackdecorate.lua:186-192`.
- **CR-190** `:LatheSync` outside a workspace silently does nothing. `nvim/sync.lua:168-170, 245-248`.
- **CR-191** Neotest finds tests only under `/src/test/` (Gradle source sets missed). `nvim/neotest/common.lua:58-60` (CR-129).
- **CR-192** Resource refresh matches `/x/app` against `/x/app-api/…`. `nvim/resources.lua:123`.
- **CR-193** Format-on-save stacks its autocmds on every re-attach, formatting twice after `:lsp restart`. `nvim/format.lua:27-58`.

## 2. Threading contract

- **CR-194** Mutable collections cross from the loop to workers or the common pool (not mutated after hand-off, so no race today): `server/WorkspaceSession.java:919-933`; `server/RenameService.java:212-227, 284-303`; `server/ReferenceSearch.java:941-956, 1085-1087`; `server/ReactorTypeIndex.java:110-112`. Fix: `toList()`, `toUnmodifiableMap`, `Map.copyOf`.
- **CR-195** `SourceLayout.packages`/`moduleRun`/`basePackage` walk source trees on the loop, where `destinations` deliberately does not. Callers `server/LatheWorkspaceService.java:273, 299`, `server/RunCoordinator.java:772`, `server/WorkspaceQueries.java:79`.

## 3. Mutable or concrete collections handed out

Fix in every case: build with `toList()` / `toUnmodifiableSet()` / `toUnmodifiableMap`, or return `List.copyOf` / `Set.copyOf` / `Map.copyOf` (`Collections.unmodifiableMap` where key order matters).

- **CR-196** A concrete return type: `server/run/LaunchPlan.java:139` `baseJavaArgs` returns `ArrayList<String>` for callers to append to. `gradle/LatheSettingsPlugin.java:59` returns `HashMap` on purpose (Gradle 8.8 cannot restore JDK immutable maps from isolated state) and stays.
- **CR-197** A live view of internal state: `server/ReferenceCandidateIndex.java:151-153` `candidateUris` returns the index's own mutable set; `gradle/LatheFactsTask.java:51-56` `getSourceSets()` returns the task's list, which `LatheProjectPlugin.java:409` adds to.
- **CR-198** Mutable results in the server: `server/module/ModuleSourceCompiler.java:265-278, 499-531`; `server/format/GjfFormatEngine.java:129-149`; `server/run/CompletenessGate.java:114-162`; `server/workspace/ResourceRootIndex.java:33-37` (public); `server/run/RunOverlay.java:107-116`; `server/module/WorkspaceModuleGraph.java:42-43, 77-97` (public); `server/ChangeVerifier.java:154-166` (a helper appending into its caller's map); `server/LatheLanguageServer.java:127-132`.
- **CR-199** Mutable results in analysis: `analysis/DocumentSymbolScanner.java:36`; `FoldingRangeScanner.java:39`; `RunnableScanner.java:60`; `FileFeatures.java:268, 340-350`; `WorkspaceSymbolResolver.java:47-78`; `UnusedDeclarationScanner.java:303-314`; `RenameProvider.java:92-101`; `RenameBindingCheck.java:292-304`; `LibrarySources.java:43-54` (map values not copied, one handed out at :163).
- **CR-200** Mutable results in completion and code actions: `completion/CompletionEngine.java:694`; `SimpleNameProvider.java:42`; `TypeReferenceCompleter.java:363`; `DeclarationNameCompletionProvider.java:94`; `refactor/ExtractVariableProvider.java:57, 466`; `ExtractConstantProvider.java:39, 172`; `ExtractFieldProvider.java:43, 252`; `ExtractMethodProvider.java:106`; `ExtractionSupport.java:254, 315, 740`; `RegionFlow.java:310`.
- **CR-201** Mutable results in the build side: `maven/ReactorProjects.java:125-156, 394-396`; `maven/MavenStyle.java:176-178` (`Arrays.asList`); `core/.../ClassMetadataReader.java:145-163`; `openjdk/OpenJdkSync.java:254-266`.

## 4. Duplication that meets the rule

- **CR-202** "The trees a config sees" is written three ways (condition 3): `server/CompilerRoute.java:34-37`, `server/RenameService.java:342-347`, `server/module/WorkspaceModuleRegistry.java:169-183`. Fix: `ModuleSourceConfig.seenTrees()`. Related: CR-65's three `remappedClasspath + remappedModulepath` concatenations.
- **CR-203** `foldingRangeFuture` copies `openDocFeature` and has drifted (logs a WARNING for every unopened document) (condition 1). `server/DocumentFeatures.java:473-485`.
- **CR-204** `WritePass.lombokConfigCandidates` copies `ServerFiles.ancestorFiles` with a different range (conditions 1, 2). `server/WritePass.java:512-516`.
- **CR-205** References and incoming calls each have their own open-document and disk-batch search (condition 3). `server/ReferenceSearch.java:465-541, 1355-1402` (CR-31, narrowed).
- **CR-206** One module owns a file by two rules (condition 1). `server/module/ModuleSourceCompiler.java:326-331`; `server/module/WorkspaceModuleRegistry.java:134-141` (CR-60).
- **CR-207** Launch notes travel as a text prefix that the engine parses back (condition 3). `server/run/Launcher.java:42-45, 105`; `server/engine/LatheTestRun.java:71-82` (CR-62).
- **CR-208** Smaller server copies, condition 2: `ExternalCompiler` re-implements `diagnosticsOnly()` and `closeLogged` (CR-58); `JavacRunner`'s three admission wrappers (CR-59); `MainClassScope` vs `SourceSetScope` (CR-64); `LatheEvaluationProvider`'s two guarded blocks and two attribute chains (CR-67); the mtime helper in `WritePass` and `WorkspaceWatcher` (CR-20); `SourceLayout.sourcePath` vs `TypeSourceLocator.findSourceFile`; `LaunchSession`'s two results-sink reads (CR-63).
- **CR-209** Analysis copies, condition 2 or 3: `data.target()` rebuilt by hand (`HierarchyFeatures.java:108-118`); "constructor at `new X`, else the element" three times (a `Cursor.namedElement()`); the `new X()` name ternary in three locators; `ReferenceLocator`'s two role methods; `JavacDiagnosticMapper` reaching into `refactor/` and re-implementing `TreePaths.enclosingClass`; `declarationNamePosition`'s `IOException` handled five ways (CR-74); the definition fallback in `NavigationFeatures`; three supertype walks (CR-73); `StaticImportCandidates` re-implementing `importedStaticNames`; `TokenScanner`'s two kind switches.
- **CR-210** Completion and code-action copies: `CodeActionSupport.source` and `ConvertToVarProvider.replaceEdit` are verbatim copies; `elementTypeName` twice; the expected-type helpers copied between `ExpectedValueResolver` and `LambdaExpectedReturnTypeResolver`; the replace-edits stream three times; the quick-fix `CodeAction` builder six times; `isRealNameSlot` and the class-body modifier lists twice (condition 3); `samReturnType` three times (CR-106).
- **CR-211** Build side: `CmdlineReader.read`/`readTool` (CR-113, conditions 2, 3); `LaunchCapture`'s seven option blocks (CR-114); the dependency-source cache layout (CR-108); `PluginProps`/`ExtensionProps` swallowing `IOException` (CR-23); the Xpp3Dom merge copied twice, which mutates its first argument (CR-112, condition 1).
- **CR-212** MCP: the capped-list records and their header (CR-50, narrowed); the `maxResults` schema property copied four times with a hard-coded default (condition 1); the string-array and position-argument reads in `LatheWorkspaceService`.
- **CR-213** Clients: `util.notify` bypassed in four modules, some toasts losing their title (CR-124); one run registry for run, debug and neotest (CR-125); the three debug-config builders (CR-131); format-on-save re-implementing `fold.format` (CR-133); the drifted `target()` and four `write_file` spec helpers.

## 5. KISS, dead code and comments

- **CR-214** Dead checks the SDK already enforces: MCP input is validated against the tool schemas before a handler runs, so `answer`'s `maxResults` check, the enum "must be one of" branches and `atFile`'s missing-file branch never run in production. `mcp/LatheMcpTools.java:389-394, 503-505, 599-606, 636-638`.
- **CR-215** Repeated requests with visible cost: `rename` runs two full position requests (CR-47); `analyze_change` reads a file per location for up to 2000 discarded snippets (CR-51); trying name columns repeats whole requests (CR-46); `callHierarchy` starts its search inside `Noted.map`, against `Noted`'s contract. `server/LatheEngine.java:530-548, 845-883, 1057-1061, 1133-1167`.
- **CR-216** `RankedCompletionCandidate` does not pay for itself: its `incomplete` flag is always false, and it forces a second `present` overload and index-aligned lists. `analysis/completion/CompletionCandidateRanker.java:23`; `CompletionItemPresenter.java:21-25`.
- **CR-217** "Not deferred" written two ways (`null` and `DeferReason.NONE`), leaving an unreachable branch. `server/LatheEngine.java:916-957`; `mcp/McpText.java:461`.
- **CR-218** Dead code: `CompletionMode` and seven unread `CompletionSite` fields; `ParsedSentinel.lambdaParamIndex`/`docVersion` and the `version` threaded to set it; `ReferenceSearch.fileQueryFuture`'s disk half (CR-37); `onSave`'s unreachable branch; `ReferenceCandidatePlanner`'s redundant local-kinds branch and `staticImportSites`; `RunItem.withName`; test-only `LaunchOverlay.NONE`, `CompilationAdmission.run(Supplier)`, `CompilationWorker.compile(request)`, the 2-argument `planCandidates` and `typeImplementations`, `HoverFormatter`'s `fmt == null` branch, `McpText.NO_NOTES`; `PortUtil`; unused `LOG`s and `typeIndex` parameters; `nvim/new.lua` `pref_scope`, `nvim/output.lua` `command_rows`, the doubled `indentexpr`, `vim.loop`, re-scheduled callbacks (CR-126); `LatheWorkspaceService`'s no-op `thenApply`.
- **CR-219** Stale or misleading comments and names: after #203 (`core/LatheFlags.java:27`, `docs/planned/lathe-vscode-run-test.md:159-161`, `docs/done/lathe-per-test-output-attribution.md:4` still say markers are on stdout only); after #206 (`RunCoordinator.java:667-676`, `LaunchTarget.java:8-9`, test names `runTestFuture_*`/`debugMainFuture_*`, `cancelTest`); after #194/#207/#200 (`WorkspaceTypeIndex.java:39-48`); after #197 (`Instantiation.java:13` claims one rule for every path); after #202 (`ServerEventLoopTest.java:21-23`, and `WritePassTest.java:494, 496` still wait 10s); after #190 (`buildStub`'s `method` parameter); `debug/JdiRef`, `LatheEvaluationProvider`, `LatheSourceLookUpProvider` calling shipped features deferred; `FormatEngine`'s `file` parameter; the round-one CR-136 comments; `LatheModelInjector.java:65-67` and `ReceiverResolver.java:53` linking to doc files; `planSearchScope`'s `cursorConfig`; the server's `TypeKind` (CR-30); `CompilationWorker`'s `context` (CR-70); `RegionFlow.isBodyBoundary` (CR-105); `SentinelParser` storing an annotation element in `enclosingMethodName`; Spotless-orphaned comment lines across modules.
- **CR-220** Backward-compatibility code CLAUDE.md says not to keep (CR-138), plus `FileUtil`'s symlink branch for old launchers.
- **CR-221** Smaller items: `CompletenessResult`'s lambda-capture workaround; `SourceParser.resolveParamNames` returning null to mean "use the element's names"; `JavadocMarkdownPrinter.ownTag` taking a predicate it calls with `null`; `CallHierarchyIncomingLocator`'s two maps with one key; `Positions.toOffset(CompilationUnitTree, …)` still spelled out at six sites after #205.

## 6. Round-one findings, re-checked

Dropped, as meeting none of the conditions or as deliberate: CR-3 (routing through `WorkspaceSync.write`), CR-10, CR-12 (analysis), CR-13, CR-14, CR-16, CR-17, CR-18, CR-21, CR-25, CR-29, CR-33, CR-34, CR-36, CR-38, CR-41, CR-42, CR-43, CR-48, CR-52, CR-54, CR-55, CR-57, CR-68, CR-81, CR-83, CR-85, CR-86, CR-97, CR-103, CR-109, CR-116, CR-117, CR-118, CR-119, CR-120, CR-121, CR-127, CR-130 (bar the stale `pick.lua` header), CR-134, CR-137.

Still open and folded into this document: CR-5, CR-7, CR-15, CR-20, CR-23, CR-24, CR-30, CR-31, CR-37, CR-39, CR-40, CR-46, CR-47, CR-50, CR-51, CR-58 to CR-60, CR-62 to CR-67, CR-69 to CR-80, CR-82, CR-84, CR-87, CR-88, CR-89, CR-90 (replace-edits helper only), CR-91 to CR-96, CR-98 to CR-102, CR-104 to CR-108, CR-110 to CR-115, CR-124, CR-125, CR-128, CR-129, CR-131 to CR-133, CR-135, CR-136, CR-138.
