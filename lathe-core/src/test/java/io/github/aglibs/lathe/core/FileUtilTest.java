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

final class FileUtilTest {

  @TempDir private Path tempDir;

  @Test
  void unzipExtractsNestedFiles() throws IOException {
    final Path zip = tempDir.resolve("sources.jar");
    zip(zip, "com/example/Hello.java", "class Hello {}");

    final Path dest = tempDir.resolve("dest");
    FileUtil.unzip(zip, dest);

    assertThat(dest.resolve("com/example/Hello.java")).hasContent("class Hello {}");
  }

  @Test
  void unzipRejectsUnsafePaths() throws IOException {
    final Path zip = tempDir.resolve("bad.jar");
    zip(zip, "../escape.java", "bad");

    assertThatThrownBy(() -> FileUtil.unzip(zip, tempDir.resolve("dest")))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("unsafe path");
  }

  @Test
  void deleteDirDeletesNestedTree() throws IOException {
    final Path nested = tempDir.resolve("tree/a/b");
    Files.createDirectories(nested);
    Files.writeString(nested.resolve("file.txt"), "content");

    FileUtil.deleteDir(tempDir.resolve("tree"));

    assertThat(tempDir.resolve("tree")).doesNotExist();
  }

  @Test
  void moveReplacingReplacesFile() throws IOException {
    final Path src = tempDir.resolve("src.txt");
    final Path dest = tempDir.resolve("dest.txt");
    Files.writeString(src, "new");
    Files.writeString(dest, "old");

    FileUtil.moveReplacing(src, dest);

    assertThat(src).doesNotExist();
    assertThat(dest).hasContent("new");
  }

  private static void zip(final Path zip, final String name, final String content)
      throws IOException {
    try (final ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      out.putNextEntry(new ZipEntry(name));
      out.write(content.getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
  }
}
