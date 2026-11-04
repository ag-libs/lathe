package io.github.aglibs.lathe.server;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

final class ServerEventLoop {

  private static final Logger LOG = Logger.getLogger(ServerEventLoop.class.getName());

  private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
  private static final int FATAL_EXIT_STATUS = 1;

  private final AtomicReference<Thread> workerThread = new AtomicReference<>();
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(this::newWorkerThread);
  private final Map<String, ScheduledFuture<?>> pending = new HashMap<>();
  private final IntConsumer processTerminator;

  ServerEventLoop() {
    this(status -> Runtime.getRuntime().halt(status));
  }

  ServerEventLoop(final IntConsumer processTerminator) {
    this.processTerminator = processTerminator;
  }

  private Thread newWorkerThread(final Runnable r) {
    final var t = new Thread(r, "lathe-worker");
    t.setDaemon(true);
    workerThread.set(t);
    return t;
  }

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
              failFastOnError(t);
            }
          });
    } catch (final RejectedExecutionException ignored) {
      future.cancel(false);
    }
    return future;
  }

  // Runs a task that starts asynchronous work on the loop and completes with that work's result, on
  // whichever thread finishes it: continue with rejoin before touching loop state.
  <T> CompletableFuture<T> submitCompose(final Callable<CompletableFuture<T>> task) {
    return submit(task).thenCompose(future -> future);
  }

  // Completes on the loop once the future completes, so what follows may touch loop state. A
  // failure hops too: thenApplyAsync(fn, loop) would propagate it on the completing thread. It is
  // wrapped as thenApplyAsync wraps it, so handlers downstream see the same exception.
  <T> CompletableFuture<T> rejoin(final CompletableFuture<T> future) {
    final var rejoined = new CompletableFuture<T>();
    future.whenComplete(
        (value, failure) ->
            deliver(
                () -> {
                  if (failure == null) {
                    rejoined.complete(value);
                  } else {
                    rejoined.completeExceptionally(
                        failure instanceof CompletionException
                            ? failure
                            : new CompletionException(failure));
                  }
                }));
    return rejoined;
  }

  // Completes a result on the loop, so whatever the receiver chains runs there. Once the loop has
  // shut down it completes in place instead, so no caller waits for a result that cannot arrive.
  void deliver(final Runnable completion) {
    try {
      executor.execute(() -> runLogged(completion));
    } catch (final RejectedExecutionException ignored) {
      completion.run();
    }
  }

  // Loop-confined state reached from another thread fails here, where a test sees it, instead of
  // racing in production.
  void checkOnLoop() {
    if (!isWorkerThread()) {
      throw new IllegalStateException(
          "[worker] loop state touched on %s".formatted(Thread.currentThread().getName()));
    }
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

  // Runs `last` (closing what the loop's tasks use) as the loop's final task: it stops the executor
  // before returning, so nothing queued behind it ever runs against what it closed. Then awaits
  // termination, so no in-flight task can outlive close() and race the caller's teardown -- the
  // fixed-rate change scan walks and writes under the workspace root, so a test's @TempDir delete
  // would race a live scan.
  void close(final Runnable last) {
    try {
      submit(
              () -> {
                try {
                  last.run();
                } finally {
                  executor.shutdownNow();
                }

                return null;
              })
          .join();
    } finally {
      try {
        if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          LOG.warning(
              () -> "[worker] shutdown timed out after %ds".formatted(SHUTDOWN_TIMEOUT_SECONDS));
        }
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
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

  // Runs the task here, logging a failure that would otherwise vanish (in a future's callback,
  // which no one reads).
  void runLogged(final Runnable task) {
    try {
      task.run();
    } catch (final Throwable t) {
      if (!(t instanceof Error)) {
        LOG.log(Level.SEVERE, t, () -> "[worker] task failed");
      }

      failFastOnError(t);
    }
  }

  // An Error on the loop ends the server, as one in javac does: the executor running the loop's
  // tasks would otherwise catch it, and the server would go on with its state half-updated.
  private void failFastOnError(final Throwable t) {
    if (t instanceof final Error error) {
      LOG.log(Level.SEVERE, error, () -> "[worker] process fatal-error on the event loop");
      processTerminator.accept(FATAL_EXIT_STATUS);
    }
  }
}
