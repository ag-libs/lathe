# Lathe — Development Plan

Four components with a fixed critical path: `lathe-core` provides shared helpers;
`lathe-compiler` must be installed before the shim can write params;
`lathe-maven-plugin` (`lathe:init`) must write `.lathe/root.marker` before the server starts;
`lathe:sync` runs after Maven compilation to refresh the workspace manifest, dependency/JDK sources,
server distribution, and later indexes.
The server reads what the shim and sync goal produce.
The plan builds vertically through the stack first (minimal shim → minimal init → diagnostics),
then adds sync and features in value order.

Each phase section is the authoritative implementation guide for that phase.
Read the full section before starting work.

## Current Implementation Status

Implemented:

- `lathe-core` shared layout, property-file, file, and timing helpers.
- `lathe:init` creates `.lathe/root.marker` and resets `.lathe/workspace.properties`.
- `lathe:sync` runs at `process-test-classes`, is aggregator/thread-safe, and skips non-top-level projects.
- `lathe:sync` discovers reactor modules, resolves direct external dependency source JARs through Maven,
  and extracts resolved sources under `~/.cache/lathe/deps/`.
- Compiler shim writes params files, manages `lathe.lock`, copies class outputs,
  and copies generated sources.
- Server supports diagnostics, hover with Javadoc, semantic tokens, formatting,
  and go-to-definition.
- Module registry currently scans `lsp-params-*.properties` directly.

Planned next:

- Fix shim/server drift found during design conformance review:
  - move compiler-shim params writing, class copying, generated-source copying, and lock cleanup into a true
    `finally` path around `javacCompiler.performCompile()`;
  - preserve file-manager flushing semantics;
  - make silent javac failure surface as an `IOException`;
  - make server compilation wait for fresh `lathe.lock` files in the target module and direct reactor dependencies;
  - redirect accidental stdout logging away from the LSP pipe before starting stdio transport.
- `.lathe/workspace.properties` workspace manifest.
- Record dependency source cache entries in the workspace manifest.
- Exact JDK source sync, after the manifest can record the selected cache path.
- Maven-managed server distribution under `~/.cache/lathe/servers/<version>/`.
- LSP stale-POM detection against `workspace.properties`.

---

## Phase 1 — Project Scaffold & Compiler Shim ✓

Complete.
`mvn compile` and `mvn test-compile` write `lsp-params-classes.properties` and `lsp-params-test-classes.properties`,
copy bytecode to `.lathe/<rel>/classes/` and `.lathe/<rel>/test-classes/`, and manage `lathe.lock`.

---

## Phase 2 — `lathe:init` ✓

**Output:** `mvn lathe:init` creates the workspace `.lathe/` directory, writes `.lathe/root.marker`,
deletes `.lathe/workspace.properties`, and leaves the user-level cache untouched.

- Current implementation: `InitMojo` writes `.lathe/root.marker` at the top-level project and deletes stale
  `.lathe/workspace.properties`.
- `lathe:init` is intentionally informational only.
- `lathe:init` does not validate Maven compiler configuration.
- `lathe:init` does not inspect or edit POM files.
- `lathe:init` does not warn about missing `lathe-compiler`, missing `<compilerId>lathe</compilerId>`,
  missing `lathe:sync`, or version mismatches.
- `lathe:init` does not install server distributions.
- Integration test: `multi-module` runs `lathe:init` before compilation and verifies `root.marker`,
  compiler-shim outputs, and stale `workspace.properties` removal.
- Installation remains documented in the user guide / README.
- Configuration problems are detected later from observable Lathe state:
  missing compiler params, missing workspace metadata, or stale sync output.

## Phase 2b — `lathe:sync`

**Output:** `lathe:sync`, with default phase `process-test-classes`, is the synchronization point for dependency
sources, the workspace manifest, server distribution, exact JDK sources, and later indexes.

Current implementation:

- `SyncMojo` has `defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES`,
  `aggregator = true`, and `threadSafe = true`.
- The goal runs only for the top-level project.
- It logs reactor modules in deterministic relative-path order.
- It resolves direct external dependency source JARs through Maven without forcing early reactor dependency resolution.
- It extracts resolved source JARs under `~/.cache/lathe/deps/<group path>/<artifact>/<version>/`.
- Extraction is parallel, zip-slip-safe via `FileUtil.unzip`, and skipped when `.lathe-source.properties`
  matches the current source JAR path, size, and modified time.
