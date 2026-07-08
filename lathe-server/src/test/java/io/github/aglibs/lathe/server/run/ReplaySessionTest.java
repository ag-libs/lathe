package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

final class ReplaySessionTest {

  private static final long TIMEOUT_SECONDS = 5;

  @Test
  void onExit_processExitsZero_completesWithZero()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final var session = new ReplaySession(new ProcessBuilder("true").start());

    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isZero();
  }

  @Test
  void onExit_processExitsNonZero_completesWithExitCode()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final var session = new ReplaySession(new ProcessBuilder("false").start());

    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1);
  }

  @Test
  void cancel_runningProcess_completesOnExitNonZero()
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    final var session = new ReplaySession(new ProcessBuilder("sleep", "30").start());

    session.cancel();

    assertThat(session.onExit().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotZero();
  }

  @Test
  void pid_startedProcess_returnsPositivePid() throws IOException {
    final var session = new ReplaySession(new ProcessBuilder("sleep", "0").start());

    assertThat(session.pid()).isPositive();
  }
}
