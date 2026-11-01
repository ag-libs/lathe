# Test capture

Lathe runs and debugs tests by **replaying** them from the captured `.lathe/` bytecode with no
recompilation.
To know *how* to launch that replay JVM — the exact classpath, module path, and JVM arguments your build's
test run used — Lathe captures the real launch from inside the test JVM.
That capture is done by `lathe-junit`, which the build puts into every module's test JVM; there is no test
configuration to add.

On any build that actually runs tests, `lathe-junit` fires once per module, before the tests run, and writes
`.lathe/<module>/test-launch.json`.
It records — from live JVM introspection, not by parsing a command line:

- `java.home` (replay uses the same JDK),
- the test classpath (with `lathe-junit`'s own jar removed, so replay never has to strip it),
- the module path and module directives (`--patch-module`, `--add-opens` / `--add-reads` /
  `--add-exports`, `--add-modules`),
- and the remaining JVM arguments the build gave the test JVM.

Because these are read *after* the JVM expanded its launch, the captured template is the **effective,
interpreted** launch — the module graph the tests actually ran under — rather than a guess reconstructed
from build files.

## On Maven

`lathe-junit` is a small `test`-scoped dependency in every module that compiles Java (injected by the
[Maven extension](maven.md), or declared by hand in a [manual setup](maven.md#method-2--manual-pom-configuration)).
It registers a JUnit Platform `LauncherSessionListener` through the standard service-loader SPI, and
Surefire's JUnit Platform provider detects it, so no `maven-surefire-plugin` configuration is needed.
`mvn test`, `mvn verify` and `mvn install` capture; the remaining JVM arguments come from `<argLine>`, read
after the JVM expanded Surefire's argfile.

## On Gradle

The [Gradle plugin](gradle.md) loads `lathe-junit` into each project's `test` task JVM as a Java agent, through
the `JDK_JAVA_OPTIONS` environment variable, which leaves the task's cache key alone.
`test`, `check` and `build` capture; Gradle passes the task's `jvmArgs` and `systemProperty` values to the test
JVM as real arguments, so they are captured too.

## Capture without running tests

To refresh the launch templates without paying for a test run, set `lathe.capture.only`: each module still
starts its test JVM and writes its `test-launch.json`, but no test runs.
On JUnit 5.10 or newer, Lathe turns on JUnit's dry-run mode: the tests are discovered as usual
(a `@Suite` included) and reported as skipped.
On older JUnit, a post-discovery filter excludes every test instead.

```bash
mvn test -Dlathe.capture.only=true                    # Maven
./gradlew latheSync -Plathe.capture.only=true         # Gradle
```

- **Maven:** for the [first run](maven.md#initialize), add `clean` (`mvn clean test -Dlathe.capture.only=true`)
  so every module compiles through Lathe rather than reusing up-to-date output.
  `-DskipTests` is **not** a substitute: it skips the fork entirely, so nothing is captured.
  On JUnit older than 5.10, if your build sets `<failIfNoTests>true</failIfNoTests>`, add
  `-DfailIfNoTests=false` so the test-free run stays green.
- **Gradle:** a capture-only run always starts the test JVM, never serving the task from the build cache or as
  up to date, and does not fail for running no tests. `-Plathe.capture.only=true` works on a plain test run
  too (`./gradlew test -Plathe.capture.only=true`).

## Resources

Saving a resource file (`.properties`, `.sql`, `.yaml`, …) inside a Lathe workspace copies it straight
into `.lathe/`, so the next run, test, or debug **replay** uses the new contents — no rebuild.
The editor forwards each non-Java save to the server, which maps it against the resource roots the build
recorded and byte-copies it into `.lathe/<module>/{classes,test-classes}`.

This is exact for **plain** resources, which the build copies verbatim anyway. For **filtered** resources —
those the build processes with placeholder substitution (Maven's `${…}`/`@…@` filtering, Gradle's `expand`),
a different target path, or includes and excludes — the copy is the **raw source**: placeholders are not
substituted. To refresh filtered resources faithfully, run a normal build (`mvn process-test-classes`, or any
Gradle build that processes resources), which processes them again.

## Requirements and current limits

On every build:

- **A test JVM on JDK 17 or newer.**
  Tests the build runs on an older JDK (Surefire's `<jvm>`, or a toolchain) run as usual but are not
  captured, so they can't be run from the editor; `lathe-junit` does nothing there.
- **JUnit Platform (JUnit 5/6, or the JUnit 4 vintage engine).**
  Capture rides the JUnit Platform launcher; pure TestNG runs, and Gradle test tasks on `useJUnit()` or
  `useTestNG()`, are not captured.
- A module whose tests are skipped or absent produces no `test-launch.json`, so its tests are not
  runnable from the editor until a build actually runs them.

On Maven:

- **A modern, JPMS-capable Surefire (e.g. 3.5.5+).**
  An old Surefire forks non-modularly and yields an empty argument list, so nothing meaningful is
  captured. Pin `maven-surefire-plugin` to a recent version if your build inherits an older one.
- **`<systemPropertyVariables>` are not captured yet** — Surefire sets them via a booter properties
  file that is invisible to JVM introspection. Capturing them is a known future improvement.
- **Integration tests (Failsafe) are not captured.**
  Capture rides Surefire's `test` fork, never Failsafe's (`integration-test` / `verify`), so `*IT` classes
  can't be run from the editor or an agent; run them with the build.
  A run of one says so, rather than naming a sync that would capture nothing.

On Gradle:

- **Only each project's `test` task is captured.** Other test tasks, such as an `integrationTest` task, and
  source sets other than `main` and `test` are not.
- The other Gradle limits (the `JDK_JAVA_OPTIONS` notice, the Develocity test launcher) are in the
  [Gradle guide](gradle.md#known-limits).
