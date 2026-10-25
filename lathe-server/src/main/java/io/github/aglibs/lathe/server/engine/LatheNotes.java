package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

// What an answer cannot vouch for, after the write pass that precedes it: files whose write failed
// and are unchanged since (path:line: message, fixed in code), what only a sync can fix (each with
// its command), the asked-about files no module compiles (and whether a sync would), and whether a
// build holds the reactor lock.
public record LatheNotes(
    List<String> notCompiled,
    List<String> syncRequired,
    List<String> notChecked,
    boolean buildInProgress) {
  public LatheNotes {
    ValidCheck.check()
        .notNull(notCompiled, "notCompiled")
        .notNull(syncRequired, "syncRequired")
        .notNull(notChecked, "notChecked")
        .validate();
    notCompiled = List.copyOf(notCompiled);
    syncRequired = List.copyOf(syncRequired);
    notChecked = List.copyOf(notChecked);
  }

  // Only the asked-about files no module checks, for an answer that reports the rest itself.
  public static LatheNotes ofNotChecked(final List<String> notChecked) {
    return new LatheNotes(List.of(), List.of(), notChecked, false);
  }
}
