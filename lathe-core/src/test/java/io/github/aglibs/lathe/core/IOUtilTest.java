package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InterruptedIOException;
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

  // Interrupted, the wait ends at once as an IOException and the thread stays interrupted, so a
  // polling loop stops instead of spinning on a sleep that throws every time.
  @Test
  void sleep_interruptedOrNot_returnsOrEndsAsInterruptedIOExceptionKeepingTheFlag()
      throws Exception {
    IOUtil.sleep(1);
    assertThat(Thread.currentThread().isInterrupted()).isFalse();

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> IOUtil.sleep(60_000)).isInstanceOf(InterruptedIOException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
