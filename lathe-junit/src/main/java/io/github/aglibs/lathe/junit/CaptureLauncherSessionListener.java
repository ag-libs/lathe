package io.github.aglibs.lathe.junit;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheWorkspace;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Optional;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

public final class CaptureLauncherSessionListener implements LauncherSessionListener {

  @Override
  public void launcherSessionOpened(final LauncherSession session) {
    try {
      capture();
    } catch (final Throwable ignored) {
      // Capture must never change the user's test result.
    }
  }

  private static void capture() throws Exception {
    final var workingDir = Path.of("").toAbsolutePath().normalize();
    final Optional<Path> workspaceRoot = LatheWorkspace.findRoot(workingDir);
    if (workspaceRoot.isEmpty()) {
      return;
    }

    final Path root = workspaceRoot.get();
    final Path moduleRel = root.relativize(workingDir);
    final Path moduleDir = root.resolve(LatheLayout.LATHE_DIR).resolve(moduleRel);
    final TestLaunchData data =
        LaunchCapture.toLaunchData(
            System.getProperty("java.home"),
            System.getProperty("java.class.path", ""),
            ManagementFactory.getRuntimeMXBean().getInputArguments(),
            ownJarLocation().orElse(null));
    Files.createDirectories(moduleDir);
    final Path target = moduleDir.resolve(LatheLayout.TEST_LAUNCH_FILE);
    FileUtil.writeAtomically(moduleDir, target, Json.toJson(data), false);
  }

  private static Optional<Path> ownJarLocation() throws URISyntaxException {
    final CodeSource codeSource =
        CaptureLauncherSessionListener.class.getProtectionDomain().getCodeSource();
    if (codeSource == null || codeSource.getLocation() == null) {
      return Optional.empty();
    }

    return Optional.of(Path.of(codeSource.getLocation().toURI()));
  }
}
