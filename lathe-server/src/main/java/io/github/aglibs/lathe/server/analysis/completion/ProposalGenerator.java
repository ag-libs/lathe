package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

final class ProposalGenerator {

  private ProposalGenerator() {}

  static List<CompletionItem> proposeMemberAccess(
      final TypeMirror receiverType,
      final String prefix,
      final boolean isStaticAccess,
      final FileAnalysis snapshot) {
    if (!(receiverType instanceof final DeclaredType declaredType)) {
      return List.of();
    }

    final var element = snapshot.types().asElement(declaredType);
    if (!(element instanceof final TypeElement typeEl)) {
      return List.of();
    }

    return snapshot.elements().getAllMembers(typeEl).stream()
        .filter(el -> el.getKind() == ElementKind.METHOD || el.getKind() == ElementKind.FIELD)
        .filter(el -> !isStaticAccess || el.getModifiers().contains(Modifier.STATIC))
        .filter(el -> el.getSimpleName().toString().startsWith(prefix))
        .map(el -> toCompletionItem(el, declaredType, snapshot.types()))
        .collect(Collectors.toList());
  }

  static List<CompletionItem> proposeNestedTypes(final TypeElement outer, final String prefix) {
    return outer.getEnclosedElements().stream()
        .filter(
            el ->
                el.getKind() == ElementKind.CLASS
                    || el.getKind() == ElementKind.INTERFACE
                    || el.getKind() == ElementKind.ENUM
                    || el.getKind() == ElementKind.RECORD)
        .filter(el -> el.getSimpleName().toString().startsWith(prefix))
        .map(
            el -> {
              final var item = new CompletionItem();
              item.setLabel(el.getSimpleName().toString());
              item.setKind(
                  el.getKind() == ElementKind.INTERFACE
                      ? CompletionItemKind.Interface
                      : CompletionItemKind.Class);
              return item;
            })
        .collect(Collectors.toList());
  }

  private static CompletionItem toCompletionItem(
      final Element el, final DeclaredType receiverType, final Types types) {
    final var item = new CompletionItem();
    if (el.getKind() == ElementKind.METHOD) {
      final var method = (ExecutableElement) el;
      final List<? extends TypeMirror> paramTypes = resolveParamTypes(method, receiverType, types);
      final var params =
          paramTypes.stream().map(t -> simpleTypeName(t, types)).collect(Collectors.joining(", "));
      item.setLabel(el.getSimpleName() + "(" + params + ")");
      item.setKind(CompletionItemKind.Method);
    } else {
      item.setLabel(el.getSimpleName().toString());
      item.setKind(CompletionItemKind.Field);
    }
    return item;
  }

  private static List<? extends TypeMirror> resolveParamTypes(
      final ExecutableElement method, final DeclaredType receiverType, final Types types) {
    try {
      return ((ExecutableType) types.asMemberOf(receiverType, method)).getParameterTypes();
    } catch (final IllegalArgumentException ignored) {
      return method.getParameters().stream()
          .map(VariableElement::asType)
          .collect(Collectors.toList());
    }
  }

  private static String simpleTypeName(final TypeMirror type, final Types types) {
    final var el = types.asElement(type);
    return el != null ? el.getSimpleName().toString() : type.toString();
  }
}
