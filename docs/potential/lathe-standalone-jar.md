# Lathe — Standalone Jar (Lathe Without a Build Tool)

## Status

**Potential.**
Records a design discussion held on 2026-10-25 and a survey of how other Java tools support projects with no build tool.
Nothing here is approved; the new module and its dependencies need a design summary and approval first.

Related designs:
- [Workspace Contract](../lathe-workspace-contract.md): the `.lathe/` files every front-end writes; this is one more front-end.
- [Personal Setup](lathe-personal-setup.md): option A, server jars in the `.vsix`, is the same idea for VS Code.
- [New Java Project (VS Code)](../planned/lathe-vscode-new-project.md): the novice path this would add a build-less choice to.
- [OpenJDK support](../done/lathe-openjdk-support.md): the front-end this would take off Maven.
- [Gradle support](../planned/lathe-gradle-support.md): its slice 0 (a Maven-free sync entry point) overlaps.
- [Lessons from Metals v2](lathe-metals-v2-lessons.md): rejects types before the first build (turbine), which this design avoids by compiling once.

## Problem / motivation

Every way into Lathe today goes through Maven:
the extension and compiler shim record the compiler settings during a Maven build,
and `ServerInstaller` resolves the server's jars through Maven's repository session (`lathe-install-maven`).

Three audiences have no Maven, or no reason to want it:

- **Users trying Lathe on code with no build tool**: a `src/` folder and a `lib/` of jars, coursework, scripts, a legacy project built by a shell script.
- **VS Code novices** who want to write Java now, before learning a build tool.
- **OpenJDK developers**, who build with `make` and need Maven on `PATH` only to run `lathe-openjdk-maven-plugin:sync`.

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

## Sketch

One runnable jar, `lathe.jar`, published to Maven Central with the other modules, with three subcommands:

```
java -jar lathe.jar install    # install the server only
java -jar lathe.jar init       # a project with no build tool: write .lathe/, compile once
java -jar lathe.jar openjdk    # the OpenJDK front-end, without Maven
```

### The jar carries the server

The jar bundles the server's and MCP server's module paths, `lathe-test-runner`, and the JUnit Platform jars.
Measured from the 0.1.23 install: the server's module path is 12 jars, about 6 MB; the MCP server's about 12 MB, mostly the same jars; the whole jar would be roughly 13–15 MB.

The nested jars stay separate files, never shaded: the server runs on the module path.
On first use the jar extracts them into the version's cache directory, which today holds only launchers and argfiles:

```
~/.cache/lathe/servers/<version>/
  lib/                     extracted from lathe.jar
  lathe-server.args        module path into lib/ (today: into ~/.m2)
  lathe-mcp.args
  lathe-launcher.sh, lathe-mcp-launcher.sh
```

A second `ArtifactResolver` maps the coordinates `ServerInstaller` asks for (server, MCP server, runner, JUnit Platform) to the paths under `lib/`.
`ServerInstaller.install()` then writes argfiles and launchers as it does now, so launchers, `.lathe/` copies, `latest/` and every editor client are unchanged.

- Extraction is skipped when a complete `lib/` exists; it writes to a temporary directory and renames, so a crash or two concurrent runs never leave half a `lib/`.
  A `-SNAPSHOT` version needs a content check, since one version string covers many builds.
- A Maven install and a jar install of the same version both write valid argfiles; the last writer wins.

### `init`: a project with no build tool

**Detect, then write down what was detected.**

- Source trees: `src/main/java` and `src/test/java` when present; otherwise `src` (main) and `test` (test), the IntelliJ and NetBeans convention.
- Source roots inside them come from each file's package declaration, read with javac's parser (`JavacTask.parse`), not text matching.
- Classpath: `lib/**/*.jar`; a `<name>-sources.jar` next to a jar is that jar's sources, not a classpath entry.
- Release: the running JDK's feature version unless overridden.

`init` writes what it found to a committed `lathe-project.json` at the root and prints it.
The user edits that file to override any guess; the next sync reads it and detects nothing it already names.
This answers the survey's two complaints at once: the guess is visible, and the override is a few fields, not a project model.

**Compile once.**
The jar runs on a JDK, so it compiles every tree with `javax.tools` into the `.lathe/` mirror (`externalOutput=false`) and writes the stamps.
After that the server keeps the mirror current with save compiles and its in-process sync, as for Maven.
No `bin/` or `out/` appears in the user's folder, and there is no "build first" step.

