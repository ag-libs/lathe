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

  // What the compile task records as it starts, the capture after it reads once: the file goes, so
  // a later capture with no compile before it finds none and stamps the sources as they are.
  @Test
  void take_recordedAtTheCompilesStart_readsTheMtimesOnce_noneWhenNothingWasRecorded()
      throws Exception {
    final Path source =
        Files.writeString(
            Files.createDirectories(tmp.resolve("src")).resolve("Foo.java"), "class Foo {}");
    Files.setLastModifiedTime(source, FileTime.fromMillis(1_000L));
    final Path file = tmp.resolve("build/tmp/compileJava").resolve(SourceMtimes.FILE_NAME);

    SourceMtimes.write(List.of(source), file);

    assertThat(SourceMtimes.take(file)).contains(Map.of(source.toAbsolutePath(), 1_000L));
    assertThat(file).doesNotExist();
    assertThat(SourceMtimes.take(file)).isEmpty();
  }
}
