package io.github.aglibs.lathe.server;

import io.github.aglibs.lathe.core.IOUtil;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

final class ServerEventLoop {

  private static final Logger LOG = Logger.getLogger(ServerEventLoop.class.getName());

  private final AtomicReference<Thread> workerThread = new AtomicReference<>();
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            final var t = new Thread(r, "lathe-worker");
            t.setDaemon(true);
            workerThread.set(t);
            return t;
          });
  private final Map<String, ScheduledFuture<?>> pending = new HashMap<>();

  void execute(final Runnable task) {
    try {
      executor.execute(() -> runLogged(task));
    } catch (final RejectedExecutionException ignored) {
      LOG.fine(() -> "[worker] execute rejected — executor shut down");
    }
  }

  <T> CompletableFuture<T> submit(final Callable<T> task) {
    final var future = new CompletableFuture<T>();
    try {
      executor.execute(
          () -> {
            try {
              future.complete(task.call());
            } catch (final Throwable t) {
              future.completeExceptionally(t);
              IOUtil.rethrowIfError(t);
            }
          });
    } catch (final RejectedExecutionException ignored) {
      future.cancel(false);
    }
    return future;
  }

  void schedule(final String key, final long delayMs, final Runnable task) {
    if (isWorkerThread()) {
      doSchedule(key, delayMs, task);
    } else {
      execute(() -> doSchedule(key, delayMs, task));
    }
  }

  void scheduleAtFixedRate(final long intervalMs, final Runnable task) {
    executor.scheduleAtFixedRate(
        () -> runLogged(task), intervalMs, intervalMs, TimeUnit.MILLISECONDS);
  }

  void cancel(final String key) {
    if (isWorkerThread()) {
      doCancel(key, false);
    } else {
      execute(() -> doCancel(key, false));
    }
  }

  void close() {
    executor.shutdownNow();
  }

  private boolean isWorkerThread() {
    return Thread.currentThread() == workerThread.get();
  }

  private void doSchedule(final String key, final long delayMs, final Runnable task) {
    doCancel(key, true);
    final ScheduledFuture<?> future =
        executor.schedule(
            () -> {
              pending.remove(key);
              runLogged(task);
            },
            delayMs,
            TimeUnit.MILLISECONDS);
    pending.put(key, future);
  }

  private void doCancel(final String key, final boolean mayInterrupt) {
    final ScheduledFuture<?> previous = pending.remove(key);
    if (previous != null) {
      previous.cancel(mayInterrupt);
      LOG.fine(() -> "[worker] cancelled %s".formatted(key));
    }
  }

  private void runLogged(final Runnable task) {
    try {
      task.run();
    } catch (final Throwable t) {
      LOG.log(Level.SEVERE, t, () -> "[worker] task failed");
      IOUtil.rethrowIfError(t);
    }
  }
}
