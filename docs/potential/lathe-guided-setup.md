# Lathe — Guided Setup from the Editor Extension

Status: potential design, not approved for implementation.
It is retained so a future evaluation can reuse the analysis.
It depends on two pieces of the [Multi-OS Design](../planned/lathe-multi-os.md): the build-neutral `SyncCommand` (D3) and the tool resolver (D9).

## Problem / motivation

Today a user must do all of this before the editor shows anything:

1. Install a JDK 21+, and Maven unless the project has a wrapper.
2. Register `lathe-maven-extension` in the build (`.mvn/extensions.xml` or the root `pom.xml`), choosing the version by hand.
3. Run `mvn process-test-classes` in a terminal.
4. Add `.lathe/` to `.gitignore`.
5. Install the editor client and reload.

Step 2 asks a beginner to edit build files they may not own, and most of them stop there.
The [VS Code client](../planned/lathe-vscode-client.md) design (§7) names the result, "install it, nothing happens, uninstall", but its defenses (a run-build button, a walkthrough, a status bar item) assume step 2 is already done.

The audience this targets is a VS Code or Cursor user, often on Windows, who expects a Java extension to work the way Red Hat's does: install it, open the folder, done.
Red Hat's extension reaches that bar natively on every OS by bundling a Java runtime and importing the build into its own model.
Lathe's value is the opposite (the real build is the source of truth), so it needs a way to hook into the real build without the user editing it.

## Goal

1. Install the extension.
2. Open the project folder.
3. Answer one notification: *"Set up Lathe for this Maven project? Your project files won't be modified."* **[Set up] [Learn more] [Not now]**
4. Watch a progress bar run "Checking JDK… → Running Maven (first build)… → Indexing…".
5. Diagnostics and navigation light up, with no reload.

Constraint: **by default, no tracked project file is changed.**

## Sketch

### Two modes

| | Personal mode (default) | Team mode (explicit) |
|---|---|---|
| Who | anyone, including someone in a repo they don't own | someone adopting Lathe for the team |
| Changes to the project | none, except `.lathe/`, which ignores itself | one previewed change to the build registration |
| Lathe version | the extension's | pinned by the project |
| Terminal builds refresh `.lathe/` | only on Maven 3.10+ (user-wide extension), or always on Gradle with an `init.d` script | yes |

### Personal mode: how Lathe gets into a build

The approach differs by Maven version:

- **Maven 3.10+: user-wide core extension.**
  Maven 3.10.0 (GA 2026-09-27) loads core extensions from `~/.m2/extensions.xml` for every build of that user.
  After confirmation, setup writes or merges that file.
  From then on every Maven build turns Lathe on where `.lathe/` exists, including a plain `mvn test` typed in a terminal.
  Lathe stays off everywhere else (no `.lathe/`, or `CI` set), so registering it machine-wide is harmless for other projects.
- **Maven 3.9: `-Dmaven.ext.class.path` on syncs.**
  Lathe's extension needs only two jars at runtime (`lathe-maven-extension`, `lathe-core`); everything else is `provided` by Maven.
  Builds Lathe starts pass `-Dmaven.ext.class.path=<ext.jar><sep><core.jar>`, a standard Maven 3 mechanism since 3.0.2.
  Terminal builds run without Lathe: they don't refresh `.lathe/` but do no harm.
- **Gradle** (after [Gradle support](../planned/lathe-gradle-support.md)): `--init-script <bootstrap>/lathe.init.gradle.kts` on syncs, or, with confirmation, the same script in `~/.gradle/init.d/` so every build refreshes.

The effective Maven version is the one the project actually uses.
That's the wrapper's `distributionUrl` in `.mvn/wrapper/maven-wrapper.properties` if there is one, else `mvn -v`.
A project whose wrapper pins 3.9 gets the 3.9 path even if 3.10 is installed globally.

### Where the jars come from: bundled in the extension

The `.vsix` bundles `lathe-maven-extension`, `lathe-core` and `lathe-install` (which holds `SyncCommand`); all three are small.
On first use they're copied to `~/.cache/lathe/bootstrap/<version>/`.
That copy outlives updates and uninstalls of the editor extension, so a sync run from a terminal keeps working.

Everything else is resolved by **Maven itself**, through the user's `settings.xml`: proxies, mirrors, private repositories.
That includes the compiler shim, `lathe-maven-plugin` and `lathe-junit`, which the extension injects at its own version.
The editor extension makes no network calls of its own, which matters on corporate networks.

### The first sync

There is no `.lathe/lathe-sync.*` yet, and the editor extension must not reimplement Maven resolution (`mvnd` / `mvnw` / `mvnw.cmd` / `mvn.cmd`) in TypeScript.
So it starts the bundled `SyncCommand` directly:

```
java -cp <bootstrap>/lathe-install.jar<sep><bootstrap>/lathe-core.jar \
     io.github.aglibs.lathe.install.SyncCommand \
     --tool maven \
     --activation "-Dmaven.ext.class.path=<bootstrap>/lathe-maven-extension.jar<sep><bootstrap>/lathe-core.jar"
```

`SyncCommand` resolves the Maven executable through the shared tool resolver and runs, for example:

```
mvnw.cmd -Dmaven.ext.class.path=C:\Users\…\bootstrap\0.1.22\lathe-maven-extension.jar;C:\Users\…\bootstrap\0.1.22\lathe-core.jar process-test-classes
```

The path separator (`;` on Windows, `:` elsewhere) is written by Java with `File.pathSeparator`, per the multi-OS rules.

The extension shows the run as a VS Code Task with progress.
It watches for `.lathe/` and attaches without a reload, as in the VS Code client design §7.

### The build records how it was activated

While `lathe:sync` writes `.lathe/lathe-sync.args` (multi-OS D3), Lathe's extension checks how it was loaded; the `-D` value is visible in the session's user properties.

