package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

final class IOUtilTest {

  @Test
  void uncheckedSupplierReturnsValue() {
    assertThat(IOUtil.unchecked(() -> "ok")).isEqualTo("ok");
  }

  @Test
  void uncheckedWrapsIOException() {
    assertThatThrownBy(
            () ->
                IOUtil.unchecked(
                    () -> {
                      throw new IOException("boom");
                    }))
        .isInstanceOf(UncheckedIOException.class)
        .hasCauseInstanceOf(IOException.class);
  }
}
