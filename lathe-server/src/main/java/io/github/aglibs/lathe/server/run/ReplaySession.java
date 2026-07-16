package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ReplaySession {

  private static final Logger LOG = Logger.getLogger(ReplaySession.class.getName());

  private static final long TAIL_POLL_MS = 25;

  // A JVM wedged in a tight loop or deadlock may ignore the graceful SIGTERM from destroy();
  // escalate to a forcible SIGKILL if it is still alive this long after cancel was requested.
  private static final long CANCEL_GRACE_MS = 2_000;

  private final Process process;
  private final Path resultsSink;
  private final Consumer<TranscriptLine> onLine;
  private final Consumer<TestResult> onResult;
  private final List<TranscriptLine> output = Collections.synchronizedList(new ArrayList<>());
  private final CompletableFuture<Void> stdoutDrained = new CompletableFuture<>();
  private final CompletableFuture<Void> stderrDrained = new CompletableFuture<>();
  private final CompletableFuture<Void> tailerDone = new CompletableFuture<>();

  ReplaySession(
      final Process process,
      final Path resultsSink,
      final Consumer<TranscriptLine> onLine,
      final Consumer<TestResult> onResult) {
    this.process = process;
    this.resultsSink = resultsSink;
    this.onLine = onLine;
    this.onResult = onResult;
    startDrain(process.getInputStream(), TranscriptLine.Stream.STDOUT, stdoutDrained);
    startDrain(process.getErrorStream(), TranscriptLine.Stream.STDERR, stderrDrained);
    startTailer();
  }

  private void startTailer() {
    if (resultsSink == null) {
      tailerDone.complete(null);
      return;
    }

    final var thread = new Thread(this::tailResults, "lathe-replay-events-" + process.pid());
    thread.setDaemon(true);
    thread.start();
  }

  private void startDrain(
      final InputStream in,
      final TranscriptLine.Stream stream,
      final CompletableFuture<Void> drained) {
    final var thread =
        new Thread(
            () -> drain(in, stream, drained),
            "lathe-replay-%s-%d".formatted(stream, process.pid()));
    thread.setDaemon(true);
    thread.start();
  }

  public long pid() {
    return process.pid();
  }

  public void cancel() {
    process.destroy();
    process
        .onExit()
        .orTimeout(CANCEL_GRACE_MS, TimeUnit.MILLISECONDS)
        .whenComplete((exited, error) -> forceKillIfAlive());
  }

  private void forceKillIfAlive() {
    if (process.isAlive()) {
      LOG.fine(() -> "[cancel] pid=%d force-kill (ignored SIGTERM)".formatted(process.pid()));
      process.destroyForcibly();
    }
  }

  /**
   * Waits for both process exit and output draining to complete, so the returned outcome's output
   * is always the full captured transcript, never a partial read racing the pipe close.
   */
  public CompletableFuture<ReplayOutcome> onExit() {
    final CompletableFuture<Void> drained =
        CompletableFuture.allOf(stdoutDrained, stderrDrained, tailerDone);
    return process
        .onExit()
        .thenCombine(
            drained,
            (exited, ignored) ->
                ReplayOutcome.completed(
                    exited.exitValue(), List.copyOf(output), readTestResults()));
  }

  // Best-effort live feed: re-read the sink each poll and emit records past the ones already sent,
  // so a position can be marked the moment its method finishes. Correctness is not on this path --
  // the authoritative testResults come from the whole-file read in onExit, which runs only after
  // this tailer completes (tailerDone) -- so a record missed here is still reconciled at the end.
  private void tailResults() {
    int emitted = 0;
    try {
      while (process.isAlive()) {
        emitted = emitNewResults(emitted);
        Thread.sleep(TAIL_POLL_MS);
      }

      emitNewResults(emitted);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      tailerDone.complete(null);
    }
  }

  private int emitNewResults(final int alreadyEmitted) {
    if (!Files.exists(resultsSink)) {
      return alreadyEmitted;
    }

    final List<String> lines;
    try {
      lines = Files.readAllLines(resultsSink, StandardCharsets.UTF_8);
    } catch (final IOException e) {
      return alreadyEmitted;
    }

    for (int index = alreadyEmitted; index < lines.size(); index++) {
      final TestResult parsed = parse(lines.get(index));
      if (parsed != null) {
        onResult.accept(parsed);
      }
    }

    return lines.size();
  }

  private void drain(
      final InputStream in,
      final TranscriptLine.Stream stream,
      final CompletableFuture<Void> drained) {
    try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        final var tagged = new TranscriptLine(stream, line);
        output.add(tagged);
        onLine.accept(tagged);
      }
    } catch (final IOException e) {
      LOG.log(
          Level.FINE, e, () -> "[replay] %s read failed pid=%d".formatted(stream, process.pid()));
    } finally {
      drained.complete(null);
    }
  }

  private List<TestResult> readTestResults() {
    if (resultsSink == null || !Files.exists(resultsSink)) {
      return List.of();
    }

    try {
      final var results = new ArrayList<TestResult>();
      for (final String line : Files.readAllLines(resultsSink, StandardCharsets.UTF_8)) {
        final TestResult parsed = parse(line);
        if (parsed != null) {
          results.add(parsed);
        }
      }

      return List.copyOf(results);
    } catch (final IOException e) {
      LOG.log(Level.FINE, e, () -> "[replay] results read failed sink=%s".formatted(resultsSink));
      return List.of();
    } finally {
      deleteQuietly(resultsSink);
    }
  }

  private static TestResult parse(final String line) {
    if (line.isBlank()) {
      return null;
    }

    try {
      return Json.fromJson(line, TestResult.class);
    } catch (final RuntimeException e) {
      LOG.log(Level.FINE, e, () -> "[replay] results parse skipped a malformed record");
      return null;
    }
  }

  private static void deleteQuietly(final Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (final IOException e) {
      LOG.log(Level.FINE, e, () -> "[replay] results sink cleanup failed sink=%s".formatted(path));
    }
  }
}
