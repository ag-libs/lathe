# Lathe — New Java Project (VS Code)

**Status:** proposed (2026-10-23), not built.

## 1. Goal

A beginner with VS Code, a JDK and Maven, but no project, gets to a working Lathe editor with one command.

Lathe only works once a build has produced `.lathe/` with the extension loaded.
Without a project that hurdle is the whole experience: the user opens a `Main.java`, nothing happens, and the "run the build" nudge assumes a build they don't have.
**Lathe: New Java Project** writes a minimal Lathe-enabled Maven project, opens it, and runs the first build.

## 2. Scope

- **VS Code only.** No server, Neovim or Maven-plugin changes.
- **Maven only.** Gradle follows once [Gradle support](lathe-gradle-support.md) exists.
- **No archetypes and no `mvn` at generation time.** The extension writes the files itself:
  `maven-archetype-quickstart` is interactive and its defaults are dated.

The feature has to live in the extension.
The cached server under `~/.cache/lathe/servers/<version>` is only unpacked by the first Lathe build,
so on a fresh machine the extension is the only Lathe code on disk.

## 3. User flow

1. Command palette → **Lathe: New Java Project** (`lathe.newProject`).
   It works with no folder open: on engine `^1.84`, a contributed command activates the extension, so `activationEvents` is unchanged.
2. An input box asks for the project name, validated as a safe directory name and Maven artifactId.
   The package is fixed to `com.example`; fewer questions suit beginners, and a rename is one refactoring away.
3. A folder dialog asks for the parent folder.
   If `<parent>/<name>` already exists, the command stops with an error and never overwrites anything.
4. The extension writes the project and opens it with `vscode.openFolder`.
5. After the window reloads, the extension sees a project this command created that has no `.lathe/`.
   It opens a terminal titled "Lathe: first build" and runs `<full path to mvn> process-test-classes`.
   When the build finishes, the user reloads the window, as the existing "no launcher" warning already says.
   - The build runs after opening, not before, so the user watches it inside their new project window.
   - The extension recognizes the project by a one-time marker in `globalState`, not by a file in the project.

## 4. Generated project

```
<name>/
  .mvn/extensions.xml        lathe-maven-extension, pinned to the extension's own version
  pom.xml                    com.example:<name>:1.0-SNAPSHOT, release 17, JUnit 5, surefire
  .gitignore                 target/, .lathe/
  src/main/java/com/example/Main.java
  src/test/java/com/example/MainTest.java
```

- **Version pin:** taken from `context.extension.packageJSON.version`.
  The release profile stamps the release version into the `.vsix` through `vsce`, so the extension and the Lathe build always match.
  A dev build pins `0.1.0-SNAPSHOT`, which resolves from `~/.m2` after `mvn install`.
- **Java release:** fixed at 17, Lathe's floor, rather than detected from the JDK.

## 5. Finding Maven

- Look up `mvn` on `PATH` (`mvn.cmd` on Windows) and run it by its full path, per the portability rule against bare tool names.
- If it isn't found, the files are still written, and a message says "Maven 3.6.3+ not found on PATH" with a link to the installation guide.
  The user can run the build later.

## 6. Code layout (`lathe-vscode`)

| File                            | Change                                                                                                     |
|---------------------------------|------------------------------------------------------------------------------------------------------------|
| `package.json`                  | `contributes.commands` with `lathe.newProject`                                                             |
| `src/newProject.ts` (new)       | The prompts, the Maven lookup, the first-build terminal                                                    |
| `src/projectTemplate.ts` (new)  | Pure `(name, latheVersion) → {relative path, content}[]`, no `vscode` import, so it is unit-testable       |
| `src/extension.ts`              | Register the command; on activation, start a pending first build                                           |
| `test/projectTemplate.test.ts`  | File paths and contents, the version pin, invalid names rejected — in the style of `launch.test.ts`        |

The templates are inline strings in `projectTemplate.ts`, not a resource directory:
five small files need no packaging changes.

## 7. Docs

- The VS Code cheatsheet under `docs/guide/editors/` gets a short "Start a new project" section.

## 8. Follow-ups

- **Template build test:** prove the generated `pom.xml` really builds with Lathe, e.g. an invoker fixture produced from the template, so it can't go stale unnoticed.
- **Gradle:** a build-tool choice in the same command, likely wrapping `gradle init`.
