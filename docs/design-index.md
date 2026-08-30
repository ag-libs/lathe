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
- [Type Index](planned/lathe-type-index.md) — implemented baseline plus active schema and freshness context.

## M2 — Neovim Public Beta

Reliability, the triaged gaps, and rename — see the [roadmap](roadmap.md) for scope.

- [Gaps](gaps/gaps.md) — active gap registry; the M2 slice is every `Status: accepted — Target: M2`
  entry (EG-039, EG-041, CA-5, CQ-0053, WS-2). EG-003 is deferred until after M2.
- [Completion Expectations](planned/lathe-completion-expectations.md) — completion behavioral contract (reference).
- [Gap Workflow](gaps/gap-workflow.md) — reproducible gap discovery and triage (all areas).
- [Rename](planned/lathe-rename.md) — `textDocument/rename` + `prepareRename` on the Find References
  pipeline (occurrence ranges → `WorkspaceEdit`, no `ASTRewrite`); scoped to the common cases,
  correctness-gated (freshness refusal + minimal conflict checks), with explicit non-goals.
- [Javac Crash Capture](planned/lathe-javac-crash-capture.md) — local repro bundles for unhandled javac
  exceptions without source text in normal logs; backs the crash-surfacing reliability work.
- [Lightweight Watcher](planned/lathe-lightweight-watcher.md) — partially stale design to re-evaluate for the
  WS-2 source-staleness re-sync prompt.
- **Maven Central beta publishing** — release automation, signing, staging, and released coordinates so
  users install without building from source; needs a dedicated design.

## Backlog — unscheduled, re-triaged after the beta

Everything beyond M2. The next release — general availability (a stable `0.1.0`) — is scheduled only
after public-beta feedback (see the [roadmap](roadmap.md)).

**Editing features (deferred from M2):**

- [Declaration Name Completion](planned/lathe-declaration-name-completion.md) — names in variable/field/parameter/
  type-parameter declaration slots.
- [New Type Creation](planned/lathe-new-type-creation.md) — scaffold a blank file's class/interface/enum/record via
  snippet completion, with no custom client-side UI.
- [Google Indentation](planned/lathe-google-indent.md) — conservative on-type formatting.
- [Type Definition Navigation](planned/lathe-type-definition.md) — `textDocument/typeDefinition` for Neovim's `grt`.
- [Semantic Tokens](planned/lathe-semantic-tokens.md) — full identifier-level coverage (local-var-vs-field, class,
  import) for Neovim and VS Code; the
  [Class/Import Semantic Highlighting](planned/lathe-class-import-semantic-highlighting.md) slice is part of it.

**Reliability and further work:**

- [Sibling Recompilation](planned/lathe-sibling-recompilation.md) — closed-file diagnostics after API changes.
- [Differential Testing Against jdtls](planned/lathe-jdtls-differential-testing.md) — semantic LSP-response
  comparison against Eclipse JDT LS to surface behavioral gaps.

**General availability (promote the beta to `0.1.0`):**

- Promote the Maven Central beta to a stable `0.1.0`: versioning/compatibility/support policies, remove
  preview/beta terminology, rollback, and full clean-install/upgrade qualification. (Maven Central *beta*
  publishing itself is M2.)
- [Launcher JVM Options](planned/lathe-launcher-jvm-opts.md) — `LATHE_JVM_OPTS` support.

**VS Code:** a supported integration; depends on the full semantic-token coverage above.

## Completed Designs

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
  (absorbs former gaps EG-029 and EG-028) remains deferred.
- [CamelCase Workspace Symbol Matching](done/lathe-workspace-symbol-camelcase.md) — resolves
  EG-005; IntelliJ-style CamelHumps abbreviation matching for `workspace/symbol`, scoped to
  reactor-owned types, merged alongside the existing exact-prefix search.
- [Workspace Symbol Browsing](done/lathe-workspace-symbol-browse.md) — superseded by the above;
  blank-query browsing was implemented then reverted once CamelCase matching solved the underlying
  problem more directly.
- [Goto Implementation and Type Hierarchy](done/lathe-goto-implementation.md) — inheritance-index-backed type
  navigation and reactor method implementation across reactor, dependency, and JDK types.
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
- [Stale-POM Detection](done/lathe-stale-pom-detection.md)
- [Structural Navigation](done/lathe-structural-navigation.md)
- [Structured Per-Test Results](done/lathe-structured-test-results.md) — real per-method pass/fail/skip from a
  class/package replay run (runner NDJSON sink → `ReplayOutcome.testResults` → neotest), replacing the aggregate
  status fan-out; follow-on to [Run, Test, and Debug](done/lathe-run-test-debug.md).
- [Unused-Code Diagnostics](done/lathe-unused-code-diagnostics.md)
- [Unused Record Components](done/lathe-unused-record-components.md)

## Potential Designs

- [Potential Design Policy](potential/README.md)
- [Shared Workspace Server](potential/lathe-shared-workspace-server.md) — no active milestone commitment.
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
