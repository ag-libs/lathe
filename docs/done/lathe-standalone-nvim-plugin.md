# Lathe — Standalone `lathe.nvim` Plugin

> **Status: shipped.** Delivered: the standalone `ag-libs/lathe.nvim` mirror published by the local,
> no-PAT `publish-nvim.sh`; the three documented install paths; the first-run readiness nudge (§6);
> double-load detection (§6); the `serverInfo` + `version.lua` `{VERSION, PROTOCOL}` schema (§2); and
> the `LATHE_PROTOCOL` handshake enforcement (§2/§3) — `LatheFlags.LATHE_PROTOCOL` advertised via
> `capabilities.experimental.latheProtocol`, the client `on_init` comparison, and the drift-guard test
> keeping the Java constant and `version.lua` in lockstep.

## Goal

Publish the Neovim Lua client as a standalone, plugin-manager-installable repository
(`github.com/ag-libs/lathe.nvim`) so that Neovim users can install Lathe the way they install every
other plugin — and so the client becomes discoverable through the ecosystem's distribution channels
(dotfyle, awesome-neovim, `store.nvim`, lazy/vim.pack).

This is **additive, not a replacement**.
The current zero-plugin delivery — where `lathe:sync` unpacks the client into
`~/.cache/lathe/current/neovim` and the user points `lazy.nvim`'s `dir` at it — is a deliberate,
valued property: a fresh checkout plus one Maven build yields a working editor with no separate
plugin to install or keep in sync. We keep that path as a first-class option and add the standalone
repo alongside it, letting each user pick the model they prefer.

## Motivation

The single worst first-time friction is a chicken-and-egg:
the Lua client lives at a cache path (`~/.cache/lathe/current/neovim`) that **does not exist until a
Maven build runs**, so the standard "install the plugin, restart Neovim" flow cannot work, and the
client cannot appear on any plugin-discovery surface because it is not a real plugin repository.

Solving this also feeds promotion: a standalone repo is the prerequisite for listing on dotfyle,
awesome-neovim, and `store.nvim` — the channels verified live as of September 2026.

