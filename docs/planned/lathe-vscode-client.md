# Lathe — VS Code Client

> **Status: planned.** No code yet. Two milestones agreed:
> **M1 — self-built, tester distribution:** we build the `.vsix` ourselves and hand it to a handful of
> testers via a GitHub release asset (no Marketplace, no publisher account, no PAT).
> **M2 — go all in:** publish to the VS Code Marketplace + Open VSX, gated on naming/trademark
> resolution and the first-run UX below.
> This doc captures the scope, launch/distribution model, source-of-truth decision, naming, first-run
> UX, and end-to-end testing so the shape is agreed before any TypeScript is written.

## Goal

Ship a supported VS Code integration for Lathe, on par with the Neovim client: diagnostics,
completion, navigation, and the Lathe-specific run/test/debug/resource features — installed the way
VS Code users install any extension.

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
- `release.yml:78-80` already creates a GitHub Release on every tag via `gh release create …
  --generate-notes`, using the built-in `github.token` — the M1 distribution hook (§5) attaches to it.

## Design

### 1. Two milestones — distribution phases (DECIDED)

The milestones are defined by **how the extension is distributed**, not by feature scope (feature
scope is a separate axis, §2):

- **M1 — self-built, tester distribution.** We build the `.vsix` in CI and attach it to the GitHub
  release. A handful of invited testers install it from the release asset and give feedback. No
  Marketplace, **no publisher account, no PAT** — this rides the existing release job and the
  auto-provided `github.token` (§5). Purpose: validate the extension on real projects and real users
  before taking on any Marketplace commitment.
  - *Exit criteria:* testers can install and get working Java code intelligence; the first-run
    experience is understood (feeding the §7 work); the feature baseline (§2) is decided.

- **M2 — go all in.** Publish to the VS Code Marketplace and Open VSX for one-click, auto-updating,
  discoverable installs. This milestone takes on the publisher agreements, PAT management, the
  mirror-repo publishing Action (§5), and — as hard gates — the naming/trademark resolution (§6) and
  the first-run UX (§7). Purpose: reach and low-friction install for the public.
  - *Entry gates:* §6 name/publisher reserved and trademark-safe; §7 first-run nudge + walkthrough
    shipped (otherwise the unbundled-server trap generates avoidable 1-star reviews).

### 2. Feature scope — passthrough baseline, parity fills in

Feature scope grows independently of the distribution milestone. The **passthrough baseline** is what
M1 testers get on day one; the custom-command **parity** work lands incrementally and can span into
M2.

**Passthrough baseline (free via `vscode-languageclient`):** diagnostics, completion, hover, signature
help, definition/references/implementation, rename, formatting, semantic-token highlighting,
document/workspace symbols, folding, and (native LSP) type hierarchy. This is a genuinely full Java
code-intelligence experience and is small enough to verify by hand.

**Parity (the custom-command map).** Every capability the Neovim client implements as a Lua module has
a VS Code-native counterpart. The server contracts are unchanged; only the client rendering differs.
This is not a mechanical port — VS Code has richer, opinionated native UIs, so the glue is a rewrite
against VS Code APIs.

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

Test Explorer and debug are the two big rocks. AST-aware indentation is the one case that is *harder*
in VS Code than in Neovim, because it fights VS Code's built-in indentation model.

### 3. Launch model: reuse the cache launcher (DECIDED)

The extension spawns the existing cache launcher over stdio — the same target the Neovim client uses:

1. Resolve the launcher: `LATHE_SERVER_DIR` (dev override) → else
   `${LATHE_CACHE:-~/.cache/lathe}/current` → `/lathe-launcher.sh`.
2. If it is not executable, drive the first-run UX (§7) rather than failing silently.
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
**extension** is a thin launcher-locator that changes rarely. So the extension is distributed
separately and locates the already-cached server — no bundled-vsix staleness, no updater.

### 4. Source of truth: monorepo (DECIDED); mirror only when M2 needs it

The client source lives in the monorepo (`lathe-maven-plugin/src/main/vscode`). For **M1**, that is
all that is needed — the release-asset `.vsix` is built straight from that directory in CI (§5); no
mirror repo required yet.

For **M2**, a standalone `ag-libs/lathe.vscode` repo is added as a **generated one-way mirror**,
exactly as for the Neovim client (see
[Standalone `lathe.nvim` Plugin](../done/lathe-standalone-nvim-plugin.md) §1), because the Marketplace
publish runs from a mirror-repo Action (§5) and the listing's `repository` link wants a public repo.

