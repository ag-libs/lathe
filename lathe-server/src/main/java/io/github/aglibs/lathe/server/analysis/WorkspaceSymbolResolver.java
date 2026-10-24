package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;

public final class WorkspaceSymbolResolver {

  private static final Range FILE_START = new Range(new Position(0, 0), new Position(0, 0));
  private static final int SEARCH_LIMIT = 100;

  private WorkspaceSymbolResolver() {}

  public static List<SymbolInformation> resolve(
      final String query, final WorkspaceTypeIndex typeIndex, final List<Path> sourceDirs) {
    if (query.isBlank()) {
      return List.of();
    }

    final List<TypeIndexEntry> entries = mergedEntries(query, typeIndex);
    final var results = new ArrayList<SymbolInformation>();
    try (var parser = new SourceParser()) {
      for (final TypeIndexEntry entry : entries) {
        final SymbolInformation info = toSymbolInformation(entry, sourceDirs, parser);
        if (info != null) {
          results.add(info);
        }
      }
    }

    return List.copyOf(results);
  }

  // Exact prefix search over the symbol index covers everything (JDK, dependencies, reactor),
  // including package-private top-level types so a stack frame's internal impl class resolves.
  // CamelCase-hump matching is a supplementary, reactor-only layer on top, so a type like
  // AbstractServerFactory is still reachable via "ServerFactory" or "ASF" even though it doesn't
  // start with either. Prefix matches win on overlap; the combined list stays capped at
  // SEARCH_LIMIT so the per-entry declarationRange parse cost is unchanged from before.
  private static List<TypeIndexEntry> mergedEntries(
      final String query, final WorkspaceTypeIndex typeIndex) {
    final int lastDot = query.lastIndexOf('.');
    if (lastDot >= 0) {
      return qualifiedEntries(query.substring(0, lastDot), query.substring(lastDot + 1), typeIndex);
    }

    final var seen = new LinkedHashSet<String>();
    final var merged = new ArrayList<TypeIndexEntry>();
    for (final TypeIndexEntry entry : typeIndex.searchSymbols(query, SEARCH_LIMIT)) {
      if (seen.add(entry.binaryName())) {
        merged.add(entry);
      }
    }

    for (final TypeIndexEntry entry : typeIndex.searchCamelCase(query, SEARCH_LIMIT)) {
      if (merged.size() >= SEARCH_LIMIT) {
        break;
      }

      if (seen.add(entry.binaryName())) {
        merged.add(entry);
      }
    }

    return merged;
  }

  // A qualified name as an import or a stack trace shows it (java.util.Map.Entry, or a tail of it
  // like util.Duration): the simple-name prefix search, narrowed to the types declared in that
  // package or type. Narrowed before the cap, so java.util.List is not starved by its siblings.
  private static List<TypeIndexEntry> qualifiedEntries(
      final String qualifier, final String simplePrefix, final WorkspaceTypeIndex typeIndex) {
    return typeIndex.searchSymbols(simplePrefix, Integer.MAX_VALUE).stream()
        .filter(entry -> declaredIn(containerName(entry), qualifier))
        .limit(SEARCH_LIMIT)
        .toList();
  }

  private static boolean declaredIn(final String container, final String qualifier) {
    return container.equals(qualifier) || container.endsWith(".%s".formatted(qualifier));
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
    info.setContainerName(containerName(entry));
    return info;
  }

  // For a nested type, the enclosing type's qualified name (java.util.Map for Map$Entry) so the
  // client shows where it is declared; for a top-level type, its package.
  private static String containerName(final TypeIndexEntry entry) {
    final String binaryName = entry.binaryName();
    final int lastNesting = binaryName.lastIndexOf('$');
    if (lastNesting < 0) {
      return entry.packageName();
    }

    return binaryName.substring(0, lastNesting).replace('$', '.');
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
