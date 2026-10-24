package io.github.aglibs.lathe.core.schema;

import java.util.Map;

// stamps: each source's mtime when its class was last written (by the build or the server).
// built: each source's content when the build last compiled it; the server's writes leave it, so a
// change put back reads as no change.
public record CompiledStampsData(Map<String, Long> stamps, Map<String, ContentFingerprint> built) {

  public CompiledStampsData {
    stamps = stamps != null ? Map.copyOf(stamps) : Map.of();
    built = built != null ? Map.copyOf(built) : Map.of();
  }
}
