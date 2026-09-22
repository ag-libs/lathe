package io.github.aglibs.lathe.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class LatheWorkspace {

  private LatheWorkspace() {}

  public static Optional<Path> findRoot(final Path startDir) {
    return findRoot(startDir, null);
  }

  // Search upward from startDir for a .lathe/, but never climb above ceiling — the directory Maven
  // treats as this build's root. A build inside a nested checkout/worktree therefore cannot adopt a
  // parent repo's .lathe/. A null ceiling leaves the search unbounded.
  public static Optional<Path> findRoot(final Path startDir, final Path ceiling) {
    if (LatheFlags.isDisabled()) {
      return Optional.empty();
    }

    var current = startDir;
    while (current != null) {
      if (Files.isDirectory(current.resolve(LatheLayout.LATHE_DIR))) {
        return Optional.of(current);
      }

      if (current.equals(ceiling)) {
        return Optional.empty();
      }

      current = current.getParent();
    }

    return Optional.empty();
  }
}
