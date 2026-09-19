# Lathe — Standalone `lathe.nvim` Plugin

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

### 1. Source of truth stays in the monorepo; publish via subtree split

`lathe-maven-plugin/src/main/neovim/` remains the authoritative working tree. This preserves the
`LATHE_NVIM_DIR` dogfood loop (the working-tree client is live in the editor) and keeps the client
versioned alongside the server contract it depends on.

A release step git-subtree-splits that directory and pushes it to `ag-libs/lathe.nvim`, adding the
files a standalone repo needs but the monorepo does not:

- `README.md` — install snippet, feature list, screenshots/GIFs.
- `doc/lathe.txt` — Vim help (`:help lathe`).
- `LICENSE`.
- `lua/lathe/version.lua` — generated, stamping the release semver and protocol (see §3).

The source directory is already a valid plugin root, so the split is near-mechanical.

### 2. Decoupling requires a version handshake (the new abstraction)

Because the standalone client (installed from git) and the server (Maven-pinned) can now drift, we
add a coarse **`LATHE_PROTOCOL`** integer, bumped only on breaking client↔server contract changes
(executeCommand names, `init_options` shape, custom notifications such as `lathe/sync`) — **not** on
every release.

- The **server** advertises it via `capabilities.experimental.latheProtocol` in `InitializeResult`,
  and additionally sets `serverInfo{name: "lathe", version}`.
- The **client** embeds its own `PROTOCOL` (from `version.lua`), reads the server's value in
  `on_init`, and on mismatch shows exactly one actionable `vim.notify`:
    - server older than the client (or absent) → *"Lathe server is out of date; run
      `mvn process-test-classes`."*
    - server newer than the client → *"Update lathe.nvim (`:Lazy update` / git pull)."*

The user-facing check lives client-side (the client owns UI). The server logs the client-reported
protocol at `FINE` for diagnostics only.

The bundled cache-delivery path (§4) is immune to drift by construction — client and server ship in
the same version directory — so the handshake is a safety net specifically for the decoupled path,
which is exactly what justifies adding it.

### 3. Single authoritative protocol constant + drift guard

`LATHE_PROTOCOL` is defined once in Java (`LatheFlags`). `lua/lathe/version.lua` carries a matching
checked-in value. A `lathe-server` (or `lathe-maven-plugin`) unit test reads the Lua file and asserts
equality, so the build fails if the two ever drift. The release process stamps the semver into
`version.lua`.

### 4. Dual delivery — both paths supported

| Path | Who it's for | Install |
|------|--------------|---------|
| **Bundled (cache)** | Users who want zero separate plugin management; the current default | `lazy.nvim` `dir = ~/.cache/lathe/current/neovim`, populated by `mvn process-test-classes` |
| **Standalone repo** | Users who expect plugins to come from a plugin manager, and the discovery channels | `{ 'ag-libs/lathe.nvim' }` in any plugin manager |

`ServerInstaller` keeps bundling and unpacking the client for the cache path. The standalone repo is
published in addition. Both resolve the launcher through the same `LATHE_SERVER_DIR` / `current`
logic, so a user on either path gets identical runtime behavior.

Guidance for users who install both (standalone plugin *and* the bundled `dir`): document that they
should pick one to avoid loading the client twice; the standalone spec should win if present.

### 5. Docs and build

- `docs/guide/editors/neovim.md` gains a standalone install snippet alongside the existing cache-`dir`
  snippet, framing them as two supported models with a one-line "which should I pick?".
- `docs/guide/installation.md` is reordered so "install the client" and "run the Maven build" read as
  independent steps for the standalone path.
- A release GitHub Action (preferred) or a `dev/` script performs the subtree split, stamps
  `version.lua`, and pushes to `ag-libs/lathe.nvim`.

## Open Decisions

1. **Publish mechanism** — release GitHub Action (recommended) vs. a manual `dev/` script run at
   release time.
2. **Repo name/org** — assumed `github.com/ag-libs/lathe.nvim` (matches `io.github.ag-libs`);
   confirm.
3. **Double-load guardrail** — whether the client should actively detect it was loaded from both the
   cache `dir` and a standalone install and warn, or leave it to documentation.

## Out of Scope

- `:checkhealth lathe`, auto-`setup()` (making `config` optional), and a Windows launcher — the other
  install-friction items, deferred to their own slices.
- Any change to server delivery. The server stays Maven-resolved and version-pinned.

## Tests

- Unit test asserting `LATHE_PROTOCOL` (Java) equals the value in `lua/lathe/version.lua` (drift
  guard).
- Server test asserting `InitializeResult` exposes `capabilities.experimental.latheProtocol` and
  `serverInfo`.
- Client handshake tests (busted/mini.test or the existing Lua test harness) covering: matching
  protocol → no notification; server older/absent → the `mvn process-test-classes` message; server
  newer → the update-plugin message.
- Verify the bundled cache path still installs and runs unchanged (existing invoker smoke coverage).
