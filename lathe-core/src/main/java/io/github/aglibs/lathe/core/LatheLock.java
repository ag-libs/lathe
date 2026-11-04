package io.github.aglibs.lathe.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

public final class LatheLock {

  private static final long STALE_MS = 2 * 60 * 1000;
  private static final long POLL_INTERVAL_MS = 100;

  private LatheLock() {}

  // On Windows a lock file just released stays "delete pending" while another process (a virus
  // scanner, an indexer) still holds it open, and writing it again is refused until that ends.
  public static void acquire(final Path lockFile) throws IOException {
    Files.createDirectories(lockFile.getParent());
    for (int attempt = 1; ; attempt++) {
      try {
        Files.writeString(lockFile, "");
        return;
      } catch (final AccessDeniedException e) {
        FileUtil.pauseBeforeRetry(attempt, e);
      }
    }
  }

  public static void release(final Path lockFile) throws IOException {
    Files.deleteIfExists(lockFile);
  }

  public static <T> T awaitAndRead(final Path lockFile, final IOUtil.IOSupplier<T> reader)
      throws IOException {
    awaitClear(lockFile);
    return reader.get();
  }

  /**
   * Non-blocking check of whether the lock is held — present and not stale. Unlike {@link
   * #awaitAndRead}, this never waits. An unreadable lock is treated as not held.
   */
  public static boolean isBuilding(final Path lockFile) {
    try {
      return isHeld(lockFile);
    } catch (final IOException e) {
      return false;
    }
  }

  // An exclusive lock between processes, taken without waiting: empty while another process, or
  // another holder in this one, has it. Closing the result releases it; so does the process ending.
  public static Optional<Closeable> tryExclusive(final Path lockFile) throws IOException {
    Files.createDirectories(lockFile.getParent());
    final var channel =
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      if (channel.tryLock() != null) {
        return Optional.of(channel);
      }
    } catch (final OverlappingFileLockException e) {
      // Held in this process.
    }

    channel.close();
    return Optional.empty();
  }

  private static void awaitClear(final Path lockFile) throws IOException {
    while (isHeld(lockFile)) {
      IOUtil.sleep(POLL_INTERVAL_MS);
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
}
