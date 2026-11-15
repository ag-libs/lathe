package io.github.aglibs.lathe.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import org.eclipse.lsp4j.Location;

// One member of a type's or method's family below it: an implementation, or a redeclaration, an
// abstract method that overrides the target without implementing it, in a subinterface or an
// abstract class.
public record ImplementationHit(Location location, boolean redeclaration) {
  public ImplementationHit {
    ValidCheck.check().notNull(location, "location").validate();
  }
}
