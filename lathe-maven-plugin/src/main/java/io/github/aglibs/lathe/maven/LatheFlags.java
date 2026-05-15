package io.github.aglibs.lathe.maven;

final class LatheFlags {

  private LatheFlags() {}

  static boolean isDisabled() {
    final var skip = System.getProperty("lathe.skip");
    if ("true".equals(skip)) {
      return true;
    }

    if ("false".equals(skip)) {
      return false;
    }

    return System.getenv("CI") != null;
  }

  static boolean isForcedSync() {
    return "true".equals(System.getProperty("lathe.sync.force"));
  }
}