**Why monorepo-source-of-truth.** The parity client (§2) co-evolves tightly with the server: every
custom command is a client↔server contract (command names, argument shapes, the `lathe/test*`
notifications) that changes on both sides together. Keeping the client in the monorepo means those
changes land in one commit and — critically — the client is testable against the **working-tree**
server *before* release (see End-to-end testing), which a standalone repo cannot do.

The mirror carries standalone-only overlay files kept in the monorepo under `dev/vscode-mirror`:
Marketplace `README.md`, `CHANGELOG.md`, `icon.png`, `.vscodeignore`, `LICENSE`, and the publish
GitHub Action (§5). The release semver is stamped into `package.json` `version` (the analogue of the
Neovim client's `version.lua`).

**Known cost — one-way mirror.** As with `lathe.nvim`, PRs opened directly against `lathe.vscode`
cannot merge back without re-authoring. Acceptable while there are no external contributors; the
escape hatch (flip the source of truth to the standalone repo, consumed via submodule/fetch) is the
same as documented for Neovim and stays explicitly reversible.

### 5. Distribution & publishing

#### M1 — release-asset `.vsix` (no PAT)

`release.yml` already creates a GitHub Release on tag with `github.token`, which **can attach assets
to its own repo** (the cross-repo limitation that forces a mirror-Action for the Marketplace does not
apply here). So M1 is a small addition to the existing release job:

```yaml
      - name: Package VS Code extension
        run: cd examples/vscode && npm ci && npx vsce package -o lathe.vsix
      - name: Create GitHub Release
        run: gh release create "$GITHUB_REF_NAME" --title "Lathe $GITHUB_REF_NAME" \
             --generate-notes examples/vscode/lathe.vsix
```

No new secret, no PAT, no mirror. Testers install from the release page:
```bash
code --install-extension ~/Downloads/lathe.vsix   # --install-extension takes a local path, not a URL
```
(or *Extensions → … → Install from VSIX* in the UI). `vsce package` requires a `publisher` field even
for a local package — use a placeholder (`"publisher": "ag-libs"`); missing `repository`/`README`/
`LICENSE` are warnings, not errors, so a local package still succeeds.

#### M2 — Marketplace + Open VSX via a mirror-repo Action (PAT isolated in CI)

Neither registry installs from git, so the publish is a token-authenticated upload, not a push. To keep
the monorepo secret-free, the publish runs as a **GitHub Action in the mirror repo**, and the local
script stays a no-PAT git push:

- **`publish-vscode.sh`** (monorepo, run after `release.sh` tags and the tag is pushed): a
  snapshot-per-release script modeled on `publish-nvim.sh` — tag worktree checkout, rebuild the mirror
  tree from `src/main/vscode` + `dev/vscode-mirror` overlays, stamp the semver into `package.json`,
  `--dry-run` support, and the "tag must be on origin first" guard. Pushes the snapshot + `vX.Y.Z` tag
  to `lathe.vscode`. **No PAT** — a cross-repo `git push` with the maintainer's own creds.
- **`.github/workflows/publish.yml`** (in the mirror, kept in the `dev/vscode-mirror` overlay so the
  snapshot rebuild does not wipe it): `on: push: tags: ['v*']` → `npm ci` → `vsce publish` +
  `ovsx publish`, using **`VSCE_PAT`** (Azure DevOps Marketplace) and **`OVSX_TOKEN`** (Open VSX)
  stored as **Actions secrets in the mirror repo**. Optionally a guard step asserts the tag matches
  `package.json`'s version (the analogue of the Neovim drift-guard on `version.lua`).

```
monorepo release (tag vX)
   └─ publish-vscode.sh  ── git push snapshot+tag ──▶  ag-libs/lathe.vscode
                                                          └─ Action (on tag)
                                                               ├─ vsce publish  → MS Marketplace
                                                               └─ ovsx publish  → Open VSX
```

**Publisher setup (one-time, out-of-band):** an Azure DevOps org, a PAT scoped *Marketplace → Manage*
across *all accessible organizations*, and a publisher created at `marketplace.visualstudio.com/manage`
whose id matches `package.json` `publisher`. For Open VSX: an Eclipse Foundation account, the signed
publisher agreement, a claimed namespace, and an `ovsx` token.

**Author-side drawbacks to accept at M2** (none apply to M1): the Microsoft Publisher Agreement + ToU
(binding, you are liable for content); PAT **expiry/rotation** (Azure DevOps PATs last ≤1 year — a
lapsed token silently fails publishes); MS-Marketplace ToU scoping to Microsoft products (VSCodium/
Cursor/Gitpod need Open VSX — a second registry + agreement); public install counts/ratings/reviews
and the support expectations they imply; takedown at Microsoft's discretion on trademark/DMCA/policy
complaints; and version immutability (a bad release is fixed forward, never overwritten).

### 6. Naming & trademark (M2 gate)

The trademark exposure is **"Java" (Oracle)**, not "Lathe" ("Lathe" is a generic tool word; its only
risk is collision with an existing extension, which is a discoverability issue, not a rules
violation — the unique key is `publisher.name`, so `ag-libs.lathe` is unique regardless).

Decided identity:

- **`name` (id):** `lathe` (fallback `lathe-java` / `lathe-lsp` if taken).
- **`publisher`:** `ag-libs`.
- **`displayName`:** `Lathe — Java` — nominative/descriptive use of "Java", the pattern Microsoft's
  "Extension Pack for Java" and Red Hat's "Language Support for Java" both use.
- **`description`:** leads with the keyword — *"Fast Java language server driven by your Maven build."*
- **`keywords`:** `java`, `maven`, `lsp`, `language-server` (drives "java" search discovery without a
  trademark-sensitive title).
- **`categories`:** `Programming Languages`, `Linters`, `Formatters`.
- **README trademark disclaimer:** *"Java is a registered trademark of Oracle and/or its affiliates.
  Lathe is an independent project, not affiliated with or endorsed by Oracle."* Nominative use + this
  disclaimer reduces the already-low Java risk to negligible.

Before M2: verify the `lathe` name and `ag-libs` publisher id are available on the Marketplace and
Open VSX, and check there is no confusingly-similar established "Lathe" extension in dev tooling.

### 7. First-run UX — the impatient-user defenses (M2 gate)

The failure mode is specific and, unaddressed, generates 1-star reviews: a Marketplace user installs,
opens a Java file, and **nothing happens** because no Maven build has populated `~/.cache/lathe` / the
project's `.lathe/`. This is the same trap the `lathe.nvim` design named ("install it, nothing
happens, uninstall"); on the Marketplace it is amplified (wider, less patient audience; public,
unremovable ratings). Silence is the enemy. Five layered defenses, strongest first:

