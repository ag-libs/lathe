package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.Stopwatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
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

final class ReferenceProgressReporter {

  private static final Logger LOG = Logger.getLogger(ReferenceProgressReporter.class.getName());
  private static final long REPORT_INTERVAL_MS = 200;

  private final LanguageClient client;
  private final long reportIntervalMs;
  private final AtomicLong nextToken = new AtomicLong();
  private final ConcurrentMap<Either<String, Integer>, CompletableFuture<?>> active =
      new ConcurrentHashMap<>();
  private volatile boolean supported;

  ReferenceProgressReporter(final LanguageClient client) {
    this(client, REPORT_INTERVAL_MS);
  }

  ReferenceProgressReporter(final LanguageClient client, final long reportIntervalMs) {
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
      token = Either.forLeft("lathe-references-%d".formatted(nextToken.incrementAndGet()));
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
                      "[references-progress] %s create %dms failed"
                          .formatted(tokenText(token), timer.elapsedMs()));
              return null;
            });
      }
    } catch (final RuntimeException failure) {
      LOG.log(
          Level.WARNING,
          failure,
          () ->
              "[references-progress] %s create %dms failed"
                  .formatted(tokenText(token), timer.elapsedMs()));
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
          () ->
              "[references-progress] %s notify %dms failed"
                  .formatted(tokenText(token), timer.elapsedMs()));
    }
  }

  private static String tokenText(final Either<String, Integer> token) {
    return token.isLeft() ? token.getLeft() : String.valueOf(token.getRight());
  }

  static final class Task {

    private enum State {
      PENDING,
      ACTIVE,
      ENDED
    }

    private final ReferenceProgressReporter reporter;
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
        final ReferenceProgressReporter reporter,
        final Either<String, Integer> token,
        final CompletableFuture<?> response,
        final boolean createRequired) {
      this.reporter = reporter;
      this.token = token;
      this.response = response;
      this.createRequired = createRequired;
    }

    // Dispatch stays under this task lock to preserve begin/report/end order across module workers.
    synchronized void begin(final String target, final int total) {
      if (token == null || state != State.PENDING) {
        return;
      }

      this.total = total;
      state = State.ACTIVE;
      if (createRequired) {
        reporter.createProgress(token);
      }
      final var begin = new WorkDoneProgressBegin();
      begin.setTitle("Finding references to %s".formatted(target));
      begin.setCancellable(true);
      begin.setMessage(message());
      begin.setPercentage(percentage());
      reporter.notifyProgress(token, begin);
    }

    synchronized void advance(final boolean requiredAttribution, final int candidateHits) {
      if (token == null || state == State.ENDED) {
        return;
      }

      completed++;
      if (requiredAttribution) {
        attributed++;
      }
      hits += candidateHits;

      final long elapsedMs = timer.elapsedMs();
      if (state != State.ACTIVE || elapsedMs - lastReportMs < reporter.reportIntervalMs) {
        return;
      }

      lastReportMs = elapsedMs;
      final var report = new WorkDoneProgressReport();
      report.setCancellable(true);
      report.setMessage(message());
      report.setPercentage(percentage());
      reporter.notifyProgress(token, report);
    }

    synchronized void finish(final Throwable failure) {
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

    private String message() {
      return "%d / %d candidates, attributed=%d, hits=%d"
          .formatted(completed, total, attributed, hits);
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
