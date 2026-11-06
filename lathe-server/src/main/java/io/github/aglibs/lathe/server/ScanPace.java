package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.Stopwatch;

// How often the change scan runs. Every tick while the server is in use; once no request has come
// in and no change been seen for a while, one tick in QUIET_TICKS, so an idle editor's server
// barely wakes. A server whose every query scans first (MCP) may stop scanning once long unused.
// Loop-confined, like the session that owns it.
final class ScanPace {

  enum Tick {
    SCAN,
    SKIP,
    // The first tick of a pause, then PAUSED until a request comes in.
    PAUSE,
    PAUSED
  }

  static final long QUIET_AFTER_MS = 60_000;
  static final int QUIET_TICKS = 5;

  // Zero: never pause.
  private long pauseAfterMs;
  private Stopwatch lastChange = Stopwatch.start();
  private int skipped;
  private boolean paused;

  void pauseAfter(final long ms) {
    pauseAfterMs = ms;
  }

  // A change was seen: back to every tick, so the next one catches the rest of a burst of edits.
  void changed() {
    lastChange = Stopwatch.start();
  }

  // idleMs: since the last request. settling: the last scan saw a change still to settle, which the
  // next tick writes.
  Tick next(final long idleMs, final boolean settling) {
    if (settling) {
      changed();
    }

    final Tick tick = tick(idleMs, lastChange.elapsedMs(), skipped, pauseAfterMs);
    if (tick == Tick.PAUSE) {
      final Tick pause = paused ? Tick.PAUSED : Tick.PAUSE;
      paused = true;
      return pause;
    }

    paused = false;
    skipped = tick == Tick.SKIP ? skipped + 1 : 0;
    return tick;
  }

  static Tick tick(
      final long idleMs, final long sinceChangeMs, final int skipped, final long pauseAfterMs) {
    if (pauseAfterMs > 0 && idleMs >= pauseAfterMs) {
      return Tick.PAUSE;
    }

    final boolean quiet = Math.min(idleMs, sinceChangeMs) >= QUIET_AFTER_MS;
    return !quiet || skipped + 1 >= QUIET_TICKS ? Tick.SCAN : Tick.SKIP;
  }
}
