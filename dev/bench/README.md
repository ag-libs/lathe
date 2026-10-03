# Lathe MCP Value — Pre-Registration

**Status: PRE-REGISTRATION.**
This document fixes the methodology, the endpoints, and the kill criterion *before* any result
exists.
It is committed to git ahead of the first scored run so that the analysis cannot be steered by the
data.
Results, when they exist, go in `results/` and are reported against the hypothesis stated here —
unchanged.

Design rationale and the broader harness vision live in
[`docs/planned/lathe-mcp-value-benchmark.md`](../../docs/planned/lathe-mcp-value-benchmark.md) and
[`docs/planned/lathe-ai-agent-integration.md`](../../docs/planned/lathe-ai-agent-integration.md#measurement).
This file is the operational pre-registration for the **local git-replay pilot**.

## The question

A single falsifiable, *per-axis* claim — not "MCP is good":

> On moat-shaped multi-module Maven tasks, a Claude Code agent **+ Lathe MCP** beats the **grep-only**
> agent on `{tokens-to-outcome, missed-sites}` — measured as paired within-task deltas — **without
> regressing** resolved-rate or wall-time.

The first field A/B on record was **negative** (≈3× cost for a correctness tie on a distinctive-name
lookup).
The design must be able to reproduce that negative.
A methodology that cannot disprove the thesis is not evidence — it is a demo.

## Scope and honest limits (reproduced verbatim in any write-up)

This is a **pilot**.
Its job is to detect **whether an effect exists, its direction, and its mechanism** — *not* to
produce a publication-grade effect size.
A 4-task pilot cannot put a tight confidence interval on a binary resolved-rate; the effect size, if
the pilot shows signal, comes from the scaled headline run.
Claiming a precise effect from four tasks would itself be the kind of over-reach this design exists to
avoid.

## Design — paired, single-variable ablation

Both arms run the **same** model, the **same** scaffold, the **same** prompt, against the **same**
instance.
The **only** difference is whether the Lathe MCP server is registered.

| | Baseline | Treatment |
|---|---|---|
| Scaffold | Claude Code headless (`claude -p … --output-format json`) | identical |
| Tools | Read, Grep, Glob, Bash (`mvn`), Edit | identical **+** `lathe` MCP server |
| MCP config | none | `--mcp-config` → snapshot launcher (below) |
| Prompt | the task `problem_statement` | identical |
| Oracle visible to agent? | no | no |

Treatment MCP launcher (already built):
`~/.cache/lathe/servers/0.1.0-SNAPSHOT/lathe-mcp-launcher.sh`
(verified: loads dropwizard's 68 modules in ~0.5 s and exposes 11 tools).

Pairing is the core of the design: both arms face the identical instance, so task difficulty, prompt
phrasing, and training-data contamination **cancel in the within-task delta**.
Contamination inflating an *absolute* resolved-rate is therefore harmless here — a genuine advantage
of an ablation over chasing a leaderboard.

## Endpoints

Chosen for statistical power at small N: continuous metrics that move on *every* task, with the
coarse binary demoted to secondary.

**Primary (pre-registered):**

| Metric | Source | Win direction | Why primary |
|---|---|---|---|
| Tokens-to-outcome | agent usage JSON | ↓ | continuous, high-power, the real cost |
| Missed real sites vs. gold | diff vs. gold | ↓ | the moat — completeness grep gets wrong |

**Secondary (reported, not pre-registered as the headline):**

| Metric | Source | Win direction |
|---|---|---|
| Resolved (oracle green, no regressions) | repo tests via `mvn` | ↑ |
| #grep+read+bash calls vs. #Lathe-tool calls | transcript | ↓ grep |
| Wrong / superseded edits | diff vs. gold | ↓ |
| Wall-clock | run timing | ↓ |

The **distrust tax** is made observable by the tool-call-mix secondary: a capability win that shows as
a cost tie (the agent calls a Lathe tool *and still greps to re-verify*) is a specific, nameable
outcome, not a hidden null.

### Why *two* primary endpoints — compiler-caught vs. silently-wrong

Authoring the first tasks surfaced a structural fact about Java that determines which metric can move
on which axis, and it is the reason tokens-to-outcome **and** missed-sites are *both* primary:

- On **compiler-enforced** changes (add a parameter, add an interface method), a missed or wrong site
  **fails to compile** — the build points the agent straight at it. Both arms converge to zero missed
  sites; the difference shows up as **how many tokens/turns** the fix-compile-refix loop costs. Lathe's
  edge here is front-loading the site set (`find_implementations` / `find_references`) instead of
  discovering it one compile error at a time.
- On **silently-wrong** changes (overload-sensitive rename, semantic substitution), a missed or wrong
  site **still compiles** — e.g. a renamed call binds to a different overload — so the compiler is no
  safety net and only a behavioural test or a semantic tool catches it. Here **missed-sites** is the
  discriminating metric, and grep+sed is actively dangerous.

So the corpus must carry both kinds, and each task is reported against the metric its axis can move.
A single-metric design would look flat on half the corpus for a structural reason, not a real null —
which is exactly the kind of artefact that would discredit a published result.

## Corpus and task-admission criteria

Provenance **C**: synthetic-on-HEAD first (prove the pipeline, reuse the fresh `.lathe/`, no
recapture), then real historical commits for the headline run.

Every task must be a **change whose correctness the build judges**, on the moat axes where text search
is wrong or drowning:

- cross-module signature change (add a param to an interface method called across modules)
- overload-sensitive rename (rename one of several same-named methods)
- implement-an-interface across modules
- safe symbol removal (delete a member, remove every cross-module use)
- (Helidon) generated-source use

**Admission gate — the discrimination pre-screen.**
A task enters the corpus **only if a baseline grep-only pilot run actually struggles** (greps several
ways, misses a site, or over-costs).
If grep nails it cleanly, the task is in the tie-zone and is **cut**.
This operationalises the discriminator trap: no read-only enumeration of rare, distinctive names
(a guaranteed tie), and no task so easy both arms breeze it (a guaranteed null).

## Oracle — hermetic, never Lathe

Scoring is the repo's **own** tests, run by plain `mvn`, identical for both arms:
a task ships with a `FAIL_TO_PASS` set (tests that fail at base, pass on the gold change) and a
`PASS_TO_PASS` set (existing tests that must stay green).
`resolved` = patch applies ∧ compiles ∧ `FAIL_TO_PASS` green ∧ `PASS_TO_PASS` green.
Lathe is **never** in the scoring path — that would be circular.

## Protocol

For each `(task × arm × repeat)`:

1. `git worktree add` a clean tree at the task's base ref.
2. (Treatment only) place `--mcp-config` registering `lathe` at the snapshot launcher.
3. Run the agent headless with `problem_statement` as the sole prompt; the oracle tests are withheld.
4. Capture `git diff` → `prediction.patch`; capture the transcript → tokens, tool-call mix, wall-time.
5. Score hermetically (above) in a fresh tree: apply patch → apply oracle tests → `mvn`.

- **Repeats over tasks for variance control:** pilot = **4 tasks × R = 4** (concentrate repeats on
  fewer tasks rather than spread R = 2 thin).
- **Randomize arm order** per task to blunt any ordering/drift effect; run a task's arms close in time.
- Raw transcripts and scored rows are written under `results/` **locally only** (git-ignored — they
  carry absolute paths and the local agent-config dump, and are reproducible from the harness + tasks).
  Only curated summaries (`results/*.md`) are committed.

## Analysis

- **Paired within-task deltas** (treatment − baseline), median-of-R per cell.
- **Bootstrap confidence intervals** on the paired deltas (not parametric — N is small and
  distributions are skewed).
- Grouped by **moat axis** and by **module cardinality** (single- vs. multi-module).
- Report the primary endpoints as the headline; secondaries as mechanism.

## Pre-registered hypothesis

> Lathe's largest advantage appears on **multi-module** tasks, driven by
> `find_references` / `call_hierarchy` / `get_definition` / `search_symbols`, showing as **fewer tokens
> and fewer missed sites** at equal-or-better resolved-rate; **single-module** tasks tie.

## Kill criterion (what a published negative looks like)

If, across the pilot, treatment **ties** the baseline on the primary endpoints **and** costs **≥ 1.5×
tokens** — the distrust tax — that is the honest result and it is reported as such.
No metric is dropped, reweighted, or added post hoc to rescue a positive.

## Threats to validity → mitigations

| Threat | Mitigation |
|---|---|
| Stochastic agent — single run is noise | R = 4 paired repeats; bootstrap CIs |
| Binary resolved-rate low-resolution at small N | continuous primary endpoints; resolved-rate secondary |
| Ceiling/floor (both arms tie) | discrimination pre-screen admits only tasks where baseline struggles |
| Capability win hidden as cost tie | tool-call-mix secondary surfaces the distrust tax |
| Task difficulty / prompt / model drift | within-task pairing; randomized arm order; arms run close in time |
| Author-chosen synthetic tasks look cherry-picked | provenance C graduates to real historical commits for the headline |
| Contamination inflates absolute rates | cancels in the paired delta (ablation, not leaderboard) |
| Circular scoring | hermetic `mvn` oracle, Lathe never in the scoring path |

## Harness layout

```
dev/bench/
  README.md            # this pre-registration
  tasks/*.yaml         # one manifest per instance: problem_statement, base ref, moat axis,
                       #   FAIL_TO_PASS, PASS_TO_PASS, gold-diff ref
  run.py               # per (task × arm × repeat): worktree → headless claude → diff + transcript
  score.py             # apply patch → mvn oracle → resolved? + diff-vs-gold (missed/wrong sites)
  report.py            # paired deltas, median-of-R, bootstrap CIs, grouped by axis + cardinality
  results/             # curated *.md summaries only; per-run artifacts are git-ignored (local)
```

## Milestones

1. **M1 — pipeline proof.** 2 synthetic-on-HEAD dropwizard tasks (cross-module signature change +
   overload-sensitive rename), both arms, R small, hand-checked. Proves `run.py`/`score.py` end to end.
2. **M2 — metrics + analysis.** Wire transcript metrics and `report.py`; confirm paired deltas and CIs
   compute on M1 data.
3. **M3 — real corpus.** Graduate to provenance A: mine real merged dropwizard/helidon commits,
   recapture `.lathe/` at each parent, apply the discrimination pre-screen.
4. **M4 — headline run + report.** R = 4, paired deltas by axis and cardinality with CIs, written
   against the pre-registered hypothesis — including a negative if that is the result.

## Toolchain notes

- dropwizard requires Corretto 25: `JAVA_HOME=/opt/amazon-corretto-25.0.0.36.2-linux-x64`.
- helidon requires its own JDK (higher build cost → fewer instances; used for the generated-source
  axis and scale).
- The headless Claude Code flags for `--mcp-config` + JSON transcript are the one load-bearing
  external dependency; confirmed in M1's wiring step before any scored run.
