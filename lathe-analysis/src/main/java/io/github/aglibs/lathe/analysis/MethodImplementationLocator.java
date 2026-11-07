package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;

public final class MethodImplementationLocator extends TreePathScanner<Void, Void> {

  private final AttributedFileAnalysis analysis;
  private final ReferenceTarget target;
  private final Set<String> candidateBinaryNames;
  // The method to find implementations of, or null for a type target, whose local and anonymous
  // subclasses are found instead.
  private final ExecutableElement targetMethod;
  // The target method's owner, or the target type.
  private final TypeElement targetType;
  // The single abstract method a lambda or method reference must implement to count, or null
  // when the target is not a functional interface or its method.
  private final ExecutableElement samMethod;
  private final String uri;
  private final List<Location> results = new ArrayList<>();

  private MethodImplementationLocator(
      final AttributedFileAnalysis analysis,
      final ReferenceTarget target,
      final Set<String> candidateBinaryNames,
      final ExecutableElement targetMethod,
      final TypeElement targetType,
      final ExecutableElement samMethod,
      final String uri) {
    this.analysis = analysis;
    this.target = target;
    this.candidateBinaryNames = candidateBinaryNames;
    this.targetMethod = targetMethod;
    this.targetType = targetType;
    this.samMethod = samMethod;
    this.uri = uri;
  }

  public static List<Location> locate(
      final AttributedFileAnalysis analysis,
      final ReferenceTarget target,
      final Set<String> candidateBinaryNames,
      final String uri) {
    final boolean typeTarget = target.isType();
    if (target.kind() != ElementKind.METHOD && !typeTarget) {
      return List.of();
    }

    final ExecutableElement targetMethod =
        typeTarget ? null : target.resolveMethodElement(analysis.elements(), analysis.types());
    final TypeElement targetType =
        typeTarget
            ? analysis.elements().getTypeElement(target.qualifiedName().replace('$', '.'))
            : targetMethod == null ? null : (TypeElement) targetMethod.getEnclosingElement();
    if (targetType == null) {
      return List.of();
    }

    final var locator =
        new MethodImplementationLocator(
            analysis,
            target,
            candidateBinaryNames,
            targetMethod,
            targetType,
            samMethod(targetType, targetMethod, analysis),
            uri);
    locator.scan(analysis.tree(), null);
    return List.copyOf(locator.results);
  }

  // Why a rename of the target method cannot follow a candidate class: the class implements it
  // through an inherited method outside the override family (a dependency's superclass, say), which
  // the rename leaves behind, so the class silently loses its implementation.
  public static List<String> inheritedOutsideFamily(
      final AttributedFileAnalysis analysis,
      final ReferenceTarget target,
      final Set<String> candidateBinaryNames) {
    final ExecutableElement targetMethod =
        target.resolveMethodElement(analysis.elements(), analysis.types());
    if (targetMethod == null) {
      return List.of();
    }

    return candidateBinaryNames.stream()
        .map(binaryName -> analysis.elements().getTypeElement(binaryName.replace('$', '.')))
        .filter(Objects::nonNull)
        .flatMap(
            type ->
                inheritedOutsideFamily(analysis.elements(), targetMethod, type).stream()
                    .map(
                        inherited ->
                            "`%s` cannot be renamed: %s implements it through %s.%s, which the rename would leave behind"
                                .formatted(
                                    target.simpleName(),
                                    type.getSimpleName(),
                                    inherited.getEnclosingElement().getSimpleName(),
                                    inherited.getSimpleName())))
        .toList();
  }

  private static Optional<ExecutableElement> inheritedOutsideFamily(
      final Elements elements, final ExecutableElement targetMethod, final TypeElement type) {
    return ElementFilter.methodsIn(elements.getAllMembers(type)).stream()
        .filter(method -> !method.equals(targetMethod))
        .filter(method -> method.getSimpleName().equals(targetMethod.getSimpleName()))
        .filter(method -> !method.getModifiers().contains(Modifier.ABSTRACT))
        .filter(method -> elements.overrides(method, targetMethod, type))
        .filter(
            method ->
                !elements.overrides(
                    method, targetMethod, (TypeElement) method.getEnclosingElement()))
        .findFirst();
  }

  // The target type's single abstract method, when the target is a functional interface or that
  // method; null otherwise.
  private static ExecutableElement samMethod(
      final TypeElement targetType,
      final ExecutableElement targetMethod,
      final AttributedFileAnalysis analysis) {
    if (targetType.getKind() != ElementKind.INTERFACE) {
      return null;
    }

    final var sam = FunctionalInterfaces.singleAbstractMethod(targetType, analysis.elements());
    return targetMethod == null || targetMethod.equals(sam) ? sam : null;
  }

  @Override
  public Void visitClass(final ClassTree classTree, final Void unused) {
    final var classPath = getCurrentPath();
    if (analysis.trees().getElement(classPath) instanceof final TypeElement candidateType) {
      addImplementation(candidateType, classTree, classPath);
    }

    return super.visitClass(classTree, unused);
  }

