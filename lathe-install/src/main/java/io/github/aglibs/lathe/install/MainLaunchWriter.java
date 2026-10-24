package io.github.aglibs.lathe.install;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.LaunchMode;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// .lathe/<module>/main-launch.json, the template the server runs a module's main classes from. The
// build tool derives the paths; the run's working directory is the module's own directory.
public final class MainLaunchWriter {

  private static final Logger LOG = LoggerFactory.getLogger(MainLaunchWriter.class);

  private MainLaunchWriter() {}

  public static void writeClasspath(
      final Path workspaceRoot,
      final String moduleRel,
      final String javaHome,
      final List<String> classpath) {
    write(workspaceRoot, moduleRel, LaunchMode.CLASSPATH, javaHome, null, List.of(), classpath);
  }

  public static void writeModule(
      final Path workspaceRoot,
      final String moduleRel,
      final String javaHome,
      final String mainModule,
      final List<String> modulePath,
      final List<String> classpath) {
    write(workspaceRoot, moduleRel, LaunchMode.MODULE, javaHome, mainModule, modulePath, classpath);
  }

  private static void write(
      final Path workspaceRoot,
      final String moduleRel,
      final LaunchMode mode,
      final String javaHome,
      final String mainModule,
      final List<String> modulePath,
      final List<String> classpath) {
    final var data =
        new MainLaunchData(
            mode,
            javaHome,
            mainModule,
            modulePath,
            classpath,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            moduleRel);
    final Path moduleDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(moduleRel);
    final Path launchFile = moduleDir.resolve(LatheLayout.MAIN_LAUNCH_FILE);
    try {
      if (FileUtil.writeIfChanged(moduleDir, launchFile, Json.toJson(data), false)) {
        LOG.debug("[sync] main-launch %s written".formatted(moduleRel));
      }
    } catch (final IOException e) {
      throw new SyncException(
          "lathe:sync failed to write main-launch template for %s".formatted(moduleRel), e);
    }
  }
}
