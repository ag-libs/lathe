package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.Platform;
import io.github.aglibs.lathe.install.SyncScript;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

// The sync script for a Gradle build: the Gradle that ran it, on the JDK it ran on, with the
// init scripts that registered Lathe from the command line, not required up front: Gradle stops on
// a missing one in its own words. Its -P/-D properties are not replayed: flags every build needs
// belong in gradle.properties.
final class GradleSyncScript {

  // The editor's sync with tests goes on past a failed task: Gradle starts no new task after a
  // failure, so a build that rejects skipped tests would capture only its first test task.
  private static final String CONTINUE_ARG = "--continue";

  private GradleSyncScript() {}

  static SyncScript of(final Path gradleHome, final Path javaHome, final List<String> initScripts) {
    return new SyncScript(
        runningTool(gradleHome, Platform.WINDOWS),
        javaHome,
        List.of(),
        initScripts.stream().flatMap(script -> Stream.of("--init-script", script)).toList(),
        List.of(LatheLayout.Gradle.SYNC_TASK),
        List.of(LatheLayout.Gradle.SYNC_TASK, LatheLayout.Gradle.CAPTURE_ONLY_ARG, CONTINUE_ARG),
        List.of("--" + LatheSyncTask.MODULE_OPTION, LatheFlags.SYNC_MODULE_TOKEN),
        List.of());
  }

  // The launcher of the Gradle distribution that ran the build (gradle.gradleHomeDir), not the
  // wrapper: the same version, with no download step.
  private static Path runningTool(final Path gradleHome, final boolean windows) {
    return gradleHome.resolve(LatheLayout.BIN_DIR).resolve(windows ? "gradle.bat" : "gradle");
  }
}
