package io.github.aglibs.lathe.server.analysis.completion;

import com.sun.source.tree.Scope;
import io.github.aglibs.lathe.server.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.server.analysis.MethodStubRenderer;
import io.github.aglibs.lathe.server.analysis.TypeDisplayFormatter;
import java.util.List;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Types;

/**
 * Override/implement completion: at a class-body member-declaration slot, offers the inherited,
 * overridable methods of the enclosing type (supertypes plus {@code Object}) as {@code @Override}
 * stubs. Types render as simple names; automatic import of stub types is not yet done (EG-015 v1).
 */
final class OverrideCompletionProvider {

  private OverrideCompletionProvider() {}

  static List<CompletionCandidate> propose(
      final AttributedFileAnalysis analysis, final int cursorOffset, final String prefix) {
    final Scope scope = TypeResolver.resolveScope(analysis, cursorOffset);
    if (scope == null) {
      return List.of();
    }

    final TypeElement enclosingType = scope.getEnclosingClass();
    if (enclosingType == null) {
      return List.of();
    }

    final var types = analysis.types();
    final var formatter = new TypeDisplayFormatter(types);
    final var classType = (DeclaredType) enclosingType.asType();

    return analysis.elements().getAllMembers(enclosingType).stream()
        .filter(el -> el.getKind() == ElementKind.METHOD)
        .map(ExecutableElement.class::cast)
        .filter(method -> isOverridable(method, enclosingType))
        .filter(method -> method.getSimpleName().toString().startsWith(prefix))
        .map(method -> overrideCandidate(method, classType, types, formatter))
        .toList();
  }

  private static boolean isOverridable(
      final ExecutableElement method, final TypeElement enclosingType) {
    if (method.getEnclosingElement().equals(enclosingType)) {
      return false;
    }

    final var modifiers = method.getModifiers();
    return !modifiers.contains(Modifier.FINAL)
        && !modifiers.contains(Modifier.STATIC)
        && !modifiers.contains(Modifier.PRIVATE);
  }

  private static CompletionCandidate overrideCandidate(
      final ExecutableElement method,
      final DeclaredType classType,
      final Types types,
      final TypeDisplayFormatter formatter) {
    final var name = method.getSimpleName().toString();
    final var declaringType = (TypeElement) method.getEnclosingElement();
    final String insertText =
        """
        @Override
        public %s {
          throw new UnsupportedOperationException();
        }"""
            .formatted(MethodStubRenderer.signature(method, classType, types, formatter));
    return new CompletionCandidate(
        name,
        name,
        CandidateKind.METHOD,
        "override %s".formatted(declaringType.getSimpleName()),
        insertText,
        false,
        "0_override_%s".formatted(name),
        null,
        "override %s".formatted(declaringType.getSimpleName()),
        null,
        declaringType.getQualifiedName().toString(),
        null);
  }
}
