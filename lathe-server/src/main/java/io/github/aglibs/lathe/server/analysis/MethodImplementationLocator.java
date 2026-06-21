package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePathScanner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

final class MethodImplementationLocator extends TreePathScanner<Void, Void> {

  private final AttributedFileAnalysis analysis;
  private final ReferenceTarget target;
  private final Set<String> candidateBinaryNames;
  private final ExecutableElement targetMethod;
  private final String uri;
  private final List<Location> results = new ArrayList<>();

  private MethodImplementationLocator(
      final AttributedFileAnalysis analysis,
      final ReferenceTarget target,
      final Set<String> candidateBinaryNames,
      final ExecutableElement targetMethod,
      final String uri) {
    this.analysis = analysis;
    this.target = target;
    this.candidateBinaryNames = candidateBinaryNames;
    this.targetMethod = targetMethod;
    this.uri = uri;
  }

  static List<Location> locate(
      final AttributedFileAnalysis analysis,
      final ReferenceTarget target,
      final Set<String> candidateBinaryNames,
      final String uri) {
    if (target.kind() != ElementKind.METHOD) {
      return List.of();
    }

    final var owner = analysis.elements().getTypeElement(target.qualifiedName().replace('$', '.'));
    if (owner == null) {
      return List.of();
    }

    final ExecutableElement targetMethod =
        owner.getEnclosedElements().stream()
            .filter(ExecutableElement.class::isInstance)
            .map(ExecutableElement.class::cast)
            .filter(method -> target.matches(method, analysis.types(), analysis.elements()))
            .findFirst()
            .orElse(null);
    if (targetMethod == null) {
      return List.of();
    }

    final var locator =
        new MethodImplementationLocator(analysis, target, candidateBinaryNames, targetMethod, uri);
    locator.scan(analysis.tree(), null);
    return List.copyOf(locator.results);
  }

  @Override
  public Void visitClass(final ClassTree classTree, final Void unused) {
    final var element = analysis.trees().getElement(getCurrentPath());
    if (element instanceof final TypeElement candidateType
        && candidateBinaryNames.contains(
            analysis.elements().getBinaryName(candidateType).toString())) {
      candidateType.getEnclosedElements().stream()
          .filter(ExecutableElement.class::isInstance)
          .map(ExecutableElement.class::cast)
          .filter(method -> method.getKind() == ElementKind.METHOD)
          .filter(method -> !method.getModifiers().contains(Modifier.ABSTRACT))
          .filter(method -> method.getSimpleName().contentEquals(target.simpleName()))
          .filter(method -> analysis.elements().overrides(method, targetMethod, candidateType))
          .map(this::location)
          .forEach(results::add);
    }

    return super.visitClass(classTree, unused);
  }

  private Location location(final ExecutableElement method) {
    final var path = analysis.trees().getPath(method);
    final CompilationUnitTree tree = analysis.tree();
    try {
      final Position start =
          SourceLocator.declarationNamePosition(
                  analysis.trees(), tree, path, method.getSimpleName().toString())
              .orElseThrow();
      final var end =
          new Position(start.getLine(), start.getCharacter() + method.getSimpleName().length());
      return new Location(uri, new Range(start, end));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
