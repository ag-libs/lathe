package io.github.aglibs.lathe.analysis;

// IntelliJ's CamelHumps, as its Go to Class matches a type name: the query starts at a word of the
// name, and each of its letters either continues the run it is in or starts a later word, case
// ignored. So TaskMan, tm and taskMan reach TaskManager, and ServerFactory AbstractServerFactory,
// but a letter never lands inside a word: Pet does not reach I18nPropertiesSyncTest, nor Mgr
// TaskManager. A query with a capital past its first letter marks its words itself, so its lower
// case letters only continue a word: OcT does not reach OwnerControllerTest, oCT and oc do. A
// capital may still continue a run, as case is ignored: OCT reaches Octopus, in IntelliJ too. A
// '_' or '$' in the query, or a lowercase letter right after one, may start a word again; the
// separator skips to the next one in the name. IntelliJ also matches a query from inside a word
// (ana in TaskManager); that is not done here.
final class CamelCaseMatcher {

  private final String query;
  private final String name;
  private final boolean marksWords;
  // A query position and a name position tried once and failed: without it, a name with many
  // words that start alike is backtracked through again and again. Made on the first failure, as
  // most names of a search fail at once and most matches never backtrack.
  private boolean[][] failed;

  private CamelCaseMatcher(final String query, final String name) {
    this.query = query;
    this.name = name;
    this.marksWords = query.chars().skip(1).anyMatch(Character::isUpperCase);
  }

  static boolean matches(final String query, final String candidateSimpleName) {
    if (query.isEmpty()) {
      return false;
    }

    return new CamelCaseMatcher(query, candidateSimpleName).fromWordStart(0, 0);
  }

  private boolean fromWordStart(final int queryAt, final int nameFrom) {
    final char next = query.charAt(queryAt);
    if (!Character.isLetterOrDigit(next)) {
      return fromSeparator(queryAt, nameFrom);
    }

    if (continuesOnly(queryAt)) {
      return false;
    }

    for (int at = nameFrom; at < name.length(); at++) {
      if (isWordStart(at) && runFrom(queryAt, at)) {
        return true;
      }
    }

    return false;
  }

  private boolean fromSeparator(final int queryAt, final int nameFrom) {
    for (int at = nameFrom; at < name.length(); at++) {
      if (name.charAt(at) == query.charAt(queryAt) && runFrom(queryAt, at)) {
        return true;
      }
    }

    return false;
  }

  private boolean continuesOnly(final int queryAt) {
    return marksWords
        && queryAt > 0
        && Character.isLowerCase(query.charAt(queryAt))
        && Character.isLetterOrDigit(query.charAt(queryAt - 1));
  }

  // The query from queryAt on, its first letter at nameAt: the longest run of letters that
  // continue there, then the rest from a later word; a shorter run when the rest needs a letter
  // the longer one took (TaskMa reaches TaskManager's M only when the run stops at Task).
  private boolean runFrom(final int queryAt, final int nameAt) {
    if (failed != null && failed[queryAt][nameAt]) {
      return false;
    }

    int run = 0;
    while (queryAt + run < query.length()
        && nameAt + run < name.length()
        && sameLetter(query.charAt(queryAt + run), name.charAt(nameAt + run))) {
      run++;
    }

    if (queryAt + run == query.length()) {
      return true;
    }

    for (int length = run; length > 0; length--) {
      if (fromWordStart(queryAt + length, nameAt + length)) {
        return true;
      }
    }

    if (failed == null) {
      failed = new boolean[query.length()][name.length()];
    }

    failed[queryAt][nameAt] = true;
    return false;
  }

  private static boolean sameLetter(final char a, final char b) {
    return Character.toLowerCase(a) == Character.toLowerCase(b);
  }

  // A word starts the name, follows a '_' or '$', or is a digit (Aes|1|2|8), a capital after a
  // lowercase letter or a digit, or the last capital of an acronym before a lowercase letter
  // (URL|Connection). A lowercase letter after a digit is no word start.
  private boolean isWordStart(final int at) {
    final char current = name.charAt(at);
    if (!Character.isLetterOrDigit(current)) {
      return false;
    }

    if (at == 0 || !Character.isLetterOrDigit(name.charAt(at - 1))) {
      return true;
    }

    if (Character.isDigit(current)) {
      return true;
    }

    final char previous = name.charAt(at - 1);
    if (!Character.isUpperCase(current)) {
      return false;
    }

    return !Character.isUpperCase(previous)
        || at + 1 < name.length() && Character.isLowerCase(name.charAt(at + 1));
  }
}
