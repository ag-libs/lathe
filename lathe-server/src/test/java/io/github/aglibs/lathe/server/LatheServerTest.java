package io.github.aglibs.lathe.server;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LatheServerTest {

  @Test
  void run_endOfInput_returnsWithinBound() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> LatheServer.run(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
  }
}
