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

- [Gaps (deferred)](gaps/gaps.md) — EG-003 (hover in Javadoc type-reference tags) and EG-005 (CamelCase and infix
  workspace symbol matching) deferred from M1.
- [Find References gaps](gaps/gaps.md) — FR-002 external-source scope, FR-003 failure propagation, and FR-004
  integration coverage.
- [Completion Expectations](planned/lathe-completion-expectations.md) — expected completion behavior.
- [Completion gaps (CQ)](gaps/gaps.md) — active completion-quality gaps, including method references and generic bounds.
- [Gap Workflow](gaps/gap-workflow.md) — reproducible gap discovery and triage (all areas).
- [New Type Creation](planned/lathe-new-type-creation.md) — scaffold a blank file's class/interface/enum/record via
  snippet completion, with no custom client-side UI.
- [Google Indentation](planned/lathe-google-indent.md) — conservative on-type formatting.
- [Class/Import Semantic Highlighting](planned/lathe-class-import-semantic-highlighting.md) — Neovim-relevant semantic
  corrections for type references.
- [Lightweight Watcher](planned/lathe-lightweight-watcher.md) — partially stale design to re-evaluate before adding
  source watching.

- [LSP Progress Notifications](planned/lathe-lsp-progress.md) — work-done progress for workspace
  initialization and reload, visible via `vim.lsp.status()`.

Rename, inlay hints, and additional M2 code actions require focused designs before implementation.

## M3 — 0.1.0 General Availability

- [Launcher JVM Options](planned/lathe-launcher-jvm-opts.md) — `LATHE_JVM_OPTS` support.

Maven Central publishing, release automation, compatibility policy, and clean-install qualification require dedicated
M3 designs before implementation.

## Post-M3

- [Run, Test, and Debug](planned/lathe-run-test-debug.md) — Maven-delegated execution and JDWP attachment.
- [VS Code Semantic Tokens](planned/lathe-vscode-semantic-tokens.md) — semantic-token parity needed for supported VS Code
  integration.
- [Sibling Recompilation](planned/lathe-sibling-recompilation.md) — closed-file diagnostics after API changes.
- [Differential Testing Against jdtls](planned/lathe-jdtls-differential-testing.md) — semantic LSP-response
  comparison against Eclipse JDT LS to surface behavioral gaps; post-M2 quality tooling.

## Completed Designs

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
- [Missing Import Code Action](done/lathe-missing-import-code-action.md)
- [Refactoring and Renaming](done/lathe-refactoring-renaming.md)
- [Rich Javadoc Rendering](done/lathe-rich-javadoc-rendering.md)
- [Server Data-Flow Recipe](done/lathe-server-data-flow-recipe.md)
- [Signature Help](done/lathe-signature-help.md)
- [Superseded Source URI Scheme](done/lathe-source-uri-scheme.md)
- [Stale-POM Detection](done/lathe-stale-pom-detection.md)
- [Structural Navigation](done/lathe-structural-navigation.md)
- [Unused-Code Diagnostics](done/lathe-unused-code-diagnostics.md)
- [Unused Record Components](done/lathe-unused-record-components.md)

## Potential Designs

- [Potential Design Policy](potential/README.md)
- [Shared Workspace Server](potential/lathe-shared-workspace-server.md) — no active milestone commitment.
