package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.server.ScanPace.QUIET_AFTER_MS;
import static io.github.aglibs.lathe.server.ScanPace.QUIET_TICKS;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.ScanPace.Tick;
import org.junit.jupiter.api.Test;

class ScanPaceTest {

  private static final long PAUSE_AFTER_MS = 5 * 60 * 1000;
  private static final long NEVER = 0;

  @Test
  void tick_requestOrChangeWithinAMinute_scansEveryTick_quietScansOneTickInFive() {
    assertThat(ScanPace.tick(0, QUIET_AFTER_MS, 0, NEVER)).isEqualTo(Tick.SCAN);
    assertThat(ScanPace.tick(QUIET_AFTER_MS, 0, 0, NEVER)).isEqualTo(Tick.SCAN);
    assertThat(ScanPace.tick(QUIET_AFTER_MS - 1, QUIET_AFTER_MS - 1, 0, NEVER))
        .isEqualTo(Tick.SCAN);

    assertThat(ScanPace.tick(QUIET_AFTER_MS, QUIET_AFTER_MS, 0, NEVER)).isEqualTo(Tick.SKIP);
    assertThat(ScanPace.tick(QUIET_AFTER_MS, QUIET_AFTER_MS, QUIET_TICKS - 2, NEVER))
        .isEqualTo(Tick.SKIP);
    assertThat(ScanPace.tick(QUIET_AFTER_MS, QUIET_AFTER_MS, QUIET_TICKS - 1, NEVER))
        .isEqualTo(Tick.SCAN);
  }

  @Test
  void tick_noRequestPastThePause_pauses_aChangeAloneDoesNotResume_neverPausesWithoutOne() {
    assertThat(ScanPace.tick(PAUSE_AFTER_MS, QUIET_AFTER_MS, 0, PAUSE_AFTER_MS))
        .isEqualTo(Tick.PAUSE);
    assertThat(ScanPace.tick(PAUSE_AFTER_MS, 0, 0, PAUSE_AFTER_MS)).isEqualTo(Tick.PAUSE);
    assertThat(ScanPace.tick(PAUSE_AFTER_MS - 1, QUIET_AFTER_MS, 0, PAUSE_AFTER_MS))
        .isEqualTo(Tick.SKIP);
    assertThat(ScanPace.tick(Long.MAX_VALUE, QUIET_AFTER_MS, QUIET_TICKS - 1, NEVER))
        .isEqualTo(Tick.SCAN);
  }

  @Test
  void next_pause_reportedOnceThenPaused_untilARequestResumesScanning() {
    final var pace = new ScanPace();
    pace.pauseAfter(PAUSE_AFTER_MS);

    assertThat(pace.next(0, false)).as("just started: a change").isEqualTo(Tick.SCAN);
    assertThat(pace.next(PAUSE_AFTER_MS, false)).isEqualTo(Tick.PAUSE);
    assertThat(pace.next(PAUSE_AFTER_MS, true)).isEqualTo(Tick.PAUSED);
    assertThat(pace.next(0, false)).isEqualTo(Tick.SCAN);
    assertThat(pace.next(PAUSE_AFTER_MS, false)).as("paused again").isEqualTo(Tick.PAUSE);
  }
}
