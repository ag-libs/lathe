package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.TestProcess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class CompileTempDirsTest {

  @TempDir Path tmp;

  // A server killed or crashed never closes its compilers: the next server to start deletes the
  // dirs of a process no longer running, and leaves a live server's and anything else.
  @Test
  void deleteOrphaned_deadOwner_deleted_liveOwnerAndOtherNamesKept() throws Exception {
    final Process exited = new ProcessBuilder(TestProcess.java().toString(), "-version").start();
    exited.waitFor();
    final Path orphaned = compileDir(exited.pid());
    Files.writeString(orphaned.resolve("A.java"), "class A {}");
    final Path live = compileDir(ProcessHandle.current().pid());
    final Path unowned =
        Files.createDirectories(
            tmp.resolve("%sx-1".formatted(LatheLayout.COMPILE_TEMP_DIR_PREFIX)));
    final Path other = Files.createDirectories(tmp.resolve("lathe-launch-1"));

    CompileTempDirs.deleteOrphaned(tmp);

    assertThat(orphaned).doesNotExist();
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
    final Path unreadable = Files.createDirectories(tmp.resolve("private/inner"));
    Files.setPosixFilePermissions(
        unreadable.getParent(), PosixFilePermissions.fromString("---------"));
    try {
      CompileTempDirs.deleteOrphaned(tmp);
    } finally {
      Files.setPosixFilePermissions(
          unreadable.getParent(), PosixFilePermissions.fromString("rwx------"));
    }

    assertThat(orphaned).doesNotExist();
  }

  private Path compileDir(final long pid) throws Exception {
    return Files.createDirectories(
        tmp.resolve("%s%d-123".formatted(LatheLayout.COMPILE_TEMP_DIR_PREFIX, pid)));
  }
}
