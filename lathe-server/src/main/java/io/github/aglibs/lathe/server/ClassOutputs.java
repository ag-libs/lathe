package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.CompiledStamps;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.typeindex.ClassSourceFile;
import io.github.aglibs.lathe.server.module.ModuleSourceConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

// A source's class files in the .lathe/ mirror: which a compile wrote, and deleting those a source
// no longer produces (a removed nested or sibling type, a deleted source). File operations only, no
// session state.
final class ClassOutputs {

  private static final Logger LOG = Logger.getLogger(ClassOutputs.class.getName());

  private ClassOutputs() {}

  // Deletes the source's class outputs except those the latest compile wrote (keptBinaryNames):
  // empty for a deleted source, the compile's output set to prune what a save removed.
  static int deleteClassOutputs(
      final ModuleSourceConfig config, final Path source, final Set<String> keptBinaryNames) {
    if (!FileUtil.isJavaFile(source)) {
      return 0;
    }

    final var classDir = classDirOf(config, source);
    if (classDir == null || !Files.isDirectory(classDir)) {
      return 0;
    }

    try (final var stream = Files.list(classDir)) {
      // A source at the tree's root (module-info.java) shares its directory with the packages.
      final var deletedClassFiles =
          stream
              .filter(path -> path.getFileName().toString().endsWith(".class"))
              .filter(
                  path ->
                      !keptBinaryNames.contains(
                          FileUtil.classBinaryName(config.latheClassesDir(), path)))
              .filter(path -> classOutputOf(source, path))
              .toList();
      deletedClassFiles.forEach(path -> IOUtil.unchecked(() -> Files.deleteIfExists(path)));
      if (!deletedClassFiles.isEmpty()) {
        LOG.fine(
            () ->
                "[delete] %s classes=%d".formatted(source.getFileName(), deletedClassFiles.size()));
      }

      return deletedClassFiles.size();
    } catch (final IOException | UncheckedIOException e) {
      LOG.log(Level.WARNING, e, () -> "[delete] class cleanup failed for %s".formatted(source));
      return 0;
    }
  }

  // A deleted source leaves its classes in the mirror, still resolvable by every sibling. A stamp
  // key with no surviving source is that orphan: drop its classes and prune the stamp. Returns the
  // class files removed (0 when a mismatched class cannot be located — a full mvn clean repairs
  // it).
  static int deleteOrphanedClassOutputs(final TreeState tree) {
    final ModuleSourceConfig config = tree.config();
    final List<Path> roots = config.sourceRoots();
    if (roots.isEmpty()) {
      return 0;
    }

    final Set<Path> orphans = tree.orphans();
    if (orphans.isEmpty()) {
      return 0;
    }

    LOG.fine(() -> "[delete] %s orphans=%s".formatted(config.moduleDir().getFileName(), orphans));
    final Path root = roots.get(0);
    int removed = 0;
    for (final var rel : orphans) {
      removed += deleteClassOutputs(config, root.resolve(rel), Set.of());
    }

    pruneOrphanStamps(config, orphans);
    return removed;
  }

  private static void pruneOrphanStamps(final ModuleSourceConfig config, final Set<Path> orphans) {
    try {
      CompiledStamps.prune(config.moduleDir(), config.sourceTree(), orphans);
    } catch (final IOException e) {
      LOG.log(
          Level.WARNING,
          e,
          () -> "[delete] stamp prune failed for %s".formatted(config.moduleDir()));
    }
  }

  // Any written class of the source counts (Foo.class, Foo$*.class, or a sibling type), so a file
  // declaring no type named after itself still freshens.
  static boolean wroteClassOf(
      final ModuleSourceConfig config, final Path source, final Set<String> writtenBinaryNames) {
    // No type to key on (package-info/module-info or no source root) -- do not block.
    if (config
        .sourceRootOf(source)
        .flatMap(root -> SourceTypeScanner.deriveEntry(root, source))
        .isEmpty()) {
      return true;
    }

    final var classDir = classDirOf(config, source);
    return writtenBinaryNames.stream()
        .map(name -> config.latheClassesDir().resolve("%s.class".formatted(name.replace('.', '/'))))
        .filter(classFile -> classFile.getParent().equals(classDir))
        .anyMatch(classFile -> classOutputOf(source, classFile));
  }

  // The mirror directory holding the source's package classes; null outside the config's roots.
  private static Path classDirOf(final ModuleSourceConfig config, final Path source) {
    final Optional<Path> sourceRoot = config.sourceRootOf(source);
    if (sourceRoot.isEmpty()) {
      return null;
    }

    final var packageRel = sourceRoot.get().relativize(source).getParent();
    return packageRel != null
        ? config.latheClassesDir().resolve(packageRel)
        : config.latheClassesDir();
  }

  // Foo.java's outputs are Foo.class, Foo$*.class, and any package-private sibling top-level type
  // (Helper.class). Only the class file's SourceFile attribute ties a sibling back to Foo.java; a
  // class generated from another source names that source, so it is never matched.
  private static boolean classOutputOf(final Path source, final Path classFile) {
    final var name = classFile.getFileName().toString();
    if (!name.endsWith(".class")) {
      return false;
    }

    final var typeName = FileUtil.javaTypeName(source);
    if (name.equals("%s.class".formatted(typeName)) || name.startsWith("%s$".formatted(typeName))) {
      return true;
    }

    final var sourceName = source.getFileName().toString();
    return ClassSourceFile.of(classFile).filter(sourceName::equals).isPresent();
  }
}
