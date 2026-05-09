package io.github.aglibs.lathe.server;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

final class DefinitionLocator {

  private static final Logger LOG = Logger.getLogger(DefinitionLocator.class.getName());

  private DefinitionLocator() {}

  static Optional<Location> locate(
      final Element element,
      final Trees trees,
      final List<Path> sourceRoots,
      final String sourceUri) {
    if (element == null) {
      return Optional.empty();
    }

    final var path = trees.getPath(element);
    if (path != null) {
      final var cu = path.getCompilationUnit();
      final long declStart = trees.getSourcePositions().getStartPosition(cu, path.getLeaf());
      if (declStart != Diagnostic.NOPOS) {
        try {
          final CharSequence content = cu.getSourceFile().getCharContent(false);
          final long nameOffset =
              nameOffset(content, declStart, element.getSimpleName().toString());
          final var lspPos = SourceLocator.offsetToPosition(cu, nameOffset);
          LOG.fine(
              () ->
                  "[definition] same-file %s %d:%d"
                      .formatted(sourceUri, lspPos.getLine(), lspPos.getCharacter()));
          return Optional.of(new Location(sourceUri, new Range(lspPos, lspPos)));
        } catch (final IOException e) {
          LOG.log(
              Level.WARNING,
              e,
              () -> "[definition] failed to read source for %s".formatted(sourceUri));
        }
      }
    }

    return findSourceFile(element, sourceRoots)
        .map(
            file -> {
              final var simpleName = element.getSimpleName().toString();
              final var lspPos = parsePosition(file, simpleName);
              LOG.fine(
                  () ->
                      "[definition] reactor %s %d:%d"
                          .formatted(file, lspPos.getLine(), lspPos.getCharacter()));
              return new Location(file.toUri().toString(), new Range(lspPos, lspPos));
            });
  }

  static Optional<Path> findSourceFile(final Element element, final List<Path> sourceRoots) {
    final var topLevel = topLevelClass(element);
    if (topLevel == null) {
      return Optional.empty();
    }
    final var fileName = topLevel.getSimpleName().toString() + ".java";
    for (final var sourceRoot : sourceRoots) {
      try (final var stream = Files.walk(sourceRoot)) {
        final var found =
            stream.filter(p -> p.getFileName().toString().equals(fileName)).findFirst();
        if (found.isPresent()) {
          return found;
        }
      } catch (final IOException e) {
        LOG.log(Level.WARNING, e, () -> "[definition] error scanning %s".formatted(sourceRoot));
      }
    }
    return Optional.empty();
  }

  static Position parsePosition(final Path sourceFile, final String simpleName) {
    return SourceParser.parse(sourceFile, (trees, cu) -> extractPosition(trees, cu, simpleName))
        .orElse(new Position(0, 0));
  }

  private static Position extractPosition(
      final Trees trees, final CompilationUnitTree cu, final String simpleName) {
    final SourcePositions positions = trees.getSourcePositions();
    try {
      final CharSequence content = cu.getSourceFile().getCharContent(false);
      for (final Tree decl : cu.getTypeDecls()) {
        if (decl instanceof final ClassTree ct && ct.getSimpleName().contentEquals(simpleName)) {
          final long declStart = positions.getStartPosition(cu, decl);
          if (declStart != Diagnostic.NOPOS) {
            return SourceLocator.offsetToPosition(cu, nameOffset(content, declStart, simpleName));
          }
        }
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return null;
  }

  private static long nameOffset(
      final CharSequence content, final long declStart, final String name) {
    final int idx = content.toString().indexOf(name, (int) declStart);
    return idx >= 0 ? idx : declStart;
  }

  private static TypeElement topLevelClass(final Element element) {
    var e = element;
    while (e != null) {
      if (e instanceof final TypeElement te
          && e.getEnclosingElement() != null
          && e.getEnclosingElement().getKind() == ElementKind.PACKAGE) {
        return te;
      }
      e = e.getEnclosingElement();
    }
    return null;
  }
}
