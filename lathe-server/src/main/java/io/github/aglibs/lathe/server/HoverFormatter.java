package io.github.aglibs.lathe.server;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

public final class HoverFormatter {

  private HoverFormatter() {}

  public static Optional<String> format(
      final Element element, final TypeMirror type, final String javadoc, final String origin) {
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

    final var sb = new StringBuilder("```java\n").append(sig).append("\n```");
    if (javadoc != null && !javadoc.isBlank()) {
      sb.append("\n\n").append(cleanDoc(javadoc));
    }
    if (origin != null) {
      sb.append("\n\n*source: ").append(origin).append("*");
    }
    return Optional.of(sb.toString());
  }

  public static String formatParameter(final VariableElement param) {
    return "```java\n" + param.asType() + " " + param.getSimpleName() + "\n```";
  }

  private static String cleanDoc(final String raw) {
    final int openLen = 3; // "/**"
    final int closeLen = 2; // "*/"
    final var withoutOpen = raw.startsWith("/**") ? raw.substring(openLen) : raw;
    final var withoutClose =
        withoutOpen.endsWith("*/")
            ? withoutOpen.substring(0, withoutOpen.length() - closeLen)
            : withoutOpen;
    return Arrays.stream(withoutClose.split("\n"))
        .map(line -> line.stripLeading().replaceFirst("^\\*\\s?", "").stripTrailing())
        .filter(line -> !line.isBlank())
        .collect(Collectors.joining("\n"))
        .strip();
  }
}
