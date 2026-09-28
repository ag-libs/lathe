# Lathe — External content directory

Status: proposed.
Let a project redirect the bulky generated `.lathe/` mirror to a Lathe-generated directory under the
user cache, keeping `.lathe/` itself as a tiny in-tree marker holding only the workspace manifest, the
build lock, and local run configuration.
Opt-in via a single boolean, backward-compatible default.

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
- **Not** a user-chosen path.
  The user flips one boolean; Lathe computes the location under the user cache. A bespoke per-project
  path is out of scope — `lathe.cache` already relocates the whole cache root if needed.
- **Not** an orphan garbage-collector.
  When a generated content directory is abandoned (e.g. a deleted checkout) it is left in place; a
  future GC can reuse the ownership record this design introduces.
- **No** editor-client or MCP discovery changes, and **no** cross-language path derivation — the
  computed path is written into `workspace.json`, so only the Java build derives it.

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

A single boolean Maven property, mirroring `lathe.disabled` (usable as a POM property and overridable
with `-D`, where `-D` wins):

```xml
<properties>
  <lathe.content.cached>true</lathe.content.cached>
</properties>
```

- Constant `CONTENT_CACHED = "lathe.content.cached"` in `LatheFlags`.
- A boolean, not a path: **the user does not choose the location** — flipping it on tells Lathe to
  generate one. Portable across machines (no absolute path baked into a shared POM).
- Orthogonal to `lathe.cache` / `LATHE_CACHE`, which relocate the whole cache root;
  `lathe.content.cached` only decides *whether* this project's mirror lives under that cache versus
  in-tree.

The property is consumed in exactly **one** place — `lathe:init` — which, when true, computes the
content directory (below), creates it, and writes its **absolute path** into
`workspace.json.contentDir`.
Thereafter every writer (compiler, plugin) and reader (server) reads the location from
`workspace.json`, never re-deriving it.
This is the crux: because the computed path is persisted in the manifest, **only the Java build
derives it** — the server just reads the string, and the editor client needs nothing (it still finds
the root via the `.lathe/` marker). No environment-variable counterpart and no cross-language hash.

### The generated location

```
LatheLayout.projectContentDir(reactorRoot):
  userCacheRoot()/projects/<basename>-<shortHash(canonicalReactorRootPath)>
```

Readable and unique (e.g. `myapp-3f9c1a2b`), it sits beside the existing `deps/` and `jdks/` caches
and honors `lathe.cache` / `LATHE_CACHE` since it hangs off `userCacheRoot()`.
Because the id derives from the *canonical reactor-root path*, two checkouts of the same project
(git worktrees for future MCP isolation) get **distinct** directories automatically — no sharing, no
collision.

### The `""` default

`workspace.json.contentDir`:

- `""` (default, `lathe.content.cached` unset/false) → content lives in `.lathe/` — **exactly today's
  layout, no behavioral change**.
- an absolute path (written when `lathe.content.cached=true`) → the mirror lives there instead.

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
  raw = manifest.contentDir()               // "" | absolute path
  raw.isEmpty() ? root.resolve(".lathe")    // in-tree default
               : Path.of(raw)               // generated cache location
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

## Ownership record (no cross-checkout collision, but same-path reuse must be handled)

Because the location is derived from the *canonical reactor-root path*, two **different** checkouts
(including git worktrees for future MCP isolation) get **distinct** content directories automatically —
they cannot resolve to the same store, so there is no cross-checkout collision to guard against.

There is, however, one real staleness case a path-only id does **not** cover: deleting project A at
`/work/proj` and cloning project B at the same path resolves to the *same* `projects/<id>`, so B would
inherit A's stale mirror (mostly inert A-only modules, but leftover disk and confusing content).

So `workspace.json` records the **owning project identity** — the reactor `groupId:artifactId` (plus
the root path for the GC) — and `init` **clears and regenerates** the store when the recorded identity
differs from the current build's. That makes the record genuinely load-bearing (it catches same-path
reuse), rather than a path-only field that can never disagree. The path component remains the future
orphan-GC hook (prune `projects/<id>` whose recorded root no longer exists).

## Worktree interaction (summary)

- **In-tree default** self-cleans: deleting a checkout/worktree removes its `.lathe/` content with it.
- **External mode** auto-isolates per checkout (distinct `projects/<id>`), so worktrees never share a
  store; abandoned stores are left for a future GC (they do not self-clean).
- A worktree that prefers the self-cleaning in-tree layout overrides per-invocation with
  `-Dlathe.content.cached=false`.

## Footprint for the adopter

`.lathe/` shrinks from a full generated tree to three small files (`workspace.json`, `lathe.lock`,
`run.json`).
The heavyweight per-module mirror leaves the repo entirely.
The only committed addition is the one `<lathe.content.cached>` property line — legitimate project
configuration, not generated output.

To address the *git* half of the complaint regardless of mode, `lathe:init` also writes
`.lathe/.gitignore` containing `*` (self-ignoring), so the marker directory never shows up in
`git status`. This is cheap, independent of `lathe.content.cached`, and applies to the default in-tree
layout too.

