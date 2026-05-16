package com.example.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MultiModuleTest {

  private static final Path ROOT = Path.of(System.getProperty("user.dir")).getParent();

  private static Path lathe(final String rel) {
    return ROOT.resolve(".lathe").resolve(rel);
  }

  private static String read(final Path path) throws IOException {
    return Files.readString(path);
  }

  // --- init ---

  @Test
  void latheDirectoryCreated() {
    assertThat(ROOT.resolve(".lathe")).isDirectory();
  }

  // --- sync: workspace.json ---

  @Test
  void workspaceJsonWritten() throws IOException {
    final var ws = lathe("workspace.json");
    assertThat(ws).exists();
    final var content = read(ws);
    assertThat(content).contains("\"schemaVersion\"");
    assertThat(content).contains("\"workspaceRoot\"");
    assertThat(content).contains("\"jdk\"");
    assertThat(content).contains("\"dependencySources\"");
    assertThat(content).contains("\"org.junit.jupiter:junit-jupiter-api");
    assertThat(content).contains("\"org.opentest4j:opentest4j");
    assertThat(content).contains("\"PRESENT\"");
  }

  // --- core module ---

  @Test
  void coreParamsWritten() {
    assertThat(lathe("core/lsp-params-classes.json")).exists();
  }

  // --- app module: cross-module dep + annotation processing ---

  @Test
  void appParamsWritten() throws IOException {
    final var params = lathe("app/lsp-params-classes.json");
    assertThat(params).exists();
    assertThat(read(params)).contains("record-companion-builder");
  }

  @Test
  void appGeneratedSourcesWritten() {
    assertThat(lathe("app/generated-sources/com/example/app/UserBuilder.java")).exists();
    assertThat(lathe("app/generated-sources/com/example/app/UserUpdater.java")).exists();
  }

  // --- jpms module: JPMS + test compilation ---

  @Test
  void jpmsParamsWritten() throws IOException {
    final var params = lathe("jpms/lsp-params-classes.json");
    assertThat(params).exists();
    final var content = read(params);
    assertThat(content).contains("Xlint");
    assertThat(content).contains("module-version");
    assertThat(content).contains("validcheck");
  }

  @Test
  void jpmsTestParamsWritten() throws IOException {
    final var params = lathe("jpms/lsp-params-test-classes.json");
    assertThat(params).exists();
    final var content = read(params);
    assertThat(content).contains("\"test-classes\"");
    assertThat(content).contains("src/test/java");
    assertThat(content).contains("validcheck");
    assertThat(content).contains("junit-jupiter-api");
    assertThat(content).contains("patch-module");
    assertThat(content).contains("add-reads");
    assertThat(content).contains("ALL-UNNAMED");
  }
}
