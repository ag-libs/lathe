# Lathe — Integration Surface for AI Coding Agents

## Status

**In progress — MCP server shipped and dogfooding on a real reactor.** Phase 0 (spike) done and
architecture approved; the `lathe-mcp-server` module now builds, launches, and serves tools over
stdio, validated live against the private payment reactor.

**Shipped (as of 2026-09-20):**

- `lathe-mcp-server` module on the official MCP Java SDK, with its own launcher
  (`lathe-mcp-launcher.sh`), workspace resolved from `cwd`, stderr logging (`LATHE_DEBUG`), and an
  in-process `LatheEngine` facade in `lathe-server` (subpackage `server.engine`; the LSP service is
  the shared analysis seam driving both front-ends).
- Tools (9): **`get_diagnostics`, `get_definition`, `find_references`, `rename_symbol`, `run_test`,
  `call_hierarchy`, `describe_symbol`, `search_symbols`, `find_implementations`** — every located
  result carries source snippets and an `origin` (REACTOR / GENERATED / EXTERNAL). The curated wedge
  is **feature-complete**.
- `rename_symbol` applies javac-accurate edits to disk across modules and refuses to touch a
  non-reactor file.
- `run_test` replays a single test **method / class / package** against the compiled classpath (no
  `mvn`, no reactor build), reusing the editor's runnable→selection mapping; returns pass/fail/skip
  counts + each failure's `type: message` and line. Verified live on the payment reactor (sub-3s per
  run). Module scope deferred (no module-run in the substrate yet).
- `call_hierarchy` traces callers/callees one level across the reactor (transitive depth deferred);
  `describe_symbol` returns the hover markdown (signature/type/javadoc) as a passthrough;
  `search_symbols` finds a type/symbol by name across reactor + dependencies + JDK. All verified live
  on the payment reactor (e.g. incoming `call_hierarchy` on an overloaded method → 97 cross-module
  callers with snippets).
- **Freshness advisory:** results append a `Stale:` note listing modules whose source is newer than
  their compiled classes (reusing the existing idle-reconcile scan, cached), so the agent knows to
  re-sync; `rename_symbol` force-refreshes so its own result is current. MCP has no server→model
  push, so this rides in the result text (verified with `claude-code-guide`).
- **Routing instructions** served at `initialize` (task→tool dispatch), mirrored into tool
  descriptions for clients that drop server instructions (claude.ai web).

