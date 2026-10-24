package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;

// A file's content as a sync saw it: its size and CRC32C. Equal fingerprints mean the content is
// back to (or still) what was synced; the size makes a checksum collision less likely still.
public record ContentFingerprint(long size, long checksum) {

  public ContentFingerprint {
    ValidCheck.check().isNonNegative(size, "size").validate();
  }
}
