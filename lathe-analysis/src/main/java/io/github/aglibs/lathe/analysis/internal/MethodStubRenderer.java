package io.github.aglibs.lathe.analysis.internal;

import io.github.aglibs.lathe.analysis.TypeDisplayFormatter;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * Renders the header of a method as it would be declared in {@code classType} — return type, name,
 * parameter list ({@code <type> <name>}) and throws clause — with the class's type substitution
 * applied and types shown as simple names. Shared by the implement-missing-methods code action and
 * override completion so the two stay identical.
 */
public final class MethodStubRenderer {

  private MethodStubRenderer() {}

  public static String signature(
      final ExecutableElement method,
      final DeclaredType classType,
      final Types types,
      final TypeDisplayFormatter formatter) {
    final var executableType = (ExecutableType) types.asMemberOf(classType, method);
    final String returnType = formatter.format(executableType.getReturnType());
    final List<? extends TypeMirror> paramTypes = executableType.getParameterTypes();
    final List<? extends VariableElement> paramElements = method.getParameters();
    final var params = new StringBuilder();
    for (int i = 0; i < paramElements.size(); i++) {
      if (i > 0) {
        params.append(", ");
      }

      params.append(formatter.format(paramTypes.get(i)));
      params.append(' ');
      params.append(paramElements.get(i).getSimpleName());
    }

    final String header = "%s %s(%s)".formatted(returnType, method.getSimpleName(), params);
    final List<? extends TypeMirror> thrown = executableType.getThrownTypes();
    if (thrown.isEmpty()) {
      return header;
    }

    return "%s throws %s"
        .formatted(
            header, thrown.stream().map(formatter::format).collect(Collectors.joining(", ")));
  }

  // The types `signature` writes, so a stub imports exactly those.
  public static Stream<TypeMirror> signatureTypes(
      final ExecutableElement method, final DeclaredType classType, final Types types) {
    final var executableType = (ExecutableType) types.asMemberOf(classType, method);
    return Stream.of(
            Stream.of(executableType.getReturnType()),
            executableType.getParameterTypes().stream(),
            executableType.getThrownTypes().stream())
        .flatMap(Function.identity());
  }
}
