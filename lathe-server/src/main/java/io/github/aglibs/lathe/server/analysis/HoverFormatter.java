package io.github.aglibs.lathe.server.analysis;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

public final class HoverFormatter {

  private HoverFormatter() {}

  public static Optional<String> format(
      final Element element,
      final TypeMirror type,
      final String javadoc,
      final String origin,
      final TypeDisplayFormatter fmt,
      final List<String> sourceParamNames) {
    if (element == null && type == null) {
      return Optional.empty();
    }

    final String sig;
    if (element instanceof final ExecutableElement exe) {
      final var params = exe.getParameters();
      final String paramStr =
          IntStream.range(0, params.size())
              .mapToObj(i -> formatParam(params.get(i), fmt, sourceParamNames, i))
              .collect(Collectors.joining(", "));
      final String returnType =
          fmt != null ? fmt.format(exe.getReturnType()) : exe.getReturnType().toString();
      sig = "%s %s(%s)".formatted(returnType, exe.getSimpleName(), paramStr);
    } else if (element instanceof final TypeElement te) {
      final var kind =
          switch (te.getKind()) {
            case ANNOTATION_TYPE -> "@interface";
            case RECORD -> "record";
            case ENUM -> "enum";
            case INTERFACE -> "interface";
            default -> "class";
          };
      sig = "%s %s".formatted(kind, te.getSimpleName());
    } else if (element != null && type != null) {
      sig = "%s %s".formatted(type, element.getSimpleName());
    } else if (type != null) {
      sig = type.toString();
    } else {
      sig = element.toString();
    }

    var result = "```java\n%s\n```".formatted(sig);
    if (javadoc != null && !javadoc.isBlank()) {
      result = "%s\n\n%s".formatted(result, javadoc);
    }
    if (origin != null) {
      result = "%s\n\n*source: %s*".formatted(result, origin);
    }
    return Optional.of(result);
  }

  static String formatParam(
      final VariableElement param,
      final TypeDisplayFormatter fmt,
      final List<String> sourceNames,
      final int index) {
    final String typeName = fmt != null ? fmt.format(param.asType()) : param.asType().toString();
    final String name =
        (sourceNames != null && index < sourceNames.size())
            ? sourceNames.get(index)
            : param.getSimpleName().toString();
    return SourceParser.isSyntheticName(name) ? typeName : "%s %s".formatted(typeName, name);
  }

  public static String formatParameter(final VariableElement param) {
    return "```java\n%s %s\n```".formatted(param.asType(), param.getSimpleName());
  }
}
