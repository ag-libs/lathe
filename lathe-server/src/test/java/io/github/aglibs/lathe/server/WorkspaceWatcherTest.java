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
    watcher.updateBuildFiles(List.of(pom));

    // Update mtime and size
    Files.writeString(pom, "<project>updated</project>");
    assertThat(watcher.poll()).isEqualTo(PollResult.BUILD_FILES_CHANGED);
  }

  @Test
  void poll_pomMtimeOnlyChanged_returnsPomChanged() throws IOException {
    final var pom = workspaceRoot.resolve("pom.xml");
    Files.writeString(pom, "<project/>");
    Files.setLastModifiedTime(pom, PAST);

    final var watcher = new WorkspaceWatcher(workspaceRoot);
    watcher.updateBuildFiles(List.of(pom));

    // Mtime changes but size stays same (touch)
    Files.setLastModifiedTime(pom, FileTime.fromMillis(PAST.toMillis() + 10_000L));
    assertThat(watcher.poll()).isEqualTo(PollResult.BUILD_FILES_CHANGED);
  }

  @Test
  void poll_updateBuildFiles_resetsBaseline() throws IOException {
    final var pom = workspaceRoot.resolve("pom.xml");
    Files.writeString(pom, "<project/>");
    Files.setLastModifiedTime(pom, PAST);

    final var watcher = new WorkspaceWatcher(workspaceRoot);
    watcher.updateBuildFiles(List.of(pom));

    // Make it stale
    Files.writeString(pom, "<project>updated</project>");

    // A sync rewrites the manifest; the reload that change triggers resets the baseline
    Files.writeString(manifest(), "{synced}");
    assertThat(watcher.poll()).isEqualTo(PollResult.WORKSPACE_CHANGED);
    watcher.updateBuildFiles(List.of(pom));

    assertThat(watcher.poll()).isEqualTo(PollResult.NO_CHANGE);
  }

  @Test
  void poll_bothWorkspaceAndPomChanged_returnsWorkspaceChanged() throws IOException {
    final var pom = workspaceRoot.resolve("pom.xml");
    Files.writeString(pom, "<project/>");
    Files.setLastModifiedTime(pom, PAST);

    final var watcher = new WorkspaceWatcher(workspaceRoot);
    watcher.updateBuildFiles(List.of(pom));

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

  @Test
  void poll_manifestTouchedSameContent_returnsMirrorRefreshOnceThenNoChange() throws IOException {
    final var watcher = new WorkspaceWatcher(workspaceRoot);

    // A mtime bump with unchanged content yields one REACTOR_REFRESH, then quiet.
    touchManifest();

    assertThat(watcher.poll()).isEqualTo(PollResult.REACTOR_REFRESH);
    assertThat(watcher.poll()).isEqualTo(PollResult.NO_CHANGE);
  }

  @Test
  void poll_contentChangeAfterMirrorTouch_returnsWorkspaceChanged() throws IOException {
    final var watcher = new WorkspaceWatcher(workspaceRoot);

    // A reactor-only touch is REACTOR_REFRESH; a later real content change is still a full reload.
    touchManifest();
    assertThat(watcher.poll()).isEqualTo(PollResult.REACTOR_REFRESH);

    Files.writeString(manifest(), "{updated}");
    assertThat(watcher.poll()).isEqualTo(PollResult.WORKSPACE_CHANGED);
  }

  @Test
  void poll_buildFileChanged_reportedOnce_againOnlyWhenItChangesAgain() throws IOException {
    final var pom = workspaceRoot.resolve("pom.xml");
    Files.writeString(pom, "<project/>");
    Files.setLastModifiedTime(pom, PAST);
    final var watcher = new WorkspaceWatcher(workspaceRoot);
    watcher.updateBuildFiles(List.of(pom));

    Files.writeString(pom, "<project>updated</project>");
    assertThat(watcher.poll()).isEqualTo(PollResult.BUILD_FILES_CHANGED);
    assertThat(watcher.poll())
        .as("not again while its sync prompt may still be unanswered")
        .isEqualTo(PollResult.NO_CHANGE);

    Files.writeString(pom, "<project>updated again</project>");
    assertThat(watcher.poll()).isEqualTo(PollResult.BUILD_FILES_CHANGED);
  }

  @Test
  void poll_buildFileEditedBeforeStartup_reportedOnFirstPollOnlyWhenNewerThanManifest()
      throws IOException {
    final var edited = workspaceRoot.resolve("pom.xml");
    Files.writeString(edited, "<project/>");
    Files.setLastModifiedTime(edited, FileTime.fromMillis(PAST.toMillis() + 10_000L));
    final var watcher = new WorkspaceWatcher(workspaceRoot);
    watcher.updateBuildFiles(List.of(edited));

    assertThat(watcher.poll()).isEqualTo(PollResult.BUILD_FILES_CHANGED);
    assertThat(watcher.poll()).isEqualTo(PollResult.NO_CHANGE);

    final var synced = workspaceRoot.resolve("module.xml");
    Files.writeString(synced, "<project/>");
    Files.setLastModifiedTime(synced, FileTime.fromMillis(PAST.toMillis() - 10_000L));
    final var syncedWatcher = new WorkspaceWatcher(workspaceRoot);
    syncedWatcher.updateBuildFiles(List.of(synced));

    assertThat(syncedWatcher.poll())
        .as("older than the manifest: the last sync saw it")
        .isEqualTo(PollResult.NO_CHANGE);
  }

  private Path manifest() {
    return latheDir.resolve(LatheLayout.WORKSPACE_JSON);
  }

  private void touchManifest() throws IOException {
    Files.setLastModifiedTime(manifest(), FileTime.fromMillis(PAST.toMillis() + 10_000L));
  }
}
