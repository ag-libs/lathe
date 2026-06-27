package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LatheUriTest {

  @Test
  void toPath_plainFileUri_returnsPath() {
    final Path result = LatheUri.toPath("file:///home/user/project/Foo.java");
    assertThat(result).isEqualTo(Path.of("/home/user/project/Foo.java"));
  }

  @Test
  void toPath_percentEncodedSpaces_decodesCorrectly() {
    final Path result = LatheUri.toPath("file:///home/user/my%20project/Foo.java");
    assertThat(result).isEqualTo(Path.of("/home/user/my project/Foo.java"));
  }
}
