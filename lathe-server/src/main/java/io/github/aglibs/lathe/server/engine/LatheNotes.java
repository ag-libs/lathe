package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

// What an answer cannot vouch for, after the write pass that precedes it: files whose write failed
// and are unchanged since (path:line: message, fixed in code), what only a sync can fix (each with
// its command), the asked-about files no module compiles (and whether a sync would), whether a
// build holds the reactor lock, and the modules with Kotlin sources, which Lathe does not read:
// answers about a symbol's uses and the workspace summary show those, no other answer.
public record LatheNotes(
    List<String> notCompiled,
    List<String> syncRequired,
    List<String> notChecked,
    boolean buildInProgress,
    List<String> kotlinModules) {
  public LatheNotes {
    ValidCheck.check()
        .notNull(notCompiled, "notCompiled")
        .notNull(syncRequired, "syncRequired")
        .notNull(notChecked, "notChecked")
        .notNull(kotlinModules, "kotlinModules")
        .validate();
    notCompiled = List.copyOf(notCompiled);
    syncRequired = List.copyOf(syncRequired);
    notChecked = List.copyOf(notChecked);
    kotlinModules = List.copyOf(kotlinModules);
  }

  // Only the asked-about files no module checks, for an answer that reports the rest itself.
  public static LatheNotes ofNotChecked(final List<String> notChecked) {
    return new LatheNotes(List.of(), List.of(), notChecked, false, List.of());
  }

  // What verify_change does not report itself: the files no module checks and the Kotlin modules.
  public LatheNotes forVerify() {
    return new LatheNotes(List.of(), List.of(), notChecked, false, kotlinModules);
  }
}
