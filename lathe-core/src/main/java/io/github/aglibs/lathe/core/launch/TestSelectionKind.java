package io.github.aglibs.lathe.core.launch;

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
}
