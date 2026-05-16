package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

final class WorkspaceWatcher {

  private static final Logger LOG = Logger.getLogger(WorkspaceWatcher.class.getName());

  private record Fingerprint(long count, long maxMtime) {
    static final Fingerprint EMPTY = new Fingerprint(0L, 0L);
  }

  private final Path latheDir;
  private final Path manifestPath;
  private final Runnable onReload;
  private final AtomicLong lastManifestMtime;
  private final AtomicReference<Fingerprint> lastParams;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            final var t = new Thread(r, "lathe-watcher");
            t.setDaemon(true);
            return t;
          });

  WorkspaceWatcher(final Path workspaceRoot, final Runnable onReload) {
    this.latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    this.manifestPath = latheDir.resolve(LatheLayout.WORKSPACE_JSON);
    this.onReload = onReload;
    this.lastManifestMtime = new AtomicLong(mtime(manifestPath));
    this.lastParams = new AtomicReference<>(paramsFingerprint());
  }

  void start() {
    executor.scheduleAtFixedRate(this::poll, 2, 2, TimeUnit.SECONDS);
  }

  void close() {
    executor.shutdownNow();
  }

  void poll() {
    final var manifestChanged = detectManifestChange();
    final var paramsChanged = detectParamsChange();
    if (manifestChanged || paramsChanged) {
      LOG.info(
          () ->
              "[watcher] reloading (manifest=%s, params=%s)"
                  .formatted(manifestChanged, paramsChanged));
      onReload.run();
    }
  }

  private boolean detectManifestChange() {
    final long current = mtime(manifestPath);
    if (current == lastManifestMtime.get()) {
      return false;
    }

    lastManifestMtime.set(current);
    LOG.fine(() -> "[watcher] workspace.json changed");
    return true;
  }

  private boolean detectParamsChange() {
    final var current = paramsFingerprint();
    if (current.equals(lastParams.get())) {
      return false;
    }

    lastParams.set(current);
    LOG.fine(() -> "[watcher] params files changed");
    return true;
  }

  private Fingerprint paramsFingerprint() {
    if (!Files.isDirectory(latheDir)) {
      return Fingerprint.EMPTY;
    }

    try (final var stream = Files.walk(latheDir)) {
      final var files = stream.filter(WorkspaceWatcher::isParamsFile).toList();
      final long maxMtime = files.stream().mapToLong(WorkspaceWatcher::mtime).max().orElse(0L);
      return new Fingerprint(files.size(), maxMtime);
    } catch (final IOException e) {
      return Fingerprint.EMPTY;
    }
  }

  private static boolean isParamsFile(final Path p) {
    final var name = p.getFileName().toString();
    return name.startsWith("lsp-params-") && name.endsWith(".json");
  }

  private static long mtime(final Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (final IOException e) {
      return 0;
    }
  }
}
