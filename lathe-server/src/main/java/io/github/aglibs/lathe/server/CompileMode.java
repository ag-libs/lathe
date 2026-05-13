package io.github.aglibs.lathe.server;

enum CompileMode {
  FAST("compile"),
  OPEN("open"),
  FULL("save");

  final String tag;

  CompileMode(final String tag) {
    this.tag = tag;
  }
}
