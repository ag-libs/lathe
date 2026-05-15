package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DebouncerTest {

  private static final String URI = "file:///Foo.java";
  private static final long DELAY_MS = 50;

  private final Debouncer debouncer = new Debouncer();

  @AfterEach
  void close() {
    debouncer.close();
  }

  @Test
  void schedule_singleTask_runsAfterDelay() throws InterruptedException {
    final var count = new AtomicInteger();
    debouncer.schedule(URI, DELAY_MS, count::incrementAndGet);
    Thread.sleep(DELAY_MS * 3);
    assertThat(count.get()).isEqualTo(1);
  }

  @Test
  void schedule_rapidRescheduling_runsOnlyOnce() throws InterruptedException {
    final var count = new AtomicInteger();
    for (int i = 0; i < 5; i++) {
      debouncer.schedule(URI, DELAY_MS, count::incrementAndGet);
      Thread.sleep(10);
    }
    Thread.sleep(DELAY_MS * 3);
    assertThat(count.get()).isEqualTo(1);
  }

  @Test
  void schedule_differentUris_runIndependently() throws InterruptedException {
    final var countA = new AtomicInteger();
    final var countB = new AtomicInteger();
    debouncer.schedule("file:///A.java", DELAY_MS, countA::incrementAndGet);
    debouncer.schedule("file:///B.java", DELAY_MS, countB::incrementAndGet);
    Thread.sleep(DELAY_MS * 3);
    assertThat(countA.get()).isEqualTo(1);
    assertThat(countB.get()).isEqualTo(1);
  }

  @Test
  void cancel_pendingTask_preventsExecution() throws InterruptedException {
    final var count = new AtomicInteger();
    debouncer.schedule(URI, DELAY_MS, count::incrementAndGet);
    debouncer.cancel(URI);
    Thread.sleep(DELAY_MS * 3);
    assertThat(count.get()).isEqualTo(0);
  }

  @Test
  void cancel_unknownUri_doesNotThrow() {
    debouncer.cancel("file:///nonexistent.java");
  }

  @Test
  void submit_runsTaskImmediately() throws InterruptedException {
    final var count = new AtomicInteger();
    debouncer.submit(count::incrementAndGet);
    Thread.sleep(DELAY_MS);
    assertThat(count.get()).isEqualTo(1);
  }
}
