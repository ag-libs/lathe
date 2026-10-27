# Lathe — Plain Java Without a Build Tool

## Status

**Potential; design goal decided 2026-10-28, adjusted 2026-10-29.**
Records a design discussion held on 2026-10-25 to 2026-10-29, a survey of how other Java tools support projects with no build tool, and the alternatives considered.
Nothing here is approved for implementation; the changes listed in [What changes](#what-changes) need a design summary and approval first.
A review on 2026-10-29 for the target audience (students and others with little Maven experience) left [open issues](#open-issues-review-2026-10-29), two of them blockers, for a follow-up design session; decisions they question are marked *under review*.

**The design goal is a hidden Maven project run through a bundled Maven Wrapper** ([Design](#design)):
the client generates a Maven build Lathe already understands, the wrapper fetches Maven on first use, and everything after that is today's Maven path.
Three other designs were worked through and set aside ([Alternatives considered](#alternatives-considered)).

**Decided:**

- **Neovim first, then VS Code**, both with the same bundled templates (2026-10-28).
- **Plain Java: sources and resources in `src/`, no tests** (2026-10-27). Libraries can be added by editing `.lathe-pom.xml` ([Growing the project](#growing-the-project), 2026-10-29); tests mean moving to a real Maven or Gradle project.
- **No JDK or release question** (2026-10-29; JDK lookup *under review*, [issue 4](#4-jdk-only-from-java_home)): the JDK comes from `$LATHE_JAVA_HOME`, else `$JAVA_HOME`, as documented; the release defaults to that JDK's version and is edited in the POM.
  The formatter is the only question.
- **`.lathe-pom.xml` is written once and then belongs to the user** (2026-10-29): re-running `:LatheInitPlain` never rewrites it, only updates the Lathe version property, so the user's edits (release, formatter, dependencies) survive.
- **No build-file detection** (2026-10-29; *under review*, [issues 3 and 8](#3-a-pomxml-in-the-same-folder)): `:LatheInitPlain` does not look for a `pom.xml` or Gradle files; it refuses only a folder whose `.lathe/` already exists without `.lathe-pom.xml`, which some other build set up.
- **Nothing for the user to install but a JDK**, and no visible build in the user's sources (2026-10-27).
- **Hidden Maven project via the Maven Wrapper** (2026-10-28), replacing an earlier CLI-jar design ([alternative A](#a-a-cli-jar-with-an-embedded-resolver)).
- **The command is `:LatheInitPlain`** (2026-10-28; *under review*, [issue 8](#8-one-latheinit-with-a-dialog)); VS Code: **Lathe: Initialize Plain Java Folder**.
  It is the only command that creates `.lathe/` from the editor: Maven projects keep registering the extension in their build, and Gradle will use its plugin.
  The `Plain` suffix tells Maven users it is not for their project, and leaves room for a sibling such as `:LatheInitOpenJdk`.
  `:LatheNew…` and `:LatheStart…` were avoided: both prefixes are taken.

Related designs:
- [Workspace Contract](../lathe-workspace-contract.md): the `.lathe/` files; this design writes them through the existing Maven front-end.
- [New Java Project (VS Code)](../planned/lathe-vscode-new-project.md): already generates a Maven project with the Maven Wrapper for novices; this design reuses that idea for an existing folder.
- [Personal Setup](lathe-personal-setup.md): Lathe on a Maven project without changing it; here there is no project to change.
- [Pinned Formatters](../done/lathe-pinned-formatters.md): the formatter is set up through the Spotless configuration Lathe already reads.
- [OpenJDK support](../done/lathe-openjdk-support.md): the bundled wrapper also removes OpenJDK's "Maven on `PATH`" prerequisite.
- [Lessons from Metals v2](lathe-metals-v2-lessons.md): Lathe trusts the real build; here the build is generated, but real.

## Problem / motivation

Every way into Lathe today goes through Maven:
the extension and compiler shim record the compiler settings during a Maven build,
and `ServerInstaller` resolves the server's jars through Maven's repository session (`lathe-install-maven`).

Three audiences have no Maven installed, or no wish to own a Maven project:

- **Users trying Lathe on plain Java with no build tool**: a `src/` folder of coursework, exercises or small programs.
- **VS Code novices** who want to write Java now, before learning a build tool.
- **OpenJDK developers**, who build with `make` and need Maven on `PATH` only to run `lathe-openjdk-maven-plugin:sync`.

The goal: open a folder of plain Java in Neovim or VS Code, run one command, and get the full Lathe experience, with a JDK as the only prerequisite and nothing Maven-shaped in the user's sources.

## Survey: how other tools do it

Researched 2026-10-25 from the tools' documentation and source.
Every tool falls into one of three approaches.

| Approach | Tools | Strength | Pain users report |
|---|---|---|---|
| **The IDE owns a project model** | Eclipse (`.project`/`.classpath`), IntelliJ (`.iml`, `.idea/`), NetBeans (`nbproject/`) | Full IDE: incremental compile, run, test, shared run configurations | The model is a second build kept in step by hand; NetBeans' free-form project says so outright |
| **Guess from the folder, override with settings** | jdtls / VS Code "invisible projects"; George Fraser's java-language-server | Opening a folder just works | Undocumented heuristics, wrong package roots, new jars unseen until the workspace is wiped, output hidden in workspace storage |
| **A JSON model a build or script writes** | Metals v2 `mbt.json`, Bloop `.bloop/*.json`, Bazel aspect sync, BSP | One contract, many writers | Someone has to write it |

Lathe's `.lathe/` is the third approach already.
This design adds a writer for projects with no build, borrowing what works from the first two.

### Findings

- **The convention is `src/` and `lib/**/*.jar`.**
  It is jdtls's default for `java.project.referencedLibraries`, and VS Code's "Create Java Project → No build tools" writes `src/`, `lib/`, `bin/` and three settings (`sourcePaths`, `outputPath`, `referencedLibraries`).
  jdtls also picks up `foo-sources.jar` next to `foo.jar`.
  It is also the shape the JDK's own launcher takes: `java --class-path 'lib/*' src/Main.java` (JEP 458, Java 22).
- **Source roots come from package declarations.**
  jdtls's `InvisibleProjectImporter` reads each opened file's `package` line and walks up one folder per segment.
  JEP 458's launcher uses the same rule to find a program's other classes.
  No editor was found that configures itself from the launcher's rules.
- **Jars are copied by hand, except in IntelliJ.**
  Only IntelliJ downloads by Maven coordinates without a Maven project ("From Maven…", stored as a `repository` library), and its search dialog is reported as nearly unusable (IDEA-376817).
  The other in-source way to declare libraries is JBang's `//DEPS g:a:v` comment.
- **Tests work once JUnit is on the classpath.**
  VS Code's test runner offers to add the JUnit jars to an unmanaged folder.
- **Overrides are a few settings, never a project model,** in the tools users find lightest (VS Code's three settings).

### Mistakes to avoid

- **Hidden state.** jdtls keeps `.project`, `.classpath` and its output under VS Code's workspace storage since vscode-java 1.1.0, and users get lost there; a relative `outputPath` that leaves the folder silently lands there too (vscode-java #2786).
- **Refresh only on a trigger.** jdtls re-reads `lib/` when the client sends trigger files; clients that don't (Sublime's LSP-jdtls) never saw a new jar until the workspace data was deleted.
- **Guessing that cannot be inspected.** jdtls's maintainers keep the heuristics undocumented on purpose, so users find a working layout by trial and error.
- **Symlinked shadow projects.** `jbang edit` builds a temporary Gradle project of symlinks, which needs elevated rights on Windows and once lost edits (jbang #1004).

### Sources

- jdtls / VS Code: https://code.visualstudio.com/docs/java/java-project,
  https://github.com/eclipse-jdtls/eclipse.jdt.ls/discussions/3468,
  `InvisibleProjectImporter.java` in eclipse-jdtls/eclipse.jdt.ls,
  https://devblogs.microsoft.com/java/say-goodbye-to-project-files-in-1-1-0/,
  https://github.com/redhat-developer/vscode-java/issues/2786,
  https://github.com/sublimelsp/LSP-jdtls/pull/96
- IntelliJ: https://www.jetbrains.com/help/idea/library.html,
  https://www.jetbrains.com/help/idea/content-roots.html,
  https://youtrack.jetbrains.com/issue/IDEA-312779,
  https://youtrack.jetbrains.com/projects/IDEA/issues/IDEA-376817
- NetBeans: https://docs.oracle.com/netbeans/nb82/netbeans/NBDAG/build_japps.htm,
  https://netbeans.apache.org/front/main/blogs/entry/restructuring-of-project-templates-in/
- java-language-server: `InferConfig.java` in georgewfraser/java-language-server
- Metals v2 and BSP: `docs/build-tools/mbt.json.md` on scalameta/metals `main-v2`,
  https://build-server-protocol.github.io/docs/extensions/jvm,
  https://scalacenter.github.io/bloop/docs/configuration-format/
- Bazel: https://blog.bazel.build/2019/09/29/intellij-bazel-sync.html
- JBang: https://www.jbang.dev/documentation/jbang/latest/editing.html,
  https://github.com/jbangdev/jbang-vscode
- JEP 458: https://openjdk.org/jeps/458

## Design

### The idea

`:LatheInitPlain` (Neovim) or **Lathe: Initialize Plain Java Folder** (VS Code) generates a small Maven build for the folder and runs it once with the Maven Wrapper.
The wrapper downloads Maven on first use, so the user installs nothing but a JDK.
From then on the folder is, to Lathe, an ordinary Maven workspace: the extension, the compiler shim and `lathe:sync` write `.lathe/` exactly as they do for any project, and the server needs no change.

### Layout

```
project/
  src/                          the user's sources (and resources), untouched
  .lathe-pom.xml                the generated build (hidden name)
  .lathe/
    build/
      mvnw, mvnw.cmd            Maven Wrapper scripts, copied from the client
      .mvn/wrapper/maven-wrapper.properties
      target/                   Maven's output (the build's <directory>)
    classes/, lsp-params-*.json, workspace.json, ...   written by Lathe as for any Maven project
```

**Why the POM sits at the root under a hidden name.**
Maven sets a project's base directory to the directory of its POM, and Lathe takes the top-level project's base directory as the workspace root (the extension, `lathe:sync` and the compiler shim all do).
A POM inside `.lathe/build/` would make Lathe write `.lathe/build/.lathe/`, and the clients would never find the launcher.
A root POM named `pom.xml` would turn the folder into a Maven project for every other tool, and change how Lathe's own clients treat it.
`.lathe-pom.xml`, run with `-f`, keeps the root right and the folder plain.

### The generated POM

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>local</groupId>
  <artifactId><!-- folder name --></artifactId>
  <version>0</version>
  <properties>
    <lathe.version><!-- client version --></lathe.version>
    <maven.compiler.release><!-- the JDK's feature version --></maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <!-- <dependencies> go here; see "Growing the project" -->
  <build>
    <directory>${project.basedir}/.lathe/build/target</directory>
    <sourceDirectory>src</sourceDirectory>
    <!-- Maven's defaults (src/test/java, src/test/resources) sit inside src/: point them at a
         directory that never exists, so a user folder src/test/ is plain sources, not a test tree -->
    <testSourceDirectory>${project.basedir}/.lathe/build/no-tests</testSourceDirectory>
    <resources>
      <resource><directory>src</directory><excludes><exclude>**/*.java</exclude></excludes></resource>
    </resources>
    <testResources>
      <testResource><directory>${project.basedir}/.lathe/build/no-tests</directory></testResource>
    </testResources>
    <extensions>
      <extension><!-- io.github.ag-libs:lathe-maven-extension:${lathe.version} --></extension>
    </extensions>
    <plugins>
      <!-- maven-compiler-plugin pinned (3.6.2+, Lathe's floor); spotless-maven-plugin with the
           chosen formatter step, when one is chosen -->
    </plugins>
  </build>
</project>
```

- **Resources live in `src/`** next to the sources, as IntelliJ and Eclipse do for projects with no build tool; there is no separate resources directory.
  Maven copies them with the `**/*.java` exclude.
  The server's own resource copy (on a resource edit) does not see Maven's excludes: `ModuleResourcesReader` records only the directory, output, target path and filtering.
  So the server gains one rule, useful to Maven users with the same setup (MyBatis mappers in `src/main/java`, for example): **a `.java` file under a compile source root is a source, never a resource.**
  The [probe](#probe-2026-10-27) showed the copy without it.
- **The formatter** is a Spotless step (`googleJavaFormat`, `palantirJavaFormat`, `eclipse`) with the chosen version; `MavenStyle` reads it and resolves the formatter as it does for any project, so any version works.
  Spotless is only declared, not bound to a phase, so the build never formats or checks.
- **The Lathe extension version is the client's version**, kept in the `lathe.version` property so it is one line to update. Neovim's `VERSION` is stamped by `publish-nvim.sh` and VS Code's `package.json` version by CI, both from the release tag that publishes the extension.
  The prototype checks that Maven interpolates a property in a build extension's version.
- **Every managed value is a property or a plain element** the user can edit: the release (`maven.compiler.release`), the formatter step, the dependencies.

### The wrapper

The client carries the **"only-script" Maven Wrapper**: `mvnw`, `mvnw.cmd` and `maven-wrapper.properties`, plain text with no jar, Apache-2.0 with its license header kept.
`:LatheInitPlain` copies them into `.lathe/build/`.

Checked in the wrapper's source (`apache/maven-wrapper`, `only-mvnw`):
- Maven is installed into `~/.m2/wrapper/dists/apache-maven-<version>/<hash>/` (`MAVEN_USER_HOME` moves it), once per Maven version and shared by every project.
- It downloads with `wget`, else `curl`, else a small Java downloader it compiles itself, which a JDK always allows; it unpacks with `unzip`, else switches to the `.tar.gz` distribution and `tar`.
  `mvnw.cmd` uses PowerShell on Windows, which suits VS Code.
- The properties pin the Maven version and its `distributionSha256Sum`, checked after download; the client stamps both from its release.
- `MVNW_REPOURL` (with `MVNW_USERNAME`/`MVNW_PASSWORD`) points the Maven download at a corporate mirror; plugin and dependency downloads follow `~/.m2/settings.xml` as for any Maven build.
- Plugins and Lathe's jars go to `~/.m2/repository`: it is Maven, so the standard place, shared with any real Maven use and reused when the user graduates.

**One copy of the templates.**
They live once in the monorepo, under `lathe-neovim/runtime/templates/` (mirrored to `lathe.nvim` with the rest of the plugin); the VS Code packaging copies them into the extension.
Text files only, so no binary history in `lathe.nvim` and no reactor-built jar in the `.vsix`.

### `:LatheInitPlain`

1. **Check the folder**, with no build-file detection:
   - **`.lathe/` exists, `.lathe-pom.xml` does not:** some other build (Maven, OpenJDK, later Gradle) set this workspace up.
     Warn and stop: *"This folder is already a Lathe workspace set up by its build; `:LatheSync` refreshes it."*
   - **`.lathe-pom.xml` exists:** a re-run, see step 3.
   - **No `src/`:** explain the expected layout and stop.
   - Otherwise: a first run.
2. **Find the JDK** from the environment, no question asked: `$LATHE_JAVA_HOME`, else `$JAVA_HOME`.
   - Neither set, or not a JDK: stop and point at the docs (*"Set JAVA_HOME to a JDK 17 or newer"*). No guessing from `PATH`.
   - Older than 17 (read from `<home>/release`): explained and refused, since Maven runs the Lathe extension on it.
   - No `lib/src.zip` (a distro package without its `-source` package): a warning, no navigation into the JDK.
3. **Write the files.**
   - First run: ask the one question, the **formatter** (none, or an engine and version; the default version is the one the Lathe release was tested with), then write `.lathe-pom.xml` with the release set to the JDK's feature version.
   - Re-run: leave `.lathe-pom.xml` as the user has it, except the `lathe.version` property, set to the client's version when it differs.
   - Both: (re)write the wrapper files under `.lathe/build/`.
4. **Run** `.lathe/build/mvnw -f .lathe-pom.xml process-test-classes` with `JAVA_HOME` set to that JDK, showing its output (the first run downloads Maven, the plugins and the server).
   The wrapper reads its properties relative to its own location (`scriptDir/.mvn/wrapper/…` in `only-mvnw`), so it runs from `.lathe/build/` with the root as the working directory.
5. **Attach** when `.lathe/lathe-launcher.sh` appears, as for any Lathe workspace.

Lathe's sync records the JDK in `.lathe/java-home`, as it does on Maven today.

**Accepted risk.** Without build-file detection, running `:LatheInitPlain` in a Maven or Gradle project that Lathe has not set up yet makes it a plain project: `src/` compiles as one source root, so `src/main/java/com/x` reads as package `main.java.com.x` and every import fails.
The errors make it visible, and deleting `.lathe-pom.xml` and `.lathe/` undoes it; the docs say `:LatheInitPlain` is for folders without a build.

### After `:LatheInitPlain`

It is a Maven workspace:
- The server keeps `.lathe/classes` current between builds: save compiles, sibling recompilation, external changes (a `git pull`, an agent's edits), stale-class cleanup.
- `.lathe-pom.xml` is a build file in `workspace.json`: editing it (the release, the formatter, a dependency) prompts a sync, as a POM edit does.
- `.lathe/lathe-sync.sh` re-runs the build on the Maven the wrapper installed, recorded by absolute path as on any Maven workspace.
  It must pass `-f .lathe-pom.xml`: `MavenSyncScript` adds `-f` when the session's POM is not `<root>/pom.xml` (one change).
- A new Lathe version (after a client update): the client sees `workspace.json`'s `serverVersion` differ from its own and suggests `:LatheInitPlain`, which updates `lathe.version` and re-runs the build.

### Running `main` and loading resources

- **Running.** `lathe:sync` writes `main-launch.json` (`MavenMainLaunch`); runs execute from `.lathe/classes`, which the server keeps current, with the project root as the working directory, so `Path.of("data.txt")` resolves where a novice expects.
  `RunnableScanner` finds the classic `public static void main(String[])` and a bare instance `void main()` (JEP 512, Java 25's compact source files), in named packages and the default package alike.
  No client change: `:LatheRun` and the gutter work as on Maven.
- **Resources.** Every non-`.java` file in `src/` is a resource:
  Maven copies it into the build output at init (the `**/*.java` exclude), which Lathe mirrors into `.lathe/classes`,
  and afterwards the server copies resource edits and new resource files into `.lathe/classes` itself, which needs the server rule above.
  Both lookup styles work at run time: `Main.class.getResourceAsStream("app.properties")` for a file beside `Main.java`, and `getClassLoader().getResource("com/example/app.properties")`.

### Creating classes and packages (`:LatheNew`)

The server is layout-neutral: `lathe.createType`, `lathe.packages` and `lathe.resolveContext` use each module's first source root from its params (`primarySourceRoot` in `WorkspaceSession`), so new files land in `src/<package>/`, a dotted name (`jobs.Scheduler`) creates a package, and `createType` handles the default package (no `package` line).
The Neovim client (`lathe/new.lua`) needs three changes:

1. **Offer the default package.** `collect_destinations` drops every entry with an empty package, so in a project with only `src/Main.java`, or an empty `src/`, the "Where:" picker is empty and the first class cannot be created.
   Offer a "(default package)" row for a module that has default-package files or no packages yet.
2. **No test kinds in a plain project.** There is no test tree, so `Test` fails with the server's raw "no test source root for module ''".
   Hide `Test` (and `module-info`, which would make the project modular) when the root has `.lathe-pom.xml`; the server's message also becomes readable for any project without a test tree.
3. **Wording.** The root module's label is empty (`com.example ( · main)`, already so for single-module Maven projects): show "(root)".
   "no modules found — run a build first" names `:LatheInitPlain` in a plain folder.

### Growing the project

`.lathe-pom.xml` belongs to the user after the first run, so it can grow without clashing with `:LatheInitPlain`:

- **Libraries.** Add a `<dependencies>` block to `.lathe-pom.xml` and save: the server sees the build file change and prompts a sync, Maven resolves the jars, and Lathe adds their sources and type index as for any Maven project.
  The docs show one example (a library with its `<dependency>`).
- **Release or formatter.** Edit `maven.compiler.release` or the Spotless step, and save.
- **Tests, or a real Maven project.** Rename `.lathe-pom.xml` to `pom.xml`, drop the `<directory>` and test-directory overrides, and move `src` to `src/main/java` (or keep `<sourceDirectory>`); tests go in `src/test/java`.
  A **Lathe: Convert to Maven Project** command can do it once users ask; until then the docs describe it.

### OpenJDK

OpenJDK keeps `lathe-openjdk-maven-plugin`; its `sync` goal runs without a project (`requiresProject = false`).
The [OpenJDK guide](../guide/openjdk.md) can document running that goal through the wrapper: copy the wrapper files (from the plugin's templates) into `.lathe/build/` and run `mvnw io.github.ag-libs:lathe-openjdk-maven-plugin:<version>:sync`, so JDK developers no longer need Maven on `PATH` either.
An editor command for it, `:LatheInitOpenJdk`, can follow if JDK developers ask; there would be no questions, since the built JDK is the JDK.

### First-run cost

The first `:LatheInitPlain` on a machine downloads Maven (about 10 MB), the plugins the build uses, and the Lathe extension and server; later runs reuse them.
The [prototype](#open-questions) measures the real time and size before this is built.

## What changes

- **Neovim:** `:LatheInitPlain` (folder check, JDK from the environment, the formatter question, templates, the `lathe.version` update on re-run, the wrapper run, attach); the templates under `runtime/templates/`; the three `:LatheNew` changes; the version-mismatch suggestion.
  The runner in `sync.lua` (output split, progress, lock) is shared: its body becomes a function that takes a command and an environment, used by `:LatheSync` and `:LatheInitPlain`.
- **VS Code (later):** **Lathe: Initialize Plain Java Folder** with quick picks; the packaging copies the templates; New Java Project gains a **Plain Java (no build tool)** choice that writes `src/Main.java` and runs the same steps.
- **`MavenSyncScript`:** `-f <pom>` when the POM is not `<root>/pom.xml`.
- **Server:** skip `.java` files under a source root when copying resources; a readable message when a module has no test tree.
- **Docs:** the Neovim and VS Code cheatsheets ("No build tool": `JAVA_HOME`, the command, adding a library, growing into Maven, resource lookup), the OpenJDK guide (the wrapper path), `status.md`.

No new module, no new dependency, no new Java front-end.

## Probe (2026-10-27)

Run for [alternative A](#a-a-cli-jar-with-an-embedded-resolver), it also holds here, since the server is the same.
On the published 0.1.24 server, with a hand-written `.lathe/` (params with `outputDir` = `.lathe/classes`, `java-home`, launcher; no classes, no stamps) and two classes, one using the other:
- the server compiled both into `.lathe/classes` within about a second of starting and wrote the stamps, with no sync prompt;
- no diagnostics on the using class, and go-to-definition across the files worked;
- with `src/` as a resource root, a `.properties` file was copied into the mirror, and so were the `.java` files, which the server rule above fixes.

## Alternatives considered

Three choices shape any design here: how the server's jars reach the machine, who writes `.lathe/`, and how a formatter is resolved.

| | A: CLI + resolver | B: bundle + `init` | **C: hidden Maven (chosen)** | D: server sets up |
|---|---|---|---|---|
| New Java code | module + resolver + bootstrap | module + bootstrap | a `-f` and a resource rule | server mode + bundle |
| New dependencies | `maven-resolver-supplier` | none | none | none |
| First run | ~25 jars | one 15 MB file | Maven + plugins (slowest) | one 15 MB file |
| Formatter | any version | bundled versions only | any version (Spotless) | none / bundled |
| Libraries or tests later | no | no | easy (graduate) | no |
| OpenJDK without Maven on `PATH` | yes | yes | yes (wrapper) | no |
| Architectural risk | medium | low | low | high |

### A: a CLI jar with an embedded resolver

A small `lathe-cli` jar, never shaded (dependency jars carried unchanged and loaded by a bootstrap `Main`), embedding Maven Resolver as a library (`maven-resolver-supplier`, reusing `MavenArtifactResolver`) to fetch the server and formatter from Central, with one re-runnable `init` that writes `.lathe/` and compiles nothing (the server fills the mirror, per the probe).
Neovim would download it with `vim.net.request` (which runs `curl`); VS Code would ship it.
Set aside: a new module, a new and unverified dependency (built against the 1.4.x resolver API, run on 1.9.x), a second way to install the server, a bootstrap class loader, the repository-location question, and an uncommitted release.

### B: a self-installing bundle with `init`

One jar carrying the server's jars, extracting them into `~/.cache/lathe/servers/<v>/`, with the same `init` as A.
Set aside: no resolver, so formatters only at bundled versions (or a resolver after all); 13–15 MB per version with no incremental upgrades; still a new module and a second install path.

### D: the server sets up a plain folder

The client starts the user-wide server, which writes its own configuration for a folder with no `.lathe/` and no build file.
Set aside: it breaks the seam that keeps every front-end simple (the server reads `.lathe/`, it never sets projects up), and still needs a bundle, a second way for clients to attach, and some formatter story.

### Downloading from an extension

Checked 2026-10-27:

- **Allowed and common.** No Marketplace rule against runtime downloads was found; Microsoft's own Remote-SSH and C# extensions download on first activation ([vsmarketplace #211](https://github.com/microsoft/vsmarketplace/discussions/211)), and the [remote development guide](https://code.visualstudio.com/api/advanced-topics/remote-extensions) tells authors to "include (or dynamically acquire)" binaries.
  Extensions run with VS Code's full rights, network included ([extension runtime security](https://code.visualstudio.com/docs/configure/extensions/extension-runtime-security)).
- **Metals splits it the same way.** The [Metals extension](https://marketplace.visualstudio.com/items?itemName=scalameta.metals) ships a small Coursier launcher and downloads the Metals server from Maven Central on startup, with repository, mirror and proxy settings ([proxy docs](http://scalameta.org/metals/docs/troubleshooting/proxy/)).
  Offline and corporate users report friction ([metals-vscode #1617](https://github.com/scalameta/metals-vscode/issues/1617), [metals #7276](https://github.com/scalameta/metals/discussions/7276)).
- **VS Code moved the extension's own files to shipping.** Since VS Code 1.61, platform-specific packages let C/C++ and Terraform drop their first-activation downloads ([vscode #23251](https://github.com/microsoft/vscode/issues/23251), [cpptools #8152](https://github.com/microsoft/vscode-cpptools/issues/8152), [vscode-terraform #905](https://github.com/hashicorp/vscode-terraform/pull/905)),
  because an installed extension that is still installing itself is a poor experience, every downloader must copy VS Code's proxy handling, and offline installs break.

Researched for alternative A (2026-10-27). Under C the extension ships only text templates and the wrapper downloads Maven, the same split Metals uses: the launcher ships, the heavy parts download.

## Open issues (review 2026-10-29)

A review against the goal: a student, or anyone with little Maven experience, sets up a simple project.
The foundation holds (only a JDK to install, nothing Maven-shaped in `src/`, Lathe's existing Maven path reused, a path to libraries and to real Maven); these issues are left for a follow-up design session.
Each has a direction from the review, not a decision.

### Blockers

#### 1. Code that does not compile blocks the setup, and every later sync

`lathe:sync` runs at `process-test-classes`, after compilation, and nothing in Lathe sets the compiler's `failOnError`.
A compile error, normal while learning, stops Maven before Lathe's sync: on the first run no launcher or `workspace.json` is written, so the editor never attaches and the student cannot use it to fix the error; every later sync fails the same way.

Direction: the POM template sets `<failOnError>false</failOnError>` on `maven-compiler-plugin`.
The build then reaches Lathe's sync, the compiler shim still writes the params (it writes them before checking success), and the server compiles what it can itself (the [probe](#probe-2026-10-27)) and shows the errors in the editor.
To be confirmed by the prototype.

#### 2. A run from the editor cannot read keyboard input

`Launcher` (`lathe-server/.../run/Launcher.java`) closes the program's stdin ("Nothing ever writes to the program's input"), so `new Scanner(System.in).nextInt()` fails at once with `NoSuchElementException`.
Student programs are full of these.
It is a gap in Lathe generally, not only in plain projects, but it hits this audience hardest.

Direction: a run mode in a real terminal: the server returns the command line it would run (it already builds it), and the client runs it in `:terminal` or a VS Code terminal.
Its own small design, needed before this is offered to students.

### Worth changing

#### 3. A `pom.xml` in the same folder

Course repositories often come with a `pom.xml`.
Without any detection, `:LatheInitPlain` turns such a folder into a broken plain project (`src/main/java/com/x` read as package `main.java.com.x`), and this audience cannot tell why.
Direction: one check in the opened folder only (not the deep search set aside on 2026-10-29), warning with "continue anyway?" rather than stopping; see also [issue 8](#8-one-latheinit-with-a-dialog).

#### 4. JDK only from `JAVA_HOME`

A JDK installed by a package manager, an installer or an IDE often leaves `JAVA_HOME` unset, so the requirement fails many students who have a working JDK.
Direction: fall back to `java` on `PATH`, resolved to its home, and always show which JDK was used and how to override it.

#### 5. A folder without `src/`

The commonest student layout is `.java` files directly in the folder, which the design refuses.
Direction: offer to move them into `src/` with one confirmation (this also settles open question 5).

#### 6. Raw Maven output and errors

The output split shows Maven's log.
Direction: run Maven quietly (`-q`), open the output only on failure, and translate the common failures into plain messages: no network or a proxy, `JAVA_HOME` wrong or too old, a failed download; compile errors then show as diagnostics ([issue 1](#1-code-that-does-not-compile-blocks-the-setup-and-every-later-sync)).

#### 7. The formatter question

Students do not know google from palantir.
Direction: no question; default to no formatter, and document the Spotless lines to add to `.lathe-pom.xml`, so setup asks nothing.

#### 8. One `:LatheInit` with a dialog

Personal setup ([Personal Setup](lathe-personal-setup.md)) has since been requested by another user, which revives the earlier idea of a single entry point: `:LatheInit` with a dialog (Plain Java, Maven, later Gradle, OpenJDK), rather than `:LatheInitPlain` alone.
The workspace state can be detected from build files in the opened folder only (a `pom.xml`, `build.gradle(.kts)` or `settings.gradle(.kts)` at the root), which covers the large majority of projects (estimated 95%), and only proposes the dialog's default.
This would also answer [issue 3](#3-a-pomxml-in-the-same-folder).

### Minor

- **Libraries mean editing XML** in a hidden file: fine as the next step, but the docs need a copy-paste example; a later `:LatheAddLibrary` could hide the XML.
- **Students mostly use VS Code, often on Windows.** Neovim first suits dogfooding, but students see nothing until the VS Code step, so it should follow right after.
- **The first run downloads a lot** (Maven, plugins, server): a message such as "first-time setup, about a minute" keeps it from looking hung; the prototype gives the real figure.
- **Java 25's `void main()` needs release 25 or newer.** The release defaults to the JDK's version, so a student on JDK 21 gets a confusing error; a hint when an instance `main` meets an older release would help.

## Open questions

1. **Prototype:** a hand-written `.lathe-pom.xml` and wrapper for the probe project. Measure the first run (time, download size), and check:
   - `.lathe/` lands at the root with `target/` under `.lathe/build/`, and the server attaches;
   - `${lathe.version}` is interpolated in the build extension's version;
   - the Spotless step is read without binding it;
   - a default-package `Main` and a Java 25 `void main()` file run;
   - `getResourceAsStream` finds a resource placed in `src/` before init, and one added after init without a sync;
   - a `<dependencies>` edit prompts a sync and the library resolves.
2. **The POM's name:** `.lathe-pom.xml` at the root (a hidden file next to `.lathe/`), or another name.
3. **Commit it?** `.lathe-pom.xml` holds the release, the formatter and any dependencies, and belongs to the user after the first run, so committing it is natural (and it is what graduation renames).
4. **First start that does not compile:** does the Maven build stop the setup, or does `:LatheInitPlain` attach anyway and let the server compile what it can?
5. **A folder of `.java` files with no `src/`** (JEP 330/458 single-file programs): offer to move them, or out of scope?

## Milestone candidate

Untriaged.
Slices, in order:

1. **Prototype** (open question 1).
2. **Lathe changes:** `-f` in `MavenSyncScript`, the server's resource rule.
3. **Neovim:** `:LatheInitPlain` with the templates and the wrapper run, and the `:LatheNew` changes.
4. **OpenJDK:** the wrapper path in the OpenJDK guide.
5. **VS Code:** the same command, the packaged templates, and the Plain Java choice in New Java Project.
