# Lathe

Lathe is a Java language server for Maven projects.
It uses Maven itself as the source of truth:
the compiler shim records the exact `javac` parameters Maven used,
and the Maven plugin refreshes workspace metadata and dependency sources.

Lathe setup has two parts:

- `lathe-compiler` is configured as the `maven-compiler-plugin` compiler.
- `lathe-maven-plugin` declares two goals in the reactor root: `init` (auto-bound to `initialize`) and `sync` (auto-bound to `process-test-classes`).

## Installation

Choose the Lathe version once in the POM where you manage plugin versions:

```xml
<properties>
    <lathe.version>0.1.0-SNAPSHOT</lathe.version>
</properties>
```

Configure the compiler shim wherever the effective `maven-compiler-plugin` configuration for Java modules is defined.
For a simple project, this is usually the parent POM:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <dependencies>
        <dependency>
            <groupId>io.github.ag-libs</groupId>
            <artifactId>lathe-compiler</artifactId>
            <version>${lathe.version}</version>
        </dependency>
    </dependencies>
    <configuration>
        <compilerId>lathe</compilerId>
    </configuration>
</plugin>
```

Keep any existing compiler configuration.
Only add the `lathe-compiler` dependency and `<compilerId>lathe</compilerId>`.

Bind both goals in the reactor root POM:

```xml
<plugin>
    <groupId>io.github.ag-libs</groupId>
    <artifactId>lathe-maven-plugin</artifactId>
    <version>${lathe.version}</version>
    <inherited>false</inherited>
    <executions>
        <execution>
            <id>lathe-init</id>
            <goals>
                <goal>init</goal>
            </goals>
        </execution>
        <execution>
            <id>lathe-sync</id>
            <goals>
                <goal>sync</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

Do not add explicit `<phase>` elements.
`init` defaults to `initialize` (the first Maven phase, before `compile`).
`sync` defaults to `process-test-classes` (after main and test compilation).

The `<inherited>false</inherited>` entry is important in multi-module reactors.
Both goals are reactor-level steps and should run once from the root,
not once for every child module.

Refresh Lathe state with:

```bash
mvn process-test-classes
```

The same refresh also happens during normal builds that reach `process-test-classes`,
such as `mvn test`, `mvn package`, or `mvn install`.

Add `.lathe/` to `.gitignore`.

## What The Build Writes

`lathe:init` creates `.lathe/` at the workspace root on the first build.
It runs automatically at the `initialize` phase — no manual invocation needed.

The compiler shim writes compilation parameter files under `.lathe/` as each module compiles.
Those files contain the classpath, module path, source roots, generated-source locations,
annotation processor settings, and other `javac` inputs that the language server needs.

`lathe:sync` resolves dependency source JARs through Maven and writes `workspace.json`.
The source JARs are downloaded into the normal local Maven repository.
They are resolved on demand by Maven instead of being guessed from POM text.
The write is skipped when the content is unchanged, so a no-op build does not trigger
a server reload.

## Opt-out and CI

Lathe is active by default and skips automatically in CI environments:

| Condition | Effect |
|---|---|
| `CI` environment variable is set | both `init` and `sync` are skipped |
| `-Dlathe.skip=true` | disabled regardless of other settings |
| `-Dlathe.skip=false` | enabled, overrides `CI` |

## Partial builds

When Maven is invoked with `-pl`, `lathe:sync` skips writing `workspace.json`
to avoid overwriting the full workspace manifest with a partial view.
Module params files are still written by the compiler shim for compiled modules.
To force a workspace manifest write from a partial build, pass `-Dlathe.sync.force=true`.

## Non-Trivial Maven Projects

Some projects have a reactor root that is not the same POM as the parent used by Java modules.
In that layout, install the two Lathe pieces in different places:

- Put `lathe-maven-plugin` with `<inherited>false</inherited>` in the reactor root POM,
  the POM from which users run `mvn process-test-classes`.
- Put the `maven-compiler-plugin` Lathe configuration in every parent or standalone POM
  that controls Java compilation for modules in the reactor.

Do not assume the top-level reactor POM is inherited by every module.
Check each module's effective compiler configuration.
If a module has its own `maven-compiler-plugin` block,
add the Lathe compiler dependency and `<compilerId>lathe</compilerId>` there too.

## Agent Installation Prompt

Use this prompt when asking an agent to configure Lathe in an existing Maven project:

```text
Configure Lathe in this Maven project.

Use version 0.1.0-SNAPSHOT unless the repository already defines a Lathe version property.

Install two pieces:

1. Configure maven-compiler-plugin so Java modules use the Lathe compiler shim.
   Add dependency io.github.ag-libs:lathe-compiler:${lathe.version}
   to the effective maven-compiler-plugin configuration and set <compilerId>lathe</compilerId>.
   Preserve all existing compiler configuration, annotation processor paths, release/source/target settings,
   compiler args, plugin management, and executions.

2. Bind io.github.ag-libs:lathe-maven-plugin:${lathe.version} in the reactor root POM.
   Add two executions: id lathe-init with goal init, and id lathe-sync with goal sync.
   Do not specify phases; init defaults to initialize and sync defaults to process-test-classes.
   Set <inherited>false</inherited> so both goals run once at the reactor root.

Do not assume the reactor root POM is the parent POM.
Inspect the Maven structure first.
If Java modules inherit compiler configuration from a separate parent POM,
put the compiler shim there.
If standalone modules or example projects define their own compiler plugin configuration,
add the shim there as well.

After editing, run:

mvn process-test-classes

If mvnd is used, restart it after rebuilding Lathe itself:

mvn --stop
```
