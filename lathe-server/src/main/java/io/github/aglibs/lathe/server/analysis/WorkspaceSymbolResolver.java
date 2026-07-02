package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.server.ProgressReporter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

public final class WorkspaceSymbolResolver {

  private static final Range FILE_START = new Range(new Position(0, 0), new Position(0, 0));
  private static final String BROWSE_TITLE = "Loading workspace symbols";
  private static final int SEARCH_LIMIT = 100;

  private WorkspaceSymbolResolver() {}

  public static List<SymbolInformation> resolve(
      final String query, final WorkspaceTypeIndex typeIndex, final List<Path> sourceDirs) {
    return resolve(query, typeIndex, sourceDirs, () -> {}, null);
  }

  public static List<SymbolInformation> resolve(
      final String query,
      final WorkspaceTypeIndex typeIndex,
      final List<Path> sourceDirs,
      final CancelChecker cancelChecker,
      final ProgressReporter.Task progress) {
    final boolean blank = query.isBlank();
    final List<TypeIndexEntry> entries =
        blank ? typeIndex.browseWorkspace() : typeIndex.search(query, SEARCH_LIMIT);
    if (blank && progress != null) {
      progress.begin(BROWSE_TITLE, entries.size());
    }

    final var results = new ArrayList<SymbolInformation>();
    try (var parser = new SourceParser()) {
      for (final TypeIndexEntry entry : entries) {
        cancelChecker.checkCanceled();

        final SymbolInformation info = toSymbolInformation(entry, sourceDirs, parser);
        if (info != null) {
          results.add(info);
        }

        if (blank && progress != null) {
          progress.advance();
        }
      }
    }

    return List.copyOf(results);
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
