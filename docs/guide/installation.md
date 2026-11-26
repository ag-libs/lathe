# Installing Lathe

Install a client for your editor once.
In each project, enable Lathe in the build and build once to install the server.
The client starts the server that belongs to the project you open.

| Your build | First setup |
|---|---|
| Maven 3.6.3+ | [Register the extension](maven.md#method-1--maven-extension-recommended), then [run the first build](maven.md#initialize). |
| Gradle 8.8+ | [Apply the settings plugin or use a personal init script](gradle.md#setup), then [run the first build](gradle.md#run-the-first-build). |
| OpenJDK | [Build the JDK and sync the checkout](openjdk.md#setup). |

Choose your [Neovim](editors/neovim.md#install), [VS Code or Cursor](editors/vscode.md), [Emacs](editors/emacs.md), or [Zed](editors/zed.md) client.
An AI agent can use the same workspace through [MCP](ai-agents.md).

Once your editor is running, use the sections below to [choose its JDK](#choosing-the-server-jdk-lathe_java_home), [set JVM options](#tuning-the-server-jvm-lathe_jvm_opts), or [configure formatting](#choosing-overriding-or-opting-out-of-the-formatter).

## What and where Lathe writes

Lathe keeps project-specific data under `.lathe/` at the root of the build.
Lathe writes a `.gitignore` inside it, so git ignores the directory with no change to your project.
Tools that scan the whole tree without reading `.gitignore` need their own exclusion.
[Exclude `.lathe/` from checks](troubleshooting.md#build-checks-and-compiler-settings)
Several projects can share downloaded sources and installed server versions under `~/.cache/lathe/`.

| Location | What you’ll find |
| --- | --- |
| `.lathe/workspace.json` | The modules, their sources and dependencies, and the version of the server that reads them. |
| `.lathe/<module>/` | Compiler inputs, classes, generated sources, and launch settings for running mains and tests. |
| `.lathe/lathe-launcher.sh` and `lathe-mcp-launcher.sh` | Launchers for the editor and coding agent, pinned to this project’s server. On Windows the files end in `.cmd`. |
| `.lathe/lathe-sync.sh` | Repeats the sync with the build tool and JDK that set up the workspace. On Windows use `lathe-sync.cmd`. |
| `.lathe/java-home` and `.lathe/jvm.args` | The JDK and JVM options the launcher uses. |
| `~/.cache/lathe/` | Installed server versions, extracted library and JDK sources, type indexes, and MCP session logs. |

To move the machine cache on Maven, set `-Dlathe.cache=<dir>` for every build, for example in `MAVEN_OPTS` in your shell profile, so the editor's sync script uses it too.
`.mvn/maven.config` also works, but it applies to everyone who builds the project.
MCP session logs stay in `~/.cache/lathe/logs/`.
In Neovim, also start the editor with `LATHE_CACHE=<dir>` so dependency sources there still open read-only.
In VS Code, add the directory to `files.readonlyInclude`.
[Read-only sources in VS Code](editors/vscode.md#read-only-dependency-sources)

The project’s launcher starts the server version selected by its build.
The `latest/` launcher in the machine cache is available for a [user-wide MCP registration](ai-agents.md).
A dependency needs a published sources JAR for navigation to open its source.
[Troubleshoot navigation](troubleshooting.md#navigation)

## Tuning the server JVM (`LATHE_JVM_OPTS`)

To limit the server’s heap, set `LATHE_JVM_OPTS` in the environment you start the editor or agent from.
This example caps each server process at 4 GB.

```sh
export LATHE_JVM_OPTS="-Xmx4g"
```

Each editor or agent session starts its own JVM.
The same variable can supply additional JVM options, including module grants an annotation processor needs.
If you enable JVM logging, send it to stderr or a file because the editor uses stdout to communicate with the server.

The build records the module grants it already declares in `.lathe/jvm.args`.
Syncing also adds the grants required by the project’s formatter.
Restart the server after those settings change.
[Compiler plugin JVM settings](troubleshooting.md#build-checks-and-compiler-settings)

## Choosing the server JDK (`LATHE_JAVA_HOME`)

The build records the server JDK in `.lathe/java-home`.
Maven records the JDK running Maven.
Gradle records the newest compilation JDK at or above 17, falling back to the daemon JDK.
An OpenJDK sync records the JDK you built.

The launcher chooses the JDK in this order:

1. `LATHE_JAVA_HOME`, if exported in the environment your editor launches from — an explicit override;
2. `.lathe/java-home`, the JDK the build recorded;
3. `java` on `PATH`, if neither is available (with a warning if a chosen JDK has no `bin/java`).

```bash
# Force a specific JDK regardless of what the build used:
export LATHE_JAVA_HOME="$HOME/.sdkman/candidates/java/26-tem"
```

Choose a JDK at least as new as the highest Java release your modules target.
The server uses the captured compile options for modules targeting older releases.

## Choosing, overriding, or opting out of the formatter

### What runs automatically

The formatter is the project's own; there is no editor-wide formatter setting.
On Maven, the sync derives it from your `spotless-maven-plugin` `<java>` config, or else from `spring-javaformat-maven-plugin` (see [spring-javaformat](#spring-javaformat)).
On Gradle, it detects a root project that applies the `io.spring.javaformat` plugin.
Elsewhere — Spotless on Gradle, or a build with neither — you declare it in a `lathe-style.json`, as described under [Declaring it](#declaring-it), and the sync resolves it the same way.

| Spotless step | Lathe engine | Range formatting | Notes |
|---|---|---|---|
| `googleJavaFormat` | `google` / `aosp` (by `<style>`) | yes | honours `version`, `groupArtifact`, `reorderImports`, `reflowLongStrings`, `formatJavadoc` |
| `palantirJavaFormat` | `palantir` | yes | honours `version`, `style`, `formatJavadoc`; does not run on JDK 27 (see below) |
| `eclipse` | `eclipse` | yes | honours `version` and the `<file>` profile (Eclipse XML export or `.prefs`); member sorting is not applied |
| anything else | `none` | — | sync warns; select an external command below |

palantir-java-format has no release that runs on JDK 27 yet (2.103.0 is the latest as of November 2026, [palantir-java-format#1785](https://github.com/palantir/palantir-java-format/issues/1785)): it reads a javac field JDK 27 removed.
Lathe runs the formatter on your build's JDK (`.lathe/java-home`, unless `LATHE_JAVA_HOME` overrides it), so it fails exactly where `spotless:apply` itself fails; the buffer is left unchanged and the first failure names the cause.

The formatter is the exact release the project's Spotless pins (its `<version>`, else that Spotless version's default), resolved by sync and run in-process from an isolated classloader — no Maven at format time, so a format takes milliseconds (the first one in a session also loads the formatter).
A change of formatter takes effect when the sync finishes.
An editor whose session started without a formatter needs a restart of the language server to format, and Lathe says so.

Lathe runs the **formatter step only**.
Other steps in the same `<java>` section — `importOrder`, `removeUnusedImports` (beyond what google-java-format and palantir already do), `licenseHeader`, `formatAnnotations`, `toggleOffOn` (`spotless:off` regions), whitespace steps — and Spotless `<includes>`/`<excludes>` are **not** applied, so a saved file can still differ from `spotless:apply`.
If you need every step on save, use the `command-file` recipe below.

Other limits:

- One formatter serves the whole workspace: the root project's Spotless, or, when the root only manages it (`<pluginManagement>`), the one every Java module runs.
  If some Java modules run no Spotless or a different configuration, formatting is off and the sync says so.
- An Eclipse profile must be a local file (Spotless also accepts URLs and classpath resources), and only Eclipse releases Spotless pins with a lockfile are supported.
- After editing an Eclipse profile, restart the language server.
- If the formatter cannot run (a missing jar, an unsupported JDK), the first failure shows a message with the reason; later ones are only logged.

### spring-javaformat

A project that formats with spring-javaformat (`spring-javaformat-maven-plugin`, or the `io.spring.javaformat` Gradle plugin) gets the `spring` engine, at the plugin's release.
The sync resolves the formatter and its Eclipse runtime, and the server runs them in-process, whole files and ranges.
It formats as the plugin's `apply` goal does:

- the settings come from `.springjavaformatconfig` (the Java baseline, tabs or spaces), read from the module's directory upwards, as the plugin reads them from the project's; without one, the defaults (tabs);
- one pass over the file; source that does not parse is left as it is;
- indentation as you type follows `.editorconfig`, as for Eclipse.

When a Maven build runs both Spotless and spring-javaformat, Spotless wins.
A Gradle build that applies both formats nothing, and the sync warns, until `lathe-style.json` declares the formatter: Lathe cannot read Spotless's settings on Gradle.
On Maven, one release serves the workspace: the root's plugin, or the one every Java module runs.
On Gradle, only a root project that applies the plugin is detected; a build that applies it only in subprojects declares `{ "formatter": { "engine": "spring" } }`, which then takes the release Lathe pins (0.0.47) unless it names a `version`.

### Declaring it

A `lathe-style.json` at the repo root names the formatter, for any build tool:

```json
{ "formatter": { "engine": "google", "version": "1.28.0", "options": { "style": "AOSP" } } }
```

- `engine`: `google`, `aosp`, `palantir`, `eclipse`, `spring`, or one of the engines below that need no jars.
- `version` (optional): the formatter's release, not Spotless's — see [The formatter version](#the-formatter-version).
- `options` (optional): the Spotless step's options from the table above (`reorderImports`, `reflowLongStrings`, `formatJavadoc`, `style`, `groupArtifact`); for `eclipse`, `file` names the profile, relative to the repo root.

#### The formatter version

`version` names the release of the formatter itself: `1.28.0` for google-java-format, `2.102.0` for palantir-java-format, `4.41` for Eclipse, `0.0.47` for spring-javaformat.
It is what the jars are resolved at, so pin it when the editor must format exactly as your CI does.

Left out, it is the default Spotless picks:

- the default of the build's Spotless when the build has one (on Gradle, only a Spotless applied in the root project is seen; when only subprojects apply it, declare the version, and the sync warns until you do);
- otherwise the default of the Spotless release Lathe pins.

For `spring`, it is the release of the build's spring-javaformat plugin (on Gradle, the root's), else the release Lathe pins.

Spotless picks the newest release that runs on the JVM it runs on: the JVM that runs the build (on Gradle, the daemon's JDK, not a toolchain's).
The language server runs the formatter on the build's compiling JDK instead (`.lathe/java-home`), which a Gradle toolchain can make older.
When the formatter needs a newer Java than that, the sync says so on every run, for example:

```text
[sync] formatter google 1.36.1 needs Java 21, but the server runs on Java 17 (the build's compiling JDK, unless
LATHE_JAVA_HOME names another): declare a version that runs on Java 17 in lathe-style.json, or set LATHE_JAVA_HOME
to a JDK 21 or later
```

The sync reads `LATHE_JAVA_HOME` when the build sees it, so setting it there also silences the warning.
Declare a release that runs on that JDK, or run the server on a newer one with [`LATHE_JAVA_HOME`](#choosing-the-server-jdk-lathe_java_home); the second keeps the formatter your build runs.

Every sync resolves the declared formatter's jars through your build (its repositories, mirrors and credentials) and writes them to the generated `.lathe/style.json`, with the indentation the formatter implies; nobody writes a classpath.
Editing `lathe-style.json` makes the editor ask for a sync, as a build-file change does.

Commit the file so the whole team gets the formatter, or keep a personal one out of version control with `.git/info/exclude`.

On a Gradle build that applies Spotless, the sync warns until a `lathe-style.json` declares a formatter:
Lathe cannot read Spotless's settings there.
Declare the one Spotless runs, or `none` to keep formatting off.

The file overrides the generated `.lathe/style.json` **section by section**: a `formatter` section replaces the detected formatter, and an `indent` section replaces the detected indent, while a section you leave out still comes from sync.
Edit `lathe-style.json`, not `.lathe/style.json`, which every sync regenerates.

Engines that need no jars:

```jsonc
// turn Lathe formatting off for this project:
{ "formatter": { "engine": "none" } }

// a tool that reads Java on stdin and writes formatted Java to stdout (here prettier-java):
{ "formatter": { "engine": "command", "command": ["npx", "prettier", "--parser", "java"] } }

// a tool that formats a file in place — here the project's full Spotless, every step included:
{ "formatter": { "engine": "command-file",
                 "command": ["%MVN%", "-pl", "%MODULE%", "spotless:apply", "-DspotlessFiles=\\Q%FILE%\\E"] } }
```

- `command` pipes the buffer through the command (10 s timeout).
- `command-file` writes the buffer to a hidden temporary file beside the edited one (so Spotless's includes still match it), runs the command, and reads the result back (60 s timeout).
  Its tokens: `%MVN%` → the build tool that ran your last build: on Maven, `mvnd`, the wrapper's Maven or `mvn`; on Gradle, the Gradle distribution's `gradle`; `%MODULE%` → the edited file's module directory; `%FILE%` → the temporary file.
  Running the build per save takes seconds; with `mvnd`, or a running Gradle daemon, it is far less.
- Both run from the workspace root, format whole files only, and pay process startup on every format.

## Coexisting with another Java language server (jdtls)

You can run Lathe side by side with Eclipse JDT LS (`nvim-jdtls` / the VS Code Java extension) on the same project — useful when migrating, or for comparing behaviour.

jdtls imports a Maven or Gradle build that registers Lathe as it would without Lathe: on Maven, Lathe stays out of jdtls's embedded Maven by itself ([Lathe for Maven](maven.md#coexisting-with-jdtls)).
On every build, keep jdtls from indexing `.lathe/`:

`.lathe/` is generated build output.
Add `**/.lathe/**` to jdtls's `java.import.exclusions` so it never scans the mirror — jdtls scopes imports with explicit globs, not `.gitignore`, so gitignoring `.lathe/` alone is not enough:

```jsonc
"java.import.exclusions": [
  "**/node_modules/**",
  "**/.metadata/**",
  "**/.lathe/**"        // add this line, keep jdtls's defaults
]
```

In VS Code this goes in `settings.json`; with `nvim-jdtls` it is the `settings.java.import.exclusions` table you pass to `start_or_attach`.

> For a systematic behavioural comparison of the two servers, the `dev/jdtls_diff.py` differential
> harness drives both over identical probe points and reports divergences — see
> [the differential-testing design](../done/lathe-jdtls-differential-testing.md).
