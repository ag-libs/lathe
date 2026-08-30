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
| M2 | Neovim Public Beta | `0.1.0-beta.N` | Public Neovim users | Maven Central (beta) |

M1 (internal) is complete and M2 (public beta) is the active milestone. M2 publishes beta artifacts to
Maven Central, so public users install from released coordinates rather than building from source.
Everything beyond M2 — general availability (a stable `0.1.0`), VS Code, and further features — is
unscheduled and lives in the [Backlog](#backlog); the next release is planned only after public-beta
feedback. Run/test execution and the debugger have shipped and are part of the M2 beta.

---

## M1 — Internal Preview

M1 made the Neovim workflow reliable for daily internal use. It delivered the core LSP surface
(navigation, completion, diagnostics, hover, signature help, workspace/document symbols, type and call
hierarchy, folding, opt-in formatting), the reliability baseline (bounded concurrent compilation,
work-done progress and cancellation, fail-fast on `Error`, proper LSP failures at fan-out boundaries),
and the consolidated M1 refactoring — plus the live-probing correctness fixes found against Helidon,
Dropwizard, and the sample workspace.

The implemented baseline is in [status.md](status.md); the designs behind it in
[design-index.md](design-index.md#completed-designs); the resolved EG/CA/FR/CQ gaps in
[gaps-archive.md](gaps/gaps-archive.md). Per this document's scope, M1's completed details are not
re-listed here. The one residual thread is the closed-file staleness case folded into WS-1 (advisory
prompt WS-2 in M2; full freshness in the Backlog).

### Exit criteria

- The Neovim workflow is reliable for daily use on representative projects and Helidon-scale reactors.
- All M1 unit, integration, invoker, formatting, and Neovim verification layers pass.

---

## M2 — Neovim Public Beta

M2 is a **lean public beta**: the goal is a Lathe that is reliable and trustworthy on real projects,
distributed publicly via Maven Central (beta artifacts), and documented — not a broad new-feature
milestone. Run/test execution (neotest) and the debugger (in-process DAP over a suspended JDWP replay:
breakpoints, stepping, inspection, conditional breakpoints, expression evaluation) already ship and are
headline capabilities of the beta.

Scope is deliberately narrow — reliability and trust, the triaged correctness gaps, rename, and public
onboarding. The broader editing-feature set is deferred to the backlog and re-triaged after the beta
ships (see "Deferred to backlog" below).

### Reliability and trust

- Raise an advisory re-sync prompt when sources change or a git branch switches, so the workspace never
  goes silently stale ([WS-2](gaps/gaps.md)); the full no-Maven freshness model (WS-1) remains deferred.
- Give JDK/dependency sources a read-only affordance instead of live diagnostics and code actions ([EG-041](gaps/gaps.md)).
- Surface server-crash / module-failure clearly and provide one-command diagnostics collection.
- Establish a measured cold-index budget and a documented "known limits" page for large, arbitrary reactors.

### Correctness gaps (triaged)

- Label unused exception/lambda parameters by their JLS kind, not "local variable" ([EG-039](gaps/gaps.md)).
- Add a "replace `var` with the inferred type" code action ([CA-5](gaps/gaps.md)).
- Member completion on an array-typed receiver ([CQ-0053](gaps/gaps.md)).

### Refactoring

- Implement prepare-rename and exact reactor rename edits, correctness-gated with explicit non-goals —
  a wrong cross-module rename corrupts code, so it ships tested or it is not advertised.
  See [Rename](design-index.md) → [lathe-rename.md](planned/lathe-rename.md): built on the Find
  References pipeline, freshness-gated for method/type renames, minimal conflict checks.

### Run, test, and debug (shipped)

- Run/test execution (neotest) and the debugger (DAP/JDWP attach) are shipped headline capabilities; the
  only deferred debug tail is DB-1 (assignment/`setVariable`) and DB-2 (array-creation) evaluation.

### Distribution (Maven Central beta)

- Publish `lathe-core`, `lathe-compiler`, `lathe-server`, and `lathe-maven-plugin` as beta artifacts
  (`0.1.0-beta.N`) to Maven Central — source/Javadoc JARs, signatures, checksums, POM/licensing/SCM
  metadata, reproducible release automation, and staging verification.
- Public users install from released coordinates instead of building from source.

### Onboarding and docs

- Getting-started, troubleshooting, diagnostics collection, and the known-limits page, using the released
  Maven coordinates.

### Not in M2

The broader editing-feature set (inlay hints, on-type indentation, semantic-highlighting expansion,
generic-bound and declaration-name completion, new-type creation, `typeDefinition`), the full no-Maven
freshness model (WS-1), incomplete-call definition ([EG-048](gaps/gaps.md)), and source-browsing
references are **not** M2 scope — they are in the [Backlog](#backlog).

### Exit criteria

- Public Neovim users install from Maven Central (beta coordinates) and perform normal Java editing,
  navigation, and refactoring (including rename) reliably — including after a branch switch, where they
  are prompted to re-sync rather than shown stale results.
- Beta artifacts are published to Maven Central and pass its validation (signatures, POM metadata).
- Every advertised LSP capability has end-to-end coverage and documented limitations.
- Setup, compatibility, troubleshooting, diagnostics-collection, and known-limits documentation is complete.
- VS Code support and general availability (a stable `0.1.0`) are in the [Backlog](#backlog)
  (unscheduled); run/test and the debugger have shipped as part of the beta.

---

## Backlog

Unscheduled. The next release after the M2 public beta — general availability (a stable `0.1.0`) — is
planned only once beta feedback is in. Items are grouped, not ordered.

### General availability (promote the beta to `0.1.0`)

- Promote the Maven Central beta to a stable `0.1.0` under stable coordinates: remove preview/beta
  terminology; define versioning, compatibility, and support policies and the supported JDK, Maven/mvnd,
  OS, and Neovim versions; add rollback and full release qualification CI (large-workspace, clean-install,
  and upgrade verification).
- Support `LATHE_JVM_OPTS` in the generated launcher; finalize manifest module metadata where it improves
  startup, staleness detection, or upgrades.

### Editing features (deferred from M2)

- Rename beyond the M2 common cases; inlay hints; conservative on-type indentation.
- Generic-bound receiver completion; declaration-name completion; new-type creation.
- `textDocument/typeDefinition` (Neovim `grt`).
- Full identifier-level semantic tokens — local-variable-vs-field, class, and import highlighting
  (see [lathe-semantic-tokens.md](planned/lathe-semantic-tokens.md)).

### Reliability and freshness

- Full no-Maven workspace freshness (WS-1); sibling recompilation and closed-file workspace diagnostics.
- References *inside* external sources (source browsing); go-to-definition on an incomplete method call
  ([EG-048](gaps/gaps.md)); reference partial-result streaming (only if measurements justify it).

### Debug and run tail

- Debug write path — DB-1 (assignment/`setVariable`) and DB-2 (array creation); named run configs
  (`:LatheRun {name}`, TE-2). The debugger itself has shipped as part of M2.

### VS Code

- A supported VS Code integration with workspace-diagnostics UX and editor-specific install/testing;
  depends on the full semantic-token coverage above.

### Quality tooling

- Differential testing against Eclipse JDT LS: identical LSP requests over a shared fixture, compared
  semantically to surface behavioral divergences as EG-/CQ- gap candidates
  (see [lathe-jdtls-differential-testing.md](planned/lathe-jdtls-differential-testing.md)).

### Exploration

- External/JDK method-implementation indexing; unsaved-source inheritance overlays; shared workspace
  server; coverage UI, hot code replacement, and launch-configuration UX.
