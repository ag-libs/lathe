package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.Stopwatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressNotification;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

public final class ProgressReporter {

  private static final Logger LOG = Logger.getLogger(ProgressReporter.class.getName());
  private static final long REPORT_INTERVAL_MS = 200;

  private final LanguageClient client;
  private final long reportIntervalMs;
  private final AtomicLong nextToken = new AtomicLong();
  private final ConcurrentMap<Either<String, Integer>, CompletableFuture<?>> active =
      new ConcurrentHashMap<>();
  private volatile boolean supported;

  ProgressReporter(final LanguageClient client) {
    this(client, REPORT_INTERVAL_MS);
  }

  ProgressReporter(final LanguageClient client, final long reportIntervalMs) {
    this.client = client;
    this.reportIntervalMs = reportIntervalMs;
  }

  void setSupported(final boolean supported) {
    this.supported = supported;
  }

  Task open(final Either<String, Integer> requestedToken, final CompletableFuture<?> response) {
    final Either<String, Integer> token;
    final boolean createRequired;
    if (requestedToken != null) {
      token = requestedToken;
      createRequired = false;
    } else if (supported) {
      token = Either.forLeft("lathe-progress-%d".formatted(nextToken.incrementAndGet()));
      createRequired = true;
    } else {
      return new Task(this, null, response, false);
    }

    active.put(token, response);
    return new Task(this, token, response, createRequired);
  }

  void cancel(final Either<String, Integer> token) {
    final CompletableFuture<?> response = active.get(token);
    if (response != null) {
      response.cancel(false);
    }
  }

  private void remove(final Either<String, Integer> token, final CompletableFuture<?> response) {
    if (token != null) {
      active.remove(token, response);
    }
  }

  private void createProgress(final Either<String, Integer> token) {
    final var timer = Stopwatch.start();
    try {
      final CompletableFuture<Void> created =
          client.createProgress(new WorkDoneProgressCreateParams(token));
      if (created != null) {
        created.exceptionally(
            failure -> {
              LOG.log(
                  Level.WARNING,
                  failure,
                  () ->
                      "[progress] %s create %dms failed"
                          .formatted(tokenText(token), timer.elapsedMs()));
              return null;
            });
      }
    } catch (final RuntimeException failure) {
      LOG.log(
          Level.WARNING,
          failure,
          () -> "[progress] %s create %dms failed".formatted(tokenText(token), timer.elapsedMs()));
    }
  }

  private void notifyProgress(
      final Either<String, Integer> token, final WorkDoneProgressNotification value) {
    final var timer = Stopwatch.start();
    try {
      client.notifyProgress(new ProgressParams(token, Either.forLeft(value)));
    } catch (final RuntimeException failure) {
      LOG.log(
          Level.WARNING,
          failure,
          () -> "[progress] %s notify %dms failed".formatted(tokenText(token), timer.elapsedMs()));
    }
  }

  private static String tokenText(final Either<String, Integer> token) {
    return token.isLeft() ? token.getLeft() : String.valueOf(token.getRight());
  }

  public static final class Task {

    private enum State {
      PENDING,
      ACTIVE,
      ENDED
    }

    private final ProgressReporter reporter;
    private final Either<String, Integer> token;
    private final CompletableFuture<?> response;
    private final boolean createRequired;
    private final Stopwatch timer = Stopwatch.start();
    private State state = State.PENDING;
    private int total;
    private int completed;
    private int attributed;
    private int hits;
    private long lastReportMs;

    private Task(
        final ProgressReporter reporter,
        final Either<String, Integer> token,
        final CompletableFuture<?> response,
        final boolean createRequired) {
      this.reporter = reporter;
      this.token = token;
      this.response = response;
      this.createRequired = createRequired;
    }

    // Dispatch stays under this task lock to preserve begin/report/end order across module workers.
    public synchronized void begin(final String title, final int total) {
      if (token == null || state != State.PENDING) {
        return;
      }

      this.total = total;
      state = State.ACTIVE;
      if (createRequired) {
        reporter.createProgress(token);
      }
      final var begin = new WorkDoneProgressBegin();
      begin.setTitle(title);
      begin.setCancellable(true);
      begin.setMessage("%d / %d".formatted(completed, total));
      begin.setPercentage(percentage());
      reporter.notifyProgress(token, begin);
    }

    public synchronized void advance() {
      if (token == null || state == State.ENDED) {
        return;
      }

      completed++;
      report(() -> "%d / %d".formatted(completed, total));
    }

    public synchronized void advance(final boolean requiredAttribution, final int candidateHits) {
      if (token == null || state == State.ENDED) {
        return;
      }

      completed++;
      if (requiredAttribution) {
        attributed++;
      }
      hits += candidateHits;
      report(
          () ->
              "%d / %d candidates, attributed=%d, hits=%d"
                  .formatted(completed, total, attributed, hits));
    }

    private void report(final Supplier<String> message) {
      final long elapsedMs = timer.elapsedMs();
      if (state != State.ACTIVE || elapsedMs - lastReportMs < reporter.reportIntervalMs) {
        return;
      }

      lastReportMs = elapsedMs;
      final var progressReport = new WorkDoneProgressReport();
      progressReport.setCancellable(true);
      progressReport.setMessage(message.get());
      progressReport.setPercentage(percentage());
      reporter.notifyProgress(token, progressReport);
    }

    public synchronized void finish(final Throwable failure) {
      if (state == State.ENDED) {
        return;
      }

      final State previous = state;
      state = State.ENDED;
      try {
        if (token != null && previous == State.ACTIVE) {
          final var end = new WorkDoneProgressEnd();
          end.setMessage(outcome(failure));
          reporter.notifyProgress(token, end);
        }
      } finally {
        reporter.remove(token, response);
      }
    }

    private int percentage() {
      return total == 0 ? 100 : Math.min(100, completed * 100 / total);
    }

    private String outcome(final Throwable failure) {
      if (response.isCancelled() || cancellationCause(failure) != null) {
        return "Cancelled";
      }
      return failure == null ? "Completed" : "Failed";
    }
  }

  private static CancellationException cancellationCause(final Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof CancellationException cancellation) {
        return cancellation;
      }
      current = current.getCause();
    }
    return null;
  }
}
