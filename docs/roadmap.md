# Lathe — Roadmap

This document defines release direction, milestone scope, and exit criteria.
It intentionally does not inventory completed implementation details or catalog every design document.

- [status.md](status.md) describes what works today and the known gaps.
- [design-index.md](design-index.md) maps active, completed, and exploratory designs.
- [lathe-design.md](lathe-design.md) defines the stable architecture.

When status wording in a feature design conflicts with this roadmap, this roadmap is authoritative.

---

## Release Sequence

| Milestone | Name | Version | Audience | Distribution |
|---|---|---|---|---|
| M1 | Internal Preview | `0.1.0-SNAPSHOT` | Internal daily use | Build from source |
| M2 | Neovim Public Beta | `0.1.0-beta.N` | Public Neovim users | Build from source |
| M3 | 0.1.0 General Availability | `0.1.0` | Public Neovim users | Maven Central |

Neovim run/test execution (capture-replay via the neotest adapter) and the debugger (in-process DAP
over JDWP attach) have both shipped and are part of the M2 beta; VS Code support is post-M3 work.

---

## M1 — Internal Preview

M1 makes the current Neovim workflow reliable for daily internal use.
It includes the current implementation and every active, non-deferred correctness and maintainability gap.

### Implemented reliability baseline

- Make workspace-wide reference search use transient closed-file analysis and a process-wide javac concurrency cap.
- Show work-done progress for reference search and honor optional LSP request cancellation.
- Treat direct or wrapped `Error` as fatal instead of continuing with partially failed compiler workers.

### Correctness and maintainability ✓

- Replace empty-success exception handling at workspace fan-out boundaries with proper LSP failures.
- Remove hard-coded sleeps and other known flakiness from asynchronous tests.
- Correct design-document drift, then complete the focused fail-fast, naming, DRY, structural, and fixture
  slices in the consolidated refactoring plan.
- Preserve the existing server-event-loop and module-worker ownership model.

See [lathe-m1-refactoring.md](done/lathe-m1-refactoring.md).

### Call hierarchy ✓

- Implement `textDocument/prepareCallHierarchy`, `callHierarchy/incomingCalls`, and
  `callHierarchy/outgoingCalls`.
- Incoming calls reuse `ReferenceTarget` identity and `ReferenceCandidateIndex` candidate discovery.
- Outgoing calls scan the target method body in a single file.

See [lathe-call-hierarchy.md](done/lathe-call-hierarchy.md).

### Declaration ✓

- Implement `textDocument/declaration` to navigate an overriding method to its contract method
  (EG-012). Resolves to the root contract across the full supertype hierarchy and handles both the
  declaration site and the call site, falling back to `definition` for non-overriding symbols.

See [lathe-declaration.md](done/lathe-declaration.md).

### Live-probing correctness fixes

Gaps confirmed by systematic probing against Helidon, Dropwizard, and sample-workspace.
See [gaps.md](gaps/gaps.md).

- Fix signature help returning the wrong signature when the first argument is itself a method call (EG-001).
- Implement `TryCatchWrapProvider` for `UNREPORTED_EXCEPTION` in regular method and lambda bodies (EG-002).
- Fix hover returning null on import declaration positions (EG-004).
- Boost reactor-origin entries ahead of dependency and JDK entries in workspace symbol results (EG-006).
- Downgrade duplicate-type index messages from WARNING to FINE and deduplicate at merge time (EG-007).
- Suppress `wait`, `notify`, and `notifyAll` from member-access completion results (EG-008).
- Skip anonymous-class instantiations with empty names in outgoing-calls results (EG-009).
- Add a `--workspace` flag to `explore.py` so dependency and JDK cache sources can be probed (EG-010).
- Emit a descriptive unused-declaration message naming the declaration and its kind, with a stable code (EG-019).
- Add basic `module-info.java` directive completion for module names, exported/opened packages,
  service types, provider types, directive keywords, and module annotation contexts (CQ-0041).

### Code-action and index freshness gaps ✓

