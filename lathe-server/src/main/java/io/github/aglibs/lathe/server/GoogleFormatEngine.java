package io.github.aglibs.lathe.server;

import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.JavaFormatterOptions;
import com.google.googlejavaformat.java.JavaFormatterOptions.Style;
import java.nio.file.Path;

// Built-in in-process engine: Google Java Format, which also reorders and prunes imports.
record GoogleFormatEngine(Style style) implements FormatEngine {

  @Override
  public String format(final String source, final Path file) throws Exception {
    final var options = JavaFormatterOptions.builder().style(style).build();
    return new Formatter(options).formatSourceAndFixImports(source);
  }
}
