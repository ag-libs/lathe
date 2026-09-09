# Lathe — `:LatheNew` Ergonomics Rethink (v4)

Status: proposed.
Revises the shipped `:LatheNew` flow (CQ-0055 v3) after real-world friction in a large
multi-module reactor.
Tracked by the `:LatheNew` gap (NV area).

This document is the authoritative "why/how" for the redesign.
The shipped v1/v2/v3 history stays in the CQ-0055 gap entry as the record of what exists today;
the sections below supersede its interaction model.

## Motivation — why rethink a shipped command

`:LatheNew` works well for the anchored common case (a class in the current package),
but it breaks down in a large workspace with many modules and many packages.
The observed failure: creating a file that landed at a module's source root **with no `package`
declaration, in the wrong directory** — which, in a JPMS module, then corrupts the whole module
mirror (see the WS gap on module-mirror corruption).

### Root cause of the scale failure

The shipped flow is: pick Kind, then type a single free-text target `[module:]package.Name` with
`vim.ui.input` completion.
Three properties of that field combine badly at scale:

1. **Package is typed, never picked.**
   With many packages the user cannot recall exact names, and the field relies on
   `vim.ui.input` completion — which silently does nothing in most non-native input backends
   (dressing / snacks / noice).
   So the user is left retyping long `module:package` strings from memory.

2. **An empty package silently means "default package."**
   When the target parses to an empty package (`_parse_target` finds no `:` and no anchor),
   `createType(pkg="")` renders the file at the module's source root with no `package` line
   rather than refusing an under-specified location.

3. **Only the module is ever offered as a picker.**
   `resolve_and_submit` pops a module `vim.ui.select` when the module is unresolved,
   but the package is never a picker — so the one field most in need of filtering at scale
   is the one the UI never assists with.

### Divergence from the v3 design

CQ-0055 v3 specified *progressive `vim.ui.select` pickers*
(Kind → Module → Package → Name), each a short, filterable list.
The implementation collapsed that into a single completable text field.
That collapse is the regression this rethink reverses.

## Design invariants

Any redesign must hold these, independent of the chosen flow:

1. **Never silently accept an under-specified location.**
   An empty package must be a deliberate, explicit choice (a rare "default package" option),
   never a fallthrough.