Note: this does **not** apply to the server. The server is resolved as a Maven dependency, pinned to
the `lathe-maven-extension` version, and needs a build to exist at all; that coupling is correct
(the server always matches the project's real classpath) and stays. Only the client is decoupled.

## Current State

- The client is pure Lua under `lathe-maven-plugin/src/main/neovim/`, already in a valid Neovim
  runtime layout (`lua/`, `ftplugin/java.lua`, `after/indent/java.lua`).
- It is zipped by `src/assembly/neovim.xml` into `META-INF/lathe/lathe-neovim.zip`, then
  `ServerInstaller.installNeovim()` (`ServerInstaller.java:108-146`) unzips it into
  `~/.cache/lathe/servers/<version>/neovim`, with the `current` symlink pointing at the active
  version (`ServerInstaller.java:249-259`).
- The client discovers the launcher via `LATHE_SERVER_DIR` or `cache_root()/current/lathe-launcher.sh`
  (`lathe.lua:43-46`) and already reports an actionable message when the launcher is missing
  (`lathe.lua:119-126`).
- Client and server versions are guaranteed to match **only because they ship together**. There is
  no version or protocol negotiation today: `initialize()` returns bare capabilities with no
  `serverInfo` (`LatheLanguageServer.java:59-60`).

## Design

### 1. Source of truth stays in the monorepo (DECIDED)

**Decision:** `lathe-maven-plugin/src/main/neovim/` remains the authoritative working tree; the
standalone `lathe.nvim` repo is a **published mirror**, not a fork. This preserves the
`LATHE_NVIM_DIR` dogfood loop (the working-tree client is live in the editor), keeps the client
versioned alongside the server contract it depends on, and keeps the zero-plugin cache path working
by construction (`ServerInstaller` bundles whatever is in the working tree).

**Why this over flipping the direction** (making `lathe.nvim` the source of truth and having the
monorepo consume it via submodule/fetch): with no external adopters yet, contribution friction is
not a real cost today, and the monorepo-as-truth model is strictly simpler for the maintainer and
dogfood loop. Flipping would only pay off once we actively court community contributions to the
client — see the reversibility note below.

A release step (§5) rebuilds the mirror tree from that directory and pushes it to `ag-libs/lathe.nvim`,
overlaying the files a standalone repo needs but the monorepo does not:

- `README.md` — install snippet, feature list, screenshots/GIFs.
- `doc/lathe.txt` — Vim help (`:help lathe`).
- `LICENSE`.
- `lua/lathe/version.lua` — the `{VERSION, PROTOCOL}` schema, with the release semver stamped in (§2).

The source directory is already a valid plugin root, so the rebuild is near-mechanical. The mechanism
(local, snapshot-per-release, no PAT) is specified in §5.

**Known cost — one-way mirror:** because the mirror is generated from the monorepo, PRs opened
directly against `lathe.nvim` cannot merge back without manual re-authoring. This is acceptable while
there are no external contributors. If/when client contributions become a goal, the escape hatch is
to **flip the source of truth** — make `lathe.nvim` (and a future `lathe-vscode`) the real repos and
have the monorepo consume the client via git submodule or fetch-at-build. That flip still preserves
both the zero-plugin bundle and the `LATHE_NVIM_DIR` dogfood loop (they just point at a submodule),
at the cost of submodule ceremony. Keep this decision explicitly reversible; do not build tooling
that assumes the mirror direction is permanent.

### 2. Version signal + protocol handshake (DECIDED — shipped)

The standalone client (installed from git) and the server (Maven-pinned) can drift, so the full
handshake is now wired (it was originally split by timing; both halves have since shipped):

- The **server** sets `serverInfo{name: "lathe", version}` in `InitializeResult` — good LSP hygiene
  regardless of the standalone work; the version comes from the jar manifest
  (`Implementation-Version`, null outside a built jar, which is acceptable).
- `lua/lathe/version.lua` ships the **fixed schema** `{ VERSION = <stamped>, PROTOCOL = <int> }`.
- The **server** advertises `capabilities.experimental.latheProtocol` (keyed by
  `LatheFlags.PROTOCOL_CAPABILITY`); the **client** reads it in `on_init` and compares to its own
  `PROTOCOL`, warning per server (re)start on a mismatch:
    - server older/absent → *"bump the `lathe-maven-extension` version in your build and rebuild"* —
      the server version is pinned by that extension, so re-syncing alone reinstalls the same one.
    - server newer → *"update lathe.nvim (`:Lazy update` / `vim.pack.update` / git pull)"*.

  A matching protocol — always true on the bundled cache path, where client and server ship together —
  is silent.

**Why keep `PROTOCOL` a distinct integer rather than derive it from the version.** Two dead ends make
the separate coarse integer the correct shape:

- *Deriving from semver MAJOR fails during beta.* Per `release.sh`, 0.x is the beta line and MAJOR
  stays `0` through every breaking change until 1.0 — exactly the phase the contract churns most, so a
  MAJOR-derived protocol never moves when it would matter.
- *Comparing full versions fights the design.* Independent cadence is the whole point of decoupling;
  client 0.5.0 + server 0.4.0 is fine when nothing broke. Warning on every version skew trains users
  to ignore the warning. The coarse integer exists precisely to separate "versions differ but
  compatible" (common → silent) from "contract actually broke" (rare → warn).

`PROTOCOL` is the **only** hand-bumped number — the semver is auto-stamped at release — and it changes
only on a breaking client↔server contract change (executeCommand names, `init_options` shape, custom
notifications such as `lathe/sync`).

### 3. Single authoritative protocol constant + drift guard (shipped)

`LATHE_PROTOCOL` is defined once in Java (`LatheFlags`), `lua/lathe/version.lua` carries a matching
checked-in value, and a `lathe-maven-plugin` unit test (`LatheProtocolDriftTest`) reads the Lua file
and asserts equality so the build fails if the two ever drift. `PROTOCOL` is the only hand-bumped
number — bump it (in both places, enforced by the guard) only on a breaking client↔server contract
change; that commit is the coordinated release that ships both sides.

### 4. Delivery — three install paths from one artifact

The standalone repo is a single git artifact that every modern install path consumes. The built-in
manager (Neovim 0.12+ `vim.pack`) installs any git URL directly, so publishing the repo unlocks all
three at once:

| Path | Who it's for | Install |
|------|--------------|---------|
| **Built-in `vim.pack`** (Neovim 0.12+) | Users who want no third-party manager at all — rides the built-in-manager trend | `vim.pack.add({ 'https://github.com/ag-libs/lathe.nvim' })` |
| **Plugin manager** | Users on `lazy.nvim` / others, and the discovery channels | `{ 'ag-libs/lathe.nvim' }` |
| **Bundled (cache)** | Users who want zero separate plugin management; the current default | `lazy.nvim` `dir = ~/.cache/lathe/current/neovim`, populated by `mvn process-test-classes` |

`ServerInstaller` keeps bundling and unpacking the client for the cache path. The standalone repo is
published in addition. All three resolve the launcher through the same `LATHE_SERVER_DIR` / `current`
logic, so a user on any path gets identical runtime behavior.

**Why the repo matters regardless of which manager wins:** `vim.pack` adds installation-from-a-URL but
adds *zero discovery* — Neovim has no built-in plugin index. Discovery is entirely out-of-band
(awesome-neovim, dotfyle, neovimcraft, store.nvim), and every one of those channels keys off a public
GitHub repo. So a standalone public repo is the discovery prerequisite; the built-in manager only
makes the lowest-friction *install* possible once that repo exists.

**Semver tags feed every path.** `vim.pack`'s `version = vim.version.range(...)`, `lazy.nvim`'s
`version = "*"`, and the discovery channels all key off release tags — so the publish step (§5) must
push a `vX.Y.Z` tag to the mirror, not just update a branch.

Guidance for users who install more than one way (e.g. a standalone install *and* the bundled `dir`):
document that they should pick one to avoid loading the client twice; the standalone spec should win
if present.

### 5. Publishing — local, no PAT (DECIDED)

**Decision:** publish the mirror from a **local script** (`publish-nvim.sh`), mirroring how
`release.sh` already works — not a GitHub Action. Rationale: `release.sh` needs zero credentials
because it only commits/tags locally and the maintainer pushes with their own already-authenticated
git identity; CI then does the Maven Central half with secrets that already live in the repo. A
cross-repo push from an Action, by contrast, would require a stored **PAT or deploy key** on the
monorepo (the built-in `GITHUB_TOKEN` is scoped to the running repo and cannot push to
`ag-libs/lathe.nvim`). Keeping the push local means **no PAT, no deploy key, nothing to rotate** — the
same trust model as pushing the monorepo release tag by hand.

Mirror model: **snapshot-per-release**, not a `git subtree split` history graft. The script rebuilds
the mirror tree from the monorepo source each release, which is simpler, deterministic, and — unlike a
subtree split — can add the standalone-only files a split can't. (Subtree-split history preservation is
the rejected alternative; it buys no user-visible benefit for a generated one-way mirror and
complicates adding files.)

