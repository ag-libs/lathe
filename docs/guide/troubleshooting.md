# Troubleshooting

Common problems and their fixes.
If yours isn't here, [open an issue](https://github.com/ag-libs/lathe/issues) with the Lathe version, your editor, and the server log from a run with `LATHE_DEBUG=1`.

- **No Lathe features, or a "launcher not found" notice** — Lathe isn't active in the build yet. Run the
  first build at the root of the build, which sets up Lathe without running the tests:
  `mvn clean test -Dlathe.capture.only=true` on Maven, `./gradlew latheSync -Plathe.capture.only=true` on Gradle.
  If it still isn't working, confirm Lathe is set up in the build ([Maven](maven.md), [Gradle](gradle.md)).
  A build log with no `[lathe]` lines means Lathe didn't load: on Maven, check `mvn -v` (or the project's
  `.mvn/wrapper`) for Maven 3.6.3 or newer.
- **The build stops with `[lathe] This build is older than Lathe supports`** — Lathe needs Maven 3.6.3+
  with `maven-compiler-plugin` 3.6.2+, or Gradle 8.8+. The message lists each problem with its fix: upgrade
  the build tool (or the project's wrapper), or on Maven pin a newer compiler plugin in
  `<pluginManagement>`. To build without Lathe, add the switch the message names (`-Dlathe.disabled=true`
  on Maven, `-Plathe.disabled=true` on Gradle).
- **The server won't attach, or crashes** — set `LATHE_DEBUG=1` before launching your editor and check
  its LSP log (Neovim: [cheatsheet](editors/neovim.md#verbose-logging)). An unexpected exit
  is also surfaced as an editor notification pointing at the log, and a server that fails at JVM
  startup writes its error there too.
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
- **A processor or plugin needs extra JVM access in the editor** — Lathe analyzes with an in-process
  javac. Sync carries the build's own module grants into `.lathe/jvm.args` automatically (on Maven, forked
  `-J--add-exports`/`--add-opens`, `.mvn/jvm.config`, and `jdk.compiler` grants in `MAVEN_OPTS`; on Gradle,
  those in `org.gradle.jvmargs`);
  restart the server after it changes. Add anything else (or tune heap/GC) with
  `LATHE_JVM_OPTS`; see
  [installation.md](installation.md#tuning-the-server-jvm-lathe_jvm_opts).
