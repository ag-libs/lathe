package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.Location;

// A definition's locations and, when it found none because it named a JDK element while the
// workspace has no JDK sources, the explanation of what to install; null otherwise.
record ExplainedDefinition(List<Location> locations, String note) {

  ExplainedDefinition {
    ValidCheck.check().notNull(locations, "locations").validate();
    locations = List.copyOf(locations);
  }

  Optional<String> jdkSourcesNote() {
    return Optional.ofNullable(note);
  }
}
