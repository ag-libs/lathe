# Lathe — Integration Surface for AI Coding Agents

## Status

Planned. No code yet.

This document proposes a second and third front-end for the existing language server — an **MCP
server** and a **Claude Code LSP plugin** — so that AI coding agents (Claude Code, OpenAI Codex, and
the broader Cursor/Windsurf family) can consume Lathe alongside the current Neovim and Emacs clients.
It captures the agent-landscape findings that motivate the split, the value proposition, the tool
surface, and a phased plan.

## Motivation

Lathe today targets humans in an editor.
Its Neovim and Emacs clients speak LSP over stdio to `LatheServer`.
AI coding agents are a distinct, fast-growing consumer with a different access model, and Lathe already
holds exactly the ground truth those agents most often fake.

An agent's core loop is **edit → does it compile → run the covering test → read the failure → fix**.
Agents fake almost every step of that loop today:
they guess Maven invocations, re-derive classpaths, over-run whole suites, and read diagnostics from
build logs after the fact.
Lathe has already captured the ground truth for all of it — the exact `javac` inputs, the exact
Surefire launch — and can replay a run without recompiling.
That is the wedge:
**Lathe is the build-truth and "run the exact test that covers this change" layer for coding agents**,
not merely another Java LSP.

## Landscape (as researched, Sept 2026)

The integration channels differ sharply by agent, and the difference drives the whole design.

| Capability | Claude Code | OpenAI Codex CLI | Cursor / others |
|---|---|---|---|
| Native LSP client | Yes — via a plugin's `.lsp.json` / `lspServers` | No (open requests, unimplemented) | No native LSP |
| LSP operations consumed | 5 read-only: definition, references, hover, documentSymbol, diagnostics | — | — |
| `workspace/executeCommand` over LSP | Not consumed | — | — |
| MCP servers | Yes | Yes (`~/.codex/config.toml`, `[mcp_servers.*]`) | Yes |
| Plugins | Yes | Yes (`.codex-plugin/plugin.json`) | Varies |

Two findings dominate:

1. **MCP is the only universal substrate.**
   It reaches every agent.
   The Claude Code native-LSP path reaches only Claude Code, and only the read half of Lathe.

2. **The native-LSP path cannot express Lathe's differentiators.**
   Claude Code's native LSP client consumes only the five read operations and does **not** invoke
   `workspace/executeCommand`.
   Lathe's run/test capture-and-replay, `lathe.createType`, `lathe.missingImports`, and the rest of the
   custom-command surface are therefore invisible over native LSP.
   They only travel over MCP, where they become first-class agent tools.

Confidence note: the existence of the Claude Code LSP-plugin channel and its five-operation limit is
well supported; the exact `.lsp.json` field schema quoted below is moderate-confidence (community
documentation and CircleCI's build-an-LSP-plugin walkthrough, not a verbatim page from
code.claude.com) and must be re-verified against the current plugins reference before Phase 3 lands.

## The tension, and the resolution

The channel that is easiest to ship (a Claude Code LSP plugin) exposes the *least* differentiated
slice of Lathe — read-only navigation, where Eclipse JDT.LS already ships as the default
`jdtls-lsp@claude-plugins-official`.
The differentiated slice — run, verify, and build-derived truth — travels **only over MCP**, which is
also the only channel that reaches Codex and everything that is not Claude Code.

Resolution: **MCP is the primary, universal, differentiated surface; the LSP plugin is a thin
Claude-Code-only convenience** so that read features flow through the fast native path there.
Build MCP first.

## Non-negotiable prerequisite

Both surfaces sit on the same foundation as the editor clients:
the server refuses to run without a populated `.lathe/` at the reactor root.
Any agent onboarding **must** begin with a real Maven build having run once, e.g.:

```bash
mvn clean test -Dlathe.capture.only=true
```

This is a genuine setup cost that the default `jdtls`-via-plugin path does not carry, and it must be
surfaced explicitly in onboarding rather than hidden.
The MCP server should detect a missing/empty `.lathe/` and return that exact remediation as a
structured, actionable error rather than a bare failure.

## Architecture

One engine, three front-ends.
The MCP server and the LSP plugin are **new clients** beside the existing Lua and Elisp clients — they
do not fork or duplicate server logic.

- **Existing (unchanged):** `LatheServer` speaks LSP/JSON-RPC over stdio, launched by
  `~/.cache/lathe/current/lathe-launcher.sh`.
- **New — MCP server:** an MCP process that owns a stdio LSP session to the launcher, translates MCP
  tool calls into LSP requests plus Lathe `executeCommand` verbs, and manages document sync
  (`didOpen` / `didChange`) so an agent's rapid external edits are reflected without a Maven round-trip.
- **New — Claude Code LSP plugin:** a declarative `.lsp.json` / `lspServers` entry pointing Claude
  Code's native LSP client at the launcher for the five read operations; no code, config only.

The MCP server is the component that carries almost all the value and almost all the work.
Whether it is authored in-repo as a fourth module or as a thin sidecar is an open decision recorded
under "Open questions".

## MCP tool surface

Prioritize the tools an agent actually loops on; deprioritize interactive-typing features an agent does
not use (completion, signature help, folding).
Names are indicative and map to existing server endpoints/commands.

**Tier 1 — the edit→verify loop (highest leverage, MCP-only value):**

