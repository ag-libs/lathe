# Lathe — `:LatheNew` Ergonomics (v5)

Status: shipped (v5).
The Neovim client (`lua/lathe/new.lua` + `lua/lathe/pick.lua`, verified by `new_spec.lua`) resolves
the destination with a **single built-in fuzzy picker** instead of a colon-grammar location string:
kind → fuzzy `module · scope · package` pick → a validated type-name prompt. The type name is always
a final prompt, the package never defaults by omission, and `module-info` / `package-info` are special
kinds. This document is the authoritative "why/how"; it supersedes the v4 interaction model below.

## Motivation — why rethink v4

v4 replaced the original free-text target with a typed `[module:][scope:]package` location plus
native command-line completion. It fixed the source-root-corruption bug, but two frictions remained:

1. **The colon grammar is a serialized tree.** `core:test:com.example.util` asks the user to encode
   module + scope + package into one token with separators and precedence rules (`main`/`test` only
   as a leading colon-segment, a module named `main` disambiguated against the list, …). It is
   powerful but not memorable, and discoverable only once you start typing.

2. **`vim.ui.select` is not fuzzy without a plugin.** The guided fallback used `vim.ui.select`, which
   is only a fuzzy picker if the user has a `ui-select` adapter (`telescope-ui-select`, fzf-lua's
   `register_ui_select`, snacks). **Telescope alone does not override `vim.ui.select`.** Without an
   adapter it falls back to the builtin numbered `inputlist` — and on a real reactor that is *548
   packages, pick a number*. Unusable, and dependent on the user's config.

The core realization: the requirement is **fuzzy filtering with the option list always visible**, and
that must hold **regardless of installed plugins**. Neither the colon grammar (fuzzy but no visible
list until Tab) nor `vim.ui.select` (visible list, fuzzy only with an adapter) delivers it alone.

## Design — a built-in fuzzy picker

`:LatheNew` drops the location grammar. The destination is chosen in **`lathe.pick`**, a small
self-contained fuzzy picker: a floating prompt over a results list, filtered live with Neovim's
builtin `vim.fn.matchfuzzypos` as you type, matched characters highlighted, `<C-n>`/`<C-p>` (or
arrows) to move, `<CR>` to select, `<Esc>` to cancel. It does **not** go through `vim.ui.select`, so
the fuzzy experience is identical for every user — Telescope, fzf-lua, snacks, or a bare Neovim.
`matchfuzzypos` is a core function (Vim 8.2 / Neovim 0.6+); there is no dependency.

### The flow

```
:LatheNew
  1. KIND       fuzzy pick: Class · Interface · Record · Enum · Annotation · Test ·
                package-info · module-info   (skipped when passed: `:LatheNew class`)

  2. WHERE      fuzzy pick over every `module · scope · package` row (context floated to the top,
                ＋ New package… right behind it). Type one word to narrow 548 → a few; a `· test`
                row puts the new type in the test root.

  3. NAME       validated identifier prompt, label shows the resolved `module / scope / package`.
                (Test seeds `<Stem>Test`; package-info: no name; module-info: none.)
```

### Entry points

