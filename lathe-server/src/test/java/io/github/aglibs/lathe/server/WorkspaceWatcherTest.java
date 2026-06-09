package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.WorkspaceWatcher.PollResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceWatcherTest {

  private static final FileTime PAST = FileTime.fromMillis(1_000_000_000L);

  @TempDir Path workspaceRoot;

  private Path latheDir;

  @BeforeEach
  void setUp() throws IOException {
    latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    final var path = latheDir.resolve(LatheLayout.WORKSPACE_JSON);
    Files.writeString(path, "{}");
    Files.setLastModifiedTime(path, PAST);
  }

  @Test
  void poll_noChange_returnsNoChange() throws IOException {
    final var watcher = new WorkspaceWatcher(workspaceRoot);
    assertThat(watcher.poll()).isEqualTo(PollResult.NO_CHANGE);
  }

  @Test
  void poll_workspaceJsonChanged_returnsWorkspaceChanged() throws IOException {
    final var watcher = new WorkspaceWatcher(workspaceRoot);
    Files.writeString(latheDir.resolve(LatheLayout.WORKSPACE_JSON), "{updated}");
    assertThat(watcher.poll()).isEqualTo(PollResult.WORKSPACE_CHANGED);
  }

  @Test
  void poll_pomChanged_returnsPomChanged() throws IOException {
    final var pom = workspaceRoot.resolve("pom.xml");
    Files.writeString(pom, "<project/>");
    Files.setLastModifiedTime(pom, PAST);

    final var watcher = new WorkspaceWatcher(workspaceRoot);
    watcher.updatePomPaths(List.of(pom));

    // Update mtime and size
    Files.writeString(pom, "<project>updated</project>");
    assertThat(watcher.poll()).isEqualTo(PollResult.POM_CHANGED);
  }

  @Test
  void poll_pomMtimeOnlyChanged_returnsPomChanged() throws IOException {
    final var pom = workspaceRoot.resolve("pom.xml");
    Files.writeString(pom, "<project/>");
    Files.setLastModifiedTime(pom, PAST);

    final var watcher = new WorkspaceWatcher(workspaceRoot);
    watcher.updatePomPaths(List.of(pom));

    // Mtime changes but size stays same (touch)
    Files.setLastModifiedTime(pom, FileTime.fromMillis(PAST.toMillis() + 10_000L));
    assertThat(watcher.poll()).isEqualTo(PollResult.POM_CHANGED);
  }

  @Test
  void poll_updatePomPaths_resetsBaseline() throws IOException {
    final var pom = workspaceRoot.resolve("pom.xml");
    Files.writeString(pom, "<project/>");
    Files.setLastModifiedTime(pom, PAST);

    final var watcher = new WorkspaceWatcher(workspaceRoot);
    watcher.updatePomPaths(List.of(pom));

    // Make it stale
    Files.writeString(pom, "<project>updated</project>");

    // Now call updatePomPaths to reset baseline to current disk state
    watcher.updatePomPaths(List.of(pom));

    assertThat(watcher.poll()).isEqualTo(PollResult.NO_CHANGE);
  }

  @Test
  void poll_bothWorkspaceAndPomChanged_returnsWorkspaceChanged() throws IOException {
    final var pom = workspaceRoot.resolve("pom.xml");
    Files.writeString(pom, "<project/>");
    Files.setLastModifiedTime(pom, PAST);

    final var watcher = new WorkspaceWatcher(workspaceRoot);
    watcher.updatePomPaths(List.of(pom));

    // Change both
    Files.writeString(latheDir.resolve(LatheLayout.WORKSPACE_JSON), "{updated}");
    Files.writeString(pom, "<project>updated</project>");

    assertThat(watcher.poll()).isEqualTo(PollResult.WORKSPACE_CHANGED);
  }

  @Test
  void poll_afterChange_secondPollReturnsNoChange() throws IOException {
    final var watcher = new WorkspaceWatcher(workspaceRoot);
    Files.writeString(latheDir.resolve(LatheLayout.WORKSPACE_JSON), "{updated}");
    assertThat(watcher.poll()).isEqualTo(PollResult.WORKSPACE_CHANGED);
    assertThat(watcher.poll()).isEqualTo(PollResult.NO_CHANGE);
  }
}
