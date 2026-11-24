package io.github.aglibs.lathe.server.module;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.server.files.ServerFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A compile's temp dir, named for the server's process. A server that exits without closing its
// compilers (killed, crashed) leaves its dirs behind, and the next server to start deletes them.
final class CompileTempDirs {

  private static final Logger LOG = Logger.getLogger(CompileTempDirs.class.getName());
  private static final Pattern OWNED =
      Pattern.compile(
          "%s(\\d{1,18})-.*".formatted(Pattern.quote(LatheLayout.COMPILE_TEMP_DIR_PREFIX)));
  // Another PID namespace sharing the temp dir (a container) runs servers whose pid this one does
  // not
  // see: a dir its server still compiles in changes on every compile, so one untouched this long is
  // gone too.
  private static final Duration ORPHAN_AGE = Duration.ofDays(1);
  // Once per process: the first compiler created sweeps.
  private static final AtomicBoolean SWEPT = new AtomicBoolean();

  private CompileTempDirs() {}

  static Path create() throws IOException {
    if (SWEPT.compareAndSet(false, true)) {
      deleteOrphaned(Path.of(System.getProperty("java.io.tmpdir")), Instant.now());
    }

    return Files.createTempDirectory(
        "%s%d-".formatted(LatheLayout.COMPILE_TEMP_DIR_PREFIX, ProcessHandle.current().pid()));
  }

  // The dirs of servers no longer running, untouched for a day. A live server's is left, and so is
  // one this user cannot delete (another user's server, on a shared temp dir), and a link: deleting
  // the files behind one could reach out of the temp dir. Only root's own level is read: below it
  // lie other users' entries this one cannot read, and the cleanup must never fail a compile.
  static void deleteOrphaned(final Path root, final Instant now) {
    final List<Path> dirs;
    try {
      dirs = ServerFiles.childDirectories(root);
    } catch (final IOException | UncheckedIOException e) {
      LOG.log(Level.WARNING, e, () -> "[cache] temp dir %s not listed".formatted(root));
      return;
    }

    for (final Path dir : dirs) {
      final Matcher owner = OWNED.matcher(dir.getFileName().toString());
      if (owner.matches()
          && !Files.isSymbolicLink(dir)
          && ProcessHandle.of(Long.parseLong(owner.group(1))).isEmpty()
          && untouchedSince(dir, now.minus(ORPHAN_AGE))) {
        try {
          FileUtil.deleteDir(dir);
          LOG.fine(() -> "[cache] orphaned temp dir %s deleted".formatted(dir));
        } catch (final IOException e) {
          // Another user's dir on a shared temp dir is expected here: its reason, not its trace.
          LOG.fine(() -> "[cache] orphaned temp dir %s not deleted: %s".formatted(dir, e));
        }
      }
    }
  }

  private static boolean untouchedSince(final Path dir, final Instant cutoff) {
    try {
      return Files.getLastModifiedTime(dir).toInstant().isBefore(cutoff);
    } catch (final IOException e) {
      return false;
    }
  }
}
