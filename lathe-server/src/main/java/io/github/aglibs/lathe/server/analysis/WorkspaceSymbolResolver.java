package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
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

    return typeIndex.search(query, 100).stream()
        .map(entry -> toSymbolInformation(entry, sourceDirs))
        .filter(Objects::nonNull)
        .toList();
  }

  private static SymbolInformation toSymbolInformation(
      final TypeIndexEntry entry, final List<Path> sourceDirs) {
    final Path file = resolveSourcePath(entry, sourceDirs);
    if (file == null) {
      return null;
    }

    final var location = new Location(file.toUri().toString(), FILE_START);
    final var info =
        new SymbolInformation(
            entry.simpleName(), SymbolKinds.fromTypeIndex(entry.kind()), location);
    info.setContainerName(entry.packageName());
    return info;
  }

  static Path resolveSourcePath(final TypeIndexEntry entry, final List<Path> sourceDirs) {
    return TypeSourceLocator.findSourceFile(entry, sourceDirs).orElse(null);
  }
}
