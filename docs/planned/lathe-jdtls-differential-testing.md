# Lathe — Differential Testing Against jdtls

Post-M2 quality-tooling design.
Builds on `lathe-design.md` and the existing probing workflow in `dev/`.
Adopted only after the M2 feature set is stable, since it measures behavior parity rather than adding a feature.

---

## Purpose

Lathe and Eclipse JDT Language Server (`jdtls`) both speak standard stdio JSON-RPC LSP.
That makes jdtls a usable oracle: send identical requests at identical positions to both servers over the
same project, then compare the answers.
The goal is not byte-for-byte parity — it is to surface *behavioral* divergences (missing results, wrong
targets, weaker completions) as triage candidates for the existing EG-/CQ- gap logs.

This complements, and does not replace, the manual `explore.py` workflow.
`explore.py` answers "what does Lathe do here?"; this harness answers "where does Lathe disagree with a
mature reference?".

---

## Principle

The harness is a `dev/` Python tool, not part of the Maven build or release gate.
It reuses the JSON-RPC client already present in `dev/lsp.py` and drives both servers from one client
abstraction.
Divergences are reported semantically — never as raw JSON diffs — so formatting and ordering noise does not
drown the signal.

---

## Components

### 1. Generalized LSP client

Extract the launcher command and initialize handshake from `dev/lsp.py`'s `LatheClient` into a shared
`LspClient`, leaving `LatheClient` as a thin preset.
Add a `JdtlsClient` preset that accounts for two jdtls specifics:

- A per-run `-data` workspace directory.
- A longer initial project-import wait before requests return meaningful results.

The progress-token handling currently hard-coded to a Lathe-specific token string becomes a client field.

### 2. Snippet authoring with inline probe markers

Probe points live as comment markers inside the Java source, so each snippet is self-describing:

```java
var first = list.stream/*?hover,definition,completion*/().findFirst();
```

The harness extracts each marker's `line:col` and the requested method list.
A marker with no method list runs the default core set.

### 3. Per-method semantic comparator

Each method has a normalizer that reduces a response to a comparable canonical form:

| Method | Canonical form |
|---|---|
| `definition`, `implementation` | set of `{targetBasename, symbolName}` |
| `references` | result count + location set |
| `completion` | label set (optionally top-N) |
| `hover` | resolved symbol / first signature line |
| `documentSymbol` | `(name, kind)` tree |
| `signatureHelp` | active signature label + active parameter |
| `diagnostics` | set of `(severity, range, code)` |

Each probe is classified as **agree**, **lathe-only**, **jdtls-only**, or **differ**.

### 4. Report

A markdown summary (agreement table plus a raw dump per divergence).
Divergences are written in a form that drops directly into the EG-/CQ- gap logs.

---

## Fixture

A small committed Maven module under `dev/` holds the snippet corpus.
A dedicated fixture keeps jdtls import fast and the corpus reproducible and version-controlled.
The harness can optionally point at an existing real project (`sample-workspace`, Dropwizard, Helidon) for
ad-hoc discovery runs, accepting slower jdtls import.

---

## Scope boundaries

- Core navigation first: `hover`, `definition`, `references`, `completion`, `documentSymbol`.
  Broader coverage (`implementation`, `signatureHelp`, hierarchies, semantic tokens, formatting) follows once
  the comparator approach is validated.
- Expected divergences (Lathe deliberately suppresses `Object` members, prefers reactor-origin symbols, etc.)
  are encoded as known-difference allowances so they do not re-surface every run.
- The harness is advisory tooling; it does not gate CI.

---

## Open questions

- Whether to snapshot known-good agreement state to detect regressions over time, or run purely as a
  discovery tool.
- How aggressively to normalize completion ordering, given Lathe's intentional reactor-origin ranking.
