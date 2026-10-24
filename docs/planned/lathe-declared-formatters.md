# Lathe — Declared Formatters

## Status

Proposed (2026-10-24), not implemented.
Scheduled after the [Gradle plugin](lathe-gradle-support.md) is done; the plugin ships without formatter support.
Builds on [Pinned Formatters In-Process](../done/lathe-pinned-formatters.md), which runs the formatter, and
[Workspace-Scoped Style](../done/lathe-workspace-style.md), which defines `lathe-style.json` and `.lathe/style.json`.

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

- **Personal setups** (the Maven `.mvn/extensions.xml` or a Gradle init script), where nobody edits the build files.

A committed `lathe-style.json` can already name a formatter, but its formatter section replaces the generated one
whole, so a pinned engine declared there has no classpath and the server reports "no resolved classpath".
Only the `command` and `command-file` engines work from it today.

## Goal

A team declares its formatter once, in a file Lathe reads the same way for every build tool, and every sync resolves
its jars, so the server formats exactly as the build does.

## Design

### The declaration: a committed `lathe-style.json` without a classpath

The team commits the formatter's engine, version and options; the classpath is left out:

```json
{
  "formatter": {
    "engine": "google",
    "version": "1.28.0",
    "options": { "style": "AOSP", "reflowLongStrings": "true" }
  }
}
```

The engines, option names and defaults are those of [Pinned Formatters](../done/lathe-pinned-formatters.md#style-schema):
`google`, `aosp`, `palantir`, `eclipse` (`options.file` names the Eclipse settings file, relative to the workspace root),
and the `command` engines, which need no resolution.
An option left out takes Spotless's own default, as on Maven today.

### Every sync resolves it

When the committed file declares a pinned engine, the sync resolves that formatter's jars through the build tool's
artifact resolution and writes the completed spec, classpath included, to `.lathe/style.json`, with the formatter's
javac grants in `jvm.args`:

- **Maven** (`lathe:sync`): through Aether, as `FormatterResolver` resolves a Spotless-detected formatter today.
  The declaration takes precedence over Spotless detection, as the committed file does now.
- **Gradle** (`latheSync`): through a configuration the root project declares at configuration time, from the
  committed file it reads then (Isolated-Projects-safe; the file is a configuration input).

### The server takes the classpath from the generated spec

`WorkspaceStyle.read` keeps "committed wins, section by section", with one refinement: when the committed formatter
has no classpath and the generated one declares the same engine and version, the server takes the generated classpath.
A committed spec that does not match what the last sync resolved keeps today's "no resolved classpath" message, which
names the sync command.

### Shared resolution in `lathe-install`

The engine table (each engine's Spotless step class, formatter coordinates, javac grants and Eclipse lockfile) and the
defaults read from Spotless's step classes move from `lathe-maven-plugin`'s `FormatterResolver` into `lathe-install`
as `FormatterSteps`, used by both front-ends.
Spotless's defaults and the Eclipse lockfile come from the build's Spotless when it has one; a build without Spotless
uses a Spotless version Lathe pins for that purpose.

## Alternatives

- **A Gradle DSL** (`lathe { formatter { googleJavaFormat("1.28.0") { aosp() } } }` in `settings.gradle.kts`):
  idiomatic for Gradle teams, but Gradle-only and unusable from an init script.
  It stays a possible addition that feeds the same spec, if users want the formatter in the build file.
- **A property** (`lathe.formatter=google:1.28.0` in `gradle.properties`): options and a settings file do not fit.
- **Detecting the formatter on Gradle** from Spotless's resolution: wrong or incomplete on real builds (table above).
- **Reading Spotless's internal model by reflection**: breaks across Spotless releases.

A team keeping the formatter version in Spotless and in `lathe-style.json` changes two places on a bump;
the guide documents the version catalog (Gradle) or a property (Maven) as the single source.

## Plan

1. **`FormatterSteps` into `lathe-install`**, with the pinned-Spotless fallback: a refactor, Maven unchanged.
2. **The merge rule in `WorkspaceStyle`, and `lathe:sync` resolving a committed declaration.** This alone brings
   pinned formatters to Maven projects without Spotless.
3. **`latheSync` resolving the same declaration**, giving the Gradle plugin formatter support.

## Tests

- `lathe-install` unit tests: each engine's defaults and coordinates, with and without a build Spotless.
- `WorkspaceStyle`: the committed spec without a classpath takes the generated one's when engine and version match,
  and does not when they differ.
- Maven invoker fixture: a project without Spotless declaring google-java-format in `lathe-style.json`; the sync
  writes the classpath, and the server formats a file as google-java-format does.
- Gradle TestKit: the same declaration, resolved by `latheSync`.
