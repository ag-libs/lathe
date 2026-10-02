package io.github.aglibs.lathe.core.schema;

// Per-workspace style: the save-formatter (server) and the indent profile (client). Either section
// may be absent, letting the consumer fall back to its global default.
public record WorkspaceStyleData(FormatterSpec formatter, IndentSpec indent) {}
