package io.github.aglibs.lathe.analysis;

import io.github.aglibs.validcheck.ValidCheck;

// A type read from a dependency jar whose dependency has no sources, by its qualified name and the
// dependency's GAV: navigating to it has no source to show, and says where it is declared instead.
public record SourcelessDeclaration(String typeName, String dependency) {

  public SourcelessDeclaration {
    ValidCheck.check().notBlank(typeName, "typeName").notBlank(dependency, "dependency").validate();
  }
}
