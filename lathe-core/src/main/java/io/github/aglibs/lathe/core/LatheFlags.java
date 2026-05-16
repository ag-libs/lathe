package io.github.aglibs.lathe.core;

public final class LatheFlags {

  private LatheFlags() {}

  public static boolean isDisabled() {
    final var skip = System.getProperty("lathe.skip");
    if ("true".equals(skip)) {
      return true;
    }

    if ("false".equals(skip)) {
      return false;
    }

    return System.getenv("CI") != null;
  }

  public static boolean isForcedSync() {
    return "true".equals(System.getProperty("lathe.sync.force"));
  }
}
