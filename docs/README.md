# Lathe — Documentation Map

Start here. This page describes how `docs/` is organized; it does not contain design content itself.

## Governance (read first)

These four define the project's direction and current truth:

- [lathe-design.md](lathe-design.md) — the stable architecture.
- [roadmap.md](roadmap.md) — release milestones (M1–M3, post-M3) and exit criteria; authoritative for scope.
- [status.md](status.md) — what works today and the known gaps; authoritative for the implemented baseline.
- [design-index.md](design-index.md) — maps every design document to the roadmap.

## Gap tracking — `gaps/`

One process for every kind of gap (navigation `EG`, references `FR`, code actions `CA`, completion `CQ`):

- [gaps/gap-process.md](gaps/gap-process.md) — the lifecycle: the `Status` and `Target` fields, the
  document → target → implement → done round, and the definition of done.
- [gaps/gap-workflow.md](gaps/gap-workflow.md) — how to discover and record a gap (the `explore.py`
  tooling and probe recipes; completion is the richest area).
- [gaps/gaps.md](gaps/gaps.md) — the single active registry of open gaps.
- [gaps/gaps-archive.md](gaps/gaps-archive.md) — resolved (`done` / `non-goal`) gaps, kept for the record.

## Design documents

- `planned/` — active designs for work that is **not yet implemented**.
- `done/` — completed design records (the implemented baseline and historical decisions).
- `potential/` — ideas with **no active milestone commitment** (see [potential/README.md](potential/README.md)).

A design moves `planned/` → `done/` once it ships. The [design-index](design-index.md) groups all of
them by milestone, so use it to find the design for a given feature.

## Conventions

- Design files use the `lathe-` prefix.
- Prose uses semantic line breaks (one sentence or clause per line).
- The roadmap is authoritative when a design's status wording conflicts with it.
