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

The language server compiles in-process, so it drops the `-J` options a Maven build passes to a
**forked** `javac` (the in-process javac API rejects them as `invalid flag`; see
`ModuleSourceCompiler.dropForkedLauncherArgs`).
Their module grants (`--add-exports`/`--add-opens`), those in `.mvn/jvm.config`, and `jdk.compiler`
grants in `MAVEN_OPTS` are carried over
automatically by sync into `.lathe/jvm.args` (see [Workspace JVM Args](lathe-jvm-args.md)).
`LATHE_JVM_OPTS` covers everything else: heap/GC tuning, or access the build does not declare —
e.g. `export LATHE_JVM_OPTS="--add-opens jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED"`.

## Implementation

`lathe:sync` renders `~/.cache/lathe/servers/<version>/lathe-launcher.sh` (and the MCP launcher) from
`ServerInstaller`. Each script expands `LATHE_JVM_OPTS` after the stdout-guard flags and the
workspace's `.lathe/jvm.args`, and before Lathe's fixed arguments (the module setup; the MCP launcher, which
runs on the class path, also carries its own javac grants):

```sh
#!/bin/sh
exec java -XX:+DisplayVMOutputToStderr -Xlog:disable -Xlog:all=warning:stderr $jvm_args ${LATHE_JVM_OPTS:-} \
  --add-modules java.net.http \
  --module-path /abs/.m2/... \
  -m io.github.aglibs.lathe.server/io.github.aglibs.lathe.server.LatheServer "$@"
```

The stdout-guard flags exist because stdout is the JSON-RPC channel.
By default the JVM prints boot failures (such as a module missing from `--module-path`) and unified-logging warnings to stdout.
The client discards that as non-protocol output, so a server that died at startup left nothing in the editor's LSP log.
`-XX:+DisplayVMOutputToStderr` moves VM output to stderr, and `-Xlog:disable -Xlog:all=warning:stderr` does the same for unified logging.
They come before `LATHE_JVM_OPTS` so user flags still take precedence.

This keeps generated launchers stateless.
Users configure shell startup files, editor environment, or command wrappers instead of modifying files under
`~/.cache/lathe/`.

## Tradeoff

Using shell word splitting is intentional for this small feature.
It supports normal JVM option strings such as `-Xmx4g -XX:+UseZGC`.
Values requiring embedded spaces or shell quoting are not a target use case.

## Tests

`ServerInstallerTest` asserts both rendered launchers contain the stdout-guard flags followed by `${LATHE_JVM_OPTS:-}`, ahead of Lathe's
fixed JVM arguments.
