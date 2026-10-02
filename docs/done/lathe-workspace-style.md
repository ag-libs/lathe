# Lathe — Workspace-Scoped Style (Formatter + Indentation)

## Status

Implemented (phases 1 and 2). The server reads the per-workspace style file for the formatter, the
nvim client reads it for indentation and gates format-on-save on the advertised capability, and
`lathe:sync` auto-detects the formatter from `spotless-maven-plugin`.

## Goal

One per-workspace source of truth that drives **both** the server's save-formatter and the client's
as-you-type indentation, so a project's style follows the project rather than a global editor setting.
This removes the current footgun — a global `formatter = "google"` reformatting a project whose build
uses a different formatter (e.g. equalsverifier's Eclipse/Spotless config), which then fails the
project's own `spotless:check` — and keeps the formatter and the indent profile from drifting apart.

The driving requirement: a developer (and their team) get Google style automatically on every project
whose build is configured for it, with no per-project editor configuration.

## The file

Two optional files, mirroring Lathe's existing run-config split (`lathe-run.json` committed /
`run.json` local — see `LatheLayout`):

- **`lathe-style.json`** at the repo root — *committed*, shareable team intent. Highest precedence.
- **`.lathe/style.json`** — *generated* by `lathe:sync` (`.lathe/` is gitignored). Auto-detected from
  the build.

Both are optional. Named `style.json` (not `formatter.json`) because it carries indentation too.

### Schema

One typed record tree in `lathe-core`, shared by the writer (plugin) and both readers (server,
client):

```json
{
  "formatter": { "engine": "google" | "aosp" | "none" | "command",
                 "command": ["spotless-cli", "-"] },
  "indent":    { "profile": "google" | "editorconfig",
                 "block": 2, "continuation": 4 }
}
```

- `formatter.engine` — `command` uses `formatter.command` (argv); `none` disables formatting.
- `indent.block` / `indent.continuation` — display-column widths. `editorconfig` profile defers to
  native EditorConfig and treats the widths as fallback; `google` profile uses them directly
  (defaults 2 / 4).

Records (`lathe-core`): `WorkspaceStyleData(FormatterSpec, IndentSpec)` (both sections optional),
`FormatterSpec(engine, command)`, `IndentSpec(profile, block, continuation)`. The leaf records carry a
compact constructor validating the enum-like string fields and defensively copying `command`.

### Precedence (both consumers)

`lathe-style.json` (committed) → `.lathe/style.json` (generated) → client init option (global default)
→ built-in default (`formatter: none`, `indent: editorconfig`).

## Producers

### Auto-detect from Spotless (`lathe:sync`)

