# Lathe — Test Capture and Running on Java 8

Status: potential, deferred — not planned; Lathe keeps the documented limit (tests on a JDK older than 17 run, but are not captured) until there is demand.
Capture the test launch, and run and debug tests from the editor, when the build runs the tests on a JDK older than 17, Java 8 first.
Lathe itself (build plugins, server) keeps its Java 17 floor; only what loads inside the test JVM goes down to Java 8.

## Problem / motivation

Lathe captures how the build starts a module's test JVM and replays it when the editor runs or debugs a test.
Both halves load Lathe classes into the test JVM, and those classes are Java 17 bytecode, which an older JVM cannot load.
So a project whose tests run on an older JDK can edit and navigate, but not run or debug a test from the editor.

| Setup | Today |
|---|---|
| Build on JDK 17+, code compiled for 8 (`--release 8`), tests on the build's JDK | captured and runnable: only the bytecode is Java 8 |
| Tests forked onto JDK 8–16: Surefire `<jvm>`, a Maven toolchain, a Gradle test toolchain | tests run, nothing is captured; the build prints `[lathe] test launch not captured: this test JVM runs Java 8…`, and a Gradle capture-only build skips the task |
| Maven or Gradle itself on JDK 8 | no Lathe at all: out of scope here |

A Gradle toolchain of 8 or 11 runs the test tasks on that JDK by default, so this is mostly a Gradle case.
Libraries that keep Java 8 support on a maintenance line do this; JUnit's `releases/5.14.x` names a `JDK8` toolchain installation in its `gradle.properties`.
The current main lines of JUnit (6.x), Spring Framework (7.x), Spring Boot (4.x), Jdbi, Dropwizard and Helidon all test on Java 17 or newer and are not affected.

