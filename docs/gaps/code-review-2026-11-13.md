# Code review — 2026-11-13

A whole-project review of `main` at `cc8a2ec8`, looking for DRY violations and code that is hard to read.
Recent changes have left several helpers, code paths and conventions that overlap or have drifted apart;
this document lists them so they can be fixed one area at a time.

Each finding has a `CR-N` ID.
Section 1 lists behaviour bugs found along the way; they come first.
The rest are grouped by area and ranked within each area, most valuable first.
Items marked **(verified)** were re-read after the review; the others are as the reviewers read them and should be confirmed before fixing.
Nothing was built or run.
Line numbers are as of `cc8a2ec8`.

Path shorthands:

- `server/` — `lathe-server/src/main/java/io/github/aglibs/lathe/server/`
- `analysis/` — `lathe-analysis/src/main/java/io/github/aglibs/lathe/analysis/`
- `mcp/` — `lathe-mcp-server/src/main/java/io/github/aglibs/lathe/mcp/`
- `install/`, `core/`, `maven/`, `gradle/`, `openjdk/` — the main source package of that module
- `nvim/` — `lathe-neovim/runtime/lua/lathe/`
- `vscode/` — `lathe-vscode/src/`

## Status after the first round (2026-11-14)

The first round fixed the bugs and a few refactors; the next review starts from this list, not from the findings below.
A duplication is worth removing only when the copies have drifted or will drift into a bug, the shared version reads simpler than any one copy, or every change has to touch all the copies.
A merge that needs a new record, a factory or a helper with many parameters, and meets none of those, stays duplicated: CR-61 was closed for that reason.
The open findings below have not been checked against that rule yet.

Done:

