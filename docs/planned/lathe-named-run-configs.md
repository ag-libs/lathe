# Lathe — Named run configurations

## Status

Planned — design under review, no code yet.

The file shape is decided: a single object with a `defaults` array (auto-applied baselines) and a
`configs` object keyed by config name (explicitly selected). No sentinel value, no temporary configs,
and nothing is written on a run — the only write is the explicit `:LatheRunSave`.

This resolves [TE-2](../gaps/gaps.md#te-2--no-named-run-configuration-selection-latherun-name)
and completes the deferred slice sketched in
[lathe-run-test-debug.md](../done/lathe-run-test-debug.md) §8.2, §8.5, §12.8, §12.10.

It builds directly on the **shipped** run-config overlay machinery (`RunItem`, `RunConfigReader`,
`RunOverlaySet`, `RunOverlay`) and the run/debug launch paths (`lathe.run.*`, `lathe.debug.*`,
`lathe.runnables.list`). Nothing here changes how a launch is *built*; it adds a **name** as an
explicit selection handle and a **pinned target** so a run is no longer only cursor-derived.

**No backward compatibility.** The tool has no external adopters. The overlay files change shape as a
clean break — from a flat JSON array to the `{ defaults, configs }` object. Any existing
`lathe-run.json` / `.lathe/run.json` must be rewritten. No migration is provided.

## Motivation

Today run-config overlays exist but are **invisible and unselectable**:

- an overlay entry is keyed only by `(module, kind)` — there is no `name`;
- selection is silent and automatic (`RunOverlaySet.defaultFor`): most-specific `(module, kind)` →
  module-less `(kind)` → built-in;
- there is no way to see *which* entry applied, and no way to *choose* one, or to run a fixed target
  independent of the cursor.

The user-facing result is confusing: "which run configuration is used when I run?" has no answer, and
there is no notion of a named, reusable configuration the way IntelliJ and VS Code have.

## Prior art — IntelliJ (the model)

The design mirrors IntelliJ's run-configuration model, which the user already works in:

| IntelliJ | Lathe equivalent |
|---|---|
| Run/Debug Configurations dropdown | `:LatheRun <Tab>` completion over `configs` |
| **Save Configuration** (name pre-filled from the class, editable) | `:LatheRunSave[!] [name]` |
| Configuration **templates / defaults** (per-type defaults applied to every config) | the `defaults` array |
| `.idea/runConfigurations/` (shared) vs `workspace.xml` (local) | `lathe-run.json` (shared) vs `.lathe/run.json` (local) |
| Run vs Debug the same configuration | `:LatheRun {name}` vs `:LatheDebug {name}` against one entry |

The key IntelliJ property carried over: a saved configuration knows **what** to run (a pinned target)
as well as **how**, so it is cursor-independent. IntelliJ's auto-created *temporary* configs are a
non-goal (see Non-goals) — gutter runs stay ephemeral and unsaved.

## Goal

From Neovim:

1. define **named** run configurations that pin a target (a `main` class or a test selector) and layer
   overlay settings on top;
2. **select** one by name to run or debug it (`:LatheRun {name}` / `:LatheDebug {name}`), with
   server-provided completion;
3. **save** the runnable under the cursor as a new named config (`:LatheRunSave[!] [name]`);
4. always **see which** configuration a run used (console header + notification + completion detail);
5. keep today's automatic, cursor-derived gutter/neotest runs working exactly as before, now driven by
   the `defaults` array.

## Design

### 1. File shape — `{ defaults, configs }`

Each layer (`lathe-run.json`, `.lathe/run.json`) is a single JSON object with two optional keys:

```json
{
  "defaults": [
    { "kind": "TEST", "jvmArgs": ["-Duser.timezone=UTC"] },
    { "kind": "MAIN", "module": "services/app", "jvmArgs": ["-Xmx2g"] }
  ],
  "configs": {
    "dev": {
      "kind": "MAIN", "module": "services/app",
      "mainClass": "com.example.app.AppServer",
      "jvmArgs": ["-Dspring.profiles.active=dev"]
    },
    "smoke": {
      "kind": "TEST", "module": "services/app",
      "selectors": [ { "selectorKind": "CLASS", "selectorValue": "com.example.app.SmokeTest" } ]
    }
  }
}
```

- **`defaults`** — an array of **baseline** overlays, each scoped by `(module, kind)`. Auto-applied to
  cursor/gutter/neotest runs (the "common config"), and inherited by named configs. No name, no target.
  This is exactly today's shipped overlay, moved under `defaults`.
- **`configs`** — an object whose **key is the config name** and whose value pins a target
  (`mainClass` for MAIN, `selectors` for TEST) plus overlay fields. Selected explicitly by name.

An absent file, or `{}`, means every run uses the built-in defaults. Either key may be omitted.

Structural benefits of the split: config-name uniqueness is enforced by JSON (no duplicate keys), the
name lives in the key (no `name` field to repeat), layer-merge is a natural key merge, and baselines
vs selectable configs are distinguished by structure — no `*` sentinel.

### 2. Schema — `RunItem` gains a pinned target

`RunItem` (`lathe-server/.../run/RunItem.java`) stays the single in-memory record for both a baseline
and a config, gaining three fields:

| Field | Type | Meaning |
|---|---|---|
| `name` | `String` | Config name (from the `configs` key). **Null for a `defaults` entry.** |
| `mainClass` | `String` | Pinned target for `kind == MAIN`. |
| `selectors` | `List<Selection>` | Pinned target for `kind == TEST`. Reuses the `{selectorKind, selectorValue}` record already used by `lathe.run.test`. |

Existing fields unchanged: `module`, `kind`, `args`, `jvmArgs`, `env`, `cwd`, `classpathAppend`,
`modulePathAppend`.

Compact-constructor invariants (`ValidCheck`):

- `name == null` → a **baseline** (`defaults` entry): **no pinned target allowed**; keyed by
  `(module, kind)`.
- `name != null` → a **config** (`configs` entry): **must pin exactly the target for its kind** —
  `mainClass` for `MAIN`, at least one `selectors` entry for `TEST`; name non-blank.
- `mainClass` only with `MAIN`; `selectors` only with `TEST`.
- All collections normalized to immutable-empty (unchanged).

The `defaults`/`configs` structure enforces which case a `RunItem` is: the reader constructs
`defaults` entries with `name = null` and `configs` entries with `name = <key>`. No user-visible
sentinel.

### 3. Resolution — two disjoint paths

`RunConfigReader` reads each layer's object, then field-merges the two layers (local over shared,
§8.3) into a `RunOverlaySet` with two buckets:

