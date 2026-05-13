package io.github.aglibs.lathe.maven;

import java.io.Serial;

final class SyncException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  SyncException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
