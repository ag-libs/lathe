# Troubleshooting

## The editor has no Java features

If the editor reports a missing launcher, run the [first build](../../README.md#first-build) from the project root.
The build creates `.lathe/lathe-launcher.sh` or, on Windows, `.lathe\lathe-launcher.cmd`.

- **Maven.** Look for `[lathe]` in the build log.
  If the extension didn't load, check its registration and the Maven version reported by `mvn -v` or the project's wrapper. [Maven requirements](maven.md#requirements)
- **Gradle.** Run `./gradlew help --task latheSync` to see whether the settings plugin was applied.
  A successful Gradle build may print no `[lathe]` lines. [Gradle setup](gradle.md#setup)

If the launcher exists but the server exits, set `LATHE_DEBUG=1` before starting the editor and reproduce the problem.
The editor's LSP log includes JVM startup errors and the reason for an unexpected exit. [Neovim logging](editors/neovim.md#verbose-logging)

## The build or server uses the wrong version

**“This build is older than Lathe supports.”**
Use Maven 3.6.3+ with `maven-compiler-plugin` 3.6.2+, or Gradle 8.8+.
The message identifies what to upgrade, including a project wrapper or a compiler plugin pinned in Maven's `<pluginManagement>`.
To run that build with Lathe disabled, use `-Dlathe.disabled=true` on Maven or `-Plathe.disabled=true` on Gradle.

**The server uses an unexpected JDK.**
Look at `java=` and `java.home=` in the server's `[startup]` log line.
The MCP server reports its JDK there too, and Maven reports its JVM in the build's `[lathe]` lines.
Compare the paths with the JDK you intended to use. [Choose the server JDK](installation.md#choosing-the-server-jdk-lathe_java_home)

## Navigation

**Go to definition on a JDK type such as `String` goes nowhere.**
Check for `lib/src.zip` under the JDK home recorded by the build.
If it's missing, sync reports `[sync] JDK sources are missing` and the editor tells you on the first failed jump.
Install sources for that JDK (for example, the matching `openjdk-<version>-source` package on Debian or Ubuntu), or select a JDK with sources through `JAVA_HOME` or the Gradle toolchain.
Sync again after the change.

**A library type has no source to open.**
Check whether the dependency publishes a readable sources JAR.
Sync warns about an unreadable archive.
The editor names the dependency on the first failed jump, and the agent's `get_definition` names it each time.
Hover or `describe_symbol` can still show the declaration from the class file.

**Eclipse JDT LS reports duplicate projects.**
Add `**/.lathe/**` to its `java.import.exclusions`.
On Maven, also keep Lathe out of JDT LS's own project import. [Run both servers](installation.md#coexisting-with-another-java-language-server-jdtls)

## Build checks and compiler settings

**A project-wide check fails on files under `.lathe/` after the first build.**
Lathe copies classes and resources into that directory.
A check such as nohttp may flag a URL in a copied resource on the next build.
Exclude the directory from the check, as you would a build output directory.

```xml
<!-- Maven: the maven-checkstyle-plugin execution that runs nohttp-checkstyle -->
<excludes>**/.git/**/*,**/target/**/*,**/.lathe/**/*</excludes>
```

```groovy
// Gradle: configure the io.spring.nohttp plugin's source set
nohttp {
  source.exclude '**/.lathe/**'
}
```

Apply the same exclusion to license-header or RAT checks that scan generated output.
Add `.lathe/` to `.dockerignore` if a Docker build copies the project directory.

**A Maven module reports a missing relocated package.**
When `maven-shade-plugin` replaces a module's main JAR, downstream modules need the JAR produced by the `package` phase.
Build through `package` once and add `-Dlathe.sync.phase=package` to `.mvn/maven.config` for future syncs.
Lathe prints this setting when it detects such a module. [Maven build output](maven.md#what-the-build-writes-to-lathe)

**A processor or compiler plugin needs extra JVM access.**
Sync copies module grants to `.lathe/jvm.args`.
On Maven these include the forked compiler's `-J--add-exports` and `-J--add-opens`, grants in `.mvn/jvm.config`, and `jdk.compiler` grants in `MAVEN_OPTS`.
On Gradle they include grants in `org.gradle.jvmargs` or the compiler task's `options.forkOptions.jvmArgs`.
Restart the server after changing them.
Use `LATHE_JVM_OPTS` for additional JVM options. [Server JVM settings](installation.md#tuning-the-server-jvm-lathe_jvm_opts)

## Lombok

**A generated getter or builder is missing.**
Check that Lombok is on the module's processor path or classpath and that the build enables annotation processing.
Settings such as `<proc>none</proc>` and `-proc:none` turn it off.
Sync after adding Lombok.
If the server stops when you open a Lombok file, check that the project's Lombok version supports the JDK running the server. [Lombok support](how-it-works.md#lombok)

**An open file still uses the old `lombok.config`.**
Lombok rereads its configuration at most every two seconds during editor analysis.
After that interval, edit or reopen the file.
Lathe's writes to `.lathe/` and the agent's `verify_change` use the new settings immediately.

## A test passes in the build but can't find a file in the editor

Lathe loads test classes from `.lathe/`.
Tests that locate source or generated files by walking up from their own class file can therefore end up under the wrong directory.
This affects tests using `getResource(...).getFile()` or `getProtectionDomain().getCodeSource().getLocation()` to locate files such as annotation-processor fixtures.

To run the test with its original layout, use `mvn -pl module test -Dtest=SomeTest` or `./gradlew :module:test --tests 'com.example.SomeTest'`.
To make the test work in both environments, use one of these paths.

- Resolve the path from the test’s working directory.
  Gradle and Surefire default to the module directory, so `Path.of("src", "test", "java")` points to the test sources.
  If your build sets a different working directory, Lathe uses that directory too.
- Pass the directory explicitly and read it with `System.getProperty("test.sourceDir")`.
  In Gradle, use `test { systemProperty 'test.sourceDir', file('src/test/java').absolutePath }`.
  In Maven Surefire, use `<argLine>-Dtest.sourceDir=${project.basedir}/src/test/java</argLine>` so the option reaches the JVM launch Lathe captures. [Test capture limits](test-capture.md#requirements-and-current-limits)

If you still need help, include your editor, Lathe and build versions, and the relevant server log when you [report the problem](../../CONTRIBUTING.md#reporting-a-bug).
