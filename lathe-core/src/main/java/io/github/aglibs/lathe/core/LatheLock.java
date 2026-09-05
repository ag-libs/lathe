package io.github.aglibs.lathe.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

public final class LatheLock {

  private static final long STALE_MS = 2 * 60 * 1000;
  private static final long POLL_INTERVAL_MS = 100;

  private LatheLock() {}

  public static void acquire(final Path moduleDir) throws IOException {
    Files.createDirectories(moduleDir);
    Files.writeString(moduleDir.resolve(LatheLayout.LOCK_FILE), "");
  }

  public static void release(final Path moduleDir) throws IOException {
    Files.deleteIfExists(moduleDir.resolve(LatheLayout.LOCK_FILE));
  }

  public static <T> T awaitAndRead(final Path moduleDir, final IOUtil.IOSupplier<T> reader)
      throws IOException {
    awaitClear(moduleDir.resolve(LatheLayout.LOCK_FILE));
    return reader.get();
  }

  /**
   * Non-blocking check of whether a module is currently being built/synced — its lock is present
   * and not stale. Unlike {@link #awaitAndRead}, this never waits; a poll loop uses it to skip a
   * module whose {@code .lathe/<module>/} is mid-write. An unreadable lock is treated as not
   * building.
   */
  public static boolean isBuilding(final Path moduleDir) {
    try {
      return isHeld(moduleDir.resolve(LatheLayout.LOCK_FILE));
    } catch (final IOException e) {
      return false;
    }
  }

  private static void awaitClear(final Path lockFile) throws IOException {
    while (isHeld(lockFile)) {
      sleep();
    }
  }

  private static boolean isHeld(final Path lockFile) throws IOException {
    if (!Files.exists(lockFile)) {
      return false;
    }

    try {
      final long modifiedMs = Files.getLastModifiedTime(lockFile).toMillis();
      return Stopwatch.start().startMs() - modifiedMs < STALE_MS;
    } catch (final NoSuchFileException e) {
      return false;
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(POLL_INTERVAL_MS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
