package io.github.aglibs.lathe.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import org.eclipse.lsp4j.Location;

// A definition's locations, and whether it found none because it names a JDK element while the
// workspace has no JDK sources: an empty answer the user can fix by installing them.
public record DefinitionResult(List<Location> locations, boolean needsJdkSources) {

  public DefinitionResult {
    ValidCheck.check().notNull(locations, "locations").validate();
    locations = List.copyOf(locations);
  }
}
