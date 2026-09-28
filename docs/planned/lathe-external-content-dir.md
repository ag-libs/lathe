# Lathe — External content directory

Status: proposed.
Let a project redirect the bulky generated `.lathe/` mirror to a directory outside the working tree,
keeping `.lathe/` itself as a tiny in-tree marker holding only the workspace manifest, the build lock,
and local run configuration.
Opt-in, one knob, backward-compatible default.

## Motivation

An early adopter reported that Lathe writes a generated tree (`.lathe/`) into the working copy and
does not want *anything* generated living in a git repo, even gitignored.
Today `.lathe/` holds the full per-module mirror — compiled classes, generated sources, params, and
launch templates — which can be large and is pure build output.

The request is reasonable: `.lathe/` is build output, like `target/`, and a developer editing a
long-lived checkout should be able to keep the heavyweight part of it out of the tree.
This document adds an opt-in redirection while leaving the default (`.lathe/` in-tree) untouched.

## Non-goals

- **Not** relocating or renaming the `.lathe/` marker.
  `.lathe/` stays an in-tree directory so all workspace discovery is unchanged (see below).
- **Not** a deterministic `~/.cache/lathe/<project>` derivation.
  The location is configured explicitly, not computed from a boolean — a deliberate rejection of
  hidden `.cache` placement.
- **Not** an orphan garbage-collector.
  When a redirected content directory is abandoned (e.g. a deleted checkout) it is left in place; a
  future GC can reuse the ownership record this design introduces.
- **No** editor-client or MCP discovery changes.

## Why the marker stays in-tree

Workspace discovery keys on the `.lathe/` **directory** in several independent places:

- `LatheWorkspace.findRoot` walks up for a `.lathe/` directory.
- The editor client resolves the workspace root by the same `.lathe` marker (`ROOT_MARKER`).
- `LatheMcpServer` detects a configured project by the `.lathe/` directory at its cwd.
- The Maven extension (`LatheLifecycleParticipant`) creates and lock-heartbeats `.lathe/`, and reads
  it for the POM opt-out.

Keeping `.lathe/` a directory means **none of these change**.
Only the *content underneath* the marker is redirected, and when the redirection is unset it points
right back into `.lathe/` — today's exact layout.

## Layout

### `.lathe/` — in-tree, always (reactor-level marker and config)

- `workspace.json` — the manifest; now **mandatory** and the single source of truth for the content
  location (carries the `contentDir` pointer).
- `lathe.lock` — build/server coordination.
- `run.json` — local run configuration (`RUN_CONFIG_LOCAL_FILE`).

Unchanged and unrelated: `lathe-run.json` (the shared run config) already lives at the reactor root,
not inside `.lathe/`.

### `contentDir` — the generated mirror (per module)

- `<moduleRel>/classes`, `<moduleRel>/test-classes`, `<moduleRel>/generated-sources`
- `lsp-params-*.json`, `lsp-stamps-*.json`
- `test-launch.json`, `main-launch.json`

## The knob and the pointer

A single Maven property, mirroring `lathe.disabled` (usable as a POM property and overridable with
`-D`, where `-D` wins):

```xml
<properties>
  <lathe.content.dir>/absolute/or/${user.home}/relative-to-root</lathe.content.dir>
</properties>
```

- Constant `CONTENT_DIR = "lathe.content.dir"` in `LatheFlags`.
- Deliberately **not** named `lathe.dir`: that reads one letter from the existing
  `LatheLayout.LATHE_DIR` (`".lathe"`, the marker directory name) while meaning something unrelated.
- Orthogonal to `lathe.cache` / `LATHE_CACHE`, which govern the user-wide shared caches
  (`deps/`, `jdks/`, `servers/`, `logs/`); this property governs only this project's mirror.

The property is consumed in exactly **one** place — `lathe:init` — which resolves it to an absolute
path and writes it into `workspace.json.contentDir`.
Thereafter every writer (compiler, plugin) and reader (server) reads the location from
`workspace.json`, never from the environment.
This is why no environment-variable counterpart is needed: the readers are separate processes that
already consume `workspace.json`, not Maven properties.

### The `""` default

`workspace.json.contentDir`:

- `""` (default, property unset) → content lives in `.lathe/` — **exactly today's layout, no
  behavioral change**.
- a path → the mirror lives there instead (absolute, or resolved relative to the reactor root).

## The resolver — one split, marker vs content

Every current `workspaceRoot.resolve(LatheLayout.LATHE_DIR)` site is classified into one of two
groups:

| Purpose | Resolves to | Change |
|---|---|---|
| Marker / coordination — `findRoot`, "configured?" check, `lathe.lock`, `workspace.json`, `run.json` | `<root>/.lathe` | none |
| Generated content — per-module classes, gen-sources, params, stamps, launch templates | `contentDir` | rerouted |

