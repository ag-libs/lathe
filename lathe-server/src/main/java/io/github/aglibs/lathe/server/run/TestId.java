package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;

/**
 * Reconstructs a method's RunTarget position id from a JUnit {@code MethodSource}'s identity,
 * matching {@code RunnableScanner.methodTarget}'s format exactly: {@code
 * <binaryClassName>#<methodName>(<erasedParams>)}. JUnit joins parameter types with {@code ", "}
 * whereas javac's erasure joins with {@code ","} and no space, so whitespace is stripped; the
 * zero-arg case is {@code <class>#<method>()} identically on both sides. Signature shapes that do
 * not reconstruct identically (arrays, generics, varargs) simply will not match a discovered
 * position and fall through to the aggregate status, the same as before this mapping moved
 * server-side.
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
    final String params = methodParameterTypes.replaceAll("\\s", "");
    return "%s#%s(%s)".formatted(className, methodName, params);
  }
}
