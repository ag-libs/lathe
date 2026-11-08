# Lathe — Gradle Source Sets Beyond `main` and `test`

## Status

**Implemented.** Step 1 (2026-11-07): every Java source set a tree of its own, with editor features.
Step 2 (2026-11-08): each source set's test task captures that tree's launch, and its tests and main methods run and debug from it (§4.9).

## 1. Problem

The Gradle plugin captures two source sets per project, `main` (tree `classes`) and `test` (tree `test-classes`).
Every other source set is invisible to Lathe:
its files get no module configuration, so the server never compiles them —
no diagnostics, completion, hover, navigation or rename inside them,
references from other code miss them, and their tests can't be run.
Only formatting works there, as it falls back to the root's formatter.

Such source sets are common. In the five public Gradle builds Lathe is checked on:

| Kind | Examples |
|---|---|
| Test fixtures (`java-test-fixtures`), consumed by other projects | Caffeine, Micronaut, junit-framework |
| Extra test suites (`jvm-test-suite` or a hand-made `Test` task) | Caffeine `moduleTest`, `osgiTest`, `lincheckTest`, `jsr166Test`; Micronaut `testWithNetty`; junit-framework `graalVmTest`; Hibernate ORM `integrationTest`, `legacyTest` |
| Benchmarks | `jmh` in all five |
| Other | Hibernate ORM `jakartaData`, `providerFixture`; Micronaut `cremaRuntime`; Caffeine `codeGen` |

Until 2026-11-07 a capture-only sync even ran those suites' tests in full, for nothing ([TE-16](../gaps/gaps-archive.md));
the initial fix skipped every test task other than `test`.
Step 2 superseded that restriction: capture-only syncs now capture each source set's test-task launch (§4.9).

## 2. Background

### 2.1 What Lathe has today