**Write the contract.**

| File | Written | Notes |
|---|---|---|
| `lsp-params-<tree>.json` | yes | from `lathe-project.json` |
| launchers and argfiles | yes | via `ServerInstaller` |
| `java-home` | yes | the JDK running the jar |
| `lsp-stamps-<tree>.json` | yes | from the compile |
| bytecode mirror | yes | from the compile |
| `workspace.json` | yes | `buildFiles` = `lathe-project.json` with a fingerprint, and `lib/` jars; `jdk` from `JdkSourceSync`; `dependencySources` from sibling `-sources.jar` files; `runnerClasspath` from the bundled runner |
| `lathe-sync.sh` | yes | `SyncScript` with `java` as the tool and `-jar <abs path> sync` as fixed arguments |
| `test-launch.json`, `main-launch.json` | yes | classpath = mirror + `lib/` jars; no build to capture from, so derived |
| `test-filter.json`, `jvm.args`, `style.json` | no | the server's defaults apply |

Watching `lathe-project.json` and the `lib/` jars as `buildFiles` means a dropped-in jar prompts a sync on its own, unlike jdtls's trigger-file refresh.

**Not in scope.**
No coordinate downloads: the survey shows manual `lib/` is the norm and IntelliJ's version of downloads is awkward.
Reading JBang's `//DEPS` is the natural next step if anyone asks, but it needs a resolver, which this design avoids.

### `openjdk`: OpenJDK without Maven

Only `SyncMojo` in `lathe-openjdk-maven-plugin` imports Maven; `OpenJdkSync`, `BuildDiscovery` and `CmdlineReader` do not.
`java -jar lathe.jar openjdk` is a thin entry point over the same classes with the bundled-jar resolver, run on the build's own `images/jdk`.
That removes "Maven 3.6.3+ on `PATH`" from the [OpenJDK guide](../guide/openjdk.md)'s prerequisites.
With no backward-compatibility requirement, the Maven plugin would then be retired, so there is one way to sync a JDK checkout.

### VS Code novices

- The extension ships `lathe.jar` in the `.vsix`, or downloads it from Maven Central, and runs `install`: no network or Maven resolution for the server itself.
  For a Maven project the build still runs, because the build is what records the compiler settings.
- New Java Project offers **Plain Java (no build tool)** next to **Maven project**:
  it writes `src/`, `lib/`, a starter class and `lathe-project.json`, then runs `init`.
  The editor is ready in seconds with no Maven download; the trade-off is no libraries except jars the user copies into `lib/`.

## Cost

- **One new module**, for example `lathe-cli`, depending on `lathe-install` and the OpenJDK sync classes, and packing the server, MCP server, runner and JUnit Platform jars as resources (`maven-dependency-plugin` copy plus an index of coordinates to file names).
- **The OpenJDK sync classes move** out of `lathe-openjdk-maven-plugin` (into `lathe-install` or the new module), and the plugin is removed.
- **The bundled-jar `ArtifactResolver`.**
- **The `init` front-end:** detection, `lathe-project.json` (a schema record in `lathe-core`, its name a `LatheLayout` constant, listed in the contract's user-owned files), the one-shot compile, and the derived launch files.
- **Release:** one more artifact on Central; the VS Code extension's packaging, if it bundles the jar.

## Open questions

1. Retire `lathe-openjdk-maven-plugin` once `lathe.jar openjdk` exists, or keep both for a release?
2. Does the VS Code extension bundle the jar (pins the server to the extension version, works offline, a bigger `.vsix`) or download it from Central on first use?
3. Is the plain-Java project in New Project wanted, or should the jar stop at `install` and `openjdk` for now?
4. Test trees: should `init` add the bundled JUnit Jupiter API to the test tree's compile classpath when `lib/` has none, as VS Code's test runner offers to?
5. Does `sync` recompile every tree, or only stale sources by stamp? Recompiling all is simplest and fast at the sizes this targets.
6. Single-file programs (JEP 330/458) with no `src/`: does a folder of `.java` files at the root count as a source tree?

## Milestone candidate

Untriaged.
`install` and `openjdk` stand alone and are the smallest useful slice; `init` follows.