1. **Turn the failure into one click.** On *Maven project + no `.lathe/`*, show an actionable warning
   with buttons — *"Run `mvn process-test-classes`"* (runs the build as a VS Code **Task** in the
   integrated terminal), *"Setup guide"*, *"Don't show again"*. Then **watch for `.lathe/` appearing**
   and auto-attach, so it lights up without a reload. Highest-impact fix.
2. **Native Walkthrough on install** (`contributes.walkthroughs`): a 3-step checklist — ① open a Maven
   project · ② run `mvn process-test-classes` (button) · ③ open a Java file — that opens automatically
   after install and sets expectations *before* the trap.
3. **Status bar item** for constant feedback: `Lathe: not set up → starting → indexing… → ready
   (N modules)`. Impatient users need to *see* progress, especially through the ~12s first-compile/
   index warmup (the same warmup behind the "empty findReferences on cold start" behavior).
4. **Progress during warmup:** wrap the first index/compile in `window.withProgress` — *"Lathe:
   indexing workspace…"* — so a legitimate 12s wait reads as working, not broken.
5. **Honest listing expectations:** the Marketplace README's first paragraph states the prerequisite
   plainly (*"Activates after a Lathe Maven build"*), so a rare bad review is unfair-and-rebuttable
   rather than deserved.

**Silence-is-never-acceptable rule:** every unattached state gets an actionable signal — *except*
genuinely non-Maven projects, where the extension stays silent so Lathe never nags on code it cannot
serve (same principle as the nvim nudge). Fire the nudge **once per root**, not per buffer.

M1 testers are the input to this section: their friction observations shape which of #1–#5 are
mandatory before M2 publish.

### 8. Protocol handshake (reuse the Neovim mechanism; M2)

