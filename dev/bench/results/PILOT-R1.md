# Pilot results — R=1 (2026-10-03, model: opus)

> **Superseded by [`PILOT-R4.md`](PILOT-R4.md)** — the R=4 run confirmed the sonnet correctness gap is
> systematic (baseline missed 3/4, not the single miss seen here). Kept as the first-look record.

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

## dw-02 — same task, SONNET agent (weaker-baseline experiment)

Re-ran dw-02's three arms with `--model sonnet` to test the refinement above: does a *less thorough*
baseline start to **miss** sites (so correctness, not just cost, separates the arms)?

| arm | resolved | missed sites | cost $ | turns | lathe calls | grep/read/bash |
|---|---|---|---|---|---|---|
| baseline (grep) | ✅ | **1 / 15** | 0.84 | **42** | 0 | **38** |
| treatment-natural | ✅ | 0 / 15 | 0.35 | 9 | 1 | 25 |
| treatment-primed | ✅ | 0 / 15 | 0.24 | 8 | 2 | 4 |

Paired vs baseline (primed): **cost 0.29×, turns 0.19×, grep/read 0.11×.**

### What it says — the correctness gap appears

- **The weaker baseline MISSED a real site** (`MyApplicationTest`'s `verify(jersey).register(eq(
  MyResource.class))` Mockito line) — despite spending *more* effort than the opus baseline (42 turns,
  38 searches). It greps more but less accurately. Both Lathe arms found all 15.
- So the axis behaves as pre-registered: with a thorough frontier model the value is **efficiency**
  (opus: correctness tie); with a weaker model the value is **efficiency *and* correctness** (sonnet:
  baseline misses, Lathe doesn't).
- Note `resolved` stayed ✅ for the sonnet baseline even though it missed a site — the jersey-scoped
  oracle doesn't build `docs/examples/core`, and a wrong-overload miss compiles silently. This is
  exactly why **missed-sites (gold-diff), not resolved, is the headline metric** for this axis.
- Caveat: N=1 — this single miss could be run variance. R≥4 is needed to establish a baseline
  *miss-rate*. But the direction matches the hypothesis and is the first correctness separation seen.

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
- Two models (opus, sonnet), single reactor (dropwizard), synthetic-on-HEAD tasks, one task per axis.
  Real historical commits (provenance A) and R≥4 repeats are the next credibility steps.

## Bottom line (R=1, directional)

On the overload-sensitive rename (dw-02), agent+Lathe beat grep-only on **both** models:
- **opus:** correctness tie (15/15 both), but Lathe ~2.6× cheaper / ~3× fewer turns.
- **sonnet:** Lathe ~3–4× cheaper **and** the grep baseline missed a real site (1/15) that Lathe caught.

Directionally this answers the main question — Lathe improves agent quality/cost on the moat axis —
with the honest nuance that *what* it improves (cost vs. correctness) depends on how thorough the base
model is. Significance still needs R≥4.
