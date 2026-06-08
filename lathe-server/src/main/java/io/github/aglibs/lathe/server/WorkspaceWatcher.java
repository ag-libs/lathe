package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

final class WorkspaceWatcher {

  private static final Logger LOG = Logger.getLogger(WorkspaceWatcher.class.getName());

  private record Fingerprint(long count, long maxMtime) {
    static final Fingerprint EMPTY = new Fingerprint(0L, 0L);
  }

  private final Path latheDir;
  private final Path manifestPath;
  private long lastManifestMtime;
  private Fingerprint lastParams;

  WorkspaceWatcher(final Path workspaceRoot) {
    this.latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    this.manifestPath = latheDir.resolve(LatheLayout.WORKSPACE_JSON);
    this.lastManifestMtime = mtime(manifestPath);
    this.lastParams = paramsFingerprint();
  }

  boolean poll() {
    final var manifestChanged = detectManifestChange();
    final var paramsChanged = detectParamsChange();
    if (manifestChanged || paramsChanged) {
      LOG.info(
          () ->
              "[watcher] reload triggered (manifest=%s, params=%s)"
                  .formatted(manifestChanged, paramsChanged));
      return true;
    }

    return false;
  }

  private boolean detectManifestChange() {
    final long current = mtime(manifestPath);
    if (current == lastManifestMtime) {
      return false;
    }

    lastManifestMtime = current;
    LOG.fine(() -> "[watcher] workspace.json changed");
    return true;
  }

  private boolean detectParamsChange() {
    final var current = paramsFingerprint();
    if (current.equals(lastParams)) {
      return false;
    }

    lastParams = current;
    LOG.fine(() -> "[watcher] params files changed");
    return true;
  }

  private Fingerprint paramsFingerprint() {
    if (!Files.isDirectory(latheDir)) {
      return Fingerprint.EMPTY;
    }

    try (final Stream<Path> stream = Files.walk(latheDir)) {
      final List<Path> files = stream.filter(LatheLayout::isParamsFile).toList();
      final long maxMtime = files.stream().mapToLong(WorkspaceWatcher::mtime).max().orElse(0L);
      return new Fingerprint(files.size(), maxMtime);
    } catch (final IOException e) {
      return Fingerprint.EMPTY;
    }
  }

  private static long mtime(final Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (final IOException e) {
      return 0;
    }
  }
}
