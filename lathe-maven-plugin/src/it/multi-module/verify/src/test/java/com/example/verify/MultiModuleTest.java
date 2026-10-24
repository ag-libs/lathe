package com.example.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MultiModuleTest {

  private static final Path ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final String LATHE_VERSION = System.getProperty("lathe.version");
  private static final Path LATHE_CACHE = Path.of(System.getProperty("lathe.cache"));
  private static final int MAX_RELATIVE_PATH = 220;
  // Characters Windows forbids, a trailing dot or space, and its reserved device names.
  private static final Pattern ILLEGAL_NAME =
      Pattern.compile("(?i).*[<>:\"|?*\\\\].*|.*[. ]|(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?");

  private static Path lathe(final String rel) {
    return ROOT.resolve(".lathe").resolve(rel);
  }

  private static String read(final Path path) throws IOException {
    return Files.readString(path);
  }

  private static String readUnchecked(final Path path) {
    try {
      return read(path);
    } catch (final IOException e) {
      throw new AssertionError(e);
    }
  }

  // --- init ---

  @Test
  void init_latheDirectory_created() {
    assertThat(ROOT.resolve(".lathe")).isDirectory();
  }

  // --- sync: server distribution ---

  @Test
  void sync_serverLauncher_installed() {
    final var launcher =
        LATHE_CACHE.resolve("servers").resolve(LATHE_VERSION).resolve(Os.script("lathe-launcher"));
    assertThat(launcher).exists().isExecutable();
  }

  // Plain copies, not symlinks (a link needs admin rights on Windows), in the workspace and in the
  // cache's latest/ for user-global MCP.
  @Test
  void sync_launchers_copiedFromVersionDirToWorkspaceAndLatest() throws IOException {
    final var versionDir = LATHE_CACHE.resolve("servers").resolve(LATHE_VERSION);
    for (final var script : List.of(Os.script("lathe-launcher"), Os.script("lathe-mcp-launcher"))) {
      final String installed = Files.readString(versionDir.resolve(script));
      for (final var copy : List.of(lathe(script), LATHE_CACHE.resolve("latest").resolve(script))) {
        assertThat(copy).isExecutable().hasContent(installed);
      }
    }

    // Each launcher reads the argfile beside it, so every copy carries the pair.
    for (final var argfile : List.of("lathe-server.args", "lathe-mcp.args")) {
      final String installed = Files.readString(versionDir.resolve(argfile));
      for (final var copy : List.of(lathe(argfile), LATHE_CACHE.resolve("latest").resolve(argfile))) {
        assertThat(copy).hasContent(installed);
      }
    }
  }

  // The launcher names no jar; its argfile holds the module path and ends with the entry point.
  @Test
  void sync_launcherAndArgfile_holdTheRequiredJvmArgs() throws IOException {
    final Path versionDir = LATHE_CACHE.resolve("servers").resolve(LATHE_VERSION);
    final String launcher = read(versionDir.resolve(Os.script("lathe-launcher")));
    if (Os.WINDOWS) {
      assertThat(launcher).startsWith("@echo off\r\n");
      assertThat(launcher).contains("@.lathe\\jvm.args", "@\"%~dp0lathe-server.args\"");
    } else {
      assertThat(launcher).startsWith("#!/bin/sh\n");
      assertThat(launcher).contains("@.lathe/jvm.args", "@\"$(dirname \"$0\")/lathe-server.args\"");
    }

    assertThat(launcher).doesNotContain(".jar");
    final List<String> args = Files.readAllLines(versionDir.resolve("lathe-server.args"));
    assertThat(args).startsWith("--add-modules", "java.net.http", "--module-path");
    assertThat(args).endsWith("-m", "io.github.aglibs.lathe.server/io.github.aglibs.lathe.server.LatheServer");
    assertThat(args).noneMatch(arg -> arg.contains("=ALL-UNNAMED"));
    // A pinned formatter runs from an isolated classloader; nothing targets a formatter module.
    assertThat(args).noneMatch(arg -> arg.contains("com.google.googlejavaformat"));
  }

  // The build itself declares no module grants; jvm.args holds only what the project's pinned
  // google-java-format needs to run in the server.
  @Test
  void sync_spotlessGoogleJavaFormat_jvmArgsHoldsFormatterGrants() throws IOException {
    assertThat(Files.readAllLines(lathe("jvm.args")))
        .containsExactly(
            "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED");
  }

  // Its behavior (goal, scope, tool, JDK) is covered by SyncScriptTest, which runs it.
  @Test
  void sync_syncScript_runsTheMavenThatRanThisBuild() throws IOException {
    final var script = lathe(Os.script("lathe-sync"));
    assertThat(script).isRegularFile().isExecutable();
    final Path tool = Path.of(System.getProperty("maven.home"), "bin", Os.WINDOWS ? "mvn.cmd" : "mvn");
    assertThat(read(script))
        .startsWith(Os.WINDOWS ? "@echo off\r\n" : "#!/bin/sh\n")
        .contains((Os.WINDOWS ? "set \"tool=%s\"" : "tool='%s'").formatted(tool))
        .contains("-Dmaven.build.cache.enabled=false")
        .contains("-am\"")
        .doesNotContain(" -amd");
  }

  // --- sync: workspace.json ---

  @Test
  void compile_customOutputLeaf_mirrorsAsClassesTreeByScope() throws IOException {
    // health compiles into target/main-classes; Lathe names the tree by scope, not by that leaf.
    assertThat(lathe("health/classes/com/example/health/DbClientHealthCheck.class")).exists();
    assertThat(lathe("health/main-classes")).doesNotExist();
    final var params = read(lathe("health/lsp-params-classes.json"));
    assertThat(params).contains("\"sourceTree\": \"classes\"").contains("main-classes");
    assertThat(read(lathe("workspace.json")))
        .contains("\"path\": \"health/target/main-classes\"");
  }

  @Test
  void sync_workspaceJson_written() throws IOException {
    final var ws = lathe("workspace.json");
    assertThat(ws).exists();
    final var content = read(ws);
    assertThat(content).contains("\"schemaVersion\"");
    assertThat(content).contains("\"path\": \"core/target/classes\"");
    assertThat(content).contains("\"workspaceRoot\"");
    assertThat(content).contains("\"serverVersion\"");
    assertThat(content).contains("\"jdk\"");
    assertThat(content).contains("\"dependencySources\"");
    assertThat(content).contains("\"org.junit.jupiter:junit-jupiter-api");
    assertThat(content).contains("\"org.opentest4j:opentest4j");
    assertThat(content).contains("\"PRESENT\"");
    assertThat(content).contains("\"typeIndex\"");
    assertThat(content).contains(Os.json(Path.of("type-index/jdks")));
  }

  @Test
  void sync_styleJson_detectsGoogleFromSpotless() throws IOException {
    final var style = lathe("style.json");
    assertThat(style).exists();
    final var content = read(style);
    assertThat(content).contains("\"engine\": \"google\"");
    assertThat(content).contains("\"profile\": \"google\"");
    // Spotless's own defaults, and the resolved formatter jars from the local repository.
    assertThat(content).containsPattern("\"version\": \"\\d+\\.\\d+");
    assertThat(content).contains("\"groupArtifact\": \"com.google.googlejavaformat:google-java-format\"");
    assertThat(content).contains("\"reorderImports\": \"false\"");
    assertThat(content).containsPattern("\"classpath\": \\[\\s*\"[^\"]+google-java-format-[^\"]+\\.jar\"");
  }

  @Test
  void sync_workspaceJson_recordsResourceRoots() throws IOException {
    final var content = read(lathe("workspace.json"));
    assertThat(content).contains("\"resourceRoots\"");
    // Real roots + real build output from the Maven model (app has a main resource, jpms a test
    // one), so the server can refresh .lathe/ on a resource edit without assuming the layout.
    assertThat(content).contains("app/src/main/resources");
    assertThat(content).contains("app/target/classes");
    assertThat(content).contains("jpms/src/test/resources");
    assertThat(content).contains("jpms/target/test-classes");
  }

  @Test
  void sync_depTypeIndexShard_created() throws IOException {
    final var shard =
        LATHE_CACHE
            .resolve("type-index")
            .resolve("deps")
            .resolve("org.junit.jupiter")
            .resolve("junit-jupiter-api")
            .resolve("5.14.4")
            .resolve("index.json");
    assertThat(shard).exists();
    final var content = read(shard);
    assertThat(content).contains("\"types\"");
    assertThat(content).contains("\"simpleName\"");
  }

  @Test
  void sync_dependencySources_nestedLikeLocalRepositoryWithoutMavenMetadata() throws IOException {
    final var assertj = LATHE_CACHE.resolve("deps").resolve("org.assertj").resolve("assertj-core");
    assertThat(assertj).isDirectory();
    try (final var versions = Files.list(assertj)) {
      final List<Path> sources = versions.toList();
      assertThat(sources).isNotEmpty();
      sources.forEach(dir -> assertThat(dir.resolve("META-INF/maven")).doesNotExist());
    }
  }

  // Every name Lathe writes must be legal on Windows too, and short enough to stay under its
  // 260-character path limit below a typical C:\Users\<name>\.cache\lathe or project root.
  @Test
  void sync_writtenNames_portableToEveryOs() throws IOException {
    assertThat(portableNameViolations(LATHE_CACHE)).isEmpty();
    assertThat(portableNameViolations(ROOT.resolve(".lathe"))).isEmpty();
  }

  private static List<String> portableNameViolations(final Path root) throws IOException {
    final var violations = new ArrayList<String>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
            check(dir);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
            check(file);
            return FileVisitResult.CONTINUE;
          }

          // Parallel invoker projects share the cache; their temp extract dirs vanish mid-walk.
          @Override
          public FileVisitResult visitFileFailed(final Path file, final IOException e) {
            return FileVisitResult.CONTINUE;
          }

          private void check(final Path path) {
            if (path.equals(root)) {
              return;
            }

            final var rel = root.relativize(path).toString();
            if (rel.length() > MAX_RELATIVE_PATH
                || ILLEGAL_NAME.matcher(path.getFileName().toString()).matches()) {
              violations.add(rel);
            }
          }
        });
    return List.copyOf(violations);
  }

  @Test
  void sync_jdkTypeIndexShard_created() throws IOException {
    final var jdkTypeIndexes = LATHE_CACHE.resolve("type-index").resolve("jdks");
    assertThat(jdkTypeIndexes).isDirectory();
    try (final var files = Files.walk(jdkTypeIndexes)) {
      final var shards =
          files
              .filter(path -> path.getFileName().toString().equals("index.json"))
              .filter(path -> readUnchecked(path).contains("\"java.lang.String\""))
              .toList();
      assertThat(shards).isNotEmpty();
    }
  }

  // --- core module ---

  @Test
  void sync_coreModuleParams_written() {
    assertThat(lathe("core/lsp-params-classes.json")).exists();
  }

  // --- app module: cross-module dep + annotation processing ---

  @Test
  void sync_appModuleParams_written() throws IOException {
    final var params = lathe("app/lsp-params-classes.json");
    assertThat(params).exists();
    assertThat(read(params)).contains("record-companion-builder");
  }

  @Test
  void sync_appMainResources_written() {
    assertThat(lathe("app/classes/com/example/app/app-resource.txt"))
        .exists()
        .hasContent("app-resource\n");
  }

  @Test
  void sync_appGeneratedSources_written() {
    assertThat(lathe("app/generated-sources/com/example/app/UserBuilder.java")).exists();
    assertThat(lathe("app/generated-sources/com/example/app/UserUpdater.java")).exists();
  }

  // --- jpms module: JPMS + test compilation ---

  @Test
  void sync_jpmsSurefireConfig_declaresLaunchMarkers() throws IOException {
    final var content = read(ROOT.resolve("jpms/pom.xml"));
    assertThat(content).contains("maven-surefire-plugin");
    assertThat(content).contains("3.5.4");
    assertThat(content).contains("lathe.fixture.argLine");
    assertThat(content).contains("lathe.fixture.systemProperty");
  }

  @Test
  void capture_jpmsTestLaunch_written() throws IOException {
    final var launch = lathe("jpms/test-launch.json");
    assertThat(launch).exists();
    final var content = read(launch);
    assertThat(content).contains("\"kind\": \"junit-platform\"");
    assertThat(content).contains("\"mode\": \"MODULE\"");
    assertThat(content).contains("\"mainModule\": \"com.example.jpms\"");
    assertThat(content).contains("\"patchModules\"");
    assertThat(content).contains(Os.json(Path.of("jpms/target/test-classes")));
    assertThat(content).contains("\"addReads\"");
    assertThat(content).contains("\"addOpens\"");
    assertThat(content).contains("lathe.fixture.argLine");
    assertThat(content).contains("from-argline");
  }

  @Test
  void sync_jpmsModuleParams_written() throws IOException {
    final var params = lathe("jpms/lsp-params-classes.json");
    assertThat(params).exists();
    final var content = read(params);
    assertThat(content).contains("Xlint");
    assertThat(content).contains("module-version");
    assertThat(content).contains("validcheck");
  }

  @Test
  void sync_jpmsMainBytecode_written() {
    assertThat(lathe("jpms/classes/com/example/jpms/HelloMain.class")).exists();
  }

  @Test
  void sync_jpmsTestParams_written() throws IOException {
    final var params = lathe("jpms/lsp-params-test-classes.json");
    assertThat(params).exists();
    final var content = read(params);
    assertThat(content).contains("\"test-classes\"");
    assertThat(content).contains(Os.json(Path.of("src/test/java")));
    assertThat(content).contains("validcheck");
    assertThat(content).contains("junit-jupiter-api");
    assertThat(content).contains("junit-jupiter-params");
    assertThat(content).contains("patch-module");
    assertThat(content)
        .contains(
            "\"--add-reads\",\n    \"com.example.jpms\\u003djava.compiler\"",
            "\"--add-reads\",\n    \"com.example.jpms\\u003dALL-UNNAMED\"");
  }

  @Test
  void sync_jpmsTestBytecodeAndResources_written() {
    assertThat(lathe("jpms/test-classes/com/example/jpms/HelloTest.class")).exists();
    assertThat(lathe("jpms/test-classes/com/example/jpms/test-resource.txt"))
        .exists()
        .hasContent("jpms-test-resource\n");
  }

  // --- main-launch templates (derived by lathe:sync for every module) ---

  @Test
  void sync_jpmsMainLaunch_recordsModularPlacement() throws IOException {
    final var launch = lathe("jpms/main-launch.json");
    assertThat(launch).exists();
    final var content = read(launch);
    assertThat(content).contains("\"mode\": \"MODULE\"");
    assertThat(content).contains("\"mainModule\": \"com.example.jpms\"");
    // The launched module's own output leads the module path; its required module follows.
    assertThat(content).contains(Os.json(Path.of("jpms/target/classes")));
    assertThat(content).contains("validcheck");
    assertThat(content).contains("\"classPath\": []");
  }

  @Test
  void sync_appMainLaunch_recordsClasspathPlacement() throws IOException {
    final var launch = lathe("app/main-launch.json");
    assertThat(launch).exists();
    final var content = read(launch);
    assertThat(content).contains("\"mode\": \"CLASSPATH\"");
    assertThat(content).contains("\"modulePath\": []");
    assertThat(content).contains(Os.json(Path.of("app/target/classes")));
    assertThat(content).contains("slf4j-api");
    // The reactor sibling core is placed by its fresh output, not dropped for lack of an installed
    // jar -- otherwise a cross-module main run fails with NoClassDefFoundError.
    assertThat(content).contains(Os.json(Path.of("core/target/classes")));
    // A classpath launch has no main module; the field is omitted, not blank.
    assertThat(content).doesNotContain("mainModule");
  }

  // --- test filters (derived by lathe:sync from each module's Surefire includes/excludes) ---

  @Test
  void sync_shapesTestFilter_recordsTheModulesOwnIncludesAndDefaultExcludes() throws IOException {
    final JsonObject filter = JsonParser.parseString(read(lathe("shapes/test-filter.json")))
        .getAsJsonObject();

    assertThat(filter.getAsJsonArray("classNameIncludes").asList())
        .extracting(JsonElement::getAsString)
        .containsExactly("(.*\\.)?[^.]*Test");
    assertThat(filter.getAsJsonArray("classNameExcludes").asList())
        .extracting(JsonElement::getAsString)
        .containsExactly("(.*\\.)?[^.]*\\$[^.]*");
  }
}
