package io.github.aglibs.lathe.server;

import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

// Formats via a user command. Source/output go through temp files (no pipe to drain); stderr is
// inherited to server stderr, so it reaches lsp.log. Runs in workingDir (the workspace root), where
// project config and repo-relative commands resolve; null inherits the server's cwd.
record ExternalCommandFormatEngine(List<String> command, Duration timeout, Path workingDir)
    implements FormatEngine {

  private static final Logger LOG = Logger.getLogger(ExternalCommandFormatEngine.class.getName());

  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  ExternalCommandFormatEngine {
    ValidCheck.check()
        .notEmpty(command, "command")
        .assertTrue(timeout != null && timeout.isPositive(), "timeout must be positive")
        .validate();
    command = List.copyOf(command);
  }

  @Override
  public String format(final String source, final Path file)
      throws IOException, InterruptedException {
    final Path stdin = Files.createTempFile("lathe-format-in", ".java");
    final Path stdout = Files.createTempFile("lathe-format-out", ".java");
    try {
      Files.writeString(stdin, source, StandardCharsets.UTF_8);
      return run(source, stdin, stdout);
    } finally {
      deleteQuietly(stdin);
      deleteQuietly(stdout);
    }
  }

  private String run(final String source, final Path stdin, final Path stdout)
      throws IOException, InterruptedException {
    final var builder = new ProcessBuilder(command);
    if (workingDir != null) {
      builder.directory(workingDir.toFile());
    }

    builder.redirectInput(stdin.toFile());
    builder.redirectOutput(stdout.toFile());
    builder.redirectError(ProcessBuilder.Redirect.INHERIT);
    FormatProcess.run(builder, timeout, command);

    final String formatted = Files.readString(stdout, StandardCharsets.UTF_8);
    if (formatted.isEmpty() && !source.isEmpty()) {
      throw new IOException("external formatter produced no output: %s".formatted(command));
    }

    return formatted;
  }

  private static void deleteQuietly(final Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (final IOException e) {
      LOG.log(Level.FINE, e, () -> "[format] temp cleanup failed for %s".formatted(path));
    }
  }
}
