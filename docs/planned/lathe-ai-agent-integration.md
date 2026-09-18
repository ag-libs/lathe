# Lathe — Integration Surface for AI Coding Agents

## Status

Planned. Phase 0 (spike) done — substrate validated. Architecture approved; no product code yet.

This document proposes a second and third front-end for the existing language server — an **MCP
server** and a **Claude Code LSP plugin** — so that AI coding agents (Claude Code, OpenAI Codex, and
the broader Cursor/Windsurf family) can consume Lathe alongside the current Neovim and Emacs clients.
It captures the agent-landscape findings that motivate the split, the value proposition, the tool
surface, and a phased plan.

**Approved decision (the MCP server):** a new in-repo Maven module **`lathe-mcp-server`**, built on the
**official MCP Java SDK** (`io.modelcontextprotocol.sdk`), talking to Lathe's engine **in-process** via
a thin facade exported from `lathe-server` — one process per agent session, no second JVM and no
LSP-over-stdio hop. It ships with its **own launcher** (`lathe-mcp-launcher.sh`). Rationale and
alternatives considered are in [Architecture](#architecture).

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
| LSP operations consumed | definition, references, completion, diagnostics, hover, rename + other standard features | — | — |
| `workspace/executeCommand` over LSP | No agent-invocable trigger | — | — |
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

Schema note (verified in Phase 0 against the live plugins reference at
`code.claude.com/docs/en/plugins-reference`): an LSP server is registered either in a `.lsp.json` at
the plugin root or under an `lspServers` key in `plugin.json`. Required fields are `command` (must be
on `$PATH`) and `extensionToLanguage`; optional fields include `args`, `transport`
(`stdio` default — note Claude Code runs *every* server over stdio even if `socket` is declared),
`env`, `initializationOptions`, `settings`, `workspaceFolder`, `startupTimeout`, `shutdownTimeout`,
`restartOnCrash`, `maxRestarts`, and `diagnostics` (push diagnostics into context after edits, default
true). The reference lists supported operations as go-to-definition, find-references, completion,
diagnostics, hover, rename, and "other standard LSP features provided by the language server" — i.e.
more than five, but still **no agent-invocable path for `workspace/executeCommand`**: custom commands
need a client-side trigger (code action / command) that an agent does not drive, so Lathe's run/test
verbs still require MCP. The `command` must resolve on `$PATH`, so the plugin points at
`lathe-launcher.sh` (or a wrapper) rather than a bare binary.

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
  `lathe-launcher.sh`. Its lean `module-info` and the editor path are untouched by this work.
- **New — `lathe-mcp-server` module:** an MCP server built on the official MCP Java SDK, calling
  Lathe's engine **in-process** through a facade (below). Its own launcher, `lathe-mcp-launcher.sh`.
- **New — Claude Code LSP plugin:** a declarative `.lsp.json` / `lspServers` entry pointing Claude
  Code's native LSP client at `lathe-launcher.sh` for the read/nav operations it surfaces; no code,
  config only.

The MCP server carries almost all the value and almost all the work; the rest of this section is its
approved shape.

### `lathe-mcp-server` — the module

- New Maven module parallel to `lathe-server`. Package root `io.github.aglibs.lathe.mcp`,
  `groupId io.github.ag-libs`, Java 21. Build order:
  `lathe-core → lathe-compiler → lathe-server → lathe-mcp-server → lathe-maven-plugin`.
- **Dependency isolation is the reason it is a separate module.** The SDK's closure — Reactor +
  reactive-streams, SLF4J, Jackson 2 annotations + Jackson 3 databind, networknt json-schema-validator
  — lands **only here**. `lathe-server` gains no new dependencies.
- Dependencies: `io.modelcontextprotocol.sdk:mcp` (the bundle = core + Jackson-3 binding, versions via
  `mcp-bom`); regular deps on `lathe-server` and `lathe-core`.

### In-process facade (the chosen integration)

The MCP module depends on `lathe-server` as a library and calls its services **directly in the same
JVM** — no child process, no JSON-RPC hop, no LSP `didOpen`/`didChange` mirroring. This preserves the
benefit that drove us off a sidecar: MCP tools invoke the *same* code the LSP handlers do
(`LatheTextDocumentService.runTestFuture` / `runnablesFuture` / diagnostics), so behaviour is identical
by construction.

To keep the coupling bounded, `lathe-server` gains **one small concrete facade** —
`LatheEngine` in a new package `io.github.aglibs.lathe.server.api`, **qualified-exported** to the MCP
module only (`exports io.github.aglibs.lathe.server.api to io.github.aglibs.lathe.mcp`). It wraps:
workspace open (synthesizes the existing LSP `initialize` against a root, reusing `WorkspaceSession`
and its missing-`.lathe/` remediation), plus `diagnostics(path)`, `runnables(uri)`, `runTest(...)`,
`runMain(...)`. Because MCP is stateless, the facade opens files from disk on demand via the existing
`didOpen` analysis path. Diagnostics and (later) progress are captured through a small
`LatheLanguageClient` stub the facade installs in place of the editor's remote proxy — the bridge point
for streaming in a later phase.

### MCP server wiring

`LatheMcpServer.main` builds an `McpSyncServer` (the SDK's blocking facade — hides Reactor) over the
SDK **stdio** transport, registers the tools with input schemas, and serves. One process per agent
session, matching today's one-server-per-client model.

### Own launcher

`ServerInstaller` (in `lathe-maven-plugin`) generates and installs `lathe-mcp-launcher.sh` to
`~/.cache/lathe/servers/<version>/`, mirroring `lathe-launcher.sh`'s javac `--add-exports/--add-opens`
set (the in-process engine runs javac) **plus** the SDK classpath and the MCP main class.

### Alternative considered and rejected

An **out-of-process** MCP server (spawns `lathe-launcher.sh` and speaks LSP to it) needs no
`lathe-server` change and a leaner launcher, but re-adds the translation hop, child-process lifecycle,
and `didOpen` mirroring — the very costs that motivated going native. Rejected. A **hand-rolled MCP
transport** (no SDK, on lsp4j's generic JSON-RPC) was also considered: zero new dependencies, but it
owns MCP spec-compliance and protocol-version churn by hand and cannot cheaply reach the richer surface
(progress/streaming, structured output, resources, HTTP) that maps onto Lathe's already-shipped
streaming/structured/cancel test features. Rejected in favour of the SDK.

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

### Phase 0 — Spike and validate the substrate — DONE

Driven through the existing Python LSP driver (`dev/lsp.py` / `dev/explore.py`), which already spawns
the **published** `lathe-launcher.sh` over stdio and issues `initialize`, `didOpen`,
`textDocument/definition`, diagnostics, `lathe.runnables.list`, and `lathe.run.test` — i.e. a working
reference for exactly what the MCP server must do. Validated end-to-end against the published 0.1.6
launcher on two workspaces:

- the in-repo `multi-module` invoker workspace (`HelloTest`): clean diagnostics, cross-file
  `definition` into main source, six runnables discovered, and `run 0` replayed a fresh JVM →
  `[PASSED] exit=0`;
- a large private multi-module workspace (10 captured modules), already synced: clean diagnostics,
  cross-file `definition` from a test into its main-source method/type, runnables discovered, and a
  single-method replay → `[PASSED] exit=0`.

The `.lsp.json` schema was re-verified against the live plugins reference (see the schema note above).

Exit criterion met: handshake + read ops + a run verb work end-to-end over the launcher on both a
public fixture and a real synced project, with no product code written.

### Phase 1 — `lathe-mcp-server` scaffold + Tier 1 (the edit→verify loop)

- New `lathe-mcp-server` module wired into the reactor; SDK dependency; `LatheMcpServer.main` with the
  SDK stdio transport.
- `LatheEngine` facade added to `lathe-server` (qualified export) + the `LatheLanguageClient` stub.
- `ServerInstaller` generates and installs `lathe-mcp-launcher.sh`.
- Ship `run_test`, `run_main`, `list_runnables`, `get_diagnostics` (structured content results).
- Missing/empty `.lathe/` returns the structured "run capture" remediation.
- Registerable in both Claude Code (`claude mcp add`) and Codex (`~/.codex/config.toml`).
- Recommend a short spike first to confirm the SDK's module graph on the JPMS module path (Reactor et
  al. as automatic modules) vs running the module on the classpath.
- Exit criterion: from a fresh agent session, edit a file, get diagnostics, and run its covering test —
  all through MCP tools, on Codex and Claude Code.

### Phase 2 — Tiers 2–3 (navigation, search, mutation)

- Add `find_references`, `goto_definition`, `workspace_symbol`.
- Add `add_missing_imports`, `create_type`.
- Validate request shapes and return friendly errors (avoid leaking internal stack traces — see the
  argument-handling robustness note in
  [New/Changed-Test Replay Inner Loop](lathe-new-test-replay-loop.md)).

### Phase 3 — Streaming and cancellation

- Stream `run_test` output/progress to the agent via MCP progress notifications, and wire cancellation
  onto the existing `lathe.run.cancel`, using the SDK's notification support through the
  `LatheLanguageClient` stub bridge. Leans on Lathe's already-shipped
  [test-output-streaming](../done/lathe-test-output-streaming.md),
  [structured-test-results](../done/lathe-structured-test-results.md), and
  [test-cancel](../done/lathe-test-cancel.md).

### Phase 4 — Resources and optional HTTP transport

- Expose `.lathe/` artifacts (workspace manifest, captured launch configs) as MCP resources.
- Evaluate the SDK's Streamable HTTP transport for shared/remote serving.

### Phase 5 — Claude Code LSP plugin (thin, config-only)

- Publish the `.lsp.json` / `lspServers` entry pointing `command` at `lathe-launcher.sh` for the
  standard read/nav/rename operations the client surfaces.
- Document the collision with `jdtls-lsp@claude-plugins-official` (first-registered-wins on `.java`)
  and how to disable/displace it.
- Position as a convenience add-on; the MCP server remains the source of Lathe's differentiated value.

### Phase 6 — Packaging, docs, distribution

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

## Resolved decisions

- **Module vs sidecar / SDK vs hand-rolled / in-process vs out-of-process** — resolved: a separate
  in-repo module `lathe-mcp-server`, on the official MCP Java SDK, calling the engine in-process via a
  facade (see [Architecture](#architecture)).
- **Session lifetime** — one MCP process per agent session (one workspace), matching the editor model.
- **Diagnostics push vs pull** — pull (`get_diagnostics`) for Phase 1; the `LatheLanguageClient` stub
  is the push bridge reserved for Phase 3 streaming.

## Open questions

- **JPMS placement of the module:** named module requiring the SDK's automatic modules, or run on the
  classpath? Decide in the Phase 1 spike against the real SDK module graph.
- **MCP protocol version** to advertise, and how to track SDK/spec revisions over time.
- **Auth/trust:** Codex project-scoped `.codex/config.toml` requires a trust marker; document the
  implications.

## Related work

- [New/Changed-Test Replay Inner Loop](lathe-new-test-replay-loop.md) — the replay-staleness work
  that directly affects `run_test` correctness for agent-authored tests.
- [External-Change Detection](../done/lathe-external-change-detection.md) and
  [Staleness Compile Stamps](../done/lathe-staleness-compile-stamps.md) — the freshness machinery the
  in-process facade's open-from-disk path relies on.
- [Workspace Readiness](../done/lathe-workspace-readiness.md) — the `.lathe/` prerequisite and its
  onboarding guidance.
