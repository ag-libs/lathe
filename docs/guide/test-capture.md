# Test capture

To rerun one JUnit test from Neovim or an AI agent, Lathe needs to know how your build starts that test's JVM.
A Lathe-enabled Maven or Gradle build records the launch once, so you can rerun the method with the same JDK and flags.

The [first build](../../README.md#first-build) captures supported JUnit Platform launches without executing the tests.
Skipping the test task or building a module with no discoverable tests records no new launch.
See the [requirements](#requirements-and-current-limits) for supported JDKs, test engines, and build tasks.

## Capture without running tests

To refresh a captured launch, run the command for your build from the project root.

```bash
mvn test -Dlathe.capture.only=true                        # Maven
./gradlew latheSync -Plathe.capture.only=true --continue  # Gradle
```

In Windows PowerShell, quote the property: `'-Dlathe.capture.only=true'` or `'-Plathe.capture.only=true'`.

Each module with supported tests starts a test JVM so Lathe can record its launch.
JUnit 5.10 and later reports the selected tests as skipped.
On older JUnit, Lathe filters them out.

**Maven.** Add `clean` for the first capture so every module compiles with Lathe.
A build with `-DskipTests` skips the JVM that records the launch.
If older JUnit and `failIfNoTests` cause the build to fail, add `-DfailIfNoTests=false`.
Skipped-test warnings are expected.
During a multi-module build, Maven can also warn that a dependency belongs to the reactor while the root sync is waiting for that module to build.

**Gradle.** A capture-only run starts the default test JVM even if the task is up to date.
To capture tests from another source set, run its own task once, for example `./gradlew integrationTest -Plathe.capture.only=true`.
An editor sync captures the default tests.
[Gradle test limits](gradle.md#known-limits)

## What Lathe records

The build loads `lathe-junit` in the test JVM.
Before tests run, it reads the effective settings and writes `.lathe/<module>/test-launch.json` for the module's default tests.
The following settings are available for replay.

- `java.home` (replay uses the same JDK),
- the test classpath (with `lathe-junit`'s own jar removed, so replay never has to strip it),
- the module path and module directives (`--patch-module`, `--add-opens` / `--add-reads` /
  `--add-exports`, `--add-modules`),
- and the remaining JVM arguments the build gave the test JVM.

The JVM has already expanded argument files and environment options when Lathe reads these settings.
A replay applies captured options once, including those originally supplied through `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, or `_JAVA_OPTIONS`.
If your editor sets a new option through one of those variables, the run output names the variable, as does the agent's `run_test` result.
To apply it, capture a new launch or set it in the [run configuration's environment](run-configuration.md).
A main method inherits the editor's environment because its launch is built from the module's paths.

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
- **A test JVM must start.**
  Skipping the test task entirely leaves the module without a new launch.
  Use [capture-only mode](#capture-without-running-tests) to record the launch without executing the tests.
- **A test that finds files from where its class file lies fails when run from the editor**, as Lathe loads the
  classes from its own copy under `.lathe/`; see
  [Troubleshooting](troubleshooting.md) for running it with the build, and for the change that makes it work in
  both.

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
- **A copied classes directory runs as the last build left it.**
  A run replays the module's compile output from `.lathe/`, kept current as you edit.
  When Surefire tests another directory instead (its `classesDirectory` set to a copy, for example a
  multi-release overlay in `target/classes-jdk21`), that copy is not kept current: the run uses it
  as the last build left it, and its result says so in a note.
  Rebuild the module to refresh it.

On Gradle:

- **A source set's tests run class by class, once its test task has run.** The test task of each source set captures
  that source set's launch when it runs (`./gradlew integrationTest -Plathe.capture.only=true` runs none of its tests),
  so a test class or method there runs from the editor; a package or folder there offers no run, and a module run
  takes `test`'s tests only.
- **A package or module run from the editor runs every test class it discovers.** On Maven such a run follows
  Surefire's include and exclude patterns; the `test` task's `include`, `exclude` and `filter` settings are not
  recorded, so a class the build leaves out runs too. A run of a class or method runs exactly that.
- The other Gradle limits (the `JDK_JAVA_OPTIONS` notice, the Develocity test launcher) are in the
  [Gradle guide](gradle.md#known-limits).
