package io.github.aglibs.lathe.maven.extension;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheLifecycleParticipantTest {

  private final LatheLifecycleParticipant participant = new LatheLifecycleParticipant();

  private static Path lockFile(final Path latheDir) {
    return latheDir.resolve(LatheLayout.LOCK_FILE);
  }

  @Test
  void reactorLock_existingLatheDir_writesLockThenReleasesOnSessionEnd(@TempDir final Path root)
      throws IOException {
    final var latheDir = Files.createDirectory(root.resolve(LatheLayout.LATHE_DIR));

    participant.startReactorLock(latheDir);
    assertThat(lockFile(latheDir)).exists();

    participant.stopReactorLock();
    assertThat(lockFile(latheDir)).doesNotExist();
  }

  @Test
  void reactorLock_missingLatheDir_writesNothingAndStopIsNoop(@TempDir final Path root) {
    final var latheDir = root.resolve(LatheLayout.LATHE_DIR);

    participant.startReactorLock(latheDir);
    assertThat(latheDir).doesNotExist();

    participant.stopReactorLock();
    assertThat(lockFile(latheDir)).doesNotExist();
  }
}
