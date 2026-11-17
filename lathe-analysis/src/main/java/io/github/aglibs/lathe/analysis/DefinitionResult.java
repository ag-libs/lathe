package io.github.aglibs.lathe.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.Location;

// A definition's locations and, when it found none, why: it names a JDK element while the workspace
// has no JDK sources, an empty answer the user can fix by installing them; or it names a type from
// a dependency without sources (sourceless, null otherwise).
public record DefinitionResult(
    List<Location> locations, boolean needsJdkSources, SourcelessDeclaration sourceless) {

  public DefinitionResult {
    ValidCheck.check().notNull(locations, "locations").validate();
    locations = List.copyOf(locations);
  }

  public static DefinitionResult none() {
    return new DefinitionResult(List.of(), false, null);
  }

  public Optional<SourcelessDeclaration> sourcelessDeclaration() {
    return Optional.ofNullable(sourceless);
  }
}
