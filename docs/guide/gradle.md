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
    id("io.github.ag-libs.lathe") version "0.1.24"
}
```

Or as a **personal init script**, with no change to the build: save it as
`~/.gradle/init.d/lathe.init.gradle.kts`, and every build on your machine gets Lathe.

```kotlin
initscript {
    repositories { gradlePluginPortal() }
    dependencies { classpath("io.github.ag-libs:lathe-gradle-plugin:0.1.24") }
}

settingsEvaluated {
    if (!pluginManager.hasPlugin("io.github.ag-libs.lathe")) {
        pluginManager.apply(io.github.aglibs.lathe.gradle.LatheSettingsPlugin::class.java)
    }
}
```

A build that commits the plugin keeps its own version; the init script then steps aside.

Keep `.lathe/` out of version control (`echo '.lathe/' >> .gitignore`), then build once:

```sh
./gradlew build
```

Open any Java source in your editor's Lathe client.
On a fresh clone, the client's sync does the same with no tests run (see [Syncing](#syncing)).

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
that produced it; `--rerun` or a sync captures again.

Lathe never changes the inputs of your build's own tasks, so a build cache shared with CI keeps working:
a Lathe-enabled build takes the same cache entries as one without Lathe.

## Syncing

The editor's sync covers a fresh clone, a wiped `.lathe/`, and a change of test setup.
It runs, through the `.lathe/lathe-sync` script the build writes:

```sh
./gradlew latheSync -Plathe.capture.only=true   # every project, tests captured without running them
./gradlew latheSync --module app                # one project (its directory), and what it depends on
```

`latheSync` compiles what is out of date and writes the workspace files; with `-Plathe.capture.only=true` it also
starts each test task with every test skipped, which is all capture needs.

## Turning it off

| Condition | Effect |
|---|---|
| `lathe.disabled=true` in `gradle.properties` | off for everyone building the repository |
| `-Plathe.disabled=true` | off for this build |
| `-Plathe.disabled=false` | on, even on CI |
| `CI` environment variable set | off |
| `.lathe/` exists | on, despite `lathe.disabled=true` in `gradle.properties` (not despite the two absolute switches) |

## Known limits

- **Tests on a JDK older than 17** (a test toolchain, or `java.toolchain` below 17) still run, but are not
  captured, so they can't be run or debugged from the editor; the editor's sync names each such test task.
- **A notice on every test run.**
  Lathe loads its capture listener into the test JVM through the `JDK_JAVA_OPTIONS` environment variable, which
  leaves the test task's cache key alone; the JVM announces it with
  `NOTE: Picked up JDK_JAVA_OPTIONS: -javaagent:…/lathe-junit.jar`, and nothing suppresses that line.
  JVMs the tests start inherit the variable: they print the notice too, and one that runs JUnit inside
  the project's directory captures its own launch over the test JVM's.
- **Tests run through the Develocity plugin's test launcher** (as JUnit's own build does) start from copies of some
  jars in a temporary directory that is gone after the run, so replaying those tests from the editor fails to load
  classes.
- **Gradle 8.x with Isolated Projects on:** only a build of the root's `build`, `check`, or `test` refreshes the
  workspace files; a build of one project refreshes that project's part, and the rest waits for the next root
  build or sync. Gradle 9 has no such limit.
- **No formatter detection.** Lathe does not read Spotless or another formatting plugin on Gradle; format through
  a committed `lathe-style.json` with a command engine, as described in
  [Choosing, overriding, or opting out of the formatter](installation.md#choosing-overriding-or-opting-out-of-the-formatter).
- **Composite builds:** only the main build's projects are covered; an included build of Java projects the editor
  should see is not yet.

## See also

- [How Lathe works](how-it-works.md)
- [Test capture](test-capture.md)
- [Run configuration](run-configuration.md)
- [Installing Lathe](installation.md): the editor clients, the server JVM and JDK, the formatter
- [Lathe for Maven](maven.md)
