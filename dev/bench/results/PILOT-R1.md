# Pilot results — R=1 (2026-10-03, model: opus)

First end-to-end 3-arm runs. **Directional only — R=1, so variance is uncaught.** Per the
pre-registration, the pilot's job is to establish direction and mechanism, not an effect size.

## dw-02 — overload-sensitive rename (`JerseyEnvironment.register(Class)` → `registerResourceClass`)

| arm | resolved | missed sites | cost $ | turns | lathe calls | grep/read/bash |
|---|---|---|---|---|---|---|
| baseline (grep) | ✅ | 0 / 15 | **2.30** | **44** | 0 | **30** |
| treatment-natural | ✅ | 0 / 15 | 0.82 | 15 | 3 | 9 |
| treatment-primed | ✅ | 0 / 15 | 0.88 | 16 | 8 | 5 |

Paired vs baseline (primed): **cost 0.38×, turns 0.36×, grep/read 0.17×.**

### What it says

- **Correctness: a tie.** All three arms produced the identical, fully-correct 15/15-site rename —
  *including* the grep-invisible variable-typed site (`DropwizardClient`'s `register(classObj)`). The
  baseline agent found it by reading the `if (resource instanceof Class<?> classObj)` guard and
  reasoning it out. **Missed-sites did not discriminate.**
- **Cost: a large Lathe advantage.** The grep baseline paid ~2.6× the cost, ~3× the turns, and ~6× the
  grep/read calls to reach the same answer — it greps many ways and re-reads to be *sure*. Lathe gives
  that certainty in a few calls.
- **Adoption:** even un-primed, the agent used Lathe here (3 calls) — unlike dw-01, where it didn't.
  On a visibly overload-heavy task the agent reaches for the semantic tool on its own; priming roughly
  doubles Lathe usage and halves grep/read again.

### The honest refinement to the pre-registered hypothesis

We predicted Lathe's lift would show as **fewer missed sites** on multi-module tasks. With a capable
baseline model (opus) that is *thorough*, the baseline does not miss — it pays to be certain. So the
value surfaced as **efficiency (tokens / turns / cost), not completeness.** This matches the design
doc's prior polymorphic A/B. Two consequences:

1. Keep **tokens/turns** as the headline for capable-model runs; **missed-sites** may only separate the
   arms with a **weaker/cheaper baseline model** (where grep-thoroughness breaks down) — a worthwhile
   next experiment.
2. R=1 cannot distinguish a 2.6× cost delta from run-to-run variance. **Next: R≥4 per arm** for
   bootstrap CIs before any claim.

## dw-01 — add-method-across-modules (compiler-enforced) — baseline only, R=1

| arm | resolved | missed | cost $ | turns | grep/read |
|---|---|---|---|---|---|
| baseline | ✅ | 0 | 0.78 | 22 | 13 |

Baseline resolved it, finding even the grep-invisible anonymous `ViewRenderer` in `ViewBundleTest` via
the compiler. Confirms dw-01 is a weak discriminator (compiler enforces completeness). Treatment arms
not re-run after the permissions fix — low priority given the axis.

## Caveats

- **R=1** — directional, not significant. CIs require repeats.
- **`missed_sites` is file-level** in `score.py`; dw-02 was additionally verified line-level by hand
  (all arms: 15/15 renames, `classObj` site included).
- Single model (opus), single reactor (dropwizard), synthetic-on-HEAD tasks. Real historical commits
  (provenance A) and a second model are the next credibility steps.
