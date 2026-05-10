package io.github.aglibs.lathe.core;

import java.io.IOException;
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

  @FunctionalInterface
  public interface IORunnable {
    void run() throws IOException;
  }

  @FunctionalInterface
  public interface IOSupplier<T> {
    T get() throws IOException;
  }
}