- Bugs: CR-1 (#188), CR-2 (#190), CR-3 (#198), CR-4 (#191), CR-5 (#192), CR-8 (#197, #200), CR-9 (#194, #207), CR-11 (#195).
- CR-19, CR-22, CR-28: the existing file, position and list helpers (#205).
- CR-26: one rule for a searchable type name (#207).
- CR-35, and CR-53's run/debug handlers and run token: one launch path for run and debug, tests and mains (#206, #210).
- CR-122, CR-123: the Neovim client's runnables in one module (#209).
- CR-139: override completion imports its types (#204).

Partly done:

- CR-3: the OpenJDK sync calls the shared helpers; routing it through `WorkspaceSync.write` waits for the build front-end work.
- CR-5: still open are stopping a run and refreshing saved configs from the server that launched the run, and the neotest adapter's own client lookup.
- CR-72: the call-hierarchy locators share the name range (#188); the `new X()` name extraction and the factories are still copies.

Not bugs: CR-6, CR-7, CR-10 (see each).

Closed as not worth it: CR-61 (#208, see above).

Everything else is open.

## 1. Bugs found along the way

Most of these are copies that drifted: one copy got a fix or a rule the others did not.

### CR-1 — Call hierarchy can underline the receiver instead of the method (verified)

For `amount.amount()` or `amount::amount`, the call-site range can land on the receiver.
Both call-hierarchy locators search for the method name from the start of the whole node.
`ReferenceLocator.identifierAnchor` searches from the end of the receiver, and its comment names exactly this case.

- Where: `analysis/CallHierarchyIncomingLocator.java:124-126, 161-162`; `analysis/CallHierarchyOutgoingLocator.java:85-86, 125-126`; the fixed copy in `analysis/ReferenceLocator.java:415-441`.
- Fix: move `addMatchAtIdentifier`/`identifierAnchor` to `SourceTreeLocator` and use it in all three locators (see CR-72).
- Status: #188.

### CR-2 — "Implement missing method" adds imports the stub never writes (verified)

`MissingMethodImplProvider.signatureTypes` collects the thrown types for imports, but `MethodStubRenderer.signature` writes no `throws` clause.
The quick fix therefore adds unused imports.
The override-completion stub renders the same text differently: 2-space indent instead of 4, a plain `TypeDisplayFormatter`, no imports.
`MethodStubRenderer` says the two must stay identical.

- Where: `analysis/refactor/MissingMethodImplProvider.java:163-181`; `analysis/completion/OverrideCompletionProvider.java:37, 68-73`; `analysis/internal/MethodStubRenderer.java`.
- Fix: put the stub text and the list of types it writes into `MethodStubRenderer`; render through `SourceTypeNames` in both places.
- Status: #190: both stubs copy the throws clause and the quick fix imports exactly what the stub writes; the stub text and indentation are still two copies, and override completion imports nothing (CR-139).

### CR-3 — OpenJDK sync bypasses `WorkspaceSync` (verified)

The OpenJDK front-end writes the manifest, `java-home` and the sync script one by one instead of through `WorkspaceSync.write`.
It never writes `.lathe/.gitignore` and never calls `CapturePruner`, so a module dropped from the JDK build leaves stale `lsp-params`.
It also builds `WorkspaceManifestData` positionally with nulls.

- Where: `openjdk/OpenJdkSync.java:80-83, 271-291`; `install/WorkspaceSync.java:72-90`.
- Fix: let `WorkspaceSync.Inputs` take what OpenJDK lacks (no `Caches`, unfingerprinted build files) and route OpenJDK through it; prune with module → `{classes}`.
- Status: #198: writes `.gitignore` and prunes. Routing through `WorkspaceSync.write` is left for the build front-end work (CR-108 to CR-111), where that API is revisited.

### CR-4 — MCP `maxResults` accepts zero and negative values (verified)

The schema says `minimum: 1`, but `maxResults(request)` passes any integer on.
A negative value reaches `Stream.limit`, which throws `IllegalArgumentException`, logged as SEVERE by `guarded`.

- Where: `mcp/LatheMcpTools.java:770-773`; `server/LatheEngine.java:244, 472, 856`.
- Fix: reject values below 1 as an argument error (and see CR-49 for the schema copies).
- Status: #191.

### CR-5 — Neovim run and debug commands ask the first Lathe server, not the buffer's (verified)

`util.client(bufnr)` returns the first Lathe client when called without a buffer.
Buffer-scoped commands call it without one, then warn "server not attached to this buffer".
With two workspaces open, a run can go to the wrong server.

- Where: `nvim/run.lua:114, 198, 244, 358` (`lathe_client()`); `nvim/dap.lua:123, 209`; `nvim/stackdecorate.lua:120`; `nvim/resources.lua:80`; `nvim/neotest/common.lua:44-46`.
- Fix: pass `bufnr` wherever there is one; keep the no-buffer lookup only for the run console and stack traces.
- Status: #192: a buffer's own server only, the current buffer's then the first with none given. Still open: stopping a run and refreshing saved configs should ask the server that launched the run, and the neotest adapter keeps its own lookup.

### CR-6 — Maven main runs may use a different JDK from the server (not a bug)

`MavenMainLaunch` records the JDK Maven runs on (`java.home`).
The workspace JDK written to `.lathe/java-home` comes from `JdkSourceResolver`, which prefers `JAVA_HOME`.
A main run can start on a different JDK than the one the server analyses with; Gradle uses the toolchain for both.

- Where: `maven/MavenMainLaunch.java:41`; `maven/SyncCoordinator.java:69`; `install/JdkSourceResolver.java:33`.
- Fix: pass `caches.jdk().home()` into `MavenMainLaunch`.
- Status: not a bug. Test runs record the JDK Maven runs on (the Surefire fork's `java.home`), so main runs doing the same keep main and test runs on one JDK; `JAVA_HOME` can even be older than the bytecode. #193 was closed.

### CR-7 — The root module has two names (not a behaviour bug)

Maven writes `"."` for the root in `ResourceRootData`, `main-launch` and `test-filter`, but `""` in `OutputData`.
Gradle writes `""` everywhere except `main-launch`, where it writes `"."`.
The schema docs disagree too: `ResourceRootData` says `"."`, `OutputData` says `""`.
Every reader resolves both spellings to the same directory, so nothing breaks; only the resource finder's origin label differs (`.` for a Maven root module, empty for a Gradle one).

- Where: `maven/ReactorProjects.java:230, 345-348`; `gradle/LatheProjectPlugin.java:470, 684-686`; `server/SourceLayout.java:44-46`; `core/LatheLayout.java:270-273`.
- Fix: one `LatheLayout.moduleRel(root, dir)` with one convention, used by all front-ends and the server.
- Status: a consistency clean-up, to do with the build front-ends (section 8).

### CR-8 — Completion disagrees with itself on sealed classes and on importable types

`MemberAccessCompleter.constructorReference` and `TypeReferenceCompleter.directlyInstantiable` reject every sealed class; `constructibleType` rejects only sealed abstract classes.
A sealed non-abstract class can be instantiated.
`ImportCandidates.isImportable` trusts reactor types with no class file yet, while `TypeIndexValidator.isResolvable` drops them, so the import quick fix offers a type completion hides.

- Where: `analysis/completion/MemberAccessCompleter.java:244-249`; `analysis/completion/TypeReferenceCompleter.java:468-472, 525-538`; `ImportCandidates.java:74-86`; `TypeIndexValidator.java:30-41`.
- Fix: one `instantiable(TypeElement)` helper; one import-validity rule (see CR-75).
- Status: #197 for sealed classes (and a subtype offered only with a callable constructor); #200 for the import rule: both trust a reactor type with no class file yet, declared in a tree the file sees.

### CR-9 — Workspace symbols can resolve the wrong nested type

`WorkspaceSymbolResolver.declarationRange`/`classPath` re-implements `TypeSourceLocator.locate` but takes the first class with that simple name.
It also opens its own `SourceParser`.

- Where: `analysis/WorkspaceSymbolResolver.java:39, 142-173`.
- Fix: call `TypeSourceLocator.locate`.
- Status: #194. A local class (`Outer$1Helper`) now resolves to its file's start, as `TypeSourceLocator.locate` deliberately skips local classes; whether to locate them is open.

### CR-10 — Type-hierarchy explorer timing log is hand-written (not a bug)

The explorer skips the `decode(...) == null` guard its sibling `typeHierarchyLevel` has, but needs none: it reads only items its own `prepare` built, and `TypeHierarchyResolver`, the one place that builds them, always sets their data.
The sibling's guard is for items that come back from the client.
What remains is its timing log, hand-written where the siblings use `FeatureAnswers.logged`.

- Where: `server/DocumentFeatures.java:300-320`.
- Fix: use `FeatureAnswers.logged`.

### CR-11 — Analysis builds file URIs outside `LatheUri`

`Path.toUri()` keeps the Windows drive-letter case; `LatheUri.canonical` upper-cases it, so the same file can get two URIs, against the portability rule.

- Where (build): `analysis/DefinitionLocator.java:74-75, 106, 136`; `NavigationFeatures.java:206`; `WorkspaceSymbolResolver.java:122`; `TypeSourceLocator.java:95`; `CallHierarchyItemDataCodec.java:80`; `TextMentions.java:97, 117`.
- Where (parse): `analysis/RenameBindingCheck.java:243, 381` use `Path.of(URI.create(..))`.
- Fix: `LatheUri.fromPath` / `toPath` everywhere; `lathe-analysis` already depends on core.
- Status: #195, and #194 for workspace symbols.

## 2. Across modules

### CR-12 — Source file lookup by class name exists three times

`server/SourceLayout.sourcePath` re-implements `TypeSourceLocator.findSourceFile(binaryName, roots)`: cut at `$`, dots to `/`, add `.java`, first existing root.
`DefinitionLocator.findSourceFile(Element, roots)` builds the same path and adds only a module-name prefix; its "top-level class → package → module" steps are copied in `LibrarySources.classify`.

- Where: `server/SourceLayout.java:289-300`; `analysis/TypeSourceLocator.java:47-67`; `analysis/DefinitionLocator.java:171-199`; `analysis/LibrarySources.java:103-116`.
- Fix: one `findSourceFile(package, topLevelName, moduleName, roots)` in `TypeSourceLocator`; the server calls it.

### CR-13 — Qualified and binary names are split by hand in about twelve places

`lastIndexOf('.')` / `lastIndexOf('$')` for package, simple or top-level name, in three modules.
The "is it `java.lang`" check is repeated four times.

- Where: `analysis/completion/QualifiedNames.java:9`; `ImportAnalyzer.java:63, 69`; `TypeSourceLocator.java:49-51`; `RenameBindingCheck.java:118`; `CandidateGenerator.java:248`; `WorkspaceTypeIndex.java:295`; `server/ReferenceCandidatePlanner.java:151, 156, 233, 245`; `server/StackFrames.java:39-48`; `server/LatheEngine.java:499-502`; `server/run/RunConfigWriter.java:116-119`; `core/typeindex/ClassFileTypeScanner.java:197-203`.
- Fix: one public `BinaryNames` in core (`packageOf`, `simpleNameOf`, `topLevelOf`, `isJavaLang`), replacing `QualifiedNames`.

### CR-14 — Package name from a directory: three copies

- Where: `core/FileUtil.java:125-128` (private `toPackageName`); `server/SourceLayout.java:327-329`; `server/SourceTypeScanner.java:49-56`. `SourceTypeScanner.java:31-35` also strips `.java` instead of calling `FileUtil.javaTypeName`.
- Fix: make `FileUtil.packageName(root, dir)` public and use it.

### CR-15 — Test position id format is defined twice

`"%s#%s(%s)"` is in `RunnableScanner` and in `TestId`, whose Javadoc says it must match "exactly".
The id is parsed back with `split("#")` / `indexOf('#')` in three more places, two of them with different split limits.

- Where: `analysis/RunnableScanner.java:175`; `server/run/TestId.java:32`; `server/RunCoordinator.java:660, 931`; `server/run/RunConfigWriter.java:107`; `server/engine/LatheTestRun.java:87`.
- Fix: one `positionId(class, method, params)` beside `RunTarget`, with accessors for the class and method parts.

### CR-16 — Four hand-written JSON codecs for LSP `data` payloads, and two stray `Gson`s

- Where: `server/DiagnosticPayloadCodec.java:20-38`; `analysis/TypeHierarchyItemDataCodec.java:9-35`; `analysis/CallHierarchyItemDataCodec.java:167-225`; `server/CompileCheckedActions.java:35, 137-157`; `server/LatheWorkspaceService.java:34`.
- Fix: `Json.toTree(Object)` / `Json.fromTree(Object, Class<T>)` in core (Gson binds records and runs their compact constructors); drop the per-type encode/decode.

### CR-17 — "Read the JSON file if it exists" is written six times

Each site does `if (!Files.exists(f)) return empty; Json.read(...)`, some with a null-to-empty step.
`RunConfigWriter.readOrEmpty` is a near copy of `RunConfigReader.readLayer`.

- Where: `server/workspace/WorkspaceManifest.java:93-98`; `server/run/RunConfigReader.java:47-54`; `server/run/RunConfigWriter.java:67-73`; `server/run/TestDiscoveryStore.java:50-55`; `server/run/LaunchReader.java:41-51`; `server/format/WorkspaceStyle.java:88-90`.
- Fix: `Json.readIfPresent(Path, Class<T>)` returning `Optional<T>`; one `RunConfigFile.read(Path)` for reader and writer.

### CR-18 — `.lathe/` and `.lathe/<module>` are resolved by hand about twenty times

`LatheLayout.moduleDir(module)` is used once.

- Where (server): `server/SourceLayout.java:49` (its own `moduleLatheDir`); `server/run/LaunchReader.java:54-56`; `server/run/MainClassScope.java:20`; `server/run/SourceSetScope.java:20`.
- Where (build side): `install/CapturePruner.java:27`; `install/LauncherCopier.java:30`; `install/WorkspaceManifestWriter.java:49`; `install/StyleWriter.java:71, 90`; `maven/InitMojo.java:35`; `openjdk/OpenJdkSync.java:261` (private copy); `LatheLifecycleParticipant.java:44, 92, 116`; `junit/LaunchCapture.java:49`; `compiler/LatheCompiler.java:152`.
- Fix: `LatheLayout.latheDir(root)` and `moduleDir(root, module)` taking the root; delete `SourceLayout.moduleLatheDir`. `LaunchCapture` may need to keep its copy if it is one of `lathe-junit`'s release-8 classes.

### CR-19 — `FileUtil` walkers and suffix checks are bypassed

- Walks: `core/typeindex/ClassFileTypeScanner.java:85-96`; `server/module/WorkspaceModuleRegistry.java:83-85`; `server/ReferenceCandidateIndex.java:155-158`; `mcp/LatheMcpServer.java:259-267`; `maven/extension/LatheLifecycleParticipant.java:121-128`.
- Suffixes: `server/ClassOutputs.java:44, 141` (the second is redundant after the first); `server/run/CompletenessGate.java:74`; `server/module/LombokProcessors.java:73`; `maven/ReactorProjects.java:131, 148`; `maven/MavenSyncScript.java:116`.
- Fix: `FileUtil.walkFiles`, `filesIn`, `isClassFile`, `isJarFile`.

### CR-20 — Small server file helpers written more than once

- `mtime`: `server/WritePass.java:891-897` and `server/WorkspaceWatcher.java:173-179` are identical; `ResourceMirror:106` and `ChangeVerifier:310` call `WritePass.mtimeMillis` across components.
- `deleteQuietly`: `server/run/LaunchSession.java:253`, `server/format/ExternalCommandFormatEngine.java:66`, `server/format/FileCommandFormatEngine.java:53`, each with a different log level.
- Temp source file: `server/module/ModuleSourceCompiler.java:288-312` re-does `ServerFiles.writeTempSourceFile`; only the Lombok step differs.
- Process-tree kill: `server/format/FormatProcess.java:50-51`, `server/run/LaunchSession.java:96-109`.
- Fix: put all of these in `ServerFiles` / `ChildProcesses`.

### CR-21 — Named daemon thread factory written eight times

- Where: `core/BuildLock.java:63-67`; `server/LatheServer.java:73-75` (not a daemon); `server/RunCoordinator.java:321-325`; `server/debug/DapHost.java:50-51`; `server/FormatService.java:106-110`; `server/run/LaunchSession.java:72-73, 82`; `server/module/CompilationWorker.java:92-97`; `server/ServerEventLoop.java:50-55`.
- Fix: one `daemonThread(name, task)` in core.

### CR-22 — `Positions.offset(String, Position)` is private, so it is copied

- Where: `analysis/Positions.java:95-97`; `server/format/JavaFormatter.java:59-61` (word for word); `analysis/RenameBindingCheck.java:84-86, 306-309, 350`; `analysis/RenameProvider.java:43`.
- Fix: make it public and delete the copies.

### CR-23 — Lathe's version and Maven coordinates are defined several times

`PluginProps` and `ExtensionProps` load `pom.properties` with identical code and both swallow `IOException`; core already has `LatheBuildInfo.version(Class)`.
The group id and `lathe-junit` / `lathe-compiler` / `lathe-maven-plugin` artifact ids are retyped outside `LatheLayout`.

- Where: `install/PluginProps.java:9-13, 23-40`; `maven/extension/ExtensionProps.java:14-32`; `maven/extension/LatheModelInjector.java:22-24`; `core/LatheLayout.java` (`Maven.LATHE_GROUP_ID`).
- Fix: one version helper in core that fails fast; all Lathe coordinates in top-level `LatheLayout`.

### CR-24 — Literals that duplicate core constants

- `module-info` / `package-info` names: `analysis/DefinitionLocator.java:119-120`; `core/typeindex/ClassFileTypeScanner.java:167-168`; `server/SourceTypeScanner.java:38-39`; `server/NewTypeTemplates.java:43, 55`; `server/format/EclipseFormatEngine.java:30`.
- `openjdk/OpenJdkSync.java:36` `"classes"` = `LatheLayout.CLASSES_DIR`; `gradle/LatheCaptureTask.java:44` `"none"` = `LatheFlags.PROC_NONE`; `gradle/LatheTestCapture.java:56` `"JDK_JAVA_OPTIONS"` is in `LatheLayout.JVM_OPTIONS_VARIABLES`; `openjdk/SyncMojo.java:24, 33` `"mvn %s"` and `"lathe:sync"`; `maven/MavenStyle.java:70` `"lathe-style.json"`; `maven/MavenTestFilter.java:19`, `maven/ReactorProjects.java:43` `"org.apache.maven.plugins"`.
- `lathe-it-support`: `Os.java:8, 13-15, 20-23` re-derive `Platform.WINDOWS`, `LatheLayout.scriptName` and `"servers"`; `LspSession.java:75` types `"lathe.scanIntervalMs"`.
- Test status wire values `"passed"/"failed"/"skipped"`: `server/engine/LatheTestRun.java:31-33`. The copy in `lathe-test-runner`'s `ResultsListener.java:37-39` is deliberate (no core dependency, drift-guard test).
- Fix: use the constants; give `lathe-it-support` a core dependency (it is test-only); `MavenModuleConfig.java:28`'s `"UTF-8"` default is redundant with `ModuleConfigData`.

### CR-25 — Classpath strings split, joined and tokenised in several modules

- Split/join on `File.pathSeparator`: `junit/LaunchCapture.java:248-257`; `openjdk/CmdlineReader.java:145-147`; `maven/MavenSyncScript.java:106, 126`; `install/ServerInstaller.java:180`; `server/run/LaunchPlan.java:181`.
- Whitespace tokenising of JVM flags: `maven/MavenJvmArgs.java:104-107` (drops blanks); `gradle/LatheWorkspaceTask.java:193` (bare `split`, yields `""` for an empty value); `server/run/Launcher.java:94`.
- Fix: `splitPathList` / `joinPathList` in core; one `tokens(String)` beside `install/JvmArgsWriter`.

### CR-26 — "Is this a named type" and "is this a valid name" decided in several places

- Where: `analysis/TypeSourceLocator.java:69-72`; `analysis/WorkspaceTypeIndex.java:201-204`; `core/typeindex/ClassFileTypeScanner.java:213-214`; `analysis/RenameProvider.java:31-33` vs `analysis/internal/VariableNameSuggester.java:185-190`.
- Fix: `TypeIndexEntry.isNamed()`; `legalize` calls `RenameProvider.isValidName`.

### CR-27 — `ReferenceCandidatePlanner` re-derives `ReferenceTarget` predicates

It lists a subset of the local-scope kinds (missing `BINDING_VARIABLE`, `TYPE_PARAMETER`) and repeats `kind.isClass() || kind.isInterface()`.

- Where: `server/ReferenceCandidatePlanner.java:60-63, 67`; `analysis/ReferenceTarget.java:83-90`.
- Fix: `target.isLocalScope()` and `target.isType()`.

### CR-28 — `CollectionUtil.last` exists but `x.get(x.size() - 1)` is still written

- Where: `analysis/SignatureHelpResolver.java:125`; `analysis/completion/SentinelParser.java:520`; `analysis/refactor/ExtractFieldProvider.java:243`; `analysis/refactor/ExtractVariableProvider.java:459`; `analysis/refactor/TryWithResourcesProvider.java:89, 95, 179`; `server/LatheEngine.java:667`.

### CR-29 — Two subtype graphs

`ReactorTypeIndex` keeps its own per-owner subtype map and BFS beside `WorkspaceTypeIndex.directSubtypesByParent` / `transitiveClosure`, which it also walks.
The likely reason is that `WorkspaceTypeIndex` drops entries that share a binary name; check whether one owner-aware graph can serve both.

- Where: `server/ReactorTypeIndex.java:46, 206-227, 248-257`; `analysis/WorkspaceTypeIndex.java:175-186, 374-388`.

### CR-30 — `TypeKind` names two different things

`server/TypeKind` (new-type flavours, including `TEST`, `PACKAGE_INFO`) and `core/typeindex/TypeKind` share a simple name.

- Fix: rename the server one `NewTypeKind`.

## 3. Server: session components

### CR-31 — `ReferenceSearch` writes its search pipeline twice

References (`searchFutures` + `searchOpenDocument` + `searchDiskChunk`) and incoming calls (`incomingCallFutures`) have the same shape: for each open document, touch the LRU, analyse, check cancel, query, advance progress; disk candidates batched by `SEARCH_BATCH_SIZE` through `searchFiles`.
Only the per-file query differs.
The progress total and the open-document `session.analysis(doc.uri(), doc.content(), doc.version(), …)` call are repeated too.

- Where: `server/ReferenceSearch.java:449-454, 465-541, 510, 671, 1130, 1245-1250, 1355-1402, 1374`.
- Fix: one generic `searchModule(inputs, fileSearch, cancel, progress)` and a shared `beginSearch(progress, title, inputs)`.

### CR-32 — "Open document → module worker" routing is written about ten ways

`FeatureRouter` has `positionFeature`, `openDocFeature`, `routeFeature` and `openRoute`, and callers still hand-roll the same steps.
Several route twice (`completionFuture`, `codeActionFuture`, `missingImportsFuture`).
`foldingRangeFuture` is a copy of `openDocFeature` that logs a WARNING no other miss logs.

- Where: `server/FeatureRouter.java:86-170`; `server/DocumentFeatures.java:98-129, 333, 359-385, 416-425, 468-479`; `server/RenameService.java:79-83, 435-443`; `server/ReferenceSearch.java:97-105, 176-184, 821-827`.
- Fix: one `FeatureRouter.openModuleRoute(uri)`; pass the computed `CompilerRoute` into `routeFeature`; `foldingRangeFuture` uses `openDocFeature`.

### CR-33 — `RenameService` copies `ReferenceSearch`'s implementations pipeline

`inheritedOutsideFamily` repeats `implementationsIn`, and five `ReferenceSearch` members are package-visible only for it.
`renameFuture` repeats `referencesFuture`'s `resolveTarget → referenceSearchTarget → searchReferencesForTarget` chain.

- Where: `server/RenameService.java:210-228, 444-507`; `server/ReferenceSearch.java:941-956` (`subtypeImplementationFiles`, `seesDeclaration`, `declaringConfigFor`, `planSearchScope`, `unionNames`).
- Fix: one `implementationFilesQuery(...)` and one "resolve and search target" step in `ReferenceSearch`; narrow what it exposes.

### CR-34 — `WorkspaceSession` holds work outside its wiring role

CLAUDE.md lists its role as document lifecycle, compile scheduling, the rename transaction, the scan tick and reload.
It also holds memory release, the sync prompt and the write-diagnostics merge.
`layout()` is a session forwarder used six times by `LatheWorkspaceService`; `notes` forwards to `verifier.notes`.
`initialize` and `reload` duplicate opening and closing the progress task; constants sit between methods.

- Where: `server/WorkspaceSession.java:83, 181-183, 186, 192-200, 231-276, 565-604, 613-655, 701-751, 972-977`.
- Fix: write-diagnostics merge into `DiagnosticPublisher`; memory release into a small component; drop the forwarders; one `withWorkspaceProgress`.

### CR-35 — Run and debug are twinned at every layer

`RunCoordinator` has `runTestFuture`/`runMainFuture`/`debugTestFuture`/`debugMainFuture`, each with its own `start…` method, and `runNamedFuture`/`debugNamedFuture` switch over the same four.
`LatheWorkspaceService` mirrors them with four near-identical handlers.
The class part of a selection is split two ways; the `CLASS || METHOD` test is repeated; `noCapturedLaunch` re-reads the test filter `withTestFilter` already read.

- Where: `server/RunCoordinator.java:99-131, 151-203, 657-660, 755, 928-933, 943-959`; `server/LatheWorkspaceService.java:121-155`.
- Fix: a small sealed `LaunchTarget` (tests or main) with its label and `prepare`; one `run(target, …)` and one `debug(target, …)`. The Neovim side is CR-122.

### CR-36 — `ModuleSearchPlan` is copied field by field, and the package scope is computed two ways

- Where: `server/ReferenceSearch.java:362, 369-372, 399, 683-693, 1206-1208, 1219-1222, 1337`.
- Fix: record withers (`withoutExposure()`, `deferredOnly()`, `filtered(...)`); one way to get the package scope.

### CR-37 — Dead branch in `ReferenceSearch.fileQueryFuture` (verified)

Its only caller calls it only for open documents, so its disk half never runs.

- Where: `server/ReferenceSearch.java:1059-1060, 1114-1151`.
- Fix: keep the open-document query and inline it into `filesQueryFuture`.

### CR-38 — `ChangeVerifier` holds queries that are not verification

`textMentionSources` serves rename, `classifyPaths` serves `analyze_change`, `moduleSummaries` serves the `modules` tool; `moduleSummaries` is an instance method forwarding to a static twin.

- Where: `server/ChangeVerifier.java:49-53, 186-200, 212-239`.
- Fix: move them to `WorkspaceQueries`; drop the twin.

### CR-39 — `WritePass` re-implements a file helper and has leftovers

- `lombokConfigCandidates` re-implements `ServerFiles.ancestorFiles(dir, name)`, which `ModuleSourceCompiler` uses for the same file.
- Two consecutive `if (!building)` blocks with a stray `checkOnLoop()` inside the first.
- `refreshIfWrittenElsewhere` adds `moduleDir` and then `downstreamModuleDirs(moduleDir)`, which already contains it.
- `reconcileNow` (a test seam) and `writeReported` end with the same `requestPass(..).thenAccept(stale -> {})`.
- "First error line of a diagnostic" is also in `CompileCheckedActions.errors`/`message`.
- Where: `server/WritePass.java:300-308, 484-487, 512-516, 561-572, 878-879`; `server/CompileCheckedActions.java:98-135`.

### CR-40 — Detached progress tasks are hand-rolled three times

`progressReporter.open(null, new CompletableFuture<>())`, `begin(title, 1)`, `finish`.

- Where: `server/WorkspaceSession.java:192, 745`; `server/WritePass.java:541`.
- Fix: `ProgressReporter.background(title)`.

### CR-41 — A deleted source's path is rebuilt from its stamp key three times

Each assumes the first source root (`ChangeVerifier` tries existing roots first).

- Where: `server/ClassOutputs.java:83-86`; `server/WritePass.java:526-527`; `server/ChangeVerifier.java:108-114`.
- Fix: one helper on `TreeState` or `ModuleSourceConfig`.

### CR-42 — "Module of a URI" and "is this document open" are repeated

`state.registry().moduleSourceFor(LatheUri.toPath(uri))` appears six times; `docs.get(uri) != null` five times; `WorkspaceSession.inSourceTree` and the open-docs filter in `planModuleSearch` are the same predicate.

- Where: `server/WorkspaceSession.java:273, 863-865, 864, 982`; `server/ReferenceSearch.java:791-799, 797, 863`; `server/RenameService.java:117`.
- Fix: `DocumentRegistry.isOpen(uri)` and one `moduleOf(uri)`.

### CR-43 — Module-relative path is spelled three ways

`state.layout().moduleRel(config)`, `SourceLayout.moduleRel(state.root(), dir)`, and `FileUtil.slashed(state.root().relativize(moduleDir))`; the third is relative to the real module dir, not `.lathe/`, but reads like the same thing.

- Where: `server/ChangeVerifier.java:164, 227, 231, 253, 335`.
- Fix: `SourceLayout.moduleRel(Path moduleDir)`; name the third distinctly.

### CR-44 — `ReferenceCandidatePlanner` leftovers

The 2-argument `planCandidates` is used only by tests; `isUnder(path, roots)` duplicates `ReferenceSearch.isInPackageScope(path, roots, null)`.

- Where: `server/ReferenceCandidatePlanner.java:40-42, 138-140`; `server/ReferenceSearch.java:695-702`.

### CR-45 — `Module` vs `External` route branched by hand

`diagnosticsFuture` and `onSave` branch on the route themselves, while `publishDiagnostics` already encodes the decision.
`ReferenceSearch.implementationFuture` and `LatheTextDocumentService.emptyLocationResult` build an empty definition result that `FeatureAnswers.definitionResult` already builds.

- Where: `server/WorkspaceSession.java:315-346, 406-416, 810-815`; `server/ReferenceSearch.java:823`; `server/LatheTextDocumentService.java:100-103`.

## 4. MCP and LSP adapters

### CR-46 — Finding a symbol's column is split between MCP and the engine, one full request per try

MCP lists the columns where the symbol's name appears and calls the engine once per column.
Each call re-reads the file, reconciles, recompiles and re-checks the name; the cross-module exception `NotOnName` is control flow.

- Where: `mcp/LatheMcpTools.java:662-732`; `server/LatheEngine.java:290-308, 348, 364, 372, 385, 393`.
- Fix: `positionWork` takes `(line, column?, symbol?)` and tries the columns in one request; drop `nameColumns`, public `sourceLine` and `NotOnName`.

### CR-47 — `rename` runs the position request twice, then a third for notes

Each `positionWork` repeats the name check, file read, reconcile, compile and `onNameFuture`; `wordAt` runs again after.

- Where: `server/LatheEngine.java:529-546`; `mcp/LatheMcpTools.java:487-489`.
- Fix: one `positionWork` that chains the mentions and the rename; reuse the original text for the old name.

### CR-48 — Read tools and write tools return notes two ways

Read tools return `Noted<T>`; `rename`, `format`, `fixImports` and `verifyChange` return bare values and MCP asks for notes separately, deciding itself which files the notes cover.

- Where: `server/LatheEngine.java:335`; `mcp/LatheMcpTools.java:488-489, 535, 545, 553`.
- Fix: write methods return `Noted<T>`, with notes read in the engine after `diskEdits.apply`.

### CR-49 — MCP argument parsing repeats itself

- Enum arguments are parsed three ways (`testScope`, `symbolScope`, inline `direction`); error messages repeat the allowed values by hand and the schemas list them again.
- The `maxResults` schema property is copied four times (the validation bug is CR-4).
- `rename_symbol`'s schema is hand-written only because `required` differs.
- `answer` catches only `NoWorkspaceException`, so a failure in the `LatheEngine` constructor escapes as an SDK error instead of an `isError` result.
- Where: `mcp/LatheMcpTools.java:118-119, 138-142, 170, 191-195, 226-227, 243-244, 384-394, 497, 574-598, 630`.
- Fix: one `enumArg(request, name, Class<E>, default)`; a `maxResultsProperty(noun)` helper; `positionSchema` takes extra required names.

### CR-50 — Result capping: three records of one shape, built through each other

`LatheReferences`, `LatheImplementations` and `LatheCallHierarchy` are each `(total, truncated, list)`.
`capImplementations` builds a `LatheReferences` only to copy it; the "N things (showing first K)" header is written twice in `McpText`.

- Where: `server/engine/LatheReferences.java`, `LatheImplementations.java`, `LatheCallHierarchy.java`; `server/LatheEngine.java:241-246, 265-271, 852-856`; `mcp/McpText.java:86-89, 256-259`.
- Fix: one generic capped-list record and `cap(list, max)`; one `countedHeader(...)`.

### CR-51 — `analyze_change` builds snippets it throws away, as a five-level pyramid

It caps references through `toLatheLocation`, which reads a source line per hit (`Files.readAllLines` each time), then uses only the URIs.
Hover, implementations and references are nested `thenCompose` though none uses the previous result.
`references`, `findImplementations` and `callHierarchy` also re-read a file per hit.

- Where: `server/LatheEngine.java:385, 1026-1093, 1132, 1164`.
- Fix: count from raw `Location` URIs; read each file's lines once per answer; combine the three facts with `thenCombine`.

### CR-52 — LSP document handlers repeat the same preamble about fourteen times

Canonical URI, `ignoreNonFile`, empty answer, `host.onLoopFuture(...)`; the `locations(...)` helper covers only four handlers.
`instantiations` and `typeHierarchy` in the workspace service canonicalize but skip the non-file check.

- Where: `server/LatheTextDocumentService.java:108-114, 144-148, 164-167, 175-178, 192-195, 202-207, 218-222, 244-249, 262-266, 275-279, 286-290, 333-348, 353-357, 380-384, 406-409`; `server/LatheWorkspaceService.java:211-228`.
- Fix: a generic `<T> atDocument(op, doc, empty, fn)` used by every handler.

### CR-53 — `LatheWorkspaceService` repeats its argument parsing

`json.has("token") ? … : ""` four times; `parseRunTestArgument` inlines `parseSelections`; string-array reading three times; `(JsonObject) params.getArguments().get(0)` throughout; `GSON.fromJson(..., TextDocumentPositionParams.class)` twice.

- Where: `server/LatheWorkspaceService.java:211-228, 293, 315, 342, 350, 357-361, 362, 370, 381-386, 392`.
- Fix: `token(json)`, `stringList(json, key)`, `firstArg(params)`, `positionArg(params)`.

### CR-54 — The workspace formatter is resolved in two places

- Where: `server/LatheEngine.java:181-184`; `server/LatheLanguageServer.java:75, 190-193`.
- Fix: one method returning engine and spec.

### CR-55 — "Reconcile, then read" is inlined outside `fileWork`

- Where: `server/LatheEngine.java:328, 343-346, 464-467, 891-896, 902-907`.
- Fix: a `freshened(fn)` `LoopWork` helper used by `fileWork`, `searchSymbols` and `verifyChange`.

### CR-56 — `LatheEngine` small duplications

`origin(LatheUri.toPath(...))` four times; `outline`, `format` and `fixImports` compute the URI outside `fileWork` and receive it inside under another name.
Identity `thenApply` calls widen generic types (`LatheTextDocumentService.java:270, 412, 435`; `LatheWorkspaceService.java:78, 273`, the last a no-op).

- Where: `server/LatheEngine.java:422, 480, 555, 595, 651-657, 674-680, 876, 1133, 1139`.
- Fix: `origin(String uri)`; one URI per tool; widen the session method types.

### CR-57 — `LatheEngine` mixes request plumbing with algorithms (1205 lines)

Rename offset shifting (`renamedMentions`, next to `Positions.applyEdits`), JDK-internal ranking, test-target selection and format line spans live inside it.

- Where: `server/LatheEngine.java:100-107, 479-510, 605-630, 705-714, 797-821`.
- Fix: move each next to what it extends, so the engine reads as one request per tool.

## 5. Server subpackages

### CR-58 — The two `JavaSourceCompiler` implementations duplicate each other

- The 3-argument `compile` delegates to the 4-argument one in both classes, while the interface default does the reverse.
- `ExternalCompiler` writes `new AttributedFileAnalysis(null, null, null, null, null)` instead of `diagnosticsOnly()`.
- `ExternalCompiler.close` is a hand copy of `ModuleSourceCompiler.closeLogged`; the temp dir is `td` in one and `tempDir` in the other.
- Where: `server/module/ModuleSourceCompiler.java:92-94, 380-392`; `server/module/ExternalCompiler.java:55-57, 70, 101-113`.
- Fix: one abstract `compile`, a default with a no-op checker (or none); share `closeLogged`. The temp file is CR-20.

### CR-59 — `JavacRunner` wraps admission three times with redundant cancel checks

`CompilationAdmission.run` already checks before and after acquiring; each `JavacRunner` method adds its own.
`CompilationAdmission.run(Supplier)` is used only by its test.

- Where: `server/module/JavacRunner.java:47-54, 72-79, 131-138`; `server/module/CompilationAdmission.java:23-35`.
- Fix: put the post-operation check in `CompilationAdmission.run`; drop the extras and the test-only overload.

### CR-60 — "Deepest source root holding the file" is reimplemented five times

`ModuleSourceCompiler.ownSourceRoot` copies `ModuleSourceConfig.sourceRootOf` and adds the generated dir; `WorkspaceModuleRegistry.moduleSourceFor` re-tests both by hand; the same "max by name count" walk is in three more places.

- Where: `server/module/ModuleSourceCompiler.java:326-331`; `server/module/ModuleSourceConfig.java:81-85`; `server/module/WorkspaceModuleRegistry.java:131-137`; `server/workspace/ResourceRootIndex.java:59-61`; `server/workspace/WorkspaceManifest.java:176-179`; `server/SourceLayout.java:246-247`.
- Fix: `ModuleSourceConfig.ownRoot(file)`; optionally `ServerFiles.deepestContaining(roots, file)`.

### CR-61 — `LaunchPlan`'s three builders repeat the same prologue

Each rewrites the module path and classpath, calls `baseJavaArgs`, adds the JDWP agent and calls `addRuntimeShape`; `forTest` and `forTestMain` differ only in the entry point.

- Where: `server/run/LaunchPlan.java:24-58, 60-85, 95-121`.
- Fix: JDWP into `baseJavaArgs`; one `runtimeArgs(...)`; each builder keeps only its entry point.
- Status: closed as not worth it (#208): the builders are short and consistent, and merging them needed a record, two factories and a shared step.

### CR-62 — Launch notes travel as a text prefix

`Launcher` writes its notes as transcript lines prefixed with `NOTE_PREFIX`; `LatheTestRun` parses them back out.
`LaunchOutcome.notes` holds the gate's notes, and `withNotes` replaces instead of appending.

- Where: `server/run/Launcher.java:42-45, 105`; `server/engine/LatheTestRun.java:75-82`; `server/run/LaunchOutcome.java:38`; `server/RunCoordinator.java:243, 566`.
- Fix: keep launch notes in `LaunchOutcome.notes`; `withNotes` appends; drop the prefix parsing.

### CR-63 — The results sink is read twice

- Where: `server/run/LaunchSession.java:149-169, 217-238`.
- Fix: one `readResults(from)`.

### CR-64 — `MainClassScope` and `SourceSetScope` overlap, and source-set trees are classified by hand

Both build `<class>.class` (one with `+`, one with `formatted`) and probe the classes and test-classes dirs; their one caller combines them, then calls `SourceSetScope` again.
`startsWith(SOURCE_SET_TREE_PREFIX)` appears four times; tree labels are built twice; `treeRank` ranks the same kinds.

- Where: `server/run/MainClassScope.java:20-21`; `server/run/SourceSetScope.java:20-29`; `server/RunCoordinator.java:505-506, 659, 669`; `server/SourceLayout.java:253`; `server/run/CompletenessGate.java:188-198`; `server/module/WorkspaceModuleRegistry.java:65-72`.
- Fix: one `treeOf(root, module, class)`; `LatheLayout.isSourceSetTree(tree)` and one label helper.

### CR-65 — Classpath plus module path and "configs by classes dir" are built more than once

`Stream.concat(remappedClasspath, remappedModulepath)` three times; a first-wins map keyed by `latheClassesDir` twice.
The registry keys its worker map by `latheClassesDir().toString()`, unlike every other map.

- Where: `server/module/ModuleSourceConfig.java:114`; `server/module/WorkspaceModuleRegistry.java:44-50, 169-171, 229`; `server/workspace/WorkspaceModuleGraph.java:45-51, 60-62`.
- Fix: `ModuleSourceConfig.remappedPaths()`; key by `Path`. `dropFromAllCaches` (232-237) can use `allWorkers()`.

### CR-66 — `WorkspaceManifest` is boilerplate and forwards to `ResourceRootIndex`

A hand-written class with eleven fields and getters; three methods only delegate; `load` is about 70 lines; `ResourceRootIndex.sourceDirModules` returns a mutable map; the `workspace.json` path is built in two places; two stacked comments on `builtModuleDirOf`, the first a leftover.

- Where: `server/workspace/WorkspaceManifest.java:28-98, 172-174, 190-201`; `server/workspace/ResourceRootIndex.java:33-37`; `server/WorkspaceWatcher.java:56-57`.
- Fix: a record with a static `load`; expose `resourceRoots()`; immutable map; one path helper.

### CR-67 — `LatheEvaluationProvider` repeats two blocks

`evaluate` and `evaluateGuarded` share the `withThreadLock` + try/completed/failed body; `interpretOn` and `interpret` share the `fileFor → onWorker → orElseThrow` chain.

- Where: `server/debug/LatheEvaluationProvider.java:79-90, 98-127, 173-184, 283-312`.
- Fix: `guarded(thread, expr, callable)` and `attribute(file, fn)`.

### CR-68 — Layered config files use two vocabularies

Run config says "shared/local", style says "committed/generated" for the same idea; the local run-config path is resolved in both reader and writer.

- Where: `server/run/RunConfigReader.java:33-38`; `server/run/RunConfigWriter.java:38-39`; `server/format/WorkspaceStyle.java:23-26`.

### CR-69 — Format engine duplications

The positive-timeout check is identical in two engines, which call the same thing `workingDir` and `workspaceRoot` in different parameter order; `FormatEngine`'s comment names a `file` parameter that is now `target`; `GjfFormatEngine.gjf()` and `EclipseFormatEngine.jdt()` are the same lazy load; properties-to-map is in `EclipseFormatEngine` and `RunOverlay`.

- Where: `server/format/ExternalCommandFormatEngine.java:24-28`; `server/format/FileCommandFormatEngine.java:30-35`; `server/format/FormatEngine.java:7`; `server/format/GjfFormatEngine.java:68-74`; `server/format/EclipseFormatEngine.java:67-73, 138-139`; `server/run/RunOverlay.java:135-136`.

### CR-70 — `CompilationWorker` names the session three ways

`context`, `contextFactory`, `ctx`, while its methods and callers say "session"; `compile(request)` is used only by its test; `submit(fn)` / `submit(fn, checker)` are twins.

- Where: `server/module/CompilationWorker.java:41-43, 175-177, 186, 221`.

### CR-71 — Small `run/` leftovers

`PortUtil` is a one-method class used in two copied lines; `LaunchOverlay.NONE` is test-only; the three `RunOverlay.applyTo…` methods rebuild the same `ResolvedLaunch` tail.

- Where: `server/run/PortUtil.java`; `server/RunCoordinator.java:182, 195`; `server/run/RunOverlay.java:32-90`.
- Fix: `JdwpOptions.onFreePort()`; a private `resolved(...)` in `RunOverlay`.

## 6. Analysis: root package

### CR-72 — The three locators repeat their name and factory code

Besides CR-1: the `new X()` name extraction is copied three times; `rangeFor` is identical in both call-hierarchy locators; the static `scan`/`references` factories repeat the same construction.

- Where: `analysis/ReferenceLocator.java:74-90, 132-139`; `analysis/CallHierarchyIncomingLocator.java:71-86, 139-150, 255-257`; `analysis/CallHierarchyOutgoingLocator.java:52-62, 102-113, 152-154`.
- Fix: `nameRange(start, name)` and `newClassName(NewClassTree)` in `SourceTreeLocator`.

### CR-73 — The breadth-first supertype walk is hand-written four times

`findContract` is also nested four levels deep.

- Where: `analysis/ReferenceTarget.java:465-492`; `analysis/DeclarationLocator.java:35-72`; `analysis/CallHierarchyItemDataCodec.java:141-164`; `analysis/FamilyExposure.java:147-167`. Related: `HoverFormatter.overridden` (245-270) walks the superclass chain itself and calls `overriddenMethods`; `ReferenceTarget.overriddenOutsideFamily` and `MethodImplementationLocator.inheritedOutsideFamily` (126-138) are two separate checks.
- Fix: one `supertypes(TypeElement, Types, Elements)` stream, nearest first, deduplicated; build the others on it.

### CR-74 — Source text is read in eight places with three error policies

`CodeActionSupport.source` is a verbatim copy of `DeclarationNames.source`.
`FoldingRangeScanner` re-reads text `FileFeatures.foldingRange` already holds.
`DeclarationNames.declarationNamePosition` re-reads the file on each call and throws `IOException`; its callers swallow it, log it, or wrap it, each differently.

- Where: `analysis/SourceTreeLocator.java:36`; `analysis/TokenScanner.java:65`; `analysis/FoldingRangeScanner.java:33`; `analysis/DeclarationNames.java:52, 119, 237-245`; `analysis/refactor/CodeActionSupport.java:46-56`; callers `CallHierarchyItemDataCodec:49-55`, `WorkspaceSymbolResolver:153`, `DefinitionLocator:88, 231`, `TypeSourceLocator:96`, `MethodImplementationLocator:298`, `RenameBindingCheck:353`.
- Fix: one public `sourceText(CompilationUnitTree)` with one policy; an overload of `declarationNamePosition` taking the text.

### CR-75 — "Element kind is a type" and "local scope kind" are spelled out about ten times

Some lists miss `RECORD` or `ANNOTATION_TYPE`.
`ReferenceLocator.roleForElement` and `roleForMemberSelect` share an identical 10-line block.

- Where (root): `analysis/ReferenceTarget.java:83-90, 114, 369`; `ReferenceLocator.java:340-387`; `ReferenceFeatures.java:196-211`; `RenameConflicts.java:33-39, 63, 93`; `RenameBindingCheck.java:101`; `TokenScanner.java:277, 301`.
- Where (completion): `ImportCompletionProvider.java:123-129`; `CandidateGenerator.java:127-132`; `SimpleNameProvider.java:163-170`; `CandidateFactory.java:107-113, 179`; `TypeReferenceCompleter.java:331-336`; `CompletionEngine.java:512-517`.
- Where (refactor): `ExtractionSupport.java:374-380, 769-775`; `DeclarationNameCompletionProvider.java:114-119`.
- Fix: `kind.isClass() || kind.isInterface()`, and one `isLocalScopeKind`; one kind → `CandidateKind` mapping.

### CR-76 — Line/character to tree path is done four ways, and the root package reaches into `refactor/`

`JavacDiagnosticMapper.pathAtStart` and `CodeActionSupport.pathAt` are the same; `JavacDiagnosticMapper` calls the `refactor` copy.
`SourceAnalysisSession.cursor` and `FileFeatures.enclosingBinaryName` inline the steps; `NavigationFeatures.signatureHelp` recomputes `Cursor.offset()`.
Five refactor providers inline it again.

- Where: `analysis/JavacDiagnosticMapper.java:159-171, 262`; `analysis/refactor/CodeActionSupport.java:74-79`; `analysis/SourceAnalysisSession.java:365-369`; `analysis/FileFeatures.java:106-111`; `analysis/NavigationFeatures.java:64-66`; `ConvertToVarProvider.java:56-58`; `ReplaceVarProvider.java:40-42`; `DeclareVariableProvider.java:42-48`; `TryWithResourcesProvider.java:50-57`; `ImportCandidates.java:90-91`.
- Fix: `CursorElements.pathAt(analysis, position)` in the root package.

### CR-77 — "Nearest enclosing class or method" hand-rolled beside `TreePaths`

- Where: `analysis/SignatureHelpResolver.java:258-267, 371-381`; `CallHierarchyIncomingLocator.java:227-233, 251-253`; `UnusedDeclarationScanner.java:265-267`; `FileFeatures.java:111-120`; `JavacDiagnosticMapper.java:270-280` (a recursive copy of `TreePaths.enclosingClass`).
- Fix: `TreePaths.enclosingMember(path)`; `TreePaths.nearest` elsewhere.

### CR-78 — "Same-file declaration, else parse the source file" is written three times

A point `Location(uri, new Range(pos, pos))` is built in eight places.

- Where: `analysis/DefinitionLocator.java:66-107, 217-234`; `JavadocLocator.java:136-152`; `SourceParser.java:88-103`; `NavigationFeatures.java:194-207`; points in `DefinitionLocator:86, 106, 136`, `NavigationFeatures:206`, `MethodImplementationLocator:226, 264`, `TextMentions:97`, `WorkspaceSymbolResolver:151`, `CallHierarchyItemDataCodec:79`.
- Fix: `parsePosition` through `SourceParser.parseDeclaration`; `Positions.point(uri, pos)`.

### CR-79 — Record component rules are written twice with different definitions

`isRecordComponentParameter` compares by name; `isCanonicalConstructorParameter` by type; `recordComponentField` is the inverse of `recordAccessorFor`.

- Where: `analysis/CursorElements.java:302-322`; `analysis/ReferenceTarget.java:179-206, 431-440`; `analysis/DefinitionLocator.java:145-169`.
- Fix: a small `RecordComponents` helper with one definition of "canonical".

### CR-80 — `ReferenceTarget` is built by hand

`HierarchyFeatures.outgoingCalls` rebuilds what `CallHierarchyItemData.target()` returns; `ReferenceTarget.from` has four near-identical 9-argument constructor calls, `RenameBindingCheck.renamedTarget` a fifth; `CallHierarchyItemDataCodec.overridden` re-derives `declarer`.

- Where: `analysis/HierarchyFeatures.java:108-118`; `ReferenceTarget.java:92-94, 114-165`; `RenameBindingCheck.java:102-113`; `CallHierarchyItemDataCodec.java:122-127`.
- Fix: use `data.target()`; `ofType` / `ofMember` factories and a copy method; drop the null-`trees` overload.

### CR-81 — Two whole-word identifier matchers

`DeclarationNames.findIdentifierFrom` and `TextMentions.wordStarts` check the same boundaries; identifier helpers live in `DeclarationNames`, not `Strings`.

- Where: `analysis/DeclarationNames.java:247-265`; `analysis/TextMentions.java:149-154`.

### CR-82 — `Positions.range` and `Positions.nameRange` rebuilt by hand

- Where: `analysis/DocumentSymbolScanner.java:124-128`; `ImportAnalyzer.java:36-37`; `CallHierarchyItemDataCodec.java:43-46`; `ReferenceLocator.java:455-457`; `UnusedDeclarationScanner.java:321-322`.

### CR-83 — Simple name of a type tree or a `TypeMirror`: about eight copies

- Tree: `analysis/TokenScanner.java:133-140`; `SourceParser.java:261-287`; `CursorElements.java:163-174`; `CallHierarchyOutgoingLocator.java:83-92`; `internal/VariableNameSuggester.java:115-126`; the `newClass` copies in CR-72.
- Mirror: `HoverFormatter.java:136-140`; `JavadocLocator.java:114-122`; `SourceParser.java:290-306`; `refactor/CodeActionSupport.java:58`.
- Fix: one `simpleName(Tree)` and one `simpleName(TypeMirror)`.

### CR-84 — The "cursor or empty" preamble is pasted sixteen times

Every cursor-reading method in `NavigationFeatures`, `ReferenceFeatures` and `HierarchyFeatures` opens with the same five lines.
"Constructor at `new X`, else the Javadoc reference or element" is computed three times.

- Where: `analysis/ReferenceFeatures.java:68-69, 182-184`; `analysis/NavigationFeatures.java:155-157`.
- Fix: `session.cursor(request).map(...)`; `Cursor.namedElement()`.

### CR-85 — `LibrarySources` runs two parallel jar lookups

- Where: `analysis/LibrarySources.java:195-270, 288-347`.
- Fix: one private `jarOf(entry, fm)`; both public methods map over it.

### CR-86 — Method signatures are rendered in three places

Hover and signature help both render return type, name and parameter lists; `MethodStubRenderer` is a third.
`NavigationFeatures.hover` wraps a label in a code fence itself and builds `TypeDisplayFormatter` twice; "source parameter names, else element names" is written twice.

- Where: `analysis/HoverFormatter.java:42-73, 289-292`; `analysis/SignatureHelpResolver.java:400-456`; `analysis/NavigationFeatures.java:97-106, 134`; `analysis/JavadocLocator.java:128-134`.

### CR-87 — Annotations are checked by name three ways

- Where: `analysis/GeneratedCode.java:25-38` and `TokenScanner.java:317-320` (string compare); `RunnableScanner.java:144-150` (qualified name); `HoverFormatter` uses `elements.isDeprecated`.
- Fix: one `hasAnnotation(element, qualifiedName)`; `Elements.isDeprecated` in `TokenScanner`.

### CR-88 — Import and package-name helpers are duplicated

`StaticImportCandidates.find` re-implements `importedStaticNames`; `importedQualifiedNames` and `importedStaticNames` are twins; `needsImport` recomputes the set per call; "package name or empty" three times; `CompletionEngine` rebuilds the static-import set.

- Where: `analysis/StaticImportCandidates.java:60-64`; `analysis/ImportAnalyzer.java:62-100`; `RunnableScanner.java:216-219`; `SourceAnalysisSession.java:268`; `completion/CompletionEngine.java:711-717`.

### CR-89 — Twins and small leftovers in the root package

- `NavigationFeatures.typeImplementations(request, index)` is test-only (373-376).
- `TypeHierarchyResolver.supertypes`/`subtypes` are one method in two styles, with `java.nio.file.Path` written fully qualified (28-48); also `SourceAnalysisSession:267`, `UnusedDeclarationScanner:70`.
- `CallHierarchyIncomingLocator.buildCallerItem` checks a kind that cannot fail; `callerPaths` and `callerRanges` are two maps with one key (36-37, 274-277).
- `JavaSourceCompiler.runAnalysis` ignores `analyzeSafely`'s result (141).
- `TokenScanner.emitIfInteresting` repeats `declarationTokenType`'s switch (273-304); `SymbolKinds.fromTypeIndex`/`fromTree` are the same switch (11-27).
- `WorkspaceTypeIndex.create` has two identical `groupingBy` blocks and `toLowerCase()` without a locale (143-169, 249, 271).
- `JavadocMarkdownPrinter.ownTag` calls a `BiPredicate<DocTree, MethodDoc>` with `null` (242-249), a trap for the next caller.
- `RenameConflicts.named` and `RenameBindingCheck.named` share a name but mean different things; `DocumentSymbolScanner.visitMethod` re-implements `DeclarationNames.declaredName`'s constructor rule (80-82).
- `ExpressionSplice.at` uses a three-part `+` concatenation (26).

## 7. Analysis: completion and code actions

### CR-90 — The three extract providers repeat their setup and scan the tree three times

Null check, `selectedExpression`, cast, type and `isDenotable`, positions, source text, `sourceType` — in each provider; the "occurrences → replace edits" block and the first-start/last-end span are copied too.

- Where: `analysis/refactor/ExtractVariableProvider.java:58-108, 340-349, 360-369`; `ExtractConstantProvider.java:41-78, 106-115`; `ExtractFieldProvider.java:44-101, 147-156, 164-173`.
- Fix: one `Selection` record built once in `ExtractionSupport`, plus `replaceAll(...)` and `span(...)`.

### CR-91 — CodeActions and TextEdits are built by hand about twenty-five times

`ExtractionSupport.action` exists but ten providers build the action themselves; `AddConstructorParameterProvider` borrows the extract helper for a non-extract action.
`ConvertToVarProvider.replaceEdit` is a verbatim copy of `ExtractionSupport.replaceEdit`; the point-insert pair appears about fifteen times.

- Where (actions): `ConvertToVarProvider.java:109-114`; `ReplaceVarProvider.java:73-78`; `DeclareVariableProvider.java:91-99`; `AddThrowsProvider.java:83-90`; `TryCatchWrapProvider.java:74-81`; `TryWithResourcesProvider.java:109-115`; `ImportQuickFixProvider.java:69-85, 119-127`; `MissingMethodImplProvider.java:89-96`; `AddConstructorParameterProvider.java:117`.
- Where (edits): `ConvertToVarProvider.java:189-194`; `ExtractionSupport.java:629-634, 643-652`; `ReplaceVarProvider.java:64-69`; `DeclareVariableProvider.java:84-88`; `TryCatchWrapProvider.java:72-75`; `TryWithResourcesProvider.java:107-110`; point inserts in `CodeActionSupport:196-203`, `ExtractVariableProvider:297-299`, `ExtractFieldProvider:217-220`, `AddConstructorParameterProvider:263-264, 279-280, 300-301, 353-354, 362-363, 381-382, 396-397`, `AddThrowsProvider:109-110, 127-128`, `MissingMethodImplProvider:76-80`, `CompletionEditApplier:246-248`.
- Fix: `action`, `quickFix`, `replaceEdit` and `insertAt` in `CodeActionSupport`; delete the copies.

### CR-92 — Completion resolves imports and scope by hand

Enum constants, annotations and nested types after `Outer.` each re-implement what `SourceTypeNames` and `ImportAnalyzer` do; `CompletionEditApplier` cuts the outer type from `item.getDetail()` strings while `MemberAccessCompleter` does it right.

- Where: `completion/CandidateGenerator.java:139-159, 184-283`; `AnnotationCompletionProvider.java:157-222`; `CompletionEditApplier.java:53-77, 205-218`; `MemberAccessCompleter.java:418-432`; `CandidateFactory.java:57-68`.
- Fix: route through `SourceTypeNames.sourceName`/`importFor` and `ImportAnalyzer`.

### CR-93 — The two expected-type resolvers duplicate each other

`methodSelectName` has four copies; the return-target type, expected type of an expression, `completeType`, receiver type, the varargs index formula and the error-tree loop are each written twice or more.

- Where: `completion/ExpectedValueResolver.java:235-256, 309-310, 384-385, 552-578, 621-622, 671-681, 764-769, 789-794, 803-819`; `LambdaExpectedReturnTypeResolver.java:94-118, 167-213, 251-256`; `CompletionLibraryRules.java:250-260`; `ReceiverResolver.java:292-298`; `internal/VariableNameSuggester.java:115-126`.
- Fix: one package-private `ExpectedTypes`; `methodSelectName` in `internal` or `TreePaths`.

### CR-94 — "Is this type usable" is checked about ten times

Not null, not ERROR, not NONE, optionally not VOID.

- Where: `completion/ExpectedValueResolver.java:129-132, 477, 500-502, 586, 601, 716, 789-794`; `ReceiverResolver.java:236-238`; `LambdaExpectedReturnTypeResolver.java:251`.

### CR-95 — Variable naming is split over four files with two `uniquify` rules

`elementTypeName` is verbatim in two providers plus a tree-based copy; three "taken names" scanners; `ExtractConstantProvider.uniquify` adds `_2`, `VariableNameSuggester.uniquify` adds `1`.

- Where: `refactor/ExtractVariableProvider.java:456-475`; `ExtractFieldProvider.java:240-265`; `ExtractConstantProvider.java:172-195`; `completion/SentinelParser.java:516-524`; `internal/VariableNameSuggester.java:205`.
- Fix: `VariableNameSuggester` takes the `TypeMirror`; one `declaredNames(path)`; one `uniquify` with a separator.

### CR-96 — "Find a class by simple name" is written three times, with different results

`SimpleNameProvider.findScopeClassElement` is a copy of `NameLookup.findClassElement` minus its null guard; `MissingMethodImplProvider.findClassPath` keeps the first match where the others keep the last.
Member signature keys are duplicated too.

- Where: `completion/SimpleNameProvider.java:132-140, 220-235`; `NameLookup.java:22, 26-47` (`LOG` unused); `refactor/MissingMethodImplProvider.java:103-120, 139-145`.

### CR-97 — Access checks are copied three times with different error handling

- Where: `completion/CandidateGenerator.java:381-398` (logs); `MemberAccessCompleter.java:281-295` and `TypeReferenceCompleter.java:546-560` (swallow).
- Fix: one `accessible(...)` (the sealed-class part is CR-8).

### CR-98 — `CompletionEngine` repeats itself, with one overlong method

`completeSimpleName` is about 125 lines and re-tests a condition it returned on, leaving a dead ternary branch.
"Expected type → `TypeElement`" four times; `new CompletionOutcome(items, req.cached() == null ? analysis : null)` four times; `req.cached() != null ? req.cached().analysis() : null` seven times; "is type X" checks in four places.

- Where: `completion/CompletionEngine.java:223, 226-351, 303-314, 464-498, 533-550, 622, 640, 655, 665`; `MemberAccessCompleter.java:42`; `TypeReferenceCompleter.java:95, 188, 217, 296, 349, 401-413`; `CompletionCandidateRanker.java:157-171`; `AnnotationCompletionProvider.java:73-75`.
- Fix: `CompletionRequest.cachedAnalysis()`, `expectedTypeElement(ctx)`, `isType(type, fqn)`; split `completeSimpleName` by case.

### CR-99 — "Rank, present, apply imports" is repeated at each call site

`map(CompletionItemPresenter::present)` appears about fifteen more times; `applyImportEdits` relies on two lists staying index-aligned.

- Where: `completion/CompletionEngine.java:330-337, 573-576, 609-617`; `TypeReferenceCompleter.java:124-126, 206-208`; `CompletionItemPresenter.java:55-81`.
- Fix: `presentAll(candidates, ctx, analysis)` attaching each import as it presents.

### CR-100 — `CompletionItem` editing is duplicated, and the semicolon rule exists twice

"Set insert text and text-edit text" three times; additional edits are replaced in one place and appended in others, so the result depends on call order; two "append `;`" rules differ (label vs filter text).

- Where: `completion/CompletionEditApplier.java:184-191, 231-254`; `CompletionItemPresenter.java:83-94`; `CompletionEngine.java:214-221`; `MemberAccessCompleter.java:197-204`.

### CR-101 — Keyword candidates are built four ways

Modifier lists are identical in two classes; `isRealNameSlot` and `firstSegment` are duplicated.

- Where: `completion/KeywordProvider.java:34-38, 78-91, 152-155`; `ImportCompletionProvider.java:46-60, 113-117, 131-134`; `TypeReferenceCompleter.java:34-37, 144-161`; `CompletionEngine.java:799-802, 842-858`.

### CR-102 — Indentation is hardcoded instead of read from the file

`indentStep` / `indentUnit` exist but these use two (or four) spaces.

- Where: `refactor/CodeActionSupport.java:203, 254, 259, 289`; `AddConstructorParameterProvider.java:316, 384, 399`; `ExtractVariableProvider.java:259`; plus the stubs in CR-2.

### CR-103 — Insert-relative-to-member edits are re-implemented per provider

- Before a statement: `ExtractVariableProvider.java:292-300` vs `CodeActionSupport.java:195-199`.
- After the last field: `ExtractFieldProvider.java:212-220`; `AddConstructorParameterProvider.java:389-400`.
- Append to a list: `AddConstructorParameterProvider.java:256-265, 346-355`; `AddThrowsProvider.java:102-111`.
- Fix: `insertBefore`, `insertAfterLastField`, `appendToList` in `CodeActionSupport`.

### CR-104 — An exception type is written three ways

`AddThrowsProvider` splits the FQN and imports without a clash check; `TryCatchWrapProvider` writes the raw FQN; `ExtractMethodProvider` uses `SourceTypeNames`.

- Where: `refactor/AddThrowsProvider.java:61-63, 78`; `TryCatchWrapProvider.java:69`.
- Fix: both quick fixes use `CodeActionSupport.sourceType`.

### CR-105 — Assignment scanners and tree helpers are duplicated

- `TryWithResourcesProvider.isReassigned` and `AddConstructorParameterProvider.assignsField` are the same; `ExtractionSupport.reassignsAny` covers both.
- `Equivalence`'s assignment and compound-assignment branches are identical.
- `VAR` is defined three times and typed twice more.
- `RegionFlow.isBodyBoundary(Tree)` and `TreePaths.isBodyBoundary(TreePath)` share a name but differ.
- The four loop kinds are listed four times; two ancestor walks do the same.
- Where: `refactor/TryWithResourcesProvider.java:134-149`; `AddConstructorParameterProvider.java:211-226`; `ExtractionSupport.java:471-481, 697-708, 780-827`; `DeclarationNames.java:31`; `ConvertToVarProvider.java:46`; `ReplaceVarProvider.java:30`; `DeclareVariableProvider.java:76`; `completion/ExpectedValueResolver.java:595`; `RegionFlow.java:433-445`; `TreePaths.java:72-77`; `ExtractVariableProvider.java:208-219, 395-402`; `ExtractMethodProvider.java:678-680`; `completion/SentinelParser.java:126-130`.

### CR-106 — Functional-interface return type is computed three times

- Where: `refactor/ExtractVariableProvider.java:267-279`; `completion/ExpectedValueResolver.java:220-233, 774-787`.
- Fix: `FunctionalInterfaces.samReturnType(...)`.

### CR-107 — Dead code and unused parameters in completion and code actions

- `CompletionMode`, `CompletionSite.mode` and `simpleNameMode` are never read; five `CompletionSite` fields are never read through `site` (`CompletionSite.java:67-87`).
- `AddConstructorParameterProvider.generateConstructor` has a `lastField == null` branch its own comment says cannot happen (366-387).
- `WorkspaceTypeIndex typeIndex` is unused in `AddThrowsProvider:36`, `TryCatchWrapProvider:28`, `DeclareVariableProvider:35`, `MissingMethodImplProvider:43`.

## 8. Build front-ends

### CR-108 — Dependency-source cache layout computed in each front-end

`LatheLayout.artifactDir(userCacheRoot().resolve(CACHE_DEPS_DIR), g, a, v)` and the `"sources"` classifier are in both Maven and Gradle; the type-index twin is already in `lathe-install`.

- Where: `maven/DependencySourceResolver.java:77, 98`; `gradle/LatheWorkspaceTask.java:74, 330`; `install/DependencyTypeIndexSync.java:49`.
- Fix: `DependencySource.present(...)` derives the dir; one `SOURCES_CLASSIFIER`.

### CR-109 — Install-then-copy is wired three times

- Where: `maven/SyncCoordinator.java:70-75`; `openjdk/SyncMojo.java:74-83`; `gradle/LatheWorkspaceTask.java:351-368`.
- Fix: `ServerInstaller.installInto(workspaceRoot)`.

### CR-110 — The module lock around a compile capture is implemented twice

- Where: `compiler/LatheCompiler.java:73-103`; `gradle/LatheCaptureTask.java:133-142`.
- Fix: `CompileCapture` takes the lock itself.

### CR-111 — "Write a `.lathe/` file" boilerplate in six writers, with Maven-only messages

`writeIfChanged`, a log line, `IOException` → `SyncException("lathe:sync failed …")` — the message names a Maven goal even when Gradle or OpenJDK runs it.

- Where: `install/MainLaunchWriter.java:61-70`; `TestFilterWriter.java:22-31`; `StyleWriter.java:26-34`; `SyncScript.java:75-83`; `WorkspaceSync.java:91-101`; `JvmArgsWriter.java:30-43`.
- Fix: one `writeIfChanged(dir, file, content, executable, what)` with a neutral message.

### CR-112 — Maven plugin lookup and configuration merge are repeated

"Find plugin, null-safe on `getBuild()`" four times; "execution config merged over plugin config" twice; the extension finds the compiler plugin a third way.

- Where: `maven/ReactorProjects.java:263-266, 279-283`; `maven/MavenTestFilter.java:113-126`; `maven/MavenJvmArgs.java:71-75`; `maven/MavenStyle.java:184-188`; `maven/extension/LatheModelInjector.java:56, 133`.
- Fix: a package-private `MavenModel` with `plugin(project, key)` and `mergedConfiguration(...)`.

### CR-113 — `CmdlineReader.read` and `readTool` are near copies

- Where: `openjdk/CmdlineReader.java:37-72, 74-108`.
- Fix: one parse for the shared flags; a mode-specific sink for `--module-source-path` / `-cp`.

### CR-114 — `LaunchCapture.toLaunchData` has seven copy-pasted option blocks

- Where: `junit/LaunchCapture.java:108-162`.
- Fix: a table of option → consumer, looped once (staying within release 8 if this class is compiled for it).

### CR-115 — Two places derive a module's `.lathe` dir from a working directory

- Where: `compiler/LatheCompiler.java:147-157`; `junit/LaunchCapture.java:41-49`.
- Fix: `LatheWorkspace.moduleDir(start, ceiling)` in core, if `LaunchCapture` may call core.

### CR-116 — Opt-out and "inside the root" rules are duplicated between Maven and Gradle

- Where: `maven/extension/LatheLifecycleParticipant.java:156-158`; `gradle/LatheSettingsPlugin.java:96-97`; `gradle/LatheProjectPlugin.java:680`; `maven/ReactorProjects.java:354`.
- Fix: `LatheFlags.isOptedOut(value, latheDir)` and one path predicate in core.

### CR-117 — The jar freshness check is written twice

- Where: `install/DependencySourceSync.java:85-102`; `install/DependencyTypeIndexSync.java:108-119`.
- Fix: a `JarStamp` record with `of(jar)` and `matches(...)`.

### CR-118 — Formatter warnings are written twice

- Where: `maven/MavenStyle.java:133`; `install/FormatterResolver.java:58`; `gradle/LatheFormatter.java:188, 206`.
- Fix: message builders in `FormatterSteps`.

### CR-119 — GAV string format and Gradle path helpers are repeated

`"%s:%s:%s"` about nine times; `LatheWorkspaceTask.paths` duplicates `GradleFiles.paths`.

- Where: `install/ResolvedJar.java:22`; `install/ServerInstaller.java:200, 244`; `install/FormatterSteps.java:86`; `install/FormatterResolver.java:76, 93`; `maven/ReactorProjects.java:391`; `DependencyFact.java:25`; `gradle/LatheProjectPlugin.java:589`; `gradle/LatheWorkspaceTask.java:444`; `gradle/GradleFiles.java:12`.

### CR-120 — Mixed SLF4J style in `lathe-install`

Most writers format eagerly inside `LOG.debug`; `CapturePruner.java:38` uses `{}` placeholders.

## 9. Editor clients

### CR-121 — The "no client attached" guard is copied eleven times with five messages

- Where: `nvim/instances.lua:16-19`; `typehierarchy.lua:49-53`; `imports.lua:104-107`; `resources.lua:80-84`; `run.lua:198-202, 244-248, 358-362`; `dap.lua:209-213, 257-260`; `new.lua:355-359, 435-439`.
- Fix: `util.attached_client(bufnr)` that notifies once and returns nil (together with CR-5).

### CR-122 — Finding the runnable under the cursor is written three times

The range check, the numeric kind tables and the "target under cursor" rules are each in `run.lua`, `dap.lua` and the neotest adapter; VS Code has none of it.

- Where: `nvim/run.lua:19-28, 51-53, 61-92, 318-338`; `nvim/dap.lua:21-30, 49-51, 56-66`; `nvim/neotest/common.lua:40-41`; `nvim/neotest/discovery.lua:11-12`; `nvim/neotest/ui.lua:89-104`.
- Fix: a server command "runnable at position" with a role; short of that, one shared Lua module.

### CR-123 — "Fetch this file's runnables, treat an error as none" is pasted three times

- Where: `nvim/run.lua:119-134, 175-183, 250, 271-282, 364, 378-401`; `nvim/dap.lua:215, 234-245`.
- Fix: one `file_targets(client, bufnr, cb)`.

### CR-124 — `util.notify` is bypassed in four modules

Some toasts come out without the "Lathe" title; `sync.lua` shadows `notify`.

- Where: `nvim/lathe.lua:57, 73, 83, 129, 149, 179, 192`; `nvim/latheinit.lua:30-32`; `nvim/pom.lua:27, 123-127`; `nvim/sync.lua:37-40, 172, 182-187, 268`.
- Fix: `util.notify` everywhere, with an optional opts argument.

### CR-125 — Three in-flight run registries and three ways to stop

`:LatheRunStop` stops runs and debug sessions but not neotest runs.

- Where: `nvim/run.lua:45, 433-445, 500-505`; `nvim/dap.lua:33, 270-296`; `nvim/neotest/spec.lua:21, 67-91`.
- Fix: one token registry with `stop_all()`.

### CR-126 — Response callbacks re-schedule themselves

`util.exec` already runs its callback in `vim.schedule`.

- Where: `nvim/util.lua:37-40`; `nvim/resources.lua:68`; `nvim/run.lua:129, 176, 253, 367, 379`; `nvim/dap.lua:154, 165, 220`; `nvim/stackdecorate.lua:140`.

### CR-127 — Root detection and launcher checks are reimplemented

The "launcher is executable" check five times; neotest finds the root with plain `vim.fs.root(dir, ".lathe")`, skipping `workspace.get_root`'s cache fallback; `".lathe"` typed instead of `ROOT_MARKER`; `lathe-launcher.sh` hardcoded where VS Code picks `.sh` or `.cmd`; VS Code requires `.lathe` to be a directory.

- Where: `nvim/lathe.lua:82, 111, 234, 264`; `nvim/latheinit.lua:257`; `nvim/neotest/discovery.lua:86, 95, 115`; `nvim/sync.lua:138, 160`; `nvim/style.lua:15`; `nvim/workspace.lua:26-28`; `vscode/launch.ts:7, 11-20`.

### CR-128 — VS Code can start two servers for one workspace and skips the protocol check

Clients are keyed by workspace folder but launched at the `.lathe` root; VS Code never reads `latheProtocol`; its setup message mentions Maven only; its read-only cache glob ignores `LATHE_CACHE`.

- Where: `vscode/extension.ts:29, 38, 47-49`; `lathe-vscode/package.json`; `nvim/lathe.lua:117-127, 164-180`; `nvim/sync.lua:16-17`; `nvim/workspace.lua:10-14`.
- Fix: key clients by root; add the protocol check; one canonical setup message from the server.

### CR-129 — The neotest adapter assumes the Maven directory layout

Class names are anchored on `/src/<scope>/java/`, test files on `/src/test/`, and `.lathe/workspace.json` is parsed on the client; custom source roots get no tests or wrong names.

- Where: `nvim/neotest/common.lua:61-74`; `nvim/neotest/results.lua:107, 109`; `nvim/neotest/spec.lua:241`; `nvim/neotest/discovery.lua:94-107`.
- Fix: ask the server (`lathe.workspace.context`, `lathe.tests.sources`), or return the class name with each runnable.

### CR-130 — Three ways to pick from a list

`util.pick`, `lathe.pick` directly (losing Telescope previews in `go_to_test`), and `vim.ui.select`; `pick.lua`'s header says only `:LatheNew` uses it; quickfix opens two ways.

- Where: `nvim/run.lua:226`; `nvim/new.lua:195, 247, 377, 400, 453`; `nvim/imports.lua:86`; `nvim/neotest/ui.lua:141, 189`; `nvim/pick.lua:4`; `nvim/instances.lua:30`.

### CR-131 — Debugger launch settings are built three ways

- Where: `nvim/dap.lua:73-82, 94-103, 107-115, 133-151, 203-207, 251-255`.
- Fix: one `attach_config(label, fields)`; one "nvim-dap missing" guard.

### CR-132 — Neotest spec and results duplications

- `neotest/spec.lua`: the skip spec is built twice (211-218, 260-267); "Nothing here to debug" twice (204, 251); `run_spec`'s doc comment sits above `stream_fn` (93-112).
- `neotest/results.lua`: `local result` shadows a parameter (263); `local outcome` shadows the module-level one (270, 298); line 120 re-implements `common.class_of`; `M._failing` and `M.failing` are one table exported twice (193-194).

### CR-133 — Folding request sent twice; format-on-save re-implements `fold.format`

- Where: `nvim/fold.lua:41-42, 101-102, 129-133`; `nvim/format.lua:39-56`.

### CR-134 — Command names follow no single pattern

`LatheResourceFind` (noun-verb), `LatheMissingImports`, `LatheGoToTest` (verb-noun), `LatheInstances`, `LatheTypeHierarchy` (noun), `LatheSyncCaptureTest` (singular); `LatheRunStop` also stops debug sessions; neotest actions are Lua-only.

- Where: `nvim/neotest.lua:35-39`; `nvim/neotest/spec.lua:66`; `nvim/neotest/ui.lua:22`.

### CR-135 — Client leftovers

- `nvim/new.lua:113-115` `pref_scope` is never called; `new.lua:7` "v5 UX" is a history note.
- `indentexpr` is set in both `lathe-neovim/runtime/after/indent/java.lua:1` and `runtime/ftplugin/java.lua:20`.
- `nvim/output.lua:31, 46, 129, 139` re-check what `current_bufnr()` checks; `nvim/sync.lua:62, 92` the same.
- `nvim/lathe.lua:280-327` repeats each module's header above its `setup()` call; some have drifted.
- Spec helpers `write_file`, `stub_client` and a `target(kind, …)` builder are repeated across specs; move them into `spec_helper.lua`.
- `nvim/indent.lua` (801 lines) is a text-based indenter only Neovim has; if indentation is ever needed elsewhere, the server is the place for it.

## 10. Leftovers across the Java code

### CR-136 — Stale or misplaced comments

- `server/LatheEngine.java:644-645` describes a write helper that moved to `DiskEdits`.
- `server/RenameService.java:206-209` describes `requireMeaningKept`, not the method it sits on; `:375` uses `+ ".java"` where `:379` uses `"%s.java"`.
- `server/LatheLanguageServer.java:56` says the in-process engine is one of its clients; it is not.
- `server/LatheWorkspaceService.java:62` links to a doc file.
- `server/debug/JdiRef.java:5-10` and `server/debug/LatheSourceLookUpProvider.java:27` call shipped features "deferred".
- `RunCoordinatorTest.java:43` refers to a `PortUtil.awaitAccepting` that does not exist.
- `install/LauncherCopier.java:16` says Maven and OpenJDK only; Gradle uses it too.
- Orphaned one-word comment lines after Spotless: `maven/ReactorProjects.java:69-71, 89-95, 257-259, 350-353`; `maven/SyncCoordinator.java:134-137`; `install/ServerInstaller.java:191-194`; `core/LatheBuildInfo.java:23-25`; `gradle/LatheProjectPlugin.java:477-479`.

### CR-137 — Code used only by tests, or not at all

- `McpText.NO_NOTES` (`mcp/McpText.java:368`) is test-only.
- `LatheLanguageServer.getWorkspaceService` (176-178) creates a new instance per call, while the text-document service is a field.
- The no-op providers are `NoEvaluationProvider` and `NoCompletionsProvider`, but `LatheHotCodeReplaceProvider`.
- Others are listed with their area: CR-44, CR-59, CR-70, CR-71, CR-89, CR-107.

### CR-138 — Backward-compatibility code CLAUDE.md says not to keep

- `ModuleConfigData.testCode` is a nullable `Boolean` for params files written before the flag existed (`core/…/ModuleConfigData.java:28-29, 40-41`).
- `ResourceRootData.module` is optional so an older manifest still loads.
- `CompiledStamps.byPath` handles pre-slashed Windows files (`:121`).
- `LauncherCopier.java:37` handles symlinks left by older versions.
- Fix: drop them; bump `SCHEMA_VERSION` if needed.

### CR-139 — Override completion adds no imports

The override stub writes its return, parameter and thrown types by simple name but imports none of them, so a stub that names an unimported type does not compile.
The implement-abstract-methods quick fix renders the same signature and imports what it writes.

- Where: `analysis/completion/OverrideCompletionProvider.java:16-18, 60-87`; `analysis/refactor/MissingMethodImplProvider.java:71-84`.
- Fix: render through `SourceTypeNames` and attach the import edits for `MethodStubRenderer.signatureTypes`, as the quick fix does.
