package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;

/**
 * What an answer cannot vouch for, after the write pass that precedes it: files whose write failed
 * and are unchanged since ({@code path:line: message}, fixed in code), what only a sync can fix
 * (each with its command), and whether a build holds the reactor lock.
 */
public record LatheNotes(
    List<String> notCompiled, List<String> syncRequired, boolean buildInProgress) {
  public LatheNotes {
    ValidCheck.check()
        .notNull(notCompiled, "notCompiled")
        .notNull(syncRequired, "syncRequired")
        .validate();
    notCompiled = List.copyOf(notCompiled);
    syncRequired = List.copyOf(syncRequired);
  }
}
