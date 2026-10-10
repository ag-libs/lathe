package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Map;

// The save-formatter for a workspace. engine is "google"/"aosp" (the project's pinned
// google-java-format, run in-process from classpath), "command" (command is an external
// stdin/stdout process), or "none". version and options mirror the project's Spotless step;
// classpath is the formatter's resolved jars.
public record FormatterSpec(
    String engine,
    List<String> command,
    String version,
    Map<String, String> options,
    List<String> classpath) {

  public FormatterSpec {
    ValidCheck.check().notBlank(engine, "engine").validate();
    command = command != null ? List.copyOf(command) : List.of();
    version = version != null ? version : "";
    options = options != null ? Map.copyOf(options) : Map.of();
    classpath = classpath != null ? List.copyOf(classpath) : List.of();
  }

  public FormatterSpec(final String engine, final List<String> command) {
    this(engine, command, "", Map.of(), List.of());
  }
}
