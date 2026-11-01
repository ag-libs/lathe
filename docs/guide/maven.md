# Lathe for Maven

Lathe reads a Maven build through the build itself: you activate it per build in one of two ways, and
every build after that keeps `.lathe/` current.

1. **The Maven extension** (recommended) — register `lathe-maven-extension` once, either as a core
   extension in `.mvn/extensions.xml` or as a build extension in the reactor-root `pom.xml`. No other
   POM edits.
2. **Manual POM configuration** — declare the same wiring by hand. More verbose, but explicit and
   versioned in the POM.

Both produce the identical effective build.

> The editor client is a separate step: see [Installing Lathe](installation.md).

## Requirements

- **JDK 17+** to run the build (and so the server, which runs on the build's JDK).
- **Maven 3.6.3+** and **`maven-compiler-plugin` 3.6.2+**; with the extension, an older Maven or
  compiler plugin fails the build with a message naming the fix (or add `-Dlathe.disabled=true` to build
  without Lathe). CI verifies Maven 3.6.3, 3.8.8, 3.9 and 3.10.
- Tests your build runs on a JDK older than 17 (through `<jvm>` or a toolchain) still run, but are not
  captured, so they can't be run or debugged from the editor; see [test capture](test-capture.md) for
  the other test requirements.

Lathe stays out of CI builds: when the `CI` environment variable is set, it injects nothing, unless
you pass `-Dlathe.disabled=false`.
`-Dlathe.disabled=true` turns it off anywhere (see [jdtls](#coexisting-with-jdtls-keep-lathe-out-of-its-maven-import)).

## What Lathe needs in the build

Both methods add the same three pieces, for the whole reactor:

- the **`lathe-compiler` shim** on `maven-compiler-plugin`, selected by `compilerId=lathe`, for every
  module that compiles Java (`pom` modules are left alone) — it records the exact `javac` inputs the language server reads;
- the **`lathe-maven-plugin` goals** `init` (bound to `initialize`) and `sync` (bound to
  `process-test-classes`), once at the reactor root;
- the **`lathe-junit` test-scoped dependency**, for every module that compiles Java — it enables run/test capture.

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
        <version>0.2.2</version>
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
            <version>0.2.2</version>
        </extension>
    </extensions>
</build>
```

Before the build runs, the extension injects the three pieces into the effective model, in memory, for
every resolved project — including modules with their own compiler configuration or a separate parent
POM. All injected artifacts use the extension's own version, so they stay in lockstep with the version
you register, and a stale Lathe version already in a POM is overwritten.

Upgrading a project from 0.1.15 or earlier also needs a one-time editor-side step; see
[Upgrading from Lathe 0.1.15 or earlier](editors/neovim.md#upgrading-from-lathe-0115-or-earlier).

## Method 2 — Manual POM configuration

Declare the same three pieces in your **parent `pom.xml`**. Pin one version with a property:

```xml
<properties>
    <lathe.version>0.2.2</lathe.version>
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

`-Dlathe.capture.only=true` keeps the tests from running while the fork still writes its launch template:
on JUnit 5.10 or newer through JUnit's dry-run mode, on older JUnit through a filter that excludes every test
(see [test-capture.md](test-capture.md#capture-without-running-tests)).
`clean` is required on the first run so Maven compiles through the Lathe shim rather than skipping up-to-date output.
On JUnit older than 5.10, if your build sets `<failIfNoTests>true</failIfNoTests>`, add `-DfailIfNoTests=false` so the test-free run stays green.

After that, any normal build (`mvn test` / `verify` / `install`) refreshes the templates automatically.
Add `.lathe/` to `.gitignore`.

The [Maven Daemon (`mvnd`)](https://github.com/apache/maven-mvnd) makes these builds noticeably faster; use it in place of `mvn` where you can.

If your build uses the Maven build cache extension, turn it off for Lathe builds with `-Dmaven.build.cache.enabled=false`.
A cache hit skips compilation, so Lathe would not see the real build and its captured configuration would go stale.

## What the build writes to `.lathe/`

- `lathe:init` creates `.lathe/` at the workspace root on the first build, at the `initialize` phase.
- The compiler shim writes compilation-parameter files (`<module>/lsp-params-*.json`) under `.lathe/`
  as each module compiles — the classpath, module path, source roots, generated-source locations,
  annotation-processor settings, and other `javac` inputs the language server needs — plus
  per-source compile stamps (`lsp-stamps-*.json`) the server uses to tell which sources are stale.
- `lathe:sync` writes `workspace.json` and each module's derived `main-launch.json`. The write is
  skipped when the content is unchanged, so a no-op build does not trigger a server reload.
- `lathe:sync` also writes `lathe-sync.sh` (`.cmd` on Windows), which re-runs the same build tool and JDK
  that ran this build, so the editor's "sync" matches the command line you use.
  It runs `process-test-classes` (with `--tests <module>`, a capture-only `test` run that writes test
  launches without executing tests), without the `-D` properties or `-P` profiles you passed on the
  command line.
  If your build always needs a flag (say, to skip a check that does not run on your JDK), put it in
  `.mvn/maven.config`, which Maven reads on every run, or in a profile activated by the JDK version.
- `lathe:sync` also writes `lathe-launcher.sh` and `lathe-mcp-launcher.sh` (`.cmd` on Windows) here, with the argfiles
  they read (`lathe-server.args`, `lathe-mcp.args`) — copies of the launchers in the machine cache's
  `servers/<version>/` for the server version this project pins. These are what every
  editor client and the MCP agent launch; being version-pinned per workspace, two projects on
  different Lathe versions each resolve the right server.
- `lathe:sync` also writes `java-home` (the build JDK the launcher runs the server on), `jvm.args`
  (module grants for the server JVM; see [tuning the server JVM](installation.md#tuning-the-server-jvm-lathe_jvm_opts)), and each
  module's `test-filter.json` (the Surefire include/exclude patterns, applied to package and module
  test runs).
- `lathe:sync` also writes `style.json` (formatter + indent) when the reactor configures
  `spotless-maven-plugin`.
  For `googleJavaFormat`, `palantirJavaFormat`, and `eclipse` it records the formatter release the
  project's Spotless pins, its options, and the resolved jars (`classpath`, paths into the local Maven
  repository), which the server loads in-process.
  For google-java-format and palantir it also adds the javac grants they need to `jvm.args`.
  Any other Spotless formatter writes `none`.
  See [Choosing, overriding, or opting out of the formatter](installation.md#choosing-overriding-or-opting-out-of-the-formatter)
  and [lathe-pinned-formatters.md](../done/lathe-pinned-formatters.md).
- `lathe-junit` writes each module's `test-launch.json` from inside the Surefire fork during the `test`
  phase — so test run/debug needs a build that reaches `test` (see the table above and
  [test-capture.md](test-capture.md)).

[Installing Lathe](installation.md#what-and-where-lathe-writes) covers what the server reads from these, and the
machine-wide cache in `~/.cache/lathe/`.

## Coexisting with jdtls: keep Lathe out of its Maven import

jdtls imports a project with its own embedded Maven (m2e). The Lathe extension injects
`compilerId=lathe` into the effective POM (see [What Lathe needs](#what-lathe-needs-in-the-build)),
and m2e does not recognise that compiler id — so it imports the modules but **cannot configure their
Java build path**, and every jdtls request (hover, definition, completion, …) then returns empty, with
no error. Lathe itself is unaffected: it serves from the already-captured `.lathe/` and never runs
Maven at query time.

The fix is to disable Lathe's injection **for jdtls's Maven only**. Lathe honours the
`-Dlathe.disabled=true` system property and skips all injection when it is set — so jdtls imports a
normal plain-`javac` build path, while your command-line `mvn` and the Lathe server stay fully enabled.

**Recommended — a jdtls-only JVM arg (no effect on your CLI builds).** m2e runs in-process in the
jdtls JVM, so a `-D` on the jdtls process reaches it. With `nvim-jdtls`:

```lua
require("jdtls").start_or_attach({
  cmd = { "jdtls", "--jvm-arg=-Dlathe.disabled=true" },
  -- jdtls 1.51 runs on JDK 21–25, NOT 26 (its bundled ASM rejects class version 70). Point its
  -- runtime at a JDK <= 25; this is separate from your project's own JDK.
  cmd_env = { JAVA_HOME = "/path/to/jdk-25" },
  -- ... your root_dir, settings, etc.
})
```

**Alternative — `.mvn/maven.config`** (one line, no editor config), at the reactor root:

```
-Dlathe.disabled=true
```

This is read by jdtls's embedded Maven *and* your CLI `mvn`, so plain `mvn` builds also skip Lathe;
refresh `.lathe/` explicitly by overriding on the command line:

```bash
mvn -Dlathe.disabled=false clean test -Dlathe.capture.only=true
```

[Installing Lathe](installation.md#coexisting-with-another-java-language-server-jdtls) has the other half:
keeping jdtls from indexing `.lathe/`.

## Turning Lathe off

Lathe is on by default and stays out of CI builds.
To turn it off for everyone building a project, commit the property in the reactor `pom.xml`:

```xml
<properties>
  <lathe.disabled>true</lathe.disabled>
</properties>
```

| Condition                                       | Effect                                  |
|-------------------------------------------------|-----------------------------------------|
| `<lathe.disabled>true</...>` in the reactor POM | disabled for everyone building the repo |
| `-Dlathe.disabled=true`                         | disabled regardless of other settings   |
| `-Dlathe.disabled=false`                        | enabled, overrides `CI`                 |
| `CI` environment variable is set                | Lathe does not run                      |

When the POM opts out, a developer can opt back in by creating an empty `.lathe/` at the repo root.
It overrides the POM opt-out, so the next build wires Lathe up and fills the directory.
A `-Dlathe.disabled=true` or `CI` stays absolute; `.lathe/` does not override it.

## Verify

After the recommended first run above, confirm `.lathe/` exists at the reactor root and that each
module directory holds its parameter files plus a `test-launch.json` (proof that capture ran). If
`.lathe/` is missing, see [Troubleshooting](troubleshooting.md) (and confirm you are running `mvn`
from the directory that contains `.mvn/`).

## See also

- [Installing Lathe](installation.md): the editor clients, the server JVM and JDK, the formatter
- [How Lathe works](how-it-works.md)
- [Test capture](test-capture.md)
- [Lathe for Gradle](gradle.md)
