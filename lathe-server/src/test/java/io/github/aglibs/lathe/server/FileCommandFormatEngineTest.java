package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.validcheck.ValidationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCommandFormatEngineTest {

  @TempDir private Path root;

  private FileCommandFormatEngine engine(final List<String> command) {
    return new FileCommandFormatEngine(command, root, FileCommandFormatEngine.DEFAULT_TIMEOUT);
  }

  @Test
  void format_writesBufferRunsCommandInPlace_thenReadsBack() throws Exception {
    Files.writeString(root.resolve("pom.xml"), "<project/>");
    final Path file = root.resolve("Foo.java");
    // Fake in-place formatter: uppercase the file (%FILE% -> absolute path).
    final var upper =
        engine(List.of("sh", "-c", "tr a-z A-Z < %FILE% > %FILE%.tmp && mv %FILE%.tmp %FILE%"));

    final String result = upper.format("abc\n", file);

    assertThat(result).isEqualTo("ABC\n");
    assertThat(Files.readString(file)).isEqualTo("ABC\n"); // file updated in place
  }

  @Test
  void format_substitutesModulePath_relativeForSubmoduleDotForRoot() throws Exception {
    Files.writeString(root.resolve("pom.xml"), "<project/>");
    Files.createDirectories(root.resolve("sub"));
    Files.writeString(root.resolve("sub/pom.xml"), "<project/>");
    final var echoModule = engine(List.of("sh", "-c", "printf %MODULE% > %FILE%"));

    assertThat(echoModule.format("x", root.resolve("sub/Foo.java"))).isEqualTo("sub");
    assertThat(echoModule.format("x", root.resolve("Root.java"))).isEqualTo(".");
  }

  @Test
  void format_nonZeroExit_throwsAndLeavesBufferUnchanged() {
    assertThatThrownBy(
            () -> engine(List.of("sh", "-c", "exit 1")).format("x", root.resolve("F.java")))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("exited");
  }

  @Test
  void format_exceedsTimeout_throws() {
    final var slow =
        new FileCommandFormatEngine(List.of("sleep", "5"), root, Duration.ofMillis(200));

    assertThatThrownBy(() -> slow.format("x", root.resolve("F.java")))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("timed out");
  }

  @Test
  void constructor_invalidComponents_throw() {
    assertThatThrownBy(() -> engine(List.of()))
        .as("empty command")
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () -> new FileCommandFormatEngine(List.of("mvn"), null, Duration.ofSeconds(1)))
        .as("null workspace root")
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> new FileCommandFormatEngine(List.of("mvn"), root, Duration.ZERO))
        .as("non-positive timeout")
        .isInstanceOf(ValidationException.class);
  }
}