- Missing source JARs are not sync failures.

Implement in small slices:

### Phase 2b.1 — Manifest and no-op fast path

- `SyncMojo` already exists with `defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES`;
  users add an execution for `sync` and can omit `<phase>`.
- Read the Maven session/reactor after compile and test-compile have run.
- Compute a project state fingerprint from content hashes of relevant POM files, resolved dependency
  coordinates/artifacts, server version, JDK identity, and workspace/index schema version.
- If `.lathe/workspace.properties` exists and the fingerprint is unchanged, exit quickly.
- Keep `.lathe/root.marker` as the bootstrap/root-discovery marker;
  `workspace.properties` is a synchronized snapshot and may be missing or stale.
- Write one indexed `module.N.*` block per reactor project, including `rel`, `baseDir`, GAV, and paths to main/test
  params files.
  Do not duplicate compile or test classpaths in the first manifest; the shim's params files remain the source of truth
  for dependency resolution.
- Write `.lathe/workspace.properties` only after successful refresh, using an atomic temp-file-then-move update.
- Unit tests: fingerprint stability, POM SHA-256 hashing, nested/root module relative paths, manifest writer shape,
  indexed property ordering, and atomic writer behavior.
- Invoker tests: update `multi-module` to run `lathe:init process-test-classes`;
  assert manifest existence, schema/server version, POM hashes, and all reactor `module.N.*` entries without depending
  on nondeterministic module order.
  Prefer deterministic module sorting by relative path.
- No-op fast-path test: rerun `process-test-classes` with unchanged inputs and assert the manifest is not rewritten.
  Use file content hash rather than timestamp if `syncedAt` is not rewritten on no-op.

### Phase 2b.2 — Server distribution

- Install the matching server distribution under `~/.cache/lathe/servers/<version>/` if missing,
  then update `~/.cache/lathe/current`.

### Phase 2b.3 — Dependency sources

- Current implementation resolves direct external dependency source JAR artifacts through Maven,
  extracts available dependency sources under `~/.cache/lathe/deps/<gav-path>/`,
  and treats missing source JARs as non-fatal.
  It uses Maven-style group paths, for example `~/.cache/lathe/deps/com/google/guava/guava/32.0.0-jre/`.
- Current extraction is idempotent:
  extract to a temp directory, write a `.lathe-source.properties` marker after success,
  then atomically move into the final cache path.
  The marker records schema, GAV, source JAR path, source JAR size, and source JAR modified time.
- Still planned: record dependency source status and cache paths in `.lathe/workspace.properties`.
- Still planned: evaluate whether direct dependencies are sufficient,
  or whether sync should use Maven's resolved transitive artifacts after the manifest slice is in place.
- Still planned: record `sourceStatus=missing` in the manifest and continue.
  Fail only for internal cache write/corruption errors where Lathe cannot leave a valid old or new cache entry.
- Current unit tests cover shared unzip success, zip-slip rejection, and checked-IO wrapping.
- Still planned unit tests: marker-after-success, failed extraction does not leave a partial final directory,
  and rerun with the same marker skips extraction.
- Invoker tests: include one dependency with available sources and one local test dependency without a sources JAR;
  assert `sourceStatus=present` plus `sources=...` for the first and `sourceStatus=missing` for the second.

### Phase 2b.4 — JDK sources

- Depends on `.lathe/workspace.properties`;
  the server should consume the selected JDK source cache path from sync output rather than rediscovering it.
- Select/extract the exact JDK source archive for the JDK/toolchain Maven used and record the cache location.
  Prefer a cache key based on `src.zip` SHA-256 rather than only the release number.
  Example: `~/.cache/lathe/jdks/<src-zip-sha256>/`.
- JDK sources are optional.
  If `src.zip` is absent, record `jdk.sourceStatus=missing` and continue.
  If present, record `jdk.sourceStatus=present`, `jdk.sources`, `jdk.srcZip`, and `jdk.srcZip.sha256`.
- Unit tests: fake `src.zip` extraction, module-prefixed entries such as `java.base/java/lang/String.java`,
  zip-slip rejection, missing `src.zip` status, and injected/fake JDK home resolution.
  Avoid host-JDK-dependent unit tests.
