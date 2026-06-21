package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public final class TypeSourceLocator {

  private TypeSourceLocator() {}

  public static Optional<Location> locate(
      final TypeIndexEntry entry, final List<Path> sourceRoots, final SourceParser parser) {
    if (!isNamedDeclaration(entry)) {
      return Optional.empty();
    }

    return findSourceFile(entry, sourceRoots)
        .flatMap(
            sourceFile ->
                parser.parseFile(
                    sourceFile,
                    (trees, tree) -> declarationLocation(entry, sourceFile, trees, tree)));
  }

  public static Optional<Path> findSourceFile(
      final TypeIndexEntry entry, final List<Path> sourceRoots) {
    final String className = entry.className();
    final int nestedType = className.indexOf('$');
    final String topLevelName = nestedType >= 0 ? className.substring(0, nestedType) : className;
    final String relativePath =
        entry.packageName().isEmpty()
            ? topLevelName + ".java"
            : "%s/%s.java".formatted(entry.packageName().replace('.', '/'), topLevelName);
    return sourceRoots.stream()
        .map(root -> root.resolve(relativePath))
        .filter(Files::isRegularFile)
        .findFirst();
  }

  public static boolean isNamedDeclaration(final TypeIndexEntry entry) {
    return Arrays.stream(entry.className().split("\\$", -1))
        .allMatch(name -> !name.isEmpty() && Character.isJavaIdentifierStart(name.codePointAt(0)));
  }

  private static Location declarationLocation(
      final TypeIndexEntry entry,
      final Path sourceFile,
      final Trees trees,
      final CompilationUnitTree tree) {
    return new TypeDeclarationScanner(entry.className(), sourceFile, trees, tree).locate();
  }

  private static Location location(
      final Path sourceFile,
      final Trees trees,
      final CompilationUnitTree tree,
      final TreePath path,
      final String name) {
    try {
      final Position start =
          SourceLocator.declarationNamePosition(trees, tree, path, name).orElse(null);
      if (start == null) {
        return null;
      }

      final var end = new Position(start.getLine(), start.getCharacter() + name.length());
      return new Location(sourceFile.toUri().toString(), new Range(start, end));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static final class TypeDeclarationScanner extends TreePathScanner<Void, Void> {

    private final String targetName;
    private final Path sourceFile;
    private final Trees trees;
    private final CompilationUnitTree tree;
    private final ArrayDeque<String> names = new ArrayDeque<>();
    private Location result;

    private TypeDeclarationScanner(
        final String targetName,
        final Path sourceFile,
        final Trees trees,
        final CompilationUnitTree tree) {
      this.targetName = targetName;
      this.sourceFile = sourceFile;
      this.trees = trees;
      this.tree = tree;
    }

    private Location locate() {
      scan(tree, null);
      return result;
    }

    @Override
    public Void visitClass(final ClassTree classTree, final Void unused) {
      final String name = classTree.getSimpleName().toString();
      names.addLast(name);
      if (result == null && String.join("$", names).equals(targetName)) {
        result = location(sourceFile, trees, tree, getCurrentPath(), name);
      }
      super.visitClass(classTree, unused);
      names.removeLast();
      return null;
    }
  }
}
