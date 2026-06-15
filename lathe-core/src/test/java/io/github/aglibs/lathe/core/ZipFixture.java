package io.github.aglibs.lathe.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ZipFixture {

  private ZipFixture() {}

  public static Path create(final Path path, final String entry, final String content)
      throws IOException {
    try (final ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
      out.putNextEntry(new ZipEntry(entry));
      out.write(content.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    return path;
  }
}