2. **Filter, don't recall.**
   At scale the user fuzzy-filters, never retypes identifiers.
   That means `vim.ui.select` (which inherits the user's telescope / fzf-lua / snacks picker),
   not `vim.ui.input` completion.

3. **Show the destination before creating.**
   The resolved `module / root / package` is visible so a wrong target is caught up front,
   not discovered afterward.

## Approaches considered

Three interaction models satisfy the invariants.
They differ in how the destination is selected.

### Approach A — Progressive fuzzy pickers (recommended)

Kind → Module → Package → Name, each a fuzzy `vim.ui.select` list, module and package
preselected from the current buffer, module step skipped when there is only one.
No free-text location.

```
:LatheNew
▸ Kind:     [Class] Interface Record Enum Test
▸ Module:   fuzzy> ba⏎          (skipped when only 1; anchor preselected)
              core
            > batch             (← buffer anchor)
▸ Package:  fuzzy> util⏎
            > com.example.app.batch.util
              ＋ New package…
              ⇄ test root
▸ Name:     FakeClock▮
```

Anchored common case: Enter, Enter, type name.
This is the model that realizes the v3 design and makes package a picked, filterable step,
so the big-reactor failure cannot recur.

### Approach B — One flat fuzzy destination picker

Kind → a single fuzzy picker over every `module / root / package` destination
(scope shown per row) → Name.
One search across the whole reactor instead of drilling down.

```
:LatheNew → Kind: Class
▸ Destination (fuzzy over all):
   fuzzy> batch.util⏎
   batch › test › com.example.app.batch.util
   batch › main › com.example.app.batch.util
   core  › main › com.example.app.util
   ＋ New package…
▸ Name: FakeClock▮
```

Fewest steps; scope is visible in the row.
The trade-off is a large flat list (fuzzy matching absorbs it) and a "new package" sub-flow.
CQ-0055 v3 warned about materializing a big module's whole package set at once;
for a one-shot picker that is acceptable, but it argues against making B the default over A's
lazy per-module fetch.

### Approach C — Anchor-first confirm

Kind → the destination resolved from the current buffer, shown for one-key accept,
or "change" to drop into the A/B picker → Name.
Fastest when already near the target.

```
:LatheNew → Kind: Class
Destination: batch / main / com.example.app.batch
   ⏎ accept    c change    t → test root
Name: FakeClock▮
```

C is best understood as a fast path layered on A: A's anchor-preselect already gives most of C's
benefit, so the recommendation is **A with anchor-preselect**, treating C's explicit
confirm-or-change as an optional refinement rather than a separate model.

### Recommendation

**Approach A (progressive fuzzy pickers) with buffer anchor-preselect.**
It is the only model that makes package a picked, filterable step (invariant 2),
keeps the anchored case at two Enters, and cleanly absorbs the special kinds below as
"same pickers, fewer steps."

## Scope selection (main vs test)

Scope is inferred by default (from the anchor or an existing package) but must be
selectable — the missing capability behind "I couldn't create a class in the test root."

- **Interactive:** offer a `⇄ test root` / `⇄ main root` toggle in the package step, and
  surface a two-item main/test pick when a module has both roots and scope is not fixed by
  an anchor (the pick CQ-0055 v3 specified but never shipped).
- **Dual-root packages:** when a package exists under both roots, the choice is explicit via
  the toggle/pick — never the current non-deterministic "first entry wins."

## Special kinds — `module-info` and `package-info`

Both are special compilation units, not types, so they cannot go through the generic type path:
the current `SourceVersion.isIdentifier` name check rejects the hyphen, and a
`public class …{}` skeleton is wrong for them.
They reuse the destination picker but **drop the steps that do not apply**, so each is fewer
decisions than a class.

### `package-info`

- Flow: Kind → module → package (the same destination picker) → **create; no name step**
  (the file name is fixed).
- Skeleton: `package <pkg>;` with a javadoc placeholder; caret in the javadoc.
- Scope selectable (test packages get a `package-info` too).
- Refuse if it already exists.
- It is "the class flow minus the name prompt."

### `module-info`

- Flow: Kind → **module only** (skip-when-one; offer only modules that lack a
  `module-info.java`, only the main root) → **one confirmable name** → create at the source root.
- No package step: it lands at the source root by definition.
- Skeleton: `module <name> {\n\n}\n`; caret in the body.
- It is "the class flow with package + name replaced by a single module-name confirmation."

### Deriving the module name

`module <name>` is a real JPMS name, not derivable from the hyphenated filename, so
`module-info` is the one special kind that keeps a seeded, editable name prompt.
Derive the default, cheapest-first:

1. the module's **base package** — the longest common package prefix of its existing sources
   (the JPMS convention: module name = root package, e.g. `com.example.app.batch`);
2. fall back to a normalized **artifactId** when the module has no sources yet.

Show it prefilled; the user hits Enter or edits.
Fast, but never a silent guess (invariant 1).

### Relationship to module-mirror corruption

A proper `:LatheNew module-info` is the affordance that stops a modular project from ending up
with a stray default-package class corrupting the module — the concrete tie-in with the WS gap
on module-mirror corruption.

## Typed accelerator

Keep the free-text target only as a hardened, optional power-user / scriptable path,
never the default:

```
:LatheNew class test:batch:com.example.app.batch.util.FakeClock
```

- Grammar extends to `[scope:][module:]package.Name`; `<Tab>` completes `main:` / `test:`,
  then that root's packages.
- It **rejects an empty package** — `:LatheNew class Foo` no longer falls through to the source
  root; it errors or drops into the picker.
- Whether to retain it at all is an open decision (see below); the pickers are the primary path
  regardless.

## Server surface impact

The existing executeCommand surface (`lathe.modules`, `lathe.packages`, `lathe.resolveContext`,
`lathe.createType`) already models lazy per-node discovery and is largely sufficient.
Changes:

- **`lathe.createType`** gains the two special file kinds and must route them around the
  identifier check (fixed file names, dedicated skeletons, tailored placement:
  `package-info.java` in the package dir, `module-info.java` at the source root).
- **Empty-package handling** becomes an explicit, validated case rather than a silent
  source-root placement.
- **`TypeKind`** (or a parallel file-kind) extends with `PACKAGE_INFO` and `MODULE_INFO`;
  the client `KINDS` list mirrors it.
- Module-name derivation (base package → artifactId) is server-side, exposed through
  `lathe.createType` (or a small companion query) so the client only seeds the prompt.

The client (`new.lua`) shrinks: the single completable target field and its cache give way to
progressive `vim.ui.select` pickers driven by the existing queries, plus the hardened typed
accelerator if retained.

## Open decisions

1. **Flow model** — A (recommended), B, or C.
2. **Typed accelerator** — keep hardened, or drop for a single picker-only path.
3. **`module-info` name prompt** — always confirm, or auto-accept the derived name when
   unambiguous.

## Relationship to other work

- **Supersedes** the interaction model in CQ-0055 v3 (shipped `:LatheNew`); the server surface
  from v3 mostly stands.
- **Distinct from** [New Type Creation via Snippet Completion](lathe-new-type-creation.md), a
  deferred, editor-agnostic snippet approach with no client UI — a different mechanism for the
  same goal, kept as an alternative.
- **Pairs with** the WS gap on module-mirror corruption (a `module-info` affordance for modular
  projects).
