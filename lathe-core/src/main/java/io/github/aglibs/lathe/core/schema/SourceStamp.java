package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;

// A source as its class was last written, by the build or the server: its mtime then, and the
// content compiled. A writer that records no content (OpenJDK) leaves it null, so only the mtime
// decides whether the class is current.
public record SourceStamp(long mtime, ContentFingerprint content) {

  public SourceStamp {
    ValidCheck.check().isNonNegative(mtime, "mtime").validate();
  }
}
