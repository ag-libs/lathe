# Lathe — Design Index

This index maps design documents to the active roadmap.
The [roadmap](roadmap.md) is authoritative for milestone scope, and [status.md](status.md) is authoritative for the
implemented baseline.

Documents under `planned/` can contain implemented history or stale details.
Read their current-status sections and compare them with the roadmap before starting work.

The [gap lifecycle](gaps/gap-process.md) defines how every gap (EG/FR/CA and CQ) moves from
`documented` through a release `Target` to `done` in the single registry [gaps.md](gaps/gaps.md), with
discovery via [gap-workflow.md](gaps/gap-workflow.md) and resolved entries in [gaps-archive.md](gaps/gaps-archive.md).

## M1 — Internal Preview

- [Gaps](gaps/gaps.md) — the single active gap registry (EG/FR/CA/CQ); resolved entries in
  [gaps-archive.md](gaps/gaps-archive.md). Lifecycle in [gap-process.md](gaps/gap-process.md); discovery in
  [gap-workflow.md](gaps/gap-workflow.md).
- [M1 Refactoring](done/lathe-m1-refactoring.md) — single consolidated refactoring plan: documentation accuracy,
  fail-fast propagation, DRY/structural extractions, god-class decomposition, naming, and test-suite hygiene. ✓
- [Reactor Type Index](planned/lathe-reactor-type-index.md) — implemented baseline and freshness follow-ups.
- [Type Index](done/lathe-type-index.md) — implemented baseline plus active schema and freshness context.

## M2 — Neovim Public Beta

Reliability, the triaged gaps, and rename — see the [roadmap](roadmap.md) for scope.

- [Gaps](gaps/gaps.md) — active gap registry; the triaged M2 correctness/completion/code-action gaps
  are all resolved (see [gaps-archive.md](gaps/gaps-archive.md)). EG-003 is deferred until after M2.
- [Completion Expectations](done/lathe-completion-expectations.md) — completion behavioral contract (reference).
- [Gap Workflow](gaps/gap-workflow.md) — reproducible gap discovery and triage (all areas).
- [Rename](done/lathe-rename.md) — **shipped.** `textDocument/rename` + `prepareRename` on the Find
  References pipeline (occurrence ranges → `WorkspaceEdit`, no `ASTRewrite`), incl. enum constants,
  public top-level types with a file move, and Javadoc reference mentions; the cursor must name the
  target. Minimal conflict checks, with explicit non-goals.
- [Interactive Request Priority & Reaction Coalescing](planned/lathe-interactive-request-priority.md) —
  server-wide: stop multi-second background recompiles (the in-process-sync reaction) from blocking
  millisecond interactive requests on the single serial worker; prioritize/cancel, coalesce the reaction,
  and keep the open buffer's analysis from being evicted by background disk-content compiles. Surfaced by
  the OpenJDK PoC on `java.base`; benefits every project.
- [Javac Crash Capture](done/lathe-javac-crash-capture.md) — resolved for M2 as a minimal,
  source-free `[javacCrash]` log line (phase + JDK + stack trace); the full repro-bundle design is a
  deferred backlog follow-up. ✓
- [Debug/neotest Unification](done/lathe-debug-neotest-unification.md) — route test-debug through
  neotest's `dap` strategy so gutters/summary/console/pass-fail match a run; resolves NV-3/NV-4 and
  makes NV-2 an implement-once change via a new `lathe/testFinished` outcome notification.
- [Re-run Failed Tests](planned/lathe-rerun-failed-tests.md) — resolves NV-5. **M2:** re-run the
  *first* failing test (`run_first_failed()`, `<leader>tF`), a single-position run with no `results()`
  change. **Follow-up:**
  re-run *all* failures in one replay JVM (repeat the previous run, narrowed to the failed selections)
  via a `run_set`/shadow retain on the unified `results()`.
- [Maven Central beta publishing](../RELEASING.md) — release automation is implemented and documented:
  tag-driven CI (`release.yml`) with GPG signing and the `central-publishing-maven-plugin`, version
  stamping via `versions:set`, and the `release.sh` cut-a-release script. ✓ (The first beta tag is cut
  manually.)

## Backlog — unscheduled, re-triaged after the beta

Everything beyond M2. The next release — general availability (a stable `1.0.0`) — is scheduled only
after public-beta feedback (see the [roadmap](roadmap.md)).

**Editing features (deferred from M2):**

