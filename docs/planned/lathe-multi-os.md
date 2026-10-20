# Lathe — Multi-OS Design

## Status

**Proposed; A0 and A3 partly done; D4 (no links) done; R1 enforced.** Lathe today is developed on Linux, tested in CI on Linux and macOS, and does not run natively on Windows.
This document defines how Lathe becomes **portable by construction**:
OS differences are confined to a small, named platform edge;
everything else is OS-neutral, and checks enforce it.
Windows 10 and 11, with Cursor (through the VS Code extension) and Zed as its target clients (decided 2026-10-19), is the first platform added on top of it.

It is based on a read-only audit of the code done on 2026-10-18.
Nothing in it has been run on Windows yet.
Claims about Windows behavior are reasoned from the code and documented platform behavior, and the [B0 spike](#b0--spike-on-a-windows-ci-runner) checks the risky ones.

It supersedes the Windows non-goal notes in
[Sync Launcher](lathe-sync-launcher.md), [Workspace Java Home](../done/lathe-workspace-java-home.md),
[Standalone Neovim Plugin](../done/lathe-standalone-nvim-plugin.md) and
[Shared Workspace Server](../potential/lathe-shared-workspace-server.md),
and it changes the [Workspace Contract](../lathe-workspace-contract.md) (launcher files, the "executable" check, persisted relative paths).

## Goal

A Java developer on **Linux, macOS, Windows 10 or Windows 11** gets the same Lathe:
the same features, the same setup (Maven extension, one build, open the reactor root) and the same `.lathe/` contract.
Adding or maintaining an OS should mean touching only the platform edge, never the server's analysis code or the build integration.

## Principle — portable by construction

An earlier draft of this document followed *"the smallest change set that makes Windows work"*.
That gets one more OS running, but it leaves OS assumptions scattered through the code,
and the next Linux-only change silently breaks Windows again, because nobody on the team uses Windows daily.

The principle here is different:

1. **One portable core, one thin platform edge.** Code either is OS-neutral or lives in a named edge component ([below](#architecture--portable-core-and-platform-edge)). Nothing in between.
2. **Rules, not fixes.** Each class of OS bug the audit found becomes a rule ([R1–R8](#rules)), and every rule has an enforcement mechanism that runs on Linux CI.
3. **Behavior lives in Java, not in scripts.** Generated scripts are trampolines with as little logic as possible, so behavior is written and tested once.
4. **Every supported OS runs in CI.** A platform without a CI row is not supported.

Most of the work this needs is also a fix for real Linux and macOS bugs (paths with spaces or `@`, CRLF formatting, symlink fragility), so it pays off before Windows ships.

## Supported platforms

| OS | Versions | Script dialect | CI runner | Status today |
|---|---|---|---|---|
| Linux | any with Java 17+ | POSIX `sh` | `ubuntu-latest` | supported, tested |
| macOS | 13+ (Intel, Apple silicon) | POSIX `sh` | `macos-latest` | supported, tested in CI (JDK 25 row) |
| Windows | 10 (1903+), 11 (x64, arm64) | `cmd` batch | `windows-latest` | **not working** |

Common to all: Java 17+, Maven 3.6.3+ (both floors from [Wider Maven and JDK Support](../done/lathe-wider-maven-support.md)), no administrator rights, no extra shell or toolchain (no Git Bash, MSYS2, Cygwin or WSL on Windows).

Windows 10 is a full target, not legacy.
Microsoft ended its mainstream support in October 2025, but in mid-2026 it still ran on about 28–30% of desktop Windows PCs (StatCounter), and companies, where developer laptops live, upgrade later than consumers.
Version 1903 is the floor because that is where the UTF-8 console code page became reliable; older builds are out of support anyway.

## Architecture — portable core and platform edge

**Portable core: may not know which OS it runs on.**
The language server's analysis, the compiler shim, the Maven plugin's sync logic, the MCP server, the test runner,
every `.lathe/` contract file and the cache layout.
These use only portable Java APIs and follow the [rules](#rules).

**Platform edge: the only code allowed to branch on OS.**

| Edge | Owner | What differs per OS |
|---|---|---|
| E1 `Platform` | `lathe-core` | The single OS test, the script dialect and file extensions. Nothing else calls `os.name`. |
| E2 Script renderers | `lathe-install`, `lathe-maven-plugin` | Each generated script is one model, rendered as POSIX `sh` or `cmd`. |
| E3 Tool resolution | `lathe-core` | Starting the recorded build tool and user-configured commands from the server (`.cmd` / `.bat` explicitly), and moving long argument lists into argfiles. |
| E4 Filesystem quirks | `lathe-core` `FileUtil` | Retrying transient Windows locks, clearing read-only before replacing a file. |
| E5 Client spawn | each editor client | Choosing `lathe-launcher.sh` or `.cmd`, and how to start it (`cmd.exe /d /s /c` on Windows). |

The edges are plain classes and `if`s, not interfaces: each has exactly one implementation that contains the branch.

## Rules

| Rule | Statement | Enforced by |
|---|---|---|
| **R1 Portable names** | Every file and directory Lathe writes has a name legal on every OS: none of `< > : " \| ? *`, no trailing dot or space, no reserved names (`CON`, `NUL`, …), and a bounded total path length. | The **portable-names guard** in the invoker checks walks `.lathe/` and the test cache. |
| **R2 No links, no exec bit** | Lathe creates no symlinks or hard links, and no reader depends on the executable bit. Clients test that a launcher *exists*, not that it is executable. | `forbiddenapis` bans `Files.createSymbolicLink` / `createLink`; the contract wording changes. |
| **R3 Paths in data** | Absolute paths are OS-native and machine-local. Relative paths that are persisted (`moduleRel`, stamp keys, resource names, run configs) are `/`-separated on every OS. | The compact constructors of the schema records normalize them (the existing "records validate their invariants" rule). |
| **R4 URIs** | Every URI that enters from a client is canonicalized once, at the protocol boundary. Internally, a file has exactly one URI string. | A single `LatheUri.canonical`, plus a test feeding VS Code-style encoded URIs through every entry point. |
| **R5 Text** | Files are read and written as UTF-8 explicitly. Generated files have a fixed line ending per type (`.cmd` CRLF; `.sh`, JSON and argfiles LF). Edits to a user's document preserve that document's line ending. | `forbiddenapis` `jdk-unsafe` signatures (default-charset APIs); CRLF cases in the formatter and code-action tests. |
| **R6 Processes** | No process is started from a bare tool name or with an unbounded inline argument list. Everything goes through E3. | `forbiddenapis` bans `new ProcessBuilder` and `Runtime.exec` outside E3 (`@SuppressForbidden` there). |
| **R7 Thin scripts** | A generated script only resolves the JDK and starts one recorded program: Java with an argfile, or for sync the tool that ran the build ([D3](#d3--the-sync-script-runs-the-tool-that-ran-the-build)) with a `--tests` switch and a per-module flag. Every choice (which tool, which goals) is made in Java when the script is written. | Code review; the renderers are golden-tested per dialect. |
| **R8 CI per OS** | Each supported OS has a CI row that builds, runs the portable test subset and starts the server. | `.github/workflows/ci.yml` matrix. |

The enforcement is the point of this design.
Linux CI fails on an R1, R2, R5 or R6 violation before any Windows run, so a developer who never uses Windows still can't break it in those ways.

## Current state

### Violations found by the audit

The Java core is mostly portable already.
Launch and capture use `File.pathSeparator`, javac locations come from lists of `Path`, binary names use the filesystem separator, and the lock is a plain marker file.
The violations are concentrated:

| Rule | Finding | Location | Effect on Windows | Also affects Linux/macOS |
|---|---|---|---|---|
| R2 | **Fixed by [D4](#d4--no-links-on-any-platform).** Workspace launchers and the cache's `latest` pointer were **symlinks**. | `LauncherCopier` now copies them | `lathe:sync` failed without admin rights or Developer Mode | — |
| R2 | The contract and clients require the launcher to be **executable**. | `lathe-workspace-contract.md`, Neovim client | No Windows file is "executable" in that sense | — |
| R1 | Dependency-source cache dirs are named **`deps/<g>:<a>:<v>`**. *Resolved: nested `deps/<g>/<a>/<v>/` ([D7](#d7--nested-dependency-source-cache)).* | `LatheLayout.artifactDir` (was `DependencySourceResolver.sourceCacheDir`) | `lathe:sync` fails at the first source jar | — |
| R7 | Launchers and the sync script are **POSIX `sh`** with logic inside. The server module path is joined with `:` and pasted in **unquoted**. | `ServerInstaller.java:128,215-276`, `SyncScriptWriter.java:43-69` | Can't run; `:` clashes with `C:` | Breaks on a jar path with a space |
| E5 | The VS Code extension **spawns `lathe-launcher.sh` directly**. | `lathe-vscode/src/extension.ts:11,58-61` | Server never starts | — |
| R4 | Open documents and many server maps are keyed by the **raw client URI string**. | `DocumentRegistry.java:10` and all URI-keyed maps | Duplicate references, rename emits two edits for one file, breakpoints never arm, open buffers ignored, write-diagnostics lost | Same for paths with `@ + ! = ,` or spaces, which VS Code percent-encodes |
| R4 | `extractJarPath` decodes, then re-parses, a jar URI. | `WorkspaceManifest.java:435-447` | Throws on `C:\Users\First Last\.m2` | Same with a space in the path |
| R3 | `moduleRel` is persisted with the OS separator, including in the **committed** `lathe-run.json`. | `RunConfigWriter.java:49`, `CaptureLauncherSessionListener.java:44`, `LatheCompiler.java:178` | A run config written on Windows doesn't work on Linux, and vice versa | — |
| R5 | The Eclipse formatter forces `\n`; code actions insert `\n`. | `EclipseFormatEngine.java:30`, the code-action providers | Every format of a CRLF file is a whole-document edit | Same for any CRLF file |
| R5 | Sources are read as UTF-8 regardless of the project's `-encoding`. *Non-goal: honoring `-encoding` is a separate feature ([Non-goals](#non-goals)); R5 only forbids default-charset APIs.* | `WorkspaceSession.java:2101,2113,3914`, `ReferenceCandidateIndex.java:85` | Cp1252 projects lose files from search | Same |
| R6 | `%MVN%` looks for `mvnd` and `mvnw` without extensions and falls back to bare `mvn`. | `FileCommandFormatEngine.java:83-106` | Maven-driven formatting fails | — |
| R6 | Run/test classpaths are passed inline. | `lathe-core` `launch/LaunchPlan.java:162-177`, `run/Launcher.java:29` | `CreateProcess error=206` on real projects | — |
| E4 | Replacing a read-only file; `ATOMIC_MOVE` over a file held open; deleting a tree Defender is scanning. | `FileUtil.extractEntry:290-305`, `FileUtil.moveReplacing`, `LatheCompiler.replaceDir`, `ZipCache` | Reopening a resource fails; intermittent sync failures | — |
| R8 | CI runs `ubuntu-latest` only. *macOS resolved: a `macos-latest` row (JDK 25) since `5a169ce3`.* | `.github/workflows/ci.yml` | No coverage | — |

Things that look risky but are fine:
- `setExecutable` is a no-op on Windows.
- `Path.of(javaHome, "bin", "java")` still starts, because `CreateProcess` appends `.exe`.
- `LatheLock` is a marker file, with no OS file locks.
- The two `toRealPath()` calls in the Maven plugin resolve Windows junctions and normalize drive-letter case, which is what we want.

### Directory layout

The full inventory of what Lathe writes, taken from a real Linux machine on 2026-10-18.

**Machine-wide cache, `~/.cache/lathe/`** (`%USERPROFILE%\.cache\lathe\` on Windows):

| Path | Contents | Verdict |
|---|---|---|
| `servers/<version>/` | the generated launchers; the jars stay in `~/.m2` | Keep. Gains argfiles and the host dialect's scripts. |
| `latest/` | copies of the newest version's launchers (user-global MCP registration) | Done ([D4](#d4--no-links-on-any-platform)); was a symlink. |
| `current` | old pointer, deleted if present | Keep the cleanup. |
| `deps/<g>/<a>/<v>/` | dependency `-sources.jar` contents without `META-INF/maven/`, read-only, plus `.lathe-source.json` | OK since [D7](#d7--nested-dependency-source-cache); was `deps/<g>:<a>:<v>/`. |
| `jdks/<vendor-version>/<module>/…` | JDK `src.zip` per JDK | OK. The key goes through `LatheLayout.cacheName`. |
| `type-index/{deps,jdks}/<groupId>/…` | type-index shards | OK. |
| `logs/` | MCP session logs, JUL `.lck` files | OK. |

**Per workspace, `<root>/.lathe/`:**

| Path | Contents | Verdict |
|---|---|---|
| `lathe-launcher.sh`, `lathe-mcp-launcher.sh` | copies of `servers/<version>/…` | Done ([D4](#d4--no-links-on-any-platform)); was symlinks. The host dialect comes with D2. |
| `lathe-sync.sh` | generated script with logic | R7: runs the tool that ran the build, written in as a literal ([D3](#d3--the-sync-script-runs-the-tool-that-ran-the-build)). |
| `java-home`, `jvm.args`, `workspace.json`, `style.json`, `run.json`, `lathe.lock` | plain files | OK. Absolute paths stay native (R3). |
| `<moduleRel>/{classes,test-classes,generated-*}` | mirrors of the build output | OK for names; exposed to path length and Defender locks (E4). |
| `<moduleRel>/lsp-params-*.json`, `lsp-stamps-*.json`, `test-launch.json`, `main-launch.json` | per-module contract files | R3: relative keys become `/`-separated. |

There were **three links**: the two workspace launchers and `latest`.
No reader needed them to be links (clients just run the file at that path), so D4 replaced them with files without changing any reader.

**Path length.** The deepest cache path on the reference machine is 219 characters, inside a source jar's `META-INF/maven/<group>/<artifact>`.
Windows paths have a similar shape (`C:\Users\<name>\.cache\lathe` vs `/home/<name>/.cache/lathe`), so the deepest paths get close to the classic 260-character `MAX_PATH`.
Java NIO, VS Code and Cursor handle long paths; Explorer and some tools don't.
Mitigations, cheapest first:
1. Skip `META-INF/maven/` when extracting source jars. *Done.* It holds only build metadata, and its `<group>/<artifact>` nesting made every one of the deepest paths.
   The rest of `META-INF/` stays: multi-release source jars keep real sources in `META-INF/versions/N/` (byte-buddy, bouncycastle).
   This drops the deepest cache path from 246 to 224 characters below the home directory, about 240 with `C:\Users\<name>` in front.
2. The R1 guard bounds the path length below the cache and the workspace root to 220 characters. *Done*, in the `multi-module` invoker fixture.
   Measured 2026-10-19 on full syncs of Dropwizard, Helidon and equalsverifier: no name violations;
   at most 210 characters below the cache (417 nested source dirs), and 161, 178 and 194 below the respective workspace roots.
3. Only if that isn't enough, document `-Dlathe.cache=C:\lathe` or the `LongPathsEnabled` registry switch. Not needed so far.

## Decisions

### D1 — Windows scripts are `cmd` batch files, not PowerShell

The Windows dialect of every generated script is a `.cmd` file.

PowerShell was considered and rejected:

- **stdio passthrough.** The LSP server speaks length-prefixed JSON-RPC over stdin and stdout.
  `cmd.exe` hands its standard handles straight to `java.exe`.
  Windows PowerShell 5.1 runs native output through its pipeline, which can re-encode it.
- **Execution policy.** The default on Windows 10 and 11 clients is `Restricted`, so a `.ps1` only runs as `powershell.exe -ExecutionPolicy Bypass -File …`, and corporate policy can override even that.
- **Encoding.** PowerShell 5.1 reads a UTF-8 script without a BOM as ANSI.
- **Version split.** Only 5.1 ships with both Windows 10 and 11, not `pwsh` 7.

Under R7, the scripts barely do anything, which is well within what batch can do.

### D2 — One model per script, rendered per dialect

Each generated script (server launcher, MCP launcher, sync) is a small Java model, such as the JDK resolution order and the argfile path.
A POSIX renderer and a `cmd` renderer turn it into text.
`lathe:sync` writes only the host dialect, so `.lathe/` holds no scripts that can't run on that machine.
Both renderers always exist and are golden-tested on Linux.

The launcher, in both dialects, does only this:
1. Resolve the JDK: `LATHE_JAVA_HOME`, then `.lathe/java-home`, then `java` on `PATH`.
2. Run `java [@.lathe/jvm.args] $LATHE_JVM_OPTS @<servers/<v>/lathe-server.args> <client args>`.

### D3 — The sync script runs the tool that ran the build

**Decided 2026-10-19**, replacing a Java `SyncCommand` decided on 2026-10-18: no JVM is started just to start the build tool.

Today `lathe-sync.sh` holds three pieces of logic: choosing `mvnd`, `./mvnw` or `mvn` when it runs, switching goals for `--tests`, and joining module arguments into `-pl a,b -am`.
`FileCommandFormatEngine` makes the same Maven choice a second time, in Java.
Copying that into `cmd`, and again for Gradle, would mean up to six copies.

Instead, the script is rendered from **what called the build**, with everything the build knows written in as literals:

- **The tool:** the absolute path of the tool that ran the build.
  Maven reports its own distribution through `maven.home`, so the script names `<maven.home>/bin/mvn` (`mvn.cmd` on Windows); a wrapper run points into `~/.m2/wrapper/dists/…`, and an mvnd run names the mvnd client.
  Gradle records its wrapper or distribution the same way.
  Which properties identify an mvnd run, and a Gradle daemon run, is checked when each front-end is built.
- **The JDK:** the build's own `java.home`, set as `JAVA_HOME` for the tool, so sync runs Maven on the JDK that ran the build.
  This is Maven's runtime JDK, not the compile JDK in `.lathe/java-home`; the two differ under toolchains. `.lathe/java-home` and its lookup (`LATHE_JAVA_HOME`, then the file, then `PATH`) stay for the server launchers, which must run javac at the project's language level. The sync script just doesn't copy that lookup, so `LATHE_JAVA_HOME` is a server-only setting (decided 2026-10-19).
  This is the rule the OpenJDK front-end already follows: its `java-home` is the freshly built `images/jdk`, right for the server but not for running Maven, so its sync script never used the lookup.
- **The check:** before running, the script tests that the tool still exists. If it doesn't (a wrapper distribution was deleted, say), it stops with "`<tool>` not found; run a Lathe-enabled build to regenerate this script" instead of guessing.
- **Every Lathe build re-renders it** through `writeIfChanged`, so the script always follows the last build. No separate state file is needed.
- **What's left at runtime** is one `--tests` switch and one loop that puts the front-end's module flag before each argument. Maven accepts repeated `-pl` (checked on 3.9.16; the 3.6.3 CI row confirms the floor), so nothing has to be joined with commas, which is the awkward part in `cmd`.
- **The formatter's `%MVN%`** reads the same tool path from a `buildTool` field in `workspace.json`, which the server already reads, so the tool choice exists nowhere else.
- **Not replayed:** the build's own command-line flags (`-P`, `-s`, `-D…`), since one-off flags like `-DskipTests` would leak into every sync; persistent ones belong in `.mvn/maven.config`. Personal mode's activation flag is the exception, rendered in on purpose.

The POSIX Maven script then reads:

```sh
#!/bin/sh
# Generated by lathe:sync from the last build -- do not edit. Usage: .lathe/lathe-sync.sh [--tests] [module ...]
cd "$(dirname "$0")/.." || exit 1
tool=/home/me/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin/mvn
[ -x "$tool" ] || { echo "lathe-sync: $tool not found; run a Lathe-enabled build to regenerate this script" >&2; exit 1; }
goal=process-test-classes
if [ "$1" = --tests ]; then goal="test -Dlathe.capture.only=true -DfailIfNoTests=false"; shift; fi
scope=; for m; do scope="$scope -pl $m"; done
[ -n "$scope" ] && scope="$scope -am"
set -- "$tool" --no-transfer-progress -Dmaven.build.cache.enabled=false $scope $goal
echo "lathe-sync: $*" >&2
JAVA_HOME=/opt/jdk-25 exec "$@"
```

The `cmd` rendering has the same shape: `if not exist "%tool%"` for the check, a `shift` loop for the modules, and `call` plus `exit /b %ERRORLEVEL%` to run it.

**The template is today's script.** The working Maven `lathe-sync.sh` stays the reference: the front-end supplies only its goals, its `--tests` goals, its module flag and its fixed flags.
- **Maven:** `process-test-classes`; `test -Dlathe.capture.only=true -DfailIfNoTests=false`; `-pl <module>` plus `-am`; `--no-transfer-progress -Dmaven.build.cache.enabled=false`.
- **Gradle:** `latheSync`, with its own `--tests` form, module pattern and `--no-build-cache` ([Gradle support](lathe-gradle-support.md)).
- **OpenJDK:** its own goal, invoked by plugin coordinates. It supplies no `--tests` goals and no module flag, so the script keeps today's "arguments ignored" message. It gains the literals too: today it runs a bare `mvn` from `PATH` without `JAVA_HOME`, which in a JDK development shell can mean Maven on the unreleased JDK just built.

The renderers (D2) turn these into the host dialect: POSIX first, since it exists, then Gradle on the same renderer, then `cmd`.

- **Behavior change:** sync no longer prefers `mvnd` just because it's on `PATH`; it uses whatever ran the last Lathe build, which is the more faithful choice.
- **Rejected:** a Java `SyncCommand` behind a trampoline. It put the logic in one place, but started a second JVM in front of every build to do it.
- **Rejected:** choosing the tool at runtime in each dialect (`command -v`, `where`, `.cmd` vs `.exe`). It keeps the duplication this decision removes.

### D4 — No links, on any platform

**Done 2026-10-18.**

`LauncherCopier` copies the launchers from `servers/<version>/` into the workspace's `.lathe/` on every sync, through `FileUtil.writeIfChanged`, so a file is rewritten only when its content changes.
`latest/` is a directory holding copies of the newest version's launchers.
`FileUtil.linkSymbolic` is deleted.

- **Copies, not forwarding scripts.** A launcher names only absolute jar paths in the local Maven repository, plus `.lathe/java-home` and `.lathe/jvm.args` relative to the working directory. A copy runs exactly like the original, and keeps running even if `~/.cache/lathe` is wiped.
- **Always files.** No "try a link, fall back to a copy": one code path is simpler, and GitHub's Windows runners run as admin, so a link would succeed there and hide the path real users take.
- **Version header.** `ls -l .lathe` no longer shows the version, so each launcher's second line names it: `# lathe-server <version>, generated by lathe:sync; do not edit`.
- **Migration from links.** `writeIfChanged` always replaces a symlink, even one whose target holds the same content, and renames over the link, never writing through it into `servers/`. An old `latest` link is deleted as a link, never recursively. Older Lathe versions still syncing other projects on the same machine fail harmlessly on the `latest/` directory (a best-effort, debug-logged step).

### D5 — Argfiles for every long or path-bearing command line

- **Server launchers:** `ServerInstaller` writes `lathe-server.args` and `lathe-mcp.args` into `servers/<version>/`, holding the module path or classpath, the javac `--add-exports` / `--add-opens` lines, and the main module or class.
  Paths are joined with `File.pathSeparator` and quoted, with backslashes doubled inside quotes, as Java's argfile syntax requires.
  This removes the `:` vs `;` problem, fixes the spaces-in-path bug on every OS, and keeps the `.cmd` far below `cmd.exe`'s 8,191-character line limit. The MCP classpath alone is about 3.2 KB today.
- **Run/test launches:** E3 moves `--module-path`, `--class-path` and `--patch-module` into a per-launch argfile once the command line passes a threshold. That's needed for Windows' 32,767-character limit.
  The transcript shows the argfile's path and keeps it on disk, so users can still copy and paste the command.

Java has accepted argfiles since JDK 9; Lathe requires 17.

### D6 — Canonical URIs at the boundary

`LatheUri.canonical(String uri)` returns `toPath(uri).toUri().toString()` for a `file:` URI, which is the form the server builds everywhere else. Any other URI passes through unchanged.
It is applied wherever a URI enters the server:
- every `textDocument/*` entry point in `LatheTextDocumentService` (about 24 call sites);
- workspace command arguments in `LatheWorkspaceService`;
- DAP source lookup in `LatheSourceLookUpProvider`;
- the MCP engine's file arguments.

Outgoing URIs are already canonical, and clients accept them; VS Code normalizes what it receives.
Keying everything by `Path` instead was rejected: it would be a refactor of about a dozen maps for the same result.

### D7 — Nested dependency-source cache

`deps/<groupId>/<artifactId>/<version>/` mirrors `~/.m2/repository` and the type-index layout.
It can't collide, unlike flattening `:` to `-`: `a:b-c:1` and `a-b:c:1` would both become `a-b-c-1`.
The server reads the directory from `workspace.json` and never rebuilds it, so only the directory name changes: `DependencySourceResolver` and the type index both build it with `LatheLayout.artifactDir`.
Every segment already appears in the artifact's `~/.m2` path, so it is legal wherever that repository lives and needs no escaping.
*Done.* No `CACHE_SCHEMA_VERSION` bump was needed: a new directory has no marker, so it is extracted fresh, and a bump would only rebuild every type index for nothing.
Old directories are simply orphaned (`rm -rf ~/.cache/lathe/deps/*:*` reclaims them); there's no migration, per the no-backward-compatibility rule.

### D8 — `/` in persisted relative paths

`moduleRel`, compile-stamp keys, resource names and run-config `module` fields are stored with `/` on every OS.
Readers resolve them with `Path.resolve`, which accepts `/` on Windows.
The schema records normalize these fields in their compact constructors, so no writer can get it wrong.
This makes a committed `lathe-run.json` work across OSes.
Absolute paths stay native: they are machine-specific anyway.

### D9 — One tool resolver

E3 lives in `lathe-core` and answers one question for the processes the **server** starts: how to start tool X here.
- **The build tool:** read from `workspace.json`'s `buildTool` (D3), never searched for.
- **User-configured external formatter commands:** a `.cmd` / `.bat` is started explicitly.
- **Long argument lists:** moved into an argfile (D5).

`FileCommandFormatEngine`, `ExternalCommandFormatEngine` and `LaunchPlan` go through it. The sync script doesn't need it: it runs the recorded tool directly.

### D10 — Cache stays at `~/.cache/lathe` on every OS

`%LOCALAPPDATA%` on Windows and `~/Library/Caches` on macOS would be more idiomatic.
But a single location keeps the docs, the extension's `files.readonlyInclude` glob (`**/.cache/lathe/**`) and every hint identical across OSes.
`-Dlathe.cache` remains the override.

### D11 — Client launch contract

The [Workspace Contract](../lathe-workspace-contract.md) changes from "attach when `.lathe/lathe-launcher.sh` is executable" to:

> Attach when the host-dialect launcher exists: `.lathe/lathe-launcher.sh` on POSIX, `.lathe\lathe-launcher.cmd` on Windows.
> Start it with the workspace root as working directory.
> On Windows, start it through `%ComSpec% /d /s /c ""<launcher>""` (verbatim arguments).

The Windows form is needed because Node, since the CVE-2024-27980 fix, refuses to spawn `.cmd` files without a shell.
`/d` skips AutoRun, so a user's `cmd` profile can't write to stdout and corrupt the protocol.
`/s` with the doubled quotes keeps paths with spaces intact.

The server exits on the LSP `exit` notification (`LatheLanguageServer.java:177`) and when stdin closes (`LatheServer.java:42`), so killing `cmd.exe` doesn't leave `java.exe` behind.

### D12 — Enforcement

- **`forbiddenapis` Maven plugin** on the Java modules, with:
  - the bundled `jdk-unsafe` signatures (default charset and locale);
  - a short project signatures file: `Files.createSymbolicLink`, `Files.createLink`, `new ProcessBuilder`, `Runtime.exec`, `System.getProperty("os.name")`;
  - `@SuppressForbidden` allowed only inside the E1/E3 classes.
  Its version has to fit the Maven 3.6.3 / Java 17 compatibility floor ([Wider Maven and JDK Support](../done/lathe-wider-maven-support.md)).
- **The portable-names guard** (R1) in the invoker verification. *Done, failing:* `MultiModuleTest.sync_writtenNames_portableToEveryOs` walks the fixture's `.lathe/` and the test cache for Windows-illegal characters, a trailing dot or space, reserved device names and paths over 220 characters.
- **A CI OS matrix** (R8).

## Design by component

### Generated launchers (`lathe-install`)

The POSIX rendering stays close to today's script, minus the inline module path.
The `cmd` rendering of the server launcher:

```bat
@echo off
rem Generated by lathe:sync for lathe-server <version> -- do not edit.
chcp 65001 >nul
setlocal
set "jhome=%LATHE_JAVA_HOME%"
if not defined jhome if exist ".lathe\java-home" set /p jhome=<".lathe\java-home"
set "java_bin=java"
if defined jhome (
  if exist "%jhome%\bin\java.exe" (set "java_bin=%jhome%\bin\java.exe") else (echo lathe: no java at %jhome% >&2)
)
set "jvm_args="
if exist ".lathe\jvm.args" set "jvm_args=@.lathe\jvm.args"
"%java_bin%" -XX:+DisplayVMOutputToStderr %jvm_args% %LATHE_JVM_OPTS% @"C:\Users\...\.cache\lathe\servers\<v>\lathe-server.args" %*
exit /b %ERRORLEVEL%
```

- **CRLF** endings are written explicitly; `cmd.exe` mis-parses labels in LF files. `.gitattributes` gets `*.cmd text eol=crlf` and `*.sh text eol=lf`.
- **`chcp 65001`** makes `cmd.exe` read the rest of the file as UTF-8, so a non-ASCII user name (`C:\Users\Jöran\…`) survives. How the Java launcher decodes non-ASCII argfiles is checked in B0.
- **`set /p`** tolerates a trailing newline in `.lathe\java-home`. The POSIX prologue gets the matching fix (trim a trailing `\r`).

### Sync (`lathe-maven-plugin`, `lathe-install`)

- **One build-neutral model and one renderer per dialect, in `lathe-install`.** A front-end only fills the model in; it never writes script text. The renderers import no Maven types, so the Gradle plugin uses them unchanged.
  `SyncScriptWriter` (Maven) and `OpenJdkSync.writeSyncScript` (OpenJDK) both become "build the model, call the renderer".

  | Field | Maven | Gradle (later) | OpenJDK |
  |---|---|---|---|
  | tool, absolute, from the running build | `<maven.home>/bin/mvn`, or the mvnd client | `gradlew`, or `<gradleHome>/bin/gradle` | `<maven.home>/bin/mvn` |
  | JDK, the build's `java.home` | ✓ | ✓ | ✓ |
  | fixed flags | `--no-transfer-progress -Dmaven.build.cache.enabled=false` | `--no-build-cache --no-configuration-cache` | `--no-transfer-progress` |
  | goals | `process-test-classes` | `latheSync` | `<coords>:sync -Dlathe.buildDir=…` |
  | `--tests` goals | `test -Dlathe.capture.only=true -DfailIfNoTests=false` | a `latheSync` task option | not supported: "arguments ignored" |
  | module flag, before each module | `-pl` | `--module`, a repeatable `latheSync` task option | not supported |
  | after the modules | `-am` | nothing: `latheSync` adds upstream itself | — |

  "Not supported" is modeled explicitly, not as an empty-string sentinel. The Gradle column is an assumption that the [Gradle design](lathe-gradle-support.md) confirms; the golden tests include a Gradle-shaped model so the template is proven before Gradle starts.
- The front-end writes the tool to `workspace.json`'s `buildTool` too, for the formatter's `%MVN%`.
- `LatheLayout.SYNC_SCRIPT_COMMAND` and `scopedSyncCommand` get the name from E1, so every fix-it hint shows the host's command.

### Editor clients

- **VS Code / Cursor** (`extension.ts`): the launcher name and spawn shape come from `process.platform` (D11). That's the only change.
  Cursor installs the same `.vsix`; Open VSX publishing stays in the [VS Code client M2 plan](lathe-vscode-client.md).
- **Zed:** a Windows target. Its project settings already start the launcher through a shell (`/bin/sh -c "exec .lathe/lathe-launcher.sh"`); on Windows they use the D11 form, `cmd.exe /d /s /c .lathe\lathe-launcher.cmd`. Zed can't run on a CI runner, so a human session checks it.
- **Neovim, Emacs:** not on Windows ([Non-goals](#non-goals)). On Linux and macOS they keep today's launch, so they need no change.

### Server

- URIs: D6.
- `extractJarPath`: use the raw, still-encoded part of the URI.
- Formatting: pass the document's own line delimiter to the Eclipse formatter. Code actions insert the document's line ending.
- Process starts go through E3.

### Filesystem (E4)

- `FileUtil.moveReplacing` and `deleteDir` retry a short, bounded number of times on `AccessDeniedException` / `DirectoryNotEmptyException`, which covers transient Defender and indexer handles.
- `extractEntry` clears read-only on an existing target before replacing it.
- On Linux and macOS these branches never trigger.

## Gradle front-end

[Gradle support](lathe-gradle-support.md) is proposed and spiked. Its central constraint is that the Gradle front-end writes the **same `.lathe/` contract**.
That makes it a beneficiary of this design rather than a separate porting job:

- **Most of it is portable for free.** The Gradle plugin builds on `lathe-core` and `lathe-install`. The schema records' path normalization (D8), the portable cache names (D7), the launcher renderers (D2), the argfiles (D5) and the tool resolver (D9) all come with them.
- **What Gradle adds to the platform edge:**
  - rendering its own tool into the script: the wrapper (`gradlew` / `gradlew.bat`, `.bat` not `.cmd`) or the distribution;
  - its goals and module pattern for the sync script renderer (D3).
- **Gradle on Windows is common and well supported.** The one known Windows quirk is that the **Gradle daemon holds file locks** on jars and class outputs it has touched. The E4 retry covers Lathe's mirror refresh. The capture finalizer only *reads* build outputs, so it never fights the daemon.
- **Its tests run on Windows.** TestKit (`GradleRunner`) is pure Java and downloads its own distributions.
  The Maven-orchestrated Gradle sub-build (`exec-maven-plugin`) must start `gradlew.bat` on Windows, not `./gradlew`. A Maven profile activated by `<os><family>windows</family></os>` selects the executable.
- **The contract diff gets simpler.** The planned "produce `.lathe/` with Maven and with Gradle, then diff them" test needs path normalization; with `/` in persisted relative paths (D8) only absolute paths differ.

### Sequencing with Gradle

Decided 2026-10-20: **Multi-OS closes first** (Phases A and B), then Gradle starts.
Gradle doesn't wait on a rework: the sync script model (D3) is build-neutral, and its golden tests include a Gradle-shaped model.
So Gradle's sync script, launchers and Windows support come from filling in the model and recording `gradlew.bat`, not from new script logic.
Gradle's first slice makes `lathe-install` free of Maven, so the Gradle plugin can load it.

## Delivery plan

**Phase A** makes the core portable and enforced. It needs no Windows machine: it runs on Linux and adds macOS to CI.
**Phase B** adds the Windows edge on top. After Phase A, Phase B mostly *adds* code (a renderer, a client branch, a CI row) rather than reworking it.

Each slice can be merged on its own.

**Order (decided 2026-10-20):** Multi-OS closes before Gradle starts, and the sync script generator is build-neutral from the start, so Gradle only fills in the model later.

1. **B0**, the Windows spike, first: it needs no production code and can amend this design before anything is built on it.
2. **A2**, the sync script from the build: the working Maven script is the template.
3. **A1**, launcher models and server argfiles, on the same renderer.
4. **A3** (D8), **A4** (URIs, text), then **A0/A5** (enforcement).
5. **B1** (the `cmd` renderers, the Windows CI row), **B2 + B4** (Cursor and Zed), **B3** (hardening).

`lathe-install` without Maven (formerly A2b) moved to the [Gradle design](lathe-gradle-support.md) as its first slice: only Gradle needs it, and the renderers already import no Maven types.

### Phase A — Portable core

#### A0 — Guards in report mode, macOS in CI

- Add `macos-latest` to the CI matrix. *Done (`5a169ce3`): one row, on JDK 25, since the OS-sensitive parts don't vary by JDK.*
- Add `forbiddenapis` and the portable-names guard in report-only mode, and list today's violations in this document. *Names guard done, and failing rather than reporting, since D7 left nothing to report. `forbiddenapis` open.*

**Exit:** CI green on Linux and macOS; a known, written-down violation list.

#### A1 — Launchers: no links, argfiles, script models

- D2 models and the POSIX renderer, D5 server argfiles. (D4, no links, is done.)

**Fixes today:** a server jar path containing a space.
**Exit:**
- `.lathe/` contains no links.
- The launcher completes an LSP handshake from a workspace and a `-Dlathe.cache` whose paths contain a space.

#### A2 — The sync script from the build

- D3: the sync script rendered from today's Maven script as the template, with the build's tool and JDK as literals and a check that the tool still exists.
- `workspace.json` gets `buildTool`, which the formatter's `%MVN%` reads instead of searching.

**Exit:**
- `lathe-sync.sh` chooses no tool and resolves no JDK: it runs the tool that ran the build, and fails clearly when that's gone.
- Golden tests cover the rendered script for a plain, a wrapper and an mvnd build, with and without modules and `--tests`.
- The invoker fixture runs the generated script, scoped and with `--tests`.

#### A3 — Data and names

- D7 nested dependency cache; skip `META-INF/maven/`. *Done.*
- D8 `/` in persisted relative paths, normalized in the schema records.

**Exit:** the names guard passes on the fixtures and on Dropwizard and Helidon, with the path-length bound recorded here. *Met for D7 (2026-10-19, numbers under [path length](#directory-layout)); D8 still open.*

#### A4 — URIs and text

- D6 canonical URIs and the `extractJarPath` fix.
- R5 line-ending preservation in formatting and code actions.

**Fixes today:** paths with `@`, `+` or spaces in VS Code; CRLF format-on-save.
**Exit:** server tests that use VS Code-encoded URIs and CRLF documents pass.

#### A5 — Turn enforcement on

- Switch `forbiddenapis` and the names guard from reporting to failing. *The names guard fails already.*
- Update the [Workspace Contract](../lathe-workspace-contract.md) (D8, D11) and the status doc.

**Exit:** a deliberately introduced symlink, `:` file name or bare `ProcessBuilder` fails Linux CI.

### Phase B — Windows

#### B0 — Spike on a Windows CI runner

It needs no production code, so it **runs early, in parallel with Phase A**, to surface a wrong assumption before Phase B.
It uses a throwaway branch and workflow on `windows-latest`:

1. Run today's build unchanged, invoker tests included, with `continue-on-error`. The failures it produces check this audit against reality.
   Both failures the audit predicted are fixed already: the `deps/<g>:<a>:<v>` directory name (D7) and the symlinks (D4).
2. Write `lathe-launcher.cmd` and the argfile by hand, as in the sketch above.
3. Run the **stdio smoke**: a small Node script starts the launcher exactly as D11 describes. It sends `initialize`, opens a file containing a compile error, waits for `publishDiagnostics`, and sends `shutdown` and `exit`. It checks that:
   - stdout carries only protocol frames;
   - the diagnostic arrives;
   - `java.exe` is gone afterwards.
4. Repeat with the workspace and `-Dlathe.cache` under a path with a space and a non-ASCII character (`D:\a\lathe ws\Jöran\`).
5. Run Maven `-pl` with a backslash module path.
6. Check that `maven.home` and `mvnd.home` give a startable tool path on Windows (`mvn.cmd`, the mvnd client), for the D3 sync script.

**Exit:** D1, D5 and D11 hold, or this document is amended.

#### B1 — Windows edge

- E1 Windows dialect, the `cmd` renderers (launchers and the sync script), E3/E4 Windows branches.
- D9 for the server's own process starts: `LaunchPlan`, the formatter and external commands go through E3, including the D5 launch argfile for Windows' command-line limit.
- Add `windows-latest` to the CI matrix: build, the portable test subset, the invoker run with the names guard, and the stdio smoke against the launcher `lathe:sync` generates.

**Exit:** all three CI rows green; `.lathe\lathe-sync.cmd` works from `cmd` and PowerShell.

#### B2 — VS Code and Cursor

- The `extension.ts` branch (D11).
- A Windows section in `docs/guide/editors/vscode.md` and `installation.md`.
- The stdio smoke spawns through the extension's own launch code.

**Exit:** CI green, and **human session 1** passes.

#### B3 — Hardening and first users

- Watch for E4 retries in Windows CI logs.
- Test-run argfiles on Dropwizard and Helidon.
- `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8` for launched JVMs.

**Exit:** **human session 2** passes.

#### B4 — Zed

- The Windows settings in `docs/guide/editors/zed.md`: `binary.path` `cmd.exe` with `["/d", "/s", "/c", ".lathe\\lathe-launcher.cmd"]`.
- Lands with B2, so human session 1 covers both editors. Its Zed pass: error squiggle, cross-module go to definition, references, rename, and no `java.exe` left after closing.

Neovim and Emacs are not supported on Windows ([Non-goals](#non-goals)); decided 2026-10-19.

## Testing strategy

- **Renderers:** golden-text tests for both dialects run on every OS (CRLF endings, quoting, doubled backslashes).
- **Windows scripts run on Windows CI.** The `windows-latest` row executes the generated `.cmd` launcher and sync script for real, from `cmd` and PowerShell, instead of trusting the golden text alone.
- **URIs:** percent-encoding cases (`@`, `+`, space) run everywhere. Drive-letter cases (`file:///c%3A/...` vs `file:///C:/...`) are `@EnabledOnOs(WINDOWS)`, because `Path.of(URI)` treats drive letters differently on POSIX.
- **CI matrix:** Linux runs the full suite. macOS and Windows run the portable subset, the invoker fixture with the names guard, and the stdio smoke.
  - Steps use `shell: bash` (Git Bash is preinstalled on the runner) so the workflow stays readable; the product itself never needs bash.
  - The audit counted 102 `file:///` literals and 23 absolute `/…` paths in `lathe-server` tests, plus `sh -c` fixtures. They're converted when a slice touches them, and the Windows subset grows as they are.
- **Invoker tests on Windows CI: yes, with small changes.** See [below](#invoker-tests-on-windows-ci).
- **What CI can't cover** is the editor UI and the desktop Windows editions; runners are Windows Server 2022/2025. That is what the human sessions are for.

### Invoker tests on Windows CI

The Maven invoker tests are the main end-to-end check, and they can run on `windows-latest`:

- **The harness is portable.** `maven-invoker-plugin` is pure Java and finds `mvn.cmd` on Windows. Its configuration has nothing POSIX-specific: the cloned projects, the private local repository, the `-Dlathe.cache` override and the `maven-clean-plugin` pre-step.
- **The verification is already a Java test suite** in the fixture's `verify` module (`MultiModuleTest`, `LspSmokeTest`, `McpSmokeTest`). `LspSmokeTest` already starts the generated launcher with `ProcessBuilder` and speaks LSP to it, and Java's `ProcessBuilder` can start a `.cmd` directly. So the server-level smoke test for Windows exists already; only the launcher name changes.
- **What needs changing:**
  - `MultiModuleTest` asserts POSIX specifics: `lathe-launcher.sh` names, `isExecutable` and a `#!/bin/sh` header. Most of these change anyway with D2. They become assertions on the host dialect's file name and content, through one helper.
  - `McpSmokeTest` pastes raw paths into JSON strings (`"{\"file\":\"%s\"}".formatted(path)`). On Windows the backslashes are invalid JSON escapes, so the paths need JSON escaping.
  - The Node smoke is still needed for one thing only: the **extension's** `cmd.exe /d /s /c` spawn path. Java's `ProcessBuilder` quotes arguments differently from Node, so the Java tests can't stand in for it.
- **CI job details:**
  - Run with `shell: bash` so the existing `env -u CI` keeps working. Lathe deliberately stays off when `CI` is set.
  - Exclude `lathe-neovim` (its specs need `nvim` and `bash` through `exec-maven-plugin`). `lathe-vscode` builds as-is; frontend-maven-plugin supports Windows.
  - Set `git config --system core.longpaths true`. Add `.gitattributes` so fixtures check out with LF, and keep CRLF coverage in dedicated tests (R5) rather than depending on the runner's `core.autocrlf`.
  - Cache `~/.m2`. Windows runners are noticeably slower, and the invoker run resolves a lot of artifacts.

## Human validation

There is no regular Windows user on the project, so human time is two short, scripted sessions.
One must run on **Windows 10** and the other on **Windows 11**.
If both available machines run the same version, a Windows 10 VM covers the gap. Microsoft's Evaluation Center has offered time-limited Windows 10 Enterprise images; check availability when the time comes.

**Session 1: the maintainer, VS Code and Zed, after B2 and B4 (once).**
A checklist in the PR:
- install the `.vsix` CI built;
- build the `multi-module` fixture with `mvnw.cmd`;
- an error squiggle, completion, cross-module go to definition, references, rename;
- format-on-save on a CRLF file;
- after closing the window, no `java.exe` left in Task Manager.

A failure is reported with the Lathe output-channel log attached, not with a diagnosis.

**Session 2: a non-developer friend, Cursor, after B3 (once).**
It checks that a Cursor user can get going from written instructions alone.
The project prepares a **sample zip**:
- a tiny two-module project with `mvnw.cmd` and the Lathe extension already in `.mvn/extensions.xml`;
- the `.vsix`;
- `README-windows.txt` and a `build.cmd`.

The steps are:
1. `winget install Microsoft.OpenJDK.21`, or the linked `.msi` on an older Windows 10.
2. Double-click `build.cmd`.
3. In Cursor, run *Install from VSIX…*, then open the folder.
4. Screenshot an error squiggle and Ctrl+click into the other module.
5. On failure, send the log zip `build.cmd` leaves.

The zip then becomes the Windows quick-start.

After this, releases rely on CI.
A human re-check is needed only when a change touches the launchers, a client's spawn code or URI handling.

## Non-goals

- **OpenJDK workspaces on Windows.** Building OpenJDK on Windows already requires Cygwin. The OpenJDK front-end's sync still uses the same script template on POSIX.
- **Sharing one `.lathe/` between Windows and WSL.** Absolute paths are machine-local (R3). Only committed, shared files (`lathe-run.json`, `lathe-style.json`) must be portable, and D8 makes them so.
- **A VS Code handler for the `lathe/sync` notification.** It's missing on every OS, and belongs to the [VS Code client](lathe-vscode-client.md) parity work.
- **Non-UTF-8 source encodings in the server.** R5 forbids default-charset APIs, but honoring the project's `-encoding` is a separate feature.
- **PowerShell scripts** (D1).
- **Neovim and Emacs on Windows** (decided 2026-10-19). Cursor (with VS Code) and Zed are the Windows clients; Neovim and Emacs stay Linux and macOS only, and their docs say so.

### Available today: WSL

Until Phase B ships, a Windows user can run Lathe unchanged through VS Code's Remote-WSL, or Cursor's equivalent. Everything then runs inside Linux.

## Risks

- **Defender and indexer locks** make output-mirror refreshes flaky on Windows. E4 retries mitigate this; watch Windows CI for `post-compile step failed`.
- **Non-ASCII user profiles** depend on `chcp 65001` and on how the Java launcher decodes argfiles, both checked in B0. The fallback is to put the cache path in through `%USERPROFILE%` rather than as a literal.
- **Path length:** bounded by the R1 guard. See [Directory layout](#directory-layout).
- **macOS quirks** that CI shows: a case-insensitive filesystem combined with Java's case-sensitive `Path` equality, and `/var` → `/private/var` for temp directories.
  The symlinked temp path already surfaced two bugs, both fixed with the macOS row: the server misnamed written classes when the workspace path goes through a symlink (`5d034fe9`),
  and the Neovim client didn't recognize cache buffers behind a symlinked cache path (`dad23fc8`).
- **Editor-UI regressions on Windows** that CI can't see are found late, by users. That's accepted for a first release.

## Open questions

1. ~~Should Neovim, Emacs and Zed on Windows be a goal for this effort?~~ *Resolved 2026-10-19: Zed yes (B4), Neovim and Emacs no.*
2. Which Windows versions do the maintainer's and the friend's machines run? That decides whether a Windows 10 VM is needed.
