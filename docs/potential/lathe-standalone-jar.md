# Lathe — Standalone Jar (Lathe Without a Build Tool)

## Status

**Potential.**
Records a design discussion held on 2026-10-25 to 2026-10-27, and a survey of how other Java tools support projects with no build tool.
Nothing here is approved; the new module and its dependencies need a design summary and approval first.
Items marked **recommended** are proposals from the discussion, not decisions.

**Decided 2026-10-27:**

- **Neovim first**, then VS Code, then OpenJDK.
- **The source directory is `src/`**, fixed: no layout detection.
- **No libraries.** A user who needs libraries (and their sources) uses Maven or Gradle; this front-end serves plain Java only.
- **The user chooses the formatter**, by engine and version, written to the committed `lathe-style.json`; Lathe resolves the formatter's jars itself.
  The same declaration, a name and version instead of a classpath, is what [Declared Formatters](../planned/lathe-declared-formatters.md) proposes for every build tool.
- **Neovim downloads the CLI jar** with its own HTTP client, `vim.net.request` (Neovim 0.12, the client's minimum).
  Shipping it would add binaries to the `lathe.nvim` git history on every release.
- **VS Code ships the CLI jar in the `.vsix`** (the Metals pattern, see [Downloading from an extension](#downloading-from-an-extension)).
  This makes `lathe-vscode` depend on `lathe-cli` in the build order, which is **awaiting approval**.
- **The jar is small and resolves the rest.** It embeds a Maven repository resolver (a library, not a Maven install) and resolves the server's and the formatter's jars from Central itself; there is no bundle of server jars.
- **`lathe-openjdk-maven-plugin` is retired** once the documented `curl` path exists.

Related designs:
- [Workspace Contract](../lathe-workspace-contract.md): the `.lathe/` files every front-end writes; this is one more front-end.
- [Personal Setup](lathe-personal-setup.md): its option A (jars in the `.vsix`) serves VS Code only; the client download here serves every editor.
- [Pinned Formatters](../done/lathe-pinned-formatters.md) and [Declared Formatters](../planned/lathe-declared-formatters.md): the formatter `init` can set up.
- [New Java Project (VS Code)](../planned/lathe-vscode-new-project.md): the novice path this would add a build-less choice to.
- [OpenJDK support](../done/lathe-openjdk-support.md): the front-end this would take off Maven.
- [Gradle support](../planned/lathe-gradle-support.md): its slice 0 (a Maven-free sync entry point) overlaps.
- [Lessons from Metals v2](lathe-metals-v2-lessons.md): rejects types before the first build (turbine), which this design avoids by compiling once.

## Problem / motivation

Every way into Lathe today goes through Maven:
the extension and compiler shim record the compiler settings during a Maven build,
and `ServerInstaller` resolves the server's jars through Maven's repository session (`lathe-install-maven`).

Three audiences have no Maven, or no reason to want it:

- **Users trying Lathe on plain Java with no build tool**: a `src/` folder of coursework, exercises or small programs.
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

One small runnable jar, `lathe-cli-<version>.jar`, published to Maven Central with the other modules, with three commands:

```
java -jar lathe-cli-<version>.jar init      # a plain Java project: write .lathe/, compile once
java -jar lathe-cli-<version>.jar sync      # re-sync it; what lathe-sync.sh runs
java -jar lathe-cli-<version>.jar openjdk   # the OpenJDK front-end, without Maven
```

How each audience gets the jar, in delivery order:

| Audience | Who downloads the jar | Command |
|---|---|---|
| Neovim, no build tool | the client, with `vim.net.request` | `init` |
| VS Code novice | nobody: it ships in the `.vsix` | `init` |
| OpenJDK developer | the user, with a documented `curl` command | `openjdk` |

Neovim and the OpenJDK command put it in one place, `~/.cache/lathe/bootstrap/<version>/`; VS Code runs it from the extension's directory.
The user needs a JDK and nothing else: no Maven, no Gradle.

The survey's conventions are narrowed on purpose:
a fixed `src/` replaces source-root detection, and there is no `lib/`, since a project with libraries belongs to a build tool.

### The jar and its resolver

The jar holds the CLI, `lathe-install`, and an embedded Maven repository resolver, shaded into one file so a single download runs.
The CLI runs on the class path, so shading is safe; the server it installs is never shaded and runs on the module path as today.

**The resolver is Maven Resolver as a library.**
`MavenArtifactResolver` in `lathe-install-maven` is plain Maven Resolver ("Aether") API: it takes a `RepositorySystem` and a session, which Maven's build hands it today.
`maven-resolver-supplier` (Maven Resolver 1.9+) builds a working `RepositorySystem` with no Maven present, so the same `MavenArtifactResolver` serves the CLI.
It handles what a hand-written resolver would get wrong: parent POMs, imported BOMs (the MCP server's Jackson), version ranges (Eclipse JDT), exclusions and conflicts.
It is a new dependency and needs approval.

**It resolves the server.**
`ServerInstaller` asks for the server, MCP server, runner and JUnit Platform as it does on Maven, and writes argfiles and launchers unchanged.
- The jars land in the local repository, `~/.m2/repository` (recommended): shared with any Maven use on the machine, and the same layout a Maven install produces, so the argfiles look the same on both paths.
  The resolver creates the directory; it is a cache, not a Maven install.
- An upgrade downloads only the jars that changed.
- The first `init` downloads about 25 jars from Central or the configured mirror.

This replaces an earlier idea of a 13–15 MB bundle carrying the server jars, which needed its own resolver, extraction into the cache, a content check for SNAPSHOTs and a `bundle` artifact, and still needed a resolver for formatters.

### Client bootstrap

The client provides the jar; the jar does everything else.
The client's part is small: get one file (Neovim downloads and checks it, VS Code has it), ask a few questions, start one `java` process, wait for the launcher.

1. The user runs `:LatheInit` (Neovim) or **Lathe: Set Up This Folder** (VS Code).
2. The client asks the [init questions](#init-questions).
3. Neovim downloads `lathe-cli-<version>.jar` into `~/.cache/lathe/bootstrap/<version>/` with `vim.net.request` and `outpath`, unless it is already there.
   VS Code uses the jar inside the extension.
4. It runs `<jdk>/bin/java -jar <cli jar> init` with the answers as flags, showing its progress (the server download) in a notification.
5. The launcher appears, and the client attaches as it does for any Lathe workspace.

VS Code's New Java Project gets a **Plain Java (no build tool)** choice next to **Maven project**:
it writes `src/Main.java` and runs the same steps, with no Maven download.

**Neovim downloads, VS Code ships.**
Shipping the jar in `lathe.nvim` would add a few MB of binaries to its git history on every release, kept forever, and Neovim users expect plugins to download (mason.nvim).
The `.vsix` has no such cost, and VS Code practice favours shipping (below).
Either way `init` needs the network to resolve the server, so neither client works offline on first use.

#### Downloading from an extension

Checked 2026-10-27:

- **Allowed and common.** No Marketplace rule against runtime downloads was found; Microsoft's own Remote-SSH and C# extensions download on first activation ([vsmarketplace #211](https://github.com/microsoft/vsmarketplace/discussions/211)), and the [remote development guide](https://code.visualstudio.com/api/advanced-topics/remote-extensions) tells authors to "include (or dynamically acquire)" binaries.
  Extensions run with VS Code's full rights, network included ([extension runtime security](https://code.visualstudio.com/docs/configure/extensions/extension-runtime-security)).
- **Metals is this design's pattern.** The [Metals extension](https://marketplace.visualstudio.com/items?itemName=scalameta.metals) ships a small Coursier launcher and downloads the Metals server from Maven Central on startup, with repository, mirror and proxy settings ([proxy docs](http://scalameta.org/metals/docs/troubleshooting/proxy/)).
  Offline and corporate users report friction ([metals-vscode #1617](https://github.com/scalameta/metals-vscode/issues/1617), [metals #7276](https://github.com/scalameta/metals/discussions/7276)).
- **VS Code moved the extension's own files to shipping.** Since VS Code 1.61, platform-specific packages let C/C++ and Terraform drop their first-activation downloads ([vscode #23251](https://github.com/microsoft/vscode/issues/23251), [cpptools #8152](https://github.com/microsoft/vscode-cpptools/issues/8152), [vscode-terraform #905](https://github.com/hashicorp/vscode-terraform/pull/905)),
  because an installed extension that is still installing itself is a poor experience, every downloader must copy VS Code's proxy handling, and offline installs break.

So the extension ships the launcher (the CLI jar, which needs no per-platform package) and the launcher downloads the server, as Metals does.
The jar's own downloads still need the proxy and mirror settings below.

**The jar's version is the client's version.**
Neovim's `VERSION` is stamped by `publish-nvim.sh` and VS Code's `package.json` version by CI, both from the release tag that also publishes the jar.
So the client downloads exactly its own version: no `maven-metadata.xml` lookup, no compatibility check, and nothing pinned in the project.
- Upgrading the client is upgrading Lathe: a client that finds `.lathe/` written by another version (`workspace.json` `serverVersion`) runs its own jar's `sync`, which rewrites `lathe-sync.sh` to point at the new jar.
- Teammates on different client versions share the same committed files, which carry no Lathe version.
- Neovim development builds (`0.0.0-dev`) have no published jar; a setting or environment variable names a local CLI jar instead, like `LATHE_SERVER_DIR` today.
  A VS Code development `.vsix` carries the checkout's jar.
  The checkout's CLI then resolves the SNAPSHOT server from the local repository.

**Only where there is no build file.**
`:LatheInit` in a folder with a `pom.xml` or `build.gradle` explains the Maven (or later Gradle) path instead, so the two never overlap.

**Integrity (recommended).**
Neovim checks the jar against Central's `.sha1` (or a published `.sha256`), which catches a truncated or corrupt download, not tampering; the resolver verifies checksums of everything it downloads.
The confirmation prompt and the docs say what is downloaded and from where.

**Corporate networks (recommended).**
One repository URL setting (a Nexus or Artifactory mirror of Central), used by Neovim for the CLI jar and passed to the jar as a flag for everything else.
Reading mirrors from `~/.m2/settings.xml` can follow if users ask.

**Updates.**
None of their own: a new Lathe arrives with a client update.

### `init` questions

`:LatheInit` asks, and VS Code shows the same as quick picks:

1. **JDK.** The detected JDK with its version, confirmed or replaced:
   `$LATHE_JAVA_HOME`, else `$JAVA_HOME`, else `java` on `PATH`; or a path the user enters.
   It must be 17 or newer, since it runs the jar and the server; an older one is explained and refused.
2. **Java release** the code targets: default the JDK's feature version, any lower release javac accepts.
3. **Formatter**: none, or an engine and its version (see [Formatter](#formatter)).

The client only collects the answers; the jar validates them and writes them down.
The same flags work without an editor, for scripts and the docs:

```
java -jar lathe-cli-<version>.jar init --release 17 --formatter google:1.35.0
```

Where each answer lives:

- **JDK**: `.lathe/java-home`, machine-local, never committed, since JDK paths differ per machine.
  A later `sync` keeps it; re-running `init` changes it.
- **Release**: `lathe-project.json`, committed, since it is a property of the code.
- **Formatter**: `lathe-style.json`, committed.

### `init`: a plain Java project

**The layout is fixed:** sources in `src/`, no libraries.
`lathe-project.json` records what is not fixed, today only the release:

```json
{ "release": "17" }
```

It is the file `sync` reads and the server watches, so changing the release prompts a sync.

**Compile once.**
The jar runs on a JDK, so it compiles `src/` with `javax.tools` into the `.lathe/` mirror (`externalOutput=false`) and writes the stamps.
After that the server keeps the mirror current with save compiles and its in-process sync, as for Maven.
No `bin/` or `out/` appears in the user's folder, and there is no "build first" step.

**Write the contract.**

| File | Written | Notes |
|---|---|---|
| `lsp-params-classes.json` | yes | source root `src/`, empty classpath, the release |
| launchers and argfiles | yes | via `ServerInstaller` |
| `java-home` | yes | the JDK chosen at init |
| `lsp-stamps-classes.json` | yes | from the compile |
| bytecode mirror | yes | from the compile |
| `workspace.json` | yes | `buildFiles` = `lathe-project.json` and `lathe-style.json` with fingerprints; `jdk` from `JdkSourceSync`; `runnerClasspath` resolved like the server |
| `lathe-sync.sh` | yes | `SyncScript` with the chosen JDK's `java` as the tool and `-jar <abs path> sync` as fixed arguments |
| `main-launch.json` | yes | classpath = the mirror; no build to capture from, so derived |
| `style.json` | when a formatter is chosen | the declared engine and version, with the classpath the resolver produced |
| `jvm.args` | when the formatter needs grants | as Maven writes them |
| test files | no | see open question 1 |

### Formatter

The user picks the formatter at init: none, or an engine (`google`, `aosp`, `palantir`, `eclipse`) and a version, offered with the version the Lathe release was tested with as the default.
`init` writes it to the committed `lathe-style.json` as a name and version, with no classpath:

```json
{ "formatter": { "engine": "google", "version": "1.35.0" } }
```

This is the declaration [Declared Formatters](../planned/lathe-declared-formatters.md) proposes for every build tool, so one file format serves Maven, Gradle and this front-end.
`sync` resolves the engine's jars with the [embedded resolver](#the-jar-and-its-resolver), any version, and writes them into `.lathe/style.json`, as the Maven sync does from Spotless.

`eclipse` needs a settings file (`options.file`); `init` offers it only when the user names one.

### `openjdk`: OpenJDK without Maven

Only `SyncMojo` in `lathe-openjdk-maven-plugin` imports Maven; `OpenJdkSync`, `BuildDiscovery` and `CmdlineReader` do not.
`openjdk` is a thin entry point over the same classes, installing the server through the embedded resolver.
There is no client code for it: the [OpenJDK guide](../guide/openjdk.md) documents fetching the jar, and JDK developers run it on the JDK they just built:

```sh
v=<version>
mkdir -p ~/.cache/lathe/bootstrap/$v
curl -fL -o ~/.cache/lathe/bootstrap/$v/lathe-cli-$v.jar \
  https://repo1.maven.org/maven2/io/github/ag-libs/lathe-cli/$v/lathe-cli-$v.jar
build/linux-x86_64-server-release/images/jdk/bin/java \
  -jar ~/.cache/lathe/bootstrap/$v/lathe-cli-$v.jar openjdk
```

- The command downloads into the shared cache, not the checkout: `lathe-sync.sh` names the jar by absolute path, so it must stay where it was downloaded.
- `release.sh` already bumps Lathe's version in the install docs, so the guide's version stays current.
- Windows gets an `Invoke-WebRequest` version of the same block.
- The guide loses its "Maven 3.6.3+ on `PATH`" prerequisite, and `lathe-openjdk-maven-plugin` is removed (decided), so there is one way to sync a JDK checkout.

## Cost

- **One new module**, for example `lathe-cli`: the CLI, depending on `lathe-install`, `lathe-install-maven`'s `MavenArtifactResolver` and the OpenJDK sync classes, shaded with the resolver into one runnable jar.
- **One new dependency**, `maven-resolver-supplier` and its closure, to be approved.
  `MavenArtifactResolver` may move out of `lathe-install-maven` if that module's `maven-core` dependency gets in the way.
- **The `init` front-end:** `lathe-project.json` (a schema record in `lathe-core`, its name a `LatheLayout` constant, listed in the contract's user-owned files), the one-shot compile, and the derived launch file.
- **Neovim bootstrap:** `:LatheInit`, the questions, the download and checksum, the repository-URL and local-jar settings.
- **VS Code, later:** the same questions, the jar packed into the `.vsix`, and `lathe-vscode` built after `lathe-cli` (awaiting approval).
- **OpenJDK:** the sync classes move out of `lathe-openjdk-maven-plugin` (into `lathe-install` or the new module), and the plugin is removed.
- **Release:** one more artifact on Central.

## Open questions

1. Tests: is there a test tree (`test/`), and does the jar resolve the JUnit Jupiter API onto its classpath, since there are no libraries?
2. Does `sync` recompile all of `src/`, or only stale sources by stamp? Recompiling all is simplest and fast at the sizes this targets.
3. Does a folder of `.java` files with no `src/` (JEP 330/458 single-file programs) get an offer to move them, or is it out of scope?
4. Local repository: `~/.m2/repository` (shared, recommended) or `~/.cache/lathe/repository` (no Maven-looking directory for users who never use Maven)?
5. The shaded jar's size, to measure once the resolver's closure is known.

## Milestone candidate

Untriaged.
Slices, in order:

1. **Neovim:** `lathe-cli` with the resolver, `init` and `sync` (no formatter), and `:LatheInit` with the JDK and release questions.
2. **Formatter:** the question, the `lathe-style.json` declaration, and resolution.
3. **VS Code:** the same bootstrap, and the Plain Java choice in New Java Project.
4. **OpenJDK:** the `openjdk` command, the documented `curl`, and removing the Maven plugin.
