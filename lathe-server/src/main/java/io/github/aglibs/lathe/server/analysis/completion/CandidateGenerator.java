package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.Scope;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

final class CandidateGenerator {

  private static final Logger LOG = Logger.getLogger(CandidateGenerator.class.getName());

  private static final List<String> OBJECT_METHOD_NAMES =
      Arrays.stream(Object.class.getDeclaredMethods()).map(Method::getName).distinct().toList();

  private static final Set<String> SUPPRESSED_SYNC_METHODS = Set.of("wait", "notify", "notifyAll");

  private final AttributedFileAnalysis snapshot;
  private final Types types;
  private final CandidateFactory itemFactory;

  CandidateGenerator(final AttributedFileAnalysis snapshot) {
    this.snapshot = snapshot;
    this.types = snapshot.types();
    this.itemFactory = new CandidateFactory(types);
  }

  List<CompletionCandidate> proposeMemberAccessCandidates(
      final TypeMirror receiverType,
      final String prefix,
      final boolean isStaticAccess,
      final Scope scope) {
    if (!(receiverType instanceof final DeclaredType declaredType)) {
      return List.of();
    }

    final var element = types.asElement(declaredType);
    if (!(element instanceof final TypeElement typeEl)) {
      return List.of();
    }

    return snapshot.elements().getAllMembers(typeEl).stream()
        .filter(
            el ->
                el.getKind() == ElementKind.METHOD
                    || el.getKind() == ElementKind.FIELD
                    || el.getKind() == ElementKind.ENUM_CONSTANT)
        .filter(el -> el.getModifiers().contains(Modifier.STATIC) == isStaticAccess)
        .filter(el -> !isObjectSyncMethod(el))
        .filter(el -> isAccessible(el, declaredType, scope))
        .filter(el -> el.getSimpleName().toString().startsWith(prefix))
        .map(
            el -> {
              final var candidate = itemFactory.memberCandidate(el, declaredType);
              return candidate.withSortText(sortKey(el));
            })
        .toList();
  }

  List<CompletionCandidate> proposeNestedTypes(final TypeElement outer, final String prefix) {
    return outer.getEnclosedElements().stream()
        .filter(
            el ->
                el.getKind() == ElementKind.CLASS
                    || el.getKind() == ElementKind.INTERFACE
                    || el.getKind() == ElementKind.ENUM
                    || el.getKind() == ElementKind.RECORD)
        .filter(el -> el.getSimpleName().toString().startsWith(prefix))
        .map(
            el -> CandidateFactory.typeElementCandidate((TypeElement) el).withSortText(sortKey(el)))
        .toList();
  }

  List<CompletionCandidate> proposeEnumConstantCandidates(
      final TypeElement enumType, final String prefix, final int cursorOffset) {
    return proposeEnumConstantCandidates(
        enumType, prefix, classQualifiedName(enumType, cursorOffset));
  }

  List<CompletionCandidate> proposeUnqualifiedEnumConstantCandidates(
      final TypeElement enumType, final String prefix) {
    return proposeEnumConstantCandidates(enumType, prefix, "");
  }

  private List<CompletionCandidate> proposeEnumConstantCandidates(
      final TypeElement enumType, final String prefix, final String qualifier) {
    final String typeName = qualifier.isEmpty() ? "" : qualifier + ".";
    return enumType.getEnclosedElements().stream()
        .filter(el -> el.getKind() == ElementKind.ENUM_CONSTANT)
        .filter(el -> (typeName + el.getSimpleName()).startsWith(prefix))
        .map(el -> enumConstantCandidate(enumType, typeName + el.getSimpleName()))
        .toList();
  }

  private String classQualifiedName(final TypeElement typeEl, final int cursorOffset) {
    final List<TypeElement> classes =
        Stream.iterate(typeEl, Objects::nonNull, Element::getEnclosingElement)
            .takeWhile(TypeElement.class::isInstance)
            .map(TypeElement.class::cast)
            // Enclosing classes have shorter FQN lengths than their enclosed classes,
            // so sorting by length yields outermost-to-innermost ordering.
            .sorted(Comparator.comparingInt(te -> te.getQualifiedName().toString().length()))
            .toList();

    final var cursorPath = SourceLocator.pathAt(snapshot.trees(), snapshot.tree(), cursorOffset);
    final int startIndex =
        IntStream.range(0, classes.size())
            .filter(i -> isInScope(classes.get(i), cursorPath))
            .reduce((first, second) -> second)
            .orElse(0);

    return classes.subList(startIndex, classes.size()).stream()
        .map(te -> te.getSimpleName().toString())
        .collect(Collectors.joining("."));
  }

