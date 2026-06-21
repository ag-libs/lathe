# Lathe — Design Index

This index maps design documents to the active roadmap.
The [roadmap](roadmap.md) is authoritative for milestone scope, and [status.md](status.md) is authoritative for the
implemented baseline.

Documents under `planned/` can contain implemented history or stale details.
Read their current-status sections and compare them with the roadmap before starting work.

## M1 — Internal Preview

- [Request Cancellation and Compilation Admission](planned/lathe-request-cancellation-and-compilation-admission.md) —
  process-wide javac concurrency and cooperative LSP request cancellation.
- [Reference Search Resource Safety](planned/lathe-reference-search-resource-safety.md) — transient closed-file
  analysis and shared javac admission after the Helidon `String` search crash.
- [Goto Implementation and Type Hierarchy](planned/lathe-goto-implementation.md) — inheritance-index-backed type
  navigation and reactor method implementation.
- [Event-Loop Starvation](planned/lathe-event-loop-starvation.md) — diagnosis of synchronous index construction on
  `ServerEventLoop`; superseded solution details should follow the goto-implementation design.
- [Code Action Gaps](planned/lathe-code-actions-gaps.md) — missing-method provider and reactor type freshness.
- [MissingMethodImplProvider](planned/lathe-missing-method-impl.md) — generation of abstract-method stubs.
- [Maintainability Refactoring](planned/lathe-maintainability-refactoring.md) — fail-fast propagation and focused
  workspace/analysis cleanup.
- [Test Suite Refactoring](planned/lathe-test-suite-refactoring.md) — fixture consolidation and removal of flaky sleeps.
- [Reactor Type Index](planned/lathe-reactor-type-index.md) — implemented baseline and freshness follow-ups.
- [Type Index](planned/lathe-type-index.md) — implemented baseline plus active schema and freshness context.

## M2 — Neovim Public Beta

- [Find References Gaps](planned/lathe-find-references-gaps.md) — external-source scope, failure propagation, and
  integration coverage.
- [Call Hierarchy](planned/lathe-call-hierarchy.md) — incoming and outgoing exact call navigation.
- [Completion Expectations](planned/completion/expectations.md) — expected completion behavior.
- [Completion Gap Log](planned/completion/gap-log.md) — active completion gaps, including method references and generic
  bounds.
- [Completion Discovery Workflow](planned/completion/discovery-workflow.md) — reproducible gap discovery and triage.
- [Google Indentation](planned/lathe-google-indent.md) — conservative on-type formatting.
- [Class/Import Semantic Highlighting](planned/lathe-class-import-semantic-highlighting.md) — Neovim-relevant semantic
  corrections for type references.
- [Streaming References](planned/lathe-streaming-references.md) — progress and partial results for long searches if
  measurements justify the work.
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

## Completed Designs

- [Completion Design](done/completion-design.md)
- [Completion Gap Fixes](done/completion-gap-fixes.md)
- [Completion Gaps](done/completion-gaps.md)
- [Completion Semantics Audit](done/completion-semantics-audit.md)
- [Architecture and Test Improvements](done/lathe-architecture-test-improvements.md)
- [Code Actions](done/lathe-code-actions.md)
- [June 2026 Code Review](done/lathe-code-review-jun-2026.md)
- [Completion Disabled-Test Gaps](done/lathe-completion-disabled-test-gaps.md)
- [Completion Presentation](done/lathe-completion-presentation.md)
- [Standard File URI Scheme](done/lathe-file-uri-scheme.md)
- [Find References](done/lathe-find-references.md)
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

## Historical or Re-Evaluate Before Use

- [Code Quality Refactoring](planned/lathe-code-quality-refactoring.md) — superseded where it overlaps the current
  maintainability design; retained as review history.

## Potential Designs

- [Potential Design Policy](potential/README.md)
- [Shared Workspace Server](potential/lathe-shared-workspace-server.md) — no active milestone commitment.