- Implement `MissingMethodImplProvider` for unimplemented abstract methods (CA-3).
- Make new and renamed reactor types available to missing-import actions without a manual Maven sync (CA-4).
  Done for types from a prior sync and types declared in an open, already-compiled file. The residual
  closed-file case is folded into the broader source/branch-switch staleness gap (WS-1); its full
  no-Maven freshness model stays backlog, while an advisory re-sync prompt (WS-2) lands in M2.

### Find References hardening

- ~~Confirm and document the external-symbol search-scope policy (FR-002).~~ Done: project-wide search retained; open-file-only restriction superseded by import filtering.
- Propagate failures instead of silently returning empty results (FR-003).
- Add end-to-end invoker coverage for `textDocument/references` (FR-004).

### Completion quality

- Add `bind(x).to(Y.class).` captured-wildcard member access (CQ-0040).

### Exit criteria

- The Neovim workflow is reliable for daily use on representative projects and Helidon-scale reactors.
- All M1 unit, integration, invoker, formatting, and Neovim verification layers pass.

---

## M2 — Neovim Public Beta

M2 is a **lean public beta**: the goal is a Lathe that is reliable and trustworthy on real projects,
distributed publicly (build from source; Maven Central is M3), and documented — not a broad new-feature
milestone. Run/test execution (neotest) and the debugger (in-process DAP over a suspended JDWP replay:
breakpoints, stepping, inspection, conditional breakpoints, expression evaluation) already ship and are
headline capabilities of the beta.

Scope is deliberately narrow — reliability and trust, the triaged correctness gaps, rename, and public
onboarding. The broader editing-feature set is deferred to the backlog and re-triaged after the beta
ships (see "Deferred to backlog" below).

### Reliability and trust

- Raise an advisory re-sync prompt when sources change or a git branch switches, so the workspace never
  goes silently stale ([WS-2](gaps/gaps.md)); the full no-Maven freshness model (WS-1) remains deferred.
- Go-to-definition returns no result instead of the file top on unresolved targets ([EG-049](gaps/gaps.md)).
- Give JDK/dependency sources a read-only affordance instead of live diagnostics and code actions ([EG-041](gaps/gaps.md)).
- Surface server-crash / module-failure clearly and provide one-command diagnostics collection.
- Establish a measured cold-index budget and a documented "known limits" page for large, arbitrary reactors.

### Correctness gaps (triaged)

- Label unused exception/lambda parameters by their JLS kind, not "local variable" ([EG-039](gaps/gaps.md)).
- Go-to-definition on a record accessor resolves to the component, not the file top ([EG-047](gaps/gaps.md)).
- Add a "replace `var` with the inferred type" code action ([CA-5](gaps/gaps.md)).
- Member completion on an array-typed receiver ([CQ-0053](gaps/gaps.md)).

### Refactoring

- Implement prepare-rename and exact reactor rename edits, correctness-gated with explicit non-goals —
  a wrong cross-module rename corrupts code, so it ships tested or it is not advertised.

### Run, test, and debug (shipped)

- Run/test execution (neotest) and the debugger (DAP/JDWP attach) are shipped headline capabilities; the
  only deferred debug tail is DB-1 (assignment/`setVariable`) and DB-2 (array-creation) evaluation.

### Onboarding and docs

- Build-from-source public setup, getting-started, troubleshooting, diagnostics collection, and the
  known-limits page. Maven Central publication is M3.

### Deferred to backlog

Re-triaged after the beta ships; explicitly **not** M2 scope:

- Inlay hints; conservative on-type indentation.
- Neovim-relevant semantic highlighting (including local-variable-vs-field) —
  see [lathe-semantic-tokens.md](planned/lathe-semantic-tokens.md).
- Generic-bound receiver completion; declaration-name completion.
- Reference partial-result streaming (revisit only if measurements show latency/memory pressure).
- Work-done progress for workspace initialization and reload.
- Full no-Maven workspace freshness (WS-1); go-to-definition on an incomplete method call
  ([EG-048](gaps/gaps.md)); references *inside* external sources (source browsing).

### Exit criteria

- Public Neovim users install from source and perform normal Java editing, navigation, and refactoring
  (including rename) reliably — including after a branch switch, where they are prompted to re-sync
  rather than shown stale results.
