package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.validcheck.ValidationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
  void format_returnsFormattedBuffer_withoutTouchingTheOpenFile() throws Exception {
    Files.writeString(root.resolve("pom.xml"), "<project/>");
    final Path file = root.resolve("Foo.java");
    Files.writeString(file, "on disk\n");
    final FileTime before = Files.getLastModifiedTime(file);
    // Fake in-place formatter: uppercase whatever is in the file (%FILE% -> the scratch temp).
    final var upper =
        engine(List.of("sh", "-c", "tr a-z A-Z < %FILE% > %FILE%.tmp && mv %FILE%.tmp %FILE%"));

    final String result = upper.format("buffer\n", file);

    assertThat(result)
        .isEqualTo("BUFFER\n"); // the buffer, formatted via a scratch file, is returned
    assertThat(Files.readString(file)).isEqualTo("on disk\n"); // the open file is never touched
    assertThat(Files.getLastModifiedTime(file)).isEqualTo(before); // nor its mtime
    assertThat(Files.list(root).map(Path::getFileName).map(Path::toString).toList())
        .containsExactlyInAnyOrder("pom.xml", "Foo.java"); // scratch temp removed
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
  void mavenExecutable_prefersMvndThenWrapperThenMvn() throws Exception {
    final Path binDir = root.resolve("bin");
    Files.createDirectories(binDir);
    final Path mvnd = binDir.resolve("mvnd");
    Files.writeString(mvnd, "#!/bin/sh\n");
    mvnd.toFile().setExecutable(true);

    // mvnd on PATH wins even when a wrapper exists.
    final Path wrapper = root.resolve("mvnw");
    Files.writeString(wrapper, "#!/bin/sh\n");
    wrapper.toFile().setExecutable(true);
    assertThat(FileCommandFormatEngine.mavenExecutable(root, binDir.toString())).isEqualTo("mvnd");

    // No mvnd on PATH: the project's ./mvnw wrapper.
    assertThat(FileCommandFormatEngine.mavenExecutable(root, ""))
        .isEqualTo(wrapper.toAbsolutePath().toString());

    // Neither: plain mvn.
    Files.delete(wrapper);
    assertThat(FileCommandFormatEngine.mavenExecutable(root, "")).isEqualTo("mvn");
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
