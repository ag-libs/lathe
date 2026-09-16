package io.github.aglibs.lathe.server.debug;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.jdi.VMDisconnectedException;
import java.util.logging.Filter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

final class DebugLogNoiseTest {

  @Test
  void filter_vmDisconnectedRecord_isDropped() {
    final var record = new LogRecord(Level.SEVERE, "Exception on recording event");
    record.setThrown(new VMDisconnectedException());

    assertThat(DebugLogNoise.filter(null).isLoggable(record)).isFalse();
  }

  @Test
  void filter_unrelatedRecords_areKept() {
    final var plain = new LogRecord(Level.INFO, "session usage data summary");
    final var otherError = new LogRecord(Level.SEVERE, "boom");
    otherError.setThrown(new IllegalStateException("boom"));

    final Filter filter = DebugLogNoise.filter(null);
    assertThat(filter.isLoggable(plain)).isTrue();
    assertThat(filter.isLoggable(otherError)).isTrue();
  }

  @Test
  void filter_composesWithPreviousFilter() {
    // A previous filter that drops everything still drops; the disconnect filter never re-admits.
    final Filter dropAll = record -> false;
    final var plain = new LogRecord(Level.INFO, "kept by default");

    assertThat(DebugLogNoise.filter(dropAll).isLoggable(plain)).isFalse();
    assertThat(DebugLogNoise.filter(null).isLoggable(plain)).isTrue();
  }
}
