package io.github.aglibs.lathe.server;

import java.net.URI;
import java.nio.file.Path;

/** URI utilities for the {@code lathe-source://} custom scheme. */
public final class LatheUri {

  public static final String SCHEME = "lathe-source://";

  private LatheUri() {}

  public static String fromPath(final Path path) {
    return SCHEME + path;
  }

  public static Path toPath(final String uri) {
    if (uri.startsWith(SCHEME)) {
      return Path.of(uri.substring(SCHEME.length()));
    }
    return Path.of(URI.create(uri));
  }
}