- Invoker tests should only assert real JDK extraction when the running JDK has `src.zip`;
  otherwise assert that missing source status is recorded without failing sync.

### Phase 2b.5 — Server consumption

- Future extension: build type/reference indexes in the same sync pipeline.
- LSP follow-up: read `.lathe/workspace.properties` on startup and registry reload;
  keep it as an immutable in-memory snapshot for handlers.
  Watch POM files and compare their content hashes with the loaded manifest.
  When stale or missing, prompt: `Maven project changed. Run mvn process-test-classes to refresh Lathe.`

---

## Phase 3 — LS Core: Startup, Module Registry, Diagnostics ✓

**Output:** Neovim shows correct diagnostics for a compile error; diagnostics clear on fix; 500ms p95 target.

- LSP4J wiring, stdio transport, `initialize`/`initialized` handshake
- Module registry: scans `.lathe/` for all `lsp-params-*.properties`, parses each into `ModuleParams`;
  `ModuleParams` carries `outputDir`, `originalGenSourcesDir`, `sourceRoots`, `parameters`, `proc`,
  and all classpath/modulepath/processorPath lists
- `ModuleCompiler`: owns the javac invocation;
  content written to a temp file at the correct relative path under a temp dir;
  file manager locations set explicitly (`CLASS_OUTPUT` → `.lathe/<rel>/classes`, `SOURCE_OUTPUT` →
  `.lathe/<rel>/generated-sources`);
  `orElseThrow` enforces the invariant that a file must be under a known source root;
  `FileUtil.deleteDir` handles temp cleanup
- `ModuleCompiler.Mode`: `FAST` (no EP, no AP, `didChange`), `OPEN` (EP yes, AP no, `didOpen`), `FULL` (EP + AP,
  `didSave`); each mode carries a log `tag`; single `compileWith` dispatch method
- LRU cache of `StandardJavaFileManager` instances keyed by `ModuleParams` (100 entries, closes on eviction;
  invalidated on registry reload); `fm.flush()` after each `FULL` pass
- Per-file result cache stores `CompilationTaskContext` for hover, definition, and semantic-token reads.
  Cached contexts retain javac task-backed `Trees` and AST state while cached; cleanup means dropping references when
  replaced or invalidated.
- Classpath/modulepath/processorPath remapping: `remapPath()` uses `getParent()` navigation —
  handles root modules and arbitrarily nested paths; unit-tested in `ModuleParamsTest`
- `--patch-module` handling: normalised to `=` form at cache-creation time; applied once per file manager
- `runTask` throws `IOException` on silent javac failure; published as a 0:0 error diagnostic
- `DiagnosticsEngine`: position-less `NOTE`s filtered; `NOTE` with position → `Hint`;
  ranges use start/end offsets for token-spanning underlines
- `didSave`: cancels pending debounce, reads from disk, runs `FULL`;
  compiler touches `root.marker` mtime after params write;
  server polls every 2s on debouncer thread via `scheduleAtFixedRate`
- `didChange`: 500ms debounce with `cancel(true)` + `Thread.interrupted()` guard to suppress stale publish;
  `openFiles` map tracks current in-editor content
- After save: recompiles all open files in the same Maven module (`latheModuleDir` match) using latest `openFiles`
  content, routed through the same pending/cancel path
- "Run `mvn process-test-classes`" Warning published when no module params found for a file
- `singleDiag()` helper for single-message diagnostics;
  `cancelPending()` / `toPath()` helpers eliminate repeated patterns
- Invoker IT tests: `annotation-processing` (AP with `record-companion-builder`), `jpms-project` (JPMS module with
  `validcheck`, JUnit tests, `--add-reads` verification)
- Unit tests: `ModuleParamsTest` (7 `remapPath` cases), `LatheTextDocumentServiceTest` (debounce fires once, compiles
  latest content)

---

## Phase 4 — Result Cache: Hover, Semantic Tokens, Formatting

**Output:** Hover shows type info without recompiling.
Semantic highlighting works.
Full-file and range formatting via google-java-format.

### Hover ✓

- Per-file result cache: `Map<String, CompilationTaskContext>` in `AnalysisEngine`, keyed by URI;
  holds `Trees` + `CompilationUnitTree`; populated on every compile pass, dropped on `didClose` via `dropFromCache()`
