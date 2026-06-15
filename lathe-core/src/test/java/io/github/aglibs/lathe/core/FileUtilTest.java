package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileUtilTest {

  @TempDir private Path tempDir;

  @Test
  void unzip_nestedFiles_extractsToDestination() throws IOException {
    final Path zip =
        ZipFixture.create(
            tempDir.resolve("sources.jar"), "com/example/Hello.java", "class Hello {}");

    final Path dest = tempDir.resolve("dest");
    FileUtil.unzip(zip, dest);

    assertThat(dest.resolve("com/example/Hello.java")).hasContent("class Hello {}");
  }

  @Test
  void unzip_unsafePath_throwsIOException() throws IOException {
    final Path zip = ZipFixture.create(tempDir.resolve("bad.jar"), "../escape.java", "bad");

    assertThatThrownBy(() -> FileUtil.unzip(zip, tempDir.resolve("dest")))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("unsafe path");
  }

  @Test
  void deleteDir_nestedTree_deletesRecursively() throws IOException {
    final Path nested = tempDir.resolve("tree/a/b");
    Files.createDirectories(nested);
    Files.writeString(nested.resolve("file.txt"), "content");

    FileUtil.deleteDir(tempDir.resolve("tree"));

    assertThat(tempDir.resolve("tree")).doesNotExist();
  }

  @Test
  void moveReplacing_existingDest_replacesFile() throws IOException {
    final Path src = tempDir.resolve("src.txt");
    final Path dest = tempDir.resolve("dest.txt");
    Files.writeString(src, "new");
    Files.writeString(dest, "old");

    FileUtil.moveReplacing(src, dest);

    assertThat(src).doesNotExist();
    assertThat(dest).hasContent("new");
  }
}
