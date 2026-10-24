package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheFlags;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LaunchSession {

  private static final Logger LOG = Logger.getLogger(LaunchSession.class.getName());

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
  // The tests running now, from the runner's markers on stdout; both drain threads read it.
  private final Set<String> runningTests = ConcurrentHashMap.newKeySet();

  LaunchSession(
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

    final var thread = new Thread(this::tailResults, "lathe-launch-events-" + process.pid());
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
            "lathe-launch-%s-%d".formatted(stream, process.pid()));
    thread.setDaemon(true);
    thread.start();
  }

  public long pid() {
    return process.pid();
  }

  public void cancel() {
    // What the JVM started (an embedded server, a forked tool) would outlive it, holding its ports
    // and files. Taken first: once the JVM is gone its children are no longer its descendants.
    final List<ProcessHandle> descendants = process.descendants().toList();
    descendants.forEach(ProcessHandle::destroy);
    process.destroy();
    process
        .onExit()
        .orTimeout(CANCEL_GRACE_MS, TimeUnit.MILLISECONDS)
        .whenComplete((exited, error) -> forceKillIfAlive(descendants));
  }

  private void forceKillIfAlive(final List<ProcessHandle> descendants) {
    descendants.forEach(ProcessHandle::destroyForcibly);
    if (process.isAlive()) {
      LOG.fine(() -> "[cancel] pid=%d force-kill (ignored SIGTERM)".formatted(process.pid()));
      process.destroyForcibly();
    }
  }

  /**
   * Waits for both process exit and output draining to complete, so the returned outcome's output
   * is always the full captured transcript, never a partial read racing the pipe close.
   */
  public CompletableFuture<LaunchOutcome> onExit() {
    final CompletableFuture<Void> drained =
        CompletableFuture.allOf(stdoutDrained, stderrDrained, tailerDone);
    return process
        .onExit()
        .thenCombine(
            drained,
            (exited, ignored) ->
                LaunchOutcome.completed(
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
        if (!trackTest(line)) {
          final var tagged = new TranscriptLine(stream, line, currentTest());
          output.add(tagged);
          onLine.accept(tagged);
        }
      }
    } catch (final IOException e) {
      LOG.log(
          Level.FINE, e, () -> "[launch] %s read failed pid=%d".formatted(stream, process.pid()));
    } finally {
      drained.complete(null);
    }
  }

  // A marker line moves a test in or out of the running set and is not output; any other line is.
  private boolean trackTest(final String line) {
    final Optional<String> started = markedTest(line, LatheFlags.TEST_STARTED_MARKER);
    started.ifPresent(runningTests::add);
    final Optional<String> finished = markedTest(line, LatheFlags.TEST_FINISHED_MARKER);
    finished.ifPresent(runningTests::remove);
    return started.isPresent() || finished.isPresent();
  }

  // The one test running, or null when none or several are (output then belongs to no single test).
  private String currentTest() {
    final List<String> running = List.copyOf(runningTests);
    return running.size() == 1 ? running.get(0) : null;
  }

  private static Optional<String> markedTest(final String line, final String marker) {
    if (!line.startsWith(marker)) {
      return Optional.empty();
    }

    final String[] parts = line.substring(marker.length()).split("\t", -1);
    return parts.length == 3
        ? Optional.of(TestId.positionId(parts[0], parts[1], parts[2]))
        : Optional.empty();
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
      LOG.log(Level.FINE, e, () -> "[launch] results read failed sink=%s".formatted(resultsSink));
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
      LOG.log(Level.FINE, e, () -> "[launch] results parse skipped a malformed record");
      return null;
    }
  }

  private static void deleteQuietly(final Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (final IOException e) {
      LOG.log(Level.FINE, e, () -> "[launch] results sink cleanup failed sink=%s".formatted(path));
    }
  }
}
