package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.validcheck.ValidationException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalCommandFormatEngineTest {

  private static String format(final List<String> command, final String source) throws Exception {
    return new ExternalCommandFormatEngine(
            command, ExternalCommandFormatEngine.DEFAULT_TIMEOUT, null)
        .format(source);
  }

  @Test
  void format_pipesBufferThroughCommand() throws Exception {
    assertThat(format(List.of("cat"), "class A {}\n")).isEqualTo("class A {}\n");
    assertThat(format(List.of("tr", "a-z", "A-Z"), "abc")).isEqualTo("ABC");
  }

  @Test
  void format_processFailure_throwsIOExceptionWithReason() {
    assertThatThrownBy(() -> format(List.of("false"), "x"))
        .as("non-zero exit")
        .isInstanceOf(IOException.class)
        .hasMessageContaining("exited");
    assertThatThrownBy(() -> format(List.of("lathe-no-such-command-zz"), "x"))
        .as("command not found carries the command")
        .isInstanceOf(IOException.class)
        .hasMessageContaining("lathe-no-such-command-zz");
    assertThatThrownBy(() -> format(List.of("true"), "nonempty"))
        .as("empty output")
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no output");
  }

  @Test
  void format_exceedsTimeout_throws() {
    final var engine =
        new ExternalCommandFormatEngine(List.of("sleep", "5"), Duration.ofMillis(200), null);

    assertThatThrownBy(() -> engine.format("x"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("timed out");
  }

  @Test
  void format_withWorkingDir_runsInThatDirectory(@TempDir final Path dir) throws Exception {
    final var engine =
        new ExternalCommandFormatEngine(
            List.of("pwd", "-P"), ExternalCommandFormatEngine.DEFAULT_TIMEOUT, dir);

    assertThat(engine.format("x").strip()).isEqualTo(dir.toRealPath().toString());
  }

  @Test
  void constructor_invalidComponents_throw() {
    assertThatThrownBy(
            () ->
                new ExternalCommandFormatEngine(
                    List.of(), ExternalCommandFormatEngine.DEFAULT_TIMEOUT, null))
        .as("empty command")
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> new ExternalCommandFormatEngine(List.of("cat"), Duration.ZERO, null))
        .as("non-positive timeout")
        .isInstanceOf(ValidationException.class);
  }
}
