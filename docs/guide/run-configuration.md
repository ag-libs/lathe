# Run Configuration

Lathe launches your test runs and `main`-class runs from the captured or derived launch templates,
with generated defaults.
To customize a launch — extra JVM flags, program arguments, environment variables, a working
directory, or additional class-/module-path entries — supply a thin **overlay**.
The overlay is applied by the language server, so it behaves the same regardless of which editor or
client triggered the run.

Overlays are read from two optional, hand-authored files that share one schema and merge into a single
effective overlay. Lathe never creates or writes them:

| File | Scope | Committed? |
|---|---|---|
| `lathe-run.json` (reactor root) | shared, travels with the repo | yes, at your discretion |
| `.lathe/run.json` | machine-local, per developer | no — it lives inside the gitignored `.lathe/` |

Put team-wide settings in the committable `lathe-run.json`; keep machine-specific paths and
secret-bearing `env` in the local `.lathe/run.json`.
When both layers set the same field the local one wins — scalars override, lists concatenate, and
`env` entries union.
When neither file exists, runs use the built-in defaults, so **no configuration is required**.

Each file is a JSON **array** of overlay entries. An entry is scoped by `kind` (`MAIN` or `TEST`, the
only required field) and an optional `module` — the module path relative to the workspace root (the
same key used under `.lathe/<module>/`). **Omit `module` to apply the entry to every module** of that
kind:

```json
[
  { "kind": "TEST", "jvmArgs": ["-Duser.timezone=UTC", "-XX:+EnableDynamicAgentLoading"] },

  {
    "kind": "MAIN",
    "module": "services/app",
    "jvmArgs": ["-Dspring.profiles.active=dev"],
    "args": ["--port", "8080"],
    "env": { "APP_ENV": "dev" },
    "cwd": "services/app",
    "classpathAppend": ["config/dev"]
  }
]
```

`kind` is the only required field; **every field below is optional**, an omitted field keeps the
generated default, and an entry that sets nothing is a no-op.

| Field | Effect |
|---|---|
| `jvmArgs` | Appended after the captured/derived JVM args — on a duplicate `-D`/`-X`, yours wins |
| `args` | Appended to the program arguments |
| `env` | Merged into the run's environment; it never replaces the inherited environment |
| `cwd` | Working directory, resolved relative to the workspace root (absolute allowed) |
| `classpathAppend` | Extra class-path entries, appended after the derived class path (workspace-root-relative; absolute allowed) |
| `modulePathAppend` | Extra module-path entries, appended after the derived module path |

The overlay is deliberately limited to these user-owned inputs.
It **cannot** change launch-correctness fields — the module path, class path, `--patch-module`, the
captured `--add-opens` / `--add-reads` / `--add-exports` / `--add-modules` directives, or dependency
placement — so an overlaid run can never diverge from how Maven would have launched it.
`classpathAppend` / `modulePathAppend` only *add* entries after the derived ones; they cannot remove
or reorder them.

## How an overlay is selected

Selection is automatic — there is no picker.
For a run of module `M`, kind `K`, Lathe applies the most specific matching entry:

1. the entry for that exact `(module, kind)`, if any;
2. otherwise the entry for that `kind` with no `module` — the **workspace-wide default**;
3. otherwise the built-in defaults, unchanged.

The most specific match wins **as a whole** — a module entry is used *instead of* the workspace one,
not merged with it. (The shared and local *layers* of the chosen entry still field-merge, as above.)
An overlay never changes *what* runs (the test or class you launched); it only overlays *how* that
launch is configured.

Named, explicitly-selected configurations — entries that add a `name` and a pinned target to the same
array — are planned but not yet selectable.
