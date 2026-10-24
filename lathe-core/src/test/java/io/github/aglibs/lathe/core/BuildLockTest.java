package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildLockTest {

  @TempDir private Path root;

  @Test
  void hold_existingLatheDir_writesTheLockThenCloseReleasesIt() throws IOException {
    final Path latheDir = Files.createDirectory(root.resolve(LatheLayout.LATHE_DIR));
    final Path lockFile = LatheLayout.reactorLockFile(latheDir);

    try (final BuildLock lock = BuildLock.hold(latheDir).orElseThrow()) {
      assertThat(lock.latheDir()).isEqualTo(latheDir);
      assertThat(LatheLock.isBuilding(lockFile)).isTrue();
    }

    assertThat(lockFile).doesNotExist();
  }

  // A first-ever build: no lock, and no .lathe/ created, which would flip the server's
  // "configured" detection.
  @Test
  void hold_missingLatheDir_holdsNothingAndCreatesNothing() {
    final Path latheDir = root.resolve(LatheLayout.LATHE_DIR);

    assertThat(BuildLock.hold(latheDir)).isEmpty();
    assertThat(latheDir).doesNotExist();
  }
}
