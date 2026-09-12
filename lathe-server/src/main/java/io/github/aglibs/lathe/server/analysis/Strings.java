package io.github.aglibs.lathe.server.analysis;

import java.util.Locale;

// Pure string helpers shared by analysis and its completion sub-package; public because Java has no
// cross-sub-package visibility.
public final class Strings {

  private Strings() {}

  // Lower-camel a type's simple name, handling a leading acronym run: URI -> uri (whole word), and
  // IOException -> ioException (keep the last capital as the next word's start). A single leading
  // capital is the ordinary case: String -> string.
  public static String decapitalize(final String name) {
    if (name.isEmpty()) {
      return name;
    }

    final int upper = leadingUpperCount(name);
    if (upper <= 1) {
      return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    final int lower = upper == name.length() ? upper : upper - 1;
    return name.substring(0, lower).toLowerCase(Locale.ROOT) + name.substring(lower);
  }

  private static int leadingUpperCount(final String name) {
    int count = 0;
    while (count < name.length() && Character.isUpperCase(name.charAt(count))) {
      count++;
    }

    return count;
  }
}
