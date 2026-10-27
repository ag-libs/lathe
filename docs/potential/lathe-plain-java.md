# Lathe — Plain Java Without a Build Tool

## Status

**Potential; design goal decided 2026-10-28.**
Records a design discussion held on 2026-10-25 to 2026-10-28, a survey of how other Java tools support projects with no build tool, and the alternatives considered.
Nothing here is approved for implementation; the changes listed in [What changes](#what-changes) need a design summary and approval first.

**The design goal is a hidden Maven project run through a bundled Maven Wrapper** ([Design](#design)):
the client generates a Maven build Lathe already understands, the wrapper fetches Maven on first use, and everything after that is today's Maven path.
Three other designs were worked through and set aside ([Alternatives considered](#alternatives-considered)).

**Decided:**

- **Neovim first, then VS Code**, both with the same bundled templates (2026-10-28).
- **Plain Java only: sources in `src/`, no libraries, no tests** (2026-10-27). A user who needs libraries or tests moves to a real Maven or Gradle project; the hidden project makes that step small.
- **The user chooses the JDK, the release and the formatter** at `:LatheInitPlain` (2026-10-27).
- **Nothing for the user to install but a JDK**, and no visible build in the user's sources (2026-10-27).
- **Hidden Maven project via the Maven Wrapper** (2026-10-28), replacing an earlier CLI-jar design ([alternative A](#a-a-cli-jar-with-an-embedded-resolver)).
- **The command is `:LatheInitPlain`** (2026-10-28); VS Code: **Lathe: Initialize Plain Java Folder**.
  It is the only command that creates `.lathe/` from the editor: Maven projects keep registering the extension in their build, and Gradle will use its plugin.
  The `Plain` suffix keeps Maven users from running it on their project, and leaves room for a sibling such as `:LatheInitOpenJdk`.
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
    <maven.compiler.release><!-- answer --></maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <build>
    <directory>${project.basedir}/.lathe/build/target</directory>
    <sourceDirectory>src</sourceDirectory>
    <resources>
      <resource><directory>src</directory><excludes><exclude>**/*.java</exclude></excludes></resource>
    </resources>
    <extensions>
      <extension><!-- io.github.ag-libs:lathe-maven-extension:<client version> --></extension>
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
- **The Lathe extension version is the client's version.** Neovim's `VERSION` is stamped by `publish-nvim.sh` and VS Code's `package.json` version by CI, both from the release tag that publishes the extension.

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

1. **Check the folder.** Plain Java only, so any sign of a real build stops it with an explanation, and it never falls back silently:
   - It looks for `pom.xml`, `build.gradle(.kts)` and `settings.gradle(.kts)` upward to the git root (the filesystem root outside git) and a few levels down (as jdtls stops at depth 3), since the build file need not sit in the opened folder.
   - Found one: *"This folder belongs to ../pom.xml (Maven): register the Lathe extension there"*, with the `.mvn/extensions.xml` snippet at the client's version; for Gradle, the Gradle setup once it exists.
     Compiling a Maven or Gradle project's sources as plain Java (wrong source root, empty classpath) would look like a Lathe bug.
   - An OpenJDK checkout (a built `build/<conf>/jdk/modules`) points at the [OpenJDK path](#openjdk).
   - No `src/`: explain the expected layout.
   - An existing `.lathe-pom.xml` means re-running: its answers are the defaults.
   - A `.lathe-pom.xml` next to a real `pom.xml` (the project moved to Maven): the real build wins, and the command offers to delete the hidden one, which the server would otherwise keep watching.
2. **Ask** (VS Code: quick picks):
   - **JDK**: the detected one with its version, confirmed or replaced: `$LATHE_JAVA_HOME`, else `$JAVA_HOME`, else `java` on `PATH`, or a path the user enters.
     17 or newer, since Maven runs the Lathe extension on it; older is explained and refused.
     A JDK without `lib/src.zip` (a distro package without its `-source` package) gets a warning: no navigation into the JDK.
   - **Release** the code targets: default the JDK's feature version.
   - **Formatter**: none, or an engine and version; the default version is the one the Lathe release was tested with.
3. **Write** `.lathe-pom.xml` and the wrapper files.
4. **Run** `.lathe/build/mvnw -f .lathe-pom.xml process-test-classes` with `JAVA_HOME` set to the chosen JDK, showing its output (the first run downloads Maven, the plugins and the server).
5. **Attach** when `.lathe/lathe-launcher.sh` appears, as for any Lathe workspace.

The JDK is passed only through `JAVA_HOME` for the run; Lathe's sync records it in `.lathe/java-home` as it does on Maven today.

### After `:LatheInitPlain`

It is a Maven workspace:
- The server keeps `.lathe/classes` current between builds: save compiles, sibling recompilation, external changes (a `git pull`, an agent's edits), stale-class cleanup.
- `.lathe-pom.xml` is a build file in `workspace.json`: editing it (or re-running `:LatheInitPlain` with other answers) prompts a sync, as a POM edit does.
- `.lathe/lathe-sync.sh` re-runs the build on the Maven the wrapper installed, recorded by absolute path as on any Maven workspace.
  It must pass `-f .lathe-pom.xml`: `MavenSyncScript` adds `-f` when the session's POM is not `<root>/pom.xml` (one change).
- Run and debug of `main` classes work as on Maven (`main-launch.json`).

### Graduating to a real Maven project

The hidden project is a real one, so graduating is a rename: `.lathe-pom.xml` becomes `pom.xml`, the `<directory>` override goes, and `src` moves to `src/main/java` or stays with `<sourceDirectory>`.
A **Lathe: Convert to Maven Project** command can do it once users ask; until then the docs describe it.
Libraries and tests start there.

### OpenJDK

OpenJDK keeps `lathe-openjdk-maven-plugin`; its `sync` goal runs without a project (`requiresProject = false`).
The [OpenJDK guide](../guide/openjdk.md) can document running that goal through the wrapper: copy the wrapper files (from the plugin's templates) into `.lathe/build/` and run `mvnw io.github.ag-libs:lathe-openjdk-maven-plugin:<version>:sync`, so JDK developers no longer need Maven on `PATH` either.
An editor command for it, `:LatheInitOpenJdk`, can follow if JDK developers ask; there would be no questions, since the built JDK is the JDK.

### First-run cost

The first `:LatheInitPlain` on a machine downloads Maven (about 10 MB), the plugins the build uses, and the Lathe extension and server; later runs reuse them.
The [prototype](#open-questions) measures the real time and size before this is built.

## What changes

- **Neovim:** `:LatheInitPlain` (folder check, questions, templates, the wrapper run, attach); the templates under `runtime/templates/`.
- **VS Code (later):** **Lathe: Initialize Plain Java Folder** with quick picks; the packaging copies the templates; New Java Project gains a **Plain Java (no build tool)** choice that writes `src/Main.java` and runs the same steps.
- **`MavenSyncScript`:** `-f <pom>` when the POM is not `<root>/pom.xml`.
- **Server:** skip `.java` files under a source root when copying resources.
- **Docs:** the Neovim and VS Code cheatsheets ("No build tool"), the OpenJDK guide (the wrapper path), `status.md`.

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

## Open questions

1. **Prototype:** a hand-written `.lathe-pom.xml` and wrapper for the probe project; measure the first run (time, download size), and check the server attaches with `target/` and the mirror under `.lathe/`, the Spotless step is read without binding it, and `main` runs.
2. **The POM's name:** `.lathe-pom.xml` at the root (a hidden file next to `.lathe/`), or another name.
3. **Commit it?** `.lathe-pom.xml` holds the release and the formatter, so committing it shares them with teammates (and is what graduation renames); it also puts a generated file in the repository.
4. **First start that does not compile:** does the Maven build stop the setup, or does `:LatheInitPlain` attach anyway and let the server compile what it can?
5. **A folder of `.java` files with no `src/`** (JEP 330/458 single-file programs): offer to move them, or out of scope?

## Milestone candidate

Untriaged.
Slices, in order:

1. **Prototype** (open question 1).
2. **Lathe changes:** `-f` in `MavenSyncScript`, the server's resource rule.
3. **Neovim:** `:LatheInitPlain` with the templates, the questions and the wrapper run.
4. **OpenJDK:** the wrapper path in the OpenJDK guide.
5. **VS Code:** the same command, the packaged templates, and the Plain Java choice in New Java Project.
