# Lathe and jdtls — LSP Benchmark Pre-Registration

**Status: PRE-REGISTRATION, drafted 2026-11-06, revised 2026-11-07; first runs 2026-11-07/08; amended 2026-11-07, 2026-11-08, 2026-11-11, 2026-11-19, 2026-11-20 and 2026-11-21 (see [Amendments](#amendments)).**
This file fixes the projects, servers, queries, metrics and fairness rules before any result exists, so none of them can be chosen after seeing the numbers.
It is committed ahead of the first run; results go in `results/` and are reported against it unchanged.
A change to this file after the first run is listed under [Amendments](#amendments) with its date and reason, and the runs before it are reported as such.

Why this exists, and what happens with the results: [Measured Comparison with jdtls](../../docs/planned/lathe-jdtls-comparison.md).
The rule from there: Lathe claims nothing over jdtls that these measurements do not show, and where they show Lathe worse, the fix comes before any claim.

## Servers

| Server | Version | Runs on | Settings |
|---|---|---|---|
| Lathe | `main` at `c15d3a55` (2026-11-07), as no release carries its changes since 0.3.1 (the capture-only speed-up #124, Gradle source sets #127 and their test launches #131); built from a clean checkout with its version stamped `0.3.99-c15d3a55`, as the release workflow stamps one, its Gradle plugin from the same build (the Gradle Plugin Portal has not approved the plugin); measured again on the first release after it; the working tree for before-and-after runs of a fix | the JDK the project's build recorded (`.lathe/java-home`) | its launcher's defaults, with `-Xmx2G` (`LATHE_JVM_OPTS`) from the 2026-11-11 amendment |
| jdtls | 1.63.0 (`jdt-language-server-1.63.0-202610191322.tar.gz`) | Corretto 25 | VS Code's defaults (`vscode-java`'s VM arguments, `-Xmx2G`) |
| java-index | 0.1.2, JVM relay | Corretto 21 | its own; spring-petclinic only; left the comparison on 2026-11-11 (see the amendment) |

Each server runs as its users get it, except for the heap: from the 2026-11-11 amendment every run gives both servers `-Xmx2G`, the limit vscode-java gives jdtls, and another heap is a labelled variant (`--heap`).
Before it, one extra run per project gave Lathe that limit, and the others ran Lathe with no limit.

## Projects

| Project | Build | Commit | What it represents |
|---|---|---|---|
| spring-petclinic | Maven | `500158f7` | small, single module; the commit java-index's harness pins, so its published numbers compare |
| Dropwizard | Maven | `46bb4ed819` | a large reactor |
| Helidon | Maven | `2f6df16425` | a large reactor, modular (JPMS), annotation processors (its builders) |
| Hibernate ORM | Gradle | `37616099b7` | a large Gradle build |
| Apache Fineract | Gradle | `d63beed7cc` | a large Gradle build built on Lombok and MapStruct; left the comparison on 2026-11-11 (see the amendment) |
| OpenMetadata | Maven | `9a2ee8b124` | a Maven reactor built on Lombok, with a shaded module (`openmetadata-shaded-deps`) its service compiles against; built through `package` so the shading runs, without the UI, distribution and generated-client modules (`openmetadata-clients` generates its code from a `swagger.json` this build does not produce) |

Each commit is the one the setup checks and the pre-release checks of 2026-11-06 and 2026-11-07 synced with Lathe; before the first run, each is synced again with the Lathe build above and imported in jdtls, and a project that does not import is listed as such, not replaced.
Every measured run uses the Lathe build above, redone from a clean checkout.
Dependencies are downloaded before any measured run, so no startup includes network downloads.

## Queries

Fixed per project before the first run, by position (file, a unique line substring, the token, its occurrence on the line), each with a hand-written check of the answer.

**The 17 kinds** java-index's benchmark asks, one query each:
definition into project code, an inherited method, a library method and a JDK method;
type definition of a local variable;
hover on a library method;
references to a widely used method and to one overload;
document highlight of a local variable;
implementations of a base class;
supertypes and subtypes;
incoming and outgoing calls;
signature help on a library call;
document symbols;
workspace symbols for a type-name prefix.

**The hard cases** found while profiling Lathe, one query each where the project has one:
references to a method with a common name;
references to a static utility method;
implementations of an interface method with many implementations;
references to a field read through a getter in another file;
references to an enum constant used as a `switch` label.

**Choosing the positions.** Each query names the most-used candidate in the project's main module, not a hand-picked one:
the widely used method is the project method with the most call sites by text search;
the common name is the method name spelled in the most files;
the static utility method is the most-called static method of a class with only static members;
the interface is the project interface with the most implementations;
the field and the enum constant are the most-referenced ones reached through a getter or a `switch` from another file.
Where a rule picks a symbol that does not exist in a project, that query is left out for that project and listed as such.

**spring-petclinic** uses java-index's own 17 queries unchanged (`benchmark/cases.py` in `slgobinath/java-index`, Apache License 2.0), so its numbers compare with java-index's published ones.

## Metrics

- **Ready:** from starting the server to the first correct answer to the project's definition query, polled every 250 ms.
  Cold means no server state (Lathe without `.lathe/`, jdtls without its workspace data); warm means the state the previous run left.
- **Build or import:** Lathe's build with its capture (Maven `process-test-classes`, Gradle `latheSync -Plathe.capture.only=true`) and jdtls's project import, each timed and reported as its own row; never folded into the other server's ready time or left out.
- **Memory:** the summed resident set (RSS) of every process the server runs, peak while starting, peak while answering, and idle 5 s after the last query.
- **CPU:** user and system time of those processes over the run.
- **Latency per query:** the first answer, then median and 95th percentile over 10 repeats, in a warm run.
- **Correctness:** each answer checked against its hand-written expectation; every set-valued answer (references, implementations, hierarchies, highlights) also compared location by location between Lathe and jdtls.
  A disagreement is resolved by the build: what javac compiles is the truth.

## Runs

Per project and server: 3 cold and 3 warm runs, alternating, reporting medians.
Lathe and jdtls are measured in the same session on the same machine, never from runs on different days.
Every run records the machine (CPU, memory, operating system), the system load before and after, and every JDK, server and project version.
No other heavy work runs on the machine during a run; a run whose load average exceeds the core count is discarded and repeated.
Gradle and Kotlin daemons started by a run are stopped after it.
Before a run, `python3 -I selfcheck.py` checks the harness's scenario logic against a fake client, without servers, in seconds.

## What is published

Nothing until the design's comparison page is written.
Then every row with its raw results and the command that reproduces it, the rows Lathe loses included.

## Amendments

- **2026-11-07, after spring-petclinic's first run (its queries are java-index's, which no rule picks):**
  the rules under [Queries](#queries) name "the main module" without saying which; each project's is now named in `projects.py` before its first run:
  the module its other modules build on (`dropwizard-core`, Helidon's `webserver/webserver`, `hibernate-core`, `fineract-core`, `openmetadata-service`).
  How the rules are applied by text search, and the base class for the kinds without a rule of their own, are in `pick.py`'s description.
- **2026-11-07, during the unmeasured priming runs, before any measured run of the projects named:**
  - The build row of a Maven project holds no test capture: `process-test-classes` stops before Surefire runs, and Maven captures a launch only when tests run. The Gradle rows (`latheSync -Plathe.capture.only=true`) do capture. [Metrics](#metrics) said "with its capture" for both.
  - jdtls cannot import Helidon from a fresh checkout: its Maven integration resolves the compiler plugin's dependencies (Helidon's own annotation processors, `28.0.0-SNAPSHOT`) only from a repository. jdtls's cold Helidon runs therefore run `mvn install -DskipTests` first, into a repository of their own under `.work/`, and that install is timed as jdtls's build row.
  - Dropwizard's `pgpverify` rejects an unsigned artifact. Lathe's releases are signed; the pinned local build is not, so Lathe's Dropwizard build allows the `io.github.ag-libs` group unsigned (`pgp-keys-map.list`) and verifies everything else.
  - The readiness probe must be a query whose right answer no server disputes. Hibernate ORM's was a constructor call, where Lathe answers the class and jdtls the constructor; its probe is now a method call, and the constructor call a query of its own.
  - The getter rule now requires the getter's body to be `return field;`: it had matched a local variable beside a static method of the getter's name.
  - Dropwizard's checks of two queries were wrong (Jetty's `setHandler` is `Handler.Wrapper`'s; `MigrationsBundle` does not override `run`); both servers answered alike, and the checks were corrected.
  - jdtls starts with the Lombok agent vscode-java gives it by default (`java.jdt.ls.lombokSupport.enabled`), the project's own Lombok, on the projects that use Lombok (Fineract, OpenMetadata); without it jdtls would not see Lombok's generated members.
  - jdtls does not import Apache Fineract: the Gradle model jdtls fetches through Buildship creates every task, and a plugin of Fineract's (its `tiOrder` task) fails on Gradle 9.7.1 when created. Turning off jdtls's Gradle annotation processing (`java.import.gradle.annotationProcessing.enabled=false`), a labelled variant, does not avoid it. Fineract is reported as a project jdtls does not import, with Lathe's numbers alone.
  - Fineract declares its repositories per project, so Gradle ignores a repository added in settings; Lathe's build adds the pinned build's repository to each project, for the `io.github.ag-libs` group only. A released Lathe comes from Maven Central, which Fineract declares.
  - OpenMetadata builds on JDK 21, the Java it targets: its Lombok (1.18.36) predates JDK 25.
- **2026-11-08, during the measured runs:**
  - Fineract's Gradle build leaves worker processes running after it ends, and the harness counted them as Lathe's: its first measured cold runs reported 10 processes and 9.9 to 14.7 GB. The harness now stops what a build leaves (new processes naming the clone or Gradle) and takes the server's baseline after the build; Fineract's Lathe runs were measured again. No other project's runs counted more than the server's own process.
  - A run during which the machine suspends is discarded and repeated, as one on a busy machine is: one Fineract run spanned a suspend (the lid closed), so a request seemed to take 101 minutes. The run was discarded.
  - A request that times out saves a thread dump of each of the server's JVMs beside the run's results.
- **2026-11-11, before any run of what it adds:**
  - **One heap for both.** Every run gives Lathe and jdtls `-Xmx2G` (Lathe through `LATHE_JVM_OPTS`, jdtls through vscode-java's arguments), the default of `bench.py --heap`; another heap is a labelled variant (`-heap<size>` in the results' name).
    The 2026-11-08 equal-heap runs showed every Lathe answer the same at that limit, so the comparison no longer leans on a heap only one server gets.
    Runs before this amendment are reported as they ran:
    Lathe with no limit, except the `-heap2G` runs.
  - **Lathe's build, as a user first runs it.** The build row of a cold Lathe run is the README's first-build command, test capture included: Maven `mvn clean test -Dlathe.capture.only=true`, Gradle `latheSync -Plathe.capture.only=true` (as before).
    Until now a Maven row was `process-test-classes`, which captures no test launch (the 2026-11-07 amendment), so it left out part of what Lathe asks of a user; those rows are reported as they ran.
    A project that cannot run that command as is (OpenMetadata needs `package` for its shaded module) states the command it ran instead, capture included where the build allows it.
    Beside it, the build the project costs anyway, timed once per project on a fresh checkout with no Lathe extension or plugin loaded: what compiles the code and its tests, Maven `clean test-compile` and Gradle `testClasses` (a project that needs more to compile, as OpenMetadata needs `package` for its shaded module, states its command); Lathe's own share is the difference.
    The same Maven command with Lathe turned off would not do: without Lathe, `-Dlathe.capture.only` means nothing and Surefire runs every test.
    The report also gives Lathe's time to a first answer, its build plus its ready time, beside jdtls's ready time, which includes its import.
  - **Whose setup.** One user throughout: Neovim as the client, with jdtls on vscode-java's settings (the 2026-11-07 setup, now at the same heap as Lathe).
    The client side (what it sends after outside changes, how it runs a test) follows Neovim and nvim-jdtls; the server side follows vscode-java, as a Neovim user's own settings vary (often no heap limit, a Lombok agent added by hand) and the page names the setup it measured instead of measuring each.
    The heap applies to the servers only; the builds and the test JVMs run with their own defaults.
  - **A trial round first.** While the harness for these measurements is built, trial runs on the current Lathe build test it and find what is broken, on both servers and every project; their results are never reported, and the round that is reported runs on the release, as below.
  - **Gathered in sessions, one at a time.** The measurements need about 18 hours of an idle machine, so they are gathered in sessions, each one project and one group of measurements (the queries; the editing loop; the outside changes; diagnostics against the build), in the order of the design's stage 1b.
    Within a session both servers are measured, alternating, as before; sessions may fall on different days, and each published value names its session (date, machine load, versions).
    The new round measures the first Lathe release after 0.3.1 (0.4.0), as the Servers table plans, and the newest jdtls release when the round starts (1.63.0 on 2026-11-11), each recorded by version before the round's first session, with the projects' commits as pinned; all three stay the same across its sessions, and a session on another version starts the round again.
  - **java-index leaves the comparison.** It answers navigation only and ran on spring-petclinic only; spring-petclinic keeps java-index's 17 queries, so its numbers still compare with java-index's published ones.
    The outside changes (rename with its compile, the branch switch, the build-file change) run in sessions of their own, three times per server and project, not inside every cold and warm run.
  - **Apache Fineract leaves the comparison.** jdtls does not import it at the pinned commit (the 2026-11-07 amendment), but half the cause is Fineract's own build: its import creates every Gradle task, and Fineract's `org.barfuin.gradle.taskinfo` 3.0.2 plugin fails when its `tiOrder` task is created on Gradle 9, a task a normal build never creates; jdtls's annotation-processing init script also fails on Gradle 9.
    A user can work around the first, so "jdtls does not import it" would say more about this commit than about jdtls; its earlier results are kept, and published nowhere.
  - **The projects, all Java.** spring-petclinic, Dropwizard, Helidon, Hibernate ORM and OpenMetadata; projects that mix in Kotlin or Groovy were left out (Micronaut Core and Spring Framework were considered), as neither server analyses those.
    A Gradle build of spring-petclinic was considered and left out: for a project of that size the build changes only the setup, which the build rows already show, and Hibernate ORM is the Gradle case.
    Helidon is the annotation-processing project, named so on the page: its processors generate the builders the rest of its code calls, so an editor without their output reports errors the build does not; Hibernate ORM runs processors too (JBoss Logging's, its own metamodel's).
  - **OpenJDK** is reported as a project Lathe serves and jdtls does not, with how jdtls was set up: on a plain checkout of `openjdk/jdk` (2026-11-11, jdtls 1.63, vscode-java's settings, `-Xmx2G`), jdtls treats its sources as loose files, reports `java.util` in `ArrayList.java` as conflicting with `java.base`, and finds no definition or reference from there or from `jdk.compiler`; Lathe serves it through its OpenJDK `make` integration.
    An observation, not a measured project: its pinned commit and Lathe's numbers are added before any measured run of it.
  - **The editing loop and outside changes**, measured on every project both servers import, with positions fixed in each project's case file before its first measured run, by the rules below.
    Latency is reported as for the queries: the first answer, then median and 95th percentile over the repeats.
    1. **Completion**, four positions in the main module's file of the definition query: member access after `.` on a project-type receiver; the first three letters of a project type the file does not import; after `.` on a JDK class's static member access (`Math.`, `List.`); on a lambda parameter inside a lambda body.
       Each expects a named item within the first 20, in the order Neovim shows them (each item's `sortText`, else its label); the time counts the request and the resolve of that item, which jdtls defers.
       For the unimported type, the item must also add the import.
    2. **Diagnostics while typing**: in that file, `int latheBench = "x";` inserted as a method's first statement; the time from the change to the first publish for that document version (or, from a server that sends no version, the first publish after the change) with an error on that line; then from the undo until the diagnostics settle without it (the last publish before none arrives for 2 s, which must not have the error: a server that clears its diagnostics on every change shows the error gone at once, whether or not the edit fixed it); then the same with a save after each change, the file written to disk before `didSave`, as an editor saves it; each waits at most 60 s.
    3. **An error in another module**: the widely used method of the queries gains a parameter, in the editor and on disk, saved; the time until an open file of another module that calls it shows the error at the call, then, after the revert, until the error is gone.
    4. **Diagnostics against the build**: the build's own javac errors and warnings per file, from a clean build and from one with the same type error injected into each file a query of the project points at, against each server's diagnostics on those files, both ways: errors the build reports that a server misses, and errors a server reports that the build does not.
       Errors are compared strictly; warnings only in the categories the build enables, as jdtls has its own lint set.
       Targeted public fixtures (Lombok, annotation processors, `--release` below the JDK, JPMS, the Gradle source sets of the design) are named before their first run.
    5. **Rename and imports**: renaming the widely used method, with the time and the edits (files and edits apart, as jdtls also renames accessors and files); the edit applied to a copy of the project, whose main and test code must then compile with the build.
       Removing an import a file uses: the time until the error, then until a code action to import it is offered, resolved and applied.
    6. **A branch switch**:
       `git checkout` of a fixed older commit per project (named in `projects.py` before its first run), build files included, while the server runs; the client then does what Neovim does on the machine that runs the benchmark: it reloads the open buffers from disk, and on Linux sends no `workspace/didChangeWatchedFiles`, as Neovim (0.12) does not offer it there, so each server finds the changes itself.
       On macOS and Windows, where Neovim does offer it, the client sends it for the changed files the server registered for.
       The time until the definition query is right, until the widely used method's reference count is the one the same server gives after a fresh start at that commit, and until a changed open file's diagnostics match the build's at that commit; a deadline of 10 minutes.
       A server that never gets there is reported so, and measured again after what its user would do: jdtls with its workspace data wiped, Lathe after a sync.
    7. **A build-file change**: a dependency already in the local repository added to the main module's build file, and a use of one of its classes in an open file; the time until that file has no error, each step a user takes counted and timed apart: for Lathe the sync its prompt offers, for jdtls the update its prompt offers (`java.configuration.updateBuildConfiguration`, whose default is to be confirmed in its source before the first run), each answered as a user clicking "yes".
    8. **Running one test from the editor**, what Lathe's test capture is for: a test class and one of its methods in the main module (the test class with the most test methods there), each run as the Neovim client of each server runs it.
       For Lathe, `lathe.run.test`, as `lathe.nvim`'s test adapter calls it, replaying the launch the build captured.
       For jdtls, nvim-jdtls's `test_class` and `test_nearest_method` path: the java-test bundle resolves the launch, and java-debug starts it, both bundles at pinned versions loaded into jdtls as `bundles`.
       The time from the request to the last result, and whether each test's outcome matches the build's own run of it; the first run and the median of the repeats, with the method's assertion edited between repeats to pass and to fail in turn, so each run must see the edit and its outcome is known in advance.
       What each needs installed and configured goes into the Neovim setup below.
    9. **Disk and the project tree**: after the warm run, the size of jdtls's workspace data and configuration, and of the project's `.lathe/`; the servers' shared installs apart.
       Also every file each server writes into the project's tree outside the build's output, as `git status --ignored` shows them: Lathe's `.lathe/`, and any Eclipse metadata of jdtls's (`.project`, `.classpath`, `.settings/`), which vscode-java's `java.import.generatesMetadataFilesAtProjectRoot` keeps in the workspace data by default.
    10. **Setup in Neovim**: what a user installs and configures for each server, test running included (for jdtls the two bundles, for Lathe the test capture), in prose, not measured.
- **2026-11-19, before any run of the round it changes (no measured round has run since the 2026-11-11 amendment; only trial runs):**
  - **Five headline items.** The page leads with five items, fixed here, which replace the design's ten headline values:
    1. a fresh checkout to the first right answer, with the warm ready time beside it (the build and ready rows);
    2. peak and idle memory (the memory rows);
    3. the errors against the build (measurement 4 of the 2026-11-11 amendment, the clean build and the injected errors) and the time from typing an error until it shows and until it is gone (measurement 2);
    4. a branch switch and a build-file change (measurements 6 and 7), where a server that needs its workspace wiped or a restart to be right fails the item, with what it took;
    5. feature parity: per feature, supported or not, and on the fixed queries whether the two servers agree location by location, a disagreement settled by compiling with the build; the [Queries](#queries), completion and rename and the import fix (measurements 1 and 5), and formatting, folding and running and debugging a main method checked once per project.
    They were chosen where the architecture says Lathe should be stronger, and the page says so; every other value stays in the full report, the losses in "where jdtls is better".
  - **Running a test leaves the round.** Measurement 8 is not built, and few users of either server run tests from Neovim; the page states that Lathe replays the build's own launch as a fact, without a number.
  - **The projects** are spring-petclinic, Dropwizard and Helidon; Hibernate ORM and OpenMetadata keep their pinned queries but are not measured in this round.
  - **Correctness once, timings repeated.** What is right or wrong (feature parity, errors against the build, whether a change ends right) is checked once, as it does not vary between runs; only timings are repeated, replacing the 3 cold and 3 warm runs:
    2 cold runs (the build and the first start, jdtls's first start being its import, after its `mvn install` on Helidon), each giving the time to a first answer with its parts shown; 4 warm runs, giving the reopening time, the first of them the parity run and the other 3 peak and idle memory (the parity run also debugs a main method, whose JVM counts as the server's); a typed error 10 times; the branch switch and the build-file change 3 times each, with a 5-minute deadline (was 10).
    The queries run once per server and project, for their answers, not repeated in every run.
    Every timed value is the median with its minimum and maximum.
    A run starts only once the 1-minute load average is under half the core count (was: discarded above the core count, before or after it), waiting up to 10 minutes; the load after a run is recorded, not judged, as the run's own build, import and indexing raise it.
  - **Sessions of at most 45 minutes**, each one project and one or more whole groups (start and parity; errors and typing; outside changes), so the round fits around a working day; a group longer than that runs one server per session; an interrupted session is discarded and run again, never resumed.
  - **Setup is counted**, not described (measurement 10): the lines of Neovim configuration, the files downloaded and the commands run before each server answers its first query, from the minimal configs in `setup/` by the rule in `setup/README.md`.
  - **What the code fixes**, before the round's first session:
    - a branch switch's older commit is the 50th back along the main line, or the nearest one further back whose diff adds or deletes a main-code type (`package-info.java` and `module-info.java` declare none), so the switch changes what search finds: Dropwizard's (`e357826376`) and Helidon's (`c2fe76b86e`) are the 50th, spring-petclinic's is `b21890c126`, 65 back, as its 50th touches no type (found by the trial session);
    - spring-petclinic's build-file change adds AssertJ, which the Spring Boot BOM manages and the test starters put in the local repository, used in `PetClinicApplication`;
    - jdtls never runs on Lathe's checkout: an errors or changes session's runs are warm, so when Lathe's runs left the clone changed (the extension, a replaced file, `.lathe/`), jdtls's run starts from a fresh checkout of the pinned commit with its own state kept, and Lathe's next run builds again;
    - a run the harness never started (the machine stayed busy, the clone at another commit, another Lathe version) is no answer of the server's: the headline leaves it out instead of counting it "never";
    - a build-file change whose user step fails (Lathe's sync script exits non-zero) ends there: "never", with the step's exit code and the errors left, not a wait to the deadline for an error that can no longer clear;
    - the branch switch's fresh start at the older commit, which gives the right answers, gets the switch's 5-minute deadline to be ready, not the ready timeout; a fresh start that never answers right fails the run with that reason, once per server and session;
    - the main method each project debugs (`debug_main` in `projects.py`): spring-petclinic's `PetClinicApplication`, Dropwizard's `HelloWorldApplication`, Helidon's `ImperativeMain` (the webserver's own main is in its tests' application module), the breakpoint on its first statement;
    - jdtls loads the java-debug bundle (`vscode-java-debug` 0.60.0, plugin 0.53.2) in every run, as a user who debugs has it in their config, and as the Extension Pack for Java gives it to vscode-java;
    - the injected error of measurement 4 is `static final int LATHE_BENCH = "x";` after the opening line of the file's first top-level class, interface or record, where each accepts it; a file whose first top-level type is an enum or annotation type is left out; the build runs offline with no Lathe and the project's checks off, in passes, and Helidon's builds its whole reactor, as its compiler plugin takes the reactor's own processors;
    - formatting counts as supported when the server answers the request on the definition query's file, edits or none; folding when it gives ranges; debugging when the main method stops at the breakpoint.
  - **Which Lathe a trial runs: a snapshot of `main`, never the local `0.1.0-SNAPSHOT`.** A trial session before the release runs `main` built from a clean worktree and stamped with its commit, as the Servers table's pinned build is: `mvn versions:set -DnewVersion=0.3.99-<commit>`, then `mvn install -DskipTests` (`setup.sh`'s header), and `BENCH_LATHE_VERSION=0.3.99-<commit>` for the session.
    The `0.1.0-SNAPSHOT` in a developer's `~/.m2` mixes jars of several commits, so a result taken on it names no build.
    Every session records the version in `environment.json`, and its report and the headline print it, so a finding names the commit it was seen on.
    The 2026-11-19 trial ran on `0.3.99-0e2144a5` (`main` at `0e2144a5`); its first sessions, on the published 0.3.1, tested only the harness, and their findings about Lathe are not taken as `main`'s.
    The reported round runs on the release.
  - **WS-40 on spring-petclinic, and its workaround.** Found by the 2026-11-19 trial session: from the second build on, petclinic's nohttp check fails, so Lathe's sync after the build-file change fails and the file never gets the new dependency.
    The check is the `nohttp-checkstyle-validation` execution of `maven-checkstyle-plugin`, which scans `${basedir}` in `validate`, before the sync can run; it finds `http://` URLs in `.lathe/classes/META-INF/sbom/application.cdx.json`, the SBOM Lathe's class mirror copies.
    The first build passes only because `.lathe/` does not exist yet.
    The workaround is the user guide's ([troubleshooting.md](../../docs/guide/troubleshooting.md)): exclude `.lathe/` from that execution, as it already excludes `target/`:

    ```xml
    <excludes>**/.git/**/*,**/.idea/**/*,**/target/**/,**/.flattened-pom.xml,**/*.class,**/.lathe/**/*</excludes>
    ```

    Lathe's checkout of the project takes it (`lathe_replacements` in `projects.py`, applied with the Lathe extension by `lathe_edits`, again after every fresh checkout and branch switch); jdtls's checkout is left as it is, and the edit is not counted among the files a server writes into the tree.
    It is one more step of Lathe's setup on that project, which its setup row and the page name.
    Without it, Lathe's "right again after a dependency is added" on spring-petclinic is "never", and every sync after the first fails the same way.
    When WS-40 is fixed, the replacement goes, and the round runs again on the release that fixes it.
- **2026-11-20, before any measured run of the round, and before the trial round:**
  - **Item 5 lists what Lathe lacks, not only what both servers do.**
    Its rows were the features both servers offer, plus inlay hints and member symbol search, so its count read as near parity.
    It gains a row for each feature jdtls has and Lathe lacks, one row per kind: code generation (getters and setters, `toString`, `equals` and `hashCode`, a constructor from fields, delegate methods); inline (a variable, a method, a constant), move, change signature and extract interface; selection range; code lens (reference counts and run and debug actions); opening a dependency's class that has no sources jar.
    Each is checked once per server and project, as formatting and folding are: the request or code action of that kind at a position fixed in the project's case file before the trial round, offered, and its edit applied to a copy that then compiles where it is an edit.
    Each is confirmed on the pinned jdtls before the round; a row the pinned jdtls does not support either stays, marked so.
  - **Item 5 lists the refactorings and quick fixes Lathe offers, one row each**, which the earlier rows left out but for rename and the import fix: extract a variable, a constant, a field and a method (each also replacing every occurrence); convert a local to `var` and back; surround with try/catch and with try-with-resources; add `throws`; implement missing methods; add a constructor parameter from a field; checked the same way, their edits compiled.
  - **The headline counts features supported, of all compared**, for each server, instead of the features with the same answers; whether the shared features' answers agree, location by location, stays on the page.
  - **Formatting is a row of its own, not a shared feature, and is expected to read unsupported for Lathe on all three projects**, which is stated with the reason, not left to be found: Lathe formats only with the formatter a project declares (Spotless, or a `lathe-style.json`); spring-petclinic's is spring-javaformat, which Lathe does not run (EG-113), and Dropwizard and Helidon declare none.
    jdtls formats any file with Eclipse's formatter.
  - **The page says what Lathe does not do**, in a section of its own, written from the gap registry as it stands when the round runs, each line with its gap where one is filed and "no gap filed" or "by design" where none is: the features above; smaller gaps in shared features (folding of comments that are not Javadoc, and of a `package-info.java`'s Javadoc; completion in a Javadoc `{@link}`; a deprecated API's replacement; assignment and array creation while debugging); and limits of the design or its reach (a build before the first answer, where jdtls opens any folder; Neovim on Linux and macOS only; Gradle included builds and Java compiled jointly with Groovy; slow references, implementations and incoming calls on very large codebases, with no answer cached).
    Stated, not measured, apart from the rows item 5 checks.
  - **The report's layout is fixed now**, before any result: `report.py --headline` generates it whole, so no value is copied by hand and no layout is chosen after the numbers are seen.
    Feature parity shows one row per area, the shared features as a count and every difference by name; every feature's own row, with its latency, follows it, collapsed; then what Lathe does not do and where jdtls is better.
- **2026-11-21, after the trial session on spring-petclinic, before any measured run:**
  - **Idle memory is sampled after 60 seconds without a request**, in the start group's memory runs only; it was sampled 5 seconds after the last query of every run.
    The trial read the same value as idle and as peak for both servers: 5 seconds after its last answer a JVM still holds its peak, so the value said nothing about what a server keeps while the user reads code.
    Neither server is told it is idle: Lathe gives memory back only when its scan pauses, which only its MCP engine sets (after 5 minutes without a request), and jdtls runs with vscode-java's heap-shrinking collector settings; the 60 seconds measure what each does on its own.
    It adds about 6 minutes per project to the start group (3 memory runs per server).
