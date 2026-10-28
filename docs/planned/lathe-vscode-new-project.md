# Lathe — New Java Project (VS Code)

**Status:** proposed (2026-10-23); revised 2026-10-24 after a review of the whole novice journey (§2). Not built.

## 1. Goal

A beginner with VS Code and a JDK, but no project and no Maven, gets to a working Lathe editor with one command:
code intelligence first, then `▶ Run` on `main` and a green test in the Test Explorer.

Lathe only works once a build has produced `.lathe/` with the extension loaded.
Without a project that hurdle is the whole experience: the user opens a `Main.java`, nothing happens, and the "run the build" nudge assumes a build they don't have.
**Lathe: New Java Project** writes a minimal Lathe-enabled Maven project, opens it, runs the first build, and starts Lathe when the build is done.

## 2. The novice journey and where this fits

| Step | Covered by |
|---|---|
| Install the extension | [VS Code client](lathe-vscode-client.md) M2: Marketplace and Open VSX. Novices won't install a `.vsix` from GitHub, so this command matters most once M2 ships. |
| Have a JDK | §4 here: detected and explained, never installed. |
| Have Maven | Not needed: the generated project carries the Maven Wrapper (§5). |
| Start a new project | This design. |
| Open an existing project | [`:LatheInit`](../potential/lathe-init.md) (personal mode), potential. |
| First build | §6 here, and the client's first-run UX (§7 there) for existing projects. |
| Lathe starts | Picked up without a reload (§7), shared with the client's first-run UX. |
| Run `main`, run tests | [Run and Test in VS Code](lathe-vscode-run-test.md), phase 3. |
| A second Java extension | §8 here. |

## 3. Scope

- **VS Code only.** No server, Neovim or Maven-plugin changes.
- **Maven only.** Gradle follows once [Gradle support](lathe-gradle-support.md) exists.
- **No archetypes and no `mvn` at generation time.** The extension writes the files itself:
  `maven-archetype-quickstart` is interactive and its defaults are dated.
- **Lathe installs nothing system-wide.** No JDK download, no Maven installation, no changes outside the new project and VS Code's own state.

The feature has to live in the extension.
The cached server under `~/.cache/lathe/servers/<version>` is only unpacked by the first Lathe build,
so on a fresh machine the extension is the only Lathe code on disk.

## 4. Pre-flight: a JDK 17 or newer

Lathe's extension runs inside Maven, so the JVM that runs Maven must be Java 17 or newer, whatever release the project targets.
Before writing anything, the command looks for the JDK the wrapper will use, in the wrapper's own order: `JAVA_HOME`, then `java` on `PATH`.

- It reads the version from the JDK's `release` file (`JAVA_VERSION="21.0.4"`), next to `bin/`, instead of starting a process and parsing its output.
  For `java` on `PATH`, the JDK home is the real path of the executable, two levels up.
- **None found**, or **older than 17**: a modal explains what is missing, in one sentence, with buttons
  *"How to install a JDK"* and *"Retry"*.
  The first opens a short "Installing a JDK" section this command adds to the [installation guide](../guide/installation.md) (§11): a few common distributions and how to set `JAVA_HOME`.
  Nothing is written until the check passes.
- `JAVA_HOME` set to an old JDK while `PATH` has a newer one: the message names both, because the wrapper would use `JAVA_HOME`.

Downloading a JDK was considered and left out: it is a system-level install with licence and update questions, and VS Code users who need one already get guided by the JDK pages of the Java extension pack and the vendors.

## 5. Generated project

```
<name>/
  .mvn/extensions.xml                    lathe-maven-extension, pinned to the extension's own version
  .mvn/wrapper/maven-wrapper.properties  the Maven version the wrapper downloads
  mvnw, mvnw.cmd                         the Maven Wrapper scripts ("only-script" type: no jar in the project)
  pom.xml                                com.example:<name>:1.0-SNAPSHOT, release 17, JUnit 5, Surefire
  .gitignore                             target/, .lathe/
  .vscode/extensions.json                recommends Lathe; marks Red Hat's Java extensions unwanted (§8)
  src/main/java/com/example/Main.java
  src/test/java/com/example/MainTest.java
```

- **Lathe version:** taken from `context.extension.packageJSON.version`.
  The release profile stamps the release version into the `.vsix` through `vsce`, so the extension and the Lathe build always match.
  A dev build pins `0.1.0-SNAPSHOT`, which resolves from `~/.m2` after `mvn install`.
- **Maven version:** the newest release Lathe's CI covers (3.10.0 today), so the wrapper downloads a Maven that is known to work.
  Maven 3.10 also reads `~/.m2/extensions.xml`, which [`:LatheInit` personal mode](../potential/lathe-init.md) may build on.
- **Maven Wrapper:** the novice needs no Maven installation, the build uses a known Maven version, and the scripts can be run by their full path, as the portability rules require.
  The "only-script" wrapper downloads Maven on first use (with `curl`/`wget` on Linux and macOS, PowerShell on Windows); no binary is committed.
  `mvnw` is written with mode `0755`, so the user can also run `./mvnw` in a terminal.
- **Java release:** fixed at 17, Lathe's floor, rather than detected from the JDK.
- **`Main.java`** prints a greeting; **`MainTest.java`** has one passing test, so the first run of each shows success.

## 6. User flow

