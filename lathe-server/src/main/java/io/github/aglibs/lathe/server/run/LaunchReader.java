package io.github.aglibs.lathe.server.run;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.LatheLock;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.core.schema.MainLaunchData;
import io.github.aglibs.lathe.core.schema.TestFilterData;
import io.github.aglibs.lathe.core.schema.TestLaunchData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LaunchReader {

  private static final Logger LOG = Logger.getLogger(LaunchReader.class.getName());

  private final Path workspaceRoot;

  public LaunchReader(final Path workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
  }

  // The test launch of one of the module's trees: its test tree's, or a Gradle source set's. One
  // whose content this Lathe cannot take (captured by an older one, or damaged) is no capture: the
  // run asks for a new one, as for a missing one, which mends it. A file it cannot read at all
  // (permissions, an I/O error) still fails the run, as a new capture would not mend that.
  public Optional<TestLaunchData> test(final String moduleRel, final String sourceTree)
      throws IOException {
    final Path moduleDir = moduleDir(moduleRel);
    final Path launchFile = moduleDir.resolve(LatheLayout.testLaunchFile(sourceTree));
    if (!Files.exists(launchFile)) {
      return Optional.empty();
    }

    return LatheLock.awaitAndRead(
        LatheLayout.moduleLockFile(moduleDir),
        () -> readableCapture(launchFile, "run", Level.WARNING));
  }

  public Optional<MainLaunchData> main(final String moduleRel) throws IOException {
    return read(moduleRel, LatheLayout.MAIN_LAUNCH_FILE, MainLaunchData.class);
  }

  // Whether a build has captured the module's test launch, and this Lathe can take it; without
  // waiting for a build that holds the module, as the answer is a summary. A build holding it may
  // be writing the file, so the file counts as it stands, as does one that cannot be read at all.
  public boolean testCaptured(final String moduleRel) {
    final Path moduleDir = moduleDir(moduleRel);
    final Path launchFile = moduleDir.resolve(LatheLayout.TEST_LAUNCH_FILE);
    if (!Files.exists(launchFile)) {
      return false;
    }

    if (LatheLock.isBuilding(LatheLayout.moduleLockFile(moduleDir))) {
      return true;
    }

    try {
      return readableCapture(launchFile, "modules", Level.FINE).isPresent();
    } catch (final IOException e) {
      return true;
    }
  }

  public Optional<TestFilterData> testFilter(final String moduleRel) throws IOException {
    return read(moduleRel, LatheLayout.TEST_FILTER_FILE, TestFilterData.class);
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

  // A run warns of the capture it cannot take; the summary, which reads every module's, says it
  // only at FINE.
  private static Optional<TestLaunchData> readableCapture(
      final Path launchFile, final String operation, final Level level) throws IOException {
    final var t = Stopwatch.start();
    try {
      return Optional.of(Json.read(launchFile, TestLaunchData.class));
    } catch (final IOException e) {
      if (!Json.isDamaged(e)) {
        throw e;
      }

      final String reason = e.getCause() != null ? e.getCause().getMessage() : "";
      LOG.log(
          level,
          () ->
              "[%s] %s (%s) treated as not captured %dms"
                  .formatted(operation, e.getMessage(), reason, t.elapsedMs()));
      return Optional.empty();
    }
  }

  private Path moduleDir(final String moduleRel) {
    return workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(moduleRel);
  }
}