- [Declaration Name Completion](done/lathe-declaration-name-completion.md) — names in variable/field/parameter/
  catch declaration slots (shipped; type parameters and constants are documented non-goals).
- [Snippet (Template) Completion](planned/lathe-snippet-completion.md) — a minimal jdtls-style template set
  (`psvm`, `sout`, `ctor`) delivered as context-gated snippet completion items (proposed).
- [Method-Reference Completion](done/lathe-method-reference-completion.md) — completion after `::`
  (`String::§`, `this::§`, `Type::new`) reusing the member-access pipeline, with SAM-aware filtering
  (shipped; resolved CQ-0060).
- [Surround with try-with-resources](done/lathe-try-with-resources.md) — range-driven refactor wrapping an
  `AutoCloseable` declaration in a try-with-resources; sibling of Extract Variable and try/catch wrap (shipped).
- [Add constructor parameter for a field](done/lathe-add-constructor-parameter.md) — range-driven refactor
  (CA-10) that adds a `final` field as a parameter to every constructor and binds it, generating a constructor
  when none exists; sibling of Extract Field (shipped; resolved CA-10).
- [`:LatheNew` Ergonomics Rethink (v4)](done/lathe-new-type-ergonomics.md) — **shipped**; reworked the `:LatheNew`
  after large-reactor friction: a typed command (context-defaulted anchored path + explicit
  `[module:][scope:]package` location) with the type name always prompted and the package never defaulting by
  omission, a guided `vim.ui.select` fallback for the no-context 20%, selectable main/test scope, and
  `module-info` / `package-info` as special kinds; supersedes CQ-0055 v3's flow.
- [New Type Creation](planned/lathe-new-type-creation.md) — **Won't do.** Snippet-completion alternative to
  the shipped `:LatheNew`; not pursued (kept as a rejected-design record).
- [Google Indentation](planned/lathe-google-indent.md) — conservative on-type formatting.
- [Type Definition Navigation](planned/lathe-type-definition.md) — `textDocument/typeDefinition` for Neovim's `grt`.
- [Type Hierarchy Explorer](done/lathe-type-hierarchy.md) — a Ctrl-H-style `:LatheTypeHierarchy` custom
  command showing the full both-directions transitive hierarchy in one Telescope picker; resolves NV-6.
- [Resource Finder](done/lathe-resource-finder.md) — one `:LatheResourceFind` picker to find a
  resource **by name** across the workspace's reactor and dependency resources, each row tagged by
  origin (`reactor:<module>` / `dep:<gav>`). Lists entries from jar central directories (no
  decompression) and reactor resource roots, and extracts **only the entry you open** — no bulk
  unpacking. Reactor opens editable, dependency read-only; content search deferred (shipped).

**Build-file support:**

- [`pom.xml` Support](planned/lathe-pom-support.md) — **chosen direction is client-side (KISS)**:
  Neovim-only XSD validation and formatting via `xmllint`, against a Maven POM schema Lathe ships in the
  client plugin — the LSP server is untouched (no attach, no server classes). The richer server-side
  design (JDK-native schema/coordinate/property/reactor completion from a `lathe:sync`
  `.lathe/pom-index.json` capture) is documented and **deferred** unless completion is later wanted.

**Build-tool front-ends** (new front-ends onto the existing `.lathe/` contract; everything downstream
reused):

