package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    REACTOR_REFRESH,
    POM_CHANGED
  }

  // A manifest mtime bump with changed content (STRUCTURAL) vs unchanged content (REACTOR_ONLY).
  private enum ManifestChange {
    NONE,
    STRUCTURAL,
    REACTOR_ONLY
  }

  private record PomFingerprint(long mtime, long size) {}

  private final Path manifestPath;
  private long lastManifestMtime;
  private String lastManifestContent;
  private Map<Path, PomFingerprint> pomBaseline = Map.of();

  WorkspaceWatcher(final Path workspaceRoot) {
    this.manifestPath =
        workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON);
    this.lastManifestMtime = mtime(manifestPath);
    this.lastManifestContent = readManifest();
  }

  PollResult poll() {
    final ManifestChange manifestChange = detectManifestChange();
    if (manifestChange == ManifestChange.STRUCTURAL) {
      LOG.info(() -> "[watcher] workspace.json changed");
      return PollResult.WORKSPACE_CHANGED;
    }

    if (manifestChange == ManifestChange.REACTOR_ONLY) {
      LOG.info(() -> "[watcher] reactor changed — re-scan needed");
      return PollResult.REACTOR_REFRESH;
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

  private ManifestChange detectManifestChange() {
    final long current = mtime(manifestPath);
    if (current == lastManifestMtime) {
      return ManifestChange.NONE;
    }

    lastManifestMtime = current;
    final String content = readManifest();
    if (content.equals(lastManifestContent)) {
      return ManifestChange.REACTOR_ONLY;
    }

    lastManifestContent = content;
    return ManifestChange.STRUCTURAL;
  }

  private String readManifest() {
    // Missing manifest (not configured yet) is a legitimate empty state; a read failure on an
    // existing one is a real error, not empty content.
    if (!Files.exists(manifestPath)) {
      return "";
    }

    return IOUtil.unchecked(() -> Files.readString(manifestPath, StandardCharsets.UTF_8));
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
