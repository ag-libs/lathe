# Lathe and jdtls — LSP Benchmark Pre-Registration

**Status: PRE-REGISTRATION, drafted 2026-11-06, revised 2026-11-07; no benchmark run yet.**
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
| java-index | 0.1.2, JVM relay | Corretto 21 | its own; spring-petclinic only |

Each server runs as its users get it, except for the heap: from the 2026-11-11 amendment every run gives both servers `-Xmx2G`, the limit vscode-java gives jdtls, and another heap is a labelled variant (`--heap`).
Before it, one extra run per project gave Lathe that limit, and the others ran Lathe with no limit.

## Projects

| Project | Build | Commit | What it represents |
|---|---|---|---|
| spring-petclinic | Maven | `500158f7` | small, single module; the commit java-index's harness pins, so its published numbers compare |
| Dropwizard | Maven | `46bb4ed819` | a large reactor |
| Helidon | Maven | `2f6df16425` | a large reactor, modular (JPMS), annotation processors (its builders) |
| Hibernate ORM | Gradle | `37616099b7` | a large Gradle build |
| Apache Fineract | Gradle | `d63beed7cc` | a large Gradle build built on Lombok and MapStruct |
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

## What is published

Nothing until stage 4 of the design.
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
    Beside it, the same command with Lathe turned off (Maven `-Dlathe.disabled=true`; Gradle the same tasks without the Lathe plugin), timed once per project on a fresh checkout: the build the project costs anyway, so Lathe's own share is the difference.
    The report also gives Lathe's time to a first answer, its build plus its ready time, beside jdtls's ready time, which includes its import.
  - **jdtls's setup.** jdtls keeps vscode-java's settings.
    A Neovim user starts it through nvim-jdtls with settings of their own, often no heap limit and a Lombok agent added by hand; the published page names the setup measured and does not measure each nvim-jdtls configuration.
  - **The editing loop and outside changes**, measured on every project both servers import, with positions fixed in each project's case file before its first measured run, by the rules below.
    Latency is reported as for the queries: the first answer, then median and 95th percentile over the repeats.
    1. **Completion**, four positions in the main module's file of the definition query: member access after `.` on a project-type receiver; the first three letters of a project type the file does not import; after `.` on a JDK class's static member access (`Math.`, `List.`); on a lambda parameter inside a lambda body.
       Each expects a named item within the first 20; the time counts the request and the resolve of that item, which jdtls defers.
       For the unimported type, the item must also add the import.
    2. **Diagnostics while typing**: in that file, `int latheBench = "x";` inserted as a method's first statement; the time from the change to the first publish, for that document version, with an error on that line; then from the undo to the publish without it; then the same with a save after each change.
    3. **An error in another module**: the widely used method of the queries gains a parameter, in the editor and on disk, saved; the time until an open file of another module that calls it shows the error at the call, then, after the revert, until the error is gone.
    4. **Diagnostics against the build**: the build's own javac errors and warnings per file, from a clean build and from one with an error injected into each of a fixed list of files, against each server's diagnostics on those files, both ways: errors the build reports that a server misses, and errors a server reports that the build does not.
       Errors are compared strictly; warnings only in the categories the build enables, as jdtls has its own lint set.
       Targeted public fixtures (Lombok, annotation processors, `--release` below the JDK, JPMS, the Gradle source sets of the design) are named before their first run.
    5. **Rename and imports**: renaming the widely used method, with the time and the edits (files and edits apart, as jdtls also renames accessors and files); the edit applied to a copy of the project, which must then compile with the build.
       Removing an import a file uses: the time until the error, then until a code action to import it is offered, resolved and applied.
    6. **A branch switch**:
       `git checkout` of a fixed older commit per project (named in `projects.py` before its first run), build files included, while the server runs; the client then does what Neovim does on the machine that runs the benchmark: it reloads the open buffers from disk, and on Linux sends no `workspace/didChangeWatchedFiles`, as Neovim (0.12) does not offer it there, so each server finds the changes itself.
       On macOS and Windows, where Neovim does offer it, the client sends it for the changed files the server registered for.
       The time until the definition query is right, until the widely used method's reference count is that commit's, and until a changed open file's diagnostics match the build's at that commit; a deadline of 10 minutes.
       A server that never gets there is reported so, and measured again after wiping its state.
    7. **A build-file change**: a dependency already in the local repository added to the main module's build file, and a use of one of its classes in an open file; the time until that file has no error, each step a user takes counted and timed apart: for Lathe the sync its prompt offers, for jdtls the update its prompt offers (`java.configuration.updateBuildConfiguration`, by default asking), each answered as a user clicking "yes".
    8. **Disk**: after the warm run, the size of jdtls's workspace data and configuration, and of the project's `.lathe/`; the servers' shared installs apart.
    9. **Setup in Neovim**: what a user installs and configures for each server, in prose, not measured.
