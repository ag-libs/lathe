package io.github.aglibs.lathe.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class LatheWorkspace {

  private LatheWorkspace() {}

  public static Optional<Path> findRoot(final Path startDir) {
    if (LatheFlags.isDisabled()) {
      return Optional.empty();
    }

    var current = startDir;
    while (current != null) {
      if (Files.isDirectory(current.resolve(LatheLayout.LATHE_DIR))) {
        return Optional.of(current);
      }

      current = current.getParent();
    }

    return Optional.empty();
  }
}