The content group routes through a single helper:

```
contentDir(root, manifest):
  raw = manifest.contentDir()               // "" | path
  raw.isEmpty() ? root.resolve(".lathe")    // in-tree default
               : resolve(raw, root)         // absolute, or relative-to-root
```

Auditing each `resolve(LATHE_DIR)` call site into marker-vs-content is the bulk of the work; it is
mechanical but must be done deliberately.

### Decoupling `workspaceRoot` from the marker parent

`ModuleSourceConfig` currently derives `workspaceRoot = latheDir.getParent()` and walks a path up to a
segment literally named `.lathe`; `WorkspaceManifest.extractReactorModuleName` and `CompletenessGate`
similarly match the `.lathe` segment.
When content is external, module directories live under `contentDir`, whose segment name is **not**
`.lathe`, so these derivations break.

Fix: thread the already-known `workspaceRoot` and resolved `contentDir` explicitly (the server holds
both from `LatheEngine` construction) instead of re-deriving them from the path name.

## Ownership and the collision guard

Because `lathe.content.dir` is committed in the POM, two checkouts of the same project (notably git
worktrees for future MCP isolation) resolve to the **same** `contentDir` and would compile into one
directory.

Guard: `workspace.json` records its **owning reactor root** (the absolute path of the checkout it
belongs to), and `lathe:init` **refuses with a clear message** if the target `contentDir` already
holds a manifest owned by a different root.

- Git-free and cheap.
- Prevents two checkouts corrupting one store.
- The ownership record is exactly what a future orphan-GC would prune on.

A worktree that wants its own store overrides per-invocation with `-Dlathe.content.dir=` (empty),
which forces the in-tree `.lathe/` layout and self-cleans with the worktree.

## Worktree interaction (summary)

- **In-tree default** self-cleans: deleting a checkout/worktree removes its `.lathe/` content with it.
- **Redirected content** does not self-clean; the collision guard prevents corruption, and the
  ownership record enables a later GC.
- Recommended posture: redirect on the long-lived developer checkout; let ephemeral worktrees fall
  back to in-tree via the `-D` override.

## Footprint for the adopter

`.lathe/` shrinks from a full generated tree to three small files (`workspace.json`, `lathe.lock`,
`run.json`) and stays gitignorable.
The heavyweight per-module mirror leaves the repo entirely.
The only committed addition is the one `<lathe.content.dir>` property line — legitimate project
configuration, not generated output.

## Editor client and MCP impact

None for discovery.
`.lathe/` remains the marker; the client and MCP server locate the workspace exactly as today and read
`workspace.json` for structure — which now also tells them where content lives.

## Implementation slices

Each is its own commit, shown before committing.

1. **`workspace.json` schema + resolver.**
   Add `contentDir` (default `""`) and `owningRoot` to the manifest schema; add the `contentDir(root,
   manifest)` helper and `LatheFlags.CONTENT_DIR`; unit tests for the resolver (`""`, absolute,
   relative, missing). No call-site changes yet.
2. **Route content sites through the resolver.**
   Swap the content-group `resolve(LATHE_DIR)` sites; marker sites untouched. With `contentDir=""`
   behavior is identical — pure refactor.
3. **Decouple `workspaceRoot`.**
   Thread `workspaceRoot`/`contentDir` into `ModuleSourceConfig`, `WorkspaceManifest`,
   `CompletenessGate`; remove the name-based `.lathe` walk-ups.
4. **`init` writes the pointer + ownership; the guard.**
   Read `lathe.content.dir`, create the external dir, write `contentDir`/`owningRoot`; refuse on
   ownership mismatch.
5. **Tests + docs.**
   Invoker `external-content` project asserting the split layout and the guard; README + guide
   (`installation.md`, `how-it-works.md`) note the opt-in; move this doc to `docs/done/` when shipped.

## Testing

- Unit: resolver (`""`/absolute/relative/missing); guard (matching vs mismatching `owningRoot`).
- Invoker: a project with `lathe.content.dir` set — assert `.lathe/` holds only
  `{workspace.json, lathe.lock, run.json}`, the mirror is under the external dir, and a second root
  pointed at the same dir is refused.
- Regression: existing in-tree invoker projects must be byte-for-byte unchanged (`contentDir=""`).

## Risks

- **Dangling pointer** — a `workspace.json` whose `contentDir` was deleted: treat as not-built and
  prompt a sync, do not fail hard.
- **Non-portable committed path** — an absolute `lathe.content.dir` is machine-specific; document
  `${user.home}`-style interpolation or relative-to-root values for shared POMs.
- **Audit completeness** — the resolver split only works if every content site is reclassified;
  missing one silently writes to (or reads from) the wrong place. Slice 2 must enumerate them
  exhaustively.
