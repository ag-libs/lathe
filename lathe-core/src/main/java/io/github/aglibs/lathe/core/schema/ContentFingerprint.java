package io.github.aglibs.lathe.core.schema;

import io.github.aglibs.validcheck.ValidCheck;
import java.util.zip.CRC32C;

// A file's content as a sync saw it: its size and CRC32C. Equal fingerprints mean the content is
// back to (or still) what was synced; the size makes a checksum collision less likely still.
public record ContentFingerprint(long size, long checksum) {

  public ContentFingerprint {
    ValidCheck.check().isNonNegative(size, "size").validate();
  }

  // CRC32C (a CPU instruction on x86 and ARM) plus the size: cheap, and only compared for equality.
  public static ContentFingerprint of(final byte[] content) {
    final var crc = new CRC32C();
    crc.update(content);
    return new ContentFingerprint(content.length, crc.getValue());
  }
}