`lathe:sync` inspects the reactor root's effective model for `spotless-maven-plugin`
(`com.diffplug.spotless:spotless-maven-plugin`) and maps its `<configuration><java>` formatter to a
`FormatterSpec` + `IndentSpec`, writing `.lathe/style.json` (skip-if-unchanged, like the manifest and
`java-home`). Active profiles are already resolved in the `MavenProject` the Mojo sees, so a formatter
declared in a profile (as in equalsverifier's `static-analysis`) is visible.

| Spotless `<java>` config | `formatter.engine` | `indent` |
|---|---|---|
| `<googleJavaFormat>` (style GOOGLE / unset) | `google` | `{google, 2, 4}` |
| `<googleJavaFormat><style>AOSP` | `aosp` | `{google, 4, 8}` |
| `<eclipse>` / `<palantirJavaFormat>` / unknown | `command-file` | `{editorconfig}` |
| no `spotless-maven-plugin` | *(no file written)* | — |

Non-google formatters are **delegated to `mvn spotless:apply`** on the edited file (the `command-file`
engine), so Lathe applies the project's own formatter without running it in-process — see
[delegated Maven formatting](lathe-delegated-maven-formatting.md). Opt out with `-Dlathe.spotless=false`
(sync writes `none` instead) or a committed `lathe-style.json`.

### Hand-authored

A developer or team commits `lathe-style.json`, or drops a `.lathe/style.json`, to override detection
entirely. This path needs no sync support and is the fallback when a project has no Spotless config.

### Global default (per developer, all projects)

The client's existing setup options remain the **global fallback**, migrated to the typed shape:
`require('lathe').setup({ style = { formatter = { engine = 'google' }, indent = { profile = 'google' } } })`
(replacing today's `formatter = "google"` / `indent_style = "google"`). This gives a developer Google
style on every project that has no file — while a project's own file (committed or Spotless-detected)
still wins, so a Spotless-eclipse project resolves to its delegated `command-file` (or `none` when
delegation is opted out).

## Consumers

### Server — formatter

The server already builds the engine in `LatheLanguageServer.resolveFormatEngine` and has the
workspace root at `initialize`, so it reads the file directly — **no client involvement for the
formatter**. Changes:

- Resolve `WorkspaceStyleData.formatter` with the precedence above; `resolveFormatEngine` returns the
  matching engine.
- `GoogleFormatEngine` gains a style argument (`GOOGLE` / `AOSP`).
- The server advertises `documentFormattingProvider` **iff** an engine resolves. So `none` turns
  formatting off client-side with zero client logic.

### Client (nvim) — indentation and format-on-save

- **Indent** runs client-side (`indentexpr`), so the client reads the same file. `indent.lua` moves
  from a single global `M.config` to **per-workspace**: `ftplugin/java.lua` resolves the buffer's root
  (existing `get_root`), reads the file's `indent` section, and applies `block` / `continuation` /
  `profile` for that buffer. Multiple projects in one session each get their own.
- **Format-on-save** stops keying off a static option and wires on `LspAttach` **iff the server
  advertises `documentFormattingProvider`**. The server (from the file) becomes the single decider;
  the client honors the advertised capability. This also fixes today's assumption that formatting
  always exists.

## In-process vs delegated

In-process Lathe runs google-java-format (GOOGLE / AOSP) only. Every other Spotless formatter is
**delegated** to the project's build via `mvn spotless:apply` (the `command-file` engine) rather than
run in-process — see [delegated Maven formatting](lathe-delegated-maven-formatting.md). equalsverifier
therefore formats with its own eclipse config through mvn, keeping EditorConfig-driven live indent.

## Components touched (by module)

- `lathe-core` — `WorkspaceStyleData` / `FormatterSpec` / `IndentSpec` records; `LatheLayout`
  constants `STYLE_FILE` (`.lathe/style.json`) and `STYLE_SHARED_FILE` (`lathe-style.json`); a small
  precedence resolver shared by server and the sync writer.
- `lathe-maven-plugin` — Spotless config detector + `.lathe/style.json` writer; nvim client changes
  (per-workspace indent read, capability-based format-on-save, migrated setup options).
- `lathe-server` — `resolveFormatEngine` reads the file; `GoogleFormatEngine` style arg; conditional
  formatting-capability advertisement.

## Phasing

1. **Read paths + records.** `lathe-core` records + resolver; server reads the file and advertises
   conditionally; `GoogleFormatEngine` AOSP; client per-workspace indent + capability-based
   format-on-save; migrate the global default option. Validated with hand-authored files on
   equalsverifier (`none` + widths) and a Google project (`google`).
2. **Spotless auto-detect.** `lathe:sync` writes `.lathe/style.json` from the Spotless config.

## Caveats / decisions baked in

- **No backward compatibility** (per project rules): the client setup option and the init-option
  `formatter` shape migrate to the typed `{engine, …}` record outright.
- **Live changes**: read at `initialize`; editing the file needs `:LspRestart`. Extending
  `WorkspaceWatcher` to the style file is a later nicety.
- **Eclipse indent widths** are not parsed; Eclipse projects use the `editorconfig` profile (native
  EditorConfig, else the fallback width). Explicitly out of scope.
