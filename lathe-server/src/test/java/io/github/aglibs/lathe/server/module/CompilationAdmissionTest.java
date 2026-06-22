package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CompilationAdmissionTest {

  @Test
  void permitCount_processorCounts_clampsBetweenOneAndSixteen() {
    assertThat(CompilationAdmission.permitCount(0)).isOne();
    assertThat(CompilationAdmission.permitCount(1)).isOne();
    assertThat(CompilationAdmission.permitCount(8)).isEqualTo(8);
    assertThat(CompilationAdmission.permitCount(16)).isEqualTo(16);
    assertThat(CompilationAdmission.permitCount(32)).isEqualTo(16);
  }

  @Test
  void run_concurrentWork_neverExceedsConfiguredLimit() {
    final var admission = new CompilationAdmission(2);
    final var active = new AtomicInteger();
    final var maximum = new AtomicInteger();
    final var entered = new CountDownLatch(2);
    final var release = new CountDownLatch(1);
    final var futures = new ArrayList<CompletableFuture<Void>>();

    try (final ExecutorService executor = Executors.newFixedThreadPool(6)) {
      for (int i = 0; i < 6; i++) {
        final CompletableFuture<Void> future =
            CompletableFuture.runAsync(
                () ->
                    admission.run(
                        () -> {
                          final int current = active.incrementAndGet();
                          maximum.accumulateAndGet(current, Math::max);
                          entered.countDown();
                          await(release);
                          active.decrementAndGet();
                          return null;
                        }),
                executor);
        futures.add(future);
      }

      await(entered);
      release.countDown();
      CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    assertThat(maximum).hasValue(2);
  }

  @Test
  void run_failedWork_releasesPermit() {
    final var admission = new CompilationAdmission(1);

    assertThatThrownBy(
            () ->
                admission.run(
                    () -> {
                      throw new IllegalStateException("expected");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(admission.run(() -> "available")).isEqualTo("available");
  }

  private static void await(final CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
