# Lathe — Workspace JVM Args (`.lathe/jvm.args`)

## Status

Implemented.
`lathe:sync` writes `.lathe/jvm.args`; both generated launchers pass it to `java` as an `@argfile`.

## Problem

Classpath javac plugins (Error Prone, NullAway, Checker Framework, ...) run inside the server's in-process javac, in the unnamed module.
They access `jdk.compiler` internals directly and need `--add-exports`/`--add-opens ...=ALL-UNNAMED`.
The editor launcher used to grant a fixed Error Prone-shaped set to every workspace, whether or not it used a plugin.
That was broader than needed, and a plugin needing a different grant got nothing unless the user set `LATHE_JVM_OPTS` by hand.

The build already states what its plugins need, in one of these places:

- **Forked javac** (`maven-compiler-plugin` `<fork>true</fork>`, e.g. equalsverifier): `-J--add-exports=...` entries in `compilerArgs`.
  The server drops `-J` from the javac args, since the in-process javac API rejects them.
- **In-process javac** (no fork, e.g. dropwizard): `--add-exports ...` lines in `<root>/.mvn/jvm.config`, which Maven applies to its own JVM.
  The same grants can also reach Maven's JVM from `MAVEN_OPTS`.

The project's pinned formatter needs grants too: google-java-format and palantir-java-format use javac internals and run in the server's unnamed module (see [Pinned Formatters](lathe-pinned-formatters.md)).

## Design

`JvmArgsWriter` (in `lathe-maven-plugin`, called by `SyncCoordinator` next to the workspace manifest) collects, from four sources:

1. `-J` entries of `maven-compiler-plugin` `compilerArgs` (and the legacy single-string `compilerArgument`, split on whitespace), plugin-level and per execution, across every reactor project, with the `-J` prefix stripped.
2. Whitespace-separated tokens of `<root>/.mvn/jvm.config`, skipping `#` comment lines.
3. `MAVEN_OPTS`, read from the build's environment (the `env.MAVEN_OPTS` session property), keeping only its `jdk.compiler` grants.
   It often carries unrelated JVM tuning or `java.base` opens meant for Maven itself.
4. The pinned formatter's grants (`LatheFlags.FORMATTER_JAVAC_GRANTS`, six `jdk.compiler` exports to `ALL-UNNAMED`) when `.lathe/style.json` selects google-java-format or palantir-java-format.
   They are written even when the build itself declares none, since a project may run Spotless only in CI; Eclipse JDT needs none.

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

The editor launcher carries no javac grants at all: the server itself (a named module) uses no javac internals, and everything that does — classpath javac plugins and the pinned formatter — gets them from this file.
The MCP launcher keeps its own `ALL-UNNAMED` grants: it runs on the classpath, so its in-process javac runs in the unnamed module and needs them for itself.

`MAVEN_OPTS` is read from the environment, not from the running JVM's own arguments, so `mvn` and `mvnd` produce the same file.
mvnd never applies `MAVEN_OPTS` to its long-lived daemon JVM, whose arguments are fixed when it starts, but it forwards the client's environment to every build.
Reading the JVM's arguments would therefore make the file depend on which tool ran the sync, and switching tools would rewrite it and require a server restart.

## Limits

- Grants added only at runtime (by another plugin) are not seen; `LATHE_JVM_OPTS` remains the escape hatch.
- `~/.mavenrc` is a script only plain `mvn` runs, so a `MAVEN_OPTS` it sets reaches sync only under `mvn`, and only when exported; set such grants in `LATHE_JVM_OPTS` instead.
- The server reads the file only at startup; after a sync changes it, restart the server.
- Grants written for another purpose are carried over too (for example Spotless/google-java-format's `jdk.compiler` exports in `.mvn/jvm.config`).
  That only widens `jdk.compiler` access inside the server JVM.
  A grant the server JDK cannot apply (an unknown package or target module) makes the JVM print a startup warning, not fail.
- One server JVM serves the whole reactor, so the file is the reactor-wide union.
- A workspace synced before this change loses on-save plugin runs until its next `mvn process-test-classes`.

## Tests

`JvmArgsWriterTest` covers all sources together (forked `-J` in both forms, an execution duplicate, the single-string `compilerArgument`, `jvm.config` with comments and unrelated flags, `MAVEN_OPTS` with a skipped non-`jdk.compiler` grant, the formatter's grants deduped with the build's) and the no-grant case deleting a stale file.
`ServerInstallerTest` asserts both launchers wire the `@argfile` between the stdout guard and `LATHE_JVM_OPTS`, and that only the MCP launcher script carries grants of its own.
