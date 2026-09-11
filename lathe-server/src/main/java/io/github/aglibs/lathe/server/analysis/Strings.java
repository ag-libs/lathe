package io.github.aglibs.lathe.server.analysis;

// Pure string helpers shared by analysis and its completion sub-package; public because Java has no
// cross-sub-package visibility.
public final class Strings {

  private Strings() {}

  public static String decapitalize(final String name) {
    return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }
}
