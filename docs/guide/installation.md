# Installing Lathe

Lathe has two parts, set up independently and in either order.

1. **The build.** Lathe works through your project's own build, which writes what the language server
   reads to `.lathe/` and installs the server. Set it up once per project, for your build:

   | Build | Guide |
   |---|---|
   | Maven 3.6.3+ | [Lathe for Maven](maven.md) |
   | Gradle 8.8+ | [Lathe for Gradle](gradle.md) |
   | The OpenJDK `make` build | [Lathe for OpenJDK](openjdk.md) |

2. **The editor client**, for [Neovim](editors/neovim.md#install), [VS Code](editors/vscode.md),
   [Emacs](editors/emacs.md) or [Zed](editors/zed.md); an AI agent uses the [MCP server](ai-agents.md).
   A client that finds no `.lathe/` yet asks you to run the build.

The rest of this page applies to every build.

## What and where Lathe writes

Lathe writes to two locations: `.lathe/` inside the project, and `~/.cache/lathe/` on the machine.

**In the project — `.lathe/`** (per-build, add to `.gitignore`), written by the build as it compiles and
tests; each build's guide says when ([Maven](maven.md#what-the-build-writes-to-lathe),
[Gradle](gradle.md#what-the-build-keeps-current)):

- per module, the `javac` inputs the language server replays (`lsp-params-*.json`), per-source compile
  stamps (`lsp-stamps-*.json`), the compiled classes and resources, and the launch templates for
  running and debugging a `main` (`main-launch.json`) and the tests (`test-launch.json`);
- `workspace.json`: the modules, what they depend on, their library sources, and the JDK;
- `lathe-sync.sh` (`.cmd` on Windows), which re-runs the same build tool and JDK for the editor's sync;
- `lathe-launcher.sh` and `lathe-mcp-launcher.sh` (`.cmd` on Windows), with their argfiles: copies of the
  launchers for the server version the project pins, which every editor client and the MCP agent start;
- `java-home`, the JDK the server runs on ([below](#choosing-the-server-jdk-lathe_java_home)), and
  `jvm.args`, module grants for the server JVM ([below](#tuning-the-server-jvm-lathe_jvm_opts));
- from a Maven build also each module's `test-filter.json` (the classes its test run includes) and the
  detected formatter, `style.json` ([below](#choosing-overriding-or-opting-out-of-the-formatter)).
- the language server's own writes: it compiles saved and outside-changed sources into each module's
  mirrored `classes/`, `test-classes/` and `generated-sources/`, saves each module's discovered tests in
  `test-discovery.json`, and takes `write.lock` so that several Lathe servers on one workspace (an editor's
  and an agent's) take turns writing.

**On the machine — `~/.cache/lathe/`** (machine-wide, regenerable):

To move it on Maven, pass `-Dlathe.cache=<dir>` to the build, which relocates what sync writes (`servers/`,
`latest/`, `deps/`, `jdks/`, `type-index/`); Gradle has no such setting yet.
The property is not forwarded to the launchers, so MCP session logs stay in `~/.cache/lathe/logs/`;
set `LATHE_CACHE=<dir>` for the Neovim client so it still treats the moved cache as read-only
dependency sources.

- `servers/<version>/` — the launcher scripts and argfiles for that version (the jars themselves stay
  in the build's own cache, `~/.m2` for Maven, which the argfiles name), shared across every workspace that pins it. Each project's `.lathe/lathe-launcher.sh` (`.cmd` on Windows) is a copy of the matching version's launcher;
  version-correct discovery is per-workspace, not through any machine-global pointer.
- `latest/` — copies of the newest installed server version's launchers. Not used by version-correct clients;
  it exists only for a user-global MCP registration that has no project context (see
  [ai-agents.md](ai-agents.md)).
- `deps/` and `jdks/` — dependency and JDK **source** trees. The build resolves each dependency's
  `-sources` JAR through its own repositories and extracts it to `deps/<groupId>/<artifactId>/<version>/` (the
  `~/.m2` layout, without the JAR's `META-INF/maven/` build metadata), and extracts the JDK's sources when available;
  these back go-to-definition into library and JDK code. A dependency with no published `-sources` JAR
  is recorded as missing and simply not navigable.
- `type-index/` — the symbol index behind workspace symbol search.
- `logs/` — one log file per MCP server session (`mcp-<timestamp>-<pid>.log`, tagged with the agent's session when it names one); a starting server keeps the newest 100.

Everything under `~/.cache/lathe/` is derived and safe to delete; the next `sync`/build rebuilds what
it needs.

## Tuning the server JVM (`LATHE_JVM_OPTS`)

The language server analyzes your code with an **in-process** javac, so it replays the javac options
your build captured.

**Maven: forked-compiler `-J` flags are dropped.** `-J` options (for example `-J--add-exports=…` or `-J-Xmx…`) only reach a **forked** `javac`
executable, which Maven uses when a module sets `<fork>true</fork>` on `maven-compiler-plugin` — often
to run Error Prone. The in-process javac API has no launcher to receive them and would reject them as
`invalid flag`, so Lathe drops them (just as Maven's own non-forked compiler ignores them). When this
happens the server logs one line per module naming what it dropped.

The module grants your build gives its javac plugins (Error Prone, NullAway, ...) still reach the
server: the build copies them into `.lathe/jvm.args`, which the launcher passes to the server JVM.
On Maven those are every `--add-exports`/`--add-opens` from forked `-J` options and from
`.mvn/jvm.config`, and the `jdk.compiler` grants in `MAVEN_OPTS` (the same under `mvn` and `mvnd`);
on Gradle, the ones in `org.gradle.jvmargs`, which reach a compile running in the Gradle daemon, and in a forked
compile's JVM arguments (`options.forkOptions.jvmArgs` of `compileJava` and `compileTestJava`).
Sync also adds the grants the project's pinned google-java-format or palantir-java-format needs, even when the build
declares none (see [formatting](#choosing-overriding-or-opting-out-of-the-formatter)).
Other Maven `-J` flags (such as `-J-Xmx…`, sized for a short-lived compiler) are not carried over.
The server reads `.lathe/jvm.args` only at startup, so restart it (`:lsp restart` in Neovim) after a sync changes the file.

If an annotation processor or javac plugin needs JVM access your build does not declare — or you
simply want to tune heap or GC — set `LATHE_JVM_OPTS` in the environment your editor launches from.
It is expanded after `.lathe/jvm.args` and ahead of Lathe's own arguments:

```bash
export LATHE_JVM_OPTS="-Xmx4g"
# or grant access the build does not declare:
export LATHE_JVM_OPTS="--add-opens jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED"
```

**Memory.** Lathe sets no heap limit, so the JVM takes its default maximum: a quarter of the machine's memory.
On a large reactor its heap grows with use rather than collecting early: before Lathe released idle module sessions,
an MCP session on Helidon reached 9.6 GB after three `verify_change` calls on a widely used type, and ran the same work at the same speed capped at 2 GB.
Lathe now releases a module's session after 5 idle minutes, and earlier when the heap runs short, so a capped server completes such work.
If memory matters, cap it, and let an idle server return what it no longer uses (the same variable reaches the editor's and the MCP launcher):

```bash
export LATHE_JVM_OPTS="-Xmx4g -XX:G1PeriodicGCInterval=60000"
```

The server talks to the editor over stdout, so the launcher sends the JVM's own output (startup failures, `-Xlog` warnings) to stderr, where it lands in the editor's LSP log (`:LspLog` in Neovim).
If you add an `-Xlog` option, give it a `stderr` or file output (e.g. `-Xlog:gc:stderr`); the JVM's default `stdout` target would corrupt the protocol stream.

## Choosing the server JDK (`LATHE_JAVA_HOME`)

The server's in-process javac must be at least as new as the Java version your project targets — open a
Java 26 module under a Java 21 `java` and every file shows parse errors. So Lathe does **not** just use
whatever `java` is on `PATH`: the build records a JDK in `.lathe/java-home`, and the launcher runs
the server under it. On Maven that is the JDK Maven ran on; on Gradle, the newest JDK any project
compiles with (17 or newer), else the one Gradle runs on. No configuration is needed — build your
project and the server matches it.

The launcher chooses the JDK in this order:

1. `LATHE_JAVA_HOME`, if exported in the environment your editor launches from — an explicit override;
2. `.lathe/java-home`, the JDK the build recorded;
3. `java` on `PATH`, if neither is available (with a warning if a chosen JDK has no `bin/java`).

```bash
# Force a specific JDK regardless of what the build used:
export LATHE_JAVA_HOME="$HOME/.sdkman/candidates/java/26-tem"
```

For a multi-module reactor the one server JVM runs the highest JDK the build used, which down-compiles
the lower-release modules via `--release`. (The launcher reads `.lathe/java-home` from the nearest `.lathe/`
in its working directory or above it, so a client that starts it in a module directory gets the root's JDK.)

## Choosing, overriding, or opting out of the formatter

### What runs automatically

The formatter is the project's own; there is no editor-wide formatter setting.
On Maven, the sync derives it from your `spotless-maven-plugin` `<java>` config.
Elsewhere — a Gradle build, or Maven without Spotless — you declare it in a `lathe-style.json`, as
described under [Declaring it](#declaring-it), and the sync resolves it the same way.

| Spotless step | Lathe engine | Range formatting | Notes |
|---|---|---|---|
| `googleJavaFormat` | `google` / `aosp` (by `<style>`) | yes | honours `version`, `groupArtifact`, `reorderImports`, `reflowLongStrings`, `formatJavadoc` |
| `palantirJavaFormat` | `palantir` | yes | honours `version`, `style`, `formatJavadoc`; does not run on JDK 27 (see below) |
| `eclipse` | `eclipse` | yes | honours `version` and the `<file>` profile (Eclipse XML export or `.prefs`); member sorting is not applied |
| anything else | `none` | — | sync warns; select an external command below |

palantir-java-format has no release that runs on JDK 27 yet (2.102.0 is the latest as of October
2026): it reads a javac field JDK 27 removed.
Lathe runs the formatter on your build's JDK (`.lathe/java-home`, unless `LATHE_JAVA_HOME` overrides
it), so it fails exactly where `spotless:apply` itself fails; the buffer is left unchanged and the
first failure names the cause.

The formatter is the exact release the project's Spotless pins (its `<version>`, else that Spotless
version's default), resolved by sync and run in-process from an isolated classloader — no Maven at
format time, so a format takes milliseconds (the first one in a session also loads the formatter).
A change of formatter takes effect after the sync and a restart of the language server.

Lathe runs the **formatter step only**.
Other steps in the same `<java>` section — `importOrder`, `removeUnusedImports` (beyond what
google-java-format and palantir already do), `licenseHeader`, `formatAnnotations`, `toggleOffOn`
(`spotless:off` regions), whitespace steps — and Spotless `<includes>`/`<excludes>` are **not** applied,
so a saved file can still differ from `spotless:apply`.
If you need every step on save, use the `command-file` recipe below.

Other limits:

- One formatter serves the whole workspace: the root project's Spotless, or, when the root only manages it
  (`<pluginManagement>`), the one every Java module runs.
  If some Java modules run no Spotless or a different configuration, formatting is off and the sync says so.
- An Eclipse profile must be a local file (Spotless also accepts URLs and classpath resources), and only
  Eclipse releases Spotless pins with a lockfile are supported.
- After editing an Eclipse profile, restart the language server.
- If the formatter cannot run (a missing jar, an unsupported JDK), the first failure shows a message with
  the reason; later ones are only logged.

### Declaring it

A `lathe-style.json` at the repo root names the formatter, for any build tool:

```json
{ "formatter": { "engine": "google", "version": "1.28.0", "options": { "style": "AOSP" } } }
```

- `engine`: `google`, `aosp`, `palantir`, `eclipse`, or one of the engines below that need no jars.
- `version` (optional): the formatter's release, not Spotless's — see [The formatter version](#the-formatter-version).
- `options` (optional): the Spotless step's options from the table above (`reorderImports`,
  `reflowLongStrings`, `formatJavadoc`, `style`, `groupArtifact`); for `eclipse`, `file` names the
  profile, relative to the repo root.

#### The formatter version

`version` names the release of the formatter itself: `1.28.0` for google-java-format, `2.102.0` for
palantir-java-format, `4.41` for Eclipse.
It is what the jars are resolved at, so pin it when the editor must format exactly as your CI does.

Left out, it is the default Spotless picks:

- the default of the build's Spotless when the build has one (on Gradle, only a Spotless applied in the
  root project is seen; when only subprojects apply it, declare the version, and the sync warns until
  you do);
- otherwise the default of the Spotless release Lathe pins.

Spotless picks the newest release that runs on the JVM it runs on: the JVM that runs the build (on
Gradle, the daemon's JDK, not a toolchain's).
The language server runs the formatter on the build's compiling JDK instead (`.lathe/java-home`), which
a Gradle toolchain can make older.
When the formatter needs a newer Java than that, the sync says so on every run, for example:

```text
[sync] formatter google 1.36.1 needs Java 21, but the server runs on Java 17, the build's compiling JDK:
declare a version that runs on Java 17 in lathe-style.json, or set LATHE_JAVA_HOME to a JDK 21 or later
```

Declare a release that runs on that JDK, or run the server on a newer one with
[`LATHE_JAVA_HOME`](#choosing-the-server-jdk-lathe_java_home); the second keeps the formatter your build runs.

Every sync resolves the declared formatter's jars through your build (its repositories, mirrors and
credentials) and writes them to the generated `.lathe/style.json`, with the indentation the formatter
implies; nobody writes a classpath.
Editing `lathe-style.json` makes the editor ask for a sync, as a build-file change does.

Commit the file so the whole team gets the formatter, or keep a personal one out of version control
with `.git/info/exclude`.

On a Gradle build that applies Spotless, the sync warns until a `lathe-style.json` declares a formatter:
Lathe cannot read Spotless's settings there.
Declare the one Spotless runs, or `none` to keep formatting off.

The file overrides the generated `.lathe/style.json` **section by section**: a `formatter` section
replaces the detected formatter, and an `indent` section replaces the detected indent, while a section
you leave out still comes from sync.
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
- `command-file` writes the buffer to a hidden temporary file beside the edited one (so Spotless's
  includes still match it), runs the command, and reads the result back (60 s timeout).
  Its tokens: `%MVN%` → the build tool that ran your last build: on Maven, `mvnd`, the wrapper's Maven or `mvn`;
  on Gradle, the Gradle distribution's `gradle`; `%MODULE%` → the edited file's module directory; `%FILE%` → the
  temporary file.
  Running the build per save takes seconds; with `mvnd`, or a running Gradle daemon, it is far less.
- Both run from the workspace root, format whole files only, and pay process startup on every format.

## Coexisting with another Java language server (jdtls)

You can run Lathe side by side with Eclipse JDT LS (`nvim-jdtls` / the VS Code Java extension) on the
same project — useful when migrating, or for comparing behaviour.

On a Maven build, first keep Lathe's build injection out of jdtls's own Maven import, or jdtls answers
nothing: see [Lathe for Maven](maven.md#coexisting-with-jdtls-keep-lathe-out-of-its-maven-import).
On every build, keep jdtls from indexing `.lathe/`:

`.lathe/` is generated build output. Add `**/.lathe/**` to jdtls's `java.import.exclusions` so it never
scans the mirror — jdtls scopes imports with explicit globs, not `.gitignore`, so gitignoring `.lathe/`
alone is not enough:

```jsonc
"java.import.exclusions": [
  "**/node_modules/**",
  "**/.metadata/**",
  "**/.lathe/**"        // add this line, keep jdtls's defaults
]
```

In VS Code this goes in `settings.json`; with `nvim-jdtls` it is the `settings.java.import.exclusions`
table you pass to `start_or_attach`.

> For a systematic behavioural comparison of the two servers, the `dev/jdtls_diff.py` differential
> harness drives both over identical probe points and reports divergences — see
> [the differential-testing design](../done/lathe-jdtls-differential-testing.md).
