# Lathe — Multi-OS Design

## Status

**Proposed; A0 and A3 partly done; D4 (no links) done; R1 enforced.** Lathe today is developed on Linux, tested in CI on Linux and macOS, and does not run natively on Windows.
This document defines how Lathe becomes **portable by construction**:
OS differences are confined to a small, named platform edge;
everything else is OS-neutral, and checks enforce it.
Windows 10 and 11, with VS Code and Cursor as the first clients, is the first platform added on top of it.

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
| E3 Tool resolution | `lathe-core` | Turning a tool name (`mvn`, `mvnd`, the wrapper) into something `ProcessBuilder` can start (`mvn` vs `mvn.cmd`), and moving long argument lists into argfiles. |
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
| **R7 Thin scripts** | A generated script only resolves the JDK and starts Java with an argfile. Any further behavior (choosing Maven, building the command) is Java code. | Code review; the renderers are golden-tested per dialect. |
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
| R1 | Dependency-source cache dirs are named **`deps/<g>:<a>:<v>`**. *Resolved: nested `deps/<g>/<a>/<v>/` ([D7](#d7--nested-dependency-source-cache)).* | `DependencySourceResolver.sourceCacheDir:87` | `lathe:sync` fails at the first source jar | — |
| R7 | Launchers and the sync script are **POSIX `sh`** with logic inside. The server module path is joined with `:` and pasted in **unquoted**. | `ServerInstaller.java:134,221-249`, `SyncScriptWriter.java:43-69` | Can't run; `:` clashes with `C:` | Breaks on a jar path with a space |
| E5 | The VS Code extension **spawns `lathe-launcher.sh` directly**. | `lathe-vscode/src/extension.ts:11,58-61` | Server never starts | — |
| R4 | Open documents and many server maps are keyed by the **raw client URI string**. | `DocumentRegistry.java:10` and all URI-keyed maps | Duplicate references, rename emits two edits for one file, breakpoints never arm, open buffers ignored, write-diagnostics lost | Same for paths with `@ + ! = ,` or spaces, which VS Code percent-encodes |
| R4 | `extractJarPath` decodes, then re-parses, a jar URI. | `WorkspaceManifest.java:434-446` | Throws on `C:\Users\First Last\.m2` | Same with a space in the path |
| R3 | `moduleRel` is persisted with the OS separator, including in the **committed** `lathe-run.json`. | `RunConfigWriter.java:49`, `CaptureLauncherSessionListener.java:44`, `LatheCompiler.java:178` | A run config written on Windows doesn't work on Linux, and vice versa | — |
| R5 | The Eclipse formatter forces `\n`; code actions insert `\n`. | `EclipseFormatEngine.java:30`, the code-action providers | Every format of a CRLF file is a whole-document edit | Same for any CRLF file |
| R5 | Sources are read as UTF-8 regardless of the project's `-encoding`. | `WorkspaceSession.java:2107,2119,3926`, `ReferenceCandidateIndex.java:85` | Cp1252 projects lose files from search | Same |
| R6 | `%MVN%` looks for `mvnd` and `mvnw` without extensions and falls back to bare `mvn`. | `FileCommandFormatEngine.java:83-106` | Maven-driven formatting fails | — |
| R6 | Run/test classpaths are passed inline. | `LaunchPlan.java:142-177`, `run/Launcher.java:29` | `CreateProcess error=206` on real projects | — |
| E4 | Replacing a read-only file; `ATOMIC_MOVE` over a file held open; deleting a tree Defender is scanning. | `FileUtil.extractEntry:261-275`, `FileUtil.moveReplacing`, `LatheCompiler.replaceDir`, `ZipCache` | Reopening a resource fails; intermittent sync failures | — |
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
| `lathe-sync.sh` | generated script with logic | R7: a thin trampoline into Java ([D3](#d3--sync-behavior-moves-into-a-build-neutral-synccommand)). |
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
   Measured 2026-10-19 on a fresh Helidon sync: 210 characters below the cache, 178 below the workspace root, and no name violations.
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

### D3 — Sync behavior moves into a build-neutral `SyncCommand`

**Decided 2026-10-18.**

Today `lathe-sync.sh` holds real logic: choosing `mvnd`, `./mvnw` or `mvn`, handling `--tests`, joining module arguments into `-pl … -am`.
Copying that into batch would duplicate it in two languages.
`FileCommandFormatEngine` already duplicates the Maven choice a third time, in Java.

Instead, the sync script becomes a launcher like the others.
It resolves the JDK and runs `java @<lathe-sync.args> <args>`, which starts a small `SyncCommand` in `lathe-install`.
`SyncCommand` chooses Maven through E3 (the same resolver the formatter uses), builds the command and runs it with inherited stdio and exit code.

- **Cost:** one extra JVM start, about 0.1–0.3 s, in front of a Maven build that takes seconds.
- **Gain:** the logic exists once, is unit-tested, and behaves the same on every OS.
- **Build-neutral from day one.** [Gradle support](lathe-gradle-support.md) is next on the roadmap, and its front-end writes the same sync script.
  So `SyncCommand` doesn't hard-code Maven. The front-end writes a per-workspace `.lathe/lathe-sync.args` naming the build tool and its sync invocation:
  - Maven: `process-test-classes`, `--tests`, `-pl … -am`;
  - Gradle: `latheSync`, `--no-build-cache`;
  - OpenJDK: its own command.

  `SyncCommand` resolves the tool through E3 and runs it.
  Without this, Gradle would need two more copies of the sync logic, in `sh` and in `cmd`.
- **Rejected alternative:** keep the logic in the scripts. With two script dialects and three front-ends, that means up to six copies of the same logic, each tested separately or not at all.

The user-facing command stays `.lathe/lathe-sync.sh` / `.lathe\lathe-sync.cmd`, and its arguments don't change.

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

Java has accepted argfiles since JDK 9; Lathe requires 21.

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

E3 lives in `lathe-core` and answers one question: how to start tool X here.
- **Maven:** `mvnd` on `PATH`, then the project wrapper, then `mvn`. On Windows it checks `.exe` and `.cmd` and uses `mvnw.cmd`; on POSIX it uses `mvnw`.
- **Gradle:** the project wrapper, then `gradle` on `PATH`. On Windows the wrapper is **`gradlew.bat`** (`.bat`, not `.cmd`) and the distribution's launcher is `gradle.bat`.
- **User-configured external formatter commands:** a `.cmd` / `.bat` is started explicitly.
- **Long argument lists:** moved into an argfile (D5).

`SyncCommand` (D3), `FileCommandFormatEngine`, `ExternalCommandFormatEngine` and `LaunchPlan` all go through it.

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

- `SyncScriptWriter` renders the trampoline (D2) instead of the logic.
- `SyncCommand` in `lathe-install` keeps today's behavior: the `--tests` goal switch, `-pl … -am` scoping, `-Dmaven.build.cache.enabled=false`, and echoing the command to stderr.
- `LatheLayout.SYNC_SCRIPT_COMMAND` and `scopedSyncCommand` get the name from E1, so every fix-it hint shows the host's command.

### Editor clients

- **VS Code / Cursor** (`extension.ts`): the launcher name and spawn shape come from `process.platform` (D11). That's the only change.
  Cursor installs the same `.vsix`; Open VSX publishing stays in the [VS Code client M2 plan](lathe-vscode-client.md).
- **Neovim, Emacs, Zed:** the same contract change. Each is a few lines, but each needs its own Windows check, so they follow the VS Code client.

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
  - wrapper resolution (`gradlew` / `gradlew.bat`) in E3;
  - a Gradle sync invocation in `.lathe/lathe-sync.args` (D3).
- **Gradle on Windows is common and well supported.** The one known Windows quirk is that the **Gradle daemon holds file locks** on jars and class outputs it has touched. The E4 retry covers Lathe's mirror refresh. The capture finalizer only *reads* build outputs, so it never fights the daemon.
- **Its tests run on Windows.** TestKit (`GradleRunner`) is pure Java and downloads its own distributions.
  The Maven-orchestrated Gradle sub-build (`exec-maven-plugin`) must start `gradlew.bat` on Windows, not `./gradlew`. A Maven profile activated by `<os><family>windows</family></os>` selects the executable.
- **The contract diff gets simpler.** The planned "produce `.lathe/` with Maven and with Gradle, then diff them" test needs path normalization; with `/` in persisted relative paths (D8) only absolute paths differ.

### Sequencing with Gradle

The two efforts overlap mostly in `lathe-install` and the sync script, so the order matters:

| Order | Work | Why here |
|---|---|---|
| 1 | Multi-OS **A0–A3**: guards, launchers, `SyncCommand`, data and names | Gradle slice 2 (`latheSync`, server install) should write portable launchers and the build-neutral sync script from the start, instead of a sixth copy of POSIX-only logic. |
| 2 | Gradle **slices 1–2** (compiler capture, `latheSync`) | Gradle slice 1 is independent and can run in parallel with A0–A3. |
| 3 | Multi-OS **A4–A5** (URIs, text, enforcement on) | Independent of Gradle. With enforcement on, Gradle code is checked by the same rules as everything else. |
| 4 | Multi-OS **Phase B** (Windows), then Gradle **slices 3–5** in either order | The Windows CI row runs the Maven invoker tests and the Gradle TestKit tests side by side. |

The result: the first Windows release supports both Maven and Gradle projects, with no Windows-specific work on the Gradle side beyond E3's wrapper names.

## Delivery plan

**Phase A** makes the core portable and enforced. It needs no Windows machine: it runs on Linux and adds macOS to CI.
**Phase B** adds the Windows edge on top. After Phase A, Phase B mostly *adds* code (a renderer, a client branch, a CI row) rather than reworking it.

Each slice can be merged on its own.

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

#### A2 — Sync into Java, one tool resolver

- D3 `SyncCommand`, D9 tool resolver; the formatter and `LaunchPlan` move onto it, including the D5 launch argfile.
- `lathe-install` becomes Maven-free (decided 2026-10-18 with [Gradle support](lathe-gradle-support.md#what-is-reused-vs-new)):
  a small logging interface replaces Maven's `Log`, artifact resolution goes behind an interface whose Maven implementation stays in `lathe-maven-plugin`,
  and the build-neutral sync work (JDK sources, type index, `workspace.json`, the sync script) moves in from `lathe-maven-plugin`.
  `SyncCommand` and the Gradle plugin then share it without loading Maven's API.

**Exit:**
- `lathe-sync.sh` contains no Maven logic.
- `SyncCommand` unit tests cover `mvnd` / wrapper / `mvn` and the module scoping.
- `lathe-install` has no Maven dependency.

#### A3 — Data and names

- D7 nested dependency cache; skip `META-INF/maven/`. *Done.*
- D8 `/` in persisted relative paths, normalized in the schema records.

**Exit:** the names guard passes on the fixtures and on Dropwizard and Helidon, with the path-length bound recorded here.

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

**Exit:** D1, D5 and D11 hold, or this document is amended.

#### B1 — Windows edge

- E1 Windows dialect, the `cmd` renderers, E3/E4 Windows branches.
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

#### B4 — Other clients (optional)

- Neovim, Emacs and Zed adopt the D11 contract.
- Each is documented as "should work" until someone checks it on Windows.

## Testing strategy

- **Renderers:** golden-text tests for both dialects run on every OS (CRLF endings, quoting, doubled backslashes).
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

**Session 1: the maintainer, VS Code, after B2 (about 45 minutes, once).**
A checklist in the PR:
- install the `.vsix` CI built;
- build the `multi-module` fixture with `mvnw.cmd`;
- an error squiggle, completion, cross-module go to definition, references, rename;
- format-on-save on a CRLF file;
- after closing the window, no `java.exe` left in Task Manager.

A failure is reported with the Lathe output-channel log attached, not with a diagnosis.

**Session 2: a non-developer friend, Cursor, after B3 (about 20 minutes, once).**
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

## Rough effort

| Slice | Estimate |
|---|---|
| A0 — guards (report), macOS CI | 1 day |
| A1 — launchers: argfiles, models (no links: done) | 2 days |
| A2 — sync into Java, tool resolver | 2–3 days |
| A3 — data and names | 1–2 days |
| A4 — URIs and text | 2–3 days |
| A5 — enforcement on, contract docs | 1 day |
| **Phase A** | **about 2–2.5 weeks** |
| B0 — Windows CI spike (parallel with A) | 0.5–1 day |
| B1 — Windows edge + CI | 2 days |
| B2 — VS Code / Cursor + session 1 | 1 day |
| B3 — hardening + session 2 | 2 days |
| **Phase B** | **about 1–1.5 weeks** |

That is roughly 3.5–4 weeks in total: about a week more than the earlier smallest-change plan.
The extra week buys the enforcement (A0, A5), sync in Java (A2), portable persisted paths (A3) and macOS CI.

## Non-goals

- **OpenJDK workspaces on Windows.** Building OpenJDK on Windows already requires Cygwin. The OpenJDK front-end's sync can still reuse `SyncCommand` on POSIX.
- **Sharing one `.lathe/` between Windows and WSL.** Absolute paths are machine-local (R3). Only committed, shared files (`lathe-run.json`, `lathe-style.json`) must be portable, and D8 makes them so.
- **A VS Code handler for the `lathe/sync` notification.** It's missing on every OS, and belongs to the [VS Code client](lathe-vscode-client.md) parity work.
- **Non-UTF-8 source encodings in the server.** R5 forbids default-charset APIs, but honoring the project's `-encoding` is a separate feature.
- **PowerShell scripts** (D1).

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

1. Should B4 (Neovim, Emacs, Zed on Windows) be a goal for this effort, or wait for a request?
2. Which Windows versions do the maintainer's and the friend's machines run? That decides whether a Windows 10 VM is needed.
