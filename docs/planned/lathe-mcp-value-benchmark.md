# Lathe — Measuring MCP Value with a SWE-bench Ablation

## Status

Proposed. No code yet.
This document is the recommendation that came out of the MCP-value discussion: how to *prove*
(or disprove) that the `lathe-mcp-server` makes a coding agent measurably better on a real
multi-module Maven reactor, rather than asserting it.

It supersedes the ad-hoc `dev/bench/` sketch in
[AI-Agent Integration → Measurement](lathe-ai-agent-integration.md#measurement): the harness there
was to be built from scratch; here we **adopt the SWE-bench plumbing and make Lathe the single
ablation variable** instead.

Decisions already taken in discussion, recorded here so they are not re-litigated:

- **Baseline is grep-only.** A jdtls / Serena "semantic bar" was considered and **dropped** — we are
  not wrestling with another IDE or a second MCP for v1. The honest baseline for the lead audience
  (Codex / Gemini have no LSP; Claude Code ships grep) is the agent with shell/grep/read/`mvn` and
  nothing else.
- **`run_test` is being built in a separate track** (Tier 3, gated on recompile-before-replay; see
  [New/Changed-Test Replay Inner Loop](lathe-new-test-replay-loop.md)). This harness is designed so
  the run-loop task axis comes online when `run_test` lands, but does not block on it.

## Goal — a falsifiable, per-axis claim

"MCP provides value" is not one number. The first field A/B already showed it can be **negative**
(≈3× cost for a correctness tie on a distinctive name). So the thesis is stated per axis, and each
can independently pass or fail:

> On moat-shaped reactor tasks, the agent **+ Lathe MCP** beats the **grep-only** agent on
> `{resolved-rate, tokens, wall-time, missed-sites}` — measured as paired deltas — **without
> regressing** any of them.

The explicit failure mode to detect is the *distrust tax*: the agent calls a Lathe tool **and still
greps to re-verify**, so correctness ties but cost rises. That is a fail, and it is usually a
tool-description problem, not a capability one.

## Why this shape — the reasoning trail

- **The methodology already exists: SWE-bench.** Checkout the parent commit → prompt with the issue →
  score by running the repo's own tests (`FAIL_TO_PASS` / `PASS_TO_PASS`, dockerized) is the standard
  agentic-SE protocol. We adopt it rather than reinvent it.
- **Java plumbing already exists: Multi-SWE-bench / SWE-bench-java.** Ready-made git-replay + Docker
  test-oracle instances for Java, Maven-based.
- **No one has published a *tool ablation* on Java.** Every published Java number is "model + scaffold";
  none isolate a code-intelligence MCP. Our paired delta is therefore a new result regardless of sign.
- **Published failure analysis is prescriptive** (see [Pre-registered hypothesis](#pre-registered-hypothesis)):
  fault localization dominates Java failures, and it is dominated by *missed cross-module
  dependencies* — exactly Lathe's moat.

## What we adopt vs. what we build

| Component | Source |
|---|---|
| Instance schema (`base_commit`, `problem_statement`, gold `patch`, `FAIL_TO_PASS`, `PASS_TO_PASS`) | **adopt** — SWE-bench |
| Docker evaluation harness + resolved-rate metric | **adopt** — SWE-bench (unchanged, arm-agnostic) |
| Multi-SWE-bench Java base images (Maven build) | **adopt** — for the reused instances |
| **Treatment image layer** (`.lathe/` capture at `base_commit` + launcher + `.mcp.json`) | **build** — main integration effort |
| **Scaffold wrapper** (headless agent → patch + transcript, arm-toggled by `.mcp.json`) | **build** — thin |
| **Moat corpus** (Dropwizard / Helidon cross-module instances in SWE-bench schema) | **build** — the high-value part |
| **Transcript metrics layer** (tokens, tool-calls, missed-sites vs gold) | **build** — thin |

The intellectual value is the **corpus** and the **ablation design**, not the plumbing.

## Architecture

SWE-bench cleanly separates **inference** (produce a patch) from **evaluation** (score it).
**Lathe touches only inference.** Both arms are judged by the identical, Lathe-free test oracle.

```
Instance set  (reused Multi-SWE-bench Maven Java  +  our Dropwizard/Helidon instances, same schema)
      │
      │  ── build a TREATMENT image per instance ──
      │     base SWE-bench image (repo @ base_commit + deps)
      │       + capture once:  mvn … -Dlathe.capture.only=true   → .lathe/
      │       + install lathe-mcp-launcher.sh + server jars
      │       + drop .mcp.json  → registers the `lathe` MCP server
      ▼
INFERENCE   (per instance × arm × repeat) — the ONLY place Lathe lives
   spin container at base_commit
   run the agent headless with problem_statement as the prompt
     ├─ baseline :  Read / Grep / Bash(mvn) / Edit          (no .mcp.json)
     └─ treatment:  same tools  +  lathe MCP server          (.mcp.json → launcher)
   capture:  git diff → prediction.patch          (SWE-bench output)
             transcript → tokens, tool_use, wall-time   (extra metrics)
      ▼
EVALUATION  (stock SWE-bench Docker harness — identical for both arms)
   apply prediction.patch → apply test_patch → run FAIL_TO_PASS + PASS_TO_PASS
      → resolved? (binary, per instance)
      ▼
ANALYSIS  (paired, treatment − baseline)
   Δ resolved-rate                                        ← SWE-bench metric
   + Δtokens, Δ(grep+read) vs #lathe-tool calls, Δmissed-sites, Δwall-time   ← transcript
   grouped by moat axis and by single- vs multi-module, median-of-R, with CI
```

### The one hard piece — the treatment image

Lathe's non-negotiable prerequisite is a populated `.lathe/` at the reactor root; the MCP server
refuses to run without it. So the treatment image bakes in, per instance:

1. **Capture at `base_commit`** — one capture build (`mvn … -Dlathe.capture.only=true`, or
   `lathe:init` + `lathe:sync` via the extension) to produce `.lathe/`.
2. **Launcher + jars** — `lathe-mcp-launcher.sh` and the server on the classpath.
3. **`.mcp.json`** — so the agent session sees `lathe` as an MCP server.

This is pre-baked into the image (like Maven deps in the base image), so it is a one-time build cost,
**not** charged to the agent's per-run token/turn metrics — but it is footnoted as Lathe's real
onboarding cost.

### The arms

Both arms run the **same scaffold**, differing *only* by whether `.mcp.json` includes `lathe`.
Recommended scaffold: **Claude Code headless** (`claude -p … --output-format json`) — it is Lathe's
target audience, speaks MCP natively via `.mcp.json`, and emits the usage/transcript JSON we need.
Its `git diff` is the SWE-bench prediction.
(SWE-agent / OpenHands are alternatives if a more "standard" scaffold is wanted later; they are more
work to wire MCP into and are not Lathe's audience.)

## The corpus — two tiers

### Tier A — reused Multi-SWE-bench Java (plumbing smoke-test + calibration)

Filtered to what Lathe can run:

| Repo | Build | Structure | Role |
|---|---|---|---|
| apache/**dubbo** | Maven | large multi-module reactor | ✅ the real fit (but few instances) |
| alibaba/**fastjson2** | Maven | multi-module | ✅ modest |
| google/**gson** | Maven | small multi-module | ✅ modest |
| fasterxml/**jackson-databind** | Maven | ~single module | ⚠️ dominant (~54%) but no cross-module moat |
| fasterxml/**jackson-core**, **jackson-dataformat-xml** | Maven | single module | ⚠️ same |
| mockito, elastic/logstash, googlecontainertools/jib | **Gradle** | — | ❌ Lathe cannot capture |

Two hard filters apply: **Lathe is Maven-only** (drops the Gradle repos), and **Lathe's moat is
cross-module** (single-module jackson instances will tie). Tier A therefore proves the harness works
and **calibrates the baseline** (see [gate](#calibration-gate)); it does **not** demonstrate value on
its own. Results are reported **per repo and split single- vs multi-module** so the jackson bulk
cannot mask a multi-module signal.

### Tier B — our Dropwizard / Helidon instances (the value signal)

SWE-bench does not organize tasks by *kind*; every instance is "resolve this real issue." The
moat-axis taxonomy is a lens **we** impose when curating. So we author our own instances in the
SWE-bench schema by mining real merged commits that match the axes:

| Axis | Shape | Example source | Oracle strength |
|---|---|---|---|
| Cross-module signature change | add a param to an interface method called across modules | Dropwizard `ServerFactory`/`ConnectorFactory`; Helidon SPIs | strong — a missed site breaks `PASS_TO_PASS` |
| Overload-sensitive rename | rename one of several same-named methods | Dropwizard `DataSourceFactory` accessors | strong |
| Implement-an-interface across modules | add an SPI method, implement in every provider | Dropwizard `ConfiguredBundle`/`Managed`; Helidon `ConfigSource` | strong |
| Safe symbol removal | delete a member, remove every use cross-module | either | strong |
| Generated-source use (Helidon) | add a field to a `@Prototype.Blueprint`/`@Builder`, use the generated setter | Helidon builder codegen | unique vs a generic LSP |
| Who-ultimately-calls | change a method with deep transitive callers | Helidon webserver/security | medium |

- **Dropwizard** (68 modules) is the shareable, controllable corpus.
- **Helidon** (332 modules + annotation-processor codegen) provides the generated-source axis and
  scale, at higher build cost (fewer instances).
- **Discriminator trap:** no read-only enumeration of rare, distinctive names — grep is near-perfect
  there, so it is a guaranteed tie. Every task must be a *change* whose correctness the build judges,
  on a name where text search is wrong or drowning.

## Metrics and oracle

Per `(instance, arm, repeat)`:

| Metric | Source | Win direction |
|---|---|---|
| Resolved (compiles + `FAIL_TO_PASS` green + `PASS_TO_PASS` stays green) | SWE-bench oracle | ↑ (headline) |
| Tokens | agent usage output | ↓ |
| # grep + read + bash calls vs # Lathe-tool calls | transcript | ↓ (grep) |
| # wrong / superseded edits | diff vs gold | ↓ |
| Missed real sites | diff vs gold | ↓ (completeness) |
| Wall-clock | run timing | ↓ |

Reported as **paired deltas** (treatment − baseline), median-of-R per cell, aggregated by axis and
by module-cardinality, with a confidence interval. Scoring is hermetic and identical for both arms
(real `mvn`, never Lathe — avoids circularity).

### Calibration gate

Before trusting any Lathe delta, the **grep-only baseline** must reproduce the published
Multi-SWE-bench Java ballpark (top agents ≈ **25–33%** resolved). If the baseline lands there, the
harness is sound and the paired delta is trustworthy. This is the single most valuable use of the
published leaderboard.

## Pre-registered hypothesis

Published SWE-bench failure taxonomies converge: **fault localization ≈ 40%** of failures (the
single largest class), and it is dominated by *missed secondary/cross-module dependencies*
("≈52% of localization failures need coordinated multi-file edits"). Editing/reasoning errors
(≈27%) and recovery loops (≈20%) follow.

The falsifiable prediction we register **before** running:

> Lathe's largest resolved-rate lift appears on **multi-module** instances, driven by
> `find_references` / `call_hierarchy` / `get_definition` / `search_symbols`; **single-module**
> instances (the jackson bulk) tie.

This evidence also **re-orders tool priority** relative to the original tiering:

1. `find_references`, `get_definition`, `search_symbols` — attack the 40% localization class.
2. `call_hierarchy` — the "trace through call chains" failure is named explicitly; **promoted from
   Tier 4**.
3. `get_diagnostics` + `run_test` — break the ≈20% recovery-loop class and catch regressions before
   `PASS_TO_PASS` fails.
4. `rename_symbol` — the "coordinated multi-file edits" case.
5. `describe_symbol` — lowest; helps only the contract-understanding slice of editing errors.

`search_symbols` and `call_hierarchy` are therefore higher-value next additions than
`describe_symbol`.

## Honest limits

- **There is a ceiling.** Editing/reasoning failures and cognitive deadlock are not closed by any
  navigation tool. A perfect Lathe surface targets roughly the localization + regression-catchable
  slice — the top ≈50–55% of failures. We do not promise more.
- **Contamination largely cancels.** Well-known repos may be in training data, inflating *absolute*
  resolved-rates — but both arms face the identical instance, so contamination washes out of the
  *paired delta*. This is a real advantage of running an ablation rather than chasing a leaderboard.
- **Freshness is honest by construction.** During a run the agent edits files; `get_diagnostics`
  compiles the edited file fresh against the base capture (single-file javac). Cross-module
  correctness still needs the agent's own `mvn` — which **both** arms have. The A/B faithfully
  reflects Lathe's real freshness model.

## Phases

1. **M1 — plumbing smoke-test.** Reused **dubbo** instances end-to-end: treatment image (capture +
   launcher + `.mcp.json`), headless scaffold wrapper, stock oracle. Proves the pipeline on a real
   Maven reactor. Hand-score.
2. **M2 — oracle + metrics automation.** Wire the transcript metrics layer and the calibration gate;
   confirm the baseline reproduces ≈25–33% on Tier A.
3. **M3 — moat corpus.** Author the Dropwizard (then Helidon) cross-module instances; this is where
   the value signal is expected.
4. **M4 — analysis + report.** Paired deltas by axis and module-cardinality, with CIs, against the
   pre-registered hypothesis. Fold the run-loop axis in once `run_test` lands.

## Open decisions

- **Task manifest format** — YAML (readable multiline prompts) vs dependency-free JSON.
- **Headless scaffold flags** — confirm the exact Claude Code non-interactive flags for MCP config +
  JSON transcript (the one load-bearing external dependency; affects only the scaffold wrapper).
- **Repeats `R`** — cost vs variance; start `R = 2` for the pilot, raise for the headline run.
- **Corpus order** — dubbo smoke-test first (M1), or start authoring Dropwizard instances in parallel.

## Related work

- [AI-Agent Integration](lathe-ai-agent-integration.md) — the MCP surface, tiers, and the earlier
  measurement sketch this supersedes.
- [New/Changed-Test Replay Inner Loop](lathe-new-test-replay-loop.md) — the freshness work that gates
  the `run_test` task axis.
- SWE-bench (Jimenez et al.), SWE-bench-java (arXiv:2408.14354), Multi-SWE-bench (arXiv:2504.02605) —
  the adopted methodology and Java instances.
