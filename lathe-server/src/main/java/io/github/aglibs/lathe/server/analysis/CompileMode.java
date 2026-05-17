package io.github.aglibs.lathe.server.analysis;

public enum CompileMode {
  FAST("compile"),
  OPEN("open"),
  FULL("save");

  public final String tag;

  CompileMode(final String tag) {
    this.tag = tag;
  }
}
