package io.github.aglibs.lathe.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// .lathe/lathe.lock for a whole build, so the language server suppresses its sync prompt for the
// entire build, not only while a module compiles. Best-effort throughout: a failed lock costs a
// spurious prompt, never a failed build.
public final class BuildLock implements AutoCloseable {

  // Refresh well inside LatheLock's staleness window so a live build never looks crashed, while a
  // real crash (the heartbeat thread dies with the JVM) self-heals within that window.
  private static final long HEARTBEAT_MS = 30_000;

  private final Path latheDir;
  private final ScheduledExecutorService heartbeat;

  private BuildLock(final Path latheDir) {
    this.latheDir = latheDir;
    this.heartbeat = Executors.newSingleThreadScheduledExecutor(BuildLock::daemon);
    heartbeat.scheduleAtFixedRate(
        () -> touch(latheDir), HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
  }

  // Empty on a first-ever build (no .lathe/ yet): there is no mirror to protect, and creating it
  // early would flip the server's "configured" detection.
  public static Optional<BuildLock> hold(final Path latheDir) {
    if (!Files.isDirectory(latheDir) || !touch(latheDir)) {
      return Optional.empty();
    }

    return Optional.of(new BuildLock(latheDir));
  }

  public Path latheDir() {
    return latheDir;
  }

  @Override
  public void close() {
    heartbeat.shutdownNow();
    try {
      LatheLock.release(LatheLayout.reactorLockFile(latheDir));
    } catch (final IOException e) {
      // Best-effort; the staleness TTL reclaims a leaked lock.
    }
  }

  // Both the initial take and each heartbeat rewrite the lock file, bumping its mtime (and
  // recreating it if something deleted it mid-build).
  private static boolean touch(final Path latheDir) {
    try {
      LatheLock.acquire(LatheLayout.reactorLockFile(latheDir));
      return true;
    } catch (final IOException e) {
      return false;
    }
  }

  private static Thread daemon(final Runnable runnable) {
    final var thread = new Thread(runnable, "lathe-build-lock");
    thread.setDaemon(true);
    return thread;
  }
}
