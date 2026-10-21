package io.github.aglibs.lathe.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;

public final class IOUtil {

  private IOUtil() {}

  public static void unchecked(final IORunnable action) {
    try {
      action.run();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static <T> T unchecked(final IOSupplier<T> supplier) {
    try {
      return supplier.get();
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // A wait between I/O retries or polls. An interrupt keeps the thread's flag and ends the wait as
  // an InterruptedIOException, which the caller's IOException handling already covers.
  public static void sleep(final long millis) throws InterruptedIOException {
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("interrupted while waiting");
    }
  }

  public static void rethrowIfError(final Throwable t) {
    if (t instanceof final Error error) {
      throw error;
    }
  }

  @FunctionalInterface
  public interface IORunnable {
    void run() throws IOException;
  }

  @FunctionalInterface
  public interface IOSupplier<T> {
    T get() throws IOException;
  }
}
