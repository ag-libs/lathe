package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipCacheTest {

  @TempDir Path tmp;

  @Test
  void extract_existingTarget_replacedWithNoLeftovers() throws IOException {
    final Path target = Files.createDirectories(tmp.resolve("target"));
    Files.writeString(target.resolve("Old.java"), "class Old {}");
    final Path zip = ZipFixture.create(tmp.resolve("src.zip"), "A.java", "class A {}");

    ZipCache.extract(zip, target, ignored -> {});

    try (final var files = Files.list(target);
        final var siblings = Files.list(tmp)) {
      assertThat(files.map(file -> file.getFileName().toString())).containsExactly("A.java");
      assertThat(siblings.map(sibling -> sibling.getFileName().toString()))
          .containsExactlyInAnyOrder("src.zip", "target");
    }
  }

  @Test
  void extract_cleansTempDirOnFailure() throws IOException {
    final Path zip = ZipFixture.create(tmp.resolve("src.zip"), "A.java", "class A {}");

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
}