- **Guided** — bare `:LatheNew`: kind pick → destination pick → name. The picker always opens (with
  the buffer's module/package floated to the top), so "somewhere else" is one fuzzy word.
- **Anchored** — `:LatheNew <kind>` in a Java file: the buffer's module/scope/package fill the
  destination and the flow goes **straight to the name** (no picker). Falls back to the destination
  pick when the buffer has no resolvable context.
- **Typed** — `:LatheNew <kind> <pkg>`: a package word, fuzzy-completed on the command line with
  builtin `matchfuzzy` (a real subsequence match regardless of the user's picker or `wildmode`), then
  the name. Scope follows the kind; the module follows the package (buffer's module preferred on a
  cross-module name clash). A package the reactor doesn't know routes to the destination picker rather
  than a source-root default.

### Invariants (carried over from v4)

- **The name is always its own final, validated prompt** — the argument never carries the type name,
  so there is no `package.Name` split and no "is `Foo` a package or a name?" ambiguity. An invalid
  identifier is re-asked in the client instead of surfacing as a raw server error.
- **The package never defaults by omission** — it resolves from the picked/typed package or the buffer
  context; the default package is reachable only by explicitly typing it into `＋ New package…`. A file
  is never created at the source root.
- **Scope rides on the destination**, not the kind — a package is listed once per scope it exists in,
  so picking the `· test` row places a plain class (fixture, base class, helper) in the test root. The
  kind only biases the ordering (a Test floats test packages up) and forces test scope for a JUnit
  test.

### `＋ New package…`

A pinned row in the destination picker: choose the module (context floated, skip-when-one), type the
package (seeded from the module's base package), and choose the scope **only when the module has both
roots** — so a brand-new *test* package is reachable (the v4 gap where a guided new package always
landed in main). An empty entry is the deliberate default-package choice; only a cancel aborts.

## Special kinds — `module-info` and `package-info`

Both are special compilation units, not types (the `SourceVersion.isIdentifier` name check rejects the
hyphen and a `public class …{}` skeleton is wrong), so they reuse the destination resolution but drop
the steps that do not apply.

### `package-info`

Resolve the destination the same three ways (context / typed / picker) → **create; no name prompt**
(the file name is fixed). Skeleton `package <pkg>;` with a javadoc placeholder; caret in the javadoc.
Scope selectable (test packages get one too). Refuse if it already exists. It is "the anchored flow
minus the name prompt."

### `module-info`

Resolve the **module** only (context, or a fuzzy module pick — skip-when-one) → create at the **main**
source root. No package, no scope, no name prompt. Skeleton `module <name> {\n\n}\n`; caret in the
body. The JPMS `module <name>` is **derived** from the module's base package (the longest common
package prefix of its main sources — JPMS convention: module name = root package), so there is no
prompt; the only fallback is a module with no derivable base package, which asks for the name.

## Client structure

- **`lathe.pick`** — the reusable built-in fuzzy picker (`items`, `format`, `title`, `on_choice`);
  `matchfuzzypos` filtering, floating prompt + results, no `vim.ui.select`.
- **`lathe.new`** — the flow: kind (arg or pick) → destination (context anchored / typed / picker) →
  name. Keeps the flat destination collector (`collect_destinations`, shared by the picker and the
  cmdline completion cache), destination ordering/typed-resolution, and the special-kind branches.
  Command-line completion offers the kind at the first argument and a `matchfuzzy` package at the
  second. The server surface (`lathe.modules` / `lathe.packages` / `lathe.resolveContext` /
  `lathe.createType`) is unchanged.

## Decisions (settled)

1. **Picker** — a Lathe-owned built-in fuzzy picker (`lathe.pick`, `matchfuzzypos`), **not**
   `vim.ui.select`. The requirement is fuzzy + visible list for every user regardless of plugins;
   `vim.ui.select` cannot guarantee it (builtin = numbered list).
2. **No location grammar** — the colon `[module:][scope:]package` argument is removed. The command
   takes at most a kind; the destination is the fuzzy picker (or a fuzzy-completed package word on the
   typed path).
3. **Name** — always a final, validated prompt; the argument never carries the type name.
4. **Package** — never defaults by omission; unresolved → picker; the default package only on explicit
   request.
5. **Scope** — carried by the picked destination row (a class can land in the test root); the kind
   biases ordering and forces test scope for a JUnit test; a new package asks scope only when the
   module has both roots.
6. **`module-info`** — only the module is chosen; the JPMS name is auto-derived from the base package.

## History

- **v1–v3** (CQ-0055): progressive pickers spec'd, a single completable free-text target shipped;
  omitted packages silently became the default package (the source-root-corruption bug).
- **v4**: typed `[module:][scope:]package` location + native command-line completion + a
  `vim.ui.select` guided fallback; fixed the corruption bug but kept the colon grammar and depended on
  a fuzzy `vim.ui.select` backend.
- **v5** (this document): the location grammar is dropped for a built-in fuzzy picker; fuzzy + visible
  list for every user, no plugin dependency.

## Relationship to other work

- **Distinct from** [New Type Creation via Snippet Completion](../planned/lathe-new-type-creation.md), a deferred
  editor-agnostic snippet approach with no client UI — a different mechanism for the same goal.
- **Pairs with** the WS gap on module-mirror corruption (a `module-info` affordance for modular
  projects).
