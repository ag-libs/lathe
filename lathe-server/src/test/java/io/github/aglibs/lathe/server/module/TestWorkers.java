package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.analysis.SourceAnalysisSession;

// Workers for tests outside this package, whose constructor is package-private.
public final class TestWorkers {

  private TestWorkers() {}

  // A worker over a session the test makes, delivering its results on the worker's own thread.
  public static CompilationWorker over(final SourceAnalysisSession session) {
    return new CompilationWorker("lathe-test-worker", () -> session, Runnable::run);
  }

  public static void close(final CompilationWorker worker) {
    worker.close();
  }
}
