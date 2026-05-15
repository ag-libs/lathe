package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

final class WorkspaceWatcher {

  private static final Logger LOG = Logger.getLogger(WorkspaceWatcher.class.getName());

  private final Path marker;
  private final Runnable onReload;
  private final AtomicLong lastSeen;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            final var t = new Thread(r, "lathe-watcher");
            t.setDaemon(true);
            return t;
          });

  WorkspaceWatcher(final Path workspaceRoot, final Runnable onReload) {
    this.marker = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.WORKSPACE_JSON);
    this.onReload = onReload;
    this.lastSeen = new AtomicLong(mtime(marker));
  }

  void start() {
    executor.scheduleAtFixedRate(this::poll, 2, 2, TimeUnit.SECONDS);
  }

  void close() {
    executor.shutdownNow();
  }

  private void poll() {
    final long current = mtime(marker);
    if (current != lastSeen.get()) {
      lastSeen.set(current);
      LOG.info(() -> "[registry] workspace.json changed — reloading");
      onReload.run();
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
