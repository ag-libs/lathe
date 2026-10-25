package io.github.aglibs.lathe.core.schema;

// A source as its class was last written, by the build or the server: its mtime then, and the
// content compiled. A writer that records no content (OpenJDK) leaves it null, so only the mtime
// decides whether the class is current.
public record SourceStamp(long mtime, ContentFingerprint content) {

  // Any mtime is valid, a negative one too: a file can predate 1970 (an archive's, a `touch -d`).
  public SourceStamp {}
}
