package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;

// Per-workspace style: the save-formatter (server) and the indent profile (client). Either section
// may be absent, letting the consumer fall back to its global default.
public record WorkspaceStyleData(String schemaVersion, FormatterSpec formatter, IndentSpec indent) {

  public WorkspaceStyleData {
    ValidCheck.check().notBlank(schemaVersion, "schemaVersion").validate();
  }
}
