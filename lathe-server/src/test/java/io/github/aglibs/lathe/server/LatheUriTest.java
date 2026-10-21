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

  @Test
  void isFileUri_localFileUri_true() {
    assertThat(LatheUri.isFileUri("file:///home/user/project/Foo.java")).isTrue();
  }

  @Test
  void isFileUri_pathlessOrNonFileOrNull_false() {
    // file:// is what Neovim sends for an unnamed [No Name] buffer -- no path, and URI.create
    // throws.
    assertThat(LatheUri.isFileUri("file://")).isFalse();
    assertThat(LatheUri.isFileUri("untitled:Untitled-1")).isFalse();
    assertThat(LatheUri.isFileUri("")).isFalse();
    assertThat(LatheUri.isFileUri(null)).isFalse();
  }

  // VS Code encodes characters Path.toUri leaves alone; both spellings must name one document.
  @Test
  void canonical_clientEncodedFileUri_matchesTheServersOwnForm_otherUrisUnchanged() {
    final var server = Path.of("/home/user/a@b+c d/Foo.java").toUri().toString();

    assertThat(LatheUri.canonical("file:///home/user/a%40b%2Bc%20d/Foo.java")).isEqualTo(server);
    assertThat(LatheUri.canonical(server)).isEqualTo(server);
    assertThat(LatheUri.canonical("untitled:Untitled-1")).isEqualTo("untitled:Untitled-1");
    assertThat(LatheUri.canonical("file://")).isEqualTo("file://");
  }
}