- **defaults** keyed by `(module, kind)` (from `defaults[]`, `name == null`);
- **configs** keyed by `name` (from `configs{}`).

`RunOverlaySet` exposes two resolvers:

- **`defaultFor(module, kind)`** — used by cursor/gutter/neotest runs. Most-specific baseline wins:
  `(module, kind)` → module-less `(kind)` → built-in empty. This is today's behavior, now sourced from
  the `defaults` bucket.
- **`byName(name)`** — used by explicit runs. Returns the config. Its pinned target drives *what*
  runs; the effective overlay is `defaultFor(module, kind).mergedWith(config)` with the **config
  winning** (list concat baseline-first, `env` union, scalar override) — so a named config **inherits
  its matching baseline** (design §8.2 "composes on top of the matching default").

Layer merge: within `defaults`, entries merge by `(module, kind)`; within `configs`, entries merge by
name (local body field-merged over shared body). A duplicate config name **cannot occur within one
file** (JSON keys are unique); across layers, the local body wins per field.

### 4. Composition example

Given the file in §1, `:LatheRun smoke` runs `SmokeTest` with:

```
built-in test defaults
  + defaults[] TEST baseline   →  -Duser.timezone=UTC
  + "smoke" config             →  (its own jvmArgs/env, none here)
```

so it inherits `-Duser.timezone=UTC` automatically. A gutter test run in `services/app` gets the same
TEST baseline but no config overlay. A gutter `main` run in `services/app` gets the MAIN baseline
(`-Xmx2g`).

