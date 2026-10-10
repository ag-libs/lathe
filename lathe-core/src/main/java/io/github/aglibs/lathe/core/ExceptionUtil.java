package io.github.aglibs.lathe.core;

import java.util.Objects;
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
}
