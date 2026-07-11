package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReplaySessionTest {

  private static final long TIMEOUT_SECONDS = 5;
  private static final Consumer<TranscriptLine> NO_STREAM = line -> {};

  private static ReplaySession replaySession(final Process process, final Path resultsSink) {
    return new ReplaySession(process, resultsSink, NO_STREAM);
  }

  @Test
  void onExit_processExitsZero_completesWithZero()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final var session = replaySession(new ProcessBuilder("true").start(), null);

    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).exitCode()).isZero();
  }

  @Test
  void onExit_processExitsNonZero_completesWithExitCode()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final var session = replaySession(new ProcessBuilder("false").start(), null);

    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).exitCode()).isEqualTo(1);
  }

  @Test
  void onExit_processPrintsStdout_capturesLinesTaggedStdout()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final var session =
        replaySession(new ProcessBuilder("sh", "-c", "echo one; echo two").start(), null);

    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).output())
        .containsExactly(
            new TranscriptLine(TranscriptLine.Stream.STDOUT, "one"),
            new TranscriptLine(TranscriptLine.Stream.STDOUT, "two"));
  }

  @Test
  void onExit_processPrintsStderr_capturesLinesTaggedStderr()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final var session =
        replaySession(new ProcessBuilder("sh", "-c", "echo err 1>&2").start(), null);

    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).output())
        .containsExactly(new TranscriptLine(TranscriptLine.Stream.STDERR, "err"));
  }

  @Test
  void constructor_processPrintsToBothStreams_streamsEachLineTaggedToConsumer()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final List<TranscriptLine> streamed = Collections.synchronizedList(new ArrayList<>());
    final var session =
        new ReplaySession(
            new ProcessBuilder("sh", "-c", "echo out; echo err 1>&2").start(), null, streamed::add);

    session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    assertThat(streamed)
        .containsExactlyInAnyOrder(
            new TranscriptLine(TranscriptLine.Stream.STDOUT, "out"),
            new TranscriptLine(TranscriptLine.Stream.STDERR, "err"));
  }

  @Test
  void onExit_resultsSinkWithRecords_parsesAndDeletesSink(@TempDir final Path dir)
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final Path sink = dir.resolve("results.ndjson");
    Files.writeString(
        sink,
        """
        {"className":"pkg.FooTest","methodName":"passes","methodParameterTypes":"","status":"passed","failureMessage":"","failureLine":-1}
        {"className":"pkg.FooTest","methodName":"fails","methodParameterTypes":"","status":"failed","failureMessage":"boom","failureLine":12}
        """,
        StandardCharsets.UTF_8);
    final var session = replaySession(new ProcessBuilder("true").start(), sink);

    final ReplayOutcome outcome = session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    assertThat(outcome.testResults()).hasSize(2);
    assertThat(outcome.testResults())
        .anySatisfy(
            r -> {
              assertThat(r.methodName()).isEqualTo("fails");
              assertThat(r.status()).isEqualTo("failed");
              assertThat(r.failureMessage()).isEqualTo("boom");
              assertThat(r.failureLine()).isEqualTo(12);
              // positionId is absent from the sink NDJSON; it must be derived server-side while
              // deserializing, which only happens if Gson runs the record's compact constructor.
              assertThat(r.positionId()).isEqualTo("pkg.FooTest#fails()");
            });
    assertThat(Files.exists(sink)).isFalse();
  }

  @Test
  void onExit_malformedRecordLine_skipsItAndKeepsValidRecords(@TempDir final Path dir)
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final Path sink = dir.resolve("results.ndjson");
    Files.writeString(
        sink,
        """
        not json at all
        {"className":"pkg.FooTest","methodName":"passes","methodParameterTypes":"","status":"passed","failureMessage":"","failureLine":-1}
        """,
        StandardCharsets.UTF_8);
    final var session = replaySession(new ProcessBuilder("true").start(), sink);

    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).testResults()).hasSize(1);
  }

  @Test
  void onExit_missingResultsSink_returnsEmptyTestResults(@TempDir final Path dir)
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final var session =
        replaySession(new ProcessBuilder("true").start(), dir.resolve("absent.ndjson"));

    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).testResults()).isEmpty();
  }

  @Test
  void cancel_runningProcess_completesOnExitNonZero()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final var session = replaySession(new ProcessBuilder("sleep", "30").start(), null);

    session.cancel();

    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).exitCode()).isNotZero();
  }

  @Test
  void pid_startedProcess_returnsPositivePid() throws IOException {
    final var session = replaySession(new ProcessBuilder("sleep", "0").start(), null);

    assertThat(session.pid()).isPositive();
  }
}
