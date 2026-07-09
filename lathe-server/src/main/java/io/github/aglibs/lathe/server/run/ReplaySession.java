package io.github.aglibs.lathe.server.run;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ReplaySession {

  private static final Logger LOG = Logger.getLogger(ReplaySession.class.getName());

  private final Process process;
  private final List<String> output = Collections.synchronizedList(new ArrayList<>());
  private final CompletableFuture<Void> outputDrained = new CompletableFuture<>();

  ReplaySession(final Process process) {
    this.process = process;
    final var reader = new Thread(this::drainOutput, "lathe-replay-output-" + process.pid());
    reader.setDaemon(true);
    reader.start();
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
    return process
        .onExit()
        .thenCombine(
            outputDrained,
            (exited, ignored) -> ReplayOutcome.completed(exited.exitValue(), List.copyOf(output)));
  }

  private void drainOutput() {
    try (var in =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = in.readLine()) != null) {
        output.add(line);
      }
    } catch (final IOException e) {
      LOG.log(Level.FINE, e, () -> "[replay] output read failed pid=%d".formatted(process.pid()));
    } finally {
      outputDrained.complete(null);
    }
  }
}
