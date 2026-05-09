package io.github.aglibs.lathe.server;

import java.util.Optional;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

final class HoverFormatter {

  private HoverFormatter() {}

  static Optional<String> format(final Element element, final TypeMirror type) {
    if (element == null && type == null) {
      return Optional.empty();
    }

    final String sig;
    if (element instanceof final ExecutableElement exe) {
      final var params =
          exe.getParameters().stream()
              .map(p -> p.asType() + " " + p.getSimpleName())
              .collect(Collectors.joining(", "));
      sig = exe.getReturnType() + " " + exe.getSimpleName() + "(" + params + ")";
    } else if (element instanceof final TypeElement te) {
      final var kind =
          switch (te.getKind()) {
            case ANNOTATION_TYPE -> "@interface";
            case RECORD -> "record";
            case ENUM -> "enum";
            case INTERFACE -> "interface";
            default -> "class";
          };
      sig = kind + " " + te.getSimpleName();
    } else if (element != null && type != null) {
      sig = type + " " + element.getSimpleName();
    } else if (type != null) {
      sig = type.toString();
    } else {
      sig = element.toString();
    }
    return Optional.of("```java\n" + sig + "\n```");
  }

  static String formatParameter(final VariableElement param) {
    return "```java\n" + param.asType() + " " + param.getSimpleName() + "\n```";
  }
}
