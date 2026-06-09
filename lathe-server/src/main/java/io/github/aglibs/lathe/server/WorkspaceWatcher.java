package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

final class WorkspaceWatcher {

  private static final Logger LOG = Logger.getLogger(WorkspaceWatcher.class.getName());

  enum PollResult {
    NO_CHANGE,
    WORKSPACE_CHANGED,
    POM_CHANGED
  }

  private record PomFingerprint(long mtime, long size) {}

  private final Path manifestPath;
  private long lastManifestMtime;
  private Map<Path, PomFingerprint> pomBaseline = Map.of();

  WorkspaceWatcher(final Path workspaceRoot) {
    this.manifestPath =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON);
    this.lastManifestMtime = mtime(manifestPath);
  }

  PollResult poll() {
    if (detectManifestChange()) {
      LOG.info(() -> "[watcher] workspace.json changed");
      return PollResult.WORKSPACE_CHANGED;
    }

    if (detectPomChange()) {
      LOG.info(() -> "[watcher] POM changed — sync needed");
      return PollResult.POM_CHANGED;
    }

    return PollResult.NO_CHANGE;
  }

  void updatePomPaths(final List<Path> absPomPaths) {
    pomBaseline =
        absPomPaths.stream()
            .collect(Collectors.toUnmodifiableMap(p -> p, WorkspaceWatcher::fingerprint));
  }

  private boolean detectManifestChange() {
    final long current = mtime(manifestPath);
    if (current == lastManifestMtime) {
      return false;
    }

    lastManifestMtime = current;
    return true;
  }

  private boolean detectPomChange() {
    return pomBaseline.entrySet().stream()
        .anyMatch(e -> !fingerprint(e.getKey()).equals(e.getValue()));
  }

  private static PomFingerprint fingerprint(final Path path) {
    return new PomFingerprint(mtime(path), size(path));
  }

  private static long mtime(final Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (final IOException e) {
      return 0;
    }
  }

  private static long size(final Path path) {
    try {
      return Files.size(path);
    } catch (final IOException e) {
      return 0;
    }
  }
}
