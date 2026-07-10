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
- [MissingMethodImplProvider](planned/lathe-missing-method-impl.md) — generation of abstract-method stubs.
- [M1 Refactoring](done/lathe-m1-refactoring.md) — single consolidated refactoring plan: documentation accuracy,
  fail-fast propagation, DRY/structural extractions, god-class decomposition, naming, and test-suite hygiene. ✓
- [Reactor Type Index](planned/lathe-reactor-type-index.md) — implemented baseline and freshness follow-ups.
- [Type Index](planned/lathe-type-index.md) — implemented baseline plus active schema and freshness context.

## M2 — Neovim Public Beta

- [Gaps](gaps/gaps.md) — active M2 gap registry.
  EG-003 is deferred until after M2.
- [Find References gaps](gaps/gaps.md) — active reference-search gaps.
- [Completion Expectations](planned/lathe-completion-expectations.md) — expected completion behavior.
- [Completion gaps (CQ)](gaps/gaps.md) — active completion-quality gaps.
- [Declaration Name Completion](planned/lathe-declaration-name-completion.md) — M2 assistive completion for variable,
  field, parameter, and type-parameter names in declaration-name slots.
- [Gap Workflow](gaps/gap-workflow.md) — reproducible gap discovery and triage (all areas).
- [New Type Creation](planned/lathe-new-type-creation.md) — scaffold a blank file's class/interface/enum/record via
  snippet completion, with no custom client-side UI.
- [Google Indentation](planned/lathe-google-indent.md) — conservative on-type formatting.
- [Formatting and Indentation Profiles](planned/lathe-formatting-profiles.md) — opt-in Google Java Format and
  project-sensitive indentation defaults.
- [Class/Import Semantic Highlighting](planned/lathe-class-import-semantic-highlighting.md) — Neovim-relevant semantic
  corrections for type references.
- [Type Definition Navigation](planned/lathe-type-definition.md) — LSP `textDocument/typeDefinition` support for
  Neovim 0.12's default `grt` mapping.
- [Lightweight Watcher](planned/lathe-lightweight-watcher.md) — partially stale design to re-evaluate before adding
  source watching.

- [LSP Progress Notifications](planned/lathe-lsp-progress.md) — work-done progress for workspace
  initialization and reload, visible via `vim.lsp.status()`.
- [Javac Crash Capture](planned/lathe-javac-crash-capture.md) — local repro bundles for unhandled javac exceptions
  without putting source text in normal logs.

Rename, inlay hints, and additional M2 code actions require focused designs before implementation.

## M3 — 0.1.0 General Availability

- [Launcher JVM Options](planned/lathe-launcher-jvm-opts.md) — `LATHE_JVM_OPTS` support.

Maven Central publishing, release automation, compatibility policy, and clean-install qualification require dedicated
M3 designs before implementation.

## Post-M3

- [Run, Test, and Debug](planned/lathe-run-test-debug.md) — push-capture Surefire's fork launch (shim rides `mvn test`), replay against `.lathe/` bytecode, JDWP attachment; design + implementation/testing plan merged.
- [VS Code Semantic Tokens](planned/lathe-vscode-semantic-tokens.md) — semantic-token parity needed for supported VS Code
  integration.
- [Sibling Recompilation](planned/lathe-sibling-recompilation.md) — closed-file diagnostics after API changes.
- [Differential Testing Against jdtls](planned/lathe-jdtls-differential-testing.md) — semantic LSP-response
  comparison against Eclipse JDT LS to surface behavioral gaps; post-M2 quality tooling.

## Completed Designs

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
  status fan-out; follow-on to [Run, Test, and Debug](planned/lathe-run-test-debug.md).
- [Unused-Code Diagnostics](done/lathe-unused-code-diagnostics.md)
- [Unused Record Components](done/lathe-unused-record-components.md)

## Potential Designs

- [Potential Design Policy](potential/README.md)
- [Shared Workspace Server](potential/lathe-shared-workspace-server.md) — no active milestone commitment.
- [Analysis Cache Bounding](potential/lathe-analysis-cache-bounding.md) — deferred hard-cap design for
  per-open-file analysis retention (event-loop LRU, eviction delegated to module workers); the issue
  is accepted but a lighter warning-based mitigation is preferred first.
