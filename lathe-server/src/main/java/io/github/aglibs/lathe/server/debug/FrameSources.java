package io.github.aglibs.lathe.server.debug;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import io.github.aglibs.lathe.server.analysis.TypeSourceLocator;
import io.github.aglibs.lathe.server.module.CompilationWorker;
import io.github.aglibs.lathe.server.module.WorkspaceModuleRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Resolves a suspended JDI frame's location to Lathe's source file and the module worker that owns
 * it — the shared front half the debugger's evaluation and completion providers both need before
 * they can analyse the frame's line.
 */
final class FrameSources {

  private final WorkspaceModuleRegistry workspace;
  private final List<Path> sourceRoots;

  FrameSources(final WorkspaceModuleRegistry workspace, final List<Path> sourceRoots) {
    this.workspace = workspace;
    this.sourceRoots = List.copyOf(sourceRoots);
  }

  /**
   * The source file backing {@code location}, found on a configured source root or, failing that,
   * by the declaring type's name. Empty when the location carries no source information or no file
   * matches.
   */
  Optional<Path> fileFor(final Location location) {
    try {
      final String sourcePath = location.sourcePath();
      final Optional<Path> onRoot =
          sourceRoots.stream()
              .map(root -> root.resolve(sourcePath))
              .filter(Files::exists)
              .findFirst();
      if (onRoot.isPresent()) {
        return onRoot;
      }

      return TypeSourceLocator.findSourceFile(location.declaringType().name(), sourceRoots);
    } catch (final AbsentInformationException e) {
      return Optional.empty();
    }
  }

  /** The module worker that owns {@code file}, if the file maps to a known module source. */
  Optional<CompilationWorker> workerFor(final Path file) {
    return workspace.moduleSourceFor(file).map(workspace::workerFor);
  }
}
