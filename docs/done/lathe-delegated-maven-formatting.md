# Lathe — Delegated Formatting via Maven (Spotless)

**Status: shipped (mvn only).**
The in-process google-java-format engine (`google`/`aosp`) is kept for speed; non-google Spotless
formatters (eclipse, palantir, …) are delegated to `mvn spotless:apply` run on a throwaway scratch copy
of the file, preferring mvnd → `./mvnw` → mvn. Verified on equalsverifier (eclipse): formatting applied
via mvnd in ~0.2–0.6s, keeping the project's own style. Fully replacing the in-process engine, Gradle support, and
range-scoped formatting remain **future/potential** (see [Future](#future--potential)). Builds on the
shipped [workspace-scoped style](lathe-workspace-style.md).

## Goal

When a project configures Spotless with a formatter Lathe cannot run in-process, format the file
through the project's own build (`mvn spotless:apply`) instead of declining (`none`) — so the editor's
output is byte-identical to `spotless:check`, for any Spotless formatter, with no formatter knowledge
in Lathe and no extra user configuration. google/aosp projects keep the fast in-process engine.

## Decision summary (the hybrid)

`lathe:sync` detects the reactor's Spotless java formatter and writes `style.json`:

| Spotless `<java>` | `style.formatter` | engine |
|---|---|---|
| `googleJavaFormat` (GOOGLE) | `{ "engine": "google" }` | in-process (unchanged) |
| `googleJavaFormat` AOSP | `{ "engine": "aosp" }` | in-process (unchanged) |
| eclipse / palantir / other | `{ "engine": "command-file", "command": [ … mvn … ] }` | **delegated via a scratch file (new)** |
| no Spotless | *(no file)* | — |

The two engines coexist: each workspace resolves exactly one, the google fast path is untouched, and
the new path is purely additive.

## Why now plausible: mvnd

A full `mvn` per format is normally too slow (JVM boot + eclipse-jdt init every call, ~2–5s). With a
warm **mvnd** daemon the JVM and formatter stay resident. Measured on equalsverifier (eclipse Spotless,
single file, mvnd warm): **~0.12–0.19s** — acceptable even for format-on-save. The design therefore
**assumes mvnd**; cold daemon / plain `mvn` is slow (see Trade-offs).

## Design

### Engine abstraction

`FormatEngine` gains the target path so the delegated engine can place its scratch file next to it:

```java
sealed interface FormatEngine permits GoogleFormatEngine, ExternalCommandFormatEngine, FileCommandFormatEngine {
  String format(String source, Path file) throws Exception;
}
```

`GoogleFormatEngine` and `ExternalCommandFormatEngine` ignore `file` and are otherwise **unchanged** —
the existing stdin/stdout `command` engine stays exactly as it is. `JavaFormatter` and
`WorkspaceSession.format` pass the path (from the uri via `LatheUri.toPath`).

### New `FileCommandFormatEngine`

A `record (List<String> command, Path workspaceRoot, Duration timeout)`. On `format(source, file)`:

1. create a throwaway sibling temp file (`lathe-fmt-*.java`) in `file`'s own source directory and write
   `source` (the buffer) to it — so the **open file on disk is never touched**, yet the scratch still
   matches Spotless's `src/**/*.java` includes,
2. run `command` with placeholders substituted (`%FILE%` → the scratch path), cwd = `workspaceRoot`,
3. read the scratch back, delete it, and return its content.

The real file's bytes and mtime are left exactly as they were, so the editor neither flickers nor
raises a write-conflict on save; unique temp names keep concurrent formats from colliding.

Any failure — non-zero exit, timeout (`destroyForcibly`), missing `mvn`, empty output — throws; the
`JavaFormatter` late-catch logs `SEVERE`, sends a warning notification, and leaves the buffer unchanged.
stdout is Maven log noise (ignored); stderr inherits to `lsp.log`.

Placeholders substituted by the engine:

- `%FILE%` → the **scratch file's** absolute path.
- `%MODULE%` → the file's module path for `-pl`: the nearest ancestor directory of `file` containing a
  `pom.xml`, **relativized to `workspaceRoot`**. If that is the root itself (empty relative path), the
  `-pl %MODULE%` pair is dropped so the command targets the whole reactor.

### `-pl` is in the MVP

Running `spotless:apply` at the reactor root initializes Spotless for **every** module (slow even with
mvnd). The command therefore scopes to the file's module:

```json
"command": ["mvn", "-pl", "%MODULE%", "spotless:apply", "-DspotlessFiles=\\Q%FILE%\\E"]
```

`\Q…\E` makes Spotless's `spotlessFiles` **regex** match the absolute path literally. Module
resolution is a filesystem walk from `file` (no dependency on the server's module registry), so the
engine stays self-contained.

### Detection and `engineFor`

`WorkspaceStyleWriter` emits the `command-file` template (above, **mvn hard-coded for now**) for a
non-google Spotless java formatter. `LatheLanguageServer.engineFor` maps `command-file` →
`new FileCommandFormatEngine(command, rootPath, DEFAULT_TIMEOUT)`. google/aosp/none/command are
unchanged.

### Opt-out and override

- **Override / per-project opt-out** — a committed `lathe-style.json` already wins over the generated
  `.lathe/style.json`: set any engine, a custom `command`, or `{ "engine": "none" }` to opt out. No new
  code.
- **Global opt-out of auto-delegation** — a sync system property `-Dlathe.spotless=false`
  (`LatheFlags`) makes detection emit `none` for non-google formatters instead of `command-file`, for a
  developer or CI that never wants Lathe shelling out.

## The two problems, and how they're solved

1. **In-place, not a filter.** `spotless:apply` rewrites a file by path; it is not a stdin/stdout
   filter, hence the separate `FileCommandFormatEngine`. The current `command` engine is unchanged.
2. **Never disturb the open file.** Writing the buffer to the real path (the original approach) made the
   editor flicker and raised a "file changed since reading it" write-conflict on save. Instead the
   engine formats a throwaway **scratch sibling** and returns its content as the edit; the open file on
   disk is never written, so neither problem can occur.

Formatting returns a **minimal edit** (only the changed span, via common prefix/suffix trimming in
`JavaFormatter`) rather than a whole-document replacement, so applying the result doesn't force the
editor to re-render every line (which read as flicker on save). A successful format that changes the
buffer sends a `window/showMessage` `Lathe: formatted in Xms` notice — useful for the slower delegated
path, since the request is synchronous and a progress spinner could only paint after the edit.

## Trade-offs

- **Depends on mvnd** for usable latency; cold daemon / plain `mvn` is slow. Capability is advertised
  at `initialize` regardless; a missing/slow `mvn` fails gracefully (no edits, buffer unchanged).
- **A subprocess per format** vs the warm in-process google path — kept only for the non-google case.
- **Couples the delegated path to Maven/Spotless** (`-pl`, `-DspotlessFiles`); Gradle is future work.
- **A scratch temp file** briefly appears in the source directory during a format (removed in a
  `finally`); the real file is never touched.

## Future / potential

- **Drop the in-process google engine entirely** — rejected for now (speed). Revisit only if the
  delegated path proves fast and robust enough.
- **Gradle** — the engine is build-agnostic (`command` + placeholders); a Gradle sync would emit
  `["./gradlew", ":%MODULE%:spotlessApply", "-PspotlessFiles=…%FILE%…"]`. Gradle's always-on daemon
  removes the mvnd prerequisite. See [Gradle support](../planned/lathe-gradle-support.md).
- **Range-scoped formatting** — google-java-format's `getFormatReplacements(String, ranges)` formats
  only changed line ranges; a later speed optimization for the in-process path (not tree-based — there
  is no API to pass a compiled tree; see Non-goals).

## Non-goals

- Reimplementing any formatter in-process (the opposite of this direction).
- Passing Lathe's attributed javac tree to google-java-format — investigated and rejected: its API is
  String-based, it re-tokenizes the text and runs its own parse-only `Trees.parse`, and the one public
  hook (`JavaInput.setCompilationUnit`) can't drive the package-private formatter; its own parse is
  already cheap, so there is no meaningful speedup.
- A `spotless:print`-to-stdout mode — Spotless has none; in-place is the only contract.
- `onTypeFormatting` / editor-driven range formatting.

## Tests

- `FileCommandFormatEngine` driven by a **fake script that rewrites the scratch file**, so no real `mvn`
  is needed; cover success (and that the open file's bytes/mtime are untouched with no leftover scratch),
  `%MODULE%`/`%FILE%` substitution, root-module (no `-pl`), and failure (non-zero exit / timeout).
- `WorkspaceStyleWriter`: eclipse → `command-file` mvn template; `-Dlathe.spotless=false` → `none`.
- Update existing engine tests for the `format(source, file)` signature.
- Invoker: multi-module stays google; optionally a second fixture with eclipse asserting the
  `command-file` template is written.
