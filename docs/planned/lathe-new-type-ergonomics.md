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

### What actually failed — not "typed vs pickers"

CQ-0055 v3 specified progressive `vim.ui.select` pickers; the implementation shipped a single
completable text field instead.
The field is not wrong *because* it is typed — it fails for three fixable reasons:

- its completion runs through `vim.ui.input`, which silently no-ops in most backends
  (dressing / snacks / noice), so at scale the user types blind;
- an **omitted package silently becomes the default package**, so the file lands at the source root;
- the **name is folded into the location** (`package.Name`), so a partial entry is ambiguous.

So the fix is not "go back to pickers." It keeps the fast typed command and repairs those three, and
adds a guided pick only for the one case typing genuinely cannot serve — no buffer context.

## Design invariants

1. **The package never defaults by omission.**
   It must resolve from an explicitly typed package or the buffer context; if neither yields one, the
   flow guides the user to a location — it never creates at the source root.
   The default package is reachable only by explicitly asking for it.
   This is the rule that kills the original bug at the parse layer.

2. **Completion must be reliable.**
   The typed path uses **native command-line completion** (which works regardless of the user's
   `vim.ui.input` backend — the shipped field's fatal flaw); the guided path uses `vim.ui.select`
   (inheriting telescope / fzf-lua / snacks). Neither relies on `vim.ui.input` completion.

3. **Show the destination before writing.**
   The name prompt's label shows the resolved `module / scope / package`, so a wrong target is caught
   before the file is created.

## Mechanism

`:LatheNew` stays a **typed command with native command-line completion**, not a picker chain.
There are two entry points, split by the 80/20 of real use, plus one rule that removes every
ambiguity: **the type name is always its own final prompt — never part of the argument.**

### 80% — anchored (context) path

You are in a file, adding a sibling. No location argument:

```
:LatheNew class          → name: class in batch / main / com.example.app.batch: ▮
```

The current buffer's **module, scope, and package** fill the location; the only prompt is the
**name**, and its label shows the resolved destination (the "show before you write" check).
If the buffer has no resolvable context, this falls into the guided path below.

### Typed path — explicit location

You know exactly where it goes, or it is elsewhere:

```
:LatheNew class core:test:com.example.core.util     → name: … : ▮
```

The argument is **location only** — `[<module>:][<scope>:]<package>`, `scope ∈ {main,test}`:

- `<module>` and `<package>` identify the target; `<scope>` defaults to `main`, `test:` opts in.
- `main`/`test` are recognised as the scope only in a leading colon-segment; a package literally named
  `test` sits in the package slot (`main:test`), and a module named `main`/`test` is disambiguated
  against the known module list.
- `<Tab>` completion is **position-aware**: modules at the first segment, `main`/`test` after a module
  colon, packages after the scope — so the typed path is discoverable, not "great if you memorised the
  tree."

A typed location must resolve **on its own**; context does not partial-fill it (see the rule below).
Then the name prompt.

### 20% — guided path ("somewhere new")

You are starting something new and would rather select than type. Bare `:LatheNew` (no kind):

```
:LatheNew                → kind → module → package or ＋New package… → scope? → name
```

Each step is a `vim.ui.select` (skip-when-one, context-preselected), with `＋ New package…` for a
brand-new package (a `vim.ui.input` seeded from the module's base package).
This is the **only** place a picker is used, and it is also where a no-context anchored/typed
invocation lands for its missing pieces.

### The resolution rule (kills the original bug at the parse layer)

- The **name** is always prompted — the argument is location-only, so there is no `package.Name`
  split and no "is `Foo` a package or a name?" ambiguity.
- **Context fills the location only when no location is typed.** Any typed token means the location
  stands on its own; context never partial-fills a typed target.
- The **package must resolve** from a typed package *or* context. If neither yields one, the flow
  **guides** — it never creates at the source root. The **default package** is reachable only by
  explicitly asking for it, never by omission.

| You run (context = `batch` / main / `com.example.app.batch`) | Location | Then |
|---|---|---|
| `:LatheNew class` | context → `batch` / main / `com.example.app.batch` | name prompt |
| `:LatheNew class batch:test:com.example.app.batch` | typed → `batch` / **test** / same pkg | name prompt |
| `:LatheNew class core:com.example.core.util` | typed → `core` / main / `…util` | name prompt |
| `:LatheNew class` *(no file open)* | unresolvable | → guided path |
| `:LatheNew` | — | fully guided |

## Scope (main vs test)

Selectable, never a silent default that lands you in the wrong root:

- **Typed:** the `test:` / `main:` keyword in the location (default `main`).
- **Guided:** a scope step, surfaced only when the chosen module has both roots and no context settles
  it — the pick CQ-0055 v3 specified but never shipped.
- **Dual-root packages** are resolved explicitly by the keyword or the step — never the current
  non-deterministic "first entry wins."

## Special kinds — `module-info` and `package-info`

Both are special compilation units, not types, so they cannot go through the generic type path:
the current `SourceVersion.isIdentifier` name check rejects the hyphen, and a
`public class …{}` skeleton is wrong for them.
They reuse the destination picker but **drop the steps that do not apply**, so each is fewer
decisions than a class.

### `package-info`

- Flow: resolve the destination the same three ways (context / typed `[module:][scope:]package` /
  guided) → **create; no name prompt** (the file name is fixed).
- Skeleton: `package <pkg>;` with a javadoc placeholder; caret in the javadoc.
- Scope selectable (test packages get a `package-info` too).
- Refuse if it already exists.
- It is "the anchored flow minus the name prompt."

### `module-info`

- Flow: resolve the **module** only (context, typed `module:`, or guided — skip-when-one, offering
  only modules that lack a `module-info.java`, main root) → **one confirmable module name** (seeded) →
  create at the source root.
- No package step: it lands at the source root by definition.
- Skeleton: `module <name> {\n\n}\n`; caret in the body.
- It is "the anchored flow with package + type name replaced by a single module-name confirmation."

### Deriving the module name

`module <name>` is a real JPMS name, not derivable from the hyphenated filename, so
`module-info` is the one special kind that keeps a seeded, editable name prompt.
Derive the default, cheapest-first:

1. the module's **base package** — the longest common package prefix of its existing sources
   (the JPMS convention: module name = root package, e.g. `com.example.app.batch`);
2. fall back to a normalized **artifactId** when the module has no sources yet.

Show it prefilled; the user hits Enter or edits.
Fast, but never a silent guess.

### Relationship to module-mirror corruption

A proper `:LatheNew module-info` is the affordance that stops a modular project from ending up
with a stray default-package class corrupting the module — the concrete tie-in with the WS gap
on module-mirror corruption.

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

The client (`new.lua`) keeps a **location parser** (`[module:][scope:]package`, name excluded), a
**name prompt** that shows the resolved destination, **position-aware command-line completion**, and a
**guided `vim.ui.select` fallback** for the no-context / bare-invocation path — all driven by the
existing `lathe.modules` / `lathe.packages` / `lathe.resolveContext` queries.
The always-default-package-by-omission behaviour is removed.

## Decisions (settled)

1. **Model** — a typed command (anchored/context path + explicit-location path) as the primary, with a
   guided `vim.ui.select` fallback for the 20% no-context / "somewhere new" case. Not a picker-first
   flow.
2. **Name** — always a final prompt; the location argument never carries the type name.
3. **Context** — fills the location only when no location is typed; it never partial-fills a typed
   target.
4. **Package** — never defaults by omission; unresolved → guided; the default package only on explicit
   request.
5. **Scope** — `main`/`test` keyword in the typed location (default `main`), or the guided scope step
   when a module has both roots and no context settles it.
6. **`module-info` name** — a seeded, editable prompt (base package → artifactId).

## Relationship to other work

- **Supersedes** the interaction model in CQ-0055 v3 (shipped `:LatheNew`); the server surface
  from v3 mostly stands.
- **Distinct from** [New Type Creation via Snippet Completion](lathe-new-type-creation.md), a
  deferred, editor-agnostic snippet approach with no client UI — a different mechanism for the
  same goal, kept as an alternative.
- **Pairs with** the WS gap on module-mirror corruption (a `module-info` affordance for modular
  projects).
