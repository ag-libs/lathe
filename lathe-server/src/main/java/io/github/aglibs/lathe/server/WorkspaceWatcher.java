package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.ContentFingerprint;
import io.github.aglibs.lathe.server.files.ServerFiles;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class WorkspaceWatcher {

  private static final Logger LOG = Logger.getLogger(WorkspaceWatcher.class.getName());
  private static final long NO_FAILED_READ = Long.MIN_VALUE;

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
  // Watched even when the last sync saw none: declaring a formatter there needs a sync too.
  private final Path styleFile;
  // The manifest as last read: a read that fails changes neither.
  private long lastManifestMtime;
  private String lastManifestContent;
  // A failed read of an existing manifest: the mtime it failed at, or NO_FAILED_READ, and whether
  // its content failed (not UTF-8), which only a rewrite fixes, or the access to it did (a
  // permission, a lock while a sync replaces it), which is tried again on every poll.
  private long failedReadMtime = NO_FAILED_READ;
  private boolean failedOnContent;
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
    this.styleFile = workspaceRoot.resolve(LatheLayout.STYLE_SHARED_FILE);
    this.lastManifestMtime = mtime(manifestPath);
    this.lastManifestContent = readManifest(lastManifestMtime).orElse("");
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
  // content is not what the sync saw (a touch, or an edit put back, is not a change). A file the
  // sync never saw differs only while it exists.
  private boolean differsFromSync(final Path file) {
    final ContentFingerprint synced = syncedContent.get(file);
    if (synced == null && file.equals(styleFile)) {
      return Files.exists(file);
    }

    return !ServerFiles.hasContent(file, synced);
  }

  // A file watched by touch is taken as changed before startup when it is newer than the manifest
  // the last sync wrote.
  void updateBuildFiles(final List<Path> buildFiles, final Map<Path, ContentFingerprint> synced) {
    final long syncedMs = mtime(manifestPath);
    final List<Path> absBuildFiles =
        Stream.concat(buildFiles.stream(), Stream.of(styleFile)).distinct().toList();
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
    final boolean retry =
        failedReadMtime != NO_FAILED_READ && !(failedOnContent && current == failedReadMtime);
    if (current == lastManifestMtime && !retry) {
      return ManifestChange.NONE;
    }

    final Optional<String> read = readManifest(current);
    if (read.isEmpty()) {
      return ManifestChange.NONE;
    }

    final boolean touched = current != lastManifestMtime;
    lastManifestMtime = current;
    if (read.get().equals(lastManifestContent)) {
      return touched ? ManifestChange.REACTOR_ONLY : ManifestChange.NONE;
    }

    lastManifestContent = read.get();
    return ManifestChange.STRUCTURAL;
  }

  // A missing manifest (not configured yet, or removed meanwhile) is a legitimate empty state. One
  // that exists but cannot be read is not a change: the last content stands (at startup, none, and
  // the manifest's own load reports the problem to the editor), and the read is tried again.
  private Optional<String> readManifest(final long mtime) {
    if (!Files.exists(manifestPath)) {
      return Optional.of("");
    }

    try {
      final String content = Files.readString(manifestPath, StandardCharsets.UTF_8);
      failedReadMtime = NO_FAILED_READ;
      return Optional.of(content);
    } catch (final NoSuchFileException e) {
      return Optional.of("");
    } catch (final IOException e) {
      if (failedReadMtime == NO_FAILED_READ) {
        LOG.fine(() -> "[watcher] %s unreadable %s".formatted(manifestPath, e));
      }

      failedReadMtime = mtime;
      failedOnContent = e instanceof CharacterCodingException;
      return Optional.empty();
    }
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