- **Loaded through `maven.ext.class.path`:** it writes that value into the args file as an activation line.
- **Loaded from `pom.xml`, `.mvn/extensions.xml` or `~/.m2/extensions.xml`:** no activation line, because the build activates Lathe by itself.

```
--tool maven
--sync-goal process-test-classes
--activation -Dmaven.ext.class.path=C:\Users\…\bootstrap\0.1.22\lathe-maven-extension.jar;C:\Users\…\bootstrap\0.1.22\lathe-core.jar
```

The exact file shape (an argfile or a small JSON file) belongs to the D3 design.

### Every later sync

- **From the editor:** the server's `lathe/sync` notification becomes *"Build files changed. [Sync]"*, which runs `.lathe/lathe-sync.{sh,cmd}` as a Task.
  No VS Code code handles that notification today.
- **From a terminal or Neovim:** the user runs the same script.
- **In both cases:** the trampoline starts `SyncCommand` with `@.lathe/lathe-sync.args` and re-applies the activation line.

Personal mode therefore adds one field to the sync contract (the activation line), which the build writes and every sync replays.

### Pre-flight checks, with fixes that need no admin rights

- **JDK 21+:** look in `JAVA_HOME`, `PATH` and the usual install locations per OS.
  If none is found, offer, after asking, to download one (for example Eclipse Temurin) into `~/.cache/lathe/jdks/`. That's a zip extraction, so no admin rights on any OS.
  A Lathe-only JDK also serves projects that build on Java 17, since the server needs 21+.
- **Build tool:** prefer the wrapper. For a Maven project without one, offer to download Maven into the cache; it's also a zip.
- **Self-ignoring `.lathe/`:** Lathe writes `.lathe/.gitignore` containing `*`, so the user never edits their own `.gitignore`.
  The [External Content Directory](lathe-external-content-dir.md) note already floats this.

### Team mode: one previewed change, never silent

- It's an explicit command, *"Lathe: Add to project build"*, never a side effect of setup.
- The change goes through VS Code's refactor preview (a `WorkspaceEdit` marked as needing confirmation), so it shows a diff and can be undone.
- Smallest possible change:

| Build | Touches | Never touches |
|---|---|---|
| Maven | creates `.mvn/extensions.xml`, or adds one `<extension>` block to an existing one, or changes only the version if Lathe is already there | `pom.xml` (parents, profiles and user formatting make it fragile to edit) |
| Gradle | nothing: it shows the `plugins { id(…) }` line for `settings.gradle(.kts)` with a copy button and a link that opens the file | Gradle build scripts, because they are code |

- Version bumps (the project pins an older Lathe than the extension expects) use the same preview flow, one line each.
- A committed registration is safe for teammates without Lathe: it is off without `.lathe/`, and skipped when `CI` is set.

### Coexisting with Red Hat Java

Beginners often also install the "Extension Pack for Java", which means duplicate diagnostics and two indexers.
An extension can't disable another extension.
Lathe can detect it and offer one-click workspace settings, plus a short explanation.
This needs careful wording, or it becomes the source of the first bad reviews.

### Feedback

The status bar item and walkthrough are already planned in the VS Code client design §7.
Guided setup adds the setup states to the status bar: "checking JDK", "first build", "indexing".

## Relationship to other designs

- **[Multi-OS Design](../planned/lathe-multi-os.md):** personal mode is a small extension of D3, adding the activation line to `lathe-sync.args`. It reuses the D9 tool resolver (`mvnw.cmd`, `gradlew.bat`) and follows the portability rules for the path separator and the bootstrap cache.
- **[VS Code client](../planned/lathe-vscode-client.md):** this would replace its §7 defenses #1 and #2 with a flow that also performs step 2.
  With the version taken from the `.vsix` in personal mode, the §8 version-drift handshake matters only in team mode.
- **[Gradle support](../planned/lathe-gradle-support.md):** the init script it already plans is the Gradle activation mechanism.
- **Neovim and other clients:** personal mode is client-agnostic once `.lathe/` exists, because the sync scripts carry the activation. Only the first-run flow is per client.

## Open questions

1. **Spike on Maven 3.9:** does `lathe-maven-extension` load and behave correctly through `-Dmaven.ext.class.path`? It's a plain Sisu component with only `lathe-core` at runtime, so this is expected but untested.
2. **Spike on Maven 3.10:** if Lathe is declared in both `~/.m2/extensions.xml` and a project's `.mvn/extensions.xml` with different versions, which wins? Team mode needs a defined winner; the simplest rule is that the project wins and the user-level registration steps aside.
3. **mvnd:** the daemon caches core extensions, and may not honor `-Dmaven.ext.class.path` per invocation. If not, `SyncCommand` should prefer `mvnw`/`mvn` over `mvnd` in Maven 3.9 personal mode.
4. **Maven 3.10 changes:** classpath order moved from depth-first to breadth-first. Lathe records whatever the build produces, so build and editor should agree, but this should be checked on Dropwizard and Helidon.
5. **JDK download policy:** is downloading a JDK acceptable as an offered option (about 200 MB, Temurin's licence permits it), or should setup only link to an installer? Some company policies forbid downloaded runtimes.
6. **Writing to the user's home directory:** `~/.m2/extensions.xml` affects every build of that user. Is one confirmation enough, or should it also be reversible from a command (*"Lathe: Remove from my Maven builds"*)?
7. **Red Hat Java coexistence:** what exactly to offer, and whether to detect it at all in the first version.

## Milestone candidate

Untriaged.
The earliest it makes sense is after multi-OS Phase A (which delivers `SyncCommand`) and alongside the VS Code client's M2 Marketplace / Open VSX publish, whose first-run UX it would replace.