**Next (optional follow-ups, not blockers):** `type_hierarchy`; `add_missing_imports`; transitive
`call_hierarchy` depth; `run_test` **module** scope (needs a module-run path in the substrate);
`search_symbols` kind filter. The core wedge is done — the next real work is **measurement**
([Measurement](#measurement)), not more tools.

**Dropped:** `verify_build` (wrapping `mvn` over the reactor). An agent can run `mvn` itself, so a
thin wrapper fails the "does Lathe do this better than agent+bash?" test. Lathe's edge is *individual*
compilation and tests, not reactor orchestration; cross-module build verification stays the agent's
own `mvn`, and Lathe surfaces staleness instead (see [Freshness model](#freshness-model)).

> **Note.** Some deeper sections below (Architecture, Freshness model, Measurement) still describe
> `verify_build` as a planned tool. That reflects the earlier design; read those mentions as "the
> agent's own `mvn`, with Lathe's `Stale:` advisory as the freshness signal." They'll be reworked when
> those sections are next revised.

This document proposes a second and third front-end for the existing language server — an **MCP
server** and a **Claude Code LSP plugin** — so that AI coding agents (Claude Code, OpenAI Codex CLI,
Gemini CLI, and the broader Cursor/Windsurf family) can consume Lathe alongside the current Neovim
client.
It captures the agent-landscape findings that motivate the split, the strategic frame, the curated
tool surface (all methods), the freshness model, a phased plan, and how we will measure the win.

**Approved decision (the MCP server):** a new in-repo Maven module **`lathe-mcp-server`**, built on the
**official MCP Java SDK** (`io.modelcontextprotocol.sdk`), talking to Lathe's engine **in-process** via
a thin facade exported from `lathe-server` — one process per agent session, no second JVM and no
LSP-over-stdio hop. It ships with its **own launcher** (`lathe-mcp-launcher.sh`) and resolves its
workspace from the process working directory. Rationale and alternatives are in
[Architecture](#architecture).

## Strategic frame

The design optimizes for a single measurable outcome, and the decisions below flow from it.

- **North-star (success metric).** Make an AI agent measurably faster, cheaper, and more correct on a
  large multi-module Maven reactor than it is with grep alone: fewer tokens, fewer wrong edits, and
  fewer follow-up file reads on a real reactor (Dropwizard/Helidon, and privately, a real payment
  reactor). Success is a number, not a feature list — see [Measurement](#measurement).
- **Audience: AI agents, not editor users.** Emacs support is dropped as too small a niche; the agent
  audience is far larger and growing. The Neovim client remains the human path and the reference LSP
  consumer.
- **Lead with the uncontested channel.** Codex CLI and Gemini CLI have **no** native LSP and route all
  code intelligence through MCP — their baseline is grep. Claude Code has native Java LSP but a thin,
  generic plugin surface. So: lead with Codex/Gemini (where any javac-accurate intelligence beats
  grep), and beat Claude Code's native path on reactor/dependency/generated/javac accuracy.
- **Non-negotiable principle — return SOURCE SNIPPETS.** Every result returns the surrounding code
  (the enclosing declaration, line-numbered, match marked), never a bare `file:line`. Empirically this
  is the biggest lever in this space (agent pass@1 ≈ 0.67 → 0.83; follow-up reads ≈ 15 → 3 per task).
  A bridge that returns coordinates loses to one that returns code.
- **This is a curated wedge, not a port of the LSP spec.** The LSP surface is the menu, not the spec —
  see [MCP tool surface](#mcp-tool-surface) for what is in and what is deliberately out.

This frame **supersedes** the earlier tiering in this document that led with run/test as "the MCP-only
value." For the lead (grep-baseline) audience, javac-accurate **navigation and diagnostics with
snippets are the wedge**; run/test is the loop-closer, not the opener.
**References are not a standalone wedge** — see [Field evidence](#field-evidence--first-ab-2026-09-20):
reference *enumeration* on distinctive names ties grep; `find_references` pays off inside the
mutate→verify loop and on polymorphic symbols, not as standalone search.

## Field evidence — first A/B (2026-09-20)

The first measured A/B refines the frame above and corrects one claim in it.

**The result.** On a private payment reactor, task = exhaustively list every call site of a static
utility method (a currency-conversion helper, `MoneyUtil.toMinorUnits`) across the reactor. Treatment
(agent + `find_references`) vs baseline (agent + grep only):

- **Correctness: a tie.** Both arms produced the identical, fully correct answer — every call site,
  and both correctly excluded the shadowing local overloads. The grep-agent even found the module a
  one-shot `grep -rl` had missed; a thorough agent greps several ways and self-corrects.
- **Cost: MCP strictly worse.** ~3× cost, ~2× turns, ~2× wall time. The treatment called
  `find_references` once, then *still* grepped and read files to re-verify the overloads the
  snippet-enriched result had already distinguished. The tool was **additive, not a replacement**.

**Why, and the test it implies.** "List all uses" of a rare, distinctive name is inside what a careful
grep-agent does well, so a semantic tool only adds cost. The durable test every MCP tool must pass is
therefore: *it answers a question the agent cannot cheaply get right itself.* Three axes pass it;
read-only enumeration of distinctive names does not.

| Axis | What it gives | Why grep / `mvn`-by-hand can't |
|---|---|---|
| **A — compiler truth** | `get_diagnostics` (single-file, classpath-accurate); reactor build stays the agent's `mvn` | No text substitute for "does this compile on the real classpath"; Lathe gives a sub-second, single-file, classpath-accurate answer (the reactor-wide build is Maven's job, not a tool Lathe wraps) |
| **B — polymorphic relationships** | implementations, overrides, call / type hierarchy | No text pattern expresses "who implements X" / "what overrides this" / transitive callers — grep is *structurally* wrong, not just slow |
| **C — correct-by-construction mutation** | `rename_symbol` + a build oracle (the agent's `mvn`) | grep+sed risks a missed site or a broken import; the semantic edit is correct by construction and the build oracle proves it cheaply |

**The distrust tax.** The dominant cost was not the tool — it was the agent re-deriving the answer to
*trust* it. A result that is merely fast, or even name-addressed, does not fix this; an
**authoritative** result (one that states what it excluded and why) and a **cheap build oracle** do.
See the authority clause in [Snippet contract](#snippet-contract-shared-result-shape) and the corpus
guidance in [Measurement](#measurement).

**Bottom line.** Lead with the **mutate→verify loop (Tier 2)** and **polymorphic queries**, not
read-only reference enumeration. `find_references` earns its place *inside* the loop (feeding rename)
and on polymorphic symbols — not as standalone search on distinctive names.

### Second A/B — polymorphic reference sites (2026-09-20)

A follow-up on the same reactor tested the axis the first A/B pointed to: an **overloaded,
polymorphic** name instead of a distinctive one. Task = list every site to rename an interface
method `submit(Request)` — declared once, implemented by ~20 adapters, called across modules —
excluding unrelated same-named methods. Treatment (`find_references`) vs baseline (grep only), scored
against the 126-site ground truth from the tool itself.

- **Correctness: tie at 100%** — both reached 126/126 precision *and* recall. But the tie held only
  because the prompt **named the exclusions** (a static factory `submit`, a second interface's
  `submit`); in the wild that disambiguation is exactly what `find_references` does for free and the
  grep-agent must reason out. `find_references` even split two `submit` tokens on a single line
  (`h.submit(RequestFactory.submit(ctx))`), which text search cannot.
- **Cost: MCP clearly cheaper** — 1 tool call vs 20+ grep/read/bash; **~1.9× fewer turns, ~1.7×
  lower cost, ~2.5× less wall time**. The grep-agent spent its run reconstructing the class hierarchy
  (interface → sub-interfaces → ~20 overrides) by hand against 396 noisy `submit` matches.

This is the mirror image of the first A/B: on a *distinctive* name MCP cost **more** for a tie; on a
*polymorphic* name it cost **less** for the same correctness — and the correctness parity is
understated, since the prompt handed the baseline the hard exclusions. Confirms **axis B** and the
mutate→verify lead. Next A/B should withhold the exclusion hints to expose the correctness gap, and
(when isolated from the real repo safely) measure the true mutating rename end-to-end.

## Motivation

Lathe already holds exactly the ground truth agents most often fake.
An agent's core loop is **edit → does it compile → find every site → change it → run the covering
test → fix**.
Agents fake almost every step: they guess Maven invocations, re-derive classpaths, grep for
references (missing sites and colliding on names), over-run whole suites, and read diagnostics from
build logs after the fact.
Lathe has captured the ground truth for all of it — the exact `javac` inputs, the exact Surefire
launch — and can replay a run without recompiling.
That is the wedge:
**Lathe is the build-truth, "find every real use," and "run the exact covering test" layer for coding
agents**, not merely another Java LSP.

## Landscape (as researched, Sept 2026)

The integration channels differ sharply by agent, and the difference drives the whole design.

| Capability | Claude Code | OpenAI Codex CLI | Gemini CLI | Cursor / others |
|---|---|---|---|---|
| Native LSP client | Yes — via a plugin's `.lsp.json` / `lspServers` | No (open requests, unimplemented) | No | No native LSP |
| LSP operations consumed | definition, references, completion, diagnostics, hover, rename + other standard features | — | — | — |
| `workspace/executeCommand` over LSP | No agent-invocable trigger | — | — | — |
| MCP servers | Yes | Yes (`~/.codex/config.toml`, `[mcp_servers.*]`) | Yes | Yes |
| Plugins | Yes | Yes (`.codex-plugin/plugin.json`) | Yes | Varies |

Two findings dominate:

1. **MCP is the only universal substrate.**
   It reaches every agent.
   The Claude Code native-LSP path reaches only Claude Code, and only the read half of Lathe.

2. **The native-LSP path cannot express Lathe's differentiators.**
   Claude Code's native LSP client consumes only the standard read operations and does **not** invoke
   `workspace/executeCommand`.
   Lathe's run/test capture-and-replay, cross-module rename, scoped build verification, and the rest of
   the custom-command surface are therefore invisible over native LSP.
   They only travel over MCP, where they become first-class agent tools.

Schema note (verified in Phase 0 against the live plugins reference at
`code.claude.com/docs/en/plugins-reference`): an LSP server is registered either in a `.lsp.json` at
the plugin root or under an `lspServers` key in `plugin.json`. Required fields are `command` (must be
on `$PATH`) and `extensionToLanguage`; optional fields include `args`, `transport`
(`stdio` default — note Claude Code runs *every* server over stdio even if `socket` is declared),
`env`, `initializationOptions`, `settings`, `workspaceFolder`, `startupTimeout`, `shutdownTimeout`,
`restartOnCrash`, `maxRestarts`, and `diagnostics`. There is still **no agent-invocable path for
`workspace/executeCommand`**, so Lathe's run/verify/rename verbs require MCP. The `command` must
resolve on `$PATH`, so a plugin points at `lathe-launcher.sh` rather than a bare binary.

## The tension, and the resolution

The channel that is easiest to ship (a Claude Code LSP plugin) exposes the *least* differentiated
slice of Lathe — read-only navigation, where Eclipse JDT.LS already ships as the default
`jdtls-lsp@claude-plugins-official`.
The differentiated slice — javac-accurate navigation/references **with snippets**, cross-module
rename, scoped verification, and run/replay — travels **only over MCP**, which is also the only channel
that reaches Codex and Gemini.

Resolution: **MCP is the primary, universal, differentiated surface; the LSP plugin is a thin
Claude-Code-only convenience** so that read features flow through the fast native path there.
Build MCP first.

## Non-negotiable prerequisites

Two invariants sit under everything below.

1. **A populated `.lathe/` at the reactor root.**
   The server refuses to run without it.
   Any agent onboarding must begin with a real Maven build having run once, e.g.
   `mvn clean test -Dlathe.capture.only=true`.
   This is a genuine setup cost the default `jdtls`-via-plugin path does not carry; it must be surfaced
   explicitly in onboarding.
   The MCP server detects a missing/empty `.lathe/` and returns that exact remediation as a structured,
   actionable error rather than a bare failure.

2. **Snippet context on every result.**
   See the [Strategic frame](#strategic-frame). This is baked into the shared result shape, not
   optional per tool.

## Architecture

One engine, three front-ends.
The MCP server and the LSP plugin are **new clients** beside the existing Neovim client — they do not
fork or duplicate server logic.

- **Existing (unchanged):** `LatheServer` speaks LSP/JSON-RPC over stdio, launched by
  `lathe-launcher.sh`. Its lean `module-info` and the editor path are untouched by this work.
- **New — `lathe-mcp-server` module:** an MCP server built on the official MCP Java SDK, calling
  Lathe's engine **in-process** through a facade (below). Its own launcher, `lathe-mcp-launcher.sh`.
- **New — Claude Code LSP plugin:** a declarative `.lsp.json` / `lspServers` entry pointing Claude
  Code's native LSP client at `lathe-launcher.sh` for the read/nav operations it surfaces; no code,
  config only.

The MCP server carries almost all the value and almost all the work; the rest of this section is its
approved shape.

### Standalone process, workspace resolved from `cwd`

For v1 the MCP server is a **standalone** process, not shared with a running editor.
It resolves its reactor by walking up from the process **working directory** to the nearest `.lathe/`;
onboarding documents "run the agent from inside the project."
There are **no agent-facing open/close tools** — lifecycle is process spawn/exit, and workspace reload
is automatic via the existing staleness path.
The workspace is opened lazily on the first tool call and cached for the session, so the load cost
(≈3.4s on a 332-module reactor) is paid once, not per call.
A shared-with-editor server (one warm JVM serving both the editor over stdio and the agent over a
socket/HTTP transport) is a deliberate later phase — it adds warm-state reuse and human/agent
consistency but requires a second transport and concurrency work, and it does **not** change the
freshness model (see [Freshness model](#freshness-model)).

### `lathe-mcp-server` — the module

- New Maven module parallel to `lathe-server`. Package root `io.github.aglibs.lathe.mcp`,
  `groupId io.github.ag-libs`, Java 21. Build order:
  `lathe-core → lathe-compiler → lathe-server → lathe-mcp-server → lathe-maven-plugin`.
- **Dependency isolation is the reason it is a separate module.** The SDK's closure — Reactor +
  reactive-streams, SLF4J, Jackson 2 annotations + Jackson 3 databind, networknt json-schema-validator
  — lands **only here**. `lathe-server` gains no new dependencies.
- Dependencies: `io.modelcontextprotocol.sdk:mcp` (the bundle = core + Jackson-3 binding, versions via
  `mcp-bom`); regular deps on `lathe-server` and `lathe-core`.

### In-process facade — `LatheEngine`

The MCP module depends on `lathe-server` as a library and calls its services **directly in the same
JVM** — no child process, no JSON-RPC hop, no LSP `didOpen`/`didChange` mirroring. MCP tools invoke the
*same* code the LSP handlers do, so behaviour is identical by construction.

To keep the coupling bounded, `lathe-server` gains **one small concrete facade** — `LatheEngine`, a
single **`public` class in the existing package `io.github.aglibs.lathe.server`** (not a new `.api`
package: the facade needs package-private access to `WorkspaceSession` / `LatheTextDocumentService`,
which a separate package could not reach, and a lone class does not justify its own package). It grows
beyond the original `get_diagnostics`-only sketch to back every read tool plus rename, each mapping to
an existing `WorkspaceSession` / text-document-service method:

- lifecycle: `openWorkspace(root)` (synthesizes the existing `initialize`, reusing `WorkspaceSession`
  and its missing-`.lathe/` remediation), plus internal reload;
- reads: `diagnostics`, `definition`, `hover`, `references`, `implementations`, `searchSymbols`,
  `documentSymbols`, `callHierarchy`, `typeHierarchy`;
- writes: `rename`, `addMissingImports`;
- run: `runnables`, `runTest` (and optionally `runMain`).

The protocol is stateless (the agent manages no `didOpen`/`didChange` lifecycle), so the facade opens
each file from disk on demand and runs the existing analysis path.
**Every tool — including `get_diagnostics` — is request/response over a per-call `CompletableFuture`.**
Diagnostics are *not* captured as a pushed `publishDiagnostics` notification: the facade adds a
worker-confined method that wraps the compile as a future (mirroring the existing
`definitionFuture` / `referencesFuture`) and completes it with the compiled diagnostics. There is
therefore **no capturing state and no `uri → future` registry** — the only cross-thread object per call
is that one future (a lock-free single-shot handoff). The `LanguageClient` the `WorkspaceSession`
constructor requires is supplied as a **pure no-op stub**; a real streaming/progress bridge over the
`LatheLanguageClient` interface is deferred to the streaming phase and, even then, needs no
pending-diagnostics map.

Since `lathe-mcp-server` runs on the **classpath** (see the launcher note and JPMS decision below),
`lathe-server` there is loaded as an unnamed-module library: its `module-info` is ignored, so the
`public LatheEngine` is callable regardless of exports — **no `module-info` change, no qualified
export**.

`verify_build` is the one tool that is **not** a pure `LatheEngine` call: it runs a scoped Maven build
out-of-process (see [the tool surface](#mcp-tool-surface)) and then calls `LatheEngine`'s reload path
to refresh `.lathe/` and read back diagnostics.

### State and synchronization

The MCP server is **protocol-stateless but process-stateful**: it holds a warm workspace across the
session (opened lazily on the first tool call, cached) so it never pays the reactor-load cost per call.

- **Workspace state** (`WorkspaceSession` and everything it owns — module registry, type index,
  `DocumentRegistry`, candidate index) stays **confined to the single `lathe-worker` event loop**, as
  it already is for the editor. Thread-confinement *is* the synchronization: `LatheEngine` never
  touches session state from a tool thread — it only `worker.execute(...)` onto it and awaits a future.
- **Per-call futures** are local, single-completion handoffs — safe by construction, not shared state.
  Concurrent calls (even on the same file) each own their own snapshot and future, so nothing collides.
- **No MCP-introduced shared mutable state**: no locks, no concurrent maps. The lazy `openWorkspace` is
  the one write-once value, guarded by a single init future all first callers await.
- **Reload** (`verify_build`, the staleness watcher) mutates the whole workspace, but runs on the
  worker like everything else, so it is serialized with reads by FIFO ordering — never a side-channel
  mutation.

### MCP server wiring

`LatheMcpServer.main` builds an `McpSyncServer` (the SDK's blocking facade — hides Reactor) over the
SDK **stdio** transport, registers the tools with input schemas, and serves. One process per agent
session, matching today's one-server-per-client model.

### Own launcher

`ServerInstaller` (in `lathe-maven-plugin`) generates and installs `lathe-mcp-launcher.sh` to
`~/.cache/lathe/servers/<version>/`, mirroring `lathe-launcher.sh`'s javac `--add-exports/--add-opens`
set (the in-process engine runs javac) **plus** the SDK jars and the MCP main class.

It is a **classpath** launcher (`-cp … io.github.aglibs.lathe.mcp.LatheMcpServer`), not a module-path
(`-m`) launcher — see the JPMS decision below. The javac `--add-exports` targets become `ALL-UNNAMED`
(classpath code lives in the unnamed module). The editor's `lathe-launcher.sh` is untouched.

### Editor coexistence

When a developer runs the agent and the Neovim client on the same project, the agent edits files in
place with its own tools (the MCP server never writes files except through `rename`/`add_missing`).
A small `autoread` + `:checktime` addition to the Neovim client absorbs those on-disk edits silently
(no "file changed on disk" prompt) whenever the buffer is unmodified, keeping the editor consistent
with the agent.
The only unresolved case is the genuine two-writers race (a human hand-editing, with unsaved changes,
the same file the agent just wrote) — workflow discipline, not a tooling fix.

### Alternatives considered and rejected

- **Out-of-process MCP server** (spawns `lathe-launcher.sh` and speaks LSP to it): no `lathe-server`
  change and a leaner launcher, but re-adds the translation hop, child-process lifecycle, and
  `didOpen` mirroring — the very costs that motivated going native. Rejected.
- **Hand-rolled MCP transport** (no SDK, on lsp4j's generic JSON-RPC): zero new dependencies, but owns
  MCP spec-compliance and protocol-version churn by hand and cannot cheaply reach the richer surface
  (progress/streaming, structured output, resources, HTTP) that maps onto Lathe's already-shipped
  streaming/structured/cancel features. Rejected in favour of the SDK.
- **Shared-with-editor server for v1**: warm and consistent, but needs a second transport and
  concurrency work and does not improve freshness. Deferred to a later phase, not v1.

## Freshness model

The central design fact for the agent-edit workflow, stated plainly so no tool over-promises.

Because the protocol is stateless, every tool reads the target file **from disk** at call time and
feeds the fresh content through the existing open-file compile pipeline — javac compiles the current bytes
against the captured `.lathe/` classpath. So the compiler does real work on the latest saved content,
no Maven required for a single file.
The boundary is what the *rest* of the world resolves against: every other type resolves from the
captured `.lathe/` bytecode, i.e. the last `mvn process-test-classes`.

| Edit scope | How it becomes fresh | Maven? |
|---|---|---|
| The single edited file | in-process javac vs `.lathe/` | No (≈280ms) |
| Multiple files, same module | in-memory overlay recompile of siblings | No — **planned, unshipped**, bounded to one module |
| Cross-module (an API used by another module) | `verify_build` — scoped reactor build | **Yes — unavoidable** |

This is a compiler/reactor property, **identical for the human editor and the agent** — the editor's
"save" is also single-file, and cross-module correctness needs the reactor to recompile upstream
modules and regenerate sources in order.
Lathe does not fake it: `get_diagnostics` is messaged as "errors in *this* file after your edit," and
carries an honest staleness hint when a referenced file changed since the last build; cross-module
verification is `verify_build`, and `run_test` is gated on recompiling changed files before replay so
it cannot report a stale pass/fail.

## MCP tool surface

A tight, curated agent surface — under half the LSP spec.
All read results carry the snippet contract (below).
Addressing: use-site tools take `file,line,column`; search takes a name; whole-file tools take `file`.
Names are indicative and map to existing server endpoints/commands.

### Snippet contract (shared result shape)

Every located result carries `{ uri, module, range{start,end}, kind, containerName, snippet }` where
`snippet` is the **enclosing declaration** (method/field/type), line-numbered, with the matched line
marked, capped (≈30 lines; if larger, the signature plus a tight window).
List results (`find_references`, `call_hierarchy`, …) are **ranked** (same-file → same-module → rest),
**capped** to `maxResults` with a tighter per-item window, and paginated via `truncated` + `total` +
`cursor`.

**Authority clause.** Snippets are necessary but not sufficient: the first A/B showed the agent
re-verifying results it already had ([Field evidence](#field-evidence--first-ab-2026-09-20)). Every
result must also be **authoritative** — it states what it *excluded* and why, so nothing is left to
hand-verify. Concretely, semantic tools carry an `excluded[]` beside the hits: `find_references`
reports the shadowing overloads it deliberately skipped (`{binaryName, reason}`), and `rename_symbol`
reports the sites it did *not* touch. A result the agent must re-check with grep has failed this
contract.

### Tier 1 — foundational reads (build first; help every workflow)

| Tool | Maps to | In → Out |
|---|---|---|
| `get_diagnostics` ✅ | diagnostics | `{file}` → `{diagnostics[], stalenessHint?}` — "errors in *this* file after your edit." |
| `get_definition` ✅ | definition | `{file,line,column}` → `{targets[]{…snippet, resolvedInto: reactor\|dependency\|jdk\|generated}}` |
| `describe_symbol` ✅ | hover | `{file,line,column}` → `{markup}` — the rendered signature/type/javadoc markdown (passthrough, not re-parsed). |

### Tier 2 — the migration / removal loop (highest daily leverage on a reactor)

| Tool | Maps to | Kind | In → Out |
|---|---|---|---|
| `find_references` ✅ | references | read | `{file,line,column,maxResults?,cursor?}` → `{total, truncated, references[]}` |
| `rename_symbol` ✅ | rename | **write** | `{file,line,column,newName}` → `{renamed, from, to, editedFiles[], totalEdits}` \| structured refusal. Cross-module confirmed working ([probe](../gaps/gaps-archive.md#fr-017)). |
| ~~`verify_build`~~ **DROPPED** | — | — | Wrapping `mvn` over the reactor is something the agent can do itself; see [Status](#status). Cross-module verification stays the agent's own `mvn`; Lathe surfaces staleness instead. |
| `find_implementations` ✅ | implementation | read | `{file,line,column,maxResults?}` → `{total, truncated, implementations[]}` — an interface's impls / a method's overrides, cross-module. |
| `search_symbols` ✅ | workspace/symbol (CamelHumps) | read | `{query, maxResults?}` → `{query, total, symbols[]{name, kind, container, snippet}}`. Kind filter deferred. |

✅ = shipped. These compose
into the dominant reactor workflow — cross-module config/API migrations and safe removals — and each
tool's description hands off to the next: `find_references` (find every site) → `rename_symbol`
(change them atomically) → **rebuild** (the agent's own `mvn`; a `Stale:` advisory on subsequent
results tells it when a re-sync is needed before trusting them).

### Tier 3 — the run loop (loop-closer)

| Tool | Maps to | Kind | In → Out |
|---|---|---|---|
| `list_runnables` | `lathe.runnables.list` | read | `{file?}` → `{runnables[]{id, kind, module, displayName}}` |
| `run_test` ✅ | `lathe.run.test` replay | **action** | `{file, scope: class\|method\|package, method?}` → `{launched, passed, failed, skipped, failures[]}`. Resolves the target from the file's runnables and reuses the editor's `{kind, RunTarget.id}` selection mapping. **method / class / package** shipped; **module** deferred (no module-run path yet). Blocked outcome (no `test-launch.json` / runner jar) returned as an actionable result; `Stale:` advisory rides along. |

An optional sibling `run_main` → `lathe.run.main` is deprioritized (not a headline agent verb).

### Tier 4 — medium usability

| Tool | Maps to | Kind | In → Out |
|---|---|---|---|
| `document_symbols` | documentSymbol | read | `{file}` → `{symbols[] tree}` — grok a large file without reading it whole. |
| `call_hierarchy` ✅ | call hierarchy | read | `{file,line,column, direction: incoming\|outgoing, maxResults?}` → `{incoming, total, calls[]{name, snippet}}`. One level; transitive depth deferred. |
| `type_hierarchy` | typeHierarchy / `lathe.typeHierarchy` | read | `{file,line,column, direction: super\|sub\|both}` → `{types[]{…, relation}}` |
| `add_missing_imports` | `lathe.missingImports` | **write** | `{file}` → `{added[], ambiguous[]{name, candidates[]}, unresolved[]}` |

### Example — `find_references` result (pins the snippet shape)

```jsonc
{
  "content": [{ "type": "text", "text":
    "12 references to `Config.urlPatterns` across 3 modules. Showing 12 of 12." }],
  "structuredContent": {
    "symbol": "Config.urlPatterns",
    "total": 12, "truncated": false,
    "references": [
      {
        "uri": ".../app/.../UrlResolver.java",
        "module": "app",
        "range": { "start": {"line": 88, "col": 20}, "end": {"line": 88, "col": 30} },
        "kind": "read",
        "containerName": "UrlResolver.resolve(String)",
        "snippet": "  86  public String resolve(String key) {\n  87    var cfg = config();\n> 88    return cfg.urlPatterns().get(key);   // <- match\n  89  }"
      }
    ]
  }
}
```

### Deliberately out of the MCP surface

Completion, signature help, folding ranges, semantic tokens, document highlight, formatting, on-type
formatting, the debugger, extract refactors, and the new-type scaffold.
These serve a human typing/rendering in an editor, or are edits an agent performs directly — not agent
outcomes on a reactor.

## Value proposition to lead with

- **Compiler-accurate ground truth** — diagnostics, types, and references are exactly what `javac` saw
  on the exact captured classpath; no reconstructed project model to drift ("green in build, red in
  tool"), and no grep name-collisions or missed cross-module sites.
- **Snippets, not coordinates** — every answer arrives as readable code, so the agent acts without a
  follow-up read per result.
- **Safe multi-module migration** — `find_references` → `rename_symbol` (correct-by-construction
  cross-module edits) → the agent's own `mvn` to verify, with Lathe's `Stale:` advisory flagging when
  a re-sync is needed — a complete loop for the reactor's most common change shape.
- **Run the exact covering test without recompiling** — the capture-and-replay moat, expressible only
  over MCP.
- **Reactor-correct multi-module** resolution and **dependency/JDK/generated-source** navigation out of
  the box.

## Phases

Ordered so the highest-leverage, most-portable capability lands first, and each phase is independently
useful.

### Phase 0 — Spike and validate the substrate — DONE

Driven through the existing Python LSP driver (`dev/lsp.py` / `dev/explore.py`), which spawns the
published `lathe-launcher.sh` over stdio and issues `initialize`, `didOpen`,
`textDocument/definition`, diagnostics, `lathe.runnables.list`, and `lathe.run.test` — a working
reference for what the MCP server must do. Validated end-to-end against the published launcher on the
in-repo `multi-module` invoker workspace and a large private multi-module workspace: clean
diagnostics, cross-file `definition`, runnables discovered, and a replay → `[PASSED] exit=0`.
The `.lsp.json` schema was re-verified against the live plugins reference.

### Phase 1 — Scaffold + Tier 1 (foundational reads) — DONE

- New `lathe-mcp-server` module wired into the reactor; SDK dependency; `LatheMcpServer.main` over the
  SDK stdio transport; classpath launcher; `cwd`-based workspace resolution; lazy-open + cache.
- `LatheEngine` facade (public, in `io.github.aglibs.lathe.server`) + a no-op `LanguageClient` stub and
  the worker-confined diagnostics-as-future method added to `lathe-server`.
- `ServerInstaller` generates and installs `lathe-mcp-launcher.sh`.
- Ship `get_diagnostics`, `get_definition`, `describe_symbol` (structured content + snippets).
- Missing/empty `.lathe/` returns the structured remediation.
- **Server-side tool-call logging** (tool, args summary, latency, result size) — a Phase-1 requirement
  so adoption is measurable from day one (see [Measurement](#measurement)).
- `dev/mcp.py` stdio driver (analog of `dev/lsp.py`).
- Registerable in Claude Code (`claude mcp add`), Codex (`~/.codex/config.toml`), and Gemini CLI.
- Exit: from a fresh agent session, edit a file, get diagnostics, and navigate — through MCP tools, on
  Codex/Gemini and Claude Code.

### Phase 2 — Tier 2 (the migration / removal loop) — DONE

- `find_references` ✅ and `rename_symbol` ✅ shipped (cross-module, javac-accurate; rename applies
  edits to disk and refuses non-reactor files). Measured win on a polymorphic name — see
  [Second A/B](#second-ab--polymorphic-reference-sites-2026-09-20).
- `search_symbols` ✅ shipped (name lookup across reactor + deps + JDK; kind filter deferred).
- `find_implementations` ✅ shipped (an interface's impls / a method's overrides, cross-module;
  verified live — a real interface resolved ~30 implementations a grep would miss).
- `verify_build` — **dropped** (see [Status](#status)); the loop verifies with the agent's own `mvn`.
- **Freshness advisory** ✅ and **routing instructions** ✅ shipped as cross-cutting additions this
  phase (not in the original plan): every result flags stale modules, and the server tells the agent
  which tool to reach for per task.
- Exit: complete a cross-module migration and a safe removal through MCP tools + the agent's `mvn`.

### Phase 3 — Tier 3 (the run loop) — DONE (method/class/package; module deferred)

- `run_test` ✅ shipped — replays a test **method / class / package** against the compiled classpath
  (no reactor build), resolving the target from the file's runnables and reusing the editor's
  `{kind, RunTarget.id}` selection mapping; returns pass/fail/skip counts + per-failure `type: message`
  and line. A blocked outcome (no captured `test-launch.json` / runner jar) is returned as an
  actionable result, and the `Stale:` advisory rides along. Verified live on the payment reactor
  (sub-3s per run). `list_runnables` was **not** needed as a separate tool — `run_test` resolves
  runnables internally.
- **Module** scope deferred: `TestSelectionKind` has a `MODULE` selector but the substrate has no
  module-level runnable / run path, so it's a follow-up, not "already supported."
- **Compact output for now** — counts + one-line failure summaries; the full stdout/stderr transcript
  (stack traces, logs) is a possible capped-tail follow-up. Replay stays gated on the `Stale:`
  advisory (see the [New/Changed-Test Replay Inner Loop](lathe-new-test-replay-loop.md)).

### Phase 4 — Tier 4 (medium tools) — IN PROGRESS

- `call_hierarchy` ✅ shipped — incoming/outgoing, one level (transitive depth deferred); verified
  live (97 cross-module callers on an overloaded operator method).
- `document_symbols`, `type_hierarchy`, `add_missing_imports` — not yet.

### Phase 5 — Measurement harness

- Build `dev/bench/` and run the grep-baseline comparison (see [Measurement](#measurement)); can start
  alongside Phase 2 once Tier 2 exists.

### Phase 6 — Shared-with-editor server, streaming, HTTP transport

- One warm JVM serving the editor and the agent; stream `run_test` output/progress via MCP progress
  notifications and wire cancellation onto `lathe.run.cancel` through the `LatheLanguageClient` stub;
  evaluate the SDK Streamable HTTP transport for shared/remote serving. Leans on Lathe's shipped
  [test-output-streaming](../done/lathe-test-output-streaming.md),
  [structured-test-results](../done/lathe-structured-test-results.md), and
  [test-cancel](../done/lathe-test-cancel.md).

### Phase 7 — Claude Code LSP plugin, packaging, docs

- Publish the `.lsp.json` / `lspServers` entry pointing at `lathe-launcher.sh` for read/nav operations;
  document the collision with `jdtls-lsp@claude-plugins-official` (first-registered-wins on `.java`).
- Onboarding docs under `docs/guide/` (an agent cheatsheet beside the per-editor cheatsheets): the
  `.lathe/` prerequisite, MCP registration for Claude Code / Codex / Gemini, and the LSP-plugin option.
- Marketplace/plugin packaging as the ecosystems' conventions settle.

## Measurement

Two layers: a formal, publishable harness, and lightweight day-to-day tracking for personal/team use.
Both compare **with MCP** against a **grep baseline** (the agent with only shell/grep/read, MCP off).

### Formal harness (the publishable proof)

`dev/bench/`: two arms — baseline (grep/read only) and treatment (same agent + Lathe MCP).
Corpus: 20–40 curated tasks on Dropwizard (controllable, 68 modules) plus a handful on Helidon (scale,
332 modules), each with a golden diff / passing tests, chosen to exercise the moat: cross-module
caller changes, implement-an-interface, fix-a-compile-error, call-a-dependency-correctly.
**Discriminator trap (from the first A/B):** do *not* include read-only enumeration of rare,
distinctive names — grep is near-perfect there, so the result is a guaranteed tie
([Field evidence](#field-evidence--first-ab-2026-09-20)). Curate tasks where grep is *wrong or
drowning*: common/overloaded names with cross-type false positives, high module cardinality where a
missed site breaks the build, overload-sensitive renames, and "who ultimately calls X" — with
`verify_build` as the oracle.
Metrics per task, paired: total tokens, pass@1 (patch applies + compiles via Lathe + tests green),
number of file-read tool calls, number of wrong/superseded edits, wall time.
Run N repeats for variance; report paired deltas with confidence intervals — the headline number.

### Day-to-day measurement (personal / team)

Real tasks are not repeatable (doing a task changes the code), so day-to-day uses a mix of an
experiment that *is* repeatable and lightweight ongoing tracking.

**Level 1 — adoption (leading indicator).** From the Phase-1 server-side tool-call log, tally which
tools the agent actually calls over a week (`get_diagnostics ×N, find_references ×N, …`). A tool that
is never called has zero value, and low adoption is a tool-*description* problem to fix before
anything else.

**Level 2 — per-task metrics from the agent session.** A shared log, one row per task, all capturable
today (e.g. from Claude Code `/cost` and the session transcript JSONL):

| Metric | Source | Direction with MCP |
|---|---|---|
| Tokens / task | `/cost` or transcript | ↓ (most objective) |
| # grep + file-read calls | transcript tool calls | ↓ |
| Wall-clock to done | note start/end | ↓ |
| Compiled clean first try | needed a fix-the-build round? | ↑ (pass@1) |
| Interventions (1–5) | how often you redirected it | ↓ (predicts adoption) |

**Level 3 — the paired experiment on real history.** Replay past agent-authored commits from a real
reactor from their parent git state: `git checkout <parent>`, prompt a fresh agent with the same task
(the PR/commit description), run once with MCP and once without in clean worktrees, and compare against
the real merged diff (the golden answer): tokens, tool-call count, wall time, did-it-compile
(`verify_build` / `mvn`), diff fidelity (files-touched overlap), and — crucially for migration/removal
— how many real sites it *missed*. Repeatable because it is anchored to a git commit.

**Pitfalls / what to trust.** Do not A/B a live task by re-doing it (the code moved) — replay past
commits (Level 3) or randomize MCP on/off across *different* live tasks and compare distributions over
20–30 tasks. Beat confounders (task difficulty, prompting, model drift) with volume (a whole team
logging) and randomization rather than "two weeks on / two weeks off." Trust tokens and completeness
("found all N sites") over subjective smoothness — the latter matters for adoption, not as evidence.

## Testing

Layered, risk-driven, following repo conventions (JUnit 5 + AssertJ, `@TempDir`,
`methodName_condition_result`, invoker fixtures on `verify`, reuse the compile pipeline, prefer real
objects over mocks).

1. **`LatheEngine` facade tests — the core.** Drive the facade directly against a fixture `.lathe/`
   workspace, reusing `lathe-server`'s compile/workspace harness. Real objects, no mocks. Cases:
   `openWorkspace` with/without `.lathe/` (populated vs remediation error); `diagnostics` clean vs
   error (the per-call future returns the compiled diagnostics — no capturing stub); each read tool's
   mapping; `references`/`implementations` returning cross-module results with snippet fields. Real
   `runTest` replay needs captured bytecode, so it lives in the invoker layer.
2. **MCP protocol tests — in-process, no subprocess.** Wire an SDK `McpClient` to our `McpSyncServer`
   over the SDK in-memory transport and exercise the real handshake: `tools/list` returns each tool
   with the correct input schema; `tools/call` returns structured content; bad args map to an
   `isError` result. `McpSyncServer` hides Reactor, so assertions stay synchronous.
3. **End-to-end launcher test — the risk-retirer.** The real `lathe-mcp-launcher.sh` over real stdio,
   as an invoker fixture beside `LspSmokeTest` / `MultiModuleTest` (run only on `mvn verify` against
   the `multi-module` workspace). `dev/mcp.py` sends `initialize → tools/list → get_diagnostics →
   find_references` and asserts results — the Phase-0 spike promoted to a committed test. Proves the
   classpath launcher and in-process javac running as unnamed-module code (`ALL-UNNAMED` exports).
   Built first, as the walking-skeleton acceptance test.
4. **Parity smoke.** MCP `get_diagnostics` for a file equals the LSP diagnostics for the same file
   (reuse `lathe-server`'s fixtures) — documents that the two front-ends cannot drift.
5. **Cross-module rename — confirmed by probe ([FR-017](../gaps/gaps-archive.md#fr-017)).** Working
   today; per-kind regression coverage (field / override family / type) is a recommended follow-up
   before broad reliance, not a ship blocker.
6. **Gate — `run_test` freshness.** Recompile-before-replay correctness must be in place before
   `run_test` ships, so a replay cannot report stale pass/fail.
7. **Cross-agent acceptance (manual, per release).** `claude mcp add` + Codex `config.toml` + Gemini
   CLI config + the MCP Inspector — confirm the tools appear and run. A documented checklist.

## Non-goals

- **No native `workspace/executeCommand` over the Claude Code LSP plugin** — the client does not consume
  it; run/verify/rename verbs go through MCP by design.
- **No completion/signature-help/folding/formatting/debug in the MCP surface** — these serve human
  typing/rendering.
- **No removal or divergence of the Neovim client** — it remains the human editor path and the
  reference LSP consumer. (Emacs support is dropped for the agent audience.)
- **No attempt to make `.lathe/` optional** for agents — the build-derived model is the moat (see
  [Workspace Readiness](../done/lathe-workspace-readiness.md)).
- **No faked cross-module freshness** — cross-module correctness is a reactor property; `verify_build`
  runs the (scoped) reactor rather than pretending an in-process compile can substitute for it.

## Resolved decisions

- **Module vs sidecar / SDK vs hand-rolled / in-process vs out-of-process** — a separate in-repo module
  `lathe-mcp-server`, on the official MCP Java SDK, calling the engine in-process via `LatheEngine`.
- **Standalone process for v1; workspace resolved from `cwd`; no agent-facing open/close tools;
  shared-with-editor server deferred.**
- **Snippet context on every result** — non-negotiable, baked into the shared result shape.
- **Curated surface, not an LSP-spec port** — Tiers 1–4 above; completion/formatting/debug/etc. out.
- **Session lifetime** — one MCP process per agent session (one workspace), matching the editor model.
- **`LatheEngine` placement** — a single `public` class in the existing `io.github.aglibs.lathe.server`
  package (needs package-private access to the session internals; callable on the classpath because
  `module-info` is ignored there), not a new `.api` package.
- **All tools are request/response over a per-call future** — including `get_diagnostics`, which wraps
  the compile as a future rather than capturing a `publishDiagnostics` push. No capturing state, no
  `uri → future` registry, no MCP-introduced locks/concurrent maps; the `WorkspaceSession` client is a
  no-op stub. A streaming/progress bridge over `LatheLanguageClient` is deferred to the streaming
  phase.
- **State & synchronization** — protocol-stateless, process-stateful (warm workspace); all workspace
  state stays confined to the `lathe-worker` event loop, which is the synchronization mechanism.
- **Freshness** — single-file in-process javac (no Maven); cross-module via `verify_build` (scoped
  Maven); not faked.
- **JPMS placement — classpath for now.** The JPMS spike (against SDK 2.0.1) found the SDK cannot go on
  the module path: `mcp-core` and `mcp-json-jackson3` ship a hyphenated `Automatic-Module-Name`
  (`io.modelcontextprotocol.sdk.mcp-core` / `…mcp-json-jackson3`) that is an invalid module name, so
  `jar --describe-module` reports *"Unable to derive module descriptor … not a Java identifier."*
  (No split package — the jackson3 binding uses distinct `.jackson3` subpackages.) So
  `lathe-mcp-server` runs on the **classpath**. Upstream already fixed the names on `main`
  (commit `183935b`), not yet in a release. Plan: ship classpath now; revisit a module-path build once
  a fixed SDK release lands.

## Open questions / tracking

- **Client behaviours to verify per agent** (Claude Code / Codex CLI / Gemini CLI): the exact
  `mcp add` / config format, and whether each sets the server subprocess `cwd` — the `cwd`-resolution
  policy depends on it.
- **`run_test` freshness** — recompile-before-replay is a hard prerequisite (Phase 3 gate); tracked via
  [New/Changed-Test Replay Inner Loop](lathe-new-test-replay-loop.md).
- **`rename_symbol` per-kind coverage** — cross-module rename confirmed working
  ([FR-017](../gaps/gaps-archive.md#fr-017), closed); field / override-family / type regression
  coverage is an optional follow-up.
- **Upstream JPMS fix release** — SDK `main` commit `183935b` fixes the module names; watch for the
  release, then evaluate moving to the module path.
- **Name-based addressing (friction-only, deprioritized)** — a jist-inspired name mode (`find_symbol`,
  `find_references(query:"pkg.Type.member")`) reusing `WorkspaceSymbolResolver` → anchor position → the
  existing pipeline removes anchor-finding turns but not the distrust tax
  ([Field evidence](#field-evidence--first-ab-2026-09-20)); a convenience layer behind the mutate→verify
  loop, not a wedge.
- **MCP protocol version** to advertise, and how to track SDK/spec revisions over time.
- **Auth/trust** — Codex project-scoped `.codex/config.toml` requires a trust marker; document the
  implications; check Gemini CLI's equivalent.

## Related work

- [FR-017](../gaps/gaps-archive.md#fr-017) — cross-module rename coverage gap; closed as
  confirmed-working by probe, per-kind coverage optional.
- [New/Changed-Test Replay Inner Loop](lathe-new-test-replay-loop.md) — the replay-staleness work that
  gates `run_test`.
- [Sibling Recompilation](lathe-sibling-recompilation.md) and
  [In-Process External-Change Recompilation](../potential/lathe-external-change-recompilation.md) — the
  bounded single-module freshness work behind the middle row of the freshness table.
- [External-Change Detection](../done/lathe-external-change-detection.md) and
  [Staleness Compile Stamps](../done/lathe-staleness-compile-stamps.md) — the freshness machinery the
  in-process facade's open-from-disk path and staleness hints rely on.
- [Workspace Readiness](../done/lathe-workspace-readiness.md) — the `.lathe/` prerequisite and its
  onboarding guidance.
- [Shared Workspace Server](../potential/lathe-shared-workspace-server.md) — the Phase-6
  shared-with-editor design.
