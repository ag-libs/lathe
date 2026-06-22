package io.github.aglibs.lathe.server.module;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

final class CompilationAdmission {

  private static final int MAX_PERMITS = 16;

  private final Semaphore semaphore;

  CompilationAdmission(final int availableProcessors) {
    this.semaphore = new Semaphore(permitCount(availableProcessors), true);
  }

  static int permitCount(final int availableProcessors) {
    return Math.max(1, Math.min(MAX_PERMITS, availableProcessors));
  }

  <T> T run(final Supplier<T> operation) {
    semaphore.acquireUninterruptibly();
    try {
      return operation.get();
    } finally {
      semaphore.release();
    }
  }
}
