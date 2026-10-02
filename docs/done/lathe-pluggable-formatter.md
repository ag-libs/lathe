# Lathe — Pluggable Formatter

## Status

Implemented; the option shape is **superseded by [lathe-workspace-style.md](lathe-workspace-style.md)**.
The formatter is now resolved per-workspace as `style.formatter = { engine, command }` (engine
`google`/`aosp`/`none`/`command`) from a project `lathe-style.json` / `.lathe/style.json`, with the
client `setup({ style = ... })` as the global-default fallback. The engines and external-command
behaviour below are unchanged. Range and on-type formatting stay deferred (see
`lathe-formatting-profiles.md`).

## Problem

Lathe shipped a single, hard-wired formatter: google-java-format, in-process, enabled by the string
init option `formatter = "google"`. google-java-format is fast (it stays warm in the long-lived
server) but opinionated and not to everyone's taste. Users who prefer a different formatter — an
Eclipse-JDT-based tool, or any `gofmt`/`rustfmt`-style command — had no way to plug it in.

The goal: let an end user supply their own Java formatter without rebuilding the server, while keeping
the fast in-process engine as the default and not regressing the existing behavior.

## Decision

The formatter is selectable through the existing `initializationOptions.lathe.formatter` field, which
now accepts either shape:

- `"google"` — the built-in in-process engine (unchanged default).
- `{ "command": ["jfmt", "print", "-"] }` — an external command the server runs per format.
- absent — formatting disabled; the capability is not advertised.

The external command is run **server-side**, not in the editor client. Because the payload travels as
standard LSP `initializationOptions`, every editor (Neovim, and the planned VS Code / Emacs clients)
enables it by forwarding the same JSON — one server-side implementation, a tiny per-editor config
shim, and the existing `textDocument/formatting` capability, `:LatheFormat`, format-on-save, and the
imports-fold preservation all keep working unchanged.

Client-side execution (à la the `xmllint` pom path) was rejected: it is per-editor and would have to
reimplement process handling, timeout, and fold preservation in each client.

## Design

### Engine abstraction

`FormatEngine` is a `sealed interface { String format(String source) throws Exception; }` with two
implementations:

- `GoogleFormatEngine` — wraps `new Formatter().formatSourceAndFixImports(source)`.
- `ExternalCommandFormatEngine` — a `record (List<String> command, Duration timeout, Path
  workingDir)`.

Engines throw their native failure type (`FormatterException`, `IOException`, …). `JavaFormatter` is
the single late catch: it runs the engine, returns one whole-document `TextEdit` when the text
changed, an empty edit list when it did not, and on any exception logs `SEVERE` (with the engine type)
and returns an empty list — leaving the buffer unchanged. No custom exception hierarchy.

### Running the external command

Source and formatted output travel through **temp files** wired to the process stdin/stdout, so
neither is a pipe the server must drain concurrently — this removes any large-file deadlock and keeps
the timeout meaningful. The command's own **stderr is inherited** to the server's stderr, so a tool's
diagnostics land in `lsp.log` (the JSON-RPC channel is a separate fd, captured before the server
reassigns `System.out`). The process runs with the **workspace root as its working directory**, so a
tool finds project config and a repo-relative command (`{ "./tools/fmt" }`) resolves.

Any failure — command-not-found, non-zero exit, timeout (`destroyForcibly`), or empty output (which
would otherwise blank the buffer) — throws an `IOException` carrying the full command, and the buffer
is left unchanged.

### Threading

The `formatting` handler runs on lsp4j's message thread and only resolves the engine and submits the
work; the format itself runs on the `ServerEventLoop` worker that owns the `WorkspaceSession`. This
keeps the message loop responsive (an external formatter can take seconds) and confines session state
to its single thread.

## Non-goals

- **Indentation** stays decoupled: live-editing indent is the client-side `indent_style` /
  `continuation_indent` profile, independent of the formatter. A user of a non-GJF formatter should
  set those to match their tool so typing does not fight format-on-save.
- **Range and on-type formatting** remain deferred.
- **Configurable timeout / per-file working directory** are not exposed; the timeout is a fixed
  default and the working directory is the workspace root.

## Trade-offs

- A JVM-based external formatter pays full process startup on every call — potentially seconds — so it
  is markedly slower than the warm in-process `"google"` engine. This is documented; the in-process
  engine remains the recommended default.
- The command runs in the server's environment: a bare command is resolved on the server's `PATH`
  (use an absolute or repo-relative path otherwise), and changing the command requires a server
  restart (init options are fixed at startup).
