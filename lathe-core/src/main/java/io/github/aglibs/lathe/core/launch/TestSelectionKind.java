package io.github.aglibs.lathe.core.launch;

import java.util.Arrays;

public enum TestSelectionKind {
  CLASS("--select-class"),
  METHOD("--select-method"),
  PACKAGE("--select-package"),
  MODULE("--select-module"),
  // Not selectors: class-name regexes narrowing a package or module run to the classes the build's
  // own test run would execute (its Surefire includes/excludes).
  CLASS_NAME_INCLUDE("--include-classname"),
  CLASS_NAME_EXCLUDE("--exclude-classname");

  private final String runnerFlag;

  TestSelectionKind(final String runnerFlag) {
    this.runnerFlag = runnerFlag;
  }

  public String runnerFlag() {
    return runnerFlag;
  }

  public static TestSelectionKind fromRunnerFlag(final String runnerFlag) {
    return Arrays.stream(values())
        .filter(kind -> kind.runnerFlag().equals(runnerFlag))
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("Unknown selector %s".formatted(runnerFlag)));
  }
}
