package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.Scope;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.Parameterizable;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Types;

// Why a rename would break what Lombok generates, or collide with a declaration of the new name: a
// member of the same kind the owner has or inherits, a sibling type, or a variable in scope. Not
// detected: the new name shadowing the symbol at a use (a local of that name where the renamed
// field is read), a subclass member the renamed one would override or hide, and a type name other
// files import.
public final class RenameConflicts {

  private static final Set<ElementKind> VARIABLES =
      Set.of(
          ElementKind.LOCAL_VARIABLE,
          ElementKind.PARAMETER,
          ElementKind.EXCEPTION_PARAMETER,
          ElementKind.RESOURCE_VARIABLE,
          ElementKind.BINDING_VARIABLE);

  private RenameConflicts() {}

  public static Optional<String> find(
      final Element element, final String newName, final AttributedFileAnalysis analysis) {
    final Optional<String> lombok = lombokRefusal(element);
    if (lombok.isPresent()) {
      return lombok;
    }

    final String renaming =
        "`%s` cannot be renamed to `%s`".formatted(element.getSimpleName(), newName);
    if (named(element, newName)) {
      return Optional.of("%s: it already has that name".formatted(renaming));
    }

    return clash(element, newName, analysis).map(reason -> "%s: %s".formatted(renaming, reason));
  }

  private static Optional<String> clash(
      final Element element, final String newName, final AttributedFileAnalysis analysis) {
    if (VARIABLES.contains(element.getKind())) {
      return variableInScope(element, newName, analysis);
    }

    final Element owner = element.getEnclosingElement();
    return switch (element.getKind()) {
      case FIELD, ENUM_CONSTANT, RECORD_COMPONENT ->
          member(owner, newName, analysis, found -> found.getKind().isField());
      case METHOD ->
          member(
              owner,
              newName,
              analysis,
              found ->
                  found instanceof final ExecutableElement method
                      && found.getKind() == ElementKind.METHOD
                      && sameErasedParameters(
                          method, (ExecutableElement) element, analysis.types()));
      case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE -> siblingType(owner, newName, analysis);
      case TYPE_PARAMETER ->
          ((Parameterizable) owner)
              .getTypeParameters().stream()
                  .filter(parameter -> named(parameter, newName))
                  .findFirst()
                  .map(
                      parameter ->
                          "a type parameter named `%s` is already declared".formatted(newName));
      default -> Optional.empty();
    };
  }

  private static Optional<String> member(
      final Element owner,
      final String newName,
      final AttributedFileAnalysis analysis,
      final Predicate<Element> sameKind) {
    if (!(owner instanceof final TypeElement type)) {
      return Optional.empty();
    }

    return analysis.elements().getAllMembers(type).stream()
        .filter(found -> named(found, newName))
        .filter(sameKind)
        .findFirst()
        .map(
            found ->
                "%s already declares %s `%s`"
                    .formatted(
                        found.getEnclosingElement().getSimpleName(), kindName(found), newName));
  }

  private static boolean sameErasedParameters(
      final ExecutableElement first, final ExecutableElement second, final Types types) {
    final List<? extends VariableElement> a = first.getParameters();
    final List<? extends VariableElement> b = second.getParameters();
    return a.size() == b.size()
        && IntStream.range(0, a.size())
            .allMatch(
                i ->
                    types.isSameType(
                        types.erasure(a.get(i).asType()), types.erasure(b.get(i).asType())));
  }

  private static Optional<String> siblingType(
      final Element owner, final String newName, final AttributedFileAnalysis analysis) {
    if (owner instanceof final PackageElement pkg) {
      final String qualified =
          pkg.isUnnamed() ? newName : "%s.%s".formatted(pkg.getQualifiedName(), newName);
      return Optional.ofNullable(analysis.elements().getTypeElement(qualified))
          .map(found -> "a type `%s` already exists".formatted(qualified));
    }

    return owner.getEnclosedElements().stream()
        .filter(found -> found.getKind().isClass() || found.getKind().isInterface())
        .filter(found -> named(found, newName))
        .findFirst()
        .map(
            found ->
                "%s already has a nested type named `%s`"
                    .formatted(owner.getSimpleName(), newName));
  }

  // Java forbids a variable redeclaring one of an enclosing scope, both ways: one visible where the
  // renamed variable is declared, or one declared within the renamed variable's own scope.
  private static Optional<String> variableInScope(
      final Element element, final String newName, final AttributedFileAnalysis analysis) {
    final TreePath declaration = analysis.trees().getPath(element);
    if (declaration == null) {
      return Optional.empty();
    }

    final boolean visible =
        Stream.iterate(
                analysis.trees().getScope(declaration), Objects::nonNull, Scope::getEnclosingScope)
            .flatMap(scope -> StreamSupport.stream(scope.getLocalElements().spliterator(), false))
            .anyMatch(found -> isVariableNamed(found, newName));
    return visible || declaredWithin(declaration.getParentPath(), newName, analysis)
        ? Optional.of("a variable named `%s` is already in scope".formatted(newName))
        : Optional.empty();
  }

  private static boolean declaredWithin(
      final TreePath scope, final String newName, final AttributedFileAnalysis analysis) {
    return Boolean.TRUE.equals(
        new TreePathScanner<Boolean, Void>() {
          @Override
          public Boolean visitVariable(final VariableTree node, final Void unused) {
            return isVariableNamed(analysis.trees().getElement(getCurrentPath()), newName)
                || Boolean.TRUE.equals(super.visitVariable(node, unused));
          }

          @Override
          public Boolean reduce(final Boolean first, final Boolean second) {
            return Boolean.TRUE.equals(first) || Boolean.TRUE.equals(second);
          }
        }.scan(scope, null));
  }

  private static boolean isVariableNamed(final Element found, final String newName) {
    return found != null && VARIABLES.contains(found.getKind()) && named(found, newName);
  }

  // What Lombok generates follows its field, so renaming either alone breaks them apart: a
  // generated
  // member's name comes back, and a field's renamed accessors leave their calls behind.
  private static Optional<String> lombokRefusal(final Element element) {
    if (SourceLocator.isGenerated(element)) {
      return Optional.of(
          "`%s` cannot be renamed: Lombok generates it from `%s`"
              .formatted(
                  element.getSimpleName(), SourceLocator.generatedSource(element).getSimpleName()));
    }

    final List<Element> accessors =
        element.getKind() == ElementKind.FIELD
            ? SourceLocator.generatedAccessors(element)
            : List.of();
    return accessors.isEmpty()
        ? Optional.empty()
        : Optional.of(
            "`%s` cannot be renamed: Lombok generates %s from it, and renaming it would leave their calls behind"
                .formatted(
                    element.getSimpleName(),
                    accessors.stream()
                        .map(accessor -> "%s()".formatted(accessor.getSimpleName()))
                        .collect(Collectors.joining(", "))));
  }

  private static boolean named(final Element element, final String name) {
    return element.getSimpleName().contentEquals(name);
  }

  private static String kindName(final Element found) {
    return found.getKind().name().toLowerCase(Locale.ROOT).replace('_', ' ');
  }
}
