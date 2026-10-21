package io.github.aglibs.lathe.server;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

public final class LatheUri {

  private LatheUri() {}

  public static Path toPath(final String uri) {
    return Path.of(URI.create(uri));
  }

  // The one URI a file has inside the server: the form Path.toUri gives, as the server builds its
  // own. A client may encode the same file differently (VS Code writes @ as %40, + as %2B, and a
  // lower-case Windows drive), and the server keys documents by the URI string, so every client
  // URI passes through here once, where it enters. Anything but a file URI passes unchanged.
  public static String canonical(final String uri) {
    if (!isFileUri(uri)) {
      return uri;
    }

    final var path = toPath(uri);
    final Path root = path.getRoot();
    final Path upperDrive =
        root == null
            ? path
            : Path.of(root.toString().toUpperCase(Locale.ROOT)).resolve(root.relativize(path));
    return upperDrive.toUri().toString();
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
