package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Reconstructs a method's RunTarget position id from a JUnit {@code MethodSource}'s identity,
 * matching {@code RunnableScanner.methodTarget}'s format exactly: {@code
 * <binaryClassName>#<methodName>(<erasedParams>)}. JUnit joins parameter types with {@code ", "}
 * and names each the way {@code Class.getName} does ({@code [I}, {@code pkg.Outer$Inner}), whereas
 * javac's erasure joins with {@code ","} and prints source form ({@code int[]}, {@code
 * pkg.Outer.Inner}), so each parameter type is turned into source form. A result whose id still
 * matches no discovered position falls through to its class.
 */
public final class TestId {

  private TestId() {}

  public static String positionId(
      final String className, final String methodName, final String methodParameterTypes) {
    ValidCheck.check()
        .notBlank(className, "className")
        .notBlank(methodName, "methodName")
        .notNull(methodParameterTypes, "methodParameterTypes")
        .validate();
    final String params =
        Arrays.stream(methodParameterTypes.replaceAll("\\s", "").split(","))
            .filter(param -> !param.isEmpty())
            .map(TestId::sourceForm)
            .collect(Collectors.joining(","));
    return "%s#%s(%s)".formatted(className, methodName, params);
  }

  // A JVM type name read as a type descriptor's fixed grammar, not as Java source: leading '['s
  // count the array depth, then a primitive code or L<binary name>;. A '$' in a binary name is a
  // nesting step, which source form writes as '.'.
  private static String sourceForm(final String jvmName) {
    int depth = 0;
    while (jvmName.charAt(depth) == '[') {
      depth++;
    }

    if (depth == 0) {
      return jvmName.replace('$', '.');
    }

    final String element = jvmName.substring(depth);
    final String type =
        element.startsWith("L") && element.endsWith(";")
            ? element.substring(1, element.length() - 1).replace('$', '.')
            : primitive(element);
    return type + "[]".repeat(depth);
  }

  // An unknown code is kept as it is: that id then matches nothing, as before.
  private static String primitive(final String code) {
    return switch (code) {
      case "Z" -> "boolean";
      case "B" -> "byte";
      case "C" -> "char";
      case "S" -> "short";
      case "I" -> "int";
      case "J" -> "long";
      case "F" -> "float";
      case "D" -> "double";
      default -> code;
    };
  }
}
