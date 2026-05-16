package io.github.aglibs.lathe.compiler;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class WorkspaceDetector {

  private WorkspaceDetector() {}

  static Optional<Path> findWorkspaceRoot(final Path startDir) {
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
