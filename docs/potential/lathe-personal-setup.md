# Lathe — Personal Setup (Lathe Without Changing the Project)

Status: potential design, **no decisions taken**.
This document records a design discussion held on 2026-10-18: the goals, the facts established, the options considered and their trade-offs, and the open questions.
It is meant to be picked up again later. Where the discussion leaned towards an option, that is noted, but nothing here is approved.

Related designs:
- [Multi-OS Design](../planned/lathe-multi-os.md): the build-neutral `SyncCommand` (D3, decided) and the tool resolver (D9).
- [Wider Maven Support](../done/lathe-wider-maven-support.md): the Maven 3.6.3 floor, the Plexus-descriptor fix, and detect-and-explain for unsupported setups.
- [VS Code client](../planned/lathe-vscode-client.md): §7 first-run UX and §8 protocol handshake.
- [OpenJDK support](../done/lathe-openjdk-support.md): the existing coordinate-invoked front-end this design would reuse.

## Problem / motivation

Today a user must do all of this before any editor shows anything:

1. Register `lathe-maven-extension` in the build (`.mvn/extensions.xml` or the root `pom.xml`), choosing the version by hand.
2. Run a Maven build.
3. Keep `.lathe/` out of git.
4. Install and configure the editor client.

Step 1 asks the user to edit build files they may not own: a company repository, a tutorial, someone else's project.
Most of them stop there.

The audience:
- **VS Code and Cursor users**, often on Windows, who expect a Java extension to work like Red Hat's: install it, open the folder, done.
- **Neovim, Zed and Emacs users**, who would also like to try Lathe without touching the project.
- **Users of older Maven.** Feedback from real users says most of them run older Maven (distribution packages, old wrappers, corporate images). [Wider Maven Support](../done/lathe-wider-maven-support.md) lowers the floor to 3.6.3.

## Prerequisites (agreed in discussion)

- **The user's project already builds with Maven on their machine.** Lathe does not install JDKs or Maven, and doesn't fix broken builds.
- **Maven 3.6.3 or newer**, per Wider Maven Support.
- **Maven must run on Java 21 or newer when Lathe is involved.** Lathe's own code stays Java 21+; a JDK 17 support request was declined.
  The project can still *target* Java 8, 11 or 17 (`release` / `source` / `target`).
  Only the JVM that *runs* Maven matters, because the extension and compiler shim run inside it.

Consequently, pre-flight is about **detecting and explaining**, not installing.

## Two modes

| | Personal mode | Team mode |
|---|---|---|
| Who | anyone, including someone in a repo they don't own | someone adopting Lathe for the team |
| Changes to the project | none, apart from `.lathe/`, which ignores itself | one previewed change to the build registration |
| Lathe version | chosen by the client or the setup command | pinned by the project |
| Terminal builds refresh `.lathe/` | only with a user-wide registration (Maven 3.10+), see below | yes |

### Team mode (as discussed)

- An explicit action (*"Add Lathe to the project build"*), never a side effect of setup.
- The change is previewed; in VS Code, a `WorkspaceEdit` that needs confirmation and can be undone.
- Smallest change only:

| Build | Would touch | Would never touch |
|---|---|---|
| Maven | create `.mvn/extensions.xml`, or add one `<extension>` block, or change only the version | `pom.xml` (parents, profiles and user formatting make it fragile to edit) |
| Gradle | nothing: show the settings-plugin line with a copy button | build scripts, because they are code |

- A committed registration is safe for teammates without Lathe: Lathe is off without `.lathe/`, and skipped when `CI` is set.

## How Lathe can get into a build without project changes

| Mechanism | Maven | Scope | Notes |
|---|---|---|---|
| `-Dmaven.ext.class.path=<jars>` | 3.0.2+ | one command | Works for every supported Maven. Only builds Lathe starts are activated. **Not yet spiked with Lathe.** |
| `~/.m2/extensions.xml` | **3.10.0+** (GA 2026-09-27) | every build of the user | Terminal builds refresh `.lathe/` too. Writes to the user's home directory, after asking. A wrapper pinned to 3.9 or older ignores it. |
| `${maven.home}/conf/extensions.xml` | 3.10.0+ | one Maven installation | Too invasive for a default. |
| `--init-script` / `~/.gradle/init.d/` | Gradle | one command / every build | The Gradle analog, from the [Gradle design](../planned/lathe-gradle-support.md). |