1. Command palette → **Lathe: New Java Project** (`lathe.newProject`).
   It works with no folder open: on engine `^1.84`, a contributed command activates the extension, so `activationEvents` is unchanged.
2. The JDK check (§4).
3. An input box asks for the project name, validated as a safe directory name and Maven artifactId.
   The package is fixed to `com.example`; fewer questions suit beginners, and a rename is one refactoring away.
4. A folder dialog asks for the parent folder.
   If `<parent>/<name>` already exists, the command stops with an error and never overwrites anything.
5. The extension writes the project and opens it with `vscode.openFolder`.
6. After the window reloads, the extension sees a project this command created that has no `.lathe/` yet.
   (It recognizes the project by a one-time marker in `globalState`, not by a file in the project.)
   It runs the first build as a VS Code **Task** named "Lathe: first build": the wrapper by its full path, with the goal **`test`**.
   - `test`, not `process-test-classes`: Lathe records how tests are launched only when Surefire runs them,
     so after `process-test-classes` the Test Explorer would answer "no test capture" for `MainTest`.
     `test` also ends the first build with a passing test, which is a better first impression.
   - A Task, not a bare terminal: its exit code drives the next step.
     A failure shows "The first build failed" with *"Show output"* and *"Retry"*.
   - The first run downloads Maven and the dependencies; the Task's terminal shows that, and a progress notification says "Lathe: first build (downloads Maven and dependencies)".
7. Lathe starts by itself when the build has written `.lathe/` (§7), and `Main.java` opens in the editor.

## 7. Lathe starts without a reload

Today the extension only looks for `.lathe/lathe-launcher.*` when a folder opens, and the "no launcher" warning tells the user to reload the window.
A novice doesn't know what that means.
The extension instead watches for the launcher with a `FileSystemWatcher` and starts that folder's client as soon as it appears.
This is defense 1 of the client's first-run UX ([VS Code client](lathe-vscode-client.md) §7), built once and used by both paths:
after this command's first build, and after the one-click build on an existing project.

## 8. Other Java extensions

VS Code offers the "Extension Pack for Java" as soon as a `.java` file opens, and a novice who accepts it gets two Java language servers on the same files:
duplicate diagnostics, completions and code actions, and Red Hat's server importing the project as well.
Extensions can't disable each other, so:

- **Generated projects** carry `.vscode/extensions.json` with Lathe in `recommendations` and `redhat.java` and `vscjava.vscode-java-pack` in `unwantedRecommendations`,
  so VS Code doesn't suggest them for this workspace. (Whether that also silences the file-type suggestion for `.java` is to be checked when this is built.)
- **When `redhat.java` is already active** in a Lathe workspace, a one-time notice explains the conflict in one sentence and offers
  *"Show extension"*, which opens the Extensions view on it (`workbench.extensions.search` with `@id:redhat.java`), where *Disable (Workspace)* is one click.
  This applies to every Lathe workspace, not only generated ones; the [installation guide](../guide/installation.md) has the details for users who keep both.

## 9. The first success

After the first build the novice should see Lathe working without looking for it:

- **Now:** diagnostics, completion and navigation in the opened `Main.java`.
- **With [Run and Test in VS Code](lathe-vscode-run-test.md) phase 3:** a `▶ Run` lens above `main`, which prints the greeting in a `Run: com.example.Main` terminal,
  and `MainTest` in the Test Explorer, already passed from the first build's capture.

Until that phase ships, the command is still worth having: it removes the setup hurdle, and the run/test work fills in the rest.

## 10. Code layout (`lathe-vscode`)

| File                            | Change                                                                                                     |
|---------------------------------|------------------------------------------------------------------------------------------------------------|
| `package.json`                  | `contributes.commands` with `lathe.newProject`                                                             |
| `src/newProject.ts` (new)       | The JDK check, the prompts, the first-build Task                                                           |
| `src/projectTemplate.ts` (new)  | Pure `(name, latheVersion, mavenVersion) → {relative path, content, executable}[]`, no `vscode` import, so it is unit-testable |
| `src/jdk.ts` (new)              | Pure JDK lookup and `release`-file parsing, unit-testable with fake JDK homes                              |
| `src/extension.ts`              | Register the command; on activation, start a pending first build; the launcher watcher (§7); the `redhat.java` notice (§8) |
| `test/projectTemplate.test.ts`  | File paths and contents, the version pins, `mvnw` executable, invalid names rejected — in the style of `launch.test.ts` |
| `test/jdk.test.ts`              | `JAVA_HOME` vs `PATH` order, missing JDK, JDK 11 rejected, JDK 17 and 25 accepted, an unreadable `release` file |

The templates are inline strings in `projectTemplate.ts`, not a resource directory: a few small files need no packaging changes.
The wrapper scripts are the exception worth a check: they are long, and vendoring them as resources may read better.

## 11. Docs

- The VS Code cheatsheet under `docs/guide/editors/` gets a short "Start a new project" section, and its install steps point novices at it.
- The [installation guide](../guide/installation.md) gets a short "Installing a JDK" section (it has none today), which the JDK check links to (§4).

## 12. Follow-ups

- **Template build test:** prove the generated project really builds with Lathe, e.g. an invoker fixture produced from the template, so it can't go stale unnoticed.
- **Gradle:** a build-tool choice in the same command, likely wrapping `gradle init`.
- **Existing projects:** [`:LatheInit` personal mode](../potential/lathe-init.md) decides how a novice gets Lathe into a project they didn't create.
