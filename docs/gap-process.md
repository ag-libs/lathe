# Lathe — Gap Lifecycle

This document defines the single lifecycle that every gap follows, regardless of which family it
belongs to or which file records it.
It is the authoritative answer to two questions a gap must always answer:
*what state is it in* and *which release is it targeted to*.

The [roadmap](roadmap.md) defines release scope; this document defines how an individual gap moves
toward a release.

---

## Gap families and where they live

Gaps keep their family prefix and their existing home file, but share the schema below.

| Family | Prefix | Home | Scope |
|---|---|---|---|
| Exploration | `EG-NNN` | [planned/lathe-exploration-gaps.md](planned/lathe-exploration-gaps.md) | Live-probing: navigation, hover, search, editor features |
| Find References | `FR-NNN` | same file | `textDocument/references` scope, failure propagation, coverage |
| Code Action | `CA-N` | same file | `textDocument/codeAction` providers |
| Completion | `CQ-NNNN` | [planned/completion/gap-log.md](planned/completion/gap-log.md) | Completion quality; part of the completion discovery workflow |

The completion gap log stays a separate file because it is a living, process-managed queue with its
own [discovery workflow](planned/completion/discovery-workflow.md) and [expectations](planned/completion/expectations.md).
It adopts this lifecycle; it is not merged.

---

## Two mandatory fields

Every gap carries `Status` and `Target`.

### `Status`

| Status | Meaning |
|---|---|
| `documented` | Captured with a probe and expected behavior; not yet triaged. |
| `accepted` | Real and in scope; **must** also carry a `Target`. |
| `deferred` | Valid behavior, but not in a current release slice (`Target: backlog`). |
| `non-goal` | Deliberately outside Lathe's contract; will not be implemented. |
| `in-progress` | Being implemented now. |
| `done` | Implemented and verified; **must** carry a regression target. |

### `Target`

| Target | Meaning |
|---|---|
| `M1` / `M2` / `M3` | Scheduled for that release. |
| `backlog` | Deferred; re-triaged in a future round. |

`Target` is the single source of truth for milestone assignment.
The roadmap references gaps by id and target rather than re-describing them, so a gap's milestone is
never stated in two places that can drift.

---

## Lifecycle

```
 documented ──triage──► accepted (Target: M1|M2|M3) ──► in-progress ──► done (+ regression test)
     │                      ▲                                                      │
     ├──► deferred (Target: backlog) ───────────────── next round ◄───────────────┘
     └──► non-goal (rejected)
```

### The round

1. **Document** — record the gap with a probe command, the expected behavior, and the evidence
   (one line is enough; full prose is only required once a gap is `accepted` for the current
   release). New gaps start `documented`.
2. **Target** — triage sets `Status` and `Target`: `accepted` + a release, `deferred` +
   `backlog`, or `non-goal`.
3. **Implement** — work the current slice, move entries `in-progress` → `done`, and attach a
   regression target. A gap is not `done` without a test.
4. **Repeat** — the next round re-triages `documented` and `deferred` gaps and bumps targets.

### The current slice is derived, not hand-maintained

The work for a release is whatever matches `Status: accepted` and `Target: <release>`.
Do not keep a separate ordered "implementation order" list; it duplicates `Target` and drifts.
To see the slice:

```bash
grep -nE 'Target: M1' docs/planned/lathe-exploration-gaps.md docs/planned/completion/gap-log.md
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
and `Owner component` as defined by the completion [expectations](planned/completion/expectations.md)
and [discovery workflow](planned/completion/discovery-workflow.md).
`Tier` is a feature category, not a priority or a release — milestone targeting is always `Target`.
