package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.format.gjf.java.Formatter;
import io.github.aglibs.lathe.format.gjf.java.JavaFormatterOptions;
import io.github.aglibs.lathe.format.gjf.java.JavaFormatterOptions.Style;
import java.nio.file.Path;

// Built-in in-process engine: Google Java Style via lathe-format (google-java-format re-hosted on
// the
// public javac API, so it needs no javac access flags), which also reorders and prunes imports.
record GoogleFormatEngine(Style style) implements FormatEngine {

  @Override
  public String format(final String source, final Path file) throws Exception {
    final var options = JavaFormatterOptions.builder().style(style).build();
    return new Formatter(options).formatSourceAndFixImports(source);
  }
}
