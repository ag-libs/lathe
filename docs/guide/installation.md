# Installing Lathe into a Maven build

You activate Lathe per Maven build in one of two ways:

1. **The Maven extension** (recommended) — register `lathe-maven-extension` once, either as a core
   extension in `.mvn/extensions.xml` or as a build extension in the reactor-root `pom.xml`. No other
   POM edits.
2. **Manual POM configuration** — declare the same wiring by hand. More verbose, but explicit and
   versioned in the POM.

Both produce the identical effective build.

> **The editor client is a separate, independent step.** This page covers the Maven build — the
> **server** side, which delivers and installs the language server itself. Installing the **editor
> client** (e.g. the [Neovim plugin](editors/neovim.md)) is independent and can happen before or after
> the build. On the standalone plugin path the two are fully decoupled; the client simply nudges you to
> run the build if it hasn't done so yet.

## What Lathe needs in the build

Both methods add the same three pieces, for the whole reactor:

- the **`lathe-compiler` shim** on `maven-compiler-plugin`, selected by `compilerId=lathe`, for every
  module — it records the exact `javac` inputs the language server reads;
- the **`lathe-maven-plugin` goals** `init` (bound to `initialize`) and `sync` (bound to
  `process-test-classes`), once at the reactor root;
- the **`lathe-junit` test-scoped dependency**, for every module — it enables run/test capture.

Coordinates: groupId `io.github.ag-libs`; artifacts `lathe-compiler`, `lathe-maven-plugin`,
`lathe-junit`; use one Lathe version throughout.

## Method 1 — Maven extension (recommended)

Register `lathe-maven-extension` once, in either location. Both drive the same in-memory injection.

As a **core extension**, in `.mvn/extensions.xml` at the reactor root (the directory you run `mvn`
from) — the earliest, most reliable hook, with no `pom.xml` changes anywhere:

```xml
<extensions>
    <extension>
        <groupId>io.github.ag-libs</groupId>
        <artifactId>lathe-maven-extension</artifactId>
        <version>0.1.12</version>
    </extension>
</extensions>
```

Or as a **build extension**, in the reactor-root `pom.xml` — one POM edit, no `.mvn/` directory. It
must live in the reactor-root (or parent) POM to cover every module:

```xml
<build>
    <extensions>
        <extension>
            <groupId>io.github.ag-libs</groupId>
            <artifactId>lathe-maven-extension</artifactId>
            <version>0.1.12</version>
        </extension>
    </extensions>
</build>
```

Before the build runs, the extension injects the three pieces into the effective model, in memory, for
every resolved project — including modules with their own compiler configuration or a separate parent
POM. All injected artifacts use the extension's own version, so they stay in lockstep with the version
you register, and a stale Lathe version already in a POM is overwritten.

## Method 2 — Manual POM configuration

Declare the same three pieces in your **parent `pom.xml`**. Pin one version with a property:

```xml
<properties>
    <lathe.version>0.1.12</lathe.version>
</properties>
```

**1. Compiler shim** — in the parent `<build><plugins>` so every module inherits it:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <compilerId>lathe</compilerId>
    </configuration>
    <dependencies>
        <dependency>
            <groupId>io.github.ag-libs</groupId>
            <artifactId>lathe-compiler</artifactId>
            <version>${lathe.version}</version>
        </dependency>
    </dependencies>
</plugin>
```

**2. Init/sync goals** — at the reactor root only, so `<inherited>false</inherited>` keeps them from
running once per module. The goals carry their own default phases (`init` → `initialize`,
`sync` → `process-test-classes`), so no `<phase>` is needed:

```xml
<plugin>
    <groupId>io.github.ag-libs</groupId>
    <artifactId>lathe-maven-plugin</artifactId>
    <version>${lathe.version}</version>
    <inherited>false</inherited>
    <executions>
        <execution>
            <id>lathe-init</id>
            <goals><goal>init</goal></goals>
        </execution>
        <execution>
            <id>lathe-sync</id>
            <goals><goal>sync</goal></goals>
        </execution>
    </executions>
</plugin>
```

**3. Capture dependency** — in the parent `<dependencies>` so every module carries it at `test` scope:

```xml
<dependency>
    <groupId>io.github.ag-libs</groupId>
    <artifactId>lathe-junit</artifactId>
    <version>${lathe.version}</version>
    <scope>test</scope>
