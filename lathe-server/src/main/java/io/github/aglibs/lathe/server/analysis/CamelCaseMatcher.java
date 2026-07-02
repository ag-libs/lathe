package io.github.aglibs.lathe.server.analysis;

import java.util.ArrayList;
import java.util.List;

final class CamelCaseMatcher {

  private CamelCaseMatcher() {}

  static boolean matches(final String query, final String candidateSimpleName) {
    if (query.isEmpty()) {
      return false;
    }

    return matchesHumps(splitHumps(query), splitHumps(candidateSimpleName));
  }

  private static boolean matchesHumps(
      final List<String> queryHumps, final List<String> candidateHumps) {
    int qi = 0;
    for (int ci = 0; ci < candidateHumps.size() && qi < queryHumps.size(); ci++) {
      if (humpMatches(queryHumps.get(qi), candidateHumps.get(ci))) {
        qi++;
      }
    }

    return qi == queryHumps.size();
  }

  private static boolean humpMatches(final String queryHump, final String candidateHump) {
    if (candidateHump.isEmpty()) {
      return false;
    }

    if (Character.toLowerCase(queryHump.charAt(0))
        != Character.toLowerCase(candidateHump.charAt(0))) {
      return false;
    }

    return isSubsequence(queryHump.substring(1), candidateHump.substring(1));
  }

  private static boolean isSubsequence(final String needle, final String haystack) {
    int hi = 0;
    for (int ni = 0; ni < needle.length(); ni++) {
      final char c = Character.toLowerCase(needle.charAt(ni));
      boolean found = false;
      while (hi < haystack.length()) {
        final boolean isMatch = Character.toLowerCase(haystack.charAt(hi)) == c;
        hi++;
        if (isMatch) {
          found = true;
          break;
        }
      }

      if (!found) {
        return false;
      }
    }

    return true;
  }

  // Splits at each uppercase-letter or digit boundary, e.g. "AbstractServerFactory" ->
  // ["Abstract", "Server", "Factory"], "ASF" -> ["A", "S", "F"], "http2Client" -> ["http", "2",
  // "Client"].
  private static List<String> splitHumps(final String name) {
    final var humps = new ArrayList<String>();
    int start = 0;
    for (int i = 1; i < name.length(); i++) {
      final char c = name.charAt(i);
      if (Character.isUpperCase(c) || Character.isDigit(c)) {
        humps.add(name.substring(start, i));
        start = i;
      }
    }

    humps.add(name.substring(start));
    return List.copyOf(humps);
  }
}
