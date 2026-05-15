package io.github.aglibs.lathe.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

final class Debouncer {

  private static final Logger LOG = Logger.getLogger(Debouncer.class.getName());

  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            final var t = new Thread(r, "lathe-debouncer");
            t.setDaemon(true);
            return t;
          });
  private final ConcurrentHashMap<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

  void schedule(final String uri, final long delayMs, final Runnable task) {
    doCancel(uri, true);
    final var future =
        executor.schedule(
            () -> {
              pending.remove(uri);
              task.run();
            },
            delayMs,
            TimeUnit.MILLISECONDS);
    pending.put(uri, future);
  }

  void cancel(final String uri) {
    doCancel(uri, false);
  }

  void submit(final Runnable task) {
    executor.submit(task);
  }

  void close() {
    executor.shutdownNow();
  }

  private void doCancel(final String uri, final boolean mayInterrupt) {
    final var previous = pending.remove(uri);
    if (previous != null) {
      previous.cancel(mayInterrupt);
      LOG.fine(() -> "[debouncer] cancelled %s".formatted(uri));
    }
  }
}
