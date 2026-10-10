# Lathe — Sync Launcher (`.lathe/lathe-sync.sh`)

## Status

**In progress.** Slices 1–3 are done for Maven: sync writes the script, the Neovim client runs it, and server and MCP texts name it. Gradle and OpenJDK scripts come with their own work.
Prepares Lathe for [Gradle support](lathe-gradle-support.md) by removing the last place outside the build integration that knows how to run the build.

## Problem

Clients start the server without knowing anything about the build: they run `.lathe/lathe-launcher.sh`, which the build wrote.
Running a sync is different.
Every caller that refreshes `.lathe/` composes a Maven command line itself:

| Caller | What it hard-codes |
|---|---|
| Neovim client (`lathe/sync.lua`) | picks `mvnd` / `./mvnw` / `mvn`, the goal (`process-test-classes`, or `test` to capture tests), `-pl <modules> -am`, `--no-transfer-progress`, `-Dmaven.build.cache.enabled=false` |
| Server | remediation and prompt texts built from `LatheLayout.SYNC_COMMAND` (`mvn process-test-classes`) and `scopedSyncCommand` (`mvn -pl … -amd process-test-classes`) |
| MCP server | `verify_change` returns `suggestedMvn`; tool texts quote `SYNC_COMMAND` |

This has three costs:

- **Gradle would touch every caller.**
  The Gradle design claims the server, MCP server, and clients work unchanged, but each of them would need a second command dialect.
- **The callers already drift.**
  The Neovim client's scoped sync uses `-am` (also build upstream), while the server's scoped text uses `-amd` (also build dependents); each is half of what a scoped sync needs (see Scoped sync).
- **The build runs on the wrong JDK.**
  A client-triggered sync inherits the editor's environment, so `:LatheSync` builds with whatever `JAVA_HOME` the editor has, not the JDK the project builds with (for example, Helidon needs 27 and Dropwizard 25 in the same session).

## Goals

- One entry point, written by the build integration, that every caller runs to sync.
- No build-tool knowledge in the Neovim client, the server, or the MCP server.
- The sync runs on the JDK the project was built with.
- Gradle (and the OpenJDK front-end) add support by writing their own script, with no change elsewhere.

## Non-goals

- **The formatter.**
  The delegated formatter's `%MVN%` token is out of scope; formatting is moving to an in-process, Spotless-based engine on an isolated classloader.
- **The first sync.**
  The script only exists once a build has run, so the first sync stays a documented manual command (`mvn process-test-classes`, later `./gradlew latheSync`).
- **Windows.**
  The script is POSIX `sh`, like the existing launchers.
- **A VS Code sync command.**
  VS Code has none today; adding one later is a client feature built on this contract.

## Design

### The contract

The build integration writes one executable script on every sync:

```
.lathe/lathe-sync.sh [--tests] [module ...]
```

- **No arguments** — refresh the whole workspace (Maven: `process-test-classes`).
- **`--tests`** — also re-capture test launches, without running the tests (Maven: `test -Dlathe.capture.only=true -DfailIfNoTests=false`: the test fork starts and writes `test-launch.json`, but the capture-only filter excludes every test), the mode behind "Sync + capture tests".
- **`module ...`** — a scoped sync of these workspace-relative module paths and what they need (see Scoped sync).
  A build that cannot scope runs a full sync and says so on stderr.
- **`--tests module ...`** — both: a scoped sync that also re-captures test launches for those modules (and, through `-amd`, their dependents).

For Maven:

| Invocation | Runs |
|---|---|
| `.lathe/lathe-sync.sh` | `mvn … process-test-classes` |
| `.lathe/lathe-sync.sh --tests` | `mvn … test -Dlathe.capture.only=true -DfailIfNoTests=false` |
| `.lathe/lathe-sync.sh app core` | `mvn … -pl app,core -am -amd process-test-classes` |
| `.lathe/lathe-sync.sh --tests app` | `mvn … -pl app -am -amd test -Dlathe.capture.only=true -DfailIfNoTests=false` |

- **Visible command.**
  Before running the build, the script prints the exact command it is about to run on stderr, as its first line (`lathe-sync: mvnd --no-transfer-progress … process-test-classes`).
  The tool is only chosen when the script runs, so this is the one accurate place to show it; every caller that shows the build's output shows the command too.
- **Build cache disabled.**
  A sync must run the compile and test tasks, because Lathe's capture happens inside them: a build-cache hit restores outputs without running javac or the test fork, so `.lathe/` would not be refreshed.
  Every script turns the build tool's output cache off for the run.
- **Exit status** is the build's exit status; output is the build's output, unfiltered, so callers can show it on failure as today.
- **Working directory.**
  The script changes to its workspace root itself (`cd "$(dirname "$0")/.."`), so it works from any cwd.
  It is written as a regular file in `.lathe/`, not a link into the cache, because it is per workspace and per build tool.

The name is a `LatheLayout` constant (`SYNC_SCRIPT`), like `LAUNCHER_SCRIPT`.

### The JDK

The script selects the build JDK with the same order as the server launcher, and exports it as `JAVA_HOME` before running the build:

1. `LATHE_JAVA_HOME`, if set;
2. else the recorded `.lathe/java-home` (the JDK the last build ran on);
3. else the inherited environment.

The resolution prologue is shared with the server launcher's generator (`ServerInstaller.javaResolvePrologue`), so the two cannot disagree.
For mvnd this also selects the daemon JDK.

### Maven script

Written by `lathe:sync` (`SyncCoordinator`, next to the workspace manifest and `jvm.args`), and skipped on partial (`-pl`) reactors like them.

