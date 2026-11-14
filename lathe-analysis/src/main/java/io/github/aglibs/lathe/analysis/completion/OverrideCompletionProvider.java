package io.github.aglibs.lathe.analysis.completion;

import com.sun.source.tree.Scope;
import io.github.aglibs.lathe.analysis.AttributedFileAnalysis;
import io.github.aglibs.lathe.analysis.internal.MethodStubRenderer;
import io.github.aglibs.lathe.analysis.internal.SourceTypeNames;
import java.util.List;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Types;
import org.eclipse.lsp4j.CompletionItem;

/**
 * Override/implement completion: at a class-body member-declaration slot, offers the inherited,
 * overridable methods of the enclosing type (supertypes plus {@code Object}) as {@code @Override}
 * stubs. Types are written as the file names them, and each item imports the ones its stub needs,
 * as the implement-abstract-methods quick fix does.
 */
final class OverrideCompletionProvider {

  private OverrideCompletionProvider() {}

  // Finished items, not candidates: a stub may need several imports, and a candidate carries one.
  static List<CompletionItem> propose(
      final AttributedFileAnalysis analysis, final int cursorOffset, final String prefix) {
    final Scope scope = ReceiverResolver.resolveScope(analysis, cursorOffset);
    if (scope == null) {
      return List.of();
    }

    final TypeElement enclosingType = scope.getEnclosingClass();
    if (enclosingType == null) {
      return List.of();
    }

    final var types = analysis.types();
    final var names = new SourceTypeNames(analysis, enclosingType);

    return analysis.elements().getAllMembers(enclosingType).stream()
        .filter(el -> el.getKind() == ElementKind.METHOD)
        .map(ExecutableElement.class::cast)
        .filter(method -> isOverridable(method, enclosingType))
        .filter(method -> method.getSimpleName().toString().startsWith(prefix))
        .map(method -> overrideItem(method, enclosingType, types, names))
        .toList();
  }

  private static boolean isOverridable(
      final ExecutableElement method, final TypeElement enclosingType) {
    final var declaringType = (TypeElement) method.getEnclosingElement();
    if (declaringType.equals(enclosingType)) {
      return false;
    }

    // A default method may not override one of Object's.
    if (enclosingType.getKind().isInterface()
        && declaringType.getQualifiedName().contentEquals(Object.class.getName())) {
      return false;
    }

    final var modifiers = method.getModifiers();
    return !modifiers.contains(Modifier.FINAL)
        && !modifiers.contains(Modifier.STATIC)
        && !modifiers.contains(Modifier.PRIVATE);
  }

  private static CompletionItem overrideItem(
      final ExecutableElement method,
      final TypeElement enclosingType,
      final Types types,
      final SourceTypeNames names) {
    final var classType = (DeclaredType) enclosingType.asType();
    final String modifier = enclosingType.getKind().isInterface() ? "default" : "public";
    final CompletionItem item =
        CompletionItemPresenter.present(
            overrideCandidate(method, classType, modifier, types, names));
    names
        .importEditsFor(MethodStubRenderer.signatureTypes(List.of(method), classType, types))
        .forEach(edit -> CompletionItemPresenter.addAdditionalTextEdit(item, edit));
    return item;
  }

  private static CompletionCandidate overrideCandidate(
      final ExecutableElement method,
      final DeclaredType classType,
      final String modifier,
      final Types types,
      final SourceTypeNames names) {
    final var name = method.getSimpleName().toString();
    final var declaringType = (TypeElement) method.getEnclosingElement();
    final String insertText =
        """
        @Override
        %s %s {
          throw new UnsupportedOperationException();
        }"""
            .formatted(
                modifier,
                MethodStubRenderer.signature(List.of(method), classType, types, names.formatter()));
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
