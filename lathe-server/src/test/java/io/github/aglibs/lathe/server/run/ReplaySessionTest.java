package io.github.aglibs.lathe.server.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

final class ReplaySessionTest {

  @Test
  void awaitExit_processExitsZero_returnsZero() throws IOException, InterruptedException {
    final var session = new ReplaySession(new ProcessBuilder("true").start());

    assertThat(session.awaitExit()).isZero();
  }

  @Test
  void awaitExit_processExitsNonZero_returnsExitCode() throws IOException, InterruptedException {
    final var session = new ReplaySession(new ProcessBuilder("false").start());

    assertThat(session.awaitExit()).isEqualTo(1);
  }

  @Test
  void cancel_runningProcess_terminatesIt() throws IOException, InterruptedException {
    final var session = new ReplaySession(new ProcessBuilder("sleep", "30").start());

    session.cancel();

    assertThat(session.awaitExit()).isNotZero();
  }

  @Test
  void pid_startedProcess_returnsPositivePid() throws IOException, InterruptedException {
    final var session = new ReplaySession(new ProcessBuilder("sleep", "0").start());

    assertThat(session.pid()).isPositive();

    session.awaitExit();
  }
}
