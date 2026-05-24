package io.github.aglibs.lathe.server.analysis;

public enum CompileMode {
  FAST("fast"),
  OPEN("open"),
  FULL("full");

  public final String tag;

  CompileMode(final String tag) {
    this.tag = tag;
  }
}
