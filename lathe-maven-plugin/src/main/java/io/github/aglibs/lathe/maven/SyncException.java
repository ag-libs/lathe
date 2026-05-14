package io.github.aglibs.lathe.maven;

import java.io.Serial;

public final class SyncException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public SyncException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
