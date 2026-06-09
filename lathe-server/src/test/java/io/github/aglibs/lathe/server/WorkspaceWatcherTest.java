package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
  }

  @Test
  void poll_noChange_returnsFalse() throws IOException {
    writeOld(LatheLayout.WORKSPACE_JSON, "{}");
    final var watcher = new WorkspaceWatcher(workspaceRoot);
    assertThat(watcher.poll()).isFalse();
  }

  @Test
  void poll_workspaceJsonChanged_returnsTrue() throws IOException {
    writeOld(LatheLayout.WORKSPACE_JSON, "{}");
    final var watcher = new WorkspaceWatcher(workspaceRoot);
    Files.writeString(latheDir.resolve(LatheLayout.WORKSPACE_JSON), "{updated}");
    assertThat(watcher.poll()).isTrue();
  }

  @Test
  void poll_paramsFileAdded_returnsTrue() throws IOException {
    final var watcher = new WorkspaceWatcher(workspaceRoot);
    Files.writeString(latheDir.resolve(LatheLayout.paramsFileName("module-a")), "{}");
    assertThat(watcher.poll()).isTrue();
  }

  @Test
  void poll_paramsFileChanged_returnsTrue() throws IOException {
    writeOld(LatheLayout.paramsFileName("module-a"), "{}");
    final var watcher = new WorkspaceWatcher(workspaceRoot);
    Files.writeString(latheDir.resolve(LatheLayout.paramsFileName("module-a")), "{updated}");
    assertThat(watcher.poll()).isTrue();
  }

  @Test
  void poll_bothSignalsChange_returnsTrue() throws IOException {
    writeOld(LatheLayout.WORKSPACE_JSON, "{}");
    writeOld(LatheLayout.paramsFileName("module-a"), "{}");
    final var watcher = new WorkspaceWatcher(workspaceRoot);
    Files.writeString(latheDir.resolve(LatheLayout.WORKSPACE_JSON), "{updated}");
    Files.writeString(latheDir.resolve(LatheLayout.paramsFileName("module-a")), "{updated}");
    assertThat(watcher.poll()).isTrue();
  }

  @Test
  void poll_afterChange_secondPollReturnsFalse() throws IOException {
    writeOld(LatheLayout.WORKSPACE_JSON, "{}");
    final var watcher = new WorkspaceWatcher(workspaceRoot);
    Files.writeString(latheDir.resolve(LatheLayout.WORKSPACE_JSON), "{updated}");
    assertThat(watcher.poll()).isTrue();
    assertThat(watcher.poll()).isFalse();
  }

  private void writeOld(final String filename, final String content) throws IOException {
    final var path = latheDir.resolve(filename);
    Files.writeString(path, content);
    Files.setLastModifiedTime(path, PAST);
  }
}
