package io.github.aglibs.lathe.server;

import java.net.URI;
import java.nio.file.Path;

public final class LatheUri {

  private LatheUri() {}

  public static Path toPath(final String uri) {
    return Path.of(URI.create(uri));
  }
}