### 5. Server commands

Added to `LatheWorkspaceService` → `WorkspaceSession`, alongside the existing `lathe.run.*` /
`lathe.debug.*` commands:

| Command | Args | Effect |
|---|---|---|
| `lathe.run.named` | `{name, token}` | Resolve `byName(name)`, build the launch from its pinned target + composed overlay, run it. |
| `lathe.debug.named` | `{name, token}` | Same, in debug launch mode (reuses the `lathe.debug.*` path). |
| `lathe.runconfigs.list` | `{}` | Return `[{name, kind, module, target, summary}]` (the `configs` entries) for completion. |
| `lathe.runconfig.save` | `{name?, moduleRel, kind, mainClass?, selectors?, overwrite}` | Write a config under `.lathe/run.json` `configs` (§6). |

A config is **launch-mode-agnostic**: the same `dev` entry is runnable by both `:LatheRun dev` and
`:LatheDebug dev`. There is no separate "debug configuration".

`lathe.run.named` / `lathe.debug.named` route through the existing main/test launch builders — a new
front door onto the shipped launch path, not a second launch implementation.

### 6. Write path — `RunConfigWriter`

`:LatheRunSave` is the one place Lathe **writes** a config file, and it writes **only**
`.lathe/run.json` (gitignored, machine-local); the committed `lathe-run.json` is never written by
Lathe. This is a deliberate, documented exception to the design §8 "Lathe never writes these files"
invariant, scoped to the local layer.

`RunConfigWriter` (new, `lathe-server/.../run/`):

1. read `.lathe/run.json` (absent → empty `{}`), **preserving its `defaults`** and any other configs;
2. determine the config name (§6.1);
3. upsert the entry under `configs[name]` — respecting the `overwrite` flag (§6.2);
4. serialize canonically (server owns the schema) and **atomically replace** (write temp + move, via
   `FileUtil` / `IOUtil`);
5. return `{path, name}` so the client opens the file at the new entry and reports it.

No lock is needed beyond the atomic replace: writes happen only on this user-triggered command, and
never on a run.

#### 6.1 Name — optional argument, derived default (IntelliJ "Save Configuration")

The name argument is **optional**:

- `:LatheRunSave` → the server **derives** the name from the pinned target:
  - MAIN → simple class name (`com.example.app.AppServer` → `AppServer`);
  - TEST, class selector → simple class name (`SmokeTest`);
  - TEST, method selector → `SmokeTest.testBar`;
  - TEST, package selector → last package segment.
- `:LatheRunSave dev` → use the given name.

This mirrors IntelliJ: the name is pre-filled from the class but overridable. The file stays
hand-editable afterward, so extra variants of one class (`dev` vs `prod`) are made by editing the
opened file.

#### 6.2 Collision — the bang overwrites

- `:LatheRunSave` (no bang), `configs[name]` already exists → **refuse, write nothing**:
  `config 'dev' already exists — use :LatheRunSave! to overwrite`.
- `:LatheRunSave!` → overwrite the existing config (`overwrite = true`).

#### 6.3 Cursor target resolution (the "wrong place" case)

The save target is resolved the same way a run is — from the server's runnable ranges via the existing
`run.lua` / `dap.lua` cursor-target helpers, **never by parsing text** (per the no-ad-hoc-parsing
rule). Ladder:

1. cursor inside a runnable **method** (`main` or a test method) → method-level config;
2. else cursor inside a runnable **class** → class-level config;
3. else the file has **exactly one** runnable → use it, and notify which (it was not under the cursor);
4. else (zero runnables, or several with the cursor on none, or a non-Java / cold-index buffer) →
   **refuse, write nothing**:
   `:LatheRunSave — no runnable under cursor (place cursor in a main() or @Test method)`.

The **kind (MAIN/TEST) is derived from the resolved runnable, never guessed**. A file with both a
`main` and tests and the cursor on neither hits case 4.

