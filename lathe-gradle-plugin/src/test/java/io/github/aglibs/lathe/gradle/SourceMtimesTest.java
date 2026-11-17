package io.github.aglibs.lathe.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceMtimesTest {

  @TempDir Path tmp;

  // What the snapshot task records before the compile, the capture after it reads once: the file
  // goes, so a capture with no snapshot before it finds none and stamps no source.
  @Test
  void take_recordedBeforeTheCompile_readsTheMtimesOnce_noneWithoutASnapshot() throws Exception {
    final Path root = Files.createDirectories(tmp.resolve("src"));
    final Path source = Files.writeString(root.resolve("Foo.java"), "class Foo {}");
    Files.setLastModifiedTime(source, FileTime.fromMillis(1_000L));
    Files.writeString(root.resolve("notes.txt"), "not a source");
    final Path file = tmp.resolve(".lathe/app").resolve(SourceMtimes.fileName("classes"));

    SourceMtimes.write(List.of(root), file);

    assertThat(SourceMtimes.take(file)).isEqualTo(Map.of(source.toAbsolutePath(), 1_000L));
    assertThat(file).doesNotExist();
    assertThat(SourceMtimes.take(file)).isEmpty();
  }

  // A snapshot a crash cut off tells nothing: no source is stamped, the capture goes on, and the
  // file goes.
  @Test
  void take_damagedSnapshot_noneAndTheFileGone() throws Exception {
    final Path file =
        Files.createDirectories(tmp.resolve(".lathe/app"))
            .resolve(SourceMtimes.fileName("classes"));
    Files.writeString(file, "{\"byPath\": {\"/src/Foo.java\": 10");

    assertThat(SourceMtimes.take(file)).isEmpty();
    assertThat(file).doesNotExist();
  }
}
