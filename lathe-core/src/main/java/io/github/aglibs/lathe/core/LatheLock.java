package io.github.aglibs.lathe.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LatheLock {

  private static final Logger LOG = Logger.getLogger(LatheLock.class.getName());

  private static final long STALE_MS = 2 * 60 * 1000;
  private static final long POLL_INTERVAL_MS = 100;

  private LatheLock() {}

  // On Windows a lock file just released stays "delete pending" while another process (a virus
  // scanner, an indexer) still holds it open, and writing it again is refused until that ends.
  public static void acquire(final Path lockFile) throws IOException {
    Files.createDirectories(lockFile.getParent());
    final String identity = ownerIdentity(lockFile);

    for (int attempt = 1; ; attempt++) {
      try {
        Files.writeString(lockFile, identity, StandardCharsets.UTF_8);
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
   * Non-blocking check of whether the lock is held — fresh and not owned by an exited process.
   * Unlike {@link #awaitAndRead}, this never waits. An unreadable lock is treated as not held.
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

  // Runs the work under the lock of tryExclusive, waiting for it first: a build that finds another
  // filling the same cache entry waits for it, then finds the entry in place. onWait runs once,
  // when the lock is first found held, so a long wait is said rather than looking like a hang.
  public static <T> T exclusive(
      final Path lockFile, final Runnable onWait, final IOUtil.IOSupplier<T> work)
      throws IOException {
    final Closeable held = awaitExclusive(lockFile, onWait);
    try {
      return work.get();
    } finally {
      held.close();
    }
  }

  private static Closeable awaitExclusive(final Path lockFile, final Runnable onWait)
      throws IOException {
    for (int attempt = 0; ; attempt++) {
      final Optional<Closeable> held = tryExclusive(lockFile);
      if (held.isPresent()) {
        return held.get();
      }

      if (attempt == 0) {
        onWait.run();
      }

      IOUtil.sleep(POLL_INTERVAL_MS);
    }
  }

  private static void awaitClear(final Path lockFile) throws IOException {
    while (isHeld(lockFile)) {
      IOUtil.sleep(POLL_INTERVAL_MS);
    }
  }

  private static String ownerIdentity(final Path lockFile) {
    final var timer = Stopwatch.start();
    try {
      final ProcessHandle owner = ProcessHandle.current();
      return owner
          .info()
          .startInstant()
          .map(start -> "%d\n%s\n".formatted(owner.pid(), start))
          .orElse("");
    } catch (final SecurityException | UnsupportedOperationException e) {
      LOG.log(
          Level.FINE,
          e,
          () ->
              "[lock-owner] %s process inspection unavailable; using mtime expiry %dms"
                  .formatted(lockFile, timer.elapsedMs()));
      return "";
    }
  }

  private static boolean ownerMayBeAlive(final Path lockFile) throws IOException {
    final var timer = Stopwatch.start();
    try {
      final List<String> identity = Files.readAllLines(lockFile, StandardCharsets.UTF_8);
      if (identity.size() != 2) {
        return true;
      }

      final long pid = Long.parseLong(identity.get(0));
      final Instant start = Instant.parse(identity.get(1));
      if (pid <= 0) {
        return true;
      }

      final Optional<ProcessHandle> owner = ProcessHandle.of(pid);
      if (owner.isEmpty() || !owner.get().isAlive()) {
        return false;
      }

      return owner.get().info().startInstant().map(start::equals).orElse(true);
    } catch (final CharacterCodingException
        | NumberFormatException
        | DateTimeParseException
        | SecurityException
        | UnsupportedOperationException e) {
      LOG.log(
          Level.FINE,
          e,
          () ->
              "[lock-owner] %s ownership unavailable; using mtime expiry %dms"
                  .formatted(lockFile, timer.elapsedMs()));
      return true;
    }
  }

  private static boolean isHeld(final Path lockFile) throws IOException {
    if (!Files.exists(lockFile)) {
      return false;
    }

    try {
      final long modifiedMs = Files.getLastModifiedTime(lockFile).toMillis();
      if (Stopwatch.start().startMs() - modifiedMs >= STALE_MS) {
        return false;
      }

      return ownerMayBeAlive(lockFile);
    } catch (final NoSuchFileException e) {
      return false;
    }
  }
}