- `SourceLocator`: four static methods — `toOffset` (LSP 0-based → javac position), `pathAt` (narrowest `TreePath` via
  `TreePathScanner`; smallest span wins), `elementAt` (walks up path; skips PACKAGE elements;
  handles `MethodInvocationTree` fallback and static `ImportTree` member lookup via `getTypeMirror` →
  `TypeElement.getEnclosedElements()`), `parameterElementAt` (maps cursor position to the callee's declared parameter;
  guards against ENUM_CONSTANT/FIELD masking; handles `NewClassTree`)
- `HoverFormatter.format()`: `ExecutableElement` → `returnType name(params)`;
  `TypeElement` → `class/interface/enum/record/annotation name`; field/variable → `type name`;
  wrapped in ` ```java ``` ` markdown
- `HoverFormatter.formatParameter()`: used when `parameterElementAt` wins.
  It shows declared type + name of the callee's parameter at the cursor position;
  for example, hovering on a `"hello"` argument shows `String s`.
- `AnalysisEngine.hover()`: checks `parameterElementAt` first (shows callee param context);
  falls back to `elementAt` + `getTypeMirror` for the element itself
- `JavadocLocator`: resolves same-file and cross-file source Javadoc for hover using available source roots;
  hover markdown includes Javadoc when present
- Unit tests: `SourceLocatorTest` — `@Nested` groups `Declarations` (8), `Invocations` (7), `Imports` (2), `Lambdas`
  (4), `Overloads` (3); covers static-import member resolution, overload discrimination by parameter type,
  and generic type variable detection

### Semantic Tokens ✓

- Gap-filling only — emits tokens tree-sitter cannot derive:
  `enumMember`, `method`, `property`, `typeParameter`, `annotation`; modifiers `declaration`, `static`, `deprecated`
- `SemanticTokensScanner` extends `TreePathScanner<Void, Void>`; tokens held as instance field;
  `scan(Trees, CompilationUnitTree)` static factory returns `List<SemanticToken>`
- `SemanticToken` record: `(int line, int character, int length, String type, Set<String> modifiers)`
- `encode(List<SemanticToken>)` produces delta-encoded `int[]` for LSP wire format
- Tokens computed once in `runTask` after `analyze()`, stored in `CompilationTaskContext`;
  `semanticTokensFull` reads directly from cache — `CompletableFuture.completedFuture()`, no executor dispatch
- `AnalysisEngine` cache upgraded to `ConcurrentHashMap` (LSP thread reads tokens/hover, debouncer writes)
- `interestingModifiers()` returns mutable `HashSet`; callers add `"declaration"` before passing to `addToken`;
  enum constants skip implicit `STATIC`
- `isDeprecated()` static helper checks `@Deprecated` annotation on element
- `findIdentifierFrom()` uses a `for` loop with named `leftBound`/`rightBound` booleans for word-boundary checks
- `visitAnnotation()`: emits `annotation` token for the simple name after `@`;
  handles both `IdentifierTree` and `MemberSelectTree` annotation types;
  `super.visitAnnotation()` walks children without double-emitting (annotation type elements are not matched by
  `emitIfInteresting`)
- Test fixture `Sample.java` extended with `enum Status`, `getStatus()`, `@Deprecated oldFormat()`, `useDeprecated()`,
  generic `identity()`, `staticHelper()`; `SourceLocatorTest` coordinates shifted +4 lines accordingly
- `SemanticTokensTest`: nested `Annotations`, `Declarations`, `Usages`, `NoToken`, `Encoding` groups;
  `SampleFixture` base class shared with `SourceLocatorTest`

### Code quality fixes ✓

- `HoverFormatter.format()` → `Optional<String>`; `AnalysisEngine` call site uses `.map().orElse(null)`
- `SourceLocator.indexIn` → `IntStream.range` with `==` reference-equality filter
- `LatheLanguageServer` + `LatheServer` INFO log calls wrapped in lambdas (consistent with FINE calls)
- `SourceLocator.offsetToPosition(CompilationUnitTree, long)` overload added;
  used by `DefinitionLocator` and `SemanticTokensScanner` — no more inline `lineMap.getLineNumber/getColumnNumber`
