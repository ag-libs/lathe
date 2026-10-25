package io.github.aglibs.lathe.core.schema;

import java.util.Map;

// stamps: each source as its class was last written (by the build or the server), which the stale
// scan compares the source with.
// built: each source's content when the build last compiled it; the server's writes leave it, so
// verify_change reads a change put back as no change.
public record CompiledStampsData(
    Map<String, SourceStamp> stamps, Map<String, ContentFingerprint> built) {

  public CompiledStampsData {
    stamps = stamps != null ? Map.copyOf(stamps) : Map.of();
    built = built != null ? Map.copyOf(built) : Map.of();
  }
}
