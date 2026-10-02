package io.github.aglibs.lathe.server;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

// Shared process execution for the command format engines: start, wait with a timeout, and verify a
// zero exit. The caller configures redirects on the builder (stdin/stdout pipes vs in-place file).
final class FormatProcess {

  private FormatProcess() {}

  static void run(final ProcessBuilder builder, final Duration timeout, final List<String> command)
      throws IOException, InterruptedException {
    final Process process;
    try {
      process = builder.start();
    } catch (final IOException e) {
      throw new IOException("formatter failed to start: %s".formatted(command), e);
    }

    if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      process.destroyForcibly();
      throw new IOException(
          "formatter timed out after %dms: %s".formatted(timeout.toMillis(), command));
    }

    final int exit = process.exitValue();
    if (exit != 0) {
      throw new IOException("formatter exited %d: %s".formatted(exit, command));
    }
  }
}
