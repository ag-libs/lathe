package io.github.aglibs.lathe.server.run;

import io.github.aglibs.validcheck.ValidCheck;

// The source file for a test class, resolved from the reactor layout by path math (no attribution).
// `path` is a filesystem path (for a quickfix entry), empty when the class resolves to no source.
public record TestSource(String className, String path) {

  public TestSource {
    ValidCheck.check().notBlank(className, "className").notNull(path, "path").validate();
  }
}
