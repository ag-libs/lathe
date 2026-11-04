# Lathe — `:LatheInit`: Set Up Any Folder (Plain Java, Personal and Team Maven)

## Status

**Potential; direction decided, spikes partly run (2026-10-28).**
Merges two earlier designs on 2026-10-28: Personal Setup (Lathe on a Maven project without changing it; discussed 2026-10-18 and 2026-10-24) and Plain Java Without a Build Tool (discussed 2026-10-25 to 2026-10-29).
Personal mode was requested by another user, and plain mode turned out to be personal mode on a generated POM, so they are one design and one change.
Nothing here is approved for implementation; [What changes](#what-changes) needs a design summary and approval first.
[Open issues](#open-issues) remain for a follow-up session, two of them blockers.

**Agreed:**

- **From personal to team** (2026-10-24): an explicit, previewed step that writes `.mvn/extensions.xml` pinned to the version the user already runs ([Team mode](#team-mode)).
- **One command, `:LatheInit`, with a dialog** (2026-10-28), replacing `:LatheInitPlain` and Personal Setup's `:LatheSetup`: Plain Java, Maven personal, Maven team, Sync, OpenJDK, later Gradle.
- **Detection looks only at the opened folder** (2026-10-28): a `pom.xml`, Gradle files or `.lathe/` there pick the dialog's default, which covers the large majority of projects (estimated 95%); the user chooses explicitly otherwise.
- **Personal activation is `-Dmaven.ext.class.path`** with two jars, `lathe-maven-extension` and `lathe-core` at the client's version (2026-10-28), [spiked](#spike-results-2026-10-28) on Maven 3.9.11.
- **Plain mode is personal mode on a generated `.lathe-pom.xml` that never mentions Lathe** (2026-10-28), run by a bundled Maven Wrapper, so the user installs only a JDK.
- **`.lathe-pom.xml` is written once and then belongs to the user** (2026-10-29): release, formatter and dependencies are edited there.
- **The client's version is the Lathe version** in personal and plain modes (2026-10-28); in team mode the project's pin governs, and no activation is passed ([Never loading Lathe twice](#never-loading-lathe-twice)).
- **Neovim first, then VS Code**, both with the same bundled templates (2026-10-28).

Related designs:
- [Workspace Contract](../lathe-workspace-contract.md): the `.lathe/` files; every mode writes them through the existing Maven front-end.
- [Multi-OS Design](../done/lathe-multi-os.md): the sync script that runs the tool that ran the build (D3) and the tool resolver (D9).
- [Wider Maven Support](../done/lathe-wider-maven-support.md): the Maven 3.6.3 floor, the Plexus descriptor that lets the extension load through `-Dmaven.ext.class.path` on old Maven, detect-and-explain.
- [VS Code client](../planned/lathe-vscode-client.md) and [New Java Project (VS Code)](../planned/lathe-vscode-new-project.md): first-run UX, the JDK check, the launcher watcher, the Red Hat Java notice.
- [Run and Test in VS Code](../planned/lathe-vscode-run-test.md): what needs a test capture, and how a run reports a missing one.
- [OpenJDK support](../done/lathe-openjdk-support.md): the coordinate-invoked front-end the OpenJDK mode runs.
- [Pinned Formatters](../done/lathe-pinned-formatters.md): the formatter is a Spotless step Lathe already reads.
- [Gradle support](../done/lathe-gradle-support.md): Gradle's personal (init script) and team (plugin) modes slot into the same dialog.
- [Lessons from Metals v2](lathe-metals-v2-lessons.md): Lathe trusts the real build; here the build is generated or borrowed, but real.

## Problem / motivation

Before any editor shows anything, a user must today:

1. register `lathe-maven-extension` in the build (`.mvn/extensions.xml` or the root `pom.xml`), choosing the version by hand;
2. run a Maven build;
3. keep `.lathe/` out of git;
4. install and configure the editor client.

Step 1 asks the user to edit build files they may not own (a company repository, a tutorial, someone else's project), and most stop there.
And a folder with no build at all (coursework, exercises, small programs) has no way in.

The audience:
- **Students and others with little Maven experience**, with plain Java in a folder.
- **VS Code and Cursor users**, often on Windows, who expect a Java extension to work like Red Hat's: install it, open the folder, done.
- **Neovim, Zed and Emacs users** who want to try Lathe without touching the project.
- **Users of older Maven** (distribution packages, old wrappers, corporate images); the floor is 3.6.3.
- **OpenJDK developers**, who build with `make` and need Maven on `PATH` only to run Lathe's OpenJDK goal.

The goal: open a folder, run one command, accept the proposed default, and get the full Lathe experience, with no change to the user's project unless they ask for one.

## Prerequisites

- **A JDK 17 or newer** runs Maven whenever Lathe is involved, because the extension and compiler shim run inside Maven's JVM (decided 2026-10-18).
  The project can still target older Java (`release`).
- **Maven 3.6.3 or newer** for a Maven project; plain mode brings its own through the bundled wrapper.
- **The project already builds** in Maven modes: Lathe does not fix broken builds; pre-flight is about detecting and explaining, not installing.

## Design

### Modes

| Mode | The build Maven runs | How Lathe gets in | Project changes |
|---|---|---|---|
| **Plain Java** | a generated `.lathe-pom.xml` | `-D` activation | `.lathe-pom.xml` (user-owned) |
| **Maven, personal** | the project's `pom.xml` | `-D` activation | none |
| **Maven, team** | the project's `pom.xml` | `.mvn/extensions.xml` | one previewed file |
| **Sync** | whatever set the workspace up | as recorded in `lathe-sync.sh` | none |
| **OpenJDK** | none: Lathe's OpenJDK goal, by coordinates | the goal itself | none |
| **Gradle** (later) | the project's build | init script (personal) or plugin (team) | none / one line |

### The `:LatheInit` dialog

Neovim `:LatheInit`; VS Code **Lathe: Initialize Workspace** (a quick pick).
Detection looks only at the opened folder and only proposes the default:

| Found in the folder | Default | Also offered |
|---|---|---|
| `.lathe/` | **Sync** (`.lathe/lathe-sync.sh`) | the mode-specific re-init |
| `pom.xml`, Lathe registered (the client text-checks `.mvn/extensions.xml` and the POM for `lathe-maven-extension`) | **Sync** (a build of the project) | none |
| `pom.xml`, not registered | **Maven, personal** | Maven, team; Plain Java anyway (with a warning) |
| `build.gradle(.kts)` / `settings.gradle(.kts)` | **Gradle**: "coming soon" until it exists | Plain Java anyway (with a warning) |
| a built OpenJDK tree (`build/*/jdk/modules`) | **OpenJDK** | none |
| nothing | **Plain Java** | none |

```
Lathe: initialize ~/work/course-app
  Found pom.xml
> Maven (personal: your project files are not changed)
  Maven (team: adds .mvn/extensions.xml)
  Plain Java (ignore pom.xml)
```

- Enter accepts the default, so the common case is one keystroke.
- The choice can be passed directly: `:LatheInit plain|maven|maven-team|sync|openjdk`.
- A build file outside the opened folder (a module of a bigger project opened alone): the user chooses explicitly, or opens the folder that holds the build; the workspace root is always the build file's folder, because clients find `.lathe/` by walking up from a source file.

### Personal activation

Lathe enters a build it is not registered in through `-Dmaven.ext.class.path=<bootstrap>/<v>/lathe-maven-extension-<v>.jar<sep><bootstrap>/<v>/lathe-core-<v>.jar`.

- **Two jars are enough.** `lathe-maven-extension` depends only on `lathe-core` (`maven-core` is `provided`), and its Plexus `components.xml` lets it load on Maven before 3.9 ([Wider Maven Support](../done/lathe-wider-maven-support.md) D1).
  The extension injects `lathe-compiler`, `lathe:init` and `lathe:sync`, which Maven resolves as usual.
- **The client fetches them through Maven**, once per Lathe version: `dependency:copy -Dartifact=io.github.ag-libs:<artifact>:<v> -DoutputDirectory=~/.cache/lathe/bootstrap/<v>/`, outside any project.
  It goes through the user's `settings.xml`, so proxies and corporate mirrors work with no Lathe setting.
- **`<v>` is the client's version.** Neovim's `VERSION` is stamped by `publish-nvim.sh` and VS Code's `package.json` version by CI, both from the release tag that publishes the extension.
- **Which Maven runs:** the project's `mvnw`, else `mvn` on `PATH` resolved to its full path, else the bundled wrapper; plain mode always uses the bundled wrapper.

### Replaying the activation in later syncs

Every later sync (`:LatheSync`, a server prompt, the terminal) runs `.lathe/lathe-sync.sh`, which must keep Lathe active.
[Spike a](#spike-results-2026-10-28) showed the script today replays neither the activation nor a non-default POM, so a personal-mode sync would build without Lathe and silently leave `.lathe/` stale, and a plain-mode sync would fail with no `pom.xml`.

`MavenSyncScript` (`lathe-maven-plugin`) renders into the script:
- the `-Dmaven.ext.class.path` value, read from the session's user properties, when the project does not register Lathe itself;
- `-f <pom>` when the session's POM is not `<root>/pom.xml`.

### Plain mode

#### Layout

```
project/
  src/                          the user's sources and resources
  .lathe-pom.xml                the generated build (hidden name, user-owned)
  .lathe/
    build/
      mvnw, mvnw.cmd            Maven Wrapper scripts, copied from the client
      .mvn/wrapper/maven-wrapper.properties
      target/                   Maven's output (the build's <directory>)
    classes/, lsp-params-*.json, workspace.json, ...   written by Lathe as for any Maven project
```

**Why the POM sits at the root under a hidden name.**
Maven sets a project's base directory to the directory of its POM, and Lathe takes the top-level project's base directory as the workspace root.
A POM inside `.lathe/build/` would make Lathe write `.lathe/build/.lathe/`; a root `pom.xml` would turn the folder into a Maven project for every other tool.
`.lathe-pom.xml`, run with `-f`, keeps the root right and the folder plain.

#### The generated POM

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>local</groupId>
  <artifactId><!-- folder name --></artifactId>
  <version>0</version>
  <properties>
    <maven.compiler.release><!-- the JDK's feature version --></maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <!-- <dependencies> go here; see "Growing the project" -->
  <build>
    <directory>${project.basedir}/.lathe/build/target</directory>
    <sourceDirectory>src</sourceDirectory>
    <!-- Maven's defaults (src/test/java, src/test/resources) sit inside src/: point them at a
         directory that never exists, so a user folder src/test/ is plain sources -->
    <testSourceDirectory>${project.basedir}/.lathe/build/no-tests</testSourceDirectory>
    <resources>
      <resource><directory>src</directory><excludes><exclude>**/*.java</exclude></excludes></resource>
    </resources>
    <testResources>
      <testResource><directory>${project.basedir}/.lathe/build/no-tests</directory></testResource>
    </testResources>
    <plugins>
      <plugin>
        <artifactId>maven-compiler-plugin</artifactId>
        <version><!-- pinned, 3.6.2+ --></version>
        <!-- a compile error must not stop the build before lathe:sync -->
        <configuration><failOnError>false</failOnError></configuration>
      </plugin>
      <!-- optional: spotless-maven-plugin with a formatter step, declared, not bound -->
    </plugins>
  </build>
</project>
```

- **No Lathe in it.** Lathe comes in through the [personal activation](#personal-activation), so upgrading Lathe never touches the file, and [graduating](#growing-the-project) is a rename.
- **`failOnError=false`**: without it a compile error, normal while learning, stops Maven before `lathe:sync`; on the first run no launcher is written and the editor never attaches.
  [Spiked](#spike-results-2026-10-28): the build succeeds, `lathe:sync` runs, the compiler shim keeps "the last good capture", and the server compiles the rest and reports the error.
- **Resources live in `src/`** next to the sources, as IntelliJ and Eclipse do for projects with no build tool; Maven copies them with the `**/*.java` exclude.
  The server's own resource copy does not see Maven's excludes (`ModuleResourcesReader` records only the directory, output, target path and filtering), so it gains one rule, useful to Maven users with the same setup (MyBatis mappers in `src/main/java`): **a `.java` file under a compile source root is a source, never a resource.**
  Both spikes showed the copy without it.
- **The formatter** is a Spotless step, declared and not bound to a phase, so the build never formats or checks; `MavenStyle` reads it ([spiked](#spike-results-2026-10-28): `style.json` written for google-java-format 1.28.0).

#### The wrapper

The client carries the **"only-script" Maven Wrapper** (`mvnw`, `mvnw.cmd`, `maven-wrapper.properties`): plain text with no jar, Apache-2.0 with its license header kept, copied into `.lathe/build/`.
Checked in the wrapper's source (`apache/maven-wrapper`, `only-mvnw`):
- It reads its properties relative to its own location (`scriptDir/.mvn/wrapper/…`), so it runs from `.lathe/build/` with the root as the working directory, and passes the arguments through to Maven unchanged.
- Maven is installed into `~/.m2/wrapper/dists/apache-maven-<version>/<hash>/` (`MAVEN_USER_HOME` moves it), once per Maven version and shared by every project.
- It downloads with `wget`, else `curl`, else a small Java downloader it compiles itself; it unpacks with `unzip`, else switches to the `.tar.gz` distribution and `tar`. `mvnw.cmd` uses PowerShell on Windows.
- The properties pin the Maven version and its `distributionSha256Sum`, checked after download.
- `MVNW_REPOURL` (with `MVNW_USERNAME`/`MVNW_PASSWORD`) points the Maven download at a mirror; plugin downloads follow `~/.m2/settings.xml`.

The templates live once in the monorepo, under `lathe-neovim/runtime/templates/` (mirrored to `lathe.nvim`); the VS Code packaging copies them into the extension.
Text files only: no binary history in `lathe.nvim`, no reactor-built jar in the `.vsix`.

#### Plain-mode steps

1. **Check:** `.lathe/` with no `.lathe-pom.xml` belongs to another mode (the dialog defaults to Sync); an existing `.lathe-pom.xml` is a re-run, left as the user has it; no `src/` is explained ([issue 5](#open-issues)).
2. **JDK:** `$LATHE_JAVA_HOME`, else `$JAVA_HOME` ([issue 4](#open-issues) proposes a `PATH` fallback); older than 17 is refused, a JDK without `lib/src.zip` gets a warning.
3. **Write** `.lathe-pom.xml` (first run only; the release is the JDK's feature version) and the wrapper files.
4. **Fetch** the two activation jars if missing, and **run** `.lathe/build/mvnw -f .lathe-pom.xml -Dmaven.ext.class.path=… process-test-classes` with `JAVA_HOME` set, showing its output.
5. **Attach** when `.lathe/lathe-launcher.sh` appears.

#### Growing the project

`.lathe-pom.xml` belongs to the user after the first run:

- **Libraries:** add a `<dependencies>` block and save; the server sees the build file change and prompts a sync, Maven resolves the jars, and Lathe adds their sources as for any Maven project. The docs show one copy-paste example.
- **Release or formatter:** edit `maven.compiler.release` or the Spotless step, and save.
- **Tests, or a real Maven project:** rename `.lathe-pom.xml` to `pom.xml`, drop the `<directory>` and test-directory overrides, and move `src` to `src/main/java` (or keep `<sourceDirectory>`). The result is a Maven project in personal mode, and the sync script loses its `-f` at the next sync. A **Convert to Maven Project** command can follow if users ask.

### Maven personal mode

1. **Check:** a `pom.xml` in the opened folder, Lathe not registered (else the dialog defaults to Sync).
2. **Pre-flight:** find Maven (project `mvnw`, else `mvn` on `PATH`, else the bundled wrapper); Maven 3.6.3+; Maven's JDK 17+ ([Detecting and explaining](#detecting-and-explaining)).
3. **Fetch** the two activation jars if missing.
4. **Run** `<maven> -Dmaven.ext.class.path=… process-test-classes` at the reactor root: editing first, no tests executed; tests are captured on demand.
5. **Attach** when the launcher appears; `.lathe/.gitignore` keeps `.lathe/` out of git.

### Team mode

**Agreed 2026-10-24.** A user who started in personal mode moves the project to team mode like this:

1. **An explicit trigger**, never a side effect and never an automatic prompt: `:LatheInit maven-team`, or VS Code's **Lathe: Add to Project Build (for your team)**, also offered from the status bar's Lathe menu.
2. **A preview of the one change**: create `.mvn/extensions.xml`, or add one `<extension>` block to an existing one; `pom.xml` is never touched. In VS Code a reviewable, undoable `WorkspaceEdit`.
   It pins **the version the user already runs**, so nothing changes under them.
3. **A re-sync**: the build now loads Lathe itself, so the sync script is rendered without the activation; `.lathe/` stays and nothing is downloaded again.
4. **The user commits** `.mvn/extensions.xml`; teammates without Lathe are unaffected (Lathe is off without `.lathe/`, and skipped when `CI` is set).
5. **What it gains:** the user's own terminal builds refresh `.lathe/`; teammates need no setup step; the version is pinned by the project.

Version upgrades in team mode are a project change: an editor client newer than the pin shows the existing protocol-mismatch message ("bump `lathe-maven-extension`").
Removing the registration from the project returns the user to personal mode at their next `:LatheInit`.

For Gradle, team mode shows the settings-plugin line with a copy button and never edits build scripts.

### Never loading Lathe twice

[Spike e](#spike-results-2026-10-28) on Maven 3.9.11: with Lathe both registered in `.mvn/extensions.xml` and passed through `-Dmaven.ext.class.path`, Lathe's steps ran once, but **the `-D` copy won**: with the project pinning 0.1.23 and the activation passing 0.1.24, only 0.1.24 ran.
Passing the activation over a project registration would silently override the team's pin and make `.lathe/` flip between versions as editor syncs and terminal builds alternate.

So the rule: **no activation when the project registers Lathe.**
- The client checks before passing it (the same text check that picks the dialog's default).
- `MavenSyncScript` renders it only when the project does not register Lathe, so a sync after moving to team mode drops it.
- An old sync script that still carries it after the project adds a registration is the remaining case; it runs once at most, since that sync renders a new script without it.

### Keeping `.lathe/` out of git

Personal and plain modes promise no project changes, so `.lathe/` ignores itself: `lathe:init` writes `.lathe/.gitignore` containing `*` for every workspace (a small Java change; it also helps team mode and OpenJDK).

### Running `main` and loading resources

[Spiked](#spike-results-2026-10-28) on a plain project:
- `lathe:sync` writes `main-launch.json`; runs execute from `.lathe/classes`, which the server keeps current, with the project root as the working directory.
- `RunnableScanner` finds the classic `main`, a default-package class, and Java 25's compact `void main()` file; all ran.
- Every non-`.java` file in `src/` is a resource: Maven copies it at init, the server copies edits and new files afterwards (`lathe.resource.refresh`); `Main.class.getResourceAsStream(...)` read it at run time.
- **Keyboard input does not work** ([issue 2](#open-issues)).

### Creating classes and packages (`:LatheNew`)

The server is layout-neutral (`lathe.createType`, `lathe.packages` and `lathe.resolveContext` use each module's first source root), so new files land in `src/<package>/` and `createType` handles the default package.
The Neovim client (`lathe/new.lua`) needs three changes:
1. **Offer the default package**: `collect_destinations` drops entries with an empty package, so with only `src/Main.java`, or an empty `src/`, the "Where:" picker is empty. Offer a "(default package)" row for a module with default-package files or no packages yet.
2. **No test kinds in plain mode**: `Test` fails with the raw "no test source root for module ''"; hide `Test` and `module-info` when the root has `.lathe-pom.xml`, and make the server's message readable for any project without a test tree.
3. **Wording**: label the root module "(root)" (it is empty today, also for single-module Maven projects), and name `:LatheInit` in "no modules found".

### OpenJDK

OpenJDK keeps `lathe-openjdk-maven-plugin`; its `sync` goal runs without a project (`requiresProject = false`).
The dialog's OpenJDK mode runs it through the bundled wrapper (`mvnw io.github.ag-libs:lathe-openjdk-maven-plugin:<v>:sync`), so JDK developers no longer need Maven on `PATH`; there are no questions, since the built JDK is the JDK.

### Per-editor experience

**Neovim** (`lathe.nvim`):
- The existing nudge for a Java buffer without `.lathe/` names `:LatheInit`.
- `:LatheInit` runs the chosen mode through the existing sync runner (`sync.lua`: output split, progress, lock), generalized to take a command and an environment.
- After setup nothing changes: `:LatheSync`, attach via the launcher; a client newer than `workspace.json`'s `serverVersion` suggests `:LatheInit` in personal and plain modes.

**VS Code / Cursor:**

| Moment | What the user sees |
|---|---|
| Install | Marketplace / Open VSX; the listing's first line: *"Java support from your real Maven build. Sets itself up without changing your project."* |
| Open a folder | One notification, *"Set up Lathe for this folder?"* [Set up] [Not now] [Learn more], opening **Lathe: Initialize Workspace** with the detected default; "Not now" remembered per folder. |
| Pre-flight | Silent unless something is wrong; otherwise a notification with the fix. |
| First build | Status bar "Lathe: first build…", plus a terminal task with Maven's output. |
| Result | "Lathe: ready", attached without a reload; or *"Your Maven build failed (not Lathe). [Open log]"*. |
| Daily work | A POM change or `git pull`: *"Build files changed. [Sync]"*. |
| Extension update | *"Lathe updated. [Sync now] to switch versions."* in personal and plain modes. |
| Leave | *"Lathe: Remove from this folder"* deletes `.lathe/` (and `.lathe-pom.xml` after asking). |

New Java Project gains a **Plain Java (no build tool)** choice that writes `src/Main.java` and runs plain mode.

**Zed and Emacs:** settings only (no Lathe Zed extension); the docs give the terminal one-liners for each mode and the settings snippet pointing at `.lathe/lathe-launcher.sh`.

**Terminal and AI agents:** the same one-liners, then `.lathe/lathe-sync.sh`; the MCP launcher works from the same `.lathe/`.

### Detecting and explaining

| Problem | Detected by | Message (draft) |
|---|---|---|
| No Maven (Maven modes) | client, before setup | *"Lathe uses your Maven build, but no `mvnw` or `mvn` was found; using Lathe's bundled Maven."* |
| Maven below 3.6.3 | client (wrapper `distributionUrl` or `mvn -v`); inside Maven the build stops (Wider Maven Support D3) | *"Lathe needs Maven 3.6.3+; this project uses 3.5.4. Upgrade the wrapper: …"* |
| Maven's JDK below 17 | client (`mvn -v`, or the JDK's `release` file) | *"Lathe needs Maven to run on Java 17+ (your project can still target older Java)."* |
| Compiler plugin below 3.6.2 | inside Maven (D3) | *"[lathe] … pin a newer maven-compiler-plugin … or build with -Dlathe.disabled=true"* |
| A build ran but wrote no `.lathe/` | client, after the build | *"Lathe needs Maven 3.6.3+ and prints `[lathe]` lines in the build log; if there are none, the extension did not load."* |
| A JDK without `src.zip` | client | *"This JDK has no sources (install `openjdk-NN-source`): no navigation into the JDK."* |

### Other topics

- **Red Hat Java coexistence:** beginners often also install the Extension Pack for Java (duplicate diagnostics, two indexers). A one-time notice with *"Show extension"*, where *Disable (Workspace)* is one click stored by VS Code, not in the project (as in New Java Project §8).
- **First-build scope:** `process-test-classes` only; a test run without a capture reports a blocked reason with a *"Capture tests (runs the tests once)"* action.
- **Update policy:** prompt rather than auto-sync, since a sync is a full Maven build.
- **`~/.m2/extensions.xml`** (Maven 3.10+): an optional upgrade for personal mode so terminal builds refresh `.lathe/` too, offered after asking; not the default.

## Spike results (2026-10-28)

Run on Maven 3.9.11 (the plain Maven inside the local mvnd 1.0.3), JDK 21 and 25, the published Lathe 0.1.24, each with a fresh local repository and Lathe cache.

| Spike | Result |
|---|---|
| **f.** `dependency:copy` with no project | Fetched `lathe-maven-extension` and `lathe-core` (about 130 KB together) into a bootstrap directory. |
| **a.** Personal activation | A Maven project with no Lathe registration, built with the two jars on `-Dmaven.ext.class.path`: `lathe:init`, the compiler capture and `lathe:sync` all ran, `.lathe/` complete. **`lathe-sync.sh` did not carry the activation**, so the replay change is required. |
| **e.** Double registration | Same version in `.mvn/extensions.xml` and `-D`: Lathe ran once. Project pin 0.1.23, `-D` 0.1.24: only 0.1.24 ran; the project's pin was silently overridden. |
| **Plain mode, first build** | `.lathe-pom.xml` with no Lathe, `-f`, activation: 39 s and 25 MB of plugins and Lathe jars on a fresh repository (Maven itself not included: the wrapper download was not run). The Lathe cache took 245 MB, 236 MB of it the JDK's sources, unpacked once per JDK and shared by every project, as on the Maven path today. A warm re-run took 2 s. |
| **Plain mode, output** | `.lathe/` at the root, Maven's output under `.lathe/build/`, `.lathe-pom.xml` listed and fingerprinted as the build file, the Spotless step read into `style.json`. `lathe-sync.sh` had no `-f`, so the replay change is required. |
| **Plain mode, compile error** | With `failOnError=false`: the build succeeded, `lathe:sync` ran, the shim "kept the last good capture"; the server filled the mirror within about a second, reported the error on the broken file, and ran `Main`. |
| **Run and resources** | Default-package `Main` and a Java 25 compact `void main()` file detected and run; a resource in `src/` loaded at run time; a resource added later copied by `lathe.resource.refresh`. The server's resource copy also copied the `.java` files, so the server rule is required. |
| **stdin** | `Scanner(System.in).nextLine()` in a run: `NoSuchElementException: No line found`. |

**Not run:** Maven 3.6.3 and 3.10.0 (fetching the distributions was declined in the session), the wrapper's own Maven download, mvnd, and Windows.

An earlier probe (2026-10-27) on a hand-written `.lathe/` had shown the server filling an empty mirror with no sync prompt.

## What changes

- **Neovim:** `:LatheInit` (detection, dialog, the mode steps, fetching the activation jars, the wrapper templates under `runtime/templates/`); the sync runner generalized; the three `:LatheNew` changes; the nudge and version-mismatch wording.
- **VS Code (after Neovim):** **Lathe: Initialize Workspace**, the open-folder notification, the packaged templates, New Java Project's Plain Java choice, **Add to Project Build**.
- **`MavenSyncScript`:** replay `-Dmaven.ext.class.path` (only without a project registration) and `-f <pom>`.
- **`lathe:init`:** write `.lathe/.gitignore`.
- **Server:** skip `.java` files under a source root when copying resources; a readable message when a module has no test tree.
- **Docs:** the editor cheatsheets ("Set up a folder": the modes, `JAVA_HOME`, adding a library, growing into Maven, resource lookup), the installation guide (personal and team modes), the OpenJDK guide (the wrapper path), `status.md`.

No new module and no new dependency.

## Open issues

From the 2026-10-29 review against the student audience, updated with the spikes and the merge; each has a direction, not a decision.

1. **Code that does not compile blocks the setup** (blocker). Direction confirmed by the spike: `failOnError=false` in the plain-mode POM. Personal mode cannot change the project's POM: pass `-Dmaven.compiler.failOnError=false` with the activation, or accept that a broken project needs fixing first.
2. **A run cannot read keyboard input** (blocker, confirmed by the spike). `Launcher` closes the program's stdin. Direction: a run mode in a real terminal, where the server returns the command line and the client runs it in `:terminal` or a VS Code terminal; its own design, needed before this is offered to students.
3. **The JDK only from `JAVA_HOME`** fails students whose JDK came from a package manager or an IDE. Direction: fall back to `java` on `PATH`, and always show the JDK used and how to override it.
4. **A folder without `src/`**, the commonest student layout, is refused. Direction: offer to move root-level `.java` files into `src/` with one confirmation.
5. **Raw Maven output.** Direction: run quietly (`-q`), open the output only on failure, translate the common failures (no network or proxy, JDK, a failed download).
6. **The formatter question.** Direction: no question in plain mode; default to none, and document the Spotless lines.
7. **The remaining spikes:** Maven 3.6.3 (activation through the Plexus descriptor, and `dependency:copy` with the old default `maven-dependency-plugin`), 3.10.0, the wrapper's first download (time and size), mvnd (does it honor `-Dmaven.ext.class.path` per invocation, given it caches core extensions?), Windows.
8. **The hidden POM:** its name `.lathe-pom.xml`, and whether to recommend committing it (it holds the release, the formatter and the dependencies, and graduation renames it).
9. **Minor:** a hint when a compact `void main()` meets a release below 25; a "first-time setup, about a minute" message; VS Code right after Neovim, since students mostly use it; a later `:LatheAddLibrary` to hide the XML.
10. **Removal:** a command that deletes `.lathe/` (and, after asking, `.lathe-pom.xml`), or only docs.

## Milestone candidate

Untriaged. Slices, in order:

1. **The remaining spikes** (issue 7) and the blockers' designs (issues 1 and 2).
2. **Lathe changes:** the sync-script replay, `.lathe/.gitignore`, the server's resource rule and test-tree message, with an invoker fixture per mode (personal activation, plain with `-f`).
3. **Neovim:** `:LatheInit` (plain, personal, team, sync), the templates, the `:LatheNew` changes.
4. **OpenJDK** through the wrapper.
5. **VS Code.**
6. **Gradle** modes, once the Gradle front-end exists.

## Alternatives considered

### For a folder with no build

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

#### A: a CLI jar with an embedded resolver

A small `lathe-cli` jar, never shaded (dependency jars carried unchanged and loaded by a bootstrap `Main`), embedding Maven Resolver as a library (`maven-resolver-supplier`, reusing `MavenArtifactResolver`) to fetch the server and formatter from Central, with one re-runnable `init` that writes `.lathe/` and compiles nothing (the server fills the mirror, per the probe).
Neovim would download it with `vim.net.request` (which runs `curl`); VS Code would ship it.
Set aside: a new module, a new and unverified dependency (built against the 1.4.x resolver API, run on 1.9.x), a second way to install the server, a bootstrap class loader, the repository-location question, and an uncommitted release.

#### B: a self-installing bundle with `init`

One jar carrying the server's jars, extracting them into `~/.cache/lathe/servers/<v>/`, with the same `init` as A.
Set aside: no resolver, so formatters only at bundled versions (or a resolver after all); 13–15 MB per version with no incremental upgrades; still a new module and a second install path.

#### D: the server sets up a plain folder

The client starts the user-wide server, which writes its own configuration for a folder with no `.lathe/` and no build file.
Set aside: it breaks the seam that keeps every front-end simple (the server reads `.lathe/`, it never sets projects up), and still needs a bundle, a second way for clients to attach, and some formatter story.

### For personal mode's entry point

Personal Setup weighed two entry points before the merge:

- **Option A, jars bundled in the editor extension**, copied to a bootstrap directory: VS Code only, Java artifacts inside a Node package.
- **Option B, a Lathe Maven goal invoked by coordinates** (`mvn io.github.ag-libs:lathe-maven-plugin:setup`, the OpenJDK pattern), which resolves the jars, writes `.lathe/` and runs the first sync as a nested Maven build. The discussion leaned towards it.

Both are set aside for the client fetching the two jars with `dependency:copy` and running the build with `-Dmaven.ext.class.path` itself ([Personal activation](#personal-activation)): it serves every editor like B, resolves through the user's Maven like B, and needs no new goal, no nested build and no `aggregator` handling (a goal invoked by coordinates runs once per module otherwise).
What B offered and this loses, the pre-flight check from inside Maven's JVM, moves to the client ([Detecting and explaining](#detecting-and-explaining)).

### Downloading from a VS Code extension

Checked 2026-10-27:

- **Allowed and common.** No Marketplace rule against runtime downloads was found; Microsoft's own Remote-SSH and C# extensions download on first activation ([vsmarketplace #211](https://github.com/microsoft/vsmarketplace/discussions/211)), and the [remote development guide](https://code.visualstudio.com/api/advanced-topics/remote-extensions) tells authors to "include (or dynamically acquire)" binaries.
  Extensions run with VS Code's full rights, network included ([extension runtime security](https://code.visualstudio.com/docs/configure/extensions/extension-runtime-security)).
- **Metals splits it the same way.** The [Metals extension](https://marketplace.visualstudio.com/items?itemName=scalameta.metals) ships a small Coursier launcher and downloads the Metals server from Maven Central on startup, with repository, mirror and proxy settings ([proxy docs](http://scalameta.org/metals/docs/troubleshooting/proxy/)).
  Offline and corporate users report friction ([metals-vscode #1617](https://github.com/scalameta/metals-vscode/issues/1617), [metals #7276](https://github.com/scalameta/metals/discussions/7276)).
- **VS Code moved the extension's own files to shipping.** Since VS Code 1.61, platform-specific packages let C/C++ and Terraform drop their first-activation downloads ([vscode #23251](https://github.com/microsoft/vscode/issues/23251), [cpptools #8152](https://github.com/microsoft/vscode-cpptools/issues/8152), [vscode-terraform #905](https://github.com/hashicorp/vscode-terraform/pull/905)),
  because an installed extension that is still installing itself is a poor experience, every downloader must copy VS Code's proxy handling, and offline installs break.

Researched for alternative A (2026-10-27). Under C the extension ships only text templates and the wrapper downloads Maven, the same split Metals uses: the launcher ships, the heavy parts download.


## Survey: how other tools handle a folder with no build

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
