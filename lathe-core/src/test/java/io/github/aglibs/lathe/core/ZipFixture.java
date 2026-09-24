package io.github.aglibs.lathe.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ZipFixture {

  private ZipFixture() {}

  public static Path create(final Path path, final String entry, final String content)
      throws IOException {
    return create(path, Map.of(entry, content));
  }

  // Multi-entry zip; an entry whose name ends with "/" is written as a directory entry.
  public static Path create(final Path path, final Map<String, String> entries) throws IOException {
    try (final ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
      for (final Map.Entry<String, String> entry : entries.entrySet()) {
        out.putNextEntry(new ZipEntry(entry.getKey()));
        out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    return path;
  }
}
