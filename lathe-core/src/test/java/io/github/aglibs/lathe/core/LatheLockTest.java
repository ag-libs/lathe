package io.github.aglibs.lathe.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class LatheLockTest {

  private static final int PROCESS_TIMEOUT_SECONDS = 5;

  @TempDir private Path moduleDir;

  @Test
  void tryExclusive_heldOrReleased_emptyUntilTheHolderCloses() throws IOException {
    final Path lockFile = LatheLayout.writeLockFile(moduleDir.resolve("nested"));

    final Closeable held = LatheLock.tryExclusive(lockFile).orElseThrow();
    assertThat(LatheLock.tryExclusive(lockFile)).isEmpty();
    held.close();

    final Optional<Closeable> again = LatheLock.tryExclusive(lockFile);
    assertThat(again).isPresent();
    again.get().close();
  }

  // A build that finds another holding the cache lock says so once, waits, and runs its work
  // under the lock once the other lets go; a free lock runs the work at once, saying nothing.
  @Test
  void exclusive_heldByAnother_waitsOnceThenRunsUnderTheLock_freeRunsAtOnce() throws IOException {
    final Path lockFile = LatheLayout.cacheLockFile(moduleDir.resolve("cache/jdks/jdk-21"));
    final Closeable held = LatheLock.tryExclusive(lockFile).orElseThrow();
    final var waits = new AtomicInteger();
    final var freeWaits = new AtomicInteger();

    // The other build lets go as soon as this one starts waiting.
    final boolean ranLocked =
        LatheLock.exclusive(
            lockFile,
            () -> {
              waits.incrementAndGet();
              IOUtil.unchecked(held::close);
            },
            () -> LatheLock.tryExclusive(lockFile).isEmpty());
    final String free = LatheLock.exclusive(lockFile, freeWaits::incrementAndGet, () -> "free");

    assertThat(ranLocked).isTrue();
    assertThat(waits).hasValue(1);
    assertThat(free).isEqualTo("free");
    assertThat(freeWaits).hasValue(0);
  }

  @Test
  void acquire_missingParentDir_createsDirAndLockFile() throws IOException {
    final Path lockFile = LatheLayout.moduleLockFile(moduleDir.resolve("nested"));

    LatheLock.acquire(lockFile);

    assertThat(lockFile).exists();
  }

  @Test
  void release_existingOrMissingLockFile_leavesNoLockFile() throws IOException {
    final Path lockFile = moduleLock();
    LatheLock.release(lockFile);
    LatheLock.acquire(lockFile);

    LatheLock.release(lockFile);

    assertThat(lockFile).doesNotExist();
  }

  @Test
  void releaseModuleLock_rootModuleUnderHeldReactorLock_reactorLockStillHeld() throws IOException {
    // The root module's module dir is .lathe/ itself, the reactor lock's dir.
    final Path reactorLock = LatheLayout.reactorLockFile(moduleDir);
    LatheLock.acquire(reactorLock);
    LatheLock.acquire(moduleLock());

    LatheLock.release(moduleLock());

    assertThat(LatheLock.isBuilding(reactorLock)).isTrue();
    assertThat(LatheLock.isBuilding(moduleLock())).isFalse();
  }

  @Test
  void acquireThenAwaitAndRead_lockHeld_waitsUntilReleased()
      throws IOException, InterruptedException {
    LatheLock.acquire(moduleLock());

    final var releaser =
        new Thread(
            () -> {
              try {
                Thread.sleep(200);
                LatheLock.release(moduleLock());
              } catch (final IOException | InterruptedException e) {
                throw new RuntimeException(e);
              }
            });
    releaser.start();

    final var waited = Stopwatch.start();
    LatheLock.awaitAndRead(moduleLock(), () -> "value");
    releaser.join();

    assertThat(waited.elapsedMs()).isGreaterThanOrEqualTo(200);
  }

  @Test
  void awaitAndRead_noOrStaleLockFile_readsImmediately() throws IOException {
    final String unlocked = LatheLock.awaitAndRead(moduleLock(), () -> "unlocked");
    writeStaleLock();

    final String stale = LatheLock.awaitAndRead(moduleLock(), () -> "stale");

    assertThat(unlocked).isEqualTo("unlocked");
    assertThat(stale).isEqualTo("stale");
  }

  @Test
  void isBuilding_freshLockFile_true() throws IOException {
    LatheLock.acquire(moduleLock());

    assertThat(LatheLock.isBuilding(moduleLock())).isTrue();
  }

  @Test
  void isBuilding_noOrStaleLockFile_false() throws IOException {
    final boolean noLock = LatheLock.isBuilding(moduleLock());
    writeStaleLock();

    assertThat(noLock).isFalse();
    assertThat(LatheLock.isBuilding(moduleLock())).isFalse();
  }

  @Test
  void acquire_currentOwner_recordsPidAndStartTime() throws IOException {
    LatheLock.acquire(moduleLock());

    final ProcessHandle owner = ProcessHandle.current();
    assertThat(Files.readAllLines(moduleLock(), StandardCharsets.UTF_8))
        .containsExactly(
            Long.toString(owner.pid()), owner.info().startInstant().orElseThrow().toString());
  }

  @Test
  @Timeout(PROCESS_TIMEOUT_SECONDS)
  void isBuilding_terminatedOwner_ignoresFreshReactorAndModuleLocks() throws Exception {
    final var java =
        Path.of(System.getProperty(LatheLayout.JAVA_HOME_PROPERTY), LatheLayout.BIN_DIR, "java")
            .toString();
    final Process owner =
        new ProcessBuilder(java, "-version")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try {
      assertThat(owner.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      // The process has exited, so its recorded start time cannot keep the marker held.
      final String identity = "%d\n%s\n".formatted(owner.pid(), Instant.EPOCH);
      for (final Path lock : List.of(LatheLayout.reactorLockFile(moduleDir), moduleLock())) {
        Files.writeString(lock, identity, StandardCharsets.UTF_8);

        assertThat(LatheLock.isBuilding(lock)).isFalse();
        assertThat(LatheLock.awaitAndRead(lock, () -> "read")).isEqualTo("read");
        assertThat(lock).exists();
      }
    } finally {
      if (owner.isAlive()) {
        owner.destroyForcibly();
        assertThat(owner.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void isBuilding_reusedPid_ignoresFreshLock() throws IOException {
    final ProcessHandle owner = ProcessHandle.current();
    final Instant differentStart = owner.info().startInstant().orElseThrow().minusSeconds(1);
    Files.writeString(
        moduleLock(), "%d\n%s\n".formatted(owner.pid(), differentStart), StandardCharsets.UTF_8);

    assertThat(LatheLock.isBuilding(moduleLock())).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "123",
        "invalid\n2026-01-01T00:00:00Z\n",
        "0\n2026-01-01T00:00:00Z\n",
        "-1\n2026-01-01T00:00:00Z\n",
        "123\ninvalid\n",
        "123\n2026-01-01T00:00:00Z\nextra\n"
      })
  void isBuilding_incompleteOrMalformedOwner_usesMtimeExpiry(final String identity)
      throws IOException {
    Files.writeString(moduleLock(), identity, StandardCharsets.UTF_8);

    assertThat(LatheLock.isBuilding(moduleLock())).isTrue();
    Files.setLastModifiedTime(moduleLock(), FileTime.from(Instant.now().minusSeconds(180)));
    assertThat(LatheLock.isBuilding(moduleLock())).isFalse();
  }

  @Test
  void isBuilding_invalidUtf8Owner_usesMtimeExpiry() throws IOException {
    Files.write(moduleLock(), new byte[] {(byte) 0xff});

    assertThat(LatheLock.isBuilding(moduleLock())).isTrue();
    Files.setLastModifiedTime(moduleLock(), FileTime.from(Instant.now().minusSeconds(180)));
    assertThat(LatheLock.isBuilding(moduleLock())).isFalse();
  }

  @Test
  void isBuilding_staleLiveOwner_usesMtimeExpiry() throws IOException {
    LatheLock.acquire(moduleLock());
    Files.setLastModifiedTime(moduleLock(), FileTime.from(Instant.now().minusSeconds(180)));

    assertThat(LatheLock.isBuilding(moduleLock())).isFalse();
  }

  private Path moduleLock() {
    return LatheLayout.moduleLockFile(moduleDir);
  }

  private void writeStaleLock() throws IOException {
    Files.writeString(moduleLock(), "");
    Files.setLastModifiedTime(moduleLock(), FileTime.from(Instant.now().minusSeconds(180)));
  }
}
