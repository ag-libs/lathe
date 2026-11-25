package io.github.aglibs.lathe.analysis;

import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;

public final class WorkspaceSymbolResolver {

  private static final Range FILE_START = new Range(new Position(0, 0), new Position(0, 0));
  private static final int SEARCH_LIMIT = 100;

  private WorkspaceSymbolResolver() {}

  // keep narrows the types before the cap, so a narrowed search is not starved by the rest.
  public static List<SymbolInformation> resolve(
      final String query,
      final WorkspaceTypeIndex typeIndex,
      final List<Path> sourceDirs,
      final Predicate<TypeIndexEntry> keep) {
    if (query.isBlank()) {
      return List.of();
    }

    final List<TypeIndexEntry> entries = mergedEntries(query, typeIndex, keep);
    try (var parser = new SourceParser()) {
      return entries.stream()
          .flatMap(entry -> toSymbolInformation(entry, sourceDirs, parser).stream())
          .toList();
    }
  }

  // The types the same search finds in dependencies without sources, which resolve leaves out: an
  // LSP symbol needs a file to open, and these have only the dependency that declares them.
  // Narrowed to them before the cap, so the types with sources that match first do not starve them.
  public static List<SourcelessSymbol> sourceless(
      final String query,
      final WorkspaceTypeIndex typeIndex,
      final Predicate<TypeIndexEntry> keep) {
    if (query.isBlank()) {
      return List.of();
    }

    final Predicate<TypeIndexEntry> sourceless =
        entry -> typeIndex.sourcelessDependency(entry.binaryName()).isPresent();
    return mergedEntries(query, typeIndex, keep.and(sourceless)).stream()
        .flatMap(
            entry ->
                typeIndex
                    .sourcelessDependency(entry.binaryName())
                    .map(
                        dependency ->
                            new SourcelessSymbol(
                                entry.simpleName(),
                                SymbolKinds.fromTypeIndex(entry.kind()),
                                containerName(entry),
                                dependency))
                    .stream())
        .toList();
  }

  // Exact prefix search over the symbol index covers everything (JDK, dependencies, reactor),
  // including package-private top-level types so a stack frame's internal impl class resolves.
  // CamelCase-hump matching is a supplementary, reactor-only layer on top, so a type like
  // AbstractServerFactory is still reachable via "ServerFactory" or "ASF" even though it doesn't
  // start with either. Both are ordered together before the list is capped at SEARCH_LIMIT: two
  // letters prefix-match a hundred dependency types, which filled the list ahead of the project
  // type its initials name. The order: a type named exactly so, case and all, whatever its origin
  // (the hump
  // matcher is loose); a top-level type before a nested one (a project's many @Nested test classes
  // must not crowd it out); then the index's ranking, so a type the project uses comes first. The
  // top of the union comes from the top of each list, so each is cut to the cap first.
  private static List<TypeIndexEntry> mergedEntries(
      final String query,
      final WorkspaceTypeIndex typeIndex,
      final Predicate<TypeIndexEntry> keep) {
    final int lastDot = query.lastIndexOf('.');
    if (lastDot >= 0) {
      return qualifiedEntries(
          query.substring(0, lastDot), query.substring(lastDot + 1), typeIndex, keep);
    }

    final Comparator<TypeIndexEntry> order =
        Comparator.comparing((TypeIndexEntry entry) -> !entry.simpleName().equals(query))
            .thenComparing(entry -> !entry.isTopLevel())
            .thenComparing(typeIndex.ranking());
    final var seen = new LinkedHashSet<String>();
    final var merged = new ArrayList<TypeIndexEntry>();
    for (final TypeIndexEntry entry :
        Stream.concat(
                topOf(typeIndex.searchSymbols(query, Integer.MAX_VALUE), keep, order),
                topOf(typeIndex.searchCamelCase(query, Integer.MAX_VALUE), keep, order))
            .toList()) {
      if (seen.add(entry.binaryName())) {
        merged.add(entry);
      }
    }

    merged.sort(order);
    return List.copyOf(merged.subList(0, Math.min(merged.size(), SEARCH_LIMIT)));
  }

  // A qualified name as an import or a stack trace shows it (java.util.Map.Entry, or a tail of it
  // like util.Duration): the simple-name prefix search, narrowed to the types declared in that
  // package or type. Narrowed before the cap, so java.util.List is not starved by its siblings.
  private static List<TypeIndexEntry> qualifiedEntries(
      final String qualifier,
      final String simplePrefix,
      final WorkspaceTypeIndex typeIndex,
      final Predicate<TypeIndexEntry> keep) {
    return typeIndex.searchSymbols(simplePrefix, Integer.MAX_VALUE).stream()
        .filter(entry -> declaredIn(containerName(entry), qualifier))
        .filter(keep)
        .limit(SEARCH_LIMIT)
        .toList();
  }

  private static Stream<TypeIndexEntry> topOf(
      final List<TypeIndexEntry> entries,
      final Predicate<TypeIndexEntry> keep,
      final Comparator<TypeIndexEntry> order) {
    return entries.stream().filter(keep).sorted(order).limit(SEARCH_LIMIT);
  }

  private static boolean declaredIn(final String container, final String qualifier) {
    return container.equals(qualifier) || container.endsWith(".%s".formatted(qualifier));
  }

  // The declaration's name, matched by its nested name (Api$Response$Builder, not the file's first
  // Builder), else the file's start: the file is found but declares no such type by name.
  private static Optional<SymbolInformation> toSymbolInformation(
      final TypeIndexEntry entry, final List<Path> sourceDirs, final SourceParser parser) {
    return TypeSourceLocator.locate(entry, sourceDirs, parser)
        .or(
            () ->
                TypeSourceLocator.findSourceFile(entry, sourceDirs)
                    .map(file -> new Location(LatheUri.fromPath(file), FILE_START)))
        .map(location -> symbol(entry, location));
  }

  private static SymbolInformation symbol(final TypeIndexEntry entry, final Location location) {
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
}
