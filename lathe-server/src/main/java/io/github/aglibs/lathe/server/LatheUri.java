package io.github.aglibs.lathe.server;

import java.net.URI;
import java.nio.file.Path;

public final class LatheUri {

  private LatheUri() {}

  public static Path toPath(final String uri) {
    return Path.of(URI.create(uri));
  }

  /**
   * True only for a local-file URI Lathe can analyze: scheme {@code file} with a non-empty path.
   * False (never throws) for a schemeless, non-file, or pathless URI -- e.g. {@code file://} (an
   * unnamed editor buffer) or {@code untitled:...} -- so callers can ignore documents that have no
   * real file behind them instead of crashing in {@link #toPath}.
   */
  public static boolean isFileUri(final String uri) {
    if (uri == null) {
      return false;
    }

    try {
      final var parsed = URI.create(uri);
      return "file".equals(parsed.getScheme())
          && parsed.getPath() != null
          && !parsed.getPath().isEmpty();
    } catch (final IllegalArgumentException ignored) {
      return false;
    }
  }
}
