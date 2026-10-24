package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class LaunchReader {

  private final Path workspaceRoot;

  public LaunchReader(final Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
  }

  public Optional<TestLaunchData> test(final String moduleRel) throws IOException {
    return read(moduleRel, LatheLayout.TEST_LAUNCH_FILE, TestLaunchData.class);
  }

  public Optional<MainLaunchData> main(final String moduleRel) throws IOException {
    return read(moduleRel, LatheLayout.MAIN_LAUNCH_FILE, MainLaunchData.class);
  }

  // Whether a build has captured the module's test launch, without reading it.
  public boolean testCaptured(final String moduleRel) {
    return Files.exists(moduleDir(moduleRel).resolve(LatheLayout.TEST_LAUNCH_FILE));
  }

  private <T> Optional<T> read(final String moduleRel, final String fileName, final Class<T> type)
      throws IOException {
    final Path moduleDir = moduleDir(moduleRel);
    final Path launchFile = moduleDir.resolve(fileName);
    if (!Files.exists(launchFile)) {
      return Optional.empty();
    }

    return Optional.of(
        LatheLock.awaitAndRead(
            LatheLayout.moduleLockFile(moduleDir), () -> Json.read(launchFile, type)));
  }

  private Path moduleDir(final String moduleRel) {
    return workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(moduleRel);
  }
}
