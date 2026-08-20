package io.github.aglibs.lathe.server.run;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class Launcher {

  private static final Logger LOG = Logger.getLogger(Launcher.class.getName());

  private Launcher() {}

  public static LaunchSession launch(
      final List<String> argv,
      final Path resultsSink,
      final Consumer<TranscriptLine> onLine,
      final Consumer<TestResult> onResult)
      throws IOException {
    LOG.fine(() -> "[launch] argv=%s".formatted(argv));
    // Surface the launch command as the run's first output line, before any process output, so the
    // client can show what ran. COMMAND-tagged so the client renders it distinctly.
    onLine.accept(new TranscriptLine(TranscriptLine.Stream.COMMAND, String.join(" ", argv)));
    final Process process = new ProcessBuilder(argv).start();
    return new LaunchSession(process, resultsSink, onLine, onResult);
  }
}
