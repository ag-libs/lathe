# Lathe — Workspace-Specific Server JDK

## Status

Implemented. The server launcher runs under the project's build JDK by default, overridable with
`LATHE_JAVA_HOME`.

## Goal

Run the language server — and therefore its in-process `javac` — under a JDK compatible with the
project, without the user pinning it by hand. If a workspace targets Java 26 but the user's `PATH`
`java` is older, `javac` cannot even parse the sources and every file shows spurious errors. The
server JVM should default to the JDK the build used.

## Why the launcher, and why per-workspace

The JDK must be chosen *before* the JVM starts, so the decision belongs to the launcher, not the
running server (which only learns the workspace from the LSP `rootUri` after start). The launcher is
shared per server version (`~/.cache/lathe/servers/<version>/lathe-launcher.sh`, which each workspace
links at as `.lathe/lathe-launcher.sh`) and is deliberately workspace-blind — we do not pass it a
workspace directory. Instead:

- the **build** already resolves the JDK and `lathe:sync` records it in `.lathe/`, and
- the **client** already computes the workspace root to start the server.

So the launcher reads the captured JDK **relative to its working directory**, and the client starts
the server with its cwd set to the workspace root. No workspace parameter is introduced.

## Resolution precedence (in the launcher)

1. `LATHE_JAVA_HOME` — explicit user override, mirroring `LATHE_JVM_OPTS`.
2. `.lathe/java-home` — the build JDK captured by `lathe:sync` (read from the server's cwd).
3. `java` on `PATH` — the previous behavior (fallback).

If the chosen home has no `bin/java`, the launcher warns on stderr and falls back to `PATH`.

## Pieces

- **`lathe:sync`** writes the build JDK (`JdkSource.home`, already resolved for the workspace manifest)
  to a plain one-line `.lathe/java-home` — plain text so the POSIX launcher needs no JSON parser.
  Constant `LatheLayout.JAVA_HOME_FILE`; skip-if-unchanged like the manifest.
- **`ServerInstaller`** prepends a shared resolution prologue (`javaResolvePrologue()`) to both the
  editor and MCP launchers, which then `exec "$java_bin"`. Kept in a method body, not a `static final`
  field, so the maven-plugin descriptor's QDOX parser skips the text block.
- **nvim client** sets the server's working directory to the resolved root. `cmd` is a function
  (`vim.lsp.rpc.start({ launcher }, dispatchers, { cwd = config.root_dir })`) — the only hook that
  sees the per-buffer `root_dir`, and it covers both the auto-start path and `M.start`. No JDK logic
  lives in Lua; the launcher owns the whole precedence chain, so other clients get it for free by
  honoring the same contract (spawn with cwd = workspace root).

## Caveats

- **One JVM per reactor.** A single server process serves the whole reactor, so it must run a JDK ≥ the
  highest module release. The build JDK (`System.getProperty("java.home")`, the JVM Maven ran on) is
  normally the highest. A project using `maven-toolchains-plugin` with per-module JDKs could differ;
  per-module handling is a possible later item.
- **cwd contract.** A client that does not set the server cwd to the workspace root falls back to
  `PATH` java (or the user can export `LATHE_JAVA_HOME`).
- **POSIX only.** The `.sh` launcher is POSIX; a future Windows launcher would apply the same
  precedence with `\bin\java.exe`.
