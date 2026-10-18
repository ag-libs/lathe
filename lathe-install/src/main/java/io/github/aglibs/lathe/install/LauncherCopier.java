package io.github.aglibs.lathe.install;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.logging.Log;

// Copies the launchers installed under servers/<version>/ to where clients run them: a workspace's
// .lathe/, pinned per workspace so no machine-global pointer can drift, and the cache's latest/.
// Plain files, not symlinks: creating a link needs admin rights on Windows, and a launcher names
// only absolute jar paths, so a copy runs the same. Shared by the Maven and OpenJDK sync paths.
public final class LauncherCopier {

  private static final List<String> LAUNCHER_SCRIPTS =
      List.of(LatheLayout.LAUNCHER_SCRIPT, LatheLayout.MCP_LAUNCHER_SCRIPT);

  private final Log log;

  public LauncherCopier(final Log log) {
    this.log = log;
  }

  public void copyToWorkspace(final Path workspaceRoot, final Path versionDir) {
    try {
      copy(versionDir, workspaceRoot.resolve(LatheLayout.LATHE_DIR));
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to write workspace launchers", e);
    }
  }

  // By newest installed version, not last synced, so syncing an older repo cannot drag it backward.
  // Older Lathe versions made latest a symlink: delete only the link, never what it points at.
  void copyNewestToLatest(final Path serversDir, final Path latestDir) throws IOException {
    final Path newest = ServerInstaller.newestVersionDir(serversDir);
    if (newest == null) {
      return;
    }

    if (Files.isSymbolicLink(latestDir)) {
      Files.delete(latestDir);
    }

    copy(newest, latestDir);
  }

  private void copy(final Path versionDir, final Path dir) throws IOException {
    for (final var script : LAUNCHER_SCRIPTS) {
      final var content = Files.readString(versionDir.resolve(script), StandardCharsets.UTF_8);
      final Path target = dir.resolve(script);
      if (FileUtil.writeIfChanged(dir, target, content, true)) {
        log.info("[sync] wrote %s from %s".formatted(target, versionDir.getFileName()));
      } else {
        log.debug("[sync] %s unchanged".formatted(target));
      }
    }
  }
}
