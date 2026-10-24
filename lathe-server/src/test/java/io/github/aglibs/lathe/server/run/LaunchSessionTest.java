package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.TestProcess;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class LaunchSessionTest {

  private static final long TIMEOUT_SECONDS = 5;
  private static final Consumer<TranscriptLine> NO_STREAM = line -> {};
  private static final Consumer<TestResult> NO_EVENTS = result -> {};
  // As the runner writes them: a pass, a failure with values and a frame, and a class's own
  // failure.
  private static final String PASSES =
      """
      {"className":"pkg.FooTest","methodName":"passes","methodParameterTypes":"","status":"passed",\
      "failureMessage":"","failureLine":-1,"durationMs":3,"displayName":"passes()","stackTrace":[]}
      """;
  private static final String RECORDS =
      PASSES
          + """
          {"className":"pkg.FooTest","methodName":"fails","methodParameterTypes":"","status":"failed",\
          "failureMessage":"boom","failureLine":12,"durationMs":5,"displayName":"fails()",\
          "expected":"a\\nb","actual":"a\\nc","stackTrace":[{"className":"pkg.FooTest",\
          "methodName":"fails","fileName":"FooTest.java","line":12}]}
          {"className":"pkg.BrokenTest","methodName":"","methodParameterTypes":"","status":"failed",\
          "failureMessage":"setup exploded","failureLine":9,"durationMs":1,"displayName":"BrokenTest",\
          "stackTrace":[]}
          """;

  private static Process start(final String... args) throws IOException {
    return new ProcessBuilder(TestProcess.command(args)).start();
  }

  private static LaunchSession launchSession(final Process process, final Path resultsSink) {
    return new LaunchSession(process, resultsSink, NO_STREAM, NO_EVENTS);
  }

  @Test
  void onExit_processExitsZero_completesWithZero()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final var session = launchSession(start("exit", "0"), null);

    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).exitCode()).isZero();
  }

  @Test
  void onExit_processExitsNonZero_completesWithExitCode()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final var session = launchSession(start("exit", "1"), null);

    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).exitCode()).isEqualTo(1);
  }

  @Test
  void onExit_processPrintsStdout_capturesLinesTaggedStdout()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final var session = launchSession(start("echo", "out:one", "out:two"), null);

    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).output())
        .containsExactly(
            new TranscriptLine(TranscriptLine.Stream.STDOUT, "one"),
            new TranscriptLine(TranscriptLine.Stream.STDOUT, "two"));
  }

  @Test
  void onExit_processPrintsStderr_capturesLinesTaggedStderr()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final var session = launchSession(start("echo", "err:err"), null);

    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).output())
        .containsExactly(new TranscriptLine(TranscriptLine.Stream.STDERR, "err"));
  }

  @Test
  void constructor_processPrintsToBothStreams_streamsEachLineTaggedToConsumer()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final List<TranscriptLine> streamed = Collections.synchronizedList(new ArrayList<>());
    final var session =
        new LaunchSession(start("echo", "out:out", "err:err"), null, streamed::add, NO_EVENTS);

    session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    assertThat(streamed)
        .containsExactlyInAnyOrder(
            new TranscriptLine(TranscriptLine.Stream.STDOUT, "out"),
            new TranscriptLine(TranscriptLine.Stream.STDERR, "err"));
  }

  @Test
  void onExit_resultsSinkWithRecords_streamsEachToTheResultConsumer(@TempDir final Path dir)
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final Path sink = dir.resolve("results.ndjson");
    Files.writeString(sink, RECORDS, StandardCharsets.UTF_8);
    final List<TestResult> streamed = Collections.synchronizedList(new ArrayList<>());
    final var session = new LaunchSession(start("exit", "0"), sink, NO_STREAM, streamed::add);

    session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    assertThat(streamed)
        .extracting(TestResult::positionId)
        .containsExactly("pkg.FooTest#passes()", "pkg.FooTest#fails()", "pkg.BrokenTest");
  }

  @Test
  void onExit_resultsSinkWithRecords_parsesAndDeletesSink(@TempDir final Path dir)
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final Path sink = dir.resolve("results.ndjson");
    Files.writeString(sink, RECORDS, StandardCharsets.UTF_8);
    final var session = launchSession(start("exit", "0"), sink);

    final LaunchOutcome outcome = session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    assertThat(outcome.testResults()).hasSize(3);
    assertThat(outcome.testResults())
        .anySatisfy(
            r -> {
              assertThat(r.methodName()).isEqualTo("fails");
              assertThat(r.status()).isEqualTo("failed");
              assertThat(r.failureMessage()).isEqualTo("boom");
              assertThat(r.failureLine()).isEqualTo(12);
              assertThat(r.durationMs()).isEqualTo(5);
              assertThat(r.displayName()).isEqualTo("fails()");
              assertThat(r.expected()).isEqualTo("a\nb");
              assertThat(r.actual()).isEqualTo("a\nc");
              assertThat(r.stackTrace())
                  .containsExactly(new StackFrame("pkg.FooTest", "fails", "FooTest.java", 12));
              // positionId is absent from the sink NDJSON; it must be derived server-side while
              // deserializing, which only happens if Gson runs the record's compact constructor.
              assertThat(r.positionId()).isEqualTo("pkg.FooTest#fails()");
            })
        .anySatisfy(
            r -> {
              assertThat(r.expected()).isNull();
              assertThat(r.positionId()).isEqualTo("pkg.FooTest#passes()");
            })
        .anySatisfy(r -> assertThat(r.positionId()).isEqualTo("pkg.BrokenTest"));
    assertThat(Files.exists(sink)).isFalse();
  }

  @Test
  void onExit_malformedRecordLine_skipsItAndKeepsValidRecords(@TempDir final Path dir)
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final Path sink = dir.resolve("results.ndjson");
    Files.writeString(sink, "not json at all\n" + PASSES, StandardCharsets.UTF_8);
    final var session = launchSession(start("exit", "0"), sink);

    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).testResults()).hasSize(1);
  }

  @Test
  void onExit_missingResultsSink_returnsEmptyTestResults(@TempDir final Path dir)
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final var session = launchSession(start("exit", "0"), dir.resolve("absent.ndjson"));

    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).testResults()).isEmpty();
  }

  @Test
  void cancel_runningProcess_completesOnExitNonZero()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final var session = launchSession(start("sleep"), null);

    session.cancel();

    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).exitCode()).isNotZero();
  }

  // The runner's markers say which test is running; they are not output, and each line carries the
  // test it belongs to, or none outside a test or while two run at once.
  @Test
  void onExit_markedOutput_tagsEachLineWithTheTestRunningThen() throws Exception {
    final List<TranscriptLine> streamed = Collections.synchronizedList(new ArrayList<>());
    final var session = new LaunchSession(start("marked"), null, streamed::add, NO_EVENTS);

    final LaunchOutcome outcome = session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    final var expected =
        List.of(
            new TranscriptLine(TranscriptLine.Stream.STDOUT, "in a", "pkg.T#a()"),
            new TranscriptLine(TranscriptLine.Stream.STDOUT, "between"),
            new TranscriptLine(TranscriptLine.Stream.STDOUT, "in both"));
    assertThat(outcome.output()).containsExactlyElementsOf(expected);
    assertThat(streamed).containsExactlyElementsOf(expected);
  }

  // What the JVM started would otherwise outlive the cancel, holding its ports and files.
  @Test
  void cancel_processWithChild_stopsTheChildToo() throws Exception {
    final var lines = new LinkedBlockingQueue<String>();
    final var session =
        new LaunchSession(start("spawn"), null, line -> lines.add(line.text()), NO_EVENTS);
    final ProcessHandle child =
        ProcessHandle.of(Long.parseLong(lines.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)))
            .orElseThrow();

    session.cancel();

    // The child sleeps 5 s by itself, so ending within 2 s means the cancel stopped it.
    assertThat(child.onExit().get(2, TimeUnit.SECONDS).isAlive()).isFalse();
  }

  // Windows has no SIGTERM: destroy() always kills there, so there is no grace to escalate from.
  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "no SIGTERM on Windows")
  void cancel_processIgnoringSigterm_forceKillsAfterGrace()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    // `trap '' TERM` makes the shell ignore the graceful SIGTERM from destroy(); only the escalated
    // SIGKILL (destroyForcibly) can stop it, so onExit resolving proves the escalation fired.
    // `read` is a shell builtin (no forked child to inherit and hold the stdout/stderr pipes open),
    // so killing the shell closes them and the drains complete.
    final var session =
        launchSession(new ProcessBuilder("sh", "-c", "trap '' TERM; read _").start(), null);

    session.cancel();

    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).exitCode()).isNotZero();
  }

  @Test
  void pid_startedProcess_returnsPositivePid() throws IOException {
    final var session = launchSession(start("exit", "0"), null);

    assertThat(session.pid()).isPositive();
  }
}
