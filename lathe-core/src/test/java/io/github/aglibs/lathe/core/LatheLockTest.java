package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LatheLockTest {

  @TempDir private Path moduleDir;

  @Test
  void acquire_missingModuleDir_createsDirAndLockFile() throws IOException {
    final Path nested = moduleDir.resolve("nested");

    LatheLock.acquire(nested);

    assertThat(nested.resolve(LatheLayout.LOCK_FILE)).exists();
  }

  @Test
  void release_existingLockFile_deletesIt() throws IOException {
    LatheLock.acquire(moduleDir);

    LatheLock.release(moduleDir);

    assertThat(moduleDir.resolve(LatheLayout.LOCK_FILE)).doesNotExist();
  }

  @Test
  void release_missingLockFile_noop() throws IOException {
    LatheLock.release(moduleDir);

    assertThat(moduleDir.resolve(LatheLayout.LOCK_FILE)).doesNotExist();
  }

  @Test
  void acquireThenAwaitAndRead_lockHeld_waitsUntilReleased()
      throws IOException, InterruptedException {
    LatheLock.acquire(moduleDir);

    final var releaser =
        new Thread(
            () -> {
              try {
                Thread.sleep(200);
                LatheLock.release(moduleDir);
              } catch (final IOException | InterruptedException e) {
                throw new RuntimeException(e);
              }
            });
    releaser.start();

    final var waited = Stopwatch.start();
    LatheLock.awaitAndRead(moduleDir, () -> "value");
    releaser.join();

    assertThat(waited.elapsedMs()).isGreaterThanOrEqualTo(200);
  }

  @Test
  void awaitAndRead_noLockFile_readsImmediately() throws IOException {
    final String result = LatheLock.awaitAndRead(moduleDir, () -> "value");

    assertThat(result).isEqualTo("value");
  }

  @Test
  void awaitAndRead_staleLockFile_readsImmediately() throws IOException {
    final Path lockFile = moduleDir.resolve(LatheLayout.LOCK_FILE);
    Files.writeString(lockFile, "");
    Files.setLastModifiedTime(lockFile, FileTime.from(Instant.now().minusSeconds(180)));

    final String result = LatheLock.awaitAndRead(moduleDir, () -> "value");

    assertThat(result).isEqualTo("value");
  }
}
