package io.github.aglibs.lathe.server.analysis;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

// Pure string helpers shared by analysis and its completion sub-package; public because Java has no
// cross-sub-package visibility.
public final class Strings {

  private Strings() {}

  // SCREAMING_SNAKE from arbitrary text: alphanumeric runs uppercased and joined with `_`
  // (`hello world` / `error-code` -> HELLO_WORLD / ERROR_CODE). Empty when there is no
  // alphanumeric.
  public static String screamingSnake(final String text) {
    return Arrays.stream(text.split("[^A-Za-z0-9]+"))
        .filter(part -> !part.isEmpty())
        .map(part -> part.toUpperCase(Locale.ROOT))
        .collect(Collectors.joining("_"));
  }

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
