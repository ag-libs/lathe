# Test Capture

Lathe runs and debugs tests by **replaying** them from the captured `.lathe/` bytecode with no
recompilation.
To know *how* to launch that replay JVM — the exact classpath, module path, and JVM arguments Maven's
Surefire fork used — Lathe captures the real launch from inside the test fork.
That capture is done by `lathe-junit`, a small `test`-scoped dependency added to every module
(injected by the [Maven extension](installation.md), or declared by hand in a
[manual setup](installation.md#method-2--manual-pom-configuration)) — nothing else to add.

No `maven-surefire-plugin` configuration is needed:
`lathe-junit` registers a JUnit Platform `LauncherSessionListener` through the standard service-loader
SPI, and Surefire's JUnit Platform provider auto-detects it.

On any build that actually runs tests (`mvn test`, `mvn verify`, `mvn install`), the listener fires
once per module, before test execution, and writes `.lathe/<module>/test-launch.json`.
It records — from live JVM introspection, not by parsing Surefire's command line:

- `java.home` (replay uses the same JDK),
- the fork classpath (with `lathe-junit`'s own jar removed, so replay never has to strip it),
- the module path and module directives (`--patch-module`, `--add-opens` / `--add-reads` /
  `--add-exports`, `--add-modules`),
- and the remaining JVM args from `<argLine>`.

Because these are read *after* the JVM expanded Surefire's argfile, the captured template is the
**effective, interpreted** launch — the module graph the tests actually ran under — rather than a
guess reconstructed from POM text.

## Capture without running tests

To refresh the launch templates without paying for a test run, add `-Dlathe.capture.only=true` (with
`-DfailIfNoTests=false`): each module still forks and writes its `test-launch.json`, but a
post-discovery filter excludes every test from execution, so nothing actually runs.

```bash
mvn test -Dlathe.capture.only=true -DfailIfNoTests=false
```

This is the recommended [first-time setup](installation.md#initialize).
Note `-DskipTests` is **not** a substitute — it skips the fork entirely, so nothing is captured.

## Requirements and current limits

- **A modern, JPMS-capable Surefire (e.g. 3.5.5+).**
  An old Surefire forks non-modularly and yields an empty argument list, so nothing meaningful is
  captured. Pin `maven-surefire-plugin` to a recent version if your build inherits an older one.
- **JUnit Platform (JUnit 5/6, or the JUnit 4 vintage engine).**
  The listener rides the JUnit Platform launcher; pure TestNG forks are not captured.
- **`<systemPropertyVariables>` are not captured yet** — Surefire sets them via a booter properties
  file that is invisible to JVM introspection. Known gap.
- A module whose tests are skipped or absent produces no `test-launch.json`, so its tests are not
  runnable from the editor until a build actually forks them.