With real users mostly on older Maven, `-Dmaven.ext.class.path` would be the main path, and `~/.m2/extensions.xml` an optional upgrade on 3.10+.
On Maven before 3.9, either path only works once the extension is registered through a Plexus descriptor (Wider Maven Support D1). Under the old Sisu index it is silently dropped.

**How the activation would persist across syncs:** the build records how Lathe was loaded (the `-D` value is visible in the session's user properties) as an activation line in `.lathe/lathe-sync.args`. `SyncCommand` replays it on every later sync, whether started from an editor, a terminal or Neovim.

```
--tool maven
--sync-goal process-test-classes
--activation -Dmaven.ext.class.path=<cache>/bootstrap/0.1.22/lathe-maven-extension.jar<sep><cache>/bootstrap/0.1.22/lathe-core.jar
```

`<sep>` is the OS path separator, written by Java. The file shape is a detail of multi-OS D3.

## Where the setup entry point would live — two options

### Option A — jars bundled in the editor extension

The `.vsix` bundles `lathe-maven-extension`, `lathe-core` and `lathe-install`, copies them to `~/.cache/lathe/bootstrap/<version>/`, and starts `SyncCommand` directly for the first sync.

- **Pros:** works offline for the bootstrap step; the extension controls the version exactly.
- **Cons:**
  - VS Code-only. Neovim, Zed and Emacs would need their own copies or nothing.
  - The extension downloads nothing itself, but it ships Java artifacts inside a Node package.
  - To start the first `SyncCommand`, the extension must find a JDK 21 itself.

### Option B — a Maven goal invoked by coordinates (the OpenJDK pattern)

```
mvn io.github.ag-libs:lathe-maven-plugin:setup
```

or with `./mvnw` / `mvnw.cmd`, and optionally pinned (`…:0.1.22:setup`). The goal would:

1. Resolve `lathe-maven-extension` and `lathe-core` at its own version **through the user's Maven** (`settings.xml`, proxies, mirrors), the same way `ServerInstaller` resolves the server today, and copy them to the bootstrap cache.
2. Write `.lathe/`: the sync trampoline, `lathe-sync.args` with the activation line, and `.lathe/.gitignore` (`*`).
3. Run the first sync as a **nested Maven build** with the activation.
4. Optionally, on Maven 3.10+, offer `~/.m2/extensions.xml` instead.

- **Pros:**
  - One mechanism for every editor and a bare terminal; no client bundles anything or contains Java-side logic.
  - The first command runs in a shell, which already resolves `mvn` to `mvn.cmd` on Windows.
  - Downloads go through the user's Maven.
  - The goal runs inside Maven, so it can check Maven's JVM and stop early with the clear "Maven must run on Java 21+" message. That gives every client the same pre-flight check.
  - Team mode is the same step without the activation line.
- **Cons:** a nested Maven build, and a few extra seconds on first setup only.
- **Unknowns:**
  - Inside a Maven project, a goal invoked by coordinates runs once **per module** unless it is an `aggregator`.
  - The nested build hasn't been tried.

**The discussion leaned towards B.** The main reason: it's the pattern the OpenJDK front-end already ships, and it serves all clients. Not decided.

### Synergy with the OpenJDK front-end

The OpenJDK front-end (`lathe-openjdk-maven-plugin`) already implements most of option B:

| Personal-setup need | Already in the OpenJDK front-end |
|---|---|
| Set up without the project registering anything | `@Mojo(name = "sync", requiresProject = false)`, invoked by coordinates, documented in `docs/guide/openjdk.md` |
| Resolve Lathe artifacts through the user's Maven | `ServerInstaller` resolves via `RepositorySystem` with the session's repositories |
| Install the server and write launchers | `ServerInstaller` + `LauncherCopier` (`lathe-install`) |
| A re-sync that keeps the version | its `lathe-sync.sh` re-invokes the goal pinned to `<version>` |
| Keep `.lathe/` out of git without editing tracked files | `.git/info/exclude` in the docs (`.lathe/.gitignore` would automate it for both front-ends) |
| Front-end-agnostic clients | Neovim, VS Code and Zed attach to a JDK checkout like any other workspace |
| `CI` / `lathe.disabled` kill switches | `LatheFlags.isDisabled()` in the goal |

The essential difference: OpenJDK reads a *finished* `make` build, while Maven's compiler parameters exist only while javac runs. So for Maven the shim must be loaded *into* the build, hence the nested build with activation.

### The sync entry converges across front-ends

With `SyncCommand` (multi-OS D3), every front-end's sync becomes an entry in `.lathe/lathe-sync.args`:

| Front-end | Sync entry |
|---|---|
| OpenJDK | `goal io.github.ag-libs:lathe-openjdk-maven-plugin:<v>:sync -Dlathe.buildDir=…` |
| Maven, team mode | `build process-test-classes` (the build activates Lathe itself) |
| Maven, personal mode | `build process-test-classes` + `activation -Dmaven.ext.class.path=…` |
| Gradle | `build latheSync --no-build-cache` (+ `--init-script` in personal mode) |

## Per-editor experience (as discussed)

### VS Code / Cursor

| # | Moment | What the user sees | Underneath |
|---|---|---|---|
| 1 | Install | Marketplace (VS Code) or Open VSX (Cursor). The listing's first line: *"Java support from your real Maven build. Sets itself up without changing your project. Maven 3.6.3+."* | — |
| 2 | Open a folder | For a Maven project without Lathe: **one** notification, *"Set up Lathe for this project? Your project files won't be changed."* [Set up] [Not now] [Learn more]. Silent for non-Maven folders. "Not now" is remembered per folder. | Finds the reactor root, walking up from a submodule if needed. Checks for `.lathe/`, a team registration and Red Hat Java. |
| 3 | Pre-flight (seconds) | Silent unless something's wrong; otherwise a notification with the fix (see [Detecting and explaining](#detecting-and-explaining)). | Maven found; Maven ≥ 3.6.3; Maven runs on Java 21+. A setting such as `lathe.java.home` would let Lathe's own syncs use a different JDK. |
| 4 | First sync (a minute to many minutes) | Status bar "Lathe: first build…", plus a terminal task with Maven's output. | Option A or B above. `process-test-classes` only: no tests are executed. |
| 5 | Result | Success: "Indexing…" → "Lathe: ready", attached without a reload. `[lathe]` warnings become notifications with the fix. Failure: *"Your Maven build failed (not Lathe). [Open log]"* | — |
| 6 | Daily work | Diagnostics, completion, navigation, refactorings. Cursor's AI also sees Lathe's diagnostics. A POM change or `git pull`: *"Build files changed. [Sync]"* | No VS Code code handles the `lathe/sync` notification today. |
| 7 | Terminal builds | Nothing changes. | Without a user-wide registration, `mvn test` doesn't refresh `.lathe/`. Stale-source handling and the sync prompt cover it. |
| 8 | Extension update | *"Lathe updated. [Sync now] to switch versions."* | The next sync installs the matching server; the old one runs until then. |
| 9 | Leave | *"Lathe: Remove from this project"* deletes `.lathe/`. | Plus `~/.m2/extensions.xml` if the user opted into it. |

### Neovim (`lathe.nvim`)

- `:LatheSetup` would run the setup in a terminal split. The existing nudge for a Maven project without `.lathe/` would offer it.
- After setup, nothing changes: `:LatheSync`, attach via the launcher.
- The existing protocol handshake already detects version mismatch; its message would point to `:LatheSetup`.

### Zed and Emacs

- Settings only for Zed; no Lathe Zed extension, per the earlier decision.
- The docs would say: run the setup one-liner in the editor's terminal, then add the documented settings snippet that points at `.lathe/lathe-launcher.sh` / `.cmd`.
- Re-sync and updates: `.lathe/lathe-sync.*` and re-running the one-liner.

### Plain terminal and AI agents

The one-liner, then `.lathe/lathe-sync.*`. The MCP launcher works from the same `.lathe/`.

Option B serves every row in this section with one mechanism. Option A serves only VS Code and Cursor.

## Detecting and explaining

Building on [Wider Maven and JDK Support](../done/lathe-wider-maven-support.md) D3 and D5 (D3, shipped, stops an unsupported build with one message):

| Problem | Where it would be detected | Message (draft) |
|---|---|---|
| No Maven found | client, before setup | *"Lathe uses your Maven build, but no `mvnw` or `mvn` was found."* |
| Maven below 3.6.3 | client / setup goal (wrapper `distributionUrl` or `mvn -v`); inside Maven, the build stops (D3) | *"Lathe needs Maven 3.6.3+; this project uses 3.5.4. Upgrade the wrapper: …"* |
| Maven runs on Java < 17 | setup goal (it runs inside that JVM); client via `mvn -v` | *"Lathe needs Maven to run on Java 17+ (your project can still target older Java). Maven here runs on Java 11 (JAVA_HOME=…)."* |
| Compiler plugin < 3.6.2 | inside Maven, the build stops (D3) | *"[lathe] This build is older than Lathe supports: maven-compiler-plugin 3.1 (core) is older than 3.6.2: pin a newer version… To build without Lathe, add -Dlathe.disabled=true"* |
| Build ran, but no `.lathe/` | client, after the build | *"If you already built: Lathe needs Maven 3.6.3+ and prints `[lathe]` lines in the build log; if there are none, the extension didn't load."* |

**Settled 2026-10-18:** Lathe targets Java 17, so Maven on JDK 17 loads the extension normally; Maven on an older JDK cannot load it (class-version error before any Lathe code runs), which the setup goal and the client's `mvn -v` check above report.

## Other topics discussed

- **Red Hat Java coexistence:** beginners often also install the Extension Pack for Java, which gives duplicate diagnostics and two indexers. An extension can't disable another. The idea: detect it once, explain in plain words, and offer "Use Lathe here" (workspace settings that quiet jdtls for this folder) or "Keep both".
- **What the first sync captures:** `process-test-classes` (editing only, fast) versus also capturing tests (`--tests`) for run/test. The lean was: editing first; test capture on first "Run test", or as a checkbox.
- **Update policy:** prompt rather than auto-sync, since a sync is a full Maven build.
- **Cursor extras, to check:** Lathe's diagnostics reach Cursor's AI through the language server. Whether an extension can register Lathe's MCP server for Cursor's agent, as recent VS Code allows, is unverified.
- **Windows:** the one-liner is the same on every OS apart from `mvnw` vs `mvnw.cmd`. The scripts the setup writes follow the multi-OS rules.

## Open questions

1. **Option A or B** for the setup entry point, or B with a VS Code UI on top.
2. **Goal name:** `setup`, `personal`, or a flag on an existing goal (`init` already exists as a lifecycle goal).
3. **Versionless invocation in docs:** Maven 3 resolves the latest release (with a warning). Show the version (it goes stale) or not?
4. **Should the setup goal run the first sync itself, or prepare and print the next command?**
5. **The default activation:** per-run `-Dmaven.ext.class.path` only, with `~/.m2/extensions.xml` offered on 3.10+ as an opt-in ("Also refresh Lathe on your terminal builds?")?
6. **Version precedence on Maven 3.10:** Lathe in both `~/.m2/extensions.xml` and the project's `.mvn/extensions.xml` at different versions; which wins? One proposal: the project wins.
7. **mvnd:** does it honor `-Dmaven.ext.class.path` per invocation, given that it caches core extensions per daemon? If not, fall back to `mvnw` / `mvn` in personal mode.
8. **Removal:** a `teardown` goal and command, or "delete `.lathe/`" in the docs?
9. **Red Hat Java:** what exactly to offer, and whether to detect it in a first version.
10. **First-sync scope and update policy** (see above).

## Spikes identified (not yet run)

All of them fit into one session in a sandbox like the 2026-10-18 Maven 3.6.3 spike:

- **(a)** Activation via `-Dmaven.ext.class.path` with the Plexus-descriptor jar on Maven 3.6.3 and 3.9.16.
- **(b)** Maven on JDK 17 with Lathe registered: what does the user see?
- **(c)** An `aggregator` setup goal invoked by coordinates inside a Maven project, on 3.6.3 and 3.9.16.
- **(d)** The nested build started from that goal: stdio, exit code, Ctrl-C.
- **(e)** Version precedence between `~/.m2/extensions.xml` and `.mvn/extensions.xml` on Maven 3.10.0.

## Milestone candidate

Untriaged.
The earliest it would make sense: after Wider Maven Support slice 1 (the extension loads on old Maven) and multi-OS Phase A (`SyncCommand`), and alongside the VS Code client's Marketplace / Open VSX publish.