- [Sync Launcher](planned/lathe-sync-launcher.md) — **in progress** (Maven and OpenJDK write it, Neovim runs it; Gradle's remains). The build integration writes
  `.lathe/lathe-sync.sh [--tests] [module ...]`, which clients, the server, and the MCP server run instead
  of composing Maven commands; runs the build on the recorded JDK, scopes with `-am`. Prepares Gradle.
- [Build-Tool-Agnostic Core](done/lathe-build-agnostic-core.md) — **done (2026-10-11), the prerequisite
  for Gradle.** Maven-only step that makes `.lathe/` the real seam: a front-end-written `outputs` map in
  `workspace.json` replaces `target/`-shape path guessing, `pomPaths` → `buildFiles`, neutral texts,
  and a written workspace contract.
- [Workspace Contract](lathe-workspace-contract.md) — **reference.** Every `.lathe/` file a build
  front-end writes: path, schema record, writer, readers, required/recommended/optional, Maven vs
  OpenJDK coverage, and known issues.
- [Gradle Support](planned/lathe-gradle-support.md) — **proposed, spiked on Gradle 8.8, 8.14 and 9.8 (every capture assumption, 2026-10-23), readiness-reviewed.** A
  `lathe-gradle-plugin` (settings + project plugin + `latheSync`) emitting the same `.lathe/` files:
  typed-API compiler capture on `JavaCompile`, in-fork test-launch capture reusing `lathe-junit`,
  Gradle's own module placement rule, polyglot Maven-orchestrated build. Decided 2026-10-18, order revised 2026-10-20: after all of Multi-OS and
  the JDK 17 floor, starting with a Maven-free `lathe-install` (slice 0); formatter version observed from Spotless's resolution
  (a committed `lathe-style.json` wins); Lathe artifacts from a reactor-filled repository, never `~/.m2`.
- [OpenJDK Support](done/lathe-openjdk-support.md) — **shipped (code-intelligence MVP, 2026-10-03).**
  `lathe-openjdk-maven-plugin` `sync` emits `.lathe/` for the JDK's own GNU Make build by reading the
  per-module compile descriptors the build already persists (`_the.<module>.vardeps` /
  `_the.<module>_batch.filelist`); jtreg run/test deferred.

**Reliability and further work:**

- [Test Framework: Fewer Cold Compiles](done/lathe-test-framework.md) — **done (2026-10-18).** Shared
  per-thread javac file managers (`TestJavac`, JDK-only default classpath), a once-per-thread `Sample.java`
  compile, per-class completion fixtures, and parallel test classes cut `lathe-server`'s tests from 35 s to
  about 15 s with the same 1,226 tests; JaCoCo runs only with `-Pcoverage`. Invoker suite: the stale-server fix
  (`bfec20a6`), parallel projects with Maven's file lock, and a test-only `lathe.scanIntervalMs`; the parallel
  runs exposed a `ZipCache` race, fixed for users too. Full `mvn verify` ~72 s → ~45 s. Workspace tiers and the
  separate test module were skipped.
- [Named Run Configurations](done/lathe-named-run-configs.md) — resolves TE-2: the overlay file
  becomes a `{ defaults, configs }` object (`defaults` = the auto-applied baselines, `configs` =
  name-keyed selectable entries that pin a target), explicit selection via `:LatheRun {name}` /
  `:LatheDebug {name}` with server completion, cursor scaffolding via `:LatheRunSave[!] [name]` (the
  one place Lathe writes `.lathe/run.json`), and active-config visibility (console header +
  notification + run log). IntelliJ run-config model; builds on the shipped overlay machinery in
  [Run, Test, and Debug](done/lathe-run-test-debug.md) §8.
- [Type-Index Name Resolution and Ranking](done/lathe-type-index-name-resolution.md) — exact-name
  import resolution (fix A) plus usage-aware prefix-search truncation (fix B); resolves CA-9. ✓
- [Sibling Recompilation](done/lathe-sibling-recompilation.md) — open-dependent diagnostics refresh after
  an API change (shipped via `refreshOpenDependents`); closed-file recompilation dropped as a non-goal.
- [Lightweight Watcher](planned/lathe-lightweight-watcher.md) — partially stale design; would back a
  non-intrusive source-staleness signal (the deferred WS-2, folded under WS-1) if beta feedback warrants it.
- [Differential Testing Against jdtls](done/lathe-jdtls-differential-testing.md) — **implemented
  (2026-10-04)**, reversing the earlier won't-do. `dev/jdtls_diff.py` sends identical LSP requests to Lathe
  and jdtls over a shared multi-module project and writes a categorised report; advisory `dev/` tooling,
  not a CI gate. Its first run surfaced EG-049..EG-055.
- [Standalone `lathe.nvim` Plugin](done/lathe-standalone-nvim-plugin.md) — **shipped**; publishes the
  Neovim Lua client as a plugin-manager-installable repo (unlocks dotfyle/awesome-neovim discovery),
  now the sole client delivery path (the bundled cache path was since removed along with the `current`
  symlink), via a local no-PAT `publish-nvim.sh` mirror to
  `ag-libs/lathe.nvim`, plus the first-run readiness nudge, double-load detection, and the coarse
  `LATHE_PROTOCOL` handshake (server advertises `capabilities.experimental.latheProtocol`, client
  compares at `on_init`, a drift-guard test keeps the Java constant and `version.lua` in lockstep).

**General availability (promote the beta to `1.0.0`):**

- Promote the Maven Central beta to a stable `1.0.0`: versioning/compatibility/support policies, remove
  preview/beta terminology, rollback, and full clean-install/upgrade qualification. (Maven Central *beta*
  publishing itself is M2.)
- [Launcher JVM Options](done/lathe-launcher-jvm-opts.md) — **shipped.** `LATHE_JVM_OPTS` support; also
  the escape hatch for JVM access the build does not declare.
- [Workspace JVM Args](done/lathe-jvm-args.md) — **shipped.** Sync copies the build's own javac-plugin
  module grants (forked `-J`, `.mvn/jvm.config`, `jdk.compiler` grants in `MAVEN_OPTS`) into `.lathe/jvm.args`,
  passed to the server as an `@argfile`.
- [Workspace-Specific Server JDK](done/lathe-workspace-java-home.md) — **shipped.** The launcher runs the
  server under the project's build JDK (captured in `.lathe/java-home`), overridable with `LATHE_JAVA_HOME`.
- [Workspace-Scoped Style](done/lathe-workspace-style.md) — **shipped.** Per-workspace `style.json`
  (committed `lathe-style.json` / generated `.lathe/style.json`, Spotless-detected) drives both the
  server formatter and the client indent profile.
- [Delegated Formatting via Maven (Spotless)](done/lathe-delegated-maven-formatting.md) — **superseded**
  by the next entry; its `command-file` engine remains as an opt-in for in-place tools.
- [Pinned Formatters In-Process](done/lathe-pinned-formatters.md) — **shipped (Maven).** Runs the
  project's own pinned Spotless formatter (google-java-format, palantir, Eclipse JDT) in-process from an
  isolated classloader, with its configured options and range formatting; no automatic Maven
  delegation, no bundled google-java-format.

**VS Code:** a supported integration; depends on the full semantic-token coverage above.

- [VS Code Client](planned/lathe-vscode-client.md) — **M1 passthrough built** (`lathe-vscode/`,
  `ag-libs.lathe`), two milestones. **M1:** we build the
  `.vsix` ourselves and hand it to testers (attached to each GitHub release by `release.yml` — no
  Marketplace, no PAT, rides `github.token`). **M2:** go all in — publish to the Marketplace + Open VSX via
  a mirror-repo Action (PAT isolated in CI) triggered by a no-PAT `publish-vscode.sh` push, gated on
  naming/trademark ("Java" nominative use + disclaimer) and a first-run UX (run-build nudge + walkthrough)
  to defuse the unbundled-server "nothing happens" trap. Thin `vscode-languageclient` passthrough
  baseline; parity maps the custom `lathe.*` commands onto VS Code APIs (Test Explorer, DebugAdapter,
  TreeView, CodeLens). Starts the workspace's own `.lathe/` launcher; monorepo source of truth; working-tree E2E via
  `@vscode/test-electron` beside the Neovim specs.
- [Run and Test in VS Code](planned/lathe-vscode-run-test.md) — **approved, spikes done.** Test
  Explorer (a lazy module tree from JUnit's own discovery on the captured bytecode, open files from
  attributed analysis; run at every level, live results with diffs, stack traces, durations and
  per-test output, container failures, cancel) and `main()` CodeLens, run button and named configs,
  output in a terminal. Small additive server changes (discover mode, richer results, a started
  event, closed stdin), shaped by prior art in Microsoft's Java extensions, Metals and
  rust-analyzer, plus run-path fixes the spikes found (array/varargs ids, static nested classes,
  Surefire's test-class filter for wide runs, cancel stopping the process tree). Delivered
  editor-neutral first, each server piece adopted by Neovim before VS Code. Working-tree E2E with
  `@vscode/test-electron` against the `multi-module` invoker fixture. Debugging deferred.
- [New Java Project (VS Code)](planned/lathe-vscode-new-project.md) — **proposed.** A
  `Lathe: New Java Project` command for beginners: checks for a JDK 17+ (explains, never installs),
  writes a minimal Lathe-enabled Maven project with the Maven Wrapper (no Maven install needed),
  runs `test` as the first build so run/test work at once, and starts Lathe without a reload.
  Steers away from a second Java language server. VS Code and Maven only.
- [Wider Maven and JDK Support](done/lathe-wider-maven-support.md) — **done (2026-10-19, PR #24).** Maven
  3.6.3+ through a Plexus descriptor (Maven <3.9's Sisu silently dropped the extension) and the 3.6.3 API floor;
  JDK 17+ for every module, the server included, with javac API newer than 17 behind a multi-release jar
  (`CompilerTrees`); an unsupported Maven or `maven-compiler-plugin` stops the build with one clear message;
  CI covers JDK 17–27 and Maven 3.6.3–3.10.
- [Multi-OS Design](done/lathe-multi-os.md) — **done (2026-10-23).** CI on Linux, macOS and Windows; Windows checked by hand in VS Code and Zed. Lathe portable by construction across
  Linux, macOS and Windows 10/11: a portable core plus a five-part platform edge (OS test, script renderers,
  tool resolution, filesystem quirks, client spawn), rules R1–R8 enforced on Linux CI (portable-names
  guard, `forbiddenapis`, OS matrix). No symlinks (done), argfile launchers, sync logic in Java, canonical URIs,
  `/` in persisted relative paths. Phase A makes the core portable (Linux/macOS); Phase B adds Windows
  (`.cmd` scripts, VS Code/Cursor and Zed; Neovim and Emacs stay Linux/macOS).

**AI coding agents:**

- [AI Agent Integration](planned/lathe-ai-agent-integration.md) — a new MCP server (universal, carries
  Lathe's run/test-capture moat) plus a thin Claude Code LSP plugin, added beside the Neovim/Emacs
  clients. MCP-first because it is the only substrate reaching Codex and the only path that exposes
  `executeCommand` verbs to agents. **Approved architecture:** a separate `lathe-mcp-server` module on
  the official MCP Java SDK, calling the engine in-process via a `LatheEngine` facade, with its own
  launcher. The tool surface is now feature-complete — `get_diagnostics`, `get_definition`,
  `find_references`, `find_implementations`, `call_hierarchy`, `search_symbols`, `describe_symbol`,
  `rename_symbol`, `run_test`, `analyze_change`, and `verify_change`, each with per-call usage logging;
  remaining work is the scaled measurement run, streaming/cancel, and the Claude Code LSP plugin.
- [Change Impact & Verification](done/lathe-change-impact-and-verification.md) — **shipped
  (2026-09-26).** Two paired agent
  tools on the MCP surface: `analyze_change` (pre-edit "what will this break?", read-only composition
  of describe/impls/references + the reactor graph) and `verify_change` (post-edit scoped recompile that
  drives the in-process write pass — Tier 1, since 2026-10-11 the
  [One Write Pass](done/lathe-recompile-simplification.md) — plus a scoped `.lathe/lathe-sync.sh` handoff
  for the cross-module remainder — Tier 3).
- [MCP Value Benchmark](planned/lathe-mcp-value-benchmark.md) — how to prove (or disprove) that the
  MCP server makes an agent measurably better on a real Maven reactor: adopt the SWE-bench /
  Multi-SWE-bench plumbing and make Lathe the single ablation variable (grep-only baseline vs agent +
  Lathe MCP), over a moat-curated Dropwizard/Helidon corpus, against a pre-registered hypothesis.
  **Pilot run (2026-10-03)** in `dev/bench/`: on a cross-module overload rename, grep-only missed a site
  in 3 of 4 weaker-model runs and Lathe none, at 0.24–0.48× the cost; the scaled headline run is next.

## Completed Designs

- [One Write Pass: Simplifying Recompilation](done/lathe-recompile-simplification.md) — every trigger (save,
  external change, rename commit, MCP query, startup) requests one pass that writes stale files a dependency
  level at a time and re-analyzes the affected open files once; removed the 50-file bulk threshold, the save
  queue and per-path bookkeeping, and the error-code widening (live analysis includes the tree's unsaved
  buffers that parse); MCP results end with "Not compiled" (fix the code) or "Sync required" (with the command).
- [Maven Extension for Automatic POM Setup](done/lathe-maven-extension.md) — a Maven core extension
  registered in `.mvn/extensions.xml` that injects the compiler shim, `init`/`sync` goals, and the
  test-capture dependency into the effective model in memory (extension-wins merge, `lathe.capture.only`
  signal, Surefire documented not pinned), so no `pom.xml` edits are needed. **The primary install path**
  (README Setup); manual POM setup is also documented.
- [Neotest Experience](done/lathe-neotest-experience.md) — IntelliJ-parity acceptance spec for the Neovim
  neotest client; substantially implemented (discovery, run at every level, streaming output, inline
  diagnostics, cancel, show-command). Remaining tail: debug (C1–C3), R4 (re-run-failed), O7 (fold).
- [Neotest Streaming and Thin-Adapter](done/lathe-neotest-streaming.md) — server-streamed `lathe/testOutput`
  notifications with stdout/stderr split, server-side test-id mapping, and consolidated single-launch file runs.
- [Workspace Readiness via Progress](done/lathe-workspace-readiness.md) — reports workspace load/reload as
  `$/progress` and gates discovery on its completion, fixing the cold-open discovery race (D1).
- [Test-Run Cancellation](done/lathe-test-cancel.md) — stop a running/hung replay (R5): a worker-confined
  `token → ReplaySession` map, a `lathe.run.cancel` command, and non-blocking SIGTERM→SIGKILL escalation.
- [LSP Progress Notifications](done/lathe-lsp-progress.md) — work-done progress for workspace initialization
  and reload, visible via `vim.lsp.status()`.
- [README & User-Guide Restructure](done/lathe-readme-restructure.md) — editor-agnostic README plus
  `docs/guide/` guides and the Neovim cheatsheet.
- [Run, Test, and Debug](done/lathe-run-test-debug.md) — capture-replay of tests (shim rides `mvn test`, replay against `.lathe/` bytecode) and `main` classes, plus the in-process DAP debugger over JDWP: breakpoints/stepping/inspection, conditional breakpoints, expression evaluation (reads, invocation, `String` concat, cold-class force-load, object-scoped for logical views), debug-console completion, and run/debug of a test-scope `main`. Deferred tail tracked as TE/DB backlog gaps.
- [Debug Support (DAP over JDWP)](done/lathe-debug-support.md) — the phased architecture behind the
  shipped debugger (in-process java-debug host, suspended-JDWP attach, process model). Deferred tail:
  DB-1 (assignment/`setVariable`), DB-2 (array creation).
- [Debugger Expression Evaluation](done/lathe-debug-expression-evaluation.md) — the javac front-end +
  JDI tree-interpreter behind watch/hover/console/conditional-breakpoint evaluation (reads, invocation,
  `String` concat, cold-class force-load, object-scoped views). Write path deferred as DB-1.
- [Capture Dependency Isolation](done/lathe-capture-dependency-isolation.md) — resolves the archived
  TE-1 (capture-only dependency leak) by shading `lathe-junit` into a relocated uber-jar with a
  dependency-reduced POM, so no capture jars reach the consumer classpath or the recorded template.
- [MissingMethodImplProvider](done/lathe-missing-method-impl.md) — the `codeAction` quick-fix that
  generates `@Override` stubs for unimplemented abstract methods (resolves the archived gap CA-3).
- [Formatting and Indentation Profiles](done/lathe-formatting-profiles.md) — opt-in Google Java Format
  (`formatter = "google"`, gated server capability) split from always-on client indentation
  (`indent_style` = `editor_config` default | `google`); the range-aware / on-type formatting tail
  (absorbs former gaps EG-029 and EG-028) remains deferred. The formatter part is **superseded** by
  [Pinned Formatters In-Process](done/lathe-pinned-formatters.md) (no bundled formatter; range formatting shipped).
- [Pluggable Formatter](done/lathe-pluggable-formatter.md) — `formatter` also accepts
  `{ command = [...] }` to run an external tool server-side (buffer piped through stdin/stdout, run in
  the workspace root, stderr to `lsp.log`, failures leave the buffer unchanged) behind a
  `FormatEngine` abstraction, with the in-process `"google"` engine as the default. The `command` engine
  remains; the bundled `"google"` default is **superseded** by [Pinned Formatters In-Process](done/lathe-pinned-formatters.md).
- [CamelCase Workspace Symbol Matching](done/lathe-workspace-symbol-camelcase.md) — resolves
  EG-005; IntelliJ-style CamelHumps abbreviation matching for `workspace/symbol`, scoped to
  reactor-owned types, merged alongside the existing exact-prefix search.
- [Workspace Symbol Browsing](done/lathe-workspace-symbol-browse.md) — superseded by the above;
  blank-query browsing was implemented then reverted once CamelCase matching solved the underlying
  problem more directly.
- [Semantic Tokens](done/lathe-semantic-tokens.md) — dense identifier-level coverage: distinct `class`/
  `interface`/`enum` types (declarations, references, and imports), `variable` vs `property`
  (local-vs-field) and `parameter`, and all methods/fields; the
  [Class/Import Semantic Highlighting](done/lathe-class-import-semantic-highlighting.md) slice is part
  of it. `readonly`/`abstract`/visibility modifiers and `recordComponent`/`annotationMember` deferred.
- [Goto Implementation and Type Hierarchy](done/lathe-goto-implementation.md) — inheritance-index-backed type
  navigation and reactor method implementation across reactor, dependency, and JDK types, including lambda and
  method-reference implementations of functional interfaces.
- [Event-Loop Starvation](done/lathe-event-loop-starvation.md) — diagnosis of synchronous index construction;
  resolved by the goto-implementation design keeping construction on `ServerEventLoop`.
- [Call Hierarchy](done/lathe-call-hierarchy.md) — `prepareCallHierarchy`, `incomingCalls`, and `outgoingCalls`
  on `ReferenceTarget` identity and `ReferenceCandidateIndex` discovery (M1).
- [Declaration](done/lathe-declaration.md) — `textDocument/declaration` navigating an overriding method to its
  root contract; declaration- and call-site (EG-012, M1).
- [Completion Design](done/lathe-completion-design.md)
- [Completion Gap Fixes](done/lathe-completion-gap-fixes.md)
- [Completion Gaps](done/lathe-completion-gaps.md)
- [Completion Semantics Audit](done/lathe-completion-semantics-audit.md)
- [Architecture and Test Improvements](done/lathe-architecture-test-improvements.md)
- [Code Actions](done/lathe-code-actions.md)
- [June 2026 Code Review](done/lathe-code-review-jun-2026.md)
- [Completion Disabled-Test Gaps](done/lathe-completion-disabled-test-gaps.md)
- [Completion Presentation](done/lathe-completion-presentation.md)
- [Standard File URI Scheme](done/lathe-file-uri-scheme.md)
- [Find References](done/lathe-find-references.md)
- [Reference Search Reliability](done/lathe-reference-search-reliability.md) — bounded transient analysis,
  process-wide compilation admission, work-done progress, optional cancellation, and fatal `Error` handling after the
  Helidon `String` search crash.
- [Folding Ranges](done/lathe-folding-ranges.md)
- [Import Optimization](done/lathe-import-optimization.md)
- [JDK Cache Key](done/lathe-jdk-cache-key.md)
- [Lambda Completion](done/lathe-lambda-completion.md)
- [M2 Gap Work Plan](done/lathe-m2-gap-work-plan.md) — archived historical checklist; current scope is in
  [roadmap.md](roadmap.md), [status.md](status.md), and [gaps.md](gaps/gaps.md).
- [Missing Import Code Action](done/lathe-missing-import-code-action.md)
- [Refactoring and Renaming](done/lathe-refactoring-renaming.md)
- [Rich Javadoc Rendering](done/lathe-rich-javadoc-rendering.md)
- [Server Data-Flow Recipe](done/lathe-server-data-flow-recipe.md)
- [Signature Help](done/lathe-signature-help.md)
- [Superseded Source URI Scheme](done/lathe-source-uri-scheme.md)
- [In-Process Workspace Sync](done/lathe-in-process-workspace-sync.md) — **shipped**; IDE-grade
  reaction that recompiles external source add/change/delete into the `.lathe/` mirror on the idle tick
  (changed files upstream-first, deletions removing their classes, open dependents refreshed), reserving
  the Maven sync prompt for POM/structure and bulk (branch-switch-scale) changes; supersedes the parked
  in-process recompilation and the source half of detect→prompt. Verified end-to-end on the multi-module
  invoker workspace. ✓
- [External-Change Detection → Sync Prompt](done/lathe-external-change-detection.md) — detects on-disk
  edits to Java sources/resources beyond POMs (branch switch, `git pull`, agent edits) server-side; its
  **source** half is now superseded by In-Process Workspace Sync (recompile, not prompt), while the POM
  prompt (WS-3) + silent refresh (WS-4) and resource auto-copy (WS-5) remain.
- [Staleness via Compile Stamps](done/lathe-staleness-compile-stamps.md) — replaces the source-staleness
  scan's class-file-path derivation (WS-5) with a per-source compile stamp recorded at build/save time,
  so a missing/wrong `package` or name mismatch can no longer produce an un-clearable sync prompt (WS-9).
- [Extract Variable](done/lathe-extract-variable.md) — selection-driven `refactor.extract` code action
  (edit-only, no command) that introduces a local for the selected expression, with a replace-all-
  occurrences variant and inferred-name heuristics; mirrors `ReplaceVarProvider` and lays the shared
  scaffolding a later Extract Method slice reuses. `var`-typed variant tracked as CA-6.
- [Stale-POM Detection](done/lathe-stale-pom-detection.md)
- [Structural Navigation](done/lathe-structural-navigation.md)
- [Structured Per-Test Results](done/lathe-structured-test-results.md) — real per-method pass/fail/skip from a
  class/package replay run (runner NDJSON sink → `ReplayOutcome.testResults` → neotest), replacing the aggregate
  status fan-out; follow-on to [Run, Test, and Debug](done/lathe-run-test-debug.md).
- [Unused-Code Diagnostics](done/lathe-unused-code-diagnostics.md)
- [Unused Record Components](done/lathe-unused-record-components.md)

## Potential Designs

- [Potential Design Policy](potential/README.md)
- [Personal Setup](potential/lathe-personal-setup.md) — **potential; one part agreed.** Records the
  2026-10-18 and 2026-10-24 discussions on using Lathe without changing the project, in VS Code/Cursor,
  Neovim, Zed, Emacs and the terminal. Covers personal vs team mode; activation via
  `-Dmaven.ext.class.path` (Maven 3.0.2+) or `~/.m2/extensions.xml` (3.10+), replayed by the generated
  sync script; option A (jars in the `.vsix`) vs option B (a coordinate-invoked setup goal, the OpenJDK
  front-end's pattern); per-editor journeys; detect-and-explain; open questions and spikes. Agreed
  (2026-10-24): the path from personal to team mode — an explicit command, one previewed
  `.mvn/extensions.xml` change pinning the running version, a re-sync without the activation line,
  and the project's registration winning over any other copy.
- [Rename Transactions](potential/lathe-rename-transactions.md) — **slices 1–2 implemented.** The server absorbs a
  rename it produced as one transaction: no per-file compiles while the editor applies and saves it, then one commit
  (chained batch compile per source tree, one dependents refresh) at the last save or, for MCP, right after the engine
  writes the files; the as-built notes record the deferred parts.
- [Batching](potential/lathe-batching.md) — two server primitives for multi-file bursts: chained batch writes
  (reconcile, the rename commit, save coalescing) and in-memory batch analysis (dependents refresh, open bursts, reload),
  coalescing by load rather than timers; plus narrowing the dependents refresh.
- [In-Process External-Change Recompilation](potential/lathe-external-change-recompilation.md) —
  **superseded** by [In-Process Workspace Sync](done/lathe-in-process-workspace-sync.md); retained as
  the archive of the reaction mechanics (batch FULL compile, startup reconciliation,
  live-watch/gitignore/Neovim analysis) the successor builds on.
- [Shared Workspace Server](potential/lathe-shared-workspace-server.md) — no active milestone commitment.
- [External Content Directory](potential/lathe-external-content-dir.md) — opt-in redirection of the
  bulky generated `.lathe/` mirror to a Lathe-generated `~/.cache/lathe/projects/<id>` dir via a boolean
  `lathe.content.cached` (default in-tree, no behavior change), keeping `.lathe/` as a tiny marker.
  **Deferred:** the adopter's pain may be met more cheaply by `**/.lathe/**` in jdtls
  `java.import.exclusions` plus an auto-written `.lathe/.gitignore`; kept as the fallback.
- [Analysis Cache Bounding](potential/lathe-analysis-cache-bounding.md) — deferred hard-cap design for
  per-open-file analysis retention (event-loop LRU, eviction delegated to module workers); the issue
  is accepted but a lighter warning-based mitigation is preferred first.
- [Lowercase CamelCase Symbol Search](potential/lathe-lowercase-camel-symbol-search.md) — let
  `workspace/symbol` CamelHumps matching span humps from an all-lowercase query, so users need not
  capitalize boundaries; refines EG-005.
- [Fuzzy Method-Name Symbol Search in the Reactor](potential/lathe-reactor-method-symbol-search.md) —
  extend `workspace/symbol` beyond type names to fuzzy method-name search by reusing the Find
  References token index (fuzzy key scan) plus a parse-only method collector; moderate, not soon.
- [Find Instantiations of a Type](potential/lathe-find-instantiations.md) — type-scoped
  instantiation query matching `NewClassTree` sites (handles synthetic default/record constructors)
  surfaced as a command/code action, rather than a constructor-scoped `references` filter.
- [Basic Refactorings (Rename, Move, Extract)](potential/lathe-basic-refactorings.md) — rename
  (`prepareRename`/`rename`, already M2), move and extract method/variable/field via
  `refactor.move`/`refactor.extract` code actions returning `WorkspaceEdit`s.
