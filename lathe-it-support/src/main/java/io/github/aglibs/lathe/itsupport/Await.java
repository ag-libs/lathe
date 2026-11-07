package io.github.aglibs.lathe.itsupport;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

// Polls a probe until its answer is the awaited one or the time is up, so a fixture's wait fails
// with what it last saw instead of hanging the build.
public final class Await {

  private static final Duration INTERVAL = Duration.ofMillis(100);

  private Await() {}

  public static <T> T until(
      final String what, final Duration timeout, final Callable<T> probe, final Predicate<T> done)
      throws Exception {
    final Instant deadline = Instant.now().plus(timeout);
    T last = probe.call();
    while (!done.test(last)) {
      if (Instant.now().isAfter(deadline)) {
        throw new AssertionError("%s not within %s; last: %s".formatted(what, timeout, last));
      }

      Thread.sleep(INTERVAL.toMillis());
      last = probe.call();
    }

    return last;
  }
}
