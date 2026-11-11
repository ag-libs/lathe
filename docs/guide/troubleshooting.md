# Troubleshooting

Common problems and their fixes.
If yours isn't here, [open an issue](https://github.com/ag-libs/lathe/issues) with the Lathe version, your editor, and the server log from a run with `LATHE_DEBUG=1`.

- **No Lathe features, or a "launcher not found" notice** — Lathe isn't active in the build yet. Run the
  first build at the root of the build, which sets up Lathe without running the tests:
  `mvn clean test -Dlathe.capture.only=true` on Maven, `./gradlew latheSync -Plathe.capture.only=true` on Gradle.
  If it still isn't working, confirm Lathe is set up in the build ([Maven](maven.md), [Gradle](gradle.md)).
  On Maven, a build log with no `[lathe]` lines means Lathe didn't load: check `mvn -v` (or the project's
  `.mvn/wrapper`) for Maven 3.6.3 or newer.
  On Gradle, the build prints `[lathe]` lines only for warnings; `./gradlew help --task latheSync` shows whether
  the plugin is applied.
- **The build stops with `[lathe] This build is older than Lathe supports`** — Lathe needs Maven 3.6.3+
  with `maven-compiler-plugin` 3.6.2+, or Gradle 8.8+. The message lists each problem with its fix: upgrade
  the build tool (or the project's wrapper), or on Maven pin a newer compiler plugin in
  `<pluginManagement>`. To build without Lathe, add the switch the message names (`-Dlathe.disabled=true`
  on Maven, `-Plathe.disabled=true` on Gradle).
- **The server runs on the wrong JDK** — the server's `[startup]` log line, the MCP server's, and on Maven the
  build's `[lathe]` lines name the JVM they run on (`java=… java.home=…`); compare it with the JDK your project
  targets, and see [choosing the server JDK](installation.md#choosing-the-server-jdk-lathe_java_home).
- **Go to definition into the JDK (`String`, `List`) finds nothing** — the JDK the build ran on has no sources (`lib/src.zip` under its home).
  The build's sync logs `[sync] JDK sources are missing: …`, and the editor says so the first time a jump into the JDK finds nothing.
  Install the JDK's sources (on Debian/Ubuntu the `openjdk-<version>-source` package; most other JDK installers include them or have a source option),
  or build with a JDK that has `lib/src.zip` (`JAVA_HOME`, or the Gradle toolchain), then sync again.
- **The server won't attach, or crashes** — set `LATHE_DEBUG=1` before launching your editor and check
  its LSP log (Neovim: [cheatsheet](editors/neovim.md#verbose-logging)). An unexpected exit
  is also surfaced as an editor notification pointing at the log, and a server that fails at JVM
  startup writes its error there too.
- **A build check fails on files under `.lathe/`, from the second build on** — a check that scans the
  whole project directory, not just the source roots, finds the compiled classes and resources Lathe
  keeps in `.lathe/` (from the first build's sync on, so the first build passes).
  nohttp is the usual one: a copied SBOM or a resource with an `http://` URL fails it.
  Exclude `.lathe/` from the check, as you exclude `target/` or `build/`:

  ```xml
  <!-- Maven: the maven-checkstyle-plugin execution that runs nohttp-checkstyle -->
  <excludes>**/.git/**/*,**/target/**/*,**/.lathe/**/*</excludes>
  ```

  ```groovy
  // Gradle: the io.spring.nohttp plugin's own source set (an exclude on its checkstyleNohttp task is lost)
  nohttp {
    source.exclude '**/.lathe/**'
  }
  ```

  Do the same for other whole-project checks (a license-header or RAT check that does not read
  `.gitignore`), and add `.lathe/` to `.dockerignore` if a Docker build copies the project directory.
- **Another Java language server (jdtls) misbehaves when Lathe is present** — a co-running Eclipse JDT LS
  scans Lathe's generated `.lathe/` mirror and can treat it as duplicate projects. Add `**/.lathe/**` to
  its `java.import.exclusions`; see
  [installation.md](installation.md#coexisting-with-another-java-language-server-jdtls).
- **Lombok: "cannot find symbol" on a generated member, or the server stops on opening a Lombok
  file** — Lathe runs Lombok from the module's processor path (or class path), unless the build turns
  annotation processing off (`<proc>none</proc>`, `-proc:none`);
  re-sync after adding it. The server stopping on open means the JDK is newer than the project's Lombok
  supports, which fails the build too: upgrade Lombok. See [Lombok](how-it-works.md#lombok) for what
  works and its limits.
- **Errors like `package <relocated package> does not exist`, or Lathe's sync failing to compile a module,
  on a Maven project that shades a library** — a module whose main jar `maven-shade-plugin` replaces holds
  the relocated classes its dependents compile against, and only `package` builds that jar. Build with
  `package` once, and have Lathe's sync build there too: `-Dlathe.sync.phase=package` in
  `.mvn/maven.config`. The build warns with this line when it sees such a module; see
  [Maven](maven.md#what-the-build-writes-to-lathe).
- **A processor or plugin needs extra JVM access in the editor** — Lathe analyzes with an in-process
  javac. Sync carries the build's own module grants into `.lathe/jvm.args` automatically (on Maven, forked
  `-J--add-exports`/`--add-opens`, `.mvn/jvm.config`, and `jdk.compiler` grants in `MAVEN_OPTS`; on Gradle,
  those in `org.gradle.jvmargs` and in a forked compile's `options.forkOptions.jvmArgs`);
  restart the server after it changes. Add anything else (or tune heap/GC) with
  `LATHE_JVM_OPTS`; see
  [installation.md](installation.md#tuning-the-server-jvm-lathe_jvm_opts).
- **A test passes with the build but fails when run from the editor, unable to find a source or generated
  file** — Lathe runs a test from its own copy of the compiled classes under `.lathe/`, not from `target/` or
  `build/`. A test that finds files by walking up from its own class file (`getResource(...).getFile()`,
  `getProtectionDomain().getCodeSource().getLocation()`) to the build's folders (`src/<sourceSet>/java`,
  `target/generated-sources/annotations`) lands under `.lathe/` instead: annotation-processor and code-generator
  tests do this, such as Hibernate ORM's processor tests and Helidon's data-repository tests.
  To run such a test, run it with the build (`./gradlew :module:test --tests 'com.example.SomeTest'`,
  `mvn -pl module test -Dtest=SomeTest`); everything else keeps running from the editor.
  To fix it in the project, find the files without the class file's location, which works for the build and the
  editor alike:
  - relative to the working directory, which is the module's own folder for Gradle, Surefire and Lathe:
    `Path.of("src", "test", "java")`;
  - or from a system property the build sets, the most robust, since the build knows its layout. On Gradle,
    `test { systemProperty 'test.sourceDir', file('src/test/java').absolutePath }`; on Maven, in Surefire's
    `<argLine>-Dtest.sourceDir=${project.basedir}/src/test/java</argLine>`, not `<systemPropertyVariables>`,
    which Lathe can't see. The test reads `System.getProperty("test.sourceDir")`.
