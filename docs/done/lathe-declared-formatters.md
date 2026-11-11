# Lathe — Declared Formatters

## Status

Done (2026-11-11).
Builds on [Pinned Formatters In-Process](../done/lathe-pinned-formatters.md), which runs the formatter, and
[Workspace-Scoped Style](../done/lathe-workspace-style.md), which defines `lathe-style.json` and `.lathe/style.json`.
Closes EG-078 (no formatter detection on a Gradle build).

## Problem

The server formats with the project's own pinned formatter (google-java-format, palantir-java-format, Eclipse JDT),
run in-process from the jars in the formatter spec's `classpath`.
Only a sync fills that classpath, and today only one front-end can tell the sync which formatter to resolve:
the Maven sync reads it from the Spotless plugin's POM configuration.
Everything else has no way in:

- **Maven projects without Spotless**, formatting with `fmt-maven-plugin`, the revelc `formatter-maven-plugin`, or no
  plugin at all.
- **Gradle projects.** The Spotless DSL hides its settings, and observing the formatter Spotless resolves is wrong or
  incomplete on real builds (checked on four, 2026-10-24):

  | Build | What it declares | What observation would conclude |
  |---|---|---|
  | Caffeine | no Spotless; google-java-format is a library in its version catalog | nothing |
  | Micronaut Core | Spotless `java { }` with only `targetExclude`s, no formatter step | nothing |
  | Hibernate ORM | Spotless header regexes, `removeUnusedImports()`, `leadingSpacesToTabs(4)`: cleanup steps, no formatter | **wrong:** `removeUnusedImports` resolves google-java-format, so every save would reformat a tab-indented codebase in Google style |
  | JUnit | Spotless `eclipse(<version>).configFile(...)` and a custom import order | **incomplete:** the version is observable, the settings file is not |

A committed `lathe-style.json` can already name a formatter, but its formatter section replaces the generated one
whole, so a pinned engine declared there has no classpath and the server reports "no resolved classpath".
Only the `command` and `command-file` engines work from it today.

The editor's global default (`initializationOptions.lathe.style.formatter`, Neovim's `setup{ style = { formatter } }`)
has the same flaw: it names a pinned engine that nothing ever resolves, so formatting is silently off.

## Goal

Lathe formats exactly as the project does, on Maven with or without Spotless and on Gradle, and nobody writes a
classpath.
Formatting stays a property of the project; Lathe never guesses one.

## Design

### What each project gets

| Project | What the user does | What Lathe formats with |
|---|---|---|
| Maven + Spotless (google, palantir, eclipse) | nothing | Spotless's formatter, as today |
| Maven, no Spotless | a `lathe-style.json` | the declared formatter |
| Gradle + Spotless | a `lathe-style.json`; until then the sync warns, with a template | the declared formatter |
| Gradle, no Spotless | a `lathe-style.json` | the declared formatter |
| no declaration, nothing detected | nothing | nothing: the project has no format to keep |

### The declaration: `lathe-style.json` without a classpath

The project root's `lathe-style.json` names the formatter's engine, and optionally its version and options:

```json
{
  "formatter": { "engine": "google", "version": "1.28.0", "options": { "style": "AOSP" } },
  "indent": { "profile": "google" }
}
```

The engines, option names and defaults are those of [Pinned Formatters](../done/lathe-pinned-formatters.md#style-schema):
`google`, `aosp`, `palantir`, `eclipse` (`options.file` names the Eclipse settings file, relative to the workspace root),
and the `command` engines, which need no resolution.
The file is usually committed; a personal one stays out of version control through `.git/info/exclude`.

There is no per-user default formatter.
A project without a formatter has no format to keep, and formatting it would put diffs into code the team never
formatted.

### Version and option defaults come from Spotless

A version or option left out takes Spotless's own default (the step classes' `defaultVersion()`, `default<Option>()`),
so the editor formats as `spotlessApply` would:

- **The build has Spotless:** its own release's defaults.
  On Maven, from the Spotless libraries its `spotless-maven-plugin` version declares, as today.
  On Gradle, from the step classes on the applied Spotless plugin's own classloader: no resolution at all.
- **The build has no Spotless:** the defaults of a Spotless release Lathe pins (`spotless-lib.version` in the root
  POM, filtered into `lathe-install`), resolved non-transitively.
  Lathe never keeps its own table of formatter versions.

The two can differ: Spotless's Gradle plugin 7.0.2 (spotless-lib 3.0.2) defaults google-java-format to 1.24.0,
the pinned spotless-lib 4.10.4 to 1.36.1 (spike, 2026-11-11).

Spotless's default is the newest release that runs on the JVM evaluating it: the build's (on Gradle, the daemon's).
The server runs the formatter on the build's compiling JDK (`.lathe/java-home`), which a Gradle toolchain can make
older, so a default can need a newer Java than the server has (spring-petclinic: the Gradle daemon on JDK 25 picks
google-java-format 1.36.1, built for Java 21; the toolchain is Java 17).
Lathe cannot ask Spotless for another JVM's default (it reads the running JVM's version once, and has no public
lookup), and on Gradle the compiling JDK is known only after the jars are resolved.
So the sync compares the formatter's class-file version with the server's JDK and warns, on every run, naming both
remedies: declare a release that runs there, or run the server on a newer JDK with `LATHE_JAVA_HOME`.

The Eclipse formatter's jars are the ones the Spotless lockfile for its release lists, each resolved without its
dependencies; the other engines resolve the formatter's runtime closure.

### Every sync resolves it

When `lathe-style.json` declares a pinned engine, the sync completes the spec from Spotless's defaults, resolves its
jars through the build tool, and writes the completed spec, classpath and indent included, to `.lathe/style.json`,
with the formatter's javac grants in `jvm.args`:

