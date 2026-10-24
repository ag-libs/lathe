package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.TestProcess;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LauncherTest {

  private static final long TIMEOUT_SECONDS = 10;

  @Test
  void launch_shortCommandLine_runsItAsIs() throws Exception {
    final List<String> argv = TestProcess.command("echo", "out:hello");

    final List<TranscriptLine> transcript = launch(argv);

    assertThat(transcript)
        .containsExactly(
            command("config: test"),
            command(String.join(" ", argv)),
            new TranscriptLine(TranscriptLine.Stream.STDOUT, "hello"));
  }

  // Past Windows' command-line limit: the arguments after java move into an argfile, which java
  // expands, so the process still sees them all and the transcript names the argfile to rerun.
  @Test
  void launch_commandLinePastTheLimit_runsThroughAnArgfile() throws Exception {
    final List<String> command = TestProcess.command("echo", "out:hello");
    final String padding = "-Dlathe.test.padding=%s".formatted("x".repeat(40_000));
    final List<String> argv =
        Stream.concat(Stream.of(command.get(0), padding), command.stream().skip(1)).toList();

    final List<TranscriptLine> transcript = launch(argv);

    final String shown = transcript.get(1).text();
    assertThat(shown).startsWith("%s @".formatted(argv.get(0))).hasSizeLessThan(1_000);
    assertThat(Path.of(shown.substring(shown.indexOf('@') + 1))).exists();
    assertThat(transcript.get(2))
        .isEqualTo(new TranscriptLine(TranscriptLine.Stream.STDOUT, "hello"));
  }

  // A run never writes to the program's input, so a read of it ends instead of waiting forever.
  @Test
  void launch_programReadingStdin_getsEndOfInput() throws Exception {
    final List<TranscriptLine> transcript = launch(TestProcess.command("read"));

    assertThat(transcript).last().isEqualTo(new TranscriptLine(TranscriptLine.Stream.STDOUT, "-1"));
  }

  private static List<TranscriptLine> launch(final List<String> argv) throws Exception {
    final List<TranscriptLine> transcript = Collections.synchronizedList(new ArrayList<>());
    final LaunchSession session =
        Launcher.launch(argv, "test", null, transcript::add, result -> {}, Map.of(), null);
    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).exitCode()).isZero();
    return List.copyOf(transcript);
  }

  private static TranscriptLine command(final String text) {
    return new TranscriptLine(TranscriptLine.Stream.COMMAND, text);
  }
}
