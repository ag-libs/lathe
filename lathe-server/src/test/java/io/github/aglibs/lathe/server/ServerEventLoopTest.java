package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServerEventLoopTest {

  private static final String URI = "file:///Foo.java";
  private static final String LOOP_THREAD = "lathe-worker";
  private static final long DELAY_MS = 50;
  // How long a test waits for a task that should run. The latch returns as soon as it does, so the
  // deadline costs nothing when the loop works; it only has to outlast a busy CI machine, where
  // DELAY_MS * 3 once ran out (the server's test classes run in parallel).
  private static final long AWAIT_MS = 5_000;

  private final ServerEventLoop worker = new ServerEventLoop();

  @AfterEach
  void close() {
    worker.close(() -> {});
  }

  @Test
  void schedule_singleTask_runsAfterDelay() throws InterruptedException {
    final var count = new AtomicInteger();
    final var latch = new CountDownLatch(1);
    worker.schedule(
        URI,
        DELAY_MS,
        () -> {
          count.incrementAndGet();
          latch.countDown();
        });
    assertThat(latch.await(AWAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
    assertThat(count.get()).isEqualTo(1);
  }

  @Test
  void schedule_rapidRescheduling_runsOnlyOnce() throws InterruptedException {
    final var count = new AtomicInteger();
    final var latch = new CountDownLatch(1);
    for (int i = 0; i < 5; i++) {
      worker.schedule(
          URI,
          DELAY_MS,
          () -> {
            count.incrementAndGet();
            latch.countDown();
          });
    }
    assertThat(latch.await(AWAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
    assertThat(count.get()).isEqualTo(1);
  }

  @Test
  void schedule_differentUris_runIndependently() throws InterruptedException {
    final var countA = new AtomicInteger();
    final var countB = new AtomicInteger();
    final var latch = new CountDownLatch(2);
    worker.schedule(
        "file:///A.java",
        DELAY_MS,
        () -> {
          countA.incrementAndGet();
          latch.countDown();
        });
    worker.schedule(
        "file:///B.java",
        DELAY_MS,
        () -> {
          countB.incrementAndGet();
          latch.countDown();
        });
    assertThat(latch.await(AWAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
    assertThat(countA.get()).isEqualTo(1);
    assertThat(countB.get()).isEqualTo(1);
  }

  @Test
  void cancel_pendingTask_preventsExecution() throws InterruptedException {
    final var count = new AtomicInteger();
    final var latch = new CountDownLatch(1);
    worker.schedule(
        URI,
        DELAY_MS,
        () -> {
          count.incrementAndGet();
          latch.countDown();
        });
    worker.cancel(URI);

    assertThat(latch.await(DELAY_MS * 2, TimeUnit.MILLISECONDS)).isFalse();
    assertThat(count.get()).isEqualTo(0);
  }

  @Test
  void cancel_unknownUri_doesNotThrow() {
    worker.cancel("file:///nonexistent.java");
  }

  @Test
  void execute_runsTaskImmediately() throws InterruptedException {
    final var count = new AtomicInteger();
    final var latch = new CountDownLatch(1);
    worker.execute(
        () -> {
          count.incrementAndGet();
          latch.countDown();
        });
    assertThat(latch.await(AWAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
    assertThat(count.get()).isEqualTo(1);
  }

  @Test
  void submitCompose_taskStartsAsyncWork_completesWithThatWorksResult() {
    final var work = new CompletableFuture<String>();
    final var ranOn = new AtomicReference<String>();

    final CompletableFuture<String> result =
        worker.submitCompose(
            () -> {
              ranOn.set(Thread.currentThread().getName());
              return work;
            });
    work.complete("done");

    assertThat(result).succeedsWithin(Duration.ofMillis(AWAIT_MS)).isEqualTo("done");
    assertThat(ranOn.get()).isEqualTo(LOOP_THREAD);
  }

  @Test
  void rejoin_futureCompletedOffLoop_continuesOnLoopForValueAndFailure() {
    final var value = new CompletableFuture<String>();
    final var failure = new CompletableFuture<String>();
    final var valueOn = new AtomicReference<String>();
    final var failureOn = new AtomicReference<Throwable>();
    final var failureThread = new AtomicReference<String>();

    final CompletableFuture<String> rejoinedValue =
        worker.rejoin(value).whenComplete((v, t) -> valueOn.set(Thread.currentThread().getName()));
    final CompletableFuture<String> rejoinedFailure =
        worker
            .rejoin(failure)
            .whenComplete(
                (v, t) -> {
                  failureThread.set(Thread.currentThread().getName());
                  failureOn.set(t);
                });
    value.complete("v");
    failure.completeExceptionally(new IllegalArgumentException("boom"));

    assertThat(rejoinedValue).succeedsWithin(Duration.ofMillis(AWAIT_MS)).isEqualTo("v");
    assertThat(rejoinedFailure).failsWithin(Duration.ofMillis(AWAIT_MS));
    assertThat(valueOn.get()).isEqualTo(LOOP_THREAD);
    assertThat(failureThread.get()).isEqualTo(LOOP_THREAD);
    assertThat(failureOn.get())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void checkOnLoop_onLoopPasses_offLoopThrows() {
    assertThat(
            worker.submit(
                () -> {
                  worker.checkOnLoop();
                  return true;
                }))
        .succeedsWithin(Duration.ofMillis(AWAIT_MS))
        .isEqualTo(true);
    assertThatThrownBy(worker::checkOnLoop)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(Thread.currentThread().getName());
  }

  @Test
  void close_runsLastOnTheLoop_droppingEverythingQueuedBehindIt() {
    final var loop = new ServerEventLoop();
    final var ranOn = new AtomicReference<String>();
    final var late = new AtomicInteger();

    loop.close(
        () -> {
          ranOn.set(Thread.currentThread().getName());
          // Queued while the final task runs, so behind it: neither may ever run.
          loop.execute(late::incrementAndGet);
          loop.schedule(URI, 0, late::incrementAndGet);
        });

    assertThat(ranOn.get()).isEqualTo(LOOP_THREAD);
    assertThat(late.get()).isZero();
    assertThat(loop.submit(() -> 1)).isCancelled();
  }

  @Test
  void errorOnTheLoop_executedOrSubmitted_endsTheProcess_aFailureDoesNot() {
    final var statuses = new LinkedBlockingQueue<Integer>();
    final var loop = new ServerEventLoop(statuses::add);
    try {
      loop.execute(
          () -> {
            throw new IllegalStateException("an ordinary failure");
          });
      loop.execute(
          () -> {
            throw new AssertionError("executed");
          });
      final CompletableFuture<Object> submitted =
          loop.submit(
              () -> {
                throw new AssertionError("submitted");
              });

      assertThat(submitted).failsWithin(Duration.ofMillis(AWAIT_MS));
      assertThat(loop.submit(() -> statuses.size()))
          .succeedsWithin(Duration.ofMillis(AWAIT_MS))
          .isEqualTo(2);
      assertThat(statuses).containsExactly(1, 1);
    } finally {
      loop.close(() -> {});
    }
  }
}
