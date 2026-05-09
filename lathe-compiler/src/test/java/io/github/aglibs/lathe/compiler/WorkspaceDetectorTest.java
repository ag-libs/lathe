package io.github.aglibs.lathe.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceDetectorTest {

  @TempDir Path tmp;

  @Test
  void findWorkspaceRoot_findsRootMarkerAtVariousDepths() throws Exception {
    final var workspaceRoot = tmp.resolve("workspace");
    final var latheDir = workspaceRoot.resolve(".lathe");
    Files.createDirectories(latheDir);
    Files.writeString(latheDir.resolve("root.marker"), "");

    assertThat(WorkspaceDetector.findWorkspaceRoot(workspaceRoot.resolve("parent/module-a")))
        .contains(workspaceRoot);
    assertThat(WorkspaceDetector.findWorkspaceRoot(workspaceRoot)).contains(workspaceRoot);
  }

  @Test
  void findWorkspaceRoot_returnsEmptyWhenMarkerAbsent() throws Exception {
    assertThat(WorkspaceDetector.findWorkspaceRoot(tmp.resolve("project"))).isEmpty();
  }
}
