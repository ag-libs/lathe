package io.github.aglibs.lathe.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ZipFixture {

  private ZipFixture() {}

  public static Path create(final Path path, final String entry, final String content)
      throws IOException {
    return create(path, Map.of(entry, content.getBytes(StandardCharsets.UTF_8)));
  }

  public static Path create(final Path path, final Map<String, byte[]> entries) throws IOException {
    try (final ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
      for (final Map.Entry<String, byte[]> entry : entries.entrySet()) {
        writeEntry(out, entry.getKey(), entry.getValue());
      }
    }
    return path;
  }

  public static Path createFromDirectory(
      final Path path, final Path root, final Map<String, byte[]> extraEntries) throws IOException {
    try (final ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
      try (final var walk = Files.walk(root)) {
        final List<Path> files = walk.filter(Files::isRegularFile).toList();
        for (final Path file : files) {
          writeEntry(out, root.relativize(file).toString(), Files.readAllBytes(file));
        }
      }

      for (final Map.Entry<String, byte[]> entry : extraEntries.entrySet()) {
        writeEntry(out, entry.getKey(), entry.getValue());
      }
    }
    return path;
  }

  private static void writeEntry(final ZipOutputStream out, final String name, final byte[] content)
      throws IOException {
    out.putNextEntry(new ZipEntry(name.replace('\\', '/')));
    out.write(content);
    out.closeEntry();
  }
}
