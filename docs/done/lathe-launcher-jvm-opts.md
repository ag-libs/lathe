# Lathe — Launcher JVM Options

## Status

Implemented. Both generated launchers (`lathe-launcher.sh` and the MCP launcher) expand
`LATHE_JVM_OPTS` immediately after `java`, before Lathe's fixed arguments.

## Goal

Allow users to tune the Lathe server JVM without editing generated launcher scripts.
The generated `lathe-launcher.sh` should honor an optional `LATHE_JVM_OPTS` environment variable.

Example:

```bash
export LATHE_JVM_OPTS="-Xmx4g -Xms512m -XX:+UseZGC"
```

## Relation to compiler-arg capture

`LATHE_JVM_OPTS` is the sanctioned successor to the per-project `-J` options a Maven build passes to a
**forked** `javac` (`maven-compiler-plugin` `fork=true`). The language server compiles in-process, so
it drops those `-J` flags (the in-process javac API rejects them as `invalid flag`; see
`ModuleSourceCompiler.dropForkedLauncherArgs` and [the design overview](../lathe-design.md)). Most
`-J--add-exports`/`-J--add-opens` into `jdk.compiler` are already covered by the launcher's fixed
javac-access grants, which is why in-process Error Prone works. When an in-process processor or javac
plugin genuinely needs JVM access the launcher does not grant, `LATHE_JVM_OPTS` is how the user
restores it — e.g. `export LATHE_JVM_OPTS="--add-opens jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED"`.

## Implementation

`lathe:sync` renders `~/.cache/lathe/servers/<version>/lathe-launcher.sh` (and the MCP launcher) from
`ServerInstaller`. Each script expands `LATHE_JVM_OPTS` immediately after `java` and before Lathe's
fixed module and javac-access arguments:

```sh
#!/bin/sh
exec java ${LATHE_JVM_OPTS:-} \
  --add-modules java.net.http \
  --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
  ... \
  --module-path /abs/.m2/... \
  -m io.github.aglibs.lathe.server/io.github.aglibs.lathe.server.LatheServer "$@"
```

This keeps generated launchers stateless.
Users configure shell startup files, editor environment, or command wrappers instead of modifying files under
`~/.cache/lathe/`.

## Tradeoff

Using shell word splitting is intentional for this small feature.
It supports normal JVM option strings such as `-Xmx4g -XX:+UseZGC`.
Values requiring embedded spaces or shell quoting are not a target use case.

## Tests

`ServerInstallerTest` asserts both rendered launchers contain `${LATHE_JVM_OPTS:-}` ahead of Lathe's
fixed JVM arguments.