  // An anonymous or local class is recognised from the attributed tree, not by binary name: javac
  // numbers them, and the compiled classes' numbers need not match the edited source's.
  // An enum constant's body is an anonymous subclass of its enum.
  private void addImplementation(
      final TypeElement candidateType, final ClassTree classTree, final TreePath classPath) {
    final boolean unnamed = isLocalOrAnonymousSubtype(candidateType);
    if (targetMethod == null) {
      if (unnamed) {
        unnamedClassLocation(candidateType, classPath).ifPresent(results::add);
      }

      return;
    }

    if (!unnamed
        && !candidateBinaryNames.contains(
            analysis.elements().getBinaryName(candidateType).toString())) {
      return;
    }

    candidateType.getEnclosedElements().stream()
        .filter(ExecutableElement.class::isInstance)
        .map(ExecutableElement.class::cast)
        .filter(method -> method.getKind() == ElementKind.METHOD)
        .filter(method -> !method.getModifiers().contains(Modifier.ABSTRACT))
        .filter(method -> method.getSimpleName().contentEquals(target.simpleName()))
        .filter(method -> analysis.elements().overrides(method, targetMethod, candidateType))
        .flatMap(method -> location(method, classTree, classPath).stream())
        .forEach(results::add);
  }

  // A class the type index knows only by a binary name like Outer$1 or Outer$1Local.
  private boolean isLocalOrAnonymousSubtype(final TypeElement candidateType) {
    final NestingKind nesting = candidateType.getNestingKind();
    return (nesting == NestingKind.ANONYMOUS || nesting == NestingKind.LOCAL)
        && isSubtypeOfTarget(candidateType);
  }

  private boolean isSubtypeOfTarget(final TypeElement type) {
    final var types = analysis.types();
    return types.isSubtype(types.erasure(type.asType()), types.erasure(targetType.asType()));
  }

  // A local class at its name; an anonymous one where it is created, its `new Handler() {` or an
  // enum constant with a body.
  private Optional<Location> unnamedClassLocation(
      final TypeElement candidateType, final TreePath classPath) {
    if (candidateType.getNestingKind() == NestingKind.LOCAL) {
      return locationAtPath(classPath, analysis.tree(), candidateType.getSimpleName().toString());
    }

    if (!(classPath.getParentPath().getLeaf() instanceof final NewClassTree creation)) {
      return Optional.empty();
    }

    final var start = SourceLocator.range(analysis.trees(), analysis.tree(), creation).getStart();
    return Optional.of(new Location(uri, new Range(start, start)));
  }

  @Override
  public Void visitLambdaExpression(final LambdaExpressionTree node, final Void unused) {
    addFunctionalImplementation(node);
    return super.visitLambdaExpression(node, unused);
  }

  @Override
  public Void visitMemberReference(final MemberReferenceTree node, final Void unused) {
    addFunctionalImplementation(node);
    return super.visitMemberReference(node, unused);
  }

  private void addFunctionalImplementation(final ExpressionTree node) {
    if (samMethod == null) {
      return;
    }

    final var converted = analysis.trees().getTypeMirror(getCurrentPath());
    if (!(analysis.types().asElement(converted) instanceof final TypeElement convertedType)) {
      return;
    }

    // Match when the converted type's single abstract method is (or overrides) the target — so a
    // lambda typed as the interface itself or as a functional sub-interface that inherits/overrides
    // the SAM is found, while a sub-interface whose SAM comes from a different parent is not.
    // On a type, the lambda must also be one of the type: a lambda of its parent or of a sibling
    // sub-interface implements the same method without being the type.
    final var sam = FunctionalInterfaces.singleAbstractMethod(convertedType, analysis.elements());
    if (sam == null
        || !implementsTarget(sam, convertedType)
        || targetMethod == null && !isSubtypeOfTarget(convertedType)) {
      return;
    }

    final var start = SourceLocator.range(analysis.trees(), analysis.tree(), node).getStart();
    results.add(new Location(uri, new Range(start, start)));
  }

  private boolean implementsTarget(final ExecutableElement sam, final TypeElement convertedType) {
    return sam.equals(samMethod) || analysis.elements().overrides(sam, samMethod, convertedType);
  }

  private Optional<Location> location(
      final ExecutableElement method, final ClassTree classTree, final TreePath classPath) {
    final var path = analysis.trees().getPath(method);
    final CompilationUnitTree tree = analysis.tree();
    final String name = method.getSimpleName().toString();
    if (path != null) {
      return locationAtPath(path, tree, name);
    }

    if (classTree.getKind() != Tree.Kind.RECORD || !method.getParameters().isEmpty()) {
      return Optional.empty();
    }

    for (final Tree member : classTree.getMembers()) {
      if (member instanceof final VariableTree variable && variable.getName().contentEquals(name)) {
        return locationAtPath(new TreePath(classPath, variable), tree, name);
      }
    }

    return Optional.empty();
  }

  private Optional<Location> locationAtPath(
      final TreePath path, final CompilationUnitTree tree, final String name) {
    try {
      return SourceLocator.declarationNamePosition(analysis.trees(), tree, path, name)
          .map(start -> new Location(uri, SourceLocator.nameRange(start, name)));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
