package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
}
