package io.github.aglibs.lathe.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceDetectorTest {

  @TempDir Path tmp;

  @Test
  void findWorkspaceRoot_findsLatheDirAtVariousDepths() throws Exception {
    final var workspaceRoot = tmp.resolve("workspace");
    Files.createDirectories(workspaceRoot.resolve(".lathe"));

    assertThat(WorkspaceDetector.findWorkspaceRoot(workspaceRoot.resolve("parent/module-a")))
        .contains(workspaceRoot);
    assertThat(WorkspaceDetector.findWorkspaceRoot(workspaceRoot)).contains(workspaceRoot);
  }

  @Test
  void findWorkspaceRoot_returnsEmptyWhenLatheDirAbsent() {
    assertThat(WorkspaceDetector.findWorkspaceRoot(tmp.resolve("project"))).isEmpty();
  }

  @Test
  void findWorkspaceRoot_returnsEmptyWhenSkipPropertyTrue() throws Exception {
    final var workspaceRoot = tmp.resolve("workspace");
    Files.createDirectories(workspaceRoot.resolve(".lathe"));
    System.setProperty("lathe.skip", "true");
    try {
      assertThat(WorkspaceDetector.findWorkspaceRoot(workspaceRoot)).isEmpty();
    } finally {
      System.clearProperty("lathe.skip");
    }
  }

  @Test
  void findWorkspaceRoot_skipPropertyFalseOverridesDisabled() throws Exception {
    final var workspaceRoot = tmp.resolve("workspace");
    Files.createDirectories(workspaceRoot.resolve(".lathe"));
    System.setProperty("lathe.skip", "false");
    try {
      assertThat(WorkspaceDetector.findWorkspaceRoot(workspaceRoot)).contains(workspaceRoot);
    } finally {
      System.clearProperty("lathe.skip");
    }
  }
}
