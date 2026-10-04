package io.github.aglibs.lathe.server.analysis;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
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
      final int last = params.size() - 1;
      final boolean varargs = exe.isVarArgs();
      final String paramStr =
          IntStream.range(0, params.size())
              .mapToObj(
                  i -> formatParam(params.get(i), fmt, sourceParamNames, i, varargs && i == last))
              .collect(Collectors.joining(", "));
      final String typeParams = formatTypeParameters(exe.getTypeParameters());
      if (exe.getKind() == ElementKind.CONSTRUCTOR) {
        // A constructor's element name is <init> and its return type is void; render it as the
        // enclosing type's name with no return type, matching how the source declares it.
        sig = "%s%s(%s)".formatted(typeParams, exe.getEnclosingElement().getSimpleName(), paramStr);
      } else {
        final String returnType =
            fmt != null ? fmt.format(exe.getReturnType()) : exe.getReturnType().toString();
        sig = "%s%s %s(%s)".formatted(typeParams, returnType, exe.getSimpleName(), paramStr);
      }
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
      final int index,
      final boolean vararg) {
    final String rawType = fmt != null ? fmt.format(param.asType()) : param.asType().toString();
    // A varargs parameter's declared type is an array; render it as T... rather than T[].
    final String typeName =
        vararg && rawType.endsWith("[]")
            ? "%s...".formatted(rawType.substring(0, rawType.length() - 2))
            : rawType;
    final String name =
        (sourceNames != null && index < sourceNames.size())
            ? sourceNames.get(index)
            : param.getSimpleName().toString();
    return SourceParser.isSyntheticName(name) ? typeName : "%s %s".formatted(typeName, name);
  }

  // A method's own type-parameter declaration, e.g. "<T> " or "<K, V> ", or "" when it declares
  // none. Names only (bounds omitted) to keep the hover line compact; the trailing space slots it
  // before the return type.
  private static String formatTypeParameters(
      final List<? extends TypeParameterElement> typeParams) {
    return typeParams.isEmpty()
        ? ""
        : typeParams.stream()
            .map(tp -> tp.getSimpleName().toString())
            .collect(Collectors.joining(", ", "<", "> "));
  }
}
