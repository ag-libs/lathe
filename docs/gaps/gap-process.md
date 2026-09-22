# Lathe — Gap Lifecycle

This document defines the single lifecycle that every gap follows, regardless of which family it
belongs to or which file records it.
It is the authoritative answer to two questions a gap must always answer:
*what state is it in* and *which release is it targeted to*.

The [roadmap](../roadmap.md) defines release scope; this document defines how an individual gap moves
toward a release.

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
[expectations.md](../planned/lathe-completion-expectations.md) — but no separate process.

---

## Two mandatory fields

Every gap carries `Status` and `Target`.

### `Status`

| Status | Meaning |
|---|---|
| `documented` | Captured with a probe and expected behavior; not yet triaged. |
| `accepted` | Real and in scope; **must** carry a `Target` of `next` or a named version. |
| `deferred` | Valid behavior, but not in a current release slice (`Target: backlog`). |
| `non-goal` | Deliberately outside Lathe's contract; will not be implemented. |
| `in-progress` | Being implemented now. |
| `done` | Implemented and verified; **must** carry a regression target. |

### `Target`

| Target | Meaning |
|---|---|
| `M1` / `M2` | The two pre-planned milestones; both shipped. Historical — no new gaps target them. |
| a named version (`0.1.x`, `0.2.0`, …) | Committed to a specific release once that release is being cut. |
| `next` | Accepted; goes in the next release cut, which is not yet named or dated. The staging bucket that a version cut is swept from. |
| `backlog` | Real but uncommitted; re-triaged by feedback signal in a future round. |

Post-beta, releases are **demand-driven**: no new `Mn` milestone is pre-declared (the `M3` tag on a few
already-shipped archive entries is retired history, superseded by version numbering). Work accumulates
in `next`; when a `next` batch is worth shipping it is cut and given a version number (`0.2.0`, …), and
those entries are retargeted from `next` to that version. A named release is therefore the *result* of
a cut, never a promise made ahead of one.

`Target` is the single source of truth for release assignment.
The roadmap references gaps by id and target rather than re-describing them, so a gap's release is
never stated in two places that can drift.

---

## Lifecycle

```
 documented ──triage──► accepted (Target: next | version) ──► in-progress ──► done (+ regression test)
     │                      ▲                                                      │
     ├──► deferred (Target: backlog) ───────────────── next round ◄───────────────┘
     └──► non-goal (rejected)
```

### The round

1. **Document** — record the gap with a probe command, the expected behavior, and the evidence
   (one line is enough; full prose is only required once a gap is `accepted` for the current
   release). New gaps start `documented`.
2. **Target** — triage sets `Status` and `Target`: `accepted` + `next` (or a named version once a cut
   is underway), `deferred` + `backlog`, or `non-goal`. A common-sense feedback signal (see the
   optional `Signal:` field) is what graduates a gap from `backlog` to `next`.
3. **Implement** — work the current slice, move entries `in-progress` → `done`, and attach a
   regression target. A gap is not `done` without a test.
4. **Repeat** — the next round re-triages `documented` and `deferred` gaps and bumps targets. When the
   `next` batch is worth shipping, cut a version and retarget those entries from `next` to it.

### The current slice is derived, not hand-maintained

The work for the next release is whatever matches `Status: accepted` and `Target: next` (or the
version currently being cut).
Do not keep a separate ordered "implementation order" list; it duplicates `Target` and drifts.
To see the slice:

```bash
grep -n 'Target: next' docs/gaps/gaps.md
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
Completion entries keep `Tier` (`basic` / `typed` / `assistive` / `presentation`), `Failure mode`,
and `Owner component` as defined by the completion [expectations](../planned/lathe-completion-expectations.md)
and the [gap workflow](gap-workflow.md).
`Tier` is a feature category, not a priority or a release — release targeting is always `Target`.

Any gap may carry an optional one-line `Signal:` field recording the feedback evidence that argues for
doing it — who hit it, how often, in what workflow (e.g. `Signal: 3 beta users hit this after a branch
switch`). This is the common-sense demand signal that graduates a gap from `backlog` to `next`; it is
evidence, not a priority number, and absence of a `Signal:` line just means none has been recorded yet.