- **Maven** (`lathe:sync`): through Aether with the build's repository session, as `FormatterResolver` resolves a
  Spotless-detected formatter today.
  The declaration takes precedence over Spotless detection, as the committed file does now, so Spotless's formatter
  is not resolved when the file declares one.
- **Gradle** (`latheSync`): the root project reads `lathe-style.json` at configuration time through
  `providers.fileContents`, so the configuration cache is invalidated when it changes.
  A `latheFormatter` configuration adds the formatter's coordinates in `withDependencies`, which runs when it resolves:
  there it loads Spotless's step classes (the applied plugin's, else a `latheSpotless` configuration holding the
  pinned libraries) and reads the defaults and the lockfile.
  The spike (Gradle 9.8, 2026-11-11) confirmed this resolves on a configuration-cache store, is reused on the next
  run, and holds with Isolated Projects on.

The shared part, in `lathe-install`'s `FormatterSteps`: the engine table (each engine's Spotless step class,
formatter coordinates, javac grants and Eclipse lockfile), the defaults read from the step classes, the jar
coordinates to resolve, the indent derived from the engine, and parsing the declaration.
The build-tool part stays in each front-end: where Spotless's classes come from, and how the jars are resolved.

### Indentation follows the formatter

The sync derives `.lathe/style.json`'s `indent` from the formatter in effect, as the Maven sync does for Spotless today:
`google` style 2/4, `aosp` and `palantir` 4/8, and `editorconfig` for the rest.
A committed `indent` still wins, section by section.

### The server takes the classpath from the generated spec

`WorkspaceStyle.read` keeps "committed wins, section by section", with one refinement: when the committed formatter has
no classpath and the generated one is its completion (the same engine, the same version or none declared, and every
declared option the same), the server uses the generated spec.
A committed spec that does not match what the last sync resolved is unresolved, and the server says to run the sync.

The editor's global default formatter (`initializationOptions.lathe.style.formatter`) is removed: formatting comes only
from the project.

The server picks its formatter at startup, so a change takes effect after the sync and a server restart, as on Maven
today (a pinned formatter's javac grants are launcher arguments).
Its messages name what is actually missing: not resolved yet (run the sync, then restart), or no formatter declared
(name one in `lathe-style.json`).

### `lathe-style.json` is a sync input

The sync lists `lathe-style.json` among the fingerprinted build files when it exists, and the server watches it even
when it did not exist at the last sync, so creating or editing it raises the same "sync needed" prompt a build-file
change does.

### Gradle: Spotless is noticed, not read

Gradle's Spotless DSL keeps its settings out of reach (above), so the Gradle sync does not detect the formatter.
When a project applies Spotless and no `lathe-style.json` declares a formatter, `latheSync` warns once, with a
`lathe-style.json` template to fill in from the Spotless configuration; the template always carries an explicit
version.
Each project records whether it applies Spotless in its facts, so the warning sees every project without crossing
project boundaries (Isolated Projects).

Defaults come from the root project's Spotless only: under Isolated Projects the root cannot load another project's
plugin classes.
When Spotless is applied in subprojects only, a declaration without a version takes the pinned Spotless's default,
which may not be the build's: the sync warns until the version is declared, and the template carries one.

The formatter's jars are resolved leniently, so a sync never fails a build; any jar that does not resolve leaves the
formatter unresolved (formatting off, with a warning), rather than a classpath that fails at format time.
The task, not the configuration, reports a declaration it could not complete, so a reused configuration cache still
shows the warning.

## Alternatives

- **Detecting the formatter on Gradle** from Spotless's resolution: wrong or incomplete on real builds (table above).
- **Reading Spotless's internal model by reflection**: breaks across Spotless releases.
- **A per-user default formatter** (a file the sync reads, or the editor setting): formats code the project never asked
  to format; a personal `lathe-style.json` covers the deliberate case per project.
- **A Gradle DSL** (`lathe { formatter { googleJavaFormat("1.28.0") { aosp() } } }` in `settings.gradle.kts`):
  idiomatic for Gradle teams, but Gradle-only and unusable from an init script.
  It stays a possible addition that feeds the same spec.
- **Resolving the formatter in the subproject that applies Spotless** and handing the jars to the root through the
  facts: exact defaults for subproject-only Spotless, at the cost of a cross-project path; a follow-up if the explicit
  version proves a burden.

A team keeping the formatter version in Spotless and in `lathe-style.json` changes two places on a bump;
the guide documents the version catalog (Gradle) or a property (Maven) as the single source.

## Plan

One pull request, a commit per step.

1. **`FormatterSteps` into `lathe-install`**, with the pinned Spotless version: a refactor, Maven unchanged.
2. **The server**: the merge rule in `WorkspaceStyle`, the messages, watching `lathe-style.json`, and no editor
   default formatter.
3. **`lathe:sync` resolving a declaration.** This alone brings pinned formatters to Maven projects without Spotless.
4. **`latheSync` resolving the same declaration**, and the Spotless warning.
5. **Clients and docs**: Neovim's `setup()` drops `style.formatter`; the guides; this document moves to `docs/done`.

## Tests

- `lathe-install` unit tests: each engine's defaults and jar coordinates, read from a real spotless-lib; the indent
  each engine implies; parsing a declaration.
- `WorkspaceStyle`: the committed spec without a classpath takes the generated one's when it is its completion, and not
  when the engine, the version or an option differs.
- Maven invoker fixture: a project without Spotless declaring google-java-format in `lathe-style.json`; the sync
  writes the classpath, and the server formats a file as google-java-format does.
- Gradle TestKit: the same declaration, resolved by `latheSync` with the configuration cache on; with Spotless applied
  and no declaration, the warning.
