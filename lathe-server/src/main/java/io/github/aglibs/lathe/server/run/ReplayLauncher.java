package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.launch.ReplayTransform;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

public final class ReplayLauncher {

  private static final Logger LOG = Logger.getLogger(ReplayLauncher.class.getName());

  private ReplayLauncher() {}

  public static ReplaySession launch(
      final TestLaunchData data,
      final Path workspaceRoot,
      final List<Path> runnerClasspath,
      final TestSelection selection)
      throws IOException {
    final Path resultsSink = Files.createTempFile("lathe-results-", ".ndjson");
    final List<String> argv =
        ReplayTransform.forTest(data, workspaceRoot, runnerClasspath, selection, resultsSink);
    LOG.fine(() -> "[replay] argv=%s".formatted(argv));
    final Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
    return new ReplaySession(process, resultsSink);
  }
}
