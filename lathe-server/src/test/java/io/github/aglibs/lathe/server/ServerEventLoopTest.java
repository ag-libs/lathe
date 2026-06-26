package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServerEventLoopTest {

  private static final String URI = "file:///Foo.java";
  private static final long DELAY_MS = 50;

  private final ServerEventLoop worker = new ServerEventLoop();

  @AfterEach
  void close() {
    worker.close();
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
    assertThat(latch.await(DELAY_MS * 3, TimeUnit.MILLISECONDS)).isTrue();
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
    assertThat(latch.await(DELAY_MS * 3, TimeUnit.MILLISECONDS)).isTrue();
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
    assertThat(latch.await(DELAY_MS * 3, TimeUnit.MILLISECONDS)).isTrue();
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
    assertThat(latch.await(DELAY_MS * 3, TimeUnit.MILLISECONDS)).isTrue();
    assertThat(count.get()).isEqualTo(1);
  }
}