Installed from a registry, the client can drift from the Maven-pinned server, so it reuses the existing
coarse-integer handshake rather than inventing one: the server already advertises
`capabilities.experimental.latheProtocol` (keyed by `LatheFlags.PROTOCOL_CAPABILITY`). The VS Code
client reads it on initialize and compares to a `PROTOCOL` constant it ships, warning on mismatch —
server older/absent → *"bump the `lathe-maven-extension` version and rebuild"*; server newer → *"update
the Lathe extension"*. The drift guard that keeps the Java constant and the client copy in lockstep is
extended to cover the VS Code client's copy. M1 (a fixed, self-built tester `.vsix` that ships close to
the server) does not need this; it is an M2 concern.

## End-to-end testing

The Neovim client establishes the pattern: `src/test/neovim/run-specs.sh` runs headless nvim against
the client, bound to the `integration-test` phase via exec-maven-plugin, and **hard-fails under CI but
skips gracefully when the binary is absent locally**. The VS Code analogue is `@vscode/test-electron`,
and testing splits into three tiers by how much of the stack each needs:

1. **Client-logic tests (no server).** Launcher resolution, `.lathe` root walking, the first-run
   nudge state machine (§7), `documentSelector` registration. Fast, run anywhere.
2. **Contract/smoke tests (stub launcher).** Point `LATHE_SERVER_DIR` at a fake `lathe-launcher.sh`
   that returns a canned `initialize` response; assert the client starts, initializes, and registers
   for Java. No real server.
3. **True E2E (real server + fixture).** Real diagnostics/completion on real Java, needing a **built
   server** in `~/.cache/lathe/current`, a **`.lathe/`** workspace from `lathe:sync`, and the **cache
   launcher** — all of which the monorepo already produces via the `multi-module` invoker fixture. A
   `@vscode/test-electron` run slots in beside `run-specs.sh` in `integration-test`, testing the
   **working-tree** server before release. CI needs `xvfb` (headless Electron is heavier than nvim).

The working-tree E2E of tier 3 is the reason the client lives in the monorepo (§4). A standalone repo
could reach tier 3 only via a **published-server bootstrap** (a sample project using the released
`lathe-maven-extension`, `mvn process-test-classes` to populate the cache, then run the E2E) — which
tests only the *released* server, lagging any unreleased contract change by a release. That is the
documented fallback if the source of truth is ever flipped, not the primary.

## Open Decisions

- **Multi-root / submodule-open behavior.** If a user opens a *submodule* folder rather than the
  reactor root where `.lathe` lives, do we walk up past the workspace-folder boundary to find `.lathe`
  (matching Neovim) or require opening the reactor root? Leaning toward walk-up for parity.
- **Feature baseline for M1 testers** — passthrough only, or passthrough plus one or two low-effort
  custom commands (missing-imports quick-fix, `createType`)? To decide when M1 starts.
- **Mirror repo name** for M2 (`ag-libs/lathe.vscode` assumed).

## Out of Scope

- Any change to server delivery. The server stays Maven-resolved and version-pinned; only a client is
  added.
- Jar-bundling the extension into the cache (§3 rejects it for VS Code specifically).
- Marketplace listing polish beyond a functional listing (screenshots, GIFs) — a promotion task, not
  part of client bring-up.

## Tests

- **M1:** client-logic unit tests (tier 1) for launcher resolution, root walking, and the first-run
  state machine; a stub-launcher smoke test (tier 2) asserting initialize + Java registration; a
  monorepo `@vscode/test-electron` E2E (tier 3) against the `multi-module` fixture asserting
  diagnostics/completion appear — CI-hard-fail / local-skip like `run-specs.sh`. Verify the
  release-asset `.vsix` builds in CI and attaches to the GitHub release.
- **M2:** per-command-family tests as parity lands (Test Explorer streaming from `lathe/test*`, a
  debug session via the DebugAdapter, the resources TreeView, `createType` `WorkspaceEdit`, the
  `missingImports` code action); handshake tests (extend the protocol drift guard to the VS Code
  client's `PROTOCOL` copy; matching → silent, older/newer → the respective message); first-run UX
  tests (Maven-but-no-`.lathe` → the one-shot nudge with the run-build action; non-Maven → silent;
  launcher missing → the install nudge).
- **Publishing:** `publish-vscode.sh` kept `--dry-run`-able (like `publish-nvim.sh` / `release.sh`) so
  the snapshot + version stamp can be inspected without pushing; the mirror Action is exercised on a
  real tag push, not unit-tested.