[Wider Maven Support](../done/lathe-wider-maven-support.md) left Java 8 and 11 out of scope, and [Test capture](../guide/test-capture.md#requirements-and-current-limits) documents the limit.

## What ties the test JVM to Java 17

The groundwork exists: the classes JUnit and the JVM load first in every test JVM (`CaptureLauncherSessionListener`, `CaptureOnlyPostDiscoveryFilter`, `CaptureOnly`, `LatheAgent`) are compiled with `--release 8`, so an old test JVM loads them, skips the capture below `LatheLayout.MIN_JAVA_VERSION`, and still honours capture-only.
The `LatheFlags` and `LatheLayout` constants they use are compile-time constants, which javac copies into them, so those Java 17 classes never load.
What stops it:

**Capture (inside the test JVM)**

1. **`LaunchCapture` is Java 17 code** (275 lines, about 23 Java 9+ constructs).
2. **It writes through the shaded `lathe-core`**, Java 17 bytecode: `TestLaunchData` (a record, which cannot exist on Java 8), `Json` (Gson, ValidCheck), `FileUtil.writeAtomically` / `slashed`, `LatheWorkspace.findRoot`, `LatheFlags.isDisabled`, `LatheLayout.paramsFileName` / `testLaunchFile`.
3. **Gradle loads the agent through `JDK_JAVA_OPTIONS`**, which only JDK 9+ reads (`LatheTestCapture`), and `capturesOn` skips a test task below 17.
4. **`java.home` on JDK 8 is `<jdk>/jre`.**
   `jre/bin/java` exists, so a replay starts, but a reader of JDK files there (the `release` file) looks in the wrong folder.

**Running (the replay, also in the test's JVM)**

5. **`lathe-test-runner` is Java 17 code** (4 files, 680 lines, about 60 Java 9+ constructs) and runs inside the captured JDK: `LaunchPlan` starts `<javaHome>/bin/java` with the runner on the test classpath.
   Its JUnit jars already follow the project's own JUnit (`runnerClasspath`), and the JUnit Platform 1.x runs on Java 8.
6. **Argfiles**: `Launcher` moves a command past 30,000 characters into `java @argfile`, which Java 8 rejects, and a real project's classpath passes that length.

Items 1, 2 and 5 also block Java 9–16; items 3, 4 and 6 are Java 8's own.

## Sketch

Everything that loads in the test JVM becomes Java 8 bytecode, compiled with `--release 8`.
`test-launch.json` keeps its format apart from one new field, so its readers (the server's `LaunchReader`, the Gradle plugin's `LatheTestCapture`) stay as they are.
`--release 8` also limits these files to the Java 8 language and API, so a later `var`, record or Java 9+ call fails Lathe's build instead of a user's test run.

### `lathe-junit`: the capture at release 8

- **One compile at `--release 8`** for the module's main code, replacing today's two (17 for `LaunchCapture`, 8 for the four entry classes listed by name).
- **`LaunchCapture` ported to Java 8 source.**
  Its option parsing (module path, `--patch-module`, `--add-*`, build-tool arguments) moves with it unchanged, so it covers Java 9–16 too.
- **The JSON written directly**, with `TestLaunchData`'s field names, instead of building the record and calling `Json`.
  The readers still parse it into `TestLaunchData`, whose compact constructor validates it, so ValidCheck stays out of the test JVM without losing the check.
  A small string writer is enough (the runner already has one for its results); Gson leaves the capture path.
- **`javaVersion` recorded**, for the classpath jar below.
- **A `…/jre` home recorded as its parent** when the parent has `bin/java`, so readers of JDK files find them.
- **The gate**: `CaptureLauncherSessionListener` captures from `MIN_TEST_JAVA_VERSION` up.

### `lathe-core`: a Java 8 class, shading kept

The capture still needs root finding, the disabled rule, the per-tree file names, `slashed` and the atomic UTF-8 write: about 40 lines.
They move into one small `lathe-core` class compiled with `--release 8` (a second `maven-compiler-plugin` execution naming the file), and `LatheWorkspace`, `LatheFlags`, `LatheLayout` and `FileUtil` call into it, so each rule keeps one copy.
`lathe-junit` keeps shading `lathe-core`: the shaded jar still holds the Java 17 classes, but the capture never loads them, as the entry classes never load them today.
Dropping the shading would need a copy of those 40 lines in `lathe-junit`; keeping it costs less source.

Also in `lathe-core`:

- `TestLaunchData` gains `javaVersion` (an `int`, 0 when absent); no backward compatibility is needed, and a sync with tests captures again.
- `LatheLayout.MIN_TEST_JAVA_VERSION = 8` beside `MIN_JAVA_VERSION`, which stays 17 for Lathe's own code.

### `lathe-test-runner`: the replay at release 8

- Main code at `--release 8`, tests at 17 (`maven.compiler.testRelease`), so the tests stay as they are.
- Port the four files: the records (`TestRecord`, `TestRecord.Frame`, `TestSelectorParser.Request`) become final classes, and the `->` switches, `var`, `formatted`, `List.of`, `isBlank`, `toList` and `Path.of` are rewritten.
- It already keeps `lathe-core` out of its production code, so nothing else follows.

### `lathe-server`: a classpath jar instead of an argfile

When the captured `javaVersion` is below 9 and the command passes the length limit, `Launcher` writes a manifest-only jar whose `Class-Path` lists the classpath (entries as file URIs), passes `-cp <that jar>`, and keeps the rest on the command line.
From Java 9 up it keeps the argfile.

### `lathe-gradle-plugin`

- For a test task on Java 8, the agent goes in `JAVA_TOOL_OPTIONS`, which Java 8 reads (it announces it with `Picked up JAVA_TOOL_OPTIONS`, as 9+ does for `JDK_JAVA_OPTIONS`).
  Like `JDK_JAVA_OPTIONS`, an environment variable leaves the task's inputs and cache key alone.
- `capturesOn` takes `MIN_TEST_JAVA_VERSION`, so a capture-only build no longer skips the task.

### What stays as it is

- **Debugging**: `JdwpOptions` passes `address=127.0.0.1:<port>`, Java 8 syntax, and Java 8 prints the `Listening for transport dt_socket at address:` line it waits for.
- **Main methods** run on the JDK the build records (Maven's own, or the Gradle task's).
- **`LatheFlags.UTF8_OUTPUT_JVM_ARGS`** (`-Dstdout.encoding`, JDK 18+) are plain properties to Java 8, which ignores them; output uses the platform encoding, as the build's own test run does.
- **A JUnit Platform older than 1.10** has no dry run, so capture-only falls back to the excluding filter, as it does today.

### Code style

`lathe-junit`, `lathe-test-runner` and the new `lathe-core` class follow Java 8 source rules: no `var`, records, `formatted`, `List.of` or switch arrows.
That is an exception to the Java 17 rules in `CLAUDE.md`, which says so, as the four entry classes already are.

### Testing

- **Round trip**: a launch written by the Java 8 capture reads back through `Json` into the `TestLaunchData` today's capture produces, for every launch shape the current tests cover; a field renamed on one side fails it.
- **Unit**: the classpath jar's `Class-Path`, with spaces and Windows paths; the `jre` home.
- **End to end on a real JDK 8**, the only proof that the classes load and the runner runs: an invoker fixture whose Surefire forks onto a JDK 8 toolchain, captured and then run and debugged through the server; a Gradle TestKit fixture with a Java 8 test toolchain.
- **CI**: a JDK 8 installed beside the build JDK (`actions/setup-java` with two versions) for those fixtures, on Linux; Windows covers the classpath jar.
- A real-world check: JUnit's `releases/5.14.x` on its JDK 8 toolchain.

### Steps

One gap, sized L, one pull request per step:

| Step | Change | Users see it |
|---|---|---|
| 1 | Runner at release 8 | no |
| 2 | Capture at release 8: the port, the `lathe-core` class, the JSON writer, `javaVersion`, the round-trip test; gate still at 17 | no |
| 3 | Maven: open the gate, the classpath jar, the JDK 8 invoker fixture and its CI job | yes, for JDK 8–16 at once |
| 4 | Gradle: `JAVA_TOOL_OPTIONS` below 9, the floor in `capturesOn`, the TestKit fixture | yes |
| 5 | Docs: the limits in `test-capture.md`, `maven.md`, `gradle.md`, `status.md` | — |

Steps 1 and 2 build and test on JDK 17+; steps 3 and 4 need a JDK 8 in CI.

## Alternatives considered

- **Raw capture, parsing in the readers**: the test JVM records only `java.home`, the classpath and the input arguments, and the server and Gradle plugin convert them.
  It keeps the parsing in Java 17, but changes the file format and both readers; porting the parsing keeps them unchanged.
- **Compiling Java 17 syntax to Java 8 bytecode** (Jabel, Retrolambda): the code keeps its style, at the cost of a fragile compiler plugin for about 1,000 lines.
- **No shading**: removes Gson and ValidCheck from the user's test classpath, at the cost of a copy of the 40 lines of `lathe-core` rules.
  Once the capture stops calling Gson and ValidCheck, they are only shaded, never loaded, so the gain is small.

## Open questions

- **JUnit 4 without the Platform**: Java 8 projects often run JUnit 4 through Surefire's JUnit 4 provider, with no JUnit Platform, so the listener never loads; capture there needs another entry point (out of scope; the vintage engine on the Platform is covered).
- **Old Surefire**: Java 8 projects pin old Surefire versions; [Test capture](../guide/test-capture.md) notes that an old Surefire forks non-modularly with an empty argument list. The oldest Surefire to test against is to be decided.
- **Main methods on a toolchain JDK**: whether running `main` from the editor should use the toolchain's JDK rather than the build's.
