package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class WorkspaceWatcher {

  private static final Logger LOG = Logger.getLogger(WorkspaceWatcher.class.getName());

  enum PollResult {
    NO_CHANGE,
    WORKSPACE_CHANGED,
    REACTOR_REFRESH,
    BUILD_FILES_CHANGED,
    // Every build file that differed from the sync is back to the synced content.
    BUILD_FILES_RESTORED
  }

  // A manifest mtime bump with changed content (STRUCTURAL) vs unchanged content (REACTOR_ONLY).
  private enum ManifestChange {
    NONE,
    STRUCTURAL,
    REACTOR_ONLY
  }

  private record FileStat(long mtime, long size) {}

  private final Path manifestPath;
  private long lastManifestMtime;
  private String lastManifestContent;
  // Each build file's mtime and size at the last poll: what moved since is looked at again.
  private Map<Path, FileStat> buildFileStats = Map.of();
  // The content the last sync saw, for build files watched by content; the rest are watched by
  // touch (OpenJDK's batch markers).
  private Map<Path, ContentFingerprint> syncedContent = Map.of();
  private Set<Path> changedSinceSync = Set.of();
  // Changed while no server watched, reported by the first poll.
  private Set<Path> changedBeforeStartup = Set.of();

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

    final Map<Path, FileStat> current = statAll(buildFileStats.keySet());
    final Set<Path> moved =
        Stream.concat(
                current.keySet().stream()
                    .filter(file -> !current.get(file).equals(buildFileStats.get(file))),
                changedBeforeStartup.stream())
            .collect(Collectors.toUnmodifiableSet());
    buildFileStats = current;
    changedBeforeStartup = Set.of();
    return moved.isEmpty() ? PollResult.NO_CHANGE : reassess(moved);
  }

  // Reported once per change: the sync prompt it raises may wait unanswered for hours, and a
  // report on every poll meanwhile would rescan the whole workspace each tick. Reported again, as
  // restored, once every changed file is back to what the sync saw.
  private PollResult reassess(final Set<Path> moved) {
    final Set<Path> differing =
        moved.stream().filter(this::differsFromSync).collect(Collectors.toUnmodifiableSet());
    final boolean wasChanged = !changedSinceSync.isEmpty();
    changedSinceSync =
        Stream.concat(
                changedSinceSync.stream().filter(file -> !moved.contains(file)), differing.stream())
            .collect(Collectors.toUnmodifiableSet());
    if (!differing.isEmpty()) {
      LOG.info(() -> "[watcher] build files changed — sync needed");
      return PollResult.BUILD_FILES_CHANGED;
    }

    if (wasChanged && changedSinceSync.isEmpty()) {
      LOG.info(() -> "[watcher] build files back to the synced content — no sync needed");
      return PollResult.BUILD_FILES_RESTORED;
    }

    return PollResult.NO_CHANGE;
  }

  // A file watched by touch differs whenever it moved; one watched by content only when its
  // content is not what the sync saw (a touch, or an edit put back, is not a change).
  private boolean differsFromSync(final Path file) {
    return !FileUtil.hasContent(file, syncedContent.get(file));
  }

  // A file watched by touch is taken as changed before startup when it is newer than the manifest
  // the last sync wrote.
  void updateBuildFiles(
      final List<Path> absBuildFiles, final Map<Path, ContentFingerprint> synced) {
    final long syncedMs = mtime(manifestPath);
    syncedContent = Map.copyOf(synced);
    buildFileStats = statAll(absBuildFiles);
    changedSinceSync = Set.of();
    changedBeforeStartup =
        absBuildFiles.stream()
            .filter(
                file -> synced.containsKey(file) ? differsFromSync(file) : mtime(file) > syncedMs)
            .collect(Collectors.toUnmodifiableSet());
  }

  private static Map<Path, FileStat> statAll(final Collection<Path> paths) {
    return paths.stream().collect(Collectors.toUnmodifiableMap(p -> p, WorkspaceWatcher::stat));
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

  private static FileStat stat(final Path path) {
    return new FileStat(mtime(path), size(path));
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