- Every advertised LSP capability has end-to-end coverage and documented limitations.
- Setup, compatibility, troubleshooting, diagnostics-collection, and known-limits documentation is complete.
- VS Code support and Maven Central publication are explicitly documented as later scope; run/test and
  the debugger have shipped as part of the beta.

---

## M3 — 0.1.0 General Availability

M3 publishes the M2 Neovim-focused language server to Maven Central.
It is a distribution, compatibility, documentation, and release-quality milestone rather than a new editor or execution
feature milestone.

### Maven Central publication

- Publish `lathe-core`, `lathe-compiler`, `lathe-server`, and `lathe-maven-plugin` under stable coordinates.
- Produce source and Javadoc JARs, signatures, checksums, required POM metadata, licensing metadata, and SCM links.
- Add reproducible release automation, staging verification, and rollback instructions.
- Define versioning, compatibility, and support policies.

### Installation and upgrade readiness

- Replace build-from-source examples with released Maven coordinates where appropriate.
- Verify first-checkout setup, workspace sync, launcher installation, clean upgrades, and rollback from a clean local Maven
  repository.
- Support `LATHE_JVM_OPTS` in the generated launcher.
- Finalize manifest module metadata only where it improves startup, staleness detection, or upgrades without duplicating
  compiler parameters.

### Release qualification

- Define supported JDK, Maven/mvnd, operating-system, and Neovim versions.
- Run full invoker, Neovim, large-workspace, clean-install, and upgrade verification in release CI.
- Document known limitations, cache cleanup, diagnostics collection, and issue-reporting requirements.
- Remove preview and beta terminology from GA user documentation.

### Exit criteria

- A Neovim user can configure Lathe with Maven Central artifacts without cloning this repository.
- Release artifacts and metadata pass Maven Central validation.
- Clean installation, upgrade, and rollback procedures are tested and documented.
- Released artifacts satisfy all M2 feature and reliability criteria.

---

## Post-M3

### Debug

Capture-replay **test execution** has shipped for Neovim — capture rides the `mvn test` fork, replay
runs a fresh JVM against `.lathe/` bytecode (no recompilation) with streamed output/test events,
cancellation, and the neotest adapter (discovery, run at every level, inline diagnostics).

The **debugger** shipped for Neovim ahead of this original post-M3 slot and is now a capability of the
M2 beta (see "M2 — Neovim Public Beta"): the server hosts the Microsoft java-debug DAP adapter
in-process (attach-only) and attaches it over JDWP to a suspended replay of a test, `main`, or
test-scoped `main` class, driven by an `nvim-dap` client (`:LatheDebug`). Breakpoints, stepping,
inspection, conditional breakpoints, expression evaluation (reads, method/constructor invocation,
`String` concat, force-loading cold classes, and object-scoped collection/map logical views), and
debug-console completion all work. The remaining tail is the write path — DB-1
(assignment/`setVariable`) — and DB-2 (array-creation); named run configs (`:LatheRun {name}`, TE-2)
are the main run-config UX gap.
See [lathe-run-test-debug.md](done/lathe-run-test-debug.md) §12.11–§12.12 and the shipped scope in
[lathe-neotest-experience.md](planned/lathe-neotest-experience.md).

### VS Code support

Provide a supported VS Code integration, complete identifier-level semantic-token coverage, workspace-diagnostics UX,
and editor-specific installation and testing.
See [lathe-semantic-tokens.md](planned/lathe-semantic-tokens.md).

### Quality tooling

Differential testing against jdtls: drive Lathe and Eclipse JDT LS with identical LSP requests over a shared
fixture, then compare answers semantically to surface behavioral divergences as EG-/CQ- gap candidates.
Deferred from M2 because it measures parity rather than adding a feature.
See [lathe-jdtls-differential-testing.md](planned/lathe-jdtls-differential-testing.md).

### Further work

- Sibling recompilation and closed-file workspace diagnostics.
- External/JDK method implementation indexing if demand justifies method metadata.
- Unsaved-source inheritance overlays.
- Shared workspace server exploration.
- Coverage UI, hot code replacement, and launch-configuration UX.
