# Lathe for Maven

Register Lathe at the root of your Maven build so each module records its own compiler settings.
The first build installs the server, writes the workspace under `.lathe/`, and captures how Surefire launches tests.
Later builds refresh the parts they compile or test.

Use the [Maven extension](#method-1--maven-extension-recommended) even if your project already configures `maven-compiler-plugin`, then run the [first build](#initialize).
Choose the [manual POM setup](#method-2--manual-pom-configuration) if you want to declare Lathe’s compiler, sync goals, and test dependency yourself.

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

## Method 1 — Maven extension (recommended)

Register `lathe-maven-extension` once, in either location. Both drive the same in-memory injection.

As a **core extension**, in `.mvn/extensions.xml` at the reactor root (the directory you run `mvn`
from) — the earliest, most reliable hook, with no `pom.xml` changes anywhere:

```xml
<extensions>
    <extension>
        <groupId>io.github.ag-libs</groupId>
        <artifactId>lathe-maven-extension</artifactId>
        <version>0.3.1</version>
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
            <version>0.3.1</version>
        </extension>
    </extensions>
</build>
```

Before the build runs, the extension injects the three pieces into the effective model, in memory, for
every resolved project — including modules with their own compiler configuration or a separate parent
POM. All injected artifacts use the extension's own version, so they stay in lockstep with the version
you register, and a stale Lathe version already in a POM is overwritten.

## Initialize

Run this command at the root of the build after registering Lathe.

```bash
mvn clean test -Dlathe.capture.only=true
```

In Windows PowerShell, quote the property so it reaches Maven in one piece.

```powershell
mvn clean test '-Dlathe.capture.only=true'
```

The clean build compiles every module so Lathe can record its settings.
Surefire starts its test JVM and writes the launch that the editor will use to run a test.
On JUnit 5.10 and later, tests appear as skipped.
With older JUnit, capture filters out the tests.
If the build then fails because `failIfNoTests` is enabled, add `-DfailIfNoTests=false`.
[Test capture](test-capture.md#capture-without-running-tests)

Open a source file in your [editor](../../README.md#editors).
Subsequent builds refresh the workspace according to the last phase they reach.

| Command | What it refreshes |
|---|---|
| `mvn process-test-classes` | Compiler settings, the module list, and the launch configuration for main methods |
| `mvn test`, `verify`, or `install` | The items above and each module's test launch when its test JVM starts |
| `mvn test -Dlathe.capture.only=true` | The same information, skipping test execution |

Git ignores `.lathe/` through the `.gitignore` that Lathe writes inside it.
Exclude the directory from checks that scan the entire project tree.
[Troubleshooting](troubleshooting.md#build-checks-and-compiler-settings)
You can use the Maven Daemon (`mvnd`) in place of `mvn`.
After upgrading Lathe, run `mvnd --stop` so the daemon loads the new extension.
If your build uses the Maven build cache extension, pass `-Dmaven.build.cache.enabled=false` for capture.
Modules restored from that cache skip the compilation Lathe needs to observe.

## What Lathe needs in the build

Both methods add the same three pieces, for the whole reactor:

- the **`lathe-compiler` shim** on `maven-compiler-plugin`, selected by `compilerId=lathe`, for every
  module that compiles Java (`pom` modules are left alone) — it records the exact `javac` inputs the language server reads;
- the **`lathe-maven-plugin` goals** `init` (bound to `initialize`) and `sync` (bound to
  `process-test-classes`), once at the reactor root;
- the **`lathe-junit` test-scoped dependency**, for every module that compiles Java — it enables run/test capture.

Coordinates: groupId `io.github.ag-libs`; artifacts `lathe-compiler`, `lathe-maven-plugin`,
`lathe-junit`; use one Lathe version throughout.

## Method 2 — Manual POM configuration

Declare the same three pieces in your **parent `pom.xml`**. Pin one version with a property:

```xml
<properties>
    <lathe.version>0.3.1</lathe.version>
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

## What the build writes to `.lathe/`

| Build step | What Lathe records |
| --- | --- |
| A module compiles | Its compiler arguments, classes, generated sources, and resources. |
| The root project runs `lathe:sync` | The module list, dependency and JDK sources, project launchers, and main-method launch settings. Sync also resolves the project’s formatter. |
| Surefire starts a supported test JVM | The module’s test launch, including the JVM arguments and classpath needed to rerun it. |

If a shaded module supplies relocated classes, build through `package` so downstream modules can use the shaded JAR.
Use `-Dlathe.sync.phase=package` in `.mvn/maven.config` to repeat that phase on sync.
The sync phase can range from `process-test-classes` through `install`.
[Shaded-module troubleshooting](troubleshooting.md#build-checks-and-compiler-settings)

The generated sync script repeats the build with the recorded Maven and JDK.
Put required `-D` properties or `-P` profiles in `.mvn/maven.config` so they also apply when the editor starts a sync.

See [workspace files](installation.md#what-and-where-lathe-writes), [formatter setup](installation.md#choosing-overriding-or-opting-out-of-the-formatter), and [test capture](test-capture.md) for details.

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
Either one makes the build print a `[lathe] Off:` line that names it.

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
