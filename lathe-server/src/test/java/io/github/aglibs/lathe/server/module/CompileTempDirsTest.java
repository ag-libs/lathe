package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.TestProcess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class CompileTempDirsTest {

  private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");
  // Touched before the sweep's day: its owner, if gone, left it.
  private static final FileTime OLD = FileTime.from(NOW.minus(Duration.ofDays(2)));

  @TempDir Path tmp;

  // A server killed or crashed never closes its compilers: the next server to start deletes the
  // dirs of a process no longer running, untouched for a day, and leaves a live server's, a recent
  // one (its server may run in another PID namespace), and anything else.
  @Test
  void deleteOrphaned_deadOwner_deleted_liveOwnerAndOtherNamesKept() throws Exception {
    final Process exited = new ProcessBuilder(TestProcess.java().toString(), "-version").start();
    exited.waitFor();
    final Path orphaned = compileDir(exited.pid());
    Files.writeString(orphaned.resolve("A.java"), "class A {}");
    Files.setLastModifiedTime(orphaned, OLD);
    final Path recent =
        Files.createDirectories(
            tmp.resolve(
                "%s%d-recent".formatted(LatheLayout.COMPILE_TEMP_DIR_PREFIX, exited.pid())));
    Files.setLastModifiedTime(recent, FileTime.from(NOW.minus(Duration.ofHours(1))));
    final Path live = compileDir(ProcessHandle.current().pid());
    final Path unowned =
        Files.createDirectories(
            tmp.resolve("%sx-1".formatted(LatheLayout.COMPILE_TEMP_DIR_PREFIX)));
    final Path other = Files.createDirectories(tmp.resolve("lathe-launch-1"));

    CompileTempDirs.deleteOrphaned(tmp, NOW);

    assertThat(orphaned).doesNotExist();
    assertThat(recent).as("a server in another PID namespace may still use it").isDirectory();
    assertThat(live).isDirectory();
    assertThat(unowned).isDirectory();
    assertThat(other).isDirectory();
  }

  // A shared temp dir holds other users' entries this one cannot read (CI has one): the sweep
  // reads only its own level, so it still deletes the orphaned dir.
  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "an unreadable directory needs POSIX modes")
  void deleteOrphaned_unreadableEntryBeside_stillDeletesTheOrphan() throws Exception {
    assumeFalse("root".equals(System.getProperty("user.name")), "root reads any directory");
    final Process exited = new ProcessBuilder(TestProcess.java().toString(), "-version").start();
    exited.waitFor();
    final Path orphaned = compileDir(exited.pid());
    Files.setLastModifiedTime(orphaned, OLD);
    final Path unreadable = Files.createDirectories(tmp.resolve("private/inner"));
    Files.setPosixFilePermissions(
        unreadable.getParent(), PosixFilePermissions.fromString("---------"));
    try {
      CompileTempDirs.deleteOrphaned(tmp, NOW);
    } finally {
      Files.setPosixFilePermissions(
          unreadable.getParent(), PosixFilePermissions.fromString("rwx------"));
    }

    assertThat(orphaned).doesNotExist();
  }

  // A link named as an orphaned compile dir, or one inside such a dir, may lead out of the temp
  // dir:
  // the sweep deletes neither the files behind it nor, for a link in the temp dir itself, the link.
  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "creating a symbolic link needs a privilege")
  void deleteOrphaned_linksOutOfTheTempDir_leaveTheirTargetsAlone() throws Exception {
    final Path outside = Files.createDirectories(tmp.resolve("outside"));
    final Path kept = Files.writeString(outside.resolve("kept.txt"), "kept");
    // As old as an orphan: only its being a link keeps the sweep away.
    Files.setLastModifiedTime(outside, OLD);
    final Path temp = Files.createDirectories(tmp.resolve("temp"));
    final long dead = deadPid();
    final Path linkedDir =
        Files.createSymbolicLink(
            temp.resolve("%s%d-link".formatted(LatheLayout.COMPILE_TEMP_DIR_PREFIX, dead)),
            outside);
    final Path orphaned =
        Files.createDirectories(
            temp.resolve("%s%d-real".formatted(LatheLayout.COMPILE_TEMP_DIR_PREFIX, dead)));
    Files.createSymbolicLink(orphaned.resolve("escape"), outside);
    Files.setLastModifiedTime(orphaned, OLD);

    CompileTempDirs.deleteOrphaned(temp, NOW);

    assertThat(kept).hasContent("kept");
    assertThat(linkedDir).isSymbolicLink();
    assertThat(orphaned).doesNotExist();
  }

  private static long deadPid() throws Exception {
    final Process exited = new ProcessBuilder(TestProcess.java().toString(), "-version").start();
    exited.waitFor();
    return exited.pid();
  }

  private Path compileDir(final long pid) throws Exception {
    return Files.createDirectories(
        tmp.resolve("%s%d-123".formatted(LatheLayout.COMPILE_TEMP_DIR_PREFIX, pid)));
  }
}
