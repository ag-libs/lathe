# Pilot results — R=4 (2026-10-03)

Supersedes the directional R=1 note (`PILOT-R1.md`). Four repeats per cell, two models, on the
overload-sensitive cross-module rename `dw-02` (`JerseyEnvironment.register(Class)` →
`registerResourceClass`). Numbers below are medians; ranges are min–max across the 4 runs. Scoring is
hermetic (dropwizard's own tests via mvn; Lathe never in the scoring path).

## dw-02 — the result

| model | arm | missed-site runs | cost $ (median, range) | turns | grep/read | lathe calls |
|---|---|---|---|---|---|---|
| **sonnet** | baseline (grep) | **3 / 4** | 0.80 (0.59–0.84) | 34 | 34 | 0 |
| sonnet | treatment-natural | 0 / 4 | 0.36 (0.16–0.46) | 14 | 18 | 2 |
| sonnet | treatment-primed | 0 / 4 | 0.19 (0.13–0.29) | 7 | 2 | 2 |
| **opus** | baseline (grep) | 0 / 4 | 1.59 (1.15–2.30) | 46 | 27 | 0 |
| opus | treatment-natural | 0 / 4 | 0.77 (0.67–0.82) | 15 | 9 | 3 |
| opus | treatment-primed | 0 / 4 | 0.77 (0.47–0.88) | 16 | 4 | 6 |

Median paired deltas (treatment-primed vs baseline): **sonnet cost 0.24×, turns 0.21×, grep 0.07×;
opus cost 0.48×, turns 0.35×, grep 0.16×.**

## What R=4 establishes

1. **The correctness gap is systematic, not luck.** The *weaker* baseline (sonnet) dropped a real site
   (`MyApplicationTest`'s `verify(jersey).register(eq(MyResource.class))`) in **3 of 4** runs. Both
   Lathe arms missed **0 of 8**. So on a weaker model, grep-only has a ~75% miss rate on this task and
   Lathe closes it.
2. **The strong model doesn't miss — there the win is cost.** opus baseline resolved all 4 (0 misses)
   but paid **~2× the cost and ~3× the turns** of the Lathe arms, with wide variance ($1.15–2.30).
3. **Lathe is cheaper on both models, every repeat** — no run inverted the direction.

## The two-regime conclusion (defensible)

> On cross-module / overload-sensitive refactoring, agent + Lathe beats grep-only on **both** models —
> but on **different axes**: with a strong model (opus) the win is **efficiency** (≈2× cheaper, same
> correctness); with a weaker model (sonnet) it is **efficiency *and* correctness** (grep misses a real
> site ~75% of the time; Lathe never does).

This is the honest shape of the value: Lathe buys *certainty cheaply*. A thorough frontier model can
grep its way to the same answer but pays for it; a weaker model can't reliably get there at all.

## Caveats (reproduced in any write-up)

- **One task, one reactor, synthetic-on-HEAD.** dw-02 is a single moat-shaped instance on dropwizard.
  Real historical commits (provenance A) and more tasks are the next credibility step.
- **R=4** is enough to show a 3/4-vs-0/4 correctness split and a consistent ~2× cost gap, but not for a
  tight CI on the effect size. Treat the deltas as robust in *direction and rough magnitude*.
- **Curated to the moat.** This task was admitted precisely because grep struggles; the easy/
  compiler-enforced slice (see `dw-01`) ties or shows no Lathe adoption. The claim is scoped to the
  cross-module/overload task-shape, not "all tasks."
- `missed_sites` is file-level in `score.py`; dw-02 was additionally verified line-level by hand.
- Scoring (mvn, Lathe-free) is identical for all arms, so the comparison is not circular.
