package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Optional;

// Where the symbol is declared and, when it is nowhere to be found, why: what to install when it is
// the JDK's and the workspace has no JDK sources, or the dependency without sources that declares
// it; null otherwise.
public record LatheDefinition(List<LatheLocation> targets, String note) {

  public LatheDefinition {
    ValidCheck.check().notNull(targets, "targets").validate();
    targets = List.copyOf(targets);
  }

  public Optional<String> explanation() {
    return Optional.ofNullable(note);
  }
}
