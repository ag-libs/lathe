# Lathe — Completion Gap Log

This file is a lightweight queue for current completion discrepancies.
Entries should be short and mechanical.

Move durable rules into [`expectations.md`](expectations.md).
Move process changes into [`discovery-workflow.md`](discovery-workflow.md).
Move resolved implementation notes to `docs/done/` only when they are useful after the fix.

## Status Values

| Status | Meaning |
|---|---|
| `new` | Captured but not triaged. |
| `accepted` | Lathe should support this behavior. |
| `deferred` | Valid behavior, but not in the current slice. |
| `non-goal` | Deliberately outside Lathe's current completion contract. |
| `covered` | Regression test or durable probe exists. |
| `fixed` | Implementation changed and verification passed. |

## Template

```text
## CQ-0001 — Short description

ID: CQ-0001
Status:
Tier:
Failure mode:
Owner component:

Project/file:
Probe command:
Cursor context:

IntelliJ or JDT behavior:
Lathe behavior:
Expected Lathe behavior:
Accepted edit, if relevant:

Regression target:
Notes:
```

## Open Entries

No active entries yet.

For the current discovery phase,
new entries should come only from Dropwizard or Helidon explorer probes.