- `AnalysisEngine.CursorContext` record + `resolve(uri, pos)`: shared by `hover()` and `definition()` —
  cache lookup, `toOffset`, `pathAt` in one place
- Debouncer removed from `hover()` and `definition()` — both use `completedFuture()` directly;
  debouncer's sole job is sequencing compilations
- `maven.compiler.release` lowered from 25 to 21 — no Java 22+ language features were in use

### Formatting ✓

- `JavaFormatter`: `format(String)` static method; `Formatter.formatSource()` → single full-file `TextEdit`;
  catches `Throwable` (not just `FormatterException`), logs SEVERE, returns empty list; measures time with `Stopwatch`;
  `FormatterException` never escapes the handler so the server cannot die from a format failure
- `LatheTextDocumentService`: `formatting()`/`rangeFormatting()` both delegate to private `format(tag, uri)` →
  `JavaFormatter.format(openFiles.get(uri))`; `documentFormattingProvider` + `documentRangeFormattingProvider` declared
  in server capabilities
- `nvim.lua`: javac packages exported to both `ALL-UNNAMED` and `com.google.googlejavaformat` (named module —
  `ALL-UNNAMED` alone is not enough)
- **TODO:** `LatheServer.main()` should redirect accidental stdout logging away from the LSP pipe;
  current implementation loads logging config and starts the stdio server without this guard
- `FormattingTest`: 3 cases — violation produces edit, already-formatted produces nothing, syntax error produces nothing

---

## Phase 5 — Go-to-Definition ✓

**Output:** Navigate to reactor types and same-file declarations via `file://` URIs;
cursor lands on the declaration name token.

- `DefinitionLocator.locate(element, trees, sourceRoots, sourceUri)`:
  same-file → `trees.getPath(element)` → `nameOffset()` finds exact name position;
  reactor fallback → `topLevelClass(element)` → scan `sourceRoots` for `ClassName.java` → `parsePosition()` finds class
  name
- `nameOffset(content, declStart, name)`: `indexOf(name, declStart)` so cursor lands on the name token (not
  `public`/modifiers); works for methods, fields, parameters, classes
- `parsePosition(Path, String)`: parse-only javac task on reactor file;
  walks `cu.getTypeDecls()` for matching `ClassTree`; designed to be reused for dependency source jars;
  falls back to 0:0
- Temp URI fix: `trees.getPath(element)` returns temp compilation URI;
  original `sourceUri` passed through and used for same-file results
- `ModuleRegistry.allSourceRoots()`: flat list of all source roots across all modules
- `AnalysisEngine.definition(uri, pos, sourceRoots)`: shared `CursorContext`/`resolve()` helper with hover;
  delegates to `DefinitionLocator`; single consolidated log line
- `LatheTextDocumentService.definition()`: `CompletableFuture.completedFuture()` —
  no debouncer, cache read is thread-safe; returns `Either.forLeft(List.of(location))`
- `definitionProvider(true)` declared in server capabilities
- JDK and dependency types return empty (deferred)
- `DefinitionLocatorTest`: `SameFile` — type ref (`Status`) at exact col, method ref (`overloaded`) at exact col;
  `ReactorFallback` — element from bytecode-only class, file found via source root scan

---

## Phase 6 — Type Name Completion & Add Missing Import

**Output:** `Im<cursor>` completes to `ImmutableList`; selecting it inserts the import.
First-completion delay is documented.

- On first completion request per module: one dedicated enumeration `JavacTask` (from params, `proc=none`)
  assembles `TreeMap<String, List<TypeEntry>>`
  - JDK modules: `Elements.getModuleElement("java.se")` transitive requires, walk exported packages via
    `Elements.getPackageElement()` + `listMembers()`
  - JPMS reactor modules: `getModuleElement()` per reactor module on modulepath, respects exports
  - Non-JPMS reactor type discovery is deferred; fallback classpath scanning of `.lathe/<rel>/classes/` is a focused
    future contribution target
  - JAR deps: check `~/.cache/lathe/type-index/jars/<gav>.index`; build and cache if absent —
    modular JARs via `ExportsDirective`, non-modular via `fileManager.list(CLASS_PATH, ...)`
- `subMap(prefix, prefix + Character.MAX_VALUE)` for O(log n) prefix search
- `TreeMap` dropped when `CustomFileManager` is dropped; nulled on `module-info.java` `didSave` for affected modules
- Add missing import code action: unresolved name → type index prefix search → suggest candidates → insert import
  statement

