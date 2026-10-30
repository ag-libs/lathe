# Lathe — Gap Lifecycle

This document defines the single lifecycle that every gap follows, regardless of which family it belongs to or which file records it.
It is the authoritative answer to one question a gap must always answer: *what state is it in*.

The [roadmap](../roadmap.md) defines release scope; this document defines how an individual gap moves toward a release.

---

## Where gaps live

All open gaps share a single active registry, [gaps.md](gaps.md), regardless of area.
Each keeps its area prefix:

| Prefix | Area | Scope |
|---|---|---|
| `EG-NNN` | exploration | Live-probing: navigation, hover, search, completion, code actions, hierarchies |
| `FR-NNN` | references | `textDocument/references` scope, failure propagation, coverage |
| `CA-N` | code-action | `textDocument/codeAction` providers |
| `CQ-NNNN` | completion | Completion quality |
| `WS-N` | workspace lifecycle | Reactor mirror and type-index freshness, source watching, sync prompting, and reload |
| `TE-N` | test execution | Maven test-fork capture, replay launch fidelity, and test-classpath isolation |
| `DB-N` | debug & evaluation | In-process DAP adapter and expression-evaluator scope, fidelity, and coverage |
| `NV-N` | neovim client | The shipped Neovim plugin and its recommended configuration |
| `MC-N` | MCP / agent facade | The in-process `LatheEngine` facade and `lathe-mcp-server` tool surface for AI agents |

Resolved (`done` / `non-goal`) entries move to [gaps-archive.md](gaps-archive.md).
Discovery and triage follow the single [gap workflow](gap-workflow.md).
Completion keeps area-specific *reference* material — its behavioral contract,
[expectations.md](../done/lathe-completion-expectations.md) — but no separate process.

---

## The `Status` field

Every gap carries a `Status`.

| Status | Meaning |
|---|---|
| `documented` | Captured with a probe and expected behavior; not yet triaged. |
| `accepted` | Real and in scope for the next release. |
| `deferred` | Valid behavior, but not planned for the next release (the backlog). |
| `non-goal` | Deliberately outside Lathe's contract; will not be implemented. |
| `in-progress` | Being implemented now. |
| `done` | Implemented and verified; **must** carry a regression target. |

Releases are **demand-driven**: they are cut when the accepted work is worth shipping and named at cut time.
A gap carries no release field; what a release shipped is recorded by its version and the archived entries, not promised ahead of the cut.

---

## Lifecycle

```
 documented ──triage──► accepted ──► in-progress ──► done (+ regression test)
     │                     ▲                            │
     ├──► deferred ──── next round ◄────────────────────┘
     └──► non-goal (rejected)
```

### The round

1. **Document** — record the gap with a probe command, the expected behavior, and the evidence.
   One line is enough; full prose is only required once a gap is `accepted`.
   New gaps start `documented`.
2. **Triage** — set `Status`: `accepted`, `deferred`, or `non-goal`.
   A common-sense feedback signal (see the optional `Signal:` field) is what moves a gap from `deferred` to `accepted`.
3. **Implement** — move entries `in-progress` → `done` and attach a regression target.
   A gap is not `done` without a test.
4. **Repeat** — the next round re-triages `documented` and `deferred` gaps.

### The current slice is derived, not hand-maintained

The work for the next release is whatever has `Status: accepted`.
Do not keep a separate ordered "implementation order" list; it duplicates the status and drifts.
To see the slice:

```bash
grep -n 'Status: accepted' docs/gaps/gaps.md
```

---

## Definition of done

A gap reaches `done` only when:

- the implementation is merged and verified, and
- a regression target (test name) is recorded on the entry.

Move durable design lessons to `docs/done/` only when they remain useful after the fix; the gap
entry itself stays in its home file as the record.

---

## Optional metadata

Families may carry extra fields without changing the lifecycle.

A triaged gap that is not deferred carries `Value` and `Size` on one line under its `Status`, e.g. `**Value: high — Size: S**`:

| Field | Values |
|---|---|
| `Value` | `high`: wrong answers, broken code, or something hit daily; `medium`: gets in the way regularly; `low`: rare or cosmetic. |
| `Size` | `S`: under a day with its tests; `M`: one to three days; `L`: more. Ranges (`S–M`) are fine. An estimate, not a commitment. |

Pick the next work by value first, then size: `grep -n 'Value: high' docs/gaps/gaps.md`.

Completion entries keep `Tier` (`basic` / `typed` / `assistive` / `presentation`), `Failure mode`,
and `Owner component` as defined by the completion [expectations](../done/lathe-completion-expectations.md)
and the [gap workflow](gap-workflow.md).
`Tier` is a feature category, not a priority or a release.

Any gap may carry an optional one-line `Signal:` field recording the feedback evidence that argues for
doing it — who hit it, how often, in what workflow (e.g. `Signal: 3 beta users hit this after a branch
switch`). This is the common-sense demand signal that moves a gap from `deferred` to `accepted`;
it is evidence, not a priority number, and absence of a `Signal:` line just means none has been recorded yet.
