package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class ExceptionUtilTest {

  @Test
  void rootCause_wrappedAndUnwrapped_returnsInnermost() {
    final var missing = new IOException("palantir-java-format-parent.jar was not found");
    final var wrapped =
        new IllegalStateException("failed to resolve", new RuntimeException("collect", missing));
    final var plain = new IllegalArgumentException("no cause");

    assertThat(ExceptionUtil.rootCause(wrapped)).isSameAs(missing);
    assertThat(ExceptionUtil.rootCause(plain)).isSameAs(plain);
  }

  @Test
  void completionCauseAndAsCompletion_wrappedOrNot_unwrapOnceAndWrapOnce() {
    final var failure = new IOException("disk full");
    final var wrapped = new CompletionException(failure);

    assertThat(ExceptionUtil.completionCause(wrapped)).isSameAs(failure);
    assertThat(ExceptionUtil.completionCause(failure)).isSameAs(failure);
    assertThat(ExceptionUtil.asCompletion(wrapped)).isSameAs(wrapped);
    assertThat(ExceptionUtil.asCompletion(failure).getCause()).isSameAs(failure);
  }
}
