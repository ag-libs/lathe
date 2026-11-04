package io.github.aglibs.lathe.server;

import static java.util.logging.Level.SEVERE;

import java.util.List;
import java.util.concurrent.CompletableFuture;
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