- `run_test` — run a test by class or method from captured bytecode, no recompile
  (maps to `lathe.run.test`).
- `run_main` — run a main from captured bytecode (maps to `lathe.run.main`).
- `list_runnables` — discover runnable mains and tests (maps to `lathe.runnables.list`).
- `get_diagnostics` — compiler-accurate errors/warnings for a file, on the captured classpath.

**Tier 2 — navigation and search (compiler-accurate ground truth):**

- `find_references`, `goto_definition`, `workspace_symbol`.

**Tier 3 — mutation helpers (agent-friendly, one-shot):**

- `add_missing_imports` (maps to `lathe.missingImports`),
  `create_type` (maps to `lathe.createType`).

Deliberately **out of the MCP surface** initially: completion, signature help, folding ranges, semantic
tokens, document highlight — these serve a human typing in an editor, not an agent.

## Value proposition to lead with

- **Compiler-accurate ground truth** — diagnostics and types are exactly what `javac` saw on the exact
  captured classpath; no reconstructed project model to drift ("green in build, red in tool").
- **Run the exact covering test without recompiling** — the capture-and-replay moat, expressible only
  over MCP.
- **External-edit freshness** — Lathe already detects out-of-editor edits (the design explicitly names
  AI-agent edits) and reconciles; the MCP server leans on this so an agent's rapid edits stay correct.
- **Reactor-correct multi-module** resolution out of the box.

## Phases

Ordered so the highest-leverage, most-differentiated, most-portable capability lands first, and each
phase is independently useful.

### Phase 0 — Spike and validate the substrate (no committed code)

- Verify a stdio LSP session against `lathe-launcher.sh` from a standalone process against the
  `multi-module` invoker workspace (or `dropwizard`/`helidon`): `initialize`, `didOpen`, one
  `textDocument/definition`, one `get_diagnostics`, and one `lathe.run.test` `executeCommand`.
- Re-verify the current Claude Code `.lsp.json` schema against the live plugins reference.
- Exit criterion: a hand-driven transcript proving the handshake, one read op, and one run verb work
  end-to-end.

### Phase 1 — MCP server, Tier 1 (the edit→verify loop)

- New MCP client owning the LSP session and document sync.
- Ship `run_test`, `run_main`, `list_runnables`, `get_diagnostics`.
- Missing/empty `.lathe/` returns the structured "run capture" remediation.
- Registerable in both Claude Code and Codex (`~/.codex/config.toml`).
- Exit criterion: from a fresh agent session, edit a file, get diagnostics, and run its covering test —
  all through MCP tools, on Codex and Claude Code.

### Phase 2 — MCP server, Tiers 2–3 (navigation, search, mutation)

- Add `find_references`, `goto_definition`, `workspace_symbol`.
- Add `add_missing_imports`, `create_type`.
- Harden document-sync/freshness for high-frequency agent edits; validate request shapes and return
  friendly errors (avoid leaking internal stack traces — see the argument-handling robustness note in
  [New/Changed-Test Replay Inner Loop](lathe-new-test-replay-loop.md)).

### Phase 3 — Claude Code LSP plugin (thin, config-only)

- Publish the `.lsp.json` / `lspServers` entry pointing at the launcher for the five read operations.
- Document the collision with `jdtls-lsp@claude-plugins-official` (first-registered-wins on `.java`)
  and how to disable/displace it.
- Position as a convenience add-on; the MCP server remains the source of Lathe's differentiated value.

### Phase 4 — Packaging, docs, distribution

- Onboarding docs under `docs/guide/` (an agent cheatsheet beside the per-editor cheatsheets),
  covering the `.lathe/` prerequisite, MCP registration for Claude Code and Codex, and the LSP-plugin
  option.
- Marketplace/plugin packaging as the agent ecosystems' conventions settle.

## Non-goals

- **No native `workspace/executeCommand` over the Claude Code LSP plugin** — the client does not consume
  it; run/verify verbs go through MCP by design.
- **No completion/signature-help/folding in the MCP surface** initially — these serve human typing.
- **No removal or divergence of the Neovim/Emacs clients** — they remain the human editor path and the
  reference LSP consumers.
- **No attempt to make `.lathe/` optional** for agents — the build-derived model is the moat, not a
  limitation to engineer around here (see [Workspace Readiness](../done/lathe-workspace-readiness.md)).

## Open questions

- **Module vs sidecar:** does the MCP server live as an in-repo module (build-order after
  `lathe-server`) or a thin external sidecar? Language choice follows from that.
- **Session lifetime and multiplexing:** one MCP process per workspace, or one multiplexing several?
- **Diagnostics push vs pull:** agents call tools synchronously; map `publishDiagnostics` to a pull
  `get_diagnostics` (proposed) and decide whether any push channel is worth exposing.
- **Auth/trust:** Codex project-scoped `.codex/config.toml` requires a trust marker; document the
  implications.

## Related work

- [New/Changed-Test Replay Inner Loop](lathe-new-test-replay-loop.md) — the replay-staleness work
  that directly affects `run_test` correctness for agent-authored tests.
- [External-Change Detection](../done/lathe-external-change-detection.md) and
  [Staleness Compile Stamps](../done/lathe-staleness-compile-stamps.md) — the freshness machinery the
  MCP document-sync layer relies on.
- [Workspace Readiness](../done/lathe-workspace-readiness.md) — the `.lathe/` prerequisite and its
  onboarding guidance.
