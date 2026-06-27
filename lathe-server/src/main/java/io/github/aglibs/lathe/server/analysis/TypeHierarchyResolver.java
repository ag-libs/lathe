package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.TypeHierarchyItem;

final class TypeHierarchyResolver {

  private final SourceParser parser;

  TypeHierarchyResolver(final SourceParser parser) {
    this.parser = parser;
  }

  List<TypeHierarchyItem> prepare(
      final String binaryName,
      final WorkspaceTypeIndex typeIndex,
      final List<java.nio.file.Path> sourceRoots) {
    return typeIndex
        .findType(binaryName)
        .flatMap(entry -> typeHierarchyItem(entry, sourceRoots))
        .map(List::of)
        .orElseGet(List::of);
  }

  List<TypeHierarchyItem> supertypes(
      final TypeHierarchyItem item,
      final WorkspaceTypeIndex typeIndex,
      final List<java.nio.file.Path> sourceRoots) {
    final TypeHierarchyItemData data = TypeHierarchyItemDataCodec.decode(item.getData());
    return data != null
        ? typeHierarchyItems(typeIndex.directSupertypes(data.binaryName()), sourceRoots)
        : List.of();
  }

  List<TypeHierarchyItem> subtypes(
      final TypeHierarchyItem item,
      final WorkspaceTypeIndex typeIndex,
      final List<java.nio.file.Path> sourceRoots) {
    final TypeHierarchyItemData data = TypeHierarchyItemDataCodec.decode(item.getData());
    if (data == null) {
      return List.of();
    }

    return typeHierarchyItems(typeIndex.directSubtypes(data.binaryName()), sourceRoots);
  }

  Optional<Location> locateSource(
      final TypeIndexEntry entry, final List<java.nio.file.Path> sourceRoots) {
    return TypeSourceLocator.locate(entry, sourceRoots, parser);
  }

  private List<TypeHierarchyItem> typeHierarchyItems(
      final List<TypeIndexEntry> entries, final List<java.nio.file.Path> sourceRoots) {
    return entries.stream()
        .flatMap(entry -> typeHierarchyItem(entry, sourceRoots).stream())
        .toList();
  }

  private Optional<TypeHierarchyItem> typeHierarchyItem(
      final TypeIndexEntry entry, final List<java.nio.file.Path> sourceRoots) {
    return TypeSourceLocator.locate(entry, sourceRoots, parser)
        .map(
            location -> {
              final var hierarchyItem =
                  new TypeHierarchyItem(
                      entry.simpleName(),
                      SymbolKinds.fromTypeIndex(entry.kind()),
                      location.getUri(),
                      location.getRange(),
                      location.getRange());
              hierarchyItem.setDetail(entry.packageName());
              hierarchyItem.setData(
                  TypeHierarchyItemDataCodec.encode(
                      new TypeHierarchyItemData(entry.binaryName(), location.getUri())));
              return hierarchyItem;
            });
  }
}
