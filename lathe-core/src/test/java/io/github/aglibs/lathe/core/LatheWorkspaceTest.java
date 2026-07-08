package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LatheWorkspaceTest {

  @TempDir private Path tempDir;

  @AfterEach
  void clearProperty() {
    System.clearProperty(LatheFlags.SKIP);
  }

  @Test
  void findRoot_nestedPath_returnsWorkspaceRoot() throws IOException {
    final Path workspace = tempDir.resolve("workspace");
    final Path source = workspace.resolve("app/src/main/java/com/example/App.java");
    Files.createDirectories(workspace.resolve(LatheLayout.LATHE_DIR));
    Files.createDirectories(source.getParent());
    Files.writeString(source, "class App {}");

    assertThat(LatheWorkspace.findRoot(source.getParent())).contains(workspace);
  }

  @Test
  void findRoot_missingMarker_returnsEmpty() throws IOException {
    final Path source = tempDir.resolve("app/src/main/java/com/example/App.java");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "class App {}");

    assertThat(LatheWorkspace.findRoot(source.getParent())).isEmpty();
  }

  @Test
  void findRoot_skipPropertyTrue_returnsEmpty() throws IOException {
    final Path workspace = tempDir.resolve("workspace");
    Files.createDirectories(workspace.resolve(LatheLayout.LATHE_DIR));
    System.setProperty(LatheFlags.SKIP, "true");

    assertThat(LatheWorkspace.findRoot(workspace)).isEmpty();
  }

  @Test
  void findRoot_skipPropertyFalse_returnsWorkspaceRoot() throws IOException {
    final Path workspace = tempDir.resolve("workspace");
    Files.createDirectories(workspace.resolve(LatheLayout.LATHE_DIR));
    System.setProperty(LatheFlags.SKIP, "false");

    assertThat(LatheWorkspace.findRoot(workspace)).contains(workspace);
  }
}
