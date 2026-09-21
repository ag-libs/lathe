# Lathe — Type Hierarchy Explorer (`:LatheTypeHierarchy` + Telescope)

## Status

Done. Resolves [NV-6](../gaps/gaps-archive.md) (type hierarchy shows only one level in Neovim);
verified live against a large workspace. No standard LSP endpoint changes: the lazy
`prepareTypeHierarchy` / `typeHierarchy/{supertypes,subtypes}` handlers stay for the built-in one-level
use; this adds a Lathe-specific "show me everything" surface.

Two KISS simplifications were taken against §5 during implementation:

- **No bespoke entry record.** The command returns
  `TypeHierarchyExplorerResult(supertypes, self, subtypes, truncated)` where each list holds plain LSP
  `TypeHierarchyItem`s (reusing the resolver's existing entry→item mapping). The client tags each row by
  *which group it came from*, so a per-entry `relation`/`depth` field was unnecessary.
- **Fallback is the in-house `lathe.pick`** (the zero-dependency fuzzy picker `:LatheNew` already uses),
  not `vim.ui.select` — a consistent fuzzy feel with or without Telescope, and DRY.

---

## 1. Goal

One command — the Neovim analogue of IntelliJ's **Ctrl-H** — that, for the type under the cursor,
shows its **entire** hierarchy in a single [Telescope](https://github.com/nvim-telescope/telescope.nvim)
picker: all transitive **supertypes** (up to `Object`) and all transitive **subtypes**, merged into one
flat, fuzzy-searchable list, each entry tagged by its relation to the anchor type, with jump-to-source on
select.

This is the "closest to what a user expects" reading of "show all possible hierarchy": not a lazy
one-level drill-down, but the whole inheritance neighbourhood at once.

---

## 2. Current State

- Lathe advertises `typeHierarchyProvider` and implements the standard lazy endpoints
  (`prepareTypeHierarchy`, `typeHierarchy/supertypes`, `typeHierarchy/subtypes`). Each `subtypes`/
  `supertypes` call returns **one level**, and every returned item carries its own re-resolution data
  (`TypeHierarchyItemData{ binaryName, routingUri }`), so the graph is walkable to arbitrary depth.
- Neovim's built-in `vim.lsp.buf.typehierarchy('subtypes'|'supertypes')` renders **one level** in the
  location list and does not recurse. The shipped Lathe Neovim client (`lua/lathe/…`) adds no
  type-hierarchy UI of its own, so users see only direct sub/supertypes (NV-6).
- The transitive data already exists server-side: `WorkspaceTypeIndex.transitiveSubtypes(binaryName)`
  (used today by Find References and completion) and `directSupertypes(binaryName)` (walkable upward).

---

## 3. Prior Art

- **IntelliJ Ctrl-H** — "Type Hierarchy" tool window: the anchor type with its supertype chain above and
  its subtype tree below, both directions, fully expandable, up to `Object`. This is the target UX.
- **jdtls** exposes the same standard lazy endpoints Lathe does (one level per call), **and** ships a
  legacy custom `java/typeHierarchy` request taking a *direction* and a *resolve depth* that returns a
  multi-level tree in **one round-trip** — which is what vscode-java's tree view is built on.
  The lesson: the eager "whole hierarchy at once" experience is exactly the case where a single custom
  command beats N lazy round-trips, and it is the shape both IntelliJ and vscode-java use.
- **nvim-jdtls** ships no type-hierarchy UI, so jdtls Neovim users hit the same one-level built-in limit.

### Why a custom command rather than client-side recursion

For a single direction at shallow depth, recursing the standard `subtypes` endpoint client-side is fine
and needs no server change. For **both directions, full depth, eagerly** — the Ctrl-H requirement — that
becomes a 2-way fan-out of N round-trips through the server's single worker thread, with the recursion,
dedup, and cycle-guard logic living in Lua. The server already holds the full graph in memory, so it can
compute the merged, deduped, tagged set in one pass. One request, thin client. This mirrors the
jdtls/vscode-java architecture and keeps the "no ad-hoc Java in the client" rule intact.

---

## 4. User-Facing Behavior

`:LatheTypeHierarchy` on a type (or a symbol whose type resolves) opens a Telescope picker:

```
▲ supertype   AbstractAdapter   com.example.core
▲ supertype   Object            java.lang
● self        Adapter           com.example.adapter
▼ subtype     DefaultAdapter    com.example.adapter
▼ subtype     HttpAdapter       com.example.adapter.http
▼ subtype     FileAdapter       com.example.adapter.file
```

- **One list, both directions**, so a single command answers "what is this type's whole family?"
- Each row is **tagged** (`▲` supertype / `●` self / `▼` subtype), so the up/down a tree would show is
  preserved without a tree widget.
- The fuzzy-search text (`ordinal`) is the **fully-qualified name**, so typing `http` filters to
  `HttpAdapter`; the tag glyph and package are display-only.
- `<CR>` jumps to the type's declaration (reactor / dependency / JDK source, wherever `TypeSourceLocator`
  can resolve it). A type with no resolvable source is still listed (informational) and selection
  notifies rather than jumps.
- **Ordering:** supertypes nearest-ancestor → `Object`, then self, then subtypes by BFS depth
  (alphabetical within a depth). Stable and predictable.
- **Bounded:** above a node cap the result carries a `truncated` flag and the picker shows a
  `… (N more, truncated)` line — never a silent cut.

Behaviour for common anchors:

| Cursor target | Result |
|---|---|
| A class/interface name | Its full supertype chain + all transitive subtypes |
| A `new Foo()` / variable whose type is `Foo` | Anchored on `Foo` (resolve the type, then the hierarchy) |
| A `final` class with no subtypes | Supertypes + self only |
| `java.lang.Object` (or a huge marker interface) | Capped, `truncated` surfaced |
| A primitive / `var` with no denotable type | No result (clean message) |

---

## 5. Technical Design

### 5.1 Server — new custom `executeCommand`

Mirror the existing `lathe.instantiations` path (a position-anchored, workspace-wide query returning
locations).

- **Command constant** in `LatheWorkspaceService`:
  `static final String TYPE_HIERARCHY_COMMAND = "lathe.typeHierarchy";`
- **Advertise** it in `LatheLanguageServer.createCapabilities()`'s `ExecuteCommandOptions` list.
- **Dispatch** in `LatheWorkspaceService.executeCommand(...)` to a private `typeHierarchy(params)` that
  deserializes the argument as a standard `TextDocumentPositionParams` (the client's
  `make_position_params()`, same as `instantiations`) and delegates to
  `textDocumentService.typeHierarchyExplorerFuture(uri, position)`.

### 5.2 Server — resolution and graph walk

`WorkspaceSession.typeHierarchyExplorerFuture(uri, pos)` (mirrors `instantiationsFuture`):

1. One INFO log line for the user action, timed (this is a workspace-graph query — heavy enough to time,
   per the logging rules).
2. Resolve the type under the cursor to a `binaryName`, reusing the `prepareTypeHierarchy` resolution
   (which already yields a `TypeHierarchyItemData{ binaryName, … }`). If the cursor resolves to a value,
   fall back to its declared type's element (same "resolve to a type element" step as type navigation).
   No text parsing — javac attribution + the type index only.
3. **Subtypes (down):** `indexSnapshot.transitiveSubtypes(binaryName)` — the flat descendant set already
   available.
4. **Supertypes (up):** a new bounded walk `WorkspaceTypeIndex.transitiveSupertypes(binaryName)` — recurse
   `directSupertypes` from the anchor upward with a visited-guard (interfaces form a DAG → dedup), until
   no further indexed supertype is found (the chain terminates at `Object`, or at the first supertype not
   present in any shard). This is the one genuinely new piece of index logic; it is the mirror of the
   existing `transitiveSubtypes` traversal.
5. Build a **merged, deduped, tagged, ordered** list. Locate each entry's source via the existing
   `TypeSourceLocator` (nullable location). Apply a node cap; set `truncated` when exceeded.

### 5.3 Server — result shape

A small record returned as the command result (not a bare `List<Location>`, because entries need a
relation tag and may lack a location):

```java
public record TypeHierarchyEntry(
    String name,          // simple name
    String packageName,   // for display / disambiguation
    String uri,           // nullable — null when no source is locatable
    Range range,          // nullable — paired with uri
    Relation relation,    // SUPERTYPE | SELF | SUBTYPE
    int depth) { … }      // distance from the anchor (0 = self)

public record TypeHierarchyResult(List<TypeHierarchyEntry> entries, boolean truncated) { … }
```

Records carry compact constructors validating invariants and defensively copying the list, per the
project record rules.

### 5.4 Client — `lua/lathe/typehierarchy.lua`

A thin picker shell; no Java knowledge.

1. `:LatheTypeHierarchy` → `workspace/executeCommand{ command = 'lathe.typeHierarchy', arguments = { make_position_params() } }`.
2. On the result, open a Telescope picker: entry `display` = `<glyph>  <SimpleName>  «package»`,
   `ordinal` = fully-qualified name; an optional file previewer showing the declaration; `<CR>` →
   `vim.lsp.util.jump_to_location` (or notify when `uri` is nil). Append the `… (N more, truncated)`
   line when `result.truncated`.
3. **Telescope is a soft dependency:** `pcall(require, 'telescope…')`; if absent, fall back to
   `vim.ui.select` (or the location list). The command works without Telescope, just less nicely.
4. Suggested `<leader>`-keymap documented in the Neovim cheatsheet, alongside the built-in
   `typehierarchy('subtypes'|'supertypes')` (which stays for the lazy one-level view).

### 5.5 Reuse map

| Concern | Reused component |
|---|---|
| Command wiring / dispatch | `LatheWorkspaceService` (mirror `lathe.instantiations`) |
| Capability advertisement | `LatheLanguageServer.createCapabilities()` |
| Position-anchored future | `WorkspaceSession` (mirror `instantiationsFuture`) |
| Cursor → type resolution | existing `prepareTypeHierarchy` path / `TypeHierarchyItemData` |
| Transitive subtypes | `WorkspaceTypeIndex.transitiveSubtypes` |
| Transitive supertypes | **new** `WorkspaceTypeIndex.transitiveSupertypes` (mirrors the subtypes walk) |
| Source location | `TypeSourceLocator` |
| Telescope picker | new `lua/lathe/typehierarchy.lua` |

---

## 6. Required Changes

| Change | File | Notes |
|---|---|---|
| Command constant + dispatch + arg parse | `LatheWorkspaceService.java` | mirror `instantiations` |
| Advertise command | `LatheLanguageServer.java` | add to `ExecuteCommandOptions` list |
| Position-anchored future | `WorkspaceSession.java` | `typeHierarchyExplorerFuture`, timed INFO log |
| Transitive supertype walk | `WorkspaceTypeIndex.java` | `transitiveSupertypes` + visited-guard |
| Result records | `analysis/` | `TypeHierarchyEntry`, `TypeHierarchyResult` (compact ctors) |
| Client picker | `lua/lathe/typehierarchy.lua` (new) + command registration | Telescope soft dep |
| Docs | Neovim cheatsheet, NV-6, `design-index.md` | command + keymap; resolve NV-6 |

---

## 7. Test Plan

Read at least two neighbouring tests before writing new ones (per the testing rules).

**Server** (mirror the `instantiations` / `WorkspaceTypeIndex` test patterns; real fixtures, no Mockito
in the index tests):

- `WorkspaceTypeIndexTest.transitiveSupertypes_multiLevelDag_dedupesAndTerminates` (positive) /
  `transitiveSupertypes_cycleGuard_terminates` (edge).
- `typeHierarchy_anchorWithAncestorsAndDescendants_returnsMergedTaggedSet` (positive — supertypes +
  self + subtypes, correct relations/order).
- `typeHierarchy_leafFinalClass_returnsSupertypesAndSelfOnly` (edge).
- `typeHierarchy_primitiveOrVar_returnsEmpty` (negative).
- `typeHierarchy_exceedsNodeCap_setsTruncated` (boundary).
- `createCapabilities_typeHierarchyCommand_advertised` (capability).

**Client** (`typehierarchy_spec.lua`; stub `workspace/executeCommand` + Telescope):

- `latheTypeHierarchy_populatesPickerWithTaggedEntries` (positive — glyphs, FQN ordinal).
- `latheTypeHierarchy_select_jumpsToLocation` / `…_noSourceEntry_notifies` (positive/edge).
- `latheTypeHierarchy_noTelescope_fallsBackToUiSelect` (fallback).
- `latheTypeHierarchy_truncated_showsMoreLine` (boundary).

Run focused first, then broaden:

```bash
mvn -pl lathe-server -Dtest=WorkspaceTypeIndexTest,TypeHierarchyExplorerTest test
mvn -pl lathe-server test
mvn spotless:apply          # immediately after any Java edit
```

---

## 8. Non-Goals

- **No standard-endpoint change.** The lazy `prepareTypeHierarchy` / `subtypes` / `supertypes` handlers
  are untouched; the built-in one-level Neovim view keeps working. This command is additive.
- **No tree widget.** Telescope is a flat picker; structure is conveyed by relation tags, not
  expand/collapse. (A future tree client — e.g. VS Code — can consume the same command and render its own
  tree; the payload is structure-bearing via `relation`/`depth`.)
- **No member/override hierarchy** (that is call hierarchy / method-level, out of scope).
- **No client-side Java logic.** All resolution and graph walking stay server-side.
- **No silent truncation.** A capped result is always labelled.

---

## 9. Resolved Decisions

1. **Both directions merged in one list**, tagged by relation — the "all possible hierarchy" reading.
2. **Include the full JDK ancestor chain** up to `Object`, matching IntelliJ Ctrl-H completeness. A
   "stop at the project boundary" filter is a later toggle if the `Object`/`Serializable` rows prove
   noisy.
3. **Custom command over client recursion**, because the requirement is eager, both-directions, full
   depth — the case where one server call clearly wins and matches jdtls/vscode-java.
4. **Telescope is a soft dependency** with a `vim.ui.select` fallback, consistent with how Lathe treats
   nvim-ufo / vim-illuminate as recommended-not-required.

## 10. Open Questions

1. **Anchor from a value vs. a type.** When the cursor is on a value (not a type name), resolve to its
   declared type's element first (reusing the type-navigation "to type element" step), or require the
   cursor to be on a type? Conservative first cut: resolve the type when javac gives a denotable declared
   type; otherwise no result.
2. **Node cap value.** Pick a default that covers real hierarchies (Helidon/Dropwizard) without stalling
   the picker; measure before fixing the constant.
3. **Dependency-source jumps.** Entries whose declaration lives in a dependency may or may not have
   extracted sources; list them regardless, and jump only when `TypeSourceLocator` resolves a location
   (same policy as existing source-based navigation).