  private boolean isInScope(final TypeElement typeEl, final TreePath cursorPath) {
    if (cursorPath == null) {
      return false;
    }

    if (isEnclosingClass(typeEl, cursorPath)) {
      return true;
    }

    if (typeEl.getEnclosingElement().getKind() == ElementKind.PACKAGE) {
      return isImportedOrInSamePackage(typeEl);
    }

    if (isExplicitlyImported(typeEl)) {
      return true;
    }

    if (typeEl.getEnclosingElement() instanceof final TypeElement enclosingClass) {
      return isEnclosingClass(enclosingClass, cursorPath);
    }

    return false;
  }

  private boolean isEnclosingClass(final TypeElement typeEl, final TreePath cursorPath) {
    final var targetFqn = typeEl.getQualifiedName().toString();
    return Stream.iterate(cursorPath, Objects::nonNull, TreePath::getParentPath)
        .filter(path -> path.getLeaf() instanceof ClassTree)
        .map(path -> snapshot.trees().getElement(path))
        .filter(TypeElement.class::isInstance)
        .map(TypeElement.class::cast)
        .anyMatch(te -> te.getQualifiedName().toString().equals(targetFqn));
  }

  private boolean isImportedOrInSamePackage(final TypeElement typeEl) {
    final var targetPkg = snapshot.elements().getPackageOf(typeEl).getQualifiedName().toString();
    final var currentPkg =
        snapshot.tree().getPackageName() != null ? snapshot.tree().getPackageName().toString() : "";
    if (targetPkg.equals(currentPkg)) {
      return true;
    }

    if ("java.lang".equals(targetPkg)) {
      return true;
    }

    if (isExplicitlyImported(typeEl)) {
      return true;
    }

    return snapshot.tree().getImports().stream()
        .filter(imp -> !imp.isStatic())
        .map(imp -> imp.getQualifiedIdentifier().toString())
        .filter(impStr -> impStr.endsWith(".*"))
        .map(impStr -> impStr.substring(0, impStr.length() - 2))
        .anyMatch(targetPkg::equals);
  }

  private boolean isExplicitlyImported(final TypeElement typeEl) {
    final String fqn = typeEl.getQualifiedName().toString();
    return snapshot.tree().getImports().stream().anyMatch(imp -> matchesImport(imp, fqn, typeEl));
  }

  private boolean matchesImport(final ImportTree imp, final String fqn, final TypeElement typeEl) {
    final var impStr = imp.getQualifiedIdentifier().toString();
    if (impStr.equals(fqn)) {
      return true;
    }

    if (imp.isStatic() && impStr.endsWith(".*")) {
      final var parentFqn = impStr.substring(0, impStr.length() - 2);
      final var enclosing = typeEl.getEnclosingElement();
      return enclosing instanceof final TypeElement te
          && te.getQualifiedName().toString().equals(parentFqn);
    }

    return false;
  }

  private static CompletionCandidate enumConstantCandidate(
      final TypeElement enumType, final String label) {
    return new CompletionCandidate(
        label,
        label,
        CandidateKind.FIELD,
        enumType.getSimpleName().toString(),
        label,
        false,
        null,
        enumType.asType(),
        enumType.getQualifiedName().toString(),
        null);
  }

  List<CompletionCandidate> proposeSimpleNameCandidates(
      final String enclosingClass,
      final String enclosingMethod,
      final String prefix,
      final int cursorOffset,
      final SemanticCompletionContext semanticContext) {
    final var context =
        new SimpleNameContext(
            enclosingClass, enclosingMethod, prefix, cursorOffset, semanticContext);
    return new SimpleNameProvider(snapshot, itemFactory, context).collect();
  }

  private static boolean isObjectSyncMethod(final Element el) {
    if (!SUPPRESSED_SYNC_METHODS.contains(el.getSimpleName().toString())) {
      return false;
    }

    return el.getEnclosingElement() instanceof final TypeElement enclosing
        && "java.lang.Object".equals(enclosing.getQualifiedName().toString());
  }

  private static String sortKey(final Element el) {
    if (el.getKind() == ElementKind.METHOD
        && OBJECT_METHOD_NAMES.contains(el.getSimpleName().toString())) {
      return "9_" + el.getSimpleName();
    }

    return switch (el.getKind()) {
      case CLASS, INTERFACE, ENUM, RECORD -> "3_" + el.getSimpleName();
      default -> "0_" + el.getSimpleName();
    };
  }

  private boolean isAccessible(
      final Element el, final DeclaredType receiverType, final Scope scope) {
    if (scope == null) {
      return true;
    }

    try {
      return snapshot.trees().isAccessible(scope, el, receiverType);
    } catch (final IllegalArgumentException e) {
      LOG.log(
          Level.FINE,
          e,
          () ->
              "[proposal] isAccessible failed for %s on %s"
                  .formatted(el.getSimpleName(), receiverType));
      return true;
    }
  }
}
