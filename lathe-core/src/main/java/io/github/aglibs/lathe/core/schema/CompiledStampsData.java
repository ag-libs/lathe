package io.github.aglibs.lathe.core.schema;

import java.util.Map;

public record CompiledStampsData(Map<String, Long> stamps) {

  public CompiledStampsData {
    stamps = stamps != null ? Map.copyOf(stamps) : Map.of();
  }
}