`publish-nvim.sh` (run locally, after `release.sh` tags and the tag is pushed):

1. Preconditions (clean tree, `vX.Y.Z` tag exists, plain semver) — same guards as `release.sh`.
2. `git clone git@github.com:ag-libs/lathe.nvim` into a temp dir (SSH = maintainer's own creds).
3. Rebuild the tree: wipe tracked files, copy `lathe-maven-plugin/src/main/neovim/*` in.
4. Overlay the standalone-only files kept as templates in the monorepo
   (`dev/nvim-mirror/{README.md, doc/lathe.txt, LICENSE}`) and stamp the semver into
   `lua/lathe/version.lua` (`PROTOCOL` is already correct in-tree and was validated by the drift-guard
   test during `mvn verify`).
5. Commit `release vX.Y.Z`, tag `vX.Y.Z`, show the diff, prompt `[y/N]`, then push branch + tag over
   SSH.

Properties: reproducible from monorepo + tag; reversible to an Action later (the same script could run
in CI with a deploy key) without changing the split/stamp logic.

Docs:

- `docs/guide/editors/neovim.md` gains the three-way install matrix (built-in `vim.pack`, plugin
  manager, cache-`dir`) with a one-line "which should I pick?".
- `docs/guide/installation.md` is reordered so "install the client" and "run the Maven build" read as
  independent steps for the standalone path.

### 6. First-run UX: unsynced project + double-load (DECIDED)

The standalone path **introduces a failure mode the bundled path cannot have.** With the cache `dir`,
having the client at all implies the Maven build already ran (that is what unpacked it). Installed from
a marketplace, that implication is gone: a user can open a Maven Java project that has never been
`lathe:sync`-ed, and today `root_dir` finds no `.lathe` marker, logs to `lsp.log` (invisible), and
silently never attaches — the "install it, nothing happens, uninstall" trap.

**Decision — ship a one-shot, context-aware nudge in this slice** (all checks are client-side
file-existence lookups via `vim.fs.root`, not Java parsing):

| Situation | Detect | Response |
|-----------|--------|----------|
| Java + `.lathe` + launcher present | `.lathe` found | attach — unchanged happy path |
| **Java + Maven (`pom.xml` up-tree) + no `.lathe`** | `.lathe` absent, `pom.xml` present | **notify once per root**: *"Maven project detected but no `.lathe/` — run `mvn process-test-classes` (or `:LatheSync`) to enable Lathe."* |
| Java + no `pom.xml` (not Maven) | both absent | stay **silent** — Lathe genuinely doesn't apply; surface only in a future `:checkhealth` |
| Launcher missing entirely | `executable(launcher) ~= 1` | notify: *"Lathe server not installed — run `mvn process-test-classes` in a Lathe project."* |

Constraints: fire **once per root** (a per-root guard table, not per-buffer spam), and stay silent for
non-Maven projects so Lathe never nags on code it cannot serve (respecting the existing "silent for
irrelevant projects" behaviour).

**Double-load detection (DECIDED — active check).** If both the standalone repo and the cache `dir`
are on `runtimepath`, `require('lathe')` silently resolves to whichever is first in rtp order and
shadows the other. Double `setup()` itself is harmless (the augroup is `clear=true`, commands
overwrite); the real hazard is **silent version shadowing** — updating one copy while the other wins,
then debugging the wrong version. `setup()` checks
`vim.api.nvim_get_runtime_file('lua/lathe/version.lua', true)`; if more than one match, it emits one
`vim.notify` naming the paths and telling the user to keep only one. ~4 lines, and reliable because
both copies ship the same `version.lua`.

## Open Decisions

None outstanding. Repo/org confirmed as `github.com/ag-libs/lathe.nvim`; double-load and first-run UX
resolved in §6; protocol timing resolved in §2.

## Resolved Decisions

- **Source of truth: monorepo** (§1) — `lathe.nvim` is a published one-way mirror; explicitly
  reversible if client contributions ever become a goal.
- **Version signal + protocol handshake, shipped** (§2/§3) — `serverInfo`, the `{VERSION, PROTOCOL}`
  `version.lua` schema, the server's `experimental.latheProtocol`, the client `on_init` comparison,
  and the drift guard all landed. `PROTOCOL` is a distinct coarse integer (not derived from semver).
- **Publishing: local `publish-nvim.sh`, no PAT** (§5) — no CI cross-repo secret; snapshot-per-release
  mirror, not a subtree-split history graft.
- **Install paths: three from one artifact** (§4) — built-in `vim.pack`, plugin manager, and bundled
  cache; the standalone repo is the discovery prerequisite regardless of manager, and releases push
  semver tags to feed every channel.
- **First-run UX + double-load: active in this slice** (§6) — one-shot Maven-unsynced nudge, silent
  for non-Maven, plus active double-load detection.

## Out of Scope

- `:checkhealth lathe`, auto-`setup()` (making `config` optional), and a Windows launcher — the other
  install-friction items, deferred to their own slices.
- A protocol **range** (server advertising `[min..max]` so a newer server keeps serving old clients) —
  not needed yet; the shipped handshake is a coarse equality check. Add it only when backward
  compatibility across a protocol bump is actually required.
- Any change to server delivery. The server stays Maven-resolved and version-pinned.

## Tests

- Server test asserting `InitializeResult` exposes `serverInfo{name, version}`, and one asserting
  `capabilities.experimental.latheProtocol`.
- Client spec (headless Lua harness under `src/test/neovim/`) asserting `version.lua` has the
  `{VERSION, PROTOCOL}` schema.
- Drift-guard unit test (`LatheProtocolDriftTest`) asserting `LatheFlags.LATHE_PROTOCOL` equals
  `version.lua`'s `PROTOCOL`.
- Client handshake spec (`handshake_spec.lua`): matching → silent; older/absent → the
  bump-`lathe-maven-extension` message; newer → the update-plugin message.
- Client specs for the first-run nudge (§6): Maven-but-no-`.lathe` → the one-shot notify fires once;
  non-Maven → silent; launcher missing → the install notify.
- Client spec for double-load detection: two `version.lua` on `runtimepath` → one notify; single copy
  → silent.
- Verify the bundled cache path still installs and runs unchanged (existing invoker smoke coverage).
- `publish-nvim.sh` is a local release step, not CI-tested; keep it dry-run-able (like
  `release.sh --dry-run`) so the split/stamp can be inspected without pushing.