```sh
#!/bin/sh
# Generated by lathe:sync — do not edit. Usage: .lathe/lathe-sync.sh [--tests] [module ...]
cd "$(dirname "$0")/.." || exit 1
<JDK prologue: export JAVA_HOME>
goal=process-test-classes
if [ "$1" = "--tests" ]; then goal="test -Dlathe.capture.only=true -DfailIfNoTests=false"; shift; fi
if command -v mvnd >/dev/null 2>&1; then mvn=mvnd
elif [ -x ./mvnw ]; then mvn=./mvnw
else mvn=mvn
fi
scope=
if [ $# -gt 0 ]; then scope="-pl $(echo "$@" | tr ' ' ',') -am -amd"; fi
set -- "$mvn" --no-transfer-progress -Dmaven.build.cache.enabled=false $scope "$goal"
echo "lathe-sync: $*" >&2
exec "$@"
```

The tool is resolved when the script runs, not when it is written, so it follows the machine it runs on (mvnd installed later, a wrapper added).
The flags are today's Neovim flags, moved here: `--no-transfer-progress` drops download chatter, and `-Dmaven.build.cache.enabled=false` turns off the Maven build cache extension when a project uses it, so a cached module is still compiled and captured.

### Scoped sync

A scoped sync is requested after a POM change in some modules.
It needs both directions, and `-am -amd` gives them:

- **`-am`** builds the modules' upstream in the same reactor, so they resolve against fresh upstream classes rather than stale `~/.m2` artifacts.
- **`-amd`** rebuilds the downstream modules, whose captured classpath changes when a POM adds or removes a dependency.

This replaces both of today's halves (the client's `-am`, the server text's `-amd`).

### Other build front-ends

- **Gradle** (`latheSync`, see [Gradle support](lathe-gradle-support.md)) writes a script running `./gradlew --no-build-cache latheSync`; `--tests` and modules map to whatever that design settles on.
  `--no-build-cache` is the Gradle side of the cache rule: without it, a build-cache hit on `compileJava` or `test` skips the task and its capture.
- **OpenJDK** (`lathe-openjdk-maven-plugin:sync`) writes a script that re-runs its own sync goal, pinned: `mvn io.github.ag-libs:lathe-openjdk-maven-plugin:<version>:sync -Dlathe.buildDir=<build dir>`, with the version and build directory recorded when the script is written, so a re-sync never switches Lathe versions or JDK configurations.
  - It **never runs `make`**: building the JDK is the user's call and can take hours, so the script refreshes `.lathe/` from the latest build only.
  - It **skips the JDK prologue**: in OpenJDK mode `.lathe/java-home` is the freshly built JDK, right for the server but not for running Maven, so Maven runs on the inherited environment.
  - `--tests` and modules are accepted and ignored with a stderr note: jtreg capture is not supported, and the whole-JDK descriptor re-read is cheap.
  - Today's client-composed `mvn process-test-classes` fails in a JDK checkout (there is no POM), so this also fixes the sync prompt there.

The JDK prologue is therefore a per-front-end choice, not part of the contract: a front-end uses it when the script runs the project's real build (Maven, Gradle), and skips it when the build tool only hosts Lathe's own goal (OpenJDK).

### Callers

- **Neovim client.**
  `lathe/sync` and `:LatheSync` / `:LatheSyncCaptureTest` run `.lathe/lathe-sync.sh` with `--tests` and the modules from the notification, `cwd` = root.
  The Maven-specific code (`maven_executable`, the goal and flag assembly) is deleted.
  The "syncing…" toast starts with the script invocation and switches to the full build command once the script's first stderr line arrives; the failure buffer shows it as part of the output.
  If the script is missing, the workspace was synced by an older Lathe: the client shows the existing "older Lathe" notice instead of falling back to Maven.
- **Server.**
  The `lathe/sync` notification is unchanged (`captureTests`, `modules`); clients map it to script arguments.
  Remediation texts for a synced workspace (stale modules, scoped sync) name the script invocation (`.lathe/lathe-sync.sh app core`) instead of an `mvn` command; running it shows the full build command.
  `SETUP_REMEDIATION` keeps the build-specific first-sync command, since no script exists yet.
- **MCP server.**
  `verify_change`'s `suggestedMvn` becomes `suggestedSync` (for example `.lathe/lathe-sync.sh app core`), and tool texts reference the script.

## Upgrade

- A workspace synced by an older Lathe has no script; the Neovim client says so (the existing notice), and the next sync writes it.
- `suggestedMvn` is renamed with no alias; the project has no external MCP adopters to keep compatible.

## Slices

1. **Write the script.**
   `lathe:sync` writes `.lathe/lathe-sync.sh` (shared JDK prologue, scoped `-am -amd`).
   Tests: a unit test runs the generated script against stub `mvn`/`mvnd`/`./mvnw` for full, `--tests`, scoped, and combined invocations (tool choice, JDK, cwd, printed command); the invoker IT asserts sync writes it, executable.
2. **Neovim uses it.**
   Delete the Maven code from `lathe/sync.lua`; missing script → notice.
   Tests: headless spec runs a stub script and checks the arguments it receives.
3. **Server and MCP texts.**
   Remediation strings and `suggestedSync` reference the script.
4. **Gradle and OpenJDK** write their own scripts as part of their own work.

## Decisions

- **One script with parameters** — no separate `--tests` wrapper; `--tests` and modules are arguments of `lathe-sync.sh`.
- **The command is shown by the script, not recorded.**
  `workspace.json` gets no display form of the sync command: the tool is chosen at run time, so a recorded form would go stale.
  The script's first stderr line is the full command it runs.