</dependency>
```

Each block is easy to get subtly wrong — a wrong phase, a missing `inherited`, a `compilerId` typo, or
a module that overrides `maven-compiler-plugin` and drops the `compilerId` — which is exactly the
class of mistake the extension removes. If a module declares its own compiler configuration, preserve `<compilerId>lathe</compilerId>`
there too.

## Initialize

With either method, generate the Lathe metadata. What gets written depends on how far the build runs:

| Command | Writes | Enables |
|---|---|---|
| `mvn process-test-classes` | compiler params, `workspace.json`, `main-launch.json` | LSP intelligence + `main` run/debug |
| `mvn test` (or `verify` / `install`) | the above **plus** each module's `test-launch.json` | + test run/debug |
| `mvn test -Dlathe.capture.only=true` | the same, **without executing the tests** | + test run/debug, fast |

**Recommended first run** — generate everything, without paying for a test run:

```bash
mvn clean test -Dlathe.capture.only=true
```

`-Dlathe.capture.only=true` registers a filter that excludes every test from execution while the fork
still writes its launch template; `clean` is required on the first run so Maven compiles through the
Lathe shim rather than skipping up-to-date output. If your build sets
`<failIfNoTests>true</failIfNoTests>`, add `-DfailIfNoTests=false` so the test-free run stays green.

After that, any normal build (`mvn test` / `verify` / `install`) refreshes the templates automatically.
Add `.lathe/` to `.gitignore`.

## What and where Lathe writes

Lathe writes to two locations: `.lathe/` inside the project, and `~/.cache/lathe/` on the machine.

**In the project — `.lathe/`** (per-build, add to `.gitignore`):

- `lathe:init` creates `.lathe/` at the workspace root on the first build, at the `initialize` phase.
- The compiler shim writes compilation-parameter files under `.lathe/` as each module compiles — the
  classpath, module path, source roots, generated-source locations, annotation-processor settings, and
  other `javac` inputs the language server needs.
- `lathe:sync` writes `workspace.json` and each module's derived `main-launch.json`. The write is
  skipped when the content is unchanged, so a no-op build does not trigger a server reload.
- `lathe:sync` also writes `style.json` (formatter + indent) when the reactor configures
  `spotless-maven-plugin` — `googleJavaFormat` becomes the in-process formatter; any other Spotless
  formatter (eclipse, palantir) is delegated to `mvn spotless:apply` on the edited file (preferring
  mvnd → `./mvnw` → mvn), so the editor applies the project's own formatter. Commit a `lathe-style.json`
  at the repo root to override it, or opt out of mvn delegation entirely (see below). See
  [lathe-workspace-style.md](../done/lathe-workspace-style.md) and
  [lathe-delegated-maven-formatting.md](../done/lathe-delegated-maven-formatting.md).
- `lathe-junit` writes each module's `test-launch.json` from inside the Surefire fork during the `test`
  phase — so test run/debug needs a build that reaches `test` (see the table above and
  [test-capture.md](test-capture.md)).

**On the machine — `~/.cache/lathe/`** (machine-wide, regenerable, override with `-Dlathe.cache=<dir>`):

- `servers/<version>/` — the unpacked language server and its editor client; `current` symlinks the
  active version.
- `deps/` and `jdks/` — dependency and JDK **source** trees. `lathe:sync` resolves each dependency's
  `-sources` JAR through Maven and extracts it here, and extracts the JDK's sources when available;
  these back go-to-definition into library and JDK code. A dependency with no published `-sources` JAR
  is recorded as missing and simply not navigable.
- `type-index/` — the symbol index behind workspace symbol search.

Everything under `~/.cache/lathe/` is derived and safe to delete; the next `sync`/build rebuilds what
it needs.

## Tuning the server JVM (`LATHE_JVM_OPTS`)

The language server analyzes your code with an **in-process** javac, so it replays the javac options
your Maven build captured — with one exception: forked-launcher `-J` flags are dropped.

`-J` options (for example `-J--add-exports=…` or `-J-Xmx…`) only reach a **forked** `javac`
executable, which Maven uses when a module sets `<fork>true</fork>` on `maven-compiler-plugin` — often
to run Error Prone. The in-process javac API has no launcher to receive them and would reject them as
`invalid flag`, so Lathe drops them (just as Maven's own non-forked compiler ignores them). When this
happens the server logs one line per module naming what it dropped.

Most `-J--add-exports`/`-J--add-opens` into `jdk.compiler` are already granted to the server JVM by
Lathe's launcher, which is why in-process Error Prone keeps working. If an annotation processor or
javac plugin genuinely needs JVM access the launcher does not grant — or you simply want to tune heap
or GC — set `LATHE_JVM_OPTS` in the environment your editor launches from. It is expanded ahead of
Lathe's own arguments:

```bash
export LATHE_JVM_OPTS="-Xmx4g -XX:+UseZGC"
# or restore a JVM option a forked build passed via -J:
export LATHE_JVM_OPTS="--add-opens jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED"
```

## Choosing the server JDK (`LATHE_JAVA_HOME`)

The server's in-process javac must be at least as new as the Java version your project targets — open a
Java 26 module under a Java 21 `java` and every file shows parse errors. So Lathe does **not** just use
whatever `java` is on `PATH`: `lathe:sync` records the build JDK (the JVM Maven ran on) in
`.lathe/java-home`, and the launcher runs the server under it. No configuration is needed — build your
project and the server matches it.

The launcher chooses the JDK in this order:

1. `LATHE_JAVA_HOME`, if exported in the environment your editor launches from — an explicit override;
2. `.lathe/java-home`, the build JDK captured by `lathe:sync`;
3. `java` on `PATH`, if neither is available (with a warning if a chosen JDK has no `bin/java`).

```bash
# Force a specific JDK regardless of what the build used:
export LATHE_JAVA_HOME="$HOME/.sdkman/candidates/java/26-tem"
```

For a multi-module reactor the one server JVM runs the highest JDK the build used, which down-compiles
the lower-release modules via `--release`. (Editor clients spawn the server with its working directory
set to the workspace root so the launcher can find `.lathe/java-home`.)

## Choosing, overriding, or opting out of the formatter

`lathe:sync` derives the formatter from your `spotless-maven-plugin` config (above): `googleJavaFormat`
runs in-process; any other Spotless formatter is **delegated to `mvn spotless:apply`** on the edited
file. You control it at three levels, in precedence order:

1. **Per project (highest): a committed `lathe-style.json`** at the repo root — overrides the generated
   `.lathe/style.json`. This is both the override and the per-project opt-out:

   ```jsonc
   // disable Lathe formatting for this project entirely:
   { "formatter": { "engine": "none" } }

   // or force a specific engine instead of what sync detected:
   { "formatter": { "engine": "google" } }
   ```

   Edit the **committed** `lathe-style.json`, not `.lathe/style.json` — the latter is regenerated (and
   gitignored) on every sync. Setting `engine` to `"none"` is an authoritative off; *omitting* the
   `formatter` section instead falls back to the editor's global default.

2. **Globally, opt out of mvn delegation: `-Dlathe.spotless=false`** on the build. Sync then writes
   `none` for non-google formatters (google/aosp still run in-process), so Lathe never shells out to
   Maven to format. Set it on the sync build, e.g. `mvn -Dlathe.spotless=false process-test-classes`
   (or in the Maven extension/CI config).

3. **Editor global default** — the `style` you pass to the client `setup()` applies only to projects
   with no style file (see the [Neovim cheatsheet](editors/neovim.md)).

## Coexisting with another Java language server (jdtls)

`.lathe/` is generated build output. Another Java language server in the same editor (Eclipse JDT LS,
via `nvim-jdtls` or the VS Code Java extension) scans it by default and may treat the mirrored sources
and classes as duplicate projects. Add `**/.lathe/**` to its `java.import.exclusions` so the two coexist
— jdtls scopes imports with explicit globs, not `.gitignore`, so gitignoring `.lathe/` alone is not
enough:

```jsonc
"java.import.exclusions": [
  "**/node_modules/**",
  "**/.metadata/**",
  "**/.lathe/**"        // add this line, keep jdtls's defaults
]
```

In VS Code this goes in `settings.json`; with `nvim-jdtls` it is the `settings.java.import.exclusions`
table you pass to `start_or_attach`.

## Verify

After the recommended first run above, confirm `.lathe/` exists at the reactor root and that each
module directory holds its parameter files plus a `test-launch.json` (proof that capture ran). If
`.lathe/` is missing, see the README **Troubleshooting** section (and confirm you are running `mvn`
from the directory that contains `.mvn/`).
