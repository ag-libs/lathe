package io.github.aglibs.lathe.install;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Copies the launchers installed under servers/<version>/ to where clients run them: a workspace's
// .lathe/, pinned per workspace so no machine-global pointer can drift, and the cache's latest/.
// Plain files, not symlinks: creating a link needs admin rights on Windows. A launcher reads the
// argfile beside it, which names only absolute jar paths, so a copy of the pair runs the same.
// Shared by the Maven and OpenJDK sync paths.
public final class LauncherCopier {

  private static final List<String> LAUNCHER_SCRIPTS =
      List.of(LatheLayout.LAUNCHER_SCRIPT, LatheLayout.MCP_LAUNCHER_SCRIPT);
  private static final List<String> ARGFILES =
      List.of(LatheLayout.SERVER_ARGS_FILE, LatheLayout.MCP_ARGS_FILE);

  private static final Logger LOG = LoggerFactory.getLogger(LauncherCopier.class);

  private LauncherCopier() {}

  public static void copyToWorkspace(final Path workspaceRoot, final Path versionDir) {
    try {
      copy(versionDir, workspaceRoot.resolve(LatheLayout.LATHE_DIR));
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to write workspace launchers", e);
    }
  }

  // By newest installed version, not last synced, so syncing an older repo cannot drag it backward.
  // Older Lathe versions made latest a symlink: delete only the link, never what it points at.
  static void copyNewestToLatest(final Path serversDir, final Path latestDir) throws IOException {
    final Path newest = ServerInstaller.newestVersionDir(serversDir);
    if (newest == null) {
      return;
    }

    if (Files.isSymbolicLink(latestDir)) {
      Files.delete(latestDir);
    }

    copy(newest, latestDir);
  }

  private static void copy(final Path versionDir, final Path dir) throws IOException {
    for (final var script : LAUNCHER_SCRIPTS) {
      copyFile(versionDir, dir, script, true);
    }

    for (final var argfile : ARGFILES) {
      copyFile(versionDir, dir, argfile, false);
    }
  }

  private static void copyFile(
      final Path versionDir, final Path dir, final String name, final boolean executable)
      throws IOException {
    final Path target = dir.resolve(name);
    if (FileUtil.copyIfChanged(versionDir.resolve(name), dir, target, executable)) {
      LOG.info("[sync] wrote %s from %s".formatted(target, versionDir.getFileName()));
    } else {
      LOG.debug("[sync] %s unchanged".formatted(target));
    }
  }
}
