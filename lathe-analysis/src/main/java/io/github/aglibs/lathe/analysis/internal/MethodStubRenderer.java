package io.github.aglibs.lathe.analysis.internal;

import io.github.aglibs.lathe.analysis.TypeDisplayFormatter;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/**
 * Renders the header of a method as it would be declared in {@code classType} — type parameters,
 * return type, name, parameter list ({@code <type> <name>}) and throws clause — with the class's
 * type substitution applied and types shown as simple names. Shared by the
 * implement-missing-methods code action and override completion so the two stay identical. A stub
 * implements every method in {@code implemented}: inherited methods with one signature (Closeable's
 * and another interface's {@code close()}) take one stub, rendered from the first.
 */
public final class MethodStubRenderer {

  private static final String OBJECT = "java.lang.Object";

  private MethodStubRenderer() {}

  public static String signature(
      final List<ExecutableElement> implemented,
      final DeclaredType classType,
      final Types types,
      final TypeDisplayFormatter formatter) {
    final ExecutableElement method = implemented.get(0);
    final ExecutableType executableType = memberType(method, classType, types);
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

    final String header =
        "%s%s %s(%s)"
            .formatted(
                typeParameters(method, formatter), returnType, method.getSimpleName(), params);
    final List<TypeMirror> thrown = thrown(implemented, classType, types);
    if (thrown.isEmpty()) {
      return header;
    }

    return "%s throws %s"
        .formatted(
            header, thrown.stream().map(formatter::format).collect(Collectors.joining(", ")));
  }

  // The types `signature` writes, so a stub imports exactly those.
  public static Stream<TypeMirror> signatureTypes(
      final List<ExecutableElement> implemented, final DeclaredType classType, final Types types) {
    final ExecutableElement method = implemented.get(0);
    final ExecutableType executableType = memberType(method, classType, types);
    return Stream.of(
            Stream.of(executableType.getReturnType()),
            executableType.getParameterTypes().stream(),
            thrown(implemented, classType, types).stream(),
            method.getTypeParameters().stream().flatMap(MethodStubRenderer::bounds))
        .flatMap(Function.identity());
  }

  private static ExecutableType memberType(
      final ExecutableElement method, final DeclaredType classType, final Types types) {
    return (ExecutableType) types.asMemberOf(classType, method);
  }

  // A generic method declares its type variables, or its return, parameters or throws clause
  // (`<X extends Exception> void run() throws X`) name types the stub never declares.
  private static String typeParameters(
      final ExecutableElement method, final TypeDisplayFormatter formatter) {
    if (method.getTypeParameters().isEmpty()) {
      return "";
    }

    return method.getTypeParameters().stream()
        .map(parameter -> declaration(parameter, formatter))
        .collect(Collectors.joining(", ", "<", "> "));
  }

  private static String declaration(
      final TypeParameterElement parameter, final TypeDisplayFormatter formatter) {
    final List<String> bounds = bounds(parameter).map(formatter::format).toList();
    return bounds.isEmpty()
        ? parameter.getSimpleName().toString()
        : "%s extends %s".formatted(parameter.getSimpleName(), String.join(" & ", bounds));
  }

  private static Stream<? extends TypeMirror> bounds(final TypeParameterElement parameter) {
    return parameter.getBounds().stream().filter(bound -> !isObject(bound));
  }

  private static boolean isObject(final TypeMirror type) {
    return type instanceof final DeclaredType declared
        && declared.asElement() instanceof final TypeElement element
        && element.getQualifiedName().contentEquals(OBJECT);
  }

  // The first method's thrown types that every other one allows too: an implementation may throw
  // only what each method it implements declares, so Closeable's close() and a close() that throws
  // nothing leave none.
  private static List<TypeMirror> thrown(
      final List<ExecutableElement> implemented, final DeclaredType classType, final Types types) {
    final List<ExecutableType> memberTypes =
        implemented.stream().map(method -> memberType(method, classType, types)).toList();
    return memberTypes.get(0).getThrownTypes().stream()
        .filter(
            thrown ->
                memberTypes.stream()
                    .allMatch(
                        other ->
                            other.getThrownTypes().stream()
                                .anyMatch(allowed -> types.isSubtype(thrown, allowed))))
        .map(TypeMirror.class::cast)
        .toList();
  }
}
