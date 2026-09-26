# Lathe — VS Code Client

> **Status: planned.** No code yet. This doc captures the scope split (passthrough MVP → full
> parity), the launch/distribution model, the source-of-truth decision, and how end-to-end testing
> works — so the shape is agreed before any TypeScript is written.

## Goal

Ship a supported VS Code integration for Lathe, on par with the Neovim client: diagnostics,
completion, navigation, and the Lathe-specific run/test/debug/resource features — installed the way
VS Code users install any extension (Marketplace / Open VSX).

This is **additive**. It does not change server delivery: the server stays Maven-resolved and
version-pinned via the `lathe-maven-extension`, unpacked into `~/.cache/lathe/current` by
`lathe:sync` exactly as today. Only a new client is added.

## Motivation

Neovim is the only supported editor today. VS Code is the single largest editor audience, and it is
already a named backlog intent (`docs/roadmap.md`). The server surface is already **editor-agnostic**
— every Lathe feature is a standard LSP request or a custom `executeCommand`/notification that any
client can consume (the run-configs design already notes "the server side is editor-agnostic; a VS
Code picker can consume the same command"). So this is client work, not server work.

## Current State

- The server exposes the full LSP surface plus ~28 custom `lathe.*` `executeCommand` commands and the
  custom notifications `lathe/sync`, `lathe/testEvent`, `lathe/testFinished`, `lathe/testOutput`.
- The launcher the client execs is a plain stdio LSP server at
  `${LATHE_SERVER_DIR:-${LATHE_CACHE:-~/.cache/lathe}/current}/lathe-launcher.sh` — no handshake env
  required (the launcher just `exec java … "$@"`). Same discovery the Neovim client uses
  (`lathe.lua:43-49`).
- The workspace root is identified by the `.lathe` directory marker, walked up from the open file
  (`lathe.lua` `ROOT_MARKER`, `get_root`).
- Semantic tokens (the roadmap's stated VS Code prerequisite) have shipped server-side; a stock
  `vscode-languageclient` registers whatever the server advertises, so the client gets them for free.

## Design

### 1. Two milestones: passthrough, then parity (DECIDED)

The client is delivered in two milestones, not one:

- **M1 — LSP passthrough.** A thin extension wiring `vscode-languageclient` to the cache launcher.
  Delivers, for free, everything that is a standard LSP request: diagnostics, completion, hover,
  signature help, definition/references/implementation, rename, formatting, semantic tokens,
  document/workspace symbols, folding, and (native LSP) type hierarchy. Ships value immediately;
  small enough to verify by hand.

- **M2 — parity.** The Lathe-specific commands, each mapped onto a VS Code-idiomatic surface (§2).
  This is the bulk of the work and the bulk of Lathe's differentiated value.

M1 is genuinely thin. M2 is not a mechanical port of the Lua client — VS Code has richer, opinionated
native UIs (Test Explorer, the debug UI, tree views) that the server commands map onto, so the glue
is a rewrite against VS Code APIs, not a translation of Lua.

### 2. Custom-command surface → VS Code APIs (the M2 map)

Every capability the Neovim client implements as a Lua module has a VS Code-native counterpart. The
server contracts are unchanged; only the client rendering differs.

| Lathe capability (nvim module) | Server commands / notifications | VS Code API | Effort |
|---|---|---|---|
| Run main/test (`run.lua`) | `run.main/test/named`, `run.cancel`, `runnables.list/dir` | Task API or terminal + `OutputChannel`; **CodeLens** for run-under-cursor | medium |
| Test runner (`neotest`) | `runnables.list`, `run.test`, `lathe/testEvent`/`testFinished`/`testOutput` | **Test Explorer API** (`TestController`, streaming `TestRun` results) | high |
| Debug (`dap.lua`) | `debug.main/test/named` | Native **DebugAdapter** + `DebugConfigurationProvider` | high |
| Run configs | `runconfig.save`, `runconfigs.list` | **QuickPick** + optional `launch.json` contribution | medium |
| Resources browser (`resources.lua`) | `resources`, `resourceOpen`, `resource.refresh` | **TreeView** (custom view container) + read-only/virtual documents | medium |
| Type hierarchy (`typehierarchy.lua`) | `typeHierarchy` | native LSP Type Hierarchy, or a **TreeView** | low–medium |
| New type (`createType`) | `createType` | QuickPick + `WorkspaceEdit` | low |
| Missing imports | `missingImports` | **CodeAction** / quick-fix | low |
| Module / package pickers | `modules`, `packages` | QuickPick / completion | low |
| Workspace sync | `lathe/sync` | notification handler (status / mostly no-op) | low |
| Indent + fold (`indent.lua`, `fold.lua`) | on-type indent; imports fold | `OnTypeFormatting` / indentation rules; `FoldingRangeProvider` | medium |
| Code intelligence helpers | `instantiations`, `unused`, `resolveContext`, `testSources` | consumed by the above as needed | — |

The Test Explorer and debug integrations are the two big rocks. AST-aware indentation is the one case
that is *harder* in VS Code than in Neovim, because it fights VS Code's built-in indentation model.

### 3. Launch model: reuse the cache launcher (DECIDED)

The extension spawns the existing cache launcher over stdio — the same target the Neovim client uses:

1. Resolve the launcher: `LATHE_SERVER_DIR` (dev override) → else
   `${LATHE_CACHE:-~/.cache/lathe}/current` → `/lathe-launcher.sh`.
2. If it is not executable, surface an actionable message —
   *"Lathe: launcher not found; run `mvn process-test-classes`"* — and do not attach (mirrors
   `lathe.lua:119-126`).
3. `ServerOptions = { command: launcher, transport: stdio }`;
   `LanguageClientOptions = { documentSelector: [{ language: 'java' }] }`, `rootUri` = the folder
   containing `.lathe`, walked up from the workspace folder.

**Why not bundle the server/extension in the jar and cache-install it like the Neovim client.** The
Neovim client is jar-bundled because Neovim loads a plugin from any local directory via
`runtimepath`; VS Code has **no equivalent** — it loads extensions only from its own extensions dir
or a registry. A `.vsix` sitting in the cache is inert. Installing it with
`code --install-extension` copies a *snapshot* in, which goes stale on the next `lathe:sync` and would
force us to build an updater. The clean resolution falls out of what already ships: the **server** is
already cache-installed and updated by `lathe:sync` (the part that changes often), and the
**extension** is a thin launcher-locator that changes rarely. So the extension is published normally
and locates the already-cached server — no bundled-vsix staleness, no updater.

### 4. Source of truth: monorepo mirror (DECIDED), mirroring `lathe.nvim`

The client source lives in the monorepo (`lathe-maven-plugin/src/main/vscode`) and the standalone
`ag-libs/lathe.vscode` repo is a **generated one-way mirror**, exactly as for the Neovim client
(see [Standalone `lathe.nvim` Plugin](lathe-standalone-nvim-plugin.md) §1).

**Why the mirror model here specifically.** The M2 parity client co-evolves tightly with the server:
every custom command is a client↔server contract (command names, argument shapes, the `lathe/test*`
notifications) that changes on both sides together. Keeping the client in the monorepo means those
changes land in one commit and — critically — the client is testable against the **working-tree**
server *before* release (§6), which a standalone repo cannot do. For a pure-passthrough M1 in
isolation this coupling does not exist and standalone development would be defensible; but since the
target is parity, monorepo-source-of-truth is the right call from the start.

The mirror carries standalone-only overlay files kept in the monorepo under `dev/vscode-mirror`:
Marketplace `README.md`, `CHANGELOG.md`, `icon.png`, `.vscodeignore`, `LICENSE`, and the publish
GitHub Action (§5). The release semver is stamped into `package.json` `version` (the analogue of the
Neovim client's `version.lua`).

**Known cost — one-way mirror.** As with `lathe.nvim`, PRs opened directly against `lathe.vscode`
cannot merge back without re-authoring. Acceptable while there are no external contributors; the
escape hatch (flip the source of truth to the standalone repo, consumed via submodule/fetch) is the
same as documented for Neovim and stays explicitly reversible.

### 5. Publishing: monorepo push, Marketplace via CI in the mirror (DECIDED)

Distribution channels are the **VS Code Marketplace** and **Open VSX** (the latter covers
VSCodium/Cursor/Gitpod, which cannot reach the MS Marketplace). Neither installs from git, so — unlike
`publish-nvim.sh`, where the git mirror *is* the distribution channel — the actual publish is a
token-authenticated upload, not a push. To keep the monorepo secret-free, the publish runs as a
**GitHub Action in the mirror repo**, and the local script stays a no-PAT git push:

- **`publish-vscode.sh`** (monorepo, run after `release.sh` tags and the tag is pushed): a
  snapshot-per-release script modeled on `publish-nvim.sh` — tag worktree checkout, rebuild the mirror
  tree from `src/main/vscode` + `dev/vscode-mirror` overlays, stamp the semver into `package.json`,
  `--dry-run` support, and the same "tag must be on origin first" guard. It pushes the snapshot +
  `vX.Y.Z` tag to `lathe.vscode`. **No PAT** — a cross-repo `git push` with the maintainer's own creds.
- **`.github/workflows/publish.yml`** (in the mirror, kept in the `dev/vscode-mirror` overlay so the
  snapshot rebuild does not wipe it): `on: push: tags: ['v*']` → `npm ci` → `vsce publish` +
  `ovsx publish`, using **`VSCE_PAT`** (Azure DevOps Marketplace) and **`OVSX_TOKEN`** (Open VSX)
  stored as **Actions secrets in the mirror repo**. Optionally a guard step asserts the tag matches
  `package.json`'s version (the analogue of the Neovim drift-guard on `version.lua`).

Net flow:

```
monorepo release (tag vX)
   └─ publish-vscode.sh  ── git push snapshot+tag ──▶  ag-libs/lathe.vscode
                                                          └─ Action (on tag)
                                                               ├─ vsce publish  → MS Marketplace
                                                               └─ ovsx publish  → Open VSX
```

The monorepo stays secret-free and drives everything; the mirror is generated; the PAT is isolated in
the mirror's CI. Same mental model as Neovim, with the Marketplace upload bolted on where the secrets
can be contained.

**Publisher setup (one-time, out-of-band):** an Azure DevOps org, a PAT scoped *Marketplace → Manage*
across *all accessible organizations*, and a publisher created at
`marketplace.visualstudio.com/manage` whose id matches `package.json` `publisher`. An Open VSX
namespace + token for the second registry.

### 6. Protocol handshake (reuse the Neovim mechanism)

The M2 client, installed from a registry, can drift from the Maven-pinned server, so it reuses the
existing coarse-integer handshake rather than inventing a new one: the server already advertises
`capabilities.experimental.latheProtocol` (keyed by `LatheFlags.PROTOCOL_CAPABILITY`). The VS Code
client reads it on initialize and compares to a `PROTOCOL` constant it ships (in `package.json` or a
generated constant), warning on mismatch — server older/absent → *"bump the `lathe-maven-extension`
version and rebuild"*; server newer → *"update the Lathe extension"*. The drift guard that keeps the
Java constant and the client copy in lockstep is extended to cover the VS Code client's copy.

This is an M2 concern; the M1 passthrough rides only stable standard-LSP requests and does not need
it to function.

## End-to-end testing

The Neovim client already establishes the pattern to follow: `src/test/neovim/run-specs.sh` runs
headless nvim against the client, bound to the `integration-test` phase via exec-maven-plugin, and it
**hard-fails under CI but skips gracefully when the binary is absent locally**. The VS Code analogue
is `@vscode/test-electron`, and testing splits into three tiers by how much of the stack each needs:

1. **Client-logic tests (no server).** Launcher resolution, `.lathe` root walking, the missing-launcher
   message, `documentSelector` registration. Fast, run anywhere, no monorepo dependency.
2. **Contract/smoke tests (stub launcher).** Point `LATHE_SERVER_DIR` at a fake `lathe-launcher.sh`
   that returns a canned `initialize` response; assert the client starts, initializes, and registers
   for Java. No real server. (Mirrors how the nvim specs exercise client behavior.)
3. **True E2E (real server + fixture).** Real diagnostics/completion on real Java. This needs three
   monorepo-originated artifacts: a **built server** in `~/.cache/lathe/current`, a **`.lathe/`
   workspace** produced by `lathe:sync`, and the **cache launcher**. In the monorepo these already
   exist — the reactor builds the server and the `multi-module` invoker fixture produces `.lathe/` +
   the launcher, so a `@vscode/test-electron` run slots in beside `run-specs.sh` in the
   `integration-test` phase, testing the **working-tree** server before release. CI needs `xvfb`
   (headless Electron is heavier than headless nvim).

The working-tree E2E of tier 3 is the reason the client lives in the monorepo (§4): it catches
client↔server contract drift the moment it is introduced. A standalone repo could only reach tier 3
via a **published-server bootstrap** — a sample project using the released `lathe-maven-extension`,
`mvn process-test-classes` to populate the cache, then run the E2E — which works but tests only the
*released* server, lagging any unreleased contract change by a release. That path is documented here
as the fallback if the source of truth is ever flipped, not the primary.

## Open Decisions

- **Multi-root / submodule-open behavior.** If a user opens a *submodule* folder rather than the
  reactor root where `.lathe` lives, do we walk up past the workspace-folder boundary to find `.lathe`
  and set that as `rootUri` (matching Neovim), or require opening the reactor root? Leaning toward
  walk-up for parity with the Neovim client.
- **Repo/org name** for the mirror (`ag-libs/lathe.vscode` assumed, matching `ag-libs/lathe.nvim`).

## Out of Scope

- Any change to server delivery. The server stays Maven-resolved and version-pinned; only a client is
  added.
- Jar-bundling the extension into the cache (§3 rejects it for VS Code specifically).
- Marketplace listing polish beyond a functional listing (screenshots, GIFs, categories) — a
  promotion task, not part of the client bring-up.

## Tests

- **M1:** client-logic unit tests (tier 1) for launcher resolution, root walking, and the
  missing-launcher message; a stub-launcher smoke test (tier 2) asserting initialize + Java
  registration; a monorepo `@vscode/test-electron` E2E (tier 3) against the `multi-module` fixture
  asserting diagnostics/completion appear — CI-hard-fail / local-skip like `run-specs.sh`.
- **M2:** per-command-family tests as each lands — Test Explorer result streaming from `lathe/test*`,
  a debug session via the DebugAdapter, the resources TreeView, `createType` `WorkspaceEdit`, and the
  `missingImports` code action.
- **Handshake (M2):** extend the protocol drift guard to the VS Code client's `PROTOCOL` copy; a
  client test for matching → silent, older/newer → the respective message.
- **Publishing:** `publish-vscode.sh` kept `--dry-run`-able (like `publish-nvim.sh` / `release.sh`) so
  the snapshot + version stamp can be inspected without pushing; the mirror Action is exercised on a
  real tag push, not unit-tested.
