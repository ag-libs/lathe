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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCommandFormatEngineTest {

  @TempDir private Path root;

  private FileCommandFormatEngine engine(final List<String> command) {
    return new FileCommandFormatEngine(command, root, FileCommandFormatEngine.DEFAULT_TIMEOUT);
  }

  @Test
  void format_returnsFormattedBuffer_withoutTouchingTheOpenFile() throws Exception {
    final Path file = root.resolve("Foo.java");
    Files.writeString(file, "on disk\n");
    final FileTime before = Files.getLastModifiedTime(file);
    // Fake in-place formatter: uppercase whatever is in the file (%FILE% -> the scratch temp).
    final var upper = engine(TestProcess.command("upper", "%FILE%"));

    final String result = upper.format("buffer\n", FormatterFixtures.target(file));

    assertThat(result)
        .isEqualTo("BUFFER\n"); // the buffer, formatted via a scratch file, is returned
    assertThat(Files.readString(file)).isEqualTo("on disk\n"); // the open file is never touched
    assertThat(Files.getLastModifiedTime(file)).isEqualTo(before); // nor its mtime
    assertThat(Files.list(root).map(Path::getFileName).map(Path::toString).toList())
        .containsExactly("Foo.java"); // scratch temp removed
  }

  @Test
  void format_scratchFileIsHiddenDotfile_soFileTreesDoNotFlashIt() throws Exception {
    final Path file = root.resolve("Foo.java");
    Files.writeString(file, "on disk\n");
    // The formatter sees %FILE% = the scratch path; echo its basename back so we can inspect it.
    final var echoName = engine(TestProcess.command("name", "%FILE%"));

    final String scratchName = echoName.format("buffer\n", FormatterFixtures.target(file)).strip();

    assertThat(scratchName).startsWith(".lathe-fmt-").endsWith(".java");
  }

  @Test
  void format_substitutesModule_fromTheTarget() throws Exception {
    final Path sub = Files.createDirectories(root.resolve("sub"));
    final var echoModule = engine(TestProcess.command("write", "%MODULE%", "%FILE%"));

    assertThat(echoModule.format("x", new FormatTarget(sub.resolve("Foo.java"), "sub", null)))
        .isEqualTo("sub");
    assertThat(echoModule.format("x", FormatterFixtures.target(root.resolve("Root.java"))))
        .isEqualTo(".");
  }

  @Test
  void format_nonZeroExit_throwsAndLeavesBufferUnchanged() {
    assertThatThrownBy(
            () ->
                engine(TestProcess.command("exit", "1"))
                    .format("x", FormatterFixtures.target(root.resolve("F.java"))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("exited");
  }

  @Test
  void format_exceedsTimeout_throws() {
    final var slow =
        new FileCommandFormatEngine(TestProcess.command("sleep"), root, Duration.ofMillis(200));

    assertThatThrownBy(() -> slow.format("x", FormatterFixtures.target(root.resolve("F.java"))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("timed out");
  }

  @Test
  void format_mvnToken_runsTheRecordedBuildTool_failingWhenNoneIsRecorded() throws Exception {
    final List<String> command =
        Stream.concat(
                Stream.of("%MVN%"), TestProcess.toolArgs("write", "by tool", "%FILE%").stream())
            .toList();
    final Path file = root.resolve("F.java");

    assertThat(engine(command).format("x", new FormatTarget(file, ".", TestProcess.java())))
        .isEqualTo("by tool");
    assertThatThrownBy(
            () -> engine(List.of("%MVN%", "%FILE%")).format("x", FormatterFixtures.target(file)))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("%MVN%");
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
