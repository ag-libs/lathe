package io.github.aglibs.lathe.gradle;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.Platform;
import io.github.aglibs.lathe.install.SyncScript;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

// The sync script for a Gradle build: the Gradle that ran it, on the JDK it ran on, with the
// init scripts that registered Lathe from the command line. Its -P/-D properties are not replayed:
// flags every build needs belong in gradle.properties.
final class GradleSyncScript {

  private GradleSyncScript() {}

  static SyncScript of(final Path gradleHome, final Path javaHome, final List<String> initScripts) {
    return new SyncScript(
        LatheLayout.Gradle.runningTool(gradleHome, Platform.WINDOWS),
        javaHome,
        initScripts.stream().flatMap(script -> Stream.of("--init-script", script)).toList(),
        List.of(LatheProjectPlugin.SYNC_TASK),
        List.of(LatheProjectPlugin.SYNC_TASK, "-P%s=true".formatted(LatheFlags.CAPTURE_ONLY)),
        List.of("--" + LatheSyncTask.MODULE_OPTION, LatheFlags.SYNC_MODULE_TOKEN),
        List.of());
  }
}
