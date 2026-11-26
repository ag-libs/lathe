# Lathe for Gradle

Apply the Lathe settings plugin and Gradle records each Java source set as it compiles.
Your editor uses the same toolchain, classpath, and generated sources.
For a JUnit Platform test, Lathe also records the JVM arguments and working directory that Gradle uses to launch it.

## Requirements

- **Gradle 8.8 or newer**, its daemon on **JDK 17 or newer** (Gradle 9 requires that anyway).
  An older Gradle stops the build with a message naming the problem and the way out.
- Projects applying `java` or `java-library` (or a plugin that applies one).
  They may compile and test on any JDK their toolchain names; the server needs JDK 17+, and uses the JDK Gradle runs on when every project compiles on an older one (navigation into the JDK then shows that JDK's sources).
- For test capture, tests on the **JUnit Platform** (`useJUnitPlatform()`: JUnit 5 and 6, and JUnit 4 through the vintage engine).
  A `Test` task on `useJUnit()` or `useTestNG()` is not captured.

## Setup

Choose who should get Lathe when they build this project.

**For the whole project**, add the plugin to the root settings file and commit it.
Use the settings file your project already has.
The same declaration works in both `settings.gradle` (Groovy) and `settings.gradle.kts` (Kotlin).
If it already has a `plugins` block, add just the `id` line to that block.

```kotlin
plugins {
    id("io.github.ag-libs.lathe") version "0.3.1"
}
```

**For your own Gradle builds**, save the following as `~/.gradle/init.d/lathe.init.gradle.kts`.
It applies to each build on your machine and respects the version in projects that register Lathe themselves.

```kotlin
initscript {
    repositories { gradlePluginPortal() }
    dependencies { classpath("io.github.ag-libs:lathe-gradle-plugin:0.3.1") }
}

settingsEvaluated {
    if (!pluginManager.hasPlugin("io.github.ag-libs.lathe")) {
        pluginManager.apply(io.github.aglibs.lathe.gradle.LatheSettingsPlugin::class.java)
    }
}
```

### Run the first build

Run the command from the folder containing the settings file.
It compiles the project and starts the test JVMs to record their launches, skipping test execution.
The `--continue` option lets Gradle capture other projects if a task rejects skipped tests.

On Linux or macOS, use the project's Gradle wrapper.

```sh
./gradlew latheSync -Plathe.capture.only=true --continue
```

On Windows, run the wrapper from PowerShell.

```powershell
.\gradlew.bat latheSync '-Plathe.capture.only=true' --continue
```

Open a Java file in your [editor](../../README.md#editors).
Git ignores the generated `.lathe/` directory through the `.gitignore` that Lathe writes inside it.
Exclude the directory from whole-project checks.
[Troubleshooting](troubleshooting.md#build-checks-and-compiler-settings)

A build that rejects skipped tests (a test listener that fails the task, as Caffeine's does) ends this run with failed test tasks, but `.lathe/` is complete and every test task's launch is captured; see [Syncing](#syncing).

Later builds refresh each source set they compile.
[What the build keeps current](#what-the-build-keeps-current)

If you configure `failOnError = false` so a failed compile can still sync, turn off incremental compilation for that task as well.
Otherwise Gradle can omit class files even after you fix the error, and Lathe has to compile that source set itself against its last good capture.

Lathe resolves its server from your build's repositories, and adds Maven Central to the root project only when the build declares no repository of its own.

## What the build keeps current

As on Maven, the build itself keeps `.lathe/` current; there is no separate step to remember.

| You run | Lathe refreshes |
|---|---|
| A build that compiles (`build`, `assemble`, `classes`, `testClasses`, …) | each compiled source set: its compiler options, annotation-processor output, and bytecode and resources |
| A build that compiles tests | the workspace files: the module list, the libraries and their sources, the JDK, the server install |
| A test run (`test`, `check`, `build`) | each project's test launch, captured inside the test JVM while the tests run |

A task served from the build cache or up to date does no work, and Lathe keeps what it captured from the run that produced it.
To capture the test launches again, run the tests with `--rerun` (`./gradlew test --rerun`), or a capture-only build (`-Plathe.capture.only=true`, or the editor's sync with tests), which always starts the test task.

Lathe never changes the inputs of your build's own tasks, so a build cache shared with CI keeps working:
a Lathe-enabled build takes the same cache entries as one without Lathe.

## Syncing

After the [first build](#run-the-first-build), run the generated script from the project root to sync again.
It uses the same Gradle distribution, JDK, and init scripts as that build.

```sh
.lathe/lathe-sync.sh                # refresh the workspace
.lathe/lathe-sync.sh app            # refresh app and the projects it depends on
.lathe/lathe-sync.sh .              # the root project, as Lathe names it: the whole build
.lathe/lathe-sync.sh --tests        # also record test launches without executing tests
```

On Windows, use `.lathe\lathe-sync.cmd`.
The script does not replay arbitrary `-P` or `-D` arguments from the original build.
Put properties every build needs in `gradle.properties`.

## Turning it off

| Condition | Effect |
|---|---|
| `lathe.disabled=true` in `gradle.properties` | off for everyone building the repository |
| `-Plathe.disabled=true` | off for this build |
| `-Plathe.disabled=false` | on, even on CI |
| `CI` environment variable set | off |
| `.lathe/` exists | on, despite `lathe.disabled=true` in `gradle.properties` (not despite the two absolute switches) |

The two absolute switches, `-Plathe.disabled=true` and `CI`, make the build print a `[lathe] Off:` line that names the one in force.

## Coexisting with jdtls

jdtls imports a Gradle build through Buildship, which reads the compile settings Lathe leaves as they are, so jdtls works on a build that applies the Lathe plugin with no setting of its own.
Keep jdtls from indexing `.lathe/`, as [Installing Lathe](installation.md#coexisting-with-another-java-language-server-jdtls) describes.

## Known limits

- **Every Java source set is a tree of its own.**
  A source set beyond `main` and `test` (test fixtures, a JVM test suite, an `integrationTest`, `jmh`) is compiled against its own classpath, so diagnostics, navigation and references there match the build; a sync compiles it.
  The test task that runs its classes captures its launch whenever it runs (without running its tests:
  `./gradlew integrationTest -Plathe.capture.only=true`), so its tests run and debug from the editor too; the editor's sync does not start that task, package, folder and module runs there are not offered yet, and a test task of no source set Lathe captures (a second task on `test`'s classes) is skipped by a capture-only build; see [Test capture](test-capture.md#requirements-and-current-limits).
- **Tests on a JDK older than 17** (a test toolchain, or `java.toolchain` below 17) still run, but are not captured, so they can't be run or debugged from the editor; a capture-only build (the editor's sync with tests) skips each such test task and names it.
- **A notice on every test run.**
  Lathe loads its capture listener into the test JVM through the `JDK_JAVA_OPTIONS` environment variable, which leaves the test task's cache key alone; the JVM announces it with `NOTE: Picked up JDK_JAVA_OPTIONS: -javaagent:…/lathe-junit.jar`, and nothing suppresses that line.
  JVMs the tests start inherit the variable: they print the notice too, and one that runs JUnit inside the project's directory captures its own launch over the test JVM's.
- **Develocity's test acceleration** (Predictive Test Selection or Test Distribution, as JUnit's own build uses) runs the tests in a JVM of its own.
  That JVM may not load Lathe at all: nothing is captured, the capture-only sync runs every test in full, and the sync warns `[lathe] :x:test captured no test launch`.
  Or the launch captured comes from its test-selection JVM and names jars in a temporary directory that is gone after the run: the sync warns `[lathe] :x:test captured a launch naming …, a temporary jar`, and a run from the editor is blocked with a reason naming the missing jar.
  Turn those features off for the capture-only sync (JUnit's build:
  `-Pjunit.develocity.predictiveTestSelection.enabled=false`).
- **A project whose jar is shaded** (its `jar` task disabled, and a Shadow jar relocating a library written in its place, as JUnit's build does): the editor gives the projects using it its latest classes and then the shaded jar, for the relocated classes.
  A run, test or debug uses the shaded jar alone, as its classes call the relocated ones, so it runs that project's own code as the last build left it.
- **Gradle 8.x with Isolated Projects on:** only a build of the root's `build`, `check`, or `test` refreshes the workspace files; a build of one project refreshes that project's part, and the rest waits for the next root build or sync.
  Gradle 9 has no such limit.
- **The formatter is declared, not detected, except spring-javaformat.**
  Lathe cannot read Spotless's settings on Gradle, so the project declares its formatter in a `lathe-style.json`, and `latheSync` resolves it through the build's repositories; a build that applies Spotless gets a warning until it does.
  A root project that applies the `io.spring.javaformat` plugin gets it at the plugin's release, unless Spotless is applied too ([spring-javaformat](installation.md#spring-javaformat)).
  Without a version there, the formatter is the default of the root project's Spotless, or of the Spotless release Lathe pins, chosen for the daemon's JDK; when only subprojects apply Spotless, or a toolchain compiles on an older JDK than the daemon runs, declare the version (the sync warns when the server's JDK cannot run the formatter).
  See [The formatter version](installation.md#the-formatter-version).
- **Composite builds:** only the main build's projects are covered; an included build of Java projects the editor should see is not yet.

## See also

- [How Lathe works](how-it-works.md)
- [Test capture](test-capture.md)
- [Run configuration](run-configuration.md)
- [Installing Lathe](installation.md): the editor clients, the server JVM and JDK, the formatter
- [Lathe for Maven](maven.md)