A module's `.lathe/<module>/` holds one *tree* per compile:
`lsp-params-<tree>.json` (the compile's source roots, class path, processor path and options),
`lsp-stamps-<tree>.json`, the mirror `<tree>/` the server compiles against, and a generated-sources mirror.
Much of this is already generic in the tree name (repo sweep, 2026-11-07):

- the server loads every `lsp-params-*.json` in a module folder (`WorkspaceModuleRegistry`), and finds a file's tree from the source roots;
- build outputs on a class path are remapped to mirrors through the front-end's `outputs` map (`ReactorRewrite`), by path, not by name;
- the order in which trees are written follows their class paths (`WorkspaceModuleGraph.levels`/`dependsOn`, tree-level);
- compile stamps, params file names and mirror folders take any name;
- the Gradle plugin's `registerCapture` is already written per source set, and called twice.

What assumes exactly two trees (§5 lists each place):
`OutputData` rejects other names; the generated-sources mirror of every non-test tree is main's;
"is this test code" is decided by comparing the tree name with `test-classes`;
the Gradle plugin registers, lists and prunes only the two;
the run gate and main-class routing know only the two.

### 2.2 How jdtls does it

jdtls embeds Buildship, which asks Gradle, through the Tooling API, for the Eclipse model built by Gradle's own `eclipse` plugin (`EclipsePlugin`, `gradle-ide-plugins`).
That model reads the project's `SourceSetContainer`, so it sees every source set.
It then folds them:

- one Eclipse project per Gradle project; every source set's folders become source folders of it, each with its own output folder (`bin/<sourceSet>`);
- each folder is marked test or not by `eclipse.classpath.testSourceSets`, whose convention is: every JVM test suite's source set, `testFixtures`, and every source set whose lowercased name contains `test` (checked in the 9.8.1 bytecode); libraries only test configurations bring are marked test-only by the same rule on configuration names;
- the compiler (ECJ) then enforces two levels per project: main code can't see test code or test-only libraries. All test folders share one class path, and all main folders another.

So `integrationTest` code can use `test`'s classes, `main` code can use `jmh` classes and libraries (`jmh` has no "test" in its name), different versions of one library across source sets collapse into one, and a dependency on another project (including `testFixtures(project(":x"))`) is a reference to that whole project.
Each of those compiles in the editor and fails in the build.

### 2.3 Why Maven needs nothing

Maven has exactly two compiles per module.
A root added by `build-helper-maven-plugin`, or generated sources, joins the main or the test compile —
the same javac run, class path and output — and Lathe's compiler shim records the compile's actual roots, so it is already covered.
The nearest Maven counterpart of an extra suite, Failsafe, runs against the test compile's classes.

## 3. Goals and non-goals

**Goals.**
Every Java source set of a Gradle project gets the editor features `main` and `test` have,
with the class path its own compile has, so the editor reports what the build reports.
`testFixtures` consumed by another project resolves to the fixtures, not the whole test code.

**Non-goals for step 1.**
Running and debugging tests of the new trees were deferred to step 2 ([TE-12](../gaps/gaps-archive.md#te-12--on-gradle-only-each-projects-test-task-is-captured--done)), now implemented (§4.9).
Source sets with no Java sources (Kotlin- or Groovy-only) stay uncaptured, as their files are today.
Creating a type in a new tree from the create-type dialog (it offers main and test).
Maven, OpenJDK: unchanged apart from writing the test flag (§4.2).

## 4. Design

### 4.1 Options

**A′ — fold, as jdtls does.**
Two trees as today, split by Gradle's test convention:
`classes` = `main` plus the source sets that are not test code by that convention (`jmh`, `cremaRuntime`, and Hibernate ORM's `jakartaData`, which has no "test" in its name),
`test-classes` = `test` plus the test-like ones (`integrationTest`, `testFixtures`).
Each tree merges its source sets' roots, class paths, processor paths and outputs.
Gradle plugin only; no server change.
It carries every limit of §2.2, and two of its own: folding `jmh` into `classes` puts it on other modules' view of the project (their class paths map to `classes`),
and a merged tree loses which source set a file belongs to, which running their tests later needs.

**B — one tree per source set.**
Each source set is its own tree, with its own class path, output and generated sources;
visibility between trees follows class paths, which the server already reads.
Gradle plugin, `lathe-core`, about six places in the server and install code.

**Direction: B**, in two steps — editor features (§4.2–4.8), then runs (§4.9).
A′ trades about half the work for diagnostics that disagree with the build in the cases of §2.2, which is the property Lathe is built on;
and it would have to become B for runs anyway.

### 4.2 Trees and their names

| Source set | Tree | Generated sources |
|---|---|---|
| `main` | `classes` | `generated-sources` |
| `test` | `test-classes` | `generated-test-sources` |
| any other, `<name>` | `classes-<name>` | `generated-<name>-sources` |

The prefix keeps a tree folder from colliding with a child project's folder: module folders nest by project path, so a bare `jmh/` could meet a subproject `lib/jmh`.
Maven, OpenJDK and existing workspaces keep their names.
`LatheLayout` gains `sourceSetTree(name)` and `generatedSourcesDir(tree)` covers the new names.

### 4.3 Test code is data, not a name

`lsp-params` gains `testCode` (boolean), written by every front-end:
Maven — `classes` false, `test-classes` true; OpenJDK — false;
Gradle — Gradle's own convention (§2.2): true for `test`, every JVM test suite's source set, `testFixtures`, and a source set whose lowercased name contains `test`; false otherwise (`main`, `jmh`, `cremaRuntime`).
Using Gradle's rule keeps Lathe consistent with what Gradle tells the IDEs.

`ModuleSourceConfig.isTestTree()` reads the flag.
Its users keep their meaning, now right for every tree:
go-to-test (`TestCounterparts`), the static-import split of the candidate index (`ReferenceCandidateIndex`, `FeatureRouter`),
the change checks (`ChangeVerifier`), the scope a tree reports (`SourceLayout`, `SourceScope`).

### 4.4 Visibility follows class paths

The flag decides UX only.
What a tree can see is what its class path holds, as in the build:

- `integrationTest` with main's output on its class path sees `main` and not `test`;
- Hibernate ORM's `jakartaData`, with main's and test's output, sees both, though by Gradle's convention it is not test code (no "test" in its name): the flag and visibility are independent;
- a project consuming `testFixtures(project(":x"))` sees `x`'s fixtures tree and nothing else of `x`'s test code.

One place decides visibility from the name today and changes:
`RenameService.visibleEdits` assumes "a test tree's files are seen by nothing else", which `testFixtures` breaks.
It becomes "a tree's edits are visible to the trees whose class or module path holds its mirror",
the tree-level relation `WorkspaceModuleGraph.dependsOn` already computes for write ordering.
Reference search keeps its module-level scope (`referenceSearchScope`, every tree of the declaring and downstream modules): searching more files is safe, as javac decides each match.

### 4.5 Outputs

`OutputData` accepts any tree name.
For every captured source set the facts list its classes folder and resources folder as outputs of its tree,
and its jar when it has one: `SourceSet.getJarTaskName()` names it (`jar` for main, `testFixturesJar` for fixtures), and a source set without a registered jar task has none.
Another project's class path holding `x-test-fixtures.jar` or `x/build/classes/java/testFixtures` then maps to `.lathe/x/classes-testFixtures`.

### 4.6 The Gradle plugin

- A capture task per Java source set, `latheCapture<Name>` (`latheCaptureMain` and `latheCaptureTest` keep their names), each finalizing its compile, as today.
  A source set without sources is skipped by the task (`@SkipWhenEmpty`), so it gets no tree.
- `LatheFactsTask` takes a tree → sources map instead of `mainSources`/`testSources`, for the trees list and the staleness check; outputs, resource roots and access flags (`javaFacts`) come from every source set.
- Libraries become the union of every source set's compile class path, so `jmh`'s and `integrationTest`'s own libraries are listed and get `-sources` jars.
- A sync compiles every source set: the facts depend on the extra captures when the build asked for `latheSync`.
  Not otherwise: the facts finalize the test compile, so a plain test build would compile every source set (and Gradle cannot schedule a finalizer that depends on tasks nothing requested); a source set's capture still runs whenever its compile does.
  Hibernate ORM's sync already did, through the test tasks its `test` depends on; on Caffeine it adds `jmh` and the suites.
- Each capture runs after its own compile and before its source set's `classes` task, as the shared generated-sources fix (WS-32) set up for `main` and `test`.
  That fix also runs the test compile after the main capture, for a build pointing both compiles at one generated-sources folder; a third source set doing the same is not covered (§9).
- `main-launch.json` stays the main source set's.

### 4.7 Pruning

`LatheWorkspaceTask` prunes the trees a project's facts no longer list, which already covers new trees.
Its fallback for a project not built since it joined the build keeps only `classes` and `test-classes`; it keeps the trees already on disk instead.
`CapturePruner` deletes a pruned tree's own generated-sources mirror (§4.2), no longer main's.

### 4.8 File ownership

A file belongs to the tree whose source roots hold it.
Two source sets can share folders (a JVM test suite pointed at `src/test/java`): the server loads a module's trees in a fixed order, main, test, then the rest by name, and the first owns the file, so `test` keeps a folder a suite shares with it.
Generated files belong to the tree whose generated-sources mirror holds them, now one per tree (`WorkspaceModuleRegistry.moduleSourceFor` stops matching main's mirror for every tree).

### 4.9 Runs

Step 1 refused a run of a test or main method only a source set's tree holds, rather than send it to the module's `test` or main launch, where the class is missing or the classpath is wrong.

Step 2 gives each tree a launch of its own, `test-launch-<tree>.json` (`test` keeping `test-launch.json`).
Build scripts register their test tasks after the plugin applies, so once a script has run, each `Test` task is matched to the source set whose classes it runs (`testClassesDirs` against the source set's class folders):
a match loads `lathe-junit` as `test` does, with its tree named in the task's environment (`LATHE_CAPTURE_TREE`, no task input, so cache keys stay the build's), so every run of it refreshes the tree's launch, dry in a capture-only build (`./gradlew integrationTest -Plathe.capture.only=true`).
The editor's sync does not pull these tasks in, as their dependencies (a database they start, say) would run on every sync; a build whose `test` depends on them (Hibernate ORM) runs them dry in its sync.
A task of no captured source set (a second task on `test`'s classes) stays skipped by a capture-only build.
`lathe-junit` writes a named tree's launch only when the module has that tree, so a name a nested build inherits from its parent's environment captures nothing, and `test` names its own tree.
Two tasks of one source set both capture, and the last to run wins.

The server reads the launch of the tree a run's classes are in (`SourceSetScope.treeOf`): a class or method run, and a `main` in a source set's tree, replay from it; a run of classes from two trees (two source sets, or `test` and one) is refused; before the task has captured, the run names the source set and how its test task captures it.
Package, directory and module runs, and test discovery, stay with the `test` tree.
Replay uses the test runner and JUnit launcher resolved from `test`'s classpath.

Checked on Hibernate ORM: a capture-only sync captures `jakartaData`, `integrationTest`, `dataIntegrationTest` and `legacyTest`, each dry, and a test of `integrationTest` run through the server passes on its own launch.

## 5. Changes by module (step 1)

**lathe-core** — `LatheLayout.sourceSetTree`, `generatedSourcesDir` for any tree; `OutputData` accepts any tree; `ModuleConfigData.testCode`.

**lathe-install** — `CompileCapture`, `CapturePruner`: the tree's own generated-sources mirror.

**lathe-gradle-plugin** — `LatheProjectPlugin` (a capture per source set, `javaFacts` and libraries over every source set, the test convention), `LatheFactsTask` (trees from a map), `LatheWorkspaceTask` (the pruning fallback), `LatheCaptureTask` (writes `testCode`).

**lathe-compiler** (Maven shim), **lathe-openjdk-maven-plugin** — write `testCode`.

**lathe-server** — `ModuleSourceConfig.isTestTree` from the flag and `generatedSourcesDir` per tree; `WorkspaceModuleRegistry.moduleSourceFor`; `RenameService.visibleEdits` by class path; `ModuleSourceCompiler` processor output per tree; `MainClassScope`, `CompletenessGate`, `RunCoordinator` (refusal, any tree name).

**docs** — the [workspace contract](../lathe-workspace-contract.md) (tree names, `testCode`), the [Gradle guide](../guide/gradle.md), status, and a gap entry.

## 6. Plan

1. **Spike** on Hibernate ORM (`tooling/metamodel-generator`: `jakartaData`, `integrationTest`, `dataIntegrationTest`; `hibernate-ant`: `legacyTest`) and Caffeine (`testFixtures` consumed across projects, `jmh`, the suites, `codeGen`), with the plugin change alone and the server as is, to see what the server already does with extra trees and settle §9.
2. **Step 1**, one PR, a commit per layer: core and install, the Gradle plugin, the server, docs.
3. **Step 2** (TE-12), completed in PR #131; the implemented design is in §4.9.

## 7. Verification

TestKit: a project with `integrationTest` (main's output and a library of its own), `testFixtures` consumed by a second project, and `jmh`, checking each tree's params, `testCode`, outputs and mirrors; a build with only `main` and `test` producing exactly today's files.
Server tests: `isTestTree` from the flag, visibility for rename through a fixtures tree, a run in a new tree refused with its reason.
On the real builds: sync, then open a file from each new source set through `dev/explore.py` or MCP (diagnostics, definition, references from `main`).

**Correctness against the build, and against jdtls.**
Small Gradle fixtures, each a case where the build fails to compile:
`integrationTest` code using a class from `test`; `main` code using a `jmh` class or a library only `jmh` has;
a consumer of `testFixtures(project(":x"))` using one of `x`'s ordinary test classes;
two source sets built against different versions of one library, each using an API only its version has.
Lathe must report what Gradle reports in each.
The same fixtures go into the [jdtls comparison](lathe-jdtls-comparison.md); by §2.2 jdtls is expected to report none of them,
but that is stated only after it is run there, under that plan's rule.

## 8. Spike results (2026-11-07)

A throwaway plugin capturing every source set as `classes-<name>`, `OutputData` accepting any tree and a generated-sources mirror per tree; the server unchanged otherwise (no `testCode` flag, no visibility change).
Run with `dev/explore.py` against a server stamped `0.3.99-spike`.

**What already works.**
- Hibernate ORM: 11 extra source sets captured (`jakartaData`, `integrationTest`, `dataIntegrationTest`, `legacyTest`, `jmh`, `providerFixture`, …).
  A `jakartaData` file compiles with no diagnostics, and definition and hover on `CompilationTest` (from `test`) land in `src/test/java`.
  An `integrationTest` file compiles; definition on `_BookDao`, generated by Hibernate's processor in that source set, lands in `generated-integrationTest-sources`.
- **Exact class paths.** A reference to `test`'s `TestUtil` added to an `integrationTest` file is reported (`package org.hibernate.processor.test.util does not exist`), as Gradle's compile reports it; the same package resolves from `jakartaData`, whose class path holds `test`'s output.
- References to `CompilationTest` search `test` and `jakartaData` (133 candidate files) and find the uses in both.
- Caffeine: 16 extra source sets in its core project (`jmh`, `jcstress`, `codeGen`, `javaPoet`, `testFixtures`, a dozen suites).
  Definition on a fixtures class from `fuzzTest` lands in `src/testFixtures/java`; the fixtures folder and `-test-fixtures.jar` are outputs of `classes-testFixtures`.

**Cost.**
- Sync: Hibernate ORM 29 s → 50 s (it compiles the extra source sets); Caffeine 94 s.
- References to `Caffeine.newBuilder`, same workspace with and without the extra params files: 27 → 97 files searched, about 288 → 603 references found (today's server misses the uses in benchmarks and suites), time within noise (15–23 s both, server start included), peak server memory 1.22 GB → 1.63–1.82 GB.

**Found.**
- Source sets are declared after the `java` plugin applies, so the plugin must react to them as they are added (`sourceSets.matching(...).configureEach`) and read the list lazily, not list them when it registers.
- **Duplicate class names across trees.** `integrationTest` and `dataIntegrationTest` both declare `org.hibernate.processor.test.integ.model.Book`.
  javac compiles each against its own, but definition from `integrationTest` lands in `dataIntegrationTest`'s `Book.java`: mapping a class back to its source does not prefer the tree on the asking file's class path. Step 1 must fix it.
- Adding a repository to every project in an init script hides a build's settings-level repositories (Hibernate ORM resolves Spotless's formatter from them); only matters for local testing, not for the plugin.

**Not yet checked.** A project consuming another project's `testFixtures` (Caffeine has none; junit-framework or Micronaut), the UX decided by `testCode`, and refused runs.

## 9. Open questions

- ~~Memory: is the +400–600 MB peak of a wide reference search held afterwards?~~ No, it was garbage. Measured on Caffeine (2026-11-08), heap in use after a full GC, 18 trees against 2: 262 MB against 168 MB right after a references search that finds 603 references instead of 288, and 170 MB against 128 MB after 5½ idle minutes.

- ~~Does `jakartaData`, whose class path holds main's and test's output, remap both and compile cleanly?~~ Yes (§8).
- `codeGen`-style source sets whose program generates another source set's sources: captured as a tree like any other, or skipped? (spike)
- Is the extra sync compile acceptable on Caffeine and Micronaut, or should a project be able to leave a source set out (`lathe { skipSourceSets = [...] }`)? Measure first.
- ~~Overlapping source folders: should `test` win over a suite sharing its folders?~~ Yes: main, test, then the rest by name (§4.8).
- A third source set compiling into another's generated-sources folder (as Apache Fineract points `test` at main's): order it like the test compile, or leave it until a build does it?
