# Lathe — Wider Maven and JDK Support (Maven 3.6.3+, JDK 17+)

## Status

**Proposed.** Lathe's documented floor today is **Maven 3.9+** (`README.md`: "earlier 3.x releases are not supported").
Feedback from real users suggests that most of them run older Maven: distribution packages, wrappers pinned years ago, corporate images.
A spike on 2026-10-18 showed that Lathe can support **Maven 3.6.3+** with one small registration change and one defensive guard.
It also showed that today's behavior on older setups ranges from silent to build-breaking.

**JDK 17+, decided 2026-10-18.** Lathe's documented JDK floor today is **21** (`README.md`).
Java 17 is still the newest JDK for roughly a quarter to a third of Java projects
(New Relic 2024: 35% of production applications; Azul 2025: 34% of organizations; JetBrains 2025: 39% of developers, answers overlapping),
many of them in companies that standardize on one JDK.
Lathe moves to **JDK 17+** in the same effort as the Maven floor: both widen who can use Lathe, both touch the extension and plugin modules, and both add older-version CI rows.
An audit on the same day found the move cheap everywhere except `lathe-server` (see [JDK 17 audit](#jdk-17-audit-2026-10-18)).
Java 8 and 11 stay out of scope.

This document turns both into a design: the new floors, the fixes, and how Lathe detects an unsupported setup and tells the user what to do.

## Goal

1. Lathe works on **Maven 3.6.3 and newer**, with the same features as on 3.9.
2. **Lathe never fails obscurely.** A build Lathe can't support stops before anything runs, with one message naming each problem, its fix, and how to build without Lathe. *(Revised 2026-10-18: an earlier draft skipped unsupported modules with a warning; failing fast is simpler and leaves no half-supported state to record and explain.)*
3. **No silent failures.** A setup Lathe can't support is detected inside Maven where possible, and by the editor client and sync script where not. The message names the problem and the fix.
4. Lathe works on **JDK 17 and newer**: the build may run on 17, the project's tests may run on 17, and the language server runs on the project's build JDK, as it does today.

## Spike results (2026-10-18)

Setup:
- Maven 3.6.3 (archive checksum verified) on JDK 21.
- The `multi-module` invoker fixture, in a throwaway worktree with its own local repository and Lathe cache.
- Fixture clone adjusted to look like a JDK-21-era project on Maven 3.6: `maven-compiler-plugin` and Surefire pinned, because Maven 3.6.3's defaults (compiler 3.1, Surefire 2.12.4) can't build it at all.

### Finding 1 — on Maven before 3.9, Lathe silently does nothing

Maven loads the extension (its class realm is created), but `lathe:init` and `lathe:sync` are never injected, `.lathe/` is never created, and nothing is logged.

**Cause:**
- Maven discovers extension components through Sisu.
- Maven 3.6.3 ships Sisu 0.3.4, whose embedded ASM (built 2019-11-05) cannot read Java 21 class files (major version 65). A direct test shows its `ClassReader` throws `IllegalArgumentException` on `LatheLifecycleParticipant.class`.
- Sisu skips the component without reporting anything.
- Maven 3.9.16 ships Sisu 1.0.0 with ASM 9.9.1, which is why 3.9 works.

**Fix, verified:** register the participant in a Plexus descriptor (`META-INF/plexus/components.xml`), which Maven reads without parsing class files, *instead of* the Sisu index.
The participant has a no-arg constructor and no injected fields, so nothing else changes.

| Maven | Result with the Plexus descriptor |
|---|---|
| 3.6.3 | fixture verification suite 49/49 (LSP smoke 16, MCP smoke 9, multi-module checks 24); 6/6 compiler captures; test-launch capture |
| 3.9.16 | 49/49; `init` and `sync` each run exactly once (no double registration) |

### Finding 2 — `maven-compiler-plugin` older than 3.6.2 breaks the build

The compiler shim calls plexus-compiler APIs that old compiler plugins don't bundle:

| `maven-compiler-plugin` | Result |
|---|---|
| 3.1 (Maven 3.6.3's default binding), 3.5.1 | build fails: `NoSuchMethodError: CompilerConfiguration.getModulepathEntries()` |
| 3.6.0 | build fails: `NoSuchMethodError: CompilerConfiguration.isParameters()` |
| 3.6.2, 3.7.0, 3.8.1 | works |

This depends on the **compiler plugin version, not the Maven version**: a Maven 3.9 project that pins compiler plugin 3.1 breaks the same way today.
The user sees a wall of class-realm output that doesn't mention Lathe or what to do.

Mitigating context: a project building on JDK 21 effectively can't use compiler plugin 3.1 anyway, because its default `-source 5` is rejected.
The trap is a project that sets `maven.compiler.source`/`target` and leaves the plugin unpinned.

### Finding 3 — the API surface is already 3.6-compatible

- Lathe's Maven modules (`lathe-maven-extension`, `lathe-maven-plugin`, `lathe-install`, `lathe-openjdk-maven-plugin`) compile against `maven-core` / `maven-plugin-api` **3.6.3** without code changes.
- The only snag is a lint: 3.6.3's dependencies put Sisu's annotation processor on the compile path, and `-Xlint:all -Werror` turns its "no processor claimed `@Named`" warning into an error.
- The plugin descriptor currently declares `<requiredMavenVersion>3.9.15</requiredMavenVersion>`, derived from `maven-api.version`. Maven 3.6.3 ran the plugin anyway, but the descriptor should state the real floor.

### Other observations

- **Test capture works with Surefire 2.22.2**, the most common version of the Maven 3.6 era, as well as with 3.5.4.
- The fixture's `verify` module uses JUnit 6.1.3, which Surefire 2.22.2 can't run. That reproduces with Lathe disabled, so it's a JUnit requirement, not Lathe's.

## JDK 17 audit (2026-10-18)

Every module was compiled with `-Dmaven.compiler.release=17` in a throwaway worktree,
Java 18–21 constructs were counted in the sources,
and the class-file version of every runtime dependency was checked.

| Layer | Modules | Runs on | Result |
|---|---|---|---|
| Inside the project's JVMs | `lathe-junit` (test fork), `lathe-test-runner` (replay), and the `lathe-core` classes they carry | the project's test JDK | Compile for 17 unchanged; every dependency loads on 17 (the newest, JUnit 6, is exactly Java 17) |
| Inside the build tool | `lathe-maven-extension`, `lathe-maven-plugin`, `lathe-compiler`, `lathe-install`, `lathe-openjdk-maven-plugin`, `lathe-core` | Maven's JVM (and the Gradle daemon, later) | Compile for 17 unchanged, except one `getFirst()` in `lathe-openjdk-maven-plugin` |
| Language server and MCP server | `lathe-server`, `lathe-mcp-server` | the project's build JDK (`.lathe/java-home`) | Need the migration below. They use no javac internals (only the public `com.sun.source` API), and all 34 runtime jars load on 17 |

What `lathe-server` uses from Java 18–21:

| Construct | Main | Tests |
|---|---|---|
| Type patterns in `switch` (`case Foo f ->`) | 116 | 1 |
| Record deconstruction patterns (`instanceof Foo(var a, …)`) | 12 | 0 |
| `getFirst()` / `getLast()` / `reversed()` (Java 21 sequenced collections) | 48 | 191 |
| `ExecutorService` in try-with-resources (Java 19) | 0 | 1 (in `lathe-core`'s `FileUtilTest`) |

Plus one `getFirst()` in a `lathe-maven-plugin` test.

Java 17 class files (major version 61) are also newer than what Maven 3.6.3's 2019 ASM can read, so [D1](#d1--register-the-extension-through-a-plexus-descriptor-not-the-sisu-index) is still needed on JDK 17.

**Considered and rejected — build side on 17, server pinned to 21.**
The server would then run on a different JDK from the build whenever the build uses 17:
the launcher would need a fallback that finds some JDK 21+,
and the server would have to reproduce the project's API level (`--system <build JDK>`) to keep its diagnostics faithful.
Moving every module to 17 keeps today's rule, the server runs on the build's own JDK, with no new machinery.

## Decisions

### D1 — Register the extension through a Plexus descriptor, not the Sisu index

`lathe-maven-extension` ships `META-INF/plexus/components.xml`:

```xml
<component-set>
  <components>
    <component>
      <role>org.apache.maven.AbstractMavenLifecycleParticipant</role>
      <role-hint>lathe</role-hint>
      <implementation>io.github.aglibs.lathe.maven.extension.LatheLifecycleParticipant</implementation>
      <instantiation-strategy>singleton</instantiation-strategy>
    </component>
  </components>
</component-set>
```

- `META-INF/sisu/javax.inject.Named` is deleted. Keeping both is harmless on 3.9 (verified: one registration) but leaves two sources of truth.
- `@Named` / `@Singleton` stay on the class only if something else needs them; otherwise they go too.
- A test asserts the jar contains the descriptor and no Sisu index, so a future refactor can't silently reintroduce the Maven 3.6–3.8 no-op.

The plugin (`lathe-maven-plugin`) is unaffected: Mojos are described by `plugin.xml`, which `maven-plugin-plugin` generates, not by Sisu scanning.

### D2 — Compile against the floor: `maven-api.version` = 3.6.3

- The compile target *is* the floor, as the existing pin note says. Compiling against 3.6.3 makes any accidental use of a newer API a compile error, not a runtime surprise.
- `maven-plugin-plugin` then writes `<requiredMavenVersion>3.6.3</requiredMavenVersion>` into the descriptor.
- The Maven modules add `-proc:none`. They need no annotation processing: the Plexus descriptor (D1) and `plugin.xml` are hand-written or produced by plugins, not by processors. This matches what happens under 3.9 today.
- The existing Dependabot pin note for `maven-api.version` changes from 3.9.15 to 3.6.3; bump PRs are still closed.

### D3 — Fail fast, clearly, on an unsupported build (decided 2026-10-18)

It replaces the earlier skip-and-warn D3 and absorbs D4, which only warned about an old Maven.

In `afterProjectsRead`, before injecting anything, the participant collects every unmet requirement (`LatheRequirements`):

- **Maven older than 3.6.3**, read from the `maven.version` system property Maven sets to its own version;
- **any module whose effective `maven-compiler-plugin` is older than 3.6.2** (POM-declared or default-bound: both are in the model by then). Modules without the compiler plugin are skipped.

If any is unmet, the build stops with one `MavenExecutionException`; modules sharing an old compiler version share a line:

```
[ERROR] [lathe] This build is older than Lathe supports:
[ERROR]   - Maven 3.5.4 is older than 3.6.3, the oldest Lathe supports: upgrade Maven, or the project's wrapper (mvn wrapper:wrapper -Dmaven=3.9.11)
[ERROR]   - maven-compiler-plugin 3.1 (core, app) is older than 3.6.2: pin a newer version in <pluginManagement>, for example 3.13.0
[ERROR]   To build without Lathe, add -Dlathe.disabled=true
```

- **Why fail rather than skip:** a build Lathe can't support already failed before this change, with a `NoSuchMethodError` wall that never mentions Lathe; skipping modules would add a half-supported state to record (`workspace.json`) and explain (the server). Old compiler plugins are rare on JDK 17+ projects (compiler 3.1's default `-source 5` is rejected there), CI is unaffected (Lathe switches itself off under `CI`), and the message gives both ways out.
- The floors (`MIN_MAVEN_VERSION`, `MIN_COMPILER_PLUGIN_VERSION`) and the `maven.version` key are constants in `LatheLayout.Maven`; the plugin lookup is the injector's own (`findPlugin`).
- The check runs wherever the extension loads: Maven 3.3.1+ (`.mvn/extensions.xml`). Older Maven loads nothing, which D5 detects from outside.

### D5 — Detect what Maven can't report, from the outside

Maven before 3.3.1 doesn't read `.mvn/extensions.xml` at all, so nothing inside the build can warn.
And any failure that leaves no `.lathe/` looks the same from the editor: "nothing happens".
The client and sync scripts close this gap:

- **Before a build Lathe starts** (sync script, guided setup): determine the effective Maven version from the wrapper's `distributionUrl` in `.mvn/wrapper/maven-wrapper.properties`, or from `mvn -v`. If it's below 3.6.3, say so before running: *"Lathe needs Maven 3.6.3 or newer; this project uses 3.5.4 (from .mvn/wrapper). Upgrade the wrapper: …"*
  With the [Multi-OS](lathe-multi-os.md) D3 decision, this check lives once, in `SyncCommand`.
- **After a build, if `.lathe/` is still missing**, the client's existing "run the build" nudge adds what to check:
  *"If you already built: Lathe needs Maven 3.6.3+ (`mvn -v`) and prints `[lathe]` lines in the build log; if there are none, the extension didn't load. See the troubleshooting guide."*
- **Version-skew protection** (an old `.mvn/extensions.xml` pin) is unchanged and out of scope here.

### D6 — Documented support

| Component | Floor | How it's enforced |
|---|---|---|
| Maven | 3.6.3 | D3 stops the build inside Maven (3.3.1+); D5 pre-flight and post-build hints outside |
| `maven-compiler-plugin` | 3.6.2 | D3 stops the build, naming the modules |
| Surefire (test capture) | 2.22.2 observed working | capture is best-effort; no new guard |
| JDK running the build, the tests, and the language server | 17 | `release` 17 at compile time (D7); a JDK 17 CI row |

`README.md` and `docs/guide/installation.md` change "Maven 3.9+" to "Maven 3.6.3+" and "Java 21+" to "Java 17+", list the compiler-plugin floor, and add a troubleshooting section built around the messages above.

### D7 — JDK 17 floor, every module

- `maven.compiler.release` is **17** in the root POM, for every module, `lathe-server` and `lathe-mcp-server` included.
  The compiler then rejects any Java 18+ API or language feature: that is the enforcement, with no separate check.
- The server keeps running on the build JDK recorded in `.lathe/java-home` (`LATHE_JAVA_HOME` still overrides).
  A Java 17 project gets a server on JDK 17 and javac 17, so the API and language level match the build by construction.
  No launcher fallback, no `--system`.
- **Migration rules for `lathe-server`**, mechanical and behavior-preserving:
  - `switch` type patterns and record patterns become `if` / `else if` chains with `instanceof` patterns (Java 16+), in the same order as the cases.
    Where a `switch` relied on exhaustiveness over a sealed type, the chain ends in a branch that throws `IllegalStateException` naming the unexpected type.
  - `getFirst()` / `getLast()` / `reversed()` become `get(0)` / `get(size() - 1)` / an explicit reversed copy.
    If the same expression repeats across many call sites, one small helper in `lathe-core`'s `CollectionUtil` replaces it (DRY), rather than a helper per file.
  - The one `ExecutorService` try-with-resources shuts the pool down explicitly.
- **What is lost:** the compiler's exhaustiveness check on `switch` over sealed types.
  The throwing final branch turns a missed case into a loud failure, and the existing tests cover each branch.
- **Dependencies:** every added or bumped dependency must run on Java 17.
  The Maven/Java compatibility-floor pin notes change from "Java 21" to "Java 17"; Dependabot bumps that need Java 18+ are closed, like the Maven API pins.
- `AGENTS.md` changes "Java 21: `<maven.compiler.release>21`" to 17 and notes that Java 18+ features are not available.

### D8 — Newer javac API through a multi-release jar (decided 2026-10-18)

The server runs on the project's JDK, 17 through the newest, and uses that JDK's javac, so it must handle every Java language version.
Most new syntax needs nothing: tree paths, attribution, `Elements` and `TreeScanner` are version-neutral and handle new constructs on a newer JDK.
Code is version-specific only where it must *name* a newer tree type or call a newer method — today one place, the case-label check (`CaseLabelTree`, Java 21).

- **`lathe-server` is a multi-release jar** (JEP 238). The few classes that need a newer javac API have two versions:
  a JDK 17 base in `src/main/java`, and a JDK 21 version in `src/main/java21`, compiled with `release 21` into `META-INF/versions/21` (a second `maven-compiler-plugin` execution, `multiReleaseOutput`);
  the manifest says `Multi-Release: true`. Each version uses its own JDK's API: the base finds a case-label constant through `CaseTree.getExpressions()`, the JDK 21 version through `CaseLabelTree`.
- **One seam class, `CompilerTrees`,** holds every such check; the rest of the server calls it and knows nothing of JDK versions. A new JDK-specific check is one method there; a `java25` directory appears only when something needs JDK 25 API.
- **No preview API, anywhere:** `CaseLabelTree` is preview in JDK 17, so it lives only in the JDK 21 version; `-Xlint:all -Werror` with no `preview` suppression enforces it.
- **Rejected:** compiling against the newest JDK with runtime guards (loses the `release 17` guarantee), detecting new tree types by `getKind().name()` (a workaround), and keeping the server on 21 (the same problem returns between 21 and 25).
- **Tests:** Surefire runs from `target/classes`, where the JVM ignores `META-INF/versions`.
  A `multi-release-jdk21` profile, active when Maven runs on JDK 21+, copies the classes, lays the `versions/21` classes over them, and runs Surefire from the copy.
- **Lathe's own build needs JDK 21+** (to compile `src/main/java21`); Lathe *runs* on 17+. The CI JDK 17 row builds on 21 and runs every test and the invoker fixture builds on 17 (`-Djvm`, `-Dinvoker.javaHome`), with the layering profile off.

**Spike (2026-10-18):**

| Check | Result |
|---|---|
| Multi-release build with JPMS | base class (major 61) plus `META-INF/versions/21` class (major 65), `Multi-Release: true` |
| Run time, from the jar on the module path | JDK 17 loads the base, JDK 21 and 25 load `versions/21`; each recognizes a case-label constant through its own API |
| Tests on JDK 21+ without the layering | fail: the base class loads from `target/classes` |
| Tests on JDK 21+ with the layering | all pass, running the JDK 21 class |
| Build on 21, everything else on 17 | the full build, every test, and both invoker fixtures pass |

**Found on the way to green on JDK 17:**

- **Fixtures compiled at a hard-coded `release 21`** — 45 server tests failed on 17. One constant, `TestCompiler.RELEASE`, now compiles fixtures at the test JDK's own level.
- **Fixtures relied on javac 21+ keeping unresolved annotations.** Run/test discovery found no tests on 17 because `@Test` didn't resolve (the fixtures had no JUnit on their classpath, one lacked an import). `TestCompiler.JUNIT_CLASSPATH` gives them JUnit, as a real project has: the real path, on every JDK.
- **Genuine JDK requirements** are marked `@EnabledForJreRange`: the formatting tests (the google-java-format release the fixtures pin needs JDK 21+; a JDK 17 project pins one that runs on 17, as its own Spotless run requires), the Java 21 `switch`-pattern fixture, the Java 22 unnamed-variable fixture; the palantir conditions moved to the same annotation.
- **The invoker took `lathe-compiler`, `lathe-server` and `lathe-mcp-server` from `~/.m2`**, which a local `mvn verify` leaves stale (7a by another path); they are now `extraArtifacts`.
- **The invoker fixtures are Java 17 projects** (their sources use nothing newer), so the JDK 17 row proves Lathe end to end for a Java 17 project.
- **Lathe's own dogfood extension (a published Java 21 Lathe) crashes JDK 17 test JVMs** — the very bug this design fixes. It is removed from the root POM for now and returns once a Java 17 release is pinned.

## Testing

### CI matrix (decided 2026-10-18)

The repository is public, so GitHub-hosted runners are free and unlimited, macOS and Windows included.
What binds is the account-wide limit of **20 concurrent jobs, 5 of them macOS** ([Actions limits](https://docs.github.com/en/actions/reference/limits)), shared with the `lathe.nvim` mirror and Dependabot.
The axes barely interact: the JDK matters to the whole build and test suite, the Maven version only to the invoker suite (which runs the fixtures with Maven; Lathe itself builds on 3.9), the OS only to scripts, paths and launchers.
So CI **covers each axis instead of every combination** (a full cross product would be 48 jobs per push):

| Job | JDK | Maven | OS | Runs |
|---|---|---|---|---|
| Full build and tests | **17** (floor) | 3.9 | Linux | everything |
| Full build and tests | 21 (release baseline) | 3.9 | Linux | everything, plus the client modules |
| Full build and tests | 25 (LTS) | 3.9 | Linux | everything |
| Full build and tests | 27 (latest) | 3.9 | Linux | everything |
| Full build and tests | 25 | 3.9 | macOS | everything |
| Invoker suite only | **17** | **3.6.3** | Linux | Lathe built on 3.9, the fixtures run with 3.6.3 |
| Invoker suite only | 21 | **3.8.8** | Linux | same |
| Invoker suite only | 21 | **3.10** | Linux | same |
| *(later)* Full build and tests | 25 | 3.9 | Windows | [Multi-OS](lathe-multi-os.md) Phase B |

About 8 jobs of about 3 minutes, all on every push and pull request; no nightly or separate compatibility workflow.
Two more settings:

- **`fail-fast: false`**, so one broken combination does not cancel the others: compatibility rows exist to show every combination that breaks.
- **`paths-ignore` for documentation-only changes** (`docs/**`, `**/*.md`). If CI ever becomes a required check for pull requests, a docs-only pull request would wait for a check that never runs; that does not matter while changes go straight to `main`.

Notes on the rows:

- On the JDK 17 row the invoker smoke tests also run the language server on 17.
  javac 17 differs from 21 in some diagnostics and error recovery; a server test that asserts JDK-21-specific output gets a JDK-aware expectation, as the 25 and 27 rows already require.
- If the pinned google-java-format cannot run on JDK 17, that row skips the formatting check; the other rows enforce it.
- The fixture already pins its plugins for the 3.9 defaults. The older Maven rows need the compiler plugin and Surefire pinned, so the fixture's root POM pins them explicitly, the way a real project on old Maven must.
- **New invoker fixture `old-compiler`:** a project pinned to compiler plugin 3.1, with no sources, expected to fail (`invoker.buildResult=failure`).
  With no sources the old plugin never runs and cannot fail the build, so the failure is Lathe's check; no verify script (the invoker suite uses no Groovy or BeanShell), and the message wording is `LatheRequirementsTest`'s.
- **Unit tests:** `LatheRequirements` (D3): a supported build reports nothing; an old Maven plus old compiler plugins yields one line per problem, modules sharing a version on one line; an unknown Maven version skips only the Maven check. The jar-content check (D1).
- **Wrapper-version detection (D5)** is a pure function over `maven-wrapper.properties`, unit-tested on samples.

## Delivery slices

Approved 2026-10-18. Each slice builds and passes every test on its own and is one reviewable commit; the work runs on one branch with a pull request, so every slice is checked by CI.

**JDK 17 floor (D7):**

- **S1 — Java 21 APIs to Java 17 equivalents**, still compiled for 21.
  `getFirst()` → `get(0)` (37 main, 193 test); `getLast()` (10) → one-line helper `CollectionUtil.last` in `lathe-core` (every `reversed()` turned out to be Java 8's `Comparator.reversed()`);
  `removeFirst()` / `removeLast()` on a `List` → `remove(0)` / `remove(size() - 1)` (on a `Deque` they are Java 6 API and stay);
  the one `ExecutorService` try-with-resources → explicit `shutdown()`.
  **Exit:** the full suite passes on JDK 21.
- **S2 — `switch` and record patterns to Java 17**, in `lathe-server` (129 sites in 12 files).
  One mechanical rule: each `case Foo f [when …] ->` becomes `if (x instanceof Foo f [&& …]) { return …; }` in the same order (early returns);
  over Lathe's own sealed types the chain ends in `throw new IllegalStateException(…)`; record patterns become `instanceof` plus accessors. No test changes.
  **Exit:** the full suite passes on JDK 21.
- **S3 — The build targets 17, with the multi-release seam (D8).** `maven.compiler.release` 17; `CompilerTrees` and its JDK 21 version; the multi-release build and test layering;
  the JDK 17 CI row (build on 21, test on 17) and the docs-only `paths-ignore`; the test fixes and markers above; Java 17 invoker fixtures; the dogfood extension paused; `AGENTS.md`.
  **Exit:** the build and every test pass on JDK 17, 21, 25 and 27.

**Maven 3.6.3:**

- **S4 — Registration and API floor** (D1, D2): the Plexus descriptor instead of the Sisu index, `maven-api.version` 3.6.3, `-proc:none`, the jar-content test,
  the invoker-only CI rows (Maven 3.6.3 on JDK 17, 3.8.8, 3.10), and the fixture's pinned compiler plugin and Surefire.
  **Exit:** the invoker suite passes on Maven 3.6.3, 3.8.8, 3.9 and 3.10.
  *As built:* `<proc>none</proc>` once in the root compiler configuration (no module uses an annotation processor), not per module;
  a test reads this module's own output (Maven's jars on the test class path carry Sisu indexes of their own);
  the invoker-only rows are a separate CI job that downloads its Maven and passes it as `invoker.mavenHome`.
- **S5 — Fail fast, clearly** (D3): one requirements check for the Maven version and every module's compiler plugin, stopping the build with one message; the `old-compiler` fixture.
  **Exit:** a compiler plugin 3.1 project stops before compiling, with Lathe's message.
- **S6 — Tell the user** (D6 and D5's post-build hint): the client nudge text and the docs (README and installation guide to Java 17+ and Maven 3.6.3+, plus troubleshooting built around D3's message).
  D5's **pre-flight check** waits for `SyncCommand` ([Multi-OS](lathe-multi-os.md) A2) instead of going into today's shell script.
  **Exit:** each case in the D6 table produces its message, and the docs show the new floors.
  *As built:* README requirements (Java 17+, Maven 3.6.3+ with compiler plugin 3.6.2+, the CI-verified versions) and two troubleshooting entries
  (no `[lathe]` lines in the build log means the extension didn't load; D3's message and its fixes); the Neovim and VS Code nudges add one sentence pointing at the build log and the Maven floor.

S4 alone turns Maven 3.6–3.8 from "silently nothing" into "works"; S5 turns the only build-breaking case found into a clear message.

Rough effort: S1 half a day, S2 1.5–2 days, S3 half a day to a day, S4 about 1 day, S5 half a day, S6 half a day.

## Relationship to other designs

- **[Multi-OS Design](lathe-multi-os.md):** the CI matrix grows along three axes: OS, Maven version, and JDK.
  Its platform table's "Java 21+" becomes "Java 17+" once D7 lands. D5's pre-flight check lives in `SyncCommand` (multi-OS D3) and uses its tool resolver (D9) to find the wrapper.
- **[Personal Setup](../potential/lathe-personal-setup.md) (potential):** with real users on older Maven, `-Dmaven.ext.class.path` (works since Maven 3.0.2) is the main personal-mode path, and `~/.m2/extensions.xml` (Maven 3.10+) the minority path. D1 matters for personal mode too: the extension must load on old Maven whichever way it is registered.
- **[Gradle support](lathe-gradle-support.md):** benefits from D7.
  The Gradle plugin targets Java 17, so the Gradle daemon may run on 17 (Gradle 9's own minimum),
  and test workers that Gradle toolchains run on 17 can load `lathe-junit`.
  The Maven-specific decisions (D1–D5) don't apply to it.

## Open questions

1. **Maven 3.3.1–3.6.2:** worth a CI row that checks D3 stops the build? 3.6.3 is from 2019, and older versions are rare even among old setups.
2. **`maven-compiler-plugin` 3.6.2 vs 3.7.0:** 3.6.2 worked in the spike. Should the floor be the oldest that works, or a round, well-known version?
3. **Plugins between Maven and Lathe that read the compiler configuration** (Error Prone setups, Lombok, `maven-processor-plugin`): they behave the same as on 3.9, but haven't been tested on an old Maven. A real older-Maven project from a user would be the best next check.
4. ~~**Maven 3.6.3 on JDK 17** is untested.~~ *Answered 2026-10-18 (S4): the invoker suite passes with Maven 3.6.3 on JDK 17, fixtures and smoke tests included.*
5. **Toolchains (follow-up, not changed by this design):** when the build runs on one JDK but compiles on another (Maven toolchains, Gradle toolchains)
   and the project sets `-source`/`-target` instead of `--release`, `.lathe/java-home` names the JDK that ran the build, not the one that compiled,
   and the compiler shim records only `--release`.
   The server then accepts APIs of its own JDK that the real build rejects.
   This exists today on JDK 21. Recording the compiling JDK fixes it; it matters more for Gradle, where toolchains are the norm.
