package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;

public record DiagnosticPayload(Kind kind, String name) {

  public DiagnosticPayload {
    ValidCheck.check().notNull(kind, "kind").notNull(name, "name").validate();
  }

  public enum Kind {
    TYPE_REF,
    VARIABLE_REF,
    UNREPORTED_EXCEPTION,
    MISSING_METHOD_IMPL,
  }
}
