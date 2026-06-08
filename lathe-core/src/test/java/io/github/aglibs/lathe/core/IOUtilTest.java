package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

final class IOUtilTest {

  @Test
  void unchecked_supplierSucceeds_returnsValue() {
    assertThat(IOUtil.unchecked(() -> "ok")).isEqualTo("ok");
  }

  @Test
  void unchecked_supplierThrowsIOException_wrapsAsUnchecked() {
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