### 7. Signalling which configuration is active

Three surfaces, so the answer to "which config?" is always available:

1. **Console header line** — folded into the existing COMMAND-stream launch line (so no new transcript
   line, no row-count churn in `output.lua` tests):
   - `▶ run services/app · com.example.app.AppServer · config: dev` (named run)
   - `▶ run services/app · com.example.app.AppServer · config: default +overlay` (gutter run, a
     baseline applied)
   - `▶ run services/app · com.example.app.AppServer · config: default` (gutter run, no baseline
     matched)

   The *absence* of customization is visible too.

2. **Neovim notification** — `vim.notify` on run start **only when an overlay actually applies** (a
   named config, or a matching baseline) — silent for pure built-in runs, so no per-run noise:
   `[Lathe] com.example.app.AppServer · config: dev`.

3. **Completion detail** — `:LatheRun <Tab>` / `:LatheDebug <Tab>` list each config's name plus a
   short summary (module, target, key jvmArgs) from `lathe.runconfigs.list`.

4. **Server run log** — the run's existing INFO log line carries the resolved config
   (`[run] services/app AppServer config=dev …`), so the applied overlay is visible in the log as
   well. No separate telemetry.

### 8. Neovim client

| Command | Behavior |
|---|---|
| `:LatheRun` | no arg → today's cursor run (unchanged); arg → `lathe.run.named`; completion via `lathe.runconfigs.list`. |
| `:LatheDebug` | no arg → today's cursor debug (unchanged); arg → `lathe.debug.named`; same completion. |
| `:LatheRunSave[!] [name]` | resolve the cursor target (§6.3), call `lathe.runconfig.save` (bang → overwrite; name optional), open the returned file at the new entry. |
| `:LatheRunOutput` | toggle the run-output console (`output.open()`); reopens the last run's buffer without rerunning. |

`:LatheRunOutput` fills a current gap: the console buffer persists (`bufhidden = hide`) but nothing
binds `output.open()` to a user command today, so a closed console can only be reopened by rerunning.

## Reuse map

| Need | Reuse |
|---|---|
| Build a MAIN / TEST launch | existing `WorkspaceSession` main/test launch builders (`lathe.run.*`) |
| Debug launch mode | existing `lathe.debug.*` path |
| Cursor → runnable target | existing `run.lua` `_main_target_for`, `dap.lua` `_test_target_for` |
| Runnable ranges | existing `lathe.runnables.list` |
| Overlay apply | existing `RunOverlay.applyToMain` / `applyToTest` |
| Layer merge | existing `RunItem.mergedWith` |
| Test selector shape | existing `{selectorKind, selectorValue}` record |
| File IO / atomic replace | `FileUtil`, `IOUtil` |
| Console surface | existing `output.lua` (`open`, `ensure_open`) |

## Scope

- File shape: `{ defaults, configs }` in both layers.
- `RunItem` schema (`name` nullable, `mainClass`, `selectors`) + invariants.
- `RunConfigReader` parses the object into two buckets (baselines by `(module, kind)`, configs by
  name) and field-merges layers.
- `RunOverlaySet` `defaultFor` (baselines) + `byName` (compose over baseline).
- `RunConfigWriter` → `.lathe/run.json` `configs`, preserving `defaults`; optional-name derivation,
  bang overwrite, atomic replace, cursor-target ladder.
- Server commands `lathe.run.named`, `lathe.debug.named`, `lathe.runconfigs.list`,
  `lathe.runconfig.save`.
- Console header config line + `vim.notify` when an overlay applies.
- Client commands `:LatheRun {name}`, `:LatheDebug {name}`, `:LatheRunSave[!] [name]`,
  `:LatheRunOutput` + completion.
- Docs (`run-configuration.md` rewrite), design §8 update, TE-2 resolution, `status.md`.

## Non-goals

- **Writing `lathe-run.json`** (the shared/committed layer) — always hand-authored; only
  `.lathe/run.json` is written.
