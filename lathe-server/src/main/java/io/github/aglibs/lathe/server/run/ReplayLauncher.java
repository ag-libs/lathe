package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.launch.ReplayTransform;
import io.github.aglibs.lathe.core.launch.TestSelection;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class ReplayLauncher {

  private ReplayLauncher() {}

  public static ReplaySession launch(
      final TestLaunchData data,
      final Path workspaceRoot,
      final Path runnerJar,
      final TestSelection selection)
      throws IOException {
    final List<String> argv = ReplayTransform.forTest(data, workspaceRoot, runnerJar, selection);
    final Process process =
        new ProcessBuilder(argv)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    return new ReplaySession(process);
  }
}
