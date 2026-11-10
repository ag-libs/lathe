package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Optional;

// Where the symbol is declared and, when it is nowhere to be found because it is the JDK's and the
// workspace has no JDK sources, what to install; null otherwise.
public record LatheDefinition(List<LatheLocation> targets, String note) {

  public LatheDefinition {
    ValidCheck.check().notNull(targets, "targets").validate();
    targets = List.copyOf(targets);
  }

  public Optional<String> jdkSourcesNote() {
    return Optional.ofNullable(note);
  }
}
