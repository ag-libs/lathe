package io.github.aglibs.lathe.maven.extension;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheFlags;
import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LatheLifecycleParticipantTest {

  private final LatheLifecycleParticipant participant = new LatheLifecycleParticipant();

  private static Path lockFile(final Path latheDir) {
    return latheDir.resolve(LatheLayout.LOCK_FILE);
  }

  private static MavenProject projectWithDisabled(final String value) {
    final var project = new MavenProject(new Model());
    if (value != null) {
      project.getProperties().setProperty(LatheFlags.DISABLED, value);
    }

    return project;
  }

  @Test
  void isPomOptOut_disabledAndNoLatheDir_optsOut(@TempDir final Path root) {
    final var latheDir = root.resolve(LatheLayout.LATHE_DIR);
    assertThat(LatheLifecycleParticipant.isPomOptOut(projectWithDisabled("true"), latheDir))
        .isTrue();
  }

  @Test
  void isPomOptOut_disabledButLatheDirExists_developerOptsBackIn(@TempDir final Path root)
      throws IOException {
    final var latheDir = Files.createDirectory(root.resolve(LatheLayout.LATHE_DIR));
    assertThat(LatheLifecycleParticipant.isPomOptOut(projectWithDisabled("true"), latheDir))
        .isFalse();
  }

  @Test
  void isPomOptOut_notDisabled_staysActive(@TempDir final Path root) {
    final var latheDir = root.resolve(LatheLayout.LATHE_DIR);
    assertThat(LatheLifecycleParticipant.isPomOptOut(projectWithDisabled(null), latheDir))
        .isFalse();
    assertThat(LatheLifecycleParticipant.isPomOptOut(projectWithDisabled("false"), latheDir))
        .isFalse();
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
