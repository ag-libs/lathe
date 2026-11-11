# Lathe for Gradle

Lathe reads a Gradle build the way it reads a Maven one: it watches the build compile and run its tests,
and writes what it saw to `.lathe/` in the workspace root.
The language server, the editor clients, and the MCP server read only `.lathe/`, so everything in the
[README](../../README.md) works the same on a Gradle build: completion, diagnostics, navigation, refactoring,
and running and debugging tests and mains replayed from the build's own launch.

## Requirements

- **Gradle 8.8 or newer**, its daemon on **JDK 17 or newer** (Gradle 9 requires that anyway).
  An older Gradle stops the build with a message naming the problem and the way out.
- Projects applying `java` or `java-library` (or a plugin that applies one).
  They may compile and test on any JDK their toolchain names; the server needs JDK 17+, and uses the JDK
  Gradle runs on when every project compiles on an older one (navigation into the JDK then shows that
  JDK's sources).
- For test capture, tests on the **JUnit Platform** (`useJUnitPlatform()`: JUnit 5 and 6, and JUnit 4 through
  the vintage engine).
  A `Test` task on `useJUnit()` or `useTestNG()` is not captured.

## Setup

Register the plugin once, in either place.

As a **settings plugin**, committed in `settings.gradle.kts` (or `settings.gradle`), for everyone who builds
the repository:

```kotlin
plugins {
    id("io.github.ag-libs.lathe") version "0.3.1"
}
```

Or as a **personal init script**, with no change to the build: save it as
`~/.gradle/init.d/lathe.init.gradle.kts`, and every build on your machine gets Lathe.

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

A build that commits the plugin keeps its own version; the init script then steps aside.

Keep `.lathe/` out of version control (`echo '.lathe/' >> .gitignore`), then build once; this records your
project's setup without running the tests:

```sh
./gradlew latheSync -Plathe.capture.only=true
```

Open any Java source in your editor's Lathe client.
After this, every normal build keeps Lathe up to date (see [What the build keeps current](#what-the-build-keeps-current)),
and the editor's sync can re-run it for you (see [Syncing](#syncing)).

Lathe resolves its server from your build's repositories, and adds Maven Central to the root project only when
the build declares no repository of its own.

## What the build keeps current

As on Maven, the build itself keeps `.lathe/` current; there is no separate step to remember.

| You run | Lathe refreshes |
|---|---|
| A build that compiles (`build`, `assemble`, `classes`, `testClasses`, …) | each compiled source set: its compiler options, annotation-processor output, and bytecode and resources |
| A build that compiles tests | the workspace files: the module list, the libraries and their sources, the JDK, the server install |
| A test run (`test`, `check`, `build`) | each project's test launch, captured inside the test JVM while the tests run |

A task served from the build cache or up to date does no work, and Lathe keeps what it captured from the run
that produced it.
To capture the test launches again, run the tests with `--rerun` (`./gradlew test --rerun`), or a capture-only
build (`-Plathe.capture.only=true`, or the editor's sync with tests), which always starts the test task.

Lathe never changes the inputs of your build's own tasks, so a build cache shared with CI keeps working:
a Lathe-enabled build takes the same cache entries as one without Lathe.

## Syncing

The editor's sync runs `.lathe/lathe-sync.sh` (`.cmd` on Windows), a script each build writes, so it needs one
build first: after a fresh clone or a wiped `.lathe/`, run the first build above from the command line.
The script re-runs the Gradle distribution and the JDK that ran that build, with the `--init-script` arguments you
passed, and one of:

```sh
latheSync                                # a plain sync
latheSync -Plathe.capture.only=true      # a sync with tests, which also captures the test launches
latheSync --module app                   # one project (its directory), and what it depends on
```

`latheSync` compiles what is out of date and writes the workspace files; with `-Plathe.capture.only=true` it also
starts each test task with every test skipped, which is all capture needs.
The test tasks start only after every project's workspace files are written, so a build that rejects skipped tests
(a test listener that fails the task, as Caffeine's does) fails the sync, but the workspace is complete,
and the launch of each test task that ran is captured.
Gradle starts no other task after the first failure, so the editor's sync with tests adds `--continue` to capture
every project's test launch; add it yourself when you run the command by hand.
It does not replay the `-P` or `-D` properties you passed on the command line: put a property every build needs
in `gradle.properties`.

## Turning it off

| Condition | Effect |
|---|---|
| `lathe.disabled=true` in `gradle.properties` | off for everyone building the repository |
| `-Plathe.disabled=true` | off for this build |
| `-Plathe.disabled=false` | on, even on CI |
| `CI` environment variable set | off |
| `.lathe/` exists | on, despite `lathe.disabled=true` in `gradle.properties` (not despite the two absolute switches) |

## Known limits

- **Every Java source set is a tree of its own.**
  A source set beyond `main` and `test` (test fixtures, a JVM test suite, an `integrationTest`, `jmh`) is compiled
  against its own classpath, so diagnostics, navigation and references there match the build; a sync compiles it.
  The test task that runs its classes captures its launch whenever it runs (without running its tests:
  `./gradlew integrationTest -Plathe.capture.only=true`), so its tests run and debug from the editor too;
  the editor's sync does not start that task, package, folder and module runs there are not offered yet, and a
  test task of no source set Lathe captures (a second task on `test`'s classes) is skipped by a capture-only build; see
  [Test capture](test-capture.md#requirements-and-current-limits).
- **Tests on a JDK older than 17** (a test toolchain, or `java.toolchain` below 17) still run, but are not
  captured, so they can't be run or debugged from the editor; a capture-only build (the editor's sync with tests)
names each such test task.
- **A notice on every test run.**
  Lathe loads its capture listener into the test JVM through the `JDK_JAVA_OPTIONS` environment variable, which
  leaves the test task's cache key alone; the JVM announces it with
  `NOTE: Picked up JDK_JAVA_OPTIONS: -javaagent:…/lathe-junit.jar`, and nothing suppresses that line.
  JVMs the tests start inherit the variable: they print the notice too, and one that runs JUnit inside
  the project's directory captures its own launch over the test JVM's.
- **Develocity's test acceleration** (Predictive Test Selection or Test Distribution, as JUnit's own build uses)
  runs the tests in a JVM of its own.
  That JVM may not load Lathe at all: nothing is captured, the capture-only sync runs every test in full, and the
  sync warns `[lathe] :x:test captured no test launch`.
  Or the launch captured comes from its test-selection JVM and names jars in a temporary directory that is gone after
  the run: the sync warns `[lathe] :x:test captured a launch naming …, a temporary jar`, and a run from the
  editor is blocked with a reason naming the missing jar.
  Turn those features off for the capture-only sync (JUnit's build:
  `-Pjunit.develocity.predictiveTestSelection.enabled=false`).
- **Gradle 8.x with Isolated Projects on:** only a build of the root's `build`, `check`, or `test` refreshes the
  workspace files; a build of one project refreshes that project's part, and the rest waits for the next root
  build or sync. Gradle 9 has no such limit.
- **The formatter is declared, not detected.** Lathe cannot read Spotless's settings on Gradle, so the project declares
  its formatter in a `lathe-style.json`, and `latheSync` resolves it through the build's repositories;
  a build that applies Spotless gets a warning until it does.
  Without a version there, the formatter is the default of the root project's Spotless, or of the
  Spotless release Lathe pins; when only subprojects apply Spotless, declare the version.
  See [Declaring the formatter](installation.md#declaring-it).
- **Composite builds:** only the main build's projects are covered; an included build of Java projects the editor
  should see is not yet.

## See also

- [How Lathe works](how-it-works.md)
- [Test capture](test-capture.md)
- [Run configuration](run-configuration.md)
- [Installing Lathe](installation.md): the editor clients, the server JVM and JDK, the formatter
- [Lathe for Maven](maven.md)
