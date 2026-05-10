# Lathe

Lathe is a Java language server for Maven projects.
It uses Maven itself as the source of truth:
the compiler shim records the exact `javac` parameters Maven used,
and the Maven plugin refreshes workspace metadata and dependency sources.

Lathe setup has two parts:

- `lathe-compiler` is configured as the `maven-compiler-plugin` compiler.
- `lathe-maven-plugin` is bound at the reactor root so `lathe:sync` runs after test compilation.

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

Bind `lathe:sync` in the reactor root POM:

```xml
<plugin>
    <groupId>io.github.ag-libs</groupId>
    <artifactId>lathe-maven-plugin</artifactId>
    <version>${lathe.version}</version>
    <inherited>false</inherited>
    <executions>
        <execution>
            <id>lathe-sync</id>
            <goals>
                <goal>sync</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

Do not add an explicit `<phase>`.
The `sync` goal declares `process-test-classes` as its default phase,
so Maven runs it after main and test compilation.

The `<inherited>false</inherited>` entry is important in multi-module reactors.
`lathe:sync` is a reactor-level refresh step and should run once from the root,
not once for every child module.

Initialize the checkout once:

```bash
mvn io.github.ag-libs:lathe-maven-plugin:${lathe.version}:init
```

Then refresh Lathe state with:

```bash
mvn process-test-classes
```

The same refresh also happens during normal builds that reach `process-test-classes`,
such as `mvn package` or `mvn install`.

## What The Build Writes

`lathe:init` creates `.lathe/root.marker` and clears stale workspace state.

The compiler shim writes compilation parameter files under `.lathe/` as each module compiles.
Those files contain the classpath, module path, source roots, generated-source locations,
annotation processor settings, and other `javac` inputs that the language server needs.

`lathe:sync` resolves dependency source JARs through Maven.
The source JARs are downloaded into the normal local Maven repository.
They are resolved on demand by Maven instead of being guessed from POM text.

Add `.lathe/` to `.gitignore`.

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
   Add an execution with id lathe-sync and goal sync.
   Do not specify a phase; the goal has defaultPhase process-test-classes.
   Set <inherited>false</inherited> so sync runs once at the reactor root.

Do not assume the reactor root POM is the parent POM.
Inspect the Maven structure first.
If Java modules inherit compiler configuration from a separate parent POM,
put the compiler shim there.
If standalone modules or example projects define their own compiler plugin configuration,
add the shim there as well.

After editing, run:

mvn io.github.ag-libs:lathe-maven-plugin:${lathe.version}:init
mvn process-test-classes

If mvnd is used, restart it after rebuilding Lathe itself:

mvn --stop
```