---

## Phase 7 — Member Access Completion & Organize Imports

**Output:** `list.<cursor>` shows `add`, `get`, `size` with correct return types for generic substitution.

- Method body erasing: replace all method bodies except the one containing cursor with `{}`;
  write erased versions to temp dir for the pass
- Inject `$lathe$sentinel$` after dot; compile with `proc=none`
- Locate `MemberSelectTree` for sentinel, get receiver `TypeMirror`
- `Elements.getAllMembers(typeElement)` + `types.asMemberOf(receiverType, member)` for generic substitution
- Filter by accessibility and prefix match; cancel pending diagnostic debounce during completion, reschedule after
- Organize imports code action: sort and deduplicate imports, remove unused; pure source text manipulation

---

## Phase 8 — Find References & Document/Workspace Symbols

**Output:** Find all references to a method across open files and closed modules;
document outline and workspace type search work.

- Find references — open files: scan cached `CompilationUnitTree` per open file in parallel on module threads;
  trigger pass if cache empty
- Find references — closed modules: parallel virtual thread scan of `.lathe/<rel>/classes/` and
  `.lathe/<rel>/test-classes/` with Class-File API; `InvokeInstruction`/`FieldInstruction` for references;
  `LineNumberTable` for line numbers; import block string scan for column precision
- Surface limitations on every response: AP-changed descriptors, inlined constants, `invokedynamic` method references,
  partially-open modules
- Document symbols: walk `CompilationUnitTree`, emit `DocumentSymbol` for classes/methods/fields
- Workspace symbols: prefix search on type index across all modules → `SymbolInformation` list

---

## Phase 9 — Neovim e2e Harness & CI

**Output:** GitHub Actions CI green across all test projects.

- maven-invoker test projects: `simple-module`, `jpms-project` (two JPMS modules), `annotation-processing`;
  `post-build.sh` assertions per project; no Groovy scripts
- Neovim headless harness: `minimal_init.lua`, `harness.lua`, `run_tests.lua`;
  test specs for diagnostics, go-to-definition, find-references; `vim.wait()` polling, no fixed sleeps;
  exit code propagates to Maven; bound to `post-integration-test`
- GitHub Actions: `build` job (`mvn install -DskipTests`, upload local repo artifact);
  `test` job matrix (3 projects × skip/run Neovim); `lathe-server` resolved from local repo artifact —
  no Central access in CI

---

## Phase 10 — Polish & v0.1.0 Release

**Output:** Lathe works correctly on its own codebase (dogfood); release artifacts published.

- `LATHE_JVM_OPTS` support in launcher; `LATHE_DEBUG=1` overhead logging in shim
- Neovim config snippet documented (design §9);
  launcher path is user/editor configuration and is not produced by `lathe:init`
- Error surface polish: all "Run `mvn ...`"
  messages consistent; missing params per-module vs missing `.lathe/` root distinguished clearly
- Deploy parent POM, `lathe-compiler`, `lathe-maven-plugin`, `lathe-server` to Maven Central under `io.github.ag-libs`

---

## Post-v1

These build directly on the established model and are straightforward once the core is stable.

**Rename** — in-module `IdentifierTree` AST scan + cross-module Class-File API scan;
lightweight source parse for column precision; `WorkspaceEdit` including import statements.

**Inlay hints** — `MethodInvocationTree` walk, match args to `ExecutableElement.getParameters()`, emit `InlayHint`.

**Signature help** — method body erasing + sentinel inside call parentheses;
enumerate overloads, highlight current parameter.

**VS Code extension** — ~50 lines TypeScript; starts launcher, connects via LSP;
documents disabling `vscjava.vscode-java-pack`.

**Run / Test / Debug** (lathe-run-test-debug.md) —
`mvnd` delegation, six `workspace/executeCommand` commands, `lathe/sessionEvent` streaming, JDWP handshake, bundled
`java-debug` adapter, `neotest-lathe` Neovim adapter (~250 lines Lua).

---

## Risk Areas

- **Phase 3** — threading model, cancellation, and lock protocol
- **Phase 6** — first-completion latency: 300–800ms cold cost from walking `java.se` transitive modules;
  documented as expected behavior, not a bug
