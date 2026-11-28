# Lathe — Redundancy in production code and tests

**Proposed for review, November 21, 2026.**
The audit covered 925 tracked Java, Lua, TypeScript, Python, Kotlin-script, and shell files at `6e29dc6f`.
It compared repeated blocks and Java method bodies, then checked callers and test assertions against the source.
The performance observations below follow from the call paths.
No timings were collected.

Start with call hierarchy for its effect on an agent request and the external-compiler fixture for its small, test-only scope.
The remaining items can be reviewed independently.
Several production items revisit CR-208, CR-210, CR-211, CR-212, and CR-218 from the [November 14 review](../gaps/code-review-2026-11-14.md).
A readability review on November 28, 2026, at `a2f2cf5`, added the personal-setup, incoming-call and manifest-writer items; its other findings are [CQ-0085](../gaps/gaps.md), [MC-114](../gaps/gaps.md), [WS-56](../gaps/gaps.md) and PR #552.

## Production code

### Apply the call hierarchy result cap before reading snippets

Tracked as [MC-104](../gaps/gaps.md#mc-104--call_hierarchy-reads-a-snippet-for-every-call-site-then-keeps-only-maxresults-of-them).

For each incoming or outgoing call site, `LatheEngine` constructs a location with a snippet, which reads the whole source file through `Files.readAllLines`.
Only after constructing every location does it apply `maxResults`.
With 500 sites and a cap of ten, that path can read files 500 times to return ten snippets. [Call collection and cap](../../lathe-server/src/main/java/io/github/aglibs/lathe/server/LatheEngine.java#L1067) · [Source line read](../../lathe-server/src/main/java/io/github/aglibs/lathe/server/LatheEngine.java#L464)

Count all sites after the existing scope filter, order their raw locations, apply the cap, and enrich only the retained sites.
Incoming calls retain their origin, URI, and position ordering.
Outgoing calls retain source order and point to the call in the caller.
Use the same ordering of operations as [capLocations](../../lathe-server/src/main/java/io/github/aglibs/lathe/server/LatheEngine.java#L309).
Check both directions with two sites and a cap of one, asserting which site and snippet survive, a total of two, and the truncation flag.

### Resolve the output tree for a main class once

`RunCoordinator` asks `SourceSetScope.treeOf` about the class and, on an empty result, asks `MainClassScope.isTestScope` about the same class.
Both construct its relative class-file path and probe the main and test outputs. [Launch routing](../../lathe-server/src/main/java/io/github/aglibs/lathe/server/RunCoordinator.java#L565) · [Source-set lookup](../../lathe-server/src/main/java/io/github/aglibs/lathe/server/run/SourceSetScope.java#L18) · [Main/test lookup](../../lathe-server/src/main/java/io/github/aglibs/lathe/server/run/MainClassScope.java#L18)

Resolve the owning tree in one place and let each caller choose its launch template.
For a main launch, main wins when both standard outputs contain the class, test-only uses the test launch, and a custom tree uses its own capture.
A missing class keeps the current main-launch fallback.
Test selections have a different fallback to the test tree that the shared lookup must preserve. [Selection routing](../../lathe-server/src/main/java/io/github/aglibs/lathe/server/RunCoordinator.java#L713)

### Share replacement edits across extract actions

Extract constant, field, and variable each turn a list of javac occurrence paths into edits by reading the same start and end positions.
Move that mapping into the existing `ExtractionSupport`.
Keep occurrence selection, reassignment checks, and insertion placement in the provider that owns them.
The existing replace-all tests exercise edit placement and the order in which the original program evaluates expressions. [Constant](../../lathe-analysis/src/main/java/io/github/aglibs/lathe/analysis/refactor/ExtractConstantProvider.java#L105) · [Field](../../lathe-analysis/src/main/java/io/github/aglibs/lathe/analysis/refactor/ExtractFieldProvider.java#L165) · [Variable](../../lathe-analysis/src/main/java/io/github/aglibs/lathe/analysis/refactor/ExtractVariableProvider.java#L358) · [Edit placement test](../../lathe-analysis/src/test/java/io/github/aglibs/lathe/analysis/ExtractCodeActionTest.java#L837)

### Give completion one implementation of each shared javac rule

Three completion classes have the same `methodSelectName` body.
Two also duplicate the usable-type check and the walk that finds the nearest enclosing lambda or method supplying a return type.
Share those rules within the completion package, starting with the return-target walk, where choosing the wrong enclosing construct changes type inference.
Preserve the handling of incomplete trees and the precedence of a nested lambda over its enclosing method. [Expected value](../../lathe-analysis/src/main/java/io/github/aglibs/lathe/analysis/completion/ExpectedValueResolver.java#L803) · [Lambda return type](../../lathe-analysis/src/main/java/io/github/aglibs/lathe/analysis/completion/LambdaExpectedReturnTypeResolver.java#L197) · [Library rules](../../lathe-analysis/src/main/java/io/github/aglibs/lathe/analysis/completion/CompletionLibraryRules.java#L251)

### Parse the common OpenJDK compiler flags once

`CmdlineReader.read` and `readTool` each consume `-source`, `-target`, `-encoding`, `--add-exports`, and `--add-reads`.
Use one shared flag parser inside the reader and keep the two entry points responsible for their output.
Module compilation collects source-root patterns and synthesizes a patch module.
Tool compilation retains the classpath and adds `-Xlint:-options`.
The exact-argument tests pin the ordering for both paths. [Module parser](../../lathe-openjdk-maven-plugin/src/main/java/io/github/aglibs/lathe/openjdk/CmdlineReader.java#L37) · [Tool parser](../../lathe-openjdk-maven-plugin/src/main/java/io/github/aglibs/lathe/openjdk/CmdlineReader.java#L74) · [Parser tests](../../lathe-openjdk-maven-plugin/src/test/java/io/github/aglibs/lathe/openjdk/CmdlineReaderTest.java#L29)

### Two smaller production changes

- Generate the `maxResults` schema property from one definition and use `DEFAULT_MAX_RESULTS` for its documented default.
  Five MCP schemas repeat the range and default.
  Keep the descriptions specific to references, calls, symbols, implementations, and family members. [First schema](../../lathe-mcp-server/src/main/java/io/github/aglibs/lathe/mcp/LatheMcpTools.java#L128) · [Handler default](../../lathe-mcp-server/src/main/java/io/github/aglibs/lathe/mcp/LatheMcpTools.java#L871)
- Remove the package-private `RunItem.withName` method.
  A repository search found its declaration and the earlier review entry, with no caller in production or tests. [RunItem](../../lathe-server/src/main/java/io/github/aglibs/lathe/server/run/RunItem.java#L67)

### Recognize a personal Neovim setup in one place

`personal_kind` and `replays_activation` in `latheinit.lua` look for the same two markers in a sync script: Maven's `maven.ext.class.path` and the Gradle init script.
The first picks a build in a folder with both; the second decides whether a build that now registers Lathe must render a new script.
A marker added to one and not the other would make the two disagree about the same script.
Use one local function that takes the script's text and returns `"maven"`, `"gradle"` or `nil`; each caller already reads the script.
Keep the version-specific activation path and file-existence checks in `set_up_already`.
Check the mixed-build and personal-to-registered cases in `latheinit_spec.lua`. [Personal kind](../../lathe-neovim/runtime/lua/lathe/latheinit.lua#L218) · [Replays activation](../../lathe-neovim/runtime/lua/lathe/latheinit.lua#L740)

## Tests

### Build the external dependencies in one test fixture

Four `ExternalCompilerTest` cases write and compile the same `helper.jar` and `uses.jar` dependency pair.
Extract one fixture in that class, instantiated in each test's own `@TempDir`.
Keep manifest construction and request order in each test so the difference between direct definition, definition after hover, removed classpath, and workspace-JAR fallback is visible.
This reduces four copies of the setup while preserving filesystem isolation and all four assertions. [Direct definition](../../lathe-server/src/test/java/io/github/aglibs/lathe/server/module/ExternalCompilerTest.java#L31) · [Hover then definition](../../lathe-server/src/test/java/io/github/aglibs/lathe/server/module/ExternalCompilerTest.java#L79) · [Removed classpath](../../lathe-server/src/test/java/io/github/aglibs/lathe/server/module/ExternalCompilerTest.java#L126) · [Workspace-JAR fallback](../../lathe-server/src/test/java/io/github/aglibs/lathe/server/module/ExternalCompilerTest.java#L167)

### Reuse three server test workspaces

- **Module visibility.** Two LSP tests create unrelated `lib` and `app` modules, add an unbuilt `Fresh.java`, edit the app, and wait for reconciliation.
  Move the common setup to their existing `ServiceTestBase`, accepting the edited source and returning the app file.
  Keep both endpoint assertions and their different inputs, `Fres` for completion and `Fresh` for missing imports. [Completion](../../lathe-server/src/test/java/io/github/aglibs/lathe/server/LspRoutesTest.java#L114) · [Missing imports](../../lathe-server/src/test/java/io/github/aglibs/lathe/server/LatheWorkspaceServiceTest.java#L484)
- **Unsupported JDK.** Reuse the existing fixture that writes the `newer` module and sets `--release 99`.
  Put it in the server test support shared by the diagnostics and engine-notes tests.
  Retain the separate assertions about client notification, diagnostic severity, and the remediation an agent receives. [Helper and inline copy](../../lathe-server/src/test/java/io/github/aglibs/lathe/server/UnsupportedJdkTest.java#L124) · [Engine notes](../../lathe-server/src/test/java/io/github/aglibs/lathe/server/LatheEngineNotesTest.java#L26)
- **Chained callers.** Two verification tests recreate the `Span` and `Holder` sources supplied by `spanApi()` in the same class.
  Call that helper from both tests.
  Keep the method change, the deleted type, and the unaffected bystander in their respective scenarios. [Method change](../../lathe-server/src/test/java/io/github/aglibs/lathe/server/LatheEngineVerifyTest.java#L751) · [Type deletion](../../lathe-server/src/test/java/io/github/aglibs/lathe/server/LatheEngineVerifyTest.java#L795) · [Existing fixture](../../lathe-server/src/test/java/io/github/aglibs/lathe/server/LatheEngineVerifyTest.java#L904)

### Retire assertions already covered by exact argument lists

Both OpenJDK command-line tests check the complete compiler argument list, then check that specific omitted flags are absent.
Delete the second check in each case.
The launch-plan suite likewise checks complete main and test commands with debugging disabled.
Those expectations already exclude a JDWP agent.
Fold the standalone negative checks into the exact-command cases and keep the positive debug cases, which verify the agent precedes the entry point. [OpenJDK arguments](../../lathe-openjdk-maven-plugin/src/test/java/io/github/aglibs/lathe/openjdk/CmdlineReaderTest.java#L33) · [Exact test command](../../lathe-server/src/test/java/io/github/aglibs/lathe/server/run/LaunchPlanTest.java#L53) · [Exact main command](../../lathe-server/src/test/java/io/github/aglibs/lathe/server/run/LaunchPlanTest.java#L189) · [Debug ordering and absence](../../lathe-server/src/test/java/io/github/aglibs/lathe/server/run/LaunchPlanTest.java#L297)

### Use one runnable-target fixture in Neovim specs

Run, save, and DAP specs construct the same wire object using different ID conventions.
For a test class, the save helper uses the class ID, the run helper would append `#main`, and the DAP helper takes an explicit ID.
Put a constructor in the existing `spec_helper.lua` and pass explicit IDs when selectors or parent links are the subject of the test.
Keep run selection, save payload, and debug configuration assertions at their own entry points. [Run](../../lathe-neovim/test/run_spec.lua#L24) · [Save](../../lathe-neovim/test/run_save_spec.lua#L15) · [DAP](../../lathe-neovim/test/dap_spec.lua#L39)

### Name the incoming-call invocation once

Nine cases in `CallHierarchyIncomingLocatorTest` repeat the same nine-line call that asks the session for the incoming calls of a prepared hierarchy item.
A private helper, `incomingCalls(session, content, item)`, would leave each test its fixture source, target selection and assertions about callers and ranges.
Cases that use another compilation or a deliberately different analysis keep their explicit setup. [Incoming calls](../../lathe-analysis/src/test/java/io/github/aglibs/lathe/analysis/CallHierarchyIncomingLocatorTest.java#L25)

## Considered, not worth changing

`WorkspaceSync.write` unpacks its `Inputs` record into the twelve positional arguments of `WorkspaceManifestWriter.write`, several of them `List<String>`, so two swapped arguments would compile.
There is one production caller.
Passing `Inputs` instead would make the writer depend on its caller's record, which carries a sync script and caches the writer does not need, and the writer's tests, which pass no server version, would have to build all of it.
Keep the positional call. [Caller](../../lathe-install/src/main/java/io/github/aglibs/lathe/install/WorkspaceSync.java#L81) · [Writer](../../lathe-install/src/main/java/io/github/aglibs/lathe/install/WorkspaceManifestWriter.java#L28)

## An intentional copy

The standalone test runner mirrors the selector flag strings from `lathe-core` so it can launch without a runtime dependency on core.
Keep the strings at that boundary and retain the test that passes core's flag values to the runner parser.
It checks the contract between the producer and consumer directly. [Runner contract](../../lathe-test-runner/src/main/java/io/github/aglibs/lathe/runner/TestSelectorParser.java#L15) · [Cross-module test](../../lathe-test-runner/src/test/java/io/github/aglibs/lathe/runner/TestSelectorParserTest.java#L17)
