package io.github.aglibs.lathe.core.launch;

import java.util.Arrays;

public enum TestSelectionKind {
  CLASS("--select-class"),
  METHOD("--select-method"),
  PACKAGE("--select-package"),
  MODULE("--select-module");

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
