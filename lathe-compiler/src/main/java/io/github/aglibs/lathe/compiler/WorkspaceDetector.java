package io.github.aglibs.lathe.compiler;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class WorkspaceDetector {

  private WorkspaceDetector() {}

  static Optional<Path> findWorkspaceRoot(final Path startDir) {
    var current = startDir;
    while (current != null) {
      if (Files.exists(current.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.ROOT_MARKER))) {
        return Optional.of(current);
      }
      current = current.getParent();
    }

    return Optional.empty();
  }
}
