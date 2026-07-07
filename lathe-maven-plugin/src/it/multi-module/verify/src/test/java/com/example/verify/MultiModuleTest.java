package com.example.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MultiModuleTest {

  private static final Path ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final String LATHE_VERSION = System.getProperty("lathe.version");
  private static final Path LATHE_CACHE = Path.of(System.getProperty("lathe.cache"));

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
        LATHE_CACHE.resolve("servers").resolve(LATHE_VERSION).resolve("lathe-launcher.sh");
    assertThat(launcher).exists().isExecutable();
  }

  @Test
  void sync_currentSymlink_created() {
    assertThat(LATHE_CACHE.resolve("current")).isSymbolicLink();
  }

  @Test
  void sync_neovimRuntime_extracted() {
    final var neovim = LATHE_CACHE.resolve("current").resolve("neovim");
    assertThat(neovim.resolve("lua/lathe.lua")).exists();
    assertThat(neovim.resolve("lua/lathe/indent.lua")).exists();
    assertThat(neovim.resolve("ftplugin/java.lua")).exists();
    assertThat(neovim.resolve("after/indent/java.lua")).exists();
  }

  @Test
  void sync_launcherContent_containsRequiredJvmArgs() throws IOException {
    final var launcher =
        LATHE_CACHE.resolve("servers").resolve(LATHE_VERSION).resolve("lathe-launcher.sh");
    final var content = read(launcher);
    assertThat(content).startsWith("#!/bin/sh\n");
    assertThat(content).contains("--add-modules java.net.http");
    assertThat(content).contains("--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED");
    assertThat(content).contains("--add-opens jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED");
    assertThat(content).contains("--add-exports jdk.compiler/com.sun.tools.javac.api=com.google.googlejavaformat");
    assertThat(content).contains("--module-path");
    assertThat(content).contains("-m io.github.aglibs.lathe.server/io.github.aglibs.lathe.server.LatheServer");
  }

  // --- sync: workspace.json ---

  @Test
  void sync_workspaceJson_written() throws IOException {
    final var ws = lathe("workspace.json");
    assertThat(ws).exists();
    final var content = read(ws);
    assertThat(content).contains("\"schemaVersion\"");
    assertThat(content).contains("\"workspaceRoot\"");
    assertThat(content).contains("\"serverVersion\"");
    assertThat(content).contains("\"jdk\"");
    assertThat(content).contains("\"dependencySources\"");
    assertThat(content).contains("\"org.junit.jupiter:junit-jupiter-api");
    assertThat(content).contains("\"org.opentest4j:opentest4j");
    assertThat(content).contains("\"PRESENT\"");
    assertThat(content).contains("\"typeIndex\"");
    assertThat(content).contains("type-index/jdks");
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
    assertThat(content).contains("src/test/java");
    assertThat(content).contains("validcheck");
    assertThat(content).contains("junit-jupiter-api");
    assertThat(content).contains("junit-jupiter-params");
    assertThat(content).contains("patch-module");
    assertThat(content).contains("add-reads");
    assertThat(content).contains("ALL-UNNAMED");
  }

  @Test
  void sync_jpmsTestBytecodeAndResources_written() {
    assertThat(lathe("jpms/test-classes/com/example/jpms/HelloTest.class")).exists();
    assertThat(lathe("jpms/test-classes/com/example/jpms/test-resource.txt"))
        .exists()
        .hasContent("jpms-test-resource\n");
  }
}
