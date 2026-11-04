package io.github.aglibs.lathe.server;

import static java.util.logging.Level.SEVERE;

import io.github.aglibs.lathe.core.Stopwatch;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// The shapes a feature's answer takes on its way to the client, shared by the session and its
// components.
final class FeatureAnswers {

  private static final Logger LOG = Logger.getLogger(FeatureAnswers.class.getName());

  private FeatureAnswers() {}

  // A failed feature answers with its fallback; the failure is logged.
  static <T> T logAndReturn(final Throwable ex, final String msg, final T fallback) {
    LOG.log(SEVERE, ex, () -> msg);
    return fallback;
  }

  // The same, as the exceptionally step of a feature on a file: the log names the feature and file.
  static <T> Function<Throwable, T> failedFor(
      final String operation, final String uri, final T fallback) {
    return ex -> logAndReturn(ex, "[%s] failed for %s".formatted(operation, uri), fallback);
  }

  // A feature's answer passed on unchanged, logged with how many items it holds and how long it
  // took;
  // the caller's logger names the line.
  static <C extends Collection<?>> Function<C, C> logged(
      final Logger log,
      final String operation,
      final String subject,
      final Stopwatch t,
      final String noun) {
    return items -> {
      log.fine(
          () ->
              "[%s] %s %dms %s=%d"
                  .formatted(operation, subject, t.elapsedMs(), noun, items.size()));
      return items;
    };
  }

  static Either<List<? extends Location>, List<? extends LocationLink>> definitionResult(
      final List<? extends Location> locations) {
    return Either.forLeft(locations);
  }

  // One answer assembled from partial answers: every list, in the order of the futures.
  static <T> CompletableFuture<List<T>> concatAll(
      final Stream<CompletableFuture<List<T>>> futures) {
    return futures.reduce(
        CompletableFuture.completedFuture(List.of()),
        (left, right) ->
            left.thenCombine(
                right, (first, second) -> Stream.concat(first.stream(), second.stream()).toList()));
  }
}
