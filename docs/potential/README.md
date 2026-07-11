# Potential Designs

Documents in this directory preserve architectural ideas that are not part of the active roadmap.
They are not assigned to M1, M2, M3, or post-M3 work and should not be treated as approved implementation designs.

Move a document to `docs/planned/` only after the idea is reprioritized, reviewed against the current implementation,
and explicitly approved for implementation.

## Feature requests

Untriaged feature requests land here as one `lathe-<name>.md` file each, and are listed in
[design-index.md](../design-index.md) under "Potential Designs".
They are requests, not approved designs: they capture the pain and a rough direction, not a
committed implementation plan.
A request graduates by moving its file to `docs/planned/` once approved and assigned a milestone.

Keep each request light and uniform, using this skeleton:

```markdown
# Lathe — <Feature>

## Problem / motivation
Why this matters; the user-visible pain.

## Sketch
Rough approach — no committed design.

## Open questions
What needs deciding before it could be planned.

## Milestone candidate
A guess (M2, M3, post-M3), or "untriaged".
```
