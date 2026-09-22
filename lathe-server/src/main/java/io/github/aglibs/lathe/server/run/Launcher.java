package io.github.aglibs.lathe.server.run;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class Launcher {

  private static final Logger LOG = Logger.getLogger(Launcher.class.getName());

  private Launcher() {}

  public static LaunchSession launch(
      final List<String> argv,
      final String configLabel,
      final Path resultsSink,
      final Consumer<TranscriptLine> onLine,
      final Consumer<TestResult> onResult,
      final Map<String, String> env,
      final Path cwd)
      throws IOException {
    LOG.fine(() -> "[launch] argv=%s".formatted(argv));
    // The config, then the command on its own line (copy-pasteable), before any process output.
    onLine.accept(new TranscriptLine(TranscriptLine.Stream.COMMAND, "config: " + configLabel));
    onLine.accept(new TranscriptLine(TranscriptLine.Stream.COMMAND, String.join(" ", argv)));
    final var processBuilder = new ProcessBuilder(argv);
    processBuilder.environment().putAll(env);
    if (cwd != null) {
      processBuilder.directory(cwd.toFile());
    }

    final Process process = processBuilder.start();
    return new LaunchSession(process, resultsSink, onLine, onResult);
  }
}
