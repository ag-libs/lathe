package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipCacheTest {

  @TempDir Path tmp;

  @Test
  void extract_cleansTempDirOnFailure() throws IOException {
    final Path zip = createZip(tmp.resolve("src.zip"), "A.java", "class A {}");

    assertThatThrownBy(
            () ->
                ZipCache.extract(
                    zip,
                    tmp.resolve("target"),
                    ignored -> {
                      throw new IOException("callback failed");
                    }))
        .isInstanceOf(IOException.class)
        .hasMessage("callback failed");

    try (final var children = Files.list(tmp)) {
      assertThat(children.filter(p -> p.getFileName().toString().contains(".tmp-"))).isEmpty();
    }
  }

  private static Path createZip(final Path path, final String entry, final String content)
      throws IOException {
    try (final ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
      out.putNextEntry(new ZipEntry(entry));
      out.write(content.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
    return path;
  }
}
