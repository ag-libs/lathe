package io.github.aglibs.lathe.server.analysis;

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
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public final class DefinitionLocator {

  private static final Logger LOG = Logger.getLogger(DefinitionLocator.class.getName());

  private final SourceParser parser;

  public DefinitionLocator(final SourceParser parser) {
    this.parser = parser;
  }

  public Optional<Location> locate(
      final Element element,
      final Trees trees,
      final List<Path> sourceRoots,
      final String sourceUri) {
    if (element == null) {
      return Optional.empty();
    }

    final var direct = locateElement(element, trees, sourceRoots, sourceUri);
    if (direct.isPresent()) {
      return direct;
    }

    final var component = recordComponentField(element);
    return component == null ? direct : locateElement(component, trees, sourceRoots, sourceUri);
  }

  private Optional<Location> locateElement(
      final Element element,
      final Trees trees,
      final List<Path> sourceRoots,
      final String sourceUri) {
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
        .flatMap(
            file ->
                parsePosition(file, element)
                    .map(
                        lspPos -> {
                          LOG.fine(
                              () ->
                                  "[definition] reactor %s %d:%d"
                                      .formatted(file, lspPos.getLine(), lspPos.getCharacter()));
                          return new Location(file.toUri().toString(), new Range(lspPos, lspPos));
                        }));
  }

  /**
   * A synthetic record accessor has no declaration site, so it resolves to nothing; redirect it to
   * the component's backing field, whose declaration is the component name in the record header
   * (EG-047). Returns null unless {@code element} is a record's canonical accessor — an explicit
   * accessor with a body resolves directly and never reaches this fallback.
   */
  private static Element recordComponentField(final Element element) {
    if (!(element instanceof final ExecutableElement accessor)
        || accessor.getKind() != ElementKind.METHOD
        || !accessor.getParameters().isEmpty()) {
      return null;
    }

    if (!(accessor.getEnclosingElement() instanceof final TypeElement record)
        || record.getKind() != ElementKind.RECORD) {
      return null;
    }

    final boolean isAccessor =
        record.getRecordComponents().stream()
            .anyMatch(component -> component.getAccessor().equals(accessor));
    if (!isAccessor) {
      return null;
    }

    return record.getEnclosedElements().stream()
        .filter(member -> member.getKind() == ElementKind.FIELD)
        .filter(member -> member.getSimpleName().contentEquals(accessor.getSimpleName()))
        .findFirst()
        .orElse(null);
  }

  public static Optional<Path> findSourceFile(final Element element, final List<Path> sourceRoots) {
    final var topLevel = topLevelClass(element);
    if (topLevel == null) {
      return Optional.empty();
    }
    final var pkgElement = (PackageElement) topLevel.getEnclosingElement();
    final var pkg = pkgElement.getQualifiedName().toString();
    final var relPath =
        pkg.isEmpty()
            ? topLevel.getSimpleName() + ".java"
            : "%s/%s.java".formatted(pkg.replace('.', '/'), topLevel.getSimpleName());
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

  /**
   * The declaration-name position of {@code element} in {@code sourceFile}, or empty when it cannot
   * be located (e.g. a synthetic member with no declaration site). Definition callers propagate the
   * empty as "no result" rather than jumping to the file top; call-hierarchy callers default it to
   * {@code 0,0}.
   */
  public Optional<Position> parsePosition(final Path sourceFile, final Element element) {
    return parser.parseFile(
        sourceFile, (trees, cu) -> parseDeclarationPosition(trees, cu, element));
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

  public static TypeElement topLevelClass(final Element element) {
    Element e = element;
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
