# Lathe — Launcher JVM Options

## Goal

Allow users to tune the Lathe server JVM without editing generated launcher scripts.
The generated `lathe-launcher.sh` should honor an optional `LATHE_JVM_OPTS` environment variable.

Example:

```bash
export LATHE_JVM_OPTS="-Xmx4g -Xms512m -XX:+UseZGC"
```

## Current State

`lathe:sync` renders `~/.cache/lathe/servers/<version>/lathe-launcher.sh` from `ServerInstaller`.
The script currently invokes `java` with Lathe's fixed module and javac-access arguments only.
It does not expand `LATHE_JVM_OPTS`.

## Design

Render the launcher so user options appear immediately after `java` and before Lathe's fixed arguments:

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

Update launcher rendering tests and invoker verification to assert that generated launchers contain
`${LATHE_JVM_OPTS:-}` before Lathe's fixed JVM arguments.
