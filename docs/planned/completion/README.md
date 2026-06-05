# Lathe — Completion Planning

This folder contains the active planning documents for Java completion quality.

Start here when triaging completion behavior:

- [`expectations.md`](expectations.md) — the IntelliJ/JDT-informed Lathe completion contract.
- [`discovery-workflow.md`](discovery-workflow.md) — how to use `dev/explore.py`,
record discrepancies,
select the current external target projects,
and promote findings into regression coverage.
- [`gap-log.md`](gap-log.md) — active `CQ-*` completion-quality gaps.

Related documents:

- [`../lathe-completion-presentation.md`](../lathe-completion-presentation.md) —
planned JDT LS-style completion presentation and `labelDetails`.
- [`../../done/completion-design.md`](../../done/completion-design.md) —
current implemented completion architecture.
- [`../../done/completion-semantics-audit.md`](../../done/completion-semantics-audit.md) —
historical syntax-site audit.
- [`../../done/completion-gaps.md`](../../done/completion-gaps.md) —
historical gap notes and resolved implementation details.
- [`../../done/completion-gap-fixes.md`](../../done/completion-gap-fixes.md) —
historical fix plan for earlier completion gaps.

Use this split as follows:

- expectations define what Lathe should do;
- discovery workflow defines how to find and classify discrepancies;
- gap log records current unresolved work;
- historical docs explain how the current implementation got here.
