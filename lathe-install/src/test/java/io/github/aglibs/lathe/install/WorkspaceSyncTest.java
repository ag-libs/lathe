package io.github.aglibs.lathe.install;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSyncTest {

  @TempDir Path workspaceRoot;

  @Test
  void writeGitignore_missing_ignoresAllOfLathe_andLeavesAnEditedOneAlone() throws Exception {
    final Path file =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(WorkspaceSync.GITIGNORE_FILE);

    WorkspaceSync.writeGitignore(workspaceRoot);

    assertThat(file).hasContent("*");
    Files.writeString(file, "*\n!keep.txt\n", StandardCharsets.UTF_8);

    WorkspaceSync.writeGitignore(workspaceRoot);

    assertThat(file).hasContent("*\n!keep.txt");
  }
}