- **Temporary configs / per-run writes** — nothing is generated or persisted on a run. Gutter runs
  are ephemeral (resolve cursor → apply baseline → launch); a config exists only after an explicit
  `:LatheRunSave`.
- **A VS Code / other-editor picker** — the server side (`lathe.runconfigs.list`) is editor-agnostic;
  a future client swaps the `:LatheRun` completion for a QuickPick with no server change (design §8.5).
- **Changing launch-correctness fields** — unchanged from §8.4; overlays remain additive-only.

## Testing

Additive to the shipped overlay tests; the `RunItem` constructor arity and the reader's file shape
force mechanical updates.

- **`RunItemTest`** — constructor updated at all positional call sites. New invariant cases:
  baseline-with-target throws, config-without-target throws, `mainClass`-with-`TEST` throws,
  `selectors`-with-`MAIN` throws, blank config name throws. Positive: valid baseline, valid config
  MAIN, valid config TEST.
- **`RunConfigReaderTest`** — parse the `{ defaults, configs }` object; existing baseline merge
  assertions preserved (now sourced from `defaults[]`); add: configs merge by name across layers;
  `selectors` parsed from JSON; absent file / `{}` → built-in defaults.
- **`RunOverlaySet`** — `defaultFor` resolves baselines most-specific; `byName` returns the config and
  composes over the matching baseline (concat / union / override); `byName` of an unknown name is a
  clean miss.
- **`RunOverlayTest`** — constructor bump; add header-line assertion (config name folded into the
  command line).
- **`RunConfigWriter`** (new) — create / upsert-by-name / atomic-replace in `@TempDir`; **preserves
  `defaults`** and sibling configs; name derivation per kind/selector; refuse-vs-`!` overwrite; only
  `.lathe/run.json` is touched.
- **Command dispatch** — `lathe.run.named` / `lathe.debug.named` / `lathe.runconfigs.list` /
  `lathe.runconfig.save` per the existing `LatheWorkspaceService` command-test pattern.
- **Client (headless Lua)** — `:LatheRun` / `:LatheDebug` completion, `:LatheRunSave[!]` (target
  ladder + refuse/overwrite), `:LatheRunOutput` toggle.
- **Invoker** — run `mvn verify` for `LspSmokeTest` / `MultiModuleTest` after the server-visible
  command surface changes.

## Resolved decisions

1. **File shape is `{ defaults, configs }`** — a wrapper object: `defaults` is the baseline array,
   `configs` is name-keyed. Chosen over a flat array; removes the `*` sentinel and enforces name
   uniqueness structurally. Departs from design §8.1 "no wrapper object" — acceptable under no-compat.
2. **Keep the baseline, don't defer it** — the shipped `(module, kind)` overlay becomes `defaults`;
   named configs inherit it. No functionality removed.
3. **`defaults` entries are nameless; `configs` entries are named by key** — in-memory `RunItem.name`
   is null for a baseline, non-null for a config. No sentinel value.
4. **Named config ⇒ must pin a target** (cursor-independent, IntelliJ-faithful).
5. **`:LatheRunSave` name is optional** with a derived default (IntelliJ "Save Configuration");
   `:LatheRunSave!` overwrites.
6. **Only `.lathe/run.json` is written**, never the committed `lathe-run.json`; the writer preserves
   `defaults` and sibling configs. Nothing is written on a run.
7. **No temporary configs** — gutter runs are ephemeral; IntelliJ's auto-populated temp entries are a
   non-goal.
8. **`:LatheDebug {name}` is in scope** — same entry, `run` vs `debug` verb.
9. **Config name shown via** console header + `vim.notify` (when an overlay applies) + completion
   detail.
10. **Method-level TEST config name is `Class.method`** (e.g. `SmokeTest.testBar`) — the method is
    included so it does not clobber a class-level save of the same class.
11. **Overlay is surfaced on the run log too** — the server already logs each run at INFO; that line
    carries the resolved config/overlay (`config=dev`), so the applied overlay is visible in the log
    as well as the console header and the `vim.notify`. No separate telemetry.
