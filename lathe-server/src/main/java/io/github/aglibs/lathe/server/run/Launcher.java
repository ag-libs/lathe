package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.launch.LaunchPlan;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class Launcher {

  private static final Logger LOG = Logger.getLogger(Launcher.class.getName());

  private Launcher() {}

  public static LaunchSession launch(
      final TestLaunchData data,
      final Path workspaceRoot,
      final List<Path> runnerClasspath,
      final List<TestSelection> selections,
      final Consumer<TranscriptLine> onLine,
      final Consumer<TestResult> onResult)
      throws IOException {
    final Path resultsSink = Files.createTempFile("lathe-results-", ".ndjson");
    final List<String> argv =
        LaunchPlan.forTest(data, workspaceRoot, runnerClasspath, selections, resultsSink);
    LOG.fine(() -> "[launch] argv=%s".formatted(argv));
    // Surface the launch command as the run's first output line, before any process output, so the
    // client can show what ran the tests. COMMAND-tagged so the client renders it distinctly.
    onLine.accept(new TranscriptLine(TranscriptLine.Stream.COMMAND, String.join(" ", argv)));
    final Process process = new ProcessBuilder(argv).start();
    return new LaunchSession(process, resultsSink, onLine, onResult);
  }
}
