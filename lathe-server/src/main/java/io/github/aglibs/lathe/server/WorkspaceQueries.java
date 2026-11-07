package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.FileUtil;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

// The workspace commands that are not about a document: the reactor's and dependencies' resources,
// opening one, and a new type's file. They read the workspace state, which checks the loop.
final class WorkspaceQueries {

  private static final Logger LOG = Logger.getLogger(WorkspaceQueries.class.getName());

  private final WorkspaceState state;

  WorkspaceQueries(final WorkspaceState state) {
    this.state = state;
  }

  // The stack frames among a run's output lines, resolved to their sources. The roots are read on
  // the loop and the files looked up on the common pool: a long trace stats many of them.
  CompletableFuture<List<StackFrames.Resolved>> resolveStack(final List<String> lines) {
    final List<Path> sourceRoots = state.typeSourceDirs();
    return CompletableFuture.supplyAsync(() -> StackFrames.resolve(lines, sourceRoots));
  }

  CreateTypeResult createType(final CreateTypeArgs args) {
    // module-info.java always belongs to the main source root, whatever scope the client sent.
    final var scope = args.type() == TypeKind.MODULE_INFO ? SourceScope.MAIN : args.kind();
    final var sourceRoot = state.layout().sourceRootFor(args.moduleRel(), scope);
    LOG.info(
        () ->
            "[createType] %s %s.%s kind=%s type=%s"
                .formatted(args.moduleRel(), args.pkg(), args.name(), args.kind(), args.type()));
    return NewTypeTemplates.render(sourceRoot, args.pkg(), args.type(), args.name());
  }

  List<ResourceEntry> resources() {
    return ResourceFinder.list(
        state.manifest().resourceDirModules(), state.manifest().libraries().jarToGav());
  }

  // Extracts one dependency jar entry to disk (read-only) and returns its path -- the only bytes
  // written, on demand. Reuses the dep's sources cache dir when present, else a temp dir. Reactor
  // resources need no extraction (they carry their real path).
  String resourceOpen(final String jar, final String entry) {
    final var jarPath = Path.of(jar);
    final Path dest =
        state
            .manifest()
            .libraries()
            .dependencySourceDir(jarPath)
            .map(dir -> dir.resolve(entry))
            .orElseGet(ResourceFinder.tempDest(entry));
    try {
      FileUtil.extractEntry(jarPath, entry, dest);
    } catch (final IOException e) {
      throw new UncheckedIOException("extract %s from %s".formatted(entry, jar), e);
    }

    return dest.toString();
  }
}
