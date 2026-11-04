package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LatheWorkspaceTest {

  @TempDir private Path tempDir;

  // The value the run set (false on CI, which the build's CI variable would otherwise turn true),
  // put back after each test that changes it.
  private String disabledBefore;

  @BeforeEach
  void saveProperty() {
    disabledBefore = System.getProperty(LatheFlags.DISABLED);
  }

  @AfterEach
  void restoreProperty() {
    if (disabledBefore == null) {
      System.clearProperty(LatheFlags.DISABLED);
    } else {
      System.setProperty(LatheFlags.DISABLED, disabledBefore);
    }
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

  // A build disabled by lathe.disabled (or CI) finds no workspace; a server still reads the one a
  // build wrote.
  @Test
  void findRoot_disabled_returnsEmptyButNearestRootFindsIt() throws IOException {
    final Path workspace = tempDir.resolve("workspace");
    final Path module = Files.createDirectories(workspace.resolve("app/src"));
    Files.createDirectories(workspace.resolve(LatheLayout.LATHE_DIR));
    System.setProperty(LatheFlags.DISABLED, "true");

    assertThat(LatheWorkspace.findRoot(module)).isEmpty();
    assertThat(LatheWorkspace.nearestRoot(module, null)).contains(workspace);
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
  void findRoot_disabledPropertyTrue_returnsEmptyEvenWithMarker() throws IOException {
    final Path workspace = tempDir.resolve("workspace");
    Files.createDirectories(workspace.resolve(LatheLayout.LATHE_DIR));
    System.setProperty(LatheFlags.DISABLED, "true");

    assertThat(LatheWorkspace.findRoot(workspace)).isEmpty();
  }
}
