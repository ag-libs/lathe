package io.github.aglibs.lathe.server;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

// An event loop for tests of a loop-confined component: calls into it run on the loop, assertions
// stay on the test thread (the loop ends the process on an Error, so this loop never halts).
final class TestLoop implements AutoCloseable {

  final ServerEventLoop eventLoop = new ServerEventLoop(status -> {});

  <T> T call(final Callable<T> task) throws Exception {
    return eventLoop.submit(task).get(ServiceTestBase.TIMEOUT_S, TimeUnit.SECONDS);
  }

  void run(final Runnable task) throws Exception {
    call(
        () -> {
          task.run();
          return true;
        });
  }

  @Override
  public void close() {
    eventLoop.close(() -> null);
  }
}
