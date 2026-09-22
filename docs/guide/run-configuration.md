# Run configuration

Lathe launches your test runs and `main`-class runs from the captured or derived launch templates,
with generated defaults.
Configuration lives in an optional, hand-authored file with two parts:

- **`defaults`** — baseline overlays applied automatically to every cursor/gutter run (extra JVM
  flags, environment, and so on);
- **`configs`** — **named** run configurations you select explicitly (`:LatheRun {name}`), each
  pinning a target (a `main` class or a test selector) plus its own overlay.

Configuration is applied by the language server, so it behaves the same regardless of which editor or
client triggered the run.
When no file exists, runs use the built-in defaults, so **no configuration is required**.

## Files

Configuration is read from two optional files that share one schema and merge into one effective
configuration:

| File | Scope | Committed? |
|---|---|---|
| `lathe-run.json` (reactor root) | shared, travels with the repo | yes, at your discretion |
| `.lathe/run.json` | machine-local, per developer | no — it lives inside the gitignored `.lathe/` |

Put team-wide settings in the committable `lathe-run.json`; keep machine-specific paths and
secret-bearing `env` in the local `.lathe/run.json`.
When both layers set the same field the local one wins — scalars override, lists concatenate, and
`env` entries union.

`:LatheRunSave` is the **only** thing that writes a config file, and it writes only the local
`.lathe/run.json` (never the committed `lathe-run.json`). Nothing is written on a run.

## File shape

Each file is a JSON object with two optional keys:

```json
{
  "defaults": [
    { "kind": "TEST", "jvmArgs": ["-Duser.timezone=UTC"] },
    { "kind": "MAIN", "module": "services/app", "jvmArgs": ["-Xmx2g"] }
  ],
  "configs": {
    "dev": {
      "kind": "MAIN",
      "module": "services/app",
      "mainClass": "com.example.app.AppServer",
      "jvmArgs": ["-Dspring.profiles.active=dev"],
      "args": ["--port", "8080"],
      "env": { "APP_ENV": "dev" }
    },
    "smoke": {
      "kind": "TEST",
      "module": "services/app",
      "selectors": [ { "selectorKind": "CLASS", "selectorValue": "com.example.app.SmokeTest" } ]
    }
  }
}
```

An absent file, or `{}`, means every run uses the built-in defaults. Either key may be omitted.

### `defaults` — automatic baselines

Each entry is a baseline overlay scoped by `kind` (`MAIN` or `TEST`, required) and an optional
`module` (the module path relative to the workspace root, the same key used under `.lathe/<module>/`).
Omit `module` to apply the entry to **every** module of that kind. A baseline has no name and pins no
target — it only says *how* to launch.

Baselines resolve **most-specific-wins** for a cursor/gutter run of module `M`, kind `K`:

1. the entry for that exact `(module, kind)`, if any;
2. otherwise the entry for that `kind` with no `module` — the workspace-wide baseline;
3. otherwise the built-in defaults, unchanged.

### `configs` — named, selectable configurations

Each key is the config **name** (the selection handle). Each value pins a target for its kind —
`mainClass` for `MAIN`, `selectors` for `TEST` — plus any overlay fields. A named config is
cursor-independent: `:LatheRun dev` runs its pinned target from anywhere. A named config **inherits
the matching baseline** and layers its own settings on top (lists concatenate baseline-first, `env`
unions, scalars override).

## Overlay fields

Every overlay field is optional; an omitted field keeps the generated default.

| Field | Effect |
|---|---|
| `jvmArgs` | Appended after the captured/derived JVM args — on a duplicate `-D`/`-X`, yours wins |
| `args` | Appended to the program arguments |
| `env` | Merged into the run's environment; it never replaces the inherited environment |
| `cwd` | Working directory, resolved relative to the workspace root (absolute allowed) |
| `classpathAppend` | Extra class-path entries, appended after the derived class path (workspace-root-relative; absolute allowed) |
| `modulePathAppend` | Extra module-path entries, appended after the derived module path |

Configuration is deliberately limited to these user-owned inputs.
It **cannot** change launch-correctness fields — the module path, class path, `--patch-module`, the
captured `--add-opens` / `--add-reads` / `--add-exports` / `--add-modules` directives, or dependency
placement — so a run can never diverge from how Maven would have launched it.
`classpathAppend` / `modulePathAppend` only *add* entries after the derived ones; they cannot remove
or reorder them.

## Commands (Neovim)

| Command | Behavior |
|---|---|
| `:LatheRun` | Run the `main` under the cursor (unchanged). |
| `:LatheRun {name}` | Run the named config. `<Tab>` completes config names. |
| `:LatheDebug` / `:LatheDebug {name}` | Debug the cursor target, or a named config — run and debug share one entry. |
| `:LatheRunSave [name]` | Save the runnable under the cursor as a config in `.lathe/run.json`, then open it. With no name, the server derives one from the class (`AppServer`, `SmokeTest.testBar`). |
| `:LatheRunSave! [name]` | As above, overwriting an existing config of that name. |
| `:LatheRunOutput` | Toggle the run-output console (reopens the last run without rerunning). |

`:LatheRunSave` resolves the target the same way a run does — the method under the cursor, else the
enclosing class, else the file's only `main`; if nothing runnable is under the cursor it refuses and
writes nothing.

## Which configuration is active

Every run makes the active configuration visible:

- the run console's first lines show `config: <name>` (or `config: default` / `config: default+baseline`
  for a cursor run) followed by the launch command;
- Neovim shows a notification when a run starts;
- the server logs the run with its resolved config.
