package io.github.aglibs.lathe.server;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
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
      try {
        final Optional<Position> position =
            SourceLocator.declarationNamePosition(
                trees, cu, path, SourceLocator.declarationName(element).toString());
        if (position.isPresent()) {
          final var lspPos = position.get();
          LOG.fine(
              () ->
                  "[definition] same-file %s %d:%d"
                      .formatted(sourceUri, lspPos.getLine(), lspPos.getCharacter()));
          return Optional.of(new Location(sourceUri, new Range(lspPos, lspPos)));
        }
      } catch (final IOException e) {
        LOG.log(
            Level.WARNING,
            e,
            () -> "[definition] failed to read source for %s".formatted(sourceUri));
      }
    }

    return findSourceFile(element, sourceRoots)
        .map(
            file -> {
              final var lspPos = parsePosition(file, element);
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
    final var pkgElement = (PackageElement) topLevel.getEnclosingElement();
    final var pkg = pkgElement.getQualifiedName().toString();
    final var relPath =
        pkg.isEmpty()
            ? topLevel.getSimpleName() + ".java"
            : pkg.replace('.', '/') + "/" + topLevel.getSimpleName() + ".java";
    final var enclosingModule = pkgElement.getEnclosingElement();
    final String moduleName =
        enclosingModule instanceof final ModuleElement me && !me.isUnnamed()
            ? me.getQualifiedName().toString()
            : null;
    return sourceRoots.stream()
        .flatMap(
            root -> {
              final var direct = root.resolve(relPath);
              if (moduleName != null) {
                final var modPrefixed = root.resolve(moduleName).resolve(relPath);
                return Stream.of(modPrefixed, direct);
              }
              return Stream.of(direct);
            })
        .filter(Files::isRegularFile)
        .findFirst();
  }

  static Position parsePosition(final Path sourceFile, final Element element) {
    return SourceParser.parse(
            sourceFile, (trees, cu) -> parseDeclarationPosition(trees, cu, element))
        .orElse(new Position(0, 0));
  }

  private static Position parseDeclarationPosition(
      final Trees trees, final CompilationUnitTree cu, final Element element) {
    try {
      return SourceLocator.declarationNamePosition(
              trees,
              cu,
              SourceLocator.declarationPath(cu, element),
              SourceLocator.declarationName(element).toString())
          .orElse(null);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static TypeElement topLevelClass(final Element element) {
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
