package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.LatheUri;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

// The workspace commands that are not about a document: the reactor's and dependencies' resources,
// opening one, and a new type's file. They read the workspace state, which checks the loop.
final class WorkspaceQueries {

  private static final Logger LOG = Logger.getLogger(WorkspaceQueries.class.getName());

  private final WorkspaceState state;
  private final WritePass writePass;

  WorkspaceQueries(final WorkspaceState state, final WritePass writePass) {
    this.state = state;
    this.writePass = writePass;
  }

  // The stack frames among a run's output lines, resolved to their sources. The roots are read on
  // the loop and the files looked up on the common pool: a long trace stats many of them.
  CompletableFuture<List<StackFrames.Resolved>> resolveStack(final List<String> lines) {
    final List<Path> sourceRoots = state.typeSourceDirs();
    return CompletableFuture.supplyAsync(() -> StackFrames.resolve(lines, sourceRoots));
  }

  // Writes the new type's file and reports it to the write pass, as an editor save does, so the
  // pass compiles it and the type never reads as unbuilt. The answer does not wait for that pass:
  // it may queue behind a running one or another server's write lock, and the editor opens the
  // file now. An existing file is never overwritten: the answer says so and the editor opens it.
  CreatedType createType(final CreateTypeArgs args) {
    final NewTypeSource source = renderType(args);
    final Path path = Path.of(source.path());
    final String uri = LatheUri.fromPath(path);
    if (Files.exists(path)) {
      return new CreatedType(uri, null, true);
    }

    IOUtil.unchecked(() -> write(path, source.content()));
    writePass.writeReported(Set.of(path));
    return new CreatedType(uri, source.caret(), false);
  }

  // CREATE_NEW: a file that appeared since the check is not overwritten either.
  private static void write(final Path path, final String content) throws IOException {
    Files.createDirectories(path.getParent());
    Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
  }

  private NewTypeSource renderType(final CreateTypeArgs args) {
    // module-info.java always belongs to the main source root, whatever scope the client sent.
    final var scope = args.type() == TypeKind.MODULE_INFO ? SourceScope.MAIN : args.kind();
    final var sourceRoot = state.layout().sourceRootFor(args.moduleRel(), scope);
    final String name = typeName(args);
    LOG.info(
        () ->
            "[createType] %s %s.%s kind=%s type=%s"
                .formatted(args.moduleRel(), args.pkg(), name, args.kind(), args.type()));
    return NewTypeTemplates.render(sourceRoot, args.pkg(), args.type(), name);
  }

  // A module-info sent without a name is named after the module's base package (the JPMS
  // convention); a module with no package yet has none to give, and the client asks for one.
  private String typeName(final CreateTypeArgs args) {
    if (args.type() != TypeKind.MODULE_INFO || !args.name().isBlank()) {
      return args.name();
    }

    final String base = state.layout().basePackage(args.moduleRel());
    if (base.isEmpty()) {
      throw new IllegalArgumentException(
          "%s has no package to name its module after; give the module a name"
              .formatted(SourceLayout.displayed(args.moduleRel())));
    }

    return base;
  }

  // Ranked for the file the user is in; a null file (no buffer) ranks by the kind's scope alone.
  // The layout and the context are read on the loop and the trees walked on the common pool: a big
  // reactor has hundreds of them.
  CompletableFuture<List<TypeDestination>> destinations(
      final Path file, final TypeKind kind, final String pkg) {
    final SourceLayout layout = state.layout();
    final ContextInfo context = file == null ? null : layout.resolveContext(file);
    return CompletableFuture.supplyAsync(() -> layout.destinations(context, kind, pkg));
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
