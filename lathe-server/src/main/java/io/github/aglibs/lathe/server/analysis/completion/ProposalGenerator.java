package io.github.aglibs.lathe.server.analysis.completion;

import io.github.aglibs.lathe.server.analysis.FileAnalysis;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
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
    final var element = snapshot.types().asElement(receiverType);
    if (!(element instanceof final TypeElement typeEl)) {
      return List.of();
    }

    return snapshot.elements().getAllMembers(typeEl).stream()
        .filter(el -> el.getKind() == ElementKind.METHOD || el.getKind() == ElementKind.FIELD)
        .filter(el -> !isStaticAccess || el.getModifiers().contains(Modifier.STATIC))
        .filter(el -> el.getSimpleName().toString().startsWith(prefix))
        .map(el -> toCompletionItem(el, snapshot.types()))
        .collect(Collectors.toList());
  }

  private static CompletionItem toCompletionItem(final Element el, final Types types) {
    final var item = new CompletionItem();
    if (el.getKind() == ElementKind.METHOD) {
      final var method = (ExecutableElement) el;
      final var params =
          method.getParameters().stream()
              .map(p -> simpleTypeName(p.asType(), types))
              .collect(Collectors.joining(", "));
      item.setLabel(el.getSimpleName() + "(" + params + ")");
      item.setKind(CompletionItemKind.Method);
    } else {
      item.setLabel(el.getSimpleName().toString());
      item.setKind(CompletionItemKind.Field);
    }

    return item;
  }

  private static String simpleTypeName(final TypeMirror type, final Types types) {
    final var el = types.asElement(type);
    return el != null ? el.getSimpleName().toString() : type.toString();
  }
}
