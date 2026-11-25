package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.core.LatheUri;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;
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

  TypeHierarchyExplorerResult explore(
      final TypeHierarchyItem self,
      final WorkspaceTypeIndex typeIndex,
      final List<java.nio.file.Path> sourceRoots,
      final int nodeCap) {
    final TypeHierarchyItemData data = TypeHierarchyItemDataCodec.decode(self.getData());
    if (data == null) {
      return new TypeHierarchyExplorerResult(List.of(), self, List.of(), false);
    }

    final List<TypeHierarchyItem> supertypes =
        typeHierarchyItems(typeIndex.transitiveSupertypes(data.binaryName()), sourceRoots);
    final List<TypeIndexEntry> allSubtypes = typeIndex.transitiveSubtypes(data.binaryName());
    final boolean truncated = allSubtypes.size() > nodeCap;
    final List<TypeIndexEntry> capped = truncated ? allSubtypes.subList(0, nodeCap) : allSubtypes;
    return new TypeHierarchyExplorerResult(
        supertypes, self, typeHierarchyItems(capped, sourceRoots), truncated);
  }

  Optional<Location> locateSource(
      final TypeIndexEntry entry, final List<java.nio.file.Path> sourceRoots) {
    return TypeSourceLocator.locate(entry, sourceRoots, parser);
  }

  // A named type at its declaration; an anonymous or local class, which the index knows only as
  // Outer$1 or Outer$1Local and TypeSourceLocator cannot place, from its file's parse.
  private List<TypeHierarchyItem> typeHierarchyItems(
      final List<TypeIndexEntry> entries, final List<java.nio.file.Path> sourceRoots) {
    final Stream<TypeHierarchyItem> named =
        entries.stream()
            .filter(TypeSourceLocator::isNamedDeclaration)
            .flatMap(entry -> typeHierarchyItem(entry, sourceRoots).stream());
    final List<TypeIndexEntry> unnamed =
        entries.stream().filter(entry -> !TypeSourceLocator.isNamedDeclaration(entry)).toList();
    return Stream.concat(named, unnamedItems(unnamed, sourceRoots).stream()).toList();
  }

  // javac numbers anonymous and local classes, and a compiled class's number need not match the
  // edited source's, so each is found by what its file says, one parse per file.
  private List<TypeHierarchyItem> unnamedItems(
      final List<TypeIndexEntry> unnamed, final List<Path> sourceRoots) {
    final Map<Path, List<TypeIndexEntry>> byFile = new LinkedHashMap<>();
    for (final TypeIndexEntry entry : unnamed) {
      TypeSourceLocator.findSourceFile(entry, sourceRoots)
          .ifPresent(file -> byFile.computeIfAbsent(file, ignored -> new ArrayList<>()).add(entry));
    }

    final var items = new ArrayList<TypeHierarchyItem>();
    for (final Map.Entry<Path, List<TypeIndexEntry>> file : byFile.entrySet()) {
      items.addAll(
          parser
              .parseFile(
                  file.getKey(),
                  (trees, tree) ->
                      new UnnamedClassScanner(file.getKey(), file.getValue(), trees, tree).scan())
              .orElse(List.of()));
    }

    return List.copyOf(items);
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

  // Each anonymous or local class the file creates or declares, matched to the index entry it
  // stands
  // for by what the parse tells: the named class enclosing it (the entry's binary name before its
  // number), and the type its `new` or its extends/implements names (one of the entry's
  // supertypes). Unattributed, a same-named type from another package would match too.
  private static final class UnnamedClassScanner extends TreePathScanner<Void, Void> {

    private final Path file;
    private final List<TypeIndexEntry> entries;
    private final Trees trees;
    private final CompilationUnitTree tree;
    // The binary name of each named class the scan is in; empty inside an anonymous or local one,
    // whose own number the parse cannot tell.
    private final ArrayDeque<String> enclosing = new ArrayDeque<>();
    private final Set<TypeIndexEntry> used = new HashSet<>();
    private final List<TypeHierarchyItem> items = new ArrayList<>();

    private UnnamedClassScanner(
        final Path file,
        final List<TypeIndexEntry> entries,
        final Trees trees,
        final CompilationUnitTree tree) {
      this.file = file;
      this.entries = entries;
      this.trees = trees;
      this.tree = tree;
    }

    private List<TypeHierarchyItem> scan() {
      scan(tree, null);
      return List.copyOf(items);
    }

    @Override
    public Void visitNewClass(final NewClassTree node, final Void unused) {
      if (node.getClassBody() != null) {
        match("", Set.of(simpleTypeName(node.getIdentifier())))
            .ifPresent(
                entry -> {
                  final Range whole = Positions.range(trees, tree, node);
                  final Range at = new Range(whole.getStart(), whole.getStart());
                  items.add(item(entry, anonymousName(node), whole, at));
                });
      }

      return super.visitNewClass(node, unused);
    }

    @Override
    public Void visitClass(final ClassTree node, final Void unused) {
      final String name = node.getSimpleName().toString();
      final Tree parent = getCurrentPath().getParentPath().getLeaf();
      // A local class is declared among statements: a block's, or an old-style case group's.
      final boolean local = parent instanceof BlockTree || parent instanceof CaseTree;
      if (local) {
        match(name, declaredSupertypes(node))
            .ifPresent(
                entry ->
                    localNameRange(name)
                        .ifPresent(
                            range ->
                                items.add(
                                    item(entry, name, Positions.range(trees, tree, node), range))));
      }

      enclosing.push(classBinaryName(node, parent, local));
      try {
        return super.visitClass(node, unused);
      } finally {
        enclosing.pop();
      }
    }

    // The entry an unnamed class stands for, each used once while another is left.
    private Optional<TypeIndexEntry> match(final String localName, final Set<String> supertypes) {
      final String outer = enclosing.isEmpty() ? "" : enclosing.peek();
      if (outer.isEmpty()) {
        return Optional.empty();
      }

      final List<TypeIndexEntry> candidates =
          entries.stream()
              .filter(entry -> outer.equals(outerBinaryName(entry)))
              .filter(entry -> localName.equals(localName(entry)))
              .filter(entry -> namesASupertype(entry, supertypes))
              .toList();
      final Optional<TypeIndexEntry> fresh =
          candidates.stream().filter(entry -> !used.contains(entry)).findFirst();
      final Optional<TypeIndexEntry> chosen =
          fresh.isPresent() ? fresh : candidates.stream().findFirst();
      chosen.ifPresent(used::add);
      return chosen;
    }

    private String classBinaryName(final ClassTree node, final Tree parent, final boolean local) {
      final String name = node.getSimpleName().toString();
      if (local || name.isEmpty()) {
        return "";
      }

      if (parent instanceof CompilationUnitTree) {
        final String pkg =
            tree.getPackageName() == null ? "" : "%s.".formatted(tree.getPackageName());
        return pkg + name;
      }

      final String outer = enclosing.isEmpty() ? "" : enclosing.peek();
      return outer.isEmpty() ? "" : "%s$%s".formatted(outer, name);
    }

    private Optional<Range> localNameRange(final String name) {
      try {
        return DeclarationNames.declarationNamePosition(trees, tree, getCurrentPath(), name)
            .map(start -> Positions.nameRange(start, name));
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    // An enum constant's body is named after its constant, as the editor shows the constant.
    private String anonymousName(final NewClassTree node) {
      final Tree parent = getCurrentPath().getParentPath().getLeaf();
      if (parent instanceof final VariableTree constant && constant.getInitializer() == node) {
        final Tree owner = getCurrentPath().getParentPath().getParentPath().getLeaf();
        if (owner instanceof final ClassTree enumTree && enumTree.getKind() == Tree.Kind.ENUM) {
          return constant.getName().toString();
        }
      }

      return "new %s() {...}".formatted(simpleTypeName(node.getIdentifier()));
    }

    private TypeHierarchyItem item(
        final TypeIndexEntry entry, final String name, final Range range, final Range selection) {
      final String uri = LatheUri.fromPath(file);
      final var item =
          new TypeHierarchyItem(
              name, SymbolKinds.fromTypeIndex(entry.kind()), uri, range, selection);
      item.setDetail(entry.packageName());
      item.setData(
          TypeHierarchyItemDataCodec.encode(new TypeHierarchyItemData(entry.binaryName(), uri)));
      return item;
    }

    private static Set<String> declaredSupertypes(final ClassTree node) {
      return Stream.concat(
              Stream.ofNullable(node.getExtendsClause()), node.getImplementsClause().stream())
          .map(UnnamedClassScanner::simpleTypeName)
          .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean namesASupertype(
        final TypeIndexEntry entry, final Set<String> supertypes) {
      return entry.directSupertypes().stream()
          .map(UnnamedClassScanner::simpleName)
          .anyMatch(supertypes::contains);
    }

    // Outer$1 and Outer$1Local are both Outer's; the number is the compile's, not the source's.
    private static String outerBinaryName(final TypeIndexEntry entry) {
      return entry.binaryName().substring(0, entry.binaryName().lastIndexOf('$'));
    }

    // A local class's index name carries its own after the compile's number; an anonymous one's
    // is the number alone.
    private static String localName(final TypeIndexEntry entry) {
      final String last = entry.className().substring(entry.className().lastIndexOf('$') + 1);
      int digits = 0;
      while (digits < last.length() && Character.isDigit(last.charAt(digits))) {
        digits++;
      }

      return last.substring(digits);
    }

    private static String simpleName(final String binaryName) {
      return binaryName.substring(
          Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$')) + 1);
    }

    private static String simpleTypeName(final Tree type) {
      if (type instanceof final ParameterizedTypeTree parameterized) {
        return simpleTypeName(parameterized.getType());
      }

      if (type instanceof final MemberSelectTree select) {
        return select.getIdentifier().toString();
      }

      return type instanceof final IdentifierTree name ? name.getName().toString() : "";
    }
  }
}
