package io.github.aglibs.lathe.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;

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
//
// A match is ranked as IntelliJ ranks it (MinusculeMatcher.matchingDegree, its terms measured, not
// copied): a query capital on a capital of the name counts most, so ASF ranks AbstractServerFactory
// above AsyncAppenderFactory, whose S only continues As; each word skipped between two runs and
// each lowercase letter that starts a word cost less; starting and ending with the name decide
// what is left.
final class CamelCaseMatcher {

  private static final int WORD_START = 1000;
  private static final int CAPITAL = 50;
  private static final int SKIPPED_WORD = 10;
  private static final int LOWERCASE_WORD_START = 10;

  // Where a run of the query, from queryAt, lies in the name, from nameAt.
  private record Run(int queryAt, int nameAt, int length) {
    private Run {
      ValidCheck.check()
          .isNonNegative(queryAt, "queryAt")
          .isNonNegative(nameAt, "nameAt")
          .isPositive(length, "length")
          .validate();
    }

    int end() {
      return nameAt + length;
    }
  }

  private final String query;
  private final String name;
  private final boolean marksWords;
  // A query position and a name position tried once and failed: without it, a name with many
  // words that start alike is backtracked through again and again. Made on the first failure, as
  // most names of a search fail at once and most matches never backtrack.
  private boolean[][] failed;
  // The runs of the match found, the last first, as each is added once the rest has matched.
  private final List<Run> runs = new ArrayList<>();

  private CamelCaseMatcher(final String query, final String name) {
    this.query = query;
    this.name = name;
    this.marksWords = query.chars().skip(1).anyMatch(Character::isUpperCase);
  }

  static boolean matches(final String query, final String candidateSimpleName) {
    return !query.isEmpty() && new CamelCaseMatcher(query, candidateSimpleName).fromWordStart(0, 0);
  }

  // How well the query matches the name, higher is better; empty when it does not match. Scored on
  // the match found, not the best one a name allows, as IntelliJ does: TM ranks TestTaskManager
  // (T on Test) below TaskManager.
  static OptionalInt degree(final String query, final String candidateSimpleName) {
    if (query.isEmpty()) {
      return OptionalInt.empty();
    }

    final var matcher = new CamelCaseMatcher(query, candidateSimpleName);
    return matcher.fromWordStart(0, 0) ? OptionalInt.of(matcher.score()) : OptionalInt.empty();
  }

  private int score() {
    final List<Run> ordered = runs.stream().sorted(Comparator.comparingInt(Run::queryAt)).toList();
    final int start = ordered.get(0).nameAt();
    // IntelliJ's: no word-start credit for a word right after another one-letter word or a digit
    // (JobKOctets' O, after K), as such a word is likely a letter of an acronym. It sinks IService
    // and Base64Encoder for Service and Encoder too, in IntelliJ as here.
    int score = start == 0 || !isWordStart(start - 1) ? WORD_START : 0;
    int previousEnd = start;
    for (final Run run : ordered) {
      for (int i = 0; i < run.length(); i++) {
        final char letter = query.charAt(run.queryAt() + i);
        if (Character.isUpperCase(letter) && letter == name.charAt(run.nameAt() + i)) {
          score += CAPITAL;
        }
      }

      for (int at = previousEnd; at < run.nameAt(); at++) {
        if (isWordStart(at)) {
          score -= SKIPPED_WORD;
        }
      }

      // A later word the query starts lowercase was not marked by the user.
      if (run.queryAt() > 0 && Character.isLowerCase(query.charAt(run.queryAt()))) {
        score -= LOWERCASE_WORD_START;
      }

      previousEnd = run.end();
    }

    final boolean endsTheName = previousEnd == name.length();
    return score + (start == 0 ? 1 : 0) + (endsTheName ? 1 : 0);
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
  // the longer one took (TaskMa reaches TaskManager's M only when the run stops at Task). A query
  // capital the run would take onto a lowercase letter is tried at a later word first, as IntelliJ
  // does: SSH's H lands on ServerShutdownHandler's Handler, not on Shutdown's h.
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

    int capital = 0;
    for (int i = 1; i < run && capital == 0; i++) {
      final char letter = query.charAt(queryAt + i);
      if (Character.isUpperCase(letter) && letter != name.charAt(nameAt + i)) {
        capital = i;
      }
    }

    if (capital > 0 && runOf(queryAt, nameAt, capital)) {
      return true;
    }

    for (int length = run; length > 0; length--) {
      if (length != capital && runOf(queryAt, nameAt, length)) {
        return true;
      }
    }

    if (failed == null) {
      failed = new boolean[query.length()][name.length()];
    }

    failed[queryAt][nameAt] = true;
    return false;
  }

  private boolean runOf(final int queryAt, final int nameAt, final int length) {
    if (queryAt + length < query.length() && !fromWordStart(queryAt + length, nameAt + length)) {
      return false;
    }

    runs.add(new Run(queryAt, nameAt, length));
    return true;
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
