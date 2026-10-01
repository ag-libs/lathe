package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

// The save-formatter for a workspace. engine is one of "google", "aosp", "none", or "command"; a
// "command" engine runs command (argv) as an external process.
public record FormatterSpec(String engine, List<String> command) {

  public FormatterSpec {
    ValidCheck.check().notBlank(engine, "engine").validate();
    command = command != null ? List.copyOf(command) : List.of();
  }
}
