package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceWatcherTest {

  private static final FileTime PAST = FileTime.fromMillis(1_000_000_000L);

  @TempDir Path workspaceRoot;

  private Path latheDir;
  private AtomicInteger reloads;

  @BeforeEach
  void setUp() throws IOException {
    latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    reloads = new AtomicInteger();
  }

  @Test
  void poll_noChange_doesNotReload() throws IOException {
    writeOld(LatheLayout.WORKSPACE_JSON, "{}");
    final var watcher = new WorkspaceWatcher(workspaceRoot, reloads::incrementAndGet);

    watcher.poll();

    assertThat(reloads.get()).isEqualTo(0);
  }

  @Test
  void poll_workspaceJsonChanged_reloads() throws IOException {
    writeOld(LatheLayout.WORKSPACE_JSON, "{}");
    final var watcher = new WorkspaceWatcher(workspaceRoot, reloads::incrementAndGet);

    Files.writeString(latheDir.resolve(LatheLayout.WORKSPACE_JSON), "{updated}");
    watcher.poll();

    assertThat(reloads.get()).isEqualTo(1);
  }

  @Test
  void poll_paramsFileAdded_reloads() throws IOException {
    final var watcher = new WorkspaceWatcher(workspaceRoot, reloads::incrementAndGet);

    Files.writeString(latheDir.resolve("lsp-params-module-a.json"), "{}");
    watcher.poll();

    assertThat(reloads.get()).isEqualTo(1);
  }

  @Test
  void poll_paramsFileChanged_reloads() throws IOException {
    writeOld("lsp-params-module-a.json", "{}");
    final var watcher = new WorkspaceWatcher(workspaceRoot, reloads::incrementAndGet);

    Files.writeString(latheDir.resolve("lsp-params-module-a.json"), "{updated}");
    watcher.poll();

    assertThat(reloads.get()).isEqualTo(1);
  }

  @Test
  void poll_bothSignalsChange_reloadsOnce() throws IOException {
    writeOld(LatheLayout.WORKSPACE_JSON, "{}");
    writeOld("lsp-params-module-a.json", "{}");
    final var watcher = new WorkspaceWatcher(workspaceRoot, reloads::incrementAndGet);

    Files.writeString(latheDir.resolve(LatheLayout.WORKSPACE_JSON), "{updated}");
    Files.writeString(latheDir.resolve("lsp-params-module-a.json"), "{updated}");
    watcher.poll();

    assertThat(reloads.get()).isEqualTo(1);
  }

  @Test
  void poll_afterReload_secondPollDoesNotReload() throws IOException {
    writeOld(LatheLayout.WORKSPACE_JSON, "{}");
    final var watcher = new WorkspaceWatcher(workspaceRoot, reloads::incrementAndGet);

    Files.writeString(latheDir.resolve(LatheLayout.WORKSPACE_JSON), "{updated}");
    watcher.poll();
    watcher.poll();

    assertThat(reloads.get()).isEqualTo(1);
  }

  private void writeOld(final String filename, final String content) throws IOException {
    final var path = latheDir.resolve(filename);
    Files.writeString(path, content);
    Files.setLastModifiedTime(path, PAST);
  }
}
