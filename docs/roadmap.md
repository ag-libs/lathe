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

Neovim run/test integration has shipped (capture-replay test execution via the neotest adapter);
the debugger (DAP / JDWP attach) and VS Code support are post-M3 work.

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
  closed-file case is folded into the broader source/branch-switch staleness gap (WS-1), which is
  Post-M3 work.

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

M2 completes the planned LSP editing, navigation, and refactoring set needed for normal Java development in Neovim.
It remains a build-from-source release.

### Navigation and references

- Complete external-source Find References scope, failure propagation, and invoker coverage.
- Consider partial-result streaming only if post-M1 measurements show material result latency or memory pressure.

### Completion and search

- Implement generic-bound receiver completion for wildcard and type-variable upper bounds.
- Implement declaration-name completion for variables, fields, parameters, and type parameters.
- Close additional reproducible completion gaps accepted into the M2 gap log.
- Add CamelCase initial matching to workspace symbol search (EG-005).

See [gaps.md](gaps/gaps.md) for EG-005 and accepted M2 completion-gap detail.

### Editing and refactoring

- Implement conservative on-type indentation without advertising unsupported formatting behavior.
- Implement prepare-rename and exact reactor rename edits.
- Implement useful Java inlay hints.
- Complete the richer code actions accepted for M2.
- Complete Neovim-relevant semantic highlighting where tree-sitter cannot classify identifiers correctly.
- Implement LSP work-done progress notifications for workspace initialization and reload.

### Workspace behavior

- Re-evaluate source watching and closed-file invalidation against the current lightweight watcher before adding work.
- Optimize reactor indexing only where M1/M2 measurements identify a material bottleneck.
- Keep Neovim as the only supported editor integration.

### Exit criteria

- Public Neovim users can install from source and perform normal Java editing, navigation, and refactoring workflows.
- Every advertised LSP capability has end-to-end coverage and documented limitations.
- Public setup, compatibility, troubleshooting, and diagnostics-collection documentation is complete.
- Debug (DAP), VS Code, and Maven Central publication are explicitly documented as later scope
  (Neovim run/test execution has shipped).

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
cancellation, and the neotest adapter (discovery, run at every level, inline diagnostics). What
remains post-M3 is the **debugger**: launch the replay JVM suspended and attach JDWP, plus named run
configs and arg/env/cwd overlay.
See [lathe-run-test-debug.md](planned/lathe-run-test-debug.md) and the shipped scope in
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
