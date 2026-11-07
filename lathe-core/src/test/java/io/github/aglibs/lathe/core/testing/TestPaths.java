package io.github.aglibs.lathe.core.testing;

import java.nio.file.Path;

// Absolute paths in a launch are OS-native, so a test spells each one through Path, on both sides:
// "/workspace/app" stays as it is on Linux and becomes "\workspace\app" on Windows.
public final class TestPaths {

  private TestPaths() {}

  public static String os(final String posixPath) {
    return Path.of(posixPath).toString();
  }
}
