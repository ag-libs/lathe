# Installing Lathe

Lathe has two parts, set up independently and in either order.

1. **The build.** Lathe works through your project's own build, which writes what the language server
   reads to `.lathe/` and installs the server. Set it up once per project, for your build:

   | Build | Guide |
   |---|---|
   | Maven 3.6.3+ | [Lathe for Maven](maven.md) |
   | Gradle 8.8+ | [Lathe for Gradle](gradle.md) |
   | The OpenJDK `make` build | [Lathe for OpenJDK](openjdk.md) |

2. **The editor client**, for [Neovim](editors/neovim.md), [VS Code](editors/vscode.md),
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
- `logs/` — one log file per MCP server session (`mcp-<timestamp>-<pid>.log`); a starting server keeps the newest 100.

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
on Gradle, the ones in `org.gradle.jvmargs`, which reach a compile running in the Gradle daemon.
Sync also adds the grants the project's pinned google-java-format or palantir-java-format needs, even when the build
declares none (see [formatting](#choosing-overriding-or-opting-out-of-the-formatter)).
Other Maven `-J` flags (such as `-J-Xmx…`, sized for a short-lived compiler) are not carried over.
The server reads `.lathe/jvm.args` only at startup, so restart it (`:LspRestart` in Neovim) after a sync changes the file.

If an annotation processor or javac plugin needs JVM access your build does not declare — or you
simply want to tune heap or GC — set `LATHE_JVM_OPTS` in the environment your editor launches from.
It is expanded after `.lathe/jvm.args` and ahead of Lathe's own arguments:

```bash
export LATHE_JVM_OPTS="-Xmx4g"
# or grant access the build does not declare:
export LATHE_JVM_OPTS="--add-opens jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED"
```

**Memory.** Lathe sets no heap limit, so the JVM takes its default maximum: a quarter of the machine's
memory. On a large reactor its heap grows with use rather than collecting early: an MCP session on
Helidon reached 9.6 GB after three `verify_change` calls on a widely used type, and ran the same
work at the same speed capped at 2 GB. If memory matters, cap it, and let an idle server return what
it no longer uses (the same variable reaches the editor's and the MCP launcher):

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
the lower-release modules via `--release`. (Editor clients spawn the server with its working directory
set to the workspace root so the launcher can find `.lathe/java-home`.)

## Choosing, overriding, or opting out of the formatter

### What runs automatically

On Maven, the sync derives the formatter from your `spotless-maven-plugin` `<java>` config; a Gradle
build has no formatter detection yet (see [its limits](gradle.md#known-limits)), so use a committed
`lathe-style.json` as described under [Overriding it](#overriding-it):

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

Lathe runs the **formatter step only**.
Other steps in the same `<java>` section — `importOrder`, `removeUnusedImports` (beyond what
google-java-format and palantir already do), `licenseHeader`, `formatAnnotations`, `toggleOffOn`
(`spotless:off` regions), whitespace steps — and Spotless `<includes>`/`<excludes>` are **not** applied,
so a saved file can still differ from `spotless:apply`.
If you need every step on save, use the `command-file` recipe below.

Other limits:

- Only the root project's Spotless configuration is read; per-module Spotless configs are ignored.
- An Eclipse profile must be a local file (Spotless also accepts URLs and classpath resources), and only
  Eclipse releases Spotless pins with a lockfile are supported.
- After editing an Eclipse profile, restart the language server.
- If the formatter cannot run (a missing jar, an unsupported JDK), the first failure shows a message with
  the reason; later ones are only logged.

### Overriding it

Two places select the formatter, in precedence order:

1. **Per project: a committed `lathe-style.json`** at the repo root.
   It overrides the generated `.lathe/style.json` **section by section**: a `formatter` section here
   replaces the detected formatter, and an `indent` section replaces the detected indent, while a
   section you leave out still comes from sync.
   Edit the committed `lathe-style.json`, not `.lathe/style.json`, which every sync regenerates.
2. **Editor global default** — the `style` you pass to the client (for example the Neovim `setup()`; see
   the [Neovim cheatsheet](editors/neovim.md)) applies when neither file defines a formatter.

Engines you can select there:

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
  Its tokens: `%MVN%` → on Maven, the Maven that ran your last build (`mvnd`, the wrapper's Maven or `mvn`); `%MODULE%` → the edited
  file's module directory; `%FILE%` → the temporary file.
  Running Maven per save takes seconds; with `mvnd` it is well under a second.
- Both run from the workspace root, format whole files only, and pay process startup on every format.

`google`, `aosp`, `palantir`, and `eclipse` need the jars sync resolves, so they come from the
Spotless configuration; naming one in `lathe-style.json` or the editor default without a `classpath`
leaves formatting off (the server logs `run .lathe/lathe-sync.sh`).

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
