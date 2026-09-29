package io.github.aglibs.lathe.server;

import com.google.googlejavaformat.java.Formatter;

// Built-in in-process engine: Google Java Format, which also reorders and prunes imports.
final class GoogleFormatEngine implements FormatEngine {

  @Override
  public String format(final String source) throws Exception {
    return new Formatter().formatSourceAndFixImports(source);
  }
}
