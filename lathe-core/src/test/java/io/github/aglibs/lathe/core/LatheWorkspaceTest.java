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
  void findRoot_markerOnlyAboveCeiling_returnsEmpty() throws IOException {
    // A parent repo has .lathe/; a nested checkout under it (e.g. a git worktree) has none. The
    // search from the nested module must stop at the ceiling and NOT adopt the parent's .lathe/.
    final Path parent = tempDir.resolve("parent");
    Files.createDirectories(parent.resolve(LatheLayout.LATHE_DIR));
    final Path nestedRoot = parent.resolve("nested");
    final Path module = nestedRoot.resolve("module-a");
    Files.createDirectories(module);

    assertThat(LatheWorkspace.findRoot(module, nestedRoot)).isEmpty();
    // Unbounded, it would climb into the parent — the leaky behaviour the ceiling prevents.
    assertThat(LatheWorkspace.findRoot(module, null)).contains(parent);
  }

  @Test
  void findRoot_markerAtOrBelowCeiling_returnsIt() throws IOException {
    final Path reactor = tempDir.resolve("reactor");
    Files.createDirectories(reactor.resolve(LatheLayout.LATHE_DIR));
    final Path module = reactor.resolve("app/src/main/java");
    Files.createDirectories(module);

    assertThat(LatheWorkspace.findRoot(module, reactor)).contains(reactor);
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
