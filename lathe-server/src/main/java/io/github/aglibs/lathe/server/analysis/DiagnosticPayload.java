package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;

public record DiagnosticPayload(Kind kind, String name) {

  public DiagnosticPayload {
    ValidCheck.check().notNull(kind, "kind").notNull(name, "name").validate();
  }

  public enum Kind {
    TYPE_REF,
    VARIABLE_REF,
    // The qualifier of a member select (Objects in Objects.requireNonNull(o)): javac cannot tell
    // there whether it is a type to import or a statically imported field.
    QUALIFIER_REF,
    UNREPORTED_EXCEPTION,
    MISSING_METHOD_IMPL;

    public boolean offersTypeImport() {
      return this == TYPE_REF || this == QUALIFIER_REF;
    }

    public boolean offersStaticImport() {
      return this == VARIABLE_REF || this == QUALIFIER_REF;
    }
  }
}
