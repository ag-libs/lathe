package io.github.aglibs.lathe.server.engine;

import io.github.aglibs.validcheck.ValidCheck;

/**
 * One failed test: its {@code Class#method} id, the exception summary, and the line ({@code -1} if
 * unknown).
 */
public record LatheTestFailure(String test, String summary, int line) {

  public LatheTestFailure {
    ValidCheck.check().notNull(test, "test").notNull(summary, "summary").validate();
  }
}
