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
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileCommandFormatEngineTest {

  private static final Function<Path, Optional<String>> NO_MODULE = file -> Optional.empty();
  private static final Supplier<Optional<Path>> NO_TOOL = Optional::empty;

  @TempDir private Path root;

  private FileCommandFormatEngine engine(final List<String> command) {
    return new FileCommandFormatEngine(
        command, root, FileCommandFormatEngine.DEFAULT_TIMEOUT, NO_MODULE, NO_TOOL);
  }

  @Test
  void format_returnsFormattedBuffer_withoutTouchingTheOpenFile() throws Exception {
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
        .containsExactly("Foo.java"); // scratch temp removed
  }

  @Test
  void format_scratchFileIsHiddenDotfile_soFileTreesDoNotFlashIt() throws Exception {
    final Path file = root.resolve("Foo.java");
    Files.writeString(file, "on disk\n");
    // The formatter sees %FILE% = the scratch path; echo its basename back so we can inspect it.
    final var echoName = engine(List.of("sh", "-c", "basename %FILE% > %FILE%"));

    final String scratchName = echoName.format("buffer\n", file).strip();

    assertThat(scratchName).startsWith(".lathe-fmt-").endsWith(".java");
  }

  @Test
  void format_substitutesModule_fromLookupOrDotWhenNone() throws Exception {
    final Path sub = root.resolve("sub");
    Files.createDirectories(sub);
    final var echoModule =
        new FileCommandFormatEngine(
            List.of("sh", "-c", "printf %MODULE% > %FILE%"),
            root,
            FileCommandFormatEngine.DEFAULT_TIMEOUT,
            file -> file.startsWith(sub) ? Optional.of("sub") : Optional.empty(),
            NO_TOOL);

    assertThat(echoModule.format("x", sub.resolve("Foo.java"))).isEqualTo("sub");
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
        new FileCommandFormatEngine(
            List.of("sleep", "5"), root, Duration.ofMillis(200), NO_MODULE, NO_TOOL);

    assertThatThrownBy(() -> slow.format("x", root.resolve("F.java")))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("timed out");
  }

  @Test
  void format_mvnToken_runsTheRecordedBuildTool_failingWhenNoneIsRecorded() throws Exception {
    final Path tool = root.resolve("tool");
    Files.writeString(tool, "#!/bin/sh\nprintf 'by tool' > \"$1\"\n");
    assertThat(tool.toFile().setExecutable(true)).isTrue();
    final var recorded =
        new FileCommandFormatEngine(
            List.of("%MVN%", "%FILE%"),
            root,
            FileCommandFormatEngine.DEFAULT_TIMEOUT,
            NO_MODULE,
            () -> Optional.of(tool));

    assertThat(recorded.format("x", root.resolve("F.java"))).isEqualTo("by tool");
    assertThatThrownBy(() -> engine(List.of("%MVN%", "%FILE%")).format("x", root.resolve("F.java")))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("%MVN%");
  }

  @Test
  void constructor_invalidComponents_throw() {
    assertThatThrownBy(() -> engine(List.of()))
        .as("empty command")
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                new FileCommandFormatEngine(
                    List.of("mvn"), null, Duration.ofSeconds(1), NO_MODULE, NO_TOOL))
        .as("null workspace root")
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () ->
                new FileCommandFormatEngine(
                    List.of("mvn"), root, Duration.ZERO, NO_MODULE, NO_TOOL))
        .as("non-positive timeout")
        .isInstanceOf(ValidationException.class);
  }
}
