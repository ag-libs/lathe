package io.github.aglibs.lathe.server.debug;

import java.io.Serial;

/**
 * A debugger expression could not be evaluated — an unresolved symbol, an unsupported construct, or
 * a debuggee-side failure. Carries a message the adapter surfaces to the user; it never crashes the
 * session (fail-soft, per the evaluation design).
 */
final class EvaluationException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  EvaluationException(final String message) {
    super(message);
  }

  EvaluationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
