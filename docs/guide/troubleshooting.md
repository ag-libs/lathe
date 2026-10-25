# Troubleshooting

Common problems and their fixes.
If yours isn't here, [open an issue](https://github.com/ag-libs/lathe/issues) with the Lathe version, your editor, and the server log from a run with `LATHE_DEBUG=1`.

- **No Lathe features, or a "launcher not found" notice** — Lathe isn't active in the build yet. Run a
  Maven build at the reactor root — `mvn process-test-classes` is the quickest (it generates Lathe's
  metadata without running tests). If it still isn't working, confirm the Lathe extension is registered
  (see [installation.md](installation.md)). A build log with no `[lathe]` lines means the
  extension didn't load: check `mvn -v` (or the project's `.mvn/wrapper`) for Maven 3.6.3 or newer.
- **The build stops with `[lathe] This build is older than Lathe supports`** — Lathe needs Maven 3.6.3+
  and `maven-compiler-plugin` 3.6.2+. The message lists each problem with its fix: upgrade Maven (or the
  project's wrapper), or pin a newer compiler plugin in `<pluginManagement>`. To build without Lathe,
  add `-Dlathe.disabled=true`.
- **The server won't attach, or crashes** — set `LATHE_DEBUG=1` before launching your editor and check
  its LSP log (Neovim: [cheatsheet](editors/neovim.md#verbose-logging)). An unexpected exit
  is also surfaced as an editor notification pointing at the log, and a server that fails at JVM
  startup writes its error there too.
- **Another Java language server (jdtls) misbehaves when Lathe is present** — a co-running Eclipse JDT LS
  scans Lathe's generated `.lathe/` mirror and can treat it as duplicate projects. Add `**/.lathe/**` to
  its `java.import.exclusions`; see
  [installation.md](installation.md#coexisting-with-another-java-language-server-jdtls).
- **A processor or plugin needs extra JVM access in the editor** — Lathe analyzes with an in-process
  javac. Sync carries the build's own module grants (forked `-J--add-exports`/`--add-opens`,
  `.mvn/jvm.config`, and `jdk.compiler` grants in `MAVEN_OPTS`) into `.lathe/jvm.args` automatically;
  restart the server after it changes. Add anything else (or tune heap/GC) with
  `LATHE_JVM_OPTS`; see
  [installation.md](installation.md#tuning-the-server-jvm-lathe_jvm_opts).
