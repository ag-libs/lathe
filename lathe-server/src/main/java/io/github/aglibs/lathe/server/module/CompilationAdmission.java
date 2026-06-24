package io.github.aglibs.lathe.server.module;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

final class CompilationAdmission {

  private static final long CANCEL_POLL_MS = 50;

  private final Semaphore semaphore;

  CompilationAdmission(final int availableProcessors) {
    this.semaphore = new Semaphore(permitCount(availableProcessors), true);
  }

  static int permitCount(final int availableProcessors) {
    return Math.max(1, availableProcessors);
  }

  <T> T run(final Supplier<T> operation) {
    return run(() -> {}, operation);
  }

  <T> T run(final CancelChecker cancelChecker, final Supplier<T> operation) {
    acquire(cancelChecker);
    try {
      cancelChecker.checkCanceled();
      return operation.get();
    } finally {
      semaphore.release();
    }
  }

  private void acquire(final CancelChecker cancelChecker) {
    while (true) {
      cancelChecker.checkCanceled();
      try {
        if (semaphore.tryAcquire(CANCEL_POLL_MS, TimeUnit.MILLISECONDS)) {
          return;
        }
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new CancellationException("compilation admission interrupted");
      }
    }
  }
}
