package io.github.aglibs.lathe.core;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

public final class ExceptionUtil {

  private ExceptionUtil() {}

  // The innermost cause, usually the one that says what actually went wrong (a missing artifact
  // rather than the resolver's wrapper around it); error itself when it has no cause.
  public static Throwable rootCause(final Throwable error) {
    return Stream.iterate(error, Objects::nonNull, Throwable::getCause)
        .reduce((outer, inner) -> inner)
        .orElseThrow();
  }

  // The failure a future's callback sees, without the CompletionException a dependent stage wraps
  // it in: the exception that actually ended the work.
  public static Throwable completionCause(final Throwable failure) {
    return failure instanceof CompletionException && failure.getCause() != null
        ? failure.getCause()
        : failure;
  }

  // The failure as a dependent stage reports it, wrapped once in a CompletionException, so a
  // handler downstream sees the same exception whichever stage failed.
  public static CompletionException asCompletion(final Throwable failure) {
    return failure instanceof final CompletionException completion
        ? completion
        : new CompletionException(failure);
  }
}