Even so, this is **not** a zero-footprint tree: a small `.lathe/` directory remains. If an adopter's
bar is truly "nothing in the repo," see the deferred zero-footprint option below.

## Editor client and MCP impact

None for discovery.
`.lathe/` remains the marker; the client and MCP server locate the workspace exactly as today and read
`workspace.json` for structure — which now also tells them where content lives.

## Implementation slices

Each is its own commit, shown before committing.

0. **Measure first.**
   Size a real `.lathe/` on helidon/dropwizard. If the mirror is small, the disk win is marginal and
   the `.gitignore` line (below) may be the whole answer — decide before investing in the slices.
1. **`workspace.json` schema + resolver + derivation.**
   Add `contentDir` (default `""`) and the owning-project identity (`groupId:artifactId` + root path)
   to the manifest schema; add the `contentDir(root, manifest)` helper,
   `LatheLayout.projectContentDir(root)`, and `LatheFlags.CONTENT_CACHED`; unit tests for the resolver
   (`""`, absolute, missing) and the derivation (distinct ids for distinct roots). No call-site changes
   yet.
2. **Route content sites through the resolver.**
   Swap the content-group `resolve(LATHE_DIR)` sites; marker sites untouched. With `contentDir=""`
   behavior is identical — pure refactor.
3. **Decouple `workspaceRoot`.**
   Thread `workspaceRoot`/`contentDir` into `ModuleSourceConfig`, `WorkspaceManifest`,
   `CompletenessGate`; remove the name-based `.lathe` walk-ups.
4. **`init` computes + writes the pointer and ownership; auto-`.gitignore`.**
   When `lathe.content.cached=true`, compute `projectContentDir`, create it, write the pointer and
   owning identity into `workspace.json`, and clear+regenerate on identity mismatch; when false/unset,
   write `""`. In both modes, write `.lathe/.gitignore` (`*`).
5. **Dogfood + tests + docs.**
   Flip `lathe.content.cached=true` on the lathe repo and confirm the nvim run/debug/test flows against
   the cached mirror. Invoker `external-content` project asserting the split layout; README + guide
   (`installation.md`, `how-it-works.md`) note the opt-in; move this doc to `docs/done/` when shipped.

## Testing

- Unit: resolver (`""`/absolute/missing); `projectContentDir` (distinct ids for distinct roots, stable
  for the same root, honors `lathe.cache`).
- Invoker: a project with `lathe.content.cached=true` — assert `.lathe/` holds only
  `{workspace.json, lathe.lock, run.json}` and the mirror is under `~/.cache/lathe/projects/<id>`.
- Regression: existing in-tree invoker projects must be byte-for-byte unchanged (`contentDir=""`).

## Risks

- **Dangling pointer** — a `workspace.json` whose `contentDir` was deleted: treat as not-built and
  prompt a sync, do not fail hard.
- **Abandoned stores** — deleted checkouts leave `projects/<id>` behind until a future GC; the recorded
  root path is the hook that GC will prune on.
- **Audit completeness** — the resolver split only works if every content site is reclassified;
  missing one silently writes to (or reads from) the wrong place. Slice 2 must enumerate them
  exhaustively.
- **Loss of self-containment** — the mirror no longer travels with the checkout; a moved repo or a
  fresh-`$HOME` CI environment rebuilds. Acceptable (a rebuild regenerates), but a behavioral change to
  call out in the docs.

## Deferred: zero-footprint via `pom.xml` discovery

This design keeps a small in-tree `.lathe/` and therefore does **not** achieve zero repo footprint.
That is a deliberate scope choice favoring low blast radius and "works for me first" — the default is
byte-for-byte today's behavior, so shipping it cannot break an existing setup.

A truly zero-footprint mode is possible and is the genuine competitive wedge against jdtls (whose users'
top complaint is exactly in-tree pollution, and which itself discovers projects from `pom.xml`, not an
IDE marker). It would:

- discover the reactor root from the **highest `pom.xml`** (like jdtls) instead of the `.lathe/` marker;
- attach to Maven projects broadly (server reports "not built — run sync" until the cache exists),
  or detect the `lathe-maven-extension` in the POM;
- move **everything** — `workspace.json`, `lathe.lock`, `run.json`, and the mirror — under the cache,
  keyed by the canonical root path.

Notably, this needs **no cross-language hash**: the client only needs the root (from `pom.xml`), never
the content location, which the server computes from the root in Java. The real cost is migrating
*discovery* (`findRoot`, client root, MCP, "configured?" checks) off the `.lathe/` marker, which is a
larger, riskier change to the exact surface that "works for me" depends on.

Recommended posture: ship the in-tree opt-in first, prove it on our own repo, and only then decide
whether to pursue zero-footprint as a positioning bet — reusing this design's resolver split,
`workspaceRoot` decoupling, and derivation, which all still apply.
