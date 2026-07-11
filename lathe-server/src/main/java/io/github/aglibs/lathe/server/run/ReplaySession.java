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
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ReplaySession {

  private static final Logger LOG = Logger.getLogger(ReplaySession.class.getName());

  private final Process process;
  private final Path resultsSink;
  private final List<TranscriptLine> output = Collections.synchronizedList(new ArrayList<>());
  private final CompletableFuture<Void> stdoutDrained = new CompletableFuture<>();
  private final CompletableFuture<Void> stderrDrained = new CompletableFuture<>();

  ReplaySession(final Process process, final Path resultsSink) {
    this.process = process;
    this.resultsSink = resultsSink;
    startDrain(process.getInputStream(), TranscriptLine.Stream.STDOUT, stdoutDrained);
    startDrain(process.getErrorStream(), TranscriptLine.Stream.STDERR, stderrDrained);
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
  }

  /**
   * Waits for both process exit and output draining to complete, so the returned outcome's output
   * is always the full captured transcript, never a partial read racing the pipe close.
   */
  public CompletableFuture<ReplayOutcome> onExit() {
    final CompletableFuture<Void> drained = CompletableFuture.allOf(stdoutDrained, stderrDrained);
    return process
        .onExit()
        .thenCombine(
            drained,
            (exited, ignored) ->
                ReplayOutcome.completed(
                    exited.exitValue(), List.copyOf(output), readTestResults()));
  }

  private void drain(
      final InputStream in,
      final TranscriptLine.Stream stream,
      final CompletableFuture<Void> drained) {
    try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        output.add(new TranscriptLine(stream, line));
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
