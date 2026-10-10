# Lathe — Workspace JVM Args (`.lathe/jvm.args`)

## Status

Implemented.
`lathe:sync` writes `.lathe/jvm.args`; both generated launchers pass it to `java` as an `@argfile`.

## Problem

Classpath javac plugins (Error Prone, NullAway, Checker Framework, ...) run inside the server's in-process javac, in the unnamed module.
They access `jdk.compiler` internals directly and need `--add-exports`/`--add-opens ...=ALL-UNNAMED`.
The editor launcher used to grant a fixed Error Prone-shaped set to every workspace, whether or not it used a plugin.
That was broader than needed, and a plugin needing a different grant got nothing unless the user set `LATHE_JVM_OPTS` by hand.

The build already states what its plugins need, in one of two places:

- **Forked javac** (`maven-compiler-plugin` `<fork>true</fork>`, e.g. equalsverifier): `-J--add-exports=...` entries in `compilerArgs`.
  The server drops `-J` from the javac args, since the in-process javac API rejects them.
- **In-process javac** (no fork, e.g. dropwizard): `--add-exports ...` lines in `<root>/.mvn/jvm.config`, which Maven applies to its own JVM.

## Design

`JvmArgsWriter` (in `lathe-maven-plugin`, called by `SyncCoordinator` next to the workspace manifest) collects, from both sources:

1. `-J` entries of `maven-compiler-plugin` `compilerArgs`, plugin-level and per execution, across every reactor project, with the `-J` prefix stripped.
2. Whitespace-separated tokens of `<root>/.mvn/jvm.config`, skipping `#` comment lines.

It keeps only `--add-exports` and `--add-opens`, normalizes the two-token form to `--add-exports=<value>`, dedupes in order, and writes one flag per line.
Other flags are ignored on purpose: a forked compiler's `-J-Xmx256m` or the build's heap/GC tuning would starve the long-running server.
When nothing is collected the file is deleted, so a removed plugin never leaves a stale grant.
Like the manifest, it is skipped on partial (`-pl`) reactors.

The `-J` flags come from the Maven model, not the captured `lsp-params-*.json` files:
sync runs in the reactor root, which builds before any child module compiles, so the params files would lag a build behind (or be absent on a fresh clone).

The launcher hands the file to the JVM as a native argument file, so the shell never parses it:

```sh
jvm_args=
if [ -r .lathe/jvm.args ]; then
  jvm_args=@.lathe/jvm.args
fi
exec "$java_bin" <stdout guard> $jvm_args ${LATHE_JVM_OPTS:-} \
  ...
```

The path is relative to the server's cwd, the workspace root, as with `.lathe/java-home`.
`LATHE_JVM_OPTS` follows the file, so user flags still win.

The editor launcher no longer carries any `ALL-UNNAMED` grants: the server itself uses no javac internals, and google-java-format keeps its module-qualified grants.
The MCP launcher keeps its `ALL-UNNAMED` grants, because it runs on the classpath and google-java-format is in the unnamed module there.

## Limits

- Grants added only at runtime (by another plugin, or via `MAVEN_OPTS`) are not seen; `LATHE_JVM_OPTS` remains the escape hatch.
- One server JVM serves the whole reactor, so the file is the reactor-wide union.
- A workspace synced before this change loses on-save plugin runs until its next `mvn process-test-classes`.

## Tests

`JvmArgsWriterTest` covers both sources together (forked `-J` in both forms, an execution duplicate, `jvm.config` with comments and unrelated flags) and the no-grant case deleting a stale file.
`ServerInstallerTest` asserts both launchers wire the `@argfile` between the stdout guard and `LATHE_JVM_OPTS`, and that only the MCP launcher has `ALL-UNNAMED` grants.
