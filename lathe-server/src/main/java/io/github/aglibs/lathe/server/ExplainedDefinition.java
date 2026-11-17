package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.analysis.SourcelessDeclaration;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.Location;

// A definition's locations and, when it found none, why: what to install when it named a JDK
// element while the workspace has no JDK sources (note), or the type and its dependency when it
// named a type from a dependency without sources (sourceless); each null otherwise.
record ExplainedDefinition(
    List<Location> locations, String note, SourcelessDeclaration sourceless) {

  ExplainedDefinition {
    ValidCheck.check().notNull(locations, "locations").validate();
    locations = List.copyOf(locations);
  }

  Optional<String> jdkSourcesNote() {
    return Optional.ofNullable(note);
  }

  // Where the type is declared; each client ends it with its own advice.
  Optional<String> sourcelessNote() {
    return Optional.ofNullable(sourceless)
        .map(
            declaration ->
                "%s is declared in %s, which has no sources"
                    .formatted(declaration.typeName(), declaration.dependency()));
  }
}
