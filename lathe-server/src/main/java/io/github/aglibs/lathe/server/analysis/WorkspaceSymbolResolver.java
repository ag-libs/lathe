package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;

public final class WorkspaceSymbolResolver {

  private static final Range FILE_START = new Range(new Position(0, 0), new Position(0, 0));

  private WorkspaceSymbolResolver() {}

  public static List<SymbolInformation> resolve(
      final String query, final WorkspaceTypeIndex typeIndex, final List<Path> sourceDirs) {
    if (query.isBlank()) {
      return List.of();
    }

    try (var parser = new SourceParser()) {
      return typeIndex.search(query, 100).stream()
          .map(entry -> toSymbolInformation(entry, sourceDirs, parser))
          .filter(Objects::nonNull)
          .toList();
    }
  }

  private static SymbolInformation toSymbolInformation(
      final TypeIndexEntry entry, final List<Path> sourceDirs, final SourceParser parser) {
    final Path file = resolveSourcePath(entry, sourceDirs);
    if (file == null) {
      return null;
    }

    final var range = declarationRange(file, entry.simpleName(), parser);
    final var location = new Location(file.toUri().toString(), range);
    final var info =
        new SymbolInformation(
            entry.simpleName(), SymbolKinds.fromTypeIndex(entry.kind()), location);
    info.setContainerName(entry.packageName());
    return info;
  }

  private static Range declarationRange(
      final Path file, final String simpleName, final SourceParser parser) {
    return parser
        .parseFile(
            file,
            (trees, cu) -> {
              final var path = classPath(cu, simpleName);
              try {
                return SourceLocator.declarationNamePosition(trees, cu, path, simpleName)
                    .map(pos -> new Range(pos, pos))
                    .orElse(FILE_START);
              } catch (final IOException e) {
                return FILE_START;
              }
            })
        .orElse(FILE_START);
  }

  private static TreePath classPath(final CompilationUnitTree cu, final String simpleName) {
    final var result = new AtomicReference<TreePath>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitClass(final ClassTree tree, final Void unused) {
        if (result.get() == null && tree.getSimpleName().contentEquals(simpleName)) {
          result.set(getCurrentPath());
        }

        return super.visitClass(tree, unused);
      }
    }.scan(cu, null);
    return result.get();
  }

  static Path resolveSourcePath(final TypeIndexEntry entry, final List<Path> sourceDirs) {
    return TypeSourceLocator.findSourceFile(entry, sourceDirs).orElse(null);
  }
}
