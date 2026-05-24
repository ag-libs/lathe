package io.github.aglibs.lathe.server.analysis.completion;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

final class CompletionItemFactory {

  private static final Logger LOG = Logger.getLogger(CompletionItemFactory.class.getName());

  private final Types types;

  CompletionItemFactory(final Types types) {
    this.types = types;
  }

  CompletionItem variable(final String name) {
    final var item = new CompletionItem();
    item.setLabel(name);
    item.setKind(CompletionItemKind.Variable);
    return item;
  }

  CompletionItem member(final Element el, final DeclaredType receiverType) {
    final var item = new CompletionItem();
    switch (el.getKind()) {
      case METHOD -> {
        final var method = (ExecutableElement) el;
        final List<? extends TypeMirror> paramTypes = resolveParamTypes(method, receiverType);
        final var params =
            paramTypes.stream().map(this::simpleTypeName).collect(Collectors.joining(", "));
        item.setLabel(el.getSimpleName() + "(" + params + ")");
        item.setKind(CompletionItemKind.Method);
      }
      case FIELD, ENUM_CONSTANT -> {
        item.setLabel(el.getSimpleName().toString());
        item.setKind(CompletionItemKind.Field);
      }
      default -> throw new IllegalArgumentException("Unsupported completion element: " + el);
    }

    return item;
  }

  private List<? extends TypeMirror> resolveParamTypes(
      final ExecutableElement method, final DeclaredType receiverType) {
    try {
      return ((ExecutableType) types.asMemberOf(receiverType, method)).getParameterTypes();
    } catch (final IllegalArgumentException e) {
      LOG.log(
          Level.FINE,
          e,
          () ->
              "[completion-item] asMemberOf failed for %s on %s"
                  .formatted(method.getSimpleName(), receiverType));
      return method.getParameters().stream().map(VariableElement::asType).toList();
    }
  }

  private String simpleTypeName(final TypeMirror type) {
    final var el = types.asElement(type);
    return el != null ? el.getSimpleName().toString() : type.toString();
  }
}
