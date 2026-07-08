package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class LaunchTemplateReader {

  private final Path workspaceRoot;

  public LaunchTemplateReader(final Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
  }

  public Optional<TestLaunchData> read(final String moduleRel) throws IOException {
    final Path moduleDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(moduleRel);
    final Path launchFile = moduleDir.resolve(LatheLayout.TEST_LAUNCH_FILE);
    if (!Files.exists(launchFile)) {
      return Optional.empty();
    }

    return Optional.of(
        LatheLock.awaitAndRead(moduleDir, () -> Json.read(launchFile, TestLaunchData.class)));
  }
}
