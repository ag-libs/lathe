package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;

/** One frame of a test failure's stack; {@code fileName} is empty when the class names none. */
public record StackFrame(String className, String methodName, String fileName, int line) {

  public StackFrame {
    ValidCheck.check()
        .notBlank(className, "className")
        .notNull(methodName, "methodName")
        .notNull(fileName, "fileName")
        .validate();
  }
}
